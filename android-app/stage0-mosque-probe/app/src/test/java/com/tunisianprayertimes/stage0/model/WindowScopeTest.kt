package com.tunisianprayertimes.stage0.model

import org.junit.Assert.*
import org.junit.Test

class WindowScopeTest {
    private val a = ProbeWindow("prayer-day-a", 10_000, 80_000)
    private fun probe(vararg w: ProbeWindow) = WindowPresence("boot", listOf(area()), w.toList())
    private fun fix(p: WindowPresence, at: Long, token: EventEpoch? = p.epoch, accuracy: Double = 5.0) =
        p.observe(ProbeFix(point(), accuracy, at), at, token)
    private fun enter(p: WindowPresence, start: Long) {
        assertTrue(p.begin(start)); fix(p, start); fix(p, start + 15_000); assertTrue(p.presenceActive)
    }

    @Test fun startInclusiveEndExclusiveAndNoEvaluationInEitherOutsideRegion() {
        val p = probe(a)
        assertFalse(p.begin(9_999)); assertEquals(FixRejection.OUTSIDE_WINDOW, fix(p, 9_999).rejection)
        assertEquals(0, p.evaluations)
        enter(p, 10_000); val token = p.epoch; val count = p.evaluations
        assertEquals(FixRejection.OUTSIDE_WINDOW, fix(p, 80_000, token).rejection)
        assertFalse(p.presenceActive); assertEquals(count, p.evaluations); assertNull(p.snapshot)
    }

    @Test fun prewindowFixCannotBeReusedEvenThoughItIsOnlyOneMillisecondOld() {
        val p = probe(a); p.begin(10_000)
        assertEquals(FixRejection.AGE, p.observe(ProbeFix(point(), 5.0, 9_999), 10_000, p.epoch).rejection)
        assertEquals(0, p.evaluations); assertFalse(p.presenceActive)
    }

    @Test fun alreadyInsideAtOpeningNeedsFreshEntryDwell() {
        val p = probe(a); assertTrue(p.begin(10_000))
        fix(p, 10_000); fix(p, 24_999); assertFalse(p.presenceActive)
        fix(p, 25_000); assertTrue(p.presenceActive)
    }

    @Test fun acquisitionAndUncertaintyCannotExtendAnExistingWindow() {
        val p = probe(ProbeWindow("short", 0, 20_000))
        enter(p, 0); assertEquals(20_000L, p.effectiveDeadline)
        fix(p, 19_999, accuracy = 70.0)
        p.reconcile(20_000); assertFalse(p.presenceActive); assertFalse(p.locationChecksAllowed)
    }

    @Test fun shortRemainderCannotSkipTheEntryDwell() {
        val p = probe(ProbeWindow("too-short", 0, 14_999))
        assertFalse(p.begin(0)); assertFalse(p.presenceActive)
    }

    @Test fun futureWindowCannotExtendCurrentDeadlineBeforeItStarts() {
        val p = probe(a, ProbeWindow("b", 60_000, 120_000))
        enter(p, 10_000); assertEquals(80_000L, p.effectiveDeadline)
        p.reconcile(60_000); assertEquals(120_000L, p.effectiveDeadline)
    }

    @Test fun overlappingWindowsKeepValidPresencePastTheFirstEnd() {
        val p = probe(a, ProbeWindow("b", 60_000, 120_000))
        enter(p, 10_000); val token = p.epoch
        p.reconcile(80_000); assertTrue(p.presenceActive); assertEquals(token, p.epoch)
        p.reconcile(120_000); assertFalse(p.presenceActive)
    }

    @Test fun adjoiningWindowsAreContinuousButOneMillisecondGapIsNot() {
        for (gap in listOf(0L, 1L)) {
            val p = probe(a, ProbeWindow("b", 80_000 + gap, 160_000))
            enter(p, 10_000); val old = p.epoch
            p.reconcile(80_000 + gap)
            assertEquals(gap == 0L, p.presenceActive)
            if (gap > 0) {
                assertNotEquals(old, p.epoch)
                assertEquals(FixRejection.OBSOLETE_EPOCH, fix(p, 80_001, old).rejection)
                assertTrue(p.begin(80_001)); fix(p, 80_002); fix(p, 95_002); assertTrue(p.presenceActive)
            }
        }
    }

    @Test fun jumpingDirectlyAcrossGapCannotCarryPresenceEvenWithoutAnEndTick() {
        val p = probe(a, ProbeWindow("b", 100_000, 180_000))
        enter(p, 10_000); val old = p.epoch
        assertEquals(FixRejection.OBSOLETE_EPOCH, fix(p, 100_000, old).rejection)
        assertFalse(p.presenceActive)
    }

    @Test fun skipDismissesAllCurrentlySupportingOccurrencesAcrossReentryAndReplan() {
        val windows = listOf(a, ProbeWindow("b", 20_000, 120_000))
        val p = probe(*windows.toTypedArray()); enter(p, 10_000)
        p.skip(25_001); assertEquals(setOf("prayer-day-a", "b"), p.dismissed)
        assertFalse(p.begin(30_000)); fix(p, 45_000); assertFalse(p.presenceActive)
        p.replace(46_000, windows = windows.map { it.copy(end = it.end + 10_000) })
        assertFalse(p.begin(46_000)); assertFalse(p.locationChecksAllowed)
        val recovered = WindowPresence("new-boot", listOf(area()), windows, dismissed = p.dismissed)
        assertFalse(recovered.begin(47_000))
    }

    @Test fun distinctLaterOccurrenceCanAcquireFreshAfterSkippedOne() {
        val p = probe(a, ProbeWindow("b", 100_000, 180_000))
        enter(p, 10_000); p.skip(26_000)
        enter(p, 100_000); assertEquals(setOf("prayer-day-a"), p.dismissed)
    }

    @Test fun eligibleWakeDismissalDoesNotAutomaticallyResumeAfterPlayback() {
        val p = probe(a); enter(p, 10_000); p.wake(26_000, true)
        fix(p, 40_000); assertFalse(p.begin(41_000)); assertFalse(p.presenceActive)
    }

    @Test fun ordinaryWakeAndPlannedWindowAloneDoNotCreatePresence() {
        val p = probe(a); p.reconcile(10_000); p.wake(11_000, false)
        assertFalse(p.presenceActive); enter(p, 12_000); p.wake(28_000, false); assertTrue(p.presenceActive)
    }

    @Test fun timeOnlyHasNoLocationWorkAndBridgeCannotAuthorizeCombinedMode() {
        val p = probe(a); p.replace(0, mode = ProbeMode.TIME_ONLY)
        assertFalse(p.begin(10_000)); assertEquals(FixRejection.OUTSIDE_WINDOW, fix(p, 25_000).rejection)
        assertEquals(0, p.evaluations)
        assertTrue(ProbePolicy.decide(ProbeMode.TIME_ONLY, true, true, false, false,
            alreadyActiveTimeOnlyBridge = true).requestsSilence)
        assertFalse(ProbePolicy.decide(ProbeMode.TIME_AND_MOSQUE, true, true, false, true,
            alreadyActiveTimeOnlyBridge = true).requestsSilence)
        assertEquals(2, ProbeMode.entries.size)
    }

    @Test fun modeMasterPermissionAndScheduleChangesInvalidateOldCallbacks() {
        for (change in 0..3) {
            val p = probe(a); enter(p, 10_000); val old = p.epoch
            when (change) {
                0 -> p.replace(26_000, mode = ProbeMode.TIME_ONLY)
                1 -> p.replace(26_000, master = false)
                2 -> p.replace(26_000, ready = false)
                else -> p.replace(26_000, windows = listOf(a.copy(end = 26_000)))
            }
            val count = p.evaluations
            fix(p, 27_000, old); assertEquals(count, p.evaluations); assertFalse(p.presenceActive)
        }
    }

    @Test fun overnightWindowsUseInstantsRatherThanTimeOfDayAndDismissedEndCannotExtendCoverage() {
        val day = 86_400_000L
        val windows = listOf(ProbeWindow("isha-yesterday", day - 30_000, day + 60_000),
            ProbeWindow("dismissed-longer", day - 20_000, day + 120_000))
        val c = WindowScope.coverage(windows, setOf("dismissed-longer"), day)!!
        assertEquals(day + 60_000, c.activeEnd)
        assertNull(WindowScope.coverage(windows, setOf("isha-yesterday", "dismissed-longer"), day))
    }

    @Test fun longConfiguredWindowHasNoIndependentVisitCapWhileStrictSupportContinues() {
        val end = 10_900_000L
        val p = probe(ProbeWindow("long-existing-window", 0, end)); enter(p, 0)
        for (at in 60_000L..10_860_000L step 60_000L) fix(p, at)
        assertTrue(p.presenceActive)
        p.reconcile(end); assertFalse(p.presenceActive)
    }
}
