package com.tunisianprayertimes.platform

import kotlin.test.Test
import kotlin.test.assertTrue

class TimerSchedulerTest {

    @Test
    fun scheduleAfterReturnsEndTime() {
        val before = System.currentTimeMillis()
        val endsAt = TimerScheduler.scheduleAfter(1) { /* no-op */ }
        val expected = before + 60_000L

        // endsAt should be roughly 1 minute from now
        assertTrue(endsAt >= expected - 1000, "endsAt ($endsAt) should be >= expected ($expected) - 1s")
        assertTrue(endsAt <= expected + 1000, "endsAt ($endsAt) should be <= expected ($expected) + 1s")

        TimerScheduler.cancel()
    }

    @Test
    fun cancelDoesNotThrow() {
        TimerScheduler.cancel() // cancel with nothing scheduled
        TimerScheduler.scheduleAfter(5) { /* no-op */ }
        TimerScheduler.cancel() // cancel with something scheduled
    }
}
