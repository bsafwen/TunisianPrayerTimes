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
        // Now is 20 minutes into today's Fajr window (60 minutes from the adhan by default).
        Prayer.entries.forEach { PrefsManager.setPrayerSilenceEnabled(context, it, it == Prayer.FAJR) }
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Tunis"))
        val fajr = checkNotNull(PrayerTimesRepository.prayerDay(context, today)?.get(com.tunisianprayertimes.engine.TimeEvent.FAJR)?.at)
        SilenceScheduler.clock = { fajr.plusSeconds(20 * 60) }
        try {
            assertNotNull("The overlapping prayer window must be present for this regression", SilenceScheduler.currentSilenceWindowEnd(context))

            assertTrue(WakeAlarmScheduler.activateSilenceUntilAlarm(context, "wake-1"))
            assertTrue(SilenceStatus.isAppControlledSilenceActive(context))
            assertNull(SilenceStatus.appSilenceEndsAt(context))
        } finally {
            SilenceScheduler.clock = { java.time.Instant.now() }
        }
    }
}
