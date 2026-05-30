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
    private var delayedRingtoneJob: Job? = null
    private var volumeRampJob: Job? = null
    private var volumeGuardJob: Job? = null
    private var savedAlarmVolume: Int? = null
    private var speakerRoute: SpeakerPlaybackRoute? = null

    fun startWakeAlarm(payload: WakeTriggerPayload) {
        stop()

        if (payload.useAutoSilenceConflictPlayback) {
            startAutoSilenceConflictWakeAlarm(payload)
            return
        }

        if (payload.vibrationOnly) {
            startVibrationPattern()
            return
        }

        forceMaxAlarmVolume()
        val preferredUri = ringtoneUri(payload.ringtone, payload.customRingtoneUri)
        if (preferredUri != null && playUri(preferredUri, payload.progressiveVolume.rampDurationMillis())) {
            return
        }

        val fallbackUri = fallbackRingtoneUri()
        if (fallbackUri != null && fallbackUri != preferredUri && playUri(fallbackUri, payload.progressiveVolume.rampDurationMillis())) {
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
        return startRingtoneRamp(
            ringtonePreset = ringtonePreset,
            customRingtoneUri = customRingtoneUri,
            rampDurationMillis = progressiveVolume.rampDurationMillis(),
            maximizeAlarmVolume = maximizeAlarmVolume,
        )
    }

    fun startRingtoneRamp(
        ringtonePreset: RingtonePreset?,
        customRingtoneUri: String?,
        rampDurationMillis: Long?,
        maximizeAlarmVolume: Boolean = false,
    ): Boolean {
        stop()

        if (maximizeAlarmVolume) {
            forceMaxAlarmVolume()
        }

        val preferredUri = ringtonePreset?.let { preset -> ringtoneUri(preset, customRingtoneUri) }
        if (preferredUri != null && playUri(preferredUri, rampDurationMillis)) {
            return true
        }

        val fallbackUri = fallbackRingtoneUri()
        if (fallbackUri != null && fallbackUri != preferredUri && playUri(fallbackUri, rampDurationMillis)) {
            return true
        }

        if (maximizeAlarmVolume) {
            restoreAlarmVolume()
        }
        return false
    }

    fun startVibrationOnly(strong: Boolean = false) {
        stop()
        startVibrationPattern(strong = strong)
    }

    fun stop() {
        delayedRingtoneJob?.cancel()
        delayedRingtoneJob = null
        volumeRampJob?.cancel()
        volumeRampJob = null
        volumeGuardJob?.cancel()
        volumeGuardJob = null
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

    private fun startAutoSilenceConflictWakeAlarm(payload: WakeTriggerPayload) {
        startStrongVibrationThenRingtone(
            ringtonePreset = payload.ringtone,
            customRingtoneUri = payload.customRingtoneUri,
        )
    }

    private fun startStrongVibrationThenRingtone(
        ringtonePreset: RingtonePreset?,
        customRingtoneUri: String?,
    ) {
        startVibrationPattern(strong = true)
        delayedRingtoneJob = scope.launch {
            delay(STRONG_VIBRATION_MILLIS)
            vibrator?.cancel()
            vibrator = null

            forceMaxAlarmVolume()
            val preferredUri = ringtonePreset?.let { preset -> ringtoneUri(preset, customRingtoneUri) }
            if (preferredUri != null && playUri(preferredUri, LONG_RAMP_MILLIS)) {
                return@launch
            }

            val fallbackUri = fallbackRingtoneUri()
            if (fallbackUri != null && fallbackUri != preferredUri && playUri(fallbackUri, LONG_RAMP_MILLIS)) {
                return@launch
            }

            restoreAlarmVolume()
            startVibrationPattern(strong = true)
        }
    }

    private fun playUri(uri: Uri, volumeRampDurationMillis: Long?): Boolean {
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
            val initialVolume = if (volumeRampDurationMillis != null) {
                WakeProgressiveVolumeRamp.ringtoneVolumeAt(0L, volumeRampDurationMillis)
            } else {
                1f
            }
            player.setVolume(initialVolume, initialVolume)
            player.start()
            playbackRoute.start()
            if (volumeRampDurationMillis != null) {
                startProgressiveVolumeRamp(volumeRampDurationMillis) { volume ->
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

    private fun startProgressiveVolumeRamp(
        durationMillis: Long,
        onVolumeChanged: (Float) -> Unit,
    ) {
        volumeRampJob?.cancel()
        volumeRampJob = scope.launch {
            var elapsedMillis = 0L
            while (isActive && elapsedMillis < durationMillis) {
                delay(WakeProgressiveVolumeRamp.FALLBACK_STEP_INTERVAL_MILLIS)
                elapsedMillis += WakeProgressiveVolumeRamp.FALLBACK_STEP_INTERVAL_MILLIS
                runCatching {
                    onVolumeChanged(WakeProgressiveVolumeRamp.ringtoneVolumeAt(elapsedMillis, durationMillis))
                }
            }
            runCatching { onVolumeChanged(1f) }
        }
    }

    private fun startVibrationPattern(strong: Boolean = false) {
        val vibrator = systemVibrator() ?: return
        if (strong && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 1_000L, 200L, 1_000L, 200L),
                    intArrayOf(0, 255, 0, 255, 0),
                    0,
                ),
            )
        } else {
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0L, 700L, 300L, 900L), 0),
            )
        }
        this.vibrator = vibrator
    }

    private fun Boolean.rampDurationMillis(): Long? =
        WakeProgressiveVolumeRamp.DURATION_MILLIS.takeIf { this }

    private fun forceMaxAlarmVolume() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (savedAlarmVolume == null) {
            savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        }
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
        startAlarmVolumeGuard(audioManager, maxVolume)
    }

    private fun restoreAlarmVolume() {
        volumeGuardJob?.cancel()
        volumeGuardJob = null
        val volume = savedAlarmVolume ?: return
        savedAlarmVolume = null
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
    }

    private fun startAlarmVolumeGuard(
        audioManager: AudioManager,
        expectedVolume: Int,
    ) {
        volumeGuardJob?.cancel()
        volumeGuardJob = scope.launch {
            while (isActive) {
                delay(ALARM_VOLUME_GUARD_INTERVAL_MILLIS)
                val currentVolume = runCatching {
                    audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                }.getOrNull() ?: continue
                if (currentVolume < expectedVolume) {
                    runCatching {
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, expectedVolume, 0)
                    }
                }
            }
        }
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
        const val STRONG_VIBRATION_MILLIS = 2 * 60_000L
        const val LONG_RAMP_MILLIS = 10 * 60_000L
        private const val ALARM_VOLUME_GUARD_INTERVAL_MILLIS = 500L

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
