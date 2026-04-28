package com.tunisianprayertimes.nap

import android.content.Context

object NapPrefs {
    private const val PREFS_NAME = "nap_prefs"
    private const val KEY_NAP_ENDS_AT_MILLIS = "nap_ends_at_millis"
    private const val KEY_NAP_DURATION_MINUTES = "nap_duration_minutes"
    private const val KEY_NAP_PREVIOUS_RINGER_MODE = "nap_previous_ringer_mode"
    private const val KEY_NAP_PREVIOUS_INTERRUPTION_FILTER = "nap_previous_interruption_filter"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getNapEndsAtMillis(context: Context): Long =
        prefs(context).getLong(KEY_NAP_ENDS_AT_MILLIS, -1L)

    fun setNapEndsAtMillis(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_NAP_ENDS_AT_MILLIS, millis).apply()
    }

    fun getNapDurationMinutes(context: Context): Int =
        prefs(context).getInt(KEY_NAP_DURATION_MINUTES, 30)

    fun setNapDurationMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_NAP_DURATION_MINUTES, minutes.coerceAtLeast(1)).apply()
    }

    fun savePreviousAudioState(context: Context, ringerMode: Int, interruptionFilter: Int) {
        prefs(context).edit()
            .putInt(KEY_NAP_PREVIOUS_RINGER_MODE, ringerMode)
            .putInt(KEY_NAP_PREVIOUS_INTERRUPTION_FILTER, interruptionFilter)
            .apply()
    }

    fun getPreviousRingerMode(context: Context): Int =
        prefs(context).getInt(KEY_NAP_PREVIOUS_RINGER_MODE, android.media.AudioManager.RINGER_MODE_NORMAL)

    fun getPreviousInterruptionFilter(context: Context): Int =
        prefs(context).getInt(KEY_NAP_PREVIOUS_INTERRUPTION_FILTER,
            android.app.NotificationManager.INTERRUPTION_FILTER_ALL)

    fun clearNap(context: Context) {
        prefs(context).edit()
            .remove(KEY_NAP_ENDS_AT_MILLIS)
            .remove(KEY_NAP_PREVIOUS_RINGER_MODE)
            .remove(KEY_NAP_PREVIOUS_INTERRUPTION_FILTER)
            .apply()
    }
}
