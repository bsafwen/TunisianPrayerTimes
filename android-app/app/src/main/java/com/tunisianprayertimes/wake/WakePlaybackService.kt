package com.tunisianprayertimes.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.tunisianprayertimes.MainActivity
import com.tunisianprayertimes.R
import com.tunisianprayertimes.RingtonePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WakePlaybackService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var volumeRampJob: Job? = null
    private var savedAlarmVolume: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private var currentNotificationId: Int? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val payload = intent?.toWakeTriggerPayload() ?: return START_NOT_STICKY

        createChannel()

        // Cancel the previous notification if it has a different ID (e.g. main → sub-alarm)
        // so it doesn't linger in the notification shade.
        val newNotifId = notificationIdFor(payload.eventId)
        currentNotificationId?.let { oldId ->
            if (oldId != newNotifId) {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(oldId)
            }
        }
        currentNotificationId = newNotifId

        startForeground(newNotifId, buildNotification(payload))
        startPlayback(payload)

        // Explicitly deliver the payload to WakeAlertActivity.  The notification's
        // fullScreenIntent only fires on initial display; when the service is already
        // foregrounded (e.g. a sub-alarm arriving while the main alarm is active),
        // updating the notification does NOT re-trigger fullScreenIntent, so the
        // activity would never see the new payload.
        val activityIntent = Intent(this, WakeAlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtras(
                Intent().populateWakeTriggerPayload(
                    eventId = payload.eventId,
                    prayer = payload.prayer,
                    effectivePrayer = payload.effectivePrayer,
                    mainAlarmMode = payload.mainAlarmMode,
                    hour = payload.hour,
                    minute = payload.minute,
                    ringtone = payload.ringtone,
                    customRingtoneUri = payload.customRingtoneUri,
                    vibrationOnly = payload.vibrationOnly,
                    wakeUpCheckEnabled = payload.wakeUpCheckEnabled,
                    wakeUpCheckType = payload.wakeUpCheckType,
                    wakeUpCheckDifficulty = payload.wakeUpCheckDifficulty,
                    whackAMoleKillTarget = payload.whackAMoleKillTarget,
                    wakeUpCheckSteps = payload.wakeUpCheckSteps,
                    progressiveVolume = payload.progressiveVolume,
                    snoreTrackingEnabled = payload.snoreTrackingEnabled,
                    awakeCheckEnabled = payload.awakeCheckEnabled,
                    awakeCheckDelayMinutes = payload.awakeCheckDelayMinutes,
                    wakeUpCheckChallenge = payload.wakeUpCheckChallenge,
                    isSubAlarm = payload.isSubAlarm,
                    subAlarmId = payload.subAlarmId,
                    offsetMinutes = payload.offsetMinutes,
                    offsetDirection = payload.offsetDirection,
                ),
            )
        startActivity(activityIntent)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopPlayback()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(payload: WakeTriggerPayload): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(payload.title(this))
            .setContentText(payload.contentText(this))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(alertActivityPendingIntent(this, payload))
            .setFullScreenIntent(alertActivityPendingIntent(this, payload), true)
            .apply {
                if (!payload.wakeUpCheckEnabled) {
                    addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.wake_alarm_stop),
                        stopPendingIntent(this@WakePlaybackService, payload),
                    )
                }
            }
            .build()

    private fun startPlayback(payload: WakeTriggerPayload) {
        stopPlayback()

        if (payload.vibrationOnly) {
            startVibrationOnly()
            return
        }

        forceMaxAlarmVolume()

        val preferredUri = preferredRingtoneUri(payload)
        if (preferredUri != null && playUri(preferredUri, payload.progressiveVolume)) {
            return
        }

        val fallbackUri = fallbackRingtoneUri()
        if (fallbackUri != null && fallbackUri != preferredUri && playUri(fallbackUri, payload.progressiveVolume)) {
            return
        }

        startVibrationOnly()
    }

    private fun preferredRingtoneUri(payload: WakeTriggerPayload): Uri? = when {
        payload.ringtone == RingtonePreset.CUSTOM && payload.customRingtoneUri != null -> {
            runCatching { Uri.parse(payload.customRingtoneUri) }.getOrNull()
        }

        WakeRingtoneCatalog.rawResIdFor(payload.ringtone) != null -> {
            val rawResId = WakeRingtoneCatalog.rawResIdFor(payload.ringtone) ?: return null
            Uri.parse("android.resource://$packageName/$rawResId")
        }

        else -> {
            WakeRingtoneCatalog.systemTypeFor(payload.ringtone)
                ?.let { systemType -> RingtoneManager.getDefaultUri(systemType) }
        }
    }

    private fun fallbackRingtoneUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    private fun playUri(uri: Uri, progressiveVolume: Boolean): Boolean {
        val player = MediaPlayer()
        return try {
            player.setDataSource(this, uri)
            player.setAudioAttributes(alarmAudioAttributes())
            player.isLooping = true
            player.prepare()
            val initialVolume = if (progressiveVolume) {
                WakeProgressiveVolumeRamp.ringtoneVolumeAt(0L)
            } else {
                1f
            }
            player.setVolume(initialVolume, initialVolume)
            forceSpeakerOutput(player)
            player.start()
            if (progressiveVolume) {
                startVolumeRamp { volume ->
                    player.setVolume(volume, volume)
                }
            }
            mediaPlayer = player
            true
        } catch (_: Exception) {
            runCatching { player.release() }
            false
        }
    }

    private fun startVolumeRamp(onVolumeChanged: (Float) -> Unit) {
        volumeRampJob?.cancel()
        volumeRampJob = serviceScope.launch {
            var elapsedMillis = 0L
            while (isActive && elapsedMillis < WakeProgressiveVolumeRamp.DURATION_MILLIS) {
                delay(WakeProgressiveVolumeRamp.STEP_INTERVAL_MILLIS)
                elapsedMillis += WakeProgressiveVolumeRamp.STEP_INTERVAL_MILLIS
                runCatching {
                    onVolumeChanged(WakeProgressiveVolumeRamp.ringtoneVolumeAt(elapsedMillis))
                }
            }
            runCatching { onVolumeChanged(1f) }
        }
    }

    private fun stopPlayback() {
        volumeRampJob?.cancel()
        volumeRampJob = null
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

    private fun startVibrationOnly() {
        val vibrator = systemVibrator() ?: return
        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0L, 700L, 300L, 900L), 0),
        )
        this.vibrator = vibrator
    }

    private fun createChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.wake_alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.wake_alarm_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun forceMaxAlarmVolume() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
    }

    private fun restoreAlarmVolume() {
        val volume = savedAlarmVolume ?: return
        savedAlarmVolume = null
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
    }

    private fun forceSpeakerOutput(player: MediaPlayer) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?.let { player.setPreferredDevice(it) }
    }

    private fun alarmAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun alertActivityPendingIntent(
        context: Context,
        payload: WakeTriggerPayload,
    ): PendingIntent {
        val intent = Intent(context, WakeAlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtras(
                Intent().populateWakeTriggerPayload(
                    eventId = payload.eventId,
                    prayer = payload.prayer,
                    effectivePrayer = payload.effectivePrayer,
                    mainAlarmMode = payload.mainAlarmMode,
                    hour = payload.hour,
                    minute = payload.minute,
                    ringtone = payload.ringtone,
                    customRingtoneUri = payload.customRingtoneUri,
                    vibrationOnly = payload.vibrationOnly,
                    wakeUpCheckEnabled = payload.wakeUpCheckEnabled,
                    wakeUpCheckType = payload.wakeUpCheckType,
                    wakeUpCheckDifficulty = payload.wakeUpCheckDifficulty,
                    whackAMoleKillTarget = payload.whackAMoleKillTarget,
                    wakeUpCheckSteps = payload.wakeUpCheckSteps,
                    progressiveVolume = payload.progressiveVolume,
                    snoreTrackingEnabled = payload.snoreTrackingEnabled,
                    awakeCheckEnabled = payload.awakeCheckEnabled,
                    awakeCheckDelayMinutes = payload.awakeCheckDelayMinutes,
                    wakeUpCheckChallenge = payload.wakeUpCheckChallenge,
                    isSubAlarm = payload.isSubAlarm,
                    subAlarmId = payload.subAlarmId,
                    offsetMinutes = payload.offsetMinutes,
                    offsetDirection = payload.offsetDirection,
                ),
            )

        return PendingIntent.getActivity(
            context,
            wakeEventRequestCode(payload.eventId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopPendingIntent(context: Context, payload: WakeTriggerPayload): PendingIntent {
        val intent = Intent(context, WakeActionReceiver::class.java)
            .setAction(ACTION_STOP_WAKE_ALARM)
            .populateWakeStopPayload(payload.eventId)
            .putExtra(EXTRA_AWAKE_CHECK_ENABLED, payload.awakeCheckEnabled)
            .putExtra(EXTRA_AWAKE_CHECK_DELAY_MINUTES, payload.awakeCheckDelayMinutes)
            .putExtra(EXTRA_RINGTONE, payload.ringtone.name)
            .apply {
                payload.customRingtoneUri?.let { putExtra(EXTRA_CUSTOM_RINGTONE_URI, it) }
            }

        return PendingIntent.getBroadcast(
            context,
            wakeEventRequestCode(payload.eventId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationIdFor(eventId: String): Int = eventId.hashCode() and 0x7fffffff

    @Suppress("DEPRECATION")
    private fun systemVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }

    companion object {
        private const val CHANNEL_ID = "tunisianprayertimes.wake.playback"

        fun playbackIntent(context: Context, payload: WakeTriggerPayload): Intent =
            Intent(context, WakePlaybackService::class.java).putExtras(
                Intent().populateWakeTriggerPayload(
                    eventId = payload.eventId,
                    prayer = payload.prayer,
                    effectivePrayer = payload.effectivePrayer,
                    mainAlarmMode = payload.mainAlarmMode,
                    hour = payload.hour,
                    minute = payload.minute,
                    ringtone = payload.ringtone,
                    customRingtoneUri = payload.customRingtoneUri,
                    vibrationOnly = payload.vibrationOnly,
                    wakeUpCheckEnabled = payload.wakeUpCheckEnabled,
                    wakeUpCheckType = payload.wakeUpCheckType,
                    wakeUpCheckDifficulty = payload.wakeUpCheckDifficulty,
                    whackAMoleKillTarget = payload.whackAMoleKillTarget,
                    wakeUpCheckSteps = payload.wakeUpCheckSteps,
                    progressiveVolume = payload.progressiveVolume,
                    snoreTrackingEnabled = payload.snoreTrackingEnabled,
                    awakeCheckEnabled = payload.awakeCheckEnabled,
                    awakeCheckDelayMinutes = payload.awakeCheckDelayMinutes,
                    wakeUpCheckChallenge = payload.wakeUpCheckChallenge,
                    isSubAlarm = payload.isSubAlarm,
                    subAlarmId = payload.subAlarmId,
                    offsetMinutes = payload.offsetMinutes,
                    offsetDirection = payload.offsetDirection,
                ),
            )

        fun alarmClockInfoIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
