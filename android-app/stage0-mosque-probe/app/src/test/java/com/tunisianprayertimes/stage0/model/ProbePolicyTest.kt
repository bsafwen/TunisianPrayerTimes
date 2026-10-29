package com.tunisianprayertimes.stage0.model

import org.junit.Assert.*
import org.junit.Test

class ProbePolicyTest {
    @Test fun allThreeModesFollowTheirTruthTablesWithoutFallback() {
        for (prayer in listOf(false, true)) for (presence in listOf(false, true)) {
            val expected = mapOf(
                ProbeMode.TIME_ONLY to prayer,
                ProbeMode.TIME_AND_MOSQUE to (prayer && presence),
                ProbeMode.MOSQUE_ONLY to presence,
            )
            for ((mode, enabled) in expected) assertEquals("$mode T=$prayer P=$presence", enabled,
                ProbePolicy.decide(mode, true, true, prayer, presence).requestsSilence)
        }
    }

    @Test fun missingReadinessOrMasterNeverStartsAutomaticRequest() {
        for (mode in ProbeMode.entries) {
            assertFalse(ProbePolicy.decide(mode, false, true, true, true).requestsSilence)
            assertFalse(ProbePolicy.decide(mode, true, false, true, true).requestsSilence)
        }
    }

    @Test fun lossOfPresencePrayerReadinessOrMasterCannotRemoveOtherSafeOwners() {
        val retained = setOf(ProbeOwner.MANUAL, ProbeOwner.UNTIL_ALARM)
        for (mode in ProbeMode.entries) for (prayer in listOf(false, true)) for (presence in listOf(false, true)) {
            val decision = ProbePolicy.decide(mode, false, false, prayer, presence, retained)
            assertEquals(retained, decision.requestedOwners)
        }
    }

    @Test fun uncertaintyHoldsOnlyAnExistingBoundedVisitRatherThanAuthorizingAcquisition() {
        val epoch = EventEpoch("boot", "session")
        val m = PresenceMachine(epoch, listOf(area()))
        m.beginAcquisition(0, epoch)
        m.observe(ProbeFix(point(), 70.0, 0), 0, epoch)
        assertFalse(ProbePolicy.decide(ProbeMode.TIME_AND_MOSQUE, true, true, true, m.locationRequestActive).requestsSilence)
        m.observe(ProbeFix(point(), 5.0, 1_000), 1_000, epoch)
        m.observe(ProbeFix(point(), 5.0, 16_000), 16_000, epoch)
        m.observe(ProbeFix(point(), 70.0, 60_000), 60_000, epoch)
        assertTrue(ProbePolicy.decide(ProbeMode.TIME_AND_MOSQUE, true, true, true, m.locationRequestActive).requestsSilence)
        assertFalse(ProbePolicy.decide(ProbeMode.TIME_AND_MOSQUE, true, true, false, m.locationRequestActive).requestsSilence)
    }
}
