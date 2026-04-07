package com.tunisianprayertimes

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object SilenceModeController {


    /**
     * Restores the interruption filter and ringer mode.
     *
     * On some devices (Pixel 6, etc.) [NotificationManager.setInterruptionFilter]
     * is processed asynchronously — a ringer-mode change made while the old DND
     * state is still in effect can be silently clamped to SILENT.  To handle
     * every device variant we:
     *  1. Set the ringer mode immediately (works when the filter call is sync).
     *  2. Clear / change the interruption filter.
     *  3. Post a second ringer-mode write so it runs after the filter update
     *     has been processed (handles the async case).
     */
    private fun restoreState(
        notificationManager: NotificationManager,
        audioManager: AudioManager,
        previousFilter: Int,
        previousRinger: Int
    ) {
        audioManager.ringerMode = previousRinger                       // (1)
        notificationManager.setInterruptionFilter(previousFilter)      // (2)
        try {
            Thread.sleep(150)                                          // Wait synchronously for OS to process filter
        } catch (e: InterruptedException) { }
        audioManager.ringerMode = previousRinger                       // (3)
    }

    fun enableAutoSilence(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) return false

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!PrefsManager.isAutoSilenceActive(context) && !PrefsManager.isManualSilenceActive(context)) {
            PrefsManager.clearCallReceivedDuringSilence(context)
        }
        if (PrefsManager.isManualSilenceActive(context)) {
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            return true
        }
        if (!PrefsManager.isAutoSilenceActive(context)) {
            PrefsManager.markAutoSilenceActive(
                context = context,
                previousRingerMode = audioManager.ringerMode,
                previousInterruptionFilter = notificationManager.currentInterruptionFilter
            )
        }

        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        return true
    }

    fun disableAutoSilence(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) return false
        if (PrefsManager.isManualSilenceActive(context)) return false
        if (!PrefsManager.isAutoSilenceActive(context)) return false

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        restoreState(
            notificationManager, audioManager,
            PrefsManager.getAutoSilencePreviousInterruptionFilter(context),
            PrefsManager.getAutoSilencePreviousRingerMode(context)
        )
        PrefsManager.clearAutoSilenceState(context)
        return true
    }

    fun setManualSilent(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) return false

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!PrefsManager.isManualSilenceActive(context)) {
            PrefsManager.clearCallReceivedDuringSilence(context)
        }
        val previousRingerMode = if (PrefsManager.isAutoSilenceActive(context)) {
            PrefsManager.getAutoSilencePreviousRingerMode(context)
        } else {
            audioManager.ringerMode
        }
        val previousInterruptionFilter = if (PrefsManager.isAutoSilenceActive(context)) {
            PrefsManager.getAutoSilencePreviousInterruptionFilter(context)
        } else {
            notificationManager.currentInterruptionFilter
        }

        PrefsManager.clearAutoSilenceState(context)
        PrefsManager.clearManualSilenceEndsAt(context)
        if (!PrefsManager.isManualSilenceActive(context)) {
            PrefsManager.markManualSilenceActive(
                context = context,
                previousRingerMode = previousRingerMode,
                previousInterruptionFilter = previousInterruptionFilter
            )
        }

        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        return true
    }

    fun setManualNormal(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) return false

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        ManualSilenceScheduler.cancel(context)

        if (PrefsManager.isManualSilenceActive(context)) {
            restoreState(
                notificationManager, audioManager,
                PrefsManager.getManualSilencePreviousInterruptionFilter(context),
                PrefsManager.getManualSilencePreviousRingerMode(context)
            )
        } else {
            restoreState(
                notificationManager, audioManager,
                NotificationManager.INTERRUPTION_FILTER_ALL,
                AudioManager.RINGER_MODE_NORMAL
            )
        }

        PrefsManager.clearManualSilenceState(context)
        PrefsManager.clearAutoSilenceState(context)
        return true
    }

    /**
     * Unconditionally forces normal mode and clears all silence state.
     * Used as a safety net by the alarm receiver when the auto-silence flag
     * was already cleared but the phone may still be in DND.
     */
    fun forceNormalMode(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) return false

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        ManualSilenceScheduler.cancel(context)
        restoreState(
            notificationManager, audioManager,
            NotificationManager.INTERRUPTION_FILTER_ALL,
            AudioManager.RINGER_MODE_NORMAL
        )
        PrefsManager.clearAutoSilenceState(context)
        PrefsManager.clearManualSilenceState(context)
        return true
    }

    /**
     * One-shot end-of-silence alert: consume the missed-call flag and vibrate
     * only when the user enabled this behavior.
     */
    fun notifyIfMissedCallDuringSilence(context: Context) {
        val hadCallDuringSilence = PrefsManager.consumeCallReceivedDuringSilence(context)
        if (hadCallDuringSilence && PrefsManager.isCallEndVibrationEnabled(context)) {
            vibrateEndOfSilence(context)
        }
    }

    /**
     * Vibrates the phone briefly to alert the user that the silence period
     * has ended and they may have missed notifications or calls.
     */
    fun vibrateEndOfSilence(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return

        // Two short pulses: 300ms vibrate, 200ms pause, 300ms vibrate
        val pattern = longArrayOf(0, 300, 200, 300)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(pattern, -1)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}