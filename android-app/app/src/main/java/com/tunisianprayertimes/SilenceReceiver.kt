package com.tunisianprayertimes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
                SilenceModeController.enableAutoSilence(context)
                // Update delegation from cached location in the background,
                // so the next reschedule uses the correct prayer times.
                if (PrefsManager.isAutoLocationUpdateEnabled(context)) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            if (DelegationLocator.updateDelegationFromLastLocation(context)) {
                                Log.d(TAG, "Delegation updated at prayer $prayerName, rescheduling")
                                SilenceScheduler.scheduleAll(context)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update delegation", e)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
            "com.tunisianprayertimes.ACTION_UNSILENCE" -> {
                val prayerName = intent.getStringExtra("extra_prayer") ?: "UNKNOWN"
                Log.d(TAG, "Restoring normal mode after $prayerName")
                if (PrefsManager.isManualSilenceActive(context)) {
                    Log.d(TAG, "Manual silence is active, skipping auto unsilence")
                    PrefsManager.clearAutoSilenceState(context)
                    return
                }
                if (!SilenceModeController.disableAutoSilence(context)) {
                    // Flag was already cleared (manual toggle, scheduleAll, etc.)
                    // but the alarm still fired — force normal so DND is never
                    // left on permanently.
                    Log.d(TAG, "Auto-silence flag already cleared, forcing normal mode")
                    SilenceModeController.forceNormalMode(context)
                }
                SilenceModeController.notifyIfMissedCallDuringSilence(context)
            }
            ManualSilenceScheduler.ACTION_MANUAL_UNSILENCE -> {
                Log.d(TAG, "Manual silence timer expired")
                ManualSilenceScheduler.onTimerExpired(context)
                SilenceModeController.notifyIfMissedCallDuringSilence(context)
            }
            "com.tunisianprayertimes.ACTION_RESCHEDULE" -> {
                Log.d(TAG, "Rescheduling alarms")
                SilenceScheduler.scheduleAll(context)
            }
        }
    }
}
