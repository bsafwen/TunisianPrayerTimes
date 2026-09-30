package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.IqamahRule
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.mosque.PrayerSettings
import com.tunisianprayertimes.tv.ui.usb.SettingsChangeLines
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the mosque holds Jumu'a and the Eid prayer, how long its khutba lasts, and what a stale fixed
 * iqamah falls back to, as the TV keeps them.
 */
class HeldPrayersAndFallbackTest {

    private val store = InMemoryPreferences()
    private val prefs = PrefsManager(store)

    @Test
    fun whetherJumuaAndTheEidsAreHeldSurvivesARestart() {
        prefs.schedule = MosqueSchedule.DEFAULT
            .with(Prayer.JOMOAA, PrayerSettings(IqamahRule.AfterAdhan(15), 15, held = false))
            .with(Prayer.AID_FITR, PrayerSettings(IqamahRule.AfterAdhan(30), 30, held = false))
        val reloaded = PrefsManager(store).schedule
        assertFalse(reloaded.holds(Prayer.JOMOAA))
        assertFalse(reloaded.holds(Prayer.AID_FITR))
        assertTrue(reloaded.holds(Prayer.AID_ADHA))
        assertEquals(false, IqamahConfig.from(reloaded.settings(Prayer.JOMOAA)).held)
    }

    @Test
    fun aFixedTimeFallsBackToTheMinutesTheMosqueKeptBehindIt() {
        // Isha at +15, then a winter 19:00: the +15 stays behind the fixed time.
        prefs.setIqamahConfig(Prayer.ISHA, IqamahConfig(delayMinutes = 15, salahMinutes = 10))
        prefs.schedule = prefs.schedule.with(Prayer.ISHA, PrayerSettings(IqamahRule.FixedTime(LocalTime.of(19, 0)), 10))
        assertEquals(IqamahRule.FixedTime(LocalTime.of(19, 0)), prefs.schedule.settings(Prayer.ISHA).iqamah)
        assertEquals(15, prefs.schedule.fallbackMinutes(Prayer.ISHA))
        // A prayer the admin never timed falls back to the built-in minutes.
        assertEquals(MosqueSchedule.defaultIqamahMinutes(Prayer.FAJR), prefs.schedule.fallbackMinutes(Prayer.FAJR))
    }

    @Test
    fun theFilePreviewSaysWhetherAPrayerIsHeld() {
        val result = MosqueSettingsFile.parse("""{ "prayers": { "jumua": { "held": false } } }""", MosqueSchedule.DEFAULT)
        val line = SettingsChangeLines.of(result as ParseResult.Success).single()
        assertEquals("${MosqueSettingsFile.arabicName(Prayer.JOMOAA)}: تقام ← لا تقام", line)
    }

    @Test
    fun theKhutbaLengthSurvivesARestartAndReadsInThePreview() {
        val jumua = PrayerSettings(IqamahRule.FixedTime(LocalTime.of(13, 15)), 15, khutbaMinutes = 30)
        prefs.schedule = MosqueSchedule.DEFAULT.with(Prayer.JOMOAA, jumua)
        val reloaded = PrefsManager(store)
        assertEquals(jumua, reloaded.schedule.settings(Prayer.JOMOAA))
        assertEquals(30, reloaded.iqamahConfigs().getValue(Prayer.JOMOAA).khutbaMinutes)
        val result = MosqueSettingsFile.parse("""{ "prayers": { "jumua": { "khutba": 0 } } }""", reloaded.schedule)
        val line = SettingsChangeLines.of(result as ParseResult.Success).single()
        assertEquals("${MosqueSettingsFile.arabicName(Prayer.JOMOAA)} · مدة الخطبة: 30 د ← من الأذان", line)
    }
}
