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
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.WakeMainAlarmMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
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
            ),
        )
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
    }

    private fun startAwakeCheckService(eventId: String) =
        Robolectric.buildService(
            AwakeCheckService::class.java,
            AwakeCheckService.intent(
                context = context,
                eventId = eventId,
                ringtonePreset = null,
                customRingtoneUri = null,
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