package com.tunisianprayertimes.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

const val ACTION_DISMISS_WAKE_ALERT = "com.tunisianprayertimes.action.DISMISS_WAKE_ALERT"
const val ACTION_SYNC_WAKE_ALERT = "com.tunisianprayertimes.action.SYNC_WAKE_ALERT"

class WakeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP_WAKE_ALARM) {
            return
        }

        val payload = intent.toWakeTriggerPayload() ?: return
        val current = WakeAlarmQueueHolder.queue.current
        if (current == null || current.eventId != payload.eventId ||
            current.resolvedOccurrenceAtMillis() != payload.resolvedOccurrenceAtMillis()
        ) {
            // The action may have been tapped as Skip removed this notification.
            if (current != null) {
                context.startService(
                    Intent(context, WakePlaybackService::class.java)
                        .setAction(WakePlaybackService.ACTION_REFRESH_FOR_CURRENT),
                )
            }
            context.sendBroadcast(Intent(ACTION_SYNC_WAKE_ALERT).setPackage(context.packageName))
            return
        }
        context.stopService(Intent(context, WakePlaybackService::class.java))
        WakeDismissalCoordinator.recordDismissal(
            context = context,
            payload = payload,
            stopSource = "notification_action",
            wakeupCheckCompleted = !payload.wakeUpCheckEnabled,
        )
        WakeDismissalCoordinator.removeExpiredOneOffAlarmAfterDismissalAsync(
            context = context,
            payload = payload,
        )

        context.sendBroadcast(
            Intent(ACTION_DISMISS_WAKE_ALERT)
                .setPackage(context.packageName)
                .apply {
                    intent.wakeEventId()?.let { eventId ->
                        populateWakeStopPayload(eventId)
                    }
                },
        )
        WakeAlarmScheduler.resumeSilenceUntilNextAlarm(context)
    }
}
