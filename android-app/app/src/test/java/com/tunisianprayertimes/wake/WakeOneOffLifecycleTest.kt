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
import com.tunisianprayertimes.WakeRepeatMode
import com.tunisianprayertimes.ui.WakeAlarmFocus
import com.tunisianprayertimes.ui.WakeStopState
import com.tunisianprayertimes.ui.nextWakeAlarmTrigger
import com.tunisianprayertimes.ui.wakeAlarmDisplayState
import com.tunisianprayertimes.ui.wakeAlarmNow
import com.tunisianprayertimes.ui.wakeAlarmRingOffsets
import com.tunisianprayertimes.ui.wakeTimelineStops
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class WakeOneOffLifecycleTest {
    private lateinit var context: Context
    private lateinit var repository: PrayerWakeRepository
    private lateinit var alarms: ShadowAlarmManager
    private val payloadsToFinish = mutableListOf<WakeTriggerPayload>()
    private val queue get() = WakeAlarmQueueHolder.queue

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        repository = PrayerWakeRepository(context)
        repository.clearAllWakeConfigs()
        queue.clear()
        WakeOccurrenceSkipRegistry.clearAlarm(ALARM_ID)
        listOf(
            "prayer_silence_prefs",
            "wake_alarm_scheduler",
            "nap_prefs",
            "awake_check_occurrences",
            "awake_check_group_dismissals",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        PrefsManager.setEnabled(context, false)
        alarms = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun cleanup() = runBlocking {
        payloadsToFinish.forEach(WakeAlarmDeliveryTracker::finish)
        queue.clear()
        AwakeCheckScheduler.cancel(context, wakeMainEventId(ALARM_ID))
        repository.clearAllWakeConfigs()
    }

    @Test
    fun finalExtra_keepsOneShotThroughDeliveryPlaybackAndAwakeCheckThenExpires() = runBlocking {
        val (parent, finalIntent) = schedulePastOccurrence()
        val payload = requireNotNull(finalIntent.toWakeTriggerPayload())
        assertTrue("The last extra owns the follow-up check", payload.awakeCheckEnabled)

        context.deliverWakeBroadcast(WakeAlarmReceiver(), finalIntent)

        assertEquals(
            WakePlaybackService::class.java.name,
            Shadows.shadowOf(context.applicationContext as Application).nextStartedService?.component?.className,
        )
        assertEquals(payload, WakeAlarmDeliveryTracker.pendingForAlarm(parent.id))
        assertNotNull("Receiver rescheduling must retain the parent before playback starts", repository.getWakeAlarm(parent.id))
        val arriving = requireNotNull(running(parent))
        assertEquals(WakeAlarmScheduler.Ring(payload.triggerAtMillis, isExtra = true), arriving.dueRing)
        assertEquals(listOf(0, 5), arriving.ringOffsets)

        assertEquals(IncomingResult.BECAME_CURRENT, queue.handleIncoming(payload))
        WakeAlarmDeliveryTracker.finish(payload)
        WakeAlarmScheduler.scheduleAll(context)

        assertNotNull("The final ring still playing keeps its one-shot alarm", repository.getWakeAlarm(parent.id))
        val ringing = requireNotNull(running(parent))
        assertEquals(WakeAlarmScheduler.Ring(payload.triggerAtMillis, isExtra = true), ringing.ringing)
        assertFalse(ringing.ringWaiting)

        WakeDismissalCoordinator.recordDismissal(context, payload, "test", wakeupCheckCompleted = false)
        queue.handleDismiss(payload.eventId)
        WakeAlarmScheduler.scheduleAll(context)

        assertTrue(AwakeCheckScheduler.isPending(context, wakeMainEventId(parent.id)))
        assertNotNull("The pending awake check keeps its one-shot alarm", repository.getWakeAlarm(parent.id))
        val awaitingCheck = requireNotNull(running(parent))
        assertEquals(parent.mainAlarm.oneOffTriggerAtMillis, awaitingCheck.occurrenceAtMillis)
        assertEquals(AwakeCheckScheduler.triggerAtMillis(context, wakeMainEventId(parent.id)), awaitingCheck.awakeCheckAtMillis)
        assertEquals(listOf(0, 5), awaitingCheck.ringOffsets)

        AwakeCheckScheduler.cancel(context, wakeMainEventId(parent.id))
        WakeAlarmScheduler.scheduleAll(context)

        assertNull("The one-shot expires only once the ring and check have ended", repository.getWakeAlarm(parent.id))
    }

    @Test
    fun dismissingMain_keepsFiveMinuteExtraAsTheHeroNextStop() = runBlocking {
        val (parent, finalIntent) = schedulePastOccurrence(
            mainTriggerAtMillis = System.currentTimeMillis() - 1_000L,
            mainAlreadyDelivered = false,
        )
        val mainIntent = Intent(scheduledWakeIntents().getValue(wakeMainEventId(parent.id)))
        val mainPayload = requireNotNull(mainIntent.toWakeTriggerPayload())
        val extraPayload = requireNotNull(finalIntent.toWakeTriggerPayload())
        assertFalse("The main ring must wait for its later extra before starting the awake check", mainPayload.awakeCheckEnabled)

        context.deliverWakeBroadcast(WakeAlarmReceiver(), mainIntent)
        queue.handleIncoming(mainPayload)
        WakeAlarmDeliveryTracker.finish(mainPayload)
        WakeDismissalCoordinator.recordDismissal(context, mainPayload, "test", wakeupCheckCompleted = false)
        queue.handleDismiss(mainPayload.eventId)
        assertFalse(WakeDismissalCoordinator.removeExpiredOneOffAlarmAfterDismissal(context, mainPayload))
        WakeAlarmScheduler.scheduleAll(context)

        val stored = requireNotNull(repository.getWakeAlarm(parent.id))
        assertNull(queue.current)
        assertFalse(AwakeCheckScheduler.isPending(context, wakeMainEventId(parent.id)))
        assertEquals(listOf(5), stored.subAlarms.map { it.signedOffsetMinutes })
        assertTrue(scheduledWakeIntents().containsKey(extraPayload.eventId))
        val nowMillis = System.currentTimeMillis()
        val delegationId = PrefsManager.getDelegationId(context)
        val running = requireNotNull(
            WakeAlarmScheduler.runningOccurrence(
                context,
                stored,
                WakeAlarmScheduler.occurrenceWindows(context, stored),
                nowMillis,
            ),
        )
        val display = wakeAlarmDisplayState(listOf(stored), nowMillis, setOf(stored.id))
        assertEquals(listOf(stored), display.visibleWakeAlarms)
        val hero = requireNotNull(wakeAlarmNow(nextWakeAlarmTrigger(context, delegationId, stored), running))
        assertEquals(WakeAlarmFocus.RING, hero.focus)
        assertEquals(WakeAlarmScheduler.Ring(extraPayload.triggerAtMillis, isExtra = true), hero.highlightedRing)
        val offsets = hero.occurrenceRingOffsets(
            wakeAlarmRingOffsets(stored.subAlarms.map { it.signedOffsetMinutes }),
            nowMillis,
        )
        assertEquals(listOf(0, 5), offsets)
        val stops = wakeTimelineStops(
            offsets = offsets,
            occurrenceAtMillis = hero.occurrenceAtMillis,
            highlightedAtMillis = hero.highlightedRing?.atMillis,
        )
        assertEquals(listOf(mainPayload.triggerAtMillis, extraPayload.triggerAtMillis), stops.map { it.atMillis })
        assertEquals(listOf(WakeStopState.RUNG, WakeStopState.NEXT), stops.map { it.state })
        assertEquals(listOf(true, false), stops.map { it.isMain })
    }

    @Test
    fun finalExtraQueuedBehindAnotherChallenge_keepsItsOneShotAlarm() = runBlocking {
        val (parent, finalIntent) = schedulePastOccurrence()
        val payload = requireNotNull(finalIntent.toWakeTriggerPayload())
        val otherChallenge = payload.copy(
            eventId = wakeMainEventId("other-challenge"),
            isSubAlarm = false,
            wakeUpCheckEnabled = true,
        )
        assertEquals(IncomingResult.BECAME_CURRENT, queue.handleIncoming(otherChallenge))

        context.deliverWakeBroadcast(WakeAlarmReceiver(), finalIntent)
        assertEquals(IncomingResult.QUEUED, queue.handleIncoming(payload))
        WakeAlarmDeliveryTracker.finish(payload)
        WakeAlarmScheduler.scheduleAll(context)

        assertNotNull("An extra waiting behind another challenge keeps its parent", repository.getWakeAlarm(parent.id))
        val waiting = requireNotNull(running(parent))
        assertTrue(waiting.ringWaiting)
        assertEquals(WakeAlarmScheduler.Ring(payload.triggerAtMillis, isExtra = true), waiting.ringing)
        assertEquals(listOf(0, 5), waiting.ringOffsets)
    }

    @Test
    fun overdueFinalExtraNotYetDelivered_keepsItsOneShotAlarm() = runBlocking {
        val (parent, finalIntent) = schedulePastOccurrence()
        val payload = requireNotNull(finalIntent.toWakeTriggerPayload())

        WakeAlarmScheduler.scheduleAll(context)

        assertNotNull("A late ring Android has not delivered must keep its parent", repository.getWakeAlarm(parent.id))
        val overdue = requireNotNull(running(parent))
        assertEquals(WakeAlarmScheduler.Ring(payload.triggerAtMillis, isExtra = true), overdue.dueRing)
        assertNull(overdue.ringing)
        assertEquals(listOf(0, 5), overdue.ringOffsets)
        assertTrue(scheduledWakeIntents().containsKey(payload.eventId))
    }

    private suspend fun schedulePastOccurrence(
        mainTriggerAtMillis: Long = System.currentTimeMillis() - 5 * MINUTE_MILLIS - 1_000L,
        mainAlreadyDelivered: Boolean = true,
    ): Pair<PrayerWakeConfig, Intent> {
        val parent = PrayerWakeConfig(
            id = ALARM_ID,
            prayer = Prayer.FAJR,
            enabled = true,
            repeatMode = WakeRepeatMode.ONCE,
            mainAlarm = WakeMainAlarmConfig(
                mode = WakeMainAlarmMode.FROM_NOW,
                oneOffTriggerAtMillis = mainTriggerAtMillis,
            ),
            subAlarms = listOf(PrayerWakeSubAlarm("after-five", 5, OffsetDirection.AFTER)),
        )
        repository.saveWakeConfig(parent)
        // Build the intents at the earlier scheduling time, without waiting for the real clock.
        val scheduledAt = Calendar.getInstance().apply {
            timeInMillis = parent.mainAlarm.oneOffTriggerAtMillis - MINUTE_MILLIS
        }
        WakeAlarmScheduler.scheduleAllInternal(context, scheduledAt, listOf(parent))
        val intents = scheduledWakeIntents()
        val main = requireNotNull(intents.getValue(wakeMainEventId(parent.id)).toWakeTriggerPayload())
        val finalIntent = Intent(intents.getValue(wakeSubAlarmEventId(parent.id, "after-five")))
        val finalPayload = requireNotNull(finalIntent.toWakeTriggerPayload())
        payloadsToFinish += main
        payloadsToFinish += finalPayload
        assertEquals(main.triggerAtMillis + 5 * MINUTE_MILLIS, finalPayload.triggerAtMillis)
        // The main ring has already been handled; only its final extra remains.
        if (mainAlreadyDelivered) WakeAlarmScheduler.markDelivered(context, main)
        return parent to finalIntent
    }

    private fun running(parent: PrayerWakeConfig): WakeAlarmScheduler.RunningOccurrence? =
        WakeAlarmScheduler.runningOccurrence(context, parent, emptyList())

    private fun scheduledWakeIntents(): Map<String, Intent> = alarms.scheduledAlarms
        .map { Shadows.shadowOf(it.operation).savedIntent }
        .filter { it.component?.className == WakeAlarmReceiver::class.java.name }
        .associateBy { requireNotNull(it.action) }

    companion object {
        private const val ALARM_ID = "lifecycle-one-shot"
        private const val MINUTE_MILLIS = 60_000L
    }
}
