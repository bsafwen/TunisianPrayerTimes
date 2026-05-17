package com.tunisianprayertimes.wake

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tunisianprayertimes.AnalyticsTracker
import java.util.concurrent.TimeUnit

class WakeAlarmVerifyWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Verifying wake alarms")
        val hasWakeAlarms = WakeAlarmScheduler.hasEnabledWakeAlarms(applicationContext)
        val result = if (hasWakeAlarms) "success" else "disabled"

        return if (hasWakeAlarms) {
            WakeAlarmScheduler.scheduleAll(applicationContext)
            AnalyticsTracker.scheduleRefreshResult(
                context = applicationContext,
                source = "wake_worker",
                result = result,
                silencePrayerCount = AnalyticsTracker.silencePrayerCount(applicationContext),
                wakeAlarmCount = AnalyticsTracker.enabledWakeAlarmCount(applicationContext),
            )
            Result.success()
        } else {
            WakeAlarmScheduler.cancelAll(applicationContext)
            cancel(applicationContext)
            AnalyticsTracker.scheduleRefreshResult(
                context = applicationContext,
                source = "wake_worker",
                result = result,
                silencePrayerCount = AnalyticsTracker.silencePrayerCount(applicationContext),
                wakeAlarmCount = 0,
            )
            Result.success()
        }
    }

    companion object {
        private const val TAG = "WakeAlarmVerifyWorker"
        private const val WORK_NAME = "wake_alarm_verify"

        fun enqueue(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<WakeAlarmVerifyWorker>(1, TimeUnit.HOURS)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            } catch (error: IllegalStateException) {
                Log.w(TAG, "WorkManager not initialized, skipping enqueue", error)
            }
        }

        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (error: IllegalStateException) {
                Log.w(TAG, "WorkManager not initialized, skipping cancel", error)
            }
        }
    }
}