package com.tunisianprayertimes.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

const val ACTION_DISMISS_WAKE_ALERT = "com.tunisianprayertimes.action.DISMISS_WAKE_ALERT"

class WakeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP_WAKE_ALARM) {
            return
        }

        context.stopService(Intent(context, WakePlaybackService::class.java))
        intent.toWakeTriggerPayload()?.let { payload ->
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
        }

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