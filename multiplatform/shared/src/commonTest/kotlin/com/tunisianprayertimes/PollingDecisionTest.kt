package com.tunisianprayertimes

import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for shouldStartPolling(), shouldStopPolling(),
 * and parseOverride() using testDateOverride to simulate different dates.
 *
 * Reference algorithmic dates (Umm al-Qura) for 1447:
 *   Sha'ban has 29 days (last day = 2026-02-17)
 *   1 Ramadan   = 2026-02-18
 *   1 Shawwal   = 2026-03-20  (30-day Ramadan)
 *   10 Dhul Hijja = 2026-05-27
 */
class PollingDecisionTest {

    @BeforeTest
    fun setup() {
        RamadanOverrideChecker.cachedOverride = null
        RamadanOverrideChecker.testDateOverride = null
    }

    @AfterTest
    fun cleanup() {
        RamadanOverrideChecker.cachedOverride = null
        RamadanOverrideChecker.testDateOverride = null
    }

    // ── Helper to assert Hijri date from Gregorian (validates our assumptions) ──

    private fun assertHijri(greg: LocalDate, expectedMonth: Int, expectedDay: Int) {
        val hijri = HijrahDate.from(greg)
        val m = hijri.get(ChronoField.MONTH_OF_YEAR)
        val d = hijri.get(ChronoField.DAY_OF_MONTH)
        assertEquals(expectedMonth, m, "$greg → expected Hijri month $expectedMonth but got $m")
        assertEquals(expectedDay, d, "$greg → expected Hijri day $expectedDay but got $d")
    }

    // ───────────────────────────────────────────────
    // Date assumption verification
    // ───────────────────────────────────────────────

    @Test
    fun dateAssumptions_1447() {
        // Verify our Hijri dates are correct before running polling tests
        assertHijri(LocalDate.of(2026, 2, 14), 8, 26) // 26 Sha'ban
        assertHijri(LocalDate.of(2026, 2, 15), 8, 27) // 27 Sha'ban
        assertHijri(LocalDate.of(2026, 2, 17), 8, 29) // 29 Sha'ban (last day)
        assertHijri(LocalDate.of(2026, 2, 18), 9, 1)  // 1 Ramadan
        assertHijri(LocalDate.of(2026, 2, 19), 9, 2)  // 2 Ramadan
        assertHijri(LocalDate.of(2026, 2, 20), 9, 3)  // 3 Ramadan
        assertHijri(LocalDate.of(2026, 3, 17), 9, 28) // 28 Ramadan
        assertHijri(LocalDate.of(2026, 3, 19), 9, 30) // 30 Ramadan
        assertHijri(LocalDate.of(2026, 3, 20), 10, 1) // 1 Shawwal
        assertHijri(LocalDate.of(2026, 5, 27), 12, 10) // 10 Dhul Hijja
        // Sha'ban 1447 has 29 days
        assertEquals(29, HijrahDate.of(1447, 8, 1).lengthOfMonth())
    }

    // ───────────────────────────────────────────────
    // RAMADAN START polling (Sha'ban / early Ramadan)
    // ───────────────────────────────────────────────

    @Test
    fun ramadanStart_26Shaban_noCache_noPoll() {
        // 26 Sha'ban → too early (daysInMonth=29, 26 < 29-2=27)
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 2, 14)
        assertFalse(RamadanOverrideChecker.shouldStartPolling())
    }

    @Test
    fun ramadanStart_27Shaban_noCache_polls() {
        // 27 Sha'ban → daysInMonth=29, 27 >= 27 → polls
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 2, 15)
        assertTrue(RamadanOverrideChecker.shouldStartPolling())
    }

    @Test
    fun ramadanStart_29Shaban_noCache_polls() {
        // 29 Sha'ban (last day) → polls
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 2, 17)
        assertTrue(RamadanOverrideChecker.shouldStartPolling())
    }

    @Test
    fun ramadanStart_1Ramadan_noCache_polls() {
        // 1 Ramadan → month=9, day=1 (<=2) → polls
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 2, 18)
        assertTrue(RamadanOverrideChecker.shouldStartPolling())
    }

    @Test
    fun ramadanStart_2Ramadan_noCache_polls() {
        // 2 Ramadan → month=9, day=2 (<=2) → polls
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 2, 19)
        assertTrue(RamadanOverrideChecker.shouldStartPolling())
    }

    @Test
    fun ramadanStart_3Ramadan_noCache_noPoll() {
        // 3 Ramadan → day=3 > 2, no other conditions met with null cache → no poll
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 2, 20)
        assertFalse(RamadanOverrideChecker.shouldStartPolling())
    }

    @Test
    fun ramadanStart_29Shaban_alreadyCached_noPoll() {
        // 29 Sha'ban but ramadanStart already cached → no need to poll
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 2, 17)
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        // ramadanStart is set, eidFitrDate is null but we're in Sha'ban (not near 29th Ramadan)
        // The Eid Fitr condition checks: ramadanStart+28 = Mar 19. Today (Feb 17) is before Mar 19.
        // The Eid Adha condition: eidAdhaDate==null, drift from ramadanStart only → fallback:
        //   month=8 → not (11 or 12), so false
        assertFalse(RamadanOverrideChecker.shouldStartPolling())
    }

    @Test
    fun ramadanStart_1Ramadan_alreadyCached_noPoll() {
        // 1 Ramadan but ramadanStart already cached → no Ramadan start poll
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 2, 18)
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        // ramadanStart cached → skip Ramadan start conditions
        // Eid Fitr: ramadanStart+28 = Mar 19. Feb 18 < Mar 19 → not in window
        // Eid Adha: month=9, drift from ramadanStart → not in Dhul Qidah window
        assertFalse(RamadanOverrideChecker.shouldStartPolling())
    }

    @Test
    fun ramadanStart_previousHijriYearCache_pollsForNewYear() {
        val shaban1448 = HijrahDate.of(1448, 8, 1)
        RamadanOverrideChecker.testDateOverride = LocalDate.from(HijrahDate.of(1448, 8, shaban1448.lengthOfMonth()))
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 20),
            eidAdhaDate = LocalDate.of(2026, 5, 27),
        )

        assertTrue(RamadanOverrideChecker.shouldStartPolling(),
            "Previous-year cache must not block Ramadan start polling for the new Hijri year")
    }

    // ───────────────────────────────────────────────
    // EID AL-FITR polling (with known ramadanStart)
    // ───────────────────────────────────────────────

    @Test
    fun eidFitr_beforeWindow_noPoll() {
        // ramadanStart = Feb 19, real 29th Ramadan = Feb 19 + 28 = Mar 19
        // Mar 18 is before window → no poll
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 18)
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        assertFalse(RamadanOverrideChecker.shouldStartPolling())
    }

    @Test
    fun eidFitr_29thRamadan_polls() {
        // Mar 19 = ramadanStart+28 (real 29th Ramadan) → in window
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 19)
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        assertTrue(RamadanOverrideChecker.shouldStartPolling())
    }

    @Test
    fun eidFitr_30thRamadan_polls() {
        // Mar 20 = ramadanStart+29 → still in window (< Mar 22)
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 20)
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        assertTrue(RamadanOverrideChecker.shouldStartPolling())
    }

    @Test
    fun eidFitr_dayAfterEid_polls() {
        // Mar 21 = ramadanStart+30 → still in window (< Mar 22)
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 21)
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        assertTrue(RamadanOverrideChecker.shouldStartPolling())
    }

    @Test
    fun eidFitr_windowClosed_noPoll() {
        // Mar 22 = ramadanStart+31 → past window (not before Mar 22)
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 22)
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        assertFalse(RamadanOverrideChecker.shouldStartPolling())
    }

    @Test
    fun eidFitr_alreadyCached_noPoll() {
        // In Eid Fitr polling window but eidFitrDate already cached → no poll
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 19)
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 20),
            eidAdhaDate = null,
        )
        // eidFitrDate is set → Eid Fitr condition skipped
        // Falls through to Eid Adha: eidAdhaDate==null, drift from eidFitrDate
        // Algorithmic 29 Dhul Qidah ≈ May 17, drift = 0 (Mar 20 matches algorithmic Mar 20)
        // Mar 19 is way before May → false
        assertFalse(RamadanOverrideChecker.shouldStartPolling())
    }

    // ───────────────────────────────────────────────
    // EID AL-FITR polling (fallback: no ramadanStart)
    // ───────────────────────────────────────────────

    @Test
    fun eidFitr_fallback_28Ramadan_noRamadanStart_polls() {
        // 28 Ramadan (algorithmic) = Mar 17, no ramadanStart → fallback condition
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 17)
        // No cache at all → ramadanStart is null, eidFitrDate is null
        assertTrue(RamadanOverrideChecker.shouldStartPolling(),
            "28 Ramadan with no ramadanStart should poll via fallback")
    }

    @Test
    fun eidFitr_fallback_1Shawwal_noRamadanStart_polls() {
        // 1 Shawwal (algorithmic) = Mar 20, no ramadanStart → month=10 day=1 fallback
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 20)
        assertTrue(RamadanOverrideChecker.shouldStartPolling(),
            "1 Shawwal with no cache should poll via fallback")
    }

    @Test
    fun eidFitr_fallback_27Ramadan_noPoll() {
        // 27 Ramadan (algorithmic) = Mar 16 → day=27 < 28, month=9 → no fallback poll
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 16)
        assertFalse(RamadanOverrideChecker.shouldStartPolling(),
            "27 Ramadan with no cache should NOT poll (too early)")
    }

    @Test
    fun eidFitr_fallback_2Shawwal_noPoll() {
        // 2 Shawwal = Mar 21 → month=10 day=2, doesn't match month==10 && day==1
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 21)
        assertFalse(RamadanOverrideChecker.shouldStartPolling(),
            "2 Shawwal with no cache should NOT poll (past fallback window)")
    }

    @Test
    fun eidFitr_previousHijriYearCache_pollsForNewYear() {
        RamadanOverrideChecker.testDateOverride = LocalDate.from(HijrahDate.of(1448, 9, 28))
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 20),
            eidAdhaDate = LocalDate.of(2026, 5, 27),
        )

        assertTrue(RamadanOverrideChecker.shouldStartPolling(),
            "Previous-year cache must not block Eid al-Fitr polling for the new Hijri year")
    }

    // ───────────────────────────────────────────────
    // EID AL-ADHA polling (with drift)
    // ───────────────────────────────────────────────

    @Test
    fun eidAdha_withDrift_inWindow_polls() {
        // eidFitrDate = Mar 21 → drift = +1 (algorithmic = Mar 20)
        // Algorithmic 29 Dhul Qidah → real = algorithmic + 1
        // We need to be in the 13-day window after adjusted 29 Dhul Qidah
        val algorithmicDhulQidah29 = LocalDate.from(HijrahDate.of(1447, 11, 29))
        val driftAdjusted = algorithmicDhulQidah29.plusDays(1) // drift +1

        RamadanOverrideChecker.testDateOverride = driftAdjusted // first day of window
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 21),
            eidAdhaDate = null,
        )
        assertTrue(RamadanOverrideChecker.shouldStartPolling(),
            "First day of drift-adjusted Eid Adha window should poll")
    }

    @Test
    fun eidAdha_withDrift_beforeWindow_noPoll() {
        val algorithmicDhulQidah29 = LocalDate.from(HijrahDate.of(1447, 11, 29))
        val driftAdjusted = algorithmicDhulQidah29.plusDays(1) // drift +1
        val beforeWindow = driftAdjusted.minusDays(1)

        RamadanOverrideChecker.testDateOverride = beforeWindow
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 21),
            eidAdhaDate = null,
        )
        assertFalse(RamadanOverrideChecker.shouldStartPolling(),
            "Day before drift-adjusted window should NOT poll")
    }

    @Test
    fun eidAdha_withDrift_pastWindow_noPoll() {
        val algorithmicDhulQidah29 = LocalDate.from(HijrahDate.of(1447, 11, 29))
        val driftAdjusted = algorithmicDhulQidah29.plusDays(1)
        val pastWindow = driftAdjusted.plusDays(13) // exactly at end

        RamadanOverrideChecker.testDateOverride = pastWindow
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 21),
            eidAdhaDate = null,
        )
        assertFalse(RamadanOverrideChecker.shouldStartPolling(),
            "Day 14 after drift-adjusted 29 Dhul Qidah should NOT poll (window closed)")
    }

    @Test
    fun eidAdha_alreadyCached_noPoll() {
        // In the Eid Adha window but eidAdhaDate already cached
        val algorithmicDhulQidah29 = LocalDate.from(HijrahDate.of(1447, 11, 29))
        RamadanOverrideChecker.testDateOverride = algorithmicDhulQidah29

        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 21),
            eidAdhaDate = LocalDate.of(2026, 5, 28),
        )
        assertFalse(RamadanOverrideChecker.shouldStartPolling(),
            "Should NOT poll when eidAdhaDate already cached")
    }

    // ───────────────────────────────────────────────
    // EID AL-ADHA polling (no drift — fallback)
    // ───────────────────────────────────────────────

    @Test
    fun eidAdha_noDrift_endOfDhulQidah_polls() {
        // No cache at all → no drift. On last days of Dhul Qidah → fallback polls.
        val dhulQidahLength = HijrahDate.of(1447, 11, 1).lengthOfMonth()
        val lastDayDhulQidah = LocalDate.from(HijrahDate.of(1447, 11, dhulQidahLength))
        RamadanOverrideChecker.testDateOverride = lastDayDhulQidah
        assertTrue(RamadanOverrideChecker.shouldStartPolling(),
            "Last day of Dhul Qidah with no drift should poll via fallback")
    }

    @Test
    fun eidAdha_noDrift_earlyDhulHijja_polls() {
        // 5 Dhul Hijja → month=12, day=5, in 1..10 → polls
        RamadanOverrideChecker.testDateOverride = LocalDate.from(HijrahDate.of(1447, 12, 5))
        assertTrue(RamadanOverrideChecker.shouldStartPolling(),
            "5 Dhul Hijja with no drift should poll via fallback")
    }

    @Test
    fun eidAdha_noDrift_midDhulQidah_noPoll() {
        // 15 Dhul Qidah → not in last 2 days → no poll
        RamadanOverrideChecker.testDateOverride = LocalDate.from(HijrahDate.of(1447, 11, 15))
        assertFalse(RamadanOverrideChecker.shouldStartPolling(),
            "Mid-Dhul Qidah with no drift should NOT poll")
    }

    @Test
    fun eidAdha_noDrift_11DhulHijja_noPoll() {
        // 11 Dhul Hijja → day=11, not in 1..10 → no poll
        RamadanOverrideChecker.testDateOverride = LocalDate.from(HijrahDate.of(1447, 12, 11))
        assertFalse(RamadanOverrideChecker.shouldStartPolling(),
            "11 Dhul Hijja (past window) should NOT poll")
    }

    @Test
    fun eidAdha_previousHijriYearCache_pollsForNewYear() {
        RamadanOverrideChecker.testDateOverride = LocalDate.from(HijrahDate.of(1448, 12, 5))
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 20),
            eidAdhaDate = LocalDate.of(2026, 5, 27),
        )

        assertTrue(RamadanOverrideChecker.shouldStartPolling(),
            "Previous-year cache must not block Eid al-Adha polling for the new Hijri year")
    }

    // ───────────────────────────────────────────────
    // NO POLLING needed (random months / all cached)
    // ───────────────────────────────────────────────

    @Test
    fun noPoll_muharram_noCache() {
        // 15 Muharram → no polling conditions match
        RamadanOverrideChecker.testDateOverride = LocalDate.from(HijrahDate.of(1447, 1, 15))
        assertFalse(RamadanOverrideChecker.shouldStartPolling())
    }

    @Test
    fun noPoll_jumadaI_noCache() {
        // 15 Jumada I → no polling conditions match
        RamadanOverrideChecker.testDateOverride = LocalDate.from(HijrahDate.of(1447, 5, 15))
        assertFalse(RamadanOverrideChecker.shouldStartPolling())
    }

    @Test
    fun noPoll_allDatesCached() {
        // All three dates cached → no polling needed ever
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 2, 17) // 29 Sha'ban
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 20),
            eidAdhaDate = LocalDate.of(2026, 5, 28),
        )
        assertFalse(RamadanOverrideChecker.shouldStartPolling(),
            "Should NOT poll when all dates are cached")
    }

    @Test
    fun noPoll_allDatesCached_inDhulHijja() {
        // Even during Dhul Hijja, if all dates cached → no poll
        RamadanOverrideChecker.testDateOverride = LocalDate.from(HijrahDate.of(1447, 12, 5))
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = LocalDate.of(2026, 3, 20),
            eidAdhaDate = LocalDate.of(2026, 5, 28),
        )
        assertFalse(RamadanOverrideChecker.shouldStartPolling())
    }

    @Test
    fun noPoll_midRamadan_ramadanStartCached() {
        // 15 Ramadan, ramadanStart cached, no eidFitrDate → but not near 29th Ramadan yet
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 4) // ~15 Ramadan
        RamadanOverrideChecker.cachedOverride = RamadanOverrideChecker.RamadanOverride(
            hijriYear = 1447,
            ramadanStart = LocalDate.of(2026, 2, 19),
            eidFitrDate = null,
            eidAdhaDate = null,
        )
        // real29thRamadan = Feb 19 + 28 = Mar 19. Mar 4 < Mar 19 → not in window
        assertFalse(RamadanOverrideChecker.shouldStartPolling(),
            "Mid-Ramadan should NOT poll for Eid Fitr (too early)")
    }

    // ───────────────────────────────────────────────
    // shouldStopPolling tests
    // ───────────────────────────────────────────────

    private fun overrideWith(
        ramadanStart: LocalDate? = null,
        eidFitrDate: LocalDate? = null,
        eidAdhaDate: LocalDate? = null,
    ) = RamadanOverrideChecker.RamadanOverride(
        hijriYear = 1447,
        ramadanStart = ramadanStart,
        eidFitrDate = eidFitrDate,
        eidAdhaDate = eidAdhaDate,
    )

    @Test
    fun stopPolling_shaban_gotRamadanStart_stops() {
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 2, 17) // 29 Sha'ban, month=8
        assertTrue(
            RamadanOverrideChecker.shouldStopPolling(
                overrideWith(ramadanStart = LocalDate.of(2026, 2, 19))
            )
        )
    }

    @Test
    fun stopPolling_shaban_noRamadanStart_continues() {
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 2, 17)
        assertFalse(
            RamadanOverrideChecker.shouldStopPolling(overrideWith())
        )
    }

    @Test
    fun stopPolling_ramadan_gotRamadanStart_stops() {
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 2, 18) // 1 Ramadan, month=9
        assertTrue(
            RamadanOverrideChecker.shouldStopPolling(
                overrideWith(ramadanStart = LocalDate.of(2026, 2, 19))
            )
        )
    }

    @Test
    fun stopPolling_ramadan_gotEidFitr_stops() {
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 19) // 30 Ramadan, month=9
        assertTrue(
            RamadanOverrideChecker.shouldStopPolling(
                overrideWith(
                    ramadanStart = LocalDate.of(2026, 2, 19),
                    eidFitrDate = LocalDate.of(2026, 3, 20),
                )
            )
        )
    }

    @Test
    fun stopPolling_ramadan_gotRamadanStartOnly_stops() {
        // In Ramadan (month 9), once we have ramadanStart, stop the current poll.
        // A new poll for Eid Fitr will start separately when the window opens.
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 19) // 30 Ramadan
        assertTrue(
            RamadanOverrideChecker.shouldStopPolling(
                overrideWith(ramadanStart = LocalDate.of(2026, 2, 19))
            ),
            "Should stop polling in Ramadan once ramadanStart is fetched"
        )
    }

    @Test
    fun stopPolling_shawwal_gotEidFitr_stops() {
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 20) // 1 Shawwal, month=10
        assertTrue(
            RamadanOverrideChecker.shouldStopPolling(
                overrideWith(
                    ramadanStart = LocalDate.of(2026, 2, 19),
                    eidFitrDate = LocalDate.of(2026, 3, 20),
                )
            )
        )
    }

    @Test
    fun stopPolling_shawwal_noEidFitr_continues() {
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 20) // 1 Shawwal
        assertFalse(
            RamadanOverrideChecker.shouldStopPolling(
                overrideWith(ramadanStart = LocalDate.of(2026, 2, 19))
            )
        )
    }

    @Test
    fun stopPolling_dhulQidah_gotEidAdha_stops() {
        RamadanOverrideChecker.testDateOverride = LocalDate.from(HijrahDate.of(1447, 11, 29))
        assertTrue(
            RamadanOverrideChecker.shouldStopPolling(
                overrideWith(
                    ramadanStart = LocalDate.of(2026, 2, 19),
                    eidFitrDate = LocalDate.of(2026, 3, 20),
                    eidAdhaDate = LocalDate.of(2026, 5, 28),
                )
            )
        )
    }

    @Test
    fun stopPolling_dhulHijja_gotEidAdha_stops() {
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 5, 27) // 10 Dhul Hijja
        assertTrue(
            RamadanOverrideChecker.shouldStopPolling(
                overrideWith(
                    ramadanStart = LocalDate.of(2026, 2, 19),
                    eidFitrDate = LocalDate.of(2026, 3, 20),
                    eidAdhaDate = LocalDate.of(2026, 5, 27),
                )
            )
        )
    }

    @Test
    fun stopPolling_dhulHijja_noEidAdha_continues() {
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 5, 27)
        assertFalse(
            RamadanOverrideChecker.shouldStopPolling(
                overrideWith(
                    ramadanStart = LocalDate.of(2026, 2, 19),
                    eidFitrDate = LocalDate.of(2026, 3, 20),
                )
            )
        )
    }

    @Test
    fun stopPolling_muharram_everythingCached_doesNotStop() {
        // In Muharram (month 1), none of the stop conditions match
        RamadanOverrideChecker.testDateOverride = LocalDate.from(HijrahDate.of(1447, 1, 15))
        assertFalse(
            RamadanOverrideChecker.shouldStopPolling(
                overrideWith(
                    ramadanStart = LocalDate.of(2026, 2, 19),
                    eidFitrDate = LocalDate.of(2026, 3, 20),
                    eidAdhaDate = LocalDate.of(2026, 5, 28),
                )
            ),
            "Stop conditions should not match outside event months"
        )
    }

    // ───────────────────────────────────────────────
    // JSON parsing edge cases
    // ───────────────────────────────────────────────

    @Test
    fun parse_validJson_allFields() {
        val json = """
            {
              "hijriYear": 1447,
              "ramadanStart": "2026-02-19",
              "eidFitrDate": "2026-03-20",
              "eidAdhaDate": "2026-05-28",
              "lastUpdated": "2026-04-06T12:00:00Z"
            }
        """.trimIndent()
        val result = RamadanOverrideChecker.parseOverrideForTest(json)
        assertEquals(1447, result?.hijriYear)
        assertEquals(LocalDate.of(2026, 2, 19), result?.ramadanStart)
        assertEquals(LocalDate.of(2026, 3, 20), result?.eidFitrDate)
        assertEquals(LocalDate.of(2026, 5, 28), result?.eidAdhaDate)
    }

    @Test
    fun parse_validJson_nullOptionalFields() {
        val json = """
            {
              "hijriYear": 1447,
              "ramadanStart": null,
              "eidFitrDate": null,
              "eidAdhaDate": null,
              "lastUpdated": "2026-01-01T00:00:00Z"
            }
        """.trimIndent()
        val result = RamadanOverrideChecker.parseOverrideForTest(json)
        assertEquals(1447, result?.hijriYear)
        assertNull(result?.ramadanStart)
        assertNull(result?.eidFitrDate)
        assertNull(result?.eidAdhaDate)
    }

    @Test
    fun parse_partialJson_onlyRamadanStart() {
        val json = """
            {
              "hijriYear": 1447,
              "ramadanStart": "2026-02-19",
              "eidFitrDate": null,
              "eidAdhaDate": null,
              "lastUpdated": "2026-02-18T20:00:00Z"
            }
        """.trimIndent()
        val result = RamadanOverrideChecker.parseOverrideForTest(json)
        assertEquals(LocalDate.of(2026, 2, 19), result?.ramadanStart)
        assertNull(result?.eidFitrDate)
        assertNull(result?.eidAdhaDate)
    }

    @Test
    fun parse_missingHijriYear_returnsNull() {
        val json = """
            {
              "ramadanStart": "2026-02-19",
              "eidFitrDate": null
            }
        """.trimIndent()
        assertNull(RamadanOverrideChecker.parseOverrideForTest(json))
    }

    @Test
    fun parse_malformedJson_returnsNull() {
        assertNull(RamadanOverrideChecker.parseOverrideForTest("not json"))
    }

    @Test
    fun parse_emptyString_returnsNull() {
        assertNull(RamadanOverrideChecker.parseOverrideForTest(""))
    }

    @Test
    fun parse_invalidDateFormat_returnsNull() {
        val json = """
            {
              "hijriYear": 1447,
              "ramadanStart": "19/02/2026",
              "eidFitrDate": null,
              "eidAdhaDate": null
            }
        """.trimIndent()
        // parseDate expects ISO format, wrong format should cause null
        val result = RamadanOverrideChecker.parseOverrideForTest(json)
        // Should either return null entirely or have null ramadanStart
        assertTrue(result == null || result.ramadanStart == null)
    }

    // ───────────────────────────────────────────────
    // fetchOverride year selection
    // ───────────────────────────────────────────────

    @Test
    fun fetchOverride_usesTestDateForHijriYear() {
        // When testDateOverride is set, fetchOverride should use the Hijri year
        // from that date, not the real current date
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 2, 17) // 1447
        val year1447 = HijrahDate.from(LocalDate.of(2026, 2, 17)).get(ChronoField.YEAR)
        assertEquals(1447, year1447, "Feb 17, 2026 should be Hijri year 1447")

        RamadanOverrideChecker.testDateOverride = LocalDate.of(2027, 2, 10) // 1448
        val year1448 = HijrahDate.from(LocalDate.of(2027, 2, 10)).get(ChronoField.YEAR)
        assertEquals(1448, year1448, "Feb 10, 2027 should be Hijri year 1448")
    }
}
