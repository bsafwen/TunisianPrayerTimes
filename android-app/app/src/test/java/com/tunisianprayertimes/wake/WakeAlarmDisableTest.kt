package com.tunisianprayertimes.wake

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrayerWakeSubAlarm
import com.tunisianprayertimes.WakeMainAlarmConfig
import com.tunisianprayertimes.WakeMainAlarmMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WakeAlarmDisableTest {
    private lateinit var context: Context
    private lateinit var repository: PrayerWakeRepository
    private lateinit var alarms: ShadowAlarmManager
    private lateinit var now: Calendar

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        repository = PrayerWakeRepository(context)
        repository.clearAllWakeConfigs()
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("wake_alarm_scheduler", Context.MODE_PRIVATE)
            .edit().clear().commit()
        PrefsManager.setEnabled(context, false)
        alarms = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        now = Calendar.getInstance()
    }

    @Test
    fun disablingParentCancelsMainAndExtrasAndReenablingRestoresThem() {
        val parent = alarm("parent")
        schedule(parent)
        val expectedIds = setOf(
            wakeMainEventId(parent.id),
            wakeSubAlarmEventId(parent.id, "before"),
            wakeSubAlarmEventId(parent.id, "after"),
        )
        assertEquals(expectedIds, scheduledWakeIntents().keys)

        schedule(parent.copy(enabled = false))
        assertTrue("Disabling the last alarm must cancel its extras and repair alarm", alarms.scheduledAlarms.isEmpty())

        schedule(parent)
        assertEquals(expectedIds, scheduledWakeIntents().keys)
    }

    @Test
    fun disablingOneParentPreservesOtherParentsExtras() {
        val parent = alarm("parent")
        val other = alarm("other")
        schedule(parent, other)

        schedule(parent.copy(enabled = false), other)

        assertEquals(
            setOf(wakeMainEventId(other.id), wakeSubAlarmEventId(other.id, "before"), wakeSubAlarmEventId(other.id, "after")),
            scheduledWakeIntents().keys,
        )
    }

    @Test
    fun extraAlreadyDispatchedDoesNotRingAfterParentIsDisabled() = runBlocking {
        val parent = alarm("parent")
        repository.saveWakeConfig(parent)
        schedule(parent)
        // Android may have already handed this broadcast off when the user disables the alarm.
        val pendingExtra = Intent(scheduledWakeIntents().getValue(wakeSubAlarmEventId(parent.id, "after")))

        repository.saveWakeConfig(parent.copy(enabled = false))
        schedule(parent.copy(enabled = false))
        assertTrue(alarms.scheduledAlarms.isEmpty())
        context.deliverWakeBroadcast(WakeAlarmReceiver(), pendingExtra)

        assertNull("An extra must not ring after its parent is disabled", startedService())
    }

    @Test
    fun enabledParentsExtraStillRingsAfterMainTriggerHasPassed() = runBlocking {
        val parent = alarm("parent", mainTriggerAtMillis = now.timeInMillis - 60_000L)
        repository.saveWakeConfig(parent)
        schedule(parent)

        context.deliverWakeBroadcast(
            WakeAlarmReceiver(),
            scheduledWakeIntents().getValue(wakeSubAlarmEventId(parent.id, "after")),
        )

        assertEquals(WakePlaybackService::class.java.name, startedService()?.component?.className)
    }

    private fun alarm(id: String, mainTriggerAtMillis: Long = now.timeInMillis + 10 * 60_000L) =
        PrayerWakeConfig(
            id = id,
            prayer = Prayer.FAJR,
            enabled = true,
            mainAlarm = WakeMainAlarmConfig(
                mode = WakeMainAlarmMode.FROM_NOW,
                oneOffTriggerAtMillis = mainTriggerAtMillis,
            ),
            subAlarms = listOf(
                PrayerWakeSubAlarm("before", 2, OffsetDirection.BEFORE),
                PrayerWakeSubAlarm("after", 2, OffsetDirection.AFTER),
            ),
        )

    private fun schedule(vararg configs: PrayerWakeConfig) =
        WakeAlarmScheduler.scheduleAllInternal(context, now, configs.toList())

    private fun scheduledWakeIntents(): Map<String, Intent> = alarms.scheduledAlarms
        .map { Shadows.shadowOf(it.operation).savedIntent }
        .filter { it.component?.className == WakeAlarmReceiver::class.java.name }
        .associateBy { requireNotNull(it.action) }

    private fun startedService(): Intent? =
        Shadows.shadowOf(context.applicationContext as Application).nextStartedService
}
