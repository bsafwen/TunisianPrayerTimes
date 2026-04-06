package com.tunisianprayertimes

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for DayPrayerTimes.nextPrayer() — the logic that determines
 * which prayer to highlight based on the current time.
 */
class NextPrayerTest {

    private val day = DayPrayerTimes(
        day = 1,
        fajr = PrayerTime(Prayer.FAJR, 5, 0),
        shurukHour = 6,
        shurukMinute = 30,
        dhuhr = PrayerTime(Prayer.DHUHR, 12, 30),
        asr = PrayerTime(Prayer.ASR, 15, 45),
        maghrib = PrayerTime(Prayer.MAGHRIB, 18, 15),
        isha = PrayerTime(Prayer.ISHA, 19, 45)
    )

    // ── Before any prayer ───────────────────────

    @Test
    fun at_midnight_nextPrayer_isFajr() {
        assertEquals(Prayer.FAJR, day.nextPrayer(0, 0))
    }

    @Test
    fun at_1am_nextPrayer_isFajr() {
        assertEquals(Prayer.FAJR, day.nextPrayer(1, 0))
    }

    @Test
    fun at_4_59_nextPrayer_isFajr() {
        assertEquals(Prayer.FAJR, day.nextPrayer(4, 59))
    }

    // ── Exactly at prayer time ──────────────────

    @Test
    fun exactly_at_fajr_nextPrayer_isDhuhr() {
        // At 5:00 exactly, fajr (5:00) is NOT > 5:00, so next is dhuhr
        assertEquals(Prayer.DHUHR, day.nextPrayer(5, 0))
    }

    @Test
    fun exactly_at_dhuhr_nextPrayer_isAsr() {
        assertEquals(Prayer.ASR, day.nextPrayer(12, 30))
    }

    @Test
    fun exactly_at_asr_nextPrayer_isMaghrib() {
        assertEquals(Prayer.MAGHRIB, day.nextPrayer(15, 45))
    }

    @Test
    fun exactly_at_maghrib_nextPrayer_isIsha() {
        assertEquals(Prayer.ISHA, day.nextPrayer(18, 15))
    }

    @Test
    fun exactly_at_isha_nextPrayer_isNull() {
        assertNull(day.nextPrayer(19, 45))
    }

    // ── Between prayers ─────────────────────────

    @Test
    fun between_fajr_and_dhuhr_nextPrayer_isDhuhr() {
        assertEquals(Prayer.DHUHR, day.nextPrayer(9, 0))
    }

    @Test
    fun between_dhuhr_and_asr_nextPrayer_isAsr() {
        assertEquals(Prayer.ASR, day.nextPrayer(14, 0))
    }

    @Test
    fun between_asr_and_maghrib_nextPrayer_isMaghrib() {
        assertEquals(Prayer.MAGHRIB, day.nextPrayer(17, 0))
    }

    @Test
    fun between_maghrib_and_isha_nextPrayer_isIsha() {
        assertEquals(Prayer.ISHA, day.nextPrayer(19, 0))
    }

    // ── After all prayers ───────────────────────

    @Test
    fun after_isha_nextPrayer_isNull() {
        assertNull(day.nextPrayer(20, 0))
    }

    @Test
    fun at_23_59_nextPrayer_isNull() {
        assertNull(day.nextPrayer(23, 59))
    }

    // ── One minute before each prayer ───────────

    @Test
    fun oneMinBefore_fajr_isFajr() {
        assertEquals(Prayer.FAJR, day.nextPrayer(4, 59))
    }

    @Test
    fun oneMinBefore_dhuhr_isDhuhr() {
        assertEquals(Prayer.DHUHR, day.nextPrayer(12, 29))
    }

    @Test
    fun oneMinBefore_asr_isAsr() {
        assertEquals(Prayer.ASR, day.nextPrayer(15, 44))
    }

    @Test
    fun oneMinBefore_maghrib_isMaghrib() {
        assertEquals(Prayer.MAGHRIB, day.nextPrayer(18, 14))
    }

    @Test
    fun oneMinBefore_isha_isIsha() {
        assertEquals(Prayer.ISHA, day.nextPrayer(19, 44))
    }

    // ── One minute after each prayer ────────────

    @Test
    fun oneMinAfter_fajr_isDhuhr() {
        assertEquals(Prayer.DHUHR, day.nextPrayer(5, 1))
    }

    @Test
    fun oneMinAfter_isha_isNull() {
        assertNull(day.nextPrayer(19, 46))
    }

    // ── Edge case: tight schedule ───────────────

    @Test
    fun tightSchedule_consecutiveMinutes() {
        val tight = DayPrayerTimes(
            day = 1,
            fajr = PrayerTime(Prayer.FAJR, 5, 0),
            shurukHour = 5,
            shurukMinute = 0,
            dhuhr = PrayerTime(Prayer.DHUHR, 5, 1),
            asr = PrayerTime(Prayer.ASR, 5, 2),
            maghrib = PrayerTime(Prayer.MAGHRIB, 5, 3),
            isha = PrayerTime(Prayer.ISHA, 5, 4)
        )
        assertEquals(Prayer.FAJR, tight.nextPrayer(4, 59))
        assertEquals(Prayer.DHUHR, tight.nextPrayer(5, 0))
        assertEquals(Prayer.ASR, tight.nextPrayer(5, 1))
        assertEquals(Prayer.MAGHRIB, tight.nextPrayer(5, 2))
        assertEquals(Prayer.ISHA, tight.nextPrayer(5, 3))
        assertNull(tight.nextPrayer(5, 4))
    }
}
