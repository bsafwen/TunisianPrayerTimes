package com.tunisianprayertimes.platform

import android.content.Context
import android.content.SharedPreferences
import com.tunisianprayertimes.*

actual object Preferences {
    private const val PREFS_NAME = "prayer_silence_prefs"
    private const val DEFAULT_DELEGATION_ID = 615
    private const val DEFAULT_MANUAL_SILENCE_DURATION_MINUTES = 30

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(): SharedPreferences =
        (appContext ?: error("Preferences not initialized. Call init(context) first."))
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    actual fun getDelegationId(): Int = prefs().getInt("delegation_id", DEFAULT_DELEGATION_ID)
    actual fun setDelegationId(id: Int) { prefs().edit().putInt("delegation_id", id).apply() }

    actual fun isEnabled(): Boolean = prefs().getBoolean("silence_enabled", true)
    actual fun setEnabled(enabled: Boolean) { prefs().edit().putBoolean("silence_enabled", enabled).apply() }

    actual fun isFirstLaunch(): Boolean = !prefs().getBoolean("first_launch_done", false)
    actual fun markFirstLaunchDone() { prefs().edit().putBoolean("first_launch_done", true).apply() }

    actual fun isManualSilenceActive(): Boolean = prefs().getBoolean("manual_silence_active", false)
    actual fun markManualSilenceActive() { prefs().edit().putBoolean("manual_silence_active", true).apply() }
    actual fun clearManualSilenceState() {
        prefs().edit()
            .putBoolean("manual_silence_active", false)
            .remove("manual_silence_ends_at_millis")
            .apply()
    }

    actual fun usesManualSilenceDuration(): Boolean = prefs().getBoolean("manual_silence_uses_duration", false)
    actual fun setManualSilenceUsesDuration(usesDuration: Boolean) {
        prefs().edit().putBoolean("manual_silence_uses_duration", usesDuration).apply()
    }

    actual fun getManualSilenceDurationMinutes(): Int =
        prefs().getInt("manual_silence_duration_minutes", DEFAULT_MANUAL_SILENCE_DURATION_MINUTES)
    actual fun setManualSilenceDurationMinutes(minutes: Int) {
        prefs().edit().putInt("manual_silence_duration_minutes", minutes.coerceAtLeast(1)).apply()
    }

    actual fun getManualSilenceEndsAtMillis(): Long = prefs().getLong("manual_silence_ends_at_millis", -1L)
    actual fun setManualSilenceEndsAtMillis(endsAtMillis: Long) {
        prefs().edit().putLong("manual_silence_ends_at_millis", endsAtMillis).apply()
    }
    actual fun clearManualSilenceEndsAt() { prefs().edit().remove("manual_silence_ends_at_millis").apply() }

    private fun defaultAfterMinutes(prayer: Prayer): Int = when (prayer) {
        Prayer.FAJR -> 60
        Prayer.DHUHR -> 60
        Prayer.ASR -> 30
        Prayer.MAGHRIB -> 20
        Prayer.ISHA -> 30
        Prayer.JOMOAA -> 60
    }

    actual fun getAfterMinutes(prayer: Prayer): Int =
        prefs().getInt("after_${prayer.name}", defaultAfterMinutes(prayer))
    actual fun setAfterMinutes(prayer: Prayer, minutes: Int) {
        prefs().edit().putInt("after_${prayer.name}", minutes).apply()
    }

    private fun defaultSilenceMode(prayer: Prayer): SilenceMode = when (prayer) {
        Prayer.DHUHR, Prayer.JOMOAA -> SilenceMode.FIXED_TIME
        else -> SilenceMode.DURATION
    }

    actual fun getSilenceMode(prayer: Prayer): SilenceMode {
        val default = defaultSilenceMode(prayer)
        val modeStr = prefs().getString("mode_${prayer.name}", default.name)
        return try { SilenceMode.valueOf(modeStr!!) } catch (_: Exception) { default }
    }
    actual fun setSilenceMode(prayer: Prayer, mode: SilenceMode) {
        prefs().edit().putString("mode_${prayer.name}", mode.name).apply()
    }

    actual fun getFixedTimeHour(prayer: Prayer): Int {
        val default = if (prayer == Prayer.DHUHR || prayer == Prayer.JOMOAA) 13 else -1
        return prefs().getInt("fixed_hour_${prayer.name}", default)
    }
    actual fun getFixedTimeMinute(prayer: Prayer): Int {
        val default = if (prayer == Prayer.DHUHR || prayer == Prayer.JOMOAA) 15 else -1
        return prefs().getInt("fixed_minute_${prayer.name}", default)
    }
    actual fun setFixedTime(prayer: Prayer, hour: Int, minute: Int) {
        prefs().edit()
            .putInt("fixed_hour_${prayer.name}", hour)
            .putInt("fixed_minute_${prayer.name}", minute)
            .apply()
    }

    actual fun getDelayMinutes(prayer: Prayer): Int = prefs().getInt("delay_${prayer.name}", 0)
    actual fun setDelayMinutes(prayer: Prayer, minutes: Int) {
        prefs().edit().putInt("delay_${prayer.name}", minutes).apply()
    }

    actual fun getDelayMode(prayer: Prayer): DelayMode {
        val modeStr = prefs().getString("delay_mode_${prayer.name}", DelayMode.MINUTES.name)
        return try { DelayMode.valueOf(modeStr!!) } catch (_: Exception) { DelayMode.MINUTES }
    }
    actual fun setDelayMode(prayer: Prayer, mode: DelayMode) {
        prefs().edit().putString("delay_mode_${prayer.name}", mode.name).apply()
    }

    actual fun getDelayFixedHour(prayer: Prayer): Int = prefs().getInt("delay_fixed_hour_${prayer.name}", -1)
    actual fun getDelayFixedMinute(prayer: Prayer): Int = prefs().getInt("delay_fixed_minute_${prayer.name}", -1)
    actual fun setDelayFixedTime(prayer: Prayer, hour: Int, minute: Int) {
        prefs().edit()
            .putInt("delay_fixed_hour_${prayer.name}", hour)
            .putInt("delay_fixed_minute_${prayer.name}", minute)
            .apply()
    }

    actual fun getJomoaaTimeHour(): Int = prefs().getInt("jomoaa_time_hour", -1)
    actual fun getJomoaaTimeMinute(): Int = prefs().getInt("jomoaa_time_minute", -1)
    actual fun setJomoaaTime(hour: Int, minute: Int) {
        prefs().edit()
            .putInt("jomoaa_time_hour", hour)
            .putInt("jomoaa_time_minute", minute)
            .apply()
    }

    actual fun getConfig(prayer: Prayer): PrayerSilenceConfig {
        return PrayerSilenceConfig(
            mode = getSilenceMode(prayer),
            afterMinutes = getAfterMinutes(prayer),
            fixedHour = getFixedTimeHour(prayer),
            fixedMinute = getFixedTimeMinute(prayer),
            delayMode = getDelayMode(prayer),
            delayMinutes = getDelayMinutes(prayer),
            delayFixedHour = getDelayFixedHour(prayer),
            delayFixedMinute = getDelayFixedMinute(prayer)
        )
    }
}
