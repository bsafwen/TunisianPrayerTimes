package com.tunisianprayertimes.place

import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.FitStats
import com.tunisianprayertimes.method.TerrainClass

/**
 * One zone of an authority's official table (plan 6.5): the point, elevations and air its times are
 * computed at, and the offsets it adds. The engine takes the zone the repository maps a place to;
 * with no zone it computes at the place's own coordinates.
 */
data class OfficialZone(
    val authority: String,
    val id: String,
    val nameAr: String,
    val point: LatLngE5,
    /** E6: Fajr and sunrise are computed here (the UAE's and Jordan's eastern edge, Bahrain's Dilmunia). */
    val morningPoint: LatLngE5? = null,
    /** E6: Dhuhr, Asr, Maghrib and Isha are computed here. */
    val eveningPoint: LatLngE5? = null,
    /**
     * E6, a time's own point: each listed time is computed there instead of at the morning or evening point (Bahrain's
     * calendar, whose times each follow a point of their own, 8.8); an Isha rule's own point (`IshaRule.Angle.at`) wins.
     */
    val eventPoints: Map<TimeEvent, LatLngE5> = emptyMap(),
    /** E2, E10: the morning elevation where the two differ. */
    val elevationM: Int? = null,
    /** Jordan's separate evening elevation (8.6). */
    val eveningElevationM: Int? = null,
    /** E2 Refraction and E13: standard 1010 hPa when null. */
    val pressureHpa: Double? = null,
    /** E2 Refraction and E13: standard 10 °C when null. */
    val temperatureC: Double? = null,
    /** E2 PerZone: the zone's fitted sunrise altitude. */
    val sunriseAltitudeDeg: Double? = null,
    /** E2 PerZone: the zone's fitted sunset altitude. */
    val sunsetAltitudeDeg: Double? = null,
    /** Published or fitted (E7) minutes added to each time, fractions kept until the single rounding. */
    val offsets: Map<TimeEvent, Double> = emptyMap(),
    /** The zone whose unrounded times this one shifts by [offsetRule] (8.8). */
    val baseZoneId: String? = null,
    /** How a zone with a [baseZoneId] derives its shift from its own times and its base zone's (6.2, 8.8). */
    val offsetRule: ZoneOffsetRule = ZoneOffsetRule.PUBLISHED,
    /** Algeria's Adrar reference only: its attached places' windows are days 1–15 and 17 to the end. */
    val halfMonthWindows: Boolean = false,
    /** E10: the location's own Maghrib class where its elevation lies between the rule's two limits. */
    val terrainClass: TerrainClass? = null,
    val admin1: List<String> = emptyList(),
    val admin2: List<String> = emptyList(),
    val namedPlaces: List<NamedPlace> = emptyList(),
    val reachKm: Double = Double.POSITIVE_INFINITY,
    val mappingTier: Int = 0,
    /** irn south of 63.56°N: the entry's own days (1–365 of a common year) on the summer rule (7.4). */
    val switchDays: Map<TimeEvent, IntRange> = emptyMap(),
    val fit: FitStats? = null,
    /** The zone's country where an authority's tables cover several (`bosnia_iz`, `xk_bik`); null: the authority's. */
    val country: String? = null,
    /** `km_fitted` and `yt_traditional`: the sunrise row at the user's own point, every other time from the zone (6.5). */
    val sunriseAtPlace: Boolean = false,
    /**
     * `re_cir`'s Le Tampon: Maghrib and Isha move at least these minutes, the zone's own column counting as the first
     * minutes of the place's elevation shift (E16, 7.6): max(floor, E16) instead of E16.
     */
    val elevationShiftFloor: Double? = null,
    /** Jordan's Ghor tables: a place below sea level in the zone's governorate takes it first (8.8, step 1). */
    val belowSeaLevel: Boolean = false,
)

/** A place the authority itself names for a zone (plan 6.5). */
data class NamedPlace(
    val centre: LatLngE5,
    val catalogueIds: List<Long> = emptyList(),
    val areaKm: Double = 0.0,
)

/**
 * How a zone with a base zone gets its times (plan 6.2, 8.8): the base zone's unrounded times plus
 * a shift added in steps 4 and 7, so that each time is still rounded once.
 */
enum class ZoneOffsetRule {
    /** The zone's [OfficialZone.offsets] as published (Kuwait, Palestine, Slovenia, Albania, Kosovo, Montenegro). */
    PUBLISHED,

    /** Jordan's further places: the zone's own time minus the base zone's on the 15th of the month, to the half-minute. */
    MONTHLY_DIFFERENCE,

    /** Algeria's attached places: the ceiling of the largest difference over the offset window. */
    WINDOW_CEILING,

    /** bosnia_iz's Takvim places: the Takvim's longitude and latitude differences from Sarajevo, by month. */
    TAKVIM,
}
