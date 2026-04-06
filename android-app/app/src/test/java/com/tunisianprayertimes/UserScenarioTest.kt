package com.tunisianprayertimes

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class UserScenarioTest {
    @Test
    fun regression_fixedTimeCrossingMidnight_failsWithoutFix() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21) // Currently 21:00 (9:00 PM)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val typicalDay = DayPrayerTimes(
            day = 1,
            fajr = PrayerTime(Prayer.FAJR, 4, 30),
            shurukHour = 6,
            shurukMinute = 0,
            dhuhr = PrayerTime(Prayer.DHUHR, 12, 30),
            asr = PrayerTime(Prayer.ASR, 16, 0),
            maghrib = PrayerTime(Prayer.MAGHRIB, 19, 0),
            isha = PrayerTime(Prayer.ISHA, 20, 30)
        )
        val tomorrowDay = DayPrayerTimes(
            day = 2,
            fajr = PrayerTime(Prayer.FAJR, 4, 30),
            shurukHour = 6,
            shurukMinute = 0,
            dhuhr = PrayerTime(Prayer.DHUHR, 12, 30),
            asr = PrayerTime(Prayer.ASR, 16, 0),
            maghrib = PrayerTime(Prayer.MAGHRIB, 19, 0),
            isha = PrayerTime(Prayer.ISHA, 20, 30)
        )
        val configs = mapOf(
            Prayer.ISHA to PrayerSilenceConfig(
                mode = SilenceMode.FIXED_TIME,
                fixedHour = 1, // 01:00 AM
                fixedMinute = 0
            ) 
        )
        val result = SilenceAlarmComputer.compute(now, typicalDay, configs, tomorrowDay)
        assertTrue("Should be in silence window", result.currentlyInSilenceWindow)
        assertFalse("Tomorrow Fajr should not be scheduled to overwrite today's", result.tomorrowFajrScheduled)
    }
}
