package com.tunisianprayertimes.place

import com.tunisianprayertimes.method.CountryMethodDefaults
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.method.PrayerMethodChoice
import com.tunisianprayertimes.method.PrayerMethods
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where each preset and profile is offered (owner, 2026-10-18): a method only where people follow it. The international
 * conventions everywhere; a body's method in its area and at the places its own tables name abroad; a stored choice only
 * where its method is offered.
 */
class MethodAvailabilityTest {
    private val times: PlaceTimes by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !dir.resolve("android-app/app/src/main/assets/official-zones").isDirectory) dir = dir.parentFile
        val folder = checkNotNull(dir).resolve("android-app/app/src/main/assets/official-zones")
        val assets = folder.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }.map { OfficialZones.parse(it.readText()) }
        PlaceTimes(null, OfficialZoneIndex(assets))
    }

    private fun place(lat: Double, lng: Double, zone: String, country: String, admin1: String? = null, admin2: String? = null) =
        PrayerPlace.Coordinates(LatLngE5.of(lat, lng).latE5, LatLngE5.of(lat, lng).lngE5, null, zone, ZoneSource.USER, country, admin1 = admin1, admin2 = admin2)

    private val riyadh = place(24.7136, 46.6753, "Asia/Riyadh", "SA")
    private val newYork = place(40.7128, -74.0060, "America/New_York", "US")
    private val ljubljana = place(46.0569, 14.5058, "Europe/Ljubljana", "SI")
    private val oslo = place(59.9139, 10.7522, "Europe/Oslo", "NO")
    private val stockholm = place(59.3293, 18.0686, "Europe/Stockholm", "SE")
    private val belgrade = place(44.7866, 20.4489, "Europe/Belgrade", "RS")
    private val istanbul = place(41.0082, 28.9784, "Europe/Istanbul", "TR")
    private val london = place(51.5074, -0.1278, "Europe/London", "GB")
    private val paris = place(48.8566, 2.3522, "Europe/Paris", "FR", admin2 = "75")
    private val algiers = place(36.7538, 3.0588, "Africa/Algiers", "DZ")

    private fun offered(place: PrayerPlace.Coordinates) = times.offeredMethods(place).map { it.id }.toSet()

    @Test
    fun theInternationalConventionsAreOfferedEverywhereAndNothingElseWhereNoBodyPublishes() {
        for (place in listOf(riyadh, newYork, ljubljana, oslo, london, algiers)) {
            assertTrue(offered(place).containsAll(CountryMethodDefaults.EVERYWHERE), "at ${place.countryCode}")
        }
        // New York: no authority or mosque practice of its own in the app, so the conventions alone.
        assertEquals(CountryMethodDefaults.EVERYWHERE, offered(newYork))
        assertEquals(CountryMethodDefaults.EVERYWHERE + "sa_umm_al_qura", offered(riyadh))
        assertFalse("inm" in offered(newYork))
    }

    @Test
    fun aBodysMethodIsOfferedOnlyWhereItIsFollowed() {
        // Slovenia's Islamic Community: Slovenia only, never Riyadh or New York.
        assertTrue("si_islamska_skupnost" in offered(ljubljana))
        assertFalse("si_islamska_skupnost" in offered(riyadh))
        assertFalse("si_islamska_skupnost" in offered(newYork))
        // Diyanet's European tables across Europe (Oslo's and Belgrade's Turkish mosques), not in Turkey or beyond Europe.
        assertTrue("diyanet_eu" in offered(oslo))
        assertTrue("diyanet_eu" in offered(belgrade))
        assertFalse("diyanet_eu" in offered(istanbul))
        assertFalse("diyanet_eu" in offered(newYork))
        assertTrue("diyanet_tr" in offered(newYork))
        // Norway's IRN, the UK's presets, Algeria's ministry: their countries.
        assertTrue("irn" in offered(oslo))
        assertFalse("irn" in offered(stockholm))
        assertTrue("london_unified" in offered(london))
        assertFalse("london_unified" in offered(paris))
        assertTrue("dz_marw" in offered(algiers))
        assertFalse("dz_marw" in offered(paris))
        // A French pick is kept for all of France (owner, 2026-10-11): Lyon's CTIR is offered in Paris.
        assertTrue("rhone_ctir" in offered(paris))
    }

    @Test
    fun anAuthoritysTablesExtendItToThePlacesTheyNameAbroad() {
        // Habous's Ceuta and Melilla localities (6.8), not Tarifa across the strait or Madrid.
        assertTrue("ma_habous" in offered(place(35.8892, -5.3213, "Africa/Ceuta", "ES", admin1 = "CE")))
        assertFalse("ma_habous" in offered(place(36.0139, -5.6070, "Europe/Madrid", "ES")))
        assertFalse("ma_habous" in offered(place(40.4168, -3.7038, "Europe/Madrid", "ES")))
        // Jordan's Jerusalem entry (8.8), not Nablus.
        assertTrue("jo_awqaf" in offered(place(31.7767, 35.2345, "Asia/Hebron", "PS")))
        assertFalse("jo_awqaf" in offered(place(32.2211, 35.2544, "Asia/Hebron", "PS")))
        // The Bosnian Takvim names Slovenian, Croatian, Serbian and Montenegrin towns; Vienna is none of them.
        assertTrue("bosnia_iz" in offered(ljubljana))
        assertTrue("bosnia_iz" in offered(belgrade))
        assertFalse("bosnia_iz" in offered(place(48.2082, 16.3738, "Europe/Vienna", "AT")))
        // BIK's takvim in the Preševo valley, not in Belgrade.
        assertTrue("xk_bik" in offered(place(42.3092, 21.6497, "Europe/Belgrade", "RS")))
        assertFalse("xk_bik" in offered(belgrade))
        // Gaza's ministry in the Gaza Strip only; Dar al-Ifta's national table in both (A17).
        val gaza = place(31.5017, 34.4668, "Asia/Gaza", "PS")
        val nablus = place(32.2211, 35.2544, "Asia/Hebron", "PS")
        assertTrue("ps_gaza_awqaf" in offered(gaza))
        assertFalse("ps_gaza_awqaf" in offered(nablus))
        assertTrue("ps_dar_al_ifta" in offered(gaza))
        assertTrue("ps_dar_al_ifta" in offered(nablus))
    }

    @Test
    fun aStoredChoiceAppliesOnlyWhereItsMethodIsOffered() {
        // Jordan's table picked in Jerusalem stays there; in Nablus, under the same key, the place takes its default.
        val jordan = PrayerMethodChoice("jo_awqaf", methodPicked = true)
        assertEquals(jordan, times.choiceAt(place(31.7767, 35.2345, "Asia/Hebron", "PS"), jordan))
        assertEquals("ps_dar_al_ifta", times.choiceAt(place(32.2211, 35.2544, "Asia/Hebron", "PS"), jordan).methodId)
        // Slovenia's method stored for a place in the US (before this rule) gives way to the country's default.
        assertEquals("mwl", times.choiceAt(newYork, PrayerMethodChoice("si_islamska_skupnost", methodPicked = true)).methodId)
        // The user's own «مخصّصة» applies anywhere.
        val custom = PrayerMethodChoice(PrayerMethod.CUSTOM_ID, custom = PrayerMethods.MWL.copy(id = PrayerMethod.CUSTOM_ID), methodPicked = true)
        assertEquals(custom, times.choiceAt(newYork, custom))
        assertEquals("mwl", times.choiceAt(newYork, null).methodId)
    }
}
