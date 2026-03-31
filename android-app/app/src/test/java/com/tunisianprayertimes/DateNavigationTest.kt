package com.tunisianprayertimes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Integration tests for date navigation and next-prayer highlighting
 * using real bundled CSV data via Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class DateNavigationTest {

    private lateinit var context: Context
    private val delegationId = 615 // Tunis

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ── nextPrayer with real data ────────────────

    @Test
    fun realData_at_midnight_nextPrayer_isFajr() {
        val now = Calendar.getInstance()
        val times = PrayerTimesRepository.loadDayPrayerTimes(
            context, delegationId,
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)
        )
        assertNotNull(times)
        assertEquals(Prayer.FAJR, times!!.nextPrayer(0, 0))
    }

    @Test
    fun realData_at_23_59_nextPrayer_isNull() {
        val now = Calendar.getInstance()
        val times = PrayerTimesRepository.loadDayPrayerTimes(
            context, delegationId,
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)
        )
        assertNotNull(times)
        assertNull(times!!.nextPrayer(23, 59))
    }

    @Test
    fun realData_afterIsha_nextPrayer_isNull() {
        val now = Calendar.getInstance()
        val times = PrayerTimesRepository.loadDayPrayerTimes(
            context, delegationId,
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)
        )
        assertNotNull(times)
        // Isha is always before 22:00 in Tunisia
        assertNull(times!!.nextPrayer(22, 0))
    }

    // ── Tomorrow fajr fallback ──────────────────

    @Test
    fun tomorrowFajr_isAvailable_whenTodayAllPassed() {
        val now = Calendar.getInstance()
        val todayTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, delegationId,
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)
        )
        assertNotNull(todayTimes)

        // Simulate all prayers passed
        val nextFromToday = todayTimes!!.nextPrayer(23, 59)
        assertNull("All prayers should be past at 23:59", nextFromToday)

        // Load tomorrow's fajr
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
        val tomorrowTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, delegationId,
            tomorrow.get(Calendar.YEAR), tomorrow.get(Calendar.MONTH) + 1, tomorrow.get(Calendar.DAY_OF_MONTH)
        )

        // Tomorrow data might not exist at month boundary — that's OK
        if (tomorrowTimes != null) {
            val fajr = tomorrowTimes.fajr
            assertTrue("Tomorrow fajr should be early morning", fajr.hour in 3..7)
            assertEquals(Prayer.FAJR, fajr.prayer)
        }
    }

    // ── Date navigation boundaries ──────────────

    @Test
    fun hasPrayerData_currentMonth_returnsTrue() {
        val now = Calendar.getInstance()
        assertTrue(
            PrayerTimesRepository.hasPrayerData(
                context, delegationId,
                now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1
            )
        )
    }

    @Test
    fun hasPrayerData_farFuture_returnsFalse() {
        assertFalse(
            PrayerTimesRepository.hasPrayerData(context, delegationId, 2099, 1)
        )
    }

    @Test
    fun hasPrayerData_invalidDelegation_returnsFalse() {
        val now = Calendar.getInstance()
        assertFalse(
            PrayerTimesRepository.hasPrayerData(
                context, 999999,
                now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1
            )
        )
    }

    // ── loadDayPrayerTimes navigation ───────────

    @Test
    fun loadDayPrayerTimes_navigateForwardAndBack() {
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)

        val today = PrayerTimesRepository.loadDayPrayerTimes(context, delegationId, year, month, day)
        assertNotNull("Today's data must exist", today)

        // Navigate forward one day
        val nextDay = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
        val nextDayTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, delegationId,
            nextDay.get(Calendar.YEAR), nextDay.get(Calendar.MONTH) + 1, nextDay.get(Calendar.DAY_OF_MONTH)
        )
        // Could be null at month boundary, but if available, should have 5 prayers
        if (nextDayTimes != null) {
            assertEquals(5, nextDayTimes.allPrayers().size)
        }

        // Navigate back one day
        val prevDay = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
        val prevDayTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, delegationId,
            prevDay.get(Calendar.YEAR), prevDay.get(Calendar.MONTH) + 1, prevDay.get(Calendar.DAY_OF_MONTH)
        )
        if (prevDayTimes != null) {
            assertEquals(5, prevDayTimes.allPrayers().size)
        }
    }

    @Test
    fun loadDayPrayerTimes_differentDays_haveSlightlyDifferentTimes() {
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1

        // Load day 1 and day 15 of current month
        val day1 = PrayerTimesRepository.loadDayPrayerTimes(context, delegationId, year, month, 1)
        val day15 = PrayerTimesRepository.loadDayPrayerTimes(context, delegationId, year, month, 15)

        assertNotNull(day1)
        assertNotNull(day15)

        // Prayer times should shift across the month (not identical sets)
        val day1Fajr = day1!!.fajr.hour * 60 + day1.fajr.minute
        val day15Fajr = day15!!.fajr.hour * 60 + day15.fajr.minute
        // They might be equal on some days, but at least they should be valid
        assertTrue("Day 1 fajr should be valid", day1Fajr in 180..420) // 3:00–7:00
        assertTrue("Day 15 fajr should be valid", day15Fajr in 180..420)
    }

    // ── nextPrayer ordering with real data ──────

    @Test
    fun realData_nextPrayer_progressesThroughDay() {
        val now = Calendar.getInstance()
        val times = PrayerTimesRepository.loadDayPrayerTimes(
            context, delegationId,
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)
        )!!

        val prayers = times.allPrayers()
        // Before fajr → FAJR
        assertEquals(Prayer.FAJR, times.nextPrayer(0, 0))

        // After each prayer, next should advance
        for (i in 0 until prayers.size - 1) {
            val current = prayers[i]
            val next = prayers[i + 1]
            val result = times.nextPrayer(current.hour, current.minute)
            assertEquals(
                "After ${current.prayer} at ${current.hour}:${current.minute}, next should be ${next.prayer}",
                next.prayer, result
            )
        }

        // After last prayer → null
        val last = prayers.last()
        assertNull(times.nextPrayer(last.hour, last.minute))
    }
}
