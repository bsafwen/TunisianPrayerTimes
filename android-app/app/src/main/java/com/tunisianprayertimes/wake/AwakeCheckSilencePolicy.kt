package com.tunisianprayertimes.wake

import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.mosque.SilenceCoordinator
import com.tunisianprayertimes.mosque.SilenceOwner
import com.tunisianprayertimes.mosque.MosqueSilenceMonitor
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode

import android.content.Context
import android.util.Log
import com.tunisianprayertimes.ManualSilenceScheduler
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.SilenceAlarmComputer
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.SilenceStatus
import java.util.Calendar

internal object AwakeCheckSilencePolicy {
    private const val TAG = "AwakeCheckSilence"

    fun shouldCancelBeforeStart(
        context: Context,
        autoSilenceOverrideAllowed: Boolean,
        scheduledTriggerAtMillis: Long? = null,
    ): Boolean = shouldCancel(
        context = context,
        autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
        autoSilenceConflictPrayer = null,
        scheduledTriggerAtMillis = scheduledTriggerAtMillis,
        liftAutoSilence = false,
    )

    fun prepareForEscalation(
        context: Context,
        autoSilenceOverrideAllowed: Boolean,
        autoSilenceConflictPrayer: Prayer?,
    ): Boolean = !shouldCancel(
        context = context,
        autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
        autoSilenceConflictPrayer = autoSilenceConflictPrayer,
        scheduledTriggerAtMillis = null,
        liftAutoSilence = true,
    )

    private fun shouldCancel(
        context: Context,
        autoSilenceOverrideAllowed: Boolean,
        autoSilenceConflictPrayer: Prayer?,
        scheduledTriggerAtMillis: Long?,
        liftAutoSilence: Boolean,
    ): Boolean {
        if (MosqueSilenceSettings.combined(context)) MosqueSilenceMonitor.reconcile(context)
        ManualSilenceScheduler.syncExpiredTimer(context)
        if (MosqueSilenceSettings.mode(context) == AutomaticSilenceMode.TIME_ONLY && !autoSilenceOverrideAllowed && scheduledTriggerAtMillis != null && wasScheduledDuringAutoSilence(context, scheduledTriggerAtMillis)) {
            return true
        }

        if (!SilenceStatus.isAppControlledSilenceActive(context)) {
            return false
        }

        if (PrefsManager.isManualSilenceActive(context)) {
            return true
        }

        if (if (MosqueSilenceSettings.usesOwnedRules(context)) SilenceCoordinator.requested(context, SilenceOwner.WAKE)
            else WakeAlarmScheduler.isSilenceUntilAlarmActive(context)) {
            return true
        }

        if (!PrefsManager.isAutoSilenceActive(context)) {
            return true
        }

        if (!autoSilenceOverrideAllowed) {
            return true
        }

        if (!liftAutoSilence) {
            return false
        }

        SilenceScheduler.dismissCurrentWindows(context, SilenceScheduler.currentSilenceWindowPrayer(context) ?: autoSilenceConflictPrayer)

        val lifted = SilenceModeController.disableAutoSilence(context)
        if (lifted) {
            Log.d(TAG, "Lifted auto-silence for awake follow-up")
        }
        return !lifted && SilenceStatus.isAppControlledSilenceActive(context)
    }

    private fun wasScheduledDuringAutoSilence(context: Context, triggerAtMillis: Long): Boolean {
        if (triggerAtMillis <= 0L || !PrefsManager.isEnabled(context)) {
            return false
        }

        // The planner's windows around the trigger (6.9), as the scheduler arms them.
        return SilenceScheduler.planner(context).windowAt(java.time.Instant.ofEpochMilli(triggerAtMillis)) != null
    }
}
