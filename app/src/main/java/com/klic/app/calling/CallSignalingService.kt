package com.klic.app.calling

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.klic.app.KlicApplication
import com.klic.app.realtime.SocketService
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

        val socket = (application as KlicApplication).container.socket
        scope.launch {
            socket.incomingCalls.collect { invite ->
                CallNotifications.showIncomingCall(this@CallSignalingService, invite.toCallInvite())
            }
        }
        return START_STICKY
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
