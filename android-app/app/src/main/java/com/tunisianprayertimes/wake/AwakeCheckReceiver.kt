package com.tunisianprayertimes.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tunisianprayertimes.ManualSilenceScheduler
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.isSkippingWakeOccurrence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives the "Yes, I'm awake" confirmation and stops the AwakeCheckService.
 * Also receives the delayed alarm to start the awake check after alarm dismissal.
 */
class AwakeCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AwakeCheckService.ACTION_AWAKE_CHECK_CONFIRMED -> {
                val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
                AwakeCheckService.confirmRunningFromNotification(
                    context,
                    eventId,
                    intent.getLongExtra(EXTRA_AWAKE_CHECK_TRIGGER_AT_MILLIS, 0L),
                )
            }

            ACTION_START_AWAKE_CHECK -> {
                val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
                val appContext = context.applicationContext
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val scheduledTriggerAtMillis = intent.getLongExtra(
                            EXTRA_AWAKE_CHECK_TRIGGER_AT_MILLIS,
                            0L,
                        ).takeIf { millis -> millis > 0L }
                        if (AwakeCheckScheduler.wasCancelled(appContext, eventId, scheduledTriggerAtMillis)) {
                            return@launch
                        }
                        if (scheduledTriggerAtMillis != null &&
                            AwakeCheckScheduler.triggerAtMillis(appContext, eventId)?.let { stored ->
                                stored != scheduledTriggerAtMillis
                            } == true
                        ) return@launch
                        val alarmId = wakeAlarmIdFromEventId(eventId)
                        val config = alarmId?.let { PrayerWakeRepository(appContext).getWakeAlarm(it) }
                        val checkTriggerAtMillis = intent.getLongExtra(EXTRA_WAKE_TRIGGER_AT_MILLIS, 0L)
                            .takeIf { millis -> millis > 0L }
                            ?: intent.getLongExtra(EXTRA_AWAKE_CHECK_TRIGGER_AT_MILLIS, 0L)
                                .takeIf { millis -> millis > 0L }
                                ?: System.currentTimeMillis()
                        val occurrenceAtMillis = intent.getLongExtra(EXTRA_WAKE_OCCURRENCE_AT_MILLIS, 0L)
                            .takeIf { millis -> millis > 0L }
                            ?: AwakeCheckScheduler.occurrenceAtMillis(appContext, eventId)
                            ?: checkTriggerAtMillis
                        // Completed one-off alarms are removed before their awake check runs.
                        // A stored disabled parent still invalidates an already-delivered check.
                        if (config != null && !config.enabled) {
                            AwakeCheckScheduler.cancel(appContext, eventId)
                            return@launch
                        }
                        if (alarmId != null && (
                                WakeOccurrenceSkipRegistry.contains(alarmId, occurrenceAtMillis) ||
                                    config?.isSkippingWakeOccurrence(occurrenceAtMillis) == true
                            )
                        ) {
                            AwakeCheckScheduler.cancelIfMatching(
                                appContext, eventId, occurrenceAtMillis, scheduledTriggerAtMillis,
                            )
                            return@launch
                        }
                        startAwakeCheck(appContext, intent, eventId, occurrenceAtMillis)
                    } catch (error: Exception) {
                        Log.w(TAG, "Failed to start awake check eventId=$eventId", error)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private fun startAwakeCheck(context: Context, intent: Intent, eventId: String, occurrenceAtMillis: Long) {
        ManualSilenceScheduler.syncExpiredTimer(context)
        val autoSilenceOverrideAllowed = intent.getBooleanExtra(
            EXTRA_AUTO_SILENCE_OVERRIDE_ALLOWED,
            false,
        )
        val autoSilenceConflictPrayer = intent.getStringExtra(EXTRA_AUTO_SILENCE_CONFLICT_PRAYER)
            ?.let { rawPrayer -> runCatching { Prayer.valueOf(rawPrayer) }.getOrNull() }
        val scheduledTriggerAtMillis = intent.getLongExtra(EXTRA_AWAKE_CHECK_TRIGGER_AT_MILLIS, 0L)
            .takeIf { millis -> millis > 0L }

        if (AwakeCheckSilencePolicy.shouldCancelBeforeStart(context, autoSilenceOverrideAllowed, scheduledTriggerAtMillis)) {
            Log.d(TAG, "Awake check suppressed during app-controlled silence eventId=$eventId")
            AwakeCheckScheduler.cancelIfMatching(
                context, eventId, occurrenceAtMillis, scheduledTriggerAtMillis,
            )
            return
        }

        val ringtonePresetName = intent.getStringExtra(EXTRA_RINGTONE)
        val customRingtoneUri = intent.getStringExtra(EXTRA_CUSTOM_RINGTONE_URI)

        val ringtonePreset = ringtonePresetName?.let { name ->
            runCatching { com.tunisianprayertimes.RingtonePreset.valueOf(name) }.getOrNull()
        }

        val serviceIntent = AwakeCheckService.intent(
            context = context,
            eventId = eventId,
            ringtonePreset = ringtonePreset,
            customRingtoneUri = customRingtoneUri,
            autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
            autoSilenceConflictPrayer = autoSilenceConflictPrayer,
            scheduledTriggerAtMillis = scheduledTriggerAtMillis,
            occurrenceAtMillis = occurrenceAtMillis,
        )
        context.startForegroundService(serviceIntent)
    }

    companion object {
        private const val TAG = "AwakeCheckReceiver"

        const val ACTION_START_AWAKE_CHECK =
            "com.tunisianprayertimes.action.START_AWAKE_CHECK"
    }
}
