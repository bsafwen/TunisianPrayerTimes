package com.tunisianprayertimes.engine

import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.method.Rounding
import java.time.Instant
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.roundToLong

/** The working behind one [PrayerDay], for the explainer (plan 6.2, 6.11). */
data class EngineExplanation(
    val day: PrayerDay,
    /** U, the UT date of the day's solar cycle (solver step 1). */
    val cycleDate: LocalDate,
    /** N, the civil noon in the zone's standard time that selects U. */
    val civilNoon: Instant,
    /** The method as it stood on the date (E9). */
    val method: PrayerMethod,
    /** The zone's shift per time, published or derived from its base zone (6.2, 8.8). */
    val zoneOffsets: Map<TimeEvent, Double>,
    /** N(D − 1), from sunset of date − 1 to this date's sunrise. */
    val nightBefore: NightExplanation?,
    /** N(D), from this date's sunset to sunrise of date + 1. */
    val nightAfter: NightExplanation?,
    val events: List<EventExplanation>,
)

/** A night between instants (plan 6.2, "Night"), and its lowest point, which separates prayer days. */
data class NightExplanation(val sunset: Instant, val sunrise: Instant, val lowestPoint: Instant) {
    val hours: Double get() = (sunrise.epochSecond - sunset.epochSecond) / 3600.0
}

/**
 * One time: the point and sun it was solved at, its instant before any minutes ([raw]), every change
 * after that in the order of operations, and the rounding.
 */
data class EventExplanation(
    val event: TimeEvent,
    val latitudeDeg: Double?,
    val longitudeDeg: Double?,
    /** The altitude solved for; null for Dhuhr (the transit) and for a time by interval. */
    val altitudeDeg: Double?,
    val declinationDeg: Double?,
    val equationOfTimeMin: Double?,
    val raw: Instant?,
    val steps: List<EngineStep>,
    val unrounded: Instant?,
    val rounding: Rounding,
    val time: EventTime,
)

/** One change to a time: in [step] of the order of operations, by [minutes] (signed). */
data class EngineStep(
    val step: Int,
    val kind: StepKind,
    val minutes: Double,
    val bound: EnvelopeBound? = null,
    val rule: RuleName? = null,
)

enum class StepKind {
    METHOD_OFFSET,
    DHUHR_AFTER_TRANSIT,
    TERRAIN,
    RAMADAN_OFFSET,
    ZONE_OFFSET,
    ELEVATION_SHIFT,
    SEASONAL_OFFSET,
    HIGH_LATITUDE,
    POLAR,
    ISHA_LATEST,
    ISHA_EARLIEST,
    VALIDITY_ENVELOPE,
    ADJUSTMENT,
    ADJUSTMENT_HELD,
    HELD_IN_ORDER,
    NIGHT_LOWEST_POINT,
    HELD_IN_ORDER_ON_MINUTES,
}

/** E19's bounds (plan 7.7). */
enum class EnvelopeBound {
    /** Fajr not before the 18° instant. */
    FAJR_18,

    /** A custom method outside mainland France: Fajr not before the 20° instant. */
    FAJR_20,

    /** On a night without the floor's angle, Fajr not before solar midnight, the night's lowest point. */
    FAJR_SOLAR_MIDNIGHT,

    /** Fajr not after the 12° instant. */
    FAJR_12,

    /** Sunrise not after its computed time. */
    SUNRISE,

    /** Dhuhr not before transit + 1.2 min. */
    DHUHR,

    /** Asr not before the factor-1 shadow. */
    ASR,

    /** Maghrib not before sunset + 1 min. */
    MAGHRIB,

    /** Isha not before the 12° instant; an estimated Isha before it is marked «تقديري». */
    ISHA_12,

    /** An estimated Isha not before the Moonsighting Committee's «Shafaq Ahmer» curve. */
    ISHA_AHMER,
}

internal fun instantOf(epochSeconds: Double): Instant {
    val whole = floor(epochSeconds)
    val nanos = ((epochSeconds - whole) * 1e9).roundToLong().coerceIn(0, 999_999_999)
    return Instant.ofEpochSecond(whole.toLong(), nanos)
}
