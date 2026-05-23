package com.tunisianprayertimes.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tunisianprayertimes.AnalyticsTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WakeAlarmRepairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPAIR) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            var result = "success"
            try {
                if (WakeAlarmScheduler.hasEnabledWakeAlarms(context)) {
                    WakeAlarmScheduler.scheduleAll(context)
                } else {
                    result = "disabled"
                    WakeAlarmScheduler.cancelAll(context)
                }
            } catch (error: Exception) {
                result = "failure"
                Log.w(TAG, "Failed to repair wake alarm schedule", error)
            } finally {
                AnalyticsTracker.scheduleRefreshResult(
                    context = context,
                    source = "wake_repair_alarm",
                    result = result,
                    silencePrayerCount = AnalyticsTracker.silencePrayerCount(context),
                    wakeAlarmCount = AnalyticsTracker.enabledWakeAlarmCount(context),
                )
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPAIR = "com.tunisianprayertimes.wake.ACTION_REPAIR"
        private const val TAG = "WakeAlarmRepairReceiver"
    }
}