package com.tunisianprayertimes

import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.place.LatLngE5
import com.tunisianprayertimes.place.MappedZone
import com.tunisianprayertimes.place.OfficialZone
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Names, parameters, chips (A6) and the zone line of plan 6.11. */
class MethodPresentationTest {
    private fun place(countryCode: String) =
        PrayerPlace.Coordinates(latE5 = 0, lngE5 = 0, zoneId = "UTC", zoneSource = ZoneSource.CATALOGUE, countryCode = countryCode)

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
        assertEquals(MethodChip.OFFICIAL, MethodPresentation.chip(bosnia, place("BA"), null))
        assertEquals(MethodChip.ASTRONOMICAL, MethodPresentation.chip(bosnia, place("ME"), null))
        assertEquals(MethodChip.OFFICIAL, MethodPresentation.chip(method("diyanet_tr"), place("TR"), null))
        assertEquals(MethodChip.ASTRONOMICAL, MethodPresentation.chip(method("diyanet_tr"), place("DE"), null))
    }

    @Test
    fun anAuthorityTableIsOfficialAtItsZoneInTheZonesOwnCountry() {
        val om = method("om_mara")
        val muscat = OfficialZone("om_mara", "muscat", "مسقط", LatLngE5.of(23.6, 58.5))
        assertEquals(MethodChip.OFFICIAL, MethodPresentation.chip(om, place("OM"), MappedZone(muscat, null)))
        // Exact coordinates, or a place no zone serves (the Kurdistan Region, 8.8): the app's own calculation.
        assertEquals(MethodChip.ASTRONOMICAL, MethodPresentation.chip(om, place("OM"), null))
        val bahrain = OfficialZone("bh_calendar", "bahrain", "البحرين", LatLngE5.of(26.2, 50.6))
        assertEquals(MethodChip.ASTRONOMICAL, MethodPresentation.chip(method("bh_calendar"), place("SA"), MappedZone(bahrain, null)))
        val jerusalem = OfficialZone("jo_awqaf", "33", "القدس", LatLngE5.of(31.78, 35.23), country = "PS")
        assertEquals(MethodChip.OFFICIAL, MethodPresentation.chip(method("jo_awqaf"), place("PS"), MappedZone(jerusalem, null)))
    }

    @Test
    fun ummAlQuraIsOfficialAnywhereInSaudiArabia() {
        val sa = method("sa_umm_al_qura")
        assertEquals(MethodChip.OFFICIAL, MethodPresentation.chip(sa, place("SA"), null))
        assertEquals(MethodChip.ASTRONOMICAL, MethodPresentation.chip(sa, place("AE"), null))
    }

    @Test
    fun unofficialTablesStandInsAndCustomMethodsAreAstronomical() {
        val nord = OfficialZone("re_cir", "nord", "الشمال", LatLngE5.of(-20.9, 55.5))
        assertEquals(MethodChip.ASTRONOMICAL, MethodPresentation.chip(method("re_cir"), place("RE"), MappedZone(nord, null)))
        val shifted = OfficialZone("bosnia_iz", "me:-3", "سراييفو", LatLngE5.of(42.44, 19.26), country = "ME")
        assertEquals(MethodChip.ASTRONOMICAL, MethodPresentation.chip(method("bosnia_iz"), place("ME"), MappedZone(shifted, null)))
        assertEquals(MethodChip.ASTRONOMICAL, MethodPresentation.chip(PrayerMethods.MWL.copy(id = "custom"), place("FR"), null))
        assertEquals(MethodChip.ASTRONOMICAL, MethodPresentation.chip(PrayerMethods.MWL, place("FR"), null))
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
