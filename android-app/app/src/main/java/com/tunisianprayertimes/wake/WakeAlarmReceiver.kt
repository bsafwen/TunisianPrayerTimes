package com.tunisianprayertimes.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val payload = intent.toWakeTriggerPayload() ?: return

        ContextCompat.startForegroundService(
            context,
            WakePlaybackService.playbackIntent(context, payload),
        )

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WakeAlarmScheduler.scheduleAll(context)
            } catch (error: Exception) {
                Log.w(TAG, "Failed to reschedule wake alarms after trigger", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "WakeAlarmReceiver"
    }
}