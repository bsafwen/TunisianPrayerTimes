package com.tunisianprayertimes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tunisianprayertimes.wake.WakeAlarmScheduler
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
                val prayer = runCatching { Prayer.valueOf(prayerName) }.getOrNull()
                if (prayer != null && !PrefsManager.isPrayerSilenceEnabled(context, prayer)) {
                    Log.d(TAG, "Silence disabled for $prayerName, skipping")
                    return
                }
                // The alarm names its prayer day (6.9). A dismissal covers that occurrence only, and an alarm
                // that no longer matches a window of the scan (the window moved, or a cancel came too late to
                // retract an already dispatched broadcast) is obsolete: everything is armed again instead.
                val prayerDay = SilenceScheduler.prayerDayOf(intent)
                if (prayer != null && prayerDay != null) {
                    if (SilenceScheduler.isDismissed(context, prayer, prayerDay)) {
                        Log.d(TAG, "User dismissed $prayerName of $prayerDay, skipping")
                        return
                    }
                    if (SilenceScheduler.windowOf(context, prayer, prayerDay)?.contains(SilenceScheduler.now()) != true) {
                        Log.d(TAG, "Obsolete start of $prayerName of $prayerDay, rescheduling")
                        SilenceScheduler.scheduleAll(context)
                        return
                    }
                } else if (PrefsManager.getAutoSilenceDismissedPrayer(context) == prayerName) {
                    // An alarm armed before 6.9 carries no prayer day: today's rule.
                    Log.d(TAG, "User already dismissed silence for $prayerName, skipping")
                    return
                }
                Log.d(TAG, "Silencing phone for $prayerName")
                PrefsManager.clearAutoSilenceDismissed(context)
                SilenceModeController.enableAutoSilence(
                    context,
                    prayer,
                )
                // Update delegation from cached location in the background,
                // so the next reschedule uses the correct prayer times. Android gives
                // no location here without the background-location permission (6.8).
                if (PrefsManager.isAutoLocationUpdateEnabled(context) && DelegationLocator.canLocateFromBackground(context)) {
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
                    val prayer = runCatching { Prayer.valueOf(prayerName) }.getOrNull()
                    if (SilenceScheduler.isOtherSilenceWindowActive(context, prayer, SilenceScheduler.prayerDayOf(intent))) {
                        // Overlapping windows, another occurrence of the same prayer included: the
                        // latest one's own end alarm ends the silence (6.9).
                        Log.d(TAG, "Another silence window is still active, keeping silence")
                        return
                    }
                    SilenceModeController.disableAutoSilence(context)
                } else if (SilenceStatus.isPhoneSilenced(context) && !WakeAlarmScheduler.isSilenceUntilAlarmActive(context)) {
                    // Flag was already cleared (manual toggle, scheduleAll, etc.)
                    // but the phone is still silenced — force normal so DND is never
                    // left on permanently. A ringer mode the user chose is left as it
                    // is, and a silence-until-alarm is ended by its own alarm.
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
                if (PrefsManager.isAutoLocationUpdateEnabled(context) && DelegationLocator.canLocateFromBackground(context)) {
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
                if (PrefsManager.isAutoLocationUpdateEnabled(context) && DelegationLocator.canLocateFromBackground(context)) {
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
