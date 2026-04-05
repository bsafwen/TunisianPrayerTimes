package com.tunisianprayertimes.platform

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager

actual object SilenceController {
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context =
        appContext ?: error("SilenceController not initialized. Call init(context) first.")

    actual fun isSilent(): Boolean {
        val audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT
    }

    actual fun enableSilence() {
        val ctx = requireContext()
        val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
    }

    actual fun disableSilence() {
        val ctx = requireContext()
        val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    actual fun hasPermission(): Boolean {
        val notificationManager = requireContext()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.isNotificationPolicyAccessGranted
    }
}
