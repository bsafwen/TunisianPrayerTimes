package com.tunisianprayertimes.wake

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.WakeMainAlarmMode
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WakeAlarmSilenceSuppressionTest {
    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

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
    }

    @Test
    fun wakeAlarmWithoutSilenceStartsPlaybackService() {
        context.deliverWakeBroadcast(WakeAlarmReceiver(), wakeAlarmIntent(wakeMainEventId("normal-alarm")))

        assertStartedPlaybackService()
    }

    @Test
    fun wakeAlarmDuringAutoSilenceDoesNotStartPlaybackService() {
        markAutoSilenceActive()

        context.deliverWakeBroadcast(WakeAlarmReceiver(), wakeAlarmIntent(wakeMainEventId("auto-silenced-alarm")))

        assertNoStartedService("Wake alarm should not start playback during auto-silence")
    }

    @Test
    fun wakeAlarmDuringManualSilenceDoesNotStartPlaybackService() {
        assertTrue(SilenceModeController.setManualSilent(context))

        context.deliverWakeBroadcast(WakeAlarmReceiver(), wakeAlarmIntent(wakeMainEventId("manual-silenced-alarm")))

        assertNoStartedService("Wake alarm should not start playback during manual silence")
    }

    @Test
    fun wakeAlarmDuringOtherWakeSilenceDoesNotStartPlaybackService() {
        assertTrue(WakeAlarmScheduler.activateSilenceUntilAlarm(context, "other-alarm"))

        context.deliverWakeBroadcast(WakeAlarmReceiver(), wakeAlarmIntent(wakeMainEventId("unrelated-alarm")))

        assertNoStartedService("Wake alarm should not start playback during another wake-silence window")
    }

    @Test
    fun wakeAlarmReleasesItsOwnWakeSilenceAndStartsPlaybackService() {
        val alarmId = "self-silenced-alarm"
        assertTrue(WakeAlarmScheduler.activateSilenceUntilAlarm(context, alarmId))

        context.deliverWakeBroadcast(WakeAlarmReceiver(), wakeAlarmIntent(wakeMainEventId(alarmId)))

        assertStartedPlaybackService()
    }

    @Test
    fun wakeAlarmReleasesOwnWakeSilenceButStillSuppressesDuringAutoSilence() {
        val alarmId = "self-silenced-during-auto"
        assertTrue(WakeAlarmScheduler.activateSilenceUntilAlarm(context, alarmId))
        markAutoSilenceActive()

        context.deliverWakeBroadcast(WakeAlarmReceiver(), wakeAlarmIntent(wakeMainEventId(alarmId)))

        assertNoStartedService("Wake alarm should stay suppressed if auto-silence remains after releasing its own wake silence")
    }

    @Test
    fun staleAutoSilenceFlagWithoutDndDoesNotSuppressWakeAlarm() {
        PrefsManager.markAutoSilenceActive(
            context = context,
            previousRingerMode = AudioManager.RINGER_MODE_NORMAL,
            previousInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        context.deliverWakeBroadcast(WakeAlarmReceiver(), wakeAlarmIntent(wakeMainEventId("stale-auto-flag-alarm")))

        assertStartedPlaybackService()
    }

    private fun wakeAlarmIntent(eventId: String): Intent =
        Intent(context, WakeAlarmReceiver::class.java).populateWakeTriggerPayload(
            eventId = eventId,
            prayer = Prayer.FAJR,
            effectivePrayer = Prayer.FAJR,
            mainAlarmMode = WakeMainAlarmMode.PRAYER_RELATIVE,
            hour = 5,
            minute = 0,
            ringtone = RingtonePreset.DEFAULT_ALARM,
            vibrationOnly = false,
            wakeUpCheckEnabled = false,
            progressiveVolume = false,
            snoreTrackingEnabled = false,
            awakeCheckEnabled = false,
            isSubAlarm = false,
        ).setAction(eventId)

    private fun markAutoSilenceActive() {
        PrefsManager.markAutoSilenceActive(
            context = context,
            previousRingerMode = AudioManager.RINGER_MODE_NORMAL,
            previousInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
    }

    private fun assertStartedPlaybackService() {
        val startedService = nextStartedService()
        assertEquals(WakePlaybackService::class.java.name, startedService?.component?.className)
    }

    private fun assertNoStartedService(message: String) {
        assertNull(message, nextStartedService())
    }

    private fun nextStartedService(): Intent? =
        Shadows.shadowOf(RuntimeEnvironment.getApplication() as Application).nextStartedService
}
