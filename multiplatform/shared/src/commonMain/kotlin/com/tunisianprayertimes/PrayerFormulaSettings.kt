package com.tunisianprayertimes

/**
 * The values [InmPrayerFormula] computes with. [OFFICIAL] reproduces INM's published times; a mosque
 * may change them (the TV's «تخصيص الحساب»), within the ranges below:
 * - [fajrAngle] / [ishaAngle]: the sun's depth below the horizon at Fajr / Isha, in degrees;
 * - [asrShadow]: Asr when a stick's shadow is its noon shadow plus once (1) or twice (2, the Hanafi rule) its length;
 * - [dhuhrMinutes]: minutes after solar noon; [maghribMinutes]: minutes after sunset;
 * - [elevation]: the delegation's elevation lowers the horizon (false: a dip of 0, sunrise included);
 * - [adjustments]: whole minutes added to a prayer's time, only for [ADJUSTABLE] prayers and never 0
 *   (a prayer without an adjustment is left out, so equal settings are always equal; see [withAdjustment]).
 */
data class PrayerFormulaSettings(
    val fajrAngle: Double = 18.0,
    val ishaAngle: Double = 18.0,
    val asrShadow: Int = 1,
    val dhuhrMinutes: Int = 7,
    val maghribMinutes: Int = 2,
    val elevation: Boolean = true,
    val adjustments: Map<Prayer, Int> = emptyMap(),
) {
    init {
        require(adjustments.keys.all { it in ADJUSTABLE }) { "only Fajr, Dhuhr, Asr, Maghrib and Isha are adjusted: $adjustments" }
        require(0 !in adjustments.values) { "a prayer without an adjustment is left out, not 0: $adjustments" }
    }

    /** True when these are INM's values: the times are the official ones. */
    val isOfficial: Boolean get() = this == OFFICIAL

    /** True when every value is one a settings file or the dashboard accepts. */
    val isInRange: Boolean
        get() = angleAccepted(fajrAngle) && angleAccepted(ishaAngle) && asrShadow in ASR_SHADOWS &&
            dhuhrMinutes in DHUHR_MINUTES && maghribMinutes in MAGHRIB_MINUTES &&
            adjustments.values.all { it in ADJUSTMENT_MINUTES }

    /** The minutes added to [prayer]'s time (0 when none). */
    fun adjustment(prayer: Prayer): Int = adjustments[prayer] ?: 0

    /** These settings with [prayer] moved by [minutes] (0 removes its adjustment). */
    fun withAdjustment(prayer: Prayer, minutes: Int): PrayerFormulaSettings =
        copy(adjustments = if (minutes == 0) adjustments - prayer else adjustments + (prayer to minutes))

    companion object {
        val ANGLES = 15.0..20.0
        const val ANGLE_STEP = 0.5
        val ASR_SHADOWS = 1..2
        val DHUHR_MINUTES = 0..15
        val MAGHRIB_MINUTES = 0..10
        val ADJUSTMENT_MINUTES = -15..15
        /** The prayers whose time may be adjusted; the sunrise (and the Eid prayers after it) and Jumu'a (Dhuhr's) follow. */
        val ADJUSTABLE = listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)

        /** INM's values: the official times. Declared after the lists its checks read. */
        val OFFICIAL = PrayerFormulaSettings()

        /** An angle within [ANGLES] on the [ANGLE_STEP] grid (17.5, not 17.3). */
        fun angleAccepted(angle: Double): Boolean = angle in ANGLES && (angle / ANGLE_STEP).let { it == kotlin.math.floor(it) }
    }
}
