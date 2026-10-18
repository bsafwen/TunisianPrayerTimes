package com.tunisianprayertimes.engine.europe

import com.tunisianprayertimes.engine.EngineTestSupport.place
import com.tunisianprayertimes.engine.EventStatus
import com.tunisianprayertimes.engine.HighLatitudeRule
import com.tunisianprayertimes.engine.HighLatitudeRules
import com.tunisianprayertimes.engine.IrnRule
import com.tunisianprayertimes.engine.MoonsightingSeasonal
import com.tunisianprayertimes.engine.PrayerEngine
import com.tunisianprayertimes.engine.RuleName
import com.tunisianprayertimes.engine.SunEvaluation
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.EuropeanPresets
import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.place.LatLngE5
import com.tunisianprayertimes.place.OfficialZone
import com.tunisianprayertimes.place.PrayerPlace
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The high-latitude and polar rules of plan 7.4 (milestone M2), with the vectors and edge cases of 9.2 and 7.3. */
class HighLatitudeRulesTest {
    private val london = place(51.5074, -0.1278, "Europe/London")
    private val paris = place(48.8566, 2.3522, "Europe/Paris", country = "FR")
    private val berlin = place(52.52, 13.405, "Europe/Berlin")
    private val oslo = place(59.9139, 10.7522, "Europe/Oslo")
    private val june21 = LocalDate.of(2026, 6, 21)
    private val mwl = PrayerMethods.MWL
    private fun mwl(rule: HighLatitudeRule) = mwl.copy(id = "mwl_rule", highLatitude = rule)

    private fun clock(place: PrayerPlace.Coordinates, date: LocalDate, method: PrayerMethod, event: TimeEvent): String =
        checkNotNull(PrayerEngine.day(place, date, method)[event].at).atZone(place.zone).toLocalTime().toString()

    private fun minutesApart(a: String, b: String): Int {
        val d = abs(LocalTime.parse(a).toSecondOfDay() - LocalTime.parse(b).toSecondOfDay()) / 60
        return minOf(d, 1440 - d)
    }

    @Test
    fun moonsightingVectors() {
        // 9.2: at 51.5°N on 21 June, Fajr 119.73 min before sunrise and Isha (general) 80.44 after sunset; 21 December 101.83 and 98.97.
        assertEquals(119.73, MoonsightingSeasonal.minutes(MoonsightingSeasonal.Curve.FAJR, 51.5, june21), 0.01)
        assertEquals(80.44, MoonsightingSeasonal.minutes(MoonsightingSeasonal.Curve.ISHA_GENERAL, 51.5, june21), 0.01)
        val december = LocalDate.of(2026, 12, 21)
        assertEquals(101.83, MoonsightingSeasonal.minutes(MoonsightingSeasonal.Curve.FAJR, 51.5, december), 0.01)
        assertEquals(98.97, MoonsightingSeasonal.minutes(MoonsightingSeasonal.Curve.ISHA_GENERAL, 51.5, december), 0.01)
    }

    @Test
    fun angleBasedVectors() {
        // 9.2: Paris Isha 17° on 21 June = sunset + 133 min, 17/60 of the 469-minute night to sunrise on 22 June.
        val e = PrayerEngine.explain(paris, june21, mwl)
        val night = checkNotNull(e.nightAfter)
        assertEquals(469.0, night.hours * 60, 1.0)
        val isha = checkNotNull(e.events[TimeEvent.ISHA.ordinal].unrounded)
        assertEquals(133.0, Duration.between(night.sunset, isha).seconds / 60.0, 0.5)
        // 7.3's angle-based column, to the minute.
        assertEquals("02:37", clock(london, LocalDate.of(2026, 5, 15), mwl, TimeEvent.FAJR))
        assertEquals("23:08", clock(london, LocalDate.of(2026, 5, 15), mwl, TimeEvent.ISHA))
        for ((place, fajr, isha) in listOf(
            Triple(london, "02:31", "23:27"), Triple(paris, "03:26", "00:11"),
            Triple(berlin, "02:34", "23:35"), Triple(oslo, "02:21", "00:12"),
        )) {
            assertEquals(fajr, clock(place, june21, mwl, TimeEvent.FAJR), place.zoneId)
            assertEquals(isha, clock(place, june21, mwl, TimeEvent.ISHA), place.zoneId)
        }
    }

    @Test
    fun absentNightsRulesOnTheSolstice() {
        // 7.3's columns: middle of the night and nearest day to the minute; LRE with its ramp within 10 (an estimate).
        val middle = mwl(HighLatitudeRule.MiddleOfNight())
        val nearest = mwl(HighLatitudeRule.NearestDay())
        val lre = mwl(HighLatitudeRule.LocalRelativeEstimation)
        for ((place, m, n) in listOf(
            Triple(london, "01:02/01:02", "01:21/00:46"),
            Triple(berlin, "01:08/01:08", "01:30/00:48"),
            Triple(oslo, "01:19/01:19", "01:38/01:01"),
        )) {
            assertEquals(m, clock(place, june21, middle, TimeEvent.FAJR) + "/" + clock(place, june21, middle, TimeEvent.ISHA), place.zoneId)
            assertEquals(n, clock(place, june21, nearest, TimeEvent.FAJR) + "/" + clock(place, june21, nearest, TimeEvent.ISHA), place.zoneId)
        }
        for ((place, fajr, isha) in listOf(
            Triple(london, "03:24", "22:49"), Triple(berlin, "03:27", "22:50"), Triple(oslo, "02:56", "23:42"),
        )) {
            assertTrue(minutesApart(fajr, clock(place, june21, lre, TimeEvent.FAJR)) <= 10, "${place.zoneId} LRE Fajr")
            assertTrue(minutesApart(isha, clock(place, june21, lre, TimeEvent.ISHA)) <= 10, "${place.zoneId} LRE Isha")
        }
        assertTrue(minutesApart("02:53", clock(paris, june21, lre, TimeEvent.FAJR)) <= 15, "Paris's LRE Fajr is pure ramp")
        // Paris keeps its real 17° Isha under every absent-nights rule.
        for (m in listOf(middle, nearest, lre, mwl(HighLatitudeRule.OneSeventh))) assertEquals("01:07", clock(paris, june21, m, TimeEvent.ISHA))
    }

    @Test
    fun theGateKeepsRealAngleTimes() {
        val absentRules = listOf(
            HighLatitudeRule.AngleBasedAbsentNights, HighLatitudeRule.LocalRelativeEstimation, HighLatitudeRule.OneSeventh,
            HighLatitudeRule.MiddleOfNight(), HighLatitudeRule.MiddleOfNight(ramped = false), HighLatitudeRule.NearestDay(),
        )
        val plain = mwl.copy(id = "angles_only", highLatitude = HighLatitudeRule.OneSeventh)
        for (place in listOf(london, paris, oslo, place(36.8065, 10.1815, "Africa/Tunis"))) {
            var date = LocalDate.of(2026, 1, 3)
            while (date.year == 2026) {
                val reference = PrayerEngine.explain(place, date, plain)
                for (rule in absentRules) {
                    val e = PrayerEngine.explain(place, date, mwl(rule))
                    for (event in listOf(TimeEvent.FAJR, TimeEvent.ISHA)) {
                        val raw = e.events[event.ordinal].raw ?: continue
                        // On a night with the angle the rule returns exactly the angle time.
                        assertEquals(raw, e.events[event.ordinal].unrounded, "${place.zoneId} $date $rule $event")
                        assertEquals(reference.events[event.ordinal].raw, raw)
                    }
                }
                date = date.plusDays(9)
            }
        }
    }

    @Test
    fun absentNightsRulesNeverJump() {
        // 9.2: estimated times change by at most 5 min a day plus the sunset (or sunrise) drift.
        val rules = listOf(
            HighLatitudeRule.AngleBasedAbsentNights, HighLatitudeRule.LocalRelativeEstimation, HighLatitudeRule.OneSeventh,
            HighLatitudeRule.MiddleOfNight(), HighLatitudeRule.NearestDay(),
        )
        for (latitude in listOf(48.6, 52.0, 55.0, 60.0)) {
            val place = place(latitude, 10.0, "Europe/Berlin")
            for (rule in rules) {
                var previous: com.tunisianprayertimes.engine.EngineExplanation? = null
                var date = LocalDate.of(2026, 4, 15)
                while (date <= LocalDate.of(2026, 9, 1)) {
                    val e = PrayerEngine.explain(place, date, mwl(rule))
                    val p = previous
                    if (p != null) {
                        for (event in listOf(TimeEvent.FAJR, TimeEvent.ISHA)) {
                            val now = e.events[event.ordinal]
                            val before = p.events[event.ordinal]
                            if (now.time.status != EventStatus.ESTIMATED && before.time.status != EventStatus.ESTIMATED) continue
                            val anchorNow = if (event == TimeEvent.ISHA) e.nightAfter!!.sunset else e.nightBefore!!.sunrise
                            val anchorBefore = if (event == TimeEvent.ISHA) p.nightAfter!!.sunset else p.nightBefore!!.sunrise
                            val change = Duration.between(before.unrounded, now.unrounded).seconds - 86400
                            val drift = Duration.between(anchorBefore, anchorNow).seconds - 86400
                            assertTrue(abs(change - drift) <= 5 * 60 + 1, "$latitude°N $rule $event $date: ${change / 60.0} min against drift ${drift / 60.0}")
                        }
                    }
                    previous = e
                    date = date.plusDays(1)
                }
            }
        }
    }

    @Test
    fun summerForms() {
        // 9.2: Zagreb on 2026-06-21 under hr_miz_zagreb, Fajr = sunrise − 105; Ljubljana on 2026-05-10 under
        // si_islamska_skupnost, Fajr = sunrise − 105 and Isha = Maghrib + 90; Riyadh keeps its angle time. The printed
        // relations: the times after the profiles' offsets (113 from the computed sunrise, less sunrise's − 8, at Zagreb).
        fun check(place: PrayerPlace.Coordinates, date: LocalDate, method: PrayerMethod, fajrBefore: Double?, ishaAfter: Double?) {
            val e = PrayerEngine.explain(place, date, method)
            val sunrise = checkNotNull(e.events[TimeEvent.SUNRISE.ordinal].unrounded)
            val fajr = checkNotNull(e.events[TimeEvent.FAJR.ordinal].unrounded)
            if (fajrBefore != null) assertEquals(fajrBefore, Duration.between(fajr, sunrise).seconds / 60.0, 0.01)
            if (ishaAfter != null) {
                val maghrib = checkNotNull(e.events[TimeEvent.MAGHRIB.ordinal].unrounded)
                assertEquals(ishaAfter, Duration.between(maghrib, checkNotNull(e.events[TimeEvent.ISHA.ordinal].unrounded)).seconds / 60.0, 0.01)
            }
        }
        check(place(45.815, 15.9819, "Europe/Zagreb"), june21, EuropeanPresets.HR_MIZ_ZAGREB, 105.0, null)
        check(place(46.0569, 14.5058, "Europe/Ljubljana"), LocalDate.of(2026, 5, 10), EuropeanPresets.SI_ISLAMSKA_SKUPNOST, 105.0, 90.0)
        val riyadh = PrayerEngine.explain(place(24.7136, 46.6753, "Asia/Riyadh"), june21, EuropeanPresets.HR_MIZ_ZAGREB)
        assertEquals(EventStatus.COMPUTED, riyadh.day[TimeEvent.FAJR].status)
        val lead = Duration.between(riyadh.events[0].unrounded, riyadh.events[1].raw).seconds / 60.0
        assertTrue(lead in 90.0..100.0, "Riyadh's 19° Fajr about sunrise − 94: $lead")
    }

    @Test
    fun aSummerFormIshaTakesTheZonesShiftOnce() {
        // H5: Celje is Ljubljana's table plus its published shifts (Maghrib and Isha − 4). In the summer form's dates
        // Isha = Maghrib + 72 at Ljubljana, so Celje's Isha is Ljubljana's − 4, not − 8; outside them the rule keeps its name.
        val sight = LatLngE5(4576608, 1441841)
        val ljubljana = OfficialZone("si_islamska_skupnost", "ljubljana", "ليوبليانا", LatLngE5(4605108, 1450513), morningPoint = sight, eveningPoint = sight)
        val celje = OfficialZone(
            "si_islamska_skupnost", "celje", "تسليي", LatLngE5(4623092, 1526044), baseZoneId = "ljubljana",
            offsets = mapOf(TimeEvent.FAJR to -2.0, TimeEvent.SUNRISE to -2.0, TimeEvent.DHUHR to -3.0, TimeEvent.ASR to -4.0, TimeEvent.MAGHRIB to -4.0, TimeEvent.ISHA to -4.0),
        )
        val place = place(46.23092, 15.26044, "Europe/Ljubljana")
        val method = EuropeanPresets.SI_ISLAMSKA_SKUPNOST
        for (date in listOf(LocalDate.of(2027, 5, 10), LocalDate.of(2026, 4, 25))) {
            val l = PrayerEngine.explain(place, date, method, zone = ljubljana)
            val c = PrayerEngine.explain(place, date, method, zone = celje, baseZone = ljubljana)
            assertEquals(RuleName.SUMMER_FORM, c.day[TimeEvent.ISHA].rule, "$date")
            val shift = Duration.between(l.events[TimeEvent.ISHA.ordinal].unrounded, c.events[TimeEvent.ISHA.ordinal].unrounded).seconds / 60.0
            assertEquals(-4.0, shift, 0.01, "$date")
        }
        val outside = PrayerEngine.day(place(46.0569, 14.5058, "Europe/Ljubljana"), june21, method)
        assertEquals(RuleName.SUMMER_FORM, outside[TimeEvent.FAJR].rule)
    }

    @Test
    fun clockCapsMoonsightingAndDum() {
        val amsterdam = place(52.3676, 4.9041, "Europe/Amsterdam")
        val nl = mwl(HighLatitudeRule.ClockCaps(LocalTime.of(23, 30), LocalTime.of(3, 30)))
        assertEquals("03:30", clock(amsterdam, june21, nl, TimeEvent.FAJR))
        assertEquals("23:30", clock(amsterdam, june21, nl, TimeEvent.ISHA))
        // gmp at Paris on 21 June: Fajr 03:50, Isha 23:28 (7.2).
        assertEquals("03:50", clock(paris, june21, EuropeanPresets.GMP, TimeEvent.FAJR))
        assertEquals("23:28", clock(paris, june21, EuropeanPresets.GMP, TimeEvent.ISHA))
        // DUM RF's 2026 ramp: 0.30 in June, 0.25 from 3 August to 28 April, blocks of 3 days of 1/240 between.
        assertEquals(0.30, HighLatitudeRules.dumRfShare(LocalDate.of(2026, 6, 15)), 1e-12)
        assertEquals(0.30 - 1 / 240.0, HighLatitudeRules.dumRfShare(LocalDate.of(2026, 5, 31)), 1e-12)
        assertEquals(0.30 - 11 / 240.0, HighLatitudeRules.dumRfShare(LocalDate.of(2026, 4, 29)), 1e-12)
        assertEquals(0.25, HighLatitudeRules.dumRfShare(LocalDate.of(2026, 4, 28)), 1e-12)
        assertEquals(0.30 - 11 / 240.0, HighLatitudeRules.dumRfShare(LocalDate.of(2026, 8, 2)), 1e-12)
        assertEquals(0.25, HighLatitudeRules.dumRfShare(LocalDate.of(2026, 8, 3)), 1e-12)
        // DUM RT at Kazan on 21 June: the sun stays above −18°, so Fajr = sunrise − 2 h and Isha = sunset + 1 h 30.
        val kazan = PrayerEngine.explain(place(55.7887, 49.1221, "Europe/Moscow"), june21, EuropeanPresets.RU_DUM_RT)
        assertEquals(RuleName.DUM_RT, kazan.day[TimeEvent.FAJR].rule)
        assertEquals(120.0, Duration.between(kazan.events[0].unrounded, kazan.events[1].raw).seconds / 60.0, 0.01)
        assertEquals(90.0, Duration.between(kazan.nightAfter!!.sunset, kazan.events[5].unrounded).seconds / 60.0, 0.01)
        // apbif at Paris on 21 June, a night without 18°: Fajr at solar midnight + 6, 01:58, estimated.
        assertEquals("01:58", clock(paris, june21, EuropeanPresets.APBIF, TimeEvent.FAJR))
    }

    @Test
    fun polarRules() {
        val tromso = place(69.6492, 18.9553, "Europe/Oslo")
        // Every day of Tromsø's midnight sun and polar night: six ordered times, the substituted ones estimated.
        var date = LocalDate.of(2026, 1, 1)
        while (date.year == 2026) {
            val day = PrayerEngine.day(tromso, date, mwl)
            val times = day.events.map { checkNotNull(it.at) { "$date ${it.event}" } }
            assertEquals(times, times.sorted(), "Tromsø $date")
            date = date.plusDays(1)
        }
        val summer = PrayerEngine.day(tromso, june21, mwl)
        assertEquals(RuleName.MIN_DAY_NIGHT_5H, summer[TimeEvent.SUNRISE].rule)
        assertEquals(RuleName.MIN_DAY_NIGHT_5H, summer[TimeEvent.MAGHRIB].rule)
        // Murmansk on 21 December: a 5-hour day centred on Dhuhr, Asr halfway to Maghrib.
        val murmansk = PrayerEngine.explain(place(68.9585, 33.0827, "Europe/Moscow"), LocalDate.of(2026, 12, 21), mwl)
        val sunrise = checkNotNull(murmansk.events[TimeEvent.SUNRISE.ordinal].unrounded)
        val maghrib = checkNotNull(murmansk.events[TimeEvent.MAGHRIB.ordinal].unrounded)
        val transit = checkNotNull(murmansk.events[TimeEvent.DHUHR.ordinal].raw)
        assertEquals(5 * 3600.0, Duration.between(sunrise, maghrib).seconds.toDouble(), 1.0)
        assertEquals(0.0, (Duration.between(sunrise, transit).seconds - Duration.between(transit, maghrib).seconds).toDouble(), 1.0)
        assertEquals(RuleName.POLAR_ASR_MIDPOINT, murmansk.day[TimeEvent.ASR].rule)
        // Rovaniemi on 21 December: a 5-hour day with its real Asr kept; Luleå keeps its real Maghrib at 00:07 on 21 June.
        val rovaniemi = place(66.5039, 25.7294, "Europe/Helsinki")
        val r = PrayerEngine.day(rovaniemi, LocalDate.of(2026, 12, 21), mwl)
        assertEquals(EventStatus.COMPUTED, r[TimeEvent.ASR].status)
        assertEquals(RuleName.MIN_DAY_NIGHT_5H, r[TimeEvent.SUNRISE].rule)
        val lulea = place(65.5848, 22.1547, "Europe/Stockholm")
        val l = PrayerEngine.day(lulea, june21, mwl)
        assertEquals("00:07", l[TimeEvent.MAGHRIB].at!!.atZone(lulea.zone).toLocalTime().toString())
        assertEquals(EventStatus.COMPUTED, l[TimeEvent.MAGHRIB].status)
        // Bodø on 21 December under diyanet_eu: Asr = Dhuhr.
        val bodo = PrayerEngine.day(place(67.2804, 14.4049, "Europe/Oslo"), LocalDate.of(2026, 12, 21), EuropeanPresets.DIYANET_EU)
        assertEquals(bodo[TimeEvent.DHUHR].at, bodo[TimeEvent.ASR].at)
    }

    private fun seconds(instant: java.time.Instant?): Double = checkNotNull(instant).let { it.epochSecond + it.nano / 1e9 }

    @Test
    fun diyanetTakdir() {
        // H6 (fits/diyanet_takdir), case A at Cuneo on 21 June: Isha = Maghrib + S/3 and Fajr = sunrise − (18/16)·S/3, S the
        // plain Fajr + 24 h − Maghrib, with the published sunrise and Maghrib.
        val method = EuropeanPresets.DIYANET_EU
        val cuneo = PrayerEngine.explain(place(44.39, 7.55, "Europe/Rome"), june21, method)
        val sunrise = seconds(cuneo.events[TimeEvent.SUNRISE.ordinal].unrounded)
        val maghrib = seconds(cuneo.events[TimeEvent.MAGHRIB.ordinal].unrounded)
        val sharia = seconds(cuneo.events[TimeEvent.FAJR.ordinal].raw) + 86_400 - maghrib
        assertEquals(maghrib + sharia / 3, seconds(cuneo.events[TimeEvent.ISHA.ordinal].unrounded), 0.01)
        assertEquals(sunrise - 18.0 / 16 * sharia / 3, seconds(cuneo.events[TimeEvent.FAJR.ordinal].unrounded), 0.01)
        assertEquals(RuleName.DIYANET_TAKDIR, cuneo.day[TimeEvent.ISHA].rule)
        // Rodez, 44.35° N, south of the cutoff: the angle times stay.
        val rodez = PrayerEngine.day(place(44.35, 2.57, "Europe/Paris"), june21, method)
        assertEquals(EventStatus.COMPUTED, rodez[TimeEvent.FAJR].status)
        assertEquals(EventStatus.COMPUTED, rodez[TimeEvent.ISHA].status)
        // Ushuaia: Diyanet's southern tables keep the angle times; an absent Fajr takes 18/60 of the night before.
        val ushuaia = place(-54.79, -68.305, "America/Argentina/Ushuaia")
        assertEquals(EventStatus.COMPUTED, PrayerEngine.day(ushuaia, june21, method)[TimeEvent.FAJR].status)
        val december = PrayerEngine.explain(ushuaia, LocalDate.of(2026, 12, 21), method)
        val night = checkNotNull(december.nightBefore)
        val fallback = seconds(night.sunrise) - 0.3 * (seconds(night.sunrise) - seconds(night.sunset))
        assertEquals(fallback, seconds(december.events[TimeEvent.FAJR.ordinal].unrounded), 1.0)
        assertEquals(RuleName.DIYANET_TAKDIR, december.day[TimeEvent.FAJR].rule)
    }

    @Test
    fun diyanetHeldNight() {
        // H2 and H6 where 21 June holds its night (Gävle, 60.67° N): from 1 March to 31 October no Fajr earlier and no
        // Isha later than 21 June's, the sunset never earlier again up to 21 June and the sunrise never earlier again
        // after it (UTC times of day). At Tromsø the published sunrise and Maghrib of 21 June are Dhuhr ∓ 9.5 h.
        val method = EuropeanPresets.DIYANET_EU
        val gavle = place(60.67, 17.14, "Europe/Stockholm")
        fun timeOfDay(date: LocalDate, event: TimeEvent): Double =
            seconds(PrayerEngine.explain(gavle, date, method).events[event.ordinal].unrounded) - date.toEpochDay() * 86_400.0
        val juneFajr = timeOfDay(june21, TimeEvent.FAJR)
        val juneIsha = timeOfDay(june21, TimeEvent.ISHA)
        var date = LocalDate.of(2026, 3, 1)
        var sunset = timeOfDay(date, TimeEvent.MAGHRIB)
        var sunrise = timeOfDay(date, TimeEvent.SUNRISE)
        while (date < LocalDate.of(2026, 10, 31)) {
            date = date.plusDays(1)
            assertTrue(timeOfDay(date, TimeEvent.FAJR) >= juneFajr - 0.01, "$date Fajr")
            assertTrue(timeOfDay(date, TimeEvent.ISHA) <= juneIsha + 0.01, "$date Isha")
            val nextSunset = timeOfDay(date, TimeEvent.MAGHRIB)
            val nextSunrise = timeOfDay(date, TimeEvent.SUNRISE)
            if (date <= june21) assertTrue(nextSunset >= sunset - 0.01, "$date sunset")
            else assertTrue(nextSunrise >= sunrise - 0.01, "$date sunrise")
            sunset = nextSunset
            sunrise = nextSunrise
        }
        val tromso = PrayerEngine.explain(place(69.6492, 18.9553, "Europe/Oslo"), june21, method)
        val dhuhr = seconds(tromso.events[TimeEvent.DHUHR.ordinal].unrounded)
        assertEquals(dhuhr - 9.5 * 3600, seconds(tromso.events[TimeEvent.SUNRISE.ordinal].unrounded), 0.01)
        assertEquals(dhuhr + 9.5 * 3600, seconds(tromso.events[TimeEvent.MAGHRIB.ordinal].unrounded), 0.01)
        assertEquals(RuleName.DIYANET_CLAMP, tromso.day[TimeEvent.SUNRISE].rule)
    }

    @Test
    fun winterAsrBoundary() {
        val rovaniemi = place(66.5039, 25.7294, "Europe/Helsinki")
        val dec21 = LocalDate.of(2026, 12, 21)
        val m = PrayerEngine.day(rovaniemi, dec21, mwl)
        assertEquals("10:15", m[TimeEvent.DHUHR].at!!.atZone(java.time.ZoneOffset.UTC).toLocalTime().toString())
        assertEquals("10:16", m[TimeEvent.ASR].at!!.atZone(java.time.ZoneOffset.UTC).toLocalTime().toString())
        assertEquals(EventStatus.COMPUTED, m[TimeEvent.DHUHR].status)
        assertEquals(EventStatus.COMPUTED, m[TimeEvent.ASR].status)
        val t = PrayerEngine.day(rovaniemi, dec21, PrayerMethods.TUNISIA_18_18)
        assertEquals("10:22", t[TimeEvent.DHUHR].at!!.atZone(java.time.ZoneOffset.UTC).toLocalTime().toString())
        assertEquals(t[TimeEvent.DHUHR].at, t[TimeEvent.ASR].at)
        assertEquals(RuleName.HELD_IN_ORDER, t[TimeEvent.ASR].rule)
        val near = PrayerEngine.day(place(66.53, 25.73, "Europe/Helsinki"), dec21, mwl)
        assertEquals(near[TimeEvent.DHUHR].at, near[TimeEvent.ASR].at)
        assertEquals(EventStatus.COMPUTED, near[TimeEvent.ASR].status)
        // Every day from 2026-12-08 to 2027-01-03: no inversion under mwl, tunisia_18_18, diyanet_eu and a custom Dhuhr + 10.
        val custom = PrayerMethods.MWL.copy(id = PrayerMethod.CUSTOM_ID, offsets = mapOf(TimeEvent.DHUHR to 10, TimeEvent.MAGHRIB to 1))
        var date = LocalDate.of(2026, 12, 8)
        while (date <= LocalDate.of(2027, 1, 3)) {
            for (method in listOf(mwl, PrayerMethods.TUNISIA_18_18, EuropeanPresets.DIYANET_EU, custom)) {
                val times = PrayerEngine.day(rovaniemi, date, method).events.map { checkNotNull(it.at) }
                assertEquals(times, times.sorted(), "${method.id} $date")
            }
            date = date.plusDays(1)
        }
    }

    @Test
    fun shortNightsAndFixedIntervals() {
        val lulea = place(65.5848, 22.1547, "Europe/Stockholm")
        fun local(day: com.tunisianprayertimes.engine.PrayerDay, e: TimeEvent) = day[e].at!!.atZone(lulea.zone).toLocalTime().toString()
        // 9.2: ecfr_90 gives Maghrib 00:07, Isha 00:33, Fajr of 22 June 00:33 and sunrise 01:00 (CEST on 22 June).
        val ecfr = PrayerEngine.day(lulea, june21, EuropeanPresets.ECFR_90)
        val next = PrayerEngine.day(lulea, june21.plusDays(1), EuropeanPresets.ECFR_90)
        assertEquals("00:07", local(ecfr, TimeEvent.MAGHRIB))
        assertEquals("00:33", local(ecfr, TimeEvent.ISHA))
        assertEquals("00:33", local(next, TimeEvent.FAJR))
        assertEquals("01:00", local(next, TimeEvent.SUNRISE))
        assertEquals(RuleName.NIGHT_LOWEST_POINT, ecfr[TimeEvent.ISHA].rule)
        assertEquals(RuleName.NIGHT_LOWEST_POINT, next[TimeEvent.FAJR].rule)
        val uaq = PrayerEngine.day(lulea, june21, PrayerMethods.UMM_AL_QURA)
        assertEquals("00:33", local(uaq, TimeEvent.ISHA))
        assertEquals("00:44", local(PrayerEngine.day(lulea, june21.plusDays(1), PrayerMethods.UMM_AL_QURA), TimeEvent.FAJR))
        // Every night of 2026 at Luleå, Oulu and Akureyri, under interval methods and steppers: no inversion, the lowest point respected.
        val methods = listOf(
            EuropeanPresets.ECFR_90, PrayerMethods.UMM_AL_QURA, EuropeanPresets.FAJR15_ISHA90, EuropeanPresets.BE_SUNRISE_90,
            EuropeanPresets.GMP, EuropeanPresets.RU_DUM_RT,
            EuropeanPresets.ECFR_90.copy(id = PrayerMethod.CUSTOM_ID, fajr = com.tunisianprayertimes.method.FajrRule.MinutesBeforeSunrise(120),
                isha = com.tunisianprayertimes.method.IshaRule.MinutesAfter(120), offsets = mapOf(TimeEvent.DHUHR to 2, TimeEvent.MAGHRIB to 1)),
        )
        for (place in listOf(lulea, place(65.0121, 25.4651, "Europe/Helsinki"), place(65.6835, -18.1262, "Atlantic/Reykjavik"))) {
            for (method in methods) for (steppers in listOf(emptyMap(), mapOf(TimeEvent.ISHA to 30, TimeEvent.FAJR to -30))) {
                var date = LocalDate.of(2026, 1, 1)
                var previousIsha: java.time.Instant? = null
                while (date.year == 2026) {
                    val e = PrayerEngine.explain(place, date, method, ramadanDay = date.monthValue == 3, adjustments = steppers)
                    val times = e.day.events.map { checkNotNull(it.at) }
                    assertEquals(times, times.sorted(), "${place.zoneId} ${method.id} $steppers $date")
                    previousIsha?.let { assertTrue(it <= times[0], "${place.zoneId} ${method.id} $date: Isha(D − 1) ≤ Fajr(D)") }
                    val lowest = e.nightAfter?.lowestPoint
                    if (lowest != null) {
                        assertTrue(e.events[TimeEvent.ISHA.ordinal].unrounded!! <= lowest)
                        assertTrue(e.events[TimeEvent.MAGHRIB.ordinal].unrounded!! <= lowest)
                    }
                    previousIsha = times[5]
                    date = date.plusDays(1)
                }
            }
        }
    }

    @Test
    fun irnSwitchDays() {
        // 9.2's fixture entry: a southern zone whose switchDays hold Isha 90..251 and Fajr 103..238.
        val zone = OfficialZone("irn", "fixture", "fixture", LatLngE5.of(59.9139, 10.7522),
            switchDays = mapOf(TimeEvent.ISHA to 90..251, TimeEvent.FAJR to 103..238))
        val osloPlace = place(59.9139, 10.7522, "Europe/Oslo")
        fun day(d: Int, year: Int = 2026) = PrayerEngine.day(osloPlace, IrnRule.onDay(year, d), EuropeanPresets.IRN, zone = zone)
        assertEquals(null, day(89)[TimeEvent.ISHA].rule)
        assertEquals(RuleName.IRN_SOUTHERN_SUMMER, day(90)[TimeEvent.ISHA].rule)
        assertEquals(RuleName.IRN_SOUTHERN_SUMMER, day(251)[TimeEvent.ISHA].rule)
        assertEquals(null, day(252)[TimeEvent.ISHA].rule)
        assertEquals(LocalDate.of(2028, 3, 31), IrnRule.onDay(2028, 90))
        assertEquals(RuleName.IRN_SOUTHERN_SUMMER, day(90, 2028)[TimeEvent.ISHA].rule)
        assertEquals(null, day(102)[TimeEvent.FAJR].rule)
        assertEquals(RuleName.IRN_SOUTHERN_SUMMER, day(103)[TimeEvent.FAJR].rule)
        assertEquals(null, day(239)[TimeEvent.FAJR].rule)
        // Fajr on day 103 is held at the clock time of day 102's 16° Fajr (or sunrise − 60 when earlier).
        val held = day(103)[TimeEvent.FAJR].at!!.atZone(osloPlace.zone).toLocalTime()
        val before = day(102)[TimeEvent.FAJR].at!!.atZone(osloPlace.zone).toLocalTime()
        assertEquals(before, held)
    }

    @Test
    fun meccaCycleInTromso() {
        // IRN's Tromsø on 21 June 2026: Fajr 04:38, Maghrib 19:35, Isha 20:55; on 21 December 04:59, 17:13, 18:27 (7.4).
        val tromso = place(69.6492, 18.9553, "Europe/Oslo")
        for ((date, expected) in listOf(
            june21 to listOf("04:38", "19:35", "20:55"),
            LocalDate.of(2026, 12, 21) to listOf("04:59", "17:13", "18:27"),
        )) {
            val day = PrayerEngine.day(tromso, date, EuropeanPresets.IRN)
            val shown = listOf(TimeEvent.FAJR, TimeEvent.MAGHRIB, TimeEvent.ISHA).map { day[it].at!!.atZone(tromso.zone).toLocalTime().toString() }
            shown.zip(expected).forEach { (s, e) -> assertTrue(minutesApart(s, e) <= 2, "Tromsø $date: $s against IRN's $e") }
            assertEquals(RuleName.IRN_MECCA_CYCLE, day[TimeEvent.FAJR].rule)
        }
    }

    @Test
    fun meccaCycleOnTheLeapDay() {
        // H8 on 29 February: the date's own times, between 28 February's and 1 March's (day 59 numbers both dates).
        val bodo = place(67.2804, 14.4049, "Europe/Oslo")
        fun minutes(date: LocalDate, event: TimeEvent) =
            PrayerEngine.day(bodo, date, EuropeanPresets.IRN)[event].at!!.atZone(bodo.zone).toLocalTime().toSecondOfDay() / 60
        val leap = PrayerEngine.day(bodo, LocalDate.of(2028, 2, 29), EuropeanPresets.IRN).events.map { checkNotNull(it.at) }
        assertEquals(6, leap.distinct().size, "six distinct times on 29 February: $leap")
        assertEquals(leap, leap.sorted())
        for (event in TimeEvent.entries) {
            val (before, after) = minutes(LocalDate.of(2028, 2, 28), event) to minutes(LocalDate.of(2028, 3, 1), event)
            val day = minutes(LocalDate.of(2028, 2, 29), event)
            assertTrue(day in minOf(before, after) - 1..maxOf(before, after) + 1, "$event on 29 February: $day, not between $before and $after")
        }
    }

    @Test
    fun aDayWithoutSunriseIsNeverReadAsMidnightSun() {
        // H2: the polar test takes the sun the sunrise is solved with. On Steigen's 1 January 2026 the instant sun clears
        // the horizon by 0.0002° at the transit and IRN's noon sun does not: the day has no sunrise, so it is a polar night.
        // IRN's own table: Fajr 07:01, Maghrib 14:09, Isha 16:55 (the winter floor), not a 16-hour day.
        val point = LatLngE5(6785358, 1480171)
        val steigen = OfficialZone("irn", "1848", "ستايغن", point, morningPoint = LatLngE5(6785358, 1480007), eveningPoint = LatLngE5(6785358, 1480007),
            switchDays = mapOf(TimeEvent.MAGHRIB to 111..233, TimeEvent.DHUHR to 327..18))
        val place = place(point.latitude, point.longitude, "Europe/Oslo")
        val day = PrayerEngine.day(place, LocalDate.of(2026, 1, 1), EuropeanPresets.IRN, zone = steigen)
        val shown = listOf(TimeEvent.FAJR, TimeEvent.MAGHRIB, TimeEvent.ISHA).map { day[it].at!!.atZone(place.zone).toLocalTime().toString() }
        assertEquals(listOf("07:01", "14:09", "16:55"), shown)
        // The 5-hour minimum on the same edge, for a method with IRN's noon sun: never a 19-hour day.
        val noonSun = mwl.copy(id = "mwl_noon_sun", sunEvaluation = listOf(TimeEvent.SUNRISE, TimeEvent.MAGHRIB).associateWith { SunEvaluation.DAILY_LOCAL_NOON })
        var date = LocalDate.of(2025, 12, 1)
        while (date <= LocalDate.of(2026, 1, 20)) {
            val e = PrayerEngine.explain(place, date, noonSun)
            val length = Duration.between(e.events[TimeEvent.SUNRISE.ordinal].unrounded, e.events[TimeEvent.MAGHRIB.ordinal].unrounded)
            assertTrue(length.toHours() < 6, "Steigen on $date: a ${length.toMinutes()}-minute day")
            date = date.plusDays(1)
        }
    }

    @Test
    fun aCustomMethodKeepsThePolarEstimatesOfItsBase() {
        // S10 (owner, 2026-10-18): E19 reads the sunrise and sunset of steps 1–3. In Tromsø on 10 May (real sunrise
        // 02:25, sunset 23:02) «مخصّصة» keeps the 5-hour night of mwl (sunrise 03:11, Maghrib 22:11) and irn's Mecca
        // cycle (Fajr 04:47, sunrise 06:06, Maghrib 19:18, Isha 20:33): never the real short night, never Fajr = sunrise.
        val tromso = place(69.6492, 18.9553, "Europe/Oslo")
        val date = LocalDate.of(2026, 5, 10)
        for (base in listOf(mwl, EuropeanPresets.IRN)) {
            val own = PrayerEngine.day(tromso, date, base)
            val custom = PrayerEngine.day(tromso, date, base.copy(id = PrayerMethod.CUSTOM_ID))
            for (event in listOf(TimeEvent.FAJR, TimeEvent.SUNRISE, TimeEvent.MAGHRIB, TimeEvent.ISHA)) {
                assertEquals(own[event].at, custom[event].at, "${base.id} $event")
            }
            assertTrue(checkNotNull(custom[TimeEvent.FAJR].at) < checkNotNull(custom[TimeEvent.SUNRISE].at), "${base.id}: Fajr before sunrise")
        }
    }

    @Test
    fun aCustomMethodFromADiyanetPresetComputes() {
        // H6 needs both angles: a «مخصّصة» made from diyanet_eu (it keeps the preset's rule) with Isha or Fajr by
        // minutes takes the angle time where there is one, and never builds the table (it threw on every launch).
        val customs = listOf(
            EuropeanPresets.DIYANET_EU.copy(id = PrayerMethod.CUSTOM_ID, isha = IshaRule.MinutesAfter(90)),
            EuropeanPresets.DIYANET_EU.copy(id = PrayerMethod.CUSTOM_ID, fajr = FajrRule.MinutesBeforeSunrise(90)),
        )
        for (custom in customs) for (date in listOf(june21, LocalDate.of(2026, 12, 21))) {
            val day = PrayerEngine.day(berlin, date, custom)
            assertTrue(day.events.all { it.at != null }, "${custom.fajr} / ${custom.isha} on $date: ${day.events}")
        }
    }

    @Test
    fun everyPresetGivesSixTimesFromThe35thToThe71stParallel() {
        // A sample of 9.1's European grid: every preset and profile, 2026 and 2099, six ordered times every day.
        var checked = 0
        val problems = mutableListOf<String>()
        for (lat in 35..71 step 6) for (lng in listOf(-8, 10, 28)) {
            val place = place(lat + 0.31, lng + 0.47, java.time.ZoneOffset.ofHours(Math.round(lng / 15.0).toInt()).id)
            PrayerMethods.ALL.forEachIndexed { m, method ->
                for (year in listOf(2026, 2099)) {
                    var date = LocalDate.of(year, 1, 1).plusDays(((m * 7 + lat) % 23).toLong())
                    while (date.year == year) {
                        val day = PrayerEngine.day(place, date, method)
                        val missing = day.events.filter { it.at == null }.map { it.event }
                        if (missing.isNotEmpty()) problems += "${method.id} at $lat,$lng on $date: no $missing"
                        else {
                            val times = day.events.map { it.at!! }
                            if (times != times.sorted()) problems += "${method.id} at $lat,$lng on $date: out of order"
                        }
                        date = date.plusDays(23)
                        checked++
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.take(40).joinToString("\n", prefix = "${problems.size} days:\n"))
        assertTrue(checked > 5000)
    }
}
