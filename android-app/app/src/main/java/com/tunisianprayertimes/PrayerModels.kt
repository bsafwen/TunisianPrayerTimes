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

    /**
     * Returns the effective prayer list for scheduling on the given day.
     * On Fridays, DHUHR is replaced by JOMOAA (same time, different config).
     */
    fun scheduledPrayers(isFriday: Boolean): List<PrayerTime> =
        if (isFriday) listOf(fajr, PrayerTime(Prayer.JOMOAA, dhuhr.hour, dhuhr.minute), asr, maghrib, isha)
        else allPrayers()

    /**
     * Returns the next upcoming prayer based on the given time (hour:minute).
     * Returns null if all prayers have passed.
     */
    fun nextPrayer(currentHour: Int, currentMinute: Int): Prayer? {
        val nowMinutes = currentHour * 60 + currentMinute
        return allPrayers().firstOrNull { pt ->
            pt.hour * 60 + pt.minute > nowMinutes
        }?.prayer
    }

    /**
     * Friday-aware next prayer calculation.
     * On Fridays, DHUHR is replaced by JOMOAA.
     */
    fun nextPrayer(currentHour: Int, currentMinute: Int, isFriday: Boolean): Prayer? {
        val nowMinutes = currentHour * 60 + currentMinute
        return scheduledPrayers(isFriday).firstOrNull { pt ->
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
