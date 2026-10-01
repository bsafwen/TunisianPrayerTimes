package com.tunisianprayertimes.tv.remote

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerFormulaSettings
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.mosque.FlowState
import com.tunisianprayertimes.time.ClockSource
import com.tunisianprayertimes.tv.data.MediaKind
import com.tunisianprayertimes.tv.data.TestData
import com.tunisianprayertimes.tv.ui.TvStrings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        val folder: java.io.File = java.nio.file.Files.createTempDirectory("dashboard").toFile().apply { deleteOnExit() }
        var thumbnailAsked = false
        override fun image(kind: MediaKind, name: String, thumbnail: Boolean): java.io.File? {
            thumbnailAsked = thumbnail
            return images["${kind.folder}/$name"]?.let { bytes -> java.io.File(folder, name).apply { writeBytes(bytes); deleteOnExit() } }
        }
        override fun addImage(kind: MediaKind, name: String, bytes: ByteArray): ImageUpload {
            val free = DashboardRoutes.freeImageName(name, images.keys.filter { it.startsWith("${kind.folder}/") }.map { it.substringAfter('/') })
            images["${kind.folder}/$free"] = bytes
            return ImageUpload.Stored(free)
        }
        override fun deleteImage(kind: MediaKind, name: String) = images.remove("${kind.folder}/$name") != null
        override fun update() = "لا يوجد تحديث"
        var clockSetTo: Long? = null
        var clockConfirmed = 0
        var clockAnswer = ClockAnswer.DONE
        override fun setClock(epochMillis: Long): ClockAnswer { clockSetTo = epochMillis; return clockAnswer }
        override fun confirmClock(): ClockAnswer { clockConfirmed++; return clockAnswer }
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
    fun theApiNeedsTheSessionTokenAndLocksOutOnlyTheAddressThatGuesses() {
        fun admit(remote: String, t: String = token) = routes.admit(RequestHead("GET", "/api/state", mapOf("t" to t), emptyMap(), remote))
        assertEquals(403, call("GET", "/api/state", query = emptyMap()).status)
        assertEquals(200, call("GET", "/api/state").status)
        repeat(DashboardRoutes.MAX_BAD_TOKENS - 1) { admit("10.0.0.9", "wrong") }
        assertEquals(Admission.Accept(0), admit("10.0.0.9"))
        admit("10.0.0.9", "wrong")
        assertTrue("locked out, even with the right token", admit("10.0.0.9") is Admission.Reject)
        // The admin's phone, on another address, goes on.
        assertEquals(Admission.Accept(0), admit("10.0.0.5"))
        clock += DashboardRoutes.LOCKOUT_MILLIS
        assertEquals("for a minute", Admission.Accept(0), admit("10.0.0.9"))
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
        val image = call("GET", "/api/image", query = mapOf("t" to token, "kind" to "backgrounds", "name" to "a-1.jpg"))
        assertArrayEquals("streamed from its file", bytes, image.file!!.readBytes())
        assertEquals("image/jpeg", image.contentType)
        assertFalse(backend.thumbnailAsked)
        call("GET", "/api/image", query = mapOf("t" to token, "kind" to "backgrounds", "name" to "a-1.jpg", "thumb" to "1"))
        assertTrue(backend.thumbnailAsked)
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
                assertTrue(socket.getInputStream().readBytes().toString(Charsets.UTF_8).substringAfter("\r\n\r\n").startsWith("""{"ok":true,"""))
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
        assertTrue(line, line.contains("الليل") && line.endsWith("${TvStrings.ON} ← ${TvStrings.OFF}"))
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
        backend.clockAnswer = ClockAnswer.REFUSED
        val refused = json(call("POST", "/api/clock", """{"epochMillis":0}"""))
        assertFalse(refused["ok"]!!.jsonPrimitive.boolean)
        assertEquals(TvStrings.PHONE_CLOCK_SET_REFUSED, refused["message"]!!.jsonPrimitive.content)
        assertEquals(TvStrings.PHONE_CLOCK_CONFIRM_REFUSED, json(call("POST", "/api/clock", """{"confirm":true}"""))["message"]!!.jsonPrimitive.content)
        // A busy screen, or one that cannot do it, is not blamed on the phone's date.
        backend.clockAnswer = ClockAnswer.BUSY
        val busy = json(call("POST", "/api/clock", """{"epochMillis":1790000000000}"""))
        assertFalse(busy["ok"]!!.jsonPrimitive.boolean)
        assertEquals(TvStrings.PHONE_CLOCK_BUSY, busy["message"]!!.jsonPrimitive.content)
        backend.clockAnswer = ClockAnswer.UNAVAILABLE
        assertEquals(TvStrings.PHONE_CLOCK_UNAVAILABLE, json(call("POST", "/api/clock", """{"confirm":true}"""))["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun aClockChangeStartedLateIsReportedAsDoneAndOneNeverStartedIsDropped() {
        val main = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            // The main thread is stuck longer than the wait: the change is dropped, never run afterwards.
            val stuck = java.util.concurrent.CountDownLatch(1)
            main.execute { stuck.await() }
            var ran = false
            assertNull(runPosted(main::execute, 100) { ran = true; true })
            stuck.countDown()
            main.submit {}.get()
            assertFalse(ran)
            // Started within the wait but finished after it: its own answer is reported.
            assertEquals(true, runPosted(main::execute, 100) { Thread.sleep(300); true })
            assertEquals(false, runPosted(main::execute, 1_000) { false })
        } finally {
            main.shutdownNow()
        }
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
        val idle = DashboardRoutes.SESSION_IDLE_MILLIS
        val max = DashboardRoutes.SESSION_MAX_MILLIS
        assertFalse(routes.isOver())
        // Used every 10 minutes, it lasts; the state says how long it has left at most.
        for (minutes in 10..110 step 10) {
            clock = 1_000L + minutes * 60_000L
            assertEquals(200, call("GET", "/api/state").status)
        }
        val remaining = json(call("GET", "/api/state"))["session"]!!.jsonObject["remainingMillis"]!!.jsonPrimitive.long
        assertEquals(10 * 60_000L, remaining)
        clock = 1_000L + max + 1
        assertTrue("two hours at most, however much it is used", routes.isOver())
        // Refused from then on, even before the TV stops the server (the app in the background).
        val over = call("GET", "/api/state")
        assertEquals(403, over.status)
        assertEquals(DashboardRoutes.SESSION_OVER, json(over)["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun anIdleSessionRefusesItsTokenBeforeTheServerStops() {
        clock = 1_000L + DashboardRoutes.SESSION_IDLE_MILLIS + 1
        assertTrue(routes.isOver())
        assertEquals(403, call("GET", "/api/state").status)
        assertTrue("a refused call does not revive it", routes.isOver())
    }

    @Test
    fun todaysRowsFollowTheDayWithTheEidPrayerAtItsTimeAndTomorrowsFajrAfterIsha() {
        fun at(prayer: com.tunisianprayertimes.Prayer, h: Int, m: Int) = com.tunisianprayertimes.PrayerTime(prayer, h, m)
        val times = com.tunisianprayertimes.DayPrayerTimes(
            day = 20, fajr = at(com.tunisianprayertimes.Prayer.FAJR, 4, 30), shurukHour = 6, shurukMinute = 40,
            dhuhr = at(com.tunisianprayertimes.Prayer.DHUHR, 12, 50), asr = at(com.tunisianprayertimes.Prayer.ASR, 16, 30),
            maghrib = at(com.tunisianprayertimes.Prayer.MAGHRIB, 19, 50), isha = at(com.tunisianprayertimes.Prayer.ISHA, 21, 20),
        )
        val live = DashboardLive(
            LocalDateTime.of(2027, 3, 10, 5, 50), true, times,
            mapOf(com.tunisianprayertimes.Prayer.FAJR to java.time.LocalTime.of(4, 45), com.tunisianprayertimes.Prayer.AID_FITR to java.time.LocalTime.of(7, 10)),
            "", null, FlowState.IDLE, null,
            tomorrowFajr = java.time.LocalTime.of(4, 29), tomorrowFajrIqamah = java.time.LocalTime.of(4, 44),
        )
        val today = todayJson(live)
        val rows = today["prayers"]!!.jsonArray.map { it as JsonObject }
        assertEquals(listOf("FAJR", "AID_FITR", "DHUHR", "ASR", "MAGHRIB", "ISHA"), rows.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(JsonNull, rows[1]["adhan"])
        assertEquals("07:10", rows[1]["iqamah"]!!.jsonPrimitive.content)
        val tomorrow = today["tomorrowFajr"] as JsonObject
        assertEquals("04:29", tomorrow["adhan"]!!.jsonPrimitive.content)
        assertEquals("04:44", tomorrow["iqamah"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, todayJson(live.copy(tomorrowFajr = null))["tomorrowFajr"])
    }

    @Test
    fun anUploadNeverReplacesAnImageOfTheSameName() {
        assertEquals("a.jpg", DashboardRoutes.freeImageName("a.jpg", listOf("b.jpg")))
        assertEquals("IMG-1_2.jpg", DashboardRoutes.freeImageName("IMG-1.jpg", listOf("img-1.JPG")))
        assertEquals("a_3.png", DashboardRoutes.freeImageName("a.png", listOf("a.png", "a_2.png")))
        val long = "x".repeat(80) + ".jpg"
        val free = DashboardRoutes.freeImageName(long, listOf(long))
        assertEquals("x".repeat(78) + "_2.jpg", free)
        assertTrue(DashboardRoutes.IMAGE_NAME.matches(free))

        // Two phones send the same WhatsApp name: both images stay, the answer says the name kept.
        fun upload(bytes: ByteArray) = json(routes.serve(HttpRequest("POST", "/api/image", mapOf("t" to token, "kind" to "announcements", "name" to "IMG-20260929-WA0003.jpg"), bytes)))
        assertEquals("IMG-20260929-WA0003.jpg", upload(byteArrayOf(1))["name"]!!.jsonPrimitive.content)
        assertEquals("IMG-20260929-WA0003_2.jpg", upload(byteArrayOf(2))["name"]!!.jsonPrimitive.content)
        assertArrayEquals(byteArrayOf(1), backend.images["announcements/IMG-20260929-WA0003.jpg"])
    }

    @Test
    fun galleryThumbnailsAreDecodedSmall() {
        assertEquals(1, thumbnailSampleSize(0, 0))
        assertEquals(1, thumbnailSampleSize(600, 400))
        assertEquals(2, thumbnailSampleSize(1920, 1080))
        assertEquals(8, thumbnailSampleSize(4000, 3000))
        assertEquals(8, thumbnailSampleSize(3000, 4000))
    }

    @Test
    fun aThumbnailBelongsToOneVersionOfItsImage() {
        val old = thumbnailName("1.jpg", modified = 1_790_000_000_000, length = 52_000)
        // A key copied onto a box whose clock went back: an older time, yet a new image.
        assertNotEquals(old, thumbnailName("1.jpg", modified = 1_600_000_000_000, length = 52_000))
        assertNotEquals(old, thumbnailName("1.jpg", modified = 1_790_000_000_000, length = 48_000))
        assertEquals(old, thumbnailName("1.jpg", modified = 1_790_000_000_000, length = 52_000))
    }

    @Test
    fun anImageIsStreamedFromItsFile() {
        val file = java.io.File.createTempFile("image", ".jpg").apply { deleteOnExit() }
        val bytes = ByteArray(200_000) { (it % 253).toByte() }
        file.writeBytes(bytes)
        val out = ByteArrayOutputStream()
        DashboardServer.writeResponse(out, HttpResponse.file("image/jpeg", file))
        val written = out.toByteArray()
        val head = String(written, 0, written.indexOfFirst { it == '\r'.code.toByte() } + 1, Charsets.ISO_8859_1)
        assertTrue(head.startsWith("HTTP/1.0 200 OK"))
        assertTrue(String(written, Charsets.ISO_8859_1).contains("Content-Length: ${bytes.size}\r\n"))
        assertArrayEquals(bytes, written.copyOfRange(written.size - bytes.size, written.size))
        // A slow hotspot still has the time to receive the largest image.
        assertTrue(DashboardServer.writeDeadlineMillis(DashboardRoutes.MAX_IMAGE_BYTES.toLong()) > 15 * 1024 * 1000L / 32)
    }

    @Test
    fun readDeadlinesDoNotFollowTheWallClock() {
        // On System.nanoTime: a deadline passed ends the read, one ahead lets it finish.
        val request = "GET / HTTP/1.1\r\n\r\n".toByteArray()
        assertNull(DashboardServer.readHead(ByteArrayInputStream(request), System.nanoTime() - 1))
        assertEquals("/", DashboardServer.readHead(ByteArrayInputStream(request), DashboardServer.deadlineIn(60_000))!!.path)
        assertNull(DashboardServer.readBody(ByteArrayInputStream(ByteArray(10)), 10, System.nanoTime() - 1))
        assertEquals(10, DashboardServer.readBody(ByteArrayInputStream(ByteArray(10)), 10, DashboardServer.deadlineIn(60_000))!!.size)
    }

    @Test
    fun aBusyServerAnswersSoThePageRetries() {
        val release = java.util.concurrent.CountDownLatch(1)
        val server = DashboardServer({ Admission.Accept(0) }, { release.await(); HttpResponse.json(200, "{}") })
        val port = server.start(0)
        val held = (1..DashboardServer.MAX_CONNECTIONS).map {
            java.net.Socket("127.0.0.1", port).apply { getOutputStream().write("GET /api/state HTTP/1.1\r\n\r\n".toByteArray()) }
        }
        try {
            java.net.Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 15_000
                socket.getOutputStream().write("GET /api/state HTTP/1.1\r\n\r\n".toByteArray())
                val answer = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(answer, answer.startsWith("HTTP/1.0 503") && answer.endsWith(DashboardServer.BUSY_JSON))
            }
        } finally {
            release.countDown()
            held.forEach { it.close() }
            server.stop()
        }
    }

    @Test
    fun sessionTokensAreFreshAndReadable() {
        val tokens = (1..50).map { DashboardRoutes.newToken() }.toSet()
        assertEquals(50, tokens.size)
        assertTrue(tokens.all { it.length == 10 && it.none { c -> c in "0o1il" } })
    }

    private fun screen(
        adminPage: Boolean = false, clockPage: Boolean = false, prayer: Boolean = false, question: Boolean = false,
        usb: Boolean = false, adhkar: Boolean = false, announcements: Boolean = false, night: Boolean = false,
    ) = DashboardScreen.of(adminPage, clockPage, prayer, question, usb, adhkar, announcements, night, eid = false)

    @Test
    fun thePhoneIsToldWhenTheWallShowsSettingsTheClockOrAKeysOffer() {
        // The settings with the session's code on the wall: not «أوقات الصلاة».
        assertEquals(DashboardScreen.SETTINGS, screen(adminPage = true))
        assertEquals(DashboardScreen.SETTINGS, screen(adminPage = true, prayer = true, night = true))
        assertEquals(DashboardScreen.CLOCK, screen(clockPage = true))
        assertEquals(DashboardScreen.CLOCK, screen(question = true, announcements = true))
        assertEquals(DashboardScreen.USB_OFFER, screen(usb = true, night = true))
        assertEquals(DashboardScreen.USB_OFFER, screen(adminPage = true, usb = true))
    }

    @Test
    fun thePrayerAndItsAdhkarAreToldByThePhase() {
        assertNull(screen(prayer = true, usb = true))
        assertNull(screen(adhkar = true, announcements = true))
        assertEquals(DashboardScreen.ANNOUNCEMENTS, screen(announcements = true, night = true))
        assertEquals(DashboardScreen.NIGHT, screen(night = true))
        assertNull(screen())
    }

    @Test
    fun theStateAfterAnApplyWaitsForTheWallToCatchUp() {
        val old = DashboardLive(LocalDateTime.of(2026, 9, 29, 19, 0), true, null, emptyMap(), "", null, FlowState.IDLE, null, settingsVersion = 3)
        val new = old.copy(settingsVersion = 4)
        var clock = 0L
        var reads = 0
        // The display redraws 60 ms after the apply: the page gets the new iqamah times, not the old ones.
        val caught = DashboardLive.awaitSettings({ reads++; if (clock >= 60) new else old }, wanted = 4, elapsed = { clock }, sleep = { clock += it })
        assertEquals(4, caught!!.settingsVersion)
        assertTrue(reads > 1)
        // Already current: no wait at all.
        clock = 0
        DashboardLive.awaitSettings({ new }, wanted = 4, elapsed = { clock }, sleep = { clock += it })
        assertEquals(0L, clock)
        // A display that does not catch up within a second: the page gets what there is.
        clock = 0
        assertEquals(3, DashboardLive.awaitSettings({ old }, wanted = 4, elapsed = { clock }, sleep = { clock += it })!!.settingsVersion)
        assertTrue(clock in DashboardLive.CATCH_UP_MILLIS..DashboardLive.CATCH_UP_MILLIS + 20)
    }

    @Test
    fun theSessionsCodeHoldsTheAddressOfTheNetworkInUse() {
        val all = listOf("192.168.49.1", "192.168.1.20") // Wi-Fi Direct first, as the box lists them
        val session = com.tunisianprayertimes.tv.ui.remote.PhoneAdminSession.of(8080, "abc", active = "192.168.1.20", addresses = all)
        assertEquals("http://192.168.1.20:8080/?t=abc", session.url)
        assertEquals(listOf("http://192.168.49.1:8080/?t=abc"), session.otherUrls)
        // An active address that is not a local one (none on this network): the local ones in order.
        val noActive = com.tunisianprayertimes.tv.ui.remote.PhoneAdminSession.of(8080, "abc", active = "100.64.0.3", addresses = all)
        assertEquals("http://192.168.49.1:8080/?t=abc", noActive.url)
        // Not on any network yet: no code; the page says so until the hotspot is joined.
        val none = com.tunisianprayertimes.tv.ui.remote.PhoneAdminSession.of(8080, "abc", active = null, addresses = emptyList())
        assertNull(none.url)
        assertTrue(none.otherUrls.isEmpty())
    }

    @Test
    fun theStateCarriesThePrayerTimeValuesAndThePlaceAsTheFormulaSeesIt() {
        val tunis = FormulaPlace(615, "تونس", "تونس", TestData.prayerTimes.location(615)!!)
        // The page codes against these exact keys: 18 as the file writes it, every adjustment, no overrides.
        assertEquals(
            """{"official":true,"settings":{"fajrAngle":18,"ishaAngle":18,"asrShadow":1,"dhuhrMinutes":7,"maghribMinutes":2,"elevation":true,""" +
                """"adjust":{"fajr":0,"dhuhr":0,"asr":0,"maghrib":0,"isha":0}},""" +
                """"location":{"delegationId":615,"delegationName":"تونس","gouvernoratName":"تونس","latitude":36.8,"longitude":10.183,"elevation":9,"sunriseElevations":{}}}""",
            formulaJson(PrayerFormulaSettings.OFFICIAL, tunis).toString(),
        )
        val custom = PrayerFormulaSettings(fajrAngle = 17.5, asrShadow = 2, elevation = false).withAdjustment(Prayer.ISHA, 2).withAdjustment(Prayer.FAJR, -1)
        val zeriba = FormulaPlace(409, "الزريبة", "زغوان", TestData.prayerTimes.location(409)!!)
        val state = formulaJson(custom, zeriba)
        assertFalse(state["official"]!!.jsonPrimitive.boolean)
        val settings = state["settings"]!!.jsonObject
        assertEquals("17.5", settings["fajrAngle"].toString())
        assertEquals("2", settings["asrShadow"].toString())
        assertEquals("false", settings["elevation"].toString())
        assertEquals("""{"fajr":-1,"dhuhr":0,"asr":0,"maghrib":0,"isha":2}""", settings["adjust"].toString())
        // INM's 2026 sunrise for Zeriba counts 15.6 m, not the delegation's 156 m: the page's formula needs it too.
        val location = state["location"]!!.jsonObject
        assertEquals("156", location["elevation"].toString())
        assertEquals("""{"2026":15.6}""", location["sunriseElevations"].toString())
        // No place yet: the page asks for one first.
        assertEquals(JsonNull, formulaJson(PrayerFormulaSettings.OFFICIAL, null)["location"])
    }

    @Test
    fun thePhonesAutomaticDateIsWhereTheTvReturns() {
        // A Ramadan start set a day after the estimate carries Shawwal with it: «تلقائي» on the Eid al-Fitr
        // is that merged date, the one the TV's own page returns to, not the estimate alone.
        val year = 1448
        val estimate = com.tunisianprayertimes.IslamicDays.yearDates(year, com.tunisianprayertimes.ManualIslamicDates())
        val manual = com.tunisianprayertimes.ManualIslamicDates(
            ramadanStart = estimate.ramadanStart.date.plusDays(1),
            eidFitr = estimate.eidFitr.date.plusDays(3),
        )
        val merged = com.tunisianprayertimes.IslamicDays.yearDates(year, manual.copy(eidFitr = null)).eidFitr.date
        assertEquals(merged, com.tunisianprayertimes.tv.ui.settings.automaticDate(year, manual, MosqueSettingsFile.DateEvent.EID_FITR))
        assertNotEquals(estimate.eidFitr.date, merged)
    }
}
