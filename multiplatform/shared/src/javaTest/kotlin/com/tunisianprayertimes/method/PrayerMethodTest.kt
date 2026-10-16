package com.tunisianprayertimes.method

import com.tunisianprayertimes.engine.EngineReferenceCases
import com.tunisianprayertimes.engine.TimeEvent
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** The method type of plan 6.3: its JSON, its revisions (E9) and the tier-A gate of 9.5. */
class PrayerMethodTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun methodsRoundTripThroughJson() {
        val methods = PrayerMethods.REFERENCE + listOf(
            EngineReferenceCases.JORDAN_LIKE, EngineReferenceCases.YEMEN_LIKE, EngineReferenceCases.QATAR_LIKE,
            EngineReferenceCases.COMOROS_LIKE, EngineReferenceCases.REUNION_LIKE, EngineReferenceCases.KOSOVO_LIKE,
            EngineReferenceCases.BOSNIA_LIKE, EngineReferenceCases.MOROCCO_LIKE, EngineReferenceCases.CUSTOM_EARLY,
            EngineReferenceCases.MWL_CAPPED,
        )
        for (method in methods) {
            val text = json.encodeToString(PrayerMethod.serializer(), method)
            assertEquals(method, json.decodeFromString(PrayerMethod.serializer(), text), method.id)
        }
        // A short form with only what differs from the defaults also reads.
        val short = """{"id":"x","fajr":{"type":"angle","degrees":18.0},"isha":{"type":"after","minutes":90,"ramadan":120},
            "highLatitude":{"type":"angle_based"},"evidence":{"kind":"FROM_TABLES","source":"s"}}"""
        val parsed = Json.decodeFromString(PrayerMethod.serializer(), short)
        assertEquals(IshaRule.MinutesAfter(90, IshaFrom.MAGHRIB, 120), parsed.isha)
        assertEquals(Rounding.ALL_NEAREST, parsed.rounding)
    }

    @Test
    fun revisionsApplyInDateOrder() {
        val method = PrayerMethods.MWL.copy(
            revisions = listOf(
                MethodRevision(LocalDate.of(2025, 3, 30), MethodChange(offsets = mapOf(TimeEvent.MAGHRIB to 2))),
                MethodRevision(LocalDate.of(2026, 1, 2), MethodChange(jumua = JumuaRule.Fixed(java.time.LocalTime.of(12, 45)))),
            ),
        )
        assertEquals(emptyMap(), method.revisedFor(LocalDate.of(2025, 3, 29)).offsets)
        assertEquals(mapOf(TimeEvent.MAGHRIB to 2), method.revisedFor(LocalDate.of(2025, 3, 30)).offsets)
        assertEquals(JumuaRule.AT_DHUHR, method.revisedFor(LocalDate.of(2026, 1, 1)).jumua)
        assertEquals(JumuaRule.Fixed(java.time.LocalTime.of(12, 45)), method.revisedFor(LocalDate.of(2026, 1, 2)).jumua)
        assertFailsWith<IllegalArgumentException> { method.copy(revisions = method.revisions.reversed()) }
    }

    @Test
    fun terrainClasses() {
        val rule = TerrainRule()
        assertEquals(TerrainClass.LOW, rule.classOf(650.0, null))
        assertEquals(TerrainClass.HIGH, rule.classOf(1100.0, TerrainClass.LOW))
        assertEquals(TerrainClass.LOW, rule.classOf(663.0, null))
        assertEquals(TerrainClass.HIGH, rule.classOf(1095.0, null))
        assertEquals(TerrainClass.HIGH, rule.classOf(700.0, TerrainClass.HIGH))
        assertEquals(10, rule.minutes(TerrainClass.HIGH))
    }

    @Test
    fun tierAGate() {
        val sample = FitSample("test", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), 365, 1)
        val passing = FitStats(
            fittedOn = listOf(sample),
            heldOut = listOf(sample),
            exact = TimeEvent.entries.associateWith { 0.98 },
            within1 = TimeEvent.entries.associateWith { 1.0 },
            daysPerMonth = TimeEvent.entries.associateWith { List(12) { 30 } },
            heldOutRamadanDays = 0,
            quirkDays = 0,
            maxDiffMin = 1,
        )
        val mwl = PrayerMethods.MWL
        assertTrue(passing.passesTierA(mwl))
        assertFalse(passing.copy(heldOut = emptyList()).passesTierA(mwl))
        assertFalse(passing.copy(exact = passing.exact + (TimeEvent.ASR to 0.969)).passesTierA(mwl))
        assertFalse(passing.copy(within1 = passing.within1 + (TimeEvent.ISHA to 0.999)).passesTierA(mwl))
        assertFalse(passing.copy(daysPerMonth = passing.daysPerMonth + (TimeEvent.FAJR to List(12) { if (it == 6) 6 else 30 })).passesTierA(mwl))
        // A method with Ramadan rules needs a whole held-out Ramadan; Saudi Arabia needs all 13 regions too.
        assertFalse(passing.passesTierA(PrayerMethods.UMM_AL_QURA))
        assertTrue(passing.copy(heldOutRamadanDays = 29).passesTierA(PrayerMethods.UMM_AL_QURA))
        val saudi = PrayerMethods.UMM_AL_QURA.copy(id = "sa_umm_al_qura")
        assertFalse(passing.copy(heldOutRamadanDays = 30).passesTierA(saudi))
        assertTrue(passing.copy(heldOutRamadanDays = 30, regions = (1..13).map { "r$it" }.toSet()).passesTierA(saudi))
    }

    @Test
    fun customMethodIsKnownByItsId() {
        assertTrue(EngineReferenceCases.CUSTOM_GMP.isCustom)
        assertFalse(PrayerMethods.MWL.isCustom)
    }
}
