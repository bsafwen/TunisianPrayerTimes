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

    fun schedule(
        context: Context,
        eventId: String?,
        delayMinutes: Int,
        ringtonePresetName: String?,
        customRingtoneUri: String?,
        autoSilenceOverrideAllowed: Boolean = false,
        autoSilenceConflictPrayer: Prayer? = null,
    ): Boolean {
        val resolvedEventId = eventId?.takeIf { it.isNotBlank() } ?: return false
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAtMillis = System.currentTimeMillis() + delayMinutes * 60_000L
        val pendingIntent = pendingIntent(
            context = context,
            eventId = resolvedEventId,
            triggerAtMillis = triggerAtMillis,
            ringtonePresetName = ringtonePresetName,
            customRingtoneUri = customRingtoneUri,
            autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
            autoSilenceConflictPrayer = autoSilenceConflictPrayer,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        return try {
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
    }

    fun cancel(context: Context, eventId: String?) {
        val resolvedEventId = eventId?.takeIf { it.isNotBlank() } ?: return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(
            pendingIntent(
                context = context,
                eventId = resolvedEventId,
                triggerAtMillis = null,
                ringtonePresetName = null,
                customRingtoneUri = null,
                autoSilenceOverrideAllowed = false,
                autoSilenceConflictPrayer = null,
                flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    private fun pendingIntent(
        context: Context,
        eventId: String,
        triggerAtMillis: Long?,
        ringtonePresetName: String?,
        customRingtoneUri: String?,
        autoSilenceOverrideAllowed: Boolean,
        autoSilenceConflictPrayer: Prayer?,
        flags: Int,
    ): PendingIntent {
        val intent = Intent(context, AwakeCheckReceiver::class.java)
            .setAction(AwakeCheckReceiver.ACTION_START_AWAKE_CHECK)
            .setData(wakeEventUri("awake-check:$eventId"))
            .putExtra(EXTRA_EVENT_ID, eventId)
            .putExtra(EXTRA_AUTO_SILENCE_OVERRIDE_ALLOWED, autoSilenceOverrideAllowed)
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
}
