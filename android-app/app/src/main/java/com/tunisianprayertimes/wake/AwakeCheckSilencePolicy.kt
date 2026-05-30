package com.tunisianprayertimes.wake

import android.content.Context
import android.util.Log
import com.tunisianprayertimes.ManualSilenceScheduler
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.SilenceStatus

internal object AwakeCheckSilencePolicy {
    private const val TAG = "AwakeCheckSilence"

    fun shouldCancelBeforeStart(
        context: Context,
        autoSilenceOverrideAllowed: Boolean,
    ): Boolean = shouldCancel(
        context = context,
        autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
        autoSilenceConflictPrayer = null,
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
        liftAutoSilence = true,
    )

    private fun shouldCancel(
        context: Context,
        autoSilenceOverrideAllowed: Boolean,
        autoSilenceConflictPrayer: Prayer?,
        liftAutoSilence: Boolean,
    ): Boolean {
        ManualSilenceScheduler.syncExpiredTimer(context)
        if (!SilenceStatus.isAppControlledSilenceActive(context)) {
            return false
        }

        if (PrefsManager.isManualSilenceActive(context)) {
            return true
        }

        if (WakeAlarmScheduler.isSilenceUntilAlarmActive(context)) {
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

        val dismissedPrayer = SilenceScheduler.currentSilenceWindowPrayer(context)
            ?: autoSilenceConflictPrayer
        dismissedPrayer?.let { prayer ->
            PrefsManager.setAutoSilenceDismissed(
                context = context,
                millis = System.currentTimeMillis(),
                prayer = prayer,
            )
        }

        val lifted = SilenceModeController.disableAutoSilence(context)
        if (lifted) {
            Log.d(TAG, "Lifted auto-silence for awake follow-up")
        }
        return !lifted && SilenceStatus.isAppControlledSilenceActive(context)
    }
}