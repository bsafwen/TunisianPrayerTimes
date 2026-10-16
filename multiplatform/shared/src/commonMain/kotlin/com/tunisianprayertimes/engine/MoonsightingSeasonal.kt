package com.tunisianprayertimes.engine

import java.time.LocalDate
import kotlin.math.abs

/**
 * The Moonsighting Committee's seasonal twilight curves (plan 7.4; adhan-js 4.4.6 `Astronomical.ts`,
 * MIT): minutes after sunset, or before sunrise for Fajr, piecewise linear over the days since the
 * winter solstice. E19 bounds an estimated Isha by the «Shafaq Ahmer» curve (7.7); the seasonal rule of
 * `mcw`, `london_unified` and `gmp` (M2) reads the others.
 */
object MoonsightingSeasonal {
    enum class Curve(val base: Double, val k: List<Double>) {
        FAJR(75.0, listOf(28.65, 19.44, 32.74, 48.10)),
        ISHA_GENERAL(75.0, listOf(25.60, 2.05, -9.21, 6.14)),
        ISHA_AHMER(62.0, listOf(17.40, -7.16, 5.12, 19.44)),
        ISHA_ABYAD(75.0, listOf(25.60, 7.16, 36.84, 81.84)),
    }

    /** The curve's minutes at [latitudeDeg] on [date]. */
    fun minutes(curve: Curve, latitudeDeg: Double, date: LocalDate): Double {
        val (a, b, c, d) = curve.k.map { curve.base + it * abs(latitudeDeg) / 55.0 }
        val dyy = daysSinceSolstice(date, latitudeDeg).toDouble()
        return when {
            dyy < 91 -> a + (b - a) / 91.0 * dyy
            dyy < 137 -> b + (c - b) / 46.0 * (dyy - 91)
            dyy < 183 -> c + (d - c) / 46.0 * (dyy - 137)
            dyy < 229 -> d + (c - d) / 46.0 * (dyy - 183)
            dyy < 275 -> c + (b - c) / 46.0 * (dyy - 229)
            else -> b + (a - b) / 91.0 * (dyy - 275)
        }
    }

    /** dyy: day of the year + 10 in the north, − 172 (− 173 in a leap year) in the south, wrapped into the year. */
    fun daysSinceSolstice(date: LocalDate, latitudeDeg: Double): Int {
        val daysInYear = date.lengthOfYear()
        return if (latitudeDeg >= 0) {
            (date.dayOfYear + 10).let { if (it >= daysInYear) it - daysInYear else it }
        } else {
            (date.dayOfYear - if (date.isLeapYear) 173 else 172).let { if (it < 0) it + daysInYear else it }
        }
    }
}
