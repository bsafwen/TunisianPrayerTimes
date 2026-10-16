package com.tunisianprayertimes.engine

import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.PrayerMethod
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * `irn`'s Mecca cycle (plan 7.4), from 63.56°N: in the polar seasons Mecca's day is placed on the local
 * solar noon. Fajr and Isha keep Mecca's 18° offsets from its noon, sunrise its sunrise offset and Maghrib
 * its sunset offset (the method's Maghrib minutes are added in step 4 as usual).
 *
 * The seasons follow 7.4's description of IRN's tables: towards the midnight sun once the real sunset is
 * later than noon + 8 h 05 (or there is none), towards the polar night (north of 66.33°N only) once it is
 * earlier than noon + 3 h 20 (or there is none); between 63.56° and 66.33°N, a winter Maghrib floor at noon
 * + 2 h instead. IRN switches Maghrib about 7 days before Fajr and Isha; this build switches them together,
 * provisional until fitted on IRN's 2026 tables.
 */
internal object IrnMeccaCycle {
    const val FROM_LATITUDE = 63.56
    const val POLAR_NIGHT_LATITUDE = 66.33
    private const val SUMMER_SUNSET = 8 * 3600.0 + 5 * 60.0
    private const val WINTER_SUNSET = 3 * 3600.0 + 20 * 60.0
    const val WINTER_MAGHRIB_FLOOR = 2 * 3600.0
    private val MECCA = Point(21.4225, 39.8262)
    private val RIYADH: ZoneId = ZoneId.of("Asia/Riyadh")

    /** Mecca's day placed on local noon: the four times it gives, as epoch seconds. */
    data class Times(val fajr: Double, val sunrise: Double, val sunset: Double, val isha: Double)

    fun applies(site: Site, method: PrayerMethod): Boolean =
        method.polar == PolarFallback.IRN_MECCA_CYCLE && abs(site.main.latitude) >= FROM_LATITUDE

    /** True on a day of a polar season at [site]. */
    fun inSeason(site: Site, day: DayAstro): Boolean {
        val sunset = day.sunsetRaw
        val polarNight = day.noonAltitudeDeg < day.sunriseHorizonDeg
        if (sunset == null) return !polarNight || abs(site.main.latitude) >= POLAR_NIGHT_LATITUDE
        val afterNoon = sunset - day.transit
        if (afterNoon > SUMMER_SUNSET) return true
        return abs(site.main.latitude) >= POLAR_NIGHT_LATITUDE && afterNoon < WINTER_SUNSET
    }

    fun times(method: PrayerMethod, date: LocalDate, localTransit: Double): Times {
        val mecca = Site(MECCA, MECCA, MECCA, 0.0, 0.0, Site.STANDARD_PRESSURE_HPA, Site.STANDARD_TEMPERATURE_C, null)
        val at18 = method.copy(fajr = FajrRule.Angle(18.0), isha = IshaRule.Angle(18.0), twilightLatitudeScale = 1.0, sunEvaluation = emptyMap())
        val sky = Sky(mecca, date, RIYADH, at18)
        val noon = checkNotNull(sky.transit.seconds)
        fun offset(t: Double?) = checkNotNull(t) { "Mecca has every event" } - noon
        return Times(
            fajr = localTransit + offset(sky.twilight(TimeEvent.FAJR, 18.0).seconds),
            sunrise = localTransit + offset(sky.sunrise.seconds),
            sunset = localTransit + offset(sky.sunset.seconds),
            isha = localTransit + offset(sky.twilight(TimeEvent.ISHA, 18.0).seconds),
        )
    }
}
