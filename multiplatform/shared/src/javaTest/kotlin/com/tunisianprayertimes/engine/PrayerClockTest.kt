package com.tunisianprayertimes.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Next, current and the prayer day by instant, over D − 1..D + 1 in the place's zone (plan 6.6). */
class PrayerClockTest {
    private val zone = ZoneId.of("Europe/Paris")

    /** Test prayer days with Isha at 01:07 after midnight, as at Paris in June. */
    private fun day(date: LocalDate): PrayerDay {
        fun at(time: String, days: Long = 0) = date.plusDays(days).atTime(java.time.LocalTime.parse(time)).atZone(zone).toInstant()
        val times = listOf(at("03:50"), at("05:47"), at("13:54"), at("18:10"), at("21:58"), at("01:07", 1))
        return PrayerDay(date, zone, "test", TimeEvent.entries.zip(times).map { (e, t) -> EventTime(e, t, EventStatus.COMPUTED) })
    }

    private val clock = PrayerClock(zone, ::day)

    private fun local(text: String): Instant = java.time.LocalDateTime.parse(text).atZone(zone).toInstant()

    @Test
    fun yesterdaysIshaAfterMidnightIsStillNext() {
        val now = local("2026-06-22T00:30")
        val next = checkNotNull(clock.next(now))
        assertEquals(LocalDate.parse("2026-06-21"), next.date)
        assertEquals(TimeEvent.ISHA, next.event)
        assertEquals(LocalDate.parse("2026-06-21"), clock.prayerDay(now))
        assertEquals(TimeEvent.MAGHRIB, checkNotNull(clock.current(now)).event)
    }

    @Test
    fun afterTheLastIshaTheNextDaysFajrIsNext() {
        val now = local("2026-06-22T01:30")
        assertEquals(TimeEvent.FAJR, checkNotNull(clock.next(now)).event)
        assertEquals(LocalDate.parse("2026-06-22"), checkNotNull(clock.next(now)).date)
        assertEquals(LocalDate.parse("2026-06-21"), clock.prayerDay(now))
    }

    @Test
    fun prayersOnlyAndTies() {
        val now = local("2026-06-22T04:00")
        assertEquals(TimeEvent.SUNRISE, checkNotNull(clock.next(now)).event)
        assertEquals(TimeEvent.DHUHR, checkNotNull(clock.next(now, PrayerClock.PRAYERS)).event)
        // A tie across days is ordered by day: yesterday's Isha before today's Fajr at the same instant.
        val tied = PrayerClock(zone) { date ->
            val base = day(date)
            val fajr = base[TimeEvent.FAJR].copy(at = day(date.minusDays(1))[TimeEvent.ISHA].at)
            base.copy(events = base.events.map { if (it.event == TimeEvent.FAJR) fajr else it })
        }
        val timeline = tied.timeline(local("2026-06-22T00:00"))
        val i = timeline.indexOfFirst { it.event == TimeEvent.ISHA && it.date == LocalDate.parse("2026-06-21") }
        assertEquals(TimeEvent.FAJR, timeline[i + 1].event)
        assertEquals(LocalDate.parse("2026-06-22"), timeline[i + 1].date)
    }

    @Test
    fun todayIsThePlacesDateNotThePhones() {
        // A Kiritimati place (UTC+14) read at 2026-06-21T23:00Z: D is already 22 June there.
        val kiritimati = PrayerClock(ZoneId.of("Pacific/Kiritimati")) { null }
        assertEquals(LocalDate.parse("2026-06-22"), kiritimati.today(Instant.parse("2026-06-21T23:00:00Z")))
    }
}
