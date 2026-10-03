package com.tunisianprayertimes.wake

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrayerWakeSubAlarm
import com.tunisianprayertimes.WakeMainAlarmConfig
import com.tunisianprayertimes.WakeMainAlarmMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AwakeCheckDisableTest {
    private lateinit var context: Context
    private lateinit var repository: PrayerWakeRepository
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        repository = PrayerWakeRepository(context)
        repository.clearAllWakeConfigs()
        shadowAlarmManager = Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    }

    @Test
    fun disablingAlarmCancelsItsPendingAwakeCheckAndPreservesOtherAlarms() = runBlocking {
        val alarm = PrayerWakeConfig(id = "alarm-to-disable", prayer = Prayer.FAJR, enabled = true)
        val otherAlarm = PrayerWakeConfig(id = "other-alarm", prayer = Prayer.FAJR, enabled = true)
        repository.saveWakeConfig(alarm)
        repository.saveWakeConfig(otherAlarm)
        scheduleAwakeCheck(wakeMainEventId(alarm.id))
        scheduleAwakeCheck(wakeMainEventId(otherAlarm.id))
        assertEquals(2, shadowAlarmManager.scheduledAlarms.size)

        repository.saveWakeConfig(alarm.copy(enabled = false))

        assertFalse(requireNotNull(repository.getWakeAlarm(alarm.id)).enabled)
        assertEquals(setOf(wakeMainEventId(otherAlarm.id)), scheduledEventIds())
    }

    @Test
    fun disablingLegacyConfigWithoutIdCancelsResolvedAlarmsAwakeCheck() = runBlocking {
        val alarm = PrayerWakeConfig(id = "existing-alarm", prayer = Prayer.FAJR, enabled = true)
        repository.saveWakeConfig(alarm)
        scheduleAwakeCheck(wakeMainEventId(alarm.id))

        repository.saveWakeConfig(alarm.copy(id = "", enabled = false))

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun disablingAlarmAlsoCancelsFollowUpsForRemovedSubAlarms() = runBlocking {
        val alarm = PrayerWakeConfig(
            id = "alarm-with-sub",
            prayer = Prayer.FAJR,
            enabled = true,
            subAlarms = listOf(PrayerWakeSubAlarm("old-sub", 5, OffsetDirection.AFTER)),
        )
        repository.saveWakeConfig(alarm)
        scheduleAwakeCheck(wakeSubAlarmEventId(alarm.id, "old-sub"))

        repository.saveWakeConfig(alarm.copy(enabled = false, subAlarms = emptyList()))

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun editingEnabledAlarmPreservesItsPendingAwakeCheck() = runBlocking {
        val alarm = PrayerWakeConfig(id = "enabled-alarm", prayer = Prayer.FAJR, enabled = true)
        repository.saveWakeConfig(alarm)
        scheduleAwakeCheck(wakeMainEventId(alarm.id))

        repository.saveWakeConfig(alarm.copy(title = "Updated title"))

        assertEquals(setOf(wakeMainEventId(alarm.id)), scheduledEventIds())
    }

    @Test
    fun alreadyDeliveredAwakeCheckDoesNotStartAfterParentIsDisabled() = runBlocking {
        val alarm = PrayerWakeConfig(id = "disabled-parent", prayer = Prayer.FAJR, enabled = true)
        repository.saveWakeConfig(alarm)
        scheduleAwakeCheck(wakeMainEventId(alarm.id))
        val deliveredIntent = Intent(Shadows.shadowOf(shadowAlarmManager.scheduledAlarms.single().operation).savedIntent)
        repository.saveWakeConfig(alarm.copy(enabled = false))

        context.deliverWakeBroadcast(AwakeCheckReceiver(), deliveredIntent)

        assertNull(Shadows.shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun awakeCheckStillStartsAfterCompletedOneOffParentIsAutomaticallyDeleted() = runBlocking {
        val alarm = PrayerWakeConfig(
            id = "completed-one-off",
            prayer = Prayer.FAJR,
            enabled = true,
            mainAlarm = WakeMainAlarmConfig(
                mode = WakeMainAlarmMode.FROM_NOW,
                oneOffTriggerAtMillis = System.currentTimeMillis() - 60_000L,
            ),
        )
        repository.saveWakeConfig(alarm)
        scheduleAwakeCheck(wakeMainEventId(alarm.id))
        val deliveredIntent = Intent(Shadows.shadowOf(shadowAlarmManager.scheduledAlarms.single().operation).savedIntent)
        WakeAlarmScheduler.scheduleAll(context)
        assertNull(repository.getWakeAlarm(alarm.id))

        context.deliverWakeBroadcast(AwakeCheckReceiver(), deliveredIntent)

        val startedService = Shadows.shadowOf(context as Application).nextStartedService
        assertEquals(AwakeCheckService::class.java.name, startedService?.component?.className)
    }

    private fun scheduleAwakeCheck(eventId: String) {
        assertTrue(
            AwakeCheckScheduler.schedule(
                context = context,
                eventId = eventId,
                delayMinutes = 3,
                ringtonePresetName = null,
                customRingtoneUri = null,
            ),
        )
    }

    private fun scheduledEventIds(): Set<String?> = shadowAlarmManager.scheduledAlarms.mapTo(linkedSetOf()) { alarm ->
        Shadows.shadowOf(alarm.operation).savedIntent.getStringExtra(EXTRA_EVENT_ID)
    }
}
