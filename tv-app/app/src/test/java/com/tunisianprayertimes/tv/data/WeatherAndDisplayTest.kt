package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.mosque.DisplayOptions
import com.tunisianprayertimes.mosque.MosqueProfile
import com.tunisianprayertimes.tv.usb.RemovableVolume
import com.tunisianprayertimes.tv.usb.UsbMedia
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherAndDisplayTest {

    private val answer = """{"current":{"temperature_2m":24.2,"weather_code":0,"is_day":1},
        "daily":{"temperature_2m_min":[18.0],"temperature_2m_max":[27.0]}}"""

    @Test
    fun theWeatherIsKeptForItsPlaceOnly() {
        var asked = ""
        val weather = WeatherRepository(InMemoryPreferences()) { url -> asked = url; answer }
        val fetched = weather.refresh(615, 36.8, 10.18, nowMillis = 1_000)!!
        assertTrue(asked.contains("latitude=36.800&longitude=10.180"))
        assertEquals("☀️ ⁦24°⁩ صحو · ⁦18°–27°⁩", fetched.weather.line())
        assertEquals(fetched, weather.cached(615))
        assertNull("another place", weather.cached(101))
    }

    @Test
    fun anOfflineTvKeepsItsLastWeather() {
        val prefs = InMemoryPreferences()
        WeatherRepository(prefs) { answer }.refresh(615, 36.8, 10.18, nowMillis = 1_000)
        val offline = WeatherRepository(prefs) { error("no network") }
        assertNull(offline.refresh(615, 36.8, 10.18, nowMillis = 2_000))
        assertEquals(1_000L, offline.cached(615)!!.fetchedAtMillis)
    }

    @Test
    fun displayOptionsTravelWithTheProfile() {
        val prefs = PrefsManager(InMemoryPreferences())
        assertEquals(DisplayOptions(true, true, true, 15, 15, nightScreen = true), prefs.profile.display)
        prefs.applyProfile(MosqueProfile(display = DisplayOptions(weather = false, slideSeconds = 30, announcementsEveryMinutes = 0))) { null }
        assertEquals(DisplayOptions(false, true, true, 30, 0, nightScreen = true), prefs.profile.display)
        assertEquals(false, prefs.weatherEnabled)
        assertEquals(0, prefs.announcementsEveryMinutes)
    }

    @Test
    fun textFilesOnAKeyBecomeWrittenAnnouncements() {
        val root: File = Files.createTempDirectory("media").toFile()
        try {
            val key = RemovableVolume(File(root, "key"))
            File(key.appFolder, "announcements").mkdirs()
            File(key.appFolder, "announcements/1-lesson.txt").writeText("  درس في التفسير بعد صلاة العشاء\n")
            File(key.appFolder, "announcements/2-binary.txt").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D))
            File(key.appFolder, "backgrounds").mkdirs()
            File(key.appFolder, "backgrounds/note.txt").writeText("not an announcement")
            val found = UsbMedia.find(listOf(key))!!
            assertEquals(listOf("1-lesson.txt"), found.images.getValue(MediaKind.ANNOUNCEMENTS).map { it.name })
            assertTrue(found.images.getValue(MediaKind.BACKGROUNDS).isEmpty())
            val store = LocalMediaManager(File(root, "tv"))
            store.replace(MediaKind.ANNOUNCEMENTS, found.images.getValue(MediaKind.ANNOUNCEMENTS))
            assertEquals(listOf("درس في التفسير بعد صلاة العشاء"), store.textFileAnnouncements())
            assertTrue("not counted as images", store.images(MediaKind.ANNOUNCEMENTS).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
