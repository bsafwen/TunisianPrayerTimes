package com.tunisianprayertimes

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the overlap validation logic.
 *
 * [SilenceAlarmComputer.overlapsNextPrayer] checks whether a prayer's silence
 * window (delay + duration or fixed end time) extends past the next prayer's
 * start time. Pure computation — no Android dependencies.
 */
class OverlapValidationTest {

    // --- Test fixtures (typical Tunisian April prayer times) ---

    private val fajr = PrayerTime(Prayer.FAJR, 4, 35)
    private val dhuhr = PrayerTime(Prayer.DHUHR, 12, 30)
    private val asr = PrayerTime(Prayer.ASR, 15, 57)
    private val maghrib = PrayerTime(Prayer.MAGHRIB, 18, 45)
    private val isha = PrayerTime(Prayer.ISHA, 20, 15)

    // ==================== DURATION mode ====================

    @Test
    fun duration_noOverlap_returnsFalse() {
        // Fajr 04:35 + 60min = 05:35, next is Dhuhr 12:30
        val config = PrayerSilenceConfig(afterMinutes = 60)
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(fajr, dhuhr, config))
    }

    @Test
    fun duration_exactlyAtNextPrayer_returnsFalse() {
        // Maghrib 18:45 + 90min = 20:15, Isha at 20:15 → not overlap (equal is fine)
        val config = PrayerSilenceConfig(afterMinutes = 90)
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(maghrib, isha, config))
    }

    @Test
    fun duration_exceedsNextPrayer_returnsTrue() {
        // Maghrib 18:45 + 91min = 20:16, Isha at 20:15 → overlap by 1 min
        val config = PrayerSilenceConfig(afterMinutes = 91)
        assertTrue(SilenceAlarmComputer.overlapsNextPrayer(maghrib, isha, config))
    }

    @Test
    fun duration_largeOverlap_returnsTrue() {
        // Asr 15:57 + 200min = 19:17, Maghrib at 18:45 → overlap by 32 min
        val config = PrayerSilenceConfig(afterMinutes = 200)
        assertTrue(SilenceAlarmComputer.overlapsNextPrayer(asr, maghrib, config))
    }

    @Test
    fun duration_zeroDuration_returnsFalse() {
        val config = PrayerSilenceConfig(afterMinutes = 0)
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(dhuhr, asr, config))
    }

    // ==================== DURATION mode + delay minutes ====================

    @Test
    fun durationWithDelay_noOverlap_returnsFalse() {
        // Maghrib 18:45 + 5min delay = 18:50 + 30min = 19:20, Isha 20:15
        val config = PrayerSilenceConfig(delayMinutes = 5, afterMinutes = 30)
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(maghrib, isha, config))
    }

    @Test
    fun durationWithDelay_overlaps_returnsTrue() {
        // Maghrib 18:45 + 10min delay = 18:55 + 85min = 20:20, Isha 20:15 → overlap
        val config = PrayerSilenceConfig(delayMinutes = 10, afterMinutes = 85)
        assertTrue(SilenceAlarmComputer.overlapsNextPrayer(maghrib, isha, config))
    }

    @Test
    fun durationWithDelay_exactlyAtNextPrayer_returnsFalse() {
        // Maghrib 18:45 + 0min delay + 90min = 20:15, Isha 20:15 → not overlap
        val config = PrayerSilenceConfig(delayMinutes = 0, afterMinutes = 90)
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(maghrib, isha, config))
    }

    // ==================== DURATION mode + fixed-time delay ====================

    @Test
    fun durationWithFixedDelay_noOverlap_returnsFalse() {
        // Maghrib fixed delay at 19:00, + 30min = 19:30, Isha 20:15
        val config = PrayerSilenceConfig(
            delayMode = DelayMode.FIXED_TIME,
            delayFixedHour = 19, delayFixedMinute = 0,
            afterMinutes = 30
        )
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(maghrib, isha, config))
    }

    @Test
    fun durationWithFixedDelay_overlaps_returnsTrue() {
        // Maghrib fixed delay at 19:30, + 60min = 20:30, Isha 20:15 → overlap
        val config = PrayerSilenceConfig(
            delayMode = DelayMode.FIXED_TIME,
            delayFixedHour = 19, delayFixedMinute = 30,
            afterMinutes = 60
        )
        assertTrue(SilenceAlarmComputer.overlapsNextPrayer(maghrib, isha, config))
    }

    // ==================== FIXED_TIME end mode ====================

    @Test
    fun fixedTime_beforeNextPrayer_returnsFalse() {
        // Dhuhr with fixed end at 15:00, Asr at 15:57
        val config = PrayerSilenceConfig(
            mode = SilenceMode.FIXED_TIME,
            fixedHour = 15, fixedMinute = 0
        )
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(dhuhr, asr, config))
    }

    @Test
    fun fixedTime_exactlyAtNextPrayer_returnsFalse() {
        // Dhuhr with fixed end at 15:57, Asr at 15:57 → not overlap
        val config = PrayerSilenceConfig(
            mode = SilenceMode.FIXED_TIME,
            fixedHour = 15, fixedMinute = 57
        )
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(dhuhr, asr, config))
    }

    @Test
    fun fixedTime_afterNextPrayer_returnsTrue() {
        // Dhuhr with fixed end at 16:00, Asr at 15:57 → overlap by 3 min
        val config = PrayerSilenceConfig(
            mode = SilenceMode.FIXED_TIME,
            fixedHour = 16, fixedMinute = 0
        )
        assertTrue(SilenceAlarmComputer.overlapsNextPrayer(dhuhr, asr, config))
    }

    @Test
    fun fixedTime_withInvalidHour_fallsBackToDuration() {
        // fixedHour = -1 means unset → falls back to duration mode
        val config = PrayerSilenceConfig(
            mode = SilenceMode.FIXED_TIME,
            fixedHour = -1, fixedMinute = 30,
            afterMinutes = 30
        )
        // Dhuhr 12:30 + 30min = 13:00, Asr 15:57 → no overlap
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(dhuhr, asr, config))
    }

    // ==================== Edge cases ====================

    @Test
    fun consecutivePrayers_tightSchedule_noOverlap() {
        // Prayers 1 minute apart, 1 min duration
        val p1 = PrayerTime(Prayer.FAJR, 5, 0)
        val p2 = PrayerTime(Prayer.DHUHR, 5, 1)
        val config = PrayerSilenceConfig(afterMinutes = 1)
        // 05:00 + 1min = 05:01, next at 05:01 → not overlap (equal)
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(p1, p2, config))
    }

    @Test
    fun consecutivePrayers_tightSchedule_overlaps() {
        val p1 = PrayerTime(Prayer.FAJR, 5, 0)
        val p2 = PrayerTime(Prayer.DHUHR, 5, 1)
        val config = PrayerSilenceConfig(afterMinutes = 2)
        // 05:00 + 2min = 05:02, next at 05:01 → overlap
        assertTrue(SilenceAlarmComputer.overlapsNextPrayer(p1, p2, config))
    }

    @Test
    fun allPrayerPairs_defaultConfig_noOverlap() {
        // Default 30 min duration should never overlap between standard prayer times
        val config = PrayerSilenceConfig()
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(fajr, dhuhr, config))
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(dhuhr, asr, config))
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(asr, maghrib, config))
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(maghrib, isha, config))
    }

    @Test
    fun maghribToIsha_closestGap_variousDurations() {
        // Maghrib 18:45 → Isha 20:15: gap is 90 minutes
        val noOverlap = PrayerSilenceConfig(afterMinutes = 89)
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(maghrib, isha, noOverlap))

        val exact = PrayerSilenceConfig(afterMinutes = 90)
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(maghrib, isha, exact))

        val overlap = PrayerSilenceConfig(afterMinutes = 91)
        assertTrue(SilenceAlarmComputer.overlapsNextPrayer(maghrib, isha, overlap))
    }

    @Test
    fun fixedTimeDelay_withFixedTimeEnd_overlaps() {
        // Fixed delay 19:00, fixed end 20:30, Isha at 20:15 → overlap
        val config = PrayerSilenceConfig(
            mode = SilenceMode.FIXED_TIME,
            fixedHour = 20, fixedMinute = 30,
            delayMode = DelayMode.FIXED_TIME,
            delayFixedHour = 19, delayFixedMinute = 0
        )
        assertTrue(SilenceAlarmComputer.overlapsNextPrayer(maghrib, isha, config))
    }

    @Test
    fun fixedTimeDelay_withFixedTimeEnd_noOverlap() {
        // Fixed delay 19:00, fixed end 20:10, Isha at 20:15 → no overlap
        val config = PrayerSilenceConfig(
            mode = SilenceMode.FIXED_TIME,
            fixedHour = 20, fixedMinute = 10,
            delayMode = DelayMode.FIXED_TIME,
            delayFixedHour = 19, delayFixedMinute = 0
        )
        assertFalse(SilenceAlarmComputer.overlapsNextPrayer(maghrib, isha, config))
    }
}
