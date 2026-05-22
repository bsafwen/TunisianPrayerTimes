package com.tunisianprayertimes.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tunisianprayertimes.SilenceStatus

/**
 * Receives the "Yes, I'm awake" confirmation and stops the AwakeCheckService.
 * Also receives the delayed alarm to start the awake check after alarm dismissal.
 */
class AwakeCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AwakeCheckService.ACTION_AWAKE_CHECK_CONFIRMED -> {
                AwakeCheckScheduler.cancel(context, intent.getStringExtra(EXTRA_EVENT_ID))
                context.stopService(Intent(context, AwakeCheckService::class.java))
            }

            ACTION_START_AWAKE_CHECK -> {
                val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
                if (SilenceStatus.isAppControlledSilenceActive(context)) {
                    Log.d(TAG, "Awake check suppressed during app-controlled silence eventId=$eventId")
                    AwakeCheckScheduler.cancel(context, eventId)
                    return
                }

                val ringtonePresetName = intent.getStringExtra(EXTRA_RINGTONE)
                val customRingtoneUri = intent.getStringExtra(EXTRA_CUSTOM_RINGTONE_URI)

                val ringtonePreset = ringtonePresetName?.let { name ->
                    runCatching { com.tunisianprayertimes.RingtonePreset.valueOf(name) }.getOrNull()
                }

                val serviceIntent = AwakeCheckService.intent(
                    context = context,
                    eventId = eventId,
                    ringtonePreset = ringtonePreset,
                    customRingtoneUri = customRingtoneUri,
                )
                context.startForegroundService(serviceIntent)
            }
        }
    }

    companion object {
        private const val TAG = "AwakeCheckReceiver"

        const val ACTION_START_AWAKE_CHECK =
            "com.tunisianprayertimes.action.START_AWAKE_CHECK"
    }
}
