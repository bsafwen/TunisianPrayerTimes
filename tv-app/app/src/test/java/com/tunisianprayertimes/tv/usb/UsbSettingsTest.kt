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
    fun theNewestFileInAnAppFolderWins() {
        val key = volume("key")
        val other = volume("other")
        assertNull(UsbSettings.find(listOf(key, other)))
        val older = UsbSettings.settingsFile(key).put(ishaAt20)
        assertEquals(older, UsbSettings.find(listOf(key, other)))
        val newer = UsbSettings.settingsFile(other).put(ishaAt20, modified = older.lastModified() + 60_000)
        assertEquals(newer, UsbSettings.find(listOf(key, other)))
    }

    @Test
    fun anEmptyFileCountsAsAbsent() {
        // A key pulled out mid-write leaves an empty file behind; it gets a fresh template.
        val key = volume("key")
        UsbSettings.settingsFile(key).put("")
        assertNull(UsbSettings.find(listOf(key)))
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
}
