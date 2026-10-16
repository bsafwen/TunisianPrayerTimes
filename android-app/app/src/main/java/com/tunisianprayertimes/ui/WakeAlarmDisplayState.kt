package com.tunisianprayertimes.ui

import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.WakeMainAlarmMode
import com.tunisianprayertimes.WakeRepeatMode

internal data class WakeAlarmDisplayState(
    val visibleWakeAlarms: List<PrayerWakeConfig>,
    val showEmptyState: Boolean,
)

/**
 * The alarms the Alarms tab lists. A one-off whose rings are all past is hidden, unless its occurrence is still in
 * progress ([inProgressAlarmIds]: a ring queued behind a challenge, or its awake check), so it can still be seen and
 * stopped.
 */
internal fun wakeAlarmDisplayState(
    wakeAlarms: List<PrayerWakeConfig>?,
    nowMillis: Long = System.currentTimeMillis(),
    inProgressAlarmIds: Set<String> = emptySet(),
): WakeAlarmDisplayState {
    if (wakeAlarms == null) {
        return WakeAlarmDisplayState(
            visibleWakeAlarms = emptyList(),
            showEmptyState = false,
        )
    }

    val visibleWakeAlarms = wakeAlarms.filterNot { alarm ->
        alarm.isExpiredFromNowAlarm(nowMillis) && alarm.id !in inProgressAlarmIds
    }

    return WakeAlarmDisplayState(
        visibleWakeAlarms = visibleWakeAlarms,
        showEmptyState = visibleWakeAlarms.isEmpty(),
    )
}

private fun PrayerWakeConfig.isExpiredFromNowAlarm(nowMillis: Long): Boolean {
    if (mainAlarm.mode != WakeMainAlarmMode.FROM_NOW && repeatMode != WakeRepeatMode.ONCE) {
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