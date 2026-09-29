package com.tunisianprayertimes.tv.ui

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.DayBanner
import com.tunisianprayertimes.mosque.MosqueAdhkar
import com.tunisianprayertimes.mosque.PrayerEvent
import com.tunisianprayertimes.tv.ui.display.EidMorning
import com.tunisianprayertimes.tv.ui.display.IqamahWait
import com.tunisianprayertimes.tv.ui.display.NightWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class DisplayWindowsTest {

    private val day = LocalDate.of(2026, 9, 29)
    private fun at(hm: String, date: LocalDate = day) = LocalDateTime.of(date, LocalTime.parse(hm))

    // Isha's iqamah at 19:42, the next Fajr adhan at 04:47.
    private val isha = at("19:42")
    private val fajr = at("04:47", day.plusDays(1))

    private fun night(now: LocalDateTime, ramadan: Boolean = false, enabled: Boolean = true) =
        NightWindow.isNight(now, isha, fajr, ramadan, enabled)

    @Test
    fun theNightRunsFromAnHourAfterIshaToHalfAnHourBeforeFajr() {
        assertFalse(night(at("20:41")))
        assertTrue(night(at("20:42")))
        assertTrue(night(at("23:59")))
        assertTrue(night(at("02:00", day.plusDays(1))))
        assertTrue(night(at("04:16", day.plusDays(1))))
        assertFalse(night(at("04:17", day.plusDays(1))))
    }

    @Test
    fun inRamadanTheTarawihKeepsTheScreenOnLonger() {
        assertFalse(night(at("21:00"), ramadan = true))
        assertFalse(night(at("21:41"), ramadan = true))
        assertTrue(night(at("21:42"), ramadan = true))
    }

    @Test
    fun noNightWhenDisabledOrWithoutTimes() {
        assertFalse(night(at("23:00"), enabled = false))
        assertFalse(NightWindow.isNight(at("23:00"), null, fajr, ramadan = false, enabled = true))
        assertFalse(NightWindow.isNight(at("23:00"), isha, null, ramadan = false, enabled = true))
    }

    @Test
    fun anIshaAndAFajrOfDifferentNightsMakeNoNight() {
        // Yesterday's Isha and tomorrow's Fajr (today's times missing): in the day, not a night.
        val yesterdayIsha = at("19:43", day.minusDays(1))
        assertFalse(NightWindow.isNight(at("12:00"), yesterdayIsha, fajr, ramadan = false, enabled = true))
        assertFalse(NightWindow.isNight(at("23:00"), isha, at("04:48", day), ramadan = false, enabled = true))
    }

    private fun event(prayer: Prayer, adhan: LocalDateTime, iqamahMinutes: Long = 10) = PrayerEvent(
        prayer, adhan, adhan.plusMinutes(3), adhan.plusMinutes(iqamahMinutes), adhan.plusMinutes(iqamahMinutes + 10),
        adhan.plusMinutes(iqamahMinutes + 20), iqamahAdjusted = false,
    )

    @Test
    fun theNightIsFoundFromThePrayerEvents() {
        val events = listOf(
            event(Prayer.FAJR, at("04:46")), event(Prayer.ISHA, at("19:32")),
            event(Prayer.FAJR, at("04:47", day.plusDays(1))), event(Prayer.ISHA, at("19:31", day.plusDays(1))),
        )
        val now = at("23:00")
        assertEquals(at("19:42"), NightWindow.lastIshaIqamah(now, events))
        assertEquals(at("04:47", day.plusDays(1)), NightWindow.nextFajr(now, events)?.adhanAt)
        // Before the iqamah, the last Isha is yesterday's (absent here).
        assertNull(NightWindow.lastIshaIqamah(at("19:40"), events))
        assertNotNull(NightWindow.nextFajr(at("04:00"), events))
    }

    @Test
    fun theBlockMovesEveryFiveMinutesToAnotherRow() {
        val start = at("23:40")
        val anchors = (0 until 9).map { NightWindow.anchorAt(start.plusMinutes(it * 5L)) }
        assertEquals((0 until 9).toSet(), anchors.toSet())
        anchors.zipWithNext().forEach { (a, b) -> assertNotEquals(a / 3, b / 3) }
        // The same place all through the five minutes, and after a restart.
        assertEquals(NightWindow.anchorAt(start), NightWindow.anchorAt(start.plusSeconds(59)))
        assertEquals(NightWindow.anchorAt(at("23:40")), NightWindow.anchorAt(at("23:44")))
        assertTrue(NightWindow.anchorAt(at("00:00", LocalDate.of(1960, 1, 1))) in 0..8)
    }

    @Test
    fun eidMorningRunsFromTheEndOfFajrToTheDhuhrAdhan() {
        val eid = DayBanner.Eid(Prayer.AID_FITR, at("06:40"))
        val fajrDone = at("05:35")
        val dhuhr = at("12:17")
        // Before and during Fajr the timetable stays: the dawn congregation needs Fajr's times.
        assertFalse(EidMorning.isShown(at("00:00"), eid, fajrDone, dhuhr))
        assertFalse(EidMorning.isShown(at("05:34"), eid, fajrDone, dhuhr))
        assertTrue(EidMorning.isShown(at("05:35"), eid, fajrDone, dhuhr))
        assertTrue(EidMorning.isShown(at("12:16"), DayBanner.Eid(Prayer.AID_FITR, null), fajrDone, dhuhr))
        assertFalse(EidMorning.isShown(at("12:17"), eid, fajrDone, dhuhr))
        assertFalse(EidMorning.isShown(at("08:00"), DayBanner.Arafah, fajrDone, dhuhr))
        assertFalse(EidMorning.isShown(at("08:00"), null, fajrDone, dhuhr))
        // Without today's times, from midnight until noon.
        assertTrue(EidMorning.isShown(at("00:00"), eid, null, null))
        assertTrue(EidMorning.isShown(at("11:59"), eid, null, null))
        assertFalse(EidMorning.isShown(at("12:00"), eid, null, null))
    }

    @Test
    fun theCountdownRoundsUpLikeTheMainScreen() {
        val iqamah = at("15:42")
        // The clock is read a few milliseconds after each second.
        assertEquals(1, IqamahWait.remainingSeconds(at("15:41:59").plusNanos(4_000_000), iqamah))
        assertEquals(600, IqamahWait.remainingSeconds(at("15:32").plusNanos(4_000_000), iqamah))
        assertEquals(0, IqamahWait.remainingSeconds(iqamah.plusNanos(4_000_000), iqamah))
        assertEquals(1, IqamahWait.litStuds(at("15:41:59").plusNanos(4_000_000), at("15:32"), iqamah))
    }

    @Test
    fun theStudsGoOutOneByOneOverTheWait() {
        val adhan = at("15:32")
        val iqamah = at("15:42")
        assertEquals(24, IqamahWait.litStuds(adhan, adhan, iqamah))
        // One stud for each 25 seconds of the ten minutes.
        assertEquals(24, IqamahWait.litStuds(at("15:32:24"), adhan, iqamah))
        assertEquals(23, IqamahWait.litStuds(at("15:32:25"), adhan, iqamah))
        // The board: 07:42 left of ten minutes.
        assertEquals(19, IqamahWait.litStuds(at("15:34:18"), adhan, iqamah))
        assertEquals(1, IqamahWait.litStuds(at("15:41:59"), adhan, iqamah))
        assertEquals(0, IqamahWait.litStuds(iqamah, adhan, iqamah))
        assertEquals(0, IqamahWait.litStuds(at("15:50"), adhan, iqamah))
        assertEquals(0, IqamahWait.litStuds(adhan, adhan, adhan))
    }

    @Test
    fun theCountdownIsMinutesAndSeconds() {
        assertEquals(462, IqamahWait.remainingSeconds(at("15:34:18"), at("15:42")))
        assertEquals(0, IqamahWait.remainingSeconds(at("15:43"), at("15:42")))
        assertEquals("07:42", IqamahWait.text(462))
        assertEquals("00:00", IqamahWait.text(-5))
        // An Eid prayer 85 minutes after sunrise keeps the same four digits.
        assertEquals("85:00", IqamahWait.text(85 * 60))
    }

    @Test
    fun everyTextBesideTheMuezzinSaysWhenItIsSaid() {
        MosqueAdhkar.ADHAN_IDS.forEach { assertNotNull(it, TvStrings.adhanCaption(it)) }
        assertNull(TvStrings.adhanCaption(null))
        assertEquals(MosqueAdhkar.ADHAN_IDS, MosqueAdhkar.adhanCompanion().map { it.entryId })
    }
}
