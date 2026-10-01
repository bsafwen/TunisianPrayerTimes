package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.mosque.TextAnnouncement
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnnouncementTextTest {

    private val text = "درس بعد صلاة العشاء"

    @Test
    fun filesAreReadHoweverNotepadOrAPhoneSavedThem() {
        assertEquals(text, AnnouncementText.fromBytes(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, AnnouncementText.fromBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, AnnouncementText.fromBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, AnnouncementText.fromBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)))
        // Notepad's "ANSI" on an Arabic Windows.
        assertEquals(text, AnnouncementText.fromBytes(text.toByteArray(Charset.forName("windows-1256"))))
    }

    @Test
    fun theTextIsPutOnOneLineAndKeptShort() {
        assertEquals("درس بعد صلاة العشاء", AnnouncementText.fromBytes("  درس\r\nبعد صلاة\n\n\tالعشاء \n".toByteArray()))
        val long = AnnouncementText.fromBytes("ب ".repeat(400).toByteArray())!!
        assertEquals(TextAnnouncement.MAX_LENGTH, long.length)
        assertEquals('…', long.last())
    }

    @Test
    fun emptyOrBinaryFilesAreNotAnnouncements() {
        assertNull(AnnouncementText.fromBytes(" \n\t ".toByteArray()))
        assertNull(AnnouncementText.fromBytes(ByteArray(0)))
        assertNull(AnnouncementText.fromBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)))
    }
}
