package com.tunisianprayertimes.engine

import com.tunisianprayertimes.InmPrayerFormula
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos

/** E14 (D17): the sun a method is computed with (plan 6.2). */
enum class SunModel {
    /** INM's own sun: Meeus's 1900-epoch formulae, with no Delta T (under 3″). The default. */
    MEEUS,

    /** The VSOP87D Earth series with FK5, nutation, aberration and Delta T, as Accurate Times (8.6). */
    VSOP87,

    /** The low-precision sun Libya's perpetual table was computed with (8.2). */
    TEXTBOOK,

    /** The Astronomical Almanac's low-precision sun with PrayTimes.js's constants, which Oman's tables follow. */
    ALMANAC,
}

/**
 * The sun at an instant, reduced to what every event needs: the apparent declination, the equation of
 * time E (apparent minus mean solar time, so that local apparent solar time = UT + λ/15 + E/60 hours),
 * and the distance, which only the semi-diameter of E2's refraction model reads.
 */
data class SunState(val declinationDeg: Double, val equationOfTimeMin: Double, val distanceAu: Double)

object SolarEngine {
    private const val DEG = PI / 180

    /** The sun of [model] at Julian day [jdUt] (UT). */
    fun sun(model: SunModel, jdUt: Double): SunState = when (model) {
        SunModel.MEEUS -> InmPrayerFormula.sun(jdUt).let { (decl, eot) -> SunState(decl, eot, almanacDistanceAu(jdUt)) }
        SunModel.VSOP87 -> Vsop87Sun.at(jdUt)
        SunModel.TEXTBOOK -> TextbookSun.at(jdUt)
        SunModel.ALMANAC -> AlmanacSun.at(jdUt)
    }

    /** Julian day at 0h UT of a civil date, INM's own function. */
    fun julianDay(date: LocalDate): Double = InmPrayerFormula.julianDay(date.year, date.monthValue, date.dayOfMonth)

    /** Julian day of an instant given as seconds since 1970-01-01T00:00Z. */
    fun julianDay(epochSeconds: Double): Double = UNIX_EPOCH_JD + epochSeconds / 86400.0

    /** Seconds since 1970-01-01T00:00Z of a Julian day. */
    fun epochSeconds(jdUt: Double): Double = (jdUt - UNIX_EPOCH_JD) * 86400.0

    /** The Earth–sun distance of the Astronomical Almanac's low-precision formula, for the MEEUS and TEXTBOOK suns. */
    fun almanacDistanceAu(jdUt: Double): Double {
        val g = (357.528 + 0.9856003 * (jdUt - 2451545.0)) * DEG
        return 1.00014 - 0.01671 * cos(g) - 0.00014 * cos(2 * g)
    }

    const val UNIX_EPOCH_JD = 2440587.5
}
