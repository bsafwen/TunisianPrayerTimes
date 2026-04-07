package com.tunisianprayertimes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Tracks incoming ringing calls while app-managed silence is active.
 */
class PhoneStateReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PhoneStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        if (!PrefsManager.isAutoSilenceActive(context) && !PrefsManager.isManualSilenceActive(context)) {
            return
        }

        PrefsManager.markCallReceivedDuringSilence(context)
        Log.d(TAG, "Incoming call detected during silence window")
    }
}