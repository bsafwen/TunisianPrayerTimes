package com.tunisianprayertimes.engine

import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.PrayerMethod
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs

/** What step 6 makes of a Fajr or an Isha: its instant (null when absent) and the rule, when it moved it. */
internal data class Bounded(val seconds: Double?, val rule: RuleName?)

/**
 * The high-latitude rules of step 6 (plan 6.2, 7.4) on the angle times of a date. The nights are those
 * of steps 1–3 ([Season]): Isha takes N(D), from the date's sunset to the next sunrise, and Fajr N(D − 1).
 */
internal class HighLatitudeRules(
    private val season: Season,
    private val date: LocalDate,
    private val zone: ZoneId,
    private val method: PrayerMethod,
    /** Step 4's Maghrib before the zone's shift, which the summer forms count Isha from (Isha takes its own shift in step 7). */
    private val maghrib: Double?,
    private val latitudeDeg: Double,
) {
    private val ends = season.ends(date)
    private val sunrise = ends.sunrise
    private val sunset = ends.sunset
    private val nightBefore = season.nightBefore(date)
    private val nightAfter = season.nightAfter(date)

    fun fajr(angleTime: Double?, angleDeg: Double): Bounded {
        val sunrise = sunrise ?: return Bounded(angleTime, null)
        val night = nightBefore
        return when (val rule = method.highLatitude) {
            HighLatitudeRule.AngleBased ->
                night?.let { later(angleTime, sunrise - angleDeg / 60 * it.seconds, RuleName.ANGLE_BASED) } ?: Bounded(angleTime, null)
            HighLatitudeRule.AngleBasedAbsentNights ->
                absent(Side.FAJR, angleTime, RuleName.ANGLE_BASED_ABSENT_NIGHTS) { d -> season.nightSeconds(d.minusDays(1))?.let { angleDeg / 60 * it } }
            HighLatitudeRule.LocalRelativeEstimation -> {
                val r = season.lreRatio(date.year) ?: angleDeg / 60
                absent(Side.FAJR, angleTime, RuleName.LOCAL_RELATIVE_ESTIMATION) { d -> season.nightSeconds(d.minusDays(1))?.let { r * it } }
            }
            HighLatitudeRule.OneSeventh ->
                absent(Side.FAJR, angleTime, RuleName.ONE_SEVENTH) { d -> season.nightSeconds(d.minusDays(1))?.let { it / 7 } }
            is HighLatitudeRule.MiddleOfNight ->
                absent(Side.FAJR, angleTime, RuleName.MIDDLE_OF_NIGHT, rule.ramped) { d -> season.nightSeconds(d.minusDays(1))?.let { it / 2 } }
            is HighLatitudeRule.NearestDay -> nearestDay(Side.FAJR, angleTime, rule.lastClockTime)
            is HighLatitudeRule.ClockCaps -> later(angleTime, morningClock(rule.fajrEarliest), RuleName.CLOCK_CAPS)
            is HighLatitudeRule.MoonsightingSeasonal -> {
                val base = if (abs(latitudeDeg) >= 55.0) night?.let { sunrise - it.seconds / 7 } else angleTime
                val curve = sunrise - MoonsightingSeasonal.minutes(MoonsightingSeasonal.Curve.FAJR, latitudeDeg, date) * 60
                moved(angleTime, if (base == null) curve else maxOf(base, curve), RuleName.MOONSIGHTING_SEASONAL)
            }
            HighLatitudeRule.DiyanetTakdir -> takdir(Side.FAJR, angleTime, angleDeg)
            // DUM RF's night: 24 h minus the same date's day (H4), as its tables take it.
            HighLatitudeRule.DumRf -> sunset?.let { set -> later(angleTime, sunrise - 0.30 * (DAY_SECONDS - (set - sunrise)), RuleName.DUM_RF) } ?: Bounded(angleTime, null)
            HighLatitudeRule.DumRt ->
                if (night != null && staysAbove18(night)) moved(angleTime, sunrise - 2 * 3600.0, RuleName.DUM_RT) else Bounded(angleTime, null)
            // H5: D5's bound on the other dates, under the summer form's own name.
            is HighLatitudeRule.SummerForm ->
                if (rule.covers(MonthDay.from(date))) later(angleTime, sunrise - rule.fajrBeforeSunrise * 60.0, RuleName.SUMMER_FORM)
                else night?.let { later(angleTime, sunrise - angleDeg / 60 * it.seconds, RuleName.SUMMER_FORM) } ?: Bounded(angleTime, null)
            HighLatitudeRule.Irn -> IrnRule(season, zone, method).fajr(date, angleTime, sunrise, nightBefore)
        }
    }

    fun isha(angleTime: Double?, angleDeg: Double): Bounded {
        val sunset = sunset ?: return Bounded(angleTime, null)
        val night = nightAfter
        return when (val rule = method.highLatitude) {
            HighLatitudeRule.AngleBased ->
                night?.let { earlier(angleTime, sunset + angleDeg / 60 * it.seconds, RuleName.ANGLE_BASED) } ?: Bounded(angleTime, null)
            HighLatitudeRule.AngleBasedAbsentNights ->
                absent(Side.ISHA, angleTime, RuleName.ANGLE_BASED_ABSENT_NIGHTS) { d -> season.nightSeconds(d)?.let { angleDeg / 60 * it } }
            HighLatitudeRule.LocalRelativeEstimation -> {
                val r = season.lreRatio(date.year) ?: angleDeg / 60
                absent(Side.ISHA, angleTime, RuleName.LOCAL_RELATIVE_ESTIMATION) { d -> season.nightSeconds(d)?.let { r * it } }
            }
            HighLatitudeRule.OneSeventh ->
                absent(Side.ISHA, angleTime, RuleName.ONE_SEVENTH) { d -> season.nightSeconds(d)?.let { it / 7 } }
            is HighLatitudeRule.MiddleOfNight ->
                absent(Side.ISHA, angleTime, RuleName.MIDDLE_OF_NIGHT, rule.ramped) { d -> season.nightSeconds(d)?.let { it / 2 } }
            is HighLatitudeRule.NearestDay -> nearestDay(Side.ISHA, angleTime, rule.lastClockTime)
            is HighLatitudeRule.ClockCaps -> earlier(angleTime, eveningClock(rule.ishaLatest), RuleName.CLOCK_CAPS)
            is HighLatitudeRule.MoonsightingSeasonal -> {
                if (rule.fajrOnly) return Bounded(angleTime, null)
                val base = if (abs(latitudeDeg) >= 55.0) night?.let { sunset + it.seconds / 7 } else angleTime
                val curve = sunset + MoonsightingSeasonal.minutes(MoonsightingSeasonal.Curve.ISHA_GENERAL, latitudeDeg, date) * 60
                moved(angleTime, if (base == null) curve else minOf(base, curve), RuleName.MOONSIGHTING_SEASONAL)
            }
            HighLatitudeRule.DiyanetTakdir -> takdir(Side.ISHA, angleTime, angleDeg)
            HighLatitudeRule.DumRf -> sunrise?.let { rise -> earlier(angleTime, sunset + dumRfShare(date) * (DAY_SECONDS - (sunset - rise)), RuleName.DUM_RF) } ?: Bounded(angleTime, null)
            HighLatitudeRule.DumRt ->
                if (night != null && staysAbove18(night)) moved(angleTime, sunset + 90 * 60.0, RuleName.DUM_RT) else Bounded(angleTime, null)
            // H5: in the summer form's dates Isha moves only by its own interval; D5's bound on the other dates, under its name.
            is HighLatitudeRule.SummerForm -> {
                val after = rule.ishaAfterMaghrib
                when {
                    !rule.covers(MonthDay.from(date)) ->
                        night?.let { earlier(angleTime, sunset + angleDeg / 60 * it.seconds, RuleName.SUMMER_FORM) } ?: Bounded(angleTime, null)
                    after != null && maghrib != null -> earlier(angleTime, maghrib + after * 60.0, RuleName.SUMMER_FORM)
                    else -> Bounded(angleTime, null)
                }
            }
            HighLatitudeRule.Irn -> IrnRule(season, zone, method).isha(date, angleTime, sunset, nightAfter)
        }
    }

    // ---- Every-night bounds ----

    /** Fajr no earlier than [bound]: an absent angle takes the bound. */
    private fun later(angleTime: Double?, bound: Double, rule: RuleName) =
        if (angleTime == null || angleTime < bound) Bounded(bound, rule) else Bounded(angleTime, null)

    /** Isha no later than [bound]. */
    private fun earlier(angleTime: Double?, bound: Double, rule: RuleName) =
        if (angleTime == null || angleTime > bound) Bounded(bound, rule) else Bounded(angleTime, null)

    private fun moved(angleTime: Double?, value: Double, rule: RuleName) =
        if (angleTime != null && abs(angleTime - value) <= MOVED) Bounded(angleTime, null) else Bounded(value, rule)

    /** An evening clock time on the date, or on the next day when it is before noon. */
    private fun eveningClock(time: LocalTime): Double =
        ZonedDateTime.of(if (time < LocalTime.NOON) date.plusDays(1) else date, time, zone).toInstant().epochSecond.toDouble()

    /** A morning clock time on the date, or on the day before when it is after noon. */
    private fun morningClock(time: LocalTime): Double =
        ZonedDateTime.of(if (time > LocalTime.NOON) date.minusDays(1) else date, time, zone).toInstant().epochSecond.toDouble()

    /** True when the sun stays above −18° all night (DUM RT): its lowest altitude is above −18°. */
    private fun staysAbove18(night: Night): Boolean {
        val sky = Sky(season.site, date, zone, method)
        val altitude = Solver.altitude(sky.cycle.hours(night.lowestPoint), season.site.evening.latitude, season.site.evening.longitude, sky.instantSun, sky.cycle)
        return altitude > -18.0
    }

    // ---- Diyanet's takdir ----

    /**
     * H6: the year's table north of 44.375° N; elsewhere the angle time, or D5's α/60 of the night where it is absent.
     * The table is built on both angles, so a method with Fajr or Isha by minutes (a custom method from a Diyanet
     * preset) takes the latter everywhere.
     */
    private fun takdir(side: Side, angleTime: Double?, angleDeg: Double): Bounded {
        if (latitudeDeg >= DiyanetTakdir.START_LATITUDE && method.fajr is FajrRule.Angle && method.isha is IshaRule.Angle) {
            DiyanetTakdir.of(season, date.year).value(date, side == Side.FAJR)?.let { return moved(angleTime, it, RuleName.DIYANET_TAKDIR) }
        }
        if (angleTime != null) return Bounded(angleTime, null)
        val night = (if (side == Side.FAJR) nightBefore else nightAfter) ?: return Bounded(null, null)
        val value = if (side == Side.FAJR) checkNotNull(sunrise) - angleDeg / 60 * night.seconds else checkNotNull(sunset) + angleDeg / 60 * night.seconds
        return Bounded(value, RuleName.DIYANET_TAKDIR)
    }

    // ---- Absent nights, with the ramp ----

    private enum class Side { FAJR, ISHA }

    /** The real angle time of [d] as minutes from its sunset (Isha) or to its sunrise (Fajr); null on a night without it. */
    private fun interval(side: Side, d: LocalDate): Double? {
        val astro = season.day(d)
        val e = season.ends(d)
        return when (side) {
            Side.ISHA -> astro.ishaAngle?.let { i -> e.sunset?.let { i - it } }
            Side.FAJR -> astro.fajrAngle?.let { f -> e.sunrise?.let { it - f } }
        }
    }

    /**
     * An absent-nights rule: the real angle time on a night that has it; otherwise [estimate] (seconds from
     * sunset, or before sunrise), held within 5 minutes a day of the last and the next real times when
     * [ramped] (7.4), so the rule never jumps.
     */
    private fun absent(side: Side, angleTime: Double?, rule: RuleName, ramped: Boolean = true, estimate: (LocalDate) -> Double?): Bounded {
        if (angleTime != null) return Bounded(angleTime, null)
        var value = estimate(date) ?: return Bounded(null, null)
        if (ramped) value = ramp(side, value)
        return Bounded(anchor(side, value), rule)
    }

    private fun anchor(side: Side, interval: Double): Double = when (side) {
        Side.ISHA -> checkNotNull(sunset) + interval
        Side.FAJR -> checkNotNull(sunrise) - interval
    }

    /** Holds [estimate] within 5 min a day of the last and the next nights with a real angle time. */
    private fun ramp(side: Side, estimate: Double): Double {
        var value = estimate
        for (direction in listOf(-1L, 1L)) {
            for (k in 1..MAX_SEARCH_DAYS) {
                val real = interval(side, date.plusDays(direction * k)) ?: continue
                value = value.coerceIn(real - RAMP_SECONDS * k, real + RAMP_SECONDS * k)
                break
            }
        }
        return value
    }

    private fun nearestDay(side: Side, angleTime: Double?, lastClockTime: Boolean): Bounded {
        if (angleTime != null) return Bounded(angleTime, null)
        val night = (if (side == Side.ISHA) season.nightSeconds(date) else season.nightSeconds(date.minusDays(1))) ?: return Bounded(null, null)
        val estimate: Double? = if (lastClockTime) {
            (1..MAX_SEARCH_DAYS).firstNotNullOfOrNull { k ->
                val d = date.minusDays(k.toLong())
                val real = (if (side == Side.ISHA) season.day(d).ishaAngle else season.day(d).fajrAngle) ?: return@firstNotNullOfOrNull null
                val at = java.time.Instant.ofEpochSecond(real.toLong()).atZone(zone)
                val here = ZonedDateTime.of(at.toLocalDate().plusDays(k.toLong()), at.toLocalTime(), zone).toInstant().epochSecond.toDouble()
                if (side == Side.ISHA) here - checkNotNull(sunset) else checkNotNull(sunrise) - here
            }
        } else {
            // The nearest day with the sign, the earlier first, and its share of its own night.
            (1..MAX_SEARCH_DAYS).firstNotNullOfOrNull { k ->
                listOf(date.minusDays(k.toLong()), date.plusDays(k.toLong())).firstNotNullOfOrNull { d ->
                    val real = interval(side, d) ?: return@firstNotNullOfOrNull null
                    val itsNight = (if (side == Side.ISHA) season.nightSeconds(d) else season.nightSeconds(d.minusDays(1))) ?: return@firstNotNullOfOrNull null
                    real / itsNight * night
                }
            }
        }
        val value = ramp(side, estimate ?: return Bounded(null, null))
        return Bounded(anchor(side, value), RuleName.NEAREST_DAY)
    }

    companion object {
        const val RAMP_SECONDS = 5 * 60.0
        const val MAX_SEARCH_DAYS = 200
        private const val MOVED = 1e-3
        private const val DAY_SECONDS = 86_400.0

        /**
         * DUM RF's Isha share of the night in 2026 (7.4): 0.30 from 1 to 30 June, 0.30 − n/240 in the n-th block
         * of 3 days before 1 June or after 30 June, never below 0.25.
         */
        fun dumRfShare(date: LocalDate): Double {
            val june1 = LocalDate.of(date.year, 6, 1)
            val june30 = LocalDate.of(date.year, 6, 30)
            val block = when {
                date < june1 -> (june1.toEpochDay() - date.toEpochDay() - 1) / 3 + 1
                date > june30 -> (date.toEpochDay() - june30.toEpochDay() - 1) / 3 + 1
                else -> 0
            }
            return maxOf(0.25, 0.30 - block / 240.0)
        }
    }
}

/**
 * `irn` (README H7): Islamic Council Norway's southern summer rule, south of 63.56°N; from there H8 ([IrnNorth])
 * sets the times. The switch days follow the rules fitted on IRN's 2026 tables unless the mapped zone stores its
 * own (`switchDays`, 6.5: Fajr's at the entries no rule fits).
 */
internal class IrnRule(private val season: Season, private val zone: ZoneId, private val method: PrayerMethod) {
    private val south = abs(season.site.main.latitude) < IRN_SOUTH_LIMIT

    fun isha(date: LocalDate, angleTime: Double?, sunset: Double, night: Night?): Bounded {
        if (!south) return Bounded(angleTime, null)  // H8 ([IrnNorth]) sets the northern times
        val (first, last) = season.site.zone?.switchDays?.get(TimeEvent.ISHA)?.let { it.first to it.last } ?: ishaSwitch(date.year)
        val day = commonDay(date)
        if (day < first || day > last) return Bounded(angleTime, null)
        val solstice = SOLSTICE_DAY
        val maghribOffset = (method.offsets[TimeEvent.MAGHRIB] ?: 0) * 60.0
        fun utcHours(t: Double) = ((t / 3600.0) % 24 + 24) % 24
        val solsticeSunset = season.ends(onDay(date.year, solstice)).sunset ?: return Bounded(angleTime, null)
        val target = utcHours(solsticeSunset + maghribOffset + 40 * 60)
        val (fromDay, fromHours) = if (day <= solstice) (first - 1) to utcHours(ishaAt15(onDay(date.year, first - 1)) ?: return Bounded(angleTime, null))
        else (last + 1) to utcHours(ishaAt15(onDay(date.year, last + 1)) ?: return Bounded(angleTime, null))
        val hours = fromHours + (target - fromHours) * (day - fromDay).toDouble() / (solstice - fromDay)
        val midnightUtc = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond().toDouble()
        val linear = midnightUtc + hours * 3600
        val floor = sunset + maghribOffset + 40 * 60
        return Bounded(maxOf(linear, floor), RuleName.IRN_SOUTHERN_SUMMER)
    }

    fun fajr(date: LocalDate, angleTime: Double?, sunrise: Double, night: Night?): Bounded {
        if (!south) return Bounded(angleTime, null)  // H8 ([IrnNorth]) sets the northern times
        val (first, last) = season.site.zone?.switchDays?.get(TimeEvent.FAJR)?.let { it.first to it.last } ?: fajrSwitch(date.year)
        val day = commonDay(date)
        if (day < first || day > last) return Bounded(angleTime, null)
        // Held at the clock time of the last 16° Fajr before the season through 21 June, then of the first one after it.
        val heldFrom = if (day <= SOLSTICE_DAY) onDay(date.year, first - 1) else onDay(date.year, last + 1)
        val held = fajrAt16(heldFrom) ?: return Bounded(angleTime, null)
        val clock = java.time.Instant.ofEpochSecond(held.toLong()).atZone(zone).toLocalTime()
        val atClock = ZonedDateTime.of(date, clock, zone).toInstant().epochSecond.toDouble()
        return Bounded(minOf(atClock, sunrise - 3600.0), RuleName.IRN_SOUTHERN_SUMMER)
    }

    private fun ishaAt15(d: LocalDate): Double? = season.day(d).ishaAngle
    private fun fajrAt16(d: LocalDate): Double? = season.day(d).fajrAngle

    /**
     * The day after the first day (of a common year, 32–172) whose angle Isha is absent or at or after 20:00 UTC,
     * and the day before the last such day (172–365), each side on its own (all of IRN's 255 southern tables).
     */
    private fun ishaSwitch(year: Int): Pair<Int, Int> {
        fun late(day: Int) = ishaAt15(onDay(year, day)).let { it == null || ((it / 3600.0) % 24 + 24) % 24 >= 20.0 }
        val first = (32..SOLSTICE_DAY).firstOrNull(::late) ?: return Int.MAX_VALUE to Int.MIN_VALUE
        val last = (365 downTo SOLSTICE_DAY).firstOrNull(::late) ?: return Int.MAX_VALUE to Int.MIN_VALUE
        return (first + 1) to (last - 1)
    }

    /** The first day whose 16° Fajr is 153 min or more before sunrise, and its mirror about 20 June. */
    private fun fajrSwitch(year: Int): Pair<Int, Int> {
        val first = (32..SOLSTICE_DAY).firstOrNull { day ->
            val d = onDay(year, day)
            val fajr = fajrAt16(d)
            val sunrise = season.ends(d).sunrise
            fajr == null || (sunrise != null && sunrise - fajr >= 153 * 60.0)
        } ?: return Int.MAX_VALUE to Int.MIN_VALUE
        return first to (MIRROR - first)
    }

    companion object {
        const val IRN_SOUTH_LIMIT = 63.56
        const val SOLSTICE_DAY = 172
        /** The two seasons are symmetric about 20 June: their day numbers sum to 340–342 (7.4). */
        const val MIRROR = 341

        /** The day number of [date] in a common year: the same calendar date every year (90 = 31 March). */
        fun commonDay(date: LocalDate): Int =
            LocalDate.of(2025, date.monthValue, if (date.monthValue == 2 && date.dayOfMonth == 29) 28 else date.dayOfMonth).dayOfYear

        fun onDay(year: Int, commonDay: Int): LocalDate {
            val d = LocalDate.ofYearDay(2025, commonDay)
            return LocalDate.of(year, d.monthValue, d.dayOfMonth)
        }
    }
}
