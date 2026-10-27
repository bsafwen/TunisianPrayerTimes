package com.tunisianprayertimes.worship

import com.tunisianprayertimes.engine.EventStatus
import com.tunisianprayertimes.engine.PrayerDay
import com.tunisianprayertimes.engine.TimeEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A position within a planning window, not a judgement about worship being performed. */
enum class VoluntaryPrayerStatus { UPCOMING, ACTIVE, ENDED }

data class VoluntaryPrayerWindow(
    val start: Instant,
    val end: Instant,
    val isEstimated: Boolean,
) {
    init {
        require(start < end)
    }

    fun status(now: Instant): VoluntaryPrayerStatus = when {
        now < start -> VoluntaryPrayerStatus.UPCOMING
        now < end -> VoluntaryPrayerStatus.ACTIVE
        else -> VoluntaryPrayerStatus.ENDED
    }
}

data class NightPrayerWindow(
    val eveningDate: LocalDate,
    val sunset: Instant,
    val isha: Instant,
    val fajr: Instant,
    /** The last third of the complete sunset-to-Fajr night. */
    val lastThirdStart: Instant,
    /** Prayer planning begins no earlier than Isha, even when the last third has already begun. */
    val lastThirdPrayerStart: Instant,
    val isEstimated: Boolean,
) {
    val prayerWindow: VoluntaryPrayerWindow
        get() = VoluntaryPrayerWindow(isha, fajr, isEstimated)

    val lastThirdWindow: VoluntaryPrayerWindow
        get() = VoluntaryPrayerWindow(lastThirdPrayerStart, fajr, isEstimated)
}

data class VoluntaryPrayerTimes(
    val date: LocalDate,
    val duha: VoluntaryPrayerWindow?,
    val night: NightPrayerWindow?,
)

private const val DUHA_AFTER_SUNRISE_MINUTES = 20L
private const val DUHA_BEFORE_TRANSIT_MINUTES = 20L

/**
 * Today's approximate Duha planning period and the current/upcoming night, in the selected place's
 * time zone. Fixed margins are only a planning estimate: the sun's sufficient rise varies by place
 * and season. The UI must retain that qualification even for [VoluntaryPrayerStatus.ACTIVE].
 *
 * [solarNoonFor] supplies astronomical transit, never the configured Dhuhr prayer time. An absent or
 * estimated sunrise cannot define a Duha period. Missing or unordered night events remain absent;
 * no fallback invents sunrise, sunset or dawn in polar conditions.
 */
fun voluntaryPrayerTimes(
    now: Instant,
    zone: ZoneId,
    dayFor: (LocalDate) -> PrayerDay?,
    solarNoonFor: (LocalDate) -> Instant?,
): VoluntaryPrayerTimes {
    val today = now.atZone(zone).toLocalDate()
    val days = mutableMapOf<LocalDate, PrayerDay?>()
    fun day(date: LocalDate): PrayerDay? = days.getOrPut(date) {
        dayFor(date)?.takeIf { it.date == date && it.zone == zone }
    }

    val todayDay = day(today)
    val duha = todayDay?.let { prayerDay ->
        val sunriseEvent = prayerDay[TimeEvent.SUNRISE]
        val sunrise = sunriseEvent.at
        val noon = solarNoonFor(today)
        if (sunriseEvent.status != EventStatus.COMPUTED || sunrise == null || noon == null ||
            sunrise.atZone(zone).toLocalDate() != today || noon.atZone(zone).toLocalDate() != today
        ) {
            null
        } else {
            val start = sunrise.plusSeconds(DUHA_AFTER_SUNRISE_MINUTES * 60)
            val end = noon.minusSeconds(DUHA_BEFORE_TRANSIT_MINUTES * 60)
            if (start < end) VoluntaryPrayerWindow(start, end, isEstimated = true) else null
        }
    }

    // Both Isha and Fajr can cross their prayer day's civil-date boundary. Compare adjacent nights
    // by actual instants, so a Fajr before midnight ends that night immediately.
    val candidates = (-1L..1L).map { offset ->
        val date = today.plusDays(offset)
        val evening = day(date)
        val morning = day(date.plusDays(1))
        val window = nightWindow(date, evening, morning)
        val sunset = evening?.get(TimeEvent.MAGHRIB)?.at
        val fajr = morning?.get(TimeEvent.FAJR)?.at?.takeIf { sunset == null || it > sunset }
        val sunrise = morning?.get(TimeEvent.SUNRISE)?.takeIf {
            it.status == EventStatus.COMPUTED
        }?.at?.takeIf { sunset == null || it > sunset }
        // A computed sunrise can establish that an incomplete previous night has ended without
        // inventing its absent Fajr. Always use this candidate's following morning.
        val ended = when {
            fajr != null -> now >= fajr
            sunrise != null -> now >= sunrise
            else -> false
        }
        NightCandidate(date, window, ended)
    }
    val current = candidates.mapNotNull { it.window }.filter {
        it.sunset <= now && now < it.fajr
    }.maxByOrNull { it.sunset }
    val upcoming = candidates.mapNotNull { it.window }.filter {
        now < it.sunset && now < it.fajr
    }.minByOrNull { it.sunset }
    // Do not silently jump over an unavailable current or nearer upcoming night. Once there is
    // evidence that the incomplete night ended, a fully available following night can be shown.
    val night = current ?: upcoming?.takeUnless { next ->
        candidates.any { !it.ended && it.window == null && it.eveningDate <= next.eveningDate }
    }
    return VoluntaryPrayerTimes(today, duha, night)
}

private data class NightCandidate(
    val eveningDate: LocalDate,
    val window: NightPrayerWindow?,
    val ended: Boolean,
)

private fun nightWindow(
    date: LocalDate,
    evening: PrayerDay?,
    morning: PrayerDay?,
): NightPrayerWindow? {
    if (evening == null || morning == null || evening.placeKey != morning.placeKey) return null
    val sunsetEvent = evening[TimeEvent.MAGHRIB]
    val ishaEvent = evening[TimeEvent.ISHA]
    val fajrEvent = morning[TimeEvent.FAJR]
    val sunset = sunsetEvent.at ?: return null
    val isha = ishaEvent.at ?: return null
    val fajr = fajrEvent.at ?: return null
    if (sunset >= isha || isha >= fajr || Duration.between(sunset, fajr) > Duration.ofDays(1)) return null

    // Elapsed time is essential on nights when the civil clock changes for DST.
    val lastThirdStart = fajr.minus(Duration.between(sunset, fajr).dividedBy(3))
    return NightPrayerWindow(
        eveningDate = date,
        sunset = sunset,
        isha = isha,
        fajr = fajr,
        lastThirdStart = lastThirdStart,
        lastThirdPrayerStart = maxOf(isha, lastThirdStart),
        isEstimated = listOf(sunsetEvent, ishaEvent, fajrEvent).any {
            it.status == EventStatus.ESTIMATED
        },
    )
}
