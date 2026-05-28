package com.tunisianprayertimes.wake

internal object WakeProgressiveVolumeRamp {
    const val DURATION_MILLIS = 120_000L
    const val FALLBACK_STEP_INTERVAL_MILLIS = 100L

    const val START_RINGTONE_VOLUME = 0.05f
    private const val END_RINGTONE_VOLUME = 1f

    fun ringtoneVolumeAt(elapsedMillis: Long, durationMillis: Long = DURATION_MILLIS): Float {
        val boundedDuration = durationMillis.coerceAtLeast(1L)
        val boundedElapsed = elapsedMillis.coerceIn(0L, boundedDuration)
        val fraction = boundedElapsed / boundedDuration.toFloat()
        return START_RINGTONE_VOLUME + ((END_RINGTONE_VOLUME - START_RINGTONE_VOLUME) * fraction)
    }
}
