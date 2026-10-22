package com.tunisianprayertimes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("BootReceiver", "Received action: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED -> {
                Log.d("BootReceiver", "Refreshing schedules after ${sourceForAction(action)}")

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    var refreshResult = "success"
                    try {
                        // The scheduler's codes before 6.9's day slots, on every update, before the reschedule.
                        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) SilenceScheduler.cancelLegacyAlarms(context)
                        // 6.8: a new time zone may mean a trip; with auto-location on, a fix goes through the travel rule first.
                        if (action == Intent.ACTION_TIMEZONE_CHANGED) {
                            runCatching { TravelPlaceWorker.checkAfterZoneChange(context) }
                                .onFailure { Log.w("BootReceiver", "Travel check failed", it) }
                        }
                        refreshResult = refreshSchedules(context)
                    } catch (error: Exception) {
                        refreshResult = "failure"
                        Log.w("BootReceiver", "Failed to refresh schedules", error)
                    } finally {
                        AnalyticsTracker.scheduleRefreshResult(
                            context = context,
                            source = sourceForAction(action),
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

    internal suspend fun refreshSchedules(context: Context): String {
        // First, so that syncAll schedules from the state left after an expired manual silence ends.
        runCatching { ManualSilenceScheduler.restoreTimer(context) }
            .onFailure { Log.w("BootReceiver", "Failed to restore the manual silence timer", it) }
        runCatching { com.tunisianprayertimes.quran.hifz.HifzReminder.refresh(context) }
            .onFailure { Log.w("BootReceiver", "Failed to refresh the hifz reminder", it) }
        runCatching { com.tunisianprayertimes.fasting.FastingReminder.refresh(context) }
            .onFailure { Log.w("BootReceiver", "Failed to refresh the fasting reminder", it) }
        AnalyticsTracker.installRamadanOverrideReporter(context)
        RamadanOverrideChecker.startPollingIfNeeded()
        return ScheduleRefreshCoordinator.syncAll(context).analyticsResult
    }

    private fun sourceForAction(action: String): String = when (action) {
        Intent.ACTION_BOOT_COMPLETED -> "boot"
        Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
        Intent.ACTION_TIME_CHANGED -> "time_changed"
        Intent.ACTION_TIMEZONE_CHANGED -> "timezone_changed"
        Intent.ACTION_DATE_CHANGED -> "date_changed"
        ACTION_EXACT_ALARM_PERMISSION_CHANGED -> "exact_alarm_permission_changed"
        else -> "system_broadcast"
    }

    companion object {
        private const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
