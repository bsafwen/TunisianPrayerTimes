package com.tunisianprayertimes.stage0.model

import org.junit.Assert.*
import org.junit.Test

class PresenceMachineTest {
    private val epoch = EventEpoch("boot-a", "session-a")
    private fun machine(areas: List<ProbeArea> = listOf(area())) = PresenceMachine(epoch, areas)
    private fun observe(machine: PresenceMachine, at: Long, x: Double = 0.0, accuracy: Double = 5.0) =
        machine.observe(ProbeFix(point(x), accuracy, at), at, machine.snapshot.epoch)
    private fun entered(areas: List<ProbeArea> = listOf(area())): PresenceMachine = machine(areas).also {
        assertTrue(it.beginAcquisition(0, epoch))
        observe(it, 0); observe(it, 15_000)
        assertTrue(it.locationRequestActive)
    }

    @Test fun entryNeedsDistinctMonotonicSamplesAndFifteenSeconds() {
        val m = machine()
        assertTrue(m.beginAcquisition(0, epoch))
        observe(m, 0)
        assertEquals(FixRejection.DUPLICATE_OR_OUT_OF_ORDER, observe(m, 0).rejection)
        observe(m, 14_999)
        assertFalse(m.locationRequestActive)
        observe(m, 15_000)
        assertTrue(m.locationRequestActive)
    }

    @Test fun deliveredSpacingCannotInventSampleDwell() {
        val m = machine(); m.beginAcquisition(0, epoch)
        m.observe(ProbeFix(point(), 5.0, 10_000), 10_000, epoch)
        m.observe(ProbeFix(point(), 5.0, 10_001), 25_000, epoch)
        assertFalse(m.locationRequestActive)
        m.observe(ProbeFix(point(), 5.0, 25_000), 26_000, epoch)
        assertTrue(m.locationRequestActive)
    }

    @Test fun uncertainOrDifferentPlaceInterruptsEntryDwell() {
        val m = machine(listOf(area("a", 40.0, -100.0), area("b", 40.0, 100.0)))
        m.beginAcquisition(0, epoch)
        observe(m, 0, -100.0); observe(m, 15_000, 100.0)
        assertFalse(m.locationRequestActive)
        observe(m, 30_000, 100.0, 50.0)
        observe(m, 45_000, 100.0)
        assertFalse(m.locationRequestActive)
        observe(m, 60_000, 100.0)
        assertTrue(m.locationRequestActive)
        assertEquals(setOf("b"), m.snapshot.supportingAreaIds)
    }

    @Test fun spontaneousInsideCallbackCannotStartAnIdleVisit() {
        val m = machine()
        observe(m, 0); observe(m, 15_000)
        assertEquals(PresenceState.IDLE, m.snapshot.state)
        assertFalse(m.locationRequestActive)
    }

    @Test fun acquisitionDeadlineIncludesStartupAndCannotBeExtendedByRetry() {
        val m = machine(); m.beginAcquisition(0, epoch)
        assertTrue(m.retryAcquisition(30_000, epoch))
        assertFalse(m.retryAcquisition(40_000, epoch))
        assertFalse(m.beginAcquisition(50_000, epoch))
        observe(m, 80_000)
        observe(m, 95_000)
        assertEquals(PresenceReason.ACQUISITION_TIMEOUT, m.snapshot.reason)
        assertFalse(m.locationRequestActive)
    }

    @Test fun staleAndUnusableCallbacksCannotBecomeObservationHeartbeats() {
        val m = entered()
        for (t in 60_000L..300_000L step 60_000L) {
            assertEquals(FixClass.UNUSABLE, observe(m, t, accuracy = 150.0).classification)
            m.coarseExitHint(t, epoch)
        }
        m.tick(315_000)
        assertEquals(PresenceReason.NO_OBSERVATION, m.snapshot.reason)
        assertFalse(m.locationRequestActive)
    }

    @Test fun repeatedAndOutOfOrderFixesDoNotRenewLastUsableDelivery() {
        val m = entered()
        assertEquals(FixRejection.DUPLICATE_OR_OUT_OF_ORDER,
            m.observe(ProbeFix(point(), 5.0, 15_000), 30_000, epoch).rejection)
        assertEquals(FixRejection.DUPLICATE_OR_OUT_OF_ORDER,
            m.observe(ProbeFix(point(), 5.0, 14_999), 30_001, epoch).rejection)
        assertEquals(15_000L, m.snapshot.lastUsableDeliveredAtMillis)
        m.tick(315_000)
        assertFalse(m.locationRequestActive)
    }

    @Test fun unusableSampleCannotBeReplayedWithAnEditedAccuracy() {
        val m = entered()
        observe(m, 60_000, accuracy = 200.0)
        assertEquals(FixRejection.DUPLICATE_OR_OUT_OF_ORDER, observe(m, 60_000).rejection)
        assertEquals(15_000L, m.snapshot.lastUsableDeliveredAtMillis)
    }

    @Test fun freshLivenessHoldsForFifteenMinutesButDoesNotRenewInsideSupport() {
        val m = entered()
        for (t in 75_000L until 915_000L step 60_000L) {
            observe(m, t, accuracy = 70.0)
            assertTrue(m.locationRequestActive)
        }
        observe(m, 915_000, accuracy = 70.0)
        assertEquals(PresenceReason.AMBIGUITY_LIMIT, m.snapshot.reason)
        assertEquals(PresenceState.INACTIVE_NEEDS_REARM, m.snapshot.state)
        assertFalse(m.locationRequestActive)
    }

    @Test fun strictInsideCanRefreshAmbiguityBudgetButNeverTheVisitCap() {
        val m = entered()
        for (t in 75_000L until 10_815_000L step 60_000L) {
            observe(m, t)
            assertTrue(m.locationRequestActive)
        }
        observe(m, 10_815_000)
        assertEquals(PresenceReason.VISIT_CAP, m.snapshot.reason)
        assertEquals(10_815_000L, m.snapshot.visitCapAtMillis)
        assertFalse(m.locationRequestActive)
    }

    @Test fun outsideNeedsTwoSamplesWithTwentySecondDwell() {
        val m = entered()
        observe(m, 60_000, 200.0)
        assertEquals(PresenceState.EXITING, m.snapshot.state)
        observe(m, 79_999, 200.0)
        assertTrue(m.locationRequestActive)
        observe(m, 80_000, 200.0)
        assertEquals(PresenceReason.CONFIRMED_EXIT, m.snapshot.reason)
        assertFalse(m.locationRequestActive)
    }

    @Test fun ambiguousFixResetsOutsideDwellAndInsideRestoresConfirmedState() {
        val m = entered()
        observe(m, 60_000, 200.0)
        observe(m, 75_000, 98.0)
        observe(m, 80_000, 200.0)
        assertTrue(m.locationRequestActive)
        observe(m, 90_000)
        assertEquals(PresenceState.VISIT_CONFIRMED, m.snapshot.state)
        assertTrue(m.locationRequestActive)
    }

    @Test fun movingIntoAnotherEnabledAreaKeepsOneBoundedVisitWithoutAudioGap() {
        val m = entered(listOf(area("a"), area("b", 100.0, 300.0)))
        observe(m, 60_000, 300.0)
        assertTrue(m.locationRequestActive)
        assertEquals(setOf("b"), m.snapshot.supportingAreaIds)
        assertEquals(15_000L, m.snapshot.enteredAtMillis)
    }

    @Test fun coarseExitIsAnEvaluationHintWithNoExitAuthorityOrRenewal() {
        val m = entered()
        m.coarseExitHint(100_000, epoch)
        assertTrue(m.locationRequestActive)
        assertTrue(m.snapshot.exitEvaluationRequested)
        assertEquals(15_000L, m.snapshot.lastUsableDeliveredAtMillis)
        m.tick(315_000)
        assertFalse(m.locationRequestActive)
        assertEquals(PresenceReason.NO_OBSERVATION, m.snapshot.reason)
    }

    @Test fun noFixExpiryCannotImmediatelyReacquireTheUnresolvedVisit() {
        val m = entered(); m.tick(315_000)
        observe(m, 315_001); observe(m, 330_001)
        assertFalse(m.locationRequestActive)
        assertFalse(m.beginAcquisition(330_002, epoch))
        assertFalse(m.beginAcquisition(330_003, epoch, deliberateRearm = true, route = StartRoute.GEOFENCE_EVENT))
    }

    @Test fun explicitRearmRequiresNewEntryEvidenceAfterTheAction() {
        val m = entered(); m.stop(16_000)
        assertTrue(m.beginAcquisition(20_000, epoch, deliberateRearm = true))
        assertEquals(FixRejection.AGE, m.observe(ProbeFix(point(), 5.0, 15_000), 20_000, epoch).rejection)
        observe(m, 20_001); observe(m, 35_001)
        assertTrue(m.locationRequestActive)
    }

    @Test fun stopSurvivesInsideAndCoarseExitUntilConfidentDeparture() {
        val m = entered(); m.stop(16_000)
        observe(m, 20_000); m.coarseExitHint(21_000, epoch)
        assertEquals(PresenceState.SUPPRESSED, m.snapshot.state)
        assertFalse(m.locationRequestActive)
        observe(m, 40_000, 200.0); observe(m, 60_000, 200.0)
        assertEquals(PresenceState.IDLE, m.snapshot.state)
        assertTrue(m.beginAcquisition(60_001, epoch, route = StartRoute.GEOFENCE_EVENT))
        observe(m, 60_001); observe(m, 75_001)
        assertTrue(m.locationRequestActive)
    }

    @Test fun suppressionExpiresIntoNeedsRearmInsteadOfSilentlyRestarting() {
        val m = entered(); m.stop(16_000)
        m.tick(21_616_000)
        assertEquals(PresenceReason.SUPPRESSION_EXPIRED, m.snapshot.reason)
        assertEquals(PresenceState.INACTIVE_NEEDS_REARM, m.snapshot.state)
        observe(m, 21_616_001)
        assertFalse(m.beginAcquisition(21_616_002, epoch))
        assertFalse(m.locationRequestActive)
    }

    @Test fun departureAfterUncertaintyExpiryCanRearmWithoutDiscardingBarrierEarly() {
        val m = entered(); m.tick(315_000)
        observe(m, 320_000, 200.0); observe(m, 340_000, 200.0)
        assertEquals(PresenceState.IDLE, m.snapshot.state)
        assertTrue(m.beginAcquisition(340_001, epoch, route = StartRoute.GEOFENCE_EVENT))
    }

    @Test fun permissionLossReleasesAndPermissionRestoreCannotReuseOldVisit() {
        val m = entered()
        val lost = epoch.copy(permissionGeneration = 1)
        m.replaceEnvironment(20_000, lost, ready = false)
        assertFalse(m.locationRequestActive)
        assertEquals(PresenceState.INACTIVE_NEEDS_SETUP, m.snapshot.state)
        assertEquals(FixRejection.OBSOLETE_EPOCH, m.observe(ProbeFix(point(), 5.0, 20_001), 20_001, epoch).rejection)
        val restored = lost.copy(permissionGeneration = 2)
        m.replaceEnvironment(21_000, restored, ready = true)
        observe(m, 21_001)
        assertEquals(PresenceState.INACTIVE_NEEDS_REARM, m.snapshot.state)
        assertFalse(m.locationRequestActive)
    }

    @Test fun geometryRevisionAndAllObsoleteGenerationsRejectOldCallbacks() {
        for (replacement in listOf(
            epoch.copy(modeGeneration = 1), epoch.copy(permissionGeneration = 1),
            epoch.copy(geometryGeneration = 1), epoch.copy(requestGeneration = 1), epoch.copy(sessionId = "session-b"),
        )) {
            val m = entered()
            m.replaceEnvironment(20_000, replacement, areas = listOf(area(revision = 2)))
            assertFalse(m.locationRequestActive)
            assertEquals(FixRejection.OBSOLETE_EPOCH, m.observe(ProbeFix(point(), 5.0, 20_001), 20_001, epoch).rejection)
            observe(m, 21_000)
            assertFalse(m.locationRequestActive)
        }
    }

    @Test fun environmentAndRecoveryCannotReuseTheOldCallbackIdentity() {
        val m = entered()
        assertThrows(IllegalArgumentException::class.java) { m.replaceEnvironment(20_000, epoch, ready = false) }
        assertThrows(IllegalArgumentException::class.java) {
            PresenceMachine.recovered(m.snapshot, epoch.copy(requestGeneration = 1), listOf(area()), 20_000)
        }
        assertTrue(m.locationRequestActive)
    }

    @Test fun unavailableAreaStoreReleasesRatherThanProvingAnExit() {
        val m = entered()
        m.replaceEnvironment(20_000, epoch.copy(geometryGeneration = 1), storeAvailable = false)
        assertFalse(m.locationRequestActive)
        assertEquals(PresenceState.INACTIVE_NEEDS_SETUP, m.snapshot.state)
        assertTrue(m.snapshot.requiresDepartureOrExplicitRearm)
    }

    @Test fun processRecoveryNeverRevivesAnInsideFlag() {
        val before = entered()
        val after = PresenceMachine.recovered(before.snapshot, epoch.copy(sessionId = "process-b"), listOf(area()), 20_000)
        assertEquals(PresenceState.INACTIVE_NEEDS_REARM, after.snapshot.state)
        observe(after, 20_001); observe(after, 35_001)
        assertFalse(after.locationRequestActive)
    }

    @Test fun sameBootRecoveryRetainsSuppressionDeadline() {
        val before = entered(); before.stop(16_000)
        val after = PresenceMachine.recovered(before.snapshot, epoch.copy(sessionId = "process-b"), listOf(area()), 20_000)
        assertEquals(PresenceState.SUPPRESSED, after.snapshot.state)
        assertEquals(21_616_000L, after.snapshot.suppressionDeadlineMillis)
        after.tick(21_616_000)
        assertEquals(PresenceState.INACTIVE_NEEDS_REARM, after.snapshot.state)
    }

    @Test fun rebootDiscardsOldElapsedDeadlinesAndRequiresRearm() {
        val before = entered(); before.stop(16_000)
        val after = PresenceMachine.recovered(before.snapshot, EventEpoch("boot-b", "session-b"), listOf(area()), 5)
        assertEquals(PresenceState.INACTIVE_NEEDS_REARM, after.snapshot.state)
        assertNull(after.snapshot.suppressionDeadlineMillis)
        assertNull(after.snapshot.enteredAtMillis)
        observe(after, 10); observe(after, 15_010)
        assertFalse(after.locationRequestActive)
        assertEquals(FixRejection.OBSOLETE_EPOCH, after.observe(ProbeFix(point(), 5.0, 20_000), 20_000, epoch).rejection)
    }

    @Test fun monotonicRollbackCannotChangeAuthorityOrExtendDeadlines() {
        val m = entered()
        m.tick(100_000)
        assertEquals(FixRejection.CLOCK, observe(m, 99_999).rejection)
        assertEquals(15_000L, m.snapshot.lastUsableDeliveredAtMillis)
        m.tick(315_000)
        assertFalse(m.locationRequestActive)
    }

    @Test fun eligibleWakeSuppressesTheRestOfTheVisitAndDoesNotResumeOnCompletion() {
        val m = entered()
        m.onWake(16_000, eligible = true, insidePrayerWindow = true)
        assertEquals(PresenceReason.WAKE_EXCEPTION, m.snapshot.reason)
        assertEquals(PresenceState.SUPPRESSED, m.snapshot.state)
        observe(m, 20_000); observe(m, 35_000)
        m.tick(40_000)
        assertFalse(m.locationRequestActive)
    }

    @Test fun ordinaryMosqueOnlyWakeOutsidePrayerEndsVisitButInsideWindowDoesNot() {
        val outside = entered()
        outside.onWake(16_000, eligible = false, insidePrayerWindow = false)
        assertEquals(PresenceState.SUPPRESSED, outside.snapshot.state)
        val inside = entered()
        inside.onWake(16_000, eligible = false, insidePrayerWindow = true)
        assertTrue(inside.locationRequestActive)
        val combined = entered()
        combined.onWake(16_000, eligible = false, insidePrayerWindow = false, mode = ProbeMode.TIME_AND_MOSQUE)
        assertTrue(combined.locationRequestActive)
    }

    @Test fun acceleratedTradeoffScenariosExposeBothFalseReleaseAndLateDepartureRelease() {
        val actualInsideButNoFix = entered()
        actualInsideButNoFix.tick(315_000)
        assertEquals(300_000L, 315_000L - requireNotNull(actualInsideButNoFix.snapshot.enteredAtMillis))
        assertEquals(PresenceReason.NO_OBSERVATION, actualInsideButNoFix.snapshot.reason)
        val departedAt16SecondsButAmbiguous = entered()
        for (t in 75_000L..915_000L step 60_000L) observe(departedAt16SecondsButAmbiguous, t, x = 300.0, accuracy = 70.0)
        assertFalse(departedAt16SecondsButAmbiguous.locationRequestActive)
        assertEquals(PresenceReason.AMBIGUITY_LIMIT, departedAt16SecondsButAmbiguous.snapshot.reason)
        assertEquals(899_000L, 915_000L - 16_000L)
        val confidentlyDeparted = entered()
        observe(confidentlyDeparted, 16_000, 200.0); observe(confidentlyDeparted, 36_000, 200.0)
        assertEquals(PresenceReason.CONFIRMED_EXIT, confidentlyDeparted.snapshot.reason)
    }
}
