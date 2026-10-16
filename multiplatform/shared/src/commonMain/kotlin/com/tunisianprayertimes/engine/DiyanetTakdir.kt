package com.tunisianprayertimes.engine

import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.IshaRule
import java.time.LocalDate

/**
 * Diyanet's takdir (README H6; plan 7.2, 7.4): the criteria of the 2021 International Prayer Times Congress as
 * Diyanet's 2027 tables apply them (scripts/prayer_formula/fits/diyanet_takdir, 144 entries from 43 to 78° N and
 * Diyanet's 190 Austrian tables; the tables stay local, D13). One table per site and year, every value a time of day
 * (seconds after 00:00 UTC of its date), so that dates compare: F and I the angle events, R and M the published
 * sunrise and Maghrib (steps 1–3 plus the method's offsets), the sharia night S = F + 24 h − M, the night
 * N = R + 24 h − M, and k = the Fajr angle / the Isha angle.
 *
 * - Case A (F on every date of February to November, to 48.56° N): both sides anchor on 21 June, at M + S/3 for
 *   Isha and R − k·S/3 for Fajr, one third of the sharia night.
 * - Case B: on the dates without F, the share r = (S/3)/N of the last date with it, Isha = M + r·N and
 *   Fajr = R − k·r·N; the sides anchor on the first and the last of those dates.
 * - Away from each anchor, a gradual switch from 20 minutes off: the line A ∓ 20 min · i/n over the n dates whose angle
 *   time is past it, the earlier Isha (later Fajr) of the line and the angle time.
 * - Where 21 June holds its night (H2), nothing earlier than 21 June's Fajr or later than its Isha, and in case B the
 *   Isha never earlier again before 21 June, the Fajr never earlier again after it.
 *
 * North of 44.375° N only: Rodez (44.365° N) has no takdir, Cuneo (44.385° N) has one, and Diyanet's southern tables
 * (45.9 to 54.8° S) keep the angle times all year.
 */
internal class DiyanetTakdir private constructor(private val season: Season, private val year: Int) {
    /** [Fajr, Isha] of each date of the year as times of day, NaN where absent. */
    private val table = HashMap<LocalDate, DoubleArray>()

    init {
        build()
    }

    /** [date]'s takdir Fajr ([fajr] true) or Isha as an instant (epoch seconds), or null where the table has none. */
    fun value(date: LocalDate, fajr: Boolean): Double? {
        val v = table[date]?.get(if (fajr) 0 else 1) ?: return null
        return if (v.isNaN()) null else midnight(date) + v
    }

    private fun midnight(date: LocalDate) = date.toEpochDay() * DAY

    private fun build() {
        val method = season.method
        val k = (method.fajr as FajrRule.Angle).degrees / (method.isha as IshaRule.Angle).degrees
        val rise = method.offset(TimeEvent.SUNRISE) * 60.0
        val set = method.offset(TimeEvent.MAGHRIB) * 60.0
        fun plain(d: LocalDate, j: Int): Double? =
            (if (j == FAJR) season.day(d).fajrAngle else season.day(d).ishaAngle)?.let { it - midnight(d) }
        fun r(d: LocalDate) = checkNotNull(season.ends(d).sunrise) + rise - midnight(d)
        fun m(d: LocalDate) = checkNotNull(season.ends(d).sunset) + set - midnight(d)
        fun sharia(d: LocalDate) = plain(d, FAJR)?.let { it + DAY - m(d) }
        fun night(d: LocalDate) = r(d) + DAY - m(d)

        val first = LocalDate.of(year, 1, 1)
        val last = LocalDate.of(year, 12, 31)
        var d = first
        while (d <= last) {
            table[d] = doubleArrayOf(plain(d, FAJR) ?: Double.NaN, plain(d, ISHA) ?: Double.NaN)
            d = d.plusDays(1)
        }
        val solstice = LocalDate.of(year, 6, 21)
        val absent = ArrayList<LocalDate>()
        d = LocalDate.of(year, 2, 1)
        while (d <= LocalDate.of(year, 11, 30)) {
            if (plain(d, FAJR) == null) absent += d
            d = d.plusDays(1)
        }
        // anchors[j] = (spring date, spring value, autumn date, autumn value) for Fajr (0) and Isha (1).
        val springDate: LocalDate
        val autumnDate: LocalDate
        val anchors: List<Pair<Double, Double>>
        if (absent.isEmpty()) {
            val s = sharia(solstice) ?: return
            springDate = solstice
            autumnDate = solstice
            val fajr = r(solstice) - k * s / 3
            val isha = m(solstice) + s / 3
            anchors = listOf(fajr to fajr, isha to isha)
        } else {
            springDate = absent.first()
            autumnDate = absent.last()
            val lastReal = springDate.minusDays(1)
            val share = (sharia(lastReal) ?: return) / 3 / night(lastReal)
            fun frozen(x: LocalDate, j: Int) = if (j == FAJR) r(x) - k * share * night(x) else m(x) + share * night(x)
            d = springDate
            while (d <= autumnDate) {
                table[d] = doubleArrayOf(frozen(d, FAJR), frozen(d, ISHA))
                d = d.plusDays(1)
            }
            anchors = listOf(frozen(springDate, FAJR) to frozen(autumnDate, FAJR), frozen(springDate, ISHA) to frozen(autumnDate, ISHA))
        }
        // The transitions' lengths: [j][0] the spring side's, [j][1] the autumn side's.
        val lengths = Array(2) { IntArray(2) }
        for (j in 0..1) {
            val sign = if (j == FAJR) 1 else -1
            for (side in 0..1) {
                val a = if (side == 0) springDate else autumnDate
                val value = if (side == 0) anchors[j].first else anchors[j].second
                val step = if (side == 0) -1L else 1L
                fun past(x: LocalDate): Boolean {
                    val p = plain(x, j) ?: return true
                    return if (j == FAJR) p < value + STEP else p > value - STEP
                }
                var n = 0
                while (n < SCAN && past(a.plusDays(step * (n + 1)))) n++
                lengths[j][side] = n
                for (i in 1..n) {
                    val x = a.plusDays(step * i)
                    val row = table[x] ?: continue
                    val line = value + sign * STEP * i / n
                    val p = plain(x, j)
                    row[j] = if (p == null) line else if (j == FAJR) maxOf(line, p) else minOf(line, p)
                }
                table[a]?.set(j, value)
            }
        }
        if (season.holdsItsNight(year)) {
            val cap = checkNotNull(table[solstice]).copyOf()
            for (v in table.values) {
                if (!v[FAJR].isNaN() && !cap[FAJR].isNaN()) v[FAJR] = maxOf(v[FAJR], cap[FAJR])
                if (!v[ISHA].isNaN() && !cap[ISHA].isNaN()) v[ISHA] = minOf(v[ISHA], cap[ISHA])
            }
            if (absent.isNotEmpty()) {
                // In date order: the Isha never earlier again up to 21 June, the Fajr never earlier again after it.
                d = maxOf(springDate.minusDays(lengths[ISHA][0].toLong()), first).plusDays(1)
                while (d <= solstice) {
                    hold(d, ISHA)
                    d = d.plusDays(1)
                }
                val end = minOf(autumnDate.plusDays(lengths[FAJR][1].toLong()), last)
                d = solstice.plusDays(1)
                while (d <= end) {
                    hold(d, FAJR)
                    d = d.plusDays(1)
                }
            }
        }
    }

    /** The time j of [d] no earlier than the previous date's (both times of day), where both are present. */
    private fun hold(d: LocalDate, j: Int) {
        val v = table[d] ?: return
        val previous = table[d.minusDays(1)] ?: return
        if (!v[j].isNaN() && !previous[j].isNaN()) v[j] = maxOf(v[j], previous[j])
    }

    companion object {
        /** No takdir south of it: Rodez (44.365° N) has none, Cuneo (44.385° N) has one. */
        const val START_LATITUDE = 44.375

        /** The switch starts 20 minutes from the anchor. */
        private const val STEP = 20 * 60.0
        private const val SCAN = 200
        private const val DAY = 86_400.0
        private const val FAJR = 0
        private const val ISHA = 1

        /** The takdir of [year] at [season]'s site, built once and kept with the season. */
        fun of(season: Season, year: Int): DiyanetTakdir {
            synchronized(season.takdirTables) { season.takdirTables[year] }?.let { return it }
            return DiyanetTakdir(season, year).also { synchronized(season.takdirTables) { season.takdirTables[year] = it } }
        }
    }
}
