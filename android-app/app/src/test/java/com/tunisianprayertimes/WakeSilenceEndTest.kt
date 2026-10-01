package com.tunisianprayertimes

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import java.util.Calendar
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class)
class WakeSilenceEndTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("wake_alarm_scheduler", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nap_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Shadows.shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @Test
    fun wakeSilenceDoesNotBorrowEndFromCoincidentPrayerWindow() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val startHour = if (hour < 12) 0 else 12
        val endHour = if (hour < 12) 12 else 0 // Midnight on the following day.
        Prayer.entries.forEach { PrefsManager.setPrayerSilenceEnabled(context, it, it == Prayer.FAJR) }
        PrefsManager.setConfig(
            context,
            Prayer.FAJR,
            PrayerSilenceConfig(
                mode = SilenceMode.FIXED_TIME,
                fixedHour = endHour,
                fixedMinute = 0,
                delayMode = DelayMode.FIXED_TIME,
                delayFixedHour = startHour,
                delayFixedMinute = 0,
            ),
        )
        assertNotNull("The overlapping prayer window must be present for this regression", SilenceScheduler.currentSilenceWindowEnd(context))

        assertTrue(WakeAlarmScheduler.activateSilenceUntilAlarm(context, "wake-1"))
        assertTrue(SilenceStatus.isAppControlledSilenceActive(context))
        assertNull(SilenceStatus.appSilenceEndsAt(context))
    }
}
