package com.tunisianprayertimes.method

import com.tunisianprayertimes.engine.HighLatitudeRule
import com.tunisianprayertimes.engine.Horizons
import com.tunisianprayertimes.engine.PolarFallback
import com.tunisianprayertimes.engine.SunEvaluation
import com.tunisianprayertimes.engine.SunModel
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.place.LatLngE5
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a preset, an authority profile or a custom method computes the six times outside Tunisia
 * (plan 6.3). Separate from [com.tunisianprayertimes.PrayerFormulaSettings], which stays the TV's
 * contract with INM's formula. Every field but [highLatitude] defaults to the European presets' value.
 */
@Serializable
data class PrayerMethod(
    val id: String,
    val fajr: FajrRule,
    val isha: IshaRule,
    /** ICCI's 22:45 display cap; a capped Isha is ESTIMATED. */
    @Serializable(with = LocalTimeIsoSerializer::class)
    val ishaLatest: LocalTime? = null,
    /** Custom only: «العشاء ليس قبل» (6.11); a held Isha is not ESTIMATED. */
    @Serializable(with = LocalTimeIsoSerializer::class)
    val ishaEarliest: LocalTime? = null,
    /** E12: Maghrib at this depression angle instead of sunset. No shipped method uses it. */
    val maghribAngle: Double? = null,
    /** Whole minutes per time, sunrise and Asr included (Diyanet −7 / +4). */
    val offsets: Map<TimeEvent, Int> = emptyMap(),
    /** E7: `bh_calendar`'s 1.1, the whole disc past the meridian. */
    val dhuhrAfterTransitMin: Double = 0.0,
    val asrShadow: Int = 1,
    /** E15: the shadow rule, the signed noon shadow (`dz_marw`, in Algeria) or Maghrib − n. */
    val asr: AsrRule = AsrRule.Shadow,
    /** E16: `re_cir`'s minutes by elevation band. */
    val elevationShifts: List<ElevationShift> = emptyList(),
    /** E18: minutes at 36 equal steps through the year, interpolated by day of the year. */
    val seasonalOffsets: Map<TimeEvent, List<Double>> = emptyMap(),
    /** E18: minutes on calendar dates of every year, both ends included (`irn`: Dhuhr + 5 from 21 March to 20 September). */
    val datedOffsets: List<DatedOffset> = emptyList(),
    /** `bosnia_iz`: the Takvim's Table 1, 12 monthly means north of Sarajevo then 12 south, with printed signs. */
    val takvimMeans: List<Double> = emptyList(),
    /** E13. */
    val asrSun: AsrSun = AsrSun.GEOMETRIC,
    /** E14 (D17). */
    val sunModel: SunModel = SunModel.MEEUS,
    /** E17: `ly_awqaf` 0.99330562 (1 − e²); Fajr and Isha only. */
    val twilightLatitudeScale: Double = 1.0,
    /** No default: every preset and profile names one (D5). */
    val highLatitude: HighLatitudeRule,
    val polar: PolarFallback = PolarFallback.MIN_DAY_NIGHT_5H,
    /** Diyanet: 5. */
    val minDayNightHours: Int? = null,
    /** E1. */
    val rounding: Map<TimeEvent, Rounding> = Rounding.ALL_NEAREST,
    /** E2. */
    val horizon: Horizons = Horizons.STANDARD,
    /** E3: INM only, which never reaches the engine. */
    val dipOnTwilight: Boolean = false,
    /** E4; EVENT_INSTANT where absent. */
    val sunEvaluation: Map<TimeEvent, SunEvaluation> = emptyMap(),
    /** E8: minutes added on Ramadan days. */
    val ramadanOffsets: Map<TimeEvent, Int> = emptyMap(),
    /** E8. */
    val ramadanDays: RamadanDays = RamadanDays.PLACE_CALENDAR,
    /** E9: the method's fields are its rule before the first revision. */
    val revisions: List<MethodRevision> = emptyList(),
    /**
     * A8 (owner, revised 2026-10-17): a perpetual table's year (E5). Each date is computed as its own month and day
     * of that year, in the zone's standard time, so the app shows the table as printed; null computes the actual year.
     */
    val referenceYear: Int? = null,
    /** E10. */
    val maghribTerrain: TerrainRule? = null,
    /** A18. */
    val jumua: JumuaRule = JumuaRule.AT_DHUHR,
    val evidence: Evidence,
    /** The profile's aggregate fit record (6.3); null for presets. */
    val fit: FitStats? = null,
) {
    init {
        require(asrShadow in 1..2) { "$id: Asr shadow factor 1 or 2, not $asrShadow" }
        require(seasonalOffsets.values.all { it.size == SEASONAL_POINTS }) { "$id: E18 offsets take $SEASONAL_POINTS points a year" }
        require(takvimMeans.isEmpty() || takvimMeans.size == 24) { "$id: the Takvim's Table 1 has 24 means" }
        require(revisions.zipWithNext().all { (a, b) -> a.validFrom < b.validFrom }) { "$id: revisions in date order" }
        require(referenceYear == null || referenceYear in 1800..2200) { "$id: reference year $referenceYear" }
    }

    /** True for the user's «مخصّصة» method, which E19 checks everywhere (6.11). */
    val isCustom: Boolean get() = id == CUSTOM_ID

    /** True when the method has a rule of E8 that a Ramadan day changes. */
    val hasRamadanRules: Boolean
        get() = ramadanOffsets.isNotEmpty() || (isha as? IshaRule.MinutesAfter)?.ramadan != null

    /** Step 0 (E9): the method as it stood on [date], each revision from its first day applied in turn. */
    fun revisedFor(date: LocalDate): PrayerMethod =
        revisions.filter { it.validFrom <= date }.fold(this) { method, revision -> revision.change.applyTo(method) }

    fun rounding(event: TimeEvent): Rounding = rounding[event] ?: Rounding.NEAREST

    fun offset(event: TimeEvent): Int = offsets[event] ?: 0

    fun evaluation(event: TimeEvent): SunEvaluation = sunEvaluation[event] ?: SunEvaluation.EVENT_INSTANT

    companion object {
        const val CUSTOM_ID = "custom"
        const val SEASONAL_POINTS = 36
    }
}

@Serializable
sealed interface FajrRule {
    @Serializable
    @SerialName("angle")
    data class Angle(val degrees: Double) : FajrRule

    @Serializable
    @SerialName("before_sunrise")
    data class MinutesBeforeSunrise(val minutes: Int) : FajrRule
}

@Serializable
sealed interface IshaRule {
    /**
     * The sun [degrees] below the horizon at the evening point, or at [at] when set: a table that copies another
     * place's twilight, on the place's own cycle and with Isha's evaluation (Slovenia's Isha is Sarajevo's 17°, 8.8).
     * [atNameAr] names that point in «طريقتك».
     */
    @Serializable
    @SerialName("angle")
    data class Angle(val degrees: Double, val at: LatLngE5? = null, val atNameAr: String? = null) : IshaRule

    /** A fixed interval after Maghrib (after all of Maghrib's adjustments, step 4) or after sunset; [ramadan] on Ramadan days (E8). */
    @Serializable
    @SerialName("after")
    data class MinutesAfter(val minutes: Int, val from: IshaFrom = IshaFrom.MAGHRIB, val ramadan: Int? = null) : IshaRule
}

enum class IshaFrom { MAGHRIB, SUNSET }

/** E15 (plan 6.2); the shadow factor is [PrayerMethod.asrShadow]. */
@Serializable
sealed interface AsrRule {
    /** The altitude atan(1 / (k + tan|φ − δ|)). */
    @Serializable
    @SerialName("shadow")
    data object Shadow : AsrRule

    /** Algeria: tan(φ − δ) without the absolute value; the repository passes [Shadow] outside DZ. */
    @Serializable
    @SerialName("signed_shadow")
    data object SignedShadow : AsrRule

    /** The Comoros' and Mayotte's traditional table: Maghrib − [minutes], counted from step 4's Maghrib. */
    @Serializable
    @SerialName("before_maghrib")
    data class BeforeMaghrib(val minutes: Int) : AsrRule
}

/** E13: the shadow of the geometric sun, or of the refracted one (Jordan, the UAE, Iraq, Morocco). */
enum class AsrSun { GEOMETRIC, APPARENT }

/** E1: NEAREST rounds 30 s and more up; UP and DOWN keep an exact minute. */
enum class Rounding {
    NEAREST, UP, DOWN;

    companion object {
        val ALL_NEAREST: Map<TimeEvent, Rounding> = TimeEvent.entries.associateWith { NEAREST }
    }
}

/** E16: from [fromM] metres up to the next shift's height, the shift's [minutes] (fractions kept until the rounding). */
@Serializable
data class ElevationShift(val fromM: Int, val minutes: Double)

/** E18's dated offset: [minutes] on [event] from one calendar date to another, both included; a span past 31 December wraps. */
@Serializable
data class DatedOffset(val event: TimeEvent, val fromMonth: Int, val fromDay: Int, val toMonth: Int, val toDay: Int, val minutes: Double) {
    fun covers(date: LocalDate): Boolean {
        val day = date.monthValue * 100 + date.dayOfMonth
        val from = fromMonth * 100 + fromDay
        val to = toMonth * 100 + toDay
        return if (from <= to) day in from..to else day >= from || day <= to
    }
}

/** E9: from [validFrom] on, [change] applies. */
@Serializable
data class MethodRevision(
    @Serializable(with = LocalDateIsoSerializer::class) val validFrom: LocalDate,
    val change: MethodChange,
)

/** What a revision changes; [offsets] replace the method's offsets of the times they name. */
@Serializable
data class MethodChange(
    val offsets: Map<TimeEvent, Int> = emptyMap(),
    val fajr: FajrRule? = null,
    val isha: IshaRule? = null,
    val jumua: JumuaRule? = null,
) {
    fun applyTo(method: PrayerMethod): PrayerMethod = method.copy(
        offsets = method.offsets + offsets,
        fajr = fajr ?: method.fajr,
        isha = isha ?: method.isha,
        jumua = jumua ?: method.jumua,
    )
}

/** E10: Yemen's Maghrib + [lowMinutes] at or below [lowMaxM] metres and + [highMinutes] at or above [highMinM]. */
@Serializable
data class TerrainRule(
    val lowMaxM: Int = 650,
    val lowMinutes: Int = 6,
    val highMinM: Int = 1100,
    val highMinutes: Int = 10,
) {
    /** The class of a place at [elevationM]; between the limits the nearer limit decides unless [own] is given. */
    fun classOf(elevationM: Double, own: TerrainClass?): TerrainClass = when {
        elevationM <= lowMaxM -> TerrainClass.LOW
        elevationM >= highMinM -> TerrainClass.HIGH
        own != null -> own
        elevationM - lowMaxM <= highMinM - elevationM -> TerrainClass.LOW
        else -> TerrainClass.HIGH
    }

    fun minutes(terrainClass: TerrainClass): Int = if (terrainClass == TerrainClass.LOW) lowMinutes else highMinutes
}

enum class TerrainClass { LOW, HIGH }

/** A18: the Jumu'a time a method publishes. */
@Serializable
sealed interface JumuaRule {
    @Serializable
    @SerialName("at_dhuhr")
    data object AtDhuhr : JumuaRule

    /** A fixed clock time in the place's zone (the UAE's 12:45). */
    @Serializable
    @SerialName("fixed")
    data class Fixed(@Serializable(with = LocalTimeIsoSerializer::class) val time: LocalTime) : JumuaRule

    companion object {
        val AT_DHUHR: JumuaRule = AtDhuhr
    }
}

/** Where a method's definition comes from: decides its chip (6.3, 6.11). */
@Serializable
data class Evidence(val kind: EvidenceKind, val source: String)

enum class EvidenceKind { PUBLISHED, FROM_TABLES, APPROXIMATE }
