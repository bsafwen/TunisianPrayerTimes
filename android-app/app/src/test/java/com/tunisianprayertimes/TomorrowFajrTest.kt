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
 * so that it doesn't depend solely on the midnight reschedule surviving OEM
 * battery optimization.
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

    @Test
    fun scheduleAll_includesAlarmForTomorrowFajr() {
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)

        val alarms = shadowAlarmManager.scheduledAlarms
        assertTrue("Should have scheduled alarms", alarms.isNotEmpty())

        // Tomorrow's Fajr should be among them.
        // Load tomorrow's actual Fajr time to compute expected trigger.
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tYear = tomorrow.get(Calendar.YEAR)
        val tMonth = tomorrow.get(Calendar.MONTH) + 1
        val tDay = tomorrow.get(Calendar.DAY_OF_MONTH)

        val tomorrowTimes = PrayerTimesRepository.loadDayPrayerTimes(context, 615, tYear, tMonth, tDay)
        assertNotNull("Should have prayer data for tomorrow", tomorrowTimes)

        val fajr = tomorrowTimes!!.fajr
        val config = PrefsManager.getConfig(context, Prayer.FAJR)

        // Expected silence time: tomorrow at fajr.hour:fajr.minute + delayMinutes
        val expectedSilence = Calendar.getInstance().apply {
            set(Calendar.YEAR, tYear)
            set(Calendar.MONTH, tomorrow.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, tDay)
            set(Calendar.HOUR_OF_DAY, fajr.hour)
            set(Calendar.MINUTE, fajr.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, config.delayMinutes)
        }

        val alarmTriggerTimes = alarms.map { it.triggerAtTime }
        assertTrue(
            "Should have an alarm matching tomorrow's Fajr silence time (${expectedSilence.timeInMillis})",
            alarmTriggerTimes.contains(expectedSilence.timeInMillis)
        )
    }

    @Test
    fun scheduleAll_tomorrowFajrUsesConfiguredDelay() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.MINUTES)
        PrefsManager.setDelayMinutes(context, Prayer.FAJR, 10)
        SilenceScheduler.scheduleAll(context)

        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, 615,
            tomorrow.get(Calendar.YEAR),
            tomorrow.get(Calendar.MONTH) + 1,
            tomorrow.get(Calendar.DAY_OF_MONTH)
        )!!

        val fajr = tomorrowTimes.fajr
        val expectedSilence = Calendar.getInstance().apply {
            set(Calendar.YEAR, tomorrow.get(Calendar.YEAR))
            set(Calendar.MONTH, tomorrow.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, tomorrow.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, fajr.hour)
            set(Calendar.MINUTE, fajr.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, 10) // 10 min delay
        }

        val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
        assertTrue(
            "Tomorrow's Fajr alarm should include 10-minute delay",
            alarmTriggerTimes.contains(expectedSilence.timeInMillis)
        )
    }

    @Test
    fun scheduleAll_tomorrowFajrUsesFixedTimeDelay() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.FIXED_TIME)
        PrefsManager.setDelayFixedTime(context, Prayer.FAJR, 5, 30)
        SilenceScheduler.scheduleAll(context)

        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val expectedSilence = Calendar.getInstance().apply {
            set(Calendar.YEAR, tomorrow.get(Calendar.YEAR))
            set(Calendar.MONTH, tomorrow.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, tomorrow.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 5)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
        assertTrue(
            "Tomorrow's Fajr alarm should use fixed delay time 05:30",
            alarmTriggerTimes.contains(expectedSilence.timeInMillis)
        )
    }

    @Test
    fun scheduleAll_tomorrowFajrUsesFixedEndTime() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.FIXED_TIME)
        PrefsManager.setFixedTime(context, Prayer.FAJR, 7, 0)
        SilenceScheduler.scheduleAll(context)

        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val expectedUnsilence = Calendar.getInstance().apply {
            set(Calendar.YEAR, tomorrow.get(Calendar.YEAR))
            set(Calendar.MONTH, tomorrow.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, tomorrow.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
        assertTrue(
            "Tomorrow's Fajr unsilence should use fixed end time 07:00",
            alarmTriggerTimes.contains(expectedUnsilence.timeInMillis)
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

        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, 615,
            tomorrow.get(Calendar.YEAR),
            tomorrow.get(Calendar.MONTH) + 1,
            tomorrow.get(Calendar.DAY_OF_MONTH)
        )!!

        val fajr = tomorrowTimes.fajr
        val expectedUnsilence = Calendar.getInstance().apply {
            set(Calendar.YEAR, tomorrow.get(Calendar.YEAR))
            set(Calendar.MONTH, tomorrow.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, tomorrow.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, fajr.hour)
            set(Calendar.MINUTE, fajr.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, 45) // duration
        }

        val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
        assertTrue(
            "Tomorrow's Fajr unsilence should be fajr + 45 minutes",
            alarmTriggerTimes.contains(expectedUnsilence.timeInMillis)
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
        PrefsManager.setDelegationId(context, 386) // Different delegation
        SilenceScheduler.scheduleAll(context)

        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, 386,
            tomorrow.get(Calendar.YEAR),
            tomorrow.get(Calendar.MONTH) + 1,
            tomorrow.get(Calendar.DAY_OF_MONTH)
        )

        if (tomorrowTimes != null) {
            val fajr = tomorrowTimes.fajr
            val config = PrefsManager.getConfig(context, Prayer.FAJR)
            val expectedSilence = Calendar.getInstance().apply {
                set(Calendar.YEAR, tomorrow.get(Calendar.YEAR))
                set(Calendar.MONTH, tomorrow.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, tomorrow.get(Calendar.DAY_OF_MONTH))
                set(Calendar.HOUR_OF_DAY, fajr.hour)
                set(Calendar.MINUTE, fajr.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, config.delayMinutes)
            }

            val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
            assertTrue(
                "Should have alarm for delegation 386 tomorrow's Fajr",
                alarmTriggerTimes.contains(expectedSilence.timeInMillis)
            )
        }
    }

    @Test
    fun scheduleAll_missingTomorrowData_doesNotCrash() {
        // Use a delegation that might not have tomorrow's data
        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelegationId(context, 99999) // Non-existent delegation

        // Should not throw — gracefully skips tomorrow's scheduling
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
}
