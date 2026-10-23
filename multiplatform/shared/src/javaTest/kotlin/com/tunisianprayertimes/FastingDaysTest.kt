package com.tunisianprayertimes

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FastingDaysTest {

    // Tunisia's announcements for 1447: Ramadan from 2026-02-19, Eid al-Fitr 2026-03-20, Eid al-Adha 2026-05-27.
    private val official = TunisianHijriCalendar(
        mapOf(1447 to RamadanOverrideChecker.RamadanOverride(
            1447, LocalDate.of(2026, 2, 19), LocalDate.of(2026, 3, 20), LocalDate.of(2026, 5, 27),
        ))
    )

    private fun verdict(iso: String, abroad: Boolean = false, calendar: TunisianHijriCalendar = official) =
        FastingDays.verdict(LocalDate.parse(iso), abroad, calendar)

    @Test
    fun forbiddenDaysAreBlockedForThemselves() {
        assertEquals(FastingVerdict(FastingBlock.RAMADAN), verdict("2026-03-02"))
        assertEquals(FastingVerdict(FastingBlock.EID_FITR), verdict("2026-03-20"))
        assertEquals(FastingVerdict(FastingBlock.EID_ADHA), verdict("2026-05-27"))
        assertEquals(FastingVerdict(FastingBlock.TASHRIQ), verdict("2026-05-28"))
        assertEquals(FastingVerdict(FastingBlock.TASHRIQ), verdict("2026-05-30"), "the 13th too")
    }

    @Test
    fun announcedDaysNextToThemStayAllowedInTunisia() {
        assertTrue(verdict("2026-02-18").allowed, "30 Sha'ban, once Ramadan is announced")
        assertTrue(verdict("2026-03-21").allowed, "2 Shawwal")
        assertTrue(verdict("2026-05-26").allowed, "Arafa")
        assertTrue(verdict("2026-05-31").allowed, "14 Dhul Hijja")
        assertTrue(verdict("2026-10-26").allowed)
    }

    @Test
    fun abroadTheDayNextToAForbiddenOneStaysSilent() {
        assertEquals(FastingVerdict(FastingBlock.RAMADAN, margin = true, abroad = true), verdict("2026-02-18", abroad = true))
        assertEquals(FastingVerdict(FastingBlock.EID_ADHA, margin = true, abroad = true), verdict("2026-05-26", abroad = true))
        assertEquals(FastingVerdict(FastingBlock.TASHRIQ, margin = true, abroad = true), verdict("2026-05-31", abroad = true))
        assertTrue(verdict("2026-10-26", abroad = true).allowed)
    }

    @Test
    fun arafaTasuaAndAshuraFallOnTheirHijriDays() {
        assertEquals(FastingOccasion.ARAFA, FastingDays.occasion(LocalDate.parse("2026-05-26"), abroad = false, calendar = official))
        assertEquals(null, FastingDays.occasion(LocalDate.parse("2026-05-27"), abroad = false, calendar = official), "the Eid itself")
        val muharram = official.month(1448, 1).start
        assertEquals(FastingOccasion.TASUA, FastingDays.occasion(muharram.plusDays(8), abroad = false, calendar = official))
        assertEquals(FastingOccasion.ASHURA, FastingDays.occasion(muharram.plusDays(9), abroad = false, calendar = official))
        assertEquals(null, FastingDays.occasion(muharram.plusDays(10), abroad = false, calendar = official))
    }

    @Test
    fun theWhiteDaysAndSixDaysOfShawwal() {
        val rajab = official.month(1448, 7).start
        assertEquals(null, FastingDays.occasion(rajab.plusDays(11), abroad = false, calendar = official))
        (12L..14L).forEach { assertEquals(FastingOccasion.WHITE_DAYS, FastingDays.occasion(rajab.plusDays(it), abroad = false, calendar = official)) }
        assertEquals(null, FastingDays.occasion(rajab.plusDays(15), abroad = false, calendar = official))
        // 1447: Eid al-Fitr on 2026-03-20, so 2 to 7 Shawwal are 21 to 26 March.
        assertEquals(null, FastingDays.occasion(LocalDate.parse("2026-03-20"), abroad = false, calendar = official), "the Eid itself")
        (21..26).forEach { assertEquals(FastingOccasion.SHAWWAL, FastingDays.occasion(LocalDate.of(2026, 3, it), abroad = false, calendar = official)) }
        assertEquals(null, FastingDays.occasion(LocalDate.parse("2026-03-27"), abroad = false, calendar = official))
        // A white day that may not be fasted keeps its own rule: the 13th of Dhul Hijja is a tashriq day.
        assertEquals(FastingOccasion.WHITE_DAYS, FastingDays.occasion(LocalDate.parse("2026-05-30"), abroad = false, calendar = official))
        assertEquals(FastingVerdict(FastingBlock.TASHRIQ), verdict("2026-05-30"))
    }

    @Test
    fun abroadTheSixOfShawwalStaySixAndAnEstimatedEidStillCountsAsAbroad() {
        // 2 Shawwal 1447 (2026-03-21) is held abroad, so 8 Shawwal (2026-03-27) takes its place; at home it does not.
        assertEquals(FastingOccasion.SHAWWAL, FastingDays.occasion(LocalDate.parse("2026-03-27"), abroad = true, calendar = official))
        assertEquals(null, FastingDays.occasion(LocalDate.parse("2026-03-27"), abroad = false, calendar = official))
        val estimate = TunisianHijriCalendar()
        val secondOfShawwal = estimate.month(1448, 10).start.plusDays(1)
        assertEquals(FastingVerdict(FastingBlock.EID_FITR, margin = true, abroad = true), FastingDays.verdict(secondOfShawwal, abroad = true, calendar = estimate))
    }

    @Test
    fun anEidIsKnownAtHomeAndMayMoveAbroad() {
        assertTrue(FastingDays.mayBeEid(LocalDate.parse("2026-03-20"), abroad = false, calendar = official))
        assertFalse(FastingDays.mayBeEid(LocalDate.parse("2026-03-19"), abroad = false, calendar = official), "30 Ramadan, announced")
        assertTrue(FastingDays.mayBeEid(LocalDate.parse("2026-03-19"), abroad = true, calendar = official), "abroad, a day earlier")
        assertTrue(FastingDays.mayBeEid(LocalDate.parse("2026-05-28"), abroad = true, calendar = official), "abroad, a day later")
        assertFalse(FastingDays.mayBeEid(LocalDate.parse("2026-05-28"), abroad = false, calendar = official))
        val estimate = TunisianHijriCalendar()
        val eid = estimate.month(1448, 10).start
        assertTrue(FastingDays.mayBeEid(eid.minusDays(1), abroad = false, calendar = estimate), "not announced yet")
    }

    @Test
    fun arafaWaitsForTheAnnouncementAndStaysSilentAbroad() {
        assertTrue(verdict("2026-05-26").allowed, "announced in Tunisia")
        val estimate = TunisianHijriCalendar()
        val arafa = estimate.month(1448, 12).start.plusDays(8)
        assertEquals(FastingOccasion.ARAFA, FastingDays.occasion(arafa, abroad = false, calendar = estimate))
        val held = FastingDays.verdict(arafa, abroad = false, calendar = estimate)
        assertEquals(FastingBlock.EID_ADHA, held.block, "the Eid may still move onto it")
        assertTrue(held.margin)
    }

    @Test
    fun anEstimatedMonthKeepsADayOfMarginOnEachSide() {
        val estimate = TunisianHijriCalendar()
        val ramadan = estimate.month(1448, 9).start
        val before = FastingDays.verdict(ramadan.minusDays(1), abroad = false, calendar = estimate)
        assertEquals(FastingBlock.RAMADAN, before.block)
        assertTrue(before.margin)
        assertFalse(before.abroad)
        val tashriqEnd = estimate.month(1448, 12).start.plusDays(12)
        assertEquals(FastingBlock.TASHRIQ, FastingDays.verdict(tashriqEnd.plusDays(1), abroad = false, calendar = estimate).block)
    }
}
