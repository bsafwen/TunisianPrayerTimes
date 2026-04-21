package com.tunisianprayertimes.wake

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

const val ACTION_DISMISS_WAKE_ALERT = "com.tunisianprayertimes.action.DISMISS_WAKE_ALERT"

class WakeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP_WAKE_ALARM) {
            return
        }

        context.stopService(Intent(context, WakePlaybackService::class.java))

        val awakeCheckEnabled = intent.getBooleanExtra(EXTRA_AWAKE_CHECK_ENABLED, false)
        val delayMinutes = intent.getIntExtra(EXTRA_AWAKE_CHECK_DELAY_MINUTES, 7)

        if (awakeCheckEnabled) {
            scheduleAwakeCheck(
                context = context,
                eventId = intent.wakeEventId(),
                delayMinutes = delayMinutes,
                ringtonePresetName = intent.getStringExtra(EXTRA_RINGTONE),
                customRingtoneUri = intent.getStringExtra(EXTRA_CUSTOM_RINGTONE_URI),
            )
        }

        context.sendBroadcast(
            Intent(ACTION_DISMISS_WAKE_ALERT)
                .setPackage(context.packageName)
                .apply {
                    intent.wakeEventId()?.let { eventId ->
                        populateWakeStopPayload(eventId)
                    }
                },
        )
    }

    private fun scheduleAwakeCheck(
        context: Context,
        eventId: String?,
        delayMinutes: Int,
        ringtonePresetName: String?,
        customRingtoneUri: String?,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAtMillis = System.currentTimeMillis() + delayMinutes * 60_000L

        val intent = Intent(context, AwakeCheckReceiver::class.java)
            .setAction(AwakeCheckReceiver.ACTION_START_AWAKE_CHECK)
            .apply {
                eventId?.let { putExtra(EXTRA_EVENT_ID, it) }
                ringtonePresetName?.let { putExtra(EXTRA_RINGTONE, it) }
                customRingtoneUri?.let { putExtra(EXTRA_CUSTOM_RINGTONE_URI, it) }
            }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            "awake_check_schedule".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        try {
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
        } catch (e: SecurityException) {
            Log.w("WakeActionReceiver", "Exact alarm denied; falling back to inexact awake check", e)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        }
    }
}