package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.mosque.TextAnnouncement
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Written announcements from .txt files, however the admin saved them: UTF-8 (phones, recent
 * Notepad), UTF-16 with a byte-order mark (Notepad's "Unicode"), or Windows Arabic (Notepad's "ANSI"
 * on an Arabic Windows). The text is put on one line, as the ticker and the slides show it.
 */
object AnnouncementText {

    /** The file's text on one line, at most [TextAnnouncement.MAX_LENGTH] characters; null when it is not text or is empty. */
    fun fromBytes(bytes: ByteArray): String? {
        val text = decode(bytes) ?: return null
        if (text.any { it < ' ' && it != '\n' && it != '\r' && it != '\t' }) return null // a renamed binary file
        return oneLine(text).takeIf { it.isNotEmpty() }
    }

    fun decode(bytes: ByteArray): String? = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> strict(Charsets.UTF_8, bytes, 3)
        bytes.startsWith(0xFF, 0xFE) -> strict(Charsets.UTF_16LE, bytes, 2)
        bytes.startsWith(0xFE, 0xFF) -> strict(Charsets.UTF_16BE, bytes, 2)
        else -> strict(Charsets.UTF_8, bytes, 0) ?: WINDOWS_ARABIC?.let { strict(it, bytes, 0) }
    }

    fun oneLine(text: String): String {
        val line = text.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
        return if (line.length <= TextAnnouncement.MAX_LENGTH) line else line.take(TextAnnouncement.MAX_LENGTH - 1).trimEnd() + "…"
    }

    private fun strict(charset: Charset, bytes: ByteArray, offset: Int): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }

    private val WHITESPACE = Regex("""\s+""")
    private val WINDOWS_ARABIC: Charset? = runCatching { Charset.forName("windows-1256") }.getOrNull()
}
