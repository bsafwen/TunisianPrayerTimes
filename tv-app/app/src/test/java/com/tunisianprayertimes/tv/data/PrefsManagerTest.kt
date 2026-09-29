package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.IqamahRule
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.PrayerOverride
import com.tunisianprayertimes.mosque.PrayerSettings
import java.time.LocalTime
import org.junit.Assert.assertEquals
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
    fun anUnsetFixedTimeFallsBackToMinutesAfterTheAdhan() {
        val config = IqamahConfig(IqamahMode.FIXED_TIME, delayMinutes = 12, fixedHour = -1, fixedMinute = -1, salahMinutes = 8)
        assertEquals(PrayerSettings(IqamahRule.AfterAdhan(12), 8), config.toPrayerSettings())
    }
}
