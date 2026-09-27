package com.tunisianprayertimes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QiblaDirectionTest {

    // Reference azimuths come from GeographicLib (Karney's geodesic solver) on WGS84.
    @Test
    fun qiblaBearing_matchesWgs84GeodesicAcrossTunisia() {
        assertBearing(112.5173, calculateQiblaBearing(36.797, 10.177)) // Bab Bhar, Tunis
        assertBearing(105.7507, calculateQiblaBearing(33.924, 8.131)) // Tozeur
        assertBearing(111.1907, calculateQiblaBearing(36.952, 8.758)) // Tabarka
        assertBearing(105.1524, calculateQiblaBearing(32.316, 10.400)) // Remada
        assertBearing(113.8975, calculateQiblaBearing(37.055, 11.013)) // El Haouaria
        assertBearing(100.7083, calculateQiblaBearing(30.250, 9.555)) // Borj El Khadra
    }

    @Test
    fun qiblaBearing_matchesWgs84GeodesicElsewhere() {
        assertBearing(119.0395, calculateQiblaBearing(48.8566, 2.3522)) // Paris
        assertBearing(58.3960, calculateQiblaBearing(40.7128, -74.0060)) // New York
        assertBearing(295.0247, calculateQiblaBearing(-6.2088, 106.8456)) // Jakarta
        assertBearing(23.4673, calculateQiblaBearing(-33.9249, 18.4241)) // Cape Town
    }

    @Test
    fun sphericalBearing_readsAboutATenthOfADegreeHigherInTunis() {
        val spherical = sphericalQiblaBearing(36.797, 10.177)
        assertBearing(112.6454, spherical)
        val difference = spherical - calculateQiblaBearing(36.797, 10.177)
        assertTrue("difference was $difference", difference in 0.09..0.14)
    }

    @Test
    fun qiblaBearing_atKaabaAntipode_fallsBackToSphere() {
        val bearing = calculateQiblaBearing(-21.422487, -140.173794)
        assertTrue(bearing.isFinite())
        assertTrue(bearing >= 0.0 && bearing < 360.0)
        assertEquals(sphericalQiblaBearing(-21.422487, -140.173794), bearing, 1e-9)
    }

    @Test
    fun qiblaBearing_atKaaba_isFinite() {
        assertEquals(0.0, calculateQiblaBearing(21.422487, 39.826206), 0.0)
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
