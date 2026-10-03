package com.tunisianprayertimes.ui

import com.tunisianprayertimes.KAABA_LATITUDE
import com.tunisianprayertimes.KAABA_LONGITUDE
import com.tunisianprayertimes.QiblaMethod
import com.tunisianprayertimes.calculateQiblaBearing
import com.tunisianprayertimes.normalizeDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class QiblaRoutesIllustrationTest {

    private val tunis = qiblaRoutes(36.797, 10.177)
    private val newYork = qiblaRoutes(40.7128, -74.0060)

    @Test
    fun bothRoutes_runFromThePlaceToTheKaaba() {
        for (route in listOf(tunis.greatCircle, tunis.rhumbLine)) {
            assertEquals(36.797, route.first().latitude, 1e-9)
            assertEquals(10.177, route.first().longitude, 1e-9)
            assertEquals(KAABA_LATITUDE, route.last().latitude, 1e-6)
            assertEquals(KAABA_LONGITUDE, route.last().longitude, 1e-6)
        }
    }

    @Test
    fun rhumbLine_isStraightOnTheMap() {
        for (routes in listOf(tunis, newYork)) {
            assertEquals(0.0, largestBendPerLength(routes.rhumbLine.map(::mercatorProjection)), 1e-9)
        }
    }

    @Test
    fun rhumbLine_leavesAtTheBearingTheAppShows() {
        // The sketch uses a spherical Mercator map; the app's bearing is on the ellipsoid.
        for ((latitude, longitude) in listOf(36.797 to 10.177, 40.7128 to -74.0060, 48.8566 to 2.3522)) {
            val route = qiblaRoutes(latitude, longitude).rhumbLine.map(::mercatorProjection)
            val sketchBearing = normalizeDegrees(
                Math.toDegrees(atan2(route[1].x - route[0].x, route[1].y - route[0].y)),
            )
            val appBearing = calculateQiblaBearing(latitude, longitude, QiblaMethod.RhumbLine)
            assertEquals(appBearing, sketchBearing, 0.5)
        }
    }

    @Test
    fun greatCircle_isStraightOnTheGlobeAndBendsOnTheMap() {
        for (routes in listOf(tunis, newYork)) {
            val onGlobe = routes.greatCircle.map { point -> orthographicProjection(point, routes.midpoint)!! }
            assertTrue(largestBendPerLength(onGlobe) < 0.01)

            val onMap = routes.greatCircle.map(::mercatorProjection)
            assertTrue(largestBendPerLength(onMap) > 0.01)
        }
    }

    @Test
    fun greatCircle_bowsTowardThePoleOnTheMap() {
        val greatCircleMiddle = mercatorProjection(newYork.greatCircle[newYork.greatCircle.size / 2])
        val rhumbLineMiddle = mercatorProjection(newYork.rhumbLine[newYork.rhumbLine.size / 2])
        assertTrue(greatCircleMiddle.y > rhumbLineMiddle.y)
    }

    @Test
    fun orthographicProjection_hidesTheFarSideOfTheGlobe() {
        val center = RoutePoint(0.0, 0.0)
        assertNull(orthographicProjection(RoutePoint(0.0, 180.0), center))
        val facing = orthographicProjection(RoutePoint(0.0, 0.0), center)!!
        assertEquals(0.0, facing.x, 1e-12)
        assertEquals(0.0, facing.y, 1e-12)
    }

    @Test
    fun routes_nextToTheKaabaAndAcrossItsOppositeMeridian_areDrawable() {
        for ((latitude, longitude) in listOf(21.4205 to 39.8266, -21.683 to -140.617, -21.833 to -138.917)) {
            val routes = qiblaRoutes(latitude, longitude)
            val coordinates = (routes.greatCircle + routes.rhumbLine + routes.midpoint)
                .flatMap { point -> listOf(point.latitude, point.longitude) }
            assertTrue(coordinates.all { coordinate -> coordinate.isFinite() })
        }
    }

    /** Farthest any point strays from the chord between the ends, as a fraction of the chord. */
    private fun largestBendPerLength(points: List<PlanePoint>): Double {
        val start = points.first()
        val end = points.last()
        val length = hypot(end.x - start.x, end.y - start.y)
        return points.maxOf { point ->
            abs((end.x - start.x) * (point.y - start.y) - (end.y - start.y) * (point.x - start.x)) / length / length
        }
    }
}
