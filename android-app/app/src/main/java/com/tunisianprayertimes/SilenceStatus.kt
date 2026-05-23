package com.tunisianprayertimes

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import com.tunisianprayertimes.wake.WakeAlarmScheduler

object SilenceStatus {
    fun isPhoneSilenced(context: Context): Boolean {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val dndSilenced = notificationManager.currentInterruptionFilter ==
            NotificationManager.INTERRUPTION_FILTER_NONE
        val ringerSilenced = audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT

        return dndSilenced || ringerSilenced
    }

    fun isAppControlledSilenceActive(context: Context): Boolean {
        val hasAppSilenceFlag = PrefsManager.isAutoSilenceActive(context) ||
            PrefsManager.isManualSilenceActive(context) ||
            WakeAlarmScheduler.isSilenceUntilAlarmActive(context)
        if (!hasAppSilenceFlag) return false

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
    }
}