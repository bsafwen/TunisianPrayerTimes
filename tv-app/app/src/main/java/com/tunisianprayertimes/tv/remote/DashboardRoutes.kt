package com.tunisianprayertimes.tv.remote

import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.tv.data.MediaKind
import com.tunisianprayertimes.tv.ui.TvStrings
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

    fun image(kind: MediaKind, name: String): ByteArray?

    /** Stores an uploaded image; null when stored, else what is wrong (Arabic). */
    fun addImage(kind: MediaKind, name: String, bytes: ByteArray): String?

    /** Removes an image, or an announcement .txt file, by its exact listed name. */
    fun deleteImage(kind: MediaKind, name: String): Boolean

    /** Starts installing an available update (GitHub build); the message tells what happens. */
    fun update(): String

    /** The phone's clock says it is [epochMillis] now: the TV's time is set to it. False when it cannot be right. */
    fun setClock(epochMillis: Long): Boolean

    /** The admin says the TV's time is right (it agrees with the phone). False when it cannot be. */
    fun confirmClock(): Boolean

    /** A file of the page itself, from the app's assets (dashboard/…). */
    fun asset(name: String): ByteArray?

    /**
     * A font of the page ([DashboardRoutes.FONTS]: "readex-pro.ttf", "amiri.ttf"), from the app's own
     * font resources, so the page looks like the screen without internet; null for any other name.
     */
    fun font(name: String): ByteArray?
}

/**
 * The dashboard's routes. The page files and its fonts are public (they hold no data); every /api call
 * needs the session [token] shown in the QR code on the TV, so only someone in front of the screen can
 * manage it. The token and the size of a request are checked from its head, before its body is read
 * ([admit]). After [MAX_BAD_TOKENS] wrong tokens the session refuses everything.
 *
 * [now] measures the session's age and idle time, in millis. On the TV it should be the time since boot
 * (SystemClock.elapsedRealtime): the wall clock can be corrected during a session, from this very page,
 * and would end the session at once or stretch it past its limit.
 */
class DashboardRoutes(private val token: String, private val backend: DashboardBackend, private val now: () -> Long = System::currentTimeMillis) {

    private var badTokens = 0

    /** When the session started, and when it was last used with the token (a phone keeping the page open), on [now]'s clock. */
    val startedAt: Long = now()
    @Volatile var lastUsedAt: Long = startedAt
        private set

    /** Whether the session should end: unused for longer than [idleMillis], or older than [maxMillis]. */
    fun isOver(idleMillis: Long, maxMillis: Long): Boolean {
        val at = now()
        return at - lastUsedAt > idleMillis || at - startedAt > maxMillis
    }

    /** Judges a request from its head: the token for /api calls, and how large a body each route takes. */
    fun admit(head: RequestHead): Admission {
        if (!head.path.startsWith("/api/")) {
            return if (head.method == "GET") Admission.Accept(0) else Admission.Reject(error(404, "غير موجود"))
        }
        synchronized(this) {
            if (badTokens >= MAX_BAD_TOKENS) return Admission.Reject(error(403, "الجلسة مغلقة: ابدأ جلسة جديدة من الشاشة"))
            if (!sameToken(head.query["t"].orEmpty())) {
                // An old page left open asks for its images with the previous session's token: not an attack.
                if (!(head.method == "GET" && head.path == "/api/image")) badTokens++
                return Admission.Reject(error(403, "افتح الرابط من رمز QR الظاهر على شاشة المسجد"))
            }
        }
        lastUsedAt = now()
        val maxBody = when (head.method to head.path) {
            "POST" to "/api/image" -> MAX_IMAGE_BYTES
            "POST" to "/api/preview", "POST" to "/api/apply" -> MosqueSettingsFile.MAX_CHARS * 4
            "POST" to "/api/clock" -> MAX_CLOCK_BODY
            else -> 0
        }
        return Admission.Accept(maxBody)
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
        "GET" to "/api/state" -> HttpResponse.json(200, backend.stateJson())
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
            backend.image(kind, name)?.let { HttpResponse(200, imageType(name), it) } ?: error(404, "الصورة غير موجودة")
        }
        "POST" to "/api/image" -> imageRequest(request) { kind, name ->
            val problem = when {
                request.body.isEmpty() -> "الملف فارغ"
                request.body.size > MAX_IMAGE_BYTES -> "الصورة أكبر من 15 ميغابايت"
                else -> backend.addImage(kind, name, request.body)
            }
            result(problem)
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
        val (ok, message) = when {
            body.keys.size != 1 -> return error(400, TvStrings.PHONE_CLOCK_BAD_REQUEST)
            epochMillis != null -> backend.setClock(epochMillis).let { it to if (it) TvStrings.PHONE_CLOCK_SET else TvStrings.PHONE_CLOCK_SET_REFUSED }
            confirm -> backend.confirmClock().let { it to if (it) TvStrings.PHONE_CLOCK_CONFIRMED else TvStrings.PHONE_CLOCK_CONFIRM_REFUSED }
            else -> return error(400, TvStrings.PHONE_CLOCK_BAD_REQUEST)
        }
        return HttpResponse.json(200, buildJsonObject {
            put("ok", ok)
            put("message", message)
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

    private fun sameToken(candidate: String): Boolean =
        MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))

    companion object {
        const val MAX_BAD_TOKENS = 10

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

        /** 10 characters from a 31-letter alphabet without look-alikes (about 50 bits), new for every session. */
        fun newToken(random: SecureRandom = SecureRandom()): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            return String(CharArray(10) { alphabet[random.nextInt(alphabet.length)] })
        }
    }
}
