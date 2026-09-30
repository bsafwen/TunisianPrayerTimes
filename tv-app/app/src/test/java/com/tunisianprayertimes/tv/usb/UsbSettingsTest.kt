package com.tunisianprayertimes.tv.usb

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.IqamahRule
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.mosque.PrayerSettings
import java.io.File
import java.nio.file.Files
import java.time.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSettingsTest {

    private val root: File = Files.createTempDirectory("usb").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** A key laid out as Android mounts it: this app's folder lives under Android/data on the key. */
    private fun volume(name: String) = RemovableVolume(File(root, "$name/Android/data/com.tunisianprayertimes.tv/files"))

    private fun File.put(text: String, modified: Long = System.currentTimeMillis()): File = apply {
        parentFile!!.mkdirs()
        writeText(text)
        setLastModified(modified)
    }

    private val ishaAt20 = """{ "prayers": { "isha": { "iqamah": "20:00", "duration": 12 } } }"""

    @Test
    fun theFilesInAppFoldersComeNewestFirst() {
        val key = volume("key")
        val other = volume("other")
        assertEquals(emptyList<File>(), UsbSettings.files(listOf(key, other)))
        val older = UsbSettings.settingsFile(key).put(ishaAt20)
        assertEquals(listOf(older), UsbSettings.files(listOf(key, other)))
        val newer = UsbSettings.settingsFile(other).put(ishaAt20, modified = older.lastModified() + 60_000)
        assertEquals(listOf(newer, older), UsbSettings.files(listOf(key, other)))
    }

    @Test
    fun anEmptyFileCountsAsAbsent() {
        // A key pulled out mid-write leaves an empty file behind; it gets a fresh template.
        val key = volume("key")
        UsbSettings.settingsFile(key).put("")
        assertEquals(emptyList<File>(), UsbSettings.files(listOf(key)))
        val (written, _) = UsbSettings.writeTemplates(listOf(key), MosqueSettingsFile.write(MosqueSchedule.DEFAULT))
        assertEquals(listOf(UsbSettings.settingsFile(key)), written)
        assertTrue(UsbSettings.settingsFile(key).length() > 0)
    }

    @Test
    fun theSignatureFollowsTheContent() {
        val file = UsbSettings.settingsFile(volume("key")).put(ishaAt20)
        val first = UsbSettings.read(file)!!
        assertEquals(ishaAt20, first.text)
        assertEquals(first.signature, UsbSettings.read(file)!!.signature)
        file.put(ishaAt20.replace("12", "13"))
        assertNotEquals(first.signature, UsbSettings.read(file)!!.signature)
        assertNull(UsbSettings.read(File(root, "missing.json")))
    }

    @Test
    fun templatesGoOnlyToKeysWithoutAFileAndLeaveNoTempFile() {
        val empty = volume("empty")
        val configured = volume("configured")
        val existing = UsbSettings.settingsFile(configured).put(ishaAt20)
        val current = MosqueSchedule.DEFAULT.with(Prayer.FAJR, PrayerSettings(IqamahRule.AfterAdhan(25), 9))

        val (written, signature) = UsbSettings.writeTemplates(listOf(empty, configured), MosqueSettingsFile.write(current))

        assertEquals(listOf(UsbSettings.settingsFile(empty)), written)
        assertEquals(ishaAt20, existing.readText())
        assertFalse(File(empty.appFolder, "${MosqueSettingsFile.FILE_NAME}.tmp").exists())
        val template = UsbSettings.read(written.single())!!
        assertEquals(signature, template.signature)
        val parsed = MosqueSettingsFile.parse(template.text, MosqueSchedule.DEFAULT) as ParseResult.Success
        assertEquals(current.settings(Prayer.FAJR), parsed.schedule.settings(Prayer.FAJR))
        assertEquals(PrayerSettings(IqamahRule.FixedTime(LocalTime.of(20, 0)), 12), (MosqueSettingsFile.parse(ishaAt20, current) as ParseResult.Success).schedule.settings(Prayer.ISHA))
    }

    @Test
    fun aFileSavedByNotepadInAnyEncodingIsRead() {
        val file = UsbSettings.settingsFile(volume("key")).put("")
        val arabic = """{ "mosque": { "name": "مسجد النور" } }"""
        val encodings = listOf(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + arabic.toByteArray(Charsets.UTF_8), // UTF-8 with its mark
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + arabic.toByteArray(Charsets.UTF_16LE), // Notepad's "Unicode"
            arabic.toByteArray(charset("windows-1256")), // Notepad's "ANSI" on an Arabic Windows
        )
        for (bytes in encodings) {
            file.writeBytes(bytes)
            assertEquals(arabic, UsbSettings.read(file)!!.text)
        }
        // UTF-16 without its mark cannot be told apart: its NULs get the file refused for its encoding.
        file.writeBytes(arabic.toByteArray(Charsets.UTF_16LE))
        val refused = MosqueSettingsFile.parse(UsbSettings.read(file)!!.text, MosqueSchedule.DEFAULT) as ParseResult.Failure
        assertEquals(MosqueSettingsFile.ErrorCode.INVALID_ENCODING, refused.errors.single().code)
    }

    @Test
    fun theKeyJustMountedIsKnownByItsVolumeId() {
        val key = RemovableVolume(File("/storage/1A2B-3C4D/Android/data/com.tunisianprayertimes.tv/files"))
        // Boxes name the same key by its public or its raw mount point.
        assertTrue(UsbVolumes.isAt(key, "/storage/1A2B-3C4D"))
        assertTrue(UsbVolumes.isAt(key, "/mnt/media_rw/1A2B-3C4D"))
        assertFalse(UsbVolumes.isAt(key, "/storage/9F00-1234"))
        assertFalse(UsbVolumes.isAt(key, "/"))
    }

    @Test
    fun anExportReplacesTheKeysFileAndKeepsTheOldOne() {
        val key = volume("key")
        val readOnly = RemovableVolume(volume("ntfs").appFolder, readOnly = true)
        val old = UsbSettings.settingsFile(key).put(ishaAt20)
        val text = MosqueSettingsFile.write(MosqueSchedule.DEFAULT)

        val (written, signature) = UsbSettings.export(listOf(key, readOnly), text)

        assertEquals("a read-only key is never written", listOf(old), written)
        assertEquals(text, old.readText())
        assertEquals(ishaAt20, File(key.appFolder, "${MosqueSettingsFile.FILE_NAME}.bak").readText())
        assertEquals(signature, UsbSettings.read(old)!!.signature)
        assertFalse(readOnly.appFolder.exists())
    }
}
