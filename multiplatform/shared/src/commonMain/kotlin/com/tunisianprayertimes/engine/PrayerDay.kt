package com.tunisianprayertimes.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The six times of a prayer day, in the order of the day (plan 6.2). */
enum class TimeEvent { FAJR, SUNRISE, DHUHR, ASR, MAGHRIB, ISHA }

/** COMPUTED: the method's own time; ESTIMATED: a rule, bound or the order guard set it («تقديري»); ABSENT: none. */
enum class EventStatus { COMPUTED, ESTIMATED, ABSENT }

/** What produced an ESTIMATED time (plan 6.2, 7.4, 7.7). */
enum class RuleName {
    /** D5: angle/60 of the night, every night. */
    ANGLE_BASED,

    /** `mdf_12` and `luxembourg_shoura`: angle/60 of the night on nights without the angle, ramped (M2). */
    ANGLE_BASED_ABSENT_NIGHTS,
    LOCAL_RELATIVE_ESTIMATION,
    ONE_SEVENTH,
    MIDDLE_OF_NIGHT,
    NEAREST_DAY,
    CLOCK_CAPS,
    MOONSIGHTING_SEASONAL,
    DIYANET_TAKDIR,
    DUM_RF,
    DUM_RT,
    SUMMER_FORM,
    IRN_SOUTHERN_SUMMER,

    /** D6: the 5-hour minimum day and night. */
    MIN_DAY_NIGHT_5H,

    /** D6: Asr halfway between Dhuhr and Maghrib when the noon sun stays at or below the horizon. */
    POLAR_ASR_MIDPOINT,
    DIYANET_CLAMP,
    IRN_MECCA_CYCLE,

    /** ICCI's latest Isha (`ishaLatest`). */
    ISHA_LATEST,

    /** E19, the validity envelope: [EngineExplanation] names the bound. */
    VALIDITY_ENVELOPE,

    /** Step 9: a minute adjustment held at a bound of the sun's disk. */
    ADJUSTMENT_HELD,

    /** The order guard's walk within the day. */
    HELD_IN_ORDER,

    /** The order guard's night hold at the night's lowest point. */
    NIGHT_LOWEST_POINT,
}

/** One time of a [PrayerDay]: [at] is rounded to the minute, and null exactly when [status] is ABSENT. */
data class EventTime(
    val event: TimeEvent,
    val at: Instant?,
    val status: EventStatus,
    val rule: RuleName? = null,
) {
    init {
        require((at == null) == (status == EventStatus.ABSENT)) { "$event: an instant iff not ABSENT ($at, $status)" }
    }
}

/**
 * Prayer day [date], a civil date in [zone]: the solar cycle that step 1 of the solver selects for it
 * (plan 6.2). Fajr may fall on date − 1 and the Isha of date's evening on date + 1, in [zone].
 */
data class PrayerDay(
    val date: LocalDate,
    val zone: ZoneId,
    val placeKey: String,
    val events: List<EventTime>,
) {
    init {
        require(events.map { it.event } == TimeEvent.entries) { "six events in the order of the day: $events" }
    }

    operator fun get(event: TimeEvent): EventTime = events[event.ordinal]
}
