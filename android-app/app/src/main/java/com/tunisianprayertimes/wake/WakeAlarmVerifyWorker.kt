package com.tunisianprayertimes.wake

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tunisianprayertimes.AnalyticsTracker

/** Legacy WorkManager verifier retained to drain old hourly work after app update. */
class WakeAlarmVerifyWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Verifying wake alarms")
        val hasWakeAlarms = WakeAlarmScheduler.hasEnabledWakeAlarms(applicationContext)
        val result = if (hasWakeAlarms) "success" else "disabled"

        val workerResult = if (hasWakeAlarms) {
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
        cancel(applicationContext)
        return workerResult
    }

    companion object {
        private const val TAG = "WakeAlarmVerifyWorker"
        private const val WORK_NAME = "wake_alarm_verify"

        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (error: IllegalStateException) {
                Log.w(TAG, "WorkManager not initialized, skipping cancel", error)
            }
        }
    }
}