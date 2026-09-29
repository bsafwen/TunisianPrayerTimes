package com.tunisianprayertimes.tv.remote

import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

data class HttpRequest(val method: String, val path: String, val query: Map<String, String>, val body: String)

data class HttpResponse(val status: Int, val contentType: String, val body: String)

/** What the phone page can do: read the TV's settings file, check an edited one, apply it. */
interface PhoneAdminBackend {
    fun currentFile(): String
    fun preview(text: String): ParseResult
    fun apply(text: String): Boolean

    /** The admin-readable lines for a checked file: its changes, or its mistakes. */
    fun describe(result: ParseResult): List<String>
}

/**
 * The phone page and its three calls. Every request needs the session [token] shown in the QR code
 * on the TV, so only someone in front of the screen can change it; after [MAX_BAD_TOKENS] wrong
 * tokens the session refuses everything (the admin starts a new one from the TV).
 */
class PhoneAdminRoutes(private val token: String, private val backend: PhoneAdminBackend) {

    private var badTokens = 0

    @Synchronized
    fun handle(request: HttpRequest): HttpResponse {
        if (badTokens >= MAX_BAD_TOKENS) return text(403, "الجلسة مغلقة: ابدأ جلسة جديدة من الشاشة")
        if (!sameToken(request.query["t"].orEmpty())) {
            badTokens++
            return text(403, "افتح الرابط من رمز QR الظاهر على شاشة المسجد")
        }
        return when (request.method to request.path) {
            "GET" to "/" -> HttpResponse(200, "text/html; charset=utf-8", PhoneAdminPage.html(token))
            "GET" to "/settings" -> text(200, backend.currentFile())
            "POST" to "/preview" -> checked(request.body) { result -> text(200, (listOf(status(result)) + backend.describe(result)).joinToString("\n")) }
            "POST" to "/apply" -> checked(request.body) { result ->
                if (result is ParseResult.Success && backend.apply(request.body)) text(200, "OK\nطُبّقت الإعدادات على الشاشة")
                else text(200, (listOf("ERROR") + backend.describe(result)).joinToString("\n"))
            }
            else -> text(404, "غير موجود")
        }
    }

    private fun checked(body: String, respond: (ParseResult) -> HttpResponse): HttpResponse =
        if (body.length > MAX_BODY_CHARS) text(413, "الملف كبير جدًا") else respond(backend.preview(body))

    private fun status(result: ParseResult) = if (result is ParseResult.Success) "OK" else "ERROR"

    private fun sameToken(candidate: String): Boolean =
        MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))

    private fun text(status: Int, body: String) = HttpResponse(status, "text/plain; charset=utf-8", body)

    companion object {
        const val MAX_BAD_TOKENS = 10
        const val MAX_BODY_CHARS = 64 * 1024

        /** 10 characters from a 32-letter alphabet without look-alikes (50 bits), new for every session. */
        fun newToken(random: SecureRandom = SecureRandom()): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            return String(CharArray(10) { alphabet[random.nextInt(alphabet.length)] })
        }
    }
}

/**
 * A minimal HTTP server on the local network: one request per connection, small bodies, no files
 * served. Enough for a phone browser on the mosque's Wi-Fi or on a phone hotspot, without internet.
 */
class PhoneAdminServer(private val handle: (HttpRequest) -> HttpResponse) {

    @Volatile private var socket: ServerSocket? = null

    /** When the last request arrived (System.currentTimeMillis), so an idle session can be closed. */
    @Volatile var lastRequestAt: Long = System.currentTimeMillis()
        private set

    /** Listens on [preferredPort], or any free port; returns the port. */
    fun start(preferredPort: Int = 8080): Int {
        val server = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(preferredPort)) } }
            .getOrElse { ServerSocket(0) }
        socket = server
        lastRequestAt = System.currentTimeMillis()
        thread(name = "phone-admin", isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(name = "phone-admin-request", isDaemon = true) { serve(client) }
            }
        }
        return server.localPort
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    val isRunning: Boolean get() = socket?.isClosed == false

    private fun serve(client: Socket) {
        client.use {
            runCatching {
                it.soTimeout = 10_000
                val request = readRequest(it.getInputStream()) ?: return@runCatching
                lastRequestAt = System.currentTimeMillis()
                writeResponse(it.getOutputStream(), handle(request))
            }
        }
    }

    companion object {
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val HEADER_END = 0x0D0A0D0A // CR LF CR LF

        /** The request line, the query string and a body of Content-Length bytes; null when malformed. */
        fun readRequest(input: InputStream): HttpRequest? = try {
            parse(input)
        } catch (e: SocketTimeoutException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        private fun parse(input: InputStream): HttpRequest? {
            val stream = BufferedInputStream(input)
            val head = ByteArrayOutputStream()
            var last4 = 0 // the last four bytes read: the headers end with a blank line
            while (last4 != HEADER_END) {
                val byte = stream.read()
                if (byte < 0) return null
                head.write(byte)
                if (head.size() > MAX_HEADER_BYTES) return null
                last4 = (last4 shl 8) or byte
            }
            val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
            val (method, target) = lines.first().split(" ").let { if (it.size < 2) return null else it[0] to it[1] }
            val length = lines.drop(1).firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            if (length !in 0..MAX_BODY_BYTES) return null
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = stream.read(body, read, length - read)
                if (count < 0) return null
                read += count
            }
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate { pair ->
                URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
            }
            return HttpRequest(method.uppercase(), path, query, String(body, Charsets.UTF_8))
        }

        fun writeResponse(output: OutputStream, response: HttpResponse) {
            val body = response.body.toByteArray(Charsets.UTF_8)
            val reason = when (response.status) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Error" }
            val head = "HTTP/1.0 ${response.status} $reason\r\n" +
                "Content-Type: ${response.contentType}\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            output.write(body)
            output.flush()
        }

        /** The TV's IPv4 addresses on the local network (Wi-Fi, Ethernet or a phone's hotspot). */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress.orEmpty() }
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }
}
