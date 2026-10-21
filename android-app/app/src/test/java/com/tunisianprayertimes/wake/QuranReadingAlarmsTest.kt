package com.tunisianprayertimes.wake

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.SilenceReceiver
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.WakeMainAlarmMode
import com.tunisianprayertimes.quran.QuranReading
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The Qur'an read in the app holds back the app's own reminders only: an alarm, an extra alarm, the awake check and a
 * prayer's silence come at their time all the same.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuranReadingAlarmsTest {
    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    // The scheduler's clock at 00:30 in Tunis, hours from any default window (6.9), as in the wake silence tests: an
    // open or imminent prayer window would turn an alarm into a prayer conflict.
    @Before
    fun pinSilenceClock() {
        val tunis = java.time.ZoneId.of("Africa/Tunis")
        SilenceScheduler.clock = { java.time.ZonedDateTime.of(java.time.LocalDate.now(tunis), java.time.LocalTime.of(0, 30), tunis).toInstant() }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // The app's own start-up runs at the main looper's first idle: let it run before the test, not inside it.
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        runBlocking { PrayerWakeRepository(context).clearAllWakeConfigs() }
        listOf("prayer_silence_prefs", "wake_alarm_scheduler", "nap_prefs")
            .forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Shadows.shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        // The Qur'an is being read now.
        QuranReading.opened(context)
        assertTrue(QuranReading.holdsUntil(context) != null)
    }

    @After
    fun teardown() {
        QuranReading.end(context, 0L)
        SilenceScheduler.clock = { java.time.Instant.now() }
    }

    @Test
    fun anAlarmRingsWhileTheQuranIsRead() {
        context.deliverWakeBroadcast(WakeAlarmReceiver(), wakeIntent(wakeMainEventId("reading-alarm")))

        assertStarted(WakePlaybackService::class.java)
    }

    @Test
    fun anExtraAlarmRingsWhileTheQuranIsRead() {
        context.deliverWakeBroadcast(WakeAlarmReceiver(), wakeIntent(wakeSubAlarmEventId("reading-alarm", "extra-1"), subAlarmId = "extra-1"))

        assertStarted(WakePlaybackService::class.java)
    }

    @Test
    fun theAwakeCheckStartsWhileTheQuranIsRead() {
        val eventId = wakeMainEventId("reading-check")
        runBlocking { PrayerWakeRepository(context).saveWakeConfig(PrayerWakeConfig(id = "reading-check", prayer = Prayer.FAJR, enabled = true)) }

        context.deliverWakeBroadcast(
            AwakeCheckReceiver(),
            Intent(context, AwakeCheckReceiver::class.java)
                .setAction(AwakeCheckReceiver.ACTION_START_AWAKE_CHECK)
                .putExtra(EXTRA_EVENT_ID, eventId)
                .putExtra(EXTRA_AUTO_SILENCE_OVERRIDE_ALLOWED, false)
                .putExtra(EXTRA_WAKE_OCCURRENCE_AT_MILLIS, System.currentTimeMillis()),
        )

        assertStarted(AwakeCheckService::class.java)
    }

    @Test
    fun aPrayersSilenceComesWhileTheQuranIsRead() {
        PrefsManager.setAutoLocationUpdateEnabled(context, false)

        SilenceReceiver().onReceive(context, Intent("com.tunisianprayertimes.ACTION_SILENCE").putExtra("extra_prayer", Prayer.DHUHR.name))

        assertTrue(PrefsManager.isAutoSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notificationManager.currentInterruptionFilter)
    }

    private fun wakeIntent(eventId: String, subAlarmId: String? = null): Intent =
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
            isSubAlarm = subAlarmId != null,
            subAlarmId = subAlarmId,
        ).setAction(eventId)

    private fun assertStarted(service: Class<*>) {
        val started = Shadows.shadowOf(RuntimeEnvironment.getApplication() as Application).nextStartedService
        assertEquals(service.name, started?.component?.className)
    }
}
