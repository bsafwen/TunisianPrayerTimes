package com.tunisianprayertimes.quran.assets

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

class QuranPackException(val problem: QuranDownloadProblem, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Downloads a pack's archive from the quran-cdn Worker and unpacks it into [root], only after
 * its size and SHA-256 match the layout. An interrupted download resumes where it stopped.
 */
internal class QuranPackInstaller(private val root: File) {
    /** Named after the archive, so a pack whose files change is downloaded again. */
    fun installedDirectory(pack: QuranPack): File = File(root, "${pack.name}-${pack.archive.sha256.take(12)}")

    fun isInstalled(pack: QuranPack): Boolean = installedDirectory(pack).isDirectory

    private fun partialFile(pack: QuranPack) = File(root, "$DOWNLOADS/${pack.archive.key.substringAfterLast('/')}.part")

    /** Returns the verified archive. [onProgress] receives the bytes downloaded so far. */
    fun download(url: String, pack: QuranPack, onProgress: (Long) -> Unit = {}): File {
        val expected = pack.archive.bytes
        val part = partialFile(pack)
        part.parentFile?.mkdirs()
        if (part.length() > expected) part.delete()
        if (part.length() < expected) {
            if (root.usableSpace < (expected - part.length()) + pack.bytes) {
                throw QuranPackException(QuranDownloadProblem.Storage, "Not enough space for ${pack.name}")
            }
            transfer(url, part, expected, onProgress)
        }
        onProgress(part.length())
        if (part.length() != expected || sha256(part) != pack.archive.sha256) {
            part.delete()
            throw QuranPackException(QuranDownloadProblem.Integrity, "${pack.archive.key} does not match the layout")
        }
        return part
    }

    private fun transfer(url: String, part: File, expected: Long, onProgress: (Long) -> Unit, restarted: Boolean = false) {
        val offset = part.length()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val code = connection.responseCode
            val resumed = offset > 0L && code == HttpURLConnection.HTTP_PARTIAL &&
                connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true
            // Only the server's word that the partial file cannot be continued starts it over;
            // any other failure keeps it, so the next attempt resumes.
            val unresumable = offset > 0L && !resumed && (code == HTTP_RANGE_NOT_SATISFIABLE || code == HttpURLConnection.HTTP_PARTIAL)
            if (unresumable && !restarted) {
                connection.disconnect()
                part.delete()
                return transfer(url, part, expected, onProgress, restarted = true)
            }
            if (code != HttpURLConnection.HTTP_OK && !resumed) {
                throw QuranPackException(QuranDownloadProblem.Network, "HTTP $code for $url")
            }
            var written = if (resumed) offset else 0L
            connection.inputStream.use { input ->
                FileOutputStream(part, resumed).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (written < expected) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Download interrupted")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written)
                    }
                }
            }
        } catch (error: QuranPackException) {
            throw error
        } catch (error: IOException) {
            val problem = if (root.usableSpace < BUFFER) QuranDownloadProblem.Storage else QuranDownloadProblem.Network
            throw QuranPackException(problem, "Could not download $url", error)
        } finally {
            connection.disconnect()
        }
    }

    /** Unpacks exactly the files the layout lists, then moves them into place in one step. */
    fun install(archive: File, pack: QuranPack): File {
        val target = installedDirectory(pack)
        if (target.isDirectory) return target
        val staging = File(root, "$INSTALLS/${target.name}")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw QuranPackException(QuranDownloadProblem.Storage, "Cannot create $staging")
        val expected = pack.files.associate { it.path to it.bytes }
        val stagingPath = staging.canonicalPath + File.separator
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().toList().filterNot { it.isDirectory }
                if (entries.map { it.name }.sorted() != expected.keys.sorted()) {
                    throw QuranPackException(QuranDownloadProblem.Integrity, "${archive.name} holds other files than ${pack.name}")
                }
                entries.forEach { entry ->
                    val destination = File(staging, entry.name)
                    if (!destination.canonicalPath.startsWith(stagingPath) || entry.size != expected.getValue(entry.name)) {
                        throw QuranPackException(QuranDownloadProblem.Integrity, "Unexpected entry ${entry.name} in ${archive.name}")
                    }
                    destination.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> destination.outputStream().use(input::copyTo) }
                    if (destination.length() != entry.size) {
                        throw QuranPackException(QuranDownloadProblem.Integrity, "Truncated entry ${entry.name}")
                    }
                }
            }
        } catch (error: QuranPackException) {
            staging.deleteRecursively()
            throw error
        } catch (error: IOException) {
            staging.deleteRecursively()
            throw QuranPackException(QuranDownloadProblem.Storage, "Could not unpack ${archive.name}", error)
        }
        // Whatever happened to the folder meanwhile, only a complete pack goes into place.
        if (pack.files.any { File(staging, it.path).length() != it.bytes }) {
            staging.deleteRecursively()
            throw QuranPackException(QuranDownloadProblem.Integrity, "Incomplete unpack of ${pack.name}")
        }
        if (!staging.renameTo(target)) {
            staging.deleteRecursively()
            throw QuranPackException(QuranDownloadProblem.Storage, "Could not move ${pack.name} into place")
        }
        archive.delete()
        return target
    }

    fun remove(pack: QuranPack) {
        installedDirectory(pack).deleteRecursively()
        partialFile(pack).delete()
    }

    /**
     * Deletes earlier versions of packs and downloads the current layout no longer lists. Work on a
     * current pack is left alone: a download worker may be writing or unpacking it right now.
     */
    fun removeStale(layout: QuranPackLayout) {
        val current = layout.packs.map { installedDirectory(it).name }.toSet()
        val archives = layout.packs.map { partialFile(it).name }.toSet()
        root.listFiles()?.forEach { file ->
            if (file.name != DOWNLOADS && file.name != INSTALLS && file.name !in current) file.deleteRecursively()
        }
        File(root, INSTALLS).listFiles()?.forEach { if (it.name !in current) it.deleteRecursively() }
        File(root, DOWNLOADS).listFiles()?.forEach { if (it.name !in archives) it.delete() }
    }

    private companion object {
        const val DOWNLOADS = ".downloads"
        const val INSTALLS = ".installs"
        const val BUFFER = 64 * 1024
        const val HTTP_RANGE_NOT_SATISFIABLE = 416

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
