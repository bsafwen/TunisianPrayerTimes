package com.tunisianprayertimes.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

actual object TimerScheduler {
    private var appContext: Context? = null
    private var onExpiredCallback: (() -> Unit)? = null

    private const val ACTION_TIMER_EXPIRED = "com.tunisianprayertimes.shared.ACTION_TIMER_EXPIRED"
    private const val REQUEST_CODE = 20_001

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context =
        appContext ?: error("TimerScheduler not initialized. Call init(context) first.")

    actual fun scheduleAfter(durationMinutes: Int, onExpired: () -> Unit): Long {
        cancel()
        onExpiredCallback = onExpired
        val safeMinutes = durationMinutes.coerceAtLeast(1)
        val endsAtMillis = System.currentTimeMillis() + safeMinutes * 60_000L

        val ctx = requireContext()
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createPendingIntent(ctx)

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

        return endsAtMillis
    }

    actual fun cancel() {
        val ctx = appContext ?: return
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(createPendingIntent(ctx))
        onExpiredCallback = null
    }

    internal fun onAlarmFired() {
        onExpiredCallback?.invoke()
        onExpiredCallback = null
    }

    private fun createPendingIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_TIMER_EXPIRED).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    class TimerReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == ACTION_TIMER_EXPIRED) {
                onAlarmFired()
            }
        }
    }
}
