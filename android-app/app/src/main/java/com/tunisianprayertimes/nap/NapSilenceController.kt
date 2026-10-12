package com.tunisianprayertimes.nap

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.PrefsManager

object NapSilenceController {

    fun enableNapSilence(context: Context): Boolean {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) return false

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Save current state before silencing. A manual or prayer silence has already silenced
        // the phone, so keep the state it saved before it started.
        val previousRingerMode = when {
            PrefsManager.isManualSilenceActive(context) -> PrefsManager.getManualSilencePreviousRingerMode(context)
            PrefsManager.isAutoSilenceActive(context) -> PrefsManager.getAutoSilencePreviousRingerMode(context)
            else -> audioManager.ringerMode
        }
        val previousInterruptionFilter = when {
            PrefsManager.isManualSilenceActive(context) -> PrefsManager.getManualSilencePreviousInterruptionFilter(context)
            PrefsManager.isAutoSilenceActive(context) -> PrefsManager.getAutoSilencePreviousInterruptionFilter(context)
            else -> notificationManager.currentInterruptionFilter
        }
        NapPrefs.savePreviousAudioState(context, previousRingerMode, previousInterruptionFilter)

        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        AnalyticsTracker.phoneSilenced(context, source = "wake_alarm")
        return true
    }

    /**
     * Keeps the phone silenced for a pending alarm when the manual or prayer silence that owned it
     * ends. The state that silence saved before it started is restored when the alarm rings.
     */
    fun takeOverSilence(context: Context, previousRingerMode: Int, previousInterruptionFilter: Int): Boolean {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) return false

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        NapPrefs.savePreviousAudioState(context, previousRingerMode, previousInterruptionFilter)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        return true
    }

    fun disableNapSilence(context: Context): Boolean {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) return false

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val previousRinger = NapPrefs.getPreviousRingerMode(context)
        val previousFilter = NapPrefs.getPreviousInterruptionFilter(context)

        // Restore in the same order as SilenceModeController to handle async DND
        audioManager.ringerMode = previousRinger
        notificationManager.setInterruptionFilter(previousFilter)
        try {
            Thread.sleep(150)
        } catch (_: InterruptedException) {}
        audioManager.ringerMode = previousRinger

        return true
    }
}
