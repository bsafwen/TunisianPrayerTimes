package com.tunisianprayertimes

import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Vibrates with the alarm usage. An untagged vibration is dropped by the system while the
 * ringer is silent; an alarm one is kept.
 */
internal fun Vibrator.vibrateAsAlarm(effect: VibrationEffect) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
    } else {
        @Suppress("DEPRECATION")
        vibrate(
            effect,
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
        )
    }
}
