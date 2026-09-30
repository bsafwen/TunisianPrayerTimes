package com.tunisianprayertimes.tv.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogViewTest {

    private val day = 24 * 3_600_000L
    private val now = 100 * day

    @Test
    fun oldCrashesAndSleepsNoLongerWarn() {
        val log = listOf(
            EventEntry(now - 2 * day, KioskEvent.SLEEP_GAP, "a"),
            EventEntry(now - 20 * day, KioskEvent.SLEEP_GAP, "b"),
            EventEntry(now - 30 * day, KioskEvent.CRASH, "old"),
        )
        val view = LogView(log, now)
        assertEquals(listOf("a"), view.sleepGaps.map { it.detail })
        assertNull(view.lastCrash)
        assertEquals(3, view.events.size)
    }

    @Test
    fun theNewestOfEachKindIsTakenFromOneRead() {
        val log = listOf(
            EventEntry(now - 1_000, KioskEvent.CRASH, "new"),
            EventEntry(now - 2_000, KioskEvent.AUTOSTART_BLOCKED),
            EventEntry(now - 3_000, KioskEvent.BOOT_TIMING, "t"),
            EventEntry(now - 4_000, KioskEvent.AUTOSTART_OK),
        )
        val view = LogView(log, now)
        assertEquals("new", view.lastCrash?.detail)
        assertEquals(KioskEvent.AUTOSTART_BLOCKED, view.lastAutoStart?.type)
        assertEquals("t", view.lastBootTiming?.detail)
    }
}
