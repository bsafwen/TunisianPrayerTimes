package com.tunisianprayertimes.wake

import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.WakeMainAlarmMode
import com.tunisianprayertimes.WakeRepeatMode

internal fun PrayerWakeConfig.hasFutureWakeTriggers(nowMillis: Long): Boolean {
    if (!enabled) {
        return false
    }

    if (mainAlarm.mode != WakeMainAlarmMode.FROM_NOW && repeatMode != WakeRepeatMode.ONCE) {
        return true
    }

    return hasIncompleteOneOffTriggers(nowMillis)
}

internal fun PrayerWakeConfig.isExpiredOneOffWakeAlarm(nowMillis: Long): Boolean =
    (mainAlarm.mode == WakeMainAlarmMode.FROM_NOW || repeatMode == WakeRepeatMode.ONCE) &&
        !hasIncompleteOneOffTriggers(nowMillis)

private fun PrayerWakeConfig.hasIncompleteOneOffTriggers(nowMillis: Long): Boolean {
    val mainTriggerAtMillis = mainAlarm.oneOffTriggerAtMillis
    if (mainTriggerAtMillis > nowMillis) {
        return true
    }

    if (mainTriggerAtMillis <= 0L) {
        return false
    }

    return subAlarms.any { subAlarm ->
        mainTriggerAtMillis + subAlarm.signedOffsetMinutes.toMillis() > nowMillis
    }
}

private fun Int.toMillis(): Long = this * 60_000L