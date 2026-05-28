package com.tunisianprayertimes.ui

import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.WakeMainAlarmMode

internal data class WakeAlarmDisplayState(
    val visibleWakeAlarms: List<PrayerWakeConfig>,
    val showEmptyState: Boolean,
)

internal fun wakeAlarmDisplayState(
    wakeAlarms: List<PrayerWakeConfig>?,
    nowMillis: Long = System.currentTimeMillis(),
): WakeAlarmDisplayState {
    if (wakeAlarms == null) {
        return WakeAlarmDisplayState(
            visibleWakeAlarms = emptyList(),
            showEmptyState = false,
        )
    }

    val visibleWakeAlarms = wakeAlarms.filterNot { alarm ->
        alarm.isExpiredFromNowAlarm(nowMillis)
    }

    return WakeAlarmDisplayState(
        visibleWakeAlarms = visibleWakeAlarms,
        showEmptyState = visibleWakeAlarms.isEmpty(),
    )
}

private fun PrayerWakeConfig.isExpiredFromNowAlarm(nowMillis: Long): Boolean {
    if (mainAlarm.mode != WakeMainAlarmMode.FROM_NOW) {
        return false
    }

    val mainTriggerAtMillis = mainAlarm.oneOffTriggerAtMillis
    if (mainTriggerAtMillis <= 0L) {
        return true
    }

    if (mainTriggerAtMillis > nowMillis) {
        return false
    }

    return subAlarms.none { subAlarm ->
        mainTriggerAtMillis + subAlarm.signedOffsetMinutes.toMillis() > nowMillis
    }
}

private fun Int.toMillis(): Long = this * 60_000L