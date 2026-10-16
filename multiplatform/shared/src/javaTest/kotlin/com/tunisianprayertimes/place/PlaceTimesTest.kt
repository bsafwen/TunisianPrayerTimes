package com.tunisianprayertimes.place

import com.tunisianprayertimes.InmPrayerTimes
import com.tunisianprayertimes.engine.InmPrayerDays
import com.tunisianprayertimes.engine.PrayerEngine
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.ArabAuthorityProfiles
import com.tunisianprayertimes.method.PrayerMethodChoice
import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.method.ZonePick
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The repository's routing (plan 6.8, 6.9): INM, the engine, zones, E8, Algeria's windows, adjustments. */
class PlaceTimesTest {
    private val inm = InmPrayerTimes.fromParamsJson(checkNotNull(javaClass.getResource("/delegation_params.json")).readText())

    private fun place(lat: Double, lng: Double, zone: String, country: String?, admin1: String? = null) =
        PrayerPlace.Coordinates(LatLngE5.of(lat, lng).latE5, LatLngE5.of(lat, lng).lngE5, null, zone, ZoneSource.USER, country, admin1 = admin1)

    @Test
    fun aTunisianDelegationKeepsInmsTimes() {
        // Plan 6.8: delegation 615 byte-identical to today, relabelled at +01:00.
        val times = PlaceTimes(InmPrayerDays(inm))
        val date = LocalDate.of(2026, 7, 21)
        val day = assertNotNull(times.day(PrayerPlace.Inm(615), PrayerMethodChoice("mwl", adjustments = mapOf(TimeEvent.FAJR to 5)), date))
        val today = checkNotNull(inm.loadDayPrayerTimes(615, 2026, 7, 21))
        val fajr = checkNotNull(day[TimeEvent.FAJR].at).atOffset(ZoneOffset.ofHours(1))
        assertEquals(today.fajr.hour to today.fajr.minute, fajr.hour to fajr.minute)
        assertNull(times.day(PrayerPlace.Inm(615), null, LocalDate.of(2101, 1, 1)))
    }

    @Test
    fun theCountrysDefaultAndTheUsersChoice() {
        val times = PlaceTimes(null)
        val riyadh = place(24.7136, 46.6753, "Asia/Riyadh", "SA")
        assertEquals("sa_umm_al_qura", times.method(riyadh, null).id)
        assertEquals("mwl", times.method(place(33.6844, 73.0479, "Asia/Karachi", "PK"), null).id)
        assertEquals("isna", times.method(riyadh, PrayerMethodChoice("isna")).id)
        // An adjustment moves the time (step 9), the Asr factor is the user's.
        val date = LocalDate.of(2026, 10, 16)
        val plain = checkNotNull(times.day(riyadh, PrayerMethodChoice("mwl"), date))
        val moved = checkNotNull(times.day(riyadh, PrayerMethodChoice("mwl", asrShadow = 2, adjustments = mapOf(TimeEvent.MAGHRIB to 3)), date))
        assertEquals(3, Duration.between(plain[TimeEvent.MAGHRIB].at, moved[TimeEvent.MAGHRIB].at).toMinutes())
        assertEquals(true, Duration.between(plain[TimeEvent.ASR].at, moved[TimeEvent.ASR].at).toMinutes() > 30)
    }

    @Test
    fun theRamadanIshaOfE8() {
        val times = PlaceTimes(null)
        val mecca = place(21.4225, 39.8262, "Asia/Riyadh", "SA")
        fun ishaGap(date: LocalDate) = checkNotNull(times.day(mecca, null, date)).let { Duration.between(it[TimeEvent.MAGHRIB].at, it[TimeEvent.ISHA].at).toMinutes() }
        assertEquals(90, ishaGap(LocalDate.of(2026, 2, 17)))
        assertEquals(120, ishaGap(LocalDate.of(2026, 2, 18)))
    }

    @Test
    fun zonesAndTheGuard() {
        val libya = AuthorityZones(
            "ly_awqaf", "ly_awqaf", "LY", MappingKind.NEAREST, byAdmin1 = false, namedFirst = false, terrainClassKm = null, fit = null, source = "test",
            zones = listOf(
                OfficialZone("ly_awqaf", "zawiya", "الزاوية", LatLngE5.of(32.7522, 12.7278)),
                OfficialZone("ly_awqaf", "sabha", "سبها", LatLngE5.of(27.0377, 14.4283)),
            ),
        )
        val times = PlaceTimes(null, OfficialZoneIndex(listOf(libya)))
        val sabratha = place(32.7933, 12.4885, "Africa/Tripoli", "LY")
        assertEquals("zawiya", times.mapping(sabratha, null, 2026)?.zone?.id)
        assertNull(times.mapping(place(24.9647, 10.1728, "Africa/Tripoli", "LY"), null, 2026))
        assertNull(times.mapping(sabratha, PrayerMethodChoice("ly_awqaf", zonePick = ZonePick.Exact), 2026))
        // The zone's table: Sabratha shows Zawiya's times, not its own.
        val date = LocalDate.of(2026, 6, 21)
        val atZawiya = checkNotNull(times.day(place(32.7522, 12.7278, "Africa/Tripoli", "LY"), null, date))
        assertEquals(atZawiya.events.map { it.at }, checkNotNull(times.day(sabratha, null, date)).events.map { it.at })
    }

    @Test
    fun algeriasOffsetWindows() {
        val starts1448 = listOf(
            "2026-06-16", "2026-07-16", "2026-08-14", "2026-09-13", "2026-10-12", "2026-11-11",
            "2026-12-10", "2027-01-09", "2027-02-08", "2027-03-10", "2027-04-08", "2027-05-08",
        ).map(LocalDate::parse)
        val zones = AuthorityZones(
            "dz_marw", "dz_marw", "DZ", MappingKind.NEAREST, byAdmin1 = true, namedFirst = false, terrainClassKm = null, fit = null, source = "test",
            zones = listOf(
                OfficialZone("dz_marw", "algiers", "الجزائر", LatLngE5.of(36.7538, 3.0588)),
                OfficialZone("dz_marw", "adrar", "أدرار", LatLngE5.of(27.8742, -0.2939), halfMonthWindows = true),
                OfficialZone("dz_marw", "blida", "البليدة", LatLngE5.of(36.47, 2.83), baseZoneId = "algiers", offsetRule = ZoneOffsetRule.WINDOW_CEILING),
                OfficialZone("dz_marw", "timimoun", "تيميمون", LatLngE5.of(29.26, 0.23), baseZoneId = "adrar", offsetRule = ZoneOffsetRule.WINDOW_CEILING),
            ),
            hijriMonthStarts = mapOf(1448 to starts1448),
        )
        val windows = AlgerianOffsetWindows(zones)
        val blida = zones.byId.getValue("blida")
        assertEquals(LocalDate.of(2026, 10, 12)..LocalDate.of(2026, 11, 10), windows.windowFor(blida, LocalDate.of(2026, 10, 15)))
        assertEquals(LocalDate.of(2026, 9, 13)..LocalDate.of(2026, 10, 11), windows.windowFor(blida, LocalDate.of(2026, 10, 11)))
        val timimoun = zones.byId.getValue("timimoun")
        assertEquals(LocalDate.of(2026, 10, 12)..LocalDate.of(2026, 10, 26), windows.windowFor(timimoun, LocalDate.of(2026, 10, 20)))
        assertEquals(LocalDate.of(2026, 10, 28)..LocalDate.of(2026, 11, 10), windows.windowFor(timimoun, LocalDate.of(2026, 10, 27)))
        assertEquals(LocalDate.of(2026, 10, 28)..LocalDate.of(2026, 11, 10), windows.windowFor(timimoun, LocalDate.of(2026, 11, 5)))
        assertNull(windows.windowFor(zones.byId.getValue("algiers"), LocalDate.of(2026, 10, 15)))
        // A year not in the asset takes Umm al-Qura's month starts: 1 Ramadan 1447 = 2026-02-18.
        assertEquals(LocalDate.of(2026, 2, 18), windows.windowFor(blida, LocalDate.of(2026, 3, 1))?.start)
        // The place's times are never earlier than its own computed times (the ceiling over the window).
        val times = PlaceTimes(null, OfficialZoneIndex(listOf(zones)))
        val blidaPlace = place(36.47, 2.83, "Africa/Algiers", "DZ")
        val mapped = assertNotNull(times.mapping(blidaPlace, PrayerMethodChoice("dz_marw"), 2026))
        assertEquals("blida", mapped.zone.id)
        val date = LocalDate.of(2026, 10, 15)
        val table = checkNotNull(times.day(blidaPlace, PrayerMethodChoice("dz_marw"), date))
        val own = PrayerEngine.explain(blidaPlace, date, PrayerMethods.byId("dz_marw")!!)
        for (event in TimeEvent.entries) {
            val r = checkNotNull(own.events[event.ordinal].unrounded)
            assertEquals(true, !checkNotNull(table[event].at).isBefore(r.minusSeconds(30)), "$event")
        }
    }
}
