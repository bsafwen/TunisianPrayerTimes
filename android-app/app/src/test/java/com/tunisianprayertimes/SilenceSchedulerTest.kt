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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SilenceSchedulerApi26Test {

    private lateinit var context: Context
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = Shadows.shadowOf(alarmManager)

        // Clear prefs
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun scheduleAll_whenEnabled_schedulesAlarms() {
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)

        // Should have scheduled alarms (silence + unsilence for future prayers + midnight reschedule)
        val scheduledAlarms = shadowAlarmManager.scheduledAlarms
        assertTrue("Should schedule at least one alarm", scheduledAlarms.isNotEmpty())
    }

    @Test
    fun scheduleAll_whenDisabled_cancelsAlarms() {
        // First schedule
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)

        // Then disable
        PrefsManager.setEnabled(context, false)
        SilenceScheduler.scheduleAll(context)

        // All alarms should be cancelled
        val scheduledAlarms = shadowAlarmManager.scheduledAlarms
        assertEquals("No alarms when disabled", 0, scheduledAlarms.size)
    }

    @Test
    fun cancelAll_removesAllAlarms() {
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)
        SilenceScheduler.cancelAll(context)

        val scheduledAlarms = shadowAlarmManager.scheduledAlarms
        assertEquals("All alarms cancelled", 0, scheduledAlarms.size)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SilenceSchedulerApi30Test {

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
    fun scheduleAll_api30_noExactAlarmPermissionNeeded() {
        // API 30 doesn't require SCHEDULE_EXACT_ALARM
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)
        assertTrue("Should schedule alarms on API 30", shadowAlarmManager.scheduledAlarms.isNotEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SilenceSchedulerApi33Test {

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
    fun scheduleAll_api33_withoutPermission_schedulesNoAlarms() {
        // On API 33 (S+), canScheduleExactAlarms() defaults to false
        // The scheduler should gracefully skip scheduling
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)
        assertEquals("Should skip alarms when exact alarm permission not granted", 0,
            shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun cancelAll_api33_doesNotCrash() {
        SilenceScheduler.cancelAll(context)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SilenceSchedulerApi34Test {

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
    fun scheduleAll_api34_withoutPermission_schedulesNoAlarms() {
        // On API 34, setAlarmClock also requires SCHEDULE_EXACT_ALARM
        // Without permission, scheduler should gracefully skip
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)
        assertEquals("Should skip alarms when exact alarm permission not granted", 0,
            shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun cancelAll_api34_doesNotCrash() {
        SilenceScheduler.cancelAll(context)
    }
}
