package com.tunisianprayertimes.engine

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * E14's VSOP87 sun, as the Arab prototype computes it (plan 8.6; scripts/prayer_formula/README.md,
 * "Shared engine"): the truncated VSOP87D Earth series ([Vsop87Terms]) at TT = UT + Delta T, the FK5
 * correction, a four-term nutation, aberration, the true obliquity, and Greenwich apparent sidereal
 * time for the equation of time.
 */
internal object Vsop87Sun {
    private const val DEG = PI / 180
    private const val ARC_SECOND = DEG / 3600

    /** Delta T in seconds by the Espenak–Meeus polynomials: 2005–2050, then 2050–2150. */
    fun deltaTSeconds(year: Double): Double = if (year < 2050.0) {
        val t = year - 2000.0
        62.92 + 0.32217 * t + 0.005589 * t * t
    } else {
        val u = (year - 1820.0) / 100.0
        -20.0 + 32.0 * u * u - 0.5628 * (2150.0 - year)
    }

    /** The apparent sun at [jdUt]: right ascension and declination (degrees), distance (AU) and Greenwich apparent sidereal time (degrees). */
    data class Apparent(val rightAscensionDeg: Double, val declinationDeg: Double, val distanceAu: Double, val siderealTimeDeg: Double)

    fun at(jdUt: Double): SunState {
        val sun = apparent(jdUt)
        val utHours = (jdUt - 0.5 - floor(jdUt - 0.5)) * 24.0
        return SunState(sun.declinationDeg, wrapMinutes(4.0 * (sun.siderealTimeDeg - sun.rightAscensionDeg) + 720.0 - 60.0 * utHours), sun.distanceAu)
    }

    fun apparent(jdUt: Double): Apparent {
        val year = 2000.0 + (jdUt - 2451545.0) / 365.25
        val jde = jdUt + deltaTSeconds(year) / 86400.0
        val tau = (jde - 2451545.0) / 365250.0
        val t = tau * 10.0
        val l = series(Vsop87Terms.L, tau)
        val b = series(Vsop87Terms.B, tau)
        val r = series(Vsop87Terms.R, tau)
        // Geocentric, then the FK5 frame.
        var theta = l + PI
        var beta = -b
        val lPrime = theta - (1.397 * t + 0.00031 * t * t) * DEG
        theta += -0.09033 * ARC_SECOND
        beta += 0.03916 * (cos(lPrime) - sin(lPrime)) * ARC_SECOND
        // Nutation (four terms), aberration and the true obliquity.
        val omega = (125.04452 - 1934.136261 * t + 0.0020708 * t * t + t.pow(3) / 450000.0) * DEG
        val sunLong = (280.4665 + 36000.7698 * t) * DEG
        val moonLong = (218.3165 + 481267.8813 * t) * DEG
        val dPsi = (-17.20 * sin(omega) - 1.32 * sin(2 * sunLong) - 0.23 * sin(2 * moonLong) + 0.21 * sin(2 * omega)) * ARC_SECOND
        val dEps = (9.20 * cos(omega) + 0.57 * cos(2 * sunLong) + 0.10 * cos(2 * moonLong) - 0.09 * cos(2 * omega)) * ARC_SECOND
        val lambda = theta + dPsi - 20.4898 * ARC_SECOND / r
        val eps0 = (23.0 + 26.0 / 60.0 + 21.448 / 3600.0 - (46.8150 * t + 0.00059 * t * t - 0.001813 * t.pow(3)) / 3600.0) * DEG
        val eps = eps0 + dEps
        val ra = atan2(sin(lambda) * cos(eps) - tan(beta) * sin(eps), cos(lambda))
        val decl = asin(sin(beta) * cos(eps) + cos(beta) * sin(eps) * sin(lambda))
        // Greenwich apparent sidereal time; at() turns it into E = 4 (GAST − α) + 720 − 60 UT.
        val d = jdUt - 2451545.0
        val tu = d / 36525.0
        val gmst = 280.46061837 + 360.98564736629 * d + 0.000387933 * tu * tu - tu.pow(3) / 38710000.0
        val gast = gmst + dPsi * cos(eps) / DEG
        return Apparent(ra / DEG, decl / DEG, r, gast)
    }

    private fun series(powers: List<DoubleArray>, tau: Double): Double {
        var total = 0.0
        var tauPower = 1.0
        for (terms in powers) {
            var sum = 0.0
            var i = 0
            while (i < terms.size) {
                sum += terms[i] * cos(terms[i + 1] + terms[i + 2] * tau)
                i += 3
            }
            total += sum * tauPower
            tauPower *= tau
        }
        return total
    }

    /** Minutes into [−720, 720). */
    private fun wrapMinutes(minutes: Double): Double = minutes - 1440.0 * floor((minutes + 720.0) / 1440.0)
}
