package com.tunisianprayertimes.method

import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

/**
 * E8: which days are Ramadan days for the method, worked out by the repository, never by the engine.
 * `UMM_AL_QURA_COMPUTED`: the Gregorian dates whose `HijrahDate` month is 9, as KACST's tables do.
 * `PLACE_CALENDAR`: the place's Hijri calendar; outside Tunisia there is none in the first release (A11),
 * so it means `UMM_AL_QURA_COMPUTED` there (6.10). A Tunisian delegation never reaches the engine.
 */
enum class RamadanDays { PLACE_CALENDAR, UMM_AL_QURA_COMPUTED }

/**
 * E8: true when [date], a civil date in the place's zone (6.2, step 5), is a Ramadan day for this method.
 * Only a method with Ramadan rules asks, so a method without them never depends on the calendar (6.6). The
 * rule applies to the evening of each Ramadan day, not to the eve of 1 Ramadan, and the buffered
 * `RamadanDetector.isRamadan` never drives it.
 */
fun PrayerMethod.isRamadanDay(date: LocalDate): Boolean {
    if (!hasRamadanRules) return false
    return when (ramadanDays) {
        RamadanDays.PLACE_CALENDAR, RamadanDays.UMM_AL_QURA_COMPUTED ->
            HijrahDate.from(date).get(ChronoField.MONTH_OF_YEAR) == RAMADAN
    }
}

private const val RAMADAN = 9
