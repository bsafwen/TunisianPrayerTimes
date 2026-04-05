package com.tunisianprayertimes.platform

import com.tunisianprayertimes.*
import java.io.File
import java.util.prefs.Preferences as JavaPreferences

/**
 * Desktop implementation using java.util.prefs.Preferences.
 */
actual object Preferences {
    private val prefs = JavaPreferences.userNodeForPackage(Preferences::class.java)

    private const val DEFAULT_DELEGATION_ID = 615
    private const val DEFAULT_MANUAL_SILENCE_DURATION_MINUTES = 30

    actual fun getDelegationId(): Int = prefs.getInt("delegation_id", DEFAULT_DELEGATION_ID)
    actual fun setDelegationId(id: Int) { prefs.putInt("delegation_id", id); prefs.flush() }

    actual fun isEnabled(): Boolean = prefs.getBoolean("silence_enabled", true)
    actual fun setEnabled(enabled: Boolean) { prefs.putBoolean("silence_enabled", enabled); prefs.flush() }

    actual fun isFirstLaunch(): Boolean = !prefs.getBoolean("first_launch_done", false)
    actual fun markFirstLaunchDone() { prefs.putBoolean("first_launch_done", true); prefs.flush() }

    actual fun isManualSilenceActive(): Boolean = prefs.getBoolean("manual_silence_active", false)
    actual fun markManualSilenceActive() { prefs.putBoolean("manual_silence_active", true); prefs.flush() }
    actual fun clearManualSilenceState() {
        prefs.putBoolean("manual_silence_active", false)
        prefs.remove("manual_silence_ends_at_millis")
        prefs.flush()
    }

    actual fun usesManualSilenceDuration(): Boolean = prefs.getBoolean("manual_silence_uses_duration", false)
    actual fun setManualSilenceUsesDuration(usesDuration: Boolean) {
        prefs.putBoolean("manual_silence_uses_duration", usesDuration); prefs.flush()
    }

    actual fun getManualSilenceDurationMinutes(): Int =
        prefs.getInt("manual_silence_duration_minutes", DEFAULT_MANUAL_SILENCE_DURATION_MINUTES)
    actual fun setManualSilenceDurationMinutes(minutes: Int) {
        prefs.putInt("manual_silence_duration_minutes", minutes.coerceAtLeast(1)); prefs.flush()
    }

    actual fun getManualSilenceEndsAtMillis(): Long = prefs.getLong("manual_silence_ends_at_millis", -1L)
    actual fun setManualSilenceEndsAtMillis(endsAtMillis: Long) {
        prefs.putLong("manual_silence_ends_at_millis", endsAtMillis); prefs.flush()
    }
    actual fun clearManualSilenceEndsAt() { prefs.remove("manual_silence_ends_at_millis"); prefs.flush() }

    private fun defaultAfterMinutes(prayer: Prayer): Int = when (prayer) {
        Prayer.FAJR -> 60
        Prayer.DHUHR -> 60
        Prayer.ASR -> 30
        Prayer.MAGHRIB -> 20
        Prayer.ISHA -> 30
        Prayer.JOMOAA -> 60
    }

    actual fun getAfterMinutes(prayer: Prayer): Int =
        prefs.getInt("after_${prayer.name}", defaultAfterMinutes(prayer))
    actual fun setAfterMinutes(prayer: Prayer, minutes: Int) {
        prefs.putInt("after_${prayer.name}", minutes); prefs.flush()
    }

    private fun defaultSilenceMode(prayer: Prayer): SilenceMode = when (prayer) {
        Prayer.DHUHR, Prayer.JOMOAA -> SilenceMode.FIXED_TIME
        else -> SilenceMode.DURATION
    }

    actual fun getSilenceMode(prayer: Prayer): SilenceMode {
        val default = defaultSilenceMode(prayer)
        val modeStr = prefs.get("mode_${prayer.name}", default.name)
        return try { SilenceMode.valueOf(modeStr) } catch (_: Exception) { default }
    }
    actual fun setSilenceMode(prayer: Prayer, mode: SilenceMode) {
        prefs.put("mode_${prayer.name}", mode.name); prefs.flush()
    }

    actual fun getFixedTimeHour(prayer: Prayer): Int {
        val default = if (prayer == Prayer.DHUHR || prayer == Prayer.JOMOAA) 13 else -1
        return prefs.getInt("fixed_hour_${prayer.name}", default)
    }
    actual fun getFixedTimeMinute(prayer: Prayer): Int {
        val default = if (prayer == Prayer.DHUHR || prayer == Prayer.JOMOAA) 15 else -1
        return prefs.getInt("fixed_minute_${prayer.name}", default)
    }
    actual fun setFixedTime(prayer: Prayer, hour: Int, minute: Int) {
        prefs.putInt("fixed_hour_${prayer.name}", hour)
        prefs.putInt("fixed_minute_${prayer.name}", minute)
        prefs.flush()
    }

    actual fun getDelayMinutes(prayer: Prayer): Int = prefs.getInt("delay_${prayer.name}", 0)
    actual fun setDelayMinutes(prayer: Prayer, minutes: Int) {
        prefs.putInt("delay_${prayer.name}", minutes); prefs.flush()
    }

    actual fun getDelayMode(prayer: Prayer): DelayMode {
        val modeStr = prefs.get("delay_mode_${prayer.name}", DelayMode.MINUTES.name)
        return try { DelayMode.valueOf(modeStr) } catch (_: Exception) { DelayMode.MINUTES }
    }
    actual fun setDelayMode(prayer: Prayer, mode: DelayMode) {
        prefs.put("delay_mode_${prayer.name}", mode.name); prefs.flush()
    }

    actual fun getDelayFixedHour(prayer: Prayer): Int = prefs.getInt("delay_fixed_hour_${prayer.name}", -1)
    actual fun getDelayFixedMinute(prayer: Prayer): Int = prefs.getInt("delay_fixed_minute_${prayer.name}", -1)
    actual fun setDelayFixedTime(prayer: Prayer, hour: Int, minute: Int) {
        prefs.putInt("delay_fixed_hour_${prayer.name}", hour)
        prefs.putInt("delay_fixed_minute_${prayer.name}", minute)
        prefs.flush()
    }

    actual fun getJomoaaTimeHour(): Int = prefs.getInt("jomoaa_time_hour", -1)
    actual fun getJomoaaTimeMinute(): Int = prefs.getInt("jomoaa_time_minute", -1)
    actual fun setJomoaaTime(hour: Int, minute: Int) {
        prefs.putInt("jomoaa_time_hour", hour)
        prefs.putInt("jomoaa_time_minute", minute)
        prefs.flush()
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
