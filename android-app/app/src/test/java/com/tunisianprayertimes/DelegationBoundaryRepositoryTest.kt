package com.tunisianprayertimes

import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class DelegationBoundaryRepositoryTest {

    @Test
    fun `closed rings do not treat outside points as on the boundary`() {
        val ring = squareRing(minLng = 0.0, minLat = 0.0, maxLng = 10.0, maxLat = 10.0)

        assertFalse(ringContainsPoint(ring, lat = -1.0, lng = -1.0))
    }

    @Test
    fun `polygon holes are excluded from containment`() {
        val polygon = BoundaryPolygon(
            outerRing = squareRing(minLng = 0.0, minLat = 0.0, maxLng = 10.0, maxLat = 10.0),
            holes = listOf(squareRing(minLng = 2.0, minLat = 2.0, maxLng = 8.0, maxLat = 8.0))
        )

        assertTrue(polygon.contains(lat = 1.0, lng = 1.0))
        assertFalse(polygon.contains(lat = 5.0, lng = 5.0))
        assertTrue(polygon.contains(lat = 0.0, lng = 5.0))
    }

    @Test
    fun `boundary match chooses nearest candidate delegation inside the polygon`() {
        val boundary = DelegationBoundary(
            candidateDelegationIds = listOf(447, 454),
            minLng = 10.17,
            minLat = 36.72,
            maxLng = 10.21,
            maxLat = 36.75,
            polygons = listOf(
                BoundaryPolygon(
                    outerRing = squareRing(
                        minLng = 10.17,
                        minLat = 36.72,
                        maxLng = 10.21,
                        maxLat = 36.75
                    ),
                    holes = emptyList()
                )
            )
        )
        val delegations = listOf(
            Delegation(id = 447, nomFr = "El Mourouj", nomAr = "المروج", nomEn = "El Mourouj", lat = 36.7333, lng = 10.1833),
            Delegation(id = 454, nomFr = "Medina Jedida", nomAr = "المدينة الجديدة", nomEn = "Medina Jedida", lat = 36.7250, lng = 10.2050)
        )

        val resolved = resolveDelegationFromBoundaries(
            boundaries = listOf(boundary),
            delegations = delegations,
            lat = 36.7340,
            lng = 10.1840
        )

        assertEquals(447, resolved?.id)
    }

    @Test
    fun `coordinates 36 43 00 1N 10 12 09 1E resolve to expected delegation`() {
        val lat = 36 + 43.0 / 60.0 + 0.1 / 3600.0
        val lng = 10 + 12.0 / 60.0 + 9.1 / 3600.0

        val resolved = resolveDelegationFromBoundaries(
            boundaries = loadBoundariesFromAssets(),
            delegations = loadDelegationsFromAssets(),
            lat = lat,
            lng = lng
        )

        val delegation = requireNotNull(resolved)
        assertEquals(447, delegation.id)
        assertEquals("المروج", delegation.nomAr)
        assertGoogleMapsContainsDelegation(lat, lng, delegation)
    }

    @Test
    fun `coordinates 36 46 06 3N 10 13 28 9E resolve to expected delegation`() {
        val lat = 36 + 46.0 / 60.0 + 6.3 / 3600.0
        val lng = 10 + 13.0 / 60.0 + 28.9 / 3600.0

        val resolved = resolveDelegationFromBoundaries(
            boundaries = loadBoundariesFromAssets(),
            delegations = loadDelegationsFromAssets(),
            lat = lat,
            lng = lng
        )

        val delegation = requireNotNull(resolved)
        assertEquals(448, delegation.id)
        assertEquals("مقرين", delegation.nomAr)
        assertGoogleMapsContainsDelegation(lat, lng, delegation)
    }

    @Test
    fun `coordinates 36 46 08 0N 10 10 48 1E resolve to expected delegation`() {
        val lat = 36 + 46.0 / 60.0 + 8.0 / 3600.0
        val lng = 10 + 10.0 / 60.0 + 48.1 / 3600.0

        val resolved = resolveDelegationFromBoundaries(
            boundaries = loadBoundariesFromAssets(),
            delegations = loadDelegationsFromAssets(),
            lat = lat,
            lng = lng
        )

        val delegation = requireNotNull(resolved)
        assertEquals(399, delegation.id)
        assertEquals("الوردية", delegation.nomAr)
        assertGoogleMapsContainsDelegation(lat, lng, delegation)
    }

    @Test
    fun `coordinates 36 49 45 6N 10 08 44 9E resolve to expected delegation`() {
        val lat = 36 + 49.0 / 60.0 + 45.6 / 3600.0
        val lng = 10 + 8.0 / 60.0 + 44.9 / 3600.0

        val resolved = resolveDelegationFromBoundaries(
            boundaries = loadBoundariesFromAssets(),
            delegations = loadDelegationsFromAssets(),
            lat = lat,
            lng = lng
        )

        val delegation = requireNotNull(resolved)
        assertEquals(390, delegation.id)
        assertEquals("المنزه", delegation.nomAr)
        assertGoogleMapsContainsDelegation(lat, lng, delegation)
    }

    @Test
    fun `coordinates 36 45 33 8N 10 11 21 5E resolve to expected delegation`() {
        val lat = 36 + 45.0 / 60.0 + 33.8 / 3600.0
        val lng = 10 + 11.0 / 60.0 + 21.5 / 3600.0

        val resolved = resolveDelegationFromBoundaries(
            boundaries = loadBoundariesFromAssets(),
            delegations = loadDelegationsFromAssets(),
            lat = lat,
            lng = lng
        )

        val delegation = requireNotNull(resolved)
        assertEquals(400, delegation.id)
        assertEquals("كبارية", delegation.nomAr)
        assertGoogleMapsContainsDelegation(lat, lng, delegation)
    }

    @Test
    fun `coordinates 36 44 51 8N 10 11 03 9E resolve to expected delegation`() {
        val lat = 36 + 44.0 / 60.0 + 51.8 / 3600.0
        val lng = 10 + 11.0 / 60.0 + 3.9 / 3600.0

        val resolved = resolveDelegationFromBoundaries(
            boundaries = loadBoundariesFromAssets(),
            delegations = loadDelegationsFromAssets(),
            lat = lat,
            lng = lng
        )

        val delegation = requireNotNull(resolved)
        assertEquals(400, delegation.id)
        assertEquals("كبارية", delegation.nomAr)
        assertGoogleMapsContainsDelegation(lat, lng, delegation)
    }

    @Test
    fun `coordinates 33 14 36 9N 10 12 04 3E resolve to expected delegation`() {
        val lat = 33 + 14.0 / 60.0 + 36.9 / 3600.0
        val lng = 10 + 12.0 / 60.0 + 4.3 / 3600.0

        val resolved = resolveDelegationFromBoundaries(
            boundaries = loadBoundariesFromAssets(),
            delegations = loadDelegationsFromAssets(),
            lat = lat,
            lng = lng
        )

        val delegation = requireNotNull(resolved)
        assertEquals(489, delegation.id)
        assertEquals("بني خداش", delegation.nomAr)
        assertGoogleMapsContainsDelegation(lat, lng, delegation)
    }

    @Test
    fun `coordinates 34 18 32 0N 8 24 02 8E resolve to expected delegation`() {
        val lat = 34 + 18.0 / 60.0 + 32.0 / 3600.0
        val lng = 8 + 24.0 / 60.0 + 2.8 / 3600.0

        val resolved = resolveDelegationFromBoundaries(
            boundaries = loadBoundariesFromAssets(),
            delegations = loadDelegationsFromAssets(),
            lat = lat,
            lng = lng
        )

        val delegation = requireNotNull(resolved)
        assertEquals(606, delegation.id)
        assertEquals("المتلوي", delegation.nomAr)
        assertGoogleMapsContainsDelegation(lat, lng, delegation)
    }

    @Test
    fun `coordinates 34 00 22 5N 8 07 08 2E resolve to expected delegation`() {
        val lat = 34 + 0.0 / 60.0 + 22.5 / 3600.0
        val lng = 8 + 7.0 / 60.0 + 8.2 / 3600.0

        val resolved = resolveDelegationFromBoundaries(
            boundaries = loadBoundariesFromAssets(),
            delegations = loadDelegationsFromAssets(),
            lat = lat,
            lng = lng
        )

        val delegation = requireNotNull(resolved)
        assertEquals(625, delegation.id)
        assertEquals("توزر", delegation.nomAr)
        assertGoogleMapsContainsDelegation(lat, lng, delegation)
    }

    @Test
    fun `coordinates 36 49 45 3N 10 08 35 5E resolve to expected delegation`() {
        val lat = 36 + 49.0 / 60.0 + 45.3 / 3600.0
        val lng = 10 + 8.0 / 60.0 + 35.5 / 3600.0

        val resolved = resolveDelegationFromBoundaries(
            boundaries = loadBoundariesFromAssets(),
            delegations = loadDelegationsFromAssets(),
            lat = lat,
            lng = lng
        )

        val delegation = requireNotNull(resolved)
        assertEquals(392, delegation.id)
        assertEquals("العمران الأعلى", delegation.nomAr)
        assertGoogleMapsContainsDelegation(lat, lng, delegation)
    }

    private fun squareRing(
        minLng: Double,
        minLat: Double,
        maxLng: Double,
        maxLat: Double
    ): List<BoundaryPoint> {
        return normalizeRing(
            listOf(
                BoundaryPoint(lng = minLng, lat = minLat),
                BoundaryPoint(lng = maxLng, lat = minLat),
                BoundaryPoint(lng = maxLng, lat = maxLat),
                BoundaryPoint(lng = minLng, lat = maxLat),
                BoundaryPoint(lng = minLng, lat = minLat)
            )
        )
    }

    private fun loadDelegationsFromAssets(): List<Delegation> {
        val root = JSONObject(findAssetFile("app/src/main/assets/gouvernorats.json").readText())
        val gouvernorats = root.getJSONArray("gouvernorats")
        val result = mutableListOf<Delegation>()
        for (gouvernoratIndex in 0 until gouvernorats.length()) {
            val gouvernorat = gouvernorats.getJSONObject(gouvernoratIndex)
            val delegations = gouvernorat.getJSONArray("delegations")
            for (delegationIndex in 0 until delegations.length()) {
                val delegation = delegations.getJSONObject(delegationIndex)
                result += Delegation(
                    id = delegation.getInt("id"),
                    nomFr = delegation.getString("nomFr"),
                    nomAr = delegation.getString("nomAr"),
                    nomEn = delegation.getString("nomEn"),
                    lat = delegation.optDouble("lat", 0.0),
                    lng = delegation.optDouble("lng", 0.0)
                )
            }
        }
        return result
    }

    private fun loadBoundariesFromAssets(): List<DelegationBoundary> {
        val root = JSONObject(findAssetFile("app/src/main/assets/delegation_boundaries.json").readText())
        val boundaries = root.getJSONArray("boundaries")
        return List(boundaries.length()) { index ->
            parseBoundary(boundaries.getJSONObject(index))
        }
    }

    private fun parseBoundary(json: JSONObject): DelegationBoundary {
        val bbox = json.getJSONArray("bbox")
        val geometry = json.getJSONObject("geometry")
        return DelegationBoundary(
            candidateDelegationIds = List(json.getJSONArray("candidateDelegationIds").length()) { index ->
                json.getJSONArray("candidateDelegationIds").getInt(index)
            },
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

            else -> error("Unsupported geometry type ${geometry.getString("type")}")
        }
    }

    private fun parsePolygon(rings: JSONArray): BoundaryPolygon {
        val parsedRings = List(rings.length()) { index ->
            normalizeRing(parseRing(rings.getJSONArray(index)))
        }.filter { ring -> ring.size >= 3 }

        return BoundaryPolygon(
            outerRing = parsedRings.first(),
            holes = parsedRings.drop(1)
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

    private fun findAssetFile(relativePath: String): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        var directory = File(workingDirectory).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.exists()) {
                return candidate
            }
            directory = directory.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath from $workingDirectory")
    }

    private fun assertGoogleMapsContainsDelegation(lat: Double, lng: Double, delegation: Delegation) {
        val apiKey = System.getenv("GOOGLE_MAPS_API_KEY")
            ?: System.getProperty("googleMapsApiKey")
            ?: return

        val params = buildString {
            append("latlng=")
            append(URLEncoder.encode("$lat,$lng", StandardCharsets.UTF_8.name()))
            append("&language=ar")
            append("&key=")
            append(URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name()))
        }
        val payload = JSONObject(
            URL("https://maps.googleapis.com/maps/api/geocode/json?$params").readText()
        )
        assertEquals("OK", payload.getString("status"))

        val googleNames = mutableSetOf<String>()
        val results = payload.getJSONArray("results")
        for (resultIndex in 0 until results.length()) {
            val result = results.getJSONObject(resultIndex)
            val components = result.getJSONArray("address_components")
            for (componentIndex in 0 until components.length()) {
                val component = components.getJSONObject(componentIndex)
                val types = component.getJSONArray("types").toStringList()
                if ("administrative_area_level_2" in types || "locality" in types) {
                    googleNames += normalizeDelegationName(component.getString("long_name"))
                }
            }
        }

        val expectedNames = setOf(
            normalizeDelegationName(delegation.nomAr),
            normalizeDelegationName(delegation.nomFr),
            normalizeDelegationName(delegation.nomEn)
        )

        assertTrue(
            "Google Maps did not report ${delegation.nomAr}/${delegation.nomFr}. Found: $googleNames",
            googleNames.any { it in expectedNames }
        )
    }

    private fun normalizeDelegationName(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\u202c", "")
            .replace("\u202b", "")
            .replace("\u200f", "")
            .replace("\u200e", "")
            .replace("\u061c", "")
            .replace("\ufeff", "")
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
            .replace("ى", "ي")
            .replace("ؤ", "و")
            .replace("ئ", "ي")
            .replace("ة", "ه")
            .replace("ی", "ي")
            .replace("ک", "ك")
            .replace(Regex("\\p{M}+"), "")

        return normalized
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), "")
    }

    private fun JSONArray.toStringList(): List<String> {
        return List(length()) { index -> getString(index) }
    }
}