package com.tunisianprayertimes

import android.content.Context
import android.content.SharedPreferences
import android.app.NotificationManager
import android.media.AudioManager
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

object PrefsManager {
    private const val PREFS_NAME = "prayer_silence_prefs"
    private const val KEY_DELEGATION_ID = "delegation_id"
    private const val KEY_ENABLED = "silence_enabled"
    private const val KEY_FIRST_LAUNCH = "first_launch_done"
    private const val KEY_AUTO_SILENCE_ACTIVE = "auto_silence_active"
    private const val KEY_AUTO_SILENCE_PREVIOUS_RINGER_MODE = "auto_silence_previous_ringer_mode"
    private const val KEY_AUTO_SILENCE_PREVIOUS_INTERRUPTION_FILTER = "auto_silence_previous_interruption_filter"
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

    fun isAutoSilenceActive(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_SILENCE_ACTIVE, false)
    }

    fun markAutoSilenceActive(context: Context, previousRingerMode: Int, previousInterruptionFilter: Int) {
        prefs(context).edit()
            .putBoolean(KEY_AUTO_SILENCE_ACTIVE, true)
            .putInt(KEY_AUTO_SILENCE_PREVIOUS_RINGER_MODE, previousRingerMode)
            .putInt(KEY_AUTO_SILENCE_PREVIOUS_INTERRUPTION_FILTER, previousInterruptionFilter)
            .apply()
    }

    fun getAutoSilencePreviousRingerMode(context: Context): Int {
        return prefs(context).getInt(KEY_AUTO_SILENCE_PREVIOUS_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)
    }

    fun getAutoSilencePreviousInterruptionFilter(context: Context): Int {
        return prefs(context).getInt(
            KEY_AUTO_SILENCE_PREVIOUS_INTERRUPTION_FILTER,
            NotificationManager.INTERRUPTION_FILTER_ALL
        )
    }

    fun clearAutoSilenceState(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_AUTO_SILENCE_ACTIVE, false)
            .remove(KEY_AUTO_SILENCE_PREVIOUS_RINGER_MODE)
            .remove(KEY_AUTO_SILENCE_PREVIOUS_INTERRUPTION_FILTER)
            .apply()
    }

    private const val RAMADAN_ISHA_MINUTES = 90
    private const val KEY_RAMADAN_OVERRIDE_APPLIED = "ramadan_isha_override_hijri_year"

    private fun defaultAfterMinutes(prayer: Prayer): Int = when (prayer) {
        Prayer.FAJR -> 60
        Prayer.DHUHR -> 60
        Prayer.ASR -> 30
        Prayer.MAGHRIB -> 20
        Prayer.ISHA -> 30
    }

    fun getAfterMinutes(context: Context, prayer: Prayer): Int {
        return prefs(context).getInt("after_${prayer.name}", defaultAfterMinutes(prayer))
    }

    /**
     * One-time Ramadan override: if it's Ramadan and we haven't already applied the
     * override this Hijri year, bump Isha duration to 90 min (only if current value < 90).
     * After this, the user's changes are respected.
     */
    fun applyRamadanIshaOverrideIfNeeded(context: Context) {
        applyRamadanIshaOverrideIfNeeded(context, HijrahDate.now())
    }

    internal fun applyRamadanIshaOverrideIfNeeded(context: Context, hijrahDate: HijrahDate) {
        if (!RamadanDetector.isRamadan(hijrahDate)) return

        val hijriYear = hijrahDate.get(ChronoField.YEAR)
        val appliedYear = prefs(context).getInt(KEY_RAMADAN_OVERRIDE_APPLIED, -1)
        if (appliedYear == hijriYear) return // already applied this Ramadan

        val current = getAfterMinutes(context, Prayer.ISHA)
        if (current < RAMADAN_ISHA_MINUTES) {
            setAfterMinutes(context, Prayer.ISHA, RAMADAN_ISHA_MINUTES)
        }
        prefs(context).edit().putInt(KEY_RAMADAN_OVERRIDE_APPLIED, hijriYear).apply()
    }

    fun setAfterMinutes(context: Context, prayer: Prayer, minutes: Int) {
        prefs(context).edit().putInt("after_${prayer.name}", minutes).apply()
    }

    private fun defaultSilenceMode(prayer: Prayer): SilenceMode = when (prayer) {
        Prayer.DHUHR -> SilenceMode.FIXED_TIME
        else -> SilenceMode.DURATION
    }

    fun getSilenceMode(context: Context, prayer: Prayer): SilenceMode {
        val default = defaultSilenceMode(prayer)
        val modeStr = prefs(context).getString("mode_${prayer.name}", default.name)
        return try { SilenceMode.valueOf(modeStr!!) } catch (_: Exception) { default }
    }

    fun setSilenceMode(context: Context, prayer: Prayer, mode: SilenceMode) {
        prefs(context).edit().putString("mode_${prayer.name}", mode.name).apply()
    }

    fun getFixedTimeHour(context: Context, prayer: Prayer): Int {
        val default = if (prayer == Prayer.DHUHR) 13 else -1
        return prefs(context).getInt("fixed_hour_${prayer.name}", default)
    }

    fun getFixedTimeMinute(context: Context, prayer: Prayer): Int {
        val default = if (prayer == Prayer.DHUHR) 15 else -1
        return prefs(context).getInt("fixed_minute_${prayer.name}", default)
    }

    fun setFixedTime(context: Context, prayer: Prayer, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt("fixed_hour_${prayer.name}", hour)
            .putInt("fixed_minute_${prayer.name}", minute)
            .apply()
    }

    // --- Delay settings ---

    fun getDelayMinutes(context: Context, prayer: Prayer): Int {
        return prefs(context).getInt("delay_${prayer.name}", 0)
    }

    fun setDelayMinutes(context: Context, prayer: Prayer, minutes: Int) {
        prefs(context).edit().putInt("delay_${prayer.name}", minutes).apply()
    }

    fun getDelayMode(context: Context, prayer: Prayer): DelayMode {
        val modeStr = prefs(context).getString("delay_mode_${prayer.name}", DelayMode.MINUTES.name)
        return try { DelayMode.valueOf(modeStr!!) } catch (_: Exception) { DelayMode.MINUTES }
    }

    fun setDelayMode(context: Context, prayer: Prayer, mode: DelayMode) {
        prefs(context).edit().putString("delay_mode_${prayer.name}", mode.name).apply()
    }

    fun getDelayFixedHour(context: Context, prayer: Prayer): Int {
        return prefs(context).getInt("delay_fixed_hour_${prayer.name}", -1)
    }

    fun getDelayFixedMinute(context: Context, prayer: Prayer): Int {
        return prefs(context).getInt("delay_fixed_minute_${prayer.name}", -1)
    }

    fun setDelayFixedTime(context: Context, prayer: Prayer, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt("delay_fixed_hour_${prayer.name}", hour)
            .putInt("delay_fixed_minute_${prayer.name}", minute)
            .apply()
    }

    fun getConfig(context: Context, prayer: Prayer): PrayerSilenceConfig {
        return PrayerSilenceConfig(
            mode = getSilenceMode(context, prayer),
            afterMinutes = getAfterMinutes(context, prayer),
            fixedHour = getFixedTimeHour(context, prayer),
            fixedMinute = getFixedTimeMinute(context, prayer),
            delayMode = getDelayMode(context, prayer),
            delayMinutes = getDelayMinutes(context, prayer),
            delayFixedHour = getDelayFixedHour(context, prayer),
            delayFixedMinute = getDelayFixedMinute(context, prayer)
        )
    }
}
