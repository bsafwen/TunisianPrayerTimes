package com.tunisianprayertimes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("BootReceiver", "Received action: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.MY_PACKAGE_REPLACED" -> {
                Log.d("BootReceiver", "Rescheduling alarms after boot/update")
                SilenceScheduler.scheduleAll(context)
            }
        }
    }
}
