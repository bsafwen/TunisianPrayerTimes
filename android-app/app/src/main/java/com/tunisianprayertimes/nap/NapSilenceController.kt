package com.tunisianprayertimes.nap

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import com.tunisianprayertimes.AnalyticsTracker

object NapSilenceController {

    fun enableNapSilence(context: Context): Boolean {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) return false

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Save current state before silencing
        NapPrefs.savePreviousAudioState(
            context,
            audioManager.ringerMode,
            notificationManager.currentInterruptionFilter
        )

        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        AnalyticsTracker.phoneSilenced(context, source = "wake_alarm")
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
