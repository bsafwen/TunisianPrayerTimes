package com.tunisianprayertimes

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class WorldMagneticModelTest {

    private data class FieldCase(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val declination: Double,
        val inclination: Double,
        val horizontal: Double,
        val total: Double,
    )

    // NOAA's WMM2025 coefficients evaluated by pygeomag (a port of NOAA's reference code) at 2026.5, sea level.
    private val cases = listOf(
        FieldCase("Tunis", 36.8, 10.18, declination = 3.3173, inclination = 51.8719, horizontal = 27659.9, total = 44799.0),
        FieldCase("Paris", 48.86, 2.35, declination = 2.0431, inclination = 64.4214, horizontal = 20923.4, total = 48461.9),
        FieldCase("New York", 40.71, -74.01, declination = -12.4721, inclination = 65.5496, horizontal = 21019.7, total = 50783.7),
        FieldCase("Sao Paulo", -23.55, -46.63, declination = -21.8807, inclination = -40.8243, horizontal = 17247.3, total = 22792.3),
        FieldCase("Cape Town", -33.92, 18.42, declination = -26.7342, inclination = -64.6391, horizontal = 10701.9, total = 24985.8),
        FieldCase("Jakarta", -6.21, 106.85, declination = 0.6434, inclination = -28.8811, horizontal = 38890.8, total = 44415.0),
        FieldCase("Resolute", 74.7, -94.83, declination = -15.0320, inclination = 86.6512, horizontal = 3345.4, total = 57268.7),
        FieldCase("McMurdo", -77.85, 166.67, declination = 140.1664, inclination = -80.1072, horizontal = 10639.4, total = 61927.6),
    )

    @Test
    fun fieldAt_matchesTheReferenceImplementation() {
        for (case in cases) {
            val field = WorldMagneticModel.fieldAt(case.latitude, case.longitude, 0.0, 2026.5)
            assertEquals("${case.name} declination", case.declination, field.declinationDegrees, 0.01)
            assertEquals("${case.name} inclination", case.inclination, field.inclinationDegrees, 0.01)
            assertEquals("${case.name} horizontal", case.horizontal, field.horizontalIntensityNanoTesla, 1.0)
            assertEquals("${case.name} total", case.total, field.totalIntensityNanoTesla, 1.0)
        }
    }

    @Test
    fun decimalYear_countsFromTheStartOfTheUtcYear() {
        val midYear = LocalDateTime.of(2026, 7, 2, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(2026.5, WorldMagneticModel.decimalYear(midYear), 1e-3)
        val newYear = LocalDateTime.of(2027, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(2027.0, WorldMagneticModel.decimalYear(newYear), 1e-9)
    }

    @Test
    fun magneticFieldZone_flagsWeakHorizontalField() {
        assertEquals(MagneticFieldZone.Normal, zoneAt(36.8, 10.18)) // Tunis, about 27,700 nT
        assertEquals(MagneticFieldZone.Caution, zoneAt(74.7, -94.83)) // Resolute, about 3,300 nT
        assertEquals(MagneticFieldZone.Blackout, zoneAt(86.0, 150.0)) // Arctic Ocean near the dip pole, about 300 nT
    }

    private fun zoneAt(latitude: Double, longitude: Double): MagneticFieldZone {
        val field = WorldMagneticModel.fieldAt(latitude, longitude, 0.0, 2026.5)
        return magneticFieldZone(field.horizontalIntensityNanoTesla)
    }
}
