package com.tunisianprayertimes

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager worker that re-verifies and re-schedules silence alarms.
 * Acts as a safety net in case AlarmManager alarms are killed by OEM battery optimization.
 */
class SilenceVerifyWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SilenceVerifyWorker"
        private const val WORK_NAME = "silence_verify"

        fun enqueue(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<SilenceVerifyWorker>(1, TimeUnit.HOURS)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "WorkManager not initialized, skipping enqueue")
            }
        }

        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "WorkManager not initialized, skipping cancel")
            }
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Verifying silence alarms")
        if (PrefsManager.isEnabled(applicationContext)) {
            SilenceScheduler.scheduleAll(applicationContext)
        }
        return Result.success()
    }
}
