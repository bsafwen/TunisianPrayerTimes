package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.HijriCalendarDate
import com.tunisianprayertimes.IslamicDay
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.mosque.FastCountdown.Kind
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DayBannerTest {

    private val day1 = LocalDate.of(2027, 2, 8)
    private val day2 = day1.plusDays(1)

    private fun times(fajr: String, maghrib: String = "18:00"): DayPrayerTimes {
        fun t(prayer: Prayer, hm: String) = LocalTime.parse(hm).let { PrayerTime(prayer, it.hour, it.minute) }
        return DayPrayerTimes(
            day = 1, fajr = t(Prayer.FAJR, fajr), shurukHour = 7, shurukMinute = 0,
            dhuhr = t(Prayer.DHUHR, "12:30"), asr = t(Prayer.ASR, "15:30"),
            maghrib = t(Prayer.MAGHRIB, maghrib), isha = t(Prayer.ISHA, "19:30"),
        )
    }

    private fun day(date: LocalDate, month: Int, dayOfMonth: Int) = IslamicDay(
        date, HijriCalendarDate(1448, month, dayOfMonth, false),
        ramadanDay = dayOfMonth.takeIf { month == 9 },
        isArafah = month == 12 && dayOfMonth == 9,
        isEidFitr = month == 10 && dayOfMonth == 1,
        isEidAdha = month == 12 && dayOfMonth == 10,
    )

    private fun at(date: LocalDate, hm: String): LocalDateTime = date.atTime(LocalTime.parse(hm))

    @Test
    fun aRamadanDayCountsDownToSuhoorThenIftarThenTomorrowsSuhoor() {
        val today = day(day1, 9, 1)
        val tomorrow = day(day2, 9, 2)
        val t = times("05:30")
        val next = times("05:29")
        fun at(hm: String) = DayBanners.at(at(day1, hm), today, tomorrow, t, next)
        assertEquals(DayBanner.Ramadan(FastCountdown(Kind.SUHOOR_ENDS, at(day1, "05:30"))), at("03:00"))
        assertEquals(DayBanner.Ramadan(FastCountdown(Kind.IFTAR, at(day1, "18:00"))), at("05:30"))
        assertEquals(DayBanner.Ramadan(FastCountdown(Kind.IFTAR, at(day1, "18:00"))), at("17:59"))
        // After iftar the next fast's suhoor, with tomorrow's Fajr.
        assertEquals(DayBanner.Ramadan(FastCountdown(Kind.SUHOOR_ENDS, at(day2, "05:29"))), at("18:00"))
    }

    @Test
    fun theEveningBeforeRamadanAlreadyCountsDownToTheFirstSuhoor() {
        val eve = day(day1, 8, 29)
        val first = day(day2, 9, 1)
        val t = times("05:30")
        assertNull(DayBanners.at(at(day1, "12:00"), eve, first, t, t))
        assertEquals(
            DayBanner.Ramadan(FastCountdown(Kind.SUHOOR_ENDS, at(day2, "05:30"))),
            DayBanners.at(at(day1, "18:30"), eve, first, t, t),
        )
    }

    @Test
    fun theLastEveningOfRamadanHasNoCountdown() {
        val last = day(day1, 9, 30)
        val eid = day(day2, 10, 1)
        val t = times("05:30")
        assertEquals(DayBanner.Ramadan(null), DayBanners.at(at(day1, "19:00"), last, eid, t, t))
    }

    @Test
    fun eidShowsItsPrayerUntilItBeginsAndArafahIsNamed() {
        val eid = day(day1, 10, 1)
        val prayerAt = at(day1, "07:30")
        assertEquals(DayBanner.Eid(Prayer.AID_FITR, prayerAt), DayBanners.at(at(day1, "06:00"), eid, day(day2, 10, 2), null, null, prayerAt))
        assertEquals(DayBanner.Eid(Prayer.AID_FITR, null), DayBanners.at(at(day1, "08:00"), eid, day(day2, 10, 2), null, null, prayerAt))
        assertEquals(DayBanner.Eid(Prayer.AID_ADHA, null), DayBanners.at(at(day1, "08:00"), day(day1, 12, 10), day(day2, 12, 11), null, null))
        assertEquals(DayBanner.Arafah, DayBanners.at(at(day1, "08:00"), day(day1, 12, 9), day(day2, 12, 10), null, null))
        assertNull(DayBanners.at(at(day1, "08:00"), day(day1, 11, 3), day(day2, 11, 4), times("05:30"), null))
    }
}
