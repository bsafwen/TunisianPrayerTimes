package com.tunisianprayertimes

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import com.tunisianprayertimes.wake.WakeAlarmVerifyWorker

object ScheduleRefreshCoordinator {
    const val RESULT_SUCCESS = "success"
    const val RESULT_DISABLED = "disabled"
    const val RESULT_PERMISSION_MISSING = "permission_missing"
    const val RESULT_MANUAL_ACTIVE = "manual_active"
    const val RESULT_OUTSIDE_TUNISIA = "outside_tunisia"
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

        val outsideTunisia = PrefsManager.isDisabledOutsideTunisia(appContext)
        if (!outsideTunisia) {
            SilenceScheduler.scheduleAll(appContext)
        }
        SilenceVerifyWorker.enqueue(appContext)
        SilenceGuardService.stopIfNotNeeded(appContext)

        return if (outsideTunisia) RESULT_OUTSIDE_TUNISIA else RESULT_SUCCESS
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
    ): RefreshResult = RefreshResult(
        silenceResult = syncSilence(
            context = context,
            skipWhileManualSilence = skipSilenceWhileManualSilence,
        ),
        wakeResult = syncWake(context),
    )

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