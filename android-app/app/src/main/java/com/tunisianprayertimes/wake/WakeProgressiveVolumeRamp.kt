package com.tunisianprayertimes.wake

import android.media.VolumeShaper

internal object WakeProgressiveVolumeRamp {
    const val DURATION_MILLIS = 30_000L
    const val FALLBACK_STEP_INTERVAL_MILLIS = 100L

    const val START_RINGTONE_VOLUME = 0.05f
    private const val END_RINGTONE_VOLUME = 1f

    fun volumeShaperConfiguration(): VolumeShaper.Configuration =
        VolumeShaper.Configuration.Builder()
            .setDuration(DURATION_MILLIS)
            .setCurve(
                floatArrayOf(0f, 1f),
                floatArrayOf(START_RINGTONE_VOLUME, END_RINGTONE_VOLUME),
            )
            .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
            .build()

    fun ringtoneVolumeAt(elapsedMillis: Long): Float {
        val boundedElapsed = elapsedMillis.coerceIn(0L, DURATION_MILLIS)
        val fraction = boundedElapsed / DURATION_MILLIS.toFloat()
        return START_RINGTONE_VOLUME + ((END_RINGTONE_VOLUME - START_RINGTONE_VOLUME) * fraction)
    }
}
