package com.tunisianprayertimes.mosque

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MosqueRepositoryTest {
    @Test
    fun `bundled nationwide catalog loads every record but counts only approved automatic footprints`() {
        val root = JSONObject(File("src/main/assets/${MosqueRepository.ASSET_PATH}").readText())
        val catalog = parseMosqueCatalog(root)
        val features = root.getJSONArray("features")
        val eligibleCount = (0 until features.length()).count {
            val properties = features.getJSONObject(it).getJSONObject("properties")
            properties.getBoolean("polygonEligible") && properties.getBoolean("automaticSilencingEligible")
        }
        assertEquals(features.length(), catalog.mosques.size)
        assertEquals(eligibleCount, catalog.eligiblePolygonCount)
        assertEquals(eligibleCount, catalog.mosques.count { it.automaticSilencingEligible })
        assertTrue(catalog.mosques.isNotEmpty())
        assertTrue(catalog.eligiblePolygonCount > 0)
        assertTrue(catalog.eligiblePolygonCount < catalog.mosques.size)
        assertEquals("ODbL-1.0", root.getJSONObject("source").getString("license"))
    }

    @Test
    fun `missing or false automatic approval leaves structural polygons inventory only`() {
        val missing = feature("missing-approval", polygon(square())).apply {
            getJSONObject("properties").remove("automaticSilencingEligible")
            getJSONObject("properties").remove("boundaryCoverageStatus")
        }
        val catalog = catalog(missing, feature("partial", polygon(square()), automaticEligible = false,
            coverageStatus = "needs_expansion"))

        assertTrue(catalog.mosques.all { it.polygonEligible })
        assertTrue(catalog.mosques.none { it.automaticSilencingEligible })
        assertEquals(listOf("coverage_unreviewed", "needs_expansion"), catalog.mosques.map { it.boundaryCoverageStatus })
        assertEquals(0, catalog.eligiblePolygonCount)
        assertEquals(MosqueDetectionResult.NoPolygonMatch, catalog.findCandidates(fix()))
    }

    @Test
    fun `automatic approval must be a JSON Boolean and cannot be inferred from coverage status`() {
        for (flag in listOf("true", 1, JSONObject.NULL)) {
            val feature = feature("malformed-approval", polygon(square())).apply {
                getJSONObject("properties").put("automaticSilencingEligible", flag)
            }
            val catalog = catalog(feature)
            assertEquals(0, catalog.eligiblePolygonCount)
            assertEquals(MosqueDetectionResult.NoPolygonMatch, catalog.findCandidates(fix()))
        }
    }

    @Test
    fun `only explicitly approved structural polygons become candidates`() {
        val catalog = catalog(
            feature("approved", polygon(square())),
            feature("unreviewed", polygon(square()), automaticEligible = false),
            feature("partial", polygon(square()), automaticEligible = false, coverageStatus = "needs_expansion"),
        )
        assertEquals(3, catalog.mosques.size)
        assertEquals(1, catalog.eligiblePolygonCount)
        assertMatches(catalog, fix(), "approved")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `automatic approval without structural polygon eligibility is rejected`() {
        catalog(feature("invalid-approval", polygon(square()), eligible = false, automaticEligible = true))
    }

    @Test
    fun `interior fix matches real polygon but not an adjacent street`() {
        val catalog = catalog(feature("mosque", polygon(square())))
        assertMatches(catalog, fix(), "mosque")
        assertEquals(MosqueDetectionResult.NoPolygonMatch, catalog.findCandidates(fix(longitude = 10.0011)))
    }

    @Test
    fun `boundary and overlapping accuracy circles do not match`() {
        val catalog = catalog(feature("mosque", polygon(square())))
        assertEquals(MosqueDetectionResult.NoPolygonMatch, catalog.findCandidates(fix(latitude = 36.0)))
        assertEquals(MosqueDetectionResult.NoPolygonMatch, catalog.findCandidates(fix(latitude = 36.00004)))
        assertEquals(MosqueDetectionResult.NoPolygonMatch, catalog.findCandidates(fix(latitude = 36.00001, accuracy = 1.0)))
        assertMatches(catalog, fix(latitude = 36.0001), "mosque")
    }

    @Test
    fun `hole interior and accuracy circles touching a hole are excluded`() {
        val catalog = catalog(feature("courtyard", polygon(square(), square(10.0004, 36.0004, 10.0006, 36.0006))))
        assertEquals(MosqueDetectionResult.NoPolygonMatch, catalog.findCandidates(fix()))
        assertEquals(MosqueDetectionResult.NoPolygonMatch, catalog.findCandidates(fix(longitude = 10.0004)))
        assertEquals(MosqueDetectionResult.NoPolygonMatch, catalog.findCandidates(fix(longitude = 10.00035)))
        assertMatches(catalog, fix(longitude = 10.0002), "courtyard")
    }

    @Test
    fun `multipolygon islands match independently and their gap stays outside`() {
        val geometry = JSONObject().put("type", "MultiPolygon").put("coordinates", JSONArray()
            .put(JSONArray().put(square()))
            .put(JSONArray().put(square(10.002, 36.0, 10.003, 36.001))))
        val catalog = catalog(feature("islands", geometry))
        assertMatches(catalog, fix(), "islands")
        assertMatches(catalog, fix(longitude = 10.0025), "islands")
        assertEquals(MosqueDetectionResult.NoPolygonMatch, catalog.findCandidates(fix(longitude = 10.0015)))
    }

    @Test
    fun `concave outline excludes its notch without excluding the building`() {
        val ring = JSONArray("[[10,36],[10.001,36],[10.001,36.001],[10.0006,36.001],[10.0006,36.0004],[10.0004,36.0004],[10.0004,36.001],[10,36.001],[10,36]]")
        val catalog = catalog(feature("concave", polygon(ring)))
        assertEquals(MosqueDetectionResult.NoPolygonMatch, catalog.findCandidates(fix(latitude = 36.0008)))
        assertMatches(catalog, fix(latitude = 36.0002), "concave")
    }

    @Test
    fun `points missing geometries and ineligible polygons remain inventory only`() {
        val catalog = catalog(
            feature("point", JSONObject().put("type", "Point").put("coordinates", JSONArray("[10.0005,36.0005]")), false),
            feature("missing", null, false),
            feature("inactive", polygon(square()), false),
        )
        assertEquals(3, catalog.mosques.size)
        assertEquals(0, catalog.eligiblePolygonCount)
        assertEquals(MosqueDetectionResult.NoPolygonMatch, catalog.findCandidates(fix()))
    }

    @Test
    fun `invalid and nonarea review geometries do not disable eligible mosques`() {
        val catalog = catalog(
            feature("valid", polygon(square())),
            feature("unclosed", JSONObject().put("type", "LineString")
                .put("coordinates", JSONArray("[[10,36],[10.001,36.001]]")), false),
            feature("invalid", polygon(JSONArray("[[10,36],[10.001,36],[10,36.001]]")), false),
            feature("empty", polygon(), false),
        )
        assertEquals(4, catalog.mosques.size)
        assertEquals(1, catalog.eligiblePolygonCount)
        assertMatches(catalog, fix(), "valid")
    }

    @Test
    fun `all overlapping eligible candidates are returned`() {
        val catalog = catalog(feature("first", polygon(square())), feature("second", polygon(square())))
        assertMatches(catalog, fix(), "first", "second")
    }

    @Test
    fun `freshness requires known nonnegative monotonic age within thirty seconds`() {
        val catalog = catalog(feature("mosque", polygon(square())))
        for (age in listOf(null, -1L, 30_001L, Long.MAX_VALUE)) {
            assertEquals(MosqueDetectionResult.UnusableFix(MosqueFixRejection.UNKNOWN_OR_STALE_AGE),
                catalog.findCandidates(fix().copy(ageMillis = age)))
        }
        assertMatches(catalog, fix().copy(ageMillis = 30_000L), "mosque")
    }

    @Test
    fun `missing invalid and coarse accuracy cannot match`() {
        val catalog = catalog(feature("mosque", polygon(square())))
        for (accuracy in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 30.01)) {
            assertEquals(MosqueDetectionResult.UnusableFix(MosqueFixRejection.INSUFFICIENT_ACCURACY),
                catalog.findCandidates(fix().copy(accuracyMeters = accuracy)))
        }
        assertMatches(catalog, fix(accuracy = 30.0), "mosque")
    }

    @Test
    fun `mock and invalid coordinate fixes are rejected`() {
        val catalog = catalog(feature("mosque", polygon(square())))
        assertEquals(MosqueDetectionResult.UnusableFix(MosqueFixRejection.MOCK_LOCATION),
            catalog.findCandidates(fix().copy(isMock = true)))
        for ((latitude, longitude) in listOf(Double.NaN to 10.0, 36.0 to Double.POSITIVE_INFINITY, 91.0 to 10.0)) {
            assertEquals(MosqueDetectionResult.UnusableFix(MosqueFixRejection.INVALID_COORDINATES),
                catalog.findCandidates(fix(latitude, longitude)))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `point may never be marked polygon eligible`() {
        catalog(feature("point", JSONObject().put("type", "Point").put("coordinates", JSONArray("[10,36]"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `open polygon ring is rejected`() {
        catalog(feature("open", polygon(JSONArray("[[10,36],[10.001,36],[10.001,36.001],[10,36.001]]"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate ids are rejected`() {
        catalog(feature("duplicate", polygon(square())), feature("duplicate", polygon(square())))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported schema is rejected`() {
        parseMosqueCatalog(JSONObject().put("type", "FeatureCollection").put("schemaVersion", 2).put("features", JSONArray()))
    }

    private fun catalog(vararg features: JSONObject): MosqueCatalog = parseMosqueCatalog(
        JSONObject().put("type", "FeatureCollection").put("schemaVersion", 1)
            .put("features", JSONArray(features.toList()))
    )

    private fun feature(id: String, geometry: JSONObject?, eligible: Boolean = true,
                        automaticEligible: Boolean = eligible,
                        coverageStatus: String = if (automaticEligible) "complete_premises" else "coverage_unreviewed"): JSONObject =
        JSONObject().put("type", "Feature").put("id", id).put("geometry", geometry ?: JSONObject.NULL)
            .put("properties", JSONObject().put("name", id).put("nameAr", JSONObject.NULL)
                .put("status", if (eligible) "mapped_polygon" else "needs_review").put("polygonEligible", eligible)
                .put("automaticSilencingEligible", automaticEligible).put("boundaryCoverageStatus", coverageStatus))

    private fun polygon(vararg rings: JSONArray): JSONObject =
        JSONObject().put("type", "Polygon").put("coordinates", JSONArray(rings.toList()))

    private fun square(minLongitude: Double = 10.0, minLatitude: Double = 36.0,
                       maxLongitude: Double = 10.001, maxLatitude: Double = 36.001): JSONArray =
        JSONArray().put(JSONArray(listOf(minLongitude, minLatitude)))
            .put(JSONArray(listOf(maxLongitude, minLatitude)))
            .put(JSONArray(listOf(maxLongitude, maxLatitude)))
            .put(JSONArray(listOf(minLongitude, maxLatitude)))
            .put(JSONArray(listOf(minLongitude, minLatitude)))

    private fun fix(latitude: Double = 36.0005, longitude: Double = 10.0005, accuracy: Double = 5.0) =
        MosqueLocationFix(latitude, longitude, accuracy, ageMillis = 500L)

    private fun assertMatches(catalog: MosqueCatalog, fix: MosqueLocationFix, vararg ids: String) {
        val result = catalog.findCandidates(fix)
        assertTrue("Expected candidates, got $result", result is MosqueDetectionResult.Candidates)
        assertEquals(ids.toList(), (result as MosqueDetectionResult.Candidates).mosques.map { it.id })
    }
}
