package com.tunisianprayertimes.wake

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.mosque.SilenceCoordinator
import com.tunisianprayertimes.mosque.SilenceOwner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AwakeCheckSilenceRestorationTest {
    private lateinit var context: Context
    private lateinit var notifications: NotificationManager
    private lateinit var audio: AudioManager
    private var controller: ServiceController<AwakeCheckService>? = null
    private var now = at(0, 30)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        SilenceScheduler.clock = { now }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        listOf("prayer_silence_prefs", "wake_alarm_scheduler", "nap_prefs",
            "awake_check_occurrences", "mosque_silence_settings", "silence_ownership").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        notifications = context.getSystemService(NotificationManager::class.java)
        Shadows.shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audio = context.getSystemService(AudioManager::class.java)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        PrefsManager.setDelegationId(context, 615)
        PrefsManager.setEnabled(context, true)
    }

    @After
    fun cleanup() {
        controller?.destroy()
        SilenceModeController.resumeAutoSilenceAfterAwakeCheck(context)
        SilenceScheduler.clock = { Instant.now() }
    }

    @Test
    fun stoppingEscalatedCheckRestoresPrayerSilenceAndItsOriginalEnd() {
        startCheck()
        enterPrayerAndEscalate()
        val end = requireNotNull(SilenceScheduler.currentSilenceWindowEnd(context))

        stopCheck()

        assertTrue(PrefsManager.isAutoSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notifications.currentInterruptionFilter)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
        assertEquals(end, SilenceScheduler.currentSilenceWindowEnd(context))

        now = Instant.ofEpochMilli(end)
        SilenceScheduler.scheduleAllInternal(context, calendarNow(), allowDelegationBridge = false)
        assertFalse(PrefsManager.isAutoSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notifications.currentInterruptionFilter)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
    }

    @Test
    fun prayerWindowEndingDuringCheckDoesNotSilencePhoneAgain() {
        startCheck()
        enterPrayerAndEscalate()
        now = Instant.ofEpochMilli(requireNotNull(SilenceScheduler.currentSilenceWindowEnd(context)))

        stopCheck()

        assertFalse(PrefsManager.isAutoSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notifications.currentInterruptionFilter)
    }

    @Test
    fun pendingCheckDeliveredAfterPrayerStartsRestoresSilenceWhenStopped() {
        now = at(12, 50)
        assertTrue(SilenceModeController.enableAutoSilence(context, Prayer.DHUHR))
        startCheck()
        assertTrue(PrefsManager.isAutoSilenceActive(context))

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60_000L, TimeUnit.MILLISECONDS)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notifications.currentInterruptionFilter)

        stopCheck()

        assertTrue(PrefsManager.isAutoSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notifications.currentInterruptionFilter)
    }

    @Test
    fun confirmingCheckBeforeEscalationLeavesPrayerSilenceActive() {
        startCheck()
        now = at(12, 50)
        assertTrue(SilenceModeController.enableAutoSilence(context, Prayer.DHUHR))

        stopCheck()

        assertTrue(PrefsManager.isAutoSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notifications.currentInterruptionFilter)
    }

    @Test
    fun refreshDuringEscalationKeepsCheckAudibleAndRestoresSilenceAfterStop() {
        startCheck()
        enterPrayerAndEscalate()

        SilenceScheduler.scheduleAllInternal(context, calendarNow())
        assertFalse(PrefsManager.isAutoSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notifications.currentInterruptionFilter)

        stopCheck()
        assertTrue(PrefsManager.isAutoSilenceActive(context))
    }

    @Test
    fun explicitPrayerDismissalDuringCheckIsRespected() {
        startCheck()
        enterPrayerAndEscalate()
        SilenceScheduler.dismissCurrentWindows(context)

        stopCheck()

        assertFalse(PrefsManager.isAutoSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notifications.currentInterruptionFilter)
    }

    @Test
    fun disablingAutomaticSilenceDuringCheckIsRespected() {
        startCheck()
        enterPrayerAndEscalate()
        PrefsManager.setEnabled(context, false)

        stopCheck()

        assertFalse(PrefsManager.isAutoSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notifications.currentInterruptionFilter)
    }

    @Test
    fun manualSilenceStartedDuringCheckStaysActiveAfterStop() {
        startCheck()
        enterPrayerAndEscalate()
        assertTrue(SilenceModeController.setManualSilent(context))

        stopCheck()

        assertTrue(PrefsManager.isManualSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notifications.currentInterruptionFilter)
    }

    @Test
    fun ownedPrayerRuleResumesAfterCheckWithoutDismissingOccurrence() {
        assertTrue(SilenceCoordinator.enrollOrRepair(context))
        startCheck()
        now = at(12, 50)
        assertTrue(SilenceModeController.enableAutoSilence(context, Prayer.DHUHR))
        // Robolectric does not aggregate automatic Zen rules into the global interruption filter.
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60_000L, TimeUnit.MILLISECONDS)

        assertFalse(SilenceCoordinator.requested(context, SilenceOwner.PRAYER))
        assertFalse(SilenceScheduler.isDismissed(context, Prayer.DHUHR, now.atZone(TUNIS).toLocalDate()))
        assertTrue(SilenceCoordinator.enableTime(context))
        assertFalse(SilenceCoordinator.requested(context, SilenceOwner.PRAYER))

        stopCheck()

        assertTrue(SilenceCoordinator.requested(context, SilenceOwner.PRAYER))
    }

    @Test
    fun confirmingQuietCheckDoesNotCreateSilenceOutsidePrayerWindow() {
        startCheck()

        stopCheck()

        assertFalse(PrefsManager.isAutoSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notifications.currentInterruptionFilter)
    }

    private fun startCheck() {
        controller = Robolectric.buildService(AwakeCheckService::class.java,
            AwakeCheckService.intent(context, "wake:main:one-off-restoration", null, null,
                autoSilenceOverrideAllowed = true))
        val service = requireNotNull(controller).create().get()
        service.onStartCommand(requireNotNull(controller).intent, 0, 1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(AwakeCheckService.isRunning.value)
    }

    private fun enterPrayerAndEscalate() {
        now = at(12, 50)
        assertTrue(SilenceModeController.enableAutoSilence(context, Prayer.DHUHR))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notifications.currentInterruptionFilter)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60_000L, TimeUnit.MILLISECONDS)
        assertTrue(SilenceModeController.isAutoSilencePausedForAwakeCheck)
        assertFalse(PrefsManager.isAutoSilenceActive(context))
        assertFalse(SilenceScheduler.isDismissed(context, Prayer.DHUHR, now.atZone(TUNIS).toLocalDate()))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notifications.currentInterruptionFilter)
    }

    private fun stopCheck() {
        // Both notification confirmation and in-app confirmation stop this same service.
        requireNotNull(controller).destroy()
        controller = null
        assertFalse(SilenceModeController.isAutoSilencePausedForAwakeCheck)
        assertFalse(AwakeCheckService.isRunning.value)
    }

    private fun calendarNow() = Calendar.getInstance().apply { timeInMillis = now.toEpochMilli() }

    companion object {
        private val TUNIS = ZoneId.of("Africa/Tunis")
        private fun at(hour: Int, minute: Int) = ZonedDateTime.of(2026, 6, 2, hour, minute, 0, 0, TUNIS).toInstant()
    }
}
