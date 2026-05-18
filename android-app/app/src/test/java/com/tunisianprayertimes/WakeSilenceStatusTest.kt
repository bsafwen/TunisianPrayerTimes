package com.tunisianprayertimes

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class WakeSilenceStatusTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        Shadows.shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)

        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("wake_alarm_scheduler", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nap_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @Test
    fun silenceUntilAlarm_countsAsAppControlledSilenceForStatusCard() {
        val activated = WakeAlarmScheduler.activateSilenceUntilAlarm(context, "alarm-1")

        assertTrue(activated)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertFalse(PrefsManager.isAutoSilenceActive(context))
        assertFalse(PrefsManager.isManualSilenceActive(context))
        assertTrue(WakeAlarmScheduler.isSilenceUntilAlarmActive(context))
        assertTrue(SilenceStatus.isAppControlledSilenceActive(context))
    }

    @Test
    fun clearingSilencedAlarmClearsAppControlledWakeSilenceStatus() {
        WakeAlarmScheduler.activateSilenceUntilAlarm(context, "alarm-1")

        WakeAlarmScheduler.clearSilencedAlarmId(context)

        assertFalse(WakeAlarmScheduler.isSilenceUntilAlarmActive(context))
        assertFalse(SilenceStatus.isAppControlledSilenceActive(context))
    }
}