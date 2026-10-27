package com.tunisianprayertimes.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

/** Astronomical events independent of prayer-method offsets, rounding and user adjustments. */
object SolarEvents {
    /**
     * Apparent solar noon for [date]'s solar cycle at [longitudeDeg], to the nearest second.
     * Latitude does not change transit. This is not the configured Dhuhr prayer time.
     */
    fun transit(date: LocalDate, zone: ZoneId, longitudeDeg: Double): Instant? {
        if (!longitudeDeg.isFinite() || longitudeDeg !in -180.0..180.0) return null
        val cycle = SolarCycle.of(date, zone, longitudeDeg)
        val sun = SunAt { jd -> SolarEngine.sun(SunModel.MEEUS, jd) }
        val seconds = cycle.epochSeconds(Solver.transit(longitudeDeg, sun, cycle))
        return seconds.takeIf { it.isFinite() }?.let { Instant.ofEpochSecond(it.roundToLong()) }
    }
}
