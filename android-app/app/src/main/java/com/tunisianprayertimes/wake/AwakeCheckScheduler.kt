package com.tunisianprayertimes.wake

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

internal object AwakeCheckScheduler {
    private const val TAG = "AwakeCheckScheduler"

    fun schedule(
        context: Context,
        eventId: String?,
        delayMinutes: Int,
        ringtonePresetName: String?,
        customRingtoneUri: String?,
    ): Boolean {
        val resolvedEventId = eventId?.takeIf { it.isNotBlank() } ?: return false
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAtMillis = System.currentTimeMillis() + delayMinutes * 60_000L
        val pendingIntent = pendingIntent(
            context = context,
            eventId = resolvedEventId,
            ringtonePresetName = ringtonePresetName,
            customRingtoneUri = customRingtoneUri,
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
                ringtonePresetName = null,
                customRingtoneUri = null,
                flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    private fun pendingIntent(
        context: Context,
        eventId: String,
        ringtonePresetName: String?,
        customRingtoneUri: String?,
        flags: Int,
    ): PendingIntent {
        val intent = Intent(context, AwakeCheckReceiver::class.java)
            .setAction(AwakeCheckReceiver.ACTION_START_AWAKE_CHECK)
            .setData(wakeEventUri("awake-check:$eventId"))
            .putExtra(EXTRA_EVENT_ID, eventId)
            .apply {
                ringtonePresetName?.let { putExtra(EXTRA_RINGTONE, it) }
                customRingtoneUri?.let { putExtra(EXTRA_CUSTOM_RINGTONE_URI, it) }
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
