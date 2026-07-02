package com.klic.mobile.app.calling

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.klic.mobile.app.R

/**
 * Plays a looping ringtone + vibration for an incoming call, honouring the ringer mode
 * (silent → nothing, vibrate → vibrate only, normal → ring + vibrate).
 *
 * A process-wide singleton owned by the *notification* path ([CallSignalingService] /
 * [KlicMessagingService]) rather than by the incoming-call Activity: the full-screen intent
 * doesn't always launch the Activity (USE_FULL_SCREEN_INTENT not granted, or the screen is
 * unlocked and in use → heads-up only), and the Calls channel is intentionally silent, so a
 * ringer tied to the Activity left those calls ringless. [start] is idempotent (a duplicate
 * invite over the other transport won't double-ring); [stop] is safe to call from anywhere.
 *
 * A 65s backstop (server RING_TTL is 60s) tears the ring surfaces down even when the
 * `call.end` push/socket event is lost (D2), leaving a missed-call notification behind.
 */
object CallRinger {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var ringing = false
    private val handler = Handler(Looper.getMainLooper())
    private var backstop: Runnable? = null

    private const val BACKSTOP_MS = 65_000L

    @Synchronized
    fun start(context: Context, invite: CallInvite? = null) {
        // Already ringing (e.g. the socket and FCM both delivered this invite) — don't stack.
        if (ringing) return
        ringing = true
        val appContext = context.applicationContext
        // Scheduled before the ringer-mode check: the notification is up even on a silent
        // device, so the backstop must clear it either way.
        scheduleBackstop(appContext, invite)
        val audio = appContext.getSystemService(AudioManager::class.java)
        when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> return
            AudioManager.RINGER_MODE_VIBRATE -> startVibration(appContext)
            else -> { startRingtone(appContext); startVibration(appContext) }
        }
    }

    /** If no accept/decline/cancel/end arrived within the ring window, stop ringing, drop the
     *  incoming-call notification, and leave a missed-call notification in its place. */
    private fun scheduleBackstop(appContext: Context, invite: CallInvite?) {
        backstop?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            stop()
            CallNotifications.cancelIncomingCall(appContext)
            if (invite != null) {
                CallNotifications.showMessage(
                    appContext,
                    title = invite.displayLabel,
                    body = if (invite.kind == "VIDEO") "Missed video call" else "Missed call",
                    conversationId = invite.conversationId,
                )
            }
        }
        backstop = runnable
        handler.postDelayed(runnable, BACKSTOP_MS)
    }

    private fun startRingtone(context: Context) {
        // Klic's own bundled ringtone (res/raw/ringtone.wav), looped for the duration of the ring.
        val uri = Uri.parse("android.resource://${context.packageName}/${R.raw.ringtone}")
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        }
    }

    private fun startVibration(context: Context) {
        val vib = vibrator(context)
        if (vib?.hasVibrator() != true) return
        vibrator = vib
        // 1s on, 1s off, repeating from index 0.
        val pattern = longArrayOf(0, 1000, 1000)
        vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    @Synchronized
    fun stop() {
        ringing = false
        backstop?.let { handler.removeCallbacks(it) }
        backstop = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }
}
