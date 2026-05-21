package com.tunisianprayertimes.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
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
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.R
import com.tunisianprayertimes.RingtonePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that implements the "Awake Check" safety net.
 *
 * Flow:
 * 1. Shows a quiet notification asking "Are you still awake?"
 * 2. Waits 60 seconds for the user to tap "Yes, I'm awake"
 * 3. If no response: vibrates for up to 3 minutes
 * 4. If still no response after vibration: plays the configured alarm ringtone
 * 5. User can dismiss at any point by tapping "Yes, I'm awake"
 */
class AwakeCheckService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var escalationJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var speakerRoute: SpeakerPlaybackRoute? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        _isRunning.value = true
        val eventId = intent?.getStringExtra(EXTRA_EVENT_ID) ?: return START_NOT_STICKY
        val ringtonePresetName = intent.getStringExtra(EXTRA_RINGTONE)
        val customRingtoneUri = intent.getStringExtra(EXTRA_CUSTOM_RINGTONE_URI)

        createChannel()
        startForeground(NOTIFICATION_ID, buildQuietNotification(eventId))
        startEscalation(eventId, ringtonePresetName, customRingtoneUri)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false
        escalationJob?.cancel()
        stopPlayback()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startEscalation(
        eventId: String,
        ringtonePresetName: String?,
        customRingtoneUri: String?,
    ) {
        escalationJob?.cancel()
        escalationJob = serviceScope.launch {
            // Phase 1: Wait 60 seconds for user to confirm awake
            delay(QUIET_WAIT_MILLIS)

            // Phase 2: Vibrate for up to 3 minutes
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, buildVibrationNotification(eventId))
            startVibration()
            delay(VIBRATION_DURATION_MILLIS)

            // Phase 3: Switch to ringtone
            vibrator?.cancel()
            vibrator = null
            notificationManager.notify(NOTIFICATION_ID, buildRingtoneNotification(eventId))
            startRingtone(ringtonePresetName, customRingtoneUri)
        }
    }

    private fun buildQuietNotification(eventId: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.awake_check_title))
            .setContentText(getString(R.string.awake_check_prompt))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_send,
                getString(R.string.awake_check_confirm),
                dismissPendingIntent(eventId),
            )
            .setContentIntent(appPendingIntent())
            .build()

    private fun buildVibrationNotification(eventId: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.awake_check_title))
            .setContentText(getString(R.string.awake_check_no_response))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_send,
                getString(R.string.awake_check_confirm),
                dismissPendingIntent(eventId),
            )
            .setContentIntent(appPendingIntent())
            .build()

    private fun buildRingtoneNotification(eventId: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.awake_check_title))
            .setContentText(getString(R.string.awake_check_ringing))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_send,
                getString(R.string.awake_check_confirm),
                dismissPendingIntent(eventId),
            )
            .setContentIntent(appPendingIntent())
            .build()

    private fun startVibration() {
        val v = systemVibrator() ?: return
        v.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0L, 700L, 300L, 900L), 0),
        )
        vibrator = v
    }

    private fun startRingtone(ringtonePresetName: String?, customRingtoneUri: String?) {
        val ringtonePreset = ringtonePresetName
            ?.let { name -> runCatching { RingtonePreset.valueOf(name) }.getOrNull() }

        val preferredUri = when {
            ringtonePreset == RingtonePreset.CUSTOM && customRingtoneUri != null -> {
                runCatching { Uri.parse(customRingtoneUri) }.getOrNull()
            }
            ringtonePreset != null && WakeRingtoneCatalog.rawResIdFor(ringtonePreset) != null -> {
                val rawResId = WakeRingtoneCatalog.rawResIdFor(ringtonePreset)!!
                Uri.parse("android.resource://$packageName/$rawResId")
            }
            ringtonePreset != null -> {
                WakeRingtoneCatalog.systemTypeFor(ringtonePreset)
                    ?.let { systemType -> RingtoneManager.getDefaultUri(systemType) }
            }
            else -> null
        }

        val uri = preferredUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        if (uri != null) {
            playUri(uri)
        }
    }

    private fun playUri(uri: Uri) {
        val player = MediaPlayer()
        var route: SpeakerPlaybackRoute? = null
        try {
            player.setDataSource(this, uri)
            player.setAudioAttributes(alarmAudioAttributes())
            val playbackRoute = SpeakerPlaybackRoute(this, player, serviceScope)
            route = playbackRoute
            playbackRoute.applyNow()
            player.isLooping = true
            player.prepare()
            playbackRoute.applyNow()
            player.setVolume(1f, 1f)
            player.start()
            playbackRoute.start()
            speakerRoute = playbackRoute
            mediaPlayer = player
        } catch (_: Exception) {
            route?.release()
            runCatching { player.release() }
        }
    }

    private fun stopPlayback() {
        speakerRoute?.release()
        speakerRoute = null
        mediaPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun createChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.awake_check_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.awake_check_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun dismissPendingIntent(eventId: String): PendingIntent {
        val intent = Intent(this, AwakeCheckReceiver::class.java)
            .setAction(ACTION_AWAKE_CHECK_CONFIRMED)
            .putExtra(EXTRA_EVENT_ID, eventId)

        return PendingIntent.getBroadcast(
            this,
            "awake_check_dismiss".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun appPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_ALARMS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun alarmAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    @Suppress("DEPRECATION")
    private fun systemVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }

    companion object {
        private const val CHANNEL_ID = "tunisianprayertimes.awake.check.silent"
        private const val NOTIFICATION_ID = 0x7A0E

        const val ACTION_AWAKE_CHECK_CONFIRMED =
            "com.tunisianprayertimes.action.AWAKE_CHECK_CONFIRMED"

        private const val QUIET_WAIT_MILLIS = 60_000L
        private const val VIBRATION_DURATION_MILLIS = 3 * 60_000L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        /** Confirm awake from the app UI — stops the service. */
        fun confirmAwake(context: Context) {
            context.stopService(Intent(context, AwakeCheckService::class.java))
        }

        fun intent(
            context: Context,
            eventId: String,
            ringtonePreset: RingtonePreset?,
            customRingtoneUri: String?,
        ): Intent = Intent(context, AwakeCheckService::class.java).apply {
            putExtra(EXTRA_EVENT_ID, eventId)
            ringtonePreset?.let { putExtra(EXTRA_RINGTONE, it.name) }
            customRingtoneUri?.let { putExtra(EXTRA_CUSTOM_RINGTONE_URI, it) }
        }
    }
}
