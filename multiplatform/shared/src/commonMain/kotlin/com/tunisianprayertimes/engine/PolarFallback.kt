package com.tunisianprayertimes.engine

import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.PrayerMethod
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * `irn`'s northern rules (README H8, plan 7.4), from 63.56°N, as IRN's 2026 tables have them (fits/irn): the real
 * times capped (sunrise and sunset within T ∓ 8 h, Fajr and Isha within T ∓ 9 h), 7-day ramps into and out of
 * Mecca's day in summer and 30-day ones in a Mecca winter, the winter floor (sunset no earlier than T + 2 h, sunrise
 * no later than T − 2 h), and Svalbard on Mecca's day all year. The days are the zone's `switchDays` (MAGHRIB the
 * summer, SUNRISE the Mecca winter, DHUHR the floor); a place without them takes days from its own sun.
 */
internal class IrnNorth(private val season: Season) {
    private val site = season.site
    private val method = season.method

    /** Sunrise, sunset (Maghrib's base), Fajr and Isha of a date (epoch seconds). */
    data class Times(val sunrise: Double, val sunset: Double, val fajr: Double, val isha: Double)

    /** A year's days: Svalbard's Mecca year, the summer (a, b), and each winter half (Mecca with s or e, or the floor from f or to g). */
    data class Days(val svalbard: Boolean, val summer: Pair<Int, Int>?, val autumn: Half?, val spring: Half?)

    data class Half(val mecca: Boolean, val day: Int)

    /** Mecca's sunrise, sunset, Fajr and Isha minus its transit on a date (seconds). */
    class MeccaOffsets(val sunrise: Double, val sunset: Double, val fajr: Double, val isha: Double)

    private class Real(
        val transit: Double,
        val riseUncapped: Double,
        val setUncapped: Double,
        val capped: DoubleArray,
        val mecca: DoubleArray,
        val midnightUtc: Double,
    )

    fun times(date: LocalDate): Times {
        val year = date.year
        val n = IrnRule.commonDay(date)
        val reals = HashMap<Int, Real>()
        // Day n is the date itself: 29 February shares 28 February's number (its rules), never its times.
        val cur = real(date)
        fun r(m: Int) = if (m == n) cur else reals.getOrPut(m) { real(dateOf(year, m)) }
        fun line(m: Int, m0: Int, y0: Double, m1: Int, y1: Double): Double {
            val u0 = y0 - r(m0).midnightUtc
            val u1 = y1 - r(m1).midnightUtc
            return r(m).midnightUtc + u0 + (u1 - u0) * (m - m0).toDouble() / (m1 - m0)
        }
        fun uncapped(x: Real, k: Int) = if (k == RISE) x.riseUncapped else x.setUncapped
        val out = cur.capped.copyOf()
        val days = days(year)
        if (days.svalbard) {
            for (k in 0..3) out[k] = cur.mecca[k]
        } else {
            days.summer?.let { (a, b) ->
                for (k in listOf(RISE, SET)) {
                    when {
                        n > a && n < a + SUMMER_RAMP -> out[k] = line(n, a, uncapped(r(a), k), a + SUMMER_RAMP, r(a + SUMMER_RAMP).mecca[k])
                        n >= a + SUMMER_RAMP && n <= b - SUMMER_RAMP -> out[k] = cur.mecca[k]
                        n > b - SUMMER_RAMP && n < b -> out[k] = line(n, b - SUMMER_RAMP, r(b - SUMMER_RAMP).mecca[k], b, uncapped(r(b), k))
                    }
                }
                // Fajr and Isha a week later (IRN switches Maghrib first).
                val a2 = a + SUMMER_RAMP
                val b2 = b - SUMMER_RAMP - 1
                for (k in listOf(FAJR, ISHA)) {
                    when {
                        n > a2 && n < a2 + SUMMER_RAMP -> out[k] = line(n, a2, r(a2).capped[k], a2 + SUMMER_RAMP, r(a2 + SUMMER_RAMP).mecca[k])
                        n >= a2 + SUMMER_RAMP && n <= b2 - SUMMER_RAMP -> out[k] = cur.mecca[k]
                        n > b2 - SUMMER_RAMP && n < b2 -> out[k] = line(n, b2 - SUMMER_RAMP, r(b2 - SUMMER_RAMP).mecca[k], b2, r(b2).capped[k])
                    }
                }
            }
            val half = if (n <= LAST_SPRING_DAY) days.spring else days.autumn
            if (half != null && half.mecca) {
                val x = half.day
                for (k in 0..3) {
                    if (n > LAST_SPRING_DAY) {
                        if (n > x && n < x + WINTER_RAMP) out[k] = line(n, x, r(x).capped[k], x + WINTER_RAMP, r(x + WINTER_RAMP).mecca[k])
                        else if (n >= x + WINTER_RAMP) out[k] = cur.mecca[k]
                    } else {
                        if (n <= x - WINTER_RAMP) out[k] = cur.mecca[k]
                        else if (n > x - WINTER_RAMP && n < x) out[k] = line(n, x - WINTER_RAMP, r(x - WINTER_RAMP).mecca[k], x, r(x).capped[k])
                    }
                }
            } else if (half != null) {
                val inside = if (n > LAST_SPRING_DAY) n >= half.day else n <= half.day
                if (inside) {
                    out[SET] = maxOf(out[SET], cur.transit + FLOOR)
                    out[RISE] = minOf(out[RISE], cur.transit - FLOOR)
                }
            }
        }
        out[SET] = minOf(out[SET], cur.transit + CAP)
        out[RISE] = maxOf(out[RISE], cur.transit - CAP)
        return Times(out[RISE], out[SET], out[FAJR], out[ISHA])
    }

    /** The real times of a date, capped, and Mecca's day on it. */
    private fun real(d: LocalDate): Real {
        val astro = season.day(d)
        val t = astro.transit
        val polar = astro.sunriseRaw == null && astro.sunsetRaw == null && astro.noonAltitudeDeg < astro.sunriseHorizonDeg
        val riseU = astro.sunriseRaw ?: if (polar) t else t - CAP
        val setU = astro.sunsetRaw ?: if (polar) t else t + CAP
        val fajr = astro.fajrAngle
        val isha = astro.ishaAngle
        val capped = DoubleArray(4)
        capped[RISE] = if (astro.sunriseRaw != null) maxOf(riseU, t - CAP) else riseU
        capped[SET] = if (astro.sunsetRaw != null) minOf(setU, t + CAP) else setU
        capped[FAJR] = if (fajr == null || fajr < t - TWILIGHT_CAP) t - TWILIGHT_CAP else fajr
        capped[ISHA] = if (isha == null || isha > t + TWILIGHT_CAP) t + TWILIGHT_CAP else isha
        val m = mecca(d)
        return Real(
            t, riseU, setU, capped,
            doubleArrayOf(t + m.sunrise, t + m.sunset, t + m.fajr, t + m.isha),
            d.atStartOfDay(ZoneOffset.UTC).toEpochSecond().toDouble(),
        )
    }

    /** Mecca's day with the method's own evaluations, local noon being the place's, 18°/18° and the standard horizon. */
    private fun mecca(d: LocalDate): MeccaOffsets {
        synchronized(season.meccaOffsets) { season.meccaOffsets[d] }?.let { return it }
        val mecca = Site(MECCA, MECCA, MECCA, 0.0, 0.0, Site.STANDARD_PRESSURE_HPA, Site.STANDARD_TEMPERATURE_C, null)
        val at18 = method.copy(
            fajr = FajrRule.Angle(18.0), isha = IshaRule.Angle(18.0), twilightLatitudeScale = 1.0,
            horizon = Horizons(HorizonModel.Standard, HorizonModel.Standard),
        )
        val sky = Sky(mecca, d, season.zone, at18)
        val noon = checkNotNull(sky.transit.seconds)
        fun offset(t: Double?) = checkNotNull(t) { "Mecca has every event" } - noon
        val offsets = MeccaOffsets(
            offset(sky.sunrise.seconds), offset(sky.sunset.seconds),
            offset(sky.twilight(TimeEvent.FAJR, 18.0).seconds), offset(sky.twilight(TimeEvent.ISHA, 18.0).seconds),
        )
        synchronized(season.meccaOffsets) { season.meccaOffsets[d] = offsets }
        return offsets
    }

    /** The year's days: the zone's `switchDays`, or the ones a place without them takes from its own sun. */
    fun days(year: Int): Days {
        synchronized(season.northDays) { season.northDays[year] }?.let { return it }
        val days = computeDays(year)
        synchronized(season.northDays) { season.northDays[year] = days }
        return days
    }

    private fun computeDays(year: Int): Days {
        val given = site.zone?.switchDays.orEmpty()
        val summer = given[TimeEvent.MAGHRIB]
        if (summer != null) {
            if (summer.first <= 1 && summer.last >= 365) return Days(true, null, null, null)
            val mecca = given[TimeEvent.SUNRISE]
            val floor = given[TimeEvent.DHUHR]
            val autumn = when {
                mecca != null && mecca.first != 0 -> Half(true, mecca.first - 1)
                floor != null && floor.first != 0 -> Half(false, floor.first)
                else -> null
            }
            val spring = when {
                mecca != null && mecca.last != 0 -> Half(true, mecca.last + 1)
                floor != null && floor.last != 0 -> Half(false, floor.last)
                else -> null
            }
            return Days(false, (summer.first - 1) to (summer.last + 1), autumn, spring)
        }
        fun afterTransit(n: Int): Double? = season.day(IrnRule.onDay(year, n)).let { d -> d.sunsetRaw?.minus(d.transit) }
        val long = (60..300).filter { n -> afterTransit(n).let { it == null || it > SUMMER_SUNSET } }
        val first = long.firstOrNull { it <= IrnRule.SOLSTICE_DAY }
        val last = long.lastOrNull { it >= IrnRule.SOLSTICE_DAY }
        val summerDays = if (first != null && last != null) (first - 1) to (last + 1) else null
        if (abs(site.main.latitude) < POLAR_NIGHT_LATITUDE) return Days(false, summerDays, Half(false, LAST_SPRING_DAY + 1), Half(false, LAST_SPRING_DAY))
        fun short(n: Int) = afterTransit(n).let { it == null || it < WINTER_SUNSET }
        val s = (260..365).firstOrNull(::short)
        val e = (100 downTo 1).firstOrNull(::short)
        return Days(false, summerDays, s?.let { Half(true, it - 1) }, e?.let { Half(true, it + 1) })
    }

    private fun dateOf(year: Int, m: Int): LocalDate = when {
        m < 1 -> IrnRule.onDay(year - 1, m + 365)
        m > 365 -> IrnRule.onDay(year + 1, m - 365)
        else -> IrnRule.onDay(year, m)
    }

    companion object {
        const val FROM_LATITUDE = 63.56
        const val POLAR_NIGHT_LATITUDE = 66.33
        private const val RISE = 0
        private const val SET = 1
        private const val FAJR = 2
        private const val ISHA = 3
        private const val CAP = 8 * 3600.0
        private const val TWILIGHT_CAP = 9 * 3600.0
        private const val FLOOR = 2 * 3600.0
        private const val SUMMER_RAMP = 7
        private const val WINTER_RAMP = 30
        private const val LAST_SPRING_DAY = 181
        private const val SUMMER_SUNSET = 8 * 3600.0 + 5 * 60.0
        private const val WINTER_SUNSET = 3 * 3600.0 + 20 * 60.0
        private val MECCA = Point(21.4225, 39.8262)

        fun applies(site: Site, method: PrayerMethod): Boolean =
            method.polar == PolarFallback.IRN_MECCA_CYCLE && abs(site.main.latitude) >= FROM_LATITUDE
    }
}
