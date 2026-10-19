package com.tunisianprayertimes.wake

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.WakeMainAlarmMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmServiceSilenceGuardTest {
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
        // The app's own start-up waits for the main looper's first idle, and its first silence pass ends an
        // auto-silence it finds outside any open prayer window, as a missed end (6.9). Let it run now, before a test
        // sets up the silence it checks, rather than in the middle of the test.
        Shadows.shadowOf(Looper.getMainLooper()).idle()
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
        WakeAlarmQueueHolder.queue.clear()

        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Shadows.shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = Shadows.shadowOf(alarmManager)
    }

    @Test
    fun awakeCheckServiceStartDuringAutoSilenceCancelsScheduledCheckAndDoesNotRun() {
        val eventId = "wake:main:service-auto-silenced"
        assertTrue(
            AwakeCheckScheduler.schedule(
                context = context,
                eventId = eventId,
                delayMinutes = 7,
                ringtonePresetName = null,
                customRingtoneUri = null,
                occurrenceAtMillis = occurrenceAtMillis(),
            ),
        )
        markAutoSilenceActive()

        val controller = Robolectric.buildService(
            AwakeCheckService::class.java,
            AwakeCheckService.intent(
                context = context,
                eventId = eventId,
                ringtonePreset = null,
                customRingtoneUri = null,
                scheduledTriggerAtMillis = deliveredTrigger(eventId),
                occurrenceAtMillis = occurrenceAtMillis(),
            ),
        )
        val service = controller.create().get()
        val result = service.onStartCommand(controller.intent, 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertFalse(AwakeCheckService.isRunning.value)
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        controller.destroy()
    }

    @Test
    fun awakeCheckServiceCancelsIfAutoSilenceStartsBeforeVibration() {
        val eventId = "wake:main:silence-before-vibration"
        scheduleAwakeCheck(eventId)
        val controller = startAwakeCheckService(eventId)

        markAutoSilenceActive()
        advanceMainLooperBy(60_000L)

        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        controller.destroy()
    }

    @Test
    fun awakeCheckServiceCancelsIfAutoSilenceStartsBeforeRingtone() {
        val eventId = "wake:main:silence-before-ringtone"
        scheduleAwakeCheck(eventId)
        val controller = startAwakeCheckService(eventId)

        advanceMainLooperBy(60_000L)
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)

        markAutoSilenceActive()
        advanceMainLooperBy(3 * 60_000L)

        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        controller.destroy()
    }

    @Test
    fun wakePlaybackServiceStartDuringAutoSilenceDoesNotQueueAlarm() {
        markAutoSilenceActive()
        val payloadIntent = wakePlaybackIntent("wake:main:service-auto-silenced")

        val controller = Robolectric.buildService(WakePlaybackService::class.java, payloadIntent)
        val service = controller.create().get()
        val result = service.onStartCommand(controller.intent, 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertNull(WakeAlarmQueueHolder.queue.current)
        assertEquals(0, WakeAlarmQueueHolder.queue.pendingCount)
        controller.destroy()
    }

    @Test
    fun wakePlaybackServiceStartDuringAutoSilenceDoesNotQueueBehindExistingChallenge() {
        val existingPayload = wakePayload("wake:main:existing-challenge", wakeUpCheckEnabled = true)
        WakeAlarmQueueHolder.queue.handleIncoming(existingPayload)
        markAutoSilenceActive()

        val controller = Robolectric.buildService(
            WakePlaybackService::class.java,
            WakePlaybackService.playbackIntent(
                context,
                wakePayload("wake:main:suppressed-while-existing-active"),
            ),
        )
        val service = controller.create().get()
        val result = service.onStartCommand(controller.intent, 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals("wake:main:existing-challenge", WakeAlarmQueueHolder.queue.current?.eventId)
        assertEquals(0, WakeAlarmQueueHolder.queue.pendingCount)
        controller.destroy()
    }

    @Test
    fun wakePlaybackRefreshDuringAutoSilenceClearsCurrentAlarmBeforePlayback() {
        WakeAlarmQueueHolder.queue.handleIncoming(wakePayload("wake:main:refresh-current"))
        markAutoSilenceActive()

        val controller = Robolectric.buildService(
            WakePlaybackService::class.java,
            Intent(context, WakePlaybackService::class.java)
                .setAction(WakePlaybackService.ACTION_REFRESH_FOR_CURRENT),
        )
        val service = controller.create().get()
        val result = service.onStartCommand(controller.intent, 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertNull(WakeAlarmQueueHolder.queue.current)
        assertEquals(0, WakeAlarmQueueHolder.queue.pendingCount)
        controller.destroy()
    }

    private fun wakePlaybackIntent(eventId: String): Intent =
        WakePlaybackService.playbackIntent(context, wakePayload(eventId))

    private fun scheduleAwakeCheck(eventId: String) {
        assertTrue(
            AwakeCheckScheduler.schedule(
                context = context,
                eventId = eventId,
                delayMinutes = 7,
                ringtonePresetName = null,
                customRingtoneUri = null,
                occurrenceAtMillis = occurrenceAtMillis(),
            ),
        )
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
    }

    /**
     * The ring the check follows, 00:25 today, five minutes before the pinned scheduler clock. A check belongs to one
     * occurrence of its alarm, and the service cancels only the check of the occurrence it was started for.
     */
    private fun occurrenceAtMillis(): Long {
        val tunis = java.time.ZoneId.of("Africa/Tunis")
        return java.time.ZonedDateTime.of(java.time.LocalDate.now(tunis), java.time.LocalTime.of(0, 25), tunis).toInstant().toEpochMilli()
    }

    /**
     * The check's trigger, as its receiver hands it to the service, which cancels only the check it was started for.
     * Moved to 00:37, seven minutes after the pinned scheduler clock: the start asks the planner whether the trigger
     * fell in a prayer window, and the real clock's could.
     */
    private fun deliveredTrigger(eventId: String): Long {
        val tunis = java.time.ZoneId.of("Africa/Tunis")
        val at = java.time.ZonedDateTime.of(java.time.LocalDate.now(tunis), java.time.LocalTime.of(0, 37), tunis).toInstant().toEpochMilli()
        context.getSharedPreferences("awake_check_occurrences", Context.MODE_PRIVATE).edit().putLong("$eventId:trigger", at).commit()
        return at
    }

    private fun startAwakeCheckService(eventId: String) =
        Robolectric.buildService(
            AwakeCheckService::class.java,
            AwakeCheckService.intent(
                context = context,
                eventId = eventId,
                ringtonePreset = null,
                customRingtoneUri = null,
                scheduledTriggerAtMillis = deliveredTrigger(eventId),
                occurrenceAtMillis = occurrenceAtMillis(),
            ),
        ).also { controller ->
            val service = controller.create().get()
            val result = service.onStartCommand(controller.intent, 0, 1)
            assertEquals(Service.START_NOT_STICKY, result)
        }

    private fun advanceMainLooperBy(millis: Long) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)
    }

    private fun wakePayload(
        eventId: String,
        wakeUpCheckEnabled: Boolean = false,
    ): WakeTriggerPayload = WakeTriggerPayload(
            eventId = eventId,
            prayer = Prayer.FAJR,
            effectivePrayer = Prayer.FAJR,
            mainAlarmMode = WakeMainAlarmMode.PRAYER_RELATIVE,
            hour = 5,
            minute = 0,
            ringtone = RingtonePreset.DEFAULT_ALARM,
            vibrationOnly = false,
            wakeUpCheckEnabled = wakeUpCheckEnabled,
            progressiveVolume = false,
            snoreTrackingEnabled = false,
            awakeCheckEnabled = false,
            isSubAlarm = false,
        )

    private fun markAutoSilenceActive() {
        PrefsManager.markAutoSilenceActive(
            context = context,
            previousRingerMode = AudioManager.RINGER_MODE_NORMAL,
            previousInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
    }
}