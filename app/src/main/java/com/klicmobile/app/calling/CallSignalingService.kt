package com.klicmobile.app.calling

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.klicmobile.app.KlicApplication
import com.klicmobile.app.realtime.SocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the Socket.io connection alive in the background (no Firebase) so incoming
 * calls and messages are delivered, then rings calls via the full-screen notification.
 */
class CallSignalingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectorsStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                CallNotifications.SERVICE_ID,
                CallNotifications.serviceNotification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(CallNotifications.SERVICE_ID, CallNotifications.serviceNotification(this))
        }

        val container = (application as KlicApplication).container
        val socket = container.socket
        scope.launch(Dispatchers.IO) {
            container.tokenStore.load()
            runCatching { container.repository.ensureFreshToken() }
            container.tokenStore.cachedAccess?.let { socket.connect(it) }
        }
        if (collectorsStarted) return START_STICKY
        collectorsStarted = true
        scope.launch {
            socket.incomingCalls.collect { invite ->
                // Glare guard: if we're already placing/in a call with this same conversation,
                // don't pop a second incoming-call screen. The server collapses simultaneous
                // calls into one, so this incoming is the duplicate side of our own call.
                if (container.activeCallConversationId.value == invite.conversationId) return@collect
                CallNotifications.showIncomingCall(this@CallSignalingService, invite.toCallInvite())
                // Ring from here (not the full-screen Activity) so the call rings even when the
                // Activity never launches (unlocked/in-use device or no full-screen-intent grant).
                CallRinger.start(applicationContext)
            }
        }
        scope.launch {
            socket.callEvents.collect { event ->
                if (
                    event.type == SocketService.CallEvent.Type.CANCEL ||
                    event.type == SocketService.CallEvent.Type.END ||
                    event.type == SocketService.CallEvent.Type.DECLINE
                ) {
                    CallRinger.stop()
                    CallNotifications.cancelIncomingCall(this@CallSignalingService)
                    sendBroadcast(
                        Intent(IncomingCallActivity.ACTION_CALL_ENDED).apply {
                            setPackage(packageName)
                            putExtra("callId", event.callId)
                        }
                    )
                }
            }
        }
        return START_STICKY
    }

    // Android 15 caps a dataSync foreground service at ~6h/24h, then calls this. We must
    // stop within a few seconds or the system throws ForegroundServiceDidNotStopInTimeException.
    // Incoming calls still arrive via FCM after the service is gone.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CallSignalingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallSignalingService::class.java))
        }
    }
}

private fun SocketService.CallInvite.toCallInvite() = CallInvite(
    callId = callId,
    conversationId = conversationId,
    roomName = roomName,
    livekitUrl = livekitUrl,
    kind = kind,
    fromName = fromDisplayName,
)
