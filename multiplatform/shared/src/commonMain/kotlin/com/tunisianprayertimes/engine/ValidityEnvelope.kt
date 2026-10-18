package com.tunisianprayertimes.engine

import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.method.Rounding
import com.tunisianprayertimes.place.PrayerPlace

/**
 * E19, the validity envelope (plan 7.7): every computed start at a mainland French place, and a custom
 * method's anywhere, stays inside the bounds of the recognised positions. A time beyond a bound moves to
 * it, rounded inward and ESTIMATED; the 18° floor and the bounds set by the sun's disk keep a time within
 * 1 min of them. Step 8, after the `ishaLatest` cap and before the user's steppers.
 */
internal object ValidityEnvelope {
    /** E19's and step 9's allowance on the 18° floor and the bounds set by the sun's disk. */
    const val ALLOWANCE_SECONDS = 60.0

    /** The zawal is complete 1.2 min after the transit, once the disc's trailing edge has crossed the meridian. */
    const val ZAWAL_SECONDS = 72.0

    fun applies(place: PrayerPlace.Coordinates, method: PrayerMethod) = place.isMainlandFrance || method.isCustom

    fun apply(run: MethodRun, place: PrayerPlace.Coordinates, nightBefore: Night?) {
        if (!applies(place, run.method)) return
        val france = place.isMainlandFrance
        val sky = run.sky
        val method = run.method
        fun Slot.hold(to: Double, bound: EnvelopeBound, inward: Rounding) {
            moveTo(8, StepKind.VALIDITY_ENVELOPE, to, RuleName.VALIDITY_ENVELOPE, bound)
            rounding = inward
        }
        val fajr = run[TimeEvent.FAJR]
        fajr.seconds?.let { t ->
            val floorAngle = if (france) 18.0 else CustomMethodRanges.FAJR_ANGLES.endInclusive
            val angleTime = sky.twilight(TimeEvent.FAJR, floorAngle, scaled = false).seconds
            val floor = angleTime ?: nightBefore?.lowestPoint
            val bound = when {
                angleTime == null -> EnvelopeBound.FAJR_SOLAR_MIDNIGHT
                france -> EnvelopeBound.FAJR_18
                else -> EnvelopeBound.FAJR_20
            }
            if (floor != null && t < floor - ALLOWANCE_SECONDS) fajr.hold(floor, bound, Rounding.UP)
        }
        fajr.seconds?.let { t ->
            val ceiling = sky.twilight(TimeEvent.FAJR, 12.0, scaled = false).seconds
            if (ceiling != null && t > ceiling) fajr.hold(ceiling, EnvelopeBound.FAJR_12, Rounding.DOWN)
        }
        // The sunrise and sunset of steps 1–3 (owner, 2026-10-18): where a polar rule set them (the 5-hour minimum, irn's
        // cycle), the envelope keeps that estimate, so a custom method in the far north keeps the times of the method it
        // was made from instead of the real sun's short night.
        val sunrise = run[TimeEvent.SUNRISE]
        val computedSunrise = run.stepsSunrise
        sunrise.seconds?.let { t ->
            if (computedSunrise != null && t > computedSunrise + ALLOWANCE_SECONDS) sunrise.hold(computedSunrise, EnvelopeBound.SUNRISE, Rounding.DOWN)
        }
        val dhuhr = run[TimeEvent.DHUHR]
        val zawal = checkNotNull(sky.transit.seconds) + ZAWAL_SECONDS
        dhuhr.seconds?.let { t -> if (t < zawal - ALLOWANCE_SECONDS) dhuhr.hold(zawal, EnvelopeBound.DHUHR, Rounding.UP) }
        val asr = run[TimeEvent.ASR]
        val firstShadow = sky.firstShadowAsr.seconds
        asr.seconds?.let { t -> if (firstShadow != null && t < firstShadow - ALLOWANCE_SECONDS) asr.hold(firstShadow, EnvelopeBound.ASR, Rounding.UP) }
        val maghrib = run[TimeEvent.MAGHRIB]
        val sunset = run.stepsSunset
        maghrib.seconds?.let { t ->
            if (sunset != null && t < sunset + 60.0 - ALLOWANCE_SECONDS) maghrib.hold(sunset + 60.0, EnvelopeBound.MAGHRIB, Rounding.UP)
        }
        val isha = run[TimeEvent.ISHA]
        isha.seconds?.let { t ->
            val twelve = sky.twilight(TimeEvent.ISHA, 12.0, scaled = false).seconds
            val estimates = method.isha is IshaRule.MinutesAfter || isha.status == EventStatus.ESTIMATED
            if (estimates && sunset != null) {
                val ahmer = sunset + MoonsightingSeasonal.minutes(MoonsightingSeasonal.Curve.ISHA_AHMER, run.site.evening.latitude, run.date) * 60.0
                if (t < ahmer) isha.hold(ahmer, EnvelopeBound.ISHA_AHMER, Rounding.UP)
                else if (twelve == null || t < twelve) {
                    isha.steps += EngineStep(8, StepKind.VALIDITY_ENVELOPE, 0.0, EnvelopeBound.ISHA_12, RuleName.VALIDITY_ENVELOPE)
                    isha.markEstimated(RuleName.VALIDITY_ENVELOPE)
                }
            } else if (twelve != null && t < twelve) {
                isha.hold(twelve, EnvelopeBound.ISHA_12, Rounding.UP)
            }
        }
    }
}

/**
 * The bounded «مخصّصة» (plan 6.11): only the inputs that are matters of ijtihad, each limited to the
 * positions recognised bodies or credible observations hold. E19 checks a custom method wherever it is used.
 */
object CustomMethodRanges {
    /** Fajr by angle: 12°–20° in steps of 0.5°; in mainland France 12°–18° (E19). */
    val FAJR_ANGLES = 12.0..20.0
    val FRANCE_FAJR_ANGLES = 12.0..18.0
    const val ANGLE_STEP = 0.5

    /** Fajr by minutes before sunrise. */
    val FAJR_MINUTES = 60..120

    /** Isha by angle: the red twilight 12°–17°, or the white twilight, 18°. */
    val ISHA_ANGLES = 12.0..18.0

    /** Isha by minutes after Maghrib. */
    val ISHA_MINUTES = 60..120

    /** Dhuhr minutes after the transit: the zawal needs about 1.2 min. */
    val DHUHR_MINUTES = 2..10

    /** Maghrib minutes after sunset: a precaution on the start; a mosque's later adhan goes in the stepper. */
    val MAGHRIB_MINUTES = 1..10

    /** Sunrise minutes, earlier only. */
    val SUNRISE_MINUTES = -10..0

    /** The ranges [method] breaks, empty when it is a valid custom method for a place in or out of mainland France. */
    fun problems(method: PrayerMethod, mainlandFrance: Boolean): List<String> {
        val out = mutableListOf<String>()
        fun onStep(x: Double) = (x / ANGLE_STEP).let { it == kotlin.math.floor(it) }
        when (val fajr = method.fajr) {
            is FajrRule.Angle -> {
                val range = if (mainlandFrance) FRANCE_FAJR_ANGLES else FAJR_ANGLES
                if (fajr.degrees !in range || !onStep(fajr.degrees)) out += "Fajr angle ${fajr.degrees} outside $range by $ANGLE_STEP"
            }
            is FajrRule.MinutesBeforeSunrise -> if (fajr.minutes !in FAJR_MINUTES) out += "Fajr minutes ${fajr.minutes} outside $FAJR_MINUTES"
        }
        when (val isha = method.isha) {
            is IshaRule.Angle -> if (isha.degrees !in ISHA_ANGLES || !onStep(isha.degrees)) out += "Isha angle ${isha.degrees} outside $ISHA_ANGLES"
            is IshaRule.MinutesAfter ->
                if (isha.minutes !in ISHA_MINUTES || isha.from != com.tunisianprayertimes.method.IshaFrom.MAGHRIB || isha.ramadan != null) {
                    out += "Isha interval ${isha.minutes} after ${isha.from} outside $ISHA_MINUTES after Maghrib"
                }
        }
        if (method.offset(TimeEvent.DHUHR) !in DHUHR_MINUTES) out += "Dhuhr + ${method.offset(TimeEvent.DHUHR)} outside $DHUHR_MINUTES"
        if (method.offset(TimeEvent.MAGHRIB) !in MAGHRIB_MINUTES) out += "Maghrib + ${method.offset(TimeEvent.MAGHRIB)} outside $MAGHRIB_MINUTES"
        if (method.offset(TimeEvent.SUNRISE) !in SUNRISE_MINUTES) out += "sunrise ${method.offset(TimeEvent.SUNRISE)} outside $SUNRISE_MINUTES"
        if (method.offset(TimeEvent.FAJR) != 0 || method.offset(TimeEvent.ASR) != 0 || method.offset(TimeEvent.ISHA) != 0) {
            out += "Fajr, Asr and Isha take no method minutes (the steppers move them)"
        }
        val horizons = setOf(Horizons.STANDARD, Horizons(HorizonModel.StandardWithDip, HorizonModel.StandardWithDip))
        if (method.horizon !in horizons) out += "horizon ${method.horizon}: standard, or with the place's dip"
        if (method.sunModel != SunModel.MEEUS || method.sunEvaluation.isNotEmpty() || method.rounding != com.tunisianprayertimes.method.Rounding.ALL_NEAREST) {
            out += "the astronomy is not a setting (sun, evaluation, rounding)"
        }
        return out
    }
}
