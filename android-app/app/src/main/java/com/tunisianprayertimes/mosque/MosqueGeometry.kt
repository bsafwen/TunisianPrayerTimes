package com.tunisianprayertimes.mosque

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

internal data class MosqueCoordinate(val longitude: Double, val latitude: Double)

/** A validated GeoJSON polygon; the first ring is the exterior and the rest are holes. */
internal class MosquePolygon(private val rings: List<List<MosqueCoordinate>>) {
    private val exterior = rings.first()
    private val minLongitude = exterior.minOf { it.longitude }
    private val maxLongitude = exterior.maxOf { it.longitude }
    private val minLatitude = exterior.minOf { it.latitude }
    private val maxLatitude = exterior.maxOf { it.latitude }

    /**
     * Require the complete uncertainty circle to fit in one polygon, clear of every edge.
     * The local WGS84 tangent approximation is appropriate for mosque-sized footprints;
     * the extra margin used by the caller is larger than its projection error in Tunisia.
     */
    fun containsCircle(latitude: Double, longitude: Double, radiusMeters: Double): Boolean {
        if (!validMosqueCoordinates(latitude, longitude) ||
            !radiusMeters.isFinite() || radiusMeters <= 0.0 ||
            longitude !in minLongitude..maxLongitude || latitude !in minLatitude..maxLatitude
        ) return false

        val latitudeRadians = latitude * PI / 180.0
        val eccentricitySquared = 6.69437999014e-3
        val denominator = 1.0 - eccentricitySquared * sin(latitudeRadians).let { it * it }
        val longitudeScale = 6_378_137.0 / sqrt(denominator) * cos(latitudeRadians) * PI / 180.0
        val latitudeScale = 6_378_137.0 * (1.0 - eccentricitySquared) /
            (denominator * sqrt(denominator)) * PI / 180.0

        for ((ringIndex, ring) in rings.withIndex()) {
            var inside = false
            for (index in ring.indices) {
                val start = ring[index]
                val end = ring[(index + 1) % ring.size]
                val ax = (start.longitude - longitude) * longitudeScale
                val ay = (start.latitude - latitude) * latitudeScale
                val bx = (end.longitude - longitude) * longitudeScale
                val by = (end.latitude - latitude) * latitudeScale
                if (distanceToSegment(ax, ay, bx, by) <= radiusMeters) return false
                if ((ay > 0.0) != (by > 0.0) && 0.0 < ax + (bx - ax) * -ay / (by - ay)) {
                    inside = !inside
                }
            }
            if (ringIndex == 0 && !inside) return false
            if (ringIndex > 0 && inside) return false
        }
        return true
    }
}

private fun distanceToSegment(ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax
    val dy = by - ay
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0.0) return hypot(ax, ay)
    val fraction = (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
    return hypot(ax + fraction * dx, ay + fraction * dy)
}

internal fun validMosqueCoordinates(latitude: Double, longitude: Double): Boolean =
    latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
