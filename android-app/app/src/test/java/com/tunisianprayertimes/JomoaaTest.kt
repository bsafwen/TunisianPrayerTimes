package com.tunisianprayertimes

import com.tunisianprayertimes.SilenceAlarmComputer.AlarmAction
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * Tests for the JOMOAA (Friday prayer) feature.
 * JOMOAA replaces DHUHR on Fridays — same time slot, separate silence config.
 */
class JomoaaTest {

    private val day = DayPrayerTimes(
        day = 1,
        fajr = PrayerTime(Prayer.FAJR, 5, 0),
        shurukHour = 6,
        shurukMinute = 30,
        dhuhr = PrayerTime(Prayer.DHUHR, 12, 30),
        asr = PrayerTime(Prayer.ASR, 15, 45),
        maghrib = PrayerTime(Prayer.MAGHRIB, 18, 15),
        isha = PrayerTime(Prayer.ISHA, 19, 45)
    )

    private val defaultConfigs = Prayer.values().associateWith { PrayerSilenceConfig() }

    private fun makeNow(hour: Int, minute: Int): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    // ── PrayerModels: scheduledPrayers ──────────

    @Test
    fun scheduledPrayers_nonFriday_returnsFiveStandardPrayers() {
        val prayers = day.scheduledPrayers(isFriday = false)
        assertEquals(5, prayers.size)
        assertEquals(Prayer.FAJR, prayers[0].prayer)
        assertEquals(Prayer.DHUHR, prayers[1].prayer)
        assertEquals(Prayer.ASR, prayers[2].prayer)
        assertEquals(Prayer.MAGHRIB, prayers[3].prayer)
        assertEquals(Prayer.ISHA, prayers[4].prayer)
    }

    @Test
    fun scheduledPrayers_friday_replaceDhuhrWithJomoaa() {
        val prayers = day.scheduledPrayers(isFriday = true)
        assertEquals(5, prayers.size)
        assertEquals(Prayer.FAJR, prayers[0].prayer)
        assertEquals(Prayer.JOMOAA, prayers[1].prayer)
        assertEquals(Prayer.ASR, prayers[2].prayer)
        assertEquals(Prayer.MAGHRIB, prayers[3].prayer)
        assertEquals(Prayer.ISHA, prayers[4].prayer)
    }

    @Test
    fun scheduledPrayers_friday_jomoaaHasDhuhrTime() {
        val prayers = day.scheduledPrayers(isFriday = true)
        val jomoaa = prayers[1]
        assertEquals(Prayer.JOMOAA, jomoaa.prayer)
        assertEquals(day.dhuhr.hour, jomoaa.hour)
        assertEquals(day.dhuhr.minute, jomoaa.minute)
    }

    @Test
    fun scheduledPrayers_nonFriday_noDhuhrReplacement() {
        val prayers = day.scheduledPrayers(isFriday = false)
        assertFalse(prayers.any { it.prayer == Prayer.JOMOAA })
        assertTrue(prayers.any { it.prayer == Prayer.DHUHR })
    }

    // ── PrayerModels: nextPrayer(isFriday) ──────

    @Test
    fun nextPrayer_friday_beforeDhuhr_returnsJomoaa() {
        assertEquals(Prayer.JOMOAA, day.nextPrayer(9, 0, isFriday = true))
    }

    @Test
    fun nextPrayer_friday_atDhuhrTime_returnsAsr() {
        // At 12:30 exactly, JOMOAA (12:30) is NOT > 12:30, so next is ASR
        assertEquals(Prayer.ASR, day.nextPrayer(12, 30, isFriday = true))
    }

    @Test
    fun nextPrayer_nonFriday_beforeDhuhr_returnsDhuhr() {
        assertEquals(Prayer.DHUHR, day.nextPrayer(9, 0, isFriday = false))
    }

    @Test
    fun nextPrayer_friday_beforeFajr_returnsFajr() {
        assertEquals(Prayer.FAJR, day.nextPrayer(3, 0, isFriday = true))
    }

    @Test
    fun nextPrayer_friday_afterIsha_returnsNull() {
        assertNull(day.nextPrayer(20, 0, isFriday = true))
    }

    @Test
    fun nextPrayer_friday_betweenJomoaaAndAsr_returnsAsr() {
        assertEquals(Prayer.ASR, day.nextPrayer(13, 0, isFriday = true))
    }

    // ── SilenceAlarmComputer: Friday scheduling ─

    @Test
    fun compute_friday_usesJomoaaConfigForDhuhrSlot() {
        val jomoaaConfig = PrayerSilenceConfig(afterMinutes = 90)
        val configs = defaultConfigs + (Prayer.JOMOAA to jomoaaConfig)
        val now = makeNow(3, 0)

        val result = SilenceAlarmComputer.compute(now, day, configs, isFriday = true)

        val jomoaaAlarms = result.alarms.filter { it.prayer == Prayer.JOMOAA }
        assertEquals(2, jomoaaAlarms.size) // SILENCE + UNSILENCE

        val dhuhrAlarms = result.alarms.filter { it.prayer == Prayer.DHUHR }
        assertEquals(0, dhuhrAlarms.size) // No DHUHR on Friday
    }

    @Test
    fun compute_nonFriday_usesDhuhrConfig() {
        val jomoaaConfig = PrayerSilenceConfig(afterMinutes = 90)
        val configs = defaultConfigs + (Prayer.JOMOAA to jomoaaConfig)
        val now = makeNow(3, 0)

        val result = SilenceAlarmComputer.compute(now, day, configs, isFriday = false)

        val dhuhrAlarms = result.alarms.filter { it.prayer == Prayer.DHUHR }
        assertEquals(2, dhuhrAlarms.size) // SILENCE + UNSILENCE

        val jomoaaAlarms = result.alarms.filter { it.prayer == Prayer.JOMOAA }
        assertEquals(0, jomoaaAlarms.size) // No JOMOAA on non-Friday
    }

    @Test
    fun compute_friday_jomoaaInsideSilenceWindow() {
        // Jomoaa starts at 12:30, with 90 min duration -> unsilence at 14:00
        val jomoaaConfig = PrayerSilenceConfig(afterMinutes = 90)
        val configs = defaultConfigs + (Prayer.JOMOAA to jomoaaConfig)
        val now = makeNow(13, 0) // Inside Jomoaa window

        val result = SilenceAlarmComputer.compute(now, day, configs, isFriday = true)

        assertTrue(result.currentlyInSilenceWindow)
        // Should have UNSILENCE for JOMOAA
        assertTrue(result.alarms.any { it.prayer == Prayer.JOMOAA && it.action == AlarmAction.UNSILENCE })
    }

    @Test
    fun compute_friday_jomoaaWithCustomDelay() {
        val jomoaaConfig = PrayerSilenceConfig(
            afterMinutes = 60,
            delayMode = DelayMode.MINUTES,
            delayMinutes = 10
        )
        val configs = defaultConfigs + (Prayer.JOMOAA to jomoaaConfig)
        val now = makeNow(3, 0)

        val result = SilenceAlarmComputer.compute(now, day, configs, isFriday = true)

        val jomoaaSilence = result.alarms.first { it.prayer == Prayer.JOMOAA && it.action == AlarmAction.SILENCE }
        val silenceCal = Calendar.getInstance().apply { timeInMillis = jomoaaSilence.triggerAtMillis }
        // Dhuhr at 12:30 + 10 min delay = 12:40
        assertEquals(12, silenceCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(40, silenceCal.get(Calendar.MINUTE))
    }

    @Test
    fun compute_friday_jomoaaWithFixedTimeEnd() {
        val jomoaaConfig = PrayerSilenceConfig(
            mode = SilenceMode.FIXED_TIME,
            fixedHour = 14,
            fixedMinute = 0
        )
        val configs = defaultConfigs + (Prayer.JOMOAA to jomoaaConfig)
        val now = makeNow(3, 0)

        val result = SilenceAlarmComputer.compute(now, day, configs, isFriday = true)

        val jomoaaUnsilence = result.alarms.first { it.prayer == Prayer.JOMOAA && it.action == AlarmAction.UNSILENCE }
        val unsilenceCal = Calendar.getInstance().apply { timeInMillis = jomoaaUnsilence.triggerAtMillis }
        assertEquals(14, unsilenceCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, unsilenceCal.get(Calendar.MINUTE))
    }

    @Test
    fun compute_friday_totalAlarmsCorrect() {
        val now = makeNow(3, 0) // Before all prayers
        val result = SilenceAlarmComputer.compute(now, day, defaultConfigs, isFriday = true)

        val prayerAlarms = result.alarms.filter { it.action != AlarmAction.MIDNIGHT_RESCHEDULE }
        // 5 prayers (FAJR, JOMOAA, ASR, MAGHRIB, ISHA) × 2 = 10
        assertEquals(10, prayerAlarms.size)
    }

    // ── Overlap detection with JOMOAA ───────────

    @Test
    fun overlapsNextPrayer_jomoaaOverlapsAsr() {
        val jomoaaPt = PrayerTime(Prayer.JOMOAA, 12, 30)
        val asrPt = PrayerTime(Prayer.ASR, 15, 45)
        // 12:30 + 200 min = 15:50 > 15:45
        val config = PrayerSilenceConfig(afterMinutes = 200)
        assertTrue(SilenceAlarmComputer.overlapsNextPrayer(jomoaaPt, asrPt, config))
    }

    @Test
    fun overlapsNextPrayer_jomoaaDoesNotOverlapAsr() {
        val jomoaaPt = PrayerTime(Prayer.JOMOAA, 12, 30)
        val asrPt = PrayerTime(Prayer.ASR, 15, 45)
        // 12:30 + 60 min = 13:30 < 15:45
        val config = PrayerSilenceConfig(afterMinutes = 60)
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(jomoaaPt, asrPt, config))
    }

    // ── allPrayers() is unchanged ───────────────

    @Test
    fun allPrayers_doesNotIncludeJomoaa() {
        val all = day.allPrayers()
        assertEquals(5, all.size)
        assertFalse(all.any { it.prayer == Prayer.JOMOAA })
    }

    // ── Custom Jomoaa time ──────────────────────

    @Test
    fun scheduledPrayers_friday_customTime_usesCustomTime() {
        val prayers = day.scheduledPrayers(isFriday = true, jomoaaHour = 13, jomoaaMinute = 0)
        val jomoaa = prayers[1]
        assertEquals(Prayer.JOMOAA, jomoaa.prayer)
        assertEquals(13, jomoaa.hour)
        assertEquals(0, jomoaa.minute)
    }

    @Test
    fun scheduledPrayers_friday_noCustomTime_usesDhuhrTime() {
        val prayers = day.scheduledPrayers(isFriday = true, jomoaaHour = -1, jomoaaMinute = -1)
        val jomoaa = prayers[1]
        assertEquals(day.dhuhr.hour, jomoaa.hour)
        assertEquals(day.dhuhr.minute, jomoaa.minute)
    }

    @Test
    fun scheduledPrayers_nonFriday_customTimeIgnored() {
        val prayers = day.scheduledPrayers(isFriday = false, jomoaaHour = 13, jomoaaMinute = 0)
        assertFalse(prayers.any { it.prayer == Prayer.JOMOAA })
        assertEquals(day.dhuhr.hour, prayers[1].hour)
    }

    @Test
    fun nextPrayer_friday_customTimeBeforeDhuhr_returnsJomoaa() {
        // Custom Jomoaa at 13:00, current time 12:45 → next is JOMOAA
        assertEquals(Prayer.JOMOAA, day.nextPrayer(12, 45, isFriday = true, jomoaaHour = 13, jomoaaMinute = 0))
    }

    @Test
    fun nextPrayer_friday_customTimeAfterDhuhr_atDhuhrTime_returnsJomoaa() {
        // Default Dhuhr is 12:30, custom Jomoaa at 13:00, current time 12:30 → next is JOMOAA at 13:00
        assertEquals(Prayer.JOMOAA, day.nextPrayer(12, 30, isFriday = true, jomoaaHour = 13, jomoaaMinute = 0))
    }

    @Test
    fun nextPrayer_friday_customTimePassed_returnsAsr() {
        // Custom Jomoaa at 13:00, current time 13:00 → JOMOAA passed, next is ASR
        assertEquals(Prayer.ASR, day.nextPrayer(13, 0, isFriday = true, jomoaaHour = 13, jomoaaMinute = 0))
    }

    @Test
    fun compute_friday_customTime_usesCustomTimeForAlarm() {
        val jomoaaConfig = PrayerSilenceConfig(afterMinutes = 60)
        val configs = defaultConfigs + (Prayer.JOMOAA to jomoaaConfig)
        val now = makeNow(3, 0)

        val result = SilenceAlarmComputer.compute(
            now, day, configs, isFriday = true,
            jomoaaHour = 13, jomoaaMinute = 15
        )

        val jomoaaSilence = result.alarms.first { it.prayer == Prayer.JOMOAA && it.action == AlarmAction.SILENCE }
        val silenceCal = Calendar.getInstance().apply { timeInMillis = jomoaaSilence.triggerAtMillis }
        assertEquals(13, silenceCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, silenceCal.get(Calendar.MINUTE))
    }

    @Test
    fun compute_friday_customTime_unsilenceFollowsCustomStart() {
        val jomoaaConfig = PrayerSilenceConfig(afterMinutes = 45)
        val configs = defaultConfigs + (Prayer.JOMOAA to jomoaaConfig)
        val now = makeNow(3, 0)

        val result = SilenceAlarmComputer.compute(
            now, day, configs, isFriday = true,
            jomoaaHour = 13, jomoaaMinute = 0
        )

        val jomoaaUnsilence = result.alarms.first { it.prayer == Prayer.JOMOAA && it.action == AlarmAction.UNSILENCE }
        val unsilenceCal = Calendar.getInstance().apply { timeInMillis = jomoaaUnsilence.triggerAtMillis }
        // 13:00 + 45 min = 13:45
        assertEquals(13, unsilenceCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(45, unsilenceCal.get(Calendar.MINUTE))
    }
}
