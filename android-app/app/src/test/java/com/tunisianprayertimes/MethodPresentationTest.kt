package com.tunisianprayertimes

import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.FitSample
import com.tunisianprayertimes.method.FitStats
import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.place.LatLngE5
import com.tunisianprayertimes.place.MappedZone
import com.tunisianprayertimes.place.OfficialZone
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneSource
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Names, parameters, chips (A6) and the zone line of plan 6.11. */
class MethodPresentationTest {
    private val sample = FitSample("test", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 365, 1)

    private fun place(countryCode: String) =
        PrayerPlace.Coordinates(latE5 = 0, lngE5 = 0, zoneId = "UTC", zoneSource = ZoneSource.CATALOGUE, countryCode = countryCode)

    private fun fit(exact: Double = 0.99, regions: Int = 13) = FitStats(
        fittedOn = listOf(sample),
        heldOut = listOf(sample),
        exact = TimeEvent.entries.associateWith { exact },
        within1 = TimeEvent.entries.associateWith { 1.0 },
        daysPerMonth = TimeEvent.entries.associateWith { List(12) { 10 } },
        heldOutRamadanDays = 30,
        quirkDays = 0,
        maxDiffMin = 1,
        regions = (1..regions).map { "r$it" }.toSet(),
    )

    private fun method(id: String) = checkNotNull(PrayerMethods.byId(id)) { id }

    @Test
    fun everyMethodHasAnArabicName() {
        val unnamed = PrayerMethods.ALL.map { it.id }.filterNot { it in MethodPresentation.namedIds }
        assertTrue("unnamed: $unnamed", unnamed.isEmpty())
        PrayerMethods.ALL.forEach { assertNotEquals(it.id, MethodPresentation.name(it.id)) }
    }

    @Test
    fun parametersAndSignedMinutesStayLeftToRight() {
        assertEquals("⁦18° · 17°⁩", MethodPresentation.parameters(PrayerMethods.MWL))
        assertEquals("⁦−3⁩", MethodPresentation.signedMinutes(-3))
        assertEquals("⁦+5⁩", MethodPresentation.signedMinutes(5))
        // Shown in the Arabic sheet: minutes keep their order (2026-10-19, ECFR's showed «90 د 90 · د»).
        assertEquals("18° · 17°", shownInArabic(MethodPresentation.parameters(PrayerMethods.MWL)))
        assertEquals("90 د · 90 د", shownInArabic(MethodPresentation.parameters(method("ecfr_90"))))
        assertEquals("18° · 87 د", shownInArabic(MethodPresentation.parameters(method("gmp"))))
    }

    /** [text] as an RTL line displays it, left to right, by the Unicode bidi algorithm; the isolate marks dropped. */
    private fun shownInArabic(text: String): String {
        val bidi = java.text.Bidi(text, java.text.Bidi.DIRECTION_RIGHT_TO_LEFT)
        val levels = ByteArray(bidi.runCount) { bidi.getRunLevel(it).toByte() }
        val runs = Array<Any>(bidi.runCount) {
            val run = text.substring(bidi.getRunStart(it), bidi.getRunLimit(it))
            if (levels[it] % 2 == 1) run.reversed() else run
        }
        java.text.Bidi.reorderVisually(levels, 0, runs, 0, runs.size)
        return runs.joinToString("").filterNot { it in "⁦⁧⁨⁩" }
    }

    @Test
    fun nationalBodiesAreOfficialInTheirOwnCountry() {
        val bosnia = method("bosnia_iz")
        assertEquals(MethodChip.OFFICIAL, MethodPresentation.chip(bosnia, place("BA"), null, null))
        assertNotEquals(MethodChip.OFFICIAL, MethodPresentation.chip(bosnia, place("ME"), null, null))
        assertEquals(MethodChip.OFFICIAL, MethodPresentation.chip(method("diyanet_tr"), place("TR"), null, null))
    }

    @Test
    fun theKurdistanRegionIsApproximate() {
        val iq = method("iq_sunni_endowment")
        assertEquals(MethodChip.APPROXIMATE, MethodPresentation.chip(iq, place("IQ"), null, null, admin1Name = "Arbil"))
        assertEquals(MethodChip.APPROXIMATE, MethodPresentation.chip(iq, place("IQ"), null, null, admin1Name = "As Sulaymaniyah"))
        assertEquals(
            MethodPresentation.chip(iq, place("IQ"), null, null),
            MethodPresentation.chip(iq, place("IQ"), null, null, admin1Name = "Baghdad"),
        )
        assertFalse(MethodPresentation.isKurdistan("Basra"))
    }

    @Test
    fun ummAlQuraMatchesOnlyWithAPassingProfileRecordInSaudiArabia() {
        val sa = method("sa_umm_al_qura")
        assertEquals(MethodChip.MATCHES_TABLE, MethodPresentation.chip(sa, place("SA"), null, fit()))
        assertNotEquals(MethodChip.MATCHES_TABLE, MethodPresentation.chip(sa, place("SA"), null, fit(regions = 12)))
        assertNotEquals(MethodChip.MATCHES_TABLE, MethodPresentation.chip(sa, place("SA"), null, null))
        assertNotEquals(MethodChip.MATCHES_TABLE, MethodPresentation.chip(sa, place("AE"), null, fit()))
    }

    @Test
    fun aZonedProfileNeedsItsZoneAndItsBaseZoneToPass() {
        val om = method("om_mara")
        val muscat = OfficialZone("om_mara", "muscat", "مسقط", LatLngE5.of(23.6, 58.5), fit = fit())
        assertEquals(MethodChip.MATCHES_TABLE, MethodPresentation.chip(om, place("OM"), MappedZone(muscat, null), null))
        val weakBase = muscat.copy(id = "base", fit = fit(exact = 0.9))
        assertNotEquals(MethodChip.MATCHES_TABLE, MethodPresentation.chip(om, place("OM"), MappedZone(muscat, weakBase), null))
        assertNotEquals(MethodChip.MATCHES_TABLE, MethodPresentation.chip(om, place("OM"), null, null))
    }

    @Test
    fun anyCountryWhoseZonePassesMatchesInItsOwnCountry() {
        // Owner, 2026-10-17: the gate decides, not a list of countries (9.5).
        val bh = method("bh_calendar")
        val bahrain = OfficialZone("bh_calendar", "bahrain", "البحرين", LatLngE5.of(26.2, 50.6), fit = fit())
        assertEquals(MethodChip.MATCHES_TABLE, MethodPresentation.chip(bh, place("BH"), MappedZone(bahrain, null), null))
        assertNotEquals(MethodChip.MATCHES_TABLE, MethodPresentation.chip(bh, place("SA"), MappedZone(bahrain, null), null))
        val jerusalem = OfficialZone("jo_awqaf", "33", "القدس", LatLngE5.of(31.78, 35.23), fit = fit(), country = "PS")
        assertEquals(MethodChip.MATCHES_TABLE, MethodPresentation.chip(method("jo_awqaf"), place("PS"), MappedZone(jerusalem, null), null))
        // A table no authority publishes never claims the official one.
        val nord = OfficialZone("re_cir", "nord", "الشمال", LatLngE5.of(-20.9, 55.5), fit = fit())
        assertNotEquals(MethodChip.MATCHES_TABLE, MethodPresentation.chip(method("re_cir"), place("RE"), MappedZone(nord, null), null))
    }

    @Test
    fun theCustomMethodHasItsOwnChip() {
        assertEquals(MethodChip.CUSTOM, MethodPresentation.chip(PrayerMethods.MWL.copy(id = "custom"), place("FR"), null, null))
    }

    @Test
    fun tablesNoAuthorityPublishesDropOfficial() {
        val lyon = OfficialZone("rhone_ctir", "69", "الرون", LatLngE5.of(45.74, 4.84))
        assertEquals("الجدول المعتمد: الرون", MethodPresentation.zoneLine("rhone_ctir", MappedZone(lyon, null), place("FR")))
        val muscat = OfficialZone("om_mara", "muscat", "مسقط", LatLngE5.of(23.6, 58.5))
        assertEquals("الجدول الرسمي المعتمد: مسقط", MethodPresentation.zoneLine("om_mara", MappedZone(muscat, null), place("OM")))
        val podgorica = OfficialZone("bosnia_iz", "podgorica", "بودغوريتسا", LatLngE5.of(42.44, 19.26))
        assertEquals("الجدول المعتمد: بودغوريتسا", MethodPresentation.zoneLine("bosnia_iz", MappedZone(podgorica, null), place("ME")))
    }
}
