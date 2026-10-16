package com.tunisianprayertimes.engine

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * E14's ALMANAC sun: the Astronomical Almanac's low-precision formulae with the constants PrayTimes.js uses
 * (mean anomaly g, mean longitude q, a two-term equation of centre, a slowly falling obliquity), which
 * Oman's zone tables follow (AM2 fit, scripts/prayer_formula/fits/om_mara). README S1 gives the formulae.
 */
internal object AlmanacSun {
    private const val DEG = PI / 180

    fun at(jdUt: Double): SunState {
        val d = jdUt - 2451545.0
        val g = (357.529 + 0.98560028 * d).mod(360.0)
        val q = (280.459 + 0.98564736 * d).mod(360.0)
        val lambda = (q + 1.915 * sin(g * DEG) + 0.020 * sin(2 * g * DEG)) * DEG
        val obliquity = (23.439 - 0.00000036 * d) * DEG
        val rightAscension = atan2(cos(obliquity) * sin(lambda), cos(lambda)) / DEG
        val declination = asin(sin(obliquity) * sin(lambda)) / DEG
        val equation = 4.0 * ((q - rightAscension + 180.0).mod(360.0) - 180.0)
        val distance = 1.00014 - 0.01671 * cos(g * DEG) - 0.00014 * cos(2 * g * DEG)
        return SunState(declination, equation, distance)
    }
}
