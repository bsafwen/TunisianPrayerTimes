package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.time.DeviceMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsClockStoreTest {

    private val prefs = InMemoryPreferences()
    private val store = PrefsClockStore(prefs)
    private val start = 1_790_686_800_000L

    /** What a new process (a restart, a reboot) reads back. */
    private fun reloaded() = PrefsClockStore(prefs)

    private fun mark(millis: Long, elapsed: Long = millis - start + 3_600_000, boot: String? = "b1") = DeviceMark(millis, elapsed, boot)

    @Test
    fun theMarksAreWrittenTogetherOnceAMinute() {
        store.lastKnownGoodMillis = start
        store.deviceMark = mark(start)
        // Read every second: kept in memory, not written each time.
        for (second in 1..59L) {
            store.lastKnownGoodMillis = start + second * 1_000
            store.deviceMark = mark(start + second * 1_000)
        }
        assertEquals(start + 59_000, store.lastKnownGoodMillis)
        assertEquals(start, reloaded().lastKnownGoodMillis)
        assertEquals(mark(start), reloaded().deviceMark)
        store.lastKnownGoodMillis = start + 60_000
        assertEquals(start + 60_000, reloaded().lastKnownGoodMillis)
        assertEquals("the mark goes with it, uptime and all", mark(start + 59_000), reloaded().deviceMark)
    }

    @Test
    fun aMarkMovedBackOrFromANewBootIsWrittenAtOnce() {
        store.deviceMark = mark(start)
        // A confirmation or a clock set: the new, earlier mark must survive a cut that comes right after.
        store.deviceMark = mark(start - 3_600_000, elapsed = 3_700_000)
        assertEquals(mark(start - 3_600_000, elapsed = 3_700_000), reloaded().deviceMark)
        // A reboot whose clock is a few seconds on: its boot and its short uptime go together.
        store.deviceMark = mark(start - 3_595_000, elapsed = 20_000, boot = "b2")
        assertEquals(mark(start - 3_595_000, elapsed = 20_000, boot = "b2"), reloaded().deviceMark)
        store.deviceMark = mark(start - 3_590_000, elapsed = 0, boot = null)
        assertEquals("a set with the boot not known", mark(start - 3_590_000, elapsed = 0, boot = null), reloaded().deviceMark)
    }

    @Test
    fun theAnswerIsKept() {
        store.correctionMillis = -25_200_000
        store.confirmedBy = "ADMIN"
        store.foreignZoneSeen = true
        val back = reloaded()
        assertEquals(-25_200_000L, back.correctionMillis)
        assertEquals("ADMIN", back.confirmedBy)
        assertTrue(back.foreignZoneSeen)
        assertNull("unknown on a new store", PrefsClockStore(InMemoryPreferences()).deviceMark)
    }
}
