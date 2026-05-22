package com.tunisianprayertimes.wake

import android.content.Context
import com.tunisianprayertimes.AnalyticsTracker

internal object WakeDismissalCoordinator {
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
            )
        }
    }
}
