package com.tunisianprayertimes.tv.remote

import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.tv.data.MediaKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardTest {

    private val token = "abcdefghjk"

    private class FakeBackend : DashboardBackend {
        var applied: String? = null
        val images = mutableMapOf<String, ByteArray>()
        var undo: String? = null
        override fun stateJson() = """{"ok":true}"""
        override fun placesJson() = "[]"
        override fun preview(text: String) = MosqueSettingsFile.parse(text, MosqueSchedule.DEFAULT)
        override fun apply(text: String): Boolean { applied = text; return true }
        override fun describe(result: ParseResult) = when (result) {
            is ParseResult.Success -> result.changes.map { "${it.prayer}:${it.after}" }
            is ParseResult.Failure -> result.errors.map { it.path }
        }
        override fun undoText() = undo
        override fun image(kind: MediaKind, name: String) = images["${kind.folder}/$name"]
        override fun addImage(kind: MediaKind, name: String, bytes: ByteArray): String? { images["${kind.folder}/$name"] = bytes; return null }
        override fun deleteImage(kind: MediaKind, name: String) = images.remove("${kind.folder}/$name") != null
        override fun update() = "لا يوجد تحديث"
        override fun asset(name: String) = if (name == "index.html" || name == "views/prayers.js") name.toByteArray() else null
    }

    private val backend = FakeBackend()
    private val routes = DashboardRoutes(token, backend)

    private fun call(method: String, path: String, body: String = "", query: Map<String, String> = mapOf("t" to token)) =
        routes.handle(HttpRequest(method, path, query, body.toByteArray()))

    private fun json(response: HttpResponse) = Json.parseToJsonElement(response.text) as JsonObject

    @Test
    fun thePageIsPublicButOnlyItsOwnFiles() {
        assertEquals("index.html", call("GET", "/", query = emptyMap()).text)
        assertEquals(200, call("GET", "/views/prayers.js", query = emptyMap()).status)
        assertTrue(call("GET", "/views/prayers.js", query = emptyMap()).contentType.startsWith("text/javascript"))
        for (path in listOf("/../AndroidManifest.xml", "/views/../../prefs.xml", "/secret.json", "/views/Evil.js", "/api")) {
            assertEquals(path, 404, call("GET", path, query = emptyMap()).status)
        }
    }

    @Test
    fun theApiNeedsTheSessionTokenAndClosesAfterTooManyWrongOnes() {
        assertEquals(403, call("GET", "/api/state", query = emptyMap()).status)
        assertEquals(200, call("GET", "/api/state").status)
        // The call without a token was the first wrong one.
        repeat(DashboardRoutes.MAX_BAD_TOKENS - 2) { call("GET", "/api/state", query = mapOf("t" to "wrong")) }
        assertEquals(200, call("GET", "/api/state").status)
        call("GET", "/api/state", query = mapOf("t" to "wrong"))
        assertEquals("closed even for the right token", 403, call("GET", "/api/state").status)
    }

    @Test
    fun changesArePreviewedThenApplied() {
        val file = """{ "prayers": { "isha": { "duration": 12 } } }"""
        val preview = json(call("POST", "/api/preview", file))
        assertTrue(preview["ok"]!!.jsonPrimitive.boolean)
        assertEquals("ISHA:12", preview["lines"]!!.jsonArray.single().jsonPrimitive.content)
        assertNull("preview changes nothing", backend.applied)
        assertTrue(json(call("POST", "/api/apply", file))["ok"]!!.jsonPrimitive.boolean)
        assertEquals(file, backend.applied)
        // A file with a mistake is refused, with where the mistake is.
        val bad = json(call("POST", "/api/apply", """{ "prayers": { "isha": { "iqamah": "25:00" } } }"""))
        assertFalse(bad["ok"]!!.jsonPrimitive.boolean)
        assertEquals("prayers.isha.iqamah", bad["lines"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun imagesAreUploadedReadAndDeletedByPlainNamesOnly() {
        val bytes = byteArrayOf(1, 2, 3)
        assertTrue(json(routes.handle(HttpRequest("POST", "/api/image", mapOf("t" to token, "kind" to "backgrounds", "name" to "a-1.jpg"), bytes)))["ok"]!!.jsonPrimitive.boolean)
        assertArrayEquals(bytes, call("GET", "/api/image", query = mapOf("t" to token, "kind" to "backgrounds", "name" to "a-1.jpg")).body)
        for (name in listOf("../x.jpg", ".hidden.jpg", "a/b.jpg", "a.exe", "")) {
            assertEquals(name, 400, call("POST", "/api/image", query = mapOf("t" to token, "kind" to "backgrounds", "name" to name)).status)
        }
        assertEquals(400, call("POST", "/api/image", query = mapOf("t" to token, "kind" to "wallpapers", "name" to "a.jpg")).status)
        assertTrue(json(call("POST", "/api/image/delete", query = mapOf("t" to token, "kind" to "backgrounds", "name" to "a-1.jpg")))["ok"]!!.jsonPrimitive.boolean)
        assertEquals(404, call("GET", "/api/image", query = mapOf("t" to token, "kind" to "backgrounds", "name" to "a-1.jpg")).status)
    }

    @Test
    fun undoIsOfferedOnlyWhenThereIsSomethingToUndo() {
        assertFalse(json(call("GET", "/api/undo"))["available"]!!.jsonPrimitive.boolean)
        backend.undo = "{}"
        assertEquals("{}", json(call("GET", "/api/undo"))["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun requestsWithBinaryBodiesAreReadWhole() {
        val body = ByteArray(300_000) { (it % 251).toByte() }
        val head = "POST /api/image?t=abc&kind=backgrounds&name=a%20b.jpg HTTP/1.1\r\nHost: tv\r\nContent-Length: ${body.size}\r\n\r\n"
        val request = DashboardServer.readRequest(ByteArrayInputStream(head.toByteArray() + body))!!
        assertEquals("POST", request.method)
        assertEquals("/api/image", request.path)
        assertEquals("a b.jpg", request.query["name"])
        assertArrayEquals(body, request.body)
        assertNull("too big", DashboardServer.readRequest(ByteArrayInputStream("POST / HTTP/1.1\r\nContent-Length: 999999999\r\n\r\n".toByteArray())))
        assertNull("cut short", DashboardServer.readRequest(ByteArrayInputStream("GET / HTTP/1.1\r\nHost".toByteArray())))

        val out = ByteArrayOutputStream()
        DashboardServer.writeResponse(out, HttpResponse.json(200, """{"a":"ب"}"""))
        val text = out.toString("UTF-8")
        assertTrue(text.startsWith("HTTP/1.0 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: ${"""{"a":"ب"}""".toByteArray().size}\r\n"))
        assertTrue(text.contains("Cache-Control: no-store"))
    }

    @Test
    fun sessionTokensAreFreshAndReadable() {
        val tokens = (1..50).map { DashboardRoutes.newToken() }.toSet()
        assertEquals(50, tokens.size)
        assertTrue(tokens.all { it.length == 10 && it.none { c -> c in "0o1il" } })
    }
}
