package com.tunisianprayertimes.mosque

import android.content.Context
import android.location.Location
import android.os.SystemClock
import androidx.core.location.LocationCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Inventory record. A point, missing footprint, or ineligible record can never match a fix. */
class Mosque internal constructor(
    val id: String,
    val name: String?,
    val nameAr: String?,
    val nameFr: String?,
    val sourceUrl: String?,
    val status: String,
    /** Valid source area; this alone does not approve automatic silencing. */
    val polygonEligible: Boolean,
    val automaticSilencingEligible: Boolean,
    val boundaryCoverageStatus: String,
    internal val polygons: List<MosquePolygon>,
)

data class MosqueLocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    /** Monotonic age; null means the fix has no usable elapsed-realtime timestamp. */
    val ageMillis: Long?,
    val isMock: Boolean = false,
)

enum class MosqueFixRejection { INVALID_COORDINATES, MOCK_LOCATION, UNKNOWN_OR_STALE_AGE, INSUFFICIENT_ACCURACY }

sealed interface MosqueDetectionResult {
    /** Geometric candidates only: this is not proof of presence or permission to silence. */
    data class Candidates(val mosques: List<Mosque>) : MosqueDetectionResult
    /** The incomplete inventory has no eligible footprint containing the uncertainty circle. */
    data object NoPolygonMatch : MosqueDetectionResult
    data class UnusableFix(val reason: MosqueFixRejection) : MosqueDetectionResult
    data object DatasetUnavailable : MosqueDetectionResult
}

class MosqueCatalog internal constructor(val mosques: List<Mosque>) {
    private val eligibleMosques = mosques.filter { it.polygonEligible && it.automaticSilencingEligible }
    val eligiblePolygonCount: Int get() = eligibleMosques.size

    /**
     * Evaluates only fresh fixes with reported horizontal accuracy of at most 30 m.
     * Every match clears all outer/hole edges by accuracy + 2 m. Android's accuracy
     * estimate is probabilistic, and mapped outlines may be imperfect; a future silence
     * controller still needs user opt-in, dwell/exit hysteresis, and session ownership.
     */
    fun findCandidates(fix: MosqueLocationFix): MosqueDetectionResult {
        rejectFix(fix)?.let { return MosqueDetectionResult.UnusableFix(it) }
        val radiusMeters = requireNotNull(fix.accuracyMeters) + 2.0
        val matches = eligibleMosques.filter { mosque ->
            mosque.polygons.any { it.containsCircle(fix.latitude, fix.longitude, radiusMeters) }
        }
        return if (matches.isEmpty()) MosqueDetectionResult.NoPolygonMatch
        else MosqueDetectionResult.Candidates(matches)
    }
}

/** Offline data and geometry only; this object neither requests location nor changes phone audio. */
object MosqueRepository {
    const val ASSET_PATH = "mosques/tunisia-mosques.geojson"
    @Volatile private var cachedCatalog: MosqueCatalog? = null

    /** First load reads assets and parses JSON; call from a worker/IO dispatcher. */
    fun load(context: Context): MosqueCatalog = cachedCatalog ?: synchronized(this) {
        cachedCatalog ?: context.applicationContext.assets.open(ASSET_PATH).bufferedReader().use {
            parseMosqueCatalog(JSONObject(it.readText()))
        }.also { cachedCatalog = it }
    }

    /** An unavailable dataset is distinct from a valid location without a polygon match. */
    fun findCandidates(context: Context, location: Location): MosqueDetectionResult {
        val fix = location.toMosqueLocationFix(SystemClock.elapsedRealtimeNanos())
        rejectFix(fix)?.let { return MosqueDetectionResult.UnusableFix(it) }
        val catalog = runCatching { load(context) }.getOrNull()
            ?: return MosqueDetectionResult.DatasetUnavailable
        return catalog.findCandidates(fix)
    }
}

internal fun Location.toMosqueLocationFix(nowElapsedRealtimeNanos: Long): MosqueLocationFix {
    val elapsedNanos = elapsedRealtimeNanos
    val ageMillis = if (elapsedNanos > 0L && nowElapsedRealtimeNanos >= elapsedNanos) {
        (nowElapsedRealtimeNanos - elapsedNanos) / 1_000_000L
    } else null
    return MosqueLocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
        ageMillis = ageMillis,
        isMock = LocationCompat.isMock(this),
    )
}

private fun rejectFix(fix: MosqueLocationFix): MosqueFixRejection? = when {
    !validMosqueCoordinates(fix.latitude, fix.longitude) -> MosqueFixRejection.INVALID_COORDINATES
    fix.isMock -> MosqueFixRejection.MOCK_LOCATION
    fix.ageMillis == null || fix.ageMillis !in 0L..30_000L -> MosqueFixRejection.UNKNOWN_OR_STALE_AGE
    fix.accuracyMeters == null || !fix.accuracyMeters.isFinite() ||
        fix.accuracyMeters <= 0.0 || fix.accuracyMeters > 30.0 -> MosqueFixRejection.INSUFFICIENT_ACCURACY
    else -> null
}

internal fun parseMosqueCatalog(root: JSONObject): MosqueCatalog {
    require(root.getString("type") == "FeatureCollection")
    require(root.getInt("schemaVersion") == 1) { "Unsupported mosque catalog schema" }
    val features = root.getJSONArray("features")
    val ids = HashSet<String>()
    val mosques = List(features.length()) { index ->
        val feature = features.getJSONObject(index)
        require(feature.getString("type") == "Feature")
        val id = feature.getString("id")
        require(id.isNotBlank() && ids.add(id)) { "Missing or duplicate mosque id" }
        val properties = feature.getJSONObject("properties")
        val eligible = properties.getBoolean("polygonEligible")
        // Require an explicit JSON Boolean approval. Legacy/missing or malformed
        // flags stay inventory-only, including the string "true".
        val automaticEligible = properties.opt("automaticSilencingEligible") == true
        require(!automaticEligible || eligible) {
            "Automatic silencing requires a structurally eligible polygon"
        }
        // Review records deliberately retain invalid/non-area source geometry. Never parse
        // those shapes for detection: a broken review footprint must not disable valid ones.
        // The builder validates eligible topology; the app checks its representation again.
        val polygons = if (!eligible) emptyList() else {
            val geometry = requireNotNull(feature.optJSONObject("geometry")) {
                "Eligible mosque requires a polygon"
            }
            when (geometry.getString("type")) {
                "Polygon" -> listOf(parsePolygon(geometry.getJSONArray("coordinates")))
                "MultiPolygon" -> geometry.getJSONArray("coordinates").let { coordinates ->
                    require(coordinates.length() > 0)
                    List(coordinates.length()) { parsePolygon(coordinates.getJSONArray(it)) }
                }
                else -> throw IllegalArgumentException("Eligible mosque requires a polygon")
            }
        }
        Mosque(
            id = id,
            name = properties.nullableString("name"),
            nameAr = properties.nullableString("nameAr"),
            nameFr = properties.nullableString("nameFr"),
            sourceUrl = properties.nullableString("sourceUrl"),
            status = properties.getString("status"),
            polygonEligible = eligible,
            automaticSilencingEligible = automaticEligible,
            boundaryCoverageStatus = properties.nullableString("boundaryCoverageStatus") ?: "coverage_unreviewed",
            polygons = polygons,
        )
    }
    return MosqueCatalog(mosques)
}

private fun parsePolygon(coordinates: JSONArray): MosquePolygon {
    require(coordinates.length() > 0) { "Polygon must contain an exterior ring" }
    val rings = List(coordinates.length()) { ringIndex ->
        val ring = coordinates.getJSONArray(ringIndex)
        require(ring.length() >= 4) { "GeoJSON ring must have at least four coordinates" }
        val points = List(ring.length()) { parseCoordinate(ring.getJSONArray(it)) }
        require(points.first() == points.last()) { "GeoJSON ring must be closed" }
        val openRing = points.dropLast(1)
        require(openRing.distinct().size >= 3)
        val origin = openRing.first()
        val twiceArea = openRing.indices.sumOf { index ->
            val start = openRing[index]
            val end = openRing[(index + 1) % openRing.size]
            (start.longitude - origin.longitude) * (end.latitude - origin.latitude) -
                (end.longitude - origin.longitude) * (start.latitude - origin.latitude)
        }
        require(abs(twiceArea) > 1e-14) { "Degenerate mosque ring" }
        openRing
    }
    return MosquePolygon(rings)
}

private fun parseCoordinate(point: JSONArray): MosqueCoordinate {
    require(point.length() >= 2)
    val longitude = point.getDouble(0)
    val latitude = point.getDouble(1)
    require(validMosqueCoordinates(latitude, longitude))
    return MosqueCoordinate(longitude, latitude)
}

private fun JSONObject.nullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
