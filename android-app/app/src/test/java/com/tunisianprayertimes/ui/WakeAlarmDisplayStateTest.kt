package com.tunisianprayertimes.ui

import com.tunisianprayertimes.ClockTime
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrayerWakeSubAlarm
import com.tunisianprayertimes.WakeMainAlarmConfig
import com.tunisianprayertimes.WakeMainAlarmMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeAlarmDisplayStateTest {
    private val nowMillis = 10_000_000L

    @Test
    fun loadingState_doesNotShowEmptyState() {
        val state = wakeAlarmDisplayState(
            wakeAlarms = null,
            nowMillis = nowMillis,
        )

        assertFalse(state.showEmptyState)
        assertTrue(state.visibleWakeAlarms.isEmpty())
    }

    @Test
    fun expiredFromNowAlarm_isHiddenAndShowsEmptyState() {
        val expiredTimer = fromNowAlarm(
            id = "expired-timer",
            triggerAtMillis = nowMillis - 1_000L,
        )

        val state = wakeAlarmDisplayState(
            wakeAlarms = listOf(expiredTimer),
            nowMillis = nowMillis,
        )

        assertTrue(state.showEmptyState)
        assertTrue(state.visibleWakeAlarms.isEmpty())
    }

    @Test
    fun fromNowAlarmWithFutureSubAlarm_remainsVisible() {
        val timerWithPendingSubAlarm = fromNowAlarm(
            id = "pending-subalarm-timer",
            triggerAtMillis = nowMillis - 60_000L,
            subAlarms = listOf(
                PrayerWakeSubAlarm(
                    id = "after-main",
                    minutesOffset = 10,
                    direction = OffsetDirection.AFTER,
                ),
            ),
        )

        val state = wakeAlarmDisplayState(
            wakeAlarms = listOf(timerWithPendingSubAlarm),
            nowMillis = nowMillis,
        )

        assertFalse(state.showEmptyState)
        assertEquals(listOf(timerWithPendingSubAlarm), state.visibleWakeAlarms)
    }

    @Test
    fun disabledFixedAlarm_remainsVisibleWithoutEmptyState() {
        val disabledFixedAlarm = PrayerWakeConfig(
            id = "disabled-fixed",
            prayer = Prayer.FAJR,
            enabled = false,
            mainAlarm = WakeMainAlarmConfig(
                mode = WakeMainAlarmMode.FIXED_TIME,
                fixedTime = ClockTime(hour = 6, minute = 30),
            ),
        )

        val state = wakeAlarmDisplayState(
            wakeAlarms = listOf(disabledFixedAlarm),
            nowMillis = nowMillis,
        )

        assertFalse(state.showEmptyState)
        assertEquals(listOf(disabledFixedAlarm), state.visibleWakeAlarms)
    }

    private fun fromNowAlarm(
        id: String,
        triggerAtMillis: Long,
        subAlarms: List<PrayerWakeSubAlarm> = emptyList(),
    ): PrayerWakeConfig = PrayerWakeConfig(
        id = id,
        prayer = Prayer.FAJR,
        enabled = true,
        mainAlarm = WakeMainAlarmConfig(
            mode = WakeMainAlarmMode.FROM_NOW,
            oneOffOffsetMinutes = 15,
            oneOffTriggerAtMillis = triggerAtMillis,
        ),
        subAlarms = subAlarms,
    )
}