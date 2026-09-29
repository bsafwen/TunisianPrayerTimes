package com.tunisianprayertimes.tv.remote

import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.mosque.FlowState
import com.tunisianprayertimes.time.ClockSource
import com.tunisianprayertimes.tv.data.MediaKind
import com.tunisianprayertimes.tv.ui.TvStrings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
        var clockSetTo: Long? = null
        var clockConfirmed = 0
        var clockAccepts = true
        override fun setClock(epochMillis: Long): Boolean { clockSetTo = epochMillis; return clockAccepts }
        override fun confirmClock(): Boolean { clockConfirmed++; return clockAccepts }
        override fun asset(name: String) = if (name == "index.html" || name == "views/prayers.js") name.toByteArray() else null
        val fontsAsked = mutableListOf<String>()
        // Answers any name, so the tests see which names the routes let through.
        override fun font(name: String): ByteArray { fontsAsked += name; return "font:$name".toByteArray() }
    }

    private val backend = FakeBackend()
    private var clock = 1_000L
    private val routes = DashboardRoutes(token, backend) { clock }

    private fun call(method: String, path: String, body: String = "", query: Map<String, String> = mapOf("t" to token)) =
        routes.serve(HttpRequest(method, path, query, body.toByteArray()))

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
    fun theFontsArePublicButOnlyTheListedOnes() {
        clock = 7_000L
        val readex = call("GET", "/fonts/readex-pro.ttf", query = emptyMap())
        assertEquals(200, readex.status)
        assertEquals("font/ttf", readex.contentType)
        assertEquals("font:readex-pro.ttf", readex.text)
        // The phone keeps the fonts; everything else, which holds the mosque's settings, is never stored.
        assertEquals(DashboardRoutes.FONT_CACHE, readex.cacheControl)
        assertEquals(200, call("GET", "/fonts/amiri.ttf", query = emptyMap()).status)
        for (path in listOf("/fonts/reem-kufi.ttf", "/fonts/../index.html", "/fonts/readex-pro.TTF", "/fonts/", "/fonts/x/readex-pro.ttf")) {
            assertEquals(path, 404, call("GET", path, query = emptyMap()).status)
        }
        assertEquals(404, call("POST", "/fonts/readex-pro.ttf", query = emptyMap()).status)
        assertEquals(listOf("readex-pro.ttf", "amiri.ttf"), backend.fontsAsked)
        // Loading the page's fonts is not using the session.
        assertEquals(1_000L, routes.lastUsedAt)
        assertEquals("no-store", call("GET", "/", query = emptyMap()).cacheControl)
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
        assertTrue(json(routes.serve(HttpRequest("POST", "/api/image", mapOf("t" to token, "kind" to "backgrounds", "name" to "a-1.jpg"), bytes)))["ok"]!!.jsonPrimitive.boolean)
        assertArrayEquals(bytes, call("GET", "/api/image", query = mapOf("t" to token, "kind" to "backgrounds", "name" to "a-1.jpg")).body)
        for (name in listOf("../x.jpg", ".hidden.jpg", "a/b.jpg", "a.exe", "")) {
            assertEquals(name, 400, call("POST", "/api/image", query = mapOf("t" to token, "kind" to "backgrounds", "name" to name)).status)
        }
        assertEquals(400, call("POST", "/api/image", query = mapOf("t" to token, "kind" to "wallpapers", "name" to "a.jpg")).status)
        // Files already on the TV keep their own names (from a USB key), but never a path.
        backend.images["announcements/درس الجمعة.txt"] = byteArrayOf(1)
        assertTrue(json(call("POST", "/api/image/delete", query = mapOf("t" to token, "kind" to "announcements", "name" to "درس الجمعة.txt")))["ok"]!!.jsonPrimitive.boolean)
        for (name in listOf("../prefs.xml", "a\\b.txt", ".part")) {
            assertEquals(name, 400, call("POST", "/api/image/delete", query = mapOf("t" to token, "kind" to "announcements", "name" to name)).status)
        }
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

        // A font goes out with the header its route asked for, so the phone keeps it.
        val font = ByteArrayOutputStream()
        DashboardServer.writeResponse(font, HttpResponse(200, "font/ttf", ByteArray(4), cacheControl = DashboardRoutes.FONT_CACHE))
        assertTrue(font.toString("UTF-8").contains("Cache-Control: ${DashboardRoutes.FONT_CACHE}\r\n"))
    }

    @Test
    fun requestsAreJudgedFromTheirHeadBeforeAnyBodyIsRead() {
        fun head(method: String, path: String, t: String = token) = RequestHead(method, path, mapOf("t" to t), emptyMap())
        assertEquals(Admission.Accept(0), routes.admit(head("GET", "/api/state")))
        assertEquals(Admission.Accept(DashboardRoutes.MAX_IMAGE_BYTES), routes.admit(head("POST", "/api/image")))
        assertEquals(Admission.Accept(MosqueSettingsFile.MAX_CHARS * 4), routes.admit(head("POST", "/api/apply")))
        assertEquals(404, (routes.admit(head("POST", "/index.html")) as Admission.Reject).response.status)
        assertEquals(403, (routes.admit(head("POST", "/api/apply", t = "wrong")) as Admission.Reject).response.status)
        assertEquals(413, call("POST", "/api/state", body = "x").status)
    }

    @Test
    fun theSessionCountsAsUsedOnlyWithItsToken() {
        assertEquals(1_000L, routes.startedAt)
        clock = 5_000L
        call("GET", "/api/state", query = mapOf("t" to "wrong"))
        call("GET", "/")
        assertEquals(1_000L, routes.lastUsedAt)
        call("GET", "/api/state")
        assertEquals(5_000L, routes.lastUsedAt)
    }

    @Test
    fun anOldPageAskingForImagesDoesNotCloseTheSession() {
        repeat(DashboardRoutes.MAX_BAD_TOKENS * 2) {
            call("GET", "/api/image", query = mapOf("t" to "previous", "kind" to "backgrounds", "name" to "a.jpg"))
        }
        assertEquals(200, call("GET", "/api/state").status)
    }

    @Test
    fun theServerRefusesTooLargeBodiesUnreadAndCutsConnectionsWhenStopped() {
        val server = DashboardServer(routes::admit, routes::handle)
        val port = server.start(0)
        try {
            java.net.Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                // Claims 50 MB and sends none of it: answered at once from the head.
                socket.getOutputStream().write("POST /api/apply?t=$token HTTP/1.1\r\nContent-Length: 50000000\r\n\r\n".toByteArray())
                val answer = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(answer, answer.startsWith("HTTP/1.0 413"))
            }
            java.net.Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write("GET /api/state?t=$token HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().readBytes().toString(Charsets.UTF_8).endsWith("""{"ok":true}"""))
            }
            // A connection that sends nothing is closed by stop(), long before its own time limit.
            java.net.Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                Thread.sleep(200)
                val started = System.currentTimeMillis()
                server.stop()
                assertEquals(-1, runCatching { socket.getInputStream().read() }.getOrDefault(-1))
                assertTrue(System.currentTimeMillis() - started < 4_000)
            }
            assertFalse(server.isRunning)
        } finally {
            server.stop()
        }
    }

    @Test
    fun malformedHeadsAreRefused() {
        fun head(text: String) = DashboardServer.readHead(ByteArrayInputStream(text.toByteArray()), Long.MAX_VALUE)
        assertNull("bad length", head("POST / HTTP/1.1\r\nContent-Length: lots\r\n\r\n"))
        assertNull("no target", head("GET\r\n\r\n"))
        assertNull("endless headers", head("GET / HTTP/1.1\r\n" + "X-A: b\r\n".repeat(2_000) + "\r\n"))
        assertEquals(12, head("POST /api/apply HTTP/1.1\r\ncontent-length: 12\r\n\r\n")!!.contentLength)
    }

    @Test
    fun theAdhkarLibraryListsEveryReviewedTextTheListsCanUse() {
        assertEquals(403, call("GET", "/api/adhkar", query = emptyMap()).status)
        val library = json(call("GET", "/api/adhkar"))
        val entries = library["entries"]!!.jsonArray.map { it as JsonObject }
        val ids = entries.map { it["id"]!!.jsonPrimitive.content }.toSet()
        assertEquals(ids.size, entries.size)
        val lists = library["lists"] as JsonObject
        for (list in listOf("afterSalah", "ticker")) {
            val bundled = lists[list]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(list, bundled.isNotEmpty() && ids.containsAll(bundled))
        }
        val categories = library["categories"]!!.jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }.toSet()
        for (entry in entries) {
            val id = entry["id"]!!.jsonPrimitive.content
            assertTrue(id, entry["text"]!!.jsonPrimitive.content.isNotBlank() && entry["reference"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue(id, categories.containsAll(entry["categories"]!!.jsonArray.map { it.jsonPrimitive.content }))
            assertTrue(id, entry["afterSalahMillis"]!!.jsonPrimitive.content.toLong() > 0 && entry["tickerMillis"]!!.jsonPrimitive.content.toLong() > 0)
        }
        val hundred = entries.single { it["id"]!!.jsonPrimitive.content == "salah_hundred" }
        assertEquals(4, hundred["steps"]!!.jsonArray.size)
        assertTrue("small enough for a phone on a hotspot", AdhkarLibrary.json.toByteArray().size < 400_000)
    }

    @Test
    fun aPreviewSaysWhichAdhkarComeAndGo() {
        val file = """{ "adhkar": { "afterSalah": { "mode": "replace", "items": [ { "id": "salah_salam" }, { "id": "salah_istighfar" } ] } } }"""
        val result = MosqueSettingsFile.parse(file, MosqueSchedule.DEFAULT) as ParseResult.Success
        val lines = com.tunisianprayertimes.tv.ui.usb.SettingsChangeLines.of(result)
        assertTrue(lines.toString(), lines.first().startsWith("أذكار"))
        assertTrue(lines.toString(), lines.any { it.contains("يُحذف") && it.contains("آية الكرسي") })
        assertTrue(lines.toString(), lines.none { it.contains("يُضاف") })
    }

    @Test
    fun aPreviewNamesTheNightScreen() {
        val tv = com.tunisianprayertimes.mosque.MosqueProfile(display = com.tunisianprayertimes.mosque.DisplayOptions(nightScreen = true))
        val file = """{ "display": { "nightScreen": false } }"""
        val result = MosqueSettingsFile.parse(file, MosqueSchedule.DEFAULT, currentProfile = tv) as ParseResult.Success
        val line = com.tunisianprayertimes.tv.ui.usb.SettingsChangeLines.of(result).single()
        assertTrue(line, line.contains("الليل") && line.endsWith("تشغيل ← إيقاف"))
    }

    @Test
    fun thePhoneSetsTheClockOrConfirmsIt() {
        val set = json(call("POST", "/api/clock", """{"epochMillis":1790000000000}"""))
        assertTrue(set["ok"]!!.jsonPrimitive.boolean)
        assertEquals(TvStrings.PHONE_CLOCK_SET, set["message"]!!.jsonPrimitive.content)
        assertEquals(1_790_000_000_000L, backend.clockSetTo)
        val confirmed = json(call("POST", "/api/clock", """{ "confirm": true }"""))
        assertTrue(confirmed["ok"]!!.jsonPrimitive.boolean)
        assertEquals(TvStrings.PHONE_CLOCK_CONFIRMED, confirmed["message"]!!.jsonPrimitive.content)
        assertEquals(1, backend.clockConfirmed)
        // A time the TV cannot take (a phone set to 1970, a clock reset): said in the answer, not an HTTP error.
        backend.clockAccepts = false
        val refused = json(call("POST", "/api/clock", """{"epochMillis":0}"""))
        assertFalse(refused["ok"]!!.jsonPrimitive.boolean)
        assertEquals(TvStrings.PHONE_CLOCK_SET_REFUSED, refused["message"]!!.jsonPrimitive.content)
        assertEquals(TvStrings.PHONE_CLOCK_CONFIRM_REFUSED, json(call("POST", "/api/clock", """{"confirm":true}"""))["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun aClockRequestIsOneOfTheTwoAndNothingElse() {
        val bad = listOf(
            "", "x", "[]", "{}", "null", """{"epochMillis":"1790000000000"}""", """{"epochMillis":1.79E12}""",
            """{"confirm":false}""", """{"confirm":"true"}""", """{"epochMillis":1790000000000,"confirm":true}""",
            """{"epochMillis":1790000000000,"zone":"Asia/Shanghai"}""",
        )
        for (body in bad) assertEquals(body, 400, call("POST", "/api/clock", body).status)
        assertNull(backend.clockSetTo)
        assertEquals(0, backend.clockConfirmed)
        // Only with the session's token, only a small body, and only as a POST.
        assertEquals(Admission.Accept(DashboardRoutes.MAX_CLOCK_BODY), routes.admit(RequestHead("POST", "/api/clock", mapOf("t" to token), emptyMap())))
        assertEquals(413, call("POST", "/api/clock", """{"confirm":true}""" + " ".repeat(DashboardRoutes.MAX_CLOCK_BODY)).status)
        assertEquals(403, call("POST", "/api/clock", """{"confirm":true}""", query = emptyMap()).status)
        assertEquals(404, call("GET", "/api/clock").status)
        assertEquals(0, backend.clockConfirmed)
    }

    @Test
    fun theStateSaysHowFarTheClockCanBeTrusted() {
        val live = DashboardLive(LocalDateTime.of(2026, 9, 29, 14, 0, 5, 700_000_000), true, null, emptyMap(), "", null, FlowState.IDLE, null)
        // Without the guard's view (as before): the time shown and whether it is plausible, nothing to compare.
        val before = clockJson(live, null)
        assertEquals(setOf("now", "trusted"), before.keys)
        assertEquals("2026-09-29T14:00:05", before["now"]!!.jsonPrimitive.content)
        val state = DashboardClockState(1_790_686_805_700L, verified = false, source = null, deviceZone = "Asia/Shanghai", zoneDiffers = true)
        val clock = clockJson(live, state)
        assertTrue(clock["trusted"]!!.jsonPrimitive.boolean)
        assertEquals(1_790_686_805_700L, clock["epochMillis"]!!.jsonPrimitive.long)
        assertFalse(clock["verified"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, clock["source"])
        assertEquals("Asia/Shanghai", clock["deviceZone"]!!.jsonPrimitive.content)
        assertTrue(clock["zoneDiffers"]!!.jsonPrimitive.boolean)
        val phone = clockJson(live, state.copy(verified = true, source = ClockSource.PHONE))
        assertEquals("PHONE", phone["source"]!!.jsonPrimitive.content)
        // Before the display's first reading.
        assertEquals("", clockJson(null, null)["now"]!!.jsonPrimitive.content)
    }

    @Test
    fun theSessionEndsOnItsOwnClock() {
        // The TV passes the time since boot, which correcting the wall clock from this very page does not move.
        val idle = 15 * 60_000L
        val max = 2 * 3_600_000L
        assertFalse(routes.isOver(idle, max))
        clock = 1_000L + idle + 1
        assertTrue(routes.isOver(idle, max))
        call("GET", "/api/state")
        assertFalse(routes.isOver(idle, max))
        clock = 1_000L + max + 1
        call("GET", "/api/state")
        assertTrue("two hours at most, however much it is used", routes.isOver(idle, max))
    }

    @Test
    fun sessionTokensAreFreshAndReadable() {
        val tokens = (1..50).map { DashboardRoutes.newToken() }.toSet()
        assertEquals(50, tokens.size)
        assertTrue(tokens.all { it.length == 10 && it.none { c -> c in "0o1il" } })
    }
}
