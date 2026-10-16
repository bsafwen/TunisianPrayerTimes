package com.tunisianprayertimes.engine

import com.tunisianprayertimes.method.AsrRule
import com.tunisianprayertimes.method.AsrSun
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.place.LatLngE5
import com.tunisianprayertimes.place.OfficialZone
import com.tunisianprayertimes.place.PrayerPlace
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan
import kotlin.math.withSign

internal data class Point(val latitude: Double, val longitude: Double) {
    companion object {
        fun of(p: LatLngE5) = Point(p.latitude, p.longitude)
    }
}

/**
 * Where a method runs and in what air (plan 6.2, E2 and E6): the place's own point at exact
 * coordinates, or the zone's points, elevations, pressure and temperature.
 */
internal data class Site(
    val main: Point,
    val morning: Point,
    val evening: Point,
    val morningElevationM: Double,
    val eveningElevationM: Double,
    val pressureHpa: Double,
    val temperatureC: Double,
    val zone: OfficialZone?,
) {
    companion object {
        const val STANDARD_PRESSURE_HPA = 1010.0
        const val STANDARD_TEMPERATURE_C = 10.0

        fun of(place: PrayerPlace.Coordinates, zone: OfficialZone?): Site {
            if (zone == null) {
                val p = Point(place.latE5 / 1e5, place.lngE5 / 1e5)
                val h = place.elevationM?.toDouble() ?: 0.0
                return Site(p, p, p, h, h, STANDARD_PRESSURE_HPA, STANDARD_TEMPERATURE_C, null)
            }
            val p = Point.of(zone.point)
            return Site(
                main = p,
                morning = zone.morningPoint?.let(Point::of) ?: p,
                evening = zone.eveningPoint?.let(Point::of) ?: p,
                morningElevationM = (zone.elevationM ?: 0).toDouble(),
                eveningElevationM = (zone.eveningElevationM ?: zone.elevationM ?: 0).toDouble(),
                pressureHpa = zone.pressureHpa ?: STANDARD_PRESSURE_HPA,
                temperatureC = zone.temperatureC ?: STANDARD_TEMPERATURE_C,
                zone = zone,
            )
        }
    }
}

/** One solved instant (epoch seconds; null when absent) with the point, altitude and sun it was solved at. */
internal data class Solved(val seconds: Double?, val point: Point, val altitudeDeg: Double?, val sun: SunState?)

/**
 * The raw astronomy of one civil date at one [site] (plan 6.2, step 1): the solar cycle and the events,
 * each with its E4 evaluation. The nights and the other days are [Season]'s.
 */
internal class Sky(val site: Site, val date: LocalDate, val zoneId: ZoneId, val method: PrayerMethod) {
    private val model = method.sunModel

    /** The sun at each instant (EVENT_INSTANT), memoized (bounded): bisections ask for the same instants again. */
    val instantSun: SunAt = object : SunAt {
        private val cache = HashMap<Double, SunState>()
        override fun at(jdUt: Double): SunState {
            cache[jdUt]?.let { return it }
            if (cache.size >= 128) cache.clear()
            return SolarEngine.sun(model, jdUt).also { cache[jdUt] = it }
        }
    }

    val cycle: SolarCycle = SolarCycle.of(date, zoneId, site.main.longitude)

    /** E4: the sun an event of cycle [c] is solved with. */
    fun provider(event: TimeEvent, c: SolarCycle): SunAt = when (method.evaluation(event)) {
        SunEvaluation.EVENT_INSTANT, SunEvaluation.LOCAL_MIDNIGHT_SHADOW,
        SunEvaluation.SHADOW_AT_DHUHR, SunEvaluation.SHADOW_AT_EVENT -> instantSun
        SunEvaluation.DAILY_0H_UT -> fixed(SolarEngine.sun(model, c.jd0))
        SunEvaluation.DAILY_10H_UT -> fixed(SolarEngine.sun(model, c.jd0 + 10.0 / 24.0))
        SunEvaluation.KACST -> {
            val declination = SolarEngine.sun(model, c.jd0 + 21.0 / 24.0)
            val equation = SolarEngine.sun(model, c.jd0 + 9.0 / 24.0)
            fixed(SunState(declination.declinationDeg, equation.equationOfTimeMin, equation.distanceAu))
        }
        SunEvaluation.DAILY_LOCAL_NOON -> fixed(SolarEngine.sun(model, localNoonJd(c)))
        SunEvaluation.EVENT_DECLINATION -> {
            val noon = SolarEngine.sun(model, localNoonJd(c))
            SunAt { jd -> SunState(instantSun.at(jd).declinationDeg, noon.equationOfTimeMin, noon.distanceAu) }
        }
    }

    /** S4: 12:00 local civil time of [c]'s civil date in the place's zone. */
    private fun localNoonJd(c: SolarCycle): Double =
        SolarEngine.julianDay(ZonedDateTime.of(c.date, LocalTime.NOON, zoneId).toInstant().epochSecond.toDouble())

    private fun fixed(state: SunState) = SunAt { state }

    private fun horizonInputs(rising: Boolean, distanceAu: Double) = HorizonInputs(
        elevationM = if (rising) site.morningElevationM else site.eveningElevationM,
        pressureHpa = site.pressureHpa,
        temperatureC = site.temperatureC,
        zone = site.zone,
        distanceAu = distanceAu,
    )

    /** An event of [c] at [point] on [side] with [sun], started from that sun's own transit there. */
    private fun solve(point: Point, latitude: Double, altitude: Double, side: Int, sun: SunAt, c: SolarCycle): Solved {
        val transit = Solver.transit(point.longitude, sun, c)
        val hours = Solver.event(latitude, point.longitude, altitude, side, transit, sun, c)
        return Solved(hours?.let(c::epochSeconds), point, altitude, hours?.let { sun.at(c.jd0 + it / 24.0) })
    }

    fun sunrise(c: SolarCycle = cycle): Solved {
        val sun = provider(TimeEvent.SUNRISE, c)
        val distance = sun.at(c.jd0 + Solver.transit(site.morning.longitude, sun, c) / 24.0).distanceAu
        val altitude = HorizonAltitude.of(method.horizon.sunrise, true, horizonInputs(true, distance))
        return solve(site.morning, site.morning.latitude, altitude, -1, sun, c)
    }

    fun sunset(c: SolarCycle = cycle): Solved {
        val sun = provider(TimeEvent.MAGHRIB, c)
        val distance = sun.at(c.jd0 + Solver.transit(site.evening.longitude, sun, c) / 24.0).distanceAu
        val altitude = HorizonAltitude.of(method.horizon.sunset, false, horizonInputs(false, distance))
        return solve(site.evening, site.evening.latitude, altitude, +1, sun, c)
    }

    /** The transit at the evening point, with Dhuhr's evaluation: Dhuhr before any minutes. */
    val transit: Solved by lazy {
        val sun = provider(TimeEvent.DHUHR, cycle)
        val hours = Solver.transit(site.evening.longitude, sun, cycle)
        Solved(cycle.epochSeconds(hours), site.evening, null, sun.at(cycle.jd0 + hours / 24.0))
    }

    val sunrise: Solved by lazy { sunrise(cycle) }
    val sunset: Solved by lazy { sunset(cycle) }

    /**
     * Fajr (side −1, morning point) or Isha (+1, evening point, or [at], `IshaRule.Angle.at`) at a depression
     * angle: the method's own ([scaled]: E17's twilight latitude, E4's evaluation, E3's dip), or, for E19's
     * bounds, the plain angle at the real latitude and the sun at its own instant.
     */
    fun twilight(event: TimeEvent, angleDeg: Double, scaled: Boolean = true, at: LatLngE5? = null): Solved {
        val morning = event == TimeEvent.FAJR
        val point = at?.let(Point::of) ?: if (morning) site.morning else site.evening
        val latitude = point.latitude * if (scaled) method.twilightLatitudeScale else 1.0
        val dip = if (scaled && method.dipOnTwilight) {
            HorizonAltitude.geometricDipDeg(if (morning) site.morningElevationM else site.eveningElevationM)
        } else 0.0
        val sun = if (scaled) provider(event, cycle) else instantSun
        return solve(point, latitude, -(angleDeg + dip), if (morning) -1 else 1, sun, cycle)
    }

    /** E12: Maghrib at a depression angle. */
    fun maghribAngle(angleDeg: Double): Solved =
        solve(site.evening, site.evening.latitude, -angleDeg, 1, provider(TimeEvent.MAGHRIB, cycle), cycle)

    /** The method's shadow Asr (E4, E13, E15); absent when the noon sun gives no shadow ratio. */
    val asr: Solved by lazy {
        val sun = provider(TimeEvent.ASR, cycle)
        val point = site.evening
        val transitHours = Solver.transit(point.longitude, sun, cycle)
        val signed = method.asr == AsrRule.SignedShadow
        fun altitudeAt(noonZenith: Double) = asrAltitudeAtZenith(
            noonZenith, method.asrShadow.toDouble(), apparent = method.asrSun == AsrSun.APPARENT,
            pressureHpa = site.pressureHpa, temperatureC = site.temperatureC,
        )
        fun zenithOf(declination: Double) = if (signed) point.latitude - declination else abs(point.latitude - declination)
        fun solveAt(altitude: Double?) = altitude?.let { Solver.event(point.latitude, point.longitude, it, 1, transitHours, sun, cycle) }
        val atTransit = sun.at(cycle.jd0 + transitHours / 24.0).declinationDeg
        var altitude: Double?
        var hours: Double?
        when (method.evaluation(TimeEvent.ASR)) {
            SunEvaluation.LOCAL_MIDNIGHT_SHADOW -> {
                val midnight = ZonedDateTime.of(date, LocalTime.MIDNIGHT, zoneId).toInstant().epochSecond.toDouble()
                altitude = altitudeAt(zenithOf(instantSun.at(SolarEngine.julianDay(midnight)).declinationDeg))
                hours = solveAt(altitude)
            }
            SunEvaluation.SHADOW_AT_DHUHR -> {
                // The noon shadow at Dhuhr's published instant: the transit plus Dhuhr's minutes.
                val dhuhr = transitHours + (method.offset(TimeEvent.DHUHR) + method.dhuhrAfterTransitMin) / 60.0
                val noonZenith = 90.0 - Solver.altitude(dhuhr, point.latitude, point.longitude, sun, cycle)
                altitude = altitudeAt(if (signed) noonZenith.withSign(point.latitude - atTransit) else noonZenith)
                hours = solveAt(altitude)
            }
            SunEvaluation.SHADOW_AT_EVENT -> {
                // The noon shadow with δ at the Asr instant itself, re-solved until Asr moves by less than 0.1 s.
                altitude = altitudeAt(zenithOf(atTransit))
                hours = solveAt(altitude)
                var passes = 0
                while (passes < 5) {
                    val previous = hours ?: break
                    altitude = altitudeAt(zenithOf(sun.at(cycle.jd0 + previous / 24.0).declinationDeg))
                    hours = solveAt(altitude)
                    passes++
                    val next = hours ?: break
                    if (abs(next - previous) * 3600.0 < 0.1) break
                }
            }
            else -> {
                altitude = altitudeAt(zenithOf(atTransit))
                hours = solveAt(altitude)
            }
        }
        val solvedAltitude = altitude
        if (solvedAltitude == null) Solved(null, point, null, null)
        else Solved(hours?.let(cycle::epochSeconds), point, solvedAltitude, hours?.let { sun.at(cycle.jd0 + it / 24.0) })
    }

    /** E19 and step 9: the factor-1 shadow on the geometric sun at its own instant, whatever the method. */
    val firstShadowAsr: Solved by lazy {
        val transitHours = Solver.transit(site.evening.longitude, instantSun, cycle)
        val declination = instantSun.at(cycle.jd0 + transitHours / 24.0).declinationDeg
        val altitude = asrAltitude(site.evening.latitude, declination, 1.0, signed = false, apparent = false, 0.0, 0.0)
        if (altitude == null) Solved(null, site.evening, null, null)
        else {
            val hours = Solver.event(site.evening.latitude, site.evening.longitude, altitude, 1, transitHours, instantSun, cycle)
            Solved(hours?.let(cycle::epochSeconds), site.evening, altitude, null)
        }
    }

    companion object {
        private const val DEG = PI / 180

        /**
         * The Asr altitude: atan(1 / (k + tan|φ − δ|)), or with the signed tan(φ − δ) (E15); on the
         * apparent sun (E13) the noon and Asr altitudes include Bennett's refraction at the given air.
         * Null when there is no noon shadow ratio (|φ − δ| ≥ 90°, or k + tan(φ − δ) ≤ 0 when signed). No
         * shipped method combines the signed shadow with the apparent sun; the apparent rule takes |φ − δ|.
         */
        fun asrAltitude(
            latitudeDeg: Double,
            declinationDeg: Double,
            factor: Double,
            signed: Boolean,
            apparent: Boolean,
            pressureHpa: Double,
            temperatureC: Double,
        ): Double? = asrAltitudeAtZenith(
            if (signed) latitudeDeg - declinationDeg else abs(latitudeDeg - declinationDeg), factor, apparent, pressureHpa, temperatureC,
        )

        /** [asrAltitude] from the noon zenith distance, signed for E15 (S7). */
        fun asrAltitudeAtZenith(zenith: Double, factor: Double, apparent: Boolean, pressureHpa: Double, temperatureC: Double): Double? {
            if (abs(zenith) >= 90.0) return null
            if (!apparent) {
                val ratio = factor + tan(zenith * DEG)
                return if (ratio <= 0) null else atan(1.0 / ratio) / DEG
            }
            val noonTrue = 90.0 - abs(zenith)
            val noonApparent = noonTrue + HorizonAltitude.bennettRefractionDeg(noonTrue, pressureHpa, temperatureC)
            val asrApparent = atan(1.0 / (factor + tan((90.0 - noonApparent) * DEG))) / DEG
            return asrApparent - HorizonAltitude.bennettRefractionDeg(asrApparent, pressureHpa, temperatureC)
        }
    }
}
