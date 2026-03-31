package com.tunisianprayertimes

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Tests for SilenceVerifyWorker using WorkManager's test infrastructure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SilenceVerifyWorkerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()

        // Initialize WorkManager for testing
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun doWork_whenEnabled_returnsSuccess() = runBlocking {
        PrefsManager.setEnabled(context, true)
        val worker = TestListenableWorkerBuilder<SilenceVerifyWorker>(context).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun doWork_whenDisabled_returnsSuccess() = runBlocking {
        PrefsManager.setEnabled(context, false)
        val worker = TestListenableWorkerBuilder<SilenceVerifyWorker>(context).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun enqueue_doesNotThrow() {
        // Should not throw even when called multiple times
        SilenceVerifyWorker.enqueue(context)
        SilenceVerifyWorker.enqueue(context)
    }

    @Test
    fun cancel_doesNotThrow() {
        SilenceVerifyWorker.cancel(context)
    }

    @Test
    fun enqueueAndCancel_cycle() {
        SilenceVerifyWorker.enqueue(context)
        SilenceVerifyWorker.cancel(context)
        SilenceVerifyWorker.enqueue(context)
    }

    @Test
    fun doWork_whenEnabled_actuallySchedulesAlarms() = runBlocking {
        PrefsManager.setEnabled(context, true)

        val worker = TestListenableWorkerBuilder<SilenceVerifyWorker>(context).build()
        worker.doWork()

        // doWork calls scheduleAll on applicationContext — get the AlarmManager from it
        val alarmManager = worker.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAM: ShadowAlarmManager = Shadows.shadowOf(alarmManager)

        assertTrue(
            "doWork with enabled=true should schedule alarms",
            shadowAM.scheduledAlarms.isNotEmpty()
        )
    }

    @Test
    fun doWork_whenDisabled_doesNotScheduleAlarms() = runBlocking {
        PrefsManager.setEnabled(context, false)

        // Clear any pre-existing alarms
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAM: ShadowAlarmManager = Shadows.shadowOf(alarmManager)
        val alarmsBefore = shadowAM.scheduledAlarms.size

        val worker = TestListenableWorkerBuilder<SilenceVerifyWorker>(context).build()
        worker.doWork()

        // No new alarms should be added
        assertEquals(
            "doWork with enabled=false should NOT add new alarms",
            alarmsBefore,
            shadowAM.scheduledAlarms.size
        )
    }
}
