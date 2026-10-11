package com.tunisianprayertimes.bugreport

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.ClockTime
import com.tunisianprayertimes.Locality
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerRelativeOffset
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.WakeMainAlarmConfig
import com.tunisianprayertimes.WakeMainAlarmMode
import com.tunisianprayertimes.WakeRepeatMode
import com.tunisianprayertimes.wake.AwakeCheckReceiver
import com.tunisianprayertimes.wake.AwakeCheckScheduler
import com.tunisianprayertimes.wake.PrayerWakeRepository
import com.tunisianprayertimes.wake.WakeAlarmQueueHolder
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import com.tunisianprayertimes.wake.WakeAutoSilenceConflictController
import com.tunisianprayertimes.wake.WakePlaybackService
import com.tunisianprayertimes.wake.WakeTriggerPayload
import com.tunisianprayertimes.wake.wakeMainEventId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.chrono.HijrahDate
import java.util.Calendar

/** Wake-alarm, preference and analytics bugs from BUG_REPORT.md: M1, M3, M6, M8, M9, L20 and L29. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WakeAndPrefsBugsTest {
    private lateinit var context: Context
    private lateinit var repository: PrayerWakeRepository

    @Before
    fun setup() = runBlocking {
        assumeBugReproEnabled()
        context = ApplicationProvider.getApplicationContext()
        context.clearSilenceAndWakePrefs()
        context.resetPhoneToNormal()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        WakeAlarmQueueHolder.queue.clear()
        repository = PrayerWakeRepository(context)
        repository.clearAllWakeConfigs()
    }

    /** M1: an alarm allowed to ring over a prayer window is dropped when another nap is pending. */
    @Test
    fun M1_alarmAllowedOverPrayerWindowIsPlayedWhileAnotherNapIsPending() {
        assertTrue(WakeAlarmScheduler.activateSilenceUntilAlarm(context, "B")) // nap B is pending
        SilenceModeController.enableAutoSilence(context, Prayer.MAGHRIB) // window starts during the nap
        val payload = WakeTriggerPayload(
            eventId = wakeMainEventId("A"),
            prayer = Prayer.FAJR,
            effectivePrayer = Prayer.FAJR,
            mainAlarmMode = WakeMainAlarmMode.FROM_NOW,
            hour = 5,
            minute = 0,
            ringtone = RingtonePreset.DEFAULT_ALARM,
            vibrationOnly = true,
            wakeUpCheckEnabled = false,
            progressiveVolume = false,
            snoreTrackingEnabled = false,
            autoSilenceOverrideAllowed = true,
            useAutoSilenceConflictPlayback = true,
            autoSilenceConflictPrayer = Prayer.MAGHRIB,
            awakeCheckEnabled = false,
            isSubAlarm = false,
        )
        // WakeAlarmReceiver lifts the window, then starts the playback service with the same payload.
        assertTrue(WakeAutoSilenceConflictController.liftAutoSilenceIfNeeded(context, payload))
        val controller = Robolectric.buildService(WakePlaybackService::class.java, WakePlaybackService.playbackIntent(context, payload))
        val service = controller.create().get()
        service.onStartCommand(controller.intent, 0, 1)

        assertEquals("The approved alarm must be played", payload.eventId, WakeAlarmQueueHolder.queue.current?.eventId)
        controller.destroy()
    }

    /** M3: the Ramadan Isha override is not reverted after Ramadan and deletes the user's Isha end offset. */
    @Test
    fun M3_ramadanIshaOverrideIsRevertedAfterRamadan() {
        PrefsManager.setEndOffsetMinutes(context, Prayer.ISHA, 45) // the user's own Isha end
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, HijrahDate.of(1448, 9, 10)) // during Ramadan
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, HijrahDate.of(1448, 10, 5)) // after Ramadan

        assertEquals("Isha duration after Ramadan", 30, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
        assertEquals("The user's Isha end offset after Ramadan", 45, PrefsManager.getEndOffsetMinutes(context, Prayer.ISHA))
    }

    /** M6: deleting an alarm leaves its pending "are you awake?" check scheduled. */
    @Test
    fun M6_deletingAnAlarmCancelsItsPendingAwakeCheck() = runBlocking {
        val alarm = PrayerWakeConfig(id = "deleted", prayer = Prayer.FAJR, enabled = true)
        repository.saveWakeConfig(alarm)
        assertTrue(AwakeCheckScheduler.schedule(
            context = context,
            eventId = wakeMainEventId(alarm.id),
            delayMinutes = 3,
            ringtonePresetName = null,
            customRingtoneUri = null,
        ))

        repository.deleteWakeAlarm(alarm.id)

        val awakeChecks = context.shadowAlarms().scheduledAlarms.filter { scheduled ->
            Shadows.shadowOf(scheduled.operation).savedIntent.component?.className == AwakeCheckReceiver::class.java.name
        }
        assertTrue("No awake check may remain for a deleted alarm", awakeChecks.isEmpty())
    }

    /** M8: saving from an editor opened before a stop writes the old stop marker back. */
    @Test
    fun M8_savingAStaleEditorSnapshotKeepsTheStoppedOccurrence() = runBlocking {
        val alarm = PrayerWakeConfig(
            id = "repeating",
            prayer = Prayer.FAJR,
            enabled = true,
            mainAlarm = WakeMainAlarmConfig(mode = WakeMainAlarmMode.FIXED_TIME, fixedTime = ClockTime(6, 0)),
            repeatMode = WakeRepeatMode.RECURRING,
        )
        repository.saveWakeConfig(alarm)
        val editorSnapshot = requireNotNull(repository.getWakeAlarm(alarm.id)) // the editor opens
        val stoppedOccurrence = calendarAt(2026, Calendar.OCTOBER, 15, 6, 0).timeInMillis
        repository.setWakeAlarmStoppedOccurrence(alarm.id, stoppedOccurrence) // the alarm rings and is stopped

        // The editor saves a sound change; the schedule is unchanged.
        repository.saveWakeConfig(editorSnapshot.copy(playback = editorSnapshot.playback.copy(vibrationOnly = true)))

        assertEquals(stoppedOccurrence, repository.getWakeAlarm(alarm.id)?.stoppedOccurrenceAtMillis)
    }

    /** M9: a one-time prayer-relative alarm saved after midnight is pinned to the next day. */
    @Test
    fun M9_oneTimePrayerRelativeAlarmSavedAfterMidnightRingsTonight() {
        val delegationId = PrefsManager.getDelegationId(context)
        val minutesLeft = withLocalClockAt(0, 10) {
            val now = Calendar.getInstance()
            val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            val times = requireNotNull(PrayerTimesRepository.loadDayPrayerTimes(
                context, delegationId,
                yesterday.get(Calendar.YEAR), yesterday.get(Calendar.MONTH) + 1, yesterday.get(Calendar.DAY_OF_MONTH),
            ))
            val yesterdayIsha = (yesterday.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, times.isha.hour)
                set(Calendar.MINUTE, times.isha.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // "Isha + N minutes" that falls 30 minutes from now, counted from yesterday's Isha.
            val offset = ((now.timeInMillis + 30 * 60_000L - yesterdayIsha.timeInMillis) / 60_000L).toInt()
            val config = PrayerWakeConfig(
                id = "once",
                prayer = Prayer.ISHA,
                enabled = true,
                mainAlarm = WakeMainAlarmConfig(mode = WakeMainAlarmMode.PRAYER_RELATIVE, prayerOffset = PrayerRelativeOffset(offset)),
                repeatMode = WakeRepeatMode.ONCE,
            )
            val resolve = Class.forName("com.tunisianprayertimes.ui.WakeEditorSheetKt").getDeclaredMethod(
                "resolveOneTimeWakeTrigger", Context::class.java, Int::class.javaPrimitiveType, PrayerWakeConfig::class.java,
            ).apply { isAccessible = true }
            val saved = resolve.invoke(null, context, delegationId, config) as PrayerWakeConfig
            (saved.mainAlarm.oneOffTriggerAtMillis - now.timeInMillis) / 60_000L
        }

        assertTrue("The alarm should ring in about 30 minutes, not $minutesLeft minutes", minutesLeft in 0L..60L)
    }

    /** L20: one location save notifies the location listener once per changed key. */
    @Test
    fun L20_oneLocationSaveNotifiesTheListenerOnce() {
        var notifications = 0
        val unsubscribe = PrefsManager.observeLocationSelection(context) { notifications++ }
        PrefsManager.setLocality(
            context,
            Locality(
                id = "bug-report-locality",
                name = "Test",
                parentName = "Tunis",
                governorateId = 1,
                delegationId = PrefsManager.getDelegationId(context) + 1,
                searchText = "test",
            ),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        unsubscribe()

        assertEquals("One location save should trigger one refresh", 1, notifications)
    }

    /** L29: the analytics silence count ignores which prayers are enabled. */
    @Test
    fun L29_analyticsSilenceCountCountsEnabledPrayers() {
        Prayer.values().forEach { PrefsManager.setPrayerSilenceEnabled(context, it, it == Prayer.FAJR) }

        assertEquals(1, AnalyticsTracker.silencePrayerCount(context))
    }
}
