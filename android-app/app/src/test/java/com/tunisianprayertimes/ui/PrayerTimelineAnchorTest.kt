package com.tunisianprayertimes.ui

import com.tunisianprayertimes.DelayMode
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.SilenceMode
import com.tunisianprayertimes.silence.SilenceEnd
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The silence editors' axis and its rules (plan 6.9, D8, D16). */
class PrayerTimelineAnchorTest {
    private val tunis = ZoneId.of("Africa/Tunis")
    private val paris = ZoneId.of("Europe/Paris")
    private val day = LocalDate.of(2026, 6, 15)

    private fun anchor(prayer: Prayer, hour: Int, minute: Int, place: ZoneId = tunis, phone: ZoneId = place, savedEnd: SilenceEnd.Clock? = null) =
        PrayerTimelineAnchor(prayer, day, ZonedDateTime.of(day, LocalTime.of(hour, minute), place).toInstant(), place, inm = false, savedEnd = savedEnd, displayZone = phone)

    @Test
    fun clockSettingsAreStoredInThePlaceZoneAndShownInThePhones() {
        // A Paris place on a phone kept at Tunis's time (one hour behind in summer): Dhuhr 13:50 shows at 12:50.
        val dhuhr = anchor(Prayer.DHUHR, 13, 50, place = paris, phone = tunis)
        assertEquals(12 * 60 + 50, dhuhr.minutes)
        val fixedStart = PrayerSilenceConfig().withStartRule(dhuhr, EndpointRuleMode.FIXED_TIME, 0, 13 * 60)
        assertEquals(14, fixedStart.delayFixedHour)
        assertEquals(0, fixedStart.delayFixedMinute)
        assertEquals(13 * 60, resolvePrayerTimelineWindow(dhuhr, fixedStart).startMinutes)
    }

    @Test
    fun aFixedEndAfterMidnightIsTheNextDaysClock() {
        val isha = anchor(Prayer.ISHA, 21, 0)
        val config = PrayerSilenceConfig(mode = SilenceMode.FIXED_TIME, fixedHour = 0, fixedMinute = 30)
        val window = resolvePrayerTimelineWindow(isha, config)
        assertEquals(21 * 60, window.startMinutes)
        assertEquals(24 * 60 + 30, window.endMinutes)
        assertTrue(configResolvesToValidWindow(isha, config))
        assertEquals(24 * 60 + 30, isha.endAxisFor(30, window.startMinutes))
    }

    @Test
    fun theEndpointDomainBoundsBothEnds() {
        val fajr = anchor(Prayer.FAJR, 4, 0)
        assertTrue(configResolvesToValidWindow(fajr, PrayerSilenceConfig(delayMinutes = -720, endOffsetMinutes = 30)))
        assertFalse(configResolvesToValidWindow(fajr, PrayerSilenceConfig(delayMinutes = -721, endOffsetMinutes = 30)))
        assertTrue(configResolvesToValidWindow(fajr, PrayerSilenceConfig(endOffsetMinutes = 1440)))
        assertFalse(configResolvesToValidWindow(fajr, PrayerSilenceConfig(endOffsetMinutes = 1441)))
        // The slider's edge extension stops at the bound: a window past it has no config.
        assertNull(prayerTimelineConfigForWindow(fajr, PrayerSilenceConfig(endOffsetMinutes = 30), PrayerTimelineWindow(fajr.minutes, fajr.minutes + 1441)))
        assertEquals(1440, endpointOffsetLimit(SilenceEndpoint.END, after = true))
        assertEquals(720, endpointOffsetLimit(SilenceEndpoint.START, after = true))
    }

    @Test
    fun aFixedStartTakesItsOccurrenceNearestTheAdhan() {
        val fajr = anchor(Prayer.FAJR, 6, 0)
        // 23:00 is 7 hours before a 06:00 adhan the evening before, not 17 hours after it.
        assertEquals(-60, fajr.startAxisFor(23 * 60))
        val config = PrayerSilenceConfig(delayMode = DelayMode.FIXED_TIME, delayFixedHour = 23, delayFixedMinute = 0)
        assertEquals(-60, resolvePrayerTimelineWindow(fajr, config).startMinutes)
    }

    @Test
    fun d16sExample_aSavedEndKeepsItsOffsetWhenEvenTheCapIsTooEarly() {
        // Plan D16: an end of 13:00 saved when Dhuhr was at 12:25 (adhan + 35) becomes 14:30 on a Paris day with Dhuhr at 13:55.
        val dhuhr = anchor(Prayer.DHUHR, 13, 55, place = paris, savedEnd = SilenceEnd.Clock(LocalTime.of(13, 0), 35))
        val config = PrayerSilenceConfig(delayMinutes = 5, mode = SilenceMode.FIXED_TIME, fixedHour = 13, fixedMinute = 0)
        val window = resolvePrayerTimelineWindow(dhuhr, config)
        assertEquals(14 * 60, window.startMinutes)
        assertEquals(14 * 60 + 30, window.endMinutes)
    }

    @Test
    fun aMovedWindowMapsBackToExplicitRules() {
        val asr = anchor(Prayer.ASR, 16, 30)
        val moved = prayerTimelineConfigForWindow(asr, PrayerSilenceConfig(endOffsetMinutes = 30), PrayerTimelineWindow(16 * 60 + 20, 17 * 60 + 15))
        requireNotNull(moved)
        assertEquals(-10, moved.delayMinutes)
        assertEquals(45, moved.endOffsetMinutes)
    }
}
