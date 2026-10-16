package com.tunisianprayertimes

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.tunisianprayertimes.adhkar.DhikrReminderReceiver
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import com.tunisianprayertimes.wake.WakeAlarmVerifyWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ScheduleRefreshCoordinator {
    /** Runs the hifz refresh off the caller's thread: it reads the notebook and the prayer times. */
    private val hifzScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    const val RESULT_SUCCESS = "success"
    const val RESULT_DISABLED = "disabled"
    const val RESULT_PERMISSION_MISSING = "permission_missing"
    const val RESULT_MANUAL_ACTIVE = "manual_active"
    const val RESULT_WAKE_DISABLED = "wake_disabled"

    data class RefreshResult(
        val silenceResult: String,
        val wakeResult: String,
    ) {
        val analyticsResult: String
            get() = when {
                silenceResult == RESULT_PERMISSION_MISSING -> RESULT_PERMISSION_MISSING
                silenceResult == RESULT_DISABLED && wakeResult == RESULT_WAKE_DISABLED -> RESULT_DISABLED
                wakeResult == RESULT_WAKE_DISABLED -> silenceResult
                else -> silenceResult
            }
    }

    fun syncSilence(
        context: Context,
        skipWhileManualSilence: Boolean = false,
    ): String {
        val appContext = context.applicationContext
        // The hifz reminders after prayers come when the silences end: they move with them.
        hifzScope.launch {
            runCatching { com.tunisianprayertimes.quran.hifz.HifzReminder.refresh(appContext) }
                .onFailure { Log.w("ScheduleRefresh", "Could not restore hifz reminders", it) }
        }

        if (!PrefsManager.isEnabled(appContext)) {
            SilenceScheduler.cancelAll(appContext)
            SilenceVerifyWorker.cancel(appContext)
            SilenceGuardService.stop(appContext)
            return RESULT_DISABLED
        }

        if (!hasSilencePermissions(appContext)) {
            SilenceScheduler.cancelAll(appContext)
            SilenceVerifyWorker.cancel(appContext)
            SilenceGuardService.stop(appContext)
            return RESULT_PERMISSION_MISSING
        }

        if (skipWhileManualSilence && PrefsManager.isManualSilenceActive(appContext)) {
            SilenceGuardService.stopIfNotNeeded(appContext)
            return RESULT_MANUAL_ACTIVE
        }

        SilenceScheduler.scheduleAll(appContext)
        SilenceVerifyWorker.enqueue(appContext)
        SilenceGuardService.stopIfNotNeeded(appContext)

        return RESULT_SUCCESS
    }

    suspend fun syncWake(context: Context): String {
        val appContext = context.applicationContext
        return if (WakeAlarmScheduler.hasEnabledWakeAlarms(appContext)) {
            WakeAlarmScheduler.scheduleAll(appContext)
            WakeAlarmVerifyWorker.cancel(appContext)
            RESULT_SUCCESS
        } else {
            WakeAlarmScheduler.cancelAll(appContext)
            WakeAlarmVerifyWorker.cancel(appContext)
            RESULT_WAKE_DISABLED
        }
    }

    suspend fun syncAll(
        context: Context,
        skipSilenceWhileManualSilence: Boolean = false,
    ): RefreshResult {
        try {
            DhikrReminderScheduler.refresh(context.applicationContext, rearm = true)
        } catch (error: Exception) {
            Log.w("ScheduleRefresh", "Could not restore Adhkar reminders", error)
            runCatching { DhikrReminderReceiver.enqueueFailureRepair(context.applicationContext) }
        }
        return RefreshResult(
            silenceResult = syncSilence(
                context = context,
                skipWhileManualSilence = skipSilenceWhileManualSilence,
            ),
            wakeResult = syncWake(context),
        )
    }

    private fun hasSilencePermissions(context: Context): Boolean {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.isNotificationPolicyAccessGranted && hasExactAlarmPermission(context)
    }

    private fun hasExactAlarmPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return alarmManager.canScheduleExactAlarms()
        }
        return true
    }
}
