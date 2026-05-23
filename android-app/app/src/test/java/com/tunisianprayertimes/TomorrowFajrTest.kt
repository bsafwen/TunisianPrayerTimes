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
 * Unit tests verifying that scheduleAll() pre-schedules tomorrow's Fajr alarm
 * only after today's final silence window has passed, so that it doesn't depend
 * solely on the midnight reschedule surviving OEM battery optimization.
 *
 * Tomorrow's Fajr is gated on the final window end because all of today's prayer
 * alarms share PendingIntent request codes with tomorrow's alarms. Waiting until
 * every current-day UNSILENCE is past guarantees no overwrite.
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

    private fun expectedPostFinalWindowRescheduleMillis(now: Calendar): Long {
        val todayTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context,
            PrefsManager.getDelegationId(context),
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH),
        ) ?: error("Missing today prayer times for controlled test date")
        val isFriday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        val latestWindowEnd = todayTimes.scheduledPrayers(
            isFriday,
            PrefsManager.getJomoaaTimeHour(context),
            PrefsManager.getJomoaaTimeMinute(context),
        ).maxOf { prayerTime ->
            val config = PrefsManager.getConfig(context, prayerTime.prayer)
            val silenceTime = if (config.delayMode == DelayMode.FIXED_TIME && config.delayFixedHour >= 0 && config.delayFixedMinute >= 0) {
                (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, config.delayFixedHour)
                    set(Calendar.MINUTE, config.delayFixedMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            } else {
                (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, prayerTime.hour)
                    set(Calendar.MINUTE, prayerTime.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    add(Calendar.MINUTE, config.delayMinutes)
                }
            }
            if (config.mode == SilenceMode.FIXED_TIME && config.fixedHour >= 0 && config.fixedMinute >= 0) {
                (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, config.fixedHour)
                    set(Calendar.MINUTE, config.fixedMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (before(silenceTime)) {
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                }.timeInMillis
            } else {
                (silenceTime.clone() as Calendar).apply {
                    add(Calendar.MINUTE, config.afterMinutes)
                }.timeInMillis
            }
        }

        return latestWindowEnd + TimeUnit.MINUTES.toMillis(2)
    }

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
    fun scheduleAll_whenIshaNotPast_doesNotOverwriteTodayFajr() {
        PrefsManager.setEnabled(context, true)
        val now = beforeIshaNow()
        SilenceScheduler.scheduleAllInternal(context, now)

        assertFalse(
            "When today's Isha hasn't passed, tomorrow's Fajr should NOT be scheduled",
            scheduledTriggerTimes().contains(expectedTomorrowFajrSilenceMillis(now))
        )
    }

    @Test
    fun scheduleAll_whenFinalWindowNotPast_schedulesPostFinalWindowReschedule() {
        PrefsManager.setEnabled(context, true)
        val now = beforeIshaNow()
        SilenceScheduler.scheduleAllInternal(context, now)

        assertTrue(
            "A post-final-window reschedule should be scheduled so tomorrow's alarms are set without a frequent worker",
            scheduledTriggerTimes().contains(expectedPostFinalWindowRescheduleMillis(now))
        )
    }

    @Test
    fun scheduleAll_whenCustomWindowEndsAfterIsha_waitsForThatWindowBeforeTomorrow() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.FIXED_TIME)
        PrefsManager.setFixedTime(context, Prayer.FAJR, 23, 45)
        val now = afterIshaNow()
        SilenceScheduler.scheduleAllInternal(context, now)

        assertFalse(
            "Tomorrow's Fajr should not overwrite today's extended Fajr UNSILENCE",
            scheduledTriggerTimes().contains(expectedTomorrowFajrSilenceMillis(now))
        )
        assertTrue(
            "The handoff reschedule should wait until the extended window has ended",
            scheduledTriggerTimes().contains(expectedPostFinalWindowRescheduleMillis(now))
        )
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
     * Regression test: when scheduleAll is called during the Fajr silence window
     * (e.g. by the hourly SilenceVerifyWorker), tomorrow's Fajr must NOT be
     * pre-scheduled because that would overwrite today's still-pending Fajr
     * UNSILENCE alarm (same PendingIntent request code via FLAG_UPDATE_CURRENT).
     *
     * Uses scheduleAllInternal() with a controlled clock to simulate being inside
     * the Fajr silence window (Fajr at 04:35 + 45 min duration → unsilence 05:20,
     * "now" = 05:00).
     */
    @Test
    fun scheduleAll_duringFajrSilenceWindow_doesNotScheduleTomorrowFajr() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 45)
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.MINUTES)
        PrefsManager.setDelayMinutes(context, Prayer.FAJR, 0)

        val fakeNow = duringFajrSilenceWindowNow()

        SilenceScheduler.scheduleAllInternal(context, fakeNow)

        // Fajr UNSILENCE request code = Prayer.FAJR.ordinal * 2 + 1 = 1
        // If tomorrow's Fajr was incorrectly scheduled, the alarm at request code 1
        // would point to tomorrow instead of today.
        assertFalse(
            "During Fajr silence window, tomorrow's Fajr should NOT be scheduled",
            scheduledTriggerTimes().contains(expectedTomorrowFajrSilenceMillis(fakeNow))
        )
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
