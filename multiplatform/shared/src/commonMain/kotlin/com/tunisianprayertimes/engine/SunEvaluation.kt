package com.tunisianprayertimes.engine

/**
 * E4: when the sun is evaluated for an event (plan 6.2). U is the UT date of the day's solar cycle,
 * the civil date at every authority's place.
 */
enum class SunEvaluation {
    /** The default: the sun at the event's own UT instant, iterated. */
    EVENT_INSTANT,

    /** Declination and equation of time once, at 0h UT of U, for the event (Oman). */
    DAILY_0H_UT,

    /** The same at 10:00 UT of U, noon in Libya (`ly_awqaf`). */
    DAILY_10H_UT,

    /** The declination at 21:00 UT of U and the equation of time at 09:00 UT of U (KACST's Fajr and Asr). */
    KACST,

    /** Asr only: the noon shadow takes the declination at 00:00 of the civil date in the place's zone (Yemen). */
    LOCAL_MIDNIGHT_SHADOW,

    /** Asr only: the noon shadow is the one at Dhuhr's published instant, the transit plus Dhuhr's minutes (the UAE). */
    SHADOW_AT_DHUHR,

    /** Asr only: the noon shadow takes the declination at the Asr instant itself, iterated (Bahrain). */
    SHADOW_AT_EVENT,

    /** The sun once, at 12:00 local civil time of the date in the place's zone, summer time included (`irn`). */
    DAILY_LOCAL_NOON,

    /** The declination at the event's instant, iterated, with the equation of time and distance at local noon (`irn`). */
    EVENT_DECLINATION,

    /**
     * PrayTimes.js and aladhan.com's PHP port of it (Syria's mosque screens): each time's sun once, at its default hour
     * of local mean time on the cycle's date ([PRAYTIMES_HOURS]), so every time is solved in one pass. Asr's noon shadow
     * takes the declination at the zone's `asrShadowHourUt` when it has one (aladhan's server clock), else its own.
     */
    PRAYTIMES,
    ;

    companion object {
        /** PRAYTIMES: each time's default hour, local mean time (Fajr 5, sunrise 6, Dhuhr 12, Asr 13, Maghrib and Isha 18). */
        val PRAYTIMES_HOURS: Map<TimeEvent, Double> = mapOf(
            TimeEvent.FAJR to 5.0, TimeEvent.SUNRISE to 6.0, TimeEvent.DHUHR to 12.0,
            TimeEvent.ASR to 13.0, TimeEvent.MAGHRIB to 18.0, TimeEvent.ISHA to 18.0,
        )
    }
}
