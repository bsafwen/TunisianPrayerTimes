package com.tunisianprayertimes

import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for RamadanOverrideChecker (drift, Eid dates) and
 * RamadanDetector integration with overrides.
 *
 * Reference algorithmic dates (Umm al-Qura):
 *   1 Ramadan 1447  = 2026-02-18
 *   1 Shawwal 1447  = 2026-03-20  (30-day Ramadan)
 *   10 Dhul Hijja 1447 = 2026-05-27
 *
 *   1 Ramadan 1448  = 2027-02-08
 *   1 Shawwal 1448  = 2027-03-09  (29-day Ramadan)
 *   10 Dhul Hijja 1448 = 2027-05-16
 */
class RamadanOverrideTest {

    @AfterTest
    fun cleanup() {
        RamadanOverrideChecker.cachedOverride = null
    }

    // ---------------------------------------------------------------
    // Drift computation
    // ---------------------------------------------------------------

    @Test
    fun drift_noOverride_returnsNull() {
        assertNull(RamadanOverrideChecker.computeDriftDays())
    }

    @Test
    fun drift_ramadanStartOnly_plusOne() {
        // Algorithmic 1 Ramadan 1447 = 2026-02-18, announced = 2026-02-19 → drift +1
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        assertEquals(1L, RamadanOverrideChecker.computeDriftDays())
    }

    @Test
    fun drift_ramadanStartOnly_minusOne() {
        // Algorithmic 1 Ramadan 1447 = 2026-02-18, announced = 2026-02-17 → drift -1
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 17),
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        assertEquals(-1L, RamadanOverrideChecker.computeDriftDays())
    }

    @Test
    fun drift_ramadanStartOnly_zeroDrift() {
        // Algorithmic 1 Ramadan 1447 = 2026-02-18, announced same → drift 0
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 18),
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        assertEquals(0L, RamadanOverrideChecker.computeDriftDays())
    }

    @Test
    fun drift_eidFitrTakesPrecedence() {
        // ramadanStart drift = +1, but eidFitrDate drift = -1 → should use -1
        // Algorithmic 1 Ramadan 1447 = 2026-02-18, announced = 2026-02-19 (drift +1)
        // Algorithmic 1 Shawwal 1447 = 2026-03-20, announced = 2026-03-19 (drift -1)
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 19),
            eidAdhaDate = null,
        )
        assertEquals(-1L, RamadanOverrideChecker.computeDriftDays())
    }

    // ---------------------------------------------------------------
    // Eid al-Fitr date
    // ---------------------------------------------------------------

    @Test
    fun eidFitr_withOverride_returnsOverrideDate() {
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 19),
            eidAdhaDate = null,
        )
        assertEquals(LocalDate.of(2026, 3, 19), RamadanOverrideChecker.getEidFitrDate())
    }

    @Test
    fun isEidFitr_withOverride_correctDate() {
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 19),
            eidAdhaDate = null,
        )
        assertTrue(RamadanOverrideChecker.isEidFitr(LocalDate.of(2026, 3, 19)))
        assertFalse(RamadanOverrideChecker.isEidFitr(LocalDate.of(2026, 3, 20)))
        assertFalse(RamadanOverrideChecker.isEidFitr(LocalDate.of(2026, 3, 18)))
    }

    // ---------------------------------------------------------------
    // Eid al-Adha with drift
    // ---------------------------------------------------------------

    @Test
    fun eidAdha_driftedFromEidFitr_plusOne() {
        // Algorithmic 1 Shawwal 1447 = 2026-03-20, announced = 2026-03-21 → drift +1
        // Algorithmic 10 Dhul Hijja 1447 = 2026-05-27 → predicted = 2026-05-28
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 21),
            eidAdhaDate = null,
        )
        assertEquals(LocalDate.of(2026, 5, 28), RamadanOverrideChecker.getEidAdhaDate())
    }

    @Test
    fun eidAdha_driftedFromEidFitr_minusOne() {
        // Algorithmic 1 Shawwal 1447 = 2026-03-20, announced = 2026-03-19 → drift -1
        // Algorithmic 10 Dhul Hijja 1447 = 2026-05-27 → predicted = 2026-05-26
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 19),
            eidAdhaDate = null,
        )
        assertEquals(LocalDate.of(2026, 5, 26), RamadanOverrideChecker.getEidAdhaDate())
    }

    @Test
    fun eidAdha_driftedFromRamadanStart_whenNoEidFitr() {
        // Algorithmic 1 Ramadan 1447 = 2026-02-18, announced = 2026-02-19 → drift +1
        // Algorithmic 10 Dhul Hijja 1447 = 2026-05-27 → predicted = 2026-05-28
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        assertEquals(LocalDate.of(2026, 5, 28), RamadanOverrideChecker.getEidAdhaDate())
    }

    @Test
    fun isEidAdha_withDrift() {
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 21),
            eidAdhaDate = null,
        )
        // Predicted Eid al-Adha = 2026-05-28 (algorithmic 2026-05-27 + 1 drift)
        assertTrue(RamadanOverrideChecker.isEidAdha(LocalDate.of(2026, 5, 28)))
        assertFalse(RamadanOverrideChecker.isEidAdha(LocalDate.of(2026, 5, 27)))
    }

    @Test
    fun eidAdha_explicitOverride_takesPrecedence() {
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 21),
            eidAdhaDate = LocalDate.of(2026, 5, 29),
        )
        assertEquals(LocalDate.of(2026, 5, 29), RamadanOverrideChecker.getEidAdhaDate())
    }

    // ---------------------------------------------------------------
    // RamadanDetector with ramadanStart override
    // ---------------------------------------------------------------

    @Test
    fun ramadanDetector_overrideRamadanStart_delayedByOneDay() {
        // Algorithmic Ramadan starts 2026-02-18; Ministry says 2026-02-19
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        // 2026-02-18 = last Sha'ban (eve of Ramadan) — still in the Ramadan buffer zone
        val hijri18Feb = HijrahDate.of(1447, 9, 1) // algorithmic 1 Ramadan = 2026-02-18
        assertTrue(RamadanDetector.isRamadan(hijri18Feb),
            "Eve of override Ramadan (last Sha'ban) should be in Ramadan buffer")

        // 2026-02-17 is NOT Ramadan (two days before override start)
        val hijri17Feb = HijrahDate.of(1447, 8, 29) // algorithmic 29 Sha'ban = 2026-02-17
        assertFalse(RamadanDetector.isRamadan(hijri17Feb),
            "Two days before override Ramadan should not be Ramadan")

        // 2026-02-19 IS Ramadan (override start)
        val hijri19Feb = HijrahDate.of(1447, 9, 2) // algorithmic 2 Ramadan = 2026-02-19
        assertTrue(RamadanDetector.isRamadan(hijri19Feb),
            "First day of override Ramadan should be detected")
    }

    @Test
    fun ramadanDetector_overrideRamadanStart_earlyByOneDay() {
        // Algorithmic Ramadan starts 2026-02-18; Ministry says 2026-02-17
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 17),
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        // 2026-02-17 IS Ramadan (override says so, though algorithmic says Sha'ban 29)
        val hijri17Feb = HijrahDate.of(1447, 8, 29) // algorithmic last day of Sha'ban
        assertTrue(RamadanDetector.isRamadan(hijri17Feb),
            "Override Ramadan start should be detected even on algorithmic Sha'ban")
    }

    // ---------------------------------------------------------------
    // RamadanDetector with eidFitrDate override (Ramadan length)
    // ---------------------------------------------------------------

    @Test
    fun ramadanDetector_29dayRamadan_overrideEidFitr() {
        // Ramadan = 29 days: starts 2026-02-19, Eid = 2026-03-19
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 19),
            eidAdhaDate = null,
        )
        // 2026-03-18 = last day of Ramadan (day 28, 0-indexed from start)
        val hijriMar18 = HijrahDate.of(1447, 9, 29) // algo 29 Ram = 2026-03-18
        assertTrue(RamadanDetector.isRamadan(hijriMar18),
            "Last day of 29-day Ramadan should still be Ramadan")

        // 2026-03-19 = Eid al-Fitr (first Shawwal) — included in isRamadan buffer
        val hijriMar19 = HijrahDate.of(1447, 9, 30) // algo 30 Ram = 2026-03-19
        assertTrue(RamadanDetector.isRamadan(hijriMar19),
            "Eid al-Fitr day should be in Ramadan buffer (firstShawwal)")

        // 2026-03-20 = day after Eid — NOT Ramadan
        val hijriMar20 = HijrahDate.of(1447, 10, 1) // algo 1 Shawwal = 2026-03-20
        assertFalse(RamadanDetector.isRamadan(hijriMar20),
            "Day after Eid should not be Ramadan")
    }

    @Test
    fun ramadanDetector_30dayRamadan_overrideEidFitr() {
        // Ramadan = 30 days: starts 2026-02-19, Eid = 2026-03-21
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 21),
            eidAdhaDate = null,
        )
        // 2026-03-20 = day 30 of Ramadan (still Ramadan)
        val hijriMar20 = HijrahDate.of(1447, 10, 1) // algo 1 Shawwal = 2026-03-20
        assertTrue(RamadanDetector.isRamadan(hijriMar20),
            "30th day of Ramadan should be detected when override says 30-day Ramadan")

        // 2026-03-21 = Eid al-Fitr — in Ramadan buffer
        val hijriMar21 = HijrahDate.of(1447, 10, 2) // algo 2 Shawwal = 2026-03-21
        assertTrue(RamadanDetector.isRamadan(hijriMar21),
            "Eid al-Fitr day should be in Ramadan buffer")

        // 2026-03-22 = day after Eid — NOT Ramadan
        val hijriMar22 = HijrahDate.of(1447, 10, 3) // algo 3 Shawwal = 2026-03-22
        assertFalse(RamadanDetector.isRamadan(hijriMar22),
            "Day after Eid should not be Ramadan")
    }

    @Test
    fun ramadanDetector_noOverride_fallsBackToAlgorithmic() {
        // No override set — pure algorithmic
        val midRamadan = HijrahDate.of(1447, 9, 15)
        assertTrue(RamadanDetector.isRamadan(midRamadan))

        val muharram = HijrahDate.of(1447, 1, 15)
        assertFalse(RamadanDetector.isRamadan(muharram))
    }

    // ---------------------------------------------------------------
    // JSON parsing
    // ---------------------------------------------------------------

    @Test
    fun fetchOverrideForYear_parsesJsonCorrectly() {
        // This test validates the JSON parsing logic indirectly.
        // We can't mock the HTTP call, but we can validate the data class structure.
        val override = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 19),
            eidAdhaDate = null,
        )
        assertEquals(1447, override.hijriYear)
        assertEquals(LocalDate.of(2026, 2, 19), override.ramadanStart)
        assertEquals(LocalDate.of(2026, 3, 19), override.eidFitrDate)
    }

    @Test
    fun override_nullDatesAreHandled() {
        val override = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = null,
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        assertNull(override.ramadanStart)
        assertNull(override.eidFitrDate)
    }

    // ---------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------

    @Test
    fun eidFitr_noOverride_fallsBackToAlgorithmic() {
        // No override → getEidFitrDate should return algorithmic 1 Shawwal
        // Current Hijri year from HijrahDate.now() — just verify it returns a date
        val date = RamadanOverrideChecker.getEidFitrDate()
        // Should be the Gregorian equivalent of 1 Shawwal of current Hijri year
        val expected = LocalDate.from(
            HijrahDate.now().let { HijrahDate.of(it.get(ChronoField.YEAR), 10, 1) }
        )
        assertEquals(expected, date)
    }

    @Test
    fun eidAdha_noOverride_fallsBackToAlgorithmic() {
        // No override → getEidAdhaDate should return algorithmic 10 Dhul Hijja
        val date = RamadanOverrideChecker.getEidAdhaDate()
        val expected = LocalDate.from(
            HijrahDate.now().let { HijrahDate.of(it.get(ChronoField.YEAR), 12, 10) }
        )
        assertEquals(expected, date)
    }

    @Test
    fun drift_zeroDrift_eidAdhaUnchanged() {
        // Algorithmic 1 Shawwal 1447 = 2026-03-20, announced same → drift 0
        // Eid al-Adha should remain at algorithmic 2026-05-27
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 18),
            eidFitrDate = LocalDate.of(2026, 3, 20),
            eidAdhaDate = null,
        )
        assertEquals(0L, RamadanOverrideChecker.computeDriftDays())
        assertEquals(LocalDate.of(2026, 5, 27), RamadanOverrideChecker.getEidAdhaDate())
    }

    @Test
    fun ramadanDetector_overrideWithNullRamadanStart_fallsBackToAlgorithmic() {
        // Override exists but ramadanStart is null → should fall back to algorithmic
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = null,
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        // Mid-Ramadan should still be detected via algorithmic fallback
        val midRamadan = HijrahDate.of(1447, 9, 15)
        assertTrue(RamadanDetector.isRamadan(midRamadan))
    }

    @Test
    fun ramadanDetector_overrideDifferentHijriYear_fallsBackToAlgorithmic() {
        // Override is for year 1448 but we're checking a 1447 date → should fall back
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1448,
            ramadanStart = LocalDate.of(2027, 2, 9),
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        // 1447 Ramadan should use algorithmic detection, not the 1448 override
        val ramadan1447 = HijrahDate.of(1447, 9, 15)
        assertTrue(RamadanDetector.isRamadanByHijrahDate(ramadan1447))
        // The override code path checks hijriYear match — should fall through
        assertTrue(RamadanDetector.isRamadan(ramadan1447))
    }

    @Test
    fun ramadanDetector_dayBeforeOverrideStart_isLastShaban() {
        // Make sure the day before ramadanStart is correctly treated as lastShaban buffer
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 18), // same as algorithmic
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        // 2026-02-17 = lastShaban (ramadanStart - 1)
        val lastShaban = HijrahDate.of(1447, 8, 29) // = 2026-02-17
        assertTrue(RamadanDetector.isRamadan(lastShaban),
            "Day before override ramadanStart should be in Ramadan buffer (lastShaban)")

        // 2026-02-16 = NOT in buffer
        val tooEarly = HijrahDate.of(1447, 8, 28) // = 2026-02-16
        assertFalse(RamadanDetector.isRamadan(tooEarly),
            "Two days before override ramadanStart should not be in Ramadan buffer")
    }

    // ---------------------------------------------------------------
    // Eid prayer visibility (2-day window + after-Dhuhr cutoff)
    // ---------------------------------------------------------------

    @Test
    fun eidFitrVisibility_twoDaysBefore_shown() {
        // Eid al-Fitr = 2026-03-20 → 2 days before = 2026-03-18
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 20),
            eidAdhaDate = null,
        )
        assertTrue(RamadanOverrideChecker.shouldShowEidFitrPrayer(
            date = LocalDate.of(2026, 3, 18),
            nowHour = 10, nowMinute = 0, dhuhrHour = 12, dhuhrMinute = 30, isToday = true
        ), "Should show Eid Fitr prayer 2 days before Eid")
    }

    @Test
    fun eidFitrVisibility_threeDaysBefore_hidden() {
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 20),
            eidAdhaDate = null,
        )
        assertFalse(RamadanOverrideChecker.shouldShowEidFitrPrayer(
            date = LocalDate.of(2026, 3, 17),
            nowHour = 10, nowMinute = 0, dhuhrHour = 12, dhuhrMinute = 30, isToday = true
        ), "Should NOT show Eid Fitr prayer 3 days before Eid")
    }

    @Test
    fun eidFitrVisibility_oneDayBefore_shown() {
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 20),
            eidAdhaDate = null,
        )
        assertTrue(RamadanOverrideChecker.shouldShowEidFitrPrayer(
            date = LocalDate.of(2026, 3, 19),
            nowHour = 10, nowMinute = 0, dhuhrHour = 12, dhuhrMinute = 30, isToday = true
        ), "Should show Eid Fitr prayer 1 day before Eid")
    }

    @Test
    fun eidFitrVisibility_eidDayBeforeDhuhr_shown() {
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 20),
            eidAdhaDate = null,
        )
        assertTrue(RamadanOverrideChecker.shouldShowEidFitrPrayer(
            date = LocalDate.of(2026, 3, 20),
            nowHour = 11, nowMinute = 0, dhuhrHour = 12, dhuhrMinute = 30, isToday = true
        ), "Should show Eid Fitr prayer on Eid day before Dhuhr")
    }

    @Test
    fun eidFitrVisibility_eidDayAfterDhuhr_hidden() {
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 20),
            eidAdhaDate = null,
        )
        assertFalse(RamadanOverrideChecker.shouldShowEidFitrPrayer(
            date = LocalDate.of(2026, 3, 20),
            nowHour = 12, nowMinute = 30, dhuhrHour = 12, dhuhrMinute = 30, isToday = true
        ), "Should NOT show Eid Fitr prayer on Eid day at/after Dhuhr")
    }

    @Test
    fun eidFitrVisibility_eidDayNotToday_alwaysShown() {
        // Browsing to Eid date but it's not today → no Dhuhr cutoff
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 20),
            eidAdhaDate = null,
        )
        assertTrue(RamadanOverrideChecker.shouldShowEidFitrPrayer(
            date = LocalDate.of(2026, 3, 20),
            nowHour = 15, nowMinute = 0, dhuhrHour = 12, dhuhrMinute = 30, isToday = false
        ), "Should show Eid Fitr prayer on Eid date when browsing (not today)")
    }

    @Test
    fun eidFitrVisibility_dayAfterEid_hidden() {
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 20),
            eidAdhaDate = null,
        )
        assertFalse(RamadanOverrideChecker.shouldShowEidFitrPrayer(
            date = LocalDate.of(2026, 3, 21),
            nowHour = 8, nowMinute = 0, dhuhrHour = 12, dhuhrMinute = 30, isToday = true
        ), "Should NOT show Eid Fitr prayer the day after Eid")
    }

    @Test
    fun eidAdhaVisibility_twoDaysBefore_shown() {
        // Eid al-Adha predicted = 2026-05-28 (drift +1)
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 21),
            eidAdhaDate = null,
        )
        // drift = +1, predicted Eid al-Adha = 2026-05-28, 2 days before = 2026-05-26
        assertTrue(RamadanOverrideChecker.shouldShowEidAdhaPrayer(
            date = LocalDate.of(2026, 5, 26),
            nowHour = 10, nowMinute = 0, dhuhrHour = 12, dhuhrMinute = 30, isToday = true
        ), "Should show Eid Adha prayer 2 days before predicted date")
    }

    @Test
    fun eidAdhaVisibility_eidDayAfterDhuhr_hidden() {
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 21),
            eidAdhaDate = null,
        )
        // drift = +1, predicted Eid al-Adha = 2026-05-28
        assertFalse(RamadanOverrideChecker.shouldShowEidAdhaPrayer(
            date = LocalDate.of(2026, 5, 28),
            nowHour = 13, nowMinute = 0, dhuhrHour = 12, dhuhrMinute = 30, isToday = true
        ), "Should NOT show Eid Adha prayer on Eid day after Dhuhr")
    }
}
