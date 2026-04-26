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
                RamadanOverrideChecker.startPollingIfNeeded()
                SilenceScheduler.scheduleAll(context)
                if (PrefsManager.isEnabled(context)) {
                    SilenceVerifyWorker.enqueue(context)
                    SilenceGuardService.start(context)
                }

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (WakeAlarmScheduler.hasEnabledWakeAlarms(context)) {
                            WakeAlarmVerifyWorker.enqueue(context)
                            WakeAlarmScheduler.scheduleAll(context)
                        } else {
                            WakeAlarmScheduler.cancelAll(context)
                            WakeAlarmVerifyWorker.cancel(context)
                        }
                    } catch (error: Exception) {
                        Log.w("BootReceiver", "Failed to restore wake alarms", error)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
