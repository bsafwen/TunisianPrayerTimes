package com.tunisianprayertimes

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

/**
 * Instrumented tests for PrayerTimesRepository on a real device.
 * Verifies CSV asset loading works correctly across Android versions.
 */
@RunWith(AndroidJUnit4::class)
class PrayerTimesRepositoryInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun loadPrayerTimes_tunis_returnsValidData() {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val month = Calendar.getInstance().get(Calendar.MONTH) + 1
        val times = PrayerTimesRepository.loadPrayerTimes(context, 615, year, month)

        assertTrue("Should have days for the month", times.size in 28..31)
    }

    @Test
    fun loadDayPrayerTimes_today_returnsCurrentDay() {
        val now = Calendar.getInstance()
        val dayTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, 615,
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH)
        )

        assertNotNull("Today's prayer times should exist", dayTimes)
        assertEquals(5, dayTimes!!.allPrayers().size)
    }

    @Test
    fun prayerTimes_areChronologicallyOrdered() {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val month = Calendar.getInstance().get(Calendar.MONTH) + 1
        val times = PrayerTimesRepository.loadPrayerTimes(context, 615, year, month)

        for (day in times) {
            val minutes = day.allPrayers().map { it.hour * 60 + it.minute }
            for (i in 0 until minutes.size - 1) {
                assertTrue("Prayers should be chronological: ${day.allPrayers()[i].prayer} < ${day.allPrayers()[i+1].prayer}",
                    minutes[i] < minutes[i + 1])
            }
        }
    }

    @Test
    fun multipleDelegations_loadSuccessfully() {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val month = Calendar.getInstance().get(Calendar.MONTH) + 1
        val delegations = listOf(386, 400, 450, 500, 550, 600, 615)

        for (id in delegations) {
            val times = PrayerTimesRepository.loadPrayerTimes(context, id, year, month)
            assertTrue("Delegation $id should have data", times.isNotEmpty())
        }
    }
}
