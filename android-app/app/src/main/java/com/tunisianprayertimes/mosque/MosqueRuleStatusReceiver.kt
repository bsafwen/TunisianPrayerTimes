package com.tunisianprayertimes.mosque

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class MosqueRuleStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < 30 || intent.action != NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED) return
        val id = intent.getStringExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID) ?: return
        SilenceCoordinator.onRuleStatus(context, id, intent.getIntExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_STATUS, -1))
    }
}
