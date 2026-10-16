package com.tunisianprayertimes.engine

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * E14's TEXTBOOK sun, the low-precision sun Libya's perpetual table was computed with (plan 8.2):
 * Meeus's mean longitude L, a mean anomaly of L − 282°, a two-term equation of centre, the declination
 * with ε = 23.44°, and an equation of time reduced to the equator (−C + y sin 2λ, y = tan²(23.5°/2)).
 * An accurate sun cannot reproduce even Tripoli's Dhuhr column, so `ly_awqaf` takes this one (D17).
 */
internal object TextbookSun {
    private const val DEG = PI / 180

    fun at(jdUt: Double): SunState {
        val t = (jdUt - 2451545.0) / 36525.0
        val meanLong = (280.46646 + 36000.76983 * t + 0.0003032 * t * t).mod(360.0)
        val anomaly = (meanLong - 282.0) * DEG
        val centre = 1.9146 * sin(anomaly) + 0.019993 * sin(2 * anomaly)
        val lambda = (meanLong + centre) * DEG
        val decl = asin(sin(23.44 * DEG) * sin(lambda)) / DEG
        val y = tan(23.5 * DEG / 2).pow(2)
        val eot = 4.0 * (-centre + y * sin(2 * lambda) / DEG)
        return SunState(decl, eot, SolarEngine.almanacDistanceAu(jdUt))
    }
}
