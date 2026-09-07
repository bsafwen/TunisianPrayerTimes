package com.tunisianprayertimes

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
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

/** The picker can read names independently of the packed geometry needed only by GPS. */
internal fun parseNeighborhoodLocalities(json: JSONObject): List<Locality> {
    require(json.getInt("schemaVersion") == 1)
    val records = json.getJSONArray("features")
    val localities = List(records.length()) { index ->
        val row = records.getJSONObject(index)
        val aliases = row.getJSONArray("aliases")
        val names = List(aliases.length()) { aliases.getString(it) }
        Locality(
            id = row.getString("id"), name = row.getString("name"),
            parentName = row.getString("parentName"), governorateId = row.getInt("governorateId"),
            delegationId = row.getInt("delegationId"),
            searchText = normalizeLocalitySearch((names + row.getString("name") + row.getString("parentName")).joinToString(" ")),
            lat = row.getDouble("lat"), lng = row.getDouble("lng"),
            hasBoundary = row.getBoolean("hasBoundary"), kind = row.getString("kind"),
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
}

internal fun validCoordinates(lat: Double, lng: Double): Boolean =
    lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

private const val COORDINATE_EPSILON = 1e-10

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
                if (lng >= minOf(previousX, x) - COORDINATE_EPSILON && lng <= maxOf(previousX, x) + COORDINATE_EPSILON &&
                    lat >= minOf(previousY, y) - COORDINATE_EPSILON && lat <= maxOf(previousY, y) + COORDINATE_EPSILON &&
                    abs(cross) <= COORDINATE_EPSILON * hypot(dx, dy)) onEdge = true
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
                Metadata(json, parseNeighborhoodLocalities(json)).also { metadata = it }
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
