package com.tunisianprayertimes

/**
 * Prayer names matching the CSV columns (excluding Shuruk which is sunrise, not a prayer).
 */
enum class Prayer {
    FAJR, DHUHR, ASR, MAGHRIB, ISHA
}

data class PrayerTime(
    val prayer: Prayer,
    val hour: Int,
    val minute: Int
)

data class DayPrayerTimes(
    val day: Int,
    val fajr: PrayerTime,
    val dhuhr: PrayerTime,
    val asr: PrayerTime,
    val maghrib: PrayerTime,
    val isha: PrayerTime
) {
    fun allPrayers(): List<PrayerTime> = listOf(fajr, dhuhr, asr, maghrib, isha)
}

enum class SilenceMode { DURATION, FIXED_TIME }

enum class DelayMode { MINUTES, FIXED_TIME }

data class PrayerSilenceConfig(
    val mode: SilenceMode = SilenceMode.DURATION,
    val afterMinutes: Int = 30,
    val fixedHour: Int = -1,
    val fixedMinute: Int = -1,
    val delayMode: DelayMode = DelayMode.MINUTES,
    val delayMinutes: Int = 0,
    val delayFixedHour: Int = -1,
    val delayFixedMinute: Int = -1
)
