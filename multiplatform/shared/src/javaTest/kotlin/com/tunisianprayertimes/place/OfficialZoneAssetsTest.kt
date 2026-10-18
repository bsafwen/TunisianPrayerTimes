package com.tunisianprayertimes.place

import com.tunisianprayertimes.engine.EventStatus
import com.tunisianprayertimes.engine.PrayerEngine
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.PrayerMethods
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every official-zone asset the fits wrote (android-app/app/src/main/assets/official-zones, plan 6.5, 9.4)
 * loads, names a known method, has unique zone ids and existing base zones, and gives six times at each
 * zone's own point on two dates.
 */
class OfficialZoneAssetsTest {
    private fun assetsFolder(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = dir.resolve("android-app/app/src/main/assets/official-zones")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun everyAssetLoadsAndComputes() {
        val files = assetsFolder()?.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }.orEmpty()
        val problems = mutableListOf<String>()
        for (file in files) {
            val zones = OfficialZones.parse(file.readText())
            val name = file.nameWithoutExtension
            if (zones.authority != name) problems += "$name: authority ${zones.authority}"
            val method = PrayerMethods.byId(zones.methodId)
            if (method == null) {
                problems += "$name: unknown method ${zones.methodId}"
                continue
            }
            if (zones.zones.isEmpty()) problems += "$name: no zones"
            val duplicates = zones.zones.groupBy { it.id }.filterValues { it.size > 1 }.keys
            if (duplicates.isNotEmpty()) problems += "$name: duplicate ids $duplicates"
            if (!zones.country.split(',').all { it.matches(Regex("[A-Z]{2}")) }) problems += "$name: country ${zones.country}"
            for (zone in zones.zones) {
                val where = "$name/${zone.id}"
                if (zone.nameAr.isBlank()) problems += "$where: no Arabic name"
                if (zone.point.latitude !in -90.0..90.0 || zone.point.longitude !in -180.0..180.0) problems += "$where: point ${zone.point}"
                zone.baseZoneId?.let { if (zones.byId[it] == null) problems += "$where: unknown base zone $it" }
                zone.country?.let { if (!it.matches(Regex("[A-Z]{2}"))) problems += "$where: country $it" }
                val place = PrayerPlace.Coordinates(
                    zone.point.latE5, zone.point.lngE5, zone.elevationM, zoneIdOf(zone.country ?: zones.country.split(',').first()),
                    ZoneSource.USER, zone.country ?: zones.country.split(',').first(),
                )
                for (date in listOf(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 6, 21))) {
                    val day = runCatching { PrayerEngine.day(place, date, method.revisedFor(date), zone = zone, baseZone = zones.baseOf(zone)) }
                        .getOrElse { problems += "$where $date: ${it.message}"; null } ?: continue
                    val absent = TimeEvent.entries.filter { day[it].status == EventStatus.ABSENT }
                    if (absent.isNotEmpty()) problems += "$where $date: absent $absent"
                }
            }
            zones.fit?.let { assertNotNull(it.heldOut, "$name: held-out samples") }
        }
        println("Official-zone assets: ${files.size} files, ${files.sumOf { OfficialZones.parse(it.readText()).zones.size }} zones")
        assertTrue(problems.isEmpty(), problems.take(40).joinToString("\n", prefix = "${problems.size} problems:\n"))
    }

    @Test
    fun theManifestListsEveryAssetAsTheAppSelectsIt() {
        // index.tsv (fits/common.write_index): the app reads each asset only when its method serves a place in its
        // countries, so the manifest must name every asset with its method and countries, its zones' included.
        val folder = assetsFolder() ?: return
        val expected = folder.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.map { file ->
            val zones = OfficialZones.parse(file.readText())
            listOf(zones.authority, zones.methodId, OfficialZoneIndex.countriesOf(zones).sorted().joinToString(",")).joinToString("\t")
        }
        val manifest = folder.resolve("index.tsv").readLines().drop(1).filter { it.isNotBlank() }
        assertEquals(expected, manifest, "official-zones/index.tsv is stale: run fits/common.write_index()")
    }

    @Test
    fun syriasPlacesTakeTheTablesTheirScreensShow() {
        // fits/sy_awqaf: a zone is the town its sampled screens are in, whatever point its table was computed at.
        val file = assetsFolder()?.resolve("sy_awqaf.json")?.takeIf { it.isFile } ?: return
        val syria = OfficialZones.parse(file.readText())
        val method = assertNotNull(PrayerMethods.byId(syria.methodId))
        fun zoneAt(lat: Double, lng: Double, catalogueId: Long? = null): String? {
            val point = LatLngE5.of(lat, lng)
            val place = PrayerPlace.Coordinates(point.latE5, point.lngE5, null, "Asia/Damascus", ZoneSource.USER, "SY", catalogueId)
            return ZoneMapping.attach(place, method, syria)?.id
        }
        // Region 783 is computed at Aleppo's point but shown in Jindires and Afrin; Aleppo's mosques show 804.
        assertEquals("r783", zoneAt(36.39496, 36.6889))
        assertEquals("r783", zoneAt(36.51194, 36.86954, catalogueId = 174186L))
        assertEquals("r804", zoneAt(36.20124, 37.16117, catalogueId = 170063L))
        // Region 809 is computed 60 km east of Salamiyah, whose mosques show it.
        assertEquals("r809", zoneAt(35.01127, 37.05324))
        // Damascus takes 764–767's table, even at the point region 761 (no sampled screen) was computed at.
        assertEquals("r764", zoneAt(33.5102, 36.29128))
        assertEquals("r764", zoneAt(33.50795, 36.28304))
        // Live towns: Mayadin and Quneitra run aladhan's API, Khanasir too (its table, 786, is a manual pick).
        assertEquals("live173480", zoneAt(35.01982, 40.45154))
        assertEquals("live173334", zoneAt(33.12595, 35.82461))
        assertEquals("live168351", zoneAt(35.78159, 37.49919))
        assertEquals(1, syria.byId["r786"]?.mappingTier)
    }

    @Test
    fun theAssetFormatRoundTrips() {
        val zones = AuthorityZones(
            "om_mara", "om_mara", "OM", MappingKind.NEAREST, byAdmin1 = false, namedFirst = true, terrainClassKm = null, fit = null, source = "test",
            zones = listOf(OfficialZone("om_mara", "0", "مسقط", LatLngE5.of(23.588, 58.3829), offsets = mapOf(TimeEvent.FAJR to 1.0))),
        )
        assertEquals(zones.zones, OfficialZones.parse(OfficialZones.write(zones)).zones)
    }

    /** A zone for the country of an asset, for the sanity run only (the engine reads clock rules from it). */
    private fun zoneIdOf(country: String): String = ZONES[country] ?: "UTC"

    private companion object {
        val ZONES = mapOf(
            "SA" to "Asia/Riyadh", "EG" to "Africa/Cairo", "MA" to "Africa/Casablanca", "DZ" to "Africa/Algiers", "LY" to "Africa/Tripoli",
            "MR" to "Africa/Nouakchott", "JO" to "Asia/Amman", "PS" to "Asia/Hebron", "SY" to "Asia/Damascus", "LB" to "Asia/Beirut",
            "IQ" to "Asia/Baghdad", "KW" to "Asia/Kuwait", "QA" to "Asia/Qatar", "BH" to "Asia/Bahrain", "AE" to "Asia/Dubai",
            "OM" to "Asia/Muscat", "YE" to "Asia/Aden", "SD" to "Africa/Khartoum", "SO" to "Africa/Mogadishu", "DJ" to "Africa/Djibouti",
            "KM" to "Indian/Comoro", "FR" to "Europe/Paris", "RE" to "Indian/Reunion", "YT" to "Indian/Mayotte", "MQ" to "America/Martinique",
            "GF" to "America/Cayenne", "AT" to "Europe/Vienna", "LU" to "Europe/Luxembourg", "BA" to "Europe/Sarajevo", "HR" to "Europe/Zagreb",
            "SI" to "Europe/Ljubljana", "RS" to "Europe/Belgrade", "ME" to "Europe/Podgorica", "AL" to "Europe/Tirane", "XK" to "Europe/Belgrade",
            "MK" to "Europe/Skopje", "BG" to "Europe/Sofia", "TR" to "Europe/Istanbul", "NO" to "Europe/Oslo", "RU" to "Europe/Moscow",
            "IE" to "Europe/Dublin", "ES" to "Europe/Madrid",
        )
    }
}
