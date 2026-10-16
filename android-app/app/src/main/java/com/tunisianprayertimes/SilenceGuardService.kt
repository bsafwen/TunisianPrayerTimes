package com.tunisianprayertimes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Lightweight foreground service that keeps the app process alive during an
 * active auto-silence window so the restore alarm is less likely to be missed.
 *
 * On aggressive OEM ROMs (Honor MagicOS, Xiaomi MIUI, Huawei EMUI, etc.)
 * the system may kill app processes even when setAlarmClock() alarms are
 * pending. Keeping the guard scoped to active auto-silence avoids an all-day
 * foreground service while still protecting the most costly failure: DND not
 * being restored at the end of a prayer window.
 */
class SilenceGuardService : Service() {

    companion object {
        private const val TAG = "SilenceGuardService"
        private const val CHANNEL_ID = "silence_guard"
        private const val NOTIFICATION_ID = 7001

        private val lock = Any()

        /** Starts asked for and not yet delivered to onStartCommand, which runs after onCreate's startForeground. */
        private var pendingStarts = 0

        fun start(context: Context) {
            if (!shouldRun(context)) return
            val intent = Intent(context, SilenceGuardService::class.java)
            synchronized(lock) {
                pendingStarts++
                try {
                    context.startForegroundService(intent)
                } catch (e: Exception) {
                    pendingStarts--
                    Log.w(TAG, "Failed to start foreground service", e)
                }
            }
        }

        /**
         * Android crashes the app when a service started with startForegroundService is stopped before it calls
         * startForeground (ForegroundServiceDidNotStartInTimeException), as two schedule runs a moment apart can do
         * (a place change and the refresh of a zone change). While a start is under way the service decides in
         * onStartCommand instead, stopping itself when it is no longer needed.
         */
        fun stop(context: Context) {
            synchronized(lock) {
                if (pendingStarts > 0) return
                context.stopService(Intent(context, SilenceGuardService::class.java))
            }
        }

        fun stopIfNotNeeded(context: Context) {
            if (!shouldRun(context)) {
                stop(context)
            }
        }

        private fun shouldRun(context: Context): Boolean =
            PrefsManager.isEnabled(context) && PrefsManager.isAutoSilenceActive(context)
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        Log.d(TAG, "Guard service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lock) { if (pendingStarts > 0) pendingStarts-- }
        if (!shouldRun(this)) {
            // Only this start's stop: a newer start under way keeps the service until its own onStartCommand.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.guard_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.guard_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.mosque_silhouette)
            .setContentTitle(getString(R.string.guard_notification_title))
            .setContentText(getString(R.string.guard_notification_text))
            .setContentIntent(MainActivityPendingIntents.silenceGuard(this))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
