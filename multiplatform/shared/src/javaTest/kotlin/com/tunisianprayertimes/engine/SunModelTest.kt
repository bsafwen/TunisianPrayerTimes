package com.tunisianprayertimes.engine

import com.tunisianprayertimes.InmPrayerFormula
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** E14's four suns (plan 6.2, D17). */
class SunModelTest {
    /**
     * The Arab prototype's VSOP87 (scripts/prayer_formula/arab_prototype/engine.py, full series, no
     * interpolation table) and Libya's textbook sun (`sun_b` of the Libya fit), recorded on 2026-10-15:
     * JD, prototype δ, prototype E, prototype r, textbook δ, textbook E. The prototype's Delta T stays on
     * the 2005–2050 polynomial after 2050, so its last rows differ from the engine's by up to 52 s of ΔT.
     */
    private val recorded = listOf(
        doubleArrayOf(2461041.5, -23.017245931, -3.330264039, 0.983326665506, -23.015077152, -3.410552232),
        doubleArrayOf(2461212.75, 23.437893486, -1.762936821, 1.016187875927, 23.439932075, -1.582687554),
        doubleArrayOf(2461395.25, -23.432533238, 2.306287358, 0.983813854213, -23.435514878, 2.089491368),
        doubleArrayOf(2470000.0, 21.888221801, -5.746747416, 1.016551177526, 21.904511430, -5.334865190),
        doubleArrayOf(2488070.5, -22.920374910, -3.646557401, 0.983346354296, -22.923088935, -3.907371573),
        doubleArrayOf(2488253.6, 22.862193635, -4.565963556, 1.016682975507, 22.880620435, -4.068114014),
    )

    @Test
    fun vsop87ReproducesThePrototype() {
        for (row in recorded) {
            val sun = SolarEngine.sun(SunModel.VSOP87, row[0])
            assertEquals(row[1], sun.declinationDeg, 1e-4, "δ at ${row[0]}")
            // After 2050 the prototype's ΔT falls behind the engine's (52 s by 2100), which moves E by up to 0.003 min.
            assertEquals(row[2], sun.equationOfTimeMin, if (row[0] < 2469807.5) 2e-3 else 4e-3, "E at ${row[0]}")
            assertEquals(row[3], sun.distanceAu, 1e-7, "r at ${row[0]}")
        }
    }

    @Test
    fun textbookReproducesLibyasSun() {
        for (row in recorded) {
            val sun = SolarEngine.sun(SunModel.TEXTBOOK, row[0])
            assertEquals(row[4], sun.declinationDeg, 1e-8, "δ at ${row[0]}")
            assertEquals(row[5], sun.equationOfTimeMin, 1e-8, "E at ${row[0]}")
        }
    }

    @Test
    fun meeusIsInmsSun() {
        for (jd in listOf(2461041.5, 2461212.75, 2488070.5)) {
            val (decl, eot) = InmPrayerFormula.sun(jd)
            val sun = SolarEngine.sun(SunModel.MEEUS, jd)
            assertEquals(decl, sun.declinationDeg)
            assertEquals(eot, sun.equationOfTimeMin)
        }
    }

    @Test
    fun accurateSunsAgreeWithinSecondsOverTheSupportedYears() {
        // MEEUS (1900 epoch, no ΔT) against VSOP87: the declination within 0.01° and E within 0.1 min (6 s), 2020–2100.
        var jd = SolarEngine.julianDay(java.time.LocalDate.of(2020, 1, 1))
        val end = SolarEngine.julianDay(java.time.LocalDate.of(2100, 12, 31))
        var worstDecl = 0.0
        var worstEot = 0.0
        while (jd <= end) {
            val m = SolarEngine.sun(SunModel.MEEUS, jd)
            val v = SolarEngine.sun(SunModel.VSOP87, jd)
            worstDecl = maxOf(worstDecl, abs(m.declinationDeg - v.declinationDeg))
            worstEot = maxOf(worstEot, abs(m.equationOfTimeMin - v.equationOfTimeMin))
            jd += 7.3
        }
        assertTrue(worstDecl < 0.01, "declination differs by $worstDecl°")
        assertTrue(worstEot < 0.1, "equation of time differs by $worstEot min")
    }

    @Test
    fun almanacSunIsWithinSecondsOfVsop87() {
        // ALMANAC (Oman's tables) against VSOP87, 2020–2100: 22″ in declination and 3 s in E at worst, bounded as MEEUS is.
        var jd = SolarEngine.julianDay(java.time.LocalDate.of(2020, 1, 1))
        val end = SolarEngine.julianDay(java.time.LocalDate.of(2100, 12, 31))
        var worstDecl = 0.0
        var worstEot = 0.0
        while (jd <= end) {
            val a = SolarEngine.sun(SunModel.ALMANAC, jd)
            val v = SolarEngine.sun(SunModel.VSOP87, jd)
            worstDecl = maxOf(worstDecl, abs(a.declinationDeg - v.declinationDeg))
            worstEot = maxOf(worstEot, abs(a.equationOfTimeMin - v.equationOfTimeMin))
            jd += 7.3
        }
        assertTrue(worstDecl < 0.01, "declination differs by $worstDecl°")
        assertTrue(worstEot < 0.1, "equation of time differs by $worstEot min")
        // The reference's values at 12:00 UT on 2026-02-11, 2026-06-21 and 2026-11-03 (README S1).
        assertEquals(-14.1991, SolarEngine.sun(SunModel.ALMANAC, 2461083.0).equationOfTimeMin, 1e-3)
        assertEquals(23.4354, SolarEngine.sun(SunModel.ALMANAC, 2461213.0).declinationDeg, 1e-3)
        assertEquals(16.4481, SolarEngine.sun(SunModel.ALMANAC, 2461348.0).equationOfTimeMin, 1e-3)
    }

    @Test
    fun deltaTIsContinuousAt2050() {
        assertEquals(Vsop87Sun.deltaTSeconds(2049.999999), Vsop87Sun.deltaTSeconds(2050.0), 1e-3)
        assertEquals(71.599, Vsop87Sun.deltaTSeconds(2020.0), 0.001)
        assertEquals(202.74, Vsop87Sun.deltaTSeconds(2100.0), 0.001)
    }
}
