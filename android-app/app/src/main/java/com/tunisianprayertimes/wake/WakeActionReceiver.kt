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
        // Advance the queue here: the alert screen only listens while it is started, and a stopped
        // alarm left as current would be shown again, silent, the next time the app comes forward.
        WakeAlarmQueueHolder.queue.handleDismiss(payload.eventId)
        // The service plays the next queued alarm, or stops itself and resumes silence when none is left.
        context.startService(
            Intent(context, WakePlaybackService::class.java)
                .setAction(WakePlaybackService.ACTION_REFRESH_FOR_CURRENT),
        )
        context.sendBroadcast(Intent(ACTION_SYNC_WAKE_ALERT).setPackage(context.packageName))
    }
}
