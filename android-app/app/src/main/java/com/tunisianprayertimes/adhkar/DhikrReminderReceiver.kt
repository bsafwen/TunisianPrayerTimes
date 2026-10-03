package com.tunisianprayertimes.adhkar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DhikrReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DhikrReminderScheduler.receive(app, intent)
            } catch (error: Exception) {
                Log.w("DhikrReminderReceiver", "Unable to handle dhikr reminder", error)
                try {
                    enqueueFailureRepair(app)
                } catch (repairError: Exception) {
                    Log.w("DhikrReminderReceiver", "Unable to queue dhikr reminder repair", repairError)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        internal const val FAILURE_REPAIR_WORK_NAME = "adhkar_failed_delivery_repair"

        internal fun enqueueFailureRepair(context: Context) {
            val request = OneTimeWorkRequestBuilder<DhikrReminderRepairWorker>()
                .setInitialDelay(15, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // Persist the recovery request before the broadcast releases the process.
            WorkManager.getInstance(context).enqueueUniqueWork(
                FAILURE_REPAIR_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            ).result.get(3, TimeUnit.SECONDS)
        }
    }
}
