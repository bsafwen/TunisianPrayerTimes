package com.tunisianprayertimes.wake

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.WakeAlarmComputer
import java.util.Calendar
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** A skip or a stop stays on its occurrence when the prayer time moves under it (another delegation here). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WakeMarkersFollowMovedPrayerTest {
    private lateinit var context: Context
    private lateinit var repository: PrayerWakeRepository

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        listOf("prayer_silence_prefs", "wake_alarm_scheduler", "nap_prefs").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        WakeAlarmQueueHolder.queue.clear()
        WakeOccurrenceSkipRegistry.clearAlarm(ALARM_ID)
        repository = PrayerWakeRepository(context)
        repository.clearAllWakeConfigs()
        PrefsManager.setDelegationId(context, TUNIS)
    }

    @After
    fun cleanup() = runBlocking {
        repository.clearAllWakeConfigs()
        WakeOccurrenceSkipRegistry.clearAlarm(ALARM_ID)
        PrefsManager.setDelegationId(context, TUNIS)
    }

    @Test
    fun aSkippedFajrStaysSkippedWhenAnotherDelegationMovesIt() = runBlocking {
        repository.saveWakeConfig(PrayerWakeConfig(id = ALARM_ID, prayer = Prayer.FAJR, enabled = true))
        val skipped = requireNotNull(WakeAlarmScheduler.toggleSkipNextWakeOccurrence(context, ALARM_ID)?.skipNextOccurrenceAtMillis)

        // Tabarka's Fajr comes about 6 minutes after Tunis's all year: the skip used to keep the old instant, and
        // the moved Fajr rang. A later Fajr keeps the test clear of the minutes between the two.
        PrefsManager.setDelegationId(context, TABARKA)
        WakeAlarmScheduler.scheduleAll(context)

        val stored = requireNotNull(repository.getWakeAlarm(ALARM_ID))
        val moved = WakeAlarmScheduler.occurrenceWindows(context, stored)
            .map { window -> window.occurrenceAtMillis }
            .minBy { occurrence -> abs(occurrence - skipped) }
        assertNotEquals(skipped, moved)
        assertEquals(moved, stored.skipNextOccurrenceAtMillis)
        val now = Calendar.getInstance()
        val next = WakeAlarmComputer.compute(now, stored, WakeAlarmScheduler.loadPrayerDayContexts(context, now)).mainAlarm
        assertNotEquals("The moved occurrence must not ring", moved, next?.occurrenceAtMillis)
    }

    @Test
    fun aStoppedOccurrenceStaysStoppedWhenItsPrayerMoves() = runBlocking {
        repository.saveWakeConfig(PrayerWakeConfig(id = ALARM_ID, prayer = Prayer.FAJR, enabled = true))
        val config = requireNotNull(repository.getWakeAlarm(ALARM_ID))
        val now = Calendar.getInstance()
        val stopped = WakeAlarmScheduler.occurrenceWindows(context, config)
            .filter { window -> window.startsAtMillis > now.timeInMillis }
            .minOf { window -> window.occurrenceAtMillis }
        repository.setWakeAlarmStoppedOccurrence(ALARM_ID, stopped)

        PrefsManager.setDelegationId(context, TABARKA)
        WakeAlarmScheduler.scheduleAll(context)

        val stored = requireNotNull(repository.getWakeAlarm(ALARM_ID))
        val all = WakeAlarmComputer.scheduledTriggers(
            stored.copy(stoppedOccurrenceAtMillis = null),
            WakeAlarmScheduler.loadPrayerDayContexts(context, Calendar.getInstance()),
        ).map { trigger -> trigger.occurrenceAtMillis }
        val moved = all.minBy { occurrence -> abs(occurrence - stopped) }
        assertNotEquals(stopped, moved)
        assertEquals(moved, stored.stoppedOccurrenceAtMillis)
    }

    private companion object {
        const val ALARM_ID = "fajr_follow"
        const val TUNIS = 615
        const val TABARKA = 497
    }
}
