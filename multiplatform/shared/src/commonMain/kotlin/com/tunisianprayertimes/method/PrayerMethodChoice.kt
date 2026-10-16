package com.tunisianprayertimes.method

import com.tunisianprayertimes.engine.HighLatitudeRule
import com.tunisianprayertimes.engine.PolarFallback
import com.tunisianprayertimes.engine.TimeEvent
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Which official zone the user wants (6.5): their own exact coordinates («حسب موقعي بالضبط»), or a named zone. */
@Serializable
sealed interface ZonePick {
    @Serializable
    @SerialName("exact")
    data object Exact : ZonePick

    @Serializable
    @SerialName("zone")
    data class Zone(val id: String) : ZonePick
}

/**
 * What the phone stores for one country (plan 6.3, 6.4), `method_choice_<CC>`: the method and the user's
 * own settings on it. [key] identifies the resolved choice for caches; whether the chooser was answered and
 * whether the user picked the method do not change the times, so they stay out of it.
 */
@Serializable
data class PrayerMethodChoice(
    val methodId: String,
    val asrShadow: Int? = null,
    val highLatitude: HighLatitudeRule? = null,
    val polar: PolarFallback? = null,
    /** −30..30 per time, never 0 (omitted). */
    val adjustments: Map<TimeEvent, Int> = emptyMap(),
    /** The bounded «مخصّصة» method (6.11), when [methodId] is `custom`. */
    val custom: PrayerMethod? = null,
    /** Null: the zone the place maps to (6.5). */
    val zonePick: ZonePick? = null,
    val chooserAnswered: Boolean = false,
    val methodPicked: Boolean = false,
) {
    init {
        require(adjustments.values.all { it in ADJUSTMENT_RANGE && it != 0 }) { "adjustments within $ADJUSTMENT_RANGE, never 0: $adjustments" }
        require(asrShadow == null || asrShadow in 1..2) { "Asr factor 1 or 2: $asrShadow" }
        require((methodId == PrayerMethod.CUSTOM_ID) == (custom != null)) { "a custom method iff methodId is custom" }
    }

    /** The method this choice computes with: the preset or profile (or the custom one) with the user's overrides. */
    fun resolve(): PrayerMethod? {
        val base = if (methodId == PrayerMethod.CUSTOM_ID) custom else PrayerMethods.byId(methodId)
        return base?.copy(
            asrShadow = asrShadow ?: base.asrShadow,
            highLatitude = highLatitude ?: base.highLatitude,
            polar = polar ?: base.polar,
        )
    }

    /** A stable key of what changes the times: the method, its overrides, the adjustments and the zone pick. */
    val key: String
        get() {
            val canonical = KEY_JSON.encodeToString(serializer(), copy(chooserAnswered = false, methodPicked = false, adjustments = adjustments.toSortedMap()))
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

    /** This choice with [event] moved by [minutes] (0 removes the adjustment). */
    fun withAdjustment(event: TimeEvent, minutes: Int): PrayerMethodChoice =
        copy(adjustments = if (minutes == 0) adjustments - event else adjustments + (event to minutes))

    companion object {
        val ADJUSTMENT_RANGE = -30..30
        private val KEY_JSON = Json { encodeDefaults = true }

        /** The country's default choice (6.4): its fallback method, nothing picked yet. */
        fun default(defaults: CountryDefault): PrayerMethodChoice = PrayerMethodChoice(defaults.fallback)
    }
}
