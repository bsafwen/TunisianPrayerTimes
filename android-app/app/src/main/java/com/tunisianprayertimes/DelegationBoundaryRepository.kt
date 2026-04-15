package com.tunisianprayertimes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

private const val BOUNDARY_EPSILON = 1e-10

internal data class BoundaryPoint(
    val lng: Double,
    val lat: Double
)

internal data class BoundaryPolygon(
    val outerRing: List<BoundaryPoint>,
    val holes: List<List<BoundaryPoint>>
) {
    fun contains(lat: Double, lng: Double): Boolean {
        if (!ringContainsPoint(outerRing, lat, lng)) {
            return false
        }
        return holes.none { hole -> ringContainsPoint(hole, lat, lng) }
    }
}

internal data class DelegationBoundary(
    val candidateDelegationIds: List<Int>,
    val minLng: Double,
    val minLat: Double,
    val maxLng: Double,
    val maxLat: Double,
    val polygons: List<BoundaryPolygon>
) {
    fun contains(lat: Double, lng: Double): Boolean {
        if (lng < minLng || lng > maxLng || lat < minLat || lat > maxLat) {
            return false
        }
        return polygons.any { polygon -> polygon.contains(lat, lng) }
    }
}

object DelegationBoundaryRepository {

    private var cachedBoundaries: List<DelegationBoundary>? = null

    fun findDelegationForLocation(context: Context, lat: Double, lng: Double): Delegation? {
        val boundaries = runCatching { loadAll(context) }.getOrDefault(emptyList())
        if (boundaries.isEmpty()) {
            return null
        }

        return resolveDelegationFromBoundaries(
            boundaries = boundaries,
            delegations = GouvernoratRepository.loadAllDelegations(context),
            lat = lat,
            lng = lng
        )
    }

    private fun loadAll(context: Context): List<DelegationBoundary> {
        cachedBoundaries?.let { return it }

        val json = context.assets
            .open("delegation_boundaries.json")
            .bufferedReader()
            .use { it.readText() }

        val root = JSONObject(json)
        val boundaries = root.getJSONArray("boundaries")
        val result = MutableList(boundaries.length()) { index ->
            parseBoundary(boundaries.getJSONObject(index))
        }

        cachedBoundaries = result
        return result
    }

    private fun parseBoundary(json: JSONObject): DelegationBoundary {
        val bbox = json.getJSONArray("bbox")
        val geometry = json.getJSONObject("geometry")
        return DelegationBoundary(
            candidateDelegationIds = json.getJSONArray("candidateDelegationIds").toIntList(),
            minLng = bbox.getDouble(0),
            minLat = bbox.getDouble(1),
            maxLng = bbox.getDouble(2),
            maxLat = bbox.getDouble(3),
            polygons = parseGeometry(geometry)
        )
    }

    private fun parseGeometry(geometry: JSONObject): List<BoundaryPolygon> {
        val coordinates = geometry.getJSONArray("coordinates")
        return when (geometry.getString("type")) {
            "Polygon" -> listOf(parsePolygon(coordinates))
            "MultiPolygon" -> List(coordinates.length()) { index ->
                parsePolygon(coordinates.getJSONArray(index))
            }

            else -> emptyList()
        }
    }

    private fun parsePolygon(rings: JSONArray): BoundaryPolygon {
        val parsedRings = List(rings.length()) { index ->
            normalizeRing(parseRing(rings.getJSONArray(index)))
        }.filter { ring -> ring.size >= 3 }

        return BoundaryPolygon(
            outerRing = parsedRings.firstOrNull().orEmpty(),
            holes = if (parsedRings.size > 1) parsedRings.drop(1) else emptyList()
        )
    }

    private fun parseRing(points: JSONArray): List<BoundaryPoint> {
        return List(points.length()) { index ->
            val point = points.getJSONArray(index)
            BoundaryPoint(
                lng = point.getDouble(0),
                lat = point.getDouble(1)
            )
        }
    }
}

internal fun resolveDelegationFromBoundaries(
    boundaries: List<DelegationBoundary>,
    delegations: List<Delegation>,
    lat: Double,
    lng: Double
): Delegation? {
    if (boundaries.isEmpty()) {
        return null
    }

    val delegationsById = delegations.associateBy { delegation -> delegation.id }
    return boundaries.asSequence()
        .filter { boundary -> boundary.contains(lat, lng) }
        .flatMap { boundary -> boundary.candidateDelegationIds.asSequence() }
        .distinct()
        .mapNotNull { id -> delegationsById[id] }
        .filter { delegation -> delegation.lat != 0.0 && delegation.lng != 0.0 }
        .minByOrNull { delegation -> haversineKm(lat, lng, delegation.lat, delegation.lng) }
}

internal fun ringContainsPoint(ring: List<BoundaryPoint>, lat: Double, lng: Double): Boolean {
    if (ring.size < 3) {
        return false
    }

    var inside = false
    for (index in ring.indices) {
        val start = ring[index]
        val end = ring[(index + 1) % ring.size]
        if (pointOnSegment(lng, lat, start, end)) {
            return true
        }

        val crossesLatitude = (start.lat > lat) != (end.lat > lat)
        if (!crossesLatitude) {
            continue
        }

        val denominator = end.lat - start.lat
        val intersectionLng = (end.lng - start.lng) * (lat - start.lat) /
            denominator.coerceAwayFromZero() + start.lng
        if (lng < intersectionLng) {
            inside = !inside
        }
    }

    return inside
}

internal fun pointOnSegment(
    pointLng: Double,
    pointLat: Double,
    start: BoundaryPoint,
    end: BoundaryPoint
): Boolean {
    if (start.approximatelyEquals(end)) {
        return abs(pointLng - start.lng) <= BOUNDARY_EPSILON &&
            abs(pointLat - start.lat) <= BOUNDARY_EPSILON
    }

    val cross = (pointLat - start.lat) * (end.lng - start.lng) -
        (pointLng - start.lng) * (end.lat - start.lat)
    if (abs(cross) > BOUNDARY_EPSILON) {
        return false
    }

    val dot = (pointLng - start.lng) * (end.lng - start.lng) +
        (pointLat - start.lat) * (end.lat - start.lat)
    if (dot < 0.0) {
        return false
    }

    val segmentLengthSquared = (end.lng - start.lng) * (end.lng - start.lng) +
        (end.lat - start.lat) * (end.lat - start.lat)
    return dot <= segmentLengthSquared
}

internal fun normalizeRing(ring: List<BoundaryPoint>): List<BoundaryPoint> {
    if (ring.size >= 2 && ring.first().approximatelyEquals(ring.last())) {
        return ring.dropLast(1)
    }
    return ring
}

private fun JSONArray.toIntList(): List<Int> {
    return List(length()) { index -> getInt(index) }
}

private fun Double.coerceAwayFromZero(): Double {
    if (abs(this) > BOUNDARY_EPSILON) {
        return this
    }
    return if (this >= 0.0) BOUNDARY_EPSILON else -BOUNDARY_EPSILON
}

private fun BoundaryPoint.approximatelyEquals(other: BoundaryPoint): Boolean {
    return abs(lng - other.lng) <= BOUNDARY_EPSILON &&
        abs(lat - other.lat) <= BOUNDARY_EPSILON
}