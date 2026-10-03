package com.tunisianprayertimes

import com.tunisianprayertimes.RamadanOverrideChecker.RamadanOverride
import java.time.LocalDate
import java.time.chrono.HijrahChronology
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TunisianHijriCalendarTest {
    private val firstYear = HijrahChronology.INSTANCE.range(ChronoField.YEAR).minimum.toInt()
    private val lastYear = HijrahChronology.INSTANCE.range(ChronoField.YEAR).maximum.toInt()

    @Test
    fun unannouncedCalendarMatchesUmmAlQuraAtEverySupportedMonthBoundary() {
        val calendar = TunisianHijriCalendar()
        for (year in firstYear..lastYear) {
            for (month in 1..12) {
                val expected = HijrahDate.of(year, month, 1)
                val actual = calendar.month(year, month)
                assertEquals(LocalDate.from(expected), actual.start, "$year/$month start")
                assertEquals(expected.lengthOfMonth(), actual.lengthOfMonth, "$year/$month length")
                assertEquals(actual.start.plusDays(expected.lengthOfMonth().toLong()), actual.endExclusive)
                assertTrue(actual.isEstimated)
                assertEquals(HijriCalendarDate(year, month, 1, true), calendar.date(actual.start))
                assertEquals(HijriCalendarDate(year, month, expected.lengthOfMonth(), true),
                    calendar.date(actual.endExclusive.minusDays(1)))
            }
        }
    }

    @Test
    fun officialDatesWithDifferentOffsetsAreExactWithoutAnAnnualBlanketShift() {
        val calendar = calendar(record(
            ramadan = LocalDate.of(2026, 2, 19), // +1 from Umm al-Qura
            fitr = LocalDate.of(2026, 3, 20),    // no offset
            adha = LocalDate.of(2026, 5, 27),    // no offset
        ))
        assertEquals(HijriCalendarDate(1447, 9, 1, false), calendar.date(LocalDate.of(2026, 2, 19)))
        assertEquals(HijriCalendarDate(1447, 9, 29, false), calendar.date(LocalDate.of(2026, 3, 19)))
        assertEquals(HijriCalendarDate(1447, 10, 1, false), calendar.date(LocalDate.of(2026, 3, 20)))
        assertEquals(HijriCalendarDate(1447, 12, 10, false), calendar.date(LocalDate.of(2026, 5, 27)))
        assertEquals(29, calendar.month(1447, 9).lengthOfMonth)
        assertContinuousMonths(calendar, 1446, 1448)
    }

    @Test
    fun unannouncedDayThirtyBecomesShawwalOneAfterTwentyNineDayAnnouncement() {
        val ramadan = LocalDate.of(2026, 2, 19)
        val before = calendar(record(ramadan = ramadan))
        val disputedDay = ramadan.plusDays(29)
        assertEquals(HijriCalendarDate(1447, 9, 29, false), before.date(disputedDay.minusDays(1)))
        assertEquals(HijriCalendarDate(1447, 9, 30, true), before.date(disputedDay))
        // Knowing the start does not establish an as-yet-unannounced 30th day.
        assertFalse(before.month(1447, 9).isEstimated)
        assertTrue(before.month(1447, 10).isEstimated)

        val after = calendar(record(ramadan = ramadan, fitr = disputedDay))
        assertEquals(HijriCalendarDate(1447, 10, 1, false), after.date(disputedDay))
        assertEquals(29, after.month(1447, 9).lengthOfMonth)
        // Existing UI snapshots remain immutable when an announcement arrives.
        assertEquals(HijriCalendarDate(1447, 9, 30, true), before.date(disputedDay))
    }

    @Test
    fun dayThirtyBecomesConfirmedWhenFitrEstablishesThirtyDayRamadan() {
        val ramadan = LocalDate.of(2026, 2, 19)
        val before = calendar(record(ramadan = ramadan))
        val after = calendar(record(ramadan = ramadan, fitr = ramadan.plusDays(30)))
        assertEquals(HijriCalendarDate(1447, 9, 30, true), before.date(ramadan.plusDays(29)))
        assertEquals(HijriCalendarDate(1447, 9, 30, false), after.date(ramadan.plusDays(29)))
        assertEquals(HijriCalendarDate(1447, 10, 1, false), after.date(ramadan.plusDays(30)))
        assertEquals(30, after.month(1447, 9).lengthOfMonth)
    }

    @Test
    fun confirmedEndDoesNotClaimAnUnannouncedStartIsConfirmed() {
        val calendar = calendar(record(fitr = LocalDate.of(2026, 3, 20)))
        val ramadan = calendar.month(1447, 9)
        assertTrue(ramadan.isEstimated)
        assertTrue(calendar.date(ramadan.endExclusive.minusDays(1)).isEstimated)
        assertFalse(calendar.date(ramadan.endExclusive).isEstimated)
    }

    @Test
    fun latestKnownOffsetPredictsOnlyUnannouncedEventsInThatYear() {
        val ramadan = LocalDate.of(2026, 2, 19)
        val startOnly = calendar(record(ramadan = ramadan))
        assertEquals(LocalDate.of(2026, 3, 21), startOnly.month(1447, 10).start)
        assertEquals(LocalDate.of(2026, 5, 28), startOnly.month(1447, 12).start.plusDays(9))
        val withFitr = calendar(record(ramadan = ramadan, fitr = LocalDate.of(2026, 3, 20)))
        assertEquals(LocalDate.of(2026, 5, 27), withFitr.month(1447, 12).start.plusDays(9))
        for (month in 8..12) {
            assertEquals(baseline(1448, month), startOnly.month(1448, month).start)
            assertTrue(startOnly.month(1448, month).isEstimated)
        }
    }

    @Test
    fun allFeasibleOneDayOffsetCombinationsPreserveEventsAndConsecutiveDays() {
        var scenarios = 0
        for (year in listOf(1447, 1448)) {
            for (ramadanOffset in -1L..1L) {
                for (fitrOffset in -1L..1L) {
                    for (adhaOffset in -1L..1L) {
                        val ramadan = baseline(year, 9).plusDays(ramadanOffset)
                        val fitr = baseline(year, 10).plusDays(fitrOffset)
                        val dhulHijja = baseline(year, 12).plusDays(adhaOffset)
                        // Feasibility follows independently from lunar months having 29 or 30 days.
                        if (ChronoUnit.DAYS.between(ramadan, fitr) !in 29L..30L ||
                            ChronoUnit.DAYS.between(fitr, dhulHijja) !in 58L..60L) continue
                        val calendar = calendar(record(year, ramadan, fitr, dhulHijja.plusDays(9)))
                        assertEquals(HijriCalendarDate(year, 9, 1, false), calendar.date(ramadan))
                        assertEquals(HijriCalendarDate(year, 10, 1, false), calendar.date(fitr))
                        assertEquals(HijriCalendarDate(year, 12, 10, false), calendar.date(dhulHijja.plusDays(9)))
                        assertContinuousMonths(calendar, year - 1, year + 1)
                        scenarios++
                    }
                }
            }
        }
        assertTrue(scenarios >= 20, "The invariant test must exercise both 29/30-day years and mixed offsets")
    }

    @Test
    fun correctionsInAdjacentYearsCoexistAndIgnoreMapInsertionOrder() {
        val earlier = record(1447, baseline(1447, 9).plusDays(1),
            baseline(1447, 10).plusDays(1), baseline(1447, 12).plusDays(10))
        val later = record(1448, baseline(1448, 9).minusDays(1),
            baseline(1448, 10).minusDays(1), baseline(1448, 12).plusDays(8))
        val ordered = TunisianHijriCalendar(linkedMapOf(1447 to earlier, 1448 to later))
        val reversed = TunisianHijriCalendar(linkedMapOf(1448 to later, 1447 to earlier))
        for (record in listOf(earlier, later)) {
            assertEquals(HijriCalendarDate(record.hijriYear, 9, 1, false), ordered.date(record.ramadanStart!!))
            assertEquals(HijriCalendarDate(record.hijriYear, 10, 1, false), ordered.date(record.eidFitrDate!!))
            assertEquals(HijriCalendarDate(record.hijriYear, 12, 10, false), ordered.date(record.eidAdhaDate!!))
        }
        for (year in 1446..1449) for (month in 1..12) {
            assertEquals(ordered.month(year, month), reversed.month(year, month))
        }
        assertContinuousMonths(ordered, 1446, 1449)
    }

    @Test
    fun twentyEightAndThirtyOneDayRamadanRejectFitrButKeepOtherValidAnchors() {
        val ramadan = LocalDate.of(2026, 2, 19)
        for (invalidLength in listOf(28L, 31L)) {
            val calendar = calendar(record(ramadan = ramadan, fitr = ramadan.plusDays(invalidLength),
                adha = LocalDate.of(2026, 5, 27)))
            assertEquals(HijriCalendarDate(1447, 9, 1, false), calendar.date(ramadan))
            assertTrue(calendar.month(1447, 10).isEstimated, "$invalidLength-day Ramadan must be rejected")
            assertEquals(HijriCalendarDate(1447, 12, 10, false), calendar.date(LocalDate.of(2026, 5, 27)))
            assertContinuousMonths(calendar, 1447, 1447)
        }
    }

    @Test
    fun anAdminDateKeepsItsDayBesideAnAnnouncementWithOneOddMonth() {
        // Announced: a 30-day Ramadan. The mosque's own Eid al-Fitr a day later makes it 31 days.
        val ramadan = LocalDate.of(2026, 2, 19)
        val manualFitr = ramadan.plusDays(31)
        val calendar = TunisianHijriCalendar(
            mapOf(1447 to record(ramadan = ramadan, fitr = ramadan.plusDays(30), adha = LocalDate.of(2026, 5, 28))),
            mapOf(1447 to ManualIslamicDates(eidFitr = manualFitr)),
        )
        assertEquals(HijriCalendarDate(1447, 9, 1, false), calendar.date(ramadan))
        assertEquals(HijriCalendarDate(1447, 9, 31, false), calendar.date(manualFitr.minusDays(1)))
        assertEquals(HijriCalendarDate(1447, 10, 1, false), calendar.date(manualFitr))
        assertEquals(HijriCalendarDate(1447, 12, 10, false), calendar.date(LocalDate.of(2026, 5, 28)))
        // Every day still has one Hijri date, and only Ramadan leaves the usual lengths.
        for (month in 1..12) {
            val current = calendar.month(1447, month)
            assertTrue(if (month == 9) current.lengthOfMonth == 31 else current.lengthOfMonth in 29..30, "1447/$month has ${current.lengthOfMonth} days")
            assertEquals(current.endExclusive, calendar.adjacentMonth(1447, month, 1)!!.start)
            for (day in 1..current.lengthOfMonth) {
                val converted = calendar.date(current.start.plusDays(day - 1L))
                assertEquals(Triple(1447, month, day), Triple(converted.year, converted.month, converted.day))
            }
        }
    }

    @Test
    fun impossibleAdhaSpacingRetainsEarlierAnnouncements() {
        val ramadan = LocalDate.of(2026, 2, 19)
        val fitr = LocalDate.of(2026, 3, 20)
        for (invalidSpacing in listOf(57L, 61L)) {
            val calendar = calendar(record(ramadan = ramadan, fitr = fitr,
                adha = fitr.plusDays(invalidSpacing + 9)))
            assertEquals(HijriCalendarDate(1447, 9, 1, false), calendar.date(ramadan))
            assertEquals(HijriCalendarDate(1447, 10, 1, false), calendar.date(fitr))
            assertTrue(calendar.month(1447, 12).isEstimated)
            assertContinuousMonths(calendar, 1447, 1448)
        }
    }

    @Test
    fun mislabelledUnsupportedAndImplausibleDatesLeaveBaselineUntouched() {
        val baselineCalendar = TunisianHijriCalendar()
        val invalidRecords = listOf(
            mapOf(1447 to record(year = 1448, ramadan = LocalDate.of(2026, 2, 19))),
            mapOf(firstYear - 1 to record(year = firstYear - 1, ramadan = LocalDate.MIN)),
            mapOf(lastYear + 1 to record(year = lastYear + 1, adha = LocalDate.MAX)),
            mapOf(1447 to record(ramadan = LocalDate.MIN, fitr = LocalDate.MAX, adha = LocalDate.MIN)),
            mapOf(1447 to record(ramadan = baseline(1447, 9).plusDays(8),
                fitr = baseline(1447, 10).minusDays(8), adha = baseline(1447, 12).plusDays(17))),
        )
        for (invalid in invalidRecords) {
            val calendar = TunisianHijriCalendar(invalid)
            for (year in listOf(firstYear, 1447, lastYear)) for (month in 1..12) {
                assertEquals(baselineCalendar.month(year, month), calendar.month(year, month))
            }
            assertEquals(baselineCalendar.supportedFirst, calendar.supportedFirst)
            assertEquals(baselineCalendar.supportedLast, calendar.supportedLast)
        }
    }

    @Test
    fun largestAcceptedCorrectionsRemainContinuousAtSupportedEdges() {
        for (year in listOf(firstYear, 1447, lastYear)) {
            for (offset in listOf(-7L, 7L)) {
                val ramadan = baseline(year, 9).plusDays(offset)
                val fitr = baseline(year, 10).plusDays(offset)
                val adha = baseline(year, 12).plusDays(offset + 9)
                val calendar = calendar(record(year, ramadan, fitr, adha))
                assertEquals(HijriCalendarDate(year, 9, 1, false), calendar.date(ramadan))
                assertEquals(HijriCalendarDate(year, 10, 1, false), calendar.date(fitr))
                assertEquals(HijriCalendarDate(year, 12, 10, false), calendar.date(adha))
                assertContinuousMonths(calendar, maxOf(firstYear, year - 1), minOf(lastYear, year + 1))
                assertEquals(1, calendar.date(calendar.supportedFirst).day)
                val last = calendar.month(lastYear, 12)
                assertEquals(last.lengthOfMonth, calendar.date(calendar.supportedLast).day)
                assertFailsWith<IllegalArgumentException> { calendar.date(calendar.supportedFirst.minusDays(1)) }
                assertFailsWith<IllegalArgumentException> { calendar.date(calendar.supportedLast.plusDays(1)) }
            }
        }
    }

    @Test
    fun adjacencyWrapsYearsAndBoundsHugeNavigationAmountsSafely() {
        val calendar = TunisianHijriCalendar()
        assertEquals(calendar.month(1448, 1), calendar.adjacentMonth(1447, 12, 1))
        assertEquals(calendar.month(1447, 12), calendar.adjacentMonth(1448, 1, -1))
        assertEquals(calendar.month(1447, 9), calendar.adjacentMonth(1447, 9, 0))
        assertNull(calendar.adjacentMonth(firstYear, 1, -1))
        assertNull(calendar.adjacentMonth(lastYear, 12, 1))
        assertNull(calendar.adjacentMonth(1447, 9, Int.MIN_VALUE))
        assertNull(calendar.adjacentMonth(1447, 9, Int.MAX_VALUE))
    }

    @Test
    fun invalidMonthRequestsFailInsteadOfWrappingIntoAnotherYear() {
        val calendar = TunisianHijriCalendar()
        for ((year, month) in listOf(firstYear - 1 to 12, lastYear + 1 to 1, 1447 to 0, 1447 to 13)) {
            assertFailsWith<IllegalArgumentException> { calendar.month(year, month) }
            assertFailsWith<IllegalArgumentException> { calendar.adjacentMonth(year, month, 0) }
        }
    }

    private fun assertContinuousMonths(calendar: TunisianHijriCalendar, fromYear: Int, throughYear: Int) {
        var previous: HijriCalendarMonth? = null
        for (year in fromYear..throughYear) for (month in 1..12) {
            val current = calendar.month(year, month)
            assertTrue(current.lengthOfMonth in 29..30, "$year/$month has ${current.lengthOfMonth} days")
            previous?.let { assertEquals(it.endExclusive, current.start, "Gap or overlap at $year/$month") }
            for (day in 1..current.lengthOfMonth) {
                val solar = current.start.plusDays(day.toLong() - 1)
                val converted = calendar.date(solar)
                assertEquals(Triple(year, month, day), Triple(converted.year, converted.month, converted.day),
                    "Incorrect day conversion at $solar")
                assertEquals(current, calendar.monthFor(solar), "Month lookup disagrees at $solar")
            }
            previous = current
        }
    }

    private fun baseline(year: Int, month: Int): LocalDate = LocalDate.from(HijrahDate.of(year, month, 1))

    private fun calendar(record: RamadanOverride): TunisianHijriCalendar =
        TunisianHijriCalendar(mapOf(record.hijriYear to record))

    private fun record(
        year: Int = 1447,
        ramadan: LocalDate? = null,
        fitr: LocalDate? = null,
        adha: LocalDate? = null,
    ) = RamadanOverride(year, ramadan, fitr, adha)
}
