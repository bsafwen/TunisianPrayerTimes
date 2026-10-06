package com.tunisianprayertimes.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import org.robolectric.Shadows
import java.util.concurrent.TimeUnit

internal fun Context.deliverWakeBroadcast(receiver: BroadcastReceiver, intent: Intent) {
    val action = requireNotNull(intent.action)
    registerReceiver(receiver, IntentFilter(action))
    try {
        sendBroadcast(Intent(intent).setComponent(null).setData(null).setPackage(packageName))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val shadowReceiver = Shadows.shadowOf(receiver)
        if (shadowReceiver.wentAsync()) {
            Shadows.shadowOf(shadowReceiver.originalPendingResult).future.get(10, TimeUnit.SECONDS)
        }
    } finally {
        unregisterReceiver(receiver)
    }
}
