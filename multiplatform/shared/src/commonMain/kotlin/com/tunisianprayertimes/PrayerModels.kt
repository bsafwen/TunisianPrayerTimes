package com.tunisianprayertimes

/**
 * Prayer names matching the CSV columns (excluding Shuruk which is sunrise, not a prayer).
 * JOMOAA is the Friday prayer — it reuses the DHUHR time slot but has its own silence config.
 */
enum class Prayer {
    FAJR, DHUHR, ASR, MAGHRIB, ISHA, JOMOAA
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

    fun scheduledPrayers(
        isFriday: Boolean,
        jomoaaHour: Int = -1,
        jomoaaMinute: Int = -1
    ): List<PrayerTime> =
        if (isFriday) {
            val h = if (jomoaaHour >= 0) jomoaaHour else dhuhr.hour
            val m = if (jomoaaMinute >= 0) jomoaaMinute else dhuhr.minute
            listOf(fajr, PrayerTime(Prayer.JOMOAA, h, m), asr, maghrib, isha)
        } else allPrayers()

    fun nextPrayer(currentHour: Int, currentMinute: Int): Prayer? {
        val nowMinutes = currentHour * 60 + currentMinute
        return allPrayers().firstOrNull { pt ->
            pt.hour * 60 + pt.minute > nowMinutes
        }?.prayer
    }

    fun nextPrayer(
        currentHour: Int,
        currentMinute: Int,
        isFriday: Boolean,
        jomoaaHour: Int = -1,
        jomoaaMinute: Int = -1
    ): Prayer? {
        val nowMinutes = currentHour * 60 + currentMinute
        return scheduledPrayers(isFriday, jomoaaHour, jomoaaMinute).firstOrNull { pt ->
            pt.hour * 60 + pt.minute > nowMinutes
        }?.prayer
    }
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
