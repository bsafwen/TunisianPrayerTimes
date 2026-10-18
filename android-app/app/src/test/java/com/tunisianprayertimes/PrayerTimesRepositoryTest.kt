package com.tunisianprayertimes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30, 33, 34])
class PrayerTimesRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun loadPrayerTimes_tunis_returnsDataForMonth() {
        // Delegation 615 = Tunis, load current year/month
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val times = PrayerTimesRepository.loadPrayerTimes(context, 615, year, month)

        assertTrue("Should have at least 28 days", times.size >= 28)
        assertTrue("Should have at most 31 days", times.size <= 31)
    }

    @Test
    fun loadPrayerTimes_validTimesRange() {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val times = PrayerTimesRepository.loadPrayerTimes(context, 615, year, month)

        for (dayTimes in times) {
            // Fajr should be early morning (3-7 AM)
            assertTrue("Fajr hour should be 3-7, got ${dayTimes.fajr.hour}",
                dayTimes.fajr.hour in 3..7)

            // Dhuhr should be around noon (11-14)
            assertTrue("Dhuhr hour should be 11-14, got ${dayTimes.dhuhr.hour}",
                dayTimes.dhuhr.hour in 11..14)

            // Asr should be afternoon (13-17)
            assertTrue("Asr hour should be 13-17, got ${dayTimes.asr.hour}",
                dayTimes.asr.hour in 13..17)

            // Maghrib should be evening (17-21)
            assertTrue("Maghrib hour should be 17-21, got ${dayTimes.maghrib.hour}",
                dayTimes.maghrib.hour in 17..21)

            // Isha should be night (18-23)
            assertTrue("Isha hour should be 18-23, got ${dayTimes.isha.hour}",
                dayTimes.isha.hour in 18..23)

            // All minutes should be valid
            for (pt in dayTimes.allPrayers()) {
                assertTrue("Minute should be 0-59", pt.minute in 0..59)
            }
        }
    }

    @Test
    fun loadDayPrayerTimes_returnsCorrectDay() {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val dayTimes = PrayerTimesRepository.loadDayPrayerTimes(context, 615, year, month, 1)

        assertNotNull("Day 1 should exist", dayTimes)
        assertEquals(1, dayTimes!!.day)
        assertEquals(5, dayTimes.allPrayers().size)
    }

    @Test
    fun loadDayPrayerTimes_invalidDay_returnsNull() {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val dayTimes = PrayerTimesRepository.loadDayPrayerTimes(context, 615, year, month, 99)

        assertNull("Day 99 should not exist", dayTimes)
    }

    @Test
    fun loadPrayerTimes_prayerOrder_isChronological() {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val times = PrayerTimesRepository.loadPrayerTimes(context, 615, year, month)

        for (dayTimes in times) {
            val fajrMin = dayTimes.fajr.hour * 60 + dayTimes.fajr.minute
            val dhuhrMin = dayTimes.dhuhr.hour * 60 + dayTimes.dhuhr.minute
            val asrMin = dayTimes.asr.hour * 60 + dayTimes.asr.minute
            val maghribMin = dayTimes.maghrib.hour * 60 + dayTimes.maghrib.minute
            val ishaMin = dayTimes.isha.hour * 60 + dayTimes.isha.minute

            assertTrue("Fajr < Dhuhr", fajrMin < dhuhrMin)
            assertTrue("Dhuhr < Asr", dhuhrMin < asrMin)
            assertTrue("Asr < Maghrib", asrMin < maghribMin)
            assertTrue("Maghrib < Isha", maghribMin < ishaMin)
        }
    }

    @Test
    fun loadPrayerTimes_multipleDelegations_allLoad() {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1

        // Test a few known delegations
        val delegations = listOf(386, 400, 500, 600, 615)
        for (id in delegations) {
            val times = PrayerTimesRepository.loadPrayerTimes(context, id, year, month)
            assertTrue("Delegation $id should have data", times.isNotEmpty())
        }
    }

    @Test
    fun jumuaAnHourBeforeAsrFollowsEachFridaysAsr() {
        // About one Tunisian user in ten sets Jumu'a an hour before Asr, their mosque's time: it follows Asr every
        // Friday (13:30-ish in winter, about 15:00 in summer), and a fixed time or the default replaces it.
        val tunis = com.tunisianprayertimes.place.PrayerPlace.Inm(615)
        PrefsManager.setJumuaMinutesBeforeAsr(context, tunis, PrayerTimesRepository.JUMUA_MINUTES_BEFORE_ASR)
        for (friday in listOf(java.time.LocalDate.of(2026, 1, 2), java.time.LocalDate.of(2026, 7, 3))) {
            val asr = requireNotNull(PrayerTimesRepository.prayerDay(context, friday, tunis)?.get(com.tunisianprayertimes.engine.TimeEvent.ASR)?.at)
            assertEquals(asr.minusSeconds(3600), PrayerTimesRepository.jumuaTime(context, friday, tunis))
        }
        val july3 = java.time.LocalDate.of(2026, 7, 3)
        PrefsManager.setJumuaTime(context, tunis, java.time.LocalTime.of(13, 30))
        assertNull(PrefsManager.getJumuaMinutesBeforeAsr(context, tunis))
        assertEquals(java.time.ZonedDateTime.of(july3, java.time.LocalTime.of(13, 30), tunis.zone).toInstant(), PrayerTimesRepository.jumuaTime(context, july3, tunis))
        PrefsManager.setJumuaMinutesBeforeAsr(context, tunis, 60)
        assertNull(PrefsManager.getJumuaTime(context, tunis))
        PrefsManager.setJumuaTime(context, tunis, null)
        assertNull(PrefsManager.getJumuaMinutesBeforeAsr(context, tunis))
        assertEquals(PrayerTimesRepository.prayerDay(context, july3, tunis)?.get(com.tunisianprayertimes.engine.TimeEvent.DHUHR)?.at, PrayerTimesRepository.jumuaTime(context, july3, tunis))
        // Trondheim in December: Asr comes less than an hour after Dhuhr, so Jumu'a holds at Dhuhr.
        val point = com.tunisianprayertimes.place.LatLngE5.of(63.4305, 10.3951)
        val trondheim = com.tunisianprayertimes.place.PrayerPlace.Coordinates(point.latE5, point.lngE5, null, "Europe/Oslo", com.tunisianprayertimes.place.ZoneSource.USER, "NO")
        PrefsManager.setJumuaMinutesBeforeAsr(context, trondheim, 60)
        val december = java.time.LocalDate.of(2026, 12, 18)
        val day = requireNotNull(PrayerTimesRepository.prayerDay(context, december, trondheim))
        val dhuhr = requireNotNull(day[com.tunisianprayertimes.engine.TimeEvent.DHUHR].at)
        assertTrue(requireNotNull(day[com.tunisianprayertimes.engine.TimeEvent.ASR].at).minusSeconds(3600) < dhuhr)
        assertEquals(dhuhr, PrayerTimesRepository.jumuaTime(context, december, trondheim))
        PrefsManager.setJumuaTime(context, trondheim, null)
    }
}
