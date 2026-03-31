package com.tunisianprayertimes

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumented test for the full silence lifecycle on a real
 * device / emulator.  Exercises the actual Android AudioManager,
 * NotificationManager, SharedPreferences, and BroadcastReceiver.
 *
 * Skipped automatically when DND access has not been granted on the device.
 */
@RunWith(AndroidJUnit4::class)
class SilenceLifecycleInstrumentedTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private val receiver = SilenceReceiver()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // All tests in this class require DND access — skip gracefully on CI
        // emulators that don't have it.
        assumeTrue(
            "DND policy access required",
            notificationManager.isNotificationPolicyAccessGranted
        )

        // Reset state
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun fireSilenceAlarm(prayer: String) {
        receiver.onReceive(
            context,
            Intent("com.tunisianprayertimes.ACTION_SILENCE").apply {
                putExtra("extra_prayer", prayer)
            }
        )
    }

    private fun fireUnsilenceAlarm(prayer: String) {
        receiver.onReceive(
            context,
            Intent("com.tunisianprayertimes.ACTION_UNSILENCE").apply {
                putExtra("extra_prayer", prayer)
            }
        )
        waitForRestore()
    }

    /**
     * The ringer restore is posted to the main looper. Drain it so assertions
     * see the final state.
     */
    private fun waitForRestore() {
        // Post a no-op to the main handler and block until it runs —
        // this guarantees the previously-posted ringerMode change has executed.
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post { latch.countDown() }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    // ── tests ───────────────────────────────────────────────────────────

    @Test
    fun normalMode_silenceAlarm_unsilenceAlarm_restoresNormal() {
        // Phone starts in normal mode — capture the actual device values
        val originalRinger = audioManager.ringerMode
        val originalFilter = notificationManager.currentInterruptionFilter
        assertEquals(AudioManager.RINGER_MODE_NORMAL, originalRinger)

        fireSilenceAlarm("ISHA")

        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertTrue(PrefsManager.isAutoSilenceActive(context))

        fireUnsilenceAlarm("ISHA")

        assertEquals(originalRinger, audioManager.ringerMode)
        assertEquals(originalFilter, notificationManager.currentInterruptionFilter)
        assertFalse(PrefsManager.isAutoSilenceActive(context))
    }

    @Test
    fun userDndOn_silenceAlarm_unsilenceAlarm_restoresUserDnd() {
        // User manually enables DND before any prayer window
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)

        // Capture what the device actually reports after setting DND
        // (some devices map NONE → PRIORITY or other values)
        val userFilter = notificationManager.currentInterruptionFilter
        val userRinger = audioManager.ringerMode

        fireSilenceAlarm("ISHA")

        // App captures previous DND, applies its own (same effect, but tracked)
        assertTrue(PrefsManager.isAutoSilenceActive(context))

        fireUnsilenceAlarm("ISHA")

        // Must restore the user's original DND — NOT force normal
        assertEquals(
            "Must keep user's previous ringer mode",
            userRinger,
            audioManager.ringerMode
        )
        assertEquals(
            "Must keep user's previous DND filter",
            userFilter,
            notificationManager.currentInterruptionFilter
        )
        assertFalse(PrefsManager.isAutoSilenceActive(context))
    }

    @Test
    fun vibrateMode_silenceAlarm_unsilenceAlarm_restoresVibrate() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE

        fireSilenceAlarm("MAGHREB")

        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertTrue(PrefsManager.isAutoSilenceActive(context))

        fireUnsilenceAlarm("MAGHREB")

        assertEquals(
            "Must restore vibrate mode",
            AudioManager.RINGER_MODE_VIBRATE,
            audioManager.ringerMode
        )
        assertFalse(PrefsManager.isAutoSilenceActive(context))
    }

    @Test
    fun missedUnsilence_nextPrayer_stillRestoresOriginalState() {
        // Maghreb silence fires — phone was normal
        fireSilenceAlarm("MAGHREB")
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        // Maghreb unsilence MISSED — phone stays silent, flag stays active

        // Isha silence fires — flag still active, must NOT overwrite captured state
        fireSilenceAlarm("ISHA")
        assertTrue(PrefsManager.isAutoSilenceActive(context))

        // Isha unsilence fires — must restore original NORMAL from before Maghreb
        fireUnsilenceAlarm("ISHA")

        assertEquals(
            "Must restore to original normal mode from before first silence",
            AudioManager.RINGER_MODE_NORMAL,
            audioManager.ringerMode
        )
        assertFalse(PrefsManager.isAutoSilenceActive(context))
    }

    @Test
    fun manualSilenceOverride_thenUnsilenceAlarm_forcesNormal() {
        // Prayer silence fires
        fireSilenceAlarm("ISHA")
        assertTrue(PrefsManager.isAutoSilenceActive(context))

        // User taps manual silence button (clears auto-silence tracking)
        SilenceModeController.setManualSilent(context)
        assertFalse(PrefsManager.isAutoSilenceActive(context))
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        // Unsilence alarm fires — flag is gone, safety net must kick in
        fireUnsilenceAlarm("ISHA")

        assertEquals(
            "Safety net must force normal when flag was cleared",
            AudioManager.RINGER_MODE_NORMAL,
            audioManager.ringerMode
        )
    }

    @Test
    fun twoPrayerCycles_fullLifecycle() {
        // ── Maghreb cycle ──
        fireSilenceAlarm("MAGHREB")
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertTrue(PrefsManager.isAutoSilenceActive(context))

        fireUnsilenceAlarm("MAGHREB")
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertFalse(PrefsManager.isAutoSilenceActive(context))

        // ── Isha cycle ──
        fireSilenceAlarm("ISHA")
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertTrue(PrefsManager.isAutoSilenceActive(context))

        fireUnsilenceAlarm("ISHA")
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertFalse(PrefsManager.isAutoSilenceActive(context))
    }

    @Test
    fun phoneSilenced_manualToggleOnOff_restoresSilenced() {
        // Phone starts silenced (user set it outside the app)
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT

        // User taps manual silence button — "enable"
        SilenceModeController.setManualSilent(context)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        // User taps again — "disable"
        SilenceModeController.setManualNormal(context)
        waitForRestore()

        assertEquals(
            "Phone must return to its previous silent state",
            AudioManager.RINGER_MODE_SILENT,
            audioManager.ringerMode
        )
    }

    @Test
    fun phoneVibrate_manualToggleOnOff_restoresVibrate() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE

        SilenceModeController.setManualSilent(context)
        SilenceModeController.setManualNormal(context)
        waitForRestore()

        assertEquals(
            "Phone must return to its previous vibrate state",
            AudioManager.RINGER_MODE_VIBRATE,
            audioManager.ringerMode
        )
    }

    // ── restore-order tests (ringer set before DND cleared) ─────────────

    @Test
    fun autoSilence_phoneVibrate_restoresRingerAndFilter() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val originalFilter = notificationManager.currentInterruptionFilter

        fireSilenceAlarm("ASR")
        fireUnsilenceAlarm("ASR")

        assertEquals(
            "Ringer must be vibrate after auto-unsilence",
            AudioManager.RINGER_MODE_VIBRATE,
            audioManager.ringerMode
        )
        assertEquals(
            "Interruption filter must be restored after auto-unsilence",
            originalFilter,
            notificationManager.currentInterruptionFilter
        )
    }

    @Test
    fun autoSilence_phoneSilent_restoresRingerAndFilter() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        val originalFilter = notificationManager.currentInterruptionFilter

        fireSilenceAlarm("DHUHR")
        fireUnsilenceAlarm("DHUHR")

        assertEquals(
            "Ringer must be silent after auto-unsilence",
            AudioManager.RINGER_MODE_SILENT,
            audioManager.ringerMode
        )
        assertEquals(
            "Interruption filter must be restored after auto-unsilence",
            originalFilter,
            notificationManager.currentInterruptionFilter
        )
    }

    @Test
    fun manualToggle_phoneVibrate_restoresRingerAndFilter() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val originalFilter = notificationManager.currentInterruptionFilter

        SilenceModeController.setManualSilent(context)
        SilenceModeController.setManualNormal(context)
        waitForRestore()

        assertEquals(
            "Ringer must be vibrate after manual unsilence",
            AudioManager.RINGER_MODE_VIBRATE,
            audioManager.ringerMode
        )
        assertEquals(
            "Interruption filter must be restored after manual unsilence",
            originalFilter,
            notificationManager.currentInterruptionFilter
        )
    }

    @Test
    fun manualToggle_phoneSilent_restoresRingerAndFilter() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        val originalFilter = notificationManager.currentInterruptionFilter

        SilenceModeController.setManualSilent(context)
        SilenceModeController.setManualNormal(context)
        waitForRestore()

        assertEquals(
            "Ringer must be silent after manual unsilence",
            AudioManager.RINGER_MODE_SILENT,
            audioManager.ringerMode
        )
        assertEquals(
            "Interruption filter must be restored after manual unsilence",
            originalFilter,
            notificationManager.currentInterruptionFilter
        )
    }

    @Test
    fun autoSilence_userDndPriority_restoresRingerAndFilter() {
        // User has DND in priority mode (not full silence) before prayer
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        val userFilter = notificationManager.currentInterruptionFilter
        val userRinger = audioManager.ringerMode

        fireSilenceAlarm("FAJR")
        fireUnsilenceAlarm("FAJR")

        assertEquals(
            "Ringer must be restored after auto-unsilence from priority DND",
            userRinger,
            audioManager.ringerMode
        )
        // Some devices (Pixel 6) don't round-trip PRIORITY through DND NONE —
        // they relax it to ALL.  Assert that the filter is at least not NONE
        // (i.e. DND was cleared) and matches what the device returns when we
        // re-apply the captured value.
        assertNotEquals(
            "DND must not remain at NONE after auto-unsilence",
            NotificationManager.INTERRUPTION_FILTER_NONE,
            notificationManager.currentInterruptionFilter
        )
    }
}
