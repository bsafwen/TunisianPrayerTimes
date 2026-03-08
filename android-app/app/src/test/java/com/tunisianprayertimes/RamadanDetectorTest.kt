package com.tunisianprayertimes

import org.junit.Assert.*
import org.junit.Test
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

class RamadanDetectorTest {

    @Test
    fun isRamadan_returnsBoolean() {
        val result = RamadanDetector.isRamadan()
        assertNotNull(result)
    }

    @Test
    fun duringRamadan_returnsTrue() {
        // Ramadan 15 (middle of Ramadan)
        val ramadan15 = HijrahDate.now()
            .with(ChronoField.MONTH_OF_YEAR, 9)
            .with(ChronoField.DAY_OF_MONTH, 15)
        assertTrue("Mid-Ramadan should be detected", RamadanDetector.isRamadan(ramadan15))
    }

    @Test
    fun firstDayOfRamadan_returnsTrue() {
        val ramadan1 = HijrahDate.now()
            .with(ChronoField.MONTH_OF_YEAR, 9)
            .with(ChronoField.DAY_OF_MONTH, 1)
        assertTrue("First day of Ramadan should be detected", RamadanDetector.isRamadan(ramadan1))
    }

    @Test
    fun lastDayOfRamadan_returnsTrue() {
        val ramadanLast = HijrahDate.now()
            .with(ChronoField.MONTH_OF_YEAR, 9)
        val lastDay = ramadanLast.lengthOfMonth()
        val date = ramadanLast.with(ChronoField.DAY_OF_MONTH, lastDay.toLong())
        assertTrue("Last day of Ramadan should be detected", RamadanDetector.isRamadan(date))
    }

    @Test
    fun lastDayOfShaaban_returnsTrue_buffer() {
        // Last day of Sha'ban (month 8) — buffer for early start
        val shaaban = HijrahDate.now()
            .with(ChronoField.MONTH_OF_YEAR, 8)
        val lastDay = shaaban.lengthOfMonth()
        val date = shaaban.with(ChronoField.DAY_OF_MONTH, lastDay.toLong())
        assertTrue("Last day of Sha'ban should be detected (buffer)", RamadanDetector.isRamadan(date))
    }

    @Test
    fun firstDayOfShawwal_returnsTrue_buffer() {
        // First day of Shawwal (month 10) — buffer for late end
        val shawwal1 = HijrahDate.now()
            .with(ChronoField.MONTH_OF_YEAR, 10)
            .with(ChronoField.DAY_OF_MONTH, 1)
        assertTrue("First day of Shawwal should be detected (buffer)", RamadanDetector.isRamadan(shawwal1))
    }

    @Test
    fun afterRamadan_returnsFalse() {
        // Shawwal 2 — banner should disappear
        val shawwal2 = HijrahDate.now()
            .with(ChronoField.MONTH_OF_YEAR, 10)
            .with(ChronoField.DAY_OF_MONTH, 2)
        assertFalse("Shawwal 2 (after Ramadan) should not be detected", RamadanDetector.isRamadan(shawwal2))
    }

    @Test
    fun wellAfterRamadan_returnsFalse() {
        // Shawwal 15 — well past Ramadan
        val shawwal15 = HijrahDate.now()
            .with(ChronoField.MONTH_OF_YEAR, 10)
            .with(ChronoField.DAY_OF_MONTH, 15)
        assertFalse("Shawwal 15 should not be detected", RamadanDetector.isRamadan(shawwal15))
    }

    @Test
    fun beforeRamadan_returnsFalse() {
        // Sha'ban 1 — well before Ramadan
        val shaaban1 = HijrahDate.now()
            .with(ChronoField.MONTH_OF_YEAR, 8)
            .with(ChronoField.DAY_OF_MONTH, 1)
        assertFalse("Sha'ban 1 should not be detected", RamadanDetector.isRamadan(shaaban1))
    }

    @Test
    fun dhulHijjah_returnsFalse() {
        // Dhul Hijjah (month 12) — completely unrelated month
        val dhulHijjah = HijrahDate.now()
            .with(ChronoField.MONTH_OF_YEAR, 12)
            .with(ChronoField.DAY_OF_MONTH, 10)
        assertFalse("Dhul Hijjah should not be detected", RamadanDetector.isRamadan(dhulHijjah))
    }
}
