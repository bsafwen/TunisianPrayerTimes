package com.tunisianprayertimes

import org.junit.Assert.*
import org.junit.Test

class PrayerModelsTest {

    @Test
    fun prayer_enum_hasFiveValues() {
        assertEquals(5, Prayer.values().size)
        assertEquals(Prayer.FAJR, Prayer.values()[0])
        assertEquals(Prayer.ISHA, Prayer.values()[4])
    }

    @Test
    fun prayerTime_storesHourAndMinute() {
        val pt = PrayerTime(Prayer.FAJR, 5, 30)
        assertEquals(Prayer.FAJR, pt.prayer)
        assertEquals(5, pt.hour)
        assertEquals(30, pt.minute)
    }

    @Test
    fun dayPrayerTimes_allPrayers_returnsFiveInOrder() {
        val day = DayPrayerTimes(
            day = 1,
            fajr = PrayerTime(Prayer.FAJR, 5, 0),
            dhuhr = PrayerTime(Prayer.DHUHR, 12, 30),
            asr = PrayerTime(Prayer.ASR, 15, 45),
            maghrib = PrayerTime(Prayer.MAGHRIB, 18, 15),
            isha = PrayerTime(Prayer.ISHA, 19, 45)
        )
        val all = day.allPrayers()
        assertEquals(5, all.size)
        assertEquals(Prayer.FAJR, all[0].prayer)
        assertEquals(Prayer.DHUHR, all[1].prayer)
        assertEquals(Prayer.ASR, all[2].prayer)
        assertEquals(Prayer.MAGHRIB, all[3].prayer)
        assertEquals(Prayer.ISHA, all[4].prayer)
    }

    @Test
    fun silenceMode_hasTwoValues() {
        assertEquals(2, SilenceMode.values().size)
        assertEquals(SilenceMode.DURATION, SilenceMode.valueOf("DURATION"))
        assertEquals(SilenceMode.FIXED_TIME, SilenceMode.valueOf("FIXED_TIME"))
    }

    @Test
    fun prayerSilenceConfig_defaults() {
        val config = PrayerSilenceConfig()
        assertEquals(SilenceMode.DURATION, config.mode)
        assertEquals(30, config.afterMinutes)
        assertEquals(-1, config.fixedHour)
        assertEquals(-1, config.fixedMinute)
    }

    @Test
    fun prayerSilenceConfig_fixedTimeMode() {
        val config = PrayerSilenceConfig(
            mode = SilenceMode.FIXED_TIME,
            afterMinutes = 0,
            fixedHour = 13,
            fixedMinute = 45
        )
        assertEquals(SilenceMode.FIXED_TIME, config.mode)
        assertEquals(13, config.fixedHour)
        assertEquals(45, config.fixedMinute)
    }
}
