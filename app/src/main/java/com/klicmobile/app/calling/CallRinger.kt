package com.klicmobile.app.calling

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.klicmobile.app.R

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
 */
object CallRinger {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    @Synchronized
    fun start(context: Context) {
        // Already ringing (e.g. the socket and FCM both delivered this invite) — don't stack.
        if (player != null || vibrator != null) return
        val appContext = context.applicationContext
        val audio = appContext.getSystemService(AudioManager::class.java)
        when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> return
            AudioManager.RINGER_MODE_VIBRATE -> startVibration(appContext)
            else -> { startRingtone(appContext); startVibration(appContext) }
        }
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
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }
}
