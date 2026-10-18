package com.tunisianprayertimes

import android.content.Context
import com.tunisianprayertimes.engine.InmPrayerDays
import com.tunisianprayertimes.engine.PrayerClock
import com.tunisianprayertimes.engine.PrayerDay
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.PrayerMethodChoice
import com.tunisianprayertimes.place.PlaceTimes
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.platform.PrayerDataLoader
import com.tunisianprayertimes.silence.SilenceDefaults
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Calendar

/**
 * The only dispatch point of prayer times on the phone (plan 6.8, 6.9): a Tunisian delegation through INM's
 * formula ([InmPrayerFormula], which reproduces meteo.tn; shared with the TV through [InmPrayerTimes]), any
 * other place through the shared engine with its country's method choice and official zone. Times are
 * instants ([PrayerDay]); every cache key holds the place key and the choice key.
 */
object PrayerTimesRepository {

    /** Years offered in the app; meteo.tn publishes (and the formula is validated from) 2020. */
    val SUPPORTED_YEARS = InmPrayerTimes.SUPPORTED_YEARS

    /** The Jumu'a an hour before Asr that about one Tunisian user in ten sets (their mosque's). */
    const val JUMUA_MINUTES_BEFORE_ASR = 60

    private fun source(context: Context): InmPrayerTimes = PrayerDataLoader.prayerTimes(context)

    @Volatile
    private var placeTimes: PlaceTimes? = null

    @Volatile
    private var inmTimes: PlaceTimes? = null

    /** The routing with the official-zone assets (about 5 MB of JSON, parsed once): places abroad only. */
    private fun placeTimes(context: Context): PlaceTimes = placeTimes ?: synchronized(this) {
        placeTimes ?: PlaceTimes(InmPrayerDays(source(context)), OfficialZoneAssets.index(context)).also { placeTimes = it }
    }

    /** A delegation's routing never reads a zone, so it does not parse the assets: no cost in Tunisia or in a cold alarm process. */
    private fun inmTimes(context: Context): PlaceTimes = inmTimes ?: synchronized(this) {
        inmTimes ?: PlaceTimes(InmPrayerDays(source(context))).also { inmTimes = it }
    }

    /** The routing itself, for the travel comparison of 6.8 (the mapped official zone of a fix). */
    internal fun placeTimesFor(context: Context): PlaceTimes = placeTimes(context)

    private val days = object : LinkedHashMap<String, PrayerDay?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PrayerDay?>?) = size > 512
    }

    /** The saved place (6.8). */
    fun place(context: Context): PrayerPlace = PrefsManager.getPlace(context)

    /**
     * A place's method choice: its country's saved one where its method is offered at the place, else the country's
     * default (6.4, owner 2026-10-18); none for a delegation.
     */
    fun choice(context: Context, place: PrayerPlace = place(context)): PrayerMethodChoice? = when (place) {
        is PrayerPlace.Inm -> null
        is PrayerPlace.Coordinates -> placeTimes(context).choiceAt(place, PrefsManager.getMethodChoice(context, place))
    }

    /** What the times depend on (6.6): the place key, then the choice key. */
    fun timesKey(context: Context, place: PrayerPlace = place(context)): String =
        place.cacheKey + (choice(context, place)?.let { "|" + it.key } ?: "")

    /** Prayer day [date] (a civil date in the place's zone), or null outside the supported years or INM's delegations. */
    fun prayerDay(context: Context, date: LocalDate, place: PrayerPlace = place(context)): PrayerDay? {
        val choice = choice(context, place)
        val key = "${place.cacheKey}|${choice?.key}|$date"
        synchronized(days) { if (days.containsKey(key)) return days[key] }
        val times = if (place is PrayerPlace.Inm) inmTimes(context) else placeTimes(context)
        val day = times.day(place, choice, date)
        synchronized(days) { days[key] = day }
        return day
    }

    /** Next, current and the prayer day by instant over D − 1..D + 1 in the place's zone (6.6). */
    fun clock(context: Context, place: PrayerPlace = place(context)): PrayerClock =
        PrayerClock(place.zone) { prayerDay(context, it, place) }

    /** The times after [now] over D − 1..D + 1, by instant (6.9). */
    fun upcoming(context: Context, now: Instant = Instant.now(), place: PrayerPlace = place(context)): List<PrayerClock.Occurrence> =
        clock(context, place).timeline(now).filter { it.at > now }

    /** One prayer of one prayer day, as an instant. */
    data class PrayerOccurrence(val prayer: Prayer, val prayerDay: LocalDate, val at: Instant)

    /** The five prayers of prayer day [date], by instant: on a Friday, Jumu'a at its resolved time in Dhuhr's place (A18). */
    fun prayerOccurrences(context: Context, date: LocalDate, place: PrayerPlace = place(context)): List<PrayerOccurrence> {
        val day = prayerDay(context, date, place) ?: return emptyList()
        val friday = date.dayOfWeek == java.time.DayOfWeek.FRIDAY
        return listOfNotNull(
            day[TimeEvent.FAJR].at?.let { PrayerOccurrence(Prayer.FAJR, date, it) },
            if (friday) jumuaTime(context, date, place)?.let { PrayerOccurrence(Prayer.JOMOAA, date, it) }
            else day[TimeEvent.DHUHR].at?.let { PrayerOccurrence(Prayer.DHUHR, date, it) },
            day[TimeEvent.ASR].at?.let { PrayerOccurrence(Prayer.ASR, date, it) },
            day[TimeEvent.MAGHRIB].at?.let { PrayerOccurrence(Prayer.MAGHRIB, date, it) },
            day[TimeEvent.ISHA].at?.let { PrayerOccurrence(Prayer.ISHA, date, it) },
        ).sortedBy { it.at }
    }

    /**
     * The prayers after [now] over prayer days D − 1..D + 1, D being today in the place's zone (6.6), by instant,
     * so at 00:30 yesterday's 01:07 Isha is still next.
     */
    fun upcomingPrayers(context: Context, now: Instant = Instant.now(), place: PrayerPlace = place(context)): List<PrayerOccurrence> {
        val today = now.atZone(place.zone).toLocalDate()
        return (-1L..1L).flatMap { prayerOccurrences(context, today.plusDays(it), place) }
            .filter { it.at > now }
            .sortedWith(compareBy<PrayerOccurrence>({ it.at }, { it.prayerDay }))
    }

    /**
     * The Friday [date]'s Jumu'a time: the edited time of the place's country (6.9), or the set minutes before that
     * Friday's Asr (a mosque that prays an hour before Asr), never before Dhuhr; else [defaultJumuaTime].
     */
    fun jumuaTime(context: Context, date: LocalDate, place: PrayerPlace = place(context)): Instant? {
        PrefsManager.getJumuaTime(context, place)?.let { return ZonedDateTime.of(date, it, place.zone).toInstant() }
        PrefsManager.getJumuaMinutesBeforeAsr(context, place)?.let { minutes -> jumuaBeforeAsr(context, date, place, minutes)?.let { return it } }
        return defaultJumuaTime(context, place, date)
    }

    /** [minutes] before the Friday [date]'s Asr, never before its Dhuhr; null without an Asr that day. */
    fun jumuaBeforeAsr(context: Context, date: LocalDate, place: PrayerPlace, minutes: Int): Instant? {
        val day = prayerDay(context, date, place) ?: return null
        val asr = day[TimeEvent.ASR].at ?: return null
        val at = asr.minusSeconds(minutes * 60L)
        return day[TimeEvent.DHUHR].at?.let { dhuhr -> maxOf(at, dhuhr) } ?: at
    }

    /** A18: the authority's Friday time, then the place's or country's entry of [SilenceDefaults], then Dhuhr. */
    fun defaultJumuaTime(context: Context, place: PrayerPlace, date: LocalDate): Instant? {
        val dhuhr = prayerDay(context, date, place)?.get(TimeEvent.DHUHR)?.at ?: return null
        return SilenceDefaults.BUNDLED.jumuaTime(place, date, dhuhr)
    }

    /** The dates with times, for both place kinds: the engine's and INM's supported years. */
    fun getDateRange(place: PrayerPlace): ClosedRange<LocalDate> =
        LocalDate.of(SUPPORTED_YEARS.first, 1, 1)..LocalDate.of(SUPPORTED_YEARS.last, 12, 31)

    /** Drops the computed days, after an asset or rule change; place and method changes need no call (their keys change). */
    fun invalidate() {
        synchronized(days) { days.clear() }
    }

    // --- INM's tables by delegation, for the callers not yet on instants (6.6) ---

    /**
     * Returns true when prayer times exist for the month: the delegation is one INM
     * publishes and the year is within [SUPPORTED_YEARS].
     */
    fun hasPrayerData(context: Context, delegationId: Int, year: Int, month: Int): Boolean =
        source(context).hasPrayerData(delegationId, year, month)

    /**
     * Prayer times for every day of the month, or an empty list when the delegation or
     * year has no data.
     */
    fun loadPrayerTimes(context: Context, delegationId: Int, year: Int, month: Int): List<DayPrayerTimes> =
        source(context).loadPrayerTimes(delegationId, year, month)

    /**
     * Load prayer times for a specific day.
     */
    fun loadDayPrayerTimes(context: Context, delegationId: Int, year: Int, month: Int, day: Int): DayPrayerTimes? =
        source(context).loadDayPrayerTimes(delegationId, year, month, day)

    /**
     * Returns the (minMillis, maxMillis) date range with prayer times for the given
     * delegation, or null if it has none.
     */
    fun getDateRange(context: Context, delegationId: Int): Pair<Long, Long>? {
        if (!source(context).hasDelegation(delegationId)) return null
        val minCal = Calendar.getInstance().apply {
            set(SUPPORTED_YEARS.first, Calendar.JANUARY, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val maxCal = Calendar.getInstance().apply {
            set(SUPPORTED_YEARS.last, Calendar.DECEMBER, 31, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return minCal.timeInMillis to maxCal.timeInMillis
    }
}
