package com.tunisianprayertimes.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.tunisianprayertimes.nap.NapSilenceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val payload = intent.toWakeTriggerPayload() ?: return
        Log.d("WakeFlow", "WakeAlarmReceiver.onReceive eventId=${payload.eventId}")

        // If this alarm had silence activated, restore audio state before playing the wake-up alarm
        val alarmId = wakeAlarmIdFromEventId(payload.eventId)
        if (alarmId != null && WakeAlarmScheduler.isSilencedAlarm(context, alarmId)) {
            Log.d("WakeFlow", "Silenced alarm fired — restoring audio state")
            NapSilenceController.disableNapSilence(context)
            WakeAlarmScheduler.clearSilencedAlarmId(context)
        }

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