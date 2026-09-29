package com.tunisianprayertimes.tv.remote

import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.tv.data.MediaKind
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

    /** A file of the page itself, from the app's assets (dashboard/…). */
    fun asset(name: String): ByteArray?
}

/**
 * The dashboard's routes. The page files are public (they hold no data); every /api call needs the
 * session [token] shown in the QR code on the TV, so only someone in front of the screen can manage
 * it. The token and the size of a request are checked from its head, before its body is read
 * ([admit]). After [MAX_BAD_TOKENS] wrong tokens the session refuses everything.
 */
class DashboardRoutes(private val token: String, private val backend: DashboardBackend, private val now: () -> Long = System::currentTimeMillis) {

    private var badTokens = 0

    /** When the session started, and when it was last used with the token (a phone keeping the page open). */
    val startedAt: Long = now()
    @Volatile var lastUsedAt: Long = startedAt
        private set

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
        else -> error(404, "غير موجود")
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
        const val MAX_IMAGE_BYTES = 15 * 1024 * 1024

        /** The page's own files: nothing else can be read through this path. */
        private val PAGE_FILE = Regex("""index\.html|app\.js|style\.css|views/[a-z0-9-]{1,40}\.js""")

        /** A plain file name: no folders, no hidden files. */
        val IMAGE_NAME = Regex("""[A-Za-z0-9_-][A-Za-z0-9._-]{0,79}\.(?i:jpg|jpeg|png|webp)""")

        /** 10 characters from a 31-letter alphabet without look-alikes (about 50 bits), new for every session. */
        fun newToken(random: SecureRandom = SecureRandom()): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            return String(CharArray(10) { alphabet[random.nextInt(alphabet.length)] })
        }
    }
}
