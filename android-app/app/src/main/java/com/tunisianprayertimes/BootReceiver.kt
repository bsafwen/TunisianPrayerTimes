package com.tunisianprayertimes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import com.tunisianprayertimes.wake.WakeAlarmVerifyWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("BootReceiver", "Received action: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.MY_PACKAGE_REPLACED" -> {
                Log.d("BootReceiver", "Rescheduling alarms after boot/update")
                AnalyticsTracker.installRamadanOverrideReporter(context)
                RamadanOverrideChecker.startPollingIfNeeded()
                SilenceScheduler.scheduleAll(context)
                if (PrefsManager.isEnabled(context)) {
                    SilenceVerifyWorker.enqueue(context)
                    SilenceGuardService.start(context)
                }

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    var refreshResult = "success"
                    try {
                        if (WakeAlarmScheduler.hasEnabledWakeAlarms(context)) {
                            WakeAlarmVerifyWorker.enqueue(context)
                            WakeAlarmScheduler.scheduleAll(context)
                        } else {
                            refreshResult = "wake_disabled"
                            WakeAlarmScheduler.cancelAll(context)
                            WakeAlarmVerifyWorker.cancel(context)
                        }
                    } catch (error: Exception) {
                        refreshResult = "failure"
                        Log.w("BootReceiver", "Failed to restore wake alarms", error)
                    } finally {
                        AnalyticsTracker.scheduleRefreshResult(
                            context = context,
                            source = if (action == Intent.ACTION_BOOT_COMPLETED) "boot" else "package_replaced",
                            result = refreshResult,
                            silencePrayerCount = AnalyticsTracker.silencePrayerCount(context),
                            wakeAlarmCount = AnalyticsTracker.enabledWakeAlarmCount(context),
                        )
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
