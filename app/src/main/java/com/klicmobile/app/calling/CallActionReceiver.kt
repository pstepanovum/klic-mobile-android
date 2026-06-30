package com.klicmobile.app.calling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.klicmobile.app.KlicApplication
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
                container.applicationScope.launch { container.repository.declineCall(callId) }
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
        const val ACTION_DECLINE = "com.klicmobile.app.action.DECLINE"
        const val ACTION_HANGUP = "com.klicmobile.app.action.HANGUP"
    }
}
