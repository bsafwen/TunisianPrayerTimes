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
        // Default Maghrib: iqamah +5, prayer 8 minutes; adhan screen 2 minutes, adhkar 10.
        val events = PrayerFlow.eventsFor(tuesday, times(), MosqueSchedule.DEFAULT)
        assertEquals(FlowPhase.IDLE to null, phaseAt(events, tuesday, "18:07:59"))
        assertEquals(FlowPhase.ADHAN to Prayer.MAGHRIB, phaseAt(events, tuesday, "18:08:00"))
        assertEquals(FlowPhase.ADHAN to Prayer.MAGHRIB, phaseAt(events, tuesday, "18:09:59"))
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN to Prayer.MAGHRIB, phaseAt(events, tuesday, "18:10:00"))
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
    fun aStaleFixedTimeFallsBackToTheMosquesOwnDelay() {
        // The mosque usually calls Isha at +15 and set a winter 19:00; the adhan is now 19:32.
        val schedule = schedule(Prayer.ISHA, IqamahRule.FixedTime(LocalTime.of(19, 0)), 10).copy(delays = mapOf(Prayer.ISHA to 15))
        val isha = PrayerFlow.eventsFor(tuesday, times(), schedule).single { it.prayer == Prayer.ISHA }
        assertEquals(at(tuesday, "19:47:00"), isha.iqamahAt)
        assertTrue(isha.iqamahAdjusted)
    }

    @Test
    fun aStaleRamadanFixedTimeFallsBackToTheUsualMinutes() {
        val schedule = schedule(Prayer.ISHA, IqamahRule.AfterAdhan(20), 10)
            .withRamadan(Prayer.ISHA, PrayerOverride(IqamahRule.FixedTime(LocalTime.of(19, 0))))
        val isha = PrayerFlow.eventsFor(tuesday, times(), schedule, islamicDay(tuesday, ramadanDay = 5)).last()
        assertEquals(at(tuesday, "19:52:00"), isha.iqamahAt)
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
    fun theIqamahWaitsForTheEndOfTheAdhanScreen() {
        // +0 (read as +1) or +1 with the 2-minute adhan screen: the black screen starts when the adhan screen ends.
        listOf(0, 1).forEach { minutes ->
            val events = PrayerFlow.eventsFor(tuesday, times(), schedule(Prayer.ASR, IqamahRule.AfterAdhan(minutes), 10))
            val asr = events.single { it.prayer == Prayer.ASR }
            assertEquals(at(tuesday, "15:34:00"), asr.iqamahAt)
            assertEquals(at(tuesday, "15:34:00"), asr.adhanScreenEndAt)
            assertTrue(asr.iqamahAdjusted, "the admin sees the iqamah was moved")
            assertEquals(FlowPhase.ADHAN to Prayer.ASR, phaseAt(events, tuesday, "15:33:59"))
            assertEquals(FlowPhase.SALAH to Prayer.ASR, phaseAt(events, tuesday, "15:34:00"))
        }
        // A fixed time too close to the adhan waits the same way; one at the end of the adhan screen is kept.
        val close = PrayerFlow.eventsFor(tuesday, times(), schedule(Prayer.MAGHRIB, IqamahRule.FixedTime(LocalTime.of(18, 9)), 8))
            .single { it.prayer == Prayer.MAGHRIB }
        assertEquals(at(tuesday, "18:10:00") to true, close.iqamahAt to close.iqamahAdjusted)
        val atTheEnd = PrayerFlow.eventsFor(tuesday, times(), schedule(Prayer.MAGHRIB, IqamahRule.FixedTime(LocalTime.of(18, 10)), 8))
            .single { it.prayer == Prayer.MAGHRIB }
        assertEquals(at(tuesday, "18:10:00") to false, atTheEnd.iqamahAt to atTheEnd.iqamahAdjusted)
        // A longer adhan screen pushes a +3 iqamah to its end; +2 is on time with the default.
        val longer = PrayerFlow.eventsFor(tuesday, times(), schedule(Prayer.ASR, IqamahRule.AfterAdhan(3), 10), timing = FlowTiming(adhanScreenMinutes = 5))
            .single { it.prayer == Prayer.ASR }
        assertEquals(at(tuesday, "15:37:00") to true, longer.iqamahAt to longer.iqamahAdjusted)
        val onTime = PrayerFlow.eventsFor(tuesday, times(), schedule(Prayer.ASR, IqamahRule.AfterAdhan(2), 10)).single { it.prayer == Prayer.ASR }
        assertEquals(at(tuesday, "15:34:00") to false, onTime.iqamahAt to onTime.iqamahAdjusted)
    }

    @Test
    fun theAdhanScreenLastsTheMosquesMinutes() {
        fun maghrib(minutes: Int) = PrayerFlow.eventsFor(tuesday, times(), MosqueSchedule.DEFAULT, timing = FlowTiming(adhanScreenMinutes = minutes))
            .single { it.prayer == Prayer.MAGHRIB }
        assertEquals(at(tuesday, "18:09:00"), maghrib(1).adhanScreenEndAt)
        assertEquals(at(tuesday, "18:13:00"), maghrib(5).adhanScreenEndAt)
        assertEquals(at(tuesday, "18:13:00"), maghrib(5).iqamahAt) // +5: on time, not moved
        assertFalse(maghrib(5).iqamahAdjusted)
        // Out of range, as from a hand-edited setting: kept within 1..5.
        assertEquals(at(tuesday, "18:09:00"), maghrib(0).adhanScreenEndAt)
        assertEquals(at(tuesday, "18:13:00"), maghrib(30).adhanScreenEndAt)
        // Two minutes unless the mosque chose otherwise.
        assertEquals(2, FlowTiming().adhanScreenMinutes)
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
        // The settings page marks the prayers whose Ramadan changes run today, and only those.
        assertTrue(ramadan.ramadanSettings)
        assertFalse(ordinary.ramadanSettings)
        assertTrue(PrayerFlow.eventsFor(tuesday, times(), schedule, islamicDay(tuesday, ramadanDay = 5)).dropLast(1).none { it.ramadanSettings })
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
    fun aLateFixedJumuaCountsDownUntilTheKhutba() {
        // Jumu'a at 13:15 after a 12:17 adhan, with a khutba of 30 minutes: the countdown until 12:45, then the quiet screen.
        val jumua = PrayerSettings(IqamahRule.FixedTime(LocalTime.of(13, 15)), 15, khutbaMinutes = 30)
        val events = PrayerFlow.eventsFor(friday, times(), MosqueSchedule.DEFAULT.with(Prayer.JOMOAA, jumua))
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN to Prayer.JOMOAA, phaseAt(events, friday, "12:30:00"))
        assertEquals(at(friday, "12:45:00"), PrayerFlow.stateAt(at(friday, "12:30:00"), events).phaseEndsAt)
        assertEquals(FlowPhase.KHUTBA to Prayer.JOMOAA, phaseAt(events, friday, "12:45:00"))
        assertEquals(at(friday, "13:15:00"), PrayerFlow.stateAt(at(friday, "12:45:00"), events).phaseEndsAt)
        assertEquals(FlowPhase.SALAH to Prayer.JOMOAA, phaseAt(events, friday, "13:15:00"))
        // Without a khutba length, the khutba screen follows the adhan, as by default.
        val whole = PrayerFlow.eventsFor(friday, times(), MosqueSchedule.DEFAULT.with(Prayer.JOMOAA, jumua.copy(khutbaMinutes = 0)))
        assertEquals(FlowPhase.KHUTBA to Prayer.JOMOAA, phaseAt(whole, friday, "12:30:00"))
        // A khutba longer than the wait starts after the adhan screen too.
        val short = PrayerFlow.eventsFor(friday, times(), MosqueSchedule.DEFAULT.with(Prayer.JOMOAA, PrayerSettings(IqamahRule.AfterAdhan(15), 15, khutbaMinutes = 30)))
        assertEquals(FlowPhase.KHUTBA to Prayer.JOMOAA, phaseAt(short, friday, "12:20:00"))
        assertEquals(null, PrayerFlow.eventsFor(tuesday, times(), MosqueSchedule.DEFAULT)[1].khutbaAt, "only Jumu'a has a khutba")
    }

    @Test
    fun aMosqueWithoutJumuaPraysDhuhrOnFridays() {
        val schedule = MosqueSchedule.DEFAULT.with(Prayer.JOMOAA, PrayerSettings(IqamahRule.AfterAdhan(15), 15, held = false))
        val events = PrayerFlow.eventsFor(friday, times(), schedule)
        assertEquals(listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA), events.map { it.prayer })
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN to Prayer.DHUHR, phaseAt(events, friday, "12:25:00"))
        assertEquals(at(friday, "12:27:00"), events[1].iqamahAt)
    }

    @Test
    fun aMosqueWithoutTheEidPrayerHasNoEidEvent() {
        val schedule = MosqueSchedule.DEFAULT
            .with(Prayer.AID_FITR, PrayerSettings(IqamahRule.AfterAdhan(30), 30, held = false))
            // A Ramadan change to Jumu'a keeps whether it is held.
            .with(Prayer.JOMOAA, PrayerSettings(IqamahRule.AfterAdhan(15), 15, held = false))
            .withRamadan(Prayer.JOMOAA, PrayerOverride(salahMinutes = 20))
        assertTrue(PrayerFlow.eventsFor(tuesday, times(), schedule, islamicDay(tuesday, eidFitr = true)).none { it.prayer in MosqueSchedule.EID })
        assertEquals(1, PrayerFlow.eventsFor(tuesday, times(), schedule, islamicDay(tuesday, eidAdha = true)).count { it.prayer == Prayer.AID_ADHA })
        assertFalse(schedule.settingsOn(Prayer.JOMOAA, isRamadan = true).held)
        assertEquals(Prayer.DHUHR, PrayerFlow.eventsFor(friday, times(), schedule, islamicDay(friday, ramadanDay = 5))[1].prayer)
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
