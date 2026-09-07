package com.tunisianprayertimes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class NeighborhoodRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `GPS resolves specific neighborhood instead of its broader administrative area`() {
        val index = NeighborhoodRepository.load(context)
        assertEquals("المنزه 9 أ", index.find(36.8428, 10.1465)?.name)
        assertEquals("النصر 2", index.find(36.8640, 10.1647)?.name)
        assertEquals("بوشوشة", index.find(36.8090, 10.1400)?.name)
        assertEquals("municipal:bouarada:cite_14", index.find(36.3426978, 9.6190025)?.id)
    }

    @Test fun `search metadata loads without polygon records or the spatial index`() {
        val fixture = fixtureData()
        val expected = fixture.index().localities
        listOf("country", "cells", "gridSize", "coordinateScale", "conflicts").forEach { fixture.json.remove(it) }
        val rows = fixture.json.getJSONArray("features")
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            listOf("bbox", "offset", "length", "areaKm2").forEach { row.remove(it) }
        }
        assertEquals(expected, parseNeighborhoodLocalities(fixture.json))
    }

    @Test fun `GPS reuses the same cached names as the metadata-only catalog`() {
        val localities = NeighborhoodRepository.loadLocalities(context)
        assertSame(localities, NeighborhoodRepository.load(context).localities)
        assertSame(localities, NeighborhoodRepository.loadLocalities(context))
    }

    @Test fun `compiled polygon interiors resolve offline or explicitly abstain in a known conflict`() {
        val index = NeighborhoodRepository.load(context)
        val json = JSONObject(context.assets.open("neighborhoods.json").bufferedReader().use { it.readText() })
        val bytes = context.assets.open("neighborhoods.bin").use { it.readBytes() }
        val rows = json.getJSONArray("features")
        val byId = (0 until rows.length()).map { rows.getJSONObject(it) }.associateBy { it.getString("id") }
        val conflicts = json.optJSONArray("conflicts") ?: JSONArray()
        fun contains(id: String, lat: Double, lng: Double, includeBoundary: Boolean = true): Boolean {
            val row = byId.getValue(id)
            return containsPackedGeometry(bytes, row.getInt("offset"), row.getInt("length"),
                json.getDouble("coordinateScale"), lat, lng, includeBoundary)
        }
        val areas = index.localities.filter { it.hasBoundary }
        assertTrue(areas.size >= 2700)
        assertEquals(24, areas.map { it.governorateId }.toSet().size)
        areas.forEach { area ->
            val lat = area.lat!!
            val lng = area.lng!!
            assertTrue("Invalid interior point ${area.id}", contains(area.id, lat, lng))
            assertTrue("Interior point outside the country ${area.id}", index.isInsideCountry(lat, lng))
            val selected = index.find(lat, lng)
            if (selected != null) {
                assertTrue("Selected non-containing polygon ${selected.id}", contains(selected.id, lat, lng))
            } else {
                val disputedIds = (0 until conflicts.length()).flatMap { conflictIndex ->
                    val ids = conflicts.getJSONObject(conflictIndex).getJSONArray("ids")
                    val first = ids.getString(0)
                    val second = ids.getString(1)
                    if (contains(first, lat, lng, includeBoundary = false) &&
                        contains(second, lat, lng, includeBoundary = false)) listOf(first, second) else emptyList()
                }.toSet()
                val containingIds = byId.values.filter { row ->
                    if (!row.getBoolean("hasBoundary")) return@filter false
                    val bbox = row.getJSONArray("bbox")
                    lng >= bbox.getDouble(0) && lat >= bbox.getDouble(1) &&
                        lng <= bbox.getDouble(2) && lat <= bbox.getDouble(3) &&
                        contains(row.getString("id"), lat, lng)
                }.map { it.getString("id") }
                assertTrue("Unresolvable polygon without a known conflict ${area.id}", containingIds.isNotEmpty())
                assertTrue("An unambiguous containing polygon was lost at ${area.id}", disputedIds.containsAll(containingIds))
            }
        }
    }

    @Test fun `compiled conflict intersection samples never choose either disputed name`() {
        val index = NeighborhoodRepository.load(context)
        val json = JSONObject(context.assets.open("neighborhoods.json").bufferedReader().use { it.readText() })
        val bytes = context.assets.open("neighborhoods.bin").use { it.readBytes() }
        val rows = json.getJSONArray("features")
        val byId = (0 until rows.length()).map { rows.getJSONObject(it) }.associateBy { it.getString("id") }
        val conflicts = json.getJSONArray("conflicts")
        assertTrue(conflicts.length() >= 7)
        repeat(conflicts.length()) { i ->
            val conflict = conflicts.getJSONObject(i)
            val ids = conflict.getJSONArray("ids")
            val sample = conflict.getJSONObject("sample")
            val lat = sample.getDouble("lat")
            val lng = sample.getDouble("lng")
            val pair = listOf(ids.getString(0), ids.getString(1))
            pair.forEach { id ->
                val row = byId.getValue(id)
                assertTrue("Conflict sample $i must be strictly inside $id",
                    containsPackedGeometry(bytes, row.getInt("offset"), row.getInt("length"),
                        json.getDouble("coordinateScale"), lat, lng, includeBoundary = false))
            }
            assertFalse("Chose disputed name for $pair", index.find(lat, lng)?.id in pair)
        }
    }

    @Test fun `invalid coordinates and positions outside Tunisia cannot select a neighborhood`() {
        val index = NeighborhoodRepository.load(context)
        assertNull(index.find(48.8566, 2.3522))
        assertNull(index.find(Double.NaN, 10.0))
        assertNull(index.find(36.0, Double.POSITIVE_INFINITY))
        assertNull(index.find(100.0, 10.0))
    }

    @Test fun `smallest containing polygon wins and point-only names do not claim containment`() {
        val index = fixture()
        assertEquals("small", index.find(35.21, 9.21)?.id)
        assertEquals("big", index.find(35.8, 9.8)?.id)
    }

    @Test fun `holes fall back to enclosing area and disconnected polygon parts are supported`() {
        val index = fixture()
        assertEquals("big", index.find(35.3, 9.3)?.id) // inside the small polygon's hole
        assertEquals("islands", index.find(35.625, 9.625)?.id)
        assertEquals("islands", index.find(35.725, 9.725)?.id)
    }

    @Test fun `closed rings exclude outside points but include their boundary`() {
        val index = fixture()
        assertNull(index.find(35.1, 10.1))
        assertEquals("small", index.find(35.2, 9.2)?.id)
        assertNull(index.find(40.0, 9.2))
    }

    @Test fun `short diagonal edges do not turn nearby interior or exterior points into boundary points`() {
        // A roughly one-meter triangle with a diagonal edge. A fixed cross-product
        // tolerance incorrectly labels both nearby points as lying on that short edge.
        val points = listOf(9_000_000 to 35_000_000, 9_000_010 to 35_000_000,
            9_000_000 to 35_000_010, 9_000_000 to 35_000_000)
        val packed = ByteBuffer.allocate(12 + points.size * 8).putInt(1).putInt(1).putInt(points.size)
        points.forEach { (x, y) -> packed.putInt(x).putInt(y) }
        val bytes = packed.array()
        fun contains(lat: Double, lng: Double, includeBoundary: Boolean = true) =
            containsPackedGeometry(bytes, 0, bytes.size, 1e6, lat, lng, includeBoundary)

        assertTrue(contains(35.000004, 9.000004, includeBoundary = false))
        assertFalse(contains(35.000006, 9.000006))
        assertTrue(contains(35.000005, 9.000005))
        assertFalse(contains(35.000005, 9.000005, includeBoundary = false))
        // The duplicated first/last vertex produces a zero-length closing edge.
        assertTrue(contains(35.0, 9.0))
        assertFalse(contains(35.0, 9.0, includeBoundary = false))
    }

    @Test fun `conflicting names fall back to the containing administrative area`() {
        val index = conflictFixture().index()
        assertEquals("big", index.find(35.55, 9.65)?.id)
        // The same names remain usable outside their disputed intersection.
        assertEquals("left", index.find(35.55, 9.55)?.id)
        assertEquals("right", index.find(35.55, 9.85)?.id)
    }

    @Test fun `an independent finer neighborhood survives an enclosing conflict`() {
        val index = conflictFixture(includeFine = true).index()
        assertEquals("fine", index.find(35.55, 9.65)?.id)
        assertEquals("small", index.find(35.21, 9.21)?.id)
    }

    @Test fun `only ambiguous polygons produce no guessed locality`() {
        assertNull(conflictFixture(includeBig = false).index().find(35.55, 9.65))
    }

    @Test fun `being on a conflict polygon edge does not suppress an unambiguous interior`() {
        val index = conflictFixture().index()
        assertEquals("left", index.find(35.55, 9.6)?.id)
    }

    @Test fun `schema one without conflict metadata retains the existing nested lookup`() {
        val fixture = conflictFixture()
        fixture.json.remove("conflicts")
        val index = fixture.index()
        assertEquals("left", index.find(35.55, 9.65)?.id)
        assertEquals("small", index.find(35.21, 9.21)?.id)
    }

    @Test fun `known overlapping Jouhara and Megrine Chaker sectors cannot supply a guessed GPS name`() {
        val index = NeighborhoodRepository.load(context)
        // Independently computed interior of the overlap of OSM relations 7174591 / 7174628.
        assertTrue(index.isInsideCountry(36.777334, 10.220016121993286))
        assertNull(index.find(36.777334, 10.220016121993286))
    }

    @Test fun `conflict metadata rejects unknown point-only and duplicate feature references`() {
        listOf(listOf("left", "missing"), listOf("left", "point"), listOf("left", "left")).forEach { ids ->
            val fixture = conflictFixture()
            fixture.json.put("conflicts", JSONArray().put(conflict(ids)))
            assertThrows(IllegalArgumentException::class.java) { fixture.index() }
        }
    }

    @Test fun `malformed bounding boxes and impossible coordinates reject the catalog`() {
        listOf(listOf(10.0, 35.0, 9.0, 36.0), listOf(9.0, 35.0, 10.0, 91.0), listOf(9.0, 35.0, 10.0)).forEach { bbox ->
            val fixture = fixtureData()
            fixture.json.getJSONArray("features").getJSONObject(0).put("bbox", JSONArray(bbox))
            assertThrows(IllegalArgumentException::class.java) { fixture.index() }
        }
        val fixture = fixtureData()
        fixture.json.getJSONArray("features").getJSONObject(0).put("lat", 91.0)
        assertThrows(IllegalArgumentException::class.java) { fixture.index() }
    }

    @Test fun `invalid packed headers and malformed geometry fail before any GPS lookup`() {
        val wrongHeader = fixtureData()
        wrongHeader.bytes[0] = 'X'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) { wrongHeader.index() }

        val wrongVersion = fixtureData()
        ByteBuffer.wrap(wrongVersion.bytes).putInt(4, 2)
        assertThrows(IllegalArgumentException::class.java) { wrongVersion.index() }

        val truncatedRing = fixtureData()
        val firstOffset = truncatedRing.json.getJSONArray("features").getJSONObject(0).getInt("offset")
        ByteBuffer.wrap(truncatedRing.bytes).putInt(firstOffset + 8, Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { truncatedRing.index() }

        val trailingRecord = fixtureData()
        val first = trailingRecord.json.getJSONArray("features").getJSONObject(0)
        first.put("length", first.getInt("length") + 4)
        assertThrows(IllegalArgumentException::class.java) { trailingRecord.index() }
    }

    private data class Fixture(val json: JSONObject, val bytes: ByteArray) {
        fun index() = NeighborhoodIndex(json, bytes)
    }

    private fun conflict(ids: List<String>) = JSONObject().put("ids", JSONArray(ids)).put("reason", "Non-nested areas overlap")

    private fun fixture() = fixtureData().index()

    private fun conflictFixture(includeBig: Boolean = true, includeFine: Boolean = false) =
        fixtureData(includeConflicts = true, includeBig = includeBig, includeFine = includeFine)

    private fun fixtureData(includeConflicts: Boolean = false, includeBig: Boolean = true, includeFine: Boolean = false): Fixture {
        val bytes = ByteArrayOutputStream()
        val writer = DataOutputStream(bytes)
        writer.writeBytes("NPOL")
        writer.writeInt(1)
        fun ring(x: Double, y: Double, size: Double): List<Pair<Double, Double>> = listOf(
            x to y, (x+size) to y, (x+size) to (y+size), x to (y+size), x to y,
        )
        fun geometry(polygons: List<List<List<Pair<Double, Double>>>>): Pair<Int, Int> {
            val start = bytes.size()
            writer.writeInt(polygons.size)
            polygons.forEach { rings ->
                writer.writeInt(rings.size)
                rings.forEach { points ->
                    writer.writeInt(points.size)
                    points.forEach { (x, y) -> writer.writeInt((x*1e6).toInt()); writer.writeInt((y*1e6).toInt()) }
                }
            }
            return start to bytes.size()-start
        }
        fun record(id: String, area: Double, bbox: List<Double>, polygons: List<List<List<Pair<Double, Double>>>>): JSONObject {
            val (offset, length) = geometry(polygons)
            return JSONObject().put("id", id).put("name", id).put("parentName", "parent")
                .put("kind", "neighbourhood").put("aliases", JSONArray()).put("governorateId", 1)
                .put("delegationId", 1).put("lat", 35.0).put("lng", 9.0)
                .put("hasBoundary", true).put("areaKm2", area).put("bbox", JSONArray(bbox))
                .put("offset", offset).put("length", length)
        }
        val records = JSONArray()
        if (includeBig) {
            records.put(record("big", 100.0, listOf(9.0,35.0,10.0,36.0), listOf(listOf(ring(9.0,35.0,1.0))))
                .put("kind", "sector"))
        }
        records.put(record("small", 2.0, listOf(9.2,35.2,9.4,35.4), listOf(listOf(ring(9.2,35.2,0.2),ring(9.25,35.25,0.1)))))
        records.put(record("islands", 1.0, listOf(9.6,35.6,9.75,35.75), listOf(listOf(ring(9.6,35.6,0.05)),listOf(ring(9.7,35.7,0.05)))))
        if (includeConflicts) {
            records.put(record("left", 3.0, listOf(9.5,35.5,9.8,35.8), listOf(listOf(ring(9.5,35.5,0.3)))))
            records.put(record("right", 4.0, listOf(9.6,35.5,9.9,35.8), listOf(listOf(ring(9.6,35.5,0.3)))))
        }
        if (includeFine) {
            records.put(record("fine", 0.001, listOf(9.64,35.54,9.66,35.56), listOf(listOf(ring(9.64,35.54,0.02)))))
        }
        val polygonIds = (0 until records.length()).toList()
        records.put(JSONObject().put("id","point").put("name","point").put("parentName","parent")
                .put("kind","neighbourhood").put("aliases",JSONArray()).put("governorateId",1).put("delegationId",1)
                .put("lat",35.21).put("lng",9.21).put("hasBoundary",false))
        val (countryOffset, countryLength) = geometry(listOf(listOf(ring(8.0,34.0,4.0))))
        val cells = JSONObject().put("35:9", JSONArray(polygonIds))
        val root = JSONObject().put("schemaVersion",1).put("gridSize",1.0).put("coordinateScale",1e6)
            .put("country",JSONObject().put("offset",countryOffset).put("length",countryLength))
            .put("cells",cells).put("features",records)
        if (includeConflicts) root.put("conflicts", JSONArray().put(conflict(listOf("left", "right"))))
        return Fixture(root, bytes.toByteArray())
    }
}
