package com.tunisianprayertimes.tv.kiosk

import com.tunisianprayertimes.tv.data.InMemoryPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class KioskStoreTest {

    private var boot = 7
    private val store = KioskStore(InMemoryPreferences(), EventLog(File.createTempFile("kiosk", ".log").apply { deleteOnExit() })) { boot }

    @Test
    fun crashTimesSurviveARestartInTheSameBoot() {
        val state = CrashLoopGuard.State(listOf(40_000L, 55_000L), safeModeUntil = 1_800_000L)
        store.crashes = state
        assertEquals(state, store.crashes)
    }

    @Test
    fun crashTimesFromThePreviousBootAreDropped() {
        store.crashes = CrashLoopGuard.State(listOf(40_000L, 55_000L, 70_000L, 85_000L, 100_000L))
        boot = 8
        assertEquals(CrashLoopGuard.State(), store.crashes)
        // One crash in the new boot is only the first one.
        assertEquals(CrashLoopGuard.Decision.RESTART, CrashLoopGuard().recordCrash(store.crashes, 5 * CrashLoopGuard.MINUTE).second)
    }
}
