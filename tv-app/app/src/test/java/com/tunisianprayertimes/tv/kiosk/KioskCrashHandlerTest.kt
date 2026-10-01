package com.tunisianprayertimes.tv.kiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskCrashHandlerTest {

    private val now = 1_000_000L

    @Test
    fun aCrashOnScreenRestartsTheDisplay() {
        assertTrue(KioskCrashHandler.shouldRestart(now, KioskState(resumedAt = now - 5_000, stoppedAt = now - 60_000), inFront = true))
    }

    @Test
    fun aDisplayThatDiedOnScreenBeforeItsStopWasRecordedIsRestarted() {
        // A crash at start: the new process has not resumed yet, the last record is the previous start.
        assertTrue(KioskCrashHandler.shouldRestart(now, KioskState(resumedAt = now - 5_000), inFront = false))
        assertTrue(KioskCrashHandler.shouldRestart(now, KioskState(resumedAt = now - 5_000, stoppedAt = now - 60_000), inFront = false))
    }

    @Test
    fun aCrashWhileTheDisplayIsAwayIsLeftToTheWatchdog() {
        assertFalse(KioskCrashHandler.shouldRestart(now, KioskState(resumedAt = now - 60_000, stoppedAt = now - 5_000), inFront = false))
        assertFalse(KioskCrashHandler.shouldRestart(now, KioskState(), inFront = false))
    }

    @Test
    fun theAdminsTimeInTheBoxSettingsIsRespected() {
        val away = KioskState(resumedAt = now - 5_000, adminAwayUntil = now + 60_000)
        assertFalse(KioskCrashHandler.shouldRestart(now, away, inFront = false))
        assertTrue(KioskCrashHandler.shouldRestart(now, away.copy(adminAwayUntil = now - 1), inFront = false))
    }
}
