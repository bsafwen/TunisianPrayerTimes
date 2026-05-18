package com.tunisianprayertimes

import android.content.Context
import com.tunisianprayertimes.wake.WakeAlarmScheduler

object SilenceStatus {
    fun isAppControlledSilenceActive(context: Context): Boolean =
        PrefsManager.isAutoSilenceActive(context) ||
            PrefsManager.isManualSilenceActive(context) ||
            WakeAlarmScheduler.isSilenceUntilAlarmActive(context)
}