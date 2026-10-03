package com.tunisianprayertimes.quran.assets

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class QuranPackInstallerTest {
    @get:Rule val folder = TemporaryFolder()

    // A minimal HTTP/1.1 server: the JDK's own is not on Android's unit-test classpath.
    private lateinit var server: ServerSocket
    private var served = ByteArray(0)
    private var honoursRange = true
    private val requestedRanges = mutableListOf<String?>()

    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { client ->
                    val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.drop(1).toList()
                    val range = headers.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')?.trim()
                    requestedRanges += range
                    val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
                    val out = client.getOutputStream()
                    fun respond(status: String, body: ByteArray, extra: String = "") {
                        out.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
                        out.write(body)
                        out.flush()
                    }
                    when {
                        start != null && honoursRange && start < served.size -> respond(
                            "206 Partial Content", served.copyOfRange(start, served.size),
                            "Content-Range: bytes $start-${served.size - 1}/${served.size}\r\n",
                        )
                        start != null && honoursRange -> respond("416 Range Not Satisfiable", ByteArray(0))
                        else -> respond("200 OK", served)
                    }
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    private val url get() = "http://127.0.0.1:${server.localPort}/pack.zip"

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = content.size.toLong()
                    crc = CRC32().apply { update(content) }.value
                })
                out.write(content)
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val page1 = ByteArray(3_000) { (it % 7).toByte() }
    private val page2 = ByteArray(5_000) { (it % 11).toByte() }

    private fun pack(archive: ByteArray, files: List<Pair<String, Int>> = listOf("quran/pages/1.webp" to page1.size, "quran/pages/2.webp" to page2.size)) =
        QuranPack(
            "quran_pages", QuranPack.Kind.Pages, QuranPack.Delivery.Play, null, null, files.sumOf { it.second.toLong() },
            QuranPackArchive("v1/packs/quran_pages-${sha256(archive).take(12)}.zip", archive.size.toLong(), sha256(archive)),
            files.map { QuranPackFile(it.first, it.second.toLong()) },
        )

    @Test
    fun downloadsVerifiesAndInstallsInOneStep() {
        served = zip("quran/pages/1.webp" to page1, "quran/pages/2.webp" to page2)
        val pack = pack(served)
        val installer = QuranPackInstaller(folder.root)
        var lastProgress = 0L
        val installed = installer.install(installer.download(url, pack) { lastProgress = it }, pack)
        assertEquals(served.size.toLong(), lastProgress)
        assertTrue(installer.isInstalled(pack))
        assertEquals(installer.installedDirectory(pack), installed)
        assertArrayEquals(page2, File(installed, "quran/pages/2.webp").readBytes())
        // The archive is not kept next to the files it held.
        assertEquals(listOf(installed.name), folder.root.listFiles()!!.filter { it.isDirectory && !it.name.startsWith(".") }.map { it.name })
        assertTrue(File(folder.root, ".downloads").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun anInterruptedDownloadResumesWhereItStopped() {
        served = zip("quran/pages/1.webp" to page1, "quran/pages/2.webp" to page2)
        val pack = pack(served)
        val partial = File(folder.root, ".downloads/${pack.archive.key.substringAfterLast('/')}.part")
        partial.parentFile!!.mkdirs()
        partial.writeBytes(served.copyOf(4_000))
        val installer = QuranPackInstaller(folder.root)
        installer.install(installer.download(url, pack), pack)
        assertEquals(listOf("bytes=4000-"), requestedRanges)
        assertTrue(installer.isInstalled(pack))
    }

    @Test
    fun aServerThatCannotResumeStartsTheDownloadAgain() {
        served = zip("quran/pages/1.webp" to page1, "quran/pages/2.webp" to page2)
        honoursRange = false
        val pack = pack(served)
        val partial = File(folder.root, ".downloads/${pack.archive.key.substringAfterLast('/')}.part")
        partial.parentFile!!.mkdirs()
        partial.writeBytes(ByteArray(4_000) { 9 })  // Not even the right bytes.
        val installer = QuranPackInstaller(folder.root)
        installer.install(installer.download(url, pack), pack)
        assertArrayEquals(page1, File(installer.installedDirectory(pack), "quran/pages/1.webp").readBytes())
    }

    @Test
    fun aChangedArchiveIsRejectedAndForgotten() {
        val expected = zip("quran/pages/1.webp" to page1, "quran/pages/2.webp" to page2)
        served = zip("quran/pages/1.webp" to page2, "quran/pages/2.webp" to page1)
        val pack = pack(expected)
        val installer = QuranPackInstaller(folder.root)
        val error = assertThrows(QuranPackException::class.java) { installer.download(url, pack) }
        assertEquals(QuranDownloadProblem.Integrity, error.problem)
        assertFalse(installer.isInstalled(pack))
        assertTrue(File(folder.root, ".downloads").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun archivesWithOtherFilesThanTheLayoutAreNotUnpacked() {
        val installer = QuranPackInstaller(folder.root)
        val escaping = zip("quran/pages/1.webp" to page1, "../../shared_prefs/x.xml" to page2)
        val extra = zip("quran/pages/1.webp" to page1, "quran/pages/2.webp" to page2, "quran/pages/3.webp" to page1)
        val resized = zip("quran/pages/1.webp" to page1, "quran/pages/2.webp" to page1)
        val cases = listOf(
            escaping to listOf("quran/pages/1.webp" to page1.size, "../../shared_prefs/x.xml" to page2.size),
            extra to listOf("quran/pages/1.webp" to page1.size, "quran/pages/2.webp" to page2.size),
            resized to listOf("quran/pages/1.webp" to page1.size, "quran/pages/2.webp" to page2.size),
        )
        cases.forEach { (archive, files) ->
            val file = folder.newFile().apply { writeBytes(archive) }
            val error = assertThrows(QuranPackException::class.java) { installer.install(file, pack(archive, files)) }
            assertEquals(QuranDownloadProblem.Integrity, error.problem)
        }
        assertFalse(File(folder.root.parentFile, "shared_prefs/x.xml").exists())
        assertTrue(folder.root.listFiles()!!.none { it.isDirectory && it.name.startsWith("quran_pages") })
    }

    @Test
    fun earlierVersionsOfPacksAreDeleted() {
        served = zip("quran/pages/1.webp" to page1, "quran/pages/2.webp" to page2)
        val current = pack(served)
        val installer = QuranPackInstaller(folder.root)
        installer.install(installer.download(url, current), current)
        File(folder.root, "quran_pages-000000000000/quran/pages").mkdirs()
        File(folder.root, ".downloads/quran_hosary_99-0123456789ab.zip.part").apply { parentFile!!.mkdirs(); writeText("x") }
        installer.removeStale(QuranPackLayout("https://cdn.example/", listOf(current)))
        assertEquals(listOf(installer.installedDirectory(current).name),
            folder.root.listFiles()!!.filter { !it.name.startsWith(".") }.map { it.name })
        assertTrue(File(folder.root, ".downloads").listFiles().orEmpty().isEmpty())
        installer.remove(current)
        assertFalse(installer.isInstalled(current))
    }
}
