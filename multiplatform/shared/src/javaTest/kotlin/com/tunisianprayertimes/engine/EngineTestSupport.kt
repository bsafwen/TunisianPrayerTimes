package com.tunisianprayertimes.engine

import com.tunisianprayertimes.method.Evidence
import com.tunisianprayertimes.method.EvidenceKind
import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.place.LatLngE5
import com.tunisianprayertimes.place.OfficialZone
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneOffsetRule
import com.tunisianprayertimes.method.TerrainClass
import com.tunisianprayertimes.place.ZoneSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull

/** Places, methods and JSON helpers shared by the engine tests. */
object EngineTestSupport {
    val json = Json { encodeDefaults = true; prettyPrint = false }

    fun place(
        latitude: Double,
        longitude: Double,
        zone: String,
        country: String? = null,
        elevationM: Int? = null,
    ) = PrayerPlace.Coordinates(
        latE5 = Math.round(latitude * 1e5).toInt(),
        lngE5 = Math.round(longitude * 1e5).toInt(),
        elevationM = elevationM,
        zoneId = zone,
        zoneSource = ZoneSource.USER,
        countryCode = country,
    )

    fun method(id: String, fajr: Double, isha: Double, build: PrayerMethod.() -> PrayerMethod = { this }) = PrayerMethod(
        id = id,
        fajr = FajrRule.Angle(fajr),
        isha = IshaRule.Angle(isha),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = Evidence(EvidenceKind.APPROXIMATE, "test"),
    ).build()

    fun local(instant: Instant?, zone: String) = instant?.atZone(ZoneId.of(zone))?.toLocalDateTime()

    // ---- OfficialZone <-> JSON, for the reference cases (the asset format comes with M5) ----

    fun zoneJson(zone: OfficialZone): JsonObject = buildJsonObject {
        put("authority", zone.authority)
        put("id", zone.id)
        put("point", point(zone.point))
        put("morningPoint", zone.morningPoint?.let(::point) ?: JsonNull)
        put("eveningPoint", zone.eveningPoint?.let(::point) ?: JsonNull)
        if (zone.eventPoints.isNotEmpty()) put("eventPoints", buildJsonObject { zone.eventPoints.forEach { (e, p) -> put(e.name, point(p)) } })
        put("elevationM", zone.elevationM)
        put("eveningElevationM", zone.eveningElevationM)
        put("pressureHpa", zone.pressureHpa)
        put("temperatureC", zone.temperatureC)
        put("sunriseAltitudeDeg", zone.sunriseAltitudeDeg)
        put("sunsetAltitudeDeg", zone.sunsetAltitudeDeg)
        put("offsets", buildJsonObject { zone.offsets.forEach { (e, m) -> put(e.name, m) } })
        put("baseZoneId", zone.baseZoneId)
        put("offsetRule", zone.offsetRule.name)
        put("terrainClass", zone.terrainClass?.name)
        put("switchDays", buildJsonObject {
            zone.switchDays.forEach { (e, r) -> put(e.name, buildJsonObject { put("first", r.first); put("last", r.last) }) }
        })
        put("elevationShiftFloor", zone.elevationShiftFloor)
        put("sunriseAtPlace", zone.sunriseAtPlace)
    }

    fun zoneOf(element: JsonElement): OfficialZone? {
        if (element is JsonNull) return null
        val o = element.jsonObject
        fun pointOf(e: JsonElement?) = if (e == null || e is JsonNull) null else e.jsonObject.let {
            LatLngE5(it.getValue("latE5").jsonPrimitive.int, it.getValue("lngE5").jsonPrimitive.int)
        }
        fun string(key: String) = o[key]?.jsonPrimitive?.contentOrNull
        return OfficialZone(
            authority = string("authority")!!,
            id = string("id")!!,
            nameAr = "",
            point = pointOf(o["point"])!!,
            morningPoint = pointOf(o["morningPoint"]),
            eveningPoint = pointOf(o["eveningPoint"]),
            eventPoints = o["eventPoints"]?.takeIf { it !is JsonNull }?.jsonObject.orEmpty()
                .map { (k, v) -> TimeEvent.valueOf(k) to checkNotNull(pointOf(v)) }.toMap(),
            elevationM = o["elevationM"]?.jsonPrimitive?.intOrNull,
            eveningElevationM = o["eveningElevationM"]?.jsonPrimitive?.intOrNull,
            pressureHpa = o["pressureHpa"]?.jsonPrimitive?.doubleOrNull,
            temperatureC = o["temperatureC"]?.jsonPrimitive?.doubleOrNull,
            sunriseAltitudeDeg = o["sunriseAltitudeDeg"]?.jsonPrimitive?.doubleOrNull,
            sunsetAltitudeDeg = o["sunsetAltitudeDeg"]?.jsonPrimitive?.doubleOrNull,
            offsets = o.getValue("offsets").jsonObject.mapKeys { TimeEvent.valueOf(it.key) }.mapValues { it.value.jsonPrimitive.double },
            baseZoneId = string("baseZoneId"),
            offsetRule = ZoneOffsetRule.valueOf(string("offsetRule")!!),
            terrainClass = string("terrainClass")?.let(TerrainClass::valueOf),
            switchDays = o["switchDays"]?.takeIf { it !is JsonNull }?.jsonObject.orEmpty().map { (k, v) ->
                TimeEvent.valueOf(k) to (v.jsonObject.getValue("first").jsonPrimitive.int..v.jsonObject.getValue("last").jsonPrimitive.int)
            }.toMap(),
            elevationShiftFloor = o["elevationShiftFloor"]?.jsonPrimitive?.doubleOrNull,
            sunriseAtPlace = o["sunriseAtPlace"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun point(p: LatLngE5) = buildJsonObject {
        put("latE5", p.latE5)
        put("lngE5", p.lngE5)
    }

    fun dates(vararg iso: String): List<LocalDate> = iso.map(LocalDate::parse)
}
