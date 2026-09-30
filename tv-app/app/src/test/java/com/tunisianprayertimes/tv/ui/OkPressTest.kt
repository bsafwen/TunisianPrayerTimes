package com.tunisianprayertimes.tv.ui

import com.tunisianprayertimes.tv.ui.common.OkPress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When OK acts on an admin control: its own presses only, not a double press that opened the page, and held on a stepper's key. */
class OkPressTest {

    private val shown = 10_000L

    @Test
    fun aPressOnTheElementActsOnRelease() {
        val press = OkPress(shown)
        assertFalse(press.down(shown + 2_000, repeatCount = 0))
        assertTrue(press.up())
    }

    @Test
    fun theEndOfAPressBegunElsewhereDoesNothing() {
        // OK went down on the gouvernorat; its release reaches the delegation grid that replaced it.
        val press = OkPress(shown)
        assertFalse(press.up())
    }

    @Test
    fun aDoublePressDoesNotChooseOnThePageTheFirstOpened() {
        val press = OkPress(shown)
        assertFalse(press.down(shown + 200, repeatCount = 0))
        assertFalse(press.up())
        // Read, then chosen: that press counts.
        press.down(shown + OkPress.SETTLE_MILLIS, repeatCount = 0)
        assertTrue(press.up())
    }

    @Test
    fun aHeldOkActsOnceOnOrdinaryControls() {
        val press = OkPress(shown)
        press.down(shown + 1_000, repeatCount = 0)
        repeat(10) { assertFalse(press.down(shown + 1_500 + it * 50L, repeatCount = it + 1)) }
        assertTrue(press.up())
    }

    @Test
    fun aHeldStepperKeyStepsAtEachRepeatAndNotAgainOnRelease() {
        val press = OkPress(shown, repeats = true)
        assertFalse(press.down(shown + 1_000, repeatCount = 0))
        val steps = (1..20).count { press.down(shown + 1_500 + it * 50L, repeatCount = it) }
        assertEquals(20, steps)
        assertFalse(press.up())
        // A short press still steps once, on release.
        press.down(shown + 5_000, repeatCount = 0)
        assertTrue(press.up())
    }

    @Test
    fun repeatsOfAPressThatWasNotTheElementsDoNothing() {
        // The hold that opened the page goes on over its first key.
        val press = OkPress(shown, repeats = true)
        assertFalse(press.down(shown + 100, repeatCount = 0))
        assertFalse(press.down(shown + 600, repeatCount = 5))
        assertFalse(press.up())
        val elsewhere = OkPress(shown, repeats = true)
        assertFalse(elsewhere.down(shown + 2_000, repeatCount = 7))
        assertFalse(elsewhere.up())
    }

    @Test
    fun aClickIsSettledAsAPress() {
        assertFalse(OkPress.settled(shown, shown + 100))
        assertTrue(OkPress.settled(shown, shown + OkPress.SETTLE_MILLIS))
    }
}
