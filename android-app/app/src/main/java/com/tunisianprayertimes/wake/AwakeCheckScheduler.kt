package com.tunisianprayertimes.wake

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tunisianprayertimes.Prayer

internal object AwakeCheckScheduler {
    private const val TAG = "AwakeCheckScheduler"
    private const val PREFS = "awake_check_occurrences"
    private const val CANCELLED_TRIGGER_PREFIX = "cancelled:"

    @Synchronized
    fun schedule(
        context: Context,
        eventId: String?,
        delayMinutes: Int,
        ringtonePresetName: String?,
        customRingtoneUri: String?,
        autoSilenceOverrideAllowed: Boolean = false,
        autoSilenceConflictPrayer: Prayer? = null,
        wakeTriggerAtMillis: Long = 0L,
        occurrenceAtMillis: Long = 0L,
    ): Boolean {
        val resolvedEventId = eventId?.takeIf { it.isNotBlank() } ?: return false
        val alarmId = wakeAlarmIdFromEventId(resolvedEventId)
        if (alarmId != null && occurrenceAtMillis > 0L &&
            WakeOccurrenceSkipRegistry.contains(alarmId, occurrenceAtMillis)
        ) return false
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val previousTriggerAtMillis = triggerAtMillis(context, resolvedEventId) ?: 0L
        val cancelledTriggerAtMillis = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(CANCELLED_TRIGGER_PREFIX + resolvedEventId, 0L)
        var triggerAtMillis = System.currentTimeMillis() + delayMinutes * 60_000L
        while (triggerAtMillis == previousTriggerAtMillis || triggerAtMillis == cancelledTriggerAtMillis) {
            triggerAtMillis++
        }
        val pendingIntent = pendingIntent(
            context = context,
            eventId = resolvedEventId,
            triggerAtMillis = triggerAtMillis,
            ringtonePresetName = ringtonePresetName,
            customRingtoneUri = customRingtoneUri,
            autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
            autoSilenceConflictPrayer = autoSilenceConflictPrayer,
            wakeTriggerAtMillis = wakeTriggerAtMillis,
            occurrenceAtMillis = occurrenceAtMillis,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        val scheduled = try {
            if (canUseExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            }
            true
        } catch (error: SecurityException) {
            Log.w(TAG, "Exact alarm denied; falling back to inexact awake check", error)
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            }.isSuccess
        }
        if (scheduled) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (occurrenceAtMillis > 0L) putLong(resolvedEventId, occurrenceAtMillis)
                else remove(resolvedEventId)
                putLong(triggerKey(resolvedEventId), triggerAtMillis)
            }.apply()
            if (alarmId != null && occurrenceAtMillis > 0L &&
                WakeOccurrenceSkipRegistry.contains(alarmId, occurrenceAtMillis)
            ) {
                cancel(context, resolvedEventId)
                return false
            }
        }
        return scheduled
    }

    @Synchronized
    fun occurrenceAtMillis(context: Context, eventId: String): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(eventId, 0L)
            .takeIf { it > 0L }

    @Synchronized
    fun triggerAtMillis(context: Context, eventId: String): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(triggerKey(eventId), 0L)
            .takeIf { it > 0L }

    @Synchronized
    fun wasCancelled(context: Context, eventId: String, triggerAtMillis: Long?): Boolean =
        triggerAtMillis != null && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(CANCELLED_TRIGGER_PREFIX + eventId, 0L) == triggerAtMillis

    @Synchronized
    fun cancel(context: Context, eventId: String?) {
        val resolvedEventId = eventId?.takeIf { it.isNotBlank() } ?: return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AwakeCheckReceiver::class.java)
            .setAction(AwakeCheckReceiver.ACTION_START_AWAKE_CHECK)
            .setData(wakeEventUri("awake-check:$resolvedEventId"))
        PendingIntent.getBroadcast(
            context,
            requestCode(resolvedEventId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let(alarmManager::cancel)
        val scheduledTriggerAtMillis = triggerAtMillis(context, resolvedEventId)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(resolvedEventId)
            .remove(triggerKey(resolvedEventId))
            .apply {
                scheduledTriggerAtMillis?.let { putLong(CANCELLED_TRIGGER_PREFIX + resolvedEventId, it) }
            }
            .apply()
    }

    @Synchronized
    fun cancelIfMatching(
        context: Context,
        eventId: String,
        expectedOccurrenceAtMillis: Long,
        expectedTriggerAtMillis: Long?,
    ): Boolean {
        if (occurrenceAtMillis(context, eventId) != expectedOccurrenceAtMillis ||
            triggerAtMillis(context, eventId) != expectedTriggerAtMillis
        ) return false
        cancel(context, eventId)
        return true
    }

    private fun pendingIntent(
        context: Context,
        eventId: String,
        triggerAtMillis: Long?,
        ringtonePresetName: String?,
        customRingtoneUri: String?,
        autoSilenceOverrideAllowed: Boolean,
        autoSilenceConflictPrayer: Prayer?,
        wakeTriggerAtMillis: Long = 0L,
        occurrenceAtMillis: Long = 0L,
        flags: Int,
    ): PendingIntent {
        val intent = Intent(context, AwakeCheckReceiver::class.java)
            .setAction(AwakeCheckReceiver.ACTION_START_AWAKE_CHECK)
            .setData(wakeEventUri("awake-check:$eventId"))
            .putExtra(EXTRA_EVENT_ID, eventId)
            .putExtra(EXTRA_AUTO_SILENCE_OVERRIDE_ALLOWED, autoSilenceOverrideAllowed)
            .putExtra(EXTRA_WAKE_TRIGGER_AT_MILLIS, wakeTriggerAtMillis)
            .putExtra(EXTRA_WAKE_OCCURRENCE_AT_MILLIS, occurrenceAtMillis)
            .apply {
                triggerAtMillis?.let { putExtra(EXTRA_AWAKE_CHECK_TRIGGER_AT_MILLIS, it) }
                ringtonePresetName?.let { putExtra(EXTRA_RINGTONE, it) }
                customRingtoneUri?.let { putExtra(EXTRA_CUSTOM_RINGTONE_URI, it) }
                autoSilenceConflictPrayer?.let { putExtra(EXTRA_AUTO_SILENCE_CONFLICT_PRAYER, it.name) }
            }

        return PendingIntent.getBroadcast(
            context,
            requestCode(eventId),
            intent,
            flags,
        )
    }

    private fun requestCode(eventId: String): Int = "awake_check_schedule:$eventId".hashCode()

    private fun triggerKey(eventId: String): String = "$eventId:trigger"
}
