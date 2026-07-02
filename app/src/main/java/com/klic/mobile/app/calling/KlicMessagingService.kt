package com.klic.mobile.app.calling

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.klic.mobile.app.KlicApplication
import kotlinx.coroutines.launch

/**
 * Receives high-priority FCM data messages — the wake path for incoming calls when the
 * app is backgrounded or killed (the foreground socket service only covers the running
 * app, and Android 15 caps how long it may stay alive). Mirrors the call-end handling in
 * [CallSignalingService] so both transports behave identically.
 */
class KlicMessagingService : FirebaseMessagingService() {

    private val container get() = (application as KlicApplication).container

    override fun onNewToken(token: String) {
        // Register whenever the token rotates; no-ops server-side if we're not signed in.
        container.applicationScope.launch { container.repository.registerDevice(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        when (data["type"]) {
            "call.invite" -> {
                val invite = CallInvite.fromMap(data)
                if (invite.callId.isBlank()) return
                // Glare guard: don't pop an incoming screen for a call we're already placing.
                if (container.activeCallConversationId.value == invite.conversationId) return
                CallNotifications.showIncomingCall(this, invite)
                // Ring from the notification path so a backgrounded/killed device rings even if the
                // full-screen Activity isn't launched. Idempotent if the socket also delivered this.
                CallRinger.start(applicationContext, invite)
            }
            "call.end", "call.cancel", "call.decline" -> {
                CallRinger.stop()
                CallNotifications.cancelIncomingCall(this)
                sendBroadcast(
                    Intent(IncomingCallActivity.ACTION_CALL_ENDED).apply {
                        setPackage(packageName)
                        putExtra("callId", data["callId"])
                    }
                )
            }
            "message.new" -> {
                // The live socket already shows messages while the app is open; only post a
                // notification (which puts the badge on the launcher icon) when backgrounded.
                if (!container.appForeground) {
                    CallNotifications.showMessage(
                        this,
                        title = data["title"] ?: "New message",
                        body = data["body"] ?: "",
                        conversationId = data["conversationId"] ?: "",
                    )
                }
            }
        }
    }
}
