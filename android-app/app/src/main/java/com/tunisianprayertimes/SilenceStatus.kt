package com.tunisianprayertimes

import android.app.NotificationManager
import android.content.Context
import com.tunisianprayertimes.wake.WakeAlarmScheduler

object SilenceStatus {
    fun isAppControlledSilenceActive(context: Context): Boolean {
        val hasAppSilenceFlag = PrefsManager.isAutoSilenceActive(context) ||
            PrefsManager.isManualSilenceActive(context) ||
            WakeAlarmScheduler.isSilenceUntilAlarmActive(context)
        if (!hasAppSilenceFlag) return false

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
    }
}