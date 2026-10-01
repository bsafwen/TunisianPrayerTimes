package com.tunisianprayertimes.tv.kiosk

import com.tunisianprayertimes.mosque.FlowPhase
import java.time.Duration
import java.time.LocalDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceRestartTest {

    private val isha = LocalDateTime.of(2026, 9, 29, 19, 42)
    private val fajr = LocalDateTime.of(2026, 9, 30, 4, 47)
    private val long = Duration.ofHours(21)

    private fun due(now: LocalDateTime, uptime: Duration = long, phase: FlowPhase = FlowPhase.IDLE, ramadan: Boolean = false) =
        MaintenanceRestart.isDue(now, uptime, phase, isha, fajr, ramadan)

    @Test
    fun aLongRunRestartsBetweenIshaAndFajr() {
        assertFalse(due(isha.plusMinutes(89)))
        assertTrue(due(isha.plusMinutes(90)))
        assertTrue(due(fajr.minusMinutes(61)))
        assertFalse(due(fajr.minusMinutes(60)))
    }

    @Test
    fun neverAfterAShortRunOrDuringAPrayerScreen() {
        assertFalse(due(isha.plusHours(3), uptime = Duration.ofHours(19)))
        assertFalse(due(isha.plusHours(3), phase = FlowPhase.SALAH))
        assertFalse(MaintenanceRestart.isDue(isha.plusHours(3), long, FlowPhase.IDLE, null, fajr, ramadan = false))
    }

    @Test
    fun neverUnderTheAdminsHands() {
        // An imam editing on the phone after Isha, or a key's images being copied: the restart waits.
        assertFalse(MaintenanceRestart.isDue(isha.plusMinutes(90), long, FlowPhase.IDLE, isha, fajr, ramadan = false, adminBusy = true))
        assertTrue(MaintenanceRestart.isDue(isha.plusMinutes(120), long, FlowPhase.IDLE, isha, fajr, ramadan = false, adminBusy = false))
    }

    @Test
    fun neverInTheDaytimeBetweenYesterdaysIshaAndTomorrowsFajr() {
        // 10:00: the last Isha was yesterday's and the next Fajr is tomorrow's.
        val yesterdayIsha = LocalDateTime.of(2026, 9, 28, 19, 43)
        val tomorrowFajr = LocalDateTime.of(2026, 9, 30, 4, 47)
        assertFalse(MaintenanceRestart.isDue(LocalDateTime.of(2026, 9, 29, 10, 0), long, FlowPhase.IDLE, yesterdayIsha, tomorrowFajr, ramadan = false))
    }

    @Test
    fun onARamadanNightItWaitsForTheTarawihAndTheNightScreen() {
        // Tarawih after the 19:42 iqamah: the night screen opens at 21:42, the restart half an hour later.
        assertFalse(due(isha.plusMinutes(90), ramadan = true))
        assertFalse(due(isha.plusMinutes(149), ramadan = true))
        assertTrue(due(isha.plusMinutes(150), ramadan = true))
        assertTrue(isha.plus(MaintenanceRestart.AFTER_ISHA_RAMADAN) > isha.plus(com.tunisianprayertimes.tv.ui.display.NightWindow.AFTER_ISHA_RAMADAN))
    }

    @Test
    fun aLongTarawihOnlyShortensTheWindow() {
        // Isha iqamah at 19:42 with 75 minutes of tarawih: the phase is SALAH until 20:57.
        assertFalse(due(isha.plusMinutes(70), phase = FlowPhase.SALAH))
        assertTrue(due(isha.plusMinutes(120)))
    }
}
