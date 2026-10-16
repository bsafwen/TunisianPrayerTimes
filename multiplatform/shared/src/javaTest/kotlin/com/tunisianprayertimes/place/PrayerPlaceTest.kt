package com.tunisianprayertimes.place

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The place types and their cache keys (plan 6.8). */
class PrayerPlaceTest {
    @Test
    fun cacheKeys() {
        assertEquals("inm:615", PrayerPlace.Inm(615).cacheKey)
        assertEquals(ZoneId.of("Africa/Tunis"), PrayerPlace.Inm(615).zone)
        val paris = PrayerPlace.Coordinates(4886000, 235000, null, "Europe/Paris", ZoneSource.CATALOGUE, "FR")
        assertEquals("geo:4886000,235000,Europe/Paris", paris.cacheKey)
        assertTrue(paris.isMainlandFrance)
        assertFalse(paris.copy(countryCode = "RE").isMainlandFrance)
    }

    @Test
    fun pointsInWholeE5() {
        assertEquals(LatLngE5(-1328000, -17617000), LatLngE5.of(-13.28, -176.17))
        assertEquals(36.8065, LatLngE5.of(36.8065, 10.1815).latitude, 1e-12)
    }
}
