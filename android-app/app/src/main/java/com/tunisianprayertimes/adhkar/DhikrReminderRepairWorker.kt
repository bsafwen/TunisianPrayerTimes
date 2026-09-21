package com.tunisianprayertimes.adhkar

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class DhikrReminderRepairWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        DhikrReminderScheduler.refresh(applicationContext, rearm = true)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
