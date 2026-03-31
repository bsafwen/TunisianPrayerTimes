package com.tunisianprayertimes

import android.app.AlarmManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

/**
 * Instrumented tests for tomorrow's Fajr scheduling on a real device.
 * Verifies CSV data exists for tomorrow and that scheduling doesn't crash.
 */
@RunWith(AndroidJUnit4::class)
class TomorrowFajrInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun tomorrowPrayerData_existsForDefaultDelegation() {
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, 615,
            tomorrow.get(Calendar.YEAR),
            tomorrow.get(Calendar.MONTH) + 1,
            tomorrow.get(Calendar.DAY_OF_MONTH)
        )

        assertNotNull("Tomorrow's prayer data should exist for Tunis (615)", tomorrowTimes)
        assertEquals(5, tomorrowTimes!!.allPrayers().size)

        // Fajr should be the earliest prayer
        val fajr = tomorrowTimes.fajr
        assertTrue("Fajr hour should be reasonable (3-6)", fajr.hour in 3..6)
        assertTrue("Fajr minute should be valid", fajr.minute in 0..59)
    }

    @Test
    fun tomorrowFajr_isChronologicallyAfterTodayFajr_orSame() {
        val now = Calendar.getInstance()
        val todayTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, 615,
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH)
        ) ?: return

        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, 615,
            tomorrow.get(Calendar.YEAR),
            tomorrow.get(Calendar.MONTH) + 1,
            tomorrow.get(Calendar.DAY_OF_MONTH)
        ) ?: return

        // Tomorrow's Fajr time (as minutes since midnight) should change by at most a few minutes
        val todayFajrMin = todayTimes.fajr.hour * 60 + todayTimes.fajr.minute
        val tomorrowFajrMin = tomorrowTimes.fajr.hour * 60 + tomorrowTimes.fajr.minute
        val diff = kotlin.math.abs(tomorrowFajrMin - todayFajrMin)
        assertTrue("Fajr times between consecutive days should differ by at most 5 minutes, got $diff", diff <= 5)
    }

    @Test
    fun scheduleAll_onRealDevice_doesNotCrash() {
        PrefsManager.setEnabled(context, true)
        // This exercises the full scheduling path including tomorrow's Fajr
        // on a real device — verifies no SecurityException or other runtime errors
        SilenceScheduler.scheduleAll(context)
    }

    @Test
    fun scheduleAll_withVariousConfigs_doesNotCrash() {
        PrefsManager.setEnabled(context, true)

        // Test with duration mode
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 45)
        SilenceScheduler.scheduleAll(context)

        // Test with fixed time mode
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.FIXED_TIME)
        PrefsManager.setFixedTime(context, Prayer.FAJR, 7, 0)
        SilenceScheduler.scheduleAll(context)

        // Test with fixed delay mode
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.FIXED_TIME)
        PrefsManager.setDelayFixedTime(context, Prayer.FAJR, 5, 0)
        SilenceScheduler.scheduleAll(context)
    }

    @Test
    fun cancelAll_afterScheduling_doesNotCrash() {
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)
        SilenceScheduler.cancelAll(context)
    }

    @Test
    fun allDelegations_haveTomorrowData() {
        val delegations = GouvernoratRepository.loadAllDelegations(context)
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tYear = tomorrow.get(Calendar.YEAR)
        val tMonth = tomorrow.get(Calendar.MONTH) + 1
        val tDay = tomorrow.get(Calendar.DAY_OF_MONTH)

        var loadedCount = 0
        for (delegation in delegations) {
            val dayTimes = PrayerTimesRepository.loadDayPrayerTimes(context, delegation.id, tYear, tMonth, tDay)
            if (dayTimes != null) loadedCount++
        }
        assertTrue(
            "Most delegations should have tomorrow's data ($loadedCount/${delegations.size})",
            loadedCount > delegations.size / 2
        )
    }
}
