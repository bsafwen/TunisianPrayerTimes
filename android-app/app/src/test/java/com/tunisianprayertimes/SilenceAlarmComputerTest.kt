package com.tunisianprayertimes

import com.tunisianprayertimes.SilenceAlarmComputer.AlarmAction
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * Deterministic tests for the silence alarm scheduling logic.
 *
 * NO Android dependencies, NO Robolectric, NO wall-clock dependency.
 * Every test controls the exact time, prayer times, and configs — so they
 * produce the same result regardless of when or where they run.
 *
 * These tests cover the scenarios that previously slipped through because
 * existing tests depended on the real clock and skipped branches with
 * `if (!isTimePast()) return`.
 */
class SilenceAlarmComputerTest {

    // --- Test fixtures ---

    /** Typical Tunisian prayer times (April). */
    private val typicalDay = DayPrayerTimes(
        day = 2,
        fajr = PrayerTime(Prayer.FAJR, 4, 35),
        dhuhr = PrayerTime(Prayer.DHUHR, 12, 38),
        asr = PrayerTime(Prayer.ASR, 16, 10),
        maghrib = PrayerTime(Prayer.MAGHRIB, 19, 22),
        isha = PrayerTime(Prayer.ISHA, 20, 45)
    )

    private val tomorrowDay = DayPrayerTimes(
        day = 3,
        fajr = PrayerTime(Prayer.FAJR, 4, 34),
        dhuhr = PrayerTime(Prayer.DHUHR, 12, 38),
        asr = PrayerTime(Prayer.ASR, 16, 10),
        maghrib = PrayerTime(Prayer.MAGHRIB, 19, 23),
        isha = PrayerTime(Prayer.ISHA, 20, 46)
    )

    private val defaultConfigs = Prayer.values().associateWith { PrayerSilenceConfig() }

    private fun makeNow(hour: Int, minute: Int): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun configsWith(
        prayer: Prayer,
        afterMinutes: Int = 30,
        mode: SilenceMode = SilenceMode.DURATION,
        fixedHour: Int = -1,
        fixedMinute: Int = -1,
        delayMode: DelayMode = DelayMode.MINUTES,
        delayMinutes: Int = 0,
        delayFixedHour: Int = -1,
        delayFixedMinute: Int = -1
    ): Map<Prayer, PrayerSilenceConfig> {
        return defaultConfigs + (prayer to PrayerSilenceConfig(
            mode = mode,
            afterMinutes = afterMinutes,
            fixedHour = fixedHour,
            fixedMinute = fixedMinute,
            delayMode = delayMode,
            delayMinutes = delayMinutes,
            delayFixedHour = delayFixedHour,
            delayFixedMinute = delayFixedMinute
        ))
    }

    // ==================== Basic scheduling ====================

    @Test
    fun beforeAllPrayers_schedulesAllSilenceAndUnsilence() {
        val now = makeNow(3, 0) // 03:00 — before Fajr
        val result = SilenceAlarmComputer.compute(now, typicalDay, defaultConfigs)

        // 5 prayers × 2 (silence + unsilence) + 1 midnight = 11
        val prayerAlarms = result.alarms.filter { it.action != AlarmAction.MIDNIGHT_RESCHEDULE }
        assertEquals("Each prayer should have silence + unsilence", 10, prayerAlarms.size)

        for (prayer in Prayer.values()) {
            assertTrue("Should have SILENCE for $prayer",
                prayerAlarms.any { it.prayer == prayer && it.action == AlarmAction.SILENCE })
            assertTrue("Should have UNSILENCE for $prayer",
                prayerAlarms.any { it.prayer == prayer && it.action == AlarmAction.UNSILENCE })
        }
        assertFalse(result.currentlyInSilenceWindow)
    }

    @Test
    fun afterAllPrayers_noPrayerAlarms() {
        // 21:15 + 30 min default duration = Isha unsilence at 21:15, all prayers done
        val now = makeNow(21, 30)
        val result = SilenceAlarmComputer.compute(now, typicalDay, defaultConfigs)

        val prayerAlarms = result.alarms.filter { it.action != AlarmAction.MIDNIGHT_RESCHEDULE }
        assertEquals("No prayer alarms after all windows", 0, prayerAlarms.size)
        assertFalse(result.currentlyInSilenceWindow)
    }

    @Test
    fun midDay_onlyFuturePrayersScheduled() {
        val now = makeNow(14, 0) // 14:00 — after Dhuhr, before Asr
        val result = SilenceAlarmComputer.compute(now, typicalDay, defaultConfigs)

        val prayerAlarms = result.alarms.filter { it.action != AlarmAction.MIDNIGHT_RESCHEDULE }
        val scheduledPrayers = prayerAlarms.map { it.prayer }.toSet()

        assertFalse("Fajr should not be scheduled", scheduledPrayers.contains(Prayer.FAJR))
        assertFalse("Dhuhr should not be scheduled", scheduledPrayers.contains(Prayer.DHUHR))
        assertTrue("Asr should be scheduled", scheduledPrayers.contains(Prayer.ASR))
        assertTrue("Maghrib should be scheduled", scheduledPrayers.contains(Prayer.MAGHRIB))
        assertTrue("Isha should be scheduled", scheduledPrayers.contains(Prayer.ISHA))
    }

    // ==================== Inside silence window ====================

    @Test
    fun insideFajrWindow_onlyUnsilenceScheduledForFajr() {
        val now = makeNow(4, 50) // Fajr at 04:35, default 30 min → window is 04:35–05:05
        val result = SilenceAlarmComputer.compute(now, typicalDay, defaultConfigs)

        assertTrue(result.currentlyInSilenceWindow)

        val fajrAlarms = result.alarms.filter { it.prayer == Prayer.FAJR && it.action != AlarmAction.MIDNIGHT_RESCHEDULE }
        assertEquals("Inside Fajr window: only UNSILENCE", 1, fajrAlarms.size)
        assertEquals(AlarmAction.UNSILENCE, fajrAlarms[0].action)
    }

    @Test
    fun insideIshaWindow_marksAsInSilenceWindow() {
        val now = makeNow(20, 50) // Isha at 20:45, window 20:45–21:15
        val result = SilenceAlarmComputer.compute(now, typicalDay, defaultConfigs)

        assertTrue(result.currentlyInSilenceWindow)
    }

    @Test
    fun exactlyAtSilenceStart_isInsideWindow() {
        val now = makeNow(4, 35) // exactly at Fajr
        val result = SilenceAlarmComputer.compute(now, typicalDay, defaultConfigs)

        assertTrue("At exact silence start should be inside window", result.currentlyInSilenceWindow)
    }

    @Test
    fun exactlyAtUnsilenceTime_isOutsideWindow() {
        // Fajr 04:35 + 30 min = unsilence at 05:05
        val now = makeNow(5, 5)
        val result = SilenceAlarmComputer.compute(now, typicalDay, defaultConfigs)

        val fajrAlarms = result.alarms.filter { it.prayer == Prayer.FAJR && it.action != AlarmAction.MIDNIGHT_RESCHEDULE }
        // At exactly unsilence time, now.before(unsilenceTime) is false → not in window
        assertFalse("At exact unsilence boundary, not in window for Fajr",
            fajrAlarms.any { it.action == AlarmAction.UNSILENCE })
    }

    // ==================== Duration and delay configs ====================

    @Test
    fun customDuration_45min_extendsUnsilenceTime() {
        val configs = configsWith(Prayer.FAJR, afterMinutes = 45)
        val now = makeNow(5, 10) // Fajr 04:35 + 45 = 05:20 → still inside
        val result = SilenceAlarmComputer.compute(now, typicalDay, configs)

        assertTrue("5:10 should be inside Fajr window with 45-min duration", result.currentlyInSilenceWindow)

        val fajrUnsilence = result.alarms.first { it.prayer == Prayer.FAJR && it.action == AlarmAction.UNSILENCE }
        val unsilenceCal = Calendar.getInstance().apply { timeInMillis = fajrUnsilence.triggerAtMillis }
        assertEquals(5, unsilenceCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(20, unsilenceCal.get(Calendar.MINUTE))
    }

    @Test
    fun delayMinutes_shiftsWindow() {
        val configs = configsWith(Prayer.FAJR, delayMinutes = 10) // silence starts at 04:45
        val now = makeNow(4, 40) // 04:40 — between Fajr time and delayed silence start

        val result = SilenceAlarmComputer.compute(now, typicalDay, configs)
        assertFalse("4:40 should NOT be in window (delay pushes start to 4:45)", result.currentlyInSilenceWindow)

        // Fajr silence should be scheduled in the future
        val fajrSilence = result.alarms.first { it.prayer == Prayer.FAJR && it.action == AlarmAction.SILENCE }
        val silenceCal = Calendar.getInstance().apply { timeInMillis = fajrSilence.triggerAtMillis }
        assertEquals(4, silenceCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(45, silenceCal.get(Calendar.MINUTE))
    }

    @Test
    fun fixedTimeDelay_overridesPrayerStartTime() {
        val configs = configsWith(Prayer.FAJR, delayMode = DelayMode.FIXED_TIME, delayFixedHour = 5, delayFixedMinute = 0)
        val now = makeNow(4, 50) // Fajr at 04:35, but silence starts at 05:00

        val result = SilenceAlarmComputer.compute(now, typicalDay, configs)
        assertFalse("4:50 should NOT be in window (fixed delay at 05:00)", result.currentlyInSilenceWindow)

        val fajrSilence = result.alarms.first { it.prayer == Prayer.FAJR && it.action == AlarmAction.SILENCE }
        val silenceCal = Calendar.getInstance().apply { timeInMillis = fajrSilence.triggerAtMillis }
        assertEquals(5, silenceCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, silenceCal.get(Calendar.MINUTE))
    }

    @Test
    fun fixedTimeUnsilence_overridesDuration() {
        val configs = configsWith(Prayer.FAJR, mode = SilenceMode.FIXED_TIME, fixedHour = 6, fixedMinute = 30)
        val now = makeNow(5, 0) // Inside window: 04:35 → 06:30

        val result = SilenceAlarmComputer.compute(now, typicalDay, configs)
        assertTrue("5:00 inside window with fixed end 06:30", result.currentlyInSilenceWindow)

        val fajrUnsilence = result.alarms.first { it.prayer == Prayer.FAJR && it.action == AlarmAction.UNSILENCE }
        val unsilenceCal = Calendar.getInstance().apply { timeInMillis = fajrUnsilence.triggerAtMillis }
        assertEquals(6, unsilenceCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, unsilenceCal.get(Calendar.MINUTE))
    }

    // ==================== Tomorrow's Fajr gating ====================

    @Test
    fun duringFajrWindow_tomorrowFajrNotScheduled() {
        val configs = configsWith(Prayer.FAJR, afterMinutes = 45)
        val now = makeNow(5, 0) // Inside Fajr window (04:35–05:20)

        val result = SilenceAlarmComputer.compute(now, typicalDay, configs, tomorrowDay)

        assertFalse("Tomorrow Fajr must NOT be scheduled during Fajr window",
            result.tomorrowFajrScheduled)
    }

    @Test
    fun afterFajrButBeforeIsha_tomorrowFajrNotScheduled() {
        val now = makeNow(14, 0)
        val result = SilenceAlarmComputer.compute(now, typicalDay, defaultConfigs, tomorrowDay)

        assertFalse("Tomorrow Fajr must NOT be scheduled before Isha unsilence",
            result.tomorrowFajrScheduled)
    }

    @Test
    fun duringIshaWindow_tomorrowFajrNotScheduled() {
        val now = makeNow(20, 50) // Inside Isha window (20:45–21:15)
        val result = SilenceAlarmComputer.compute(now, typicalDay, defaultConfigs, tomorrowDay)

        assertFalse("Tomorrow Fajr must NOT be scheduled during Isha window",
            result.tomorrowFajrScheduled)
    }

    @Test
    fun afterIshaUnsilence_tomorrowFajrScheduled() {
        val now = makeNow(21, 30) // After Isha unsilence (20:45 + 30 = 21:15)
        val result = SilenceAlarmComputer.compute(now, typicalDay, defaultConfigs, tomorrowDay)

        assertTrue("Tomorrow Fajr should be scheduled after Isha unsilence",
            result.tomorrowFajrScheduled)

        val tomorrowFajrSilence = result.alarms.first {
            it.prayer == Prayer.FAJR && it.action == AlarmAction.SILENCE && it.triggerAtMillis > now.timeInMillis + 3600_000
        }
        val silenceCal = Calendar.getInstance().apply { timeInMillis = tomorrowFajrSilence.triggerAtMillis }
        assertEquals(4, silenceCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(34, silenceCal.get(Calendar.MINUTE)) // tomorrow's Fajr
    }

    @Test
    fun afterIshaUnsilence_tomorrowFajrHasSilenceAndUnsilence() {
        val now = makeNow(22, 0)
        val result = SilenceAlarmComputer.compute(now, typicalDay, defaultConfigs, tomorrowDay)

        assertTrue(result.tomorrowFajrScheduled)

        // Tomorrow's alarms should be > 6 hours in the future (tomorrow ~04:34)
        val futureAlarms = result.alarms.filter {
            it.prayer == Prayer.FAJR && it.action != AlarmAction.MIDNIGHT_RESCHEDULE &&
                it.triggerAtMillis > now.timeInMillis + 3600_000
        }
        assertEquals("Tomorrow's Fajr should have SILENCE and UNSILENCE", 2, futureAlarms.size)
        assertTrue(futureAlarms.any { it.action == AlarmAction.SILENCE })
        assertTrue(futureAlarms.any { it.action == AlarmAction.UNSILENCE })
    }

    @Test
    fun noTomorrowData_tomorrowFajrNotScheduled() {
        val now = makeNow(22, 0)
        val result = SilenceAlarmComputer.compute(now, typicalDay, defaultConfigs, null)

        assertFalse(result.tomorrowFajrScheduled)
    }

    @Test
    fun ishaWithCustomDuration_gatesCorrectly() {
        // Isha at 20:45 with 60-min duration → unsilence at 21:45
        val configs = configsWith(Prayer.ISHA, afterMinutes = 60)
        val now = makeNow(21, 30) // Before 21:45

        val result = SilenceAlarmComputer.compute(now, typicalDay, configs, tomorrowDay)
        // 21:30 is inside Isha window (20:45–21:45)
        assertTrue(result.currentlyInSilenceWindow)
        assertFalse("Tomorrow Fajr should NOT be scheduled during extended Isha window",
            result.tomorrowFajrScheduled)
    }

    @Test
    fun ishaWithCustomDuration_afterExtendedWindow_gatesCorrectly() {
        val configs = configsWith(Prayer.ISHA, afterMinutes = 60)
        val now = makeNow(21, 50) // After 21:45

        val result = SilenceAlarmComputer.compute(now, typicalDay, configs, tomorrowDay)
        assertTrue("Tomorrow Fajr should be scheduled after extended Isha window",
            result.tomorrowFajrScheduled)
    }

    // ==================== Midnight reschedule ====================

    @Test
    fun alwaysIncludesMidnightReschedule() {
        val now = makeNow(12, 0)
        val result = SilenceAlarmComputer.compute(now, typicalDay, defaultConfigs)

        val midnight = result.alarms.filter { it.action == AlarmAction.MIDNIGHT_RESCHEDULE }
        assertEquals("Exactly one midnight reschedule", 1, midnight.size)

        val midnightCal = Calendar.getInstance().apply { timeInMillis = midnight[0].triggerAtMillis }
        assertEquals(0, midnightCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(1, midnightCal.get(Calendar.MINUTE))
    }

    // ==================== Regression: the exact bug that prompted this ====================

    /**
     * THE BUG: Fajr at 04:35, duration 45 min → unsilence at 05:20.
     * User opens app at 05:00 (or SilenceVerifyWorker fires).
     * scheduleAll() scheduled today's Fajr UNSILENCE at 05:20...
     * but then also scheduled TOMORROW's Fajr, which overwrote the UNSILENCE
     * PendingIntent (same request code) with tomorrow's time.
     * Result: DND stays on forever.
     *
     * Fix: gate tomorrow's Fajr on Isha unsilence, not Fajr start.
     */
    @Test
    fun regression_fajrUnsilenceSurvivesReschedule() {
        val configs = configsWith(Prayer.FAJR, afterMinutes = 45)
        val now = makeNow(5, 0) // 05:00, inside Fajr window (04:35–05:20)

        val result = SilenceAlarmComputer.compute(now, typicalDay, configs, tomorrowDay)

        // Must be inside Fajr window
        assertTrue(result.currentlyInSilenceWindow)

        // Must NOT have tomorrow's Fajr
        assertFalse(result.tomorrowFajrScheduled)

        // Must have today's Fajr UNSILENCE at 05:20
        val fajrUnsilences = result.alarms.filter {
            it.prayer == Prayer.FAJR && it.action == AlarmAction.UNSILENCE
        }
        assertEquals("Should have exactly 1 Fajr UNSILENCE (today's)", 1, fajrUnsilences.size)

        val unsilenceCal = Calendar.getInstance().apply { timeInMillis = fajrUnsilences[0].triggerAtMillis }
        assertEquals(5, unsilenceCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(20, unsilenceCal.get(Calendar.MINUTE))
    }

    // ==================== Per-prayer config independence ====================

    @Test
    fun differentConfigsPerPrayer_areIndependent() {
        val configs = mapOf(
            Prayer.FAJR to PrayerSilenceConfig(afterMinutes = 45),
            Prayer.DHUHR to PrayerSilenceConfig(mode = SilenceMode.FIXED_TIME, fixedHour = 13, fixedMinute = 30),
            Prayer.ASR to PrayerSilenceConfig(delayMode = DelayMode.FIXED_TIME, delayFixedHour = 16, delayFixedMinute = 30),
            Prayer.MAGHRIB to PrayerSilenceConfig(delayMinutes = 5, afterMinutes = 20),
            Prayer.ISHA to PrayerSilenceConfig(afterMinutes = 60)
        )
        val now = makeNow(3, 0) // Before all prayers

        val result = SilenceAlarmComputer.compute(now, typicalDay, configs)
        val alarms = result.alarms.filter { it.action != AlarmAction.MIDNIGHT_RESCHEDULE }

        // Verify Fajr silence at 04:35, unsilence at 05:20
        val fajrSilence = alarms.first { it.prayer == Prayer.FAJR && it.action == AlarmAction.SILENCE }
        val fajrCal = Calendar.getInstance().apply { timeInMillis = fajrSilence.triggerAtMillis }
        assertEquals(4, fajrCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(35, fajrCal.get(Calendar.MINUTE))

        // Verify Dhuhr unsilence at 13:30 (fixed)
        val dhuhrUnsilence = alarms.first { it.prayer == Prayer.DHUHR && it.action == AlarmAction.UNSILENCE }
        val dhuhrCal = Calendar.getInstance().apply { timeInMillis = dhuhrUnsilence.triggerAtMillis }
        assertEquals(13, dhuhrCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, dhuhrCal.get(Calendar.MINUTE))

        // Verify Asr silence at 16:30 (fixed delay)
        val asrSilence = alarms.first { it.prayer == Prayer.ASR && it.action == AlarmAction.SILENCE }
        val asrCal = Calendar.getInstance().apply { timeInMillis = asrSilence.triggerAtMillis }
        assertEquals(16, asrCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, asrCal.get(Calendar.MINUTE))

        // Verify Maghrib silence at 19:27 (19:22 + 5 delay)
        val maghribSilence = alarms.first { it.prayer == Prayer.MAGHRIB && it.action == AlarmAction.SILENCE }
        val maghribCal = Calendar.getInstance().apply { timeInMillis = maghribSilence.triggerAtMillis }
        assertEquals(19, maghribCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(27, maghribCal.get(Calendar.MINUTE))
    }

    // ==================== Full day lifecycle ====================

    @Test
    fun fullDayLifecycle_eachTimeSliceHasCorrectAlarms() {
        val configs = configsWith(Prayer.FAJR, afterMinutes = 45)

        // 03:00 — before any prayer
        var result = SilenceAlarmComputer.compute(makeNow(3, 0), typicalDay, configs, tomorrowDay)
        assertFalse(result.currentlyInSilenceWindow)
        assertFalse(result.tomorrowFajrScheduled)

        // 04:50 — inside Fajr window
        result = SilenceAlarmComputer.compute(makeNow(4, 50), typicalDay, configs, tomorrowDay)
        assertTrue(result.currentlyInSilenceWindow)
        assertFalse(result.tomorrowFajrScheduled)

        // 05:30 — after Fajr, before Dhuhr
        result = SilenceAlarmComputer.compute(makeNow(5, 30), typicalDay, configs, tomorrowDay)
        assertFalse(result.currentlyInSilenceWindow)
        assertFalse(result.tomorrowFajrScheduled)

        // 12:45 — inside Dhuhr window (12:38 + 30 = 13:08)
        result = SilenceAlarmComputer.compute(makeNow(12, 45), typicalDay, configs, tomorrowDay)
        assertTrue(result.currentlyInSilenceWindow)
        assertFalse(result.tomorrowFajrScheduled)

        // 21:00 — inside Isha window (20:45 + 30 = 21:15)
        result = SilenceAlarmComputer.compute(makeNow(21, 0), typicalDay, configs, tomorrowDay)
        assertTrue(result.currentlyInSilenceWindow)
        assertFalse(result.tomorrowFajrScheduled)

        // 21:20 — after Isha, tomorrow's Fajr should be scheduled
        result = SilenceAlarmComputer.compute(makeNow(21, 20), typicalDay, configs, tomorrowDay)
        assertFalse(result.currentlyInSilenceWindow)
        assertTrue(result.tomorrowFajrScheduled)

        // 23:30 — late night, tomorrow's Fajr still scheduled
        result = SilenceAlarmComputer.compute(makeNow(23, 30), typicalDay, configs, tomorrowDay)
        assertFalse(result.currentlyInSilenceWindow)
        assertTrue(result.tomorrowFajrScheduled)
    }
}
