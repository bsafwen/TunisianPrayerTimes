package com.tunisianprayertimes.tv.ui

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.mosque.AdhkarSlide
import com.tunisianprayertimes.mosque.DayBanner
import com.tunisianprayertimes.mosque.DisplayTexts
import com.tunisianprayertimes.mosque.FastCountdown
import com.tunisianprayertimes.mosque.MosqueAdhkar
import com.tunisianprayertimes.tv.ui.display.MainScreenModel
import com.tunisianprayertimes.tv.ui.display.MainScreenState
import com.tunisianprayertimes.tv.ui.display.WeatherIcon
import com.tunisianprayertimes.tv.ui.display.weatherIcon
import com.tunisianprayertimes.weather.WeatherNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class MainScreenModelTest {

    private fun times(day: Int, vararg hm: String): DayPrayerTimes {
        val (fajr, shuruq, dhuhr, asr, maghrib) = hm.map(LocalTime::parse)
        val isha = LocalTime.parse(hm[5])
        return DayPrayerTimes(
            day = day,
            fajr = PrayerTime(Prayer.FAJR, fajr.hour, fajr.minute), shurukHour = shuruq.hour, shurukMinute = shuruq.minute,
            dhuhr = PrayerTime(Prayer.DHUHR, dhuhr.hour, dhuhr.minute), asr = PrayerTime(Prayer.ASR, asr.hour, asr.minute),
            maghrib = PrayerTime(Prayer.MAGHRIB, maghrib.hour, maghrib.minute), isha = PrayerTime(Prayer.ISHA, isha.hour, isha.minute),
        )
    }

    // The Main board: Tuesday 29 September 2026 in La Marsa.
    private val tuesday = LocalDate.of(2026, 9, 29)
    private val today = times(29, "04:46", "06:12", "12:17", "15:32", "18:08", "19:32")
    private val tomorrow = times(30, "04:47", "06:13", "12:17", "15:31", "18:07", "19:31")
    private val iqamah = mapOf(
        Prayer.FAJR to LocalTime.of(5, 1), Prayer.DHUHR to LocalTime.of(12, 27), Prayer.ASR to LocalTime.of(15, 42),
        Prayer.MAGHRIB to LocalTime.of(18, 15), Prayer.ISHA to LocalTime.of(19, 40),
    )
    private val tomorrowFajrIqamah = LocalTime.of(5, 2)

    private fun at(
        now: LocalDateTime,
        today: DayPrayerTimes? = this.today,
        tomorrow: DayPrayerTimes? = this.tomorrow,
        iqamah: Map<Prayer, LocalTime> = this.iqamah,
        banner: DayBanner? = null,
        ramadanTomorrow: Boolean = false,
    ): MainScreenState = MainScreenModel.at(now, today, tomorrow, iqamah, tomorrowFajrIqamah, banner, ramadanTomorrow)

    private fun MainScreenState.names() = tiles.map { it.name }
    private fun MainScreenState.passed() = tiles.filter { it.passed }.map { it.name }

    @Test
    fun anAsrAfternoon() {
        val now = tuesday.atTime(14, 7, 12)
        val state = at(now)

        assertEquals(listOf("الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"), state.names())
        assertEquals(listOf("الفجر", "الشروق", "الظهر"), state.passed())
        assertEquals(listOf("العصر"), state.tiles.filter { it.next }.map { it.name })
        assertNull("the sunrise has no iqamah", state.tiles[1].iqamah)

        val hero = state.hero!!
        assertEquals("أذان العصر بعد", hero.label)
        assertEquals(tuesday.atTime(15, 32), hero.target)
        assertEquals("الإقامة 15:42", hero.detail)
        assertEquals("01:24:48", hero.countdownText(now))
        assertFalse(hero.isSoon(now))
        assertEquals(DisplayTexts.TIMES_VERSE, state.verse)
        assertNull(state.dayNote)
    }

    @Test
    fun threeMinutesBeforeMaghribTheHeroSaysSoon() {
        val now = tuesday.atTime(18, 5)
        val state = at(now)

        assertEquals("المغرب", state.next?.name)
        assertEquals(listOf("الفجر", "الشروق", "الظهر", "العصر"), state.passed())
        val hero = state.hero!!
        assertEquals("أذان المغرب بعد", hero.label)
        assertEquals("03:00", hero.countdownText(now))
        assertTrue(hero.isSoon(now))
        // Five minutes and one second before: not yet.
        assertFalse(hero.isSoon(tuesday.atTime(18, 2, 59)))
        assertTrue(hero.isSoon(tuesday.atTime(18, 3)))
    }

    @Test
    fun theCountdownRoundsUpAndReachesZeroAtTheAdhan() {
        val hero = at(tuesday.atTime(14, 0)).hero!!
        assertEquals("00:01", hero.countdownText(tuesday.atTime(15, 31, 59, 1_000_000)))
        assertEquals("00:00", hero.countdownText(tuesday.atTime(15, 32)))
        assertEquals("00:00", hero.countdownText(tuesday.atTime(15, 33)))
        assertFalse(hero.isSoon(tuesday.atTime(15, 32)))
    }

    @Test
    fun afterIshaFajrsNicheHoldsTomorrowsFajr() {
        val now = tuesday.atTime(20, 30)
        val state = at(now)

        val fajr = state.tiles.first()
        assertEquals(tuesday.plusDays(1).atTime(4, 47), fajr.at)
        assertEquals(tomorrowFajrIqamah, fajr.iqamah)
        assertTrue(fajr.next)
        assertFalse(fajr.passed)
        assertEquals(listOf("الشروق", "الظهر", "العصر", "المغرب", "العشاء"), state.passed())

        val hero = state.hero!!
        assertEquals("أذان الفجر بعد", hero.label)
        assertEquals(tuesday.plusDays(1).atTime(4, 47), hero.target)
        assertEquals("الإقامة 05:02", hero.detail)
        assertEquals("08:17:00", hero.countdownText(now))
        assertEquals("الفجر 04:47 · الإقامة 05:02", MainScreenModel.nextPrayerLine(state))
    }

    @Test
    fun afterIshaWithoutTomorrowsTimesNothingIsNext() {
        val state = at(tuesday.atTime(20, 30), tomorrow = null)
        assertEquals(6, state.tiles.size)
        assertTrue(state.tiles.all { it.passed })
        assertNull(state.next)
        assertNull(state.hero)
        assertNull(MainScreenModel.nextPrayerLine(state))
    }

    @Test
    fun beforeFajrEverythingIsAhead() {
        val state = at(tuesday.atTime(2, 0))
        assertEquals(emptyList<String>(), state.passed())
        assertEquals("الفجر", state.next?.name)
        assertEquals(tuesday.atTime(4, 46), state.next?.at)
        assertEquals("الإقامة 05:01", state.hero?.detail)
    }

    @Test
    fun onFridayDhuhrsNicheIsJumua() {
        val friday = LocalDate.of(2026, 10, 2)
        val jumua = mapOf(Prayer.FAJR to LocalTime.of(5, 4), Prayer.JOMOAA to LocalTime.of(13, 0), Prayer.ASR to LocalTime.of(15, 40))
        val now = friday.atTime(11, 47, 48)
        val state = at(now, iqamah = jumua)

        assertTrue(state.isFriday)
        assertEquals(DisplayTexts.FRIDAY_VERSE, state.verse)
        val noon = state.tiles[2]
        assertEquals(Prayer.JOMOAA, noon.prayer)
        assertEquals("الجمعة", noon.name)
        assertEquals(LocalTime.of(13, 0), noon.iqamah)
        assertTrue(noon.next)
        assertEquals("أذان الجمعة بعد", state.hero?.label)
        assertEquals("الإقامة 13:00", state.hero?.detail)
        assertEquals("29:12", state.hero?.countdownText(now))
        assertEquals("الجمعة 12:17 · الإقامة 13:00", MainScreenModel.nextPrayerLine(state))
    }

    // The Ramadan board: Sunday 21 February 2027, the 14th of Ramadan.
    private val ramadanDay = LocalDate.of(2027, 2, 21)
    private val ramadanToday = times(21, "05:32", "06:57", "12:39", "15:45", "18:17", "19:36")
    private val ramadanTomorrowTimes = times(22, "05:31", "06:56", "12:39", "15:45", "18:18", "19:37")
    private val ramadanIqamah = mapOf(
        Prayer.FAJR to LocalTime.of(5, 47), Prayer.DHUHR to LocalTime.of(12, 49), Prayer.ASR to LocalTime.of(15, 55),
        Prayer.MAGHRIB to LocalTime.of(18, 27), Prayer.ISHA to LocalTime.of(19, 50),
    )

    private fun ramadan(now: LocalDateTime, kind: FastCountdown.Kind, until: LocalDateTime, ramadanTomorrow: Boolean = true) =
        at(now, ramadanToday, ramadanTomorrowTimes, ramadanIqamah, DayBanner.Ramadan(FastCountdown(kind, until)), ramadanTomorrow)

    @Test
    fun aRamadanAfternoonCountsDownToIftar() {
        val now = ramadanDay.atTime(16, 20, 20)
        val state = ramadan(now, FastCountdown.Kind.IFTAR, ramadanDay.atTime(18, 17))

        assertTrue(state.isRamadan)
        assertEquals(DisplayTexts.RAMADAN_VERSE, state.verse)
        assertEquals("المغرب", state.next?.name)
        val hero = state.hero!!
        assertEquals("الإفطار بعد", hero.label)
        assertEquals(ramadanDay.atTime(18, 17), hero.target)
        assertEquals("01:56:40", hero.countdownText(now))
        // Imsak is the Fajr adhan, as the shared banner counts it.
        assertEquals("الإمساك غدًا 05:31", hero.detail)
        assertEquals("the Fajr niche keeps its iqamah", LocalTime.of(5, 47), state.tiles.first().iqamah)
        assertEquals("ثم التراويح", state.tiles.last().note)
    }

    @Test
    fun theLastFastShowsNoImsakTomorrowAndNoTarawih() {
        val state = ramadan(ramadanDay.atTime(16, 20), FastCountdown.Kind.IFTAR, ramadanDay.atTime(18, 17), ramadanTomorrow = false)
        assertEquals("الإقامة 18:27", state.hero?.detail)
        assertNull(state.tiles.last().note)
    }

    @Test
    fun aRamadanNightCountsDownToImsak() {
        val now = ramadanDay.atTime(21, 0)
        val state = ramadan(now, FastCountdown.Kind.SUHOOR_ENDS, ramadanDay.plusDays(1).atTime(5, 31))

        val hero = state.hero!!
        assertEquals("الإمساك بعد", hero.label)
        assertEquals("08:31:00", hero.countdownText(now))
        assertEquals("الإفطار غدًا 18:18", hero.detail)
        assertEquals("الفجر", state.next?.name)
        assertEquals(ramadanDay.plusDays(1).atTime(5, 31), state.next?.at)
    }

    @Test
    fun beforeDawnInRamadanTheIftarIsToday() {
        val now = ramadanDay.atTime(3, 30)
        val state = ramadan(now, FastCountdown.Kind.SUHOOR_ENDS, ramadanDay.atTime(5, 32))
        assertEquals("الإمساك بعد", state.hero?.label)
        assertEquals("الإفطار 18:17", state.hero?.detail)
    }

    @Test
    fun ramadanWithoutACountdownKeepsTheNormalHero() {
        val state = at(ramadanDay.atTime(19, 0), ramadanToday, ramadanTomorrowTimes, ramadanIqamah, DayBanner.Ramadan(null))
        assertTrue(state.isRamadan)
        assertEquals("أذان العشاء بعد", state.hero?.label)
        assertNull("the eve of Eid has no tarawih", state.tiles.last().note)
    }

    @Test
    fun arafahAndEidAreNotedByTheDate() {
        assertEquals("يوم عرفة", at(tuesday.atTime(10, 0), banner = DayBanner.Arafah).dayNote)
        assertEquals("عيد مبارك", at(tuesday.atTime(10, 0), banner = DayBanner.Eid(Prayer.AID_FITR, null)).dayNote)
        assertEquals("أذان الظهر بعد", at(tuesday.atTime(10, 0), banner = DayBanner.Arafah).hero?.label)
    }

    @Test
    fun withoutTimesNothingCrashes() {
        val state = at(tuesday.atTime(14, 7), today = null, tomorrow = null, iqamah = emptyMap())
        assertEquals(emptyList<Any>(), state.tiles)
        assertNull(state.hero)
        assertNull(MainScreenModel.nextPrayerLine(state))

        val ramadanState = at(ramadanDay.atTime(21, 0), today = null, tomorrow = null, iqamah = emptyMap(),
            banner = DayBanner.Ramadan(FastCountdown(FastCountdown.Kind.SUHOOR_ENDS, ramadanDay.plusDays(1).atTime(5, 31))))
        assertEquals("الإمساك بعد", ramadanState.hero?.label)
        assertNull(ramadanState.hero?.detail)
        assertNull(MainScreenModel.nextPrayerLine(tuesday.atTime(14, 7), null, null, emptyMap(), null))
    }

    @Test
    fun missingIqamahsLeaveTheirLinesOut() {
        val state = at(tuesday.atTime(14, 7), iqamah = emptyMap())
        assertNull(state.hero?.detail)
        assertEquals("العصر 15:32", MainScreenModel.nextPrayerLine(state))
    }

    @Test
    fun theNextPrayerInOneLine() {
        assertEquals("العشاء 19:32 · الإقامة 19:40", MainScreenModel.nextPrayerLine(tuesday.atTime(19, 0), today, tomorrow, iqamah, tomorrowFajrIqamah))
    }

    @Test
    fun thePlace() {
        assertEquals("المرسى · تونس", MainScreenModel.placeLine("المرسى", "تونس"))
        assertEquals("صفاقس", MainScreenModel.placeLine("صفاقس", "صفاقس"))
        assertEquals("المرسى", MainScreenModel.placeLine("المرسى", null))
        assertEquals("", MainScreenModel.placeLine(" ", ""))
    }

    @Test
    fun announcementsAreToldApartFromTheAdhkar() {
        val ticker = MosqueAdhkar.tickerWithAnnouncements(MosqueAdhkar.ticker(), listOf("درس بعد العصر"), TvStrings.ANNOUNCEMENT_LABEL)
        // The one announcement comes back after every two texts; everything else is a dhikr.
        assertEquals(setOf("درس بعد العصر"), ticker.filter(MainScreenModel::isAnnouncement).map { it.text }.toSet())
        assertEquals(MosqueAdhkar.ticker(), ticker.filterNot(MainScreenModel::isAnnouncement))
        assertFalse(MainScreenModel.isAnnouncement(AdhkarSlide(null, "نص المسجد", "مصدره", 1, 6_000)))
        assertTrue(ticker.all { MainScreenModel.tickerDwellMillis(it) >= MosqueAdhkar.TICKER_MIN_SLIDE_MILLIS })
    }

    @Test
    fun theWeather() {
        assertEquals("29°", MainScreenModel.temperatureText(WeatherNow(28.6, 0, isDay = true)))
        assertEquals("-3°", MainScreenModel.temperatureText(WeatherNow(-3.2, 71, isDay = false)))
        assertEquals(WeatherIcon.SUN, weatherIcon(0, isDay = true))
        assertEquals(WeatherIcon.MOON, weatherIcon(1, isDay = false))
        assertEquals(WeatherIcon.PARTLY_CLOUDY, weatherIcon(2, isDay = true))
        assertEquals(WeatherIcon.CLOUD, weatherIcon(2, isDay = false))
        assertEquals(WeatherIcon.RAIN, weatherIcon(81, isDay = true))
        assertEquals(WeatherIcon.THUNDER, weatherIcon(95, isDay = true))
        assertNull(weatherIcon(7, isDay = true))
    }
}
