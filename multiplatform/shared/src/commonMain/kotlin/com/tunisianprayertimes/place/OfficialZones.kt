package com.tunisianprayertimes.place

import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.FitStats
import com.tunisianprayertimes.method.TerrainClass
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How an authority attaches a place to one of its zones (plan 6.5, the Mapping table). */
enum class MappingKind { NEAREST, NEAREST_LOCAL, LISTED_PLACES, DISTRICT, NATIONAL, NAMED_REGIONS, ADMIN_AREA, EXACT }

/**
 * One authority's zones, as `android-app/app/src/main/assets/official-zones/<authority>.json` holds them
 * (plan 6.5): written by the fits (scripts/prayer_formula/fits/, 9.4) and read on the phone.
 */
data class AuthorityZones(
    val authority: String,
    val methodId: String,
    val country: String,
    val mapping: MappingKind,
    /** Zones restricted to the place's admin1 (MR, DZ, AE, JO: by province). */
    val byAdmin1: Boolean,
    /** Egypt, Oman and Qatar: a place that is one of a zone's named places takes that zone first (6.5). */
    val namedFirst: Boolean,
    /** Yemen: the nearest location of the place's own terrain class within this many km first (E10). */
    val terrainClassKm: Double?,
    val zones: List<OfficialZone>,
    /** The profile's aggregate fit record (6.3). */
    val fit: FitStats?,
    val source: String,
    /**
     * Algeria: the ministry's 12 month starts per Hijri year (6.2, 8.8), which bound the windows of its
     * `WINDOW_CEILING` offsets; a year not listed takes Umm al-Qura's (`HijrahDate`).
     */
    val hijriMonthStarts: Map<Int, List<LocalDate>> = emptyMap(),
    /** GeoNames admin1 codes where the method runs at the place's own point: Iraq's Kurdistan Region (8.8, Q8). */
    val exactAdmin1: Set<String> = emptySet(),
) {
    val byId: Map<String, OfficialZone> = zones.associateBy { it.id }

    /** The zone a zone with a base zone shifts (6.5, 8.8). */
    fun baseOf(zone: OfficialZone): OfficialZone? = zone.baseZoneId?.let { byId[it] }
}

/** Reads and writes the official-zone assets. */
object OfficialZones {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    fun parse(text: String): AuthorityZones = json.decodeFromString(AssetJson.serializer(), text).toZones()

    fun write(zones: AuthorityZones): String = Json { prettyPrint = true; encodeDefaults = false; explicitNulls = false }
        .encodeToString(AssetJson.serializer(), AssetJson.of(zones))

    @Serializable
    internal data class PointJson(val latE5: Int, val lngE5: Int) {
        fun toPoint() = LatLngE5(latE5, lngE5)
    }

    @Serializable
    internal data class NamedPlaceJson(val centre: PointJson, val catalogueIds: List<Long> = emptyList(), val areaKm: Double = 0.0)

    @Serializable
    internal data class RangeJson(val first: Int, val last: Int)

    @Serializable
    internal data class ZoneJson(
        val id: String,
        val nameAr: String,
        val point: PointJson,
        val morningPoint: PointJson? = null,
        val eveningPoint: PointJson? = null,
        val eventPoints: Map<TimeEvent, PointJson> = emptyMap(),
        val elevationM: Int? = null,
        val eveningElevationM: Int? = null,
        val pressureHpa: Double? = null,
        val temperatureC: Double? = null,
        val sunriseAltitudeDeg: Double? = null,
        val sunsetAltitudeDeg: Double? = null,
        val offsets: Map<TimeEvent, Double> = emptyMap(),
        val baseZoneId: String? = null,
        val offsetRule: ZoneOffsetRule = ZoneOffsetRule.PUBLISHED,
        val halfMonthWindows: Boolean = false,
        val terrainClass: TerrainClass? = null,
        val admin1: List<String> = emptyList(),
        val admin2: List<String> = emptyList(),
        val namedPlaces: List<NamedPlaceJson> = emptyList(),
        /** Null: no reach limit. */
        val reachKm: Double? = null,
        val mappingTier: Int = 0,
        val switchDays: Map<TimeEvent, RangeJson> = emptyMap(),
        val fit: FitStats? = null,
        val country: String? = null,
        val sunriseAtPlace: Boolean = false,
        val elevationShiftFloor: Double? = null,
        val belowSeaLevel: Boolean = false,
    )

    @Serializable
    internal data class AssetJson(
        val schema: Int = 1,
        val authority: String,
        val methodId: String,
        val country: String,
        val mapping: MappingKind,
        val byAdmin1: Boolean = false,
        val namedFirst: Boolean = false,
        val terrainClassKm: Double? = null,
        val source: String = "",
        val fit: FitStats? = null,
        /** Hijri year to the ISO dates of its 12 month starts. */
        val hijriMonthStarts: Map<String, List<String>> = emptyMap(),
        val exactAdmin1: List<String> = emptyList(),
        val zones: List<ZoneJson>,
    ) {
        fun toZones() = AuthorityZones(
            authority = authority, methodId = methodId, country = country, mapping = mapping, byAdmin1 = byAdmin1,
            namedFirst = namedFirst, terrainClassKm = terrainClassKm, fit = fit, source = source,
            hijriMonthStarts = hijriMonthStarts.entries.associate { (year, starts) -> year.toInt() to starts.map(LocalDate::parse) },
            exactAdmin1 = exactAdmin1.toSet(),
            zones = zones.map { z ->
                OfficialZone(
                    authority = authority, id = z.id, nameAr = z.nameAr, point = z.point.toPoint(),
                    morningPoint = z.morningPoint?.toPoint(), eveningPoint = z.eveningPoint?.toPoint(),
                    eventPoints = z.eventPoints.mapValues { (_, p) -> p.toPoint() },
                    elevationM = z.elevationM, eveningElevationM = z.eveningElevationM,
                    pressureHpa = z.pressureHpa, temperatureC = z.temperatureC,
                    sunriseAltitudeDeg = z.sunriseAltitudeDeg, sunsetAltitudeDeg = z.sunsetAltitudeDeg,
                    offsets = z.offsets, baseZoneId = z.baseZoneId, offsetRule = z.offsetRule, halfMonthWindows = z.halfMonthWindows,
                    terrainClass = z.terrainClass, admin1 = z.admin1, admin2 = z.admin2,
                    namedPlaces = z.namedPlaces.map { NamedPlace(it.centre.toPoint(), it.catalogueIds, it.areaKm) },
                    reachKm = z.reachKm ?: Double.POSITIVE_INFINITY, mappingTier = z.mappingTier,
                    switchDays = z.switchDays.mapValues { (_, r) -> r.first..r.last }, fit = z.fit,
                    country = z.country, sunriseAtPlace = z.sunriseAtPlace, elevationShiftFloor = z.elevationShiftFloor,
                    belowSeaLevel = z.belowSeaLevel,
                )
            },
        )

        companion object {
            fun of(a: AuthorityZones) = AssetJson(
                authority = a.authority, methodId = a.methodId, country = a.country, mapping = a.mapping, byAdmin1 = a.byAdmin1,
                namedFirst = a.namedFirst, terrainClassKm = a.terrainClassKm, source = a.source, fit = a.fit,
                hijriMonthStarts = a.hijriMonthStarts.entries.associate { (year, starts) -> year.toString() to starts.map(LocalDate::toString) },
                exactAdmin1 = a.exactAdmin1.sorted(),
                zones = a.zones.map { z ->
                    fun p(x: LatLngE5) = PointJson(x.latE5, x.lngE5)
                    ZoneJson(
                        id = z.id, nameAr = z.nameAr, point = p(z.point), morningPoint = z.morningPoint?.let(::p), eveningPoint = z.eveningPoint?.let(::p),
                        eventPoints = z.eventPoints.mapValues { (_, x) -> p(x) },
                        elevationM = z.elevationM, eveningElevationM = z.eveningElevationM, pressureHpa = z.pressureHpa, temperatureC = z.temperatureC,
                        sunriseAltitudeDeg = z.sunriseAltitudeDeg, sunsetAltitudeDeg = z.sunsetAltitudeDeg, offsets = z.offsets,
                        baseZoneId = z.baseZoneId, offsetRule = z.offsetRule, halfMonthWindows = z.halfMonthWindows, terrainClass = z.terrainClass,
                        admin1 = z.admin1, admin2 = z.admin2,
                        namedPlaces = z.namedPlaces.map { NamedPlaceJson(p(it.centre), it.catalogueIds, it.areaKm) },
                        reachKm = z.reachKm.takeIf { it.isFinite() }, mappingTier = z.mappingTier,
                        switchDays = z.switchDays.mapValues { (_, r) -> RangeJson(r.first, r.last) }, fit = z.fit,
                        country = z.country, sunriseAtPlace = z.sunriseAtPlace, elevationShiftFloor = z.elevationShiftFloor,
                        belowSeaLevel = z.belowSeaLevel,
                    )
                },
            )
        }
    }
}
