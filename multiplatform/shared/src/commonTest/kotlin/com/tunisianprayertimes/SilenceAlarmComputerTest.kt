package com.tunisianprayertimes

import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilenceAlarmComputerTest {

    private fun calendar(hour: Int, minute: Int): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private val sampleTimes = DayPrayerTimes(
        day = 1,
        fajr = PrayerTime(Prayer.FAJR, 5, 30),
        shurukHour = 6,
        shurukMinute = 50,
        dhuhr = PrayerTime(Prayer.DHUHR, 12, 15),
        asr = PrayerTime(Prayer.ASR, 15, 30),
        maghrib = PrayerTime(Prayer.MAGHRIB, 18, 10),
        isha = PrayerTime(Prayer.ISHA, 19, 30)
    )

    private val defaultConfigs = Prayer.entries.associateWith { PrayerSilenceConfig() }

    @Test
    fun beforeAllPrayersSchedulesAllAlarms() {
        val now = calendar(3, 0)
        val result = SilenceAlarmComputer.compute(now, sampleTimes, defaultConfigs)

        assertFalse(result.currentlyInSilenceWindow)
        // 5 prayers × 2 (silence + unsilence) + 1 midnight = 11
        val silenceAlarms = result.alarms.filter { it.action == SilenceAlarmComputer.AlarmAction.SILENCE }
        val unsilenceAlarms = result.alarms.filter { it.action == SilenceAlarmComputer.AlarmAction.UNSILENCE }
        val midnightAlarms = result.alarms.filter { it.action == SilenceAlarmComputer.AlarmAction.MIDNIGHT_RESCHEDULE }

        assertEquals(5, silenceAlarms.size)
        assertEquals(5, unsilenceAlarms.size)
        assertEquals(1, midnightAlarms.size)
    }

    @Test
    fun duringPrayerSilenceWindowDetected() {
        // Fajr is at 5:30, default config: delay=0, duration=30 → silence 5:30–6:00
        val now = calendar(5, 45)
        val result = SilenceAlarmComputer.compute(now, sampleTimes, defaultConfigs)

        assertTrue(result.currentlyInSilenceWindow)
        // Should have an unsilence alarm for fajr
        val unsilenceFajr = result.alarms.filter {
            it.prayer == Prayer.FAJR && it.action == SilenceAlarmComputer.AlarmAction.UNSILENCE
        }
        assertTrue(unsilenceFajr.isNotEmpty())
    }

    @Test
    fun afterAllPrayersNoSilenceWindow() {
        val now = calendar(20, 30) // after isha (19:30) + 30 min
        val result = SilenceAlarmComputer.compute(now, sampleTimes, defaultConfigs)

        assertFalse(result.currentlyInSilenceWindow)
    }

    @Test
    fun midnightRescheduleAlwaysPresent() {
        val now = calendar(12, 0)
        val result = SilenceAlarmComputer.compute(now, sampleTimes, defaultConfigs)

        val midnight = result.alarms.filter {
            it.action == SilenceAlarmComputer.AlarmAction.MIDNIGHT_RESCHEDULE
        }
        assertEquals(1, midnight.size)
    }

    @Test
    fun delayMinutesShiftsSilenceStart() {
        val configs = defaultConfigs.toMutableMap()
        configs[Prayer.FAJR] = PrayerSilenceConfig(delayMinutes = 10, afterMinutes = 30)

        val now = calendar(5, 35) // 5 min after fajr but before delay expires (5:40)
        val result = SilenceAlarmComputer.compute(now, sampleTimes, configs)

        // Should NOT be in silence window yet (silence starts at 5:40)
        val fajrSilence = result.alarms.firstOrNull {
            it.prayer == Prayer.FAJR && it.action == SilenceAlarmComputer.AlarmAction.SILENCE
        }
        assertTrue(fajrSilence != null, "Fajr silence alarm should be scheduled in the future")
        assertTrue(fajrSilence!!.triggerAtMillis > now.timeInMillis)
    }

    @Test
    fun fixedTimeModeUsesFixedEndTime() {
        val configs = defaultConfigs.toMutableMap()
        configs[Prayer.DHUHR] = PrayerSilenceConfig(
            mode = SilenceMode.FIXED_TIME,
            fixedHour = 13,
            fixedMinute = 15
        )

        val now = calendar(12, 20) // during dhuhr silence
        val result = SilenceAlarmComputer.compute(now, sampleTimes, configs)

        assertTrue(result.currentlyInSilenceWindow)
        val unsilenceDhuhr = result.alarms.firstOrNull {
            it.prayer == Prayer.DHUHR && it.action == SilenceAlarmComputer.AlarmAction.UNSILENCE
        }
        assertTrue(unsilenceDhuhr != null)

        // Verify the unsilence time is at 13:15
        val triggerCal = Calendar.getInstance().apply { timeInMillis = unsilenceDhuhr!!.triggerAtMillis }
        assertEquals(13, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, triggerCal.get(Calendar.MINUTE))
    }

    @Test
    fun fixedTimeDelayModeUsesFixedStartTime() {
        val configs = defaultConfigs.toMutableMap()
        configs[Prayer.DHUHR] = PrayerSilenceConfig(
            delayMode = DelayMode.FIXED_TIME,
            delayFixedHour = 12,
            delayFixedMinute = 30,
            afterMinutes = 30
        )

        val now = calendar(12, 20) // before fixed delay time (12:30)
        val result = SilenceAlarmComputer.compute(now, sampleTimes, configs)

        // Should NOT be in silence window (silence starts at 12:30)
        val dhuhrSilence = result.alarms.firstOrNull {
            it.prayer == Prayer.DHUHR && it.action == SilenceAlarmComputer.AlarmAction.SILENCE
        }
        assertTrue(dhuhrSilence != null)
    }

    @Test
    fun fridayUsesJomoaaPrayer() {
        val result = sampleTimes.scheduledPrayers(isFriday = true, jomoaaHour = 12, jomoaaMinute = 30)

        assertEquals(5, result.size)
        val jomoaa = result.find { it.prayer == Prayer.JOMOAA }
        assertTrue(jomoaa != null)
        assertEquals(12, jomoaa!!.hour)
        assertEquals(30, jomoaa.minute)
        // Dhuhr should be replaced by Jomoaa
        assertTrue(result.none { it.prayer == Prayer.DHUHR })
    }

    @Test
    fun tomorrowFajrScheduledAfterIsha() {
        val tomorrowTimes = DayPrayerTimes(
            day = 2,
            fajr = PrayerTime(Prayer.FAJR, 5, 28),
            shurukHour = 6,
            shurukMinute = 48,
            dhuhr = PrayerTime(Prayer.DHUHR, 12, 15),
            asr = PrayerTime(Prayer.ASR, 15, 30),
            maghrib = PrayerTime(Prayer.MAGHRIB, 18, 10),
            isha = PrayerTime(Prayer.ISHA, 19, 30)
        )

        val now = calendar(20, 30) // well after isha + 30 min
        val result = SilenceAlarmComputer.compute(now, sampleTimes, defaultConfigs, tomorrowTimes)

        assertTrue(result.tomorrowFajrScheduled)
        val fajrAlarms = result.alarms.filter { it.prayer == Prayer.FAJR }
        assertTrue(fajrAlarms.any { it.action == SilenceAlarmComputer.AlarmAction.SILENCE })
        assertTrue(fajrAlarms.any { it.action == SilenceAlarmComputer.AlarmAction.UNSILENCE })
    }

    @Test
    fun computeSilenceStartWithDefaultDelay() {
        val now = calendar(10, 0)
        val prayerTime = PrayerTime(Prayer.DHUHR, 12, 15)
        val config = PrayerSilenceConfig(delayMinutes = 0)

        val result = SilenceAlarmComputer.computeSilenceStart(now, prayerTime, config)

        assertEquals(12, result.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, result.get(Calendar.MINUTE))
    }

    @Test
    fun computeUnsilenceEndWithDuration() {
        val now = calendar(10, 0)
        val silenceStart = calendar(12, 15)
        val config = PrayerSilenceConfig(afterMinutes = 45)

        val result = SilenceAlarmComputer.computeUnsilenceEnd(now, silenceStart, config)

        assertEquals(13, result.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, result.get(Calendar.MINUTE))
    }
}
