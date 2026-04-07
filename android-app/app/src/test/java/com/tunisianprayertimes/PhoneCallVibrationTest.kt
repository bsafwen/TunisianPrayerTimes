package com.tunisianprayertimes

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Tests for the call-based end-of-silence vibration feature.
 *
 * Covers three distinct concerns:
 *
 *  1. [PhoneStateReceiver] correctly detects incoming calls and sets/skips the
 *     call flag based on silence state.
 *
 *  2. [SilenceReceiver] correctly consumes the call flag on unsilence, and
 *     respects the user's vibration-enabled toggle.
 *
 *  3. Fresh silence sessions always start with a clean call flag, preventing
 *     stale flags from previous windows from triggering spurious vibrations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PhoneCallVibrationTest {

    private lateinit var context: Context
    private val phoneReceiver = PhoneStateReceiver()
    private val silenceReceiver = SilenceReceiver()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Shadows.shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL

        // READ_PHONE_STATE is a dangerous permission and must be explicitly granted
        // in Robolectric even when declared in the manifest.
        Shadows.shadowOf(RuntimeEnvironment.getApplication() as Application)
            .grantPermissions(Manifest.permission.READ_PHONE_STATE)

        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun ringtingIntent() =
        Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
            putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_RINGING)
        }

    private fun idleIntent() =
        Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
            putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_IDLE)
        }

    private fun silenceIntent(prayer: String = "ISHA") =
        Intent("com.tunisianprayertimes.ACTION_SILENCE").apply {
            putExtra("extra_prayer", prayer)
        }

    private fun unsilenceIntent(prayer: String = "ISHA") =
        Intent("com.tunisianprayertimes.ACTION_UNSILENCE").apply {
            putExtra("extra_prayer", prayer)
        }

    // ── 1. PhoneStateReceiver: flag is set only when the right conditions hold ──

    @Test
    fun ringing_duringAutoSilence_setsCallFlag() {
        silenceReceiver.onReceive(context, silenceIntent())
        assertTrue(PrefsManager.isAutoSilenceActive(context))

        phoneReceiver.onReceive(context, ringtingIntent())

        assertTrue(
            "RINGING during auto-silence must set the call flag",
            PrefsManager.consumeCallReceivedDuringSilence(context)
        )
    }

    @Test
    fun ringing_duringManualSilence_setsCallFlag() {
        SilenceModeController.setManualSilent(context)
        assertTrue(PrefsManager.isManualSilenceActive(context))

        phoneReceiver.onReceive(context, ringtingIntent())

        assertTrue(
            "RINGING during manual silence must set the call flag",
            PrefsManager.consumeCallReceivedDuringSilence(context)
        )
    }

    @Test
    fun ringing_whenNoSilenceActive_doesNotSetCallFlag() {
        assertFalse(PrefsManager.isAutoSilenceActive(context))
        assertFalse(PrefsManager.isManualSilenceActive(context))

        phoneReceiver.onReceive(context, ringtingIntent())

        assertFalse(
            "RINGING outside any silence window must not set the call flag",
            PrefsManager.consumeCallReceivedDuringSilence(context)
        )
    }

    @Test
    fun nonRingingStateChange_duringAutoSilence_doesNotSetCallFlag() {
        silenceReceiver.onReceive(context, silenceIntent())
        assertTrue(PrefsManager.isAutoSilenceActive(context))

        // IDLE and OFFHOOK transitions must be ignored – only RINGING matters
        phoneReceiver.onReceive(context, idleIntent())

        assertFalse(
            "Non-RINGING phone state change must not set the call flag",
            PrefsManager.consumeCallReceivedDuringSilence(context)
        )
    }

    // ── 2. SilenceReceiver: vibration gating based on flag and user toggle ──

    @Test
    fun autoUnsilence_withCallFlag_vibrationEnabled_consumesFlag() {
        PrefsManager.setCallEndVibrationEnabled(context, true)
        silenceReceiver.onReceive(context, silenceIntent())
        PrefsManager.markCallReceivedDuringSilence(context)

        silenceReceiver.onReceive(context, unsilenceIntent())

        assertFalse(
            "Auto-unsilence must consume the call flag when vibration is enabled",
            PrefsManager.consumeCallReceivedDuringSilence(context)
        )
    }

    /**
     * When the user disables call-end vibration the flag must still be consumed.
     * If it were not consumed here, the next silence session would find a stale
     * flag (from this window) and behave incorrectly.
     *
     * The real-world consequence: a spurious vibration two sessions later.
     */
    @Test
    fun autoUnsilence_vibrationDisabled_flagIsStillConsumed() {
        PrefsManager.setCallEndVibrationEnabled(context, false)
        silenceReceiver.onReceive(context, silenceIntent())
        PrefsManager.markCallReceivedDuringSilence(context)

        silenceReceiver.onReceive(context, unsilenceIntent())

        assertFalse(
            "Auto-unsilence must consume the call flag even when vibration is disabled",
            PrefsManager.consumeCallReceivedDuringSilence(context)
        )
    }

    @Test
    fun manualUnsilence_withCallFlag_vibrationEnabled_consumesFlag() {
        // Prevent resumeAutoSilenceIfPossible from calling scheduleAll, which
        // can call enableAutoSilence and clear the call flag before the receiver
        // reads it.
        PrefsManager.setEnabled(context, false)
        PrefsManager.setCallEndVibrationEnabled(context, true)

        SilenceModeController.setManualSilent(context)
        ManualSilenceScheduler.schedule(context, 1)
        PrefsManager.markCallReceivedDuringSilence(context)

        silenceReceiver.onReceive(context, Intent(ManualSilenceScheduler.ACTION_MANUAL_UNSILENCE))

        assertFalse(
            "Manual-unsilence must consume the call flag when vibration is enabled",
            PrefsManager.consumeCallReceivedDuringSilence(context)
        )
    }

    @Test
    fun manualUnsilence_vibrationDisabled_flagIsStillConsumed() {
        PrefsManager.setEnabled(context, false)
        PrefsManager.setCallEndVibrationEnabled(context, false)

        SilenceModeController.setManualSilent(context)
        ManualSilenceScheduler.schedule(context, 1)
        PrefsManager.markCallReceivedDuringSilence(context)

        silenceReceiver.onReceive(context, Intent(ManualSilenceScheduler.ACTION_MANUAL_UNSILENCE))

        assertFalse(
            "Manual-unsilence must consume the call flag even when vibration is disabled",
            PrefsManager.consumeCallReceivedDuringSilence(context)
        )
    }

    // ── 3. Fresh session always starts with a clean flag ──────────────────────

    /**
     * A stale call flag left over from a previous (possibly missed) silence
     * window must be wiped when a fresh auto-silence session begins.
     * Otherwise the unsilence at the end of the new session would fire an
     * incorrect vibration for the old call.
     */
    @Test
    fun enableAutoSilence_clearsStaleFlagFromPreviousSession() {
        PrefsManager.markCallReceivedDuringSilence(context) // leftover from previous window

        // New auto-silence session starts (phone was in normal mode, no active window)
        silenceReceiver.onReceive(context, silenceIntent())

        assertFalse(
            "enableAutoSilence must clear a stale call flag from the previous session",
            PrefsManager.consumeCallReceivedDuringSilence(context)
        )
    }

    /**
     * Same guarantee for manual silence: starting a fresh timed manual silence
     * must wipe any leftover call flag so the end-of-silence vibration is only
     * triggered by calls that arrived in *this* window.
     */
    @Test
    fun setManualSilent_clearsStaleFlagFromPreviousSession() {
        PrefsManager.markCallReceivedDuringSilence(context)

        SilenceModeController.setManualSilent(context)

        assertFalse(
            "setManualSilent must clear a stale call flag from the previous session",
            PrefsManager.consumeCallReceivedDuringSilence(context)
        )
    }

    // ── 4. Default setting ────────────────────────────────────────────────────

    @Test
    fun callEndVibrationEnabled_defaultsToTrue() {
        // Fresh prefs (cleared in setup) — must be true by default so the
        // feature works out-of-the-box without any user action.
        assertTrue(
            "Call-end vibration must be enabled by default on fresh install",
            PrefsManager.isCallEndVibrationEnabled(context)
        )
    }
}
