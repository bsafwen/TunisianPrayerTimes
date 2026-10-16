package com.tunisianprayertimes

import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * INM's unrounded numbers, recorded from the code before the shared prayer engine was added
 * (plan 6.2, milestone M0): [InmPrayerFormula.explain] (and [InmPrayerFormula.minutes], its exact
 * minutes), [InmPrayerFormula.sunAltitudeDeg] and [InmPrayerFormula.sun]. The engine reuses that
 * code without editing it; this test shows it still gives the same numbers, within 1e-6.
 *
 * To re-record (only for a deliberate change to INM's formula), run this class with
 * INM_FINGERPRINT_RECORD set to the fixture's path in test-data.
 */
class InmRegressionFingerprintTest {
    private val locations = InmLocation.parseAll(resource("/delegation_params.json"))

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResource(path)) { "missing test resource $path" }.readText()

    @Test
    fun formulaGivesTheRecordedNumbers() {
        val rows = resource(FIXTURE).lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        val counts = rows.groupingBy { it.substringBefore('\t') }.eachCount()
        // Every kind of row is present in the numbers fingerprintRows() gives today.
        assertEquals(mapOf("explain" to 752, "altitude" to 168, "sun" to 252), counts)
        for (row in rows) {
            val expected = row.split('\t')
            val actual = when (expected[0]) {
                "explain" -> explainRow(expected[1].toInt(), LocalDate.parse(expected[2]), expected[3])
                "altitude" -> altitudeRow(expected[1].toInt(), LocalDate.parse(expected[2]), expected[3].toDouble())
                "sun" -> sunRow(expected[1].toDouble())
                else -> fail("unknown row: $row")
            }.split('\t')
            assertEquals(expected.size, actual.size, row)
            expected.zip(actual).forEachIndexed { i, (e, a) ->
                val ev = e.toDoubleOrNull()
                val av = a.toDoubleOrNull()
                if (ev == null || av == null) assertEquals(e, a, "column $i of $row")
                else assertTrue(abs(ev - av) <= TOLERANCE, "column $i of $row: recorded $e, now $a")
            }
            if (expected[0] == "explain") {
                // minutes() is explain()'s exact minutes: the times INM's tables round.
                val date = LocalDate.parse(expected[2])
                val minutes = InmPrayerFormula.minutes(
                    locations.getValue(expected[1].toInt()), date.year, date.monthValue, date.dayOfMonth,
                    SETTINGS.getValue(expected[3]),
                )
                val recorded = (0 until 6).map { expected[EXPLAIN_HEAD + it * 4 + 2].toDouble() }
                minutes.zip(recorded).forEach { (m, r) -> assertTrue(abs(m - r) <= TOLERANCE, "minutes() of $row: $m") }
            }
        }
    }

    @Test
    fun recordFingerprint() {
        val target = System.getenv("INM_FINGERPRINT_RECORD") ?: return
        File(target).writeText(
            buildString {
                appendLine("# INM fingerprint (plan 6.2, M0): InmPrayerFormula's unrounded numbers, recorded from the code")
                appendLine("# before the shared engine. Rows: explain id date settings | jd decl eot noon dip sunriseDip asrAlt")
                appendLine("# | per event (Fajr..Isha): altitude hourAngle exact shown; altitude id date localMinutes | deg;")
                appendLine("# sun jd | decl eot. Checked by InmRegressionFingerprintTest within 1e-6.")
                fingerprintRows().forEach { appendLine(it) }
            },
        )
    }

    private fun fingerprintRows(): List<String> {
        val ids = locations.keys.sorted()
        val rows = mutableListOf<String>()
        // Every delegation at both solstices of 2026.
        for (id in ids) for (date in listOf("2026-06-21", "2026-12-21")) rows += explainRow(id, LocalDate.parse(date), "official")
        // Tunis every third day of 2026, Zeriba's sunrise quirk year beside a normal one, and Tunis's far years.
        generateSequence(LocalDate.of(2026, 1, 1)) { it.plusDays(3) }.takeWhile { it.year == 2026 }
            .forEach { rows += explainRow(615, it, "official") }
        for (date in listOf("2025-03-20", "2025-09-23", "2026-03-20", "2026-09-23")) rows += explainRow(409, LocalDate.parse(date), "official")
        for (year in listOf(2020, 2050, 2100)) for (md in listOf("01-15", "04-15", "07-15", "10-15")) {
            rows += explainRow(615, LocalDate.parse("$year-$md"), "official")
        }
        // Every non-default setting at the delegations at the edges of INM's parameters.
        val edges = edgeDelegations()
        for (id in edges) for (date in listOf("2026-06-21", "2026-12-21")) for (key in SETTINGS.keys - "official") {
            rows += explainRow(id, LocalDate.parse(date), key)
        }
        for (id in edges) for (date in listOf("2026-03-20", "2026-06-21", "2026-09-23", "2026-12-21")) {
            for (minutes in 0..1440 step 288) rows += altitudeRow(id, LocalDate.parse(date), minutes + 0.5)
        }
        for (year in 2020..2100 step 4) for (month in 1..12) {
            val jd = InmPrayerFormula.julianDay(year, month, 1) + if (month % 2 == 0) 0.37 else 0.0
            rows += sunRow(jd)
        }
        return rows
    }

    /** Tunis, Zeriba, and the delegations farthest north, south, east, west and highest. */
    private fun edgeDelegations(): List<Int> {
        val byLat = locations.entries.sortedBy { it.value.latitude }
        val byLng = locations.entries.sortedBy { it.value.longitude }
        val highest = locations.entries.maxBy { it.value.elevationM }
        return listOf(615, 409, byLat.last().key, byLat.first().key, byLng.last().key, byLng.first().key, highest.key).distinct()
    }

    private fun explainRow(id: Int, date: LocalDate, settingsKey: String): String {
        val e = InmPrayerFormula.explain(locations.getValue(id), date.year, date.monthValue, date.dayOfMonth, SETTINGS.getValue(settingsKey))
        val head = listOf(e.julianDay, e.declinationDeg, e.equationOfTimeMin, e.solarNoonMinutes, e.dipDeg, e.sunriseDipDeg, e.asrAltitudeDeg)
        val events = e.events.flatMap { listOf(n(it.altitudeDeg), it.hourAngleDeg?.let(::n) ?: "-", n(it.exactMinutes), it.shownMinutes.toString()) }
        return (listOf("explain", id.toString(), date.toString(), settingsKey) + head.map(::n) + events).joinToString("\t")
    }

    private fun altitudeRow(id: Int, date: LocalDate, localMinutes: Double): String {
        val deg = InmPrayerFormula.sunAltitudeDeg(locations.getValue(id), date.year, date.monthValue, date.dayOfMonth, localMinutes)
        return listOf("altitude", id.toString(), date.toString(), n(localMinutes), n(deg)).joinToString("\t")
    }

    private fun sunRow(jd: Double): String {
        val (decl, eot) = InmPrayerFormula.sun(jd)
        return listOf("sun", n(jd), n(decl), n(eot)).joinToString("\t")
    }

    private fun n(x: Double) = "%.9f".format(Locale.ROOT, x)

    private companion object {
        const val FIXTURE = "/inm-prayer-times/inm-fingerprint.tsv"
        const val TOLERANCE = 1e-6
        /** Columns before the first event in an explain row: kind, id, date, settings and seven numbers. */
        const val EXPLAIN_HEAD = 11

        val SETTINGS: Map<String, PrayerFormulaSettings> = linkedMapOf(
            "official" to PrayerFormulaSettings.OFFICIAL,
            "fajr15" to PrayerFormulaSettings(fajrAngle = 15.0),
            "isha20" to PrayerFormulaSettings(ishaAngle = 20.0),
            "asr2" to PrayerFormulaSettings(asrShadow = 2),
            "dhuhr0" to PrayerFormulaSettings(dhuhrMinutes = 0),
            "maghrib10" to PrayerFormulaSettings(maghribMinutes = 10),
            "noelevation" to PrayerFormulaSettings(elevation = false),
            "adjusted" to PrayerFormulaSettings(
                adjustments = mapOf(Prayer.FAJR to -15, Prayer.DHUHR to 15, Prayer.ASR to -7, Prayer.MAGHRIB to 3, Prayer.ISHA to 15),
            ),
        )
    }
}
