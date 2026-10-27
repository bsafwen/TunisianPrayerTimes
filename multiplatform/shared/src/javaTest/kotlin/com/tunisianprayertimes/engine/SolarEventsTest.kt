package com.tunisianprayertimes.engine

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SolarEventsTest {
    @Test
    fun transitSolvesApparentSolarNoonInsteadOfCivilNoon() {
        val date = LocalDate.of(2026, 10, 26)
        val longitude = 10.18
        val transit = assertNotNull(SolarEvents.transit(date, ZoneId.of("Africa/Tunis"), longitude))
        val utcHours = Duration.between(date.atStartOfDay().toInstant(ZoneOffset.UTC), transit).seconds / 3600.0
        val equationMinutes = SolarEngine.sun(SunModel.MEEUS, SolarEngine.julianDay(transit.epochSecond.toDouble())).equationOfTimeMin
        val apparentHours = utcHours + longitude / 15.0 + equationMinutes / 60.0
        assertTrue(abs(apparentHours - 12.0) * 3600.0 <= 0.51)
        assertTrue(transit > Instant.parse("2026-10-26T11:00:00Z"))
        assertTrue(transit < Instant.parse("2026-10-26T11:10:00Z"))
    }

    @Test
    fun daylightSavingDoesNotMoveSolarTransit() {
        val paris = ZoneId.of("Europe/Paris")
        for (date in listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25))) {
            assertEquals(
                SolarEvents.transit(date, ZoneOffset.ofHours(1), 2.3522),
                SolarEvents.transit(date, paris, 2.3522),
            )
        }
    }

    @Test
    fun dateLinePlacesKeepTheirRequestedCivilDate() {
        val date = LocalDate.of(2026, 10, 26)
        for ((zone, longitude) in listOf("Pacific/Apia" to -171.75, "Pacific/Honolulu" to -157.8583, "Pacific/Kiritimati" to -157.4278)) {
            val placeZone = ZoneId.of(zone)
            val transit = assertNotNull(SolarEvents.transit(date, placeZone, longitude))
            assertEquals(date, transit.atZone(placeZone).toLocalDate())
        }
    }

    @Test
    fun invalidLongitudesAreUnavailable() {
        for (longitude in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -180.01, 180.01)) {
            assertNull(SolarEvents.transit(LocalDate.of(2026, 10, 26), ZoneOffset.UTC, longitude))
        }
    }
}
