package com.tunisianprayertimes

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import org.json.JSONObject

internal data class NeighborhoodBoundary(
    val locality: Locality,
    val areaKm2: Double,
    val bbox: DoubleArray,
    val offset: Int,
    val length: Int,
)

private data class GpsConflictPriority(
    val pair: Pair<Int, Int>,
    val preferredIndex: Int,
    val fallbackIndex: Int,
)

/** The picker can read names independently of the packed geometry needed only by GPS. */
internal fun parseNeighborhoodLocalities(json: JSONObject): List<Locality> {
    require(json.getInt("schemaVersion") == 1)
    val records = json.getJSONArray("features")
    val localities = List(records.length()) { index ->
        val row = records.getJSONObject(index)
        val aliases = row.getJSONArray("aliases")
        val names = List(aliases.length()) { aliases.getString(it) }
        val contextAliases = row.optJSONArray("contextAliases")
        val contextNames = List(contextAliases?.length() ?: 0) { contextAliases!!.getString(it) }
        Locality(
            id = row.getString("id"), name = row.getString("name"),
            parentName = row.getString("parentName"), governorateId = row.getInt("governorateId"),
            delegationId = row.getInt("delegationId"),
            searchText = normalizeLocalitySearch((names + contextNames + row.getString("name") + row.getString("parentName")).joinToString(" ")),
            lat = row.getDouble("lat"), lng = row.getDouble("lng"),
            hasBoundary = row.getBoolean("hasBoundary"), kind = row.getString("kind"),
            pickerGroupId = row.optString("pickerGroupId").takeIf { it.isNotBlank() },
            searchPhrases = names + contextNames + listOf(row.getString("name"), row.getString("parentName")),
        ).also { locality ->
            require(locality.id.isNotBlank() && locality.name.isNotBlank())
            require(validCoordinates(locality.lat!!, locality.lng!!))
        }
    }
    require(localities.map { it.id }.distinct().size == localities.size)
    return localities
}

/** Geometry is kept as packed integers, not hundreds of thousands of heap objects. */
internal class NeighborhoodIndex(
    json: JSONObject,
    private val geometry: ByteArray,
    val localities: List<Locality> = parseNeighborhoodLocalities(json),
) {
    private val boundaries: Map<Int, NeighborhoodBoundary>
    private val cells: Map<String, List<Int>>
    private val conflicts: List<Pair<Int, Int>>
    private val gpsConflictPriority: GpsConflictPriority?
    private val gridSize = json.getDouble("gridSize")
    private val scale = json.getDouble("coordinateScale")
    private val countryOffset = json.getJSONObject("country").getInt("offset")
    private val countryLength = json.getJSONObject("country").getInt("length")

    init {
        require(json.getInt("schemaVersion") == 1)
        require(gridSize > 0 && gridSize.isFinite() && scale > 0 && scale.isFinite())
        require(geometry.size >= 8 && geometry.copyOfRange(0, 4).contentEquals("NPOL".toByteArray()))
        require(ByteBuffer.wrap(geometry).getInt(4) == 1)
        val records = json.getJSONArray("features")
        require(records.length() == localities.size)
        val parsedBoundaries = mutableMapOf<Int, NeighborhoodBoundary>()
        repeat(records.length()) { index ->
            val row = records.getJSONObject(index)
            val locality = localities[index]
            require(locality.id == row.getString("id"))
            if (locality.hasBoundary) {
                val bbox = row.getJSONArray("bbox")
                require(bbox.length() == 4)
                val boundary = NeighborhoodBoundary(locality, row.getDouble("areaKm2"),
                    DoubleArray(4) { bbox.getDouble(it) }, row.getInt("offset"), row.getInt("length"))
                require(boundary.bbox.all { it.isFinite() } &&
                    validCoordinates(boundary.bbox[1], boundary.bbox[0]) &&
                    validCoordinates(boundary.bbox[3], boundary.bbox[2]) &&
                    boundary.bbox[0] < boundary.bbox[2] && boundary.bbox[1] < boundary.bbox[3])
                validatePackedGeometry(geometry, boundary.offset, boundary.length, scale)
                require(boundary.areaKm2 > 0 && boundary.areaKm2.isFinite())
                parsedBoundaries[index] = boundary
            }
        }
        boundaries = parsedBoundaries
        val boundaryIds = boundaries.entries.associate { it.value.locality.id to it.key }
        val conflictRows = if (json.has("conflicts")) json.getJSONArray("conflicts") else null
        conflicts = List(conflictRows?.length() ?: 0) { index ->
            val conflict = conflictRows!!.getJSONObject(index)
            val ids = conflict.getJSONArray("ids")
            require(ids.length() == 2 && conflict.getString("reason").isNotBlank())
            val first = requireNotNull(boundaryIds[ids.getString(0)])
            val second = requireNotNull(boundaryIds[ids.getString(1)])
            require(first != second)
            minOf(first, second) to maxOf(first, second)
        }.distinct()
        val policyRows = if (json.has("gpsConflictPolicies"))
            requireNotNull(json.optJSONArray("gpsConflictPolicies")) else null
        require(policyRows == null || policyRows.length() <= 1)
        gpsConflictPriority = if (policyRows != null && policyRows.length() == 1) {
            val policy = policyRows.getJSONObject(0)
            require(policy.getString("schemaVersion") == GPS_PRIORITY_POLICY_SCHEMA)
            require(policy.getString("status") == GPS_PRIORITY_POLICY_STATUS)
            require(policy.opt("osmFallbackWhenMNotAccuracyQualified") == true)
            require(policy.getString("scope") == GPS_PRIORITY_POLICY_SCOPE)
            val ids = policy.getJSONArray("ids")
            require(ids.length() == 2)
            require(setOf(ids.getString(0), ids.getString(1)) ==
                setOf(GPS_PRIORITY_OSM_FALLBACK_ID, GPS_PRIORITY_ACCEPTED_M_ID))
            require(policy.getString("gpsPreferredId") == GPS_PRIORITY_ACCEPTED_M_ID)
            require(policy.getString("acceptedOfficialCode") == "125654")
            require(policy.getString("preferredSourceWkbSha256") == GPS_PRIORITY_SOURCE_WKB_SHA256)
            require(policy.getString("osmPeerSourceId") == "osm")
            val preferredIndex = requireNotNull(boundaryIds[GPS_PRIORITY_ACCEPTED_M_ID])
            val fallbackIndex = requireNotNull(boundaryIds[GPS_PRIORITY_OSM_FALLBACK_ID])
            val pair = minOf(preferredIndex, fallbackIndex) to maxOf(preferredIndex, fallbackIndex)
            require(pair in conflicts)
            require(records.getJSONObject(preferredIndex).getString("sourceId") != "osm")
            require(records.getJSONObject(fallbackIndex).getString("sourceId") == "osm")
            GpsConflictPriority(pair, preferredIndex, fallbackIndex)
        } else null
        val grid = json.getJSONObject("cells")
        cells = grid.keys().asSequence().associateWith { key ->
            val ids = grid.getJSONArray(key)
            List(ids.length()) { ids.getInt(it).also { id -> require(id in boundaries) } }
        }
        validatePackedGeometry(geometry, countryOffset, countryLength, scale)
    }

    fun isInsideCountry(lat: Double, lng: Double): Boolean =
        validCoordinates(lat, lng) && containsPackedGeometry(geometry, countryOffset, countryLength, scale, lat, lng)

    fun find(lat: Double, lng: Double): Locality? {
        if (!isInsideCountry(lat, lng)) return null
        val key = "${floor(lat / gridSize).toInt()}:${floor(lng / gridSize).toInt()}"
        val candidates = cells[key].orEmpty().asSequence().mapNotNull { boundaries[it] }
            .filter { lng >= it.bbox[0] && lat >= it.bbox[1] && lng <= it.bbox[2] && lat <= it.bbox[3] }
            .filter { containsPackedGeometry(geometry, it.offset, it.length, scale, lat, lng) }
            .associateBy { it.locality.id }
        val ambiguous = mutableSetOf<String>()
        val strictInterior = mutableMapOf<String, Boolean>()
        fun isStrictlyInside(boundary: NeighborhoodBoundary): Boolean =
            strictInterior.getOrPut(boundary.locality.id) {
                containsPackedGeometry(geometry, boundary.offset, boundary.length, scale, lat, lng, includeBoundary = false)
            }
        conflicts.forEach { (firstIndex, secondIndex) ->
            val first = boundaries.getValue(firstIndex)
            val second = boundaries.getValue(secondIndex)
            // A shared edge alone is not a disputed area. Only suppress names in the
            // strict interior of both conflicting polygons; finer independent areas remain eligible.
            if (first.locality.id in candidates && second.locality.id in candidates &&
                isStrictlyInside(first) && isStrictlyInside(second)) {
                ambiguous += first.locality.id
                ambiguous += second.locality.id
            }
        }
        return candidates.values.asSequence().filter { it.locality.id !in ambiguous }
            .minWithOrNull(compareBy<NeighborhoodBoundary> { it.areaKm2 }.thenBy { it.locality.id })?.locality
    }

    /**
     * GPS-only lookup. The uncertainty disk is handled in a conservative linear frame:
     * Rmin is the minimum WGS84 meridional radius, so any path of length <= radius stays
     * within |latitude| <= |query| + radius/Rmin. Within that band the frame uses
     * y = Rmin * dLat, x = Rmin * cos(maxAbsLat) * dLng. This frame length lower-bounds
     * the true ellipsoidal path length, so clearance > radius is a proof of separation;
     * clearance <= radius may be a false positive. Null/invalid accuracy returns null.
     */
    fun findWithAccuracy(lat: Double, lng: Double, accuracyMeters: Double?): Locality? {
        val radiusMeters = accuracyMeters ?: return null
        if (!radiusMeters.isFinite() || radiusMeters <= 0.0) return null
        if (!isInsideCountry(lat, lng)) return null

        val queryLatRadians = Math.toRadians(lat)
        val deltaRadians = radiusMeters / MIN_MERIDIONAL_RADIUS_METERS
        val maxAbsLatRadians = abs(queryLatRadians) + deltaRadians
        if (!maxAbsLatRadians.isFinite() || maxAbsLatRadians >= Math.PI / 2.0) return null

        val yScale = MIN_MERIDIONAL_RADIUS_METERS
        val xScale = yScale * cos(maxAbsLatRadians)
        val queryLngRadians = Math.toRadians(lng)
        val key = "${floor(lat / gridSize).toInt()}:${floor(lng / gridSize).toInt()}"
        // The disk clearance supplies the boundary margin. Use exact membership here so
        // the legacy edge tolerance cannot hide a conflict peer for a tiny radius.
        val membershipCache = HashMap<Int, Boolean>()
        val clearanceCache = HashMap<Int, Double>()

        fun isStrictlyInside(index: Int): Boolean =
            membershipCache.getOrPut(index) {
                val boundary = boundaries.getValue(index)
                containsPackedGeometry(
                    geometry, boundary.offset, boundary.length, scale, lat, lng,
                    includeBoundary = false,
                    boundaryTolerance = 0.0,
                )
            }

        fun clearanceMeters(index: Int): Double =
            clearanceCache.getOrPut(index) {
                val boundary = boundaries.getValue(index)
                packedEdgeClearanceMeters(
                    bytes = geometry,
                    offset = boundary.offset,
                    length = boundary.length,
                    scale = scale,
                    queryLatRadians = queryLatRadians,
                    queryLngRadians = queryLngRadians,
                    xScale = xScale,
                    yScale = yScale,
                )
            }

        // Conservative peer test: the lower-bound clearance may flag a peer that the
        // true ellipsoidal disk would miss, but it never misses a possible intersection.
        fun peerMayIntersectDisk(peerIndex: Int): Boolean =
            isStrictlyInside(peerIndex) || clearanceMeters(peerIndex) <= radiusMeters

        val qualifiedCandidates = cells[key].orEmpty().asSequence()
            .filter { index ->
                val boundary = boundaries[index] ?: return@filter false
                lng >= boundary.bbox[0] && lat >= boundary.bbox[1] &&
                    lng <= boundary.bbox[2] && lat <= boundary.bbox[3]
            }
            .filter { index -> isStrictlyInside(index) && clearanceMeters(index) > radiusMeters }
            .toList()
        val preferredQualified = gpsConflictPriority?.preferredIndex?.let { it in qualifiedCandidates } == true

        return qualifiedCandidates.asSequence()
            .filterNot { candidateIndex ->
                conflicts.any { (firstIndex, secondIndex) ->
                    val priority = gpsConflictPriority
                    if (priority != null && (firstIndex to secondIndex) == priority.pair &&
                        (candidateIndex == priority.preferredIndex ||
                            (candidateIndex == priority.fallbackIndex && !preferredQualified))) {
                        false
                    } else {
                        when (candidateIndex) {
                            firstIndex -> peerMayIntersectDisk(secondIndex)
                            secondIndex -> peerMayIntersectDisk(firstIndex)
                            else -> false
                        }
                    }
                }
            }
            .mapNotNull { boundaries[it] }
            .minWithOrNull(compareBy<NeighborhoodBoundary> { it.areaKm2 }.thenBy { it.locality.id })
            ?.locality
    }
}

internal fun validCoordinates(lat: Double, lng: Double): Boolean =
    lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

private const val COORDINATE_EPSILON = 1e-10
private const val GPS_PRIORITY_OSM_FALLBACK_ID = "osm:relation:7115582" // 125655
private const val GPS_PRIORITY_ACCEPTED_M_ID = "osm:relation:7115585" // 125654
private const val GPS_PRIORITY_SOURCE_WKB_SHA256 =
    "6c60f2cd2d4d323a03d632232d0a4e453ba4f991d156b113cf7110819f4be27b"
private const val GPS_PRIORITY_POLICY_SCHEMA = "ariana-pair-specific-m-else-qualified-osm-policy-v2"
private const val GPS_PRIORITY_POLICY_STATUS = "SCRATCH_ONLY_INDEPENDENT_QA_PENDING"
private const val GPS_PRIORITY_POLICY_SCOPE =
    "GPS findWithAccuracy only; strict containment and clearance>accuracy still required for selected winner"
private const val WGS84_SEMI_MAJOR_AXIS_METERS = 6378137.0
private const val WGS84_FLATTENING = 1.0 / 298.257223563
private const val MIN_MERIDIONAL_RADIUS_METERS =
    WGS84_SEMI_MAJOR_AXIS_METERS * (1.0 - WGS84_FLATTENING) * (1.0 - WGS84_FLATTENING)

/** Reject corrupt packed records at load time, even when a query would return before reading them. */
private fun validatePackedGeometry(bytes: ByteArray, offset: Int, length: Int, scale: Double) {
    require(offset >= 8 && length >= 36 && offset % 4 == 0 && length % 4 == 0 && offset <= bytes.size - length)
    val buffer = ByteBuffer.wrap(bytes, offset, length).slice().order(ByteOrder.BIG_ENDIAN)
    val polygonCount = buffer.int
    require(polygonCount > 0 && polygonCount <= buffer.remaining() / 32)
    repeat(polygonCount) {
        require(buffer.remaining() >= 4)
        val ringCount = buffer.int
        require(ringCount > 0 && ringCount <= buffer.remaining() / 28)
        repeat(ringCount) {
            require(buffer.remaining() >= 4)
            val pointCount = buffer.int
            require(pointCount >= 3 && pointCount <= buffer.remaining() / 8)
            repeat(pointCount) {
                val lng = buffer.int / scale
                val lat = buffer.int / scale
                require(validCoordinates(lat, lng))
            }
        }
    }
    require(!buffer.hasRemaining())
}

internal fun containsPackedGeometry(
    bytes: ByteArray, offset: Int, length: Int, scale: Double, lat: Double, lng: Double,
    includeBoundary: Boolean = true,
    boundaryTolerance: Double = COORDINATE_EPSILON,
): Boolean {
    val buffer = ByteBuffer.wrap(bytes, offset, length).slice().order(ByteOrder.BIG_ENDIAN)
    val polygonCount = buffer.int
    require(polygonCount > 0 && polygonCount <= length / 4)
    repeat(polygonCount) {
        val ringCount = buffer.int
        require(ringCount > 0 && ringCount <= buffer.remaining() / 4)
        var insideOuter = false
        var insideHole = false
        repeat(ringCount) { ringIndex ->
            val count = buffer.int
            require(count >= 3 && count <= buffer.remaining() / 8)
            val firstX = buffer.int / scale
            val firstY = buffer.int / scale
            var previousX = firstX
            var previousY = firstY
            var inside = false
            var onEdge = false
            fun edge(x: Double, y: Double) {
                val dx = x - previousX
                val dy = y - previousY
                val cross = (lng - previousX) * dy - (lat - previousY) * dx
                // Scale the cross product (degrees squared) by segment length so the
                // numerical edge tolerance remains a distance, including for short edges.
                if (lng >= minOf(previousX, x) - boundaryTolerance && lng <= maxOf(previousX, x) + boundaryTolerance &&
                    lat >= minOf(previousY, y) - boundaryTolerance && lat <= maxOf(previousY, y) + boundaryTolerance &&
                    abs(cross) <= boundaryTolerance * hypot(dx, dy)) onEdge = true
                if ((previousY > lat) != (y > lat) && lng < dx * (lat - previousY) / dy + previousX) {
                    inside = !inside
                }
                previousX = x
                previousY = y
            }
            repeat(count - 1) { edge(buffer.int / scale, buffer.int / scale) }
            edge(firstX, firstY)
            if (ringIndex == 0) {
                insideOuter = if (includeBoundary) inside || onEdge else inside && !onEdge
            } else {
                insideHole = insideHole || inside || onEdge
            }
        }
        if (insideOuter && !insideHole) return true
    }
    return false
}

/**
 * Conservative lower-bound edge clearance in the linear frame described above.
 * A returned value greater than the accuracy radius proves the ellipsoidal disk
 * cannot reach the packed boundary; a smaller value may be a false positive.
 */
private fun packedEdgeClearanceMeters(
    bytes: ByteArray,
    offset: Int,
    length: Int,
    scale: Double,
    queryLatRadians: Double,
    queryLngRadians: Double,
    xScale: Double,
    yScale: Double,
): Double {
    val buffer = ByteBuffer.wrap(bytes, offset, length).slice().order(ByteOrder.BIG_ENDIAN)
    val polygonCount = buffer.int
    require(polygonCount > 0 && polygonCount <= length / 4)
    var minClearance = Double.POSITIVE_INFINITY

    repeat(polygonCount) {
        val ringCount = buffer.int
        require(ringCount > 0 && ringCount <= buffer.remaining() / 4)
        repeat(ringCount) {
            val count = buffer.int
            require(count >= 3 && count <= buffer.remaining() / 8)
            val firstX = buffer.int / scale
            val firstY = buffer.int / scale
            var previousFrameX = xScale * (Math.toRadians(firstX) - queryLngRadians)
            var previousFrameY = yScale * (Math.toRadians(firstY) - queryLatRadians)

            fun edge(x: Double, y: Double) {
                val frameX = xScale * (Math.toRadians(x) - queryLngRadians)
                val frameY = yScale * (Math.toRadians(y) - queryLatRadians)
                minClearance = minOf(
                    minClearance,
                    pointToSegmentDistance(
                        px = 0.0,
                        py = 0.0,
                        ax = previousFrameX,
                        ay = previousFrameY,
                        bx = frameX,
                        by = frameY,
                    ),
                )
                previousFrameX = frameX
                previousFrameY = frameY
            }

            repeat(count - 1) {
                edge(buffer.int / scale, buffer.int / scale)
            }
            edge(firstX, firstY)
        }
    }
    return minClearance
}

private fun pointToSegmentDistance(
    px: Double,
    py: Double,
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
): Double {
    val abX = bx - ax
    val abY = by - ay
    val lengthSquared = abX * abX + abY * abY
    if (lengthSquared == 0.0) return hypot(px - ax, py - ay)
    val projection = ((px - ax) * abX + (py - ay) * abY) / lengthSquared
    val t = projection.coerceIn(0.0, 1.0)
    return hypot(px - (ax + t * abX), py - (ay + t * abY))
}

object NeighborhoodRepository {
    @Volatile private var cached: NeighborhoodIndex? = null
    private data class Metadata(val json: JSONObject, val localities: List<Locality>)
    @Volatile private var metadata: Metadata? = null
    private val metadataLock = Any()
    private val geometryLock = Any()

    private fun loadMetadata(context: Context): Metadata {
        metadata?.let { return it }
        return synchronized(metadataLock) {
            metadata ?: run {
                val json = JSONObject(context.assets.open("neighborhoods.json").bufferedReader().use { it.readText() })
                val localities = parseNeighborhoodLocalities(json)
                require(json.getString("catalogScope") == "validated-official-sectors")
                require(localities.size == json.getInt("validatedLocationCount"))
                require(localities.all { it.kind == "sector" && it.hasBoundary })
                Metadata(json, localities.map { LocalityDisplayNames.localize(context, it) }).also { metadata = it }
            }
        }
    }

    internal fun loadLocalities(context: Context): List<Locality> = loadMetadata(context).localities

    internal fun load(context: Context): NeighborhoodIndex {
        cached?.let { return it }
        return synchronized(geometryLock) {
            cached ?: run {
                val names = loadMetadata(context)
                val bytes = context.assets.open("neighborhoods.bin").use { it.readBytes() }
                NeighborhoodIndex(names.json, bytes, names.localities).also { cached = it }
            }
        }
    }

    fun find(context: Context, lat: Double, lng: Double): Locality? =
        runCatching { load(context).find(lat, lng) }.getOrNull()

    fun isInsideCountry(context: Context, lat: Double, lng: Double): Boolean =
        runCatching { load(context).isInsideCountry(lat, lng) }.getOrDefault(false)
}
