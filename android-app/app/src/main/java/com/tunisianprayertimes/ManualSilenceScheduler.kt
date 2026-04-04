package com.tunisianprayertimes

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object ManualSilenceScheduler {
    const val ACTION_MANUAL_UNSILENCE = "com.tunisianprayertimes.ACTION_MANUAL_UNSILENCE"

    private const val TAG = "ManualSilenceScheduler"
    private const val REQUEST_CODE_MANUAL_UNSILENCE = 10_001

    fun schedule(context: Context, durationMinutes: Int): Long {
        val safeMinutes = durationMinutes.coerceAtLeast(1)
        val endsAtMillis = System.currentTimeMillis() + safeMinutes * 60_000L
        scheduleAt(context, endsAtMillis)
        return endsAtMillis
    }

    fun scheduleAt(context: Context, endsAtMillis: Long) {
        cancel(context)
        PrefsManager.setManualSilenceEndsAtMillis(context, endsAtMillis)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createPendingIntent(context)

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms() -> {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtMillis, pendingIntent)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtMillis, pendingIntent)
            }
            else -> {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, endsAtMillis, pendingIntent)
            }
        }

        Log.d(TAG, "Scheduled manual unsilence at $endsAtMillis")
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(createPendingIntent(context))
        PrefsManager.clearManualSilenceEndsAt(context)
    }

    fun syncExpiredTimer(context: Context): Boolean {
        val endsAtMillis = PrefsManager.getManualSilenceEndsAtMillis(context)
        if (!PrefsManager.isManualSilenceActive(context) || endsAtMillis <= 0L) {
            return false
        }
        if (System.currentTimeMillis() < endsAtMillis) {
            return false
        }

        onTimerExpired(context)
        return true
    }

    fun onTimerExpired(context: Context) {
        cancel(context)
        if (!PrefsManager.isManualSilenceActive(context)) {
            return
        }

        Log.d(TAG, "Manual silence timer expired")
        SilenceModeController.setManualNormal(context)
        resumeAutoSilenceIfPossible(context)
    }

    private fun resumeAutoSilenceIfPossible(context: Context) {
        if (!PrefsManager.isEnabled(context) || !hasExactAlarmPermission(context)) {
            return
        }

        SilenceScheduler.scheduleAll(context)
    }

    private fun createPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SilenceReceiver::class.java).apply {
            action = ACTION_MANUAL_UNSILENCE
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_MANUAL_UNSILENCE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun hasExactAlarmPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return alarmManager.canScheduleExactAlarms()
        }
        return true
    }
}