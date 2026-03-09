package com.qaloon.reciter

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Uploads voice contributions to the Cloudflare Worker → R2 pipeline.
 * Uses HttpURLConnection (no extra deps) with multipart/form-data.
 */
class ContributionUploader(context: Context) {

    companion object {
        private const val UPLOAD_URL = "https://mawaqittn.safwen-baroudi.workers.dev/api/contribute"
    }

    private val prefs = context.getSharedPreferences("qaloon_contributor", Context.MODE_PRIVATE)

    /** Persistent anonymous contributor ID (UUID, generated once). */
    val contributorId: String
        get() {
            var id = prefs.getString("contributor_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString().take(12)
                prefs.edit().putString("contributor_id", id).apply()
            }
            return id
        }

    /**
     * Upload a WAV recording to the cloud.
     * @return true on success, false on failure
     */
    fun upload(
        wavBytes: ByteArray,
        surah: Int,
        ayah: Int,
        text: String
    ): Boolean {
        val boundary = "----QaloonBoundary${System.currentTimeMillis()}"
        val url = URL(UPLOAD_URL)
        val conn = url.openConnection() as HttpURLConnection

        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("X-Contributor-Id", contributorId)
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000

            val body = buildMultipart(boundary, wavBytes, surah, ayah, text)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }

            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun buildMultipart(
        boundary: String,
        wavBytes: ByteArray,
        surah: Int,
        ayah: Int,
        text: String
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        val crlf = "\r\n"
        val sep = "--$boundary$crlf"

        // audio field
        dos.writeBytes(sep)
        dos.writeBytes("Content-Disposition: form-data; name=\"audio\"; filename=\"recording.wav\"$crlf")
        dos.writeBytes("Content-Type: audio/wav$crlf$crlf")
        dos.write(wavBytes)
        dos.writeBytes(crlf)

        // surah field
        dos.writeBytes(sep)
        dos.writeBytes("Content-Disposition: form-data; name=\"surah\"$crlf$crlf")
        dos.writeBytes("$surah$crlf")

        // ayah field
        dos.writeBytes(sep)
        dos.writeBytes("Content-Disposition: form-data; name=\"ayah\"$crlf$crlf")
        dos.writeBytes("$ayah$crlf")

        // text field
        dos.writeBytes(sep)
        dos.writeBytes("Content-Disposition: form-data; name=\"text\"$crlf$crlf")
        dos.writeBytes("$text$crlf")

        // end
        dos.writeBytes("--$boundary--$crlf")
        dos.flush()
        return baos.toByteArray()
    }
}
