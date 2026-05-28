package com.tunisianprayertimes.wake

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tunisianprayertimes.MainActivity
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class WakePlaybackService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioController by lazy { AlarmAudioController(this, serviceScope) }

    override fun onBind(intent: Intent?): IBinder? = null

    private var currentNotificationId: Int? = null
    private var currentEventId: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d(
            "WakeFlow",
            "Service.onStartCommand action=${intent?.action} eventId=${intent?.wakeEventId()} startId=$startId",
        )
        // Action-based commands (sent by the activity after advancing the queue).
        when (intent?.action) {
            ACTION_REFRESH_FOR_CURRENT -> {
                refreshForCurrent()
                return START_NOT_STICKY
            }
            ACTION_STOP_PLAYBACK -> {
                stopPlayback()
                WakeAlarmScheduler.resumeSilenceUntilNextAlarm(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val payload = WakeAutoSilenceConflictController.withRuntimeConflictIfNeeded(
            context = this,
            payload = intent?.toWakeTriggerPayload() ?: return START_NOT_STICKY,
        )
        val liftedAutoSilence = WakeAutoSilenceConflictController.liftAutoSilenceIfNeeded(this, payload)
        if (SilenceStatus.isAppControlledSilenceActive(this) && !liftedAutoSilence) {
            android.util.Log.d("WakeFlow", "Service suppressed during app-controlled silence eventId=${payload.eventId}")
            if (WakeAlarmQueueHolder.queue.current == null) {
                stopPlayback()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        createChannel()

        // Queue-aware: hand the payload to the process-global queue first.
        // Only refresh notification + playback + activity if it became the
        // active alarm. If queued, keep showing the existing alarm.
        val result = WakeAlarmQueueHolder.queue.handleIncoming(payload)

        when (result) {
            IncomingResult.BECAME_CURRENT -> {
                presentCurrent()
                launchActivityForCurrent()
            }
            IncomingResult.QUEUED, IncomingResult.REJECTED_DUPLICATE -> {
                // Make sure we are still foregrounded with whatever is current.
                // (The notification might have been cancelled if the activity
                // briefly fell behind; this re-asserts service state.)
                if (currentNotificationId == null) presentCurrent()
            }
        }

        return START_NOT_STICKY
    }

    private fun presentCurrent() {
        val current = WakeAlarmQueueHolder.queue.current ?: return
        val newNotifId = notificationIdFor(current.eventId)
        android.util.Log.d(
            "WakeFlow",
            "Service.presentCurrent eventId=${current.eventId} newNotifId=$newNotifId currentNotifId=$currentNotificationId currentEventId=$currentEventId",
        )

        // Cancel the previous notification if it has a different ID
        // so it doesn't linger in the notification shade.
        currentNotificationId?.let { oldId ->
            if (oldId != newNotifId) {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(oldId)
            }
        }
        currentNotificationId = newNotifId

        startForeground(newNotifId, buildNotification(current))

        // Only restart playback if the alarm being presented changed —
        // avoid stop/start churn when the queue did not advance.
        if (currentEventId != current.eventId) {
            currentEventId = current.eventId
            startPlayback(current)
        }
    }

    /**
     * Re-evaluate state after the activity advances or dismisses an alarm.
     * - If the queue still has a current alarm: refresh notif + playback for it
     *   and re-launch the activity (so a queued challenge is immediately shown).
     * - If the queue is empty: stop the service entirely.
     */
    private fun refreshForCurrent() {
        val current = WakeAlarmQueueHolder.queue.current
        android.util.Log.d("WakeFlow", "Service.refreshForCurrent current=${current?.eventId}")
        if (current == null) {
            currentNotificationId?.let { oldId ->
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(oldId)
            }
            currentNotificationId = null
            currentEventId = null
            stopPlayback()
            WakeAlarmScheduler.resumeSilenceUntilNextAlarm(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        presentCurrent()
        launchActivityForCurrent()
    }

    private fun launchActivityForCurrent() {
        val current = WakeAlarmQueueHolder.queue.current ?: return
        android.util.Log.d("WakeFlow", "Service.launchActivityForCurrent eventId=${current.eventId}")
        // Explicitly deliver the payload to WakeAlertActivity. The notification's
        // fullScreenIntent only fires on initial display; we must explicitly
        // re-launch the activity to surface a newly-current payload.
        val activityIntent = Intent(this, WakeAlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtras(
                Intent().populateWakeTriggerPayload(
                    eventId = current.eventId,
                    prayer = current.prayer,
                    effectivePrayer = current.effectivePrayer,
                    mainAlarmMode = current.mainAlarmMode,
                    hour = current.hour,
                    minute = current.minute,
                    ringtone = current.ringtone,
                    customRingtoneUri = current.customRingtoneUri,
                    vibrationOnly = current.vibrationOnly,
                    wakeUpCheckEnabled = current.wakeUpCheckEnabled,
                    wakeUpCheckType = current.wakeUpCheckType,
                    wakeUpCheckDifficulty = current.wakeUpCheckDifficulty,
                    whackAMoleKillTarget = current.whackAMoleKillTarget,
                    wakeUpCheckSteps = current.wakeUpCheckSteps,
                    progressiveVolume = current.progressiveVolume,
                    snoreTrackingEnabled = current.snoreTrackingEnabled,
                    useAutoSilenceConflictPlayback = current.useAutoSilenceConflictPlayback,
                    autoSilenceConflictPrayer = current.autoSilenceConflictPrayer,
                    awakeCheckEnabled = current.awakeCheckEnabled,
                    awakeCheckDelayMinutes = current.awakeCheckDelayMinutes,
                    wakeUpCheckChallenge = current.wakeUpCheckChallenge,
                    wakeUpCheckSeed = current.wakeUpCheckSeed,
                    isSubAlarm = current.isSubAlarm,
                    subAlarmId = current.subAlarmId,
                    offsetMinutes = current.offsetMinutes,
                    offsetDirection = current.offsetDirection,
                ),
            )
        startActivity(activityIntent)
    }

    override fun onDestroy() {
        android.util.Log.d("WakeFlow", "Service.onDestroy")
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
            .setSilent(true)
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
        val liftedAutoSilence = WakeAutoSilenceConflictController.liftAutoSilenceIfNeeded(this, payload)
        if (SilenceStatus.isAppControlledSilenceActive(this) && !liftedAutoSilence) {
            android.util.Log.d("WakeFlow", "Playback suppressed during app-controlled silence eventId=${payload.eventId}")
            WakeAlarmQueueHolder.queue.clear()
            stopPlayback()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        audioController.startWakeAlarm(payload)
    }

    private fun stopPlayback() {
        audioController.stop()
    }

    private fun createChannel() {
        AlarmAudioController.createSilentAlarmChannel(
            context = this,
            channelId = CHANNEL_ID,
            name = getString(R.string.wake_alarm_channel_name),
            description = getString(R.string.wake_alarm_channel_description),
        )
    }

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
                    useAutoSilenceConflictPlayback = payload.useAutoSilenceConflictPlayback,
                    autoSilenceConflictPrayer = payload.autoSilenceConflictPrayer,
                    awakeCheckEnabled = payload.awakeCheckEnabled,
                    awakeCheckDelayMinutes = payload.awakeCheckDelayMinutes,
                    wakeUpCheckChallenge = payload.wakeUpCheckChallenge,
                    wakeUpCheckSeed = payload.wakeUpCheckSeed,
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
            .populateWakeTriggerPayload(
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
                useAutoSilenceConflictPlayback = payload.useAutoSilenceConflictPlayback,
                autoSilenceConflictPrayer = payload.autoSilenceConflictPrayer,
                awakeCheckEnabled = payload.awakeCheckEnabled,
                awakeCheckDelayMinutes = payload.awakeCheckDelayMinutes,
                wakeUpCheckChallenge = payload.wakeUpCheckChallenge,
                wakeUpCheckSeed = payload.wakeUpCheckSeed,
                isSubAlarm = payload.isSubAlarm,
                subAlarmId = payload.subAlarmId,
                offsetMinutes = payload.offsetMinutes,
                offsetDirection = payload.offsetDirection,
            )
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

    companion object {
        private const val CHANNEL_ID = "tunisianprayertimes.wake.playback.silent"

        const val ACTION_REFRESH_FOR_CURRENT =
            "com.tunisianprayertimes.action.REFRESH_FOR_CURRENT"
        const val ACTION_STOP_PLAYBACK =
            "com.tunisianprayertimes.action.STOP_PLAYBACK"

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
                    useAutoSilenceConflictPlayback = payload.useAutoSilenceConflictPlayback,
                    autoSilenceConflictPrayer = payload.autoSilenceConflictPrayer,
                    awakeCheckEnabled = payload.awakeCheckEnabled,
                    awakeCheckDelayMinutes = payload.awakeCheckDelayMinutes,
                    wakeUpCheckChallenge = payload.wakeUpCheckChallenge,
                    wakeUpCheckSeed = payload.wakeUpCheckSeed,
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
