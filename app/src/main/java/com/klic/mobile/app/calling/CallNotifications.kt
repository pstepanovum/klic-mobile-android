package com.klic.mobile.app.calling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.klic.mobile.app.MainActivity
import com.klic.mobile.app.R

object CallNotifications {
    // "calls_v2" so the silent-channel change applies on in-place updates — a channel's sound
    // can't be changed after creation, only by recreating it under a new id.
    const val CHANNEL_CALLS = "calls_v2"
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_SERVICE = "service"
    const val INCOMING_CALL_ID = 1001
    const val ONGOING_CALL_ID = 1002
    const val SERVICE_ID = 1
    const val MESSAGE_ID_BASE = 2000

    private const val RC_FULL_SCREEN = 10
    private const val RC_ANSWER = 11
    private const val RC_DECLINE = 12
    private const val RC_ONGOING_TAP = 13
    private const val RC_HANGUP = 14

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming voice and video calls"
                setBypassDnd(true)
                // CallRinger plays our bundled ringtone, so keep the channel itself silent —
                // otherwise the channel's default sound would ring on top of it.
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(true) // launcher badge dot for unread messages
            }
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
        // Group invites ring as "<Caller> · <Group title>" so the surface says where it's from.
        val caller = Person.Builder().setName(invite.displayLabel).setImportant(true).build()

        // Answer: open MainActivity straight into the accept flow (reuses ACTION_ACCEPT_CALL).
        val answerIntent = Intent(context, MainActivity::class.java).apply {
            action = IncomingCallActivity.ACTION_ACCEPT_CALL
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtras(invite.toBundle())
        }
        val answerPending = PendingIntent.getActivity(
            context, RC_ANSWER, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Decline: broadcast so it works without opening any UI, even after process death.
        val declinePending = PendingIntent.getBroadcast(
            context, RC_DECLINE,
            Intent(context, CallActionReceiver::class.java).apply {
                action = CallActionReceiver.ACTION_DECLINE
                setPackage(context.packageName)
                putExtra("callId", invite.callId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Full-screen UI shown over the lock screen.
        val fullScreen = Intent(context, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtras(invite.toBundle())
        }
        val fsPending = PendingIntent.getActivity(
            context, RC_FULL_SCREEN, fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(invite.displayLabel)
            .setContentText(if (invite.kind == "VIDEO") "Incoming video call" else "Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(fsPending, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePending, answerPending))
            // Backstop: auto-expire the ring surface if the end event never arrives (D2);
            // CallRinger's own 65s timer handles the ringtone side.
            .setTimeoutAfter(65_000)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(INCOMING_CALL_ID, notification)
    }

    fun cancelIncomingCall(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(INCOMING_CALL_ID)
    }

    /** Ongoing-call notification for OngoingCallService: tap returns to the call, hang-up ends it. */
    fun ongoingCallNotification(context: Context, peerName: String, isVideo: Boolean): Notification {
        createChannels(context)
        val peer = Person.Builder().setName(peerName).setImportant(true).build()

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val tapPending = PendingIntent.getActivity(
            context, RC_ONGOING_TAP, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val hangUpPending = PendingIntent.getBroadcast(
            context, RC_HANGUP,
            Intent(context, CallActionReceiver::class.java).apply {
                action = CallActionReceiver.ACTION_HANGUP
                setPackage(context.packageName)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(peerName)
            .setContentText(if (isVideo) "Ongoing video call" else "Ongoing call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(tapPending)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(peer, hangUpPending))
            .build()
    }

    fun showMessage(context: Context, title: String, body: String, conversationId: String) {
        createChannels(context)
        val open = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context, conversationId.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(MESSAGE_ID_BASE + conversationId.hashCode(), notification)
    }

    fun cancelMessage(context: Context, conversationId: String) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(MESSAGE_ID_BASE + conversationId.hashCode())
    }
}
