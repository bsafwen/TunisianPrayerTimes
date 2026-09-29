package com.tunisianprayertimes.tv.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherWatchTest {

    private val min = 60_000L

    @Test
    fun amazonsHomeScreensAreWatchedButNeverSettingsOrTheAppItself() {
        val homes = LauncherPackages.of("com.google.android.tvlauncher", listOf("com.amazon.tv.launcher", "com.android.settings", "me.app"), self = "me.app")
        assertEquals(setOf("com.amazon.tv.launcher", "com.amazon.tahoe", "com.google.android.tvlauncher"), homes)
        assertFalse("Prime Video is not a home", "com.amazon.firebat" in homes)
        assertFalse("Fire TV settings", "com.amazon.tv.settings.v2" in LauncherPackages.of("com.amazon.tv.settings.v2", emptyList(), "me.app"))
    }

    @Test
    fun theHomeScreenIsReplacedAtOnceAfterBootOrWakeAndAfterAMinuteOtherwise() {
        fun action(now: Long, screenOnAt: Long? = null, setupDone: Boolean = true, away: Long? = null) =
            LauncherWatchPolicy.onLauncherFront(now, screenOnAt, setupDone, away)
        assertEquals(LauncherAction.NOW, action(now = 40_000))
        assertEquals(LauncherAction.NOW, action(now = 60 * min, screenOnAt = 60 * min - 20_000))
        assertEquals(LauncherAction.AFTER_GRACE, action(now = 60 * min, screenOnAt = 50 * min))
        assertEquals(LauncherAction.IGNORE, action(now = 40_000, setupDone = false))
        assertEquals("the admin left on purpose", LauncherAction.IGNORE, action(now = 60 * min, away = 70 * min))
    }

    @Test
    fun anInstallerPressingHomeAfterTheFirstMinutesGetsTheGrace() {
        assertEquals(LauncherAction.NOW, LauncherWatchPolicy.onLauncherFront(3 * min - 1_000, null, true, null))
        assertEquals(LauncherAction.AFTER_GRACE, LauncherWatchPolicy.onLauncherFront(3 * min + 1_000, null, true, null))
    }

    @Test
    fun theDisplayComesBackWhenItsProcessDiedOnScreen() {
        val diedOnScreen = KioskState(resumedAt = 10 * min)
        fun reconnect(state: KioskState = diedOnScreen, inFront: Boolean = false, interactive: Boolean = true, givingUp: Boolean = false) =
            LauncherWatchPolicy.refrontOnReconnect(20 * min, state, inFront, setupDone = true, interactive = interactive, givingUp = givingUp)
        assertTrue(reconnect())
        assertFalse("it had left the screen", reconnect(KioskState(resumedAt = 10 * min, stoppedAt = 11 * min)))
        assertFalse("never shown", reconnect(KioskState()))
        assertFalse("screen off: waits for the wake", reconnect(interactive = false))
        assertFalse("keeps crashing", reconnect(givingUp = true))
        assertFalse("already back", reconnect(inFront = true))
        assertFalse("admin away", reconnect(KioskState(resumedAt = 10 * min, adminAwayUntil = 30 * min)))
    }

    @Test
    fun onWakeTheDisplayReturnsOnlyWhereNothingElseBringsItBack() {
        fun wake(inFront: Boolean = false, quickStart: Boolean = false, canBring: Boolean = true, away: Long? = null) =
            WakePolicy.shouldRefront(60 * min, inFront, quickStart, canBring, setupDone = true, adminAwayUntil = away)
        assertTrue(wake())
        assertFalse("already on screen (Android TV boxes)", wake(inFront = true))
        assertFalse("the quick-start service does it", wake(quickStart = true))
        assertFalse("the box forbids it", wake(canBring = false))
        assertFalse("admin away", wake(away = 70 * min))
    }

    @Test
    fun theServiceStartsTheDisplayOncePerBoot() {
        assertTrue(LauncherWatchPolicy.launchOnConnect(20_000, setupDone = true, inFront = false, resumedThisBoot = false, adminAwayUntil = null))
        assertFalse("already shown this boot", LauncherWatchPolicy.launchOnConnect(20_000, true, false, resumedThisBoot = true, adminAwayUntil = null))
        assertFalse("not a boot any more", LauncherWatchPolicy.launchOnConnect(30 * min, true, false, false, null))
        assertFalse("first setup", LauncherWatchPolicy.launchOnConnect(20_000, setupDone = false, inFront = false, resumedThisBoot = false, adminAwayUntil = null))
    }

    @Test
    fun aScreenThatInsistsIsNotFoughtInALoop() {
        val limiter = RefrontLimiter()
        assertTrue(limiter.tryAcquire(0))
        assertFalse("5 s apart at least", limiter.tryAcquire(2_000))
        val allowed = (1..60).count { limiter.tryAcquire(it * 5_000L) }
        assertEquals("6 in 10 minutes", 5, allowed)
        assertTrue("again once the window moved on", limiter.tryAcquire(11 * min))
    }

    @Test
    fun aStartHeldBackByTheGapIsRetriedWhenTheGapEnds() {
        val limiter = RefrontLimiter()
        assertEquals(0L, limiter.waitMillis(0))
        assertTrue(limiter.tryAcquire(0))
        assertEquals("the rest of the 5 s gap", 4_200L, limiter.waitMillis(800))
        (1..5).forEach { assertTrue(limiter.tryAcquire(it * 5_000L)) }
        assertEquals("the cap: not retried", -1L, limiter.waitMillis(40_000))
    }

    @Test
    fun theBootTimingReadsAsTheDisplayFirst() {
        val line = BootTiming.summary(mapOf(BootTiming.APP to 8_200L, BootTiming.SERVICE to 8_400L, BootTiming.RECEIVER to 19_700L, BootTiming.SCREEN to 9_100L))
        assertEquals("screen=9.1s service=8.4s receiver=19.7s app=8.2s", line)
        assertEquals(9.1, BootTiming.screenSeconds(line)!!, 0.001)
        assertEquals("screen=31.0s receiver=30.2s", BootTiming.summary(mapOf(BootTiming.RECEIVER to 30_200L, BootTiming.SCREEN to 31_000L, BootTiming.SERVICE to null)))
        // The timing and the auto-start check are matched by the boot they belong to.
        assertEquals(12, BootTiming.bootOf("$line boot=12"))
        assertEquals(12, BootTiming.bootOf("ACCESSIBILITY quickStart=running boot=12"))
        assertEquals(null, BootTiming.bootOf("ACCESSIBILITY"))
    }
}
