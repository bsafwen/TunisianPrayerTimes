package com.tunisianprayertimes

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
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
class SilenceStatusTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        Shadows.shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("wake_alarm_scheduler", Context.MODE_PRIVATE).edit().clear().commit()

        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    @Test
    fun regression_manuallySilentPhoneWithoutDnd_isNotAppControlledSilence() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        assertFalse(SilenceStatus.isAppControlledSilenceActive(context))
    }

    @Test
    fun staleAutoSilenceFlagWithoutDndNone_isNotAppControlledSilence() {
        PrefsManager.markAutoSilenceActive(
            context = context,
            previousRingerMode = AudioManager.RINGER_MODE_NORMAL,
            previousInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        assertFalse(SilenceStatus.isAppControlledSilenceActive(context))
    }

    @Test
    fun autoSilenceWithDndNone_isAppControlledSilence() {
        assertTrue(SilenceModeController.enableAutoSilence(context))

        assertTrue(SilenceStatus.isAppControlledSilenceActive(context))
    }
}
