package com.tunisianprayertimes

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager

object SilenceModeController {

    fun enableAutoSilence(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) return false

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
        if (!PrefsManager.isAutoSilenceActive(context)) return false

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager.setInterruptionFilter(PrefsManager.getAutoSilencePreviousInterruptionFilter(context))
        audioManager.ringerMode = PrefsManager.getAutoSilencePreviousRingerMode(context)
        PrefsManager.clearAutoSilenceState(context)
        return true
    }

    fun setManualSilent(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) return false

        PrefsManager.clearAutoSilenceState(context)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        return true
    }

    fun setManualNormal(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) return false

        PrefsManager.clearAutoSilenceState(context)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        return true
    }
}