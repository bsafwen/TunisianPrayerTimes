package com.tunisianprayertimes.wake

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.SilenceModeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun receiveStartAwakeCheck(eventId: String) {
        AwakeCheckReceiver().onReceive(
            context,
            Intent(context, AwakeCheckReceiver::class.java)
                .setAction(AwakeCheckReceiver.ACTION_START_AWAKE_CHECK)
                .putExtra(EXTRA_EVENT_ID, eventId),
        )
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