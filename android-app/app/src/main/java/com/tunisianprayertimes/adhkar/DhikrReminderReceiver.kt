package com.tunisianprayertimes.adhkar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DhikrReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DhikrReminderScheduler.receive(context.applicationContext, intent)
            } catch (error: Exception) {
                Log.w("DhikrReminderReceiver", "Unable to handle dhikr reminder", error)
            } finally {
                pending.finish()
            }
        }
    }
}
