package com.tunisianprayertimes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.place.CityRef
import com.tunisianprayertimes.place.LatLngE5
import com.tunisianprayertimes.place.PrayerPlace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The catalogue, the picker's city search and the routing of a fix abroad (plan 6.8), on a synthetic catalogue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PlaceResolutionTest {
    private lateinit var context: Context

    private fun row(id: Long, lat: Double, lng: Double, cc: String, zone: String, name: String, arabic: String, population: Int,
                    dem: Int? = null, aliases: List<String> = emptyList(), admin2: String? = null) =
        PlaceCatalogue.Row(CityRef(id, LatLngE5.of(lat, lng), cc, zone, admin2 = admin2, population = population), name, name, arabic, aliases, dem)

    private val riyadh = row(108410, 24.68773, 46.72185, "SA", "Asia/Riyadh", "Riyadh", "الرياض", 4205961, dem = 612)
    private val koeln = row(2886242, 50.93333, 6.95, "DE", "Europe/Berlin", "Köln", "كولونيا", 963395, aliases = listOf("Cologne"))
    private val paris = row(2988507, 48.85341, 2.3488, "FR", "Europe/Paris", "Paris", "باريس", 2138551, admin2 = "75")
    private val sfaxSeat = row(-386, 34.74, 10.76, "TN", "Africa/Tunis", "Sfax", "صفاقس", 0)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        PlaceCatalogue.setForTests(PlaceCatalogue.of(listOf(riyadh, koeln, paris, sfaxSeat)))
    }

    @After
    fun tearDown() = PlaceCatalogue.setForTests(null)

    @Test
    fun theBuildScriptsFormatParses() {
        val rows = PlaceCatalogue.parse(sequenceOf(
            "id\tcountry\tadmin1\tadmin2\tdem\tname\tascii\tarabic\taliases\tlatE5\tlngE5\tzone\tpopulation\tflags",
            "2988507\tFR\t\t75\t\tParis\tParis\tباريس\tParigi|Parijs\t4885341\t234880\tEurope/Paris\t2138551\t",
            "2996944\tFR\t\t69\t\tLyon\t\tليون\t\t4574846\t484671\t\t522969\t",
            "112931\tIR\t\t\t\tKhorramshahr\t\t\t\t3044079\t4818428\tAsia/Tehran\t5000\tn",
        ))
        assertEquals(3, rows.size)
        assertEquals("75", rows[0].ref.admin2)
        assertEquals(listOf("Parigi", "Parijs"), rows[0].aliases)
        assertEquals("Europe/Paris", rows[1].ref.zoneId)  // an empty zone repeats the row above's (the 2 MB budget)
        assertEquals("Khorramshahr", rows[2].ascii)  // an empty ascii is the name
        assertEquals("Asia/Tehran", rows[2].ref.zoneId)
        assertTrue(rows[2].ref.neighbourOnly)
        assertFalse(rows[2].listed)
    }

    @Test
    fun theCitySearchFoldsAndKnowsExonyms() {
        assertEquals("koln", CitySearch.normalize("Köln"))
        assertEquals("strasse", CitySearch.normalize("Straße"))
        val rows = listOf(riyadh, koeln, paris)
        assertEquals(listOf(koeln), CitySearch.search(rows, "Koeln"))
        assertEquals(listOf(koeln), CitySearch.search(rows, "cologne"))
        assertEquals(listOf(koeln), CitySearch.search(rows, "كولونيا"))
        assertEquals(listOf(riyadh), CitySearch.search(rows, "الرياض"))
        assertEquals("فلسطين", CitySearch.countryName("PS"))
        assertEquals("المغرب", CitySearch.countryName("EH"))
    }

    @Test
    fun aFixAbroadIsIgnoredWhileItsRegionIsClosed() {
        PrefsManager.setRegionOpen(context, arabLeague = false)  // development builds open it by default
        val result = PlaceResolution.resolveAbroad(context, 24.70, 46.70, network = "sa", phoneZone = "Asia/Riyadh")
        assertEquals(DelegationLocationResult.RegionClosed("SA"), result)
    }

    @Test
    fun aFixInAnOpenRegionBecomesAPlaceAbroad() {
        PrefsManager.setRegionOpen(context, arabLeague = true)
        val result = PlaceResolution.resolveAbroad(context, 24.7012, 46.7044, network = "sa", phoneZone = "Europe/Paris")
        val abroad = result as DelegationLocationResult.Abroad
        assertEquals("SA", abroad.place.countryCode)
        assertEquals("Asia/Riyadh", abroad.place.zoneId)
        assertEquals(612, abroad.place.elevationM)
        assertEquals(LatLngE5.of(24.70, 46.70), abroad.place.point) // stored rounded to 0.01°
        assertEquals("قرب الرياض", abroad.label)
    }

    @Test
    fun aFixInNorwayTakesItsMunicipalityFromTheNearestRowOrIrnEntryPoint() {
        // 6.8: Lesja's IRN entry point (municipality 3431) lies 112 km from the nearest catalogue row (Røros, 5025);
        // a fix there takes its own municipality, so ADMIN_AREA gives it Lesja's table, not Røros's.
        val roros = row(3141310, 62.57451, 11.38427, "NO", "Europe/Oslo", "Røros", "روروس", 3598, admin2 = "5025")
        val lesja = CityRef(-10_000_000L, LatLngE5.of(62.11463, 9.44037), "NO", "Europe/Oslo", admin2 = "3431")
        PlaceCatalogue.setForTests(PlaceCatalogue.of(listOf(roros), entryPoints = { if (it == "NO") listOf(lesja) else emptyList() }))
        PrefsManager.setRegionOpen(context, europe = true)
        val result = PlaceResolution.resolveAbroad(context, 62.115, 9.441, network = "no", phoneZone = "Europe/Oslo")
        val place = (result as DelegationLocationResult.Abroad).place
        assertEquals("NO", place.countryCode)
        assertEquals("3431", place.admin2)
    }

    @Test
    fun aFixOffTunisiaTakesItsNearestDelegation() {
        // 20 km off Sfax on a Tunisian network: TN by the country step, so INM (D7).
        val result = PlaceResolution.resolveAbroad(context, 34.74, 10.98, network = "tn", phoneZone = "Africa/Tunis")
        assertTrue("$result", result is DelegationLocationResult.Success || result is DelegationLocationResult.NoDelegationFound)
    }

    @Test
    fun aPickedCityKeepsItsOwnValues() {
        val place = PlaceResolution.placeFromCatalogue(context, paris, phoneZone = "Africa/Tunis")
        assertEquals("Europe/Paris", place.zoneId)
        assertEquals("75", place.admin2)
        assertEquals(2988507L, place.catalogueId)
        assertEquals(null, place.elevationM)
        assertEquals("باريس", PlaceResolution.catalogueLabel(paris))
    }

    @Test
    fun travelStoresASemanticChangeAtAnyDistanceAndAMoveBeyond3Km() {
        val saved = PlaceResolution.placeFromCatalogue(context, paris)
        PrefsManager.setPlace(context, saved, "باريس", fromGps = false)
        val near = saved.copy(latE5 = saved.latE5 + 1_000, catalogueId = null) // about 1.1 km north
        val far = saved.copy(latE5 = saved.latE5 + 5_000, catalogueId = null) // about 5.6 km north
        assertFalse(PlaceResolution.shouldStore(context, saved, near))
        assertTrue(PlaceResolution.shouldStore(context, saved, far))
        assertTrue(PlaceResolution.shouldStore(context, saved, near.copy(admin2 = "92")))
        assertTrue(PlaceResolution.shouldStore(context, PrayerPlace.Inm(615), near))
    }

    @Test
    fun aSkippedFirstRunStartsFromTheNetworkCountryInThePhonesZone() {
        val lyon = row(2996944, 45.74846, 4.84671, "FR", "Europe/Paris", "Lyon", "ليون", 522969, admin2 = "69")
        val saintDenis = row(935264, -20.88231, 55.4504, "RE", "Indian/Reunion", "Saint-Denis", "سان دوني", 145000)
        PlaceCatalogue.setForTests(PlaceCatalogue.of(listOf(riyadh, koeln, paris, lyon, saintDenis, sfaxSeat)))
        // 1. A phone that looks Tunisian: Tunis.
        assertEquals(PrayerPlace.Inm(615), PlaceResolution.provisionalPlace(context, "tn", "Africa/Tunis", looksTunisian = true).first)
        // 2. The largest city of the network country in the phone's zone.
        val (inFrance, label) = PlaceResolution.provisionalPlace(context, "fr", "Europe/Paris", looksTunisian = false)
        assertEquals(paris.ref.id, (inFrance as PrayerPlace.Coordinates).catalogueId)
        assertEquals("باريس", label)
        // 3. Overseas France reads another network: the largest city in the phone's zone.
        val reunion = PlaceResolution.provisionalPlace(context, "gp", "Indian/Reunion", looksTunisian = false).first
        assertEquals(saintDenis.ref.id, (reunion as PrayerPlace.Coordinates).catalogueId)
        // 4. Nothing in the zone: Tunis, never a city guessed across zones.
        assertEquals(PrayerPlace.Inm(615), PlaceResolution.provisionalPlace(context, null, "America/Lima", looksTunisian = false).first)
    }

    @Test
    fun aZoneChangeAsksForTheCityOnlyWhenThePlacesOffsetDiffers() {
        val place = PlaceResolution.placeFromCatalogue(context, paris, "Europe/Paris")
        PrefsManager.setPlace(context, place, "باريس", fromGps = false)
        val summer = java.time.Instant.parse("2026-07-01T12:00:00Z")
        assertTrue(TravelPlaceWorker.placeOffsetDiffers(context, summer, java.time.ZoneId.of("Africa/Tunis")))
        assertFalse(TravelPlaceWorker.placeOffsetDiffers(context, summer, java.time.ZoneId.of("Europe/Brussels")))
    }

    @Test
    fun aMoveInFranceResolvesTheUnpickedDefaultAgain() = kotlinx.coroutines.runBlocking {
        val lyon = row(2996944, 45.74846, 4.84671, "FR", "Europe/Paris", "Lyon", "ليون", 522969, admin2 = "69")
        PlaceCatalogue.setForTests(PlaceCatalogue.of(listOf(riyadh, koeln, paris, lyon, sfaxSeat)))
        val atParis = PlaceResolution.placeFromCatalogue(context, paris, "Europe/Paris")
        val atLyon = PlaceResolution.placeFromCatalogue(context, lyon, "Europe/Paris")
        val times = PrayerTimesRepository.placeTimesFor(context)
        // A choice saved in Paris without picking a method (an Asr change) keeps Paris's default...
        PrefsManager.setMethodChoice(context, atParis, com.tunisianprayertimes.method.PrayerMethodChoice(times.defaults(atParis).fallback, asrShadow = 2))
        PrefsManager.setPlace(context, atLyon, "ليون", fromGps = true)
        PlaceResolution.onPlaceChanged(context, atParis, atLyon, "باريس", "ليون")
        // ...until the move, where France's default follows the département (6.4), the Asr choice kept.
        val after = requireNotNull(PrefsManager.getMethodChoice(context, atLyon))
        assertEquals(times.defaults(atLyon).fallback, after.methodId)
        assertEquals(2, after.asrShadow)
    }

    @Test
    fun adhkarFlagsAfterAMoveAreKeptUntilTheReminderIsSavedAgain() {
        PrefsManager.setAdhkarPlaceFlags(context, mapOf("evening" to "من 12 أفريل", "night" to "من 1 جوان"))
        assertEquals(setOf("evening", "night"), PrefsManager.getAdhkarPlaceFlags(context).keys)
        PrefsManager.clearAdhkarPlaceFlag(context, "evening")
        assertEquals(mapOf("night" to "من 1 جوان"), PrefsManager.getAdhkarPlaceFlags(context))
    }
}