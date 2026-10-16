package com.tunisianprayertimes.silence

import com.tunisianprayertimes.DelayMode
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.SilenceMode
import com.tunisianprayertimes.engine.EngineTestSupport
import com.tunisianprayertimes.engine.EventStatus
import com.tunisianprayertimes.engine.EventTime
import com.tunisianprayertimes.engine.PrayerDay
import com.tunisianprayertimes.engine.PrayerEngine
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.place.LatLngE5
import com.tunisianprayertimes.place.PrayerPlace
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The silence endpoints, the planner and the Friday defaults (plan 6.9, D12, D16, A18). */
class SilenceWindowPlannerTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val tunis = PrayerPlace.TUNIS

    /** A synthetic prayer day: "HH:mm" on [date], or "+1 HH:mm" on the next day. */
    private fun day(date: LocalDate, zone: ZoneId, vararg times: String): PrayerDay {
        require(times.size == 6)
        val events = TimeEvent.entries.mapIndexed { i, event ->
            val text = times[i]
            val next = text.startsWith("+1 ")
            val time = LocalTime.parse(text.removePrefix("+1 "))
            EventTime(event, ZonedDateTime.of(date.plusDays(if (next) 1 else 0), time, zone).toInstant(), EventStatus.COMPUTED)
        }
        return PrayerDay(date, zone, "test", events)
    }

    private fun at(date: String, time: String, zone: ZoneId): Instant = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant()

    private fun minutes(from: Instant, to: Instant) = Duration.between(from, to).toMinutes()

    @Test
    fun rulesFromPhoneConfigs() {
        val durationEnd = SilenceRule.of(PrayerSilenceConfig(afterMinutes = 20, delayMinutes = 3))
        assertEquals(SilenceRule(SilenceStart.FromAdhan(3), SilenceEnd.FromStart(20)), durationEnd)
        val offsetEnd = SilenceRule.of(PrayerSilenceConfig(endOffsetMinutes = 50))
        assertEquals(SilenceEnd.FromAdhan(50), offsetEnd.end)
        val clocks = SilenceRule.of(
            PrayerSilenceConfig(mode = SilenceMode.FIXED_TIME, fixedHour = 13, fixedMinute = 15, endOffsetMinutes = 50, delayMode = DelayMode.FIXED_TIME, delayFixedHour = 12, delayFixedMinute = 40),
            savedEndOffsetMinutes = 35,
        )
        assertEquals(SilenceRule(SilenceStart.Clock(LocalTime.of(12, 40)), SilenceEnd.Clock(LocalTime.of(13, 15), 35)), clocks)
        // A fixed mode with the −1 clock values keeps today's fallback; "unset" is the phone's flag.
        val unsetClock = SilenceRule.of(PrayerSilenceConfig(mode = SilenceMode.FIXED_TIME, delayMode = DelayMode.FIXED_TIME, delayMinutes = 2))
        assertEquals(SilenceRule(SilenceStart.FromAdhan(2), SilenceEnd.FromStart(30)), unsetClock)
        assertEquals(SilenceRule(SilenceStart.Unset, SilenceEnd.Unset), SilenceRule.of(PrayerSilenceConfig(), startUnset = true, endUnset = true))
    }

    @Test
    fun aFixedEndThatDoesNotFit() {
        // D16 (plan 9.6): 13:00 saved when Dhuhr was at 12:25 (adhan + 35), used at Paris with Dhuhr at 13:55.
        val rule = SilenceRule(SilenceStart.Unset, SilenceEnd.Clock(LocalTime.of(13, 0), savedAdhanOffsetMinutes = 35))
        val july = LocalDate.of(2026, 7, 21)
        val late = day(july, paris, "04:00", "06:00", "13:55", "17:50", "21:40", "23:30")
        val window = assertNotNull(SilenceWindowPlanner(paris, false, mapOf(Prayer.DHUHR to rule)) { SilenceDay(it, late) }.windowsOf(july).single())
        assertEquals(at("2026-07-21", "14:00", paris), window.start)
        assertEquals(at("2026-07-21", "14:30", paris), window.end)
        assertEquals(EndResolution.SAVED_OFFSET, window.endResolution)
        // On a day where 13:00 + 30 fits, the capped end.
        val earlier = day(july, paris, "04:00", "06:00", "13:20", "17:50", "21:40", "23:30")
        val capped = SilenceWindowPlanner(paris, false, mapOf(Prayer.DHUHR to rule)) { SilenceDay(it, earlier) }.windowsOf(july).single()
        assertEquals(at("2026-07-21", "13:30", paris), capped.end)
        assertEquals(EndResolution.CAPPED, capped.endResolution)
        // Never to the next day: a 23:50 end before a 23:55 start is capped at 23:59, and before a 23:59 start
        // it keeps its saved offset.
        val ishaAt = at("2026-07-21", "23:40", paris)
        val endAt2350 = SilenceEnd.Clock(LocalTime.of(23, 50), 40)
        val lastMinute = SilenceWindowPlanner.window(Prayer.ISHA, july, ishaAt, SilenceRule(SilenceStart.FromAdhan(15), endAt2350), paris, false)
        assertEquals(at("2026-07-21", "23:59", paris), assertNotNull(lastMinute).end)
        assertEquals(EndResolution.CAPPED, lastMinute.endResolution)
        val night = SilenceWindowPlanner.window(Prayer.ISHA, july, ishaAt, SilenceRule(SilenceStart.FromAdhan(19), endAt2350), paris, false)
        assertEquals(at("2026-07-22", "00:20", paris), assertNotNull(night).end)
        assertEquals(EndResolution.SAVED_OFFSET, night.endResolution)
        // A fixed end saved before its offset was stored keeps today's meaning within 12 h of the adhan
        // (a Tunisian Isha end at 00:30), but never rolls 23 h: a 13:00 Dhuhr end at Paris takes adhan + 45.
        val tunisIsha = SilenceWindowPlanner.window(
            Prayer.ISHA, LocalDate.of(2026, 3, 10), at("2026-03-10", "19:48", tunis),
            SilenceRule(SilenceStart.FromAdhan(0), SilenceEnd.Clock(LocalTime.of(0, 30))), tunis, true,
        )
        assertEquals(at("2026-03-11", "00:30", tunis), assertNotNull(tunisIsha).end)
        val legacyDhuhr = SilenceWindowPlanner.window(
            Prayer.DHUHR, july, at("2026-07-21", "13:55", paris),
            SilenceRule(SilenceStart.Unset, SilenceEnd.Clock(LocalTime.of(13, 0))), paris, false,
        )
        assertEquals(at("2026-07-21", "14:40", paris), assertNotNull(legacyDhuhr).end)
    }

    @Test
    fun aFixedStartTakesTheOccurrenceNearestTheAdhan() {
        // Plan 6.9: a Dhuhr at 12:30 with a fixed start at 00:20 starts the next night; a fixed end at 23:00 on
        // the start's day would be adhan + 34 h 30 min, so the domain clamps it to adhan + 1,440.
        val date = LocalDate.of(2026, 3, 10)
        val adhan = at("2026-03-10", "12:30", tunis)
        val window = assertNotNull(
            SilenceWindowPlanner.window(Prayer.DHUHR, date, adhan, SilenceRule(SilenceStart.Clock(LocalTime.of(0, 20)), SilenceEnd.Clock(LocalTime.of(23, 0))), tunis, true),
        )
        assertEquals(at("2026-03-11", "00:20", tunis), window.start)
        assertEquals(adhan.plusSeconds(1440 * 60), window.end)
        assertTrue(window.clamped)
        // The year run reports such a day.
        val planner = SilenceWindowPlanner(tunis, true, emptyMap()) { SilenceDay(it, day(it, tunis, "05:00", "06:30", "12:30", "15:40", "18:10", "19:35")) }
        val fit = planner.fit(Prayer.DHUHR, SilenceRule(SilenceStart.Clock(LocalTime.of(0, 20)), SilenceEnd.Clock(LocalTime.of(23, 0))), date, 3)
        assertEquals(3, fit.notFitting.size)
    }

    @Test
    fun theEndpointDomain() {
        // Saved values beyond the domain are armed at its bounds and flagged, never rewritten (plan 9.6).
        val date = LocalDate.of(2026, 3, 10)
        val adhan = at("2026-03-10", "13:00", paris)
        val window = assertNotNull(SilenceWindowPlanner.window(Prayer.DHUHR, date, adhan, SilenceRule(SilenceStart.FromAdhan(-10_080), SilenceEnd.FromAdhan(3_000)), paris, false))
        assertEquals(adhan.minusSeconds(720 * 60), window.start)
        assertEquals(adhan.plusSeconds(1440 * 60), window.end)
        assertTrue(window.clamped)
        // A window whose clamped end does not come after its clamped start is skipped.
        assertNull(SilenceWindowPlanner.window(Prayer.DHUHR, date, adhan, SilenceRule(SilenceStart.FromAdhan(30), SilenceEnd.FromAdhan(10)), paris, false))
    }

    @Test
    fun theScanSeesPrayerDayDMinus2() {
        // Plan 6.9: Paris under D5, Isha of 21 June at 00:11 on 22 June, ending at adhan + 1,440.
        val isha = SilenceRule(SilenceStart.FromAdhan(0), SilenceEnd.FromAdhan(1440))
        val planner = SilenceWindowPlanner(paris, false, mapOf(Prayer.ISHA to isha)) {
            SilenceDay(it, day(it, paris, "03:50", "05:47", "13:56", "18:02", "21:58", "+1 00:11"))
        }
        val now = at("2026-06-23", "00:05", paris)
        val open = planner.containing(now)
        assertEquals(listOf(LocalDate.of(2026, 6, 21)), open.map { it.prayerDay })
        assertEquals(at("2026-06-23", "00:11", paris), planner.latestEndContaining(now))
        assertEquals((21..24).map { LocalDate.of(2026, 6, it) }, planner.windows(now).map { it.prayerDay }.distinct().sorted())
        // Two occurrences of the same prayer may overlap: at 00:30 the 22 June window has replaced it.
        assertEquals(listOf(LocalDate.of(2026, 6, 22)), planner.containing(at("2026-06-23", "00:30", paris)).map { it.prayerDay })
    }

    @Test
    fun tunisianDelegationsKeepTunisTime() {
        // D12: Dhuhr 12:40–13:15 and the Jumu'a window until 13:15, both read in Africa/Tunis whatever the phone.
        val rules = mapOf(Prayer.DHUHR to SilenceRule(SilenceStart.Unset, SilenceEnd.Unset), Prayer.JOMOAA to SilenceRule(SilenceStart.Unset, SilenceEnd.Unset))
        val thursday = LocalDate.of(2026, 7, 2)
        val friday = LocalDate.of(2026, 7, 3)
        val planner = SilenceWindowPlanner(tunis, true, rules) { date ->
            val times = day(date, tunis, "03:20", "05:05", "12:30", "16:10", "19:45", "21:20")
            SilenceDay(date, times, jumua = if (date == friday) SilenceDefaults.BUNDLED.jumuaTime(PrayerPlace.Inm(615), date, checkNotNull(times[TimeEvent.DHUHR].at)) else null)
        }
        val dhuhr = planner.windowsOf(thursday).single()
        assertEquals(at("2026-07-02", "12:40", tunis), dhuhr.start)
        assertEquals(at("2026-07-02", "13:15", tunis), dhuhr.end)
        val jumua = planner.windowsOf(friday).single()
        assertEquals(Prayer.JOMOAA, jumua.prayer)
        assertEquals(at("2026-07-03", "12:30", tunis), jumua.start)
        // 13:15 Tunis time is 14:15 in Paris in July.
        assertEquals(LocalTime.of(14, 15), jumua.end.atZone(paris).toLocalTime())
        assertEquals(EndResolution.AS_SET, jumua.endResolution)
    }

    @Test
    fun defaultDhuhrAndJumuaWindowsAbroad() {
        // Plan 9.6: with no entry for the country, a 40-minute Dhuhr window and a 45-minute Friday window.
        val none = SilenceDefaults(emptyList())
        val unset = SilenceRule(SilenceStart.Unset, SilenceEnd.Unset)
        val rules = mapOf(Prayer.DHUHR to unset, Prayer.JOMOAA to unset)
        val places = listOf(
            EngineTestSupport.place(48.8566, 2.3522, "Europe/Paris", "FR") to LocalDate.of(2026, 1, 8),
            EngineTestSupport.place(40.4168, -3.7038, "Europe/Madrid", "ES") to LocalDate.of(2026, 1, 8),
            EngineTestSupport.place(52.2297, 21.0122, "Europe/Warsaw", "PL") to LocalDate.of(2026, 1, 8),
            EngineTestSupport.place(51.5074, -0.1278, "Europe/London", "GB") to LocalDate.of(2026, 7, 9),
        )
        for ((place, thursday) in places) {
            val planner = planner(place, rules, none)
            val dhuhr = planner.windowsOf(thursday).single()
            assertEquals(5, minutes(dhuhr.adhan, dhuhr.start), place.cacheKey)
            assertEquals(40, minutes(dhuhr.start, dhuhr.end), place.cacheKey)
            assertEquals(EndResolution.AS_SET, dhuhr.endResolution)
            assertFalse(dhuhr.clamped)
            val jumua = planner.windowsOf(thursday.plusDays(1)).single()
            assertEquals(Prayer.JOMOAA, jumua.prayer)
            val friday = PrayerEngine.day(place, thursday.plusDays(1), PrayerMethods.MWL)
            assertEquals(friday[TimeEvent.DHUHR].at, jumua.start, place.cacheKey)
            assertEquals(45, minutes(jumua.start, jumua.end), place.cacheKey)
        }
    }

    @Test
    fun countryAndPlaceEntries() {
        val parisPlace = EngineTestSupport.place(48.8566, 2.3522, "Europe/Paris", "FR")
        val lyon = EngineTestSupport.place(45.7640, 4.8357, "Europe/Paris", "FR")
        val defaults = SilenceDefaults(
            listOf(
                SilenceDefaults.Entry("FR", FridayTime.Clock(LocalTime.of(12, 0)), "test"),
                SilenceDefaults.Entry("FR", FridayTime.Clock(LocalTime.of(13, 30)), "test", centre = LatLngE5.of(48.8566, 2.3522)),
            ),
        )
        val friday = LocalDate.of(2026, 1, 9)
        fun dhuhr(place: PrayerPlace.Coordinates, date: LocalDate) = checkNotNull(PrayerEngine.day(place, date, PrayerMethods.MWL)[TimeEvent.DHUHR].at)
        // The place's entry wins within 25 km; elsewhere the country's, never before Dhuhr + 5.
        assertEquals(at("2026-01-09", "13:30", paris), defaults.jumuaTime(parisPlace, friday, dhuhr(parisPlace, friday)))
        assertEquals(dhuhr(lyon, friday).plusSeconds(300), defaults.jumuaTime(lyon, friday, dhuhr(lyon, friday)))
        val window = planner(parisPlace, mapOf(Prayer.JOMOAA to SilenceRule(SilenceStart.Unset, SilenceEnd.Unset)), defaults).windowsOf(friday).single()
        assertEquals(at("2026-01-09", "14:15", paris), window.end)
        // A seasonal entry switches with summer time; its AT_DHUHR season starts at Dhuhr.
        val seasonal = SilenceDefaults(listOf(SilenceDefaults.Entry("FR", FridayTime.Seasonal(FridayTime.Clock(LocalTime.of(14, 30)), FridayTime.AtDhuhr), "test")))
        val july = LocalDate.of(2026, 7, 3)
        assertEquals(at("2026-07-03", "14:30", paris), seasonal.jumuaTime(lyon, july, dhuhr(lyon, july)))
        assertEquals(dhuhr(lyon, friday), seasonal.jumuaTime(lyon, friday, dhuhr(lyon, friday)))
        // The bundled table: Madrid's own seasons, not Spain's winter value (Q30). In January Seville's
        // Dhuhr + 5 (about 13:36) is later than Spain's 13:30; in November it is not.
        val madrid = EngineTestSupport.place(40.42, -3.70, "Europe/Madrid", "ES")
        val november = LocalDate.of(2026, 11, 6)
        assertEquals(dhuhr(madrid, november), SilenceDefaults.BUNDLED.jumuaTime(madrid, november, dhuhr(madrid, november)))
        val seville = EngineTestSupport.place(37.3891, -5.9845, "Europe/Madrid", "ES")
        assertEquals(at("2026-11-06", "13:30", ZoneId.of("Europe/Madrid")), SilenceDefaults.BUNDLED.jumuaTime(seville, november, dhuhr(seville, november)))
        assertEquals(dhuhr(seville, friday).plusSeconds(300), SilenceDefaults.BUNDLED.jumuaTime(seville, friday, dhuhr(seville, friday)))
        // Algeria: Dhuhr west of 2°E and in Annaba, 13:00 in Constantine.
        val oran = EngineTestSupport.place(35.6971, -0.6308, "Africa/Algiers", "DZ")
        val annaba = EngineTestSupport.place(36.90, 7.76, "Africa/Algiers", "DZ")
        val constantine = EngineTestSupport.place(36.365, 6.6147, "Africa/Algiers", "DZ")
        assertEquals(dhuhr(oran, friday), SilenceDefaults.BUNDLED.jumuaTime(oran, friday, dhuhr(oran, friday)))
        assertEquals(dhuhr(annaba, friday), SilenceDefaults.BUNDLED.jumuaTime(annaba, friday, dhuhr(annaba, friday)))
        assertEquals(at("2026-01-09", "13:00", ZoneId.of("Africa/Algiers")), SilenceDefaults.BUNDLED.jumuaTime(constantine, friday, dhuhr(constantine, friday)))
    }

    @Test
    fun theAuthoritysFridayTime() {
        // A18: the UAE's 12:45 from 2026-01-02 and 13:15 before, under any method, giving 12:45–13:30.
        val dubai = EngineTestSupport.place(25.2048, 55.2708, "Asia/Dubai", "AE")
        val dubaiZone = ZoneId.of("Asia/Dubai")
        for ((date, expected) in listOf(LocalDate.of(2026, 10, 16) to "12:45", LocalDate.of(2025, 10, 17) to "13:15")) {
            val dhuhr = checkNotNull(PrayerEngine.day(dubai, date, PrayerMethods.MWL)[TimeEvent.DHUHR].at)
            assertEquals(at(date.toString(), expected, dubaiZone), SilenceDefaults.BUNDLED.jumuaTime(dubai, date, dhuhr))
        }
        val window = planner(dubai, mapOf(Prayer.JOMOAA to SilenceRule(SilenceStart.Unset, SilenceEnd.Unset)), SilenceDefaults.BUNDLED).windowsOf(LocalDate.of(2026, 10, 16)).single()
        assertEquals(at("2026-10-16", "13:30", dubaiZone), window.end)
    }

    @Test
    fun acrossTheDateLine() {
        // Plan 9.6: Mata-Utu on Friday 2026-10-16, Jumu'a from Dhuhr at 23:30 UTC on 10-15; Saturday has Dhuhr.
        val mataUtu = EngineTestSupport.place(-13.2825, -176.1736, "Pacific/Wallis", "WF")
        val unset = SilenceRule(SilenceStart.Unset, SilenceEnd.Unset)
        val planner = planner(mataUtu, mapOf(Prayer.DHUHR to unset, Prayer.JOMOAA to unset), SilenceDefaults.BUNDLED)
        val jumua = planner.windowsOf(LocalDate.of(2026, 10, 16)).single()
        assertEquals(Prayer.JOMOAA, jumua.prayer)
        val utc = jumua.start.atOffset(ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 10, 15), utc.toLocalDate())
        assertTrue(kotlin.math.abs(Duration.between(LocalTime.of(23, 30), utc.toLocalTime()).toMinutes()) <= 1, "$utc")
        assertEquals(Prayer.DHUHR, planner.windowsOf(LocalDate.of(2026, 10, 17)).single().prayer)
    }

    @Test
    fun theYearRunProposesAlternatives() {
        // At Paris, a 13:00 end saved at adhan + 35 fails on the summer-time days; both proposals fit all year.
        val parisPlace = EngineTestSupport.place(48.8566, 2.3522, "Europe/Paris", "FR")
        val saved = SilenceRule(SilenceStart.Unset, SilenceEnd.Clock(LocalTime.of(13, 0), savedAdhanOffsetMinutes = 35))
        val planner = planner(parisPlace, emptyMap(), SilenceDefaults(emptyList()))
        val fit = planner.fit(Prayer.DHUHR, saved, LocalDate.of(2026, 1, 1))
        assertTrue(fit.notFitting.isNotEmpty())
        assertTrue(LocalDate.of(2026, 7, 21) in fit.notFitting)
        assertTrue(LocalDate.of(2026, 11, 3) !in fit.notFitting)
        val later = assertNotNull(fit.laterFixedEnd)
        assertTrue(later in LocalTime.of(14, 0)..LocalTime.of(14, 15), "$later")
        assertEquals(35, fit.adhanRelativeEnd)
        assertTrue(planner.fit(Prayer.DHUHR, SilenceRule(SilenceStart.Unset, SilenceEnd.Clock(later)), LocalDate.of(2026, 1, 1)).fitsAllYear)
        assertTrue(planner.fit(Prayer.DHUHR, SilenceRule(SilenceStart.Unset, SilenceEnd.FromAdhan(35)), LocalDate.of(2026, 1, 1)).fitsAllYear)
        // A Friday-only prayer is checked on Fridays only.
        val fridays = planner(parisPlace, emptyMap(), SilenceDefaults(emptyList())).fit(Prayer.JOMOAA, SilenceRule(SilenceStart.Unset, SilenceEnd.Unset), LocalDate.of(2026, 1, 1))
        assertEquals(52, fridays.checkedDays)
        assertTrue(fridays.fitsAllYear)
    }

    /** A planner over the engine's MWL days at [place], with Friday's Jumu'a from [defaults]. */
    private fun planner(place: PrayerPlace.Coordinates, rules: Map<Prayer, SilenceRule>, defaults: SilenceDefaults) =
        SilenceWindowPlanner(place.zone, false, rules) { date ->
            val times = PrayerEngine.day(place, date, PrayerMethods.MWL)
            val jumua = if (date.dayOfWeek == java.time.DayOfWeek.FRIDAY) times[TimeEvent.DHUHR].at?.let { defaults.jumuaTime(place, date, it) } else null
            SilenceDay(date, times, jumua)
        }
}
