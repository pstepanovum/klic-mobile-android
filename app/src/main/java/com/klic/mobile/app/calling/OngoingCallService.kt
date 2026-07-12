package com.klic.mobile.app.calling

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.klic.mobile.app.KlicApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps a connected voice/video call alive while the app is backgrounded. As a foreground
 * service of type microphone|camera it grants background mic/camera capture (required on
 * Android 14+) and holds the process at foreground priority so the OS won't kill the call.
 * It does not own the LiveKit room (CallManager does, at application scope) — its job is
 * priority, the ongoing notification, and proximity blanking. Self-stops when the call ends.
 */
class OngoingCallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var proximityLock: PowerManager.WakeLock? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peerName = intent?.getStringExtra(EXTRA_PEER_NAME) ?: "Call"
        val isVideo = intent?.getBooleanExtra(EXTRA_IS_VIDEO, false) ?: false
        val manager = (application as KlicApplication).container.callManager

        // Must enter foreground immediately. Start with the type the call launched as; the
        // collector below upgrades to include camera if the user turns it on mid-call.
        enterForeground(peerName, cameraOn = isVideo)
        // Keep the CPU running while the screen is off. The mic|camera FGS keeps the PROCESS
        // alive, but on Doze-aggressive OEMs it doesn't stop the CPU being suspended once the
        // screen locks — which stalls the LiveKit connection and drops the call. A partial wake
        // lock held for the call's duration (released in onDestroy when the call ends) keeps
        // audio/video flowing with the screen off, so a call only ends on explicit hang-up or
        // remote end.
        acquireCpuLock()

        if (started) return START_NOT_STICKY
        started = true

        // Update FGS type + notification + proximity as mic/camera/connection change. The
        // call's lifecycle (start/stop) is driven explicitly by the ViewModel. Note that
        // isConnected stays true through "Reconnecting…" (CallManager's rejoin loop), so a
        // network blip never drops this service's foreground priority mid-call.
        scope.launch {
            combine(manager.cameraEnabled, manager.isConnected) { camera, connected ->
                camera to connected
            }.collect { (cameraOn, connected) ->
                enterForeground(peerName, cameraOn = cameraOn)
                // Blank the screen against the ear only for audio (camera off) calls.
                if (connected && !cameraOn) acquireProximity() else releaseProximity()
            }
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(peerName: String, cameraOn: Boolean) {
        val notification = CallNotifications.ongoingCallNotification(this, peerName, isVideo = cameraOn)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Media FGS types are only legal while their runtime permission is granted, else
            // startForeground throws (SecurityException on Android 14+) and takes the whole
            // call down with it. The call entry points request the mic before any call starts;
            // this is the never-crash backstop.
            var type = 0
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (cameraOn && hasPermission(Manifest.permission.CAMERA)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (type != 0) {
                startForeground(CallNotifications.ONGOING_CALL_ID, notification, type)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Neither grant is held (shouldn't happen once the entry points gate on the
                // mic). shortService needs no permission or manifest entry — degraded (3 min
                // cap, see onTimeout) but never a crash.
                startForeground(
                    CallNotifications.ONGOING_CALL_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
                )
            } else {
                startForeground(CallNotifications.ONGOING_CALL_ID, notification, 0)
            }
        } else {
            startForeground(CallNotifications.ONGOING_CALL_ID, notification)
        }
    }

    // Only reachable on the shortService fallback above (mic + camera grants both missing):
    // stop cleanly at its 3-minute cap instead of ANRing the process.
    override fun onTimeout(startId: Int) {
        stopSelf(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun acquireCpuLock() {
        if (cpuLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "klic:ongoing-call").apply {
            setReferenceCounted(false)
            // Safety timeout so a missed onDestroy can never leak the lock indefinitely; the
            // call's own teardown (finishCall → OngoingCallService.stop) releases it far sooner.
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseCpuLock() {
        cpuLock?.let { if (it.isHeld) it.release() }
        cpuLock = null
    }

    private fun acquireProximity() {
        val pm = getSystemService(PowerManager::class.java)
        if (proximityLock == null &&
            pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
        ) {
            proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "klic:call")
        }
        proximityLock?.let { if (!it.isHeld) it.acquire(2 * 60 * 60 * 1000L) }
    }

    private fun releaseProximity() {
        proximityLock?.let { if (it.isHeld) it.release() }
    }

    override fun onDestroy() {
        releaseProximity()
        releaseCpuLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PEER_NAME = "peerName"
        private const val EXTRA_IS_VIDEO = "isVideo"

        fun start(context: Context, peerName: String, isVideo: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OngoingCallService::class.java)
                    .putExtra(EXTRA_PEER_NAME, peerName)
                    .putExtra(EXTRA_IS_VIDEO, isVideo),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OngoingCallService::class.java))
        }
    }
}
