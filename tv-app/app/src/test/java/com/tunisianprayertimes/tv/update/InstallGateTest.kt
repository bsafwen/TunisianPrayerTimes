package com.tunisianprayertimes.tv.update

import com.tunisianprayertimes.mosque.FlowPhase
import com.tunisianprayertimes.tv.ui.TvStrings
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallGateTest {

    private val now = LocalDateTime.parse("2026-09-25T12:00")

    @Test
    fun noInstallOnAPrayerScreen() {
        for (phase in listOf(FlowPhase.ADHAN, FlowPhase.IQAMAH_COUNTDOWN, FlowPhase.KHUTBA, FlowPhase.SALAH)) {
            assertEquals(phase.name, TvStrings.UPDATE_WAIT_PRAYER, InstallGate.refusal(phase, now, null))
        }
    }

    @Test
    fun noInstallInTheQuarterHourBeforeAnAdhan() {
        assertEquals(TvStrings.UPDATE_WAIT_ADHAN, InstallGate.refusal(FlowPhase.IDLE, now, now.plusMinutes(14)))
        assertNull(InstallGate.refusal(FlowPhase.IDLE, now, now.plusMinutes(15)))
        assertNull(InstallGate.refusal(FlowPhase.IDLE, now, null))
    }

    @Test
    fun theAdhkarAfterThePrayerMayBeInterrupted() {
        assertNull(InstallGate.refusal(FlowPhase.AFTER_SALAH, now, now.plusHours(3)))
    }
}
