package com.tunisianprayertimes

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
        SilenceModeController.setManualSilent(context)

        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertFalse(PrefsManager.isAutoSilenceActive(context))
    }

    @Test
    fun autoSilence_thenScheduleAll_outsidePrayerWindow_restoresPreviousState() {
        SilenceModeController.enableAutoSilence(context)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        // Use a non-existent delegation so todayTimes is null, which guarantees
        // we are "outside" any prayer window regardless of current time/timezone.
        PrefsManager.setDelegationId(context, 99999)
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)

        // scheduleAll sees auto-silence flag is set (missed unsilence) and
        // restores to the captured previous state.
        assertEquals(
            "scheduleAll should restore the pre-auto-silence mode when flag is set",
            AudioManager.RINGER_MODE_NORMAL,
            audioManager.ringerMode
        )
        assertFalse(PrefsManager.isAutoSilenceActive(context))
    }

    @Test
    fun manualSilence_thenScheduleAll_preservesManualSilentState() {
        PrefsManager.setDelegationId(context, 99999)
        PrefsManager.setEnabled(context, true)

        SilenceModeController.setManualSilent(context)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        SilenceScheduler.scheduleAll(context)

        assertEquals(
            "scheduleAll should not undo manual silent mode outside a prayer window",
            AudioManager.RINGER_MODE_SILENT,
            audioManager.ringerMode
        )
        assertFalse(PrefsManager.isAutoSilenceActive(context))
    }

    @Test
    fun cancelAll_restoresPreviousStateOnlyForAutoSilence() {
        SilenceModeController.enableAutoSilence(context)
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.cancelAll(context)

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertFalse(PrefsManager.isAutoSilenceActive(context))
    }

    // ==================== Regression: DND not cancelled ====================

    /**
     * Manual silence must now take priority over stale prayer unsilence alarms.
     */
    @Test
    fun unsilenceAlarm_afterManualSilenceOverride_keepsManualSilentMode() {
        // 1. Auto-silence alarm fires for Isha
        val receiver = SilenceReceiver()
        receiver.onReceive(context, android.content.Intent("com.tunisianprayertimes.ACTION_SILENCE").apply {
            putExtra("extra_prayer", "ISHA")
        })
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertTrue(PrefsManager.isAutoSilenceActive(context))

        // 2. User taps manual silence button (clears auto-silence tracking)
        SilenceModeController.setManualSilent(context)
        assertFalse("Manual silence clears auto-silence flag", PrefsManager.isAutoSilenceActive(context))

        // 3. Unsilence alarm fires — manual silence must still win
        receiver.onReceive(context, android.content.Intent("com.tunisianprayertimes.ACTION_UNSILENCE").apply {
            putExtra("extra_prayer", "ISHA")
        })

        assertEquals(
            "Manual silence must survive stale auto-unsilence alarms",
            AudioManager.RINGER_MODE_SILENT,
            audioManager.ringerMode
        )
        assertTrue(PrefsManager.isManualSilenceActive(context))
    }

    /**
     * Reproduces the bug where a missed unsilence from a previous prayer
     * causes the next prayer's unsilence to restore to DND instead of NORMAL.
     *
     * Timeline:
     * 1. Maghreb silence → captures previous=NORMAL, sets DND
     * 2. Maghreb unsilence alarm MISSED (OEM killed it)
     * 3. Phone stays in DND with auto-silence still active
     * 4. Isha silence fires → isAutoSilenceActive=true → skips capture → sets DND (no-op)
     * 5. Isha unsilence fires → restores "previous" which is NORMAL from step 1
     *
     * This scenario should work correctly — verify it does.
     */
    @Test
    fun missedUnsilence_nextPrayerStillRestoresToOriginalNormalMode() {
        val receiver = SilenceReceiver()

        // 1. Maghreb silence fires — phone was NORMAL
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        receiver.onReceive(context, android.content.Intent("com.tunisianprayertimes.ACTION_SILENCE").apply {
            putExtra("extra_prayer", "MAGHREB")
        })
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        // 2. Maghreb unsilence MISSED — phone stays silent, flag stays active
        // (we just don't call the unsilence receiver)

        // 3. Isha silence fires — auto-silence already active, should NOT overwrite captured state
        receiver.onReceive(context, android.content.Intent("com.tunisianprayertimes.ACTION_SILENCE").apply {
            putExtra("extra_prayer", "ISHA")
        })
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertTrue(PrefsManager.isAutoSilenceActive(context))

        // 4. Isha unsilence fires — should restore to the ORIGINAL normal mode from step 1
        receiver.onReceive(context, android.content.Intent("com.tunisianprayertimes.ACTION_UNSILENCE").apply {
            putExtra("extra_prayer", "ISHA")
        })

        assertEquals(
            "Must restore to original NORMAL mode captured before the first silence",
            AudioManager.RINGER_MODE_NORMAL,
            audioManager.ringerMode
        )
        assertFalse(PrefsManager.isAutoSilenceActive(context))
    }

    /**
     * Reproduces the core bug: disableAutoSilence is gated on isAutoSilenceActive.
     * If the flag is false, the SilenceReceiver's ACTION_UNSILENCE must still
     * force normal mode as a safety net.
     */
    @Test
    fun unsilenceAlarm_withClearedFlag_mustStillForceNormalMode() {
        // Simulate: phone is in DND from a silence alarm
        SilenceModeController.enableAutoSilence(context)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        // Flag gets cleared by some path (manual toggle, scheduleAll, etc.)
        PrefsManager.clearAutoSilenceState(context)
        assertFalse(PrefsManager.isAutoSilenceActive(context))

        // Phone is still physically in DND/silent
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        // Unsilence alarm fires via SilenceReceiver — must force normal via safety net
        val receiver = SilenceReceiver()
        receiver.onReceive(context, android.content.Intent("com.tunisianprayertimes.ACTION_UNSILENCE").apply {
            putExtra("extra_prayer", "ISHA")
        })

        assertEquals(
            "ACTION_UNSILENCE must force normal mode even when auto-silence flag is already cleared",
            AudioManager.RINGER_MODE_NORMAL,
            audioManager.ringerMode
        )
    }

    @Test
    fun autoSilence_restoresPreviouslySilentState() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        SilenceModeController.enableAutoSilence(context)
        SilenceModeController.disableAutoSilence(context)

        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notificationManager.currentInterruptionFilter)
    }

    @Test
    fun autoSilence_restoresPreviousVibrateMode() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE

        SilenceModeController.enableAutoSilence(context)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        SilenceModeController.disableAutoSilence(context)
        assertEquals(
            "Should restore to vibrate mode",
            AudioManager.RINGER_MODE_VIBRATE,
            audioManager.ringerMode
        )
    }

    /**
     * User already has DND enabled manually before any prayer window.
     * The app should capture that state, apply its own DND during prayer,
     * and restore the user's manual DND when the prayer ends.
     */
    @Test
    fun autoSilence_whenUserAlreadyHasDndEnabled_restoresUserDnd() {
        // User manually set DND + silent before any prayer
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)

        // Prayer time comes in — alarm fires via SilenceReceiver
        val receiver = SilenceReceiver()
        receiver.onReceive(context, android.content.Intent("com.tunisianprayertimes.ACTION_SILENCE").apply {
            putExtra("extra_prayer", "ISHA")
        })

        // App captures previous state (DND + silent) and enables its own DND
        assertTrue(PrefsManager.isAutoSilenceActive(context))
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notificationManager.currentInterruptionFilter)

        // Prayer time goes out — unsilence alarm fires
        receiver.onReceive(context, android.content.Intent("com.tunisianprayertimes.ACTION_UNSILENCE").apply {
            putExtra("extra_prayer", "ISHA")
        })

        // App must restore the user's previous manual DND — NOT force normal mode
        assertEquals(
            "Phone must keep user's previous silent ringer mode",
            AudioManager.RINGER_MODE_SILENT,
            audioManager.ringerMode
        )
        assertEquals(
            "Phone must keep user's previous DND interruption filter",
            NotificationManager.INTERRUPTION_FILTER_NONE,
            notificationManager.currentInterruptionFilter
        )
        assertFalse(PrefsManager.isAutoSilenceActive(context))
    }

    // ==================== Manual toggle on already-silenced phone ====================

    /**
     * Reproduces the bug: phone is already silenced, user taps the manual
     * silence button (toggle ON), then taps again (toggle OFF).
     * The phone must return to its previous silenced state, NOT normal.
     */
    @Test
    fun phoneSilenced_manualToggleOnThenOff_restoresSilenced() {
        // Phone starts in silent mode (user set it outside the app)
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT

        // User taps manual silence button — "enable app silence"
        SilenceModeController.setManualSilent(context)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        // User taps again — "disable app silence"
        SilenceModeController.setManualNormal(context)

        assertEquals(
            "Phone must return to its previous silent state, not forced normal",
            AudioManager.RINGER_MODE_SILENT,
            audioManager.ringerMode
        )
    }

    /**
     * Same scenario but starting from vibrate mode.
     */
    @Test
    fun phoneVibrate_manualToggleOnThenOff_restoresVibrate() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE

        SilenceModeController.setManualSilent(context)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        SilenceModeController.setManualNormal(context)

        assertEquals(
            "Phone must return to its previous vibrate state",
            AudioManager.RINGER_MODE_VIBRATE,
            audioManager.ringerMode
        )
    }

    /**
     * Starting from normal mode — setManualNormal should still go to normal.
     */
    @Test
    fun phoneNormal_manualToggleOnThenOff_restoresNormal() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL

        SilenceModeController.setManualSilent(context)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        SilenceModeController.setManualNormal(context)

        assertEquals(
            "Phone should be back to normal",
            AudioManager.RINGER_MODE_NORMAL,
            audioManager.ringerMode
        )
    }

    @Test
    fun manualSilence_startedDuringAutoWindow_restoresOriginalPreAutoState() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        SilenceModeController.enableAutoSilence(context)
        assertTrue(PrefsManager.isAutoSilenceActive(context))

        SilenceModeController.setManualSilent(context)
        assertFalse(PrefsManager.isAutoSilenceActive(context))

        SilenceModeController.setManualNormal(context)

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notificationManager.currentInterruptionFilter)
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
