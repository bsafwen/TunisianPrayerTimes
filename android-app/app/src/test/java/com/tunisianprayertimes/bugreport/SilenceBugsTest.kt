package com.tunisianprayertimes.bugreport

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.BootReceiver
import com.tunisianprayertimes.ManualSilenceScheduler
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.SilenceMode
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.SilenceReceiver
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.Calendar

/** Silence-state bugs from BUG_REPORT.md: H1, H2, M7, M11, M14 and L28. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SilenceBugsTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        assumeBugReproEnabled()
        context = ApplicationProvider.getApplicationContext()
        context.clearSilenceAndWakePrefs()
        context.resetPhoneToNormal()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        PrefsManager.setEnabled(context, true)
    }

    /** H1: after a reboot the stored manual end alarm is gone; the boot path must re-arm it. */
    @Test
    fun H1_bootRearmsAnUnexpiredTimedManualSilence() = runBlocking {
        val endsAt = System.currentTimeMillis() + 30 * 60_000L
        // State left behind by a reboot: manual silence on, end time stored, no alarm.
        SilenceModeController.setManualSilent(context)
        PrefsManager.setManualSilenceEndsAtMillis(context, endsAt)

        BootReceiver().refreshSchedules(context)

        val manualEnd = context.shadowAlarms().scheduledAlarms.firstOrNull { alarm ->
            Shadows.shadowOf(alarm.operation).savedIntent.action == ManualSilenceScheduler.ACTION_MANUAL_UNSILENCE
        }
        assertNotNull("The boot path must re-arm the manual silence end alarm", manualEnd)
        assertEquals(endsAt, manualEnd!!.triggerAtTime)
    }

    /** H1: a manual silence whose end passed during the reboot must be ended by the boot path. */
    @Test
    fun H1_bootEndsAManualSilenceThatExpiredDuringTheReboot() = runBlocking {
        SilenceModeController.setManualSilent(context)
        PrefsManager.setManualSilenceEndsAtMillis(context, System.currentTimeMillis() - 60_000L)

        BootReceiver().refreshSchedules(context)

        assertEquals("Manual silence should be over", false, PrefsManager.isManualSilenceActive(context))
        assertEquals(AudioManager.RINGER_MODE_NORMAL, context.ringerMode())
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, context.interruptionFilter())
    }

    /** H2: a nap ("silent until alarm") created during a prayer window leaves the phone silenced after it rings. */
    @Test
    fun H2_napCreatedDuringPrayerWindowLeavesPhoneNormalAfterItRings() {
        SilenceModeController.enableAutoSilence(context, Prayer.DHUHR) // the prayer window starts
        assertTrue(WakeAlarmScheduler.activateSilenceUntilAlarm(context, "nap")) // nap saved during the window
        SilenceModeController.disableAutoSilence(context) // the prayer window ends
        WakeAlarmScheduler.releaseSilenceForRingingAlarm(context, "nap") // the nap alarm rings

        assertEquals("Ringer after the nap alarm", AudioManager.RINGER_MODE_NORMAL, context.ringerMode())
        assertEquals("DND after the nap alarm", NotificationManager.INTERRUPTION_FILTER_ALL, context.interruptionFilter())
    }

    /** H2: a nap that rings during a prayer window leaves the phone silenced after that window. */
    @Test
    fun H2_napRingingDuringPrayerWindowLeavesPhoneNormalAfterWindow() {
        assertTrue(WakeAlarmScheduler.activateSilenceUntilAlarm(context, "nap")) // nap before the window
        SilenceModeController.enableAutoSilence(context, Prayer.ASR) // the window starts during the nap
        WakeAlarmScheduler.releaseSilenceForRingingAlarm(context, "nap") // the nap alarm rings in the window
        SilenceModeController.disableAutoSilence(context) // the window ends

        assertEquals("Ringer after the window", AudioManager.RINGER_MODE_NORMAL, context.ringerMode())
        assertEquals("DND after the window", NotificationManager.INTERRUPTION_FILTER_ALL, context.interruptionFilter())
    }

    /** M7: a paused nap is not re-applied when a prayer window ends while another nap alarm is pending. */
    @Test
    fun M7_pausedNapIsReappliedAfterPrayerWindowEnds() {
        assertTrue(WakeAlarmScheduler.activateSilenceUntilAlarm(context, "A"))
        assertTrue(WakeAlarmScheduler.activateSilenceUntilAlarm(context, "B"))
        WakeAlarmScheduler.releaseSilenceForRingingAlarm(context, "A") // A rings; silence paused for it
        SilenceModeController.enableAutoSilence(context, Prayer.ASR) // a prayer window starts
        WakeAlarmScheduler.resumeSilenceUntilNextAlarm(context) // A's playback stops during the window
        SilenceModeController.disableAutoSilence(context) // the window ends; B is still pending

        assertTrue("Silence for the pending alarm B should be active again",
            WakeAlarmScheduler.isSilenceUntilAlarmActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, context.interruptionFilter())
    }

    /** M11: one prayer's end alarm must not end the silence while another prayer's window is still active. */
    @Test
    fun M11_unsilenceOfOnePrayerKeepsOverlappingWindowSilenced() {
        val today = Calendar.getInstance()
        val times = requireNotNull(PrayerTimesRepository.loadDayPrayerTimes(
            context, PrefsManager.getDelegationId(context),
            today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH),
        ))
        val maghrib = times.maghrib.hour * 60 + times.maghrib.minute
        val isha = times.isha.hour * 60 + times.isha.minute
        Prayer.values().forEach { PrefsManager.setPrayerSilenceEnabled(context, it, it == Prayer.MAGHRIB || it == Prayer.ISHA) }
        // Maghrib's window ends 20 minutes after Isha's adhan; Isha's window runs 30 minutes from its adhan.
        PrefsManager.setConfig(context, Prayer.MAGHRIB, PrayerSilenceConfig(endOffsetMinutes = isha - maghrib + 20))
        PrefsManager.setConfig(context, Prayer.ISHA, PrayerSilenceConfig(afterMinutes = 30))
        val maghribEnd = isha + 20

        withLocalClockAt(maghribEnd / 60, maghribEnd % 60) {
            SilenceModeController.enableAutoSilence(context, Prayer.MAGHRIB)
            SilenceReceiver().onReceive(
                context,
                Intent("com.tunisianprayertimes.ACTION_UNSILENCE").putExtra("extra_prayer", Prayer.MAGHRIB.name),
            )
        }

        assertTrue("Isha's window is still active, so silence must continue", PrefsManager.isAutoSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, context.interruptionFilter())
    }

    /** M14: a window that ends after midnight is cut short by the 00:01 reschedule. */
    @Test
    fun M14_windowThatEndsAfterMidnightSurvivesTheMidnightReschedule() {
        Prayer.values().forEach { PrefsManager.setPrayerSilenceEnabled(context, it, it == Prayer.ISHA) }
        PrefsManager.setConfig(context, Prayer.ISHA, PrayerSilenceConfig(mode = SilenceMode.FIXED_TIME, fixedHour = 1, fixedMinute = 0))

        SilenceScheduler.scheduleAllInternal(context, calendarAt(2026, Calendar.OCTOBER, 14, 21, 30))
        assertTrue("Precondition: Isha's window is active at 21:30", PrefsManager.isAutoSilenceActive(context))

        SilenceScheduler.scheduleAllInternal(context, calendarAt(2026, Calendar.OCTOBER, 15, 0, 1))

        assertTrue("Isha's window runs until 01:00, so silence must continue at 00:01",
            PrefsManager.isAutoSilenceActive(context))
        val oneAm = calendarAt(2026, Calendar.OCTOBER, 15, 1, 0).timeInMillis
        assertTrue("The 01:00 end alarm must still be scheduled",
            context.shadowAlarms().scheduledAlarms.any { it.triggerAtTime == oneAm })
    }

    /** L28: the end-of-window fallback overrides the user's own ringer mode. */
    @Test
    fun L28_endOfWindowFallbackKeepsTheUsersVibrateMode() {
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode = AudioManager.RINGER_MODE_VIBRATE
        SilenceModeController.enableAutoSilence(context, Prayer.DHUHR)
        SilenceModeController.disableAutoSilence(context) // the user dismissed the silence in the app
        assertEquals("Precondition", AudioManager.RINGER_MODE_VIBRATE, context.ringerMode())

        SilenceReceiver().onReceive(
            context,
            Intent("com.tunisianprayertimes.ACTION_UNSILENCE").putExtra("extra_prayer", Prayer.DHUHR.name),
        )

        assertEquals("The window end must not change the user's vibrate mode",
            AudioManager.RINGER_MODE_VIBRATE, context.ringerMode())
    }
}
