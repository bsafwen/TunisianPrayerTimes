package com.tunisianprayertimes

import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.mosque.SilenceCoordinator
import com.tunisianprayertimes.mosque.MosqueSilenceMonitor

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
        if (MosqueSilenceSettings.usesOwnedRules(context)) {
            return SilenceCoordinator.anyActive(context) && context.getSystemService(NotificationManager::class.java)
                .currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
        }
        val hasAppSilenceFlag = PrefsManager.isAutoSilenceActive(context) ||
            PrefsManager.isManualSilenceActive(context) ||
            WakeAlarmScheduler.isSilenceUntilAlarmActive(context)
        if (!hasAppSilenceFlag) return false

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
    }

    /**
     * Best-effort end of the app-managed silence currently in effect, or null
     * when the end is unknown (silence until stopped, wake-until-alarm, or a
     * stale state that the repair path will reconcile).
     */
    fun appSilenceEndsAt(context: Context, nowMillis: Long = System.currentTimeMillis()): Long? {
        if (!isAppControlledSilenceActive(context)) return null
        if (PrefsManager.isManualSilenceActive(context)) {
            val endsAt = PrefsManager.getManualSilenceEndsAtMillis(context)
            return endsAt.takeIf { it > nowMillis }
        }
        return if (PrefsManager.isAutoSilenceActive(context)) {
            if (MosqueSilenceSettings.combined(context)) MosqueSilenceMonitor.endWall(context)
            else SilenceScheduler.currentSilenceWindowEnd(context)
        } else {
            // Wake-alarm silence lasts until its alarm rings or is removed. A
            // nearby prayer window does not describe the end of that silence.
            null
        }
    }
}
