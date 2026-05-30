package com.tunisianprayertimes.wake

import android.content.Context
import android.util.Log
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.WakeMainAlarmMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal object WakeDismissalCoordinator {
    private const val TAG = "WakeDismissalCoordinator"

    fun recordDismissal(
        context: Context,
        payload: WakeTriggerPayload,
        stopSource: String,
        wakeupCheckCompleted: Boolean,
    ) {
        AnalyticsTracker.wakeAlarmDismissed(
            context = context,
            payload = payload,
            stopSource = stopSource,
            wakeupCheckCompleted = wakeupCheckCompleted,
        )

        if (payload.awakeCheckEnabled) {
            AwakeCheckScheduler.schedule(
                context = context,
                eventId = payload.eventId,
                delayMinutes = payload.awakeCheckDelayMinutes,
                ringtonePresetName = payload.ringtone.name,
                customRingtoneUri = payload.customRingtoneUri,
                autoSilenceOverrideAllowed = payload.autoSilenceOverrideAllowed,
                autoSilenceConflictPrayer = payload.autoSilenceConflictPrayer,
            )
        }
    }

    fun removeExpiredOneOffAlarmAfterDismissalAsync(
        context: Context,
        payload: WakeTriggerPayload,
    ) {
        if (payload.mainAlarmMode != WakeMainAlarmMode.FROM_NOW) {
            return
        }

        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            removeExpiredOneOffAlarmAfterDismissal(
                context = appContext,
                payload = payload,
            )
        }
    }

    suspend fun removeExpiredOneOffAlarmAfterDismissal(
        context: Context,
        payload: WakeTriggerPayload,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (payload.mainAlarmMode != WakeMainAlarmMode.FROM_NOW) {
            return false
        }

        val alarmId = wakeAlarmIdFromEventId(payload.eventId) ?: return false
        val repository = PrayerWakeRepository(context)
        val config = repository.getWakeAlarm(alarmId) ?: return false
        if (!config.isExpiredOneOffWakeAlarm(nowMillis)) {
            return false
        }

        return runCatching {
            repository.deleteWakeAlarm(config.id)
            true
        }.onFailure { error ->
            Log.w(TAG, "Failed to remove expired one-off wake alarm $alarmId after dismissal", error)
        }.getOrDefault(false)
    }
}
