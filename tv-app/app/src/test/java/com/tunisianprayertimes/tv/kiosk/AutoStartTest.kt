package com.tunisianprayertimes.tv.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStartTest {

    private fun resolve(
        home: Boolean = false, owner: Boolean = false, a11y: Boolean = false, overlay: Boolean = false, sdk: Int = 34, fireTv: Boolean = false,
    ) = AutoStartTierResolver.resolve(home, owner, a11y, overlay, sdk, fireTv)

    @Test
    fun theStrongestAvailableTierWins() {
        assertEquals(AutoStartTier.HOME, resolve(home = true, owner = true, a11y = true, overlay = true).tier)
        assertEquals(AutoStartTier.DEVICE_OWNER, resolve(owner = true, a11y = true, overlay = true).tier)
        assertEquals(AutoStartTier.ACCESSIBILITY, resolve(a11y = true, overlay = true).tier)
        assertEquals(AutoStartTier.OVERLAY, resolve(overlay = true).tier)
        assertEquals(AutoStartTier.LEGACY, resolve(sdk = 28).tier)
        assertEquals(AutoStartTier.NONE, resolve().tier)
    }

    @Test
    fun fireTvStartsByItselfOnFireOs7AndWithAGrantOnFireOs8() {
        // Fire OS 7 is Android 9: nothing to set.
        assertEquals(AutoStartTier.LEGACY, resolve(sdk = 28, fireTv = true).tier)
        // Fire OS 8 (Android 11) needs the quick-start service or "display over other apps", granted with adb.
        val fireOs8 = resolve(sdk = 30, fireTv = true)
        assertEquals(AutoStartTier.NONE, fireOs8.tier)
        assertTrue(fireOs8.fireTv)
        assertFalse(fireOs8.canBringToFront)
        assertEquals(AutoStartTier.ACCESSIBILITY, resolve(sdk = 30, a11y = true, overlay = true, fireTv = true).tier)
        assertEquals(AutoStartTier.OVERLAY, resolve(sdk = 30, overlay = true, fireTv = true).tier)
        // Fire OS puts its own home back: never reported as the home screen there.
        assertEquals(AutoStartTier.OVERLAY, resolve(home = true, sdk = 30, overlay = true, fireTv = true).tier)
    }

    private val overlay = AutoStart(AutoStartTier.OVERLAY)
    private val min = 60_000L

    @Test
    fun theAppIsBroughtBackThreeMinutesAfterItLeftTheScreen() {
        val state = KioskState(resumedAt = 0, stoppedAt = 10 * min)
        assertFalse(ForegroundWatchdogPolicy.shouldRefront(12 * min, state, overlay, setupDone = true))
        assertTrue(ForegroundWatchdogPolicy.shouldRefront(13 * min, state, overlay, setupDone = true))
    }

    @Test
    fun theWatchdogLeavesTheAppAloneWhenItShould() {
        val stopped = KioskState(resumedAt = 0, stoppedAt = 10 * min)
        val now = 20 * min
        assertFalse("back in front", ForegroundWatchdogPolicy.shouldRefront(now, stopped.copy(resumedAt = 11 * min), overlay, true, inFront = true))
        assertFalse("admin left on purpose", ForegroundWatchdogPolicy.shouldRefront(now, stopped.copy(adminAwayUntil = 40 * min), overlay, true))
        assertFalse("box cannot", ForegroundWatchdogPolicy.shouldRefront(now, stopped, AutoStart(AutoStartTier.NONE), true))
        assertFalse("setup not done", ForegroundWatchdogPolicy.shouldRefront(now, stopped, overlay, false))
        assertTrue("admin time is over", ForegroundWatchdogPolicy.shouldRefront(41 * min, stopped.copy(adminAwayUntil = 40 * min), overlay, true))
    }

    @Test
    fun aDisplayWhoseProcessDiedOnScreenIsBroughtBack() {
        // Started at 10 min, then crashed or was killed: no stop was ever recorded.
        val died = KioskState(resumedAt = 10 * min, stoppedAt = 2 * min)
        assertFalse(ForegroundWatchdogPolicy.shouldRefront(12 * min, died, overlay, true))
        assertTrue(ForegroundWatchdogPolicy.shouldRefront(14 * min, died, overlay, true))
        assertTrue(ForegroundWatchdogPolicy.shouldRefront(14 * min, KioskState(resumedAt = 10 * min), overlay, true))
        assertFalse("no record at all", ForegroundWatchdogPolicy.shouldRefront(14 * min, KioskState(), overlay, true))
    }

    @Test
    fun aRefusedStartIsRetriedOnlyAfterFifteenMinutes() {
        val tried = KioskState(resumedAt = 0, stoppedAt = 10 * min, refrontedAt = 13 * min)
        assertFalse(ForegroundWatchdogPolicy.shouldRefront(18 * min, tried, overlay, true))
        assertFalse(ForegroundWatchdogPolicy.shouldRefront(23 * min, tried, overlay, true))
        assertTrue(ForegroundWatchdogPolicy.shouldRefront(28 * min, tried, overlay, true))
    }
}
