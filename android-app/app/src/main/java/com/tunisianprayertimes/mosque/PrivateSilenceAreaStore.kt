package com.tunisianprayertimes.mosque

import android.content.Context
import android.util.AtomicFile
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.SilenceArea
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedSilenceArea(val name: String, val geometry: SilenceArea)
data class SilenceAreaRead(val areas: List<SavedSilenceArea>, val available: Boolean, val rejected: Int = 0)

/** Private areas and unfinished drafts never enter cloud backup or device transfer. No raw GPS fixes. */
object PrivateSilenceAreaStore {
    private const val MAX_AREAS = 80
    private const val MAX_VERTICES = 256
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "mosque-silence-areas.json"))
    private fun draft(context: Context) = AtomicFile(File(context.noBackupFilesDir, "mosque-silence-draft.json"))

    fun read(context: Context): SilenceAreaRead = synchronized(SilenceCoordinator.serial) {
        val source = file(context)
        if (!source.baseFile.exists()) return@synchronized SilenceAreaRead(emptyList(), true)
        runCatching {
            require(source.baseFile.length() <= 2_000_000)
            val root = JSONObject(source.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            require(root.getInt("version") == 1)
            val array = root.getJSONArray("areas")
            require(array.length() <= MAX_AREAS)
            var rejected = 0
            val ids = hashSetOf<String>()
            val areas = (0 until array.length()).mapNotNull { i ->
                runCatching {
                    val item = array.getJSONObject(i)
                    val id = item.getString("id")
                    require(id.length in 1..100 && ids.add(id))
                    val polygons = item.getJSONArray("polygons")
                    require(polygons.length() in 1..16)
                    val shapes = (0 until polygons.length()).map { p ->
                        val rings = polygons.getJSONArray(p)
                        require(rings.length() in 1..16)
                        val parsed = (0 until rings.length()).map { r -> parseRing(rings.getJSONArray(r)) }
                        SilencePolygon(parsed.first(), parsed.drop(1))
                    }
                    val name = item.getString("name").trim()
                    require(name.isNotEmpty() && name.length <= 100)
                    SavedSilenceArea(name, SilenceArea(id, item.getLong("revision"), shapes, item.optBoolean("enabled", false)))
                }.getOrElse { rejected++; null }
            }
            SilenceAreaRead(areas, true, rejected)
        }.getOrElse { SilenceAreaRead(emptyList(), false) }
    }

    fun save(context: Context, name: String, vertices: List<GeoPoint>, id: String? = null, enabled: Boolean = true) {
        require(name.trim().length in 1..100 && vertices.size in 3..MAX_VERTICES)
        // Validate before touching the existing committed store. The editor supports one simple exterior.
        val polygon = SilencePolygon(vertices)
        synchronized(SilenceCoordinator.serial) {
            val current = read(context)
            check(current.available) { "STORE_UNAVAILABLE" }
            val previous = current.areas.firstOrNull { it.geometry.id == id }
            require(previous == null || previous.geometry.polygons.size == 1 && previous.geometry.polygons[0].holes.isEmpty())
            require(previous != null || current.areas.size < MAX_AREAS)
            val area = SavedSilenceArea(name.trim(), SilenceArea(id ?: UUID.randomUUID().toString(),
                (previous?.geometry?.revision ?: 0) + 1, listOf(polygon), enabled))
            write(context, current.areas.filterNot { it.geometry.id == area.geometry.id } + area)
            clearDraft(context)
        }
        MosqueSilenceMonitor.environmentChanged(context)
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        mutate(context) { all -> all.map { if (it.geometry.id == id) it.copy(geometry = it.geometry.copy(enabled = enabled)) else it } }
    }
    fun delete(context: Context, id: String) = mutate(context) { all -> all.filterNot { it.geometry.id == id } }
    private fun mutate(context: Context, transform: (List<SavedSilenceArea>) -> List<SavedSilenceArea>) {
        synchronized(SilenceCoordinator.serial) {
            val current = read(context); check(current.available)
            write(context, transform(current.areas))
        }
        MosqueSilenceMonitor.environmentChanged(context)
    }
    private fun write(context: Context, areas: List<SavedSilenceArea>) {
        val json = JSONObject().put("version", 1).put("areas", JSONArray().apply {
            areas.forEach { saved -> put(JSONObject().put("id", saved.geometry.id).put("name", saved.name)
                .put("revision", saved.geometry.revision).put("enabled", saved.geometry.enabled)
                .put("polygons", JSONArray().apply { saved.geometry.polygons.forEach { polygon ->
                    put(JSONArray().apply { (listOf(polygon.exterior) + polygon.holes).forEach { put(ringJson(it)) } })
                } })) }
        })
        atomicWrite(file(context), json.toString())
    }

    fun saveDraft(context: Context, name: String, coordinates: String, id: String?) {
        if (coordinates.length > 32_000) return
        synchronized(SilenceCoordinator.serial) {
            atomicWrite(draft(context), JSONObject().put("name", name.take(100)).put("coordinates", coordinates)
                .put("id", id ?: JSONObject.NULL).toString())
        }
    }
    fun readDraft(context: Context): JSONObject? = synchronized(SilenceCoordinator.serial) {
        runCatching { require(draft(context).baseFile.length() <= 40_000)
            JSONObject(draft(context).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }) }.getOrNull()
    }
    fun clearDraft(context: Context) = synchronized(SilenceCoordinator.serial) { draft(context).delete() }

    /** Deliberate export only; names and exact private boundaries are visible in the preview first. */
    fun exportGeoJson(context: Context): String {
        val read = read(context); check(read.available)
        return JSONObject().put("type", "FeatureCollection").put("features", JSONArray().apply {
            read.areas.forEach { saved -> put(JSONObject().put("type", "Feature").put("id", saved.geometry.id)
                .put("properties", JSONObject().put("name", saved.name).put("enabled", saved.geometry.enabled))
                .put("geometry", JSONObject().put("type", "MultiPolygon").put("coordinates", JSONArray().apply {
                    saved.geometry.polygons.forEach { polygon -> put(JSONArray().apply {
                        (listOf(polygon.exterior) + polygon.holes).forEach { points ->
                            val closed = if (points.first() == points.last()) points else points + points.first()
                            put(ringJson(closed))
                        }
                    }) }
                }))) }
        }).toString(2)
    }
    private fun ringJson(points: List<GeoPoint>) = JSONArray().apply {
        points.forEach { put(JSONArray().put(it.longitude).put(it.latitude)) }
    }
    private fun parseRing(array: JSONArray): List<GeoPoint> {
        require(array.length() in 3..MAX_VERTICES)
        return (0 until array.length()).map { i -> array.getJSONArray(i).let { GeoPoint(it.getDouble(1), it.getDouble(0)) } }
    }
    private fun atomicWrite(file: AtomicFile, value: String) {
        val stream = file.startWrite()
        try { stream.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}
