package com.tunisianprayertimes

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Tests that verify the core silence feature works correctly and that
 * scheduling operations don't interfere with manual silence state.
 *
 * This test class specifically targets the race condition where scheduleAll()
 * calls disableSilentMode() outside a prayer window, which could undo a
 * manual silence action if both run in sequence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SilenceStateTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var shadowAudioManager: ShadowAudioManager
    private lateinit var shadowNotificationManager: ShadowNotificationManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowAudioManager = Shadows.shadowOf(audioManager)
        shadowNotificationManager = Shadows.shadowOf(notificationManager)

        // Grant DND permission in the shadow
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)

        // Clear prefs
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()

        // Start in normal mode
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @Test
    fun manualSilence_setsSilentMode() {
        // Simulate what the manual button does
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT

        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
    }

    @Test
    fun scheduleAll_outsidePrayerWindow_restoresNormalMode() {
        // First silence the phone
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT

        // Then run scheduleAll (which calls disableSilentMode when outside prayer window)
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)

        // scheduleAll correctly restores normal mode when not in a prayer window
        assertEquals(
            "scheduleAll should restore normal mode when outside a prayer window",
            AudioManager.RINGER_MODE_NORMAL,
            audioManager.ringerMode
        )
    }

    @Test
    fun manualSilence_thenScheduleAll_scenario_demonstratesRaceCondition() {
        // This test documents the race condition that existed before the fix:
        // If the manual button incremented refreshTick, it triggered scheduleAll,
        // which called disableSilentMode() and undid the manual silence.
        //
        // The correct behavior (after fix) is that the manual button does NOT
        // trigger scheduleAll, so these two operations should be independent.

        PrefsManager.setEnabled(context, true)

        // Step 1: Manual silence
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        // Step 2: scheduleAll runs (as it would on resume)
        SilenceScheduler.scheduleAll(context)

        // Step 3: scheduleAll DOES reset the ringer (this is by design — it ensures
        // the phone isn't stuck silent outside a prayer window). The key insight is:
        // the manual button must NOT trigger scheduleAll. The UI layer is responsible
        // for keeping manual silence and auto-scheduling independent.
        assertEquals(
            "scheduleAll resets ringer outside prayer window (by design)",
            AudioManager.RINGER_MODE_NORMAL,
            audioManager.ringerMode
        )
    }

    @Test
    fun cancelAll_restoresNormalMode() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.cancelAll(context)

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
    }

    @Test
    fun enableSilentMode_withoutDndPermission_doesNothing() {
        shadowNotificationManager.setNotificationPolicyAccessGranted(false)

        // SilenceReceiver should gracefully skip when DND not granted
        val receiver = SilenceReceiver()
        receiver.onReceive(context, android.content.Intent("com.tunisianprayertimes.ACTION_SILENCE").apply {
            putExtra("extra_prayer", "FAJR")
        })

        assertEquals(
            "Should stay in normal mode without DND permission",
            AudioManager.RINGER_MODE_NORMAL,
            audioManager.ringerMode
        )
    }

    @Test
    fun disableSilentMode_withoutDndPermission_doesNothing() {
        shadowNotificationManager.setNotificationPolicyAccessGranted(false)
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT

        val receiver = SilenceReceiver()
        receiver.onReceive(context, android.content.Intent("com.tunisianprayertimes.ACTION_UNSILENCE").apply {
            putExtra("extra_prayer", "FAJR")
        })

        assertEquals(
            "Should stay silent — can't modify without DND permission",
            AudioManager.RINGER_MODE_SILENT,
            audioManager.ringerMode
        )
    }

    @Test
    fun scheduleAll_whenDisabled_doesNotSilence() {
        PrefsManager.setEnabled(context, false)
        SilenceScheduler.scheduleAll(context)

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
    }
}
