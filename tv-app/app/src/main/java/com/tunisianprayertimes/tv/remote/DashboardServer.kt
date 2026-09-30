package com.tunisianprayertimes.tv.remote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The request line and headers, read before anything else, so a request is judged before its body is read. [remote]: the client's address. */
class RequestHead(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val remote: String = "") {
    val contentLength: Int get() = headers["content-length"]?.toIntOrNull() ?: 0
}

class HttpRequest(val method: String, val path: String, val query: Map<String, String>, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    val text: String get() = String(body, Charsets.UTF_8)
}

/**
 * [cacheControl]: never stored, except the fonts, which don't change with the settings. A [file] (an
 * image, up to 15 MB) is streamed to the socket instead of [body], never held whole in memory.
 */
class HttpResponse(val status: Int, val contentType: String, val body: ByteArray, val cacheControl: String = "no-store", val file: File? = null) {
    val text: String get() = String(body, Charsets.UTF_8)

    /** The bytes to send, for the write's time limit. */
    val length: Long get() = file?.length() ?: body.size.toLong()

    companion object {
        fun file(contentType: String, file: File) = HttpResponse(200, contentType, ByteArray(0), file = file)

        fun text(status: Int, text: String) = HttpResponse(status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))
        fun json(status: Int, json: String) = HttpResponse(status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
    }
}

/** Whether a request may go on, and how large a body it may send; decided from its head alone. */
sealed interface Admission {
    data class Accept(val maxBody: Int) : Admission
    data class Reject(val response: HttpResponse) : Admission
}

/**
 * A minimal HTTP/1.0 server on the local network for the dashboard: a phone or laptop on the mosque's
 * Wi-Fi or on a phone hotspot, without internet. A few connections at a time, each request judged
 * from its head before any body is read, each with a total time limit, and all of them closed when
 * the session stops, so no one on the network can exhaust the TV or keep a stopped session open.
 * Every time limit is measured on System.nanoTime: the page itself may correct the wall clock.
 */
class DashboardServer(
    private val admit: (RequestHead) -> Admission,
    private val handle: (HttpRequest) -> HttpResponse,
) {
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val clients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    /** Cuts a connection that outlives its total time limit: a phone gone mid-transfer would block a write for many minutes. */
    private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "dashboard-watchdog").apply { isDaemon = true } }
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var closed = false

    /** Listens on [preferredPort], or any free port; returns the port. */
    fun start(preferredPort: Int = 8080): Int {
        val server = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(preferredPort)) } }
            .getOrElse { ServerSocket(0) }
        socket = server
        thread(name = "dashboard", isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                if (closed) {
                    runCatching { client.close() }
                    continue
                }
                // Busy: a slot usually frees within seconds (an image sent); else the page is told to retry.
                if (!slots.tryAcquire(BUSY_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    answerBusy(client)
                    continue
                }
                clients += client
                runCatching {
                    thread(name = "dashboard-request", isDaemon = true) {
                        try {
                            serve(client)
                        } finally {
                            clients -= client
                            slots.release()
                        }
                    }
                }.onFailure {
                    clients -= client
                    slots.release()
                    runCatching { client.close() }
                }
            }
        }
        return server.localPort
    }

    /** Ends the session: no new connection, and the open ones are cut, whatever they were sending. */
    fun stop() {
        closed = true
        runCatching { socket?.close() }
        socket = null
        clients.toList().forEach { runCatching { it.close() } }
        watchdog.shutdownNow()
    }

    val isRunning: Boolean get() = !closed && socket?.isClosed == false

    private fun serve(client: Socket) {
        // Reading and handling: the head's and the body's own limits, and room for the handler.
        var cut = cutAfter(client, HEAD_DEADLINE_MILLIS + BODY_DEADLINE_MILLIS + HANDLER_DEADLINE_MILLIS)
        client.use {
            runCatching {
                it.soTimeout = READ_TIMEOUT_MILLIS
                val stream = BufferedInputStream(it.getInputStream())
                val head = readHead(stream, deadlineIn(HEAD_DEADLINE_MILLIS), it.inetAddress?.hostAddress.orEmpty()) ?: return@runCatching
                if (closed) return@runCatching
                val response = when (val admission = admit(head)) {
                    is Admission.Reject -> admission.response
                    is Admission.Accept -> {
                        if (head.contentLength !in 0..admission.maxBody) {
                            HttpResponse.json(413, """{"error":"الطلب كبير جدًا"}""")
                        } else {
                            val body = readBody(stream, head.contentLength, deadlineIn(BODY_DEADLINE_MILLIS))
                                ?: return@runCatching
                            if (closed) return@runCatching
                            runCatching { handle(HttpRequest(head.method, head.path, head.query, body, head.headers)) }
                                .getOrElse { HttpResponse.json(500, """{"error":"خطأ في الشاشة"}""") }
                        }
                    }
                }
                cut?.cancel(false)
                cut = cutAfter(it, writeDeadlineMillis(response.length))
                writeResponse(it.getOutputStream(), response)
            }
        }
        cut?.cancel(false)
    }

    private fun cutAfter(client: Socket, millis: Long): ScheduledFuture<*>? =
        runCatching { watchdog.schedule({ runCatching { client.close() } }, millis, TimeUnit.MILLISECONDS) }.getOrNull()

    /** Tells a client that all the slots stayed busy: the page retries on 503, since nothing was done. */
    private fun answerBusy(client: Socket) {
        runCatching {
            client.use {
                // What it already sent is dropped unread: closing on unread bytes could reset the connection before the answer.
                it.getInputStream().let { input -> input.skip(input.available().toLong()) }
                writeResponse(it.getOutputStream(), HttpResponse.json(503, BUSY_JSON))
                it.shutdownOutput()
            }
        }
    }

    companion object {
        private const val MAX_HEADER_BYTES = 8 * 1024
        const val MAX_BODY_BYTES = 16 * 1024 * 1024
        const val MAX_CONNECTIONS = 6
        private const val READ_TIMEOUT_MILLIS = 5_000
        private const val HEAD_DEADLINE_MILLIS = 15_000L
        private const val BODY_DEADLINE_MILLIS = 120_000L
        private const val HANDLER_DEADLINE_MILLIS = 30_000L
        private const val BUSY_WAIT_MILLIS = 5_000L
        private const val HEADER_END = 0x0D0A0D0A // CR LF CR LF

        /** Sending an answer: a base, plus its size at a slow hotspot's 32 KB/s. */
        private const val WRITE_BASE_MILLIS = 15_000L
        private const val WRITE_MIN_BYTES_PER_SECOND = 32 * 1024L

        const val BUSY_JSON = """{"error":"الشاشة مشغولة، أعد المحاولة","busy":true}"""

        fun writeDeadlineMillis(bytes: Long): Long = WRITE_BASE_MILLIS + bytes * 1000 / WRITE_MIN_BYTES_PER_SECOND

        /** A deadline [millis] from now, on System.nanoTime, which a corrected wall clock does not move. */
        fun deadlineIn(millis: Long): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)

        /** A whole request with a body of at most [MAX_BODY_BYTES] (for tests and simple callers); null when malformed. */
        fun readRequest(input: InputStream): HttpRequest? {
            val stream = BufferedInputStream(input)
            val head = readHead(stream, Long.MAX_VALUE) ?: return null
            if (head.contentLength !in 0..MAX_BODY_BYTES) return null
            val body = readBody(stream, head.contentLength, Long.MAX_VALUE) ?: return null
            return HttpRequest(head.method, head.path, head.query, body, head.headers)
        }

        /** The request line and headers, or null when malformed, too long or too slow ([deadline] on System.nanoTime, see [deadlineIn]). */
        fun readHead(stream: InputStream, deadline: Long, remote: String = ""): RequestHead? {
            try {
                val head = ByteArrayOutputStream()
                var last4 = 0 // the last four bytes read: the headers end with a blank line
                while (last4 != HEADER_END) {
                    val byte = stream.read()
                    if (byte < 0 || head.size() >= MAX_HEADER_BYTES || System.nanoTime() > deadline) return null
                    head.write(byte)
                    last4 = (last4 shl 8) or byte
                }
                val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
                val parts = lines.first().split(" ")
                if (parts.size < 2) return null
                val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                if (headers.containsKey("content-length") && headers["content-length"]?.toIntOrNull() == null) return null
                val target = parts[1]
                val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate { pair ->
                    URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
                }
                return RequestHead(parts[0].uppercase(), URLDecoder.decode(target.substringBefore('?'), "UTF-8"), query, headers, remote)
            } catch (e: SocketTimeoutException) {
                return null
            } catch (e: IllegalArgumentException) {
                return null
            }
        }

        /** Exactly [length] bytes, or null when the client stops or is too slow ([deadline] on System.nanoTime). */
        fun readBody(stream: InputStream, length: Int, deadline: Long): ByteArray? {
            val body = ByteArray(length)
            var read = 0
            try {
                while (read < length) {
                    if (System.nanoTime() > deadline) return null
                    val count = stream.read(body, read, minOf(64 * 1024, length - read))
                    if (count < 0) return null
                    read += count
                }
            } catch (e: SocketTimeoutException) {
                return null
            }
            return body
        }

        fun writeResponse(output: OutputStream, response: HttpResponse) {
            val file = response.file
            if (file == null) {
                writeResponse(output, response, response.body.size.toLong()) { it.write(response.body) }
                return
            }
            // The length of the file opened: an image replaced meanwhile is renamed over, and this one stays whole.
            FileInputStream(file).use { input ->
                writeResponse(output, response, input.channel.size()) { input.copyTo(it, 64 * 1024) }
            }
        }

        private fun writeResponse(output: OutputStream, response: HttpResponse, length: Long, writeBody: (OutputStream) -> Unit) {
            val reason = when (response.status) {
                200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; 503 -> "Service Unavailable"; else -> "Error"
            }
            val head = "HTTP/1.0 ${response.status} $reason\r\n" +
                "Content-Type: ${response.contentType}\r\n" +
                "Content-Length: $length\r\n" +
                "Cache-Control: ${response.cacheControl}\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Referrer-Policy: no-referrer\r\n" +
                "Connection: close\r\n\r\n"
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            writeBody(output)
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
