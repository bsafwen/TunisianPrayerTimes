package com.tunisianprayertimes.method

import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.place.LatLngE5
import com.tunisianprayertimes.place.Region
import com.tunisianprayertimes.place.Regions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Country defaults, choosers and the per-country choice (plan 6.4). */
class CountryMethodDefaultsTest {
    @Test
    fun everyDefaultAndEntryIsAKnownMethod() {
        val countries = Regions.EUROPE + Regions.OVERSEAS_FRANCE + Regions.ARAB_LEAGUE + setOf("EH", "PK", "IR", "TD", "US")
        for (cc in countries) {
            val d = CountryMethodDefaults.forCountry(cc)
            for (id in d.entries + d.fallback) {
                assertTrue(id == "inm" || PrayerMethods.byId(id) != null, "$cc: $id")
            }
            assertTrue(d.fallback in d.entries || d.chooser == com.tunisianprayertimes.method.ChooserKind.NONE || d.chooser == ChooserKind.ASR_ONLY, "$cc")
        }
        // The plan's 60 (6.3) with `inm`, which never reaches the engine: 6 reference presets, 26 European, 23 profiles, 4 overseas.
        assertEquals(59, PrayerMethods.ALL.size)
        assertEquals(PrayerMethods.ALL.size, PrayerMethods.ALL.map { it.id }.toSet().size, "ids are unique")
    }

    @Test
    fun franceByDepartement() {
        val paris = CountryMethodDefaults.forCountry("FR", admin2 = "75")
        assertEquals("mdf_12", paris.fallback)
        assertEquals(listOf("mdf_12", "isna", "diyanet_eu", "mwl"), paris.entries)
        assertEquals(null, paris.preselected)
        assertTrue(paris.resolvesOnMove)
        val lyon = CountryMethodDefaults.forCountry("FR", admin2 = "69")
        assertEquals("rhone_ctir", lyon.fallback)
        assertEquals("rhone_ctir", lyon.entries.first())
        val strasbourg = CountryMethodDefaults.forCountry("FR", admin2 = "67")
        assertEquals("diyanet_eu", strasbourg.fallback)
        assertEquals("diyanet_eu", strasbourg.entries.first())
        assertEquals(4, strasbourg.entries.size)
    }

    @Test
    fun placeDependentDefaults() {
        assertEquals("hr_miz_zagreb", CountryMethodDefaults.forCountry("HR", point = LatLngE5.of(45.49, 16.37)).fallback) // Sisak
        assertEquals("bosnia_iz", CountryMethodDefaults.forCountry("HR", point = LatLngE5.of(43.51, 16.44)).fallback) // Split
        assertEquals("xk_bik", CountryMethodDefaults.forCountry("RS", point = LatLngE5.of(42.46, 21.77)).fallback) // Bujanovac
        assertEquals("bosnia_iz", CountryMethodDefaults.forCountry("RS", point = LatLngE5.of(44.79, 20.45)).fallback) // Belgrade
        assertEquals("dum_rf", CountryMethodDefaults.forCountry("RU", admin1 = "48").fallback)
        assertEquals("dum_rf", CountryMethodDefaults.forCountry("RU", admin1 = "47").fallback)
        assertEquals("ru_dum_rt", CountryMethodDefaults.forCountry("RU", admin1 = "73").fallback)
        assertEquals("mwl", CountryMethodDefaults.forCountry("RU", admin1 = "52").fallback)
        assertEquals("ps_gaza_awqaf", CountryMethodDefaults.forCountry("PS", territory = "Asia/Gaza").fallback)
        assertEquals("ps_dar_al_ifta", CountryMethodDefaults.forCountry("PS", territory = "Asia/Hebron").fallback)
        assertEquals("ps_dar_al_ifta", CountryMethodDefaults.forCountry("PS", territory = "Asia/Jerusalem").fallback)
    }

    @Test
    fun choosersAndFallbacks() {
        val germany = CountryMethodDefaults.forCountry("DE")
        assertEquals(ChooserKind.METHODS, germany.chooser)
        assertEquals("diyanet_eu", germany.preselected)
        assertEquals("diyanet_eu", germany.fallback)
        assertEquals("karachi", CountryMethodDefaults.forCountry("BE").fallback)
        assertEquals("isna", CountryMethodDefaults.forCountry("CH").fallback)
        for (cc in listOf("NL", "IT", "GB")) assertEquals("mwl", CountryMethodDefaults.forCountry(cc).fallback, cc)
        assertEquals(ChooserKind.METHODS_AND_ASR, CountryMethodDefaults.forCountry("GB").chooser)
        assertEquals(ChooserKind.ASR_ONLY, CountryMethodDefaults.forCountry("PT").chooser)
        assertEquals(ChooserKind.ASR_ONLY, CountryMethodDefaults.forCountry("RE").chooser)
        assertEquals("yt_traditional", CountryMethodDefaults.forCountry("YT").preselected)
        assertEquals("irn", CountryMethodDefaults.forCountry("SJ").fallback)
        assertEquals("ma_habous", CountryMethodDefaults.forCountry("EH").fallback)
        assertTrue(CountryMethodDefaults.forCountry("GR").inferred)
        val unknown = CountryMethodDefaults.forCountry(null)
        assertEquals("mwl", unknown.fallback)
        assertTrue(unknown.confirm)
        assertEquals("mwl", CountryMethodDefaults.forCountry("PK").fallback)
    }

    @Test
    fun choiceKeys() {
        assertEquals("method_choice_FR", CountryMethodDefaults.choiceKey("FR"))
        assertEquals("method_choice_MA", CountryMethodDefaults.choiceKey("EH"))
        assertEquals("method_choice_ES-CE", CountryMethodDefaults.choiceKey("ES", admin1 = "CE"))
        assertEquals("method_choice_ES-ML", CountryMethodDefaults.choiceKey("ES", admin1 = "ML"))
        assertEquals("method_choice_ES", CountryMethodDefaults.choiceKey("ES", admin1 = "MD"))
        assertEquals("method_choice_ZZ", CountryMethodDefaults.choiceKey(null))
        assertEquals("method_choice_RE", CountryMethodDefaults.choiceKey("RE"))
    }

    @Test
    fun regions() {
        assertEquals(Region.TUNISIA, Regions.of("TN"))
        assertEquals(Region.ARAB_LEAGUE, Regions.of("EH"))
        assertEquals(Region.EUROPE, Regions.of("TR"))
        assertEquals(Region.OVERSEAS_FRANCE, Regions.of("WF"))
        assertEquals(Region.OTHER, Regions.of("IR"))
        assertEquals(Region.UNKNOWN, Regions.of(null))
        assertEquals(22, Regions.ARAB_LEAGUE.size)
        assertTrue(Regions.isOpen(Region.OTHER, arabLeagueOpen = true, europeOpen = true))
        assertTrue(!Regions.isOpen(Region.OTHER, arabLeagueOpen = true, europeOpen = false))
    }

    @Test
    fun choiceKeyAndResolution() {
        val plain = PrayerMethodChoice("mwl")
        val answered = plain.copy(chooserAnswered = true, methodPicked = true)
        assertEquals(plain.key, answered.key, "the chooser's state does not change the times")
        assertNotEquals(plain.key, plain.copy(asrShadow = 2).key)
        assertNotEquals(plain.key, plain.withAdjustment(TimeEvent.ISHA, 5).key)
        assertNotEquals(plain.key, plain.copy(zonePick = ZonePick.Exact).key)
        assertEquals(plain.key, plain.withAdjustment(TimeEvent.ISHA, 5).withAdjustment(TimeEvent.ISHA, 0).key)
        val hanafi = plain.copy(asrShadow = 2).resolve()
        assertNotNull(hanafi)
        assertEquals(2, hanafi.asrShadow)
        assertFailsWith<IllegalArgumentException> { plain.withAdjustment(TimeEvent.FAJR, 31) }
        assertFailsWith<IllegalArgumentException> { PrayerMethodChoice(PrayerMethod.CUSTOM_ID) }
        // A stored choice survives JSON.
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val stored = PrayerMethodChoice("rhone_ctir", adjustments = mapOf(TimeEvent.MAGHRIB to 3), zonePick = ZonePick.Zone("69"), methodPicked = true)
        assertEquals(stored, json.decodeFromString(PrayerMethodChoice.serializer(), json.encodeToString(PrayerMethodChoice.serializer(), stored)))
    }

    @Test
    fun customRanges() {
        val ok = PrayerMethod(
            id = PrayerMethod.CUSTOM_ID, fajr = FajrRule.Angle(13.5), isha = IshaRule.MinutesAfter(87),
            offsets = mapOf(TimeEvent.DHUHR to 5, TimeEvent.MAGHRIB to 3),
            highLatitude = com.tunisianprayertimes.engine.HighLatitudeRule.AngleBased, evidence = Evidence(EvidenceKind.APPROXIMATE, "custom"),
        )
        assertEquals(emptyList(), com.tunisianprayertimes.engine.CustomMethodRanges.problems(ok, mainlandFrance = true))
        assertTrue(com.tunisianprayertimes.engine.CustomMethodRanges.problems(ok.copy(fajr = FajrRule.Angle(19.0)), mainlandFrance = true).isNotEmpty())
        assertEquals(emptyList(), com.tunisianprayertimes.engine.CustomMethodRanges.problems(ok.copy(fajr = FajrRule.Angle(19.0)), mainlandFrance = false))
        assertTrue(com.tunisianprayertimes.engine.CustomMethodRanges.problems(ok.copy(fajr = FajrRule.Angle(13.3)), false).isNotEmpty())
        assertTrue(com.tunisianprayertimes.engine.CustomMethodRanges.problems(ok.copy(offsets = mapOf(TimeEvent.DHUHR to 1, TimeEvent.MAGHRIB to 3)), false).isNotEmpty())
        assertTrue(com.tunisianprayertimes.engine.CustomMethodRanges.problems(ok.copy(offsets = mapOf(TimeEvent.DHUHR to 5, TimeEvent.MAGHRIB to 11)), false).isNotEmpty())
    }
}
