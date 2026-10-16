package com.tunisianprayertimes.engine

import com.tunisianprayertimes.method.LocalTimeIsoSerializer
import java.time.LocalTime
import java.time.MonthDay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The high-latitude rule of step 6 (plan 6.2, 7.4). Every preset and profile names one (D5).
 *
 * *Every night* rules bound the times every night and can move real ones; *absent nights* rules act
 * only on nights without the method's angle, entered and left by a ramp of 5 minutes a day from the
 * last and the next real times (plan 7.4), so none of them jumps.
 */
@Serializable
sealed interface HighLatitudeRule {
    /** D5's default, every night: Isha no later than sunset + α/60 of the night, Fajr no earlier than sunrise − α/60. */
    @Serializable
    @SerialName("angle_based")
    data object AngleBased : HighLatitudeRule

    /** `mdf_12`, `luxembourg_shoura`: α/60 of the night on nights without the angle only, ramped. */
    @Serializable
    @SerialName("angle_based_absent_nights")
    data object AngleBasedAbsentNights : HighLatitudeRule

    /** MWL and ICOP 2009: R of the night, R the place's mean Isha share over its stable nights that year; ramped. */
    @Serializable
    @SerialName("local_relative_estimation")
    data object LocalRelativeEstimation : HighLatitudeRule

    /** A seventh of the night on absent nights, ramped. */
    @Serializable
    @SerialName("one_seventh")
    data object OneSeventh : HighLatitudeRule

    /** Half the night on absent nights: ramped, or not for `apbif` (7.5), whose tables jump to solar midnight. */
    @Serializable
    @SerialName("middle_of_night")
    data class MiddleOfNight(val ramped: Boolean = true) : HighLatitudeRule

    /** The nearest day with the sign, earlier first: its share of the night, or its clock time (Bradford); ramped. */
    @Serializable
    @SerialName("nearest_day")
    data class NearestDay(val lastClockTime: Boolean = false) : HighLatitudeRule

    /** The Netherlands' and Belgium's clock caps, every night: Isha no later and Fajr no earlier than the clock times. */
    @Serializable
    @SerialName("clock_caps")
    data class ClockCaps(
        @Serializable(with = LocalTimeIsoSerializer::class) val ishaLatest: LocalTime,
        @Serializable(with = LocalTimeIsoSerializer::class) val fajrEarliest: LocalTime,
    ) : HighLatitudeRule

    /** The Moonsighting Committee's seasonal curve, every night (`mcw`, `london_unified`; Fajr only in `gmp`). */
    @Serializable
    @SerialName("moonsighting_seasonal")
    data class MoonsightingSeasonal(val fajrOnly: Boolean = false) : HighLatitudeRule

    /** Diyanet's takdir from 44.375°N ([DiyanetTakdir]): a third of the sharia night, with its 5-hour day and night. */
    @Serializable
    @SerialName("diyanet_takdir")
    data object DiyanetTakdir : HighLatitudeRule

    /** DUM RF (`dum_rf`): Isha no later than sunset + r of the night (r by DUM RF's 2026 ramp), Fajr no earlier than sunrise − 0.30. */
    @Serializable
    @SerialName("dum_rf")
    data object DumRf : HighLatitudeRule

    /** DUM RT (`ru_dum_rt`): on nights when the sun stays above −18°, Fajr = sunrise − 2 h and Isha = sunset + 1 h 30. */
    @Serializable
    @SerialName("dum_rt")
    data object DumRt : HighLatitudeRule

    /**
     * The summer forms of `hr_miz_zagreb` and `si_islamska_skupnost` (7.4): from [from] to [to], both
     * included, Fajr no earlier than sunrise − [fajrBeforeSunrise] and, when set, Isha no later than Maghrib
     * + [ishaAfterMaghrib]; never before the angle time for Fajr. Outside those dates D5's angle-based rule
     * bounds them, so the method keeps six times wherever it is chosen.
     */
    @Serializable
    @SerialName("summer_form")
    data class SummerForm(
        val fromMonth: Int,
        val fromDay: Int,
        val toMonth: Int,
        val toDay: Int,
        val fajrBeforeSunrise: Int = 105,
        val ishaAfterMaghrib: Int? = null,
    ) : HighLatitudeRule {
        fun covers(day: MonthDay): Boolean {
            val from = MonthDay.of(fromMonth, fromDay)
            val to = MonthDay.of(toMonth, toDay)
            return if (from <= to) day in from..to else day >= from || day <= to
        }
    }

    /**
     * `irn` (7.4). South of 63.56°N, its summer rule: Isha leaves 15° once its time passes 20:00 UTC and
     * moves linearly in UTC to Maghrib + 40 at the June solstice, never earlier, and back; Fajr keeps 16°
     * until the 16° time is 2½ h before sunrise, then that day's clock time, never later than sunrise − 60,
     * and from 21 June the clock time of the first 16° Fajr after the season. From 63.56°N, Fajr and Isha are
     * capped at solar midnight ∓ 3 h, and the Mecca cycle of [PolarFallback.IRN_MECCA_CYCLE] takes the polar seasons.
     */
    @Serializable
    @SerialName("irn")
    data object Irn : HighLatitudeRule
}

/**
 * Steps 2 and 3 (plan 6.2, D6, 7.4): what replaces a missing or too short day or night.
 */
enum class PolarFallback {
    /** The 5-hour minimum day and night, at places whose year has a day without a sunrise, a sunset or an Asr. */
    MIN_DAY_NIGHT_5H,

    /**
     * Diyanet's clamp on its published sunrise and Maghrib, centred on its Dhuhr (noon + 5), wherever the day or night
     * is shorter than 5 h, with 21 June's bounds where that night is held; Asr = Dhuhr without a shadow.
     */
    DIYANET_CLAMP,

    /** `irn`: Mecca's day placed on the local solar noon in the polar seasons, from 63.56°N. */
    IRN_MECCA_CYCLE,
}

/**
 * A rule that is not built would have changed a time. Kept for future rules: in this build every rule
 * of plan 7.4 is built, and [throwWhenActing] stays a switch for any rule added as a stub.
 */
object EngineStubs {
    /** Set once at start-up, before any time is computed (debug builds and tests: true). */
    var throwWhenActing: Boolean = true

    internal fun acted(what: String) {
        if (throwWhenActing) throw StubRuleActed(what)
    }
}

/** A rule that is not built yet would have changed a time. */
class StubRuleActed(what: String) : IllegalStateException("not built yet: $what")
