package com.tunisianprayertimes.tv.remote

import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.tv.data.MediaKind
import com.tunisianprayertimes.tv.ui.TvStrings
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** What the dashboard can read and do on the TV. Every settings change goes through [preview] then [apply]. */
interface DashboardBackend {
    /** The JSON of GET /api/state (see docs/DASHBOARD.md). */
    fun stateJson(): String

    /** Gouvernorats and their delegations, as JSON. */
    fun placesJson(): String

    fun preview(text: String): ParseResult
    fun apply(text: String): Boolean

    /** The admin-readable lines for a checked file: its changes, or its mistakes. */
    fun describe(result: ParseResult): List<String>

    /** The settings before the last change, as a file to preview and apply; null when there is none. */
    fun undoText(): String?

    /** A stored image by its listed name, streamed from its file; with [thumbnail], a small JPEG of it for the gallery. */
    fun image(kind: MediaKind, name: String, thumbnail: Boolean): File?

    /** Stores an uploaded image, never over another one: a taken name gets a free one ([DashboardRoutes.freeImageName]). */
    fun addImage(kind: MediaKind, name: String, bytes: ByteArray): ImageUpload

    /** Removes an image, or an announcement .txt file, by its exact listed name. */
    fun deleteImage(kind: MediaKind, name: String): Boolean

    /** Starts installing an available update (GitHub build); the message tells what happens. */
    fun update(): String

    /** The phone's clock says it is [epochMillis] now: the TV's time is set to it, or the answer says why not. */
    fun setClock(epochMillis: Long): ClockAnswer

    /** The admin says the TV's time is right (it agrees with the phone), or the answer says why it cannot be. */
    fun confirmClock(): ClockAnswer

    /** A file of the page itself, from the app's assets (dashboard/…). */
    fun asset(name: String): ByteArray?

    /**
     * A font of the page ([DashboardRoutes.FONTS]: "readex-pro.ttf", "amiri.ttf"), from the app's own
     * font resources, so the page looks like the screen without internet; null for any other name.
     */
    fun font(name: String): ByteArray?
}

/** What became of an uploaded image: stored under [Stored.name], or refused for [Refused.error] (Arabic). */
sealed interface ImageUpload {
    data class Stored(val name: String) : ImageUpload
    data class Refused(val error: String) : ImageUpload
}

/**
 * The dashboard's routes. The page files and its fonts are public (they hold no data); every /api call
 * needs the session [token] shown in the QR code on the TV, so only someone in front of the screen can
 * manage it. The token and the size of a request are checked from its head, before its body is read
 * ([admit]). After [MAX_BAD_TOKENS] wrong tokens from one address, that address alone is refused for
 * [LOCKOUT_MILLIS]: with a 50-bit token that is enough against guessing, and a stranger probing the
 * Wi-Fi cannot close the admin's session.
 *
 * [now] measures the session's age and idle time, in millis. On the TV it should be the time since boot
 * (SystemClock.elapsedRealtime): the wall clock can be corrected during a session, from this very page,
 * and would end the session at once or stretch it past its limit.
 */
class DashboardRoutes(private val token: String, private val backend: DashboardBackend, private val now: () -> Long = System::currentTimeMillis) {

    /** Wrong tokens by remote address, and until when (on [now]'s clock) that address is refused. */
    private class Strikes(var count: Int = 0, var lockedUntil: Long = Long.MIN_VALUE)
    private val strikes = HashMap<String, Strikes>()

    /** When the session started, and when it was last used with the token (a phone keeping the page open), on [now]'s clock. */
    val startedAt: Long = now()
    @Volatile var lastUsedAt: Long = startedAt
        private set

    /** Whether the session should end: unused for longer than [idleMillis], or older than [maxMillis]. */
    fun isOver(idleMillis: Long, maxMillis: Long): Boolean {
        val at = now()
        return at - lastUsedAt > idleMillis || at - startedAt > maxMillis
    }

    /** Whether the session is over by its own limits ([SESSION_IDLE_MILLIS], [SESSION_MAX_MILLIS]). */
    fun isOver(): Boolean = isOver(SESSION_IDLE_MILLIS, SESSION_MAX_MILLIS)

    /** How long the session has left at most, whatever its use: the page warns before it ends. */
    fun remainingMillis(): Long = (startedAt + SESSION_MAX_MILLIS - now()).coerceAtLeast(0)

    /** Judges a request from its head: the token for /api calls, and how large a body each route takes. */
    fun admit(head: RequestHead): Admission {
        if (!head.path.startsWith("/api/")) {
            return if (head.method == "GET") Admission.Accept(0) else Admission.Reject(error(404, "غير موجود"))
        }
        synchronized(this) {
            if (now() < (strikes[head.remote]?.lockedUntil ?: Long.MIN_VALUE)) {
                return Admission.Reject(error(403, "محاولات خاطئة كثيرة من هذا الجهاز: أعد فتح الرابط بعد دقيقة"))
            }
            if (!sameToken(head.query["t"].orEmpty())) {
                // An old page left open asks for its images with the previous session's token: not an attack.
                if (!(head.method == "GET" && head.path == "/api/image")) strike(head.remote)
                return Admission.Reject(error(403, "افتح الرابط من رمز QR الظاهر على شاشة المسجد"))
            }
        }
        // The TV stops the server once it sees the session over, but not while the app is in the
        // background (Wi-Fi settings, Home): the token stops working on time all the same.
        if (isOver()) return Admission.Reject(error(403, SESSION_OVER))
        lastUsedAt = now()
        val maxBody = when (head.method to head.path) {
            "POST" to "/api/image" -> MAX_IMAGE_BYTES
            "POST" to "/api/preview", "POST" to "/api/apply" -> MosqueSettingsFile.MAX_CHARS * 4
            "POST" to "/api/clock" -> MAX_CLOCK_BODY
            else -> 0
        }
        return Admission.Accept(maxBody)
    }

    private fun strike(remote: String) {
        if (strikes.size >= MAX_TRACKED_ADDRESSES && remote !in strikes) strikes.clear() // a bound, whatever the network sends
        val entry = strikes.getOrPut(remote) { Strikes() }
        if (++entry.count >= MAX_BAD_TOKENS) {
            entry.count = 0
            entry.lockedUntil = now() + LOCKOUT_MILLIS
        }
    }

    /** Handles an admitted request (see [admit]); the token is checked again in case it was not. */
    fun handle(request: HttpRequest): HttpResponse {
        if (!request.path.startsWith("/api/")) return page(request)
        if (!sameToken(request.query["t"].orEmpty())) return error(403, "افتح الرابط من رمز QR الظاهر على شاشة المسجد")
        return api(request)
    }

    /** For tests and simple callers: admits then handles one whole request. */
    fun serve(request: HttpRequest): HttpResponse =
        when (val admission = admit(RequestHead(request.method, request.path, request.query, request.headers))) {
            is Admission.Reject -> admission.response
            is Admission.Accept -> if (request.body.size > admission.maxBody) error(413, "الطلب كبير جدًا") else handle(request)
        }

    private fun page(request: HttpRequest): HttpResponse {
        if (request.method != "GET") return error(404, "غير موجود")
        val name = if (request.path == "/") "index.html" else request.path.removePrefix("/")
        if (name.startsWith("fonts/")) {
            val font = name.removePrefix("fonts/").takeIf { it in FONTS }?.let(backend::font) ?: return error(404, "غير موجود")
            return HttpResponse(200, "font/ttf", font, cacheControl = FONT_CACHE)
        }
        if (!PAGE_FILE.matches(name)) return error(404, "غير موجود")
        val bytes = backend.asset(name) ?: return error(404, "غير موجود")
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            else -> "text/css; charset=utf-8"
        }
        return HttpResponse(200, type, bytes)
    }

    private fun api(request: HttpRequest): HttpResponse = when (request.method to request.path) {
        "GET" to "/api/state" -> HttpResponse.json(200, withSession(backend.stateJson()))
        "GET" to "/api/places" -> HttpResponse.json(200, backend.placesJson())
        "GET" to "/api/adhkar" -> HttpResponse.json(200, AdhkarLibrary.json)
        "POST" to "/api/preview" -> checked(request) { result -> lines(result is ParseResult.Success, backend.describe(result)) }
        "POST" to "/api/apply" -> checked(request) { result ->
            if (result is ParseResult.Success && backend.apply(request.text)) lines(true, backend.describe(result))
            else lines(false, backend.describe(result))
        }
        "GET" to "/api/undo" -> backend.undoText().let { text ->
            HttpResponse.json(200, buildJsonObject {
                put("available", text != null)
                if (text != null) put("text", text)
            }.toString())
        }
        "GET" to "/api/image" -> listedFile(request) { kind, name ->
            backend.image(kind, name, thumbnail = request.query["thumb"] == "1")?.let { HttpResponse.file(imageType(it.name), it) }
                ?: error(404, "الصورة غير موجودة")
        }
        "POST" to "/api/image" -> imageRequest(request) { kind, name ->
            when {
                request.body.isEmpty() -> result("الملف فارغ")
                request.body.size > MAX_IMAGE_BYTES -> result("الصورة أكبر من 15 ميغابايت")
                else -> when (val upload = backend.addImage(kind, name, request.body)) {
                    is ImageUpload.Refused -> result(upload.error)
                    is ImageUpload.Stored -> HttpResponse.json(200, buildJsonObject {
                        put("ok", true)
                        put("name", upload.name)
                    }.toString())
                }
            }
        }
        "POST" to "/api/image/delete" -> listedFile(request) { kind, name ->
            result(if (backend.deleteImage(kind, name)) null else "الملف غير موجود")
        }
        "POST" to "/api/update" -> HttpResponse.json(200, buildJsonObject {
            put("ok", true)
            put("message", backend.update())
        }.toString())
        "POST" to "/api/clock" -> clock(request)
        else -> error(404, "غير موجود")
    }

    /**
     * The TV's clock from the phone: `{"epochMillis": n}` sets it to the phone's time, `{"confirm": true}`
     * says the time it shows is right. One of the two, nothing else.
     */
    private fun clock(request: HttpRequest): HttpResponse {
        val body = runCatching { Json.parseToJsonElement(request.text) as? JsonObject }.getOrNull()
            ?: return error(400, TvStrings.PHONE_CLOCK_BAD_REQUEST)
        val epochMillis = (body["epochMillis"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val confirm = (body["confirm"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
        val (answer, done, refused) = when {
            body.keys.size != 1 -> return error(400, TvStrings.PHONE_CLOCK_BAD_REQUEST)
            epochMillis != null -> Triple(backend.setClock(epochMillis), TvStrings.PHONE_CLOCK_SET, TvStrings.PHONE_CLOCK_SET_REFUSED)
            confirm -> Triple(backend.confirmClock(), TvStrings.PHONE_CLOCK_CONFIRMED, TvStrings.PHONE_CLOCK_CONFIRM_REFUSED)
            else -> return error(400, TvStrings.PHONE_CLOCK_BAD_REQUEST)
        }
        return HttpResponse.json(200, buildJsonObject {
            put("ok", answer == ClockAnswer.DONE)
            put("message", when (answer) {
                ClockAnswer.DONE -> done
                ClockAnswer.REFUSED -> refused
                ClockAnswer.BUSY -> TvStrings.PHONE_CLOCK_BUSY
                ClockAnswer.UNAVAILABLE -> TvStrings.PHONE_CLOCK_UNAVAILABLE
            })
        }.toString())
    }

    private fun checked(request: HttpRequest, respond: (ParseResult) -> HttpResponse): HttpResponse =
        if (request.body.size > MosqueSettingsFile.MAX_CHARS * 4) error(413, "الملف كبير جدًا")
        else respond(backend.preview(request.text))

    /** An upload: a new name must be plain (letters, digits, - _ .) with an image extension. */
    private fun imageRequest(request: HttpRequest, respond: (MediaKind, String) -> HttpResponse): HttpResponse {
        val kind = MediaKind.entries.find { it.folder == request.query["kind"] } ?: return error(400, "نوع الصورة غير معروف")
        val name = request.query["name"].orEmpty()
        if (!IMAGE_NAME.matches(name)) return error(400, "اسم الصورة غير صالح: حروف لاتينية وأرقام و- و_ فقط، بامتداد jpg أو png أو webp")
        return respond(kind, name)
    }

    /**
     * An existing file, by the exact name the state lists: files copied from a USB key keep their
     * own names. Only a plain file name reaches the backend, which looks it up in its listing.
     */
    private fun listedFile(request: HttpRequest, respond: (MediaKind, String) -> HttpResponse): HttpResponse {
        val kind = MediaKind.entries.find { it.folder == request.query["kind"] } ?: return error(400, "نوع الصورة غير معروف")
        val name = request.query["name"].orEmpty()
        if (name.isEmpty() || name.length > 200 || name.startsWith(".") || name.any { it == '/' || it == '\\' || it.code < 32 }) {
            return error(400, "اسم غير صالح")
        }
        return respond(kind, name)
    }

    private fun lines(ok: Boolean, lines: List<String>) = HttpResponse.json(200, buildJsonObject {
        put("ok", ok)
        putJsonArray("lines") { lines.forEach { add(JsonPrimitive(it)) } }
    }.toString())

    private fun result(problem: String?) = HttpResponse.json(200, buildJsonObject {
        put("ok", problem == null)
        if (problem != null) put("error", problem)
    }.toString())

    private fun error(status: Int, message: String) =
        HttpResponse.json(status, buildJsonObject { put("error", message) }.toString())

    private fun imageType(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    /** The state with `session.remainingMillis`, which only the routes know. */
    private fun withSession(state: String): String {
        val fields = Json.parseToJsonElement(state) as? JsonObject ?: return state
        return JsonObject(fields + ("session" to buildJsonObject { put("remainingMillis", remainingMillis()) })).toString()
    }

    private fun sameToken(candidate: String): Boolean =
        MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))

    companion object {
        const val MAX_BAD_TOKENS = 10
        const val LOCKOUT_MILLIS = 60_000L
        private const val MAX_TRACKED_ADDRESSES = 256

        /** A session ends 15 minutes after its last use with the token (an open page keeps it alive), and after 2 hours whatever happens. */
        const val SESSION_IDLE_MILLIS = 15 * 60_000L
        const val SESSION_MAX_MILLIS = 2 * 60 * 60_000L
        const val SESSION_OVER = "انتهت الجلسة"

        /** A month: the URL has no version, so an update that changed a font would be seen within that time. */
        const val FONT_CACHE = "public, max-age=2592000"
        const val MAX_IMAGE_BYTES = 15 * 1024 * 1024

        /** `{"epochMillis":1790000000000}` with room to spare. */
        const val MAX_CLOCK_BODY = 256

        /** The page's own files: nothing else can be read through this path. */
        private val PAGE_FILE = Regex("""index\.html|app\.js|style\.css|views/[a-z0-9-]{1,40}\.js""")

        /** The fonts the page may ask for at /fonts/NAME: Readex Pro for everything, Amiri for the adhkar texts. */
        val FONTS = setOf("readex-pro.ttf", "amiri.ttf")

        /** A plain file name: no folders, no hidden files. */
        val IMAGE_NAME = Regex("""[A-Za-z0-9_-][A-Za-z0-9._-]{0,79}\.(?i:jpg|jpeg|png|webp)""")

        /**
         * [name], or when [taken] has it (ignoring case) the first free "_2", "_3"... before its extension,
         * the base kept within [IMAGE_NAME]'s 80 characters. Chosen on the TV, where the names are now:
         * two phones sending "IMG-20260929-WA0003.jpg" keep both images.
         */
        fun freeImageName(name: String, taken: Collection<String>): String {
            val names = taken.mapTo(HashSet()) { it.lowercase() }
            val base = name.substringBeforeLast('.')
            val extension = name.substring(base.length)
            var candidate = name
            var n = 2
            while (candidate.lowercase() in names) {
                val suffix = "_${n++}"
                candidate = base.take(80 - suffix.length) + suffix + extension
            }
            return candidate
        }

        /** 10 characters from a 31-letter alphabet without look-alikes (about 50 bits), new for every session. */
        fun newToken(random: SecureRandom = SecureRandom()): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            return String(CharArray(10) { alphabet[random.nextInt(alphabet.length)] })
        }
    }
}
