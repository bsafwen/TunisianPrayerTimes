package com.tunisianprayertimes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.platform.PrayerDataLoader
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Eid row's default time comes from the shared Android PrayerDataLoader. It read CSV
 * assets the app no longer ships and silently returned no time; it now uses the formula.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EidDefaultTimeTest {

    @Test
    fun defaultEidPrayerTimeIsTheEidDaysShurukInAnySupportedYear() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PrayerDataLoader.init(context)
        for (date in listOf(LocalDate.of(2026, 3, 20), LocalDate.of(2027, 3, 10), LocalDate.of(2040, 11, 5))) {
            val day = PrayerTimesRepository.loadDayPrayerTimes(context, 615, date.year, date.monthValue, date.dayOfMonth)!!
            assertEquals(
                "Eid default on $date",
                day.shurukHour to day.shurukMinute,
                RamadanOverrideChecker.getDefaultEidPrayerTime(615, date),
            )
        }
    }
}
