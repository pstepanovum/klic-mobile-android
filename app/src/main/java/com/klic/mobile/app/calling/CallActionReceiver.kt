package com.klic.mobile.app.calling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.klic.mobile.app.KlicApplication
import kotlinx.coroutines.launch

/**
 * Handles call notification actions (decline an incoming call, hang up an ongoing one).
 * Manifest-registered so the actions work even if the process was killed — the system
 * restarts us to deliver the broadcast.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as KlicApplication).container
        when (intent.action) {
            ACTION_DECLINE -> {
                val callId = intent.getStringExtra("callId") ?: return
                CallRinger.stop()
                // An FCM-woken (cold) process has never loaded the stored tokens — the decline
                // POST would go out unauthenticated and be silently dropped, leaving the caller
                // ringing the full timeout. Load them first, and goAsync() so the system keeps
                // the process alive until the request actually lands.
                val pending = goAsync()
                container.applicationScope.launch {
                    try {
                        if (!container.tokenStore.hasSession) container.tokenStore.load()
                        container.repository.declineCall(callId)
                    } finally {
                        pending.finish()
                    }
                }
                CallNotifications.cancelIncomingCall(context)
                // Close the full-screen UI if it's open.
                context.sendBroadcast(
                    Intent(IncomingCallActivity.ACTION_CALL_ENDED).apply {
                        setPackage(context.packageName)
                        putExtra("callId", callId)
                    }
                )
            }
            ACTION_HANGUP -> container.requestHangup()
        }
    }

    companion object {
        const val ACTION_DECLINE = "com.klic.mobile.app.action.DECLINE"
        const val ACTION_HANGUP = "com.klic.mobile.app.action.HANGUP"
    }
}
