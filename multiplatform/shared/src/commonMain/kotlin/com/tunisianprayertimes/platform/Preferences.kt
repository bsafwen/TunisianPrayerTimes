package com.tunisianprayertimes.platform

import com.tunisianprayertimes.*

/**
 * Platform abstraction for persistent preferences storage.
 * Replaces Android SharedPreferences.
 */
expect object Preferences {
    // Delegation
    fun getDelegationId(): Int
    fun setDelegationId(id: Int)

    // Auto-silence
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)

    // First launch
    fun isFirstLaunch(): Boolean
    fun markFirstLaunchDone()

    // Manual silence
    fun isManualSilenceActive(): Boolean
    fun markManualSilenceActive()
    fun clearManualSilenceState()
    fun usesManualSilenceDuration(): Boolean
    fun setManualSilenceUsesDuration(usesDuration: Boolean)
    fun getManualSilenceDurationMinutes(): Int
    fun setManualSilenceDurationMinutes(minutes: Int)
    fun getManualSilenceEndsAtMillis(): Long
    fun setManualSilenceEndsAtMillis(endsAtMillis: Long)
    fun clearManualSilenceEndsAt()

    // Per-prayer config
    fun getAfterMinutes(prayer: Prayer): Int
    fun setAfterMinutes(prayer: Prayer, minutes: Int)
    fun getSilenceMode(prayer: Prayer): SilenceMode
    fun setSilenceMode(prayer: Prayer, mode: SilenceMode)
    fun getFixedTimeHour(prayer: Prayer): Int
    fun getFixedTimeMinute(prayer: Prayer): Int
    fun setFixedTime(prayer: Prayer, hour: Int, minute: Int)
    fun getDelayMinutes(prayer: Prayer): Int
    fun setDelayMinutes(prayer: Prayer, minutes: Int)
    fun getDelayMode(prayer: Prayer): DelayMode
    fun setDelayMode(prayer: Prayer, mode: DelayMode)
    fun getDelayFixedHour(prayer: Prayer): Int
    fun getDelayFixedMinute(prayer: Prayer): Int
    fun setDelayFixedTime(prayer: Prayer, hour: Int, minute: Int)

    // Jomoaa
    fun getJomoaaTimeHour(): Int
    fun getJomoaaTimeMinute(): Int
    fun setJomoaaTime(hour: Int, minute: Int)

    // Config bundle
    fun getConfig(prayer: Prayer): PrayerSilenceConfig
}
