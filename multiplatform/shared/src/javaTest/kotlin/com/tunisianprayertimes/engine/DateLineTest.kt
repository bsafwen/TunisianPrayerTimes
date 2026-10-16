package com.tunisianprayertimes.engine

import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.HONOLULU_ON_PARIS
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.KIRITIMATI
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.MATA_UTU
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.SHEMYA
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.SUVA
import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.place.PrayerPlace
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Step 1 of the solver at the date line and in a far phone zone (plan 6.2, 9.1), with `mwl`. */
class DateLineTest {
    private val mwl = PrayerMethods.MWL

    private fun explain(place: PrayerPlace.Coordinates, date: String) = PrayerEngine.explain(place, LocalDate.parse(date), mwl)

    private fun EngineExplanation.raw(event: TimeEvent): Instant = checkNotNull(events[event.ordinal].raw)

    private fun EngineExplanation.at(event: TimeEvent): Instant = checkNotNull(day[event].at)

    private fun assertAbout(expected: String, actual: Instant, toleranceSeconds: Long = 60) {
        val difference = abs(Duration.between(Instant.parse(expected), actual).seconds)
        assertTrue(difference <= toleranceSeconds, "expected about $expected, got $actual")
    }

    @Test
    fun wallisTakesThePreviousUtDate() {
        val e = explain(MATA_UTU, "2026-10-13")
        assertEquals(LocalDate.parse("2026-10-12"), e.cycleDate)
        assertAbout("2026-10-12T23:31:00Z", e.raw(TimeEvent.DHUHR))
        assertEquals(LocalDate.parse("2026-10-13"), e.raw(TimeEvent.DHUHR).atZone(MATA_UTU.zone).toLocalDate())
        assertEquals(Instant.parse("2026-10-12T16:09:00Z"), e.at(TimeEvent.FAJR))
        assertEquals(Instant.parse("2026-10-13T05:42:00Z"), e.at(TimeEvent.MAGHRIB))
    }

    @Test
    fun westernAleutiansTakeTheNextUtDate() {
        val e = explain(SHEMYA, "2026-10-13")
        assertEquals(LocalDate.parse("2026-10-14"), e.cycleDate)
        assertAbout("2026-10-14T00:10:00Z", e.raw(TimeEvent.DHUHR))
        assertEquals("15:10", e.at(TimeEvent.DHUHR).atZone(SHEMYA.zone).toLocalTime().toString())
        assertEquals(Instant.parse("2026-10-13T16:52:00Z"), e.at(TimeEvent.FAJR))
        assertEquals(Instant.parse("2026-10-14T07:19:00Z"), e.at(TimeEvent.ISHA))
        // Across Adak's change to UTC−10 on 2026-11-01, each transit stays on its own date.
        val before = explain(SHEMYA, "2026-10-31")
        val after = explain(SHEMYA, "2026-11-01")
        assertAbout("2026-11-01T00:07:00Z", before.raw(TimeEvent.DHUHR))
        assertAbout("2026-11-02T00:07:00Z", after.raw(TimeEvent.DHUHR))
        assertEquals("15:07", before.at(TimeEvent.DHUHR).atZone(SHEMYA.zone).toLocalTime().toString())
        assertEquals("14:07", after.at(TimeEvent.DHUHR).atZone(SHEMYA.zone).toLocalTime().toString())
    }

    @Test
    fun kiritimatiAndSuva() {
        assertEquals(LocalDate.parse("2026-10-12"), explain(KIRITIMATI, "2026-10-13").cycleDate)
        assertEquals(LocalDate.parse("2026-10-13"), explain(SUVA, "2026-10-13").cycleDate)
    }

    @Test
    fun everyDayOf2026TakesItsOwnCycle() {
        for (place in listOf(MATA_UTU, SHEMYA, KIRITIMATI, SUVA)) {
            var date = LocalDate.of(2026, 1, 1)
            var previous: EngineExplanation? = null
            while (date.year == 2026) {
                val e = PrayerEngine.explain(place, date, mwl)
                val transit = e.raw(TimeEvent.DHUHR)
                assertEquals(date, transit.atZone(place.zone).toLocalDate(), "${place.zoneId} $date: transit on its date")
                if (previous != null) {
                    val gap = Duration.between(previous.raw(TimeEvent.DHUHR), transit).seconds
                    assertTrue(abs(gap - 86400) <= 60, "${place.zoneId} $date: transits 24 h apart, got $gap s")
                    // Both days find the same night between them, lowest point included, and its Isha comes before this Fajr.
                    val a = checkNotNull(previous.nightAfter)
                    val b = checkNotNull(e.nightBefore)
                    for ((x, y) in listOf(a.sunset to b.sunset, a.sunrise to b.sunrise, a.lowestPoint to b.lowestPoint)) {
                        assertTrue(abs(Duration.between(x, y).toNanos()) < 1_000_000, "${place.zoneId} $date: the same night, $x and $y")
                    }
                    assertTrue(previous.at(TimeEvent.ISHA) <= e.at(TimeEvent.FAJR), "${place.zoneId} $date: Isha(D − 1) ≤ Fajr(D)")
                }
                previous = e
                date = date.plusDays(1)
            }
        }
    }

    @Test
    fun dailySunIsTakenAtZeroHoursUtOfTheCycleDate() {
        val daily = mwl.copy(id = "mwl_daily", sunEvaluation = TimeEvent.entries.associateWith { SunEvaluation.DAILY_0H_UT })
        val e = PrayerEngine.explain(MATA_UTU, LocalDate.parse("2026-10-13"), daily)
        val sun = SolarEngine.sun(SunModel.MEEUS, SolarEngine.julianDay(LocalDate.parse("2026-10-12")))
        val dhuhr = e.events[TimeEvent.DHUHR.ordinal]
        assertEquals(sun.declinationDeg, checkNotNull(dhuhr.declinationDeg), 1e-12)
        assertEquals(sun.equationOfTimeMin, checkNotNull(dhuhr.equationOfTimeMin), 1e-12)
    }

    @Test
    fun farPhoneZoneTakesConsecutiveCycles() {
        // Honolulu on a Europe/Paris phone: U = D on every day of 2026, across both summer-time changes.
        var date = LocalDate.of(2026, 1, 1)
        while (date.year == 2026) {
            assertEquals(date, PrayerEngine.explain(HONOLULU_ON_PARIS, date, mwl).cycleDate, "Honolulu $date")
            date = date.plusDays(1)
        }
        val dhuhr0329 = explain(HONOLULU_ON_PARIS, "2026-03-29").at(TimeEvent.DHUHR).atZone(HONOLULU_ON_PARIS.zone)
        val dhuhr1024 = explain(HONOLULU_ON_PARIS, "2026-10-24").at(TimeEvent.DHUHR).atZone(HONOLULU_ON_PARIS.zone)
        assertEquals(LocalDate.parse("2026-03-30"), dhuhr0329.toLocalDate())
        assertEquals(LocalDate.parse("2026-10-25"), dhuhr1024.toLocalDate())
        assertTrue(abs(dhuhr0329.toLocalTime().toSecondOfDay() - (36 * 60)) <= 120, "03-29's Dhuhr about 00:36 on 03-30: $dhuhr0329")
        assertTrue(abs(dhuhr1024.toLocalTime().toSecondOfDay() - (16 * 60)) <= 120, "10-24's Dhuhr about 00:16 on 10-25: $dhuhr1024")
    }
}
