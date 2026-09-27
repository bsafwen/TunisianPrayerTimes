package com.tunisianprayertimes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QiblaDirectionTest {

    // Reference azimuths and distances come from GeographicLib's Python release (Karney, WGS84).
    @Test
    fun qiblaBearing_acrossTunisia() {
        assertBearing(112.5173, calculateQiblaBearing(36.797, 10.177)) // Bab Bhar, Tunis
        assertBearing(105.7507, calculateQiblaBearing(33.924, 8.131)) // Tozeur
        assertBearing(111.1907, calculateQiblaBearing(36.952, 8.758)) // Tabarka
        assertBearing(105.1524, calculateQiblaBearing(32.316, 10.400)) // Remada
        assertBearing(113.8975, calculateQiblaBearing(37.055, 11.013)) // El Haouaria
        assertBearing(100.7083, calculateQiblaBearing(30.250, 9.555)) // Borj El Khadra
    }

    @Test
    fun qiblaBearing_elsewhere() {
        assertBearing(119.0395, calculateQiblaBearing(48.8566, 2.3522)) // Paris
        assertBearing(58.3960, calculateQiblaBearing(40.7128, -74.0060)) // New York
        assertBearing(295.0247, calculateQiblaBearing(-6.2088, 106.8456)) // Jakarta
        assertBearing(23.4673, calculateQiblaBearing(-33.9249, 18.4241)) // Cape Town
    }

    @Test
    fun qiblaBearing_nearKaabaAntipode_staysOnTheEllipsoid() {
        // Vincenty's method does not converge here; the sphere would be 28° off at Tematangi.
        assertBearing(210.1522, calculateQiblaBearing(-21.683, -140.617)) // Tematangi atoll
        assertBearing(119.5669, calculateQiblaBearing(-21.833, -138.917)) // Mururoa atoll
        assertBearing(58.5968, calculateQiblaBearing(-20.783, -138.567)) // Tureia atoll
    }

    @Test
    fun qiblaBearing_isFiniteAtTheKaabaAndAtItsAntipode() {
        val bearings = listOf(
            calculateQiblaBearing(21.422487, 39.826206),
            calculateQiblaBearing(-21.422487, -140.173794),
        )
        for (bearing in bearings) {
            assertTrue("bearing was $bearing", bearing.isFinite() && bearing >= 0.0 && bearing < 360.0)
        }
    }

    @Test
    fun qiblaDistance_followsTheGeodesic() {
        assertEquals(3_330_255.6, calculateQibla(36.797, 10.177).distanceMeters, 1.0)
        assertEquals(223.8, calculateQibla(21.4205, 39.8266).distanceMeters, 0.5)
        assertEquals(19_963_262.0, calculateQibla(-21.683, -140.617).distanceMeters, 1.0)
    }

    @Test
    fun locationUncertainty_growsAsTheKaabaGetsCloser() {
        assertEquals(0.0573, locationBearingUncertaintyDegrees(3_000_000.0, 3_000f)!!, 1e-3)
        assertEquals(1.7191, locationBearingUncertaintyDegrees(100_000.0, 3_000f)!!, 1e-3)
        assertEquals(180.0, locationBearingUncertaintyDegrees(1_000.0, 3_000f)!!, 0.0)
        assertNull(locationBearingUncertaintyDegrees(100_000.0, null))
        assertNull(locationBearingUncertaintyDegrees(100_000.0, 0f))
    }

    @Test
    fun normalizeDegrees_wrapsIntoZeroTo360() {
        assertEquals(330.0, normalizeDegrees(-30.0), 1e-9)
        assertEquals(0.0, normalizeDegrees(720.0), 1e-9)
        assertEquals(359.5, normalizeDegrees(-0.5), 1e-9)
        assertEquals(45.0, normalizeDegrees(405.0), 1e-9)
    }

    @Test
    fun shortestSignedAngle_takesTheShortWayAround() {
        assertEquals(20.0, shortestSignedAngleDegrees(350.0, 10.0), 1e-9)
        assertEquals(-20.0, shortestSignedAngleDegrees(10.0, 350.0), 1e-9)
        assertEquals(180.0, shortestSignedAngleDegrees(0.0, 180.0), 1e-9)
        assertEquals(180.0, shortestSignedAngleDegrees(180.0, 0.0), 1e-9)
        assertEquals(-90.0, shortestSignedAngleDegrees(0.0, 270.0), 1e-9)
        assertEquals(50.0, shortestSignedAngleDegrees(700.0, 30.0), 1e-9)
    }

    private fun assertBearing(expected: Double, actual: Double) {
        assertEquals(expected, actual, 1e-3)
    }
}
