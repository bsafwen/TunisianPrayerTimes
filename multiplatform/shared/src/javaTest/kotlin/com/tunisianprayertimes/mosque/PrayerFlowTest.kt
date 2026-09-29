package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrayerFlowTest {

    // Tunis on Tuesday 2026-09-29: Fajr 04:46, Shuruk 06:12, Dhuhr 12:17, Asr 15:32, Maghrib 18:08, Isha 19:32.
    private val tuesday = LocalDate.of(2026, 9, 29)
    private val friday = LocalDate.of(2026, 10, 2)

    private fun times(isha: String = "19:32", maghrib: String = "18:08"): DayPrayerTimes {
        fun t(prayer: Prayer, hm: String) = LocalTime.parse(hm).let { PrayerTime(prayer, it.hour, it.minute) }
        return DayPrayerTimes(
            day = 29,
            fajr = t(Prayer.FAJR, "04:46"), shurukHour = 6, shurukMinute = 12,
            dhuhr = t(Prayer.DHUHR, "12:17"), asr = t(Prayer.ASR, "15:32"),
            maghrib = t(Prayer.MAGHRIB, maghrib), isha = t(Prayer.ISHA, isha),
        )
    }

    private fun at(date: LocalDate, hms: String): LocalDateTime = date.atTime(LocalTime.parse(hms))

    private fun phaseAt(events: List<PrayerEvent>, date: LocalDate, hms: String): Pair<FlowPhase, Prayer?> =
        PrayerFlow.stateAt(at(date, hms), events).let { it.phase to it.event?.prayer }

    private fun schedule(prayer: Prayer, iqamah: IqamahRule, salahMinutes: Int) =
        MosqueSchedule.DEFAULT.with(prayer, PrayerSettings(iqamah, salahMinutes))

    @Test
    fun maghribGoesFromAdhanToCountdownToBlackToAdhkarThenIdle() {
        // Default Maghrib: iqamah +5, prayer 8 minutes; adhan screen 3 minutes, adhkar 10.
        val events = PrayerFlow.eventsFor(tuesday, times(), MosqueSchedule.DEFAULT)
        assertEquals(FlowPhase.IDLE to null, phaseAt(events, tuesday, "18:07:59"))
        assertEquals(FlowPhase.ADHAN to Prayer.MAGHRIB, phaseAt(events, tuesday, "18:08:00"))
        assertEquals(FlowPhase.ADHAN to Prayer.MAGHRIB, phaseAt(events, tuesday, "18:10:59"))
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN to Prayer.MAGHRIB, phaseAt(events, tuesday, "18:11:00"))
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN to Prayer.MAGHRIB, phaseAt(events, tuesday, "18:12:59"))
        assertEquals(FlowPhase.SALAH to Prayer.MAGHRIB, phaseAt(events, tuesday, "18:13:00"))
        assertEquals(FlowPhase.SALAH to Prayer.MAGHRIB, phaseAt(events, tuesday, "18:20:59"))
        assertEquals(FlowPhase.AFTER_SALAH to Prayer.MAGHRIB, phaseAt(events, tuesday, "18:21:00"))
        assertEquals(FlowPhase.AFTER_SALAH to Prayer.MAGHRIB, phaseAt(events, tuesday, "18:30:59"))
        assertEquals(FlowPhase.IDLE to null, phaseAt(events, tuesday, "18:31:00"))
    }

    @Test
    fun phaseEndIsTheNextBoundary() {
        val events = PrayerFlow.eventsFor(tuesday, times(), MosqueSchedule.DEFAULT)
        assertEquals(at(tuesday, "18:13:00"), PrayerFlow.stateAt(at(tuesday, "18:12:00"), events).phaseEndsAt)
        assertEquals(at(tuesday, "18:21:00"), PrayerFlow.stateAt(at(tuesday, "18:15:00"), events).phaseEndsAt)
    }

    @Test
    fun everyPrayerHasItsOwnBlackScreenLength() {
        val schedule = MosqueSchedule(
            mapOf(
                Prayer.FAJR to PrayerSettings(IqamahRule.AfterAdhan(20), 12),
                Prayer.DHUHR to PrayerSettings(IqamahRule.AfterAdhan(10), 7),
                Prayer.ASR to PrayerSettings(IqamahRule.AfterAdhan(10), 6),
                Prayer.MAGHRIB to PrayerSettings(IqamahRule.AfterAdhan(5), 5),
                Prayer.ISHA to PrayerSettings(IqamahRule.AfterAdhan(10), 9),
            )
        )
        val black = PrayerFlow.eventsFor(tuesday, times(), schedule)
            .associate { it.prayer to java.time.Duration.between(it.iqamahAt, it.salahEndAt).toMinutes() }
        assertEquals(mapOf(Prayer.FAJR to 12L, Prayer.DHUHR to 7L, Prayer.ASR to 6L, Prayer.MAGHRIB to 5L, Prayer.ISHA to 9L), black)
    }

    @Test
    fun fixedIqamahIsUsedAsIs() {
        val events = PrayerFlow.eventsFor(tuesday, times(), schedule(Prayer.ISHA, IqamahRule.FixedTime(LocalTime.of(19, 45)), 10))
        val isha = events.single { it.prayer == Prayer.ISHA }
        assertEquals(at(tuesday, "19:45:00"), isha.iqamahAt)
        assertFalse(isha.iqamahAdjusted)
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN, PrayerFlow.stateAt(at(tuesday, "19:40:00"), events).phase)
        assertEquals(FlowPhase.SALAH, PrayerFlow.stateAt(at(tuesday, "19:45:00"), events).phase)
    }

    @Test
    fun aStaleFixedTimeBeforeTheAdhanFallsBackToTheDefaultOffset() {
        // A winter "Isha at 19:00" after a 19:32 summer adhan: the adhan, countdown and a full prayer still happen.
        val events = PrayerFlow.eventsFor(tuesday, times(), schedule(Prayer.ISHA, IqamahRule.FixedTime(LocalTime.of(19, 0)), 10))
        val isha = events.single { it.prayer == Prayer.ISHA }
        assertEquals(at(tuesday, "19:42:00"), isha.iqamahAt) // default Isha +10
        assertTrue(isha.iqamahAdjusted)
        assertEquals(FlowPhase.ADHAN to Prayer.ISHA, phaseAt(events, tuesday, "19:32:00"))
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN to Prayer.ISHA, phaseAt(events, tuesday, "19:40:00"))
        assertEquals(FlowPhase.SALAH to Prayer.ISHA, phaseAt(events, tuesday, "19:42:00"))
        assertEquals(FlowPhase.AFTER_SALAH to Prayer.ISHA, phaseAt(events, tuesday, "19:52:00"))
    }

    @Test
    fun aFixedTimeFarAfterTheAdhanFallsBackToo() {
        // "20:00" typed for Fajr, or "8:00" meant as 20:00 for Isha.
        val fajr = PrayerFlow.eventsFor(tuesday, times(), schedule(Prayer.FAJR, IqamahRule.FixedTime(LocalTime.of(20, 0)), 10)).first()
        assertEquals(at(tuesday, "05:01:00"), fajr.iqamahAt) // default Fajr +15
        assertTrue(fajr.iqamahAdjusted)
        val isha = PrayerFlow.eventsFor(tuesday, times(), schedule(Prayer.ISHA, IqamahRule.FixedTime(LocalTime.of(8, 0)), 10)).last()
        assertEquals(at(tuesday, "19:42:00"), isha.iqamahAt)
        assertTrue(isha.iqamahAdjusted)
    }

    @Test
    fun iqamahIsKeptBeforeTheNextAdhan() {
        val events = PrayerFlow.eventsFor(tuesday, times(), schedule(Prayer.MAGHRIB, IqamahRule.AfterAdhan(90), 8))
        val maghrib = events.single { it.prayer == Prayer.MAGHRIB }
        assertEquals(at(tuesday, "19:31:00"), maghrib.iqamahAt)
        assertTrue(maghrib.iqamahAdjusted)
    }

    @Test
    fun fajrIsPrayedBeforeSunrise() {
        // Shuruk 06:12: a 10-minute Fajr must start by 06:02.
        val fajr = PrayerFlow.eventsFor(tuesday, times(), schedule(Prayer.FAJR, IqamahRule.AfterAdhan(80), 10)).first()
        assertEquals(at(tuesday, "06:02:00"), fajr.iqamahAt)
        assertEquals(at(tuesday, "06:12:00"), fajr.salahEndAt)
        assertTrue(fajr.iqamahAdjusted)
        val onTime = PrayerFlow.eventsFor(tuesday, times(), schedule(Prayer.FAJR, IqamahRule.AfterAdhan(20), 10)).first()
        assertFalse(onTime.iqamahAdjusted)
    }

    @Test
    fun theIqamahIsAtLeastAMinuteAfterTheAdhan() {
        val events = PrayerFlow.eventsFor(tuesday, times(), schedule(Prayer.ASR, IqamahRule.AfterAdhan(0), 10))
        assertEquals(FlowPhase.ADHAN to Prayer.ASR, phaseAt(events, tuesday, "15:32:00"))
        assertEquals(FlowPhase.SALAH to Prayer.ASR, phaseAt(events, tuesday, "15:33:00"))
    }

    @Test
    fun theBlackScreenLastsAtLeastAMinute() {
        // A zero duration would show the after-salah adhkar while the congregation prays.
        val events = PrayerFlow.eventsFor(tuesday, times(), schedule(Prayer.ASR, IqamahRule.AfterAdhan(10), 0))
        assertEquals(FlowPhase.SALAH to Prayer.ASR, phaseAt(events, tuesday, "15:42:00"))
        assertEquals(FlowPhase.AFTER_SALAH to Prayer.ASR, phaseAt(events, tuesday, "15:43:00"))
    }

    @Test
    fun fridayUsesJumuaSettingsInTheDhuhrSlot() {
        val schedule = MosqueSchedule.DEFAULT
            .with(Prayer.JOMOAA, PrayerSettings(IqamahRule.FixedTime(LocalTime.of(13, 15)), 15))
        val friday = PrayerFlow.eventsFor(friday, times(), schedule)
        assertEquals(listOf(Prayer.FAJR, Prayer.JOMOAA, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA), friday.map { it.prayer })
        val jumua = friday.single { it.prayer == Prayer.JOMOAA }
        assertEquals(at(this.friday, "13:15:00"), jumua.iqamahAt)
        assertEquals(at(this.friday, "13:30:00"), jumua.salahEndAt)
        val tuesdayNoon = PrayerFlow.eventsFor(tuesday, times(), schedule)[1]
        assertEquals(Prayer.DHUHR, tuesdayNoon.prayer)
        assertEquals(at(tuesday, "12:27:00"), tuesdayNoon.iqamahAt)
    }

    @Test
    fun theNextAdhanInterruptsAnUnfinishedFlow() {
        // Maghrib prays 60 minutes from 18:38; Isha's adhan at 19:32 still takes over.
        val events = PrayerFlow.eventsFor(tuesday, times(), schedule(Prayer.MAGHRIB, IqamahRule.AfterAdhan(30), 60))
        assertEquals(FlowPhase.SALAH to Prayer.MAGHRIB, phaseAt(events, tuesday, "19:31:59"))
        assertEquals(FlowPhase.ADHAN to Prayer.ISHA, phaseAt(events, tuesday, "19:32:00"))
    }

    @Test
    fun anIshaFlowContinuesPastMidnight() {
        val lateIsha = times(isha = "23:50")
        val yesterday = PrayerFlow.eventsFor(tuesday, lateIsha, schedule(Prayer.ISHA, IqamahRule.AfterAdhan(15), 10))
        val today = PrayerFlow.eventsFor(tuesday.plusDays(1), times(), MosqueSchedule.DEFAULT)
        val wednesday = tuesday.plusDays(1)
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN to Prayer.ISHA, phaseAt(yesterday + today, wednesday, "00:04:59"))
        assertEquals(FlowPhase.SALAH to Prayer.ISHA, phaseAt(yesterday + today, wednesday, "00:05:00"))
        assertEquals(FlowPhase.AFTER_SALAH to Prayer.ISHA, phaseAt(yesterday + today, wednesday, "00:15:00"))
        assertEquals(FlowPhase.IDLE to null, phaseAt(yesterday + today, wednesday, "00:25:00"))
    }

    private fun islamicDay(date: LocalDate, ramadanDay: Int? = null, eidFitr: Boolean = false, eidAdha: Boolean = false) =
        com.tunisianprayertimes.IslamicDay(
            date, com.tunisianprayertimes.HijriCalendarDate(1448, if (ramadanDay != null) 9 else 10, ramadanDay ?: 1, false),
            ramadanDay, isArafah = false, isEidFitr = eidFitr, isEidAdha = eidAdha,
        )

    @Test
    fun onEidTheEidPrayerFollowsSunriseWithoutAnAdhan() {
        // Default Eid prayer: 30 minutes after sunrise (06:12), 30 minutes of prayer and khutba.
        val events = PrayerFlow.eventsFor(tuesday, times(), MosqueSchedule.DEFAULT, islamicDay(tuesday, eidFitr = true))
        val eid = events.single { it.prayer == Prayer.AID_FITR }
        assertEquals(listOf(Prayer.FAJR, Prayer.AID_FITR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA), events.map { it.prayer })
        assertEquals(at(tuesday, "06:42:00"), eid.iqamahAt)
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN to Prayer.AID_FITR, phaseAt(events, tuesday, "06:12:00"))
        assertEquals(FlowPhase.SALAH to Prayer.AID_FITR, phaseAt(events, tuesday, "06:42:00"))
        // No post-fard adhkar after the Eid prayer and khutba: the display returns.
        assertEquals(FlowPhase.IDLE to null, phaseAt(events, tuesday, "07:12:00"))
        assertEquals(1, PrayerFlow.eventsFor(tuesday, times(), MosqueSchedule.DEFAULT, islamicDay(tuesday, eidAdha = true))
            .count { it.prayer == Prayer.AID_ADHA })
        assertTrue(PrayerFlow.eventsFor(tuesday, times(), MosqueSchedule.DEFAULT, islamicDay(tuesday)).none { it.prayer in MosqueSchedule.EID })
    }

    @Test
    fun ramadanSettingsApplyOnlyInRamadan() {
        // Isha with tarawih: 75 minutes of black screen in Ramadan.
        val schedule = MosqueSchedule.DEFAULT.withRamadan(Prayer.ISHA, PrayerOverride(salahMinutes = 75))
        val ramadan = PrayerFlow.eventsFor(tuesday, times(), schedule, islamicDay(tuesday, ramadanDay = 5)).last()
        assertEquals(at(tuesday, "20:57:00"), ramadan.salahEndAt)
        val ordinary = PrayerFlow.eventsFor(tuesday, times(), schedule, islamicDay(tuesday)).last()
        assertEquals(at(tuesday, "19:52:00"), ordinary.salahEndAt)
    }

    @Test
    fun theFirstTarawihIsTheEveningBeforeTheFirstFastAndTheEveOfEidHasNone() {
        val schedule = MosqueSchedule.DEFAULT.withRamadan(Prayer.ISHA, PrayerOverride(salahMinutes = 75))
        val eve = PrayerFlow.eventsFor(tuesday, times(), schedule, islamicDay(tuesday), nextDay = islamicDay(tuesday.plusDays(1), ramadanDay = 1))
        assertEquals(at(tuesday, "20:57:00"), eve.last().salahEndAt)
        val lastDay = PrayerFlow.eventsFor(tuesday, times(), schedule, islamicDay(tuesday, ramadanDay = 30),
            nextDay = islamicDay(tuesday.plusDays(1), eidFitr = true))
        assertEquals(at(tuesday, "19:52:00"), lastDay.last().salahEndAt)
        // Maghrib keeps the day it ends: the iftar of the last fast is still Ramadan's.
        assertEquals(PrayerFlow.eventsFor(tuesday, times(), schedule, islamicDay(tuesday, ramadanDay = 30)).first { it.prayer == Prayer.MAGHRIB },
            lastDay.first { it.prayer == Prayer.MAGHRIB })
    }

    @Test
    fun fridayWaitsForTheIqamahInQuietDuringTheKhutba() {
        val friday = PrayerFlow.eventsFor(friday, times(), MosqueSchedule.DEFAULT) // Jumu'a +15
        assertEquals(FlowPhase.ADHAN to Prayer.JOMOAA, phaseAt(friday, this.friday, "12:17:00"))
        assertEquals(FlowPhase.KHUTBA to Prayer.JOMOAA, phaseAt(friday, this.friday, "12:25:00"))
        assertEquals(FlowPhase.SALAH to Prayer.JOMOAA, phaseAt(friday, this.friday, "12:32:00"))
        val tuesdayNoon = PrayerFlow.eventsFor(tuesday, times(), MosqueSchedule.DEFAULT)
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN to Prayer.DHUHR, phaseAt(tuesdayNoon, tuesday, "12:25:00"))
    }

    @Test
    fun theSameMomentAlwaysGivesTheSameScreen() {
        // Level-triggered: a restart at 18:15 lands in the black screen, not back at the adhan.
        val events = PrayerFlow.eventsFor(tuesday, times(), MosqueSchedule.DEFAULT)
        val first = PrayerFlow.stateAt(at(tuesday, "18:15:00"), events)
        val afterRestart = PrayerFlow.stateAt(at(tuesday, "18:15:00"), PrayerFlow.eventsFor(tuesday, times(), MosqueSchedule.DEFAULT))
        assertEquals(FlowPhase.SALAH, first.phase)
        assertEquals(first, afterRestart)
    }

    @Test
    fun outOfRangeSettingsAreClamped() {
        val events = PrayerFlow.eventsFor(tuesday, times(), schedule(Prayer.FAJR, IqamahRule.AfterAdhan(500), 500))
        val fajr = events.first()
        // +90 and 90 minutes at most; a prayer that cannot end by sunrise starts a minute after the adhan.
        assertEquals(at(tuesday, "04:47:00"), fajr.iqamahAt)
        assertEquals(at(tuesday, "06:17:00"), fajr.salahEndAt)
    }
}
