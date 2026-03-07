package com.tunisianprayertimes

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log

class SilenceReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SilenceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received action: $action")

        when (action) {
            "com.tunisianprayertimes.ACTION_SILENCE" -> {
                val prayerName = intent.getStringExtra("extra_prayer") ?: "UNKNOWN"
                Log.d(TAG, "Silencing phone for $prayerName")
                enableSilentMode(context)
            }
            "com.tunisianprayertimes.ACTION_UNSILENCE" -> {
                val prayerName = intent.getStringExtra("extra_prayer") ?: "UNKNOWN"
                Log.d(TAG, "Restoring normal mode after $prayerName")
                disableSilentMode(context)
            }
            "com.tunisianprayertimes.ACTION_RESCHEDULE",
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.MY_PACKAGE_REPLACED" -> {
                Log.d(TAG, "Rescheduling alarms")
                SilenceScheduler.scheduleAll(context)
            }
        }
    }

    private fun enableSilentMode(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.isNotificationPolicyAccessGranted) {
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        }
    }

    private fun disableSilentMode(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.isNotificationPolicyAccessGranted) {
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        }
    }
}
