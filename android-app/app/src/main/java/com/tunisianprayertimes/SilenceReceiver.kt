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
                // Check if the user already dismissed silence for this prayer.
                // A delegation change may have rescheduled this alarm to a slightly
                // later time; honour the user's prior dismissal.
                val dismissedPrayer = PrefsManager.getAutoSilenceDismissedPrayer(context)
                if (dismissedPrayer == prayerName) {
                    Log.d(TAG, "User already dismissed silence for $prayerName, skipping")
                    return
                }
                PrefsManager.clearAutoSilenceDismissed(context)
                SilenceModeController.enableAutoSilence(
                    context,
                    runCatching { Prayer.valueOf(prayerName) }.getOrNull(),
                )
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
                            pendingResult?.finish()
                        }
                    }
                }
            }
            "com.tunisianprayertimes.ACTION_UNSILENCE" -> {
                val prayerName = intent.getStringExtra("extra_prayer") ?: "UNKNOWN"
                Log.d(TAG, "Restoring normal mode after $prayerName")
                // Snapshot both flags once to avoid a race where manual silence
                // toggles on between the two reads, causing forceNormalMode()
                // to incorrectly clear the user's manual silence.
                val manualActive = PrefsManager.isManualSilenceActive(context)
                val autoActive = PrefsManager.isAutoSilenceActive(context)
                if (manualActive) {
                    Log.d(TAG, "Manual silence is active, skipping auto unsilence")
                    PrefsManager.clearAutoSilenceState(context)
                    SilenceGuardService.stopIfNotNeeded(context)
                    return
                }
                if (autoActive) {
                    SilenceModeController.disableAutoSilence(context)
                } else {
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
            "com.tunisianprayertimes.ACTION_DELEGATION_CHECK" -> {
                val prayerName = intent.getStringExtra("extra_prayer") ?: "UNKNOWN"
                Log.d(TAG, "Checking delegation before $prayerName")
                if (PrefsManager.isAutoLocationUpdateEnabled(context) && DelegationLocator.hasLocationPermission(context)) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            checkDelegationBeforePrayer(context, prayerName)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update delegation before $prayerName", e)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
            "com.tunisianprayertimes.ACTION_RESCHEDULE" -> {
                Log.d(TAG, "Rescheduling alarms")
                if (PrefsManager.isAutoLocationUpdateEnabled(context) && DelegationLocator.hasLocationPermission(context)) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            if (DelegationLocator.updateDelegationFromLastLocation(context)) {
                                Log.d(TAG, "Delegation updated before reschedule")
                            }
                            SilenceScheduler.scheduleAll(context)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update delegation before reschedule", e)
                            SilenceScheduler.scheduleAll(context)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                } else {
                    SilenceScheduler.scheduleAll(context)
                }
            }
        }
    }

    internal suspend fun checkDelegationBeforePrayer(context: Context, prayerName: String): Boolean {
        if (!PrefsManager.isAutoLocationUpdateEnabled(context) || !DelegationLocator.hasLocationPermission(context)) {
            return false
        }
        if (!DelegationLocator.updateDelegationFromCurrentLocation(context)) {
            return false
        }

        Log.d(TAG, "Delegation updated before $prayerName, rescheduling")
        SilenceScheduler.scheduleAll(context)
        return true
    }
}
