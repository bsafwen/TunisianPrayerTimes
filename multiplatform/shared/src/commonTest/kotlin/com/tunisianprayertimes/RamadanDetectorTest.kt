package com.tunisianprayertimes

import java.time.chrono.HijrahDate
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class RamadanDetectorTest {

    @Test
    fun ramadanMonthIsDetected() {
        // 1st of Ramadan 1447
        val ramadanDate = HijrahDate.of(1447, 9, 15)
        assertTrue(RamadanDetector.isRamadan(ramadanDate))
    }

    @Test
    fun lastDayBeforeRamadanIsDetected() {
        // Last day of Sha'ban (month 8)
        val shabanDate = HijrahDate.of(1447, 8, 1)
        val lastShaban = HijrahDate.of(1447, 8, shabanDate.lengthOfMonth())
        assertTrue(RamadanDetector.isRamadan(lastShaban))
    }

    @Test
    fun firstDayAfterRamadanIsDetected() {
        // 1st of Shawwal (month 10) = Eid al-Fitr
        val shawwalDate = HijrahDate.of(1447, 10, 1)
        assertTrue(RamadanDetector.isRamadan(shawwalDate))
    }

    @Test
    fun normalDayIsNotRamadan() {
        // Middle of Muharram (month 1)
        val normalDate = HijrahDate.of(1447, 1, 15)
        assertFalse(RamadanDetector.isRamadan(normalDate))
    }

    @Test
    fun earlyShawwalIsNotRamadan() {
        // 2nd of Shawwal is NOT Ramadan window
        val date = HijrahDate.of(1447, 10, 2)
        assertFalse(RamadanDetector.isRamadan(date))
    }

    @Test
    fun earlyShabanIsNotRamadan() {
        // Middle of Sha'ban
        val date = HijrahDate.of(1447, 8, 15)
        assertFalse(RamadanDetector.isRamadan(date))
    }
}
