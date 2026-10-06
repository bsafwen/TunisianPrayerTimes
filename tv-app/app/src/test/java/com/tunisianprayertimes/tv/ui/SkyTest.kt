package com.tunisianprayertimes.tv.ui

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.tv.ui.theme.Sky
import com.tunisianprayertimes.tv.ui.theme.SkyPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SkyTest {

    private val date = LocalDate.of(2026, 9, 29)
    private val times = DayPrayerTimes(
        day = 29,
        fajr = PrayerTime(Prayer.FAJR, 4, 46), shurukHour = 6, shurukMinute = 12,
        dhuhr = PrayerTime(Prayer.DHUHR, 12, 17), asr = PrayerTime(Prayer.ASR, 15, 32),
        maghrib = PrayerTime(Prayer.MAGHRIB, 18, 8), isha = PrayerTime(Prayer.ISHA, 19, 32),
    )

    private fun at(hm: String) = Sky.at(LocalDateTime.of(date, LocalTime.parse(hm)), times)

    @Test
    fun eachPhaseHoldsBetweenItsChanges() {
        assertEquals(SkyPhase.NIGHT.colors, at("02:00"))
        assertEquals(SkyPhase.FAJR.colors, at("05:15"))
        assertEquals(SkyPhase.SUNRISE.colors, at("06:10"))
        assertEquals(SkyPhase.DAY.colors, at("12:00"))
        assertEquals(SkyPhase.ASR.colors, at("16:30"))
        assertEquals(SkyPhase.MAGHRIB.colors, at("18:05"))
        assertEquals(SkyPhase.ISHA.colors, at("19:00"))
        assertEquals(SkyPhase.NIGHT.colors, at("21:00"))
        assertEquals(SkyPhase.NIGHT.colors, at("23:59"))
    }

    @Test
    fun aChangeIsSpreadAroundItsMoment() {
        // Asr at 15:32: half-way at the moment itself, and still changing a few minutes either side.
        assertEquals(Sky.blend(SkyPhase.DAY.colors, SkyPhase.ASR.colors, 0.5f), at("15:32"))
        listOf("15:20", "15:44").forEach { hm ->
            assertNotEquals(SkyPhase.DAY.colors, at(hm))
            assertNotEquals(SkyPhase.ASR.colors, at(hm))
        }
        assertEquals(SkyPhase.DAY.colors, at("15:16"))
        assertEquals(SkyPhase.ASR.colors, at("15:48"))
    }

    @Test
    fun theNightFadesIntoFajr() {
        assertNotEquals(SkyPhase.NIGHT.colors, at("04:40"))
        assertNotEquals(SkyPhase.FAJR.colors, at("04:50"))
    }

    @Test
    fun withoutTimesTheSkyIsADaySky() {
        assertEquals(SkyPhase.DAY.colors, Sky.at(LocalDateTime.of(date, LocalTime.NOON), null))
    }
}
