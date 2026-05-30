package com.tunisianprayertimes.wake

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tunisianprayertimes.MainActivity
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.R
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.RingtonePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Foreground service that implements the "Awake Check" safety net.
 *
 * Flow:
 * 1. Shows a quiet notification asking "Are you still awake?"
 * 2. Waits 60 seconds for the user to tap "Yes, I'm awake"
 * 3. If no response: vibrates until the user confirms
 * 4. User can dismiss at any point by tapping "Yes, I'm awake"
 */
class AwakeCheckService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioController by lazy { AlarmAudioController(this, serviceScope) }
    private var escalationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val eventId = intent?.getStringExtra(EXTRA_EVENT_ID) ?: return START_NOT_STICKY
        val ringtonePreset = intent.getStringExtra(EXTRA_RINGTONE)
            ?.let { raw -> runCatching { RingtonePreset.valueOf(raw) }.getOrNull() }
        val customRingtoneUri = intent.getStringExtra(EXTRA_CUSTOM_RINGTONE_URI)
        val autoSilenceOverrideAllowed = intent.getBooleanExtra(
            EXTRA_AUTO_SILENCE_OVERRIDE_ALLOWED,
            false,
        )
        val autoSilenceConflictPrayer = intent.getStringExtra(EXTRA_AUTO_SILENCE_CONFLICT_PRAYER)
            ?.let { rawPrayer -> runCatching { Prayer.valueOf(rawPrayer) }.getOrNull() }

        if (cancelIfSilenceActive(
                eventId = eventId,
                autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
                autoSilenceConflictPrayer = autoSilenceConflictPrayer,
                liftAutoSilence = false,
            )
        ) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        _isRunning.value = true

        createChannel()
        startForeground(NOTIFICATION_ID, buildQuietNotification(eventId))
        startEscalation(
            eventId = eventId,
            ringtonePreset = ringtonePreset,
            customRingtoneUri = customRingtoneUri,
            autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
            autoSilenceConflictPrayer = autoSilenceConflictPrayer,
        )
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
        ringtonePreset: RingtonePreset?,
        customRingtoneUri: String?,
        autoSilenceOverrideAllowed: Boolean,
        autoSilenceConflictPrayer: Prayer?,
    ) {
        escalationJob?.cancel()
        escalationJob = serviceScope.launch {
            delay(QUIET_WAIT_MILLIS)
            if (cancelIfSilenceActive(
                    eventId = eventId,
                    autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
                    autoSilenceConflictPrayer = autoSilenceConflictPrayer,
                    liftAutoSilence = true,
                )
            ) return@launch

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, buildVibrationNotification(eventId))
            audioController.startVibrationOnly(strong = true)

            var remainingVibrationMillis = AlarmAudioController.STRONG_VIBRATION_MILLIS
            while (isActive && remainingVibrationMillis > 0L) {
                val stepMillis = min(SILENCE_RECHECK_INTERVAL_MILLIS, remainingVibrationMillis)
                delay(stepMillis)
                remainingVibrationMillis -= stepMillis
                if (cancelIfSilenceActive(
                        eventId = eventId,
                        autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
                        autoSilenceConflictPrayer = autoSilenceConflictPrayer,
                        liftAutoSilence = true,
                    )
                ) return@launch
            }

            notificationManager.notify(NOTIFICATION_ID, buildRingingNotification(eventId))
            if (!audioController.startRingtoneRamp(
                    ringtonePreset = ringtonePreset,
                    customRingtoneUri = customRingtoneUri,
                    rampDurationMillis = AlarmAudioController.LONG_RAMP_MILLIS,
                    maximizeAlarmVolume = true,
                )
            ) {
                audioController.startVibrationOnly(strong = true)
            }

            while (isActive) {
                delay(SILENCE_RECHECK_INTERVAL_MILLIS)
                if (cancelIfSilenceActive(
                        eventId = eventId,
                        autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
                        autoSilenceConflictPrayer = autoSilenceConflictPrayer,
                        liftAutoSilence = true,
                    )
                ) return@launch
            }
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

    private fun buildRingingNotification(eventId: String): Notification =
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

    private fun stopPlayback() {
        audioController.stop()
    }

    private fun cancelIfSilenceActive(
        eventId: String,
        autoSilenceOverrideAllowed: Boolean,
        autoSilenceConflictPrayer: Prayer?,
        liftAutoSilence: Boolean,
    ): Boolean {
        val shouldCancel = if (liftAutoSilence) {
            !AwakeCheckSilencePolicy.prepareForEscalation(
                context = this,
                autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
                autoSilenceConflictPrayer = autoSilenceConflictPrayer,
            )
        } else {
            AwakeCheckSilencePolicy.shouldCancelBeforeStart(
                context = this,
                autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
            )
        }
        if (!shouldCancel) return false

        Log.d(TAG, "Awake check suppressed during app-controlled silence eventId=$eventId")
        AwakeCheckScheduler.cancel(this, eventId)
        stopPlayback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return true
    }

    private fun createChannel() {
        AlarmAudioController.createSilentAlarmChannel(
            context = this,
            channelId = CHANNEL_ID,
            name = getString(R.string.awake_check_channel_name),
            description = getString(R.string.awake_check_channel_description),
        )
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

    companion object {
        private const val CHANNEL_ID = "tunisianprayertimes.awake.check.silent"
        private const val NOTIFICATION_ID = 0x7A0E
        private const val TAG = "AwakeCheckService"

        const val ACTION_AWAKE_CHECK_CONFIRMED =
            "com.tunisianprayertimes.action.AWAKE_CHECK_CONFIRMED"

        private const val QUIET_WAIT_MILLIS = 60_000L
        private const val SILENCE_RECHECK_INTERVAL_MILLIS = 5_000L

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
            autoSilenceOverrideAllowed: Boolean = false,
            autoSilenceConflictPrayer: Prayer? = null,
        ): Intent = Intent(context, AwakeCheckService::class.java).apply {
            putExtra(EXTRA_EVENT_ID, eventId)
            ringtonePreset?.let { putExtra(EXTRA_RINGTONE, it.name) }
            customRingtoneUri?.let { putExtra(EXTRA_CUSTOM_RINGTONE_URI, it) }
            putExtra(EXTRA_AUTO_SILENCE_OVERRIDE_ALLOWED, autoSilenceOverrideAllowed)
            autoSilenceConflictPrayer?.let { putExtra(EXTRA_AUTO_SILENCE_CONFLICT_PRAYER, it.name) }
        }
    }
}
