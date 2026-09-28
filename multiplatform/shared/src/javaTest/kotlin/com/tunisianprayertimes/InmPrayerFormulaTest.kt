package com.tunisianprayertimes

import java.io.File
import kotlin.math.acos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The formula must reproduce every minute INM (meteo.tn) publishes. */
class InmPrayerFormulaTest {
    private val locations = InmLocation.parseAll(resource("/delegation_params.json"))

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResource(path)) { "missing test resource $path" }.readText()

    private fun hhmm(minutes: Pair<Int, Int>) = "%02d:%02d".format(minutes.first, minutes.second)

    private fun DayPrayerTimes.columns() = listOf(
        hhmm(fajr.hour to fajr.minute),
        hhmm(shurukHour to shurukMinute),
        hhmm(dhuhr.hour to dhuhr.minute),
        hhmm(asr.hour to asr.minute),
        hhmm(maghrib.hour to maghrib.minute),
        hhmm(isha.hour to isha.minute),
    )

    @Test
    fun julianDayIsTakenAtZeroHoursUt() {
        assertEquals(2461041.5, InmPrayerFormula.julianDay(2026, 1, 1))
        assertEquals(2460369.5, InmPrayerFormula.julianDay(2024, 2, 29))
    }

    @Test
    fun dipRoundsTheEarthRadiusRatioToSinglePrecision() {
        val dip = InmPrayerFormula.dipFromElevation(156.0)
        assertEquals(0.4005626901348312, dip, 1e-13)
        val doublePrecision = acos(6378137.0 / (6378137.0 + 156.0)) * 180 / Math.PI
        assertNotEquals(doublePrecision, dip)
    }

    @Test
    fun zeribaSunriseQuirkOnlyAppliesToIts2026Table() {
        val zeriba = checkNotNull(locations[409])
        assertEquals(mapOf(2026 to 15.6), zeriba.sunriseElevationOverrides)
        val quirk = InmPrayerFormula.dayPrayerTimes(zeriba, 2026, 1, 1)
        val regular = InmPrayerFormula.dayPrayerTimes(zeriba.copy(sunriseElevationOverrides = emptyMap()), 2026, 1, 1)
        assertEquals("07:30", hhmm(quirk.shurukHour to quirk.shurukMinute))
        assertEquals("07:28", hhmm(regular.shurukHour to regular.shurukMinute))
        assertEquals(quirk.copy(shurukHour = regular.shurukHour, shurukMinute = regular.shurukMinute), regular)
    }

    @Test
    fun reproducesEveryDelegationDayOfTheMeteoTn2026Tables() {
        val csvRoot = File(checkNotNull(System.getProperty("tunisianprayertimes.docsCsv")))
        val mismatches = mutableListOf<String>()
        var days = 0
        for ((id, location) in locations) {
            for (month in 1..12) {
                val file = File(csvRoot, "$id/2026/%02d.csv".format(month))
                for (line in file.readLines().drop(1).filter { it.isNotBlank() }) {
                    val cells = line.split(",")
                    val day = cells[0].toInt()
                    val computed = InmPrayerFormula.dayPrayerTimes(location, 2026, month, day).columns()
                    days++
                    if (computed != cells.drop(1)) mismatches += "$id 2026-$month-$day: INM ${cells.drop(1)} vs $computed"
                }
            }
        }
        assertEquals(258 * 365, days)
        assertTrue(mismatches.isEmpty(), "${mismatches.size} mismatching days, e.g. ${mismatches.take(5)}")
    }

    @Test
    fun reproducesMeteoTnSamplesFrom2020To2025() {
        val mismatches = mutableListOf<String>()
        var checked = 0
        resource("/inm-prayer-times/meteo-2020-2025-sample.csv").lines()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("Delegation") }
            .forEach { line ->
                val cells = line.split(",")
                val (year, month, day) = cells[1].split("-").map(String::toInt)
                val computed = InmPrayerFormula.dayPrayerTimes(checkNotNull(locations[cells[0].toInt()]), year, month, day).columns()
                cells.drop(2).zip(computed).forEach { (official, ours) ->
                    if (official.isNotEmpty()) {
                        checked++
                        if (official != ours) mismatches += "${cells[0]} ${cells[1]}: INM $official vs $ours"
                    }
                }
            }
        assertTrue(checked > 15_000, "only $checked times checked")
        assertTrue(mismatches.isEmpty(), "${mismatches.size} mismatches, e.g. ${mismatches.take(5)}")
    }
}
