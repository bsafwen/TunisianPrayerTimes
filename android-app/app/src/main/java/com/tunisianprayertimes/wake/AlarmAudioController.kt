package com.tunisianprayertimes.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.tunisianprayertimes.RingtonePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class AlarmAudioController(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var volumeRampJob: Job? = null
    private var savedAlarmVolume: Int? = null
    private var speakerRoute: SpeakerPlaybackRoute? = null

    fun startWakeAlarm(payload: WakeTriggerPayload) {
        stop()

        if (payload.vibrationOnly) {
            startVibrationPattern()
            return
        }

        forceMaxAlarmVolume()
        val preferredUri = ringtoneUri(payload.ringtone, payload.customRingtoneUri)
        if (preferredUri != null && playUri(preferredUri, payload.progressiveVolume)) {
            return
        }

        val fallbackUri = fallbackRingtoneUri()
        if (fallbackUri != null && fallbackUri != preferredUri && playUri(fallbackUri, payload.progressiveVolume)) {
            return
        }

        restoreAlarmVolume()
        startVibrationPattern()
    }

    fun startRingtone(
        ringtonePreset: RingtonePreset?,
        customRingtoneUri: String?,
        progressiveVolume: Boolean = false,
        maximizeAlarmVolume: Boolean = false,
    ): Boolean {
        stop()

        if (maximizeAlarmVolume) {
            forceMaxAlarmVolume()
        }

        val preferredUri = ringtonePreset?.let { preset -> ringtoneUri(preset, customRingtoneUri) }
        if (preferredUri != null && playUri(preferredUri, progressiveVolume)) {
            return true
        }

        val fallbackUri = fallbackRingtoneUri()
        if (fallbackUri != null && fallbackUri != preferredUri && playUri(fallbackUri, progressiveVolume)) {
            return true
        }

        if (maximizeAlarmVolume) {
            restoreAlarmVolume()
        }
        return false
    }

    fun startVibrationOnly() {
        stop()
        startVibrationPattern()
    }

    fun stop() {
        volumeRampJob?.cancel()
        volumeRampJob = null
        speakerRoute?.release()
        speakerRoute = null
        restoreAlarmVolume()
        mediaPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) {
                    player.stop()
                }
            }
            runCatching { player.release() }
        }
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun ringtoneUri(ringtonePreset: RingtonePreset, customRingtoneUri: String?): Uri? = when {
        ringtonePreset == RingtonePreset.CUSTOM && customRingtoneUri != null -> {
            runCatching { Uri.parse(customRingtoneUri) }.getOrNull()
        }

        WakeRingtoneCatalog.rawResIdFor(ringtonePreset) != null -> {
            val rawResId = WakeRingtoneCatalog.rawResIdFor(ringtonePreset) ?: return null
            Uri.parse("android.resource://${appContext.packageName}/$rawResId")
        }

        else -> {
            WakeRingtoneCatalog.systemTypeFor(ringtonePreset)
                ?.let { systemType -> RingtoneManager.getDefaultUri(systemType) }
        }
    }

    private fun fallbackRingtoneUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    private fun playUri(uri: Uri, progressiveVolume: Boolean): Boolean {
        val player = MediaPlayer()
        var route: SpeakerPlaybackRoute? = null
        return try {
            player.setDataSource(appContext, uri)
            player.setAudioAttributes(alarmAudioAttributes())
            val playbackRoute = SpeakerPlaybackRoute(appContext, player, scope)
            route = playbackRoute
            playbackRoute.applyNow()
            player.isLooping = true
            player.prepare()
            playbackRoute.applyNow()
            val initialVolume = if (progressiveVolume) {
                WakeProgressiveVolumeRamp.ringtoneVolumeAt(0L)
            } else {
                1f
            }
            player.setVolume(initialVolume, initialVolume)
            player.start()
            playbackRoute.start()
            if (progressiveVolume) {
                startProgressiveVolumeRamp { volume ->
                    player.setVolume(volume, volume)
                }
            }
            speakerRoute = playbackRoute
            mediaPlayer = player
            true
        } catch (_: Exception) {
            route?.release()
            runCatching { player.release() }
            false
        }
    }

    private fun startProgressiveVolumeRamp(onVolumeChanged: (Float) -> Unit) {
        volumeRampJob?.cancel()
        volumeRampJob = scope.launch {
            var elapsedMillis = 0L
            while (isActive && elapsedMillis < WakeProgressiveVolumeRamp.DURATION_MILLIS) {
                delay(WakeProgressiveVolumeRamp.FALLBACK_STEP_INTERVAL_MILLIS)
                elapsedMillis += WakeProgressiveVolumeRamp.FALLBACK_STEP_INTERVAL_MILLIS
                runCatching {
                    onVolumeChanged(WakeProgressiveVolumeRamp.ringtoneVolumeAt(elapsedMillis))
                }
            }
            runCatching { onVolumeChanged(1f) }
        }
    }

    private fun startVibrationPattern() {
        val vibrator = systemVibrator() ?: return
        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0L, 700L, 300L, 900L), 0),
        )
        this.vibrator = vibrator
    }

    private fun forceMaxAlarmVolume() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
    }

    private fun restoreAlarmVolume() {
        val volume = savedAlarmVolume ?: return
        savedAlarmVolume = null
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
    }

    private fun alarmAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    @Suppress("DEPRECATION")
    private fun systemVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            appContext.getSystemService(Vibrator::class.java)
        }

    companion object {
        fun createSilentAlarmChannel(
            context: Context,
            channelId: String,
            name: CharSequence,
            description: String,
        ) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId,
                name,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                this.description = description
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
