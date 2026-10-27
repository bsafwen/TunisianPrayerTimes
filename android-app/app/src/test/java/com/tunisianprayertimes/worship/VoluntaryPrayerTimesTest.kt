package com.tunisianprayertimes.worship

import com.tunisianprayertimes.engine.EventStatus
import com.tunisianprayertimes.engine.EventTime
import com.tunisianprayertimes.engine.PrayerDay
import com.tunisianprayertimes.engine.TimeEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoluntaryPrayerTimesTest {
    private val date = LocalDate.of(2026, 10, 26)
    private val utc = ZoneOffset.UTC

    @Test
    fun duhaHasExplicitPlanningMarginsAndExclusiveEnd() {
        val result = calculate(at(date, "08:00"))
        val duha = requireNotNull(result.duha)
        assertEquals(at(date, "06:20"), duha.start)
        assertEquals(at(date, "11:40"), duha.end)
        assertTrue(duha.isEstimated)
        assertEquals(VoluntaryPrayerStatus.UPCOMING, duha.status(duha.start.minusNanos(1)))
        assertEquals(VoluntaryPrayerStatus.ACTIVE, duha.status(duha.start))
        assertEquals(VoluntaryPrayerStatus.ACTIVE, duha.status(duha.end.minusNanos(1)))
        assertEquals(VoluntaryPrayerStatus.ENDED, duha.status(duha.end))
    }

    @Test
    fun configuredDhuhrNeverChangesDuha() {
        val baseline = calculate(at(date, "08:00")).duha
        val altered = calculate(at(date, "08:00"), dayFor = {
            day(it).withEvent(TimeEvent.DHUHR, at(it, "17:45"), EventStatus.ESTIMATED)
        }).duha
        assertEquals(baseline, altered)
    }

    @Test
    fun absentAndEstimatedSunriseCannotCreateDuha() {
        for (status in listOf(EventStatus.ABSENT, EventStatus.ESTIMATED)) {
            assertNull(calculate(at(date, "08:00"), dayFor = {
                day(it).withEvent(TimeEvent.SUNRISE, if (status == EventStatus.ABSENT) null else at(it, "06:00"), status)
            }).duha)
        }
    }

    @Test
    fun missingOrInvalidTransitCannotCreateDuha() {
        for (noon in listOf(null, at(date, "06:40"), at(date, "06:30"), at(date.plusDays(1), "12:00"))) {
            assertNull(calculate(at(date, "08:00"), solarNoonFor = { noon }).duha)
        }
    }

    @Test
    fun currentDateComesFromSelectedPlaceZone() {
        val zone = ZoneId.of("Asia/Tokyo")
        val now = Instant.parse("2026-10-25T23:00:00Z")
        val result = voluntaryPrayerTimes(now, zone, { day(it, zone) }, { at(it, "12:00", zone) })
        assertEquals(date, result.date)
        assertEquals(at(date, "06:20", zone), result.duha?.start)
        assertEquals(date, result.night?.eveningDate)
    }

    @Test
    fun midnightKeepsTheSameNightUntilFajr() {
        val beforeMidnight = calculate(at(date, "23:59")).night
        val afterMidnight = calculate(at(date.plusDays(1), "00:01")).night
        assertEquals(beforeMidnight, afterMidnight)
        assertEquals(date, afterMidnight?.eveningDate)
        assertEquals(VoluntaryPrayerStatus.ACTIVE, afterMidnight?.prayerWindow?.status(at(date.plusDays(1), "00:01")))
    }

    @Test
    fun afterMidnightIshaRemainsUpcomingOnItsOriginalNight() {
        val tomorrow = date.plusDays(1)
        val result = calculate(at(tomorrow, "00:30"), dayFor = {
            day(it).withEvent(TimeEvent.ISHA, at(it.plusDays(1), "01:00"))
        })
        val night = requireNotNull(result.night)
        assertEquals(date, night.eveningDate)
        assertEquals(at(tomorrow, "01:00"), night.isha)
        assertEquals(VoluntaryPrayerStatus.UPCOMING, night.prayerWindow.status(at(tomorrow, "00:30")))
        assertEquals(VoluntaryPrayerStatus.ACTIVE, night.prayerWindow.status(at(tomorrow, "01:00")))
    }

    @Test
    fun fajrBoundarySwitchesToFollowingEvening() {
        val fajr = at(date, "05:00")
        assertEquals(date.minusDays(1), calculate(fajr.minusNanos(1)).night?.eveningDate)
        val next = requireNotNull(calculate(fajr).night)
        assertEquals(date, next.eveningDate)
        assertEquals(VoluntaryPrayerStatus.UPCOMING, next.prayerWindow.status(fajr))
    }

    @Test
    fun fajrOnPreviousCivilDateEndsTheNightBeforeMidnight() {
        val tomorrow = date.plusDays(1)
        val earlyFajr = at(date, "23:30")
        val days: (LocalDate) -> PrayerDay = {
            if (it == tomorrow) day(it).withEvent(TimeEvent.FAJR, earlyFajr) else day(it)
        }
        assertEquals(date, calculate(earlyFajr.minusNanos(1), dayFor = days).night?.eveningDate)
        val next = requireNotNull(calculate(earlyFajr, dayFor = days).night)
        assertEquals(tomorrow, next.eveningDate)
        assertEquals(VoluntaryPrayerStatus.UPCOMING, next.prayerWindow.status(at(date, "23:45")))
        assertTrue(at(date, "23:45") < next.fajr)
    }

    @Test
    fun absentPreviousFajrDoesNotHideCompleteUpcomingNightAfterSunrise() {
        val result = calculate(at(date, "08:00"), dayFor = {
            if (it == date) day(it).withEvent(TimeEvent.FAJR, null, EventStatus.ABSENT) else day(it)
        })
        assertEquals(date, result.night?.eveningDate)
        assertEquals(VoluntaryPrayerStatus.UPCOMING, result.night?.prayerWindow?.status(at(date, "08:00")))
    }

    @Test
    fun completeCurrentNightTakesPrecedenceOverMissingPreviousFajr() {
        val result = calculate(at(date, "21:00"), dayFor = {
            if (it == date) day(it).withEvent(TimeEvent.FAJR, null, EventStatus.ABSENT) else day(it)
        })
        assertEquals(date, result.night?.eveningDate)
        assertEquals(VoluntaryPrayerStatus.ACTIVE, result.night?.prayerWindow?.status(at(date, "21:00")))
    }

    @Test
    fun missingCurrentFajrBeforeSunriseDoesNotJumpToTonight() {
        val result = calculate(at(date, "03:00"), dayFor = {
            if (it == date) day(it).withEvent(TimeEvent.FAJR, null, EventStatus.ABSENT) else day(it)
        })
        assertNull(result.night)
    }

    @Test
    fun missingTonightFajrIsNotEndedByThisMorningsSunrise() {
        val result = calculate(at(date, "21:00"), dayFor = {
            if (it == date.plusDays(1)) day(it).withEvent(TimeEvent.FAJR, null, EventStatus.ABSENT) else day(it)
        })
        assertNull(result.night)
    }

    @Test
    fun incompleteUpcomingNightDoesNotJumpToFollowingEvening() {
        val result = calculate(at(date, "08:00"), dayFor = {
            if (it == date) day(it).withEvent(TimeEvent.ISHA, null, EventStatus.ABSENT) else day(it)
        })
        assertNull(result.night)
    }

    @Test
    fun fajrBeforeSunsetCannotProveAnInvalidCurrentNightHasEnded() {
        val result = calculate(at(date, "21:00"), dayFor = {
            if (it == date.plusDays(1)) day(it).withEvent(TimeEvent.FAJR, at(date, "17:00")) else day(it)
        })
        assertNull(result.night)
    }

    @Test
    fun lastThirdIsMeasuredFromSunsetAndNotFromIsha() {
        val night = requireNotNull(calculate(at(date, "21:00")).night)
        assertEquals(at(date.plusDays(1), "01:20"), night.lastThirdStart)
        assertEquals(night.lastThirdStart, night.lastThirdPrayerStart)
        assertEquals(Duration.ofHours(11), Duration.between(night.sunset, night.fajr))
        assertFalse(night.isEstimated)
    }

    @Test
    fun lateIshaConstrainsPrayerWindowButPreservesAstronomicalThird() {
        val night = requireNotNull(calculate(at(date.plusDays(1), "02:00"), dayFor = {
            day(it).withEvent(TimeEvent.ISHA, at(it.plusDays(1), "03:00"))
        }).night)
        assertEquals(at(date.plusDays(1), "01:20"), night.lastThirdStart)
        assertEquals(at(date.plusDays(1), "03:00"), night.lastThirdPrayerStart)
        assertEquals(VoluntaryPrayerStatus.UPCOMING, night.lastThirdWindow.status(at(date.plusDays(1), "02:00")))
    }

    @Test
    fun daylightSavingNightsUseElapsedInstants() {
        val zone = ZoneId.of("Europe/Paris")
        val cases = listOf(
            Triple(LocalDate.of(2026, 3, 28), Duration.ofHours(11), Instant.parse("2026-03-29T00:20:00Z")),
            Triple(LocalDate.of(2026, 10, 24), Duration.ofHours(13), Instant.parse("2026-10-25T00:40:00Z")),
        )
        for ((evening, length, third) in cases) {
            val result = voluntaryPrayerTimes(
                at(evening, "21:00", zone), zone,
                { day(it, zone).withEvent(TimeEvent.FAJR, at(it, "06:00", zone)) },
                { at(it, "12:00", zone) },
            )
            val night = requireNotNull(result.night)
            assertEquals(length, Duration.between(night.sunset, night.fajr))
            assertEquals(third, night.lastThirdStart)
        }
    }

    @Test
    fun eachEstimatedNightEndpointPropagatesToBothWindows() {
        for (event in listOf(TimeEvent.MAGHRIB, TimeEvent.ISHA, TimeEvent.FAJR)) {
            val night = requireNotNull(calculate(at(date, "21:00"), dayFor = {
                val value = day(it)
                value.withEvent(event, value[event].at, EventStatus.ESTIMATED)
            }).night)
            assertTrue(night.isEstimated)
            assertTrue(night.prayerWindow.isEstimated)
            assertTrue(night.lastThirdWindow.isEstimated)
        }
    }

    @Test
    fun missingNightEventsRemainUnavailable() {
        for (event in listOf(TimeEvent.MAGHRIB, TimeEvent.ISHA, TimeEvent.FAJR)) {
            assertNull(calculate(at(date, "21:00"), dayFor = {
                day(it).withEvent(event, null, EventStatus.ABSENT)
            }).night)
        }
    }

    @Test
    fun missingRequiredDatesRemainUnavailable() {
        assertNull(calculate(at(date, "03:00"), dayFor = { if (it < date) null else day(it) }).night)
        assertNull(calculate(at(date, "21:00"), dayFor = { if (it > date) null else day(it) }).night)
        val noToday = calculate(at(date, "21:00"), dayFor = { if (it == date) null else day(it) })
        assertNull(noToday.duha)
        assertNull(noToday.night)
    }

    @Test
    fun invalidNightOrderingCannotProduceAWindow() {
        for (isha in listOf(at(date, "17:00"), at(date, "18:00"), at(date.plusDays(1), "05:00"), at(date.plusDays(1), "06:00"))) {
            assertNull(calculate(at(date, "21:00"), dayFor = {
                if (it == date) day(it).withEvent(TimeEvent.ISHA, isha) else day(it)
            }).night)
        }
    }

    @Test
    fun differentPlacesDatesAndZonesCannotBeCombined() {
        assertNull(calculate(at(date, "21:00"), dayFor = {
            day(it).copy(placeKey = if (it == date) "first" else "second")
        }).night)
        assertNull(calculate(at(date, "21:00"), dayFor = { day(it.plusDays(1)) }).night)
        assertNull(calculate(at(date, "21:00"), dayFor = { day(it, ZoneId.of("Europe/Paris")) }).night)
    }

    @Test
    fun unavailableNightDoesNotHideValidDuha() {
        val result = calculate(at(date, "08:00"), dayFor = {
            day(it).withEvent(TimeEvent.MAGHRIB, null, EventStatus.ABSENT)
        })
        assertNotNull(result.duha)
        assertNull(result.night)
    }

    private fun calculate(
        now: Instant,
        dayFor: (LocalDate) -> PrayerDay? = { day(it) },
        solarNoonFor: (LocalDate) -> Instant? = { at(it, "12:00") },
    ) = voluntaryPrayerTimes(now, utc, dayFor, solarNoonFor)

    private fun day(date: LocalDate, zone: ZoneId = utc): PrayerDay {
        val clocks = listOf("05:00", "06:00", "12:05", "15:00", "18:00", "19:30")
        return PrayerDay(date, zone, "test-place", TimeEvent.entries.mapIndexed { index, event ->
            EventTime(event, at(date, clocks[index], zone), EventStatus.COMPUTED)
        })
    }

    private fun PrayerDay.withEvent(
        event: TimeEvent,
        instant: Instant?,
        status: EventStatus = EventStatus.COMPUTED,
    ) = copy(events = events.map { if (it.event == event) EventTime(event, instant, status) else it })

    private fun at(date: LocalDate, time: String, zone: ZoneId = utc): Instant =
        date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
}
