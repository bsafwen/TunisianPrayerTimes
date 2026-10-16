package com.tunisianprayertimes.wake

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.tunisianprayertimes.MainActivityPendingIntents
import com.tunisianprayertimes.Prayer
import java.util.concurrent.TimeUnit

internal object AwakeCheckScheduler {
    private const val TAG = "AwakeCheckScheduler"
    private const val PREFS = "awake_check_occurrences"
    private const val CANCELLED_TRIGGER_PREFIX = "cancelled:"
    private const val HANDLED_TRIGGER_PREFIX = "handled:"
    private const val HANDLED_AT_PREFIX = "handledAt:"

    /** How late a check may still start: Android can deliver its alarm after its time. */
    private val LATE_START_GRACE_MILLIS = TimeUnit.MINUTES.toMillis(10)

    /** Once the receiver has taken a check, the service reports it; this covers the moment before it starts. */
    private val START_BRIDGE_MILLIS = TimeUnit.SECONDS.toMillis(30)

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
                // An alarm clock, like the wake alarm itself: OxygenOS delivered setExactAndAllowWhileIdle
                // checks up to 75% of their delay late (2m15s on a 3-minute check).
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAtMillis, MainActivityPendingIntents.awakeCheck(context)),
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
                putLong(scheduledAtKey(resolvedEventId), System.currentTimeMillis())
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

    /** When the check was set, i.e. when the last ring was stopped. */
    @Synchronized
    fun scheduledAtMillis(context: Context, eventId: String): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(scheduledAtKey(eventId), 0L)
            .takeIf { it > 0L }

    /**
     * A stored check that will still start: its time is ahead, or it is late by less than [LATE_START_GRACE_MILLIS]
     * and the receiver has not taken it yet (or took it a moment ago and the service is starting). A check set before
     * the last boot lost its alarm, and one the receiver took and dropped, or whose process died, never starts.
     */
    @Synchronized
    fun isPending(context: Context, eventId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val triggerAtMillis = prefs.getLong(triggerKey(eventId), 0L).takeIf { it > 0L } ?: return false
        val scheduledAtMillis = prefs.getLong(scheduledAtKey(eventId), 0L)
        val bootedAtMillis = nowMillis - SystemClock.elapsedRealtime()
        if (scheduledAtMillis in 1 until bootedAtMillis) return false
        if (triggerAtMillis > nowMillis) return true
        if (triggerAtMillis <= nowMillis - LATE_START_GRACE_MILLIS) return false
        if (prefs.getLong(HANDLED_TRIGGER_PREFIX + eventId, 0L) != triggerAtMillis) return true
        return nowMillis - prefs.getLong(HANDLED_AT_PREFIX + eventId, 0L) < START_BRIDGE_MILLIS
    }

    /** The receiver took the check due at [triggerAtMillis]: from now on the service, not the schedule, reports it. */
    @Synchronized
    fun markHandled(context: Context, eventId: String, triggerAtMillis: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(HANDLED_TRIGGER_PREFIX + eventId, triggerAtMillis)
            .putLong(HANDLED_AT_PREFIX + eventId, System.currentTimeMillis())
            .apply()
    }

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
            .remove(scheduledAtKey(resolvedEventId))
            .remove(HANDLED_TRIGGER_PREFIX + resolvedEventId)
            .remove(HANDLED_AT_PREFIX + resolvedEventId)
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

    private fun scheduledAtKey(eventId: String): String = "$eventId:scheduledAt"
}
