package com.tunisianprayertimes.wake

internal object WakeProgressiveVolumeRamp {
    const val DURATION_MILLIS = 30_000L
    const val STEP_INTERVAL_MILLIS = 1_500L

    private const val START_RINGTONE_VOLUME = 0.05f
    private const val END_RINGTONE_VOLUME = 1f

    fun ringtoneVolumeAt(elapsedMillis: Long): Float {
        val boundedElapsed = elapsedMillis.coerceIn(0L, DURATION_MILLIS)
        val fraction = boundedElapsed / DURATION_MILLIS.toFloat()
        return START_RINGTONE_VOLUME + ((END_RINGTONE_VOLUME - START_RINGTONE_VOLUME) * fraction)
    }
}
