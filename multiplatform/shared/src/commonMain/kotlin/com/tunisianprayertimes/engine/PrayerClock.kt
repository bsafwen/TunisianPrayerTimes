package com.tunisianprayertimes.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Next, current and "which prayer day is it" by instant (plan 6.6), over prayer days D − 1..D + 1,
 * where D is today in the place's [zone], never the phone's date: the two can be 25 h apart. So at
 * 00:30, yesterday's Isha at 01:07 is still next. Ties are ordered by day, then by the order of the day.
 */
class PrayerClock(private val zone: ZoneId, private val dayOf: (LocalDate) -> PrayerDay?) {
    /** One time of one prayer day. */
    data class Occurrence(val date: LocalDate, val time: EventTime) {
        val at: Instant get() = checkNotNull(time.at)
        val event: TimeEvent get() = time.event
    }

    /** D: today in the place's zone. */
    fun today(now: Instant): LocalDate = now.atZone(zone).toLocalDate()

    /** The present [events] of prayer days D − 1..D + 1, by instant, then day, then the order of the day. */
    fun timeline(now: Instant, events: Set<TimeEvent> = ALL): List<Occurrence> {
        val today = today(now)
        return (-1L..1L).flatMap { offset ->
            val date = today.plusDays(offset)
            dayOf(date)?.events.orEmpty().filter { it.at != null && it.event in events }.map { Occurrence(date, it) }
        }.sortedWith(compareBy<Occurrence>({ it.at }, { it.date }, { it.event.ordinal }))
    }

    /** The first time strictly after [now]. */
    fun next(now: Instant, events: Set<TimeEvent> = ALL): Occurrence? = timeline(now, events).firstOrNull { it.at > now }

    /** The last time at or before [now]. */
    fun current(now: Instant, events: Set<TimeEvent> = ALL): Occurrence? = timeline(now, events).lastOrNull { it.at <= now }

    /** The prayer day [now] belongs to: the day of the current time, or D − 1 before any time of the window. */
    fun prayerDay(now: Instant): LocalDate = current(now)?.date ?: today(now).minusDays(1)

    companion object {
        val ALL: Set<TimeEvent> = TimeEvent.entries.toSet()

        /** The five prayers, without sunrise. */
        val PRAYERS: Set<TimeEvent> = ALL - TimeEvent.SUNRISE
    }
}
