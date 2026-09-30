package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.DisplayOptions
import com.tunisianprayertimes.mosque.IqamahRule
import com.tunisianprayertimes.mosque.MosqueProfile
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.PrayerOverride
import com.tunisianprayertimes.mosque.PrayerSettings
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsManagerTest {

    private val store = InMemoryPreferences()
    private val prefs = PrefsManager(store)

    @Test
    fun aNewTvStartsWithTheDefaultSchedule() {
        assertEquals(
            MosqueSchedule.CONFIGURABLE.associateWith(MosqueSchedule.DEFAULT::settings),
            MosqueSchedule.CONFIGURABLE.associateWith(prefs.schedule::settings),
        )
    }

    @Test
    fun theScheduleSurvivesSavingIncludingFixedTimesAndDurations() {
        val schedule = MosqueSchedule.DEFAULT
            .with(Prayer.ISHA, PrayerSettings(IqamahRule.FixedTime(LocalTime.of(20, 15)), 12))
            .with(Prayer.MAGHRIB, PrayerSettings(IqamahRule.AfterAdhan(7), 6))
            .with(Prayer.JOMOAA, PrayerSettings(IqamahRule.FixedTime(LocalTime.of(13, 10)), 20))
        prefs.schedule = schedule
        // A fresh PrefsManager on the same store, as after an app restart.
        val reloaded = PrefsManager(store).schedule
        assertEquals(
            MosqueSchedule.CONFIGURABLE.associateWith(schedule::settings),
            MosqueSchedule.CONFIGURABLE.associateWith(reloaded::settings),
        )
        // The saved minutes-after-adhan (Jumu'a default +15) are kept behind the fixed time.
        assertEquals(IqamahConfig(IqamahMode.FIXED_TIME, delayMinutes = 15, fixedHour = 13, fixedMinute = 10, salahMinutes = 20), prefs.getIqamahConfig(Prayer.JOMOAA))
    }

    @Test
    fun eidAndRamadanSettingsSurviveARestart() {
        val schedule = MosqueSchedule.DEFAULT
            .with(Prayer.AID_ADHA, PrayerSettings(IqamahRule.FixedTime(LocalTime.of(7, 5)), 40))
            .withRamadan(Prayer.ISHA, PrayerOverride(IqamahRule.AfterAdhan(20), 75))
            .withRamadan(Prayer.FAJR, PrayerOverride(salahMinutes = 15))
        prefs.schedule = schedule
        val reloaded = PrefsManager(store).schedule
        assertEquals(schedule.settings(Prayer.AID_ADHA), reloaded.settings(Prayer.AID_ADHA))
        assertEquals(schedule.settings(Prayer.AID_FITR), reloaded.settings(Prayer.AID_FITR))
        assertEquals(schedule.ramadan, reloaded.ramadan)
        // Clearing Ramadan's changes removes them.
        prefs.schedule = schedule.copy(ramadan = emptyMap())
        assertEquals(emptyMap<Prayer, PrayerOverride>(), PrefsManager(store).ramadanOverrides)
    }

    @Test
    fun editingOnePrayerKeepsItsDuration() {
        val edited = prefs.getIqamahConfig(Prayer.ASR).copy(delayMinutes = 20)
        prefs.setIqamahConfig(Prayer.ASR, edited)
        assertEquals(PrayerSettings(IqamahRule.AfterAdhan(20), 10), prefs.schedule.settings(Prayer.ASR))
    }

    @Test
    fun theNightScreenIsOnUntilTheAdminTurnsItOff() {
        assertTrue(prefs.nightScreenEnabled)
        assertEquals(true, prefs.profile.display.nightScreen)
        // A file that does not name it leaves it alone; one that does is kept across a restart.
        prefs.applyProfile(MosqueProfile(display = DisplayOptions(weather = false))) { null }
        assertTrue(prefs.nightScreenEnabled)
        prefs.applyProfile(MosqueProfile(display = DisplayOptions(nightScreen = false))) { null }
        assertFalse(PrefsManager(store).nightScreenEnabled)
        assertEquals(false, PrefsManager(store).profile.display.nightScreen)
    }

    @Test
    fun theAdhanScreenLastsTwoMinutesUntilTheAdminChangesIt() {
        assertEquals(2, prefs.adhanScreenMinutes)
        assertEquals(2, prefs.profile.display.adhanScreenMinutes)
        // A file that does not name it leaves it alone; one that does is kept across a restart.
        prefs.applyProfile(MosqueProfile(display = DisplayOptions(weather = false))) { null }
        assertEquals(2, prefs.adhanScreenMinutes)
        prefs.applyProfile(MosqueProfile(display = DisplayOptions(adhanScreenMinutes = 4))) { null }
        assertEquals(4, PrefsManager(store).adhanScreenMinutes)
        assertEquals(4, PrefsManager(store).profile.display.adhanScreenMinutes)
        // Kept within 1 to 5 whatever is asked.
        prefs.adhanScreenMinutes = 9
        assertEquals(5, prefs.adhanScreenMinutes)
        prefs.adhanScreenMinutes = 0
        assertEquals(1, prefs.adhanScreenMinutes)
    }

    @Test
    fun aThemeFromBeforeTheRedesignReadsAsTheDefault() {
        assertEquals(PrefsManager.DEFAULT_THEME_ID, prefs.themeId)
        store.edit().putString("theme_id", "midnight_navy").apply()
        assertEquals(PrefsManager.DEFAULT_THEME_ID, prefs.themeId)
        assertEquals(PrefsManager.DEFAULT_THEME_ID, prefs.profile.themeId)
        prefs.themeId = "midad"
        assertEquals("midad", PrefsManager(store).themeId)
    }

    @Test
    fun anUnsetFixedTimeFallsBackToMinutesAfterTheAdhan() {
        val config = IqamahConfig(IqamahMode.FIXED_TIME, delayMinutes = 12, fixedHour = -1, fixedMinute = -1, salahMinutes = 8)
        assertEquals(PrayerSettings(IqamahRule.AfterAdhan(12), 8), config.toPrayerSettings())
    }
}
