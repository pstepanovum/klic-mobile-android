package com.klic.app.calling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.klic.app.R

object CallNotifications {
    const val CHANNEL_CALLS = "calls"
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_SERVICE = "service"
    const val INCOMING_CALL_ID = 1001
    const val SERVICE_ID = 1
    const val MESSAGE_ID_BASE = 2000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming voice and video calls"
                setBypassDnd(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps Klic connected for calls"
            }
        )
    }

    /** Persistent notification for the foreground connection service. */
    fun serviceNotification(context: Context): Notification {
        createChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Klic")
            .setContentText("Connected for calls")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    /** Full-screen, high-priority incoming-call notification (rings while backgrounded/locked). */
    fun showIncomingCall(context: Context, invite: CallInvite) {
        createChannels(context)
        val fullScreen = Intent(context, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtras(invite.toBundle())
        }
        val fsPending = PendingIntent.getActivity(
            context, invite.callId.hashCode(), fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(invite.fromName)
            .setContentText(if (invite.kind == "VIDEO") "Incoming video call" else "Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(true)
            .setFullScreenIntent(fsPending, true)
            .setContentIntent(fsPending)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(INCOMING_CALL_ID, notification)
    }

    fun cancelIncomingCall(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(INCOMING_CALL_ID)
    }

    fun showMessage(context: Context, title: String, body: String, conversationId: String) {
        createChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(MESSAGE_ID_BASE + conversationId.hashCode(), notification)
    }
}
