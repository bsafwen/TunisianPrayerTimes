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

data class PrayerSilenceConfig(
    val afterMinutes: Int = 30
)
