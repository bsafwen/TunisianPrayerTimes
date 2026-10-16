package com.tunisianprayertimes.engine

import com.tunisianprayertimes.engine.EngineTestSupport.place
import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.PrayerMethods
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The engine's suns and events against two public references (plan 9.1, M1), fetched once by
 * scripts/prayer_formula/astronomy_fixtures.py: JPL Horizons' apparent sun (DE441) at TT epochs every
 * 37 days of 2020–2100, and the US Naval Observatory's sunrise, transit, sunset and civil twilight
 * for 14 places on 6 dates.
 */
class AstronomyFixturesTest {
    private fun rows(path: String) = checkNotNull(javaClass.getResource(path)) { "missing $path" }.readText()
        .lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }.toList()

    /** The UT Julian day whose TT is [jde], with the engine's Delta T. */
    private fun utOf(jde: Double): Double {
        var ut = jde
        repeat(3) { ut = jde - Vsop87Sun.deltaTSeconds(2000.0 + (ut - 2451545.0) / 365.25) / 86400.0 }
        return ut
    }

    private fun wrap(minutes: Double) = minutes - 1440.0 * floor((minutes + 720.0) / 1440.0)

    @Test
    fun sunsAgainstHorizons() {
        var vsopDecl = 0.0
        var vsopEot = 0.0
        var meeusDecl = 0.0
        var meeusEot = 0.0
        var meeusEot2030 = 0.0
        val rows = rows("/engine-reference/horizons_sun.tsv")
        for (row in rows) {
            val jde = row[0].toDouble()
            val ra = row[1].toDouble()
            val decl = row[2].toDouble()
            val ut = utOf(jde)
            val apparent = Vsop87Sun.apparent(ut)
            val utHours = (ut - 0.5 - floor(ut - 0.5)) * 24.0
            // Horizons' equation of time, with the engine's sidereal time: E differs by 4 (α − α_H) only.
            val eotHorizons = wrap(4.0 * (apparent.siderealTimeDeg - ra) + 720.0 - 60.0 * utHours)
            val vsop = SolarEngine.sun(SunModel.VSOP87, ut)
            val meeus = SolarEngine.sun(SunModel.MEEUS, ut)
            vsopDecl = maxOf(vsopDecl, abs(vsop.declinationDeg - decl) * 3600)
            vsopEot = maxOf(vsopEot, abs(vsop.equationOfTimeMin - eotHorizons) * 60)
            meeusDecl = maxOf(meeusDecl, abs(meeus.declinationDeg - decl) * 3600)
            meeusEot = maxOf(meeusEot, abs(meeus.equationOfTimeMin - eotHorizons) * 60)
            if (jde < 2462502.5) meeusEot2030 = maxOf(meeusEot2030, abs(meeus.equationOfTimeMin - eotHorizons) * 60)
            assertTrue(abs(vsop.distanceAu - row[3].toDouble()) < 1e-6, "r at $jde")
        }
        println(
            "Horizons, ${rows.size} epochs: VSOP87 δ ${"%.3f".format(vsopDecl)}″, E ${"%.4f".format(vsopEot)} s; " +
                "MEEUS δ ${"%.3f".format(meeusDecl)}″, E ${"%.4f".format(meeusEot)} s (${"%.4f".format(meeusEot2030)} s to 2030)",
        )
        assertTrue(rows.size == 800)
        // Recorded 2026-10-15: VSOP87 0.146″ and 0.026 s; INM's MEEUS sun 10.5″ and 3.85 s, its 1900-epoch
        // formulae drifting towards 2100 (Delta T, which it ignores, accounts for under 3″).
        assertTrue(vsopDecl < 0.5 && vsopEot < 0.1, "VSOP87: δ $vsopDecl″, E $vsopEot s")
        assertTrue(meeusDecl < 12.0 && meeusEot < 4.5, "MEEUS: δ $meeusDecl″, E $meeusEot s")
    }

    @Test
    fun eventsAgainstUsno() {
        val civil = PrayerMethods.MWL.copy(id = "civil", fajr = FajrRule.Angle(6.0), isha = IshaRule.Angle(6.0))
        var compared = 0
        var exact = 0
        var worst = 0.0
        for (row in rows("/engine-reference/usno_sun.tsv")) {
            val p = place(row[1].toDouble(), row[2].toDouble(), "UTC")
            val date = LocalDate.parse(row[3])
            // USNO lists the events of a UT date; near midsummer in the north a morning twilight on that
            // date belongs to the next solar day, so each event is matched to the nearest of three days.
            val days = (-1L..1L).map { PrayerEngine.explain(p, date.plusDays(it), civil) }
            val events = listOf(TimeEvent.FAJR, TimeEvent.SUNRISE, TimeEvent.DHUHR, TimeEvent.MAGHRIB, TimeEvent.ISHA)
            for ((usno, event) in row.subList(4, 9).zip(events)) {
                if (usno == "-") continue
                val published = date.atTime(LocalTime.parse(usno)).toEpochSecond(ZoneOffset.UTC).toDouble()
                val seconds = days.mapNotNull { it.events[event.ordinal].raw }.map { it.epochSecond + it.nano / 1e9 }
                    .minByOrNull { abs(it - published) } ?: continue
                val difference = seconds - published
                worst = maxOf(worst, abs(difference))
                if (60 * floor((seconds + 30) / 60) == published) exact++
                compared++
                // USNO's minute is the nearest one: an instant within 30 s of it, or a few seconds more for the models.
                assertTrue(difference in -45.0..45.0, "${row[0]} $date $usno: ${Instant.ofEpochSecond(seconds.toLong())}")
            }
        }
        println("USNO: $compared events, $exact on the same minute, worst ${"%.1f".format(worst)} s")
        assertTrue(compared >= 400 && exact >= compared * 0.95, "$exact of $compared on USNO's minute")
    }
}
