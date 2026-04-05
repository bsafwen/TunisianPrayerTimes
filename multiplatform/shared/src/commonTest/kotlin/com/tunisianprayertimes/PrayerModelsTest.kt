package com.tunisianprayertimes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrayerModelsTest {

    private val day = DayPrayerTimes(
        day = 1,
        fajr = PrayerTime(Prayer.FAJR, 5, 30),
        dhuhr = PrayerTime(Prayer.DHUHR, 12, 15),
        asr = PrayerTime(Prayer.ASR, 15, 30),
        maghrib = PrayerTime(Prayer.MAGHRIB, 18, 10),
        isha = PrayerTime(Prayer.ISHA, 19, 30)
    )

    @Test
    fun allPrayersReturnsFive() {
        assertEquals(5, day.allPrayers().size)
    }

    @Test
    fun nextPrayerBeforeFajr() {
        assertEquals(Prayer.FAJR, day.nextPrayer(3, 0))
    }

    @Test
    fun nextPrayerBetweenPrayers() {
        assertEquals(Prayer.ASR, day.nextPrayer(13, 0))
    }

    @Test
    fun nextPrayerAfterIsha() {
        assertNull(day.nextPrayer(20, 0))
    }

    @Test
    fun scheduledPrayersNormalDay() {
        val prayers = day.scheduledPrayers(isFriday = false)
        assertEquals(5, prayers.size)
        assertEquals(Prayer.DHUHR, prayers[1].prayer)
    }

    @Test
    fun scheduledPrayersFridayReplacesWithJomoaa() {
        val prayers = day.scheduledPrayers(isFriday = true, jomoaaHour = 13, jomoaaMinute = 0)
        assertEquals(5, prayers.size)
        assertEquals(Prayer.JOMOAA, prayers[1].prayer)
        assertEquals(13, prayers[1].hour)
        assertEquals(0, prayers[1].minute)
    }

    @Test
    fun scheduledPrayersFridayDefaultsToDbhurTime() {
        val prayers = day.scheduledPrayers(isFriday = true)
        val jomoaa = prayers[1]
        assertEquals(Prayer.JOMOAA, jomoaa.prayer)
        assertEquals(12, jomoaa.hour)
        assertEquals(15, jomoaa.minute)
    }
}
