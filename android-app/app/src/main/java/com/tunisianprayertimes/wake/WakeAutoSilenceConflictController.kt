package com.tunisianprayertimes.wake

import android.content.Context
import android.util.Log
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.WakeMainAlarmMode

internal object WakeAutoSilenceConflictController {
    private const val TAG = "WakeAutoSilenceConflict"

    fun withRuntimeConflictIfNeeded(
        context: Context,
        payload: WakeTriggerPayload,
    ): WakeTriggerPayload {
        if (payload.useAutoSilenceConflictPlayback || payload.mainAlarmMode != WakeMainAlarmMode.FROM_NOW) {
            return payload
        }
        if (!PrefsManager.isAutoSilenceActive(context) || PrefsManager.isManualSilenceActive(context)) {
            return payload
        }

        val conflictPrayer = SilenceScheduler.currentSilenceWindowPrayer(context) ?: return payload
        return payload.copy(
            useAutoSilenceConflictPlayback = true,
            autoSilenceConflictPrayer = conflictPrayer,
        )
    }

    fun liftAutoSilenceIfNeeded(
        context: Context,
        payload: WakeTriggerPayload,
    ): Boolean {
        if (!payload.useAutoSilenceConflictPlayback) return false
        if (!PrefsManager.isAutoSilenceActive(context) || PrefsManager.isManualSilenceActive(context)) {
            return false
        }

        val dismissedPrayer = SilenceScheduler.currentSilenceWindowPrayer(context)
            ?: payload.autoSilenceConflictPrayer
        dismissedPrayer?.let { prayer ->
            PrefsManager.setAutoSilenceDismissed(
                context = context,
                millis = System.currentTimeMillis(),
                prayer = prayer,
            )
        }

        return SilenceModeController.disableAutoSilence(context).also { lifted ->
            if (lifted) {
                Log.d(TAG, "Lifted auto-silence for wake alarm ${payload.eventId}")
            }
        }
    }
}