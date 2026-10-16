package com.tunisianprayertimes.engine

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** The sun an event is solved with: the sun at each instant, or a fixed sun for E4's daily modes. */
internal fun interface SunAt {
    fun at(jdUt: Double): SunState
}

/**
 * The solar cycle of a civil date (plan 6.2, solver step 1): [cycleDate] is U, the UT date of the
 * cycle, and times are hours after [jd0], 0h UT of U.
 */
internal data class SolarCycle(val date: LocalDate, val cycleDate: LocalDate, val civilNoon: Instant) {
    val jd0: Double = SolarEngine.julianDay(cycleDate)

    /** The neighbouring cycle, U − 1 or U + 1, for date − 1 or date + 1 (step 5). */
    fun shifted(days: Long) = SolarCycle(date.plusDays(days), cycleDate.plusDays(days), civilNoon.plusSeconds(days * 86400))

    fun epochSeconds(hours: Double): Double = SolarEngine.epochSeconds(jd0) + hours * 3600.0

    fun hours(epochSeconds: Double): Double = (epochSeconds - SolarEngine.epochSeconds(jd0)) / 3600.0

    companion object {
        /**
         * N is the civil noon of [date] in [zone]'s standard time: 12:00 in the zone, moved later by the
         * summer-time saving in force then, so a summer-time change never moves U. U is the date N has in
         * local mean time, UT + λ/15 h.
         */
        fun of(date: LocalDate, zone: ZoneId, longitudeDeg: Double): SolarCycle {
            val noon = ZonedDateTime.of(date, LocalTime.NOON, zone).toInstant()
            val civilNoon = noon.plus(zone.rules.getDaylightSavings(noon))
            val meanTime = civilNoon.epochSecond + longitudeDeg / 15.0 * 3600.0
            val cycleDate = LocalDate.ofEpochDay(floor(meanTime / 86400.0).toLong())
            return SolarCycle(date, cycleDate, civilNoon)
        }
    }
}

internal object Solver {
    private const val DEG = PI / 180
    const val MAX_PASSES = 10
    private const val TOLERANCE_HOURS = 0.1 / 3600.0

    /** The transit, in hours after 0h UT of U: t = M − E(t)/60, M = 12 − λ/15. */
    fun transit(longitudeDeg: Double, sun: SunAt, cycle: SolarCycle): Double {
        val mean = 12.0 - longitudeDeg / 15.0
        var t = mean
        repeat(MAX_PASSES) {
            val next = mean - sun.at(cycle.jd0 + t / 24.0).equationOfTimeMin / 60.0
            if (abs(next - t) < TOLERANCE_HOURS) return next
            t = next
        }
        return t
    }

    /**
     * The instant, in hours after 0h UT of U, when the sun's centre reaches [altitudeDeg] on [side]
     * (−1 morning, +1 evening) of the transit [transitHours]; null when it never does (absent, never
     * clamped). The plain iteration t = M − E(t)/60 + s·H(a, φ, δ(t)) runs from the transit until t moves
     * by less than 0.1 s, for at most 10 passes; if it has not converged, or an iterate has |cos H| > 1, a
     * bisection on the altitude between the transit and 12 h from it decides.
     */
    fun event(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeDeg: Double,
        side: Int,
        transitHours: Double,
        sun: SunAt,
        cycle: SolarCycle,
    ): Double? {
        val mean = 12.0 - longitudeDeg / 15.0
        var t = transitHours
        for (pass in 1..MAX_PASSES) {
            val s = sun.at(cycle.jd0 + t / 24.0)
            val cosH = cosHourAngle(altitudeDeg, latitudeDeg, s.declinationDeg)
            if (abs(cosH) > 1) break
            val next = mean - s.equationOfTimeMin / 60.0 + side * acos(cosH) / DEG / 15.0
            if (abs(next - t) < TOLERANCE_HOURS) return next
            t = next
        }
        return bracket(latitudeDeg, longitudeDeg, altitudeDeg, side, transitHours, sun, cycle)
    }

    private fun bracket(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeDeg: Double,
        side: Int,
        transitHours: Double,
        sun: SunAt,
        cycle: SolarCycle,
    ): Double? {
        // Morning: the altitude rises from the lowest point (about 12 h before the transit) to the transit.
        var below = transitHours + side * 12.0
        var above = transitHours
        fun f(t: Double) = altitude(t, latitudeDeg, longitudeDeg, sun, cycle) - altitudeDeg
        if (f(below) >= 0 || f(above) <= 0) return null
        while (abs(above - below) >= TOLERANCE_HOURS) {
            val mid = (below + above) / 2
            if (f(mid) < 0) below = mid else above = mid
        }
        return (below + above) / 2
    }

    /** The sun's altitude in degrees at [hours] after 0h UT of U, from its hour angle 15(t − M) + E/4. */
    fun altitude(hours: Double, latitudeDeg: Double, longitudeDeg: Double, sun: SunAt, cycle: SolarCycle): Double {
        val s = sun.at(cycle.jd0 + hours / 24.0)
        val hourAngle = 15.0 * (hours - (12.0 - longitudeDeg / 15.0)) + s.equationOfTimeMin / 4.0
        val sinAltitude = sin(latitudeDeg * DEG) * sin(s.declinationDeg * DEG) +
            cos(latitudeDeg * DEG) * cos(s.declinationDeg * DEG) * cos(hourAngle * DEG)
        return asin(sinAltitude.coerceIn(-1.0, 1.0)) / DEG
    }

    fun cosHourAngle(altitudeDeg: Double, latitudeDeg: Double, declinationDeg: Double): Double =
        (sin(altitudeDeg * DEG) - sin(latitudeDeg * DEG) * sin(declinationDeg * DEG)) /
            (cos(latitudeDeg * DEG) * cos(declinationDeg * DEG))

    /**
     * The night's lowest point, as epoch seconds: where the sun's altitude stops falling between
     * [fromSeconds] (a sunset) and [toSeconds] (the next sunrise), by bisection on the sign of the
     * altitude's change over one second, to 0.1 s.
     */
    fun lowestPoint(
        latitudeDeg: Double,
        longitudeDeg: Double,
        fromSeconds: Double,
        toSeconds: Double,
        sun: SunAt,
        cycle: SolarCycle,
    ): Double {
        fun rising(seconds: Double): Boolean {
            val t = cycle.hours(seconds)
            return altitude(t + 0.5 / 3600.0, latitudeDeg, longitudeDeg, sun, cycle) >
                altitude(t - 0.5 / 3600.0, latitudeDeg, longitudeDeg, sun, cycle)
        }
        var falling = fromSeconds
        var risen = toSeconds
        if (rising(falling) || !rising(risen)) return (fromSeconds + toSeconds) / 2
        while (risen - falling >= 0.1) {
            val mid = (falling + risen) / 2
            if (rising(mid)) risen = mid else falling = mid
        }
        return (falling + risen) / 2
    }
}
