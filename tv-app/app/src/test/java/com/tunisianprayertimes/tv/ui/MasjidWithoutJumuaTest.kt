package com.tunisianprayertimes.tv.ui

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.tv.ui.display.MainScreenModel
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** The main screen of a masjid that holds no Jumu'a. */
class MasjidWithoutJumuaTest {

    private val times = DayPrayerTimes(
        day = 2,
        fajr = PrayerTime(Prayer.FAJR, 4, 49), shurukHour = 6, shurukMinute = 15,
        dhuhr = PrayerTime(Prayer.DHUHR, 12, 16), asr = PrayerTime(Prayer.ASR, 15, 29),
        maghrib = PrayerTime(Prayer.MAGHRIB, 18, 3), isha = PrayerTime(Prayer.ISHA, 19, 27),
    )
    private val friday = LocalDate.of(2026, 10, 2)

    @Test
    fun aMasjidWithoutJumuaKeepsDhuhrsNicheOnFridays() {
        // The flow resolved Dhuhr, not Jumu'a: the niche follows it.
        val dhuhr = mapOf(Prayer.DHUHR to LocalTime.of(12, 26))
        val state = MainScreenModel.at(friday.atTime(11, 0), times, null, dhuhr, null, banner = null, ramadanTomorrow = false)
        val noon = state.tiles[2]
        assertEquals(Prayer.DHUHR, noon.prayer)
        assertEquals(LocalTime.of(12, 26), noon.iqamah)
        val jumua = mapOf(Prayer.JOMOAA to LocalTime.of(13, 0))
        assertEquals(Prayer.JOMOAA, MainScreenModel.at(friday.atTime(11, 0), times, null, jumua, null, null, false).tiles[2].prayer)
    }
}
