package com.tunisianprayertimes

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.Calendar

/**
 * Unit tests verifying that scheduleAll() pre-schedules tomorrow's Fajr alarm
 * when today's Fajr has already passed, so that it doesn't depend solely on the
 * midnight reschedule surviving OEM battery optimization.
 *
 * Note: tomorrow's Fajr is only pre-scheduled when today's Fajr silence time
 * has already passed. This prevents overwriting a still-future today's Fajr alarm
 * (which shares the same PendingIntent request code via FLAG_UPDATE_CURRENT).
 * Tests account for this by checking the condition before asserting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@Suppress("DEPRECATION")
class TomorrowFajrTest {

    private lateinit var context: Context
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = Shadows.shadowOf(alarmManager)
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /** Returns true if today's Fajr silence time has already passed. */
    private fun isTodayFajrPast(): Boolean {
        val now = Calendar.getInstance()
        val todayTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, PrefsManager.getDelegationId(context),
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)
        ) ?: return true // no data = treat as past
        val config = PrefsManager.getConfig(context, Prayer.FAJR)
        val fajrSilence = if (config.delayMode == DelayMode.FIXED_TIME && config.delayFixedHour >= 0 && config.delayFixedMinute >= 0) {
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, config.delayFixedHour)
                set(Calendar.MINUTE, config.delayFixedMinute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
        } else {
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, todayTimes.fajr.hour)
                set(Calendar.MINUTE, todayTimes.fajr.minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, config.delayMinutes)
            }
        }
        return !now.before(fajrSilence)
    }

    /** Computes expected tomorrow Fajr silence trigger time. */
    private fun expectedTomorrowFajrSilenceMillis(): Long {
        val config = PrefsManager.getConfig(context, Prayer.FAJR)
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, PrefsManager.getDelegationId(context),
            tomorrow.get(Calendar.YEAR), tomorrow.get(Calendar.MONTH) + 1, tomorrow.get(Calendar.DAY_OF_MONTH)
        )!!
        val fajr = tomorrowTimes.fajr
        return if (config.delayMode == DelayMode.FIXED_TIME && config.delayFixedHour >= 0 && config.delayFixedMinute >= 0) {
            (tomorrow.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, config.delayFixedHour)
                set(Calendar.MINUTE, config.delayFixedMinute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } else {
            (tomorrow.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, fajr.hour)
                set(Calendar.MINUTE, fajr.minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, config.delayMinutes)
            }.timeInMillis
        }
    }

    /** Computes expected tomorrow Fajr unsilence trigger time. */
    private fun expectedTomorrowFajrUnsilenceMillis(): Long {
        val config = PrefsManager.getConfig(context, Prayer.FAJR)
        val silenceMillis = expectedTomorrowFajrSilenceMillis()
        return if (config.mode == SilenceMode.FIXED_TIME && config.fixedHour >= 0 && config.fixedMinute >= 0) {
            val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
            (tomorrow.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, config.fixedHour)
                set(Calendar.MINUTE, config.fixedMinute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } else {
            Calendar.getInstance().apply {
                timeInMillis = silenceMillis
                add(Calendar.MINUTE, config.afterMinutes)
            }.timeInMillis
        }
    }

    @Test
    fun scheduleAll_whenFajrPast_includesAlarmForTomorrowFajr() {
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)

        if (!isTodayFajrPast()) return // today's Fajr hasn't passed yet — skip

        val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
        assertTrue(
            "When today's Fajr is past, tomorrow's Fajr alarm should be scheduled",
            alarmTriggerTimes.contains(expectedTomorrowFajrSilenceMillis())
        )
    }

    @Test
    fun scheduleAll_whenFajrNotPast_doesNotOverwriteTodayFajr() {
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)

        if (isTodayFajrPast()) return // today's Fajr already past — skip

        // Today's Fajr should be scheduled, not tomorrow's
        val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
        val tomorrowSilence = expectedTomorrowFajrSilenceMillis()
        assertFalse(
            "When today's Fajr hasn't passed, tomorrow's Fajr should NOT be scheduled",
            alarmTriggerTimes.contains(tomorrowSilence)
        )
    }

    @Test
    fun scheduleAll_tomorrowFajrUsesConfiguredDelay() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.MINUTES)
        PrefsManager.setDelayMinutes(context, Prayer.FAJR, 10)
        SilenceScheduler.scheduleAll(context)

        if (!isTodayFajrPast()) return

        val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
        assertTrue(
            "Tomorrow's Fajr alarm should include 10-minute delay",
            alarmTriggerTimes.contains(expectedTomorrowFajrSilenceMillis())
        )
    }

    @Test
    fun scheduleAll_tomorrowFajrUsesFixedTimeDelay() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.FIXED_TIME)
        PrefsManager.setDelayFixedTime(context, Prayer.FAJR, 5, 30)
        SilenceScheduler.scheduleAll(context)

        if (!isTodayFajrPast()) return

        val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
        assertTrue(
            "Tomorrow's Fajr alarm should use fixed delay time 05:30",
            alarmTriggerTimes.contains(expectedTomorrowFajrSilenceMillis())
        )
    }

    @Test
    fun scheduleAll_tomorrowFajrUsesFixedEndTime() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.FIXED_TIME)
        PrefsManager.setFixedTime(context, Prayer.FAJR, 7, 0)
        SilenceScheduler.scheduleAll(context)

        if (!isTodayFajrPast()) return

        val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
        assertTrue(
            "Tomorrow's Fajr unsilence should use fixed end time 07:00",
            alarmTriggerTimes.contains(expectedTomorrowFajrUnsilenceMillis())
        )
    }

    @Test
    fun scheduleAll_tomorrowFajrUsesDurationMode() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 45)
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.MINUTES)
        PrefsManager.setDelayMinutes(context, Prayer.FAJR, 0)
        SilenceScheduler.scheduleAll(context)

        if (!isTodayFajrPast()) return

        val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
        assertTrue(
            "Tomorrow's Fajr unsilence should be fajr + 45 minutes",
            alarmTriggerTimes.contains(expectedTomorrowFajrUnsilenceMillis())
        )
    }

    @Test
    fun scheduleAll_whenDisabled_noTomorrowFajr() {
        PrefsManager.setEnabled(context, false)
        SilenceScheduler.scheduleAll(context)

        assertEquals("No alarms when disabled", 0, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun scheduleAll_withDifferentDelegation_schedulesTomorrowFajr() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelegationId(context, 386)
        SilenceScheduler.scheduleAll(context)

        if (!isTodayFajrPast()) return

        val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
        assertTrue(
            "Should have alarm for delegation 386 tomorrow's Fajr",
            alarmTriggerTimes.contains(expectedTomorrowFajrSilenceMillis())
        )
    }

    @Test
    fun scheduleAll_missingTomorrowData_doesNotCrash() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelegationId(context, 99999)
        // Should not throw
        SilenceScheduler.scheduleAll(context)
    }

    @Test
    fun cancelAll_cancelsTomorrowFajrToo() {
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)
        assertTrue("Should have alarms before cancel", shadowAlarmManager.scheduledAlarms.isNotEmpty())

        SilenceScheduler.cancelAll(context)
        assertEquals("All alarms cancelled including tomorrow's", 0, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun scheduleAll_alwaysSchedulesSomething() {
        // Regardless of time of day, scheduleAll should schedule at least the midnight
        // reschedule alarm plus some prayer alarms (today's or tomorrow's Fajr)
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)
        assertTrue(
            "Should always have at least one alarm (midnight reschedule)",
            shadowAlarmManager.scheduledAlarms.isNotEmpty()
        )
    }
}
