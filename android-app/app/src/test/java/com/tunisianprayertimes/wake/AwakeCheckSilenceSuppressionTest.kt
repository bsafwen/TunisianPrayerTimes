package com.tunisianprayertimes.wake

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.SilenceModeController
import kotlinx.coroutines.runBlocking
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AwakeCheckSilenceSuppressionTest {
    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    // The scheduler's clock at 00:30 in Tunis, hours from any default window (6.9): these runs must not depend on
    // the time of day, since an open or imminent prayer window turns an alarm into a prayer conflict.
    @Before
    fun pinSilenceClock() {
        val tunis = java.time.ZoneId.of("Africa/Tunis")
        SilenceScheduler.clock = { java.time.ZonedDateTime.of(java.time.LocalDate.now(tunis), java.time.LocalTime.of(0, 30), tunis).toInstant() }
    }

    @After
    fun releaseSilenceClock() {
        SilenceScheduler.clock = { java.time.Instant.now() }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking { PrayerWakeRepository(context).clearAllWakeConfigs() }
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("wake_alarm_scheduler", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("nap_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Shadows.shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = Shadows.shadowOf(alarmManager)
    }

    @Test
    fun startAwakeCheckWithoutSilenceStartsService() {
        val eventId = "wake:main:normal-alarm"

        receiveStartAwakeCheck(eventId)

        val startedService = nextStartedService()
        assertEquals(AwakeCheckService::class.java.name, startedService?.component?.className)
    }

    @Test
    fun startAwakeCheckDuringAutoSilenceCancelsWithoutStartingService() {
        val eventId = "wake:main:auto-silenced-alarm"
        scheduleAwakeCheck(eventId)
        markAutoSilenceActive()

        receiveStartAwakeCheck(eventId)

        assertAwakeCheckCancelledWithoutService()
    }

    @Test
    fun startAwakeCheckDuringManualSilenceCancelsWithoutStartingService() {
        val eventId = "wake:main:manual-silenced-alarm"
        scheduleAwakeCheck(eventId)
        assertTrue(SilenceModeController.setManualSilent(context))

        receiveStartAwakeCheck(eventId)

        assertAwakeCheckCancelledWithoutService()
    }

    @Test
    fun startAwakeCheckDuringWakeSilenceCancelsWithoutStartingService() {
        val eventId = "wake:main:wake-silenced-alarm"
        scheduleAwakeCheck(eventId)
        assertTrue(WakeAlarmScheduler.activateSilenceUntilAlarm(context, "other-alarm"))

        receiveStartAwakeCheck(eventId)

        assertAwakeCheckCancelledWithoutService()
    }

    @Test
    fun staleAutoSilenceFlagWithoutDndDoesNotCancelAwakeCheck() {
        val eventId = "wake:main:stale-auto-flag-alarm"
        scheduleAwakeCheck(eventId)
        PrefsManager.markAutoSilenceActive(
            context = context,
            previousRingerMode = AudioManager.RINGER_MODE_NORMAL,
            previousInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        receiveStartAwakeCheck(eventId)

        assertEquals("Stale silence flags without DND should not cancel pending awake-check alarms", 1, shadowAlarmManager.scheduledAlarms.size)
        val startedService = nextStartedService()
        assertEquals(AwakeCheckService::class.java.name, startedService?.component?.className)
    }

    @Test
    fun delayedAwakeCheckScheduledInsideAutoSilenceCancelsAfterWindowEnded() {
        val eventId = "wake:main:delayed-auto-silenced-alarm"
        scheduleAwakeCheck(eventId)

        receiveStartAwakeCheck(
            eventId = eventId,
            scheduledTriggerAtMillis = dhuhrSilenceWindowTriggerAtMillis(),
        )

        assertAwakeCheckCancelledWithoutService()
    }

    @Test
    fun delayedAwakeCheckScheduledInsideAutoSilenceStartsWhenOverrideAllowed() {
        val eventId = "wake:main:override-auto-silenced-alarm"

        receiveStartAwakeCheck(
            eventId = eventId,
            scheduledTriggerAtMillis = dhuhrSilenceWindowTriggerAtMillis(),
            autoSilenceOverrideAllowed = true,
        )

        val startedService = nextStartedService()
        assertEquals(AwakeCheckService::class.java.name, startedService?.component?.className)
    }

    private fun scheduleAwakeCheck(eventId: String) {
        assertTrue(
            AwakeCheckScheduler.schedule(
                context = context,
                eventId = eventId,
                delayMinutes = 7,
                ringtonePresetName = null,
                customRingtoneUri = null,
                occurrenceAtMillis = System.currentTimeMillis(),
            ),
        )
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
    }

    private fun receiveStartAwakeCheck(
        eventId: String,
        scheduledTriggerAtMillis: Long? = null,
        autoSilenceOverrideAllowed: Boolean = false,
    ) {
        val alarmId = requireNotNull(wakeAlarmIdFromEventId(eventId))
        runBlocking {
            PrayerWakeRepository(context).saveWakeConfig(
                PrayerWakeConfig(id = alarmId, prayer = Prayer.FAJR, enabled = true),
            )
        }
        val storedTriggerAtMillis = AwakeCheckScheduler.triggerAtMillis(context, eventId)
        val deliveredTriggerAtMillis = scheduledTriggerAtMillis ?: storedTriggerAtMillis
        if (storedTriggerAtMillis != null && deliveredTriggerAtMillis != storedTriggerAtMillis) {
            // Deliver the current check late, after its original silence window ended.
            context.getSharedPreferences("awake_check_occurrences", Context.MODE_PRIVATE)
                .edit()
                .putLong("$eventId:trigger", requireNotNull(deliveredTriggerAtMillis))
                .commit()
        }
        val occurrenceAtMillis = AwakeCheckScheduler.occurrenceAtMillis(context, eventId)
            ?: System.currentTimeMillis()
        context.deliverWakeBroadcast(
            AwakeCheckReceiver(),
            Intent(context, AwakeCheckReceiver::class.java)
                .setAction(AwakeCheckReceiver.ACTION_START_AWAKE_CHECK)
                .putExtra(EXTRA_EVENT_ID, eventId)
                .putExtra(EXTRA_AUTO_SILENCE_OVERRIDE_ALLOWED, autoSilenceOverrideAllowed)
                .putExtra(EXTRA_WAKE_OCCURRENCE_AT_MILLIS, occurrenceAtMillis)
                .apply {
                    deliveredTriggerAtMillis?.let { putExtra(EXTRA_AWAKE_CHECK_TRIGGER_AT_MILLIS, it) }
                },
        )
    }

    /** A trigger inside Tunis's Dhuhr window of Tuesday 2026-06-02: D12's 12:40–13:15 in Africa/Tunis (6.9). */
    private fun dhuhrSilenceWindowTriggerAtMillis(): Long {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelegationId(context, 615)
        return java.time.ZonedDateTime.of(2026, 6, 2, 12, 50, 0, 0, java.time.ZoneId.of("Africa/Tunis")).toInstant().toEpochMilli()
    }

    private fun markAutoSilenceActive() {
        PrefsManager.markAutoSilenceActive(
            context = context,
            previousRingerMode = AudioManager.RINGER_MODE_NORMAL,
            previousInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
    }

    private fun assertAwakeCheckCancelledWithoutService() {
        assertEquals("Awake check should cancel its pending alarm during auto-silence", 0, shadowAlarmManager.scheduledAlarms.size)
        assertNull(
            "Awake check service should not start while app-controlled silence is active",
            nextStartedService(),
        )
    }

    private fun nextStartedService(): Intent? =
        Shadows.shadowOf(RuntimeEnvironment.getApplication() as Application).nextStartedService
}
