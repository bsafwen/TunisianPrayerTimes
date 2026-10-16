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
import java.util.concurrent.TimeUnit

/**
 * Unit tests verifying that scheduleAll() arms tomorrow's Fajr a day ahead (plan 6.9), so it does not depend
 * on the midnight reschedule surviving OEM battery optimization. Each prayer day has its own request codes
 * (four day slots), so tomorrow's alarms never overwrite today's.
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

    private fun calendarAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun beforeIshaNow(): Calendar = calendarAt(2026, Calendar.MAY, 21, 12, 0)

    private fun afterIshaNow(): Calendar = calendarAt(2026, Calendar.MAY, 21, 23, 30)

    private fun duringFajrSilenceWindowNow(): Calendar {
        val now = calendarAt(2026, Calendar.MAY, 21, 0, 0)
        val todayTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context,
            PrefsManager.getDelegationId(context),
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH),
        ) ?: error("Missing prayer times for controlled Fajr window test date")

        return (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, todayTimes.fajr.hour)
            set(Calendar.MINUTE, todayTimes.fajr.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, 15)
        }
    }

    private fun scheduledTriggerTimes(): List<Long> = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }

    /** Computes expected tomorrow Fajr silence trigger time. */
    private fun expectedTomorrowFajrSilenceMillis(now: Calendar): Long {
        val config = PrefsManager.getConfig(context, Prayer.FAJR)
        val tomorrow = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, PrefsManager.getDelegationId(context),
            tomorrow.get(Calendar.YEAR), tomorrow.get(Calendar.MONTH) + 1, tomorrow.get(Calendar.DAY_OF_MONTH)
        ) ?: error("Missing tomorrow prayer times for controlled test date")
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
    private fun expectedTomorrowFajrUnsilenceMillis(now: Calendar): Long {
        val config = PrefsManager.getConfig(context, Prayer.FAJR)
        val silenceMillis = expectedTomorrowFajrSilenceMillis(now)
        return if (config.mode == SilenceMode.FIXED_TIME && config.fixedHour >= 0 && config.fixedMinute >= 0) {
            val tomorrow = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
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
    fun scheduleAll_whenIshaPast_includesAlarmForTomorrowFajr() {
        PrefsManager.setEnabled(context, true)
        val now = afterIshaNow()
        SilenceScheduler.scheduleAllInternal(context, now)

        assertTrue(
            "When today's Isha is past, tomorrow's Fajr alarm should be scheduled",
            scheduledTriggerTimes().contains(expectedTomorrowFajrSilenceMillis(now))
        )
    }

    @Test
    fun scheduleAll_beforeIsha_armsTomorrowFajrBesideTodaysWindows() {
        PrefsManager.setEnabled(context, true)
        val now = beforeIshaNow()
        SilenceScheduler.scheduleAllInternal(context, now)

        assertTrue(
            "Tomorrow's Fajr is armed a day ahead, under its own day slot",
            scheduledTriggerTimes().contains(expectedTomorrowFajrSilenceMillis(now))
        )
        assertTrue("Today's Isha is still armed", scheduledTriggerTimes().any { it > now.timeInMillis && it < expectedTomorrowFajrSilenceMillis(now) })
    }

    @Test
    fun scheduleAll_armsTheMidnightRescheduleInThePlacesZone() {
        PrefsManager.setEnabled(context, true)
        val now = beforeIshaNow()
        SilenceScheduler.scheduleAllInternal(context, now)

        val midnight = java.time.ZonedDateTime.of(2026, 5, 22, 0, 1, 0, 0, java.time.ZoneId.of("Africa/Tunis")).toInstant().toEpochMilli()
        assertTrue("00:01 of D+1 in the place's zone (6.9)", scheduledTriggerTimes().contains(midnight))
    }

    @Test
    fun scheduleAll_whenCustomWindowEndsAfterIsha_keepsItsEndBesideTomorrow() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.FIXED_TIME)
        PrefsManager.setFixedTime(context, Prayer.FAJR, 23, 45)
        val now = afterIshaNow()
        SilenceScheduler.scheduleAllInternal(context, now)

        val todaysEnd = (now.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 45) }.timeInMillis
        assertTrue("Today's extended Fajr keeps its 23:45 end", scheduledTriggerTimes().contains(todaysEnd))
        assertTrue("Tomorrow's Fajr is armed beside it", scheduledTriggerTimes().contains(expectedTomorrowFajrSilenceMillis(now)))
    }

    @Test
    fun scheduleAll_tomorrowFajrUsesConfiguredDelay() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.MINUTES)
        PrefsManager.setDelayMinutes(context, Prayer.FAJR, 10)
        val now = afterIshaNow()
        SilenceScheduler.scheduleAllInternal(context, now)

        assertTrue(
            "Tomorrow's Fajr alarm should include 10-minute delay",
            scheduledTriggerTimes().contains(expectedTomorrowFajrSilenceMillis(now))
        )
    }

    @Test
    fun scheduleAll_tomorrowFajrUsesFixedTimeDelay() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.FIXED_TIME)
        PrefsManager.setDelayFixedTime(context, Prayer.FAJR, 5, 30)
        val now = afterIshaNow()
        SilenceScheduler.scheduleAllInternal(context, now)

        assertTrue(
            "Tomorrow's Fajr alarm should use fixed delay time 05:30",
            scheduledTriggerTimes().contains(expectedTomorrowFajrSilenceMillis(now))
        )
    }

    @Test
    fun scheduleAll_tomorrowFajrUsesFixedEndTime() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.FIXED_TIME)
        PrefsManager.setFixedTime(context, Prayer.FAJR, 7, 0)
        val now = afterIshaNow()
        SilenceScheduler.scheduleAllInternal(context, now)

        assertTrue(
            "Tomorrow's Fajr unsilence should use fixed end time 07:00",
            scheduledTriggerTimes().contains(expectedTomorrowFajrUnsilenceMillis(now))
        )
    }

    @Test
    fun scheduleAll_tomorrowFajrUsesDurationMode() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 45)
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.MINUTES)
        PrefsManager.setDelayMinutes(context, Prayer.FAJR, 0)
        val now = afterIshaNow()
        SilenceScheduler.scheduleAllInternal(context, now)

        assertTrue(
            "Tomorrow's Fajr unsilence should be fajr + 45 minutes",
            scheduledTriggerTimes().contains(expectedTomorrowFajrUnsilenceMillis(now))
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
        val now = afterIshaNow()
        SilenceScheduler.scheduleAllInternal(context, now)

        assertTrue(
            "Should have alarm for delegation 386 tomorrow's Fajr",
            scheduledTriggerTimes().contains(expectedTomorrowFajrSilenceMillis(now))
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

    /**
     * Regression test: when scheduleAll is called during the Fajr silence window (e.g. by the
     * SilenceVerifyWorker), today's Fajr end must survive; tomorrow's Fajr has its own day slot (6.9).
     */
    @Test
    fun scheduleAll_duringFajrSilenceWindow_keepsTodaysEndAndArmsTomorrow() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 45)
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.MINUTES)
        PrefsManager.setDelayMinutes(context, Prayer.FAJR, 0)

        val fakeNow = duringFajrSilenceWindowNow()

        SilenceScheduler.scheduleAllInternal(context, fakeNow)

        val todaysEnd = (fakeNow.clone() as Calendar).apply { add(Calendar.MINUTE, 30) }.timeInMillis
        assertTrue("Today's Fajr end (adhan + 45) is kept", scheduledTriggerTimes().contains(todaysEnd))
        assertTrue("Tomorrow's Fajr is armed too", scheduledTriggerTimes().contains(expectedTomorrowFajrSilenceMillis(fakeNow)))
    }

    /**
     * Verify that after Isha has passed, tomorrow's Fajr IS pre-scheduled.
     * Uses scheduleAllInternal() with a controlled clock (22:30, well after Isha).
     */
    @Test
    fun scheduleAll_afterIsha_schedulesTomorrowFajr() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 45)
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.MINUTES)
        PrefsManager.setDelayMinutes(context, Prayer.FAJR, 0)

        val fakeNow = afterIshaNow()

        SilenceScheduler.scheduleAllInternal(context, fakeNow)

        assertTrue(
            "After Isha, tomorrow's Fajr should be pre-scheduled",
            scheduledTriggerTimes().contains(expectedTomorrowFajrSilenceMillis(fakeNow))
        )
    }
}
