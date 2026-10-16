package com.tunisianprayertimes.engine

import kotlin.math.abs

/**
 * Diyanet's takdir (plan 7.2, 7.4), the summer bound of `diyanet_eu` and `diyanet_tr`, re-derived from
 * IGGÖ's 2026 city tables (scripts/prayer_formula/fits/diyanet_takdir.py; the tables stay local, D13).
 *
 * - From 44.5° to 46°: duration caps, Fajr no earlier than sunrise − 134 → 127.5 min and Isha no later
 *   than sunset + 118 → 112.5 min, linear in latitude (7.2).
 * - From 46°: Fajr at the effective angle min(18°, k_F·D) and Isha at min(the Isha angle, k_I·D), D being
 *   the sun's depression at the night's lowest point; Fajr also no earlier than sunrise − 127.5 min, which
 *   binds first as the season starts (IGGÖ's Vienna, 1 May 2026).
 *
 * k falls with latitude over IGGÖ's cities (46.6–48.3°N; per city the median over its takdir days, then a
 * line): within IGGÖ's range the line, outside it the value at its nearer end. Fitted on IGGÖ only, so
 * north of 48.3°N it is provisional until Diyanet's own northern tables are fitted (M3). One k per city
 * leaves a few minutes of seasonal drift (k grows from May to August), inside 9.2's ±10 on estimated times.
 */
internal object DiyanetTakdir {
    const val START_LATITUDE = 44.5
    const val K_LATITUDE = 46.0
    private const val FIT_SOUTH = 46.62
    private const val FIT_NORTH = 48.27

    /** The earliest Fajr before sunrise, in minutes, at latitude [phi] (from 44.5°). */
    fun fajrCapMinutes(phi: Double): Double = when {
        phi < START_LATITUDE -> Double.POSITIVE_INFINITY
        phi >= K_LATITUDE -> 127.5
        else -> 134.0 + (127.5 - 134.0) * (phi - START_LATITUDE) / (K_LATITUDE - START_LATITUDE)
    }

    /** The latest Isha after sunset, in minutes, from 44.5° to 46° (none beyond, where k·D bounds it). */
    fun ishaCapMinutes(phi: Double): Double = when {
        phi < START_LATITUDE || phi >= K_LATITUDE -> Double.POSITIVE_INFINITY
        else -> 118.0 + (112.5 - 118.0) * (phi - START_LATITUDE) / (K_LATITUDE - START_LATITUDE)
    }

    fun kFajr(phi: Double): Double = 2.41002 - 0.03603 * abs(phi).coerceIn(FIT_SOUTH, FIT_NORTH)

    fun kIsha(phi: Double): Double = 2.15968 - 0.03192 * abs(phi).coerceIn(FIT_SOUTH, FIT_NORTH)
}
