package com.tunisianprayertimes.place

import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.ArabAuthorityProfiles
import com.tunisianprayertimes.method.EuropeanPresets
import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.method.TerrainClass
import com.tunisianprayertimes.method.ZonePick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Zone mapping and the validity guard (plan 6.5), on synthetic zones. */
class ZoneMappingTest {
    private fun place(lat: Double, lng: Double, zone: String, country: String, admin1: String? = null, admin2: String? = null, catalogueId: Long? = null, elevation: Int? = null) =
        PrayerPlace.Coordinates(LatLngE5.of(lat, lng).latE5, LatLngE5.of(lat, lng).lngE5, elevation, zone, ZoneSource.USER, country, catalogueId, admin1, admin2)

    private fun zone(authority: String, id: String, lat: Double, lng: Double, build: OfficialZone.() -> OfficialZone = { this }) =
        OfficialZone(authority, id, id, LatLngE5.of(lat, lng)).build()

    private val libya = AuthorityZones(
        authority = "ly_awqaf", methodId = "ly_awqaf", country = "LY", mapping = MappingKind.NEAREST, byAdmin1 = false, namedFirst = false,
        terrainClassKm = null, fit = null, source = "test",
        zones = listOf(
            zone("ly_awqaf", "tripoli", 32.891, 13.273) { copy(namedPlaces = listOf(NamedPlace(LatLngE5.of(32.891, 13.273), listOf(2210247L)))) },
            zone("ly_awqaf", "zawiya", 32.7522, 12.7278),
            zone("ly_awqaf", "sabha", 27.0377, 14.4283),
        ),
    )

    @Test
    fun nearestAndTheGuard() {
        val guard = ValidityGuard()
        val method = ArabAuthorityProfiles.LY_AWQAF
        // Sabratha keeps Zawiya's table (its leads stay within the margin plus 4 minutes).
        val sabratha = ZoneMapping.resolve(place(32.7933, 12.4885, "Africa/Tripoli", "LY"), method, libya, null, 2026, guard)
        assertEquals("zawiya", assertNotNull(sabratha).zone.id)
        // Ghat would get Sabha's Maghrib before its own sunset: the guard gives it the method at its own point.
        val ghatPlace = place(24.9647, 10.1728, "Africa/Tripoli", "LY")
        assertEquals("sabha", ZoneMapping.attach(ghatPlace, method, libya)?.id)
        assertNull(ZoneMapping.resolve(ghatPlace, method, libya, null, 2026, guard))
        // A place the authority names itself is never checked; «حسب موقعي بالضبط» and a place outside the country get none.
        val tripoli = place(32.8872, 13.1913, "Africa/Tripoli", "LY", catalogueId = 2210247L)
        assertEquals("tripoli", ZoneMapping.resolve(tripoli, method, libya, null, 2026, guard)?.zone?.id)
        assertNull(ZoneMapping.resolve(tripoli, method, libya, ZonePick.Exact, 2026, guard))
        assertNull(ZoneMapping.resolve(place(36.8, 10.18, "Africa/Tunis", "TN"), method, libya, null, 2026, guard))
        assertNull(ZoneMapping.resolve(place(30.04, 31.24, "Africa/Cairo", "EG"), method, libya, null, 2026, guard))
        // A zone picked by hand is checked too.
        assertNull(ZoneMapping.resolve(ghatPlace, method, libya, ZonePick.Zone("sabha"), 2026, guard))
    }

    @Test
    fun betweenTheTerrainLimitsAPlaceTakesItsNearestLocationsClass() {
        // E10: Maghrib + 6 at or below 650 m, + 10 at or above 1,100 m; between them, the nearest location's class.
        val method = ArabAuthorityProfiles.YE_AWQAF
        val yemen = AuthorityZones("ye_awqaf", "ye_awqaf", "YE", MappingKind.NEAREST, false, false, 60.0, source = "test", fit = null,
            zones = listOf(
                zone("ye_awqaf", "low", 15.50, 44.30) { copy(elevationM = 300) },
                zone("ye_awqaf", "high", 15.50, 43.55) { copy(elevationM = 2000) },
            ))
        // 1,000 m, 32 km from the lowland location and 48 km from the highland one: the nearer limit would say high.
        val band = place(15.50, 44.00, "Asia/Aden", "YE", elevation = 1000)
        assertEquals(TerrainClass.LOW, ZoneMapping.ownTerrainClass(band, method, yemen))
        assertEquals("low", ZoneMapping.attach(band, method, yemen)?.id)
        // The guard compares the table with the place's own times in that same class, so it keeps the table: in the
        // other class the place's own Maghrib would lead the table's by 4 minutes more and the guard would act.
        assertEquals("low", ZoneMapping.resolve(band, method, yemen, null, 2026, ValidityGuard())?.zone?.id)
        // Outside the limits the elevation decides, whatever the nearest location.
        val highland = place(15.50, 44.00, "Asia/Aden", "YE", elevation = 1200)
        assertEquals(TerrainClass.HIGH, ZoneMapping.ownTerrainClass(highland, method, yemen))
        assertEquals("high", ZoneMapping.attach(highland, method, yemen)?.id)
        // A method without E10 has no class.
        assertNull(ZoneMapping.ownTerrainClass(band, PrayerMethods.MWL, yemen))
    }

    @Test
    fun jordansValleyRuleAndItsJerusalemEntry() {
        // 8.8: below sea level in Irbid, Balqa or Karak, the governorate's Ghor table first; the Jerusalem entry serves Jerusalem only.
        val jordan = AuthorityZones("jo_awqaf", "jo_awqaf", "JO", MappingKind.NEAREST, true, true, null, source = "test", fit = null,
            zones = listOf(
                zone("jo_awqaf", "1", 31.95, 35.93) {
                    copy(admin1 = listOf("16", "02", "17", "15"), namedPlaces = listOf(NamedPlace(LatLngE5.of(32.04, 35.73), areaKm = 5.0)))
                },
                zone("jo_awqaf", "13", 31.90, 35.62) { copy(admin1 = listOf("02"), belowSeaLevel = true) },
                zone("jo_awqaf", "33", 31.7834, 35.2339) { copy(country = "PS", baseZoneId = "1", reachKm = 15.0) },
            ))
        val method = ArabAuthorityProfiles.JO_AWQAF
        // Deir Alla, in Balqa, 224 m below sea level: Central Ghor, whatever the named towns.
        assertEquals("13", ZoneMapping.attach(place(32.20, 35.62, "Asia/Amman", "JO", admin1 = "02", elevation = -224), method, jordan)?.id)
        // Salt, a named town of Amman's table, above sea level.
        assertEquals("1", ZoneMapping.attach(place(32.04, 35.73, "Asia/Amman", "JO", admin1 = "02", elevation = 800), method, jordan)?.id)
        // Below sea level with no governorate known: the nearest Ghor table.
        assertEquals("13", ZoneMapping.attach(place(31.85, 35.58, "Asia/Amman", "JO", elevation = -300), method, jordan)?.id)
        // Jerusalem takes the entry; Nablus, about 49 km north, takes no Jordanian table.
        assertEquals("33", ZoneMapping.attach(place(31.78, 35.23, "Asia/Hebron", "PS"), method, jordan)?.id)
        assertNull(ZoneMapping.attach(place(32.22, 35.26, "Asia/Hebron", "PS"), method, jordan))
    }

    @Test
    fun iraqsKurdistanRegionTakesTheMethodAtThePlace() {
        // 8.8 (Q8): the Sunni Endowment's cities leave out the Kurdistan Region (GeoNames IQ.05, IQ.08, IQ.11, IQ.19).
        val iraq = AuthorityZones("iq_sunni_endowment", "iq_sunni_endowment", "IQ", MappingKind.NEAREST, false, false, null, source = "test", fit = null,
            zones = listOf(zone("iq_sunni_endowment", "baghdad", 33.34, 44.40), zone("iq_sunni_endowment", "kirkuk", 35.47, 44.39)),
            exactAdmin1 = setOf("05", "08", "11", "19"),
        )
        val method = ArabAuthorityProfiles.IQ_SUNNI_ENDOWMENT
        assertNull(ZoneMapping.attach(place(36.19, 44.01, "Asia/Baghdad", "IQ", admin1 = "11"), method, iraq))
        assertNull(ZoneMapping.resolve(place(35.56, 45.43, "Asia/Baghdad", "IQ", admin1 = "05"), method, iraq, null, 2026, ValidityGuard()))
        assertEquals("kirkuk", ZoneMapping.attach(place(35.47, 44.39, "Asia/Baghdad", "IQ", admin1 = "13"), method, iraq)?.id)
        assertEquals("baghdad", ZoneMapping.attach(place(33.31, 44.36, "Asia/Baghdad", "IQ", admin1 = "07"), method, iraq)?.id)
        // The field survives the asset's round trip.
        assertEquals(setOf("05", "08", "11", "19"), OfficialZones.parse(OfficialZones.write(iraq)).exactAdmin1)
    }

    @Test
    fun namedPlacesFirstThenTheNearestCentre() {
        val egypt = AuthorityZones("eg_egsa", "eg_egsa", "EG", MappingKind.NEAREST, false, true, null, source = "test", fit = null,
            zones = listOf(
                zone("eg_egsa", "cairo", 30.0444, 31.2357) { copy(namedPlaces = listOf(NamedPlace(LatLngE5.of(30.0444, 31.2357), areaKm = 15.0))) },
                zone("eg_egsa", "giza", 30.0131, 31.2089) { copy(namedPlaces = listOf(NamedPlace(LatLngE5.of(30.0131, 31.2089), areaKm = 15.0))) },
                zone("eg_egsa", "alexandria", 31.2001, 29.9187) { copy(namedPlaces = listOf(NamedPlace(LatLngE5.of(31.2001, 29.9187), areaKm = 15.0))) },
            ))
        // Within both areas, the nearest centre; outside every area, the nearest town centre, whatever the distance.
        assertEquals("giza", ZoneMapping.attach(place(30.02, 31.21, "Africa/Cairo", "EG"), EuropeanPresets.MWL_ANGLE, egypt)?.id)
        assertEquals("alexandria", ZoneMapping.attach(place(31.0, 30.4, "Africa/Cairo", "EG"), EuropeanPresets.MWL_ANGLE, egypt)?.id)
        assertTrue(egypt.zones[0].names(place(30.05, 31.24, "Africa/Cairo", "EG")))
    }

    @Test
    fun tiersReachesAndOtherKinds() {
        // Austria: IGGÖ's cities within 25 km, otherwise Diyanet's nearest Austrian entry.
        val austria = AuthorityZones("diyanet_eu_at", "diyanet_eu", "AT", MappingKind.NEAREST, false, false, null, source = "test", fit = null,
            zones = listOf(
                zone("diyanet_eu_at", "graz", 47.0707, 15.4395) { copy(reachKm = 25.0) },
                zone("diyanet_eu_at", "leibnitz", 46.7829, 15.5385) { copy(mappingTier = 1) },
            ))
        assertEquals("graz", ZoneMapping.attach(place(46.85, 15.44, "Europe/Vienna", "AT"), EuropeanPresets.DIYANET_EU, austria)?.id)
        assertEquals("leibnitz", ZoneMapping.attach(place(46.80, 15.50, "Europe/Vienna", "AT"), EuropeanPresets.DIYANET_EU, austria)?.id)
        // Saudi Arabia: within an entry's reach, else the user's point.
        val saudi = AuthorityZones("sa_umm_al_qura", "sa_umm_al_qura", "SA", MappingKind.NEAREST_LOCAL, false, false, null, source = "test", fit = null,
            zones = listOf(zone("sa_umm_al_qura", "riyadh", 24.7136, 46.6753) { copy(reachKm = 25.0) }))
        assertEquals("riyadh", ZoneMapping.attach(place(24.75, 46.70, "Asia/Riyadh", "SA"), PrayerMethods.UMM_AL_QURA, saudi)?.id)
        assertNull(ZoneMapping.attach(place(21.42, 39.83, "Asia/Riyadh", "SA"), PrayerMethods.UMM_AL_QURA, saudi))
        // Turkey: the place's district, else the nearest entry.
        val turkey = AuthorityZones("diyanet_tr", "diyanet_tr", "TR", MappingKind.DISTRICT, false, false, null, source = "test", fit = null,
            zones = listOf(
                zone("diyanet_tr", "istanbul", 41.010, 28.970) { copy(admin2 = listOf("Kadıköy", "Bakırköy")) },
                zone("diyanet_tr", "basaksehir", 41.093, 28.802) { copy(admin2 = listOf("Başakşehir")) },
                zone("diyanet_tr", "esenyurt", 41.034, 28.680) { copy(admin2 = listOf("Esenyurt")) },
            ))
        assertEquals("basaksehir", ZoneMapping.attach(place(41.06, 28.67, "Europe/Istanbul", "TR", admin2 = "Başakşehir"), EuropeanPresets.DIYANET_TR, turkey)?.id)
        assertEquals("istanbul", ZoneMapping.attach(place(40.99, 29.03, "Europe/Istanbul", "TR", admin2 = "Kadıköy"), EuropeanPresets.DIYANET_TR, turkey)?.id)
        // Montenegro outside its towns: Sarajevo plus the whole minute nearest to 4 × (18.41 − λ).
        val bosnia = AuthorityZones("bosnia_iz", "bosnia_iz", "BA", MappingKind.LISTED_PLACES, false, false, null, source = "test", fit = null,
            zones = listOf(
                zone("bosnia_iz", "sarajevo", 43.8667, 18.4333),
                zone("bosnia_iz", "bijelo_polje", 43.0383, 19.7476) { copy(country = "ME", reachKm = 8.0, baseZoneId = "sarajevo", offsets = TimeEvent.entries.associateWith { -5.0 }) },
            ))
        val podgorica = ZoneMapping.attach(place(42.4304, 19.2594, "Europe/Podgorica", "ME"), EuropeanPresets.BOSNIA_IZ, bosnia)
        assertEquals(-3.0, assertNotNull(podgorica).offsets[TimeEvent.FAJR])
        assertEquals("sarajevo", podgorica.baseZoneId)
        assertEquals("bijelo_polje", ZoneMapping.attach(place(43.04, 19.75, "Europe/Podgorica", "ME"), EuropeanPresets.BOSNIA_IZ, bosnia)?.id)
    }

    @Test
    fun assetsRoundTrip() {
        val text = OfficialZones.write(libya)
        val back = OfficialZones.parse(text)
        assertEquals(libya.zones, back.zones)
        assertEquals(MappingKind.NEAREST, back.mapping)
    }

    @Test
    fun aNationalZoneWithAdminAreasCoversOnlyThose() {
        // Rhône (6.5, Q33): «الرون» in département 69, the method at the user's own point elsewhere in France.
        val rhone = AuthorityZones("rhone_ctir", "rhone_ctir", "FR", MappingKind.NATIONAL, false, false, null, source = "test", fit = null,
            zones = listOf(zone("rhone_ctir", "69", 45.744, 4.842) { copy(admin2 = listOf("69")) }))
        val method = EuropeanPresets.RHONE_CTIR
        val guard = ValidityGuard()
        val villeurbanne = place(45.7719, 4.8902, "Europe/Paris", "FR", admin2 = "69")
        assertEquals("69", ZoneMapping.resolve(villeurbanne, method, rhone, null, 2026, guard)?.zone?.id)
        assertNull(ZoneMapping.resolve(place(45.5646, 5.9178, "Europe/Paris", "FR", admin2 = "73"), method, rhone, null, 2026, guard))
        assertNull(ZoneMapping.resolve(place(45.7719, 4.8902, "Europe/Paris", "FR"), method, rhone, null, 2026, guard))
    }

    @Test
    fun leTamponsColumnIsTheFloorOfTheElevationShift() {
        // Plan 7.6: Maghrib and Isha move at least + 2 at Le Tampon's places, the column counting toward E16 (Cilaos: + 5).
        val tampon = zone("re_cir", "sud_tampon", -21.278, 55.517) { copy(elevationShiftFloor = 2.0) }
        val date = java.time.LocalDate.of(2026, 6, 21)
        fun maghrib(elevation: Int?, zone: OfficialZone?) = com.tunisianprayertimes.engine.PrayerEngine.day(
            place(-21.278, 55.517, "Indian/Reunion", "RE", elevation = elevation), date, EuropeanPresets.RE_CIR, zone = zone,
        )[TimeEvent.MAGHRIB].at!!
        val base = maghrib(null, zone("re_cir", "sud", -21.278, 55.517))
        assertEquals(2L * 60, java.time.Duration.between(base, maghrib(null, tampon)).seconds)
        assertEquals(2L * 60, java.time.Duration.between(base, maghrib(300, tampon)).seconds)
        assertEquals(5L * 60, java.time.Duration.between(base, maghrib(1200, tampon)).seconds)
    }
}
