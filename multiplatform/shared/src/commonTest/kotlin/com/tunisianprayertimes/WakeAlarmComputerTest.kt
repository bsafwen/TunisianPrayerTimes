package com.tunisianprayertimes

import java.util.Calendar
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WakeAlarmComputerTest {

    @Test
    fun fixedAlarmWithDefaultDaysCanRingToday() {
        val now = calendar(2024, Calendar.JANUARY, 1, 7, 0)
        val config = fixedAlarmConfig()

        val result = WakeAlarmComputer.compute(
            now = now,
            config = config,
            prayerDays = prayerDaysAround(now),
        )

        val mainAlarm = assertNotNull(result.mainAlarm)
        assertEquals(calendar(2024, Calendar.JANUARY, 1, 8, 0).timeInMillis, mainAlarm.triggerAtMillis)
    }

    @Test
    fun emptyScheduledDaysAreTreatedAsEveryDay() {
        val now = calendar(2024, Calendar.JANUARY, 1, 7, 0)
        val config = fixedAlarmConfig(scheduledDays = emptySet())

        val result = WakeAlarmComputer.compute(
            now = now,
            config = config,
            prayerDays = prayerDaysAround(now),
        )

        val mainAlarm = assertNotNull(result.mainAlarm)
        assertEquals(calendar(2024, Calendar.JANUARY, 1, 8, 0).timeInMillis, mainAlarm.triggerAtMillis)
    }

    @Test
    fun fixedAlarmSkipsUnselectedDays() {
        val now = calendar(2024, Calendar.JANUARY, 1, 7, 0)
        val config = fixedAlarmConfig(scheduledDays = setOf(WakeScheduleDay.WEDNESDAY))

        val result = WakeAlarmComputer.compute(
            now = now,
            config = config,
            prayerDays = prayerDaysAround(now),
        )

        val mainAlarm = assertNotNull(result.mainAlarm)
        assertEquals(calendar(2024, Calendar.JANUARY, 3, 8, 0).timeInMillis, mainAlarm.triggerAtMillis)
    }

    @Test
    fun fixedAlarmUsesNextSelectedWeekdayWhenTodaysTimePassed() {
        val now = calendar(2024, Calendar.JANUARY, 1, 9, 0)
        val config = fixedAlarmConfig(scheduledDays = setOf(WakeScheduleDay.MONDAY))

        val result = WakeAlarmComputer.compute(
            now = now,
            config = config,
            prayerDays = prayerDaysAround(now),
        )

        val mainAlarm = assertNotNull(result.mainAlarm)
        assertEquals(calendar(2024, Calendar.JANUARY, 8, 8, 0).timeInMillis, mainAlarm.triggerAtMillis)
    }

    @Test
    fun subAlarmsFollowTheSelectedMainAlarmDay() {
        val now = calendar(2024, Calendar.JANUARY, 1, 7, 0)
        val config = fixedAlarmConfig(
            scheduledDays = setOf(WakeScheduleDay.WEDNESDAY),
            subAlarms = listOf(
                PrayerWakeSubAlarm(
                    id = "before_main",
                    minutesOffset = 10,
                    direction = OffsetDirection.BEFORE,
                ),
            ),
        )

        val result = WakeAlarmComputer.compute(
            now = now,
            config = config,
            prayerDays = prayerDaysAround(now),
        )

        val mainAlarm = assertNotNull(result.mainAlarm)
        val subAlarm = assertNotNull(result.subAlarms.singleOrNull())
        assertEquals(calendar(2024, Calendar.JANUARY, 3, 8, 0).timeInMillis, mainAlarm.triggerAtMillis)
        assertEquals(calendar(2024, Calendar.JANUARY, 3, 7, 50).timeInMillis, subAlarm.triggerAtMillis)
    }

    @Test
    fun prayerRelativeDaySelectionUsesThePrayerCivilDay() {
        val now = calendar(2023, Calendar.DECEMBER, 31, 23, 0)
        val config = PrayerWakeConfig(
            id = "fajr_alarm",
            prayer = Prayer.FAJR,
            enabled = true,
            mainAlarm = WakeMainAlarmConfig(
                mode = WakeMainAlarmMode.PRAYER_RELATIVE,
                prayerOffset = PrayerRelativeOffset(-30),
            ),
            scheduledDays = setOf(WakeScheduleDay.MONDAY),
        )

        val result = WakeAlarmComputer.compute(
            now = now,
            config = config,
            prayerDays = prayerDaysAround(now, fajrHour = 0, fajrMinute = 20),
        )

        val mainAlarm = assertNotNull(result.mainAlarm)
        assertEquals(calendar(2023, Calendar.DECEMBER, 31, 23, 50).timeInMillis, mainAlarm.triggerAtMillis)
    }

    @Test
    fun oneOffAlarmIgnoresScheduledDays() {
        val now = calendar(2024, Calendar.JANUARY, 1, 7, 0)
        val oneOffTrigger = calendar(2024, Calendar.JANUARY, 1, 7, 15).timeInMillis
        val config = PrayerWakeConfig(
            id = "timer",
            prayer = Prayer.FAJR,
            enabled = true,
            mainAlarm = WakeMainAlarmConfig(
                mode = WakeMainAlarmMode.FROM_NOW,
                oneOffOffsetMinutes = 15,
                oneOffTriggerAtMillis = oneOffTrigger,
            ),
            scheduledDays = setOf(WakeScheduleDay.SUNDAY),
        )

        val result = WakeAlarmComputer.compute(
            now = now,
            config = config,
            prayerDays = prayerDaysAround(now),
        )

        val mainAlarm = assertNotNull(result.mainAlarm)
        assertEquals(oneOffTrigger, mainAlarm.triggerAtMillis)
    }

    private fun fixedAlarmConfig(
        scheduledDays: Set<WakeScheduleDay> = ALL_WAKE_SCHEDULE_DAYS,
        subAlarms: List<PrayerWakeSubAlarm> = emptyList(),
    ): PrayerWakeConfig = PrayerWakeConfig(
        id = "fixed_alarm",
        prayer = Prayer.FAJR,
        enabled = true,
        mainAlarm = WakeMainAlarmConfig(
            mode = WakeMainAlarmMode.FIXED_TIME,
            fixedTime = ClockTime(8, 0),
        ),
        scheduledDays = scheduledDays,
        subAlarms = subAlarms,
    )

    private fun prayerDaysAround(
        now: Calendar,
        fajrHour: Int = 5,
        fajrMinute: Int = 30,
    ): List<WakeAlarmComputer.PrayerDayContext> = (-1..WAKE_RECURRING_LOOKAHEAD_DAYS).map { dayOffset ->
        val date = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
        }
        WakeAlarmComputer.PrayerDayContext(
            date = date,
            prayerTimes = sampleTimes(
                day = date.get(Calendar.DAY_OF_MONTH),
                fajrHour = fajrHour,
                fajrMinute = fajrMinute,
            ),
            isFriday = date.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY,
            jomoaaHour = 12,
            jomoaaMinute = 30,
        )
    }

    private fun sampleTimes(
        day: Int,
        fajrHour: Int,
        fajrMinute: Int,
    ): DayPrayerTimes = DayPrayerTimes(
        day = day,
        fajr = PrayerTime(Prayer.FAJR, fajrHour, fajrMinute),
        shurukHour = 6,
        shurukMinute = 50,
        dhuhr = PrayerTime(Prayer.DHUHR, 12, 15),
        asr = PrayerTime(Prayer.ASR, 15, 30),
        maghrib = PrayerTime(Prayer.MAGHRIB, 18, 10),
        isha = PrayerTime(Prayer.ISHA, 19, 30),
    )

    private fun calendar(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, month, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }
}
