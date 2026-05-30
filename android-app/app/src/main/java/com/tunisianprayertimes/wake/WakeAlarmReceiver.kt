package com.tunisianprayertimes.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.ManualSilenceScheduler
import com.tunisianprayertimes.SilenceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val triggerPayload = intent.toWakeTriggerPayload() ?: return
        ManualSilenceScheduler.syncExpiredTimer(context)

        val payload = WakeAutoSilenceConflictController.withRuntimeConflictIfNeeded(
            context = context,
            payload = triggerPayload,
        )
        Log.d("WakeFlow", "WakeAlarmReceiver.onReceive eventId=${payload.eventId}")
        AnalyticsTracker.wakeAlarmFired(context, payload)

        // If this alarm had silence activated, restore audio state before playing the wake-up alarm
        val alarmId = wakeAlarmIdFromEventId(payload.eventId)
        if (alarmId != null && WakeAlarmScheduler.isSilencedAlarm(context, alarmId)) {
            Log.d("WakeFlow", "Silenced alarm fired — restoring audio state")
            WakeAlarmScheduler.releaseSilenceForRingingAlarm(context, alarmId)
        }

        val liftedAutoSilence = WakeAutoSilenceConflictController.liftAutoSilenceIfNeeded(context, payload)
        if (SilenceStatus.isAppControlledSilenceActive(context) && !liftedAutoSilence) {
            Log.d("WakeFlow", "Wake alarm suppressed during app-controlled silence eventId=${payload.eventId}")
        } else {
            ContextCompat.startForegroundService(
                context,
                WakePlaybackService.playbackIntent(context, payload),
            )
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            var result = "success"
            try {
                WakeAlarmScheduler.scheduleAll(context)
            } catch (error: Exception) {
                result = "failure"
                Log.w(TAG, "Failed to reschedule wake alarms after trigger", error)
            } finally {
                AnalyticsTracker.scheduleRefreshResult(
                    context = context,
                    source = "wake_alarm_fired",
                    result = result,
                    silencePrayerCount = AnalyticsTracker.silencePrayerCount(context),
                    wakeAlarmCount = AnalyticsTracker.enabledWakeAlarmCount(context),
                )
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "WakeAlarmReceiver"
    }
}