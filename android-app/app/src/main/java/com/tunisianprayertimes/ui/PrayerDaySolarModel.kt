package com.tunisianprayertimes.ui

import com.tunisianprayertimes.InmLocation
import com.tunisianprayertimes.InmPrayerFormula
import java.time.Instant
import java.time.ZoneOffset

private val SolarFormulaOffset = ZoneOffset.ofHours(1)

/**
 * Geometric altitude of the Sun's centre above the astronomical horizon. No atmospheric
 * refraction, observer-elevation dip, or prayer-time adjustments are applied. This is a
 * visual explanation of the solar position, not a replacement for prayer calculations.
 */
internal fun solarAltitudeDegAt(location: InmLocation, epochMillis: Long): Double? {
    if (!location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
        !location.longitude.isFinite() || location.longitude !in -180.0..180.0
    ) return null

    // The shared formula expects UTC+1 minutes, regardless of the phone's time zone.
    // Convert the complete instant so the date is also correct across local midnight.
    val formulaTime = Instant.ofEpochMilli(epochMillis).atOffset(SolarFormulaOffset)
    val formulaMinutes = formulaTime.toLocalTime().toNanoOfDay() / 60_000_000_000.0
    return InmPrayerFormula.sunAltitudeDeg(
        location = location,
        year = formulaTime.year,
        month = formulaTime.monthValue,
        day = formulaTime.dayOfMonth,
        localMinutes = formulaMinutes,
    ).takeIf { it.isFinite() }
}
