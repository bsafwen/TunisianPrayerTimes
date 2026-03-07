package com.tunisianprayertimes

import android.content.Context
import android.content.SharedPreferences

object PrefsManager {
    private const val PREFS_NAME = "prayer_silence_prefs"
    private const val KEY_DELEGATION_ID = "delegation_id"
    private const val KEY_ENABLED = "silence_enabled"
    private const val KEY_FIRST_LAUNCH = "first_launch_done"
    private const val DEFAULT_DELEGATION_ID = 615 // Tunis

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDelegationId(context: Context): Int {
        return prefs(context).getInt(KEY_DELEGATION_ID, DEFAULT_DELEGATION_ID)
    }

    fun setDelegationId(context: Context, id: Int) {
        prefs(context).edit().putInt(KEY_DELEGATION_ID, id).apply()
    }

    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, true)
    }

    fun isFirstLaunch(context: Context): Boolean {
        return !prefs(context).getBoolean(KEY_FIRST_LAUNCH, false)
    }

    fun markFirstLaunchDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_FIRST_LAUNCH, true).apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun defaultAfterMinutes(prayer: Prayer): Int = when (prayer) {
        Prayer.FAJR -> 60
        Prayer.DHUHR -> 60
        Prayer.ASR -> 30
        Prayer.MAGHRIB -> 20
        Prayer.ISHA -> if (RamadanDetector.isRamadan()) 90 else 30
    }

    fun getAfterMinutes(context: Context, prayer: Prayer): Int {
        // During Ramadan, override Isha to 90 min regardless of saved value
        if (prayer == Prayer.ISHA && RamadanDetector.isRamadan()) {
            return 90
        }
        return prefs(context).getInt("after_${prayer.name}", defaultAfterMinutes(prayer))
    }

    fun setAfterMinutes(context: Context, prayer: Prayer, minutes: Int) {
        prefs(context).edit().putInt("after_${prayer.name}", minutes).apply()
    }

    fun getSilenceMode(context: Context, prayer: Prayer): SilenceMode {
        val modeStr = prefs(context).getString("mode_${prayer.name}", SilenceMode.DURATION.name)
        return try { SilenceMode.valueOf(modeStr!!) } catch (_: Exception) { SilenceMode.DURATION }
    }

    fun setSilenceMode(context: Context, prayer: Prayer, mode: SilenceMode) {
        prefs(context).edit().putString("mode_${prayer.name}", mode.name).apply()
    }

    fun getFixedTimeHour(context: Context, prayer: Prayer): Int {
        return prefs(context).getInt("fixed_hour_${prayer.name}", -1)
    }

    fun getFixedTimeMinute(context: Context, prayer: Prayer): Int {
        return prefs(context).getInt("fixed_minute_${prayer.name}", -1)
    }

    fun setFixedTime(context: Context, prayer: Prayer, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt("fixed_hour_${prayer.name}", hour)
            .putInt("fixed_minute_${prayer.name}", minute)
            .apply()
    }

    fun getConfig(context: Context, prayer: Prayer): PrayerSilenceConfig {
        return PrayerSilenceConfig(
            mode = getSilenceMode(context, prayer),
            afterMinutes = getAfterMinutes(context, prayer),
            fixedHour = getFixedTimeHour(context, prayer),
            fixedMinute = getFixedTimeMinute(context, prayer)
        )
    }
}
