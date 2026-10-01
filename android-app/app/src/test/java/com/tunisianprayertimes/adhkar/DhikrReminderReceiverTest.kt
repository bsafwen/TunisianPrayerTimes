package com.tunisianprayertimes.adhkar

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DhikrReminderReceiverTest {
    private lateinit var app: Application

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
    }

    @Test fun failedDeliveryQueuesOneDurableRepair() {
        DhikrReminderReceiver.enqueueFailureRepair(app)
        val first = WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(DhikrReminderReceiver.FAILURE_REPAIR_WORK_NAME)
            .get(3, TimeUnit.SECONDS)

        assertEquals(1, first.size)
        assertEquals(WorkInfo.State.ENQUEUED, first.single().state)
        assertTrue(first.single().tags.contains(DhikrReminderRepairWorker::class.java.name))

        DhikrReminderReceiver.enqueueFailureRepair(app)
        val repeated = WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(DhikrReminderReceiver.FAILURE_REPAIR_WORK_NAME)
            .get(3, TimeUnit.SECONDS)
        assertEquals(listOf(first.single().id), repeated.map { it.id })
    }
}
