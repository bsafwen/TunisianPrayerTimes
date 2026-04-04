package com.tunisianprayertimes

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class ManualSilenceSchedulerTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowAlarmManager = Shadows.shadowOf(alarmManager)
        Shadows.shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)

        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()

        PrefsManager.setEnabled(context, false)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @Test
    fun schedule_storesEndTimeAndAlarm() {
        val endsAtMillis = ManualSilenceScheduler.schedule(context, 20)

        assertTrue(endsAtMillis > System.currentTimeMillis())
        assertEquals(endsAtMillis, PrefsManager.getManualSilenceEndsAtMillis(context))
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun cancel_clearsStoredEndTime() {
        ManualSilenceScheduler.schedule(context, 20)

        ManualSilenceScheduler.cancel(context)

        assertEquals(-1L, PrefsManager.getManualSilenceEndsAtMillis(context))
    }

    @Test
    fun manualTimerAlarm_restoresPreviousState() {
        SilenceModeController.setManualSilent(context)
        ManualSilenceScheduler.schedule(context, 10)

        val receiver = SilenceReceiver()
        receiver.onReceive(context, android.content.Intent(ManualSilenceScheduler.ACTION_MANUAL_UNSILENCE))

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertFalse(PrefsManager.isManualSilenceActive(context))
        assertEquals(-1L, PrefsManager.getManualSilenceEndsAtMillis(context))
    }

    @Test
    fun manualTimerAlarm_duringActiveAutoWindow_handsBackToAutoSilence() {
        val now = Calendar.getInstance()

        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.FIXED_TIME)
        PrefsManager.setDelayFixedTime(
            context,
            Prayer.FAJR,
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE)
        )
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 5)

        SilenceScheduler.scheduleAll(context)
        assertTrue(PrefsManager.isAutoSilenceActive(context))
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        SilenceModeController.setManualSilent(context)
        ManualSilenceScheduler.schedule(context, 1)
        assertTrue(PrefsManager.isManualSilenceActive(context))
        assertFalse(PrefsManager.isAutoSilenceActive(context))

        val receiver = SilenceReceiver()
        receiver.onReceive(context, android.content.Intent(ManualSilenceScheduler.ACTION_MANUAL_UNSILENCE))

        assertFalse(PrefsManager.isManualSilenceActive(context))
        assertTrue(PrefsManager.isAutoSilenceActive(context))
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notificationManager.currentInterruptionFilter)
    }
}