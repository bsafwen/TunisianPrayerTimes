package com.tunisianprayertimes.tv.kiosk

import com.tunisianprayertimes.tv.kiosk.CrashLoopGuard.Decision
import com.tunisianprayertimes.tv.kiosk.CrashLoopGuard.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLoopGuardTest {

    private val guard = CrashLoopGuard()
    private val minute = CrashLoopGuard.MINUTE

    private fun crashesAt(vararg times: Long): List<Pair<State, Decision>> {
        var state = State()
        return times.map { now -> guard.recordCrash(state, now).also { state = it.first } }
    }

    @Test
    fun oneOrTwoCrashesJustRestart() {
        assertEquals(listOf(Decision.RESTART, Decision.RESTART), crashesAt(1_000, 2 * minute).map { it.second })
    }

    @Test
    fun theThirdCrashWithinTenMinutesStartsSafeModeAndTheSixthStopsRestarting() {
        val decisions = crashesAt(minute, 2 * minute, 3 * minute, 4 * minute, 5 * minute, 6 * minute).map { it.second }
        assertEquals(
            listOf(
                Decision.RESTART, Decision.RESTART, Decision.RESTART_IN_SAFE_MODE,
                Decision.RESTART_IN_SAFE_MODE, Decision.RESTART_IN_SAFE_MODE, Decision.STOP,
            ),
            decisions,
        )
    }

    @Test
    fun afterGivingUpNothingStartsTheAppUntilTheWindowPasses() {
        val (state, decision) = crashesAt(*LongArray(6) { (it + 1) * minute }).last()
        assertEquals(Decision.STOP, decision)
        assertTrue(guard.isGivingUp(state, 7 * minute))
        assertFalse(guard.isGivingUp(state, 17 * minute))
    }

    @Test
    fun crashesElevenMinutesApartNeverStartSafeMode() {
        val decisions = crashesAt(*LongArray(8) { (it + 1) * 11 * minute }).map { it.second }
        assertTrue(decisions.all { it == Decision.RESTART })
    }

    @Test
    fun safeModeEndsAfterThirtyQuietMinutesOrAReboot() {
        val (state, _) = crashesAt(minute, 2 * minute, 3 * minute).last()
        assertTrue(guard.isSafeMode(state, 20 * minute))
        assertFalse(guard.isSafeMode(state, 34 * minute))
        // After a reboot the clock restarts near zero, far below the stored end.
        assertFalse(guard.isSafeMode(state.copy(safeModeUntil = 10_000 * minute), 5_000))
    }

    @Test
    fun stateSurvivesItsStoredForm() {
        val state = State(listOf(1L, 2L, 3L), 99L)
        assertEquals(state, State.decode(state.encode()))
        assertEquals(State(), State.decode(null))
        assertEquals(State(), State.decode("garbage;x"))
        assertEquals(State(listOf(5L)), State.decode("5;"))
    }

    @Test
    fun aRescueUpdateIsOnlyForABrokenWall() {
        val safe = crashesAt(minute, 2 * minute, 3 * minute).last().first
        val now = 4 * minute
        // Safe mode with the display on screen still works: no rescue, whatever the crashes.
        assertFalse(guard.needsRescue(safe, now, packageReplacedAt = null, displayInFront = true))
        assertFalse(guard.needsRescue(safe, now, packageReplacedAt = 2 * minute, displayInFront = true))
        // Off the screen, or crashing ever since this boot's update.
        assertTrue(guard.needsRescue(safe, now, packageReplacedAt = null, displayInFront = false))
        assertTrue(guard.needsRescue(safe, now, packageReplacedAt = minute / 2, displayInFront = true))
        // Having stopped restarting is always broken; a few restarts without safe mode never are.
        val givenUp = crashesAt(*LongArray(6) { (it + 1) * minute }).last().first
        assertTrue(guard.needsRescue(givenUp, 7 * minute, packageReplacedAt = null, displayInFront = true))
        val twice = crashesAt(minute, 2 * minute).last().first
        assertFalse(guard.needsRescue(twice, now, packageReplacedAt = minute / 2, displayInFront = false))
        // Safe mode is over.
        assertFalse(guard.needsRescue(safe, 3 * minute + 31 * minute, packageReplacedAt = null, displayInFront = false))
    }
}
