package com.tunisianprayertimes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A delegation as INM (meteo.tn) computes it: published coordinates and a whole-metre
 * elevation. [sunriseElevationOverrides] reproduces per-year quirks of INM's own tables
 * (Zeriba's 2026 sunrises use 15.6 m instead of 156 m).
 */
data class InmLocation(
    val latitude: Double,
    val longitude: Double,
    val elevationM: Double,
    val sunriseElevationOverrides: Map<Int, Double> = emptyMap(),
) {
    companion object {
        /** Parses data/prayer-formula/delegation_params.json into locations keyed by delegation id. */
        fun parseAll(json: String): Map<Int, InmLocation> =
            Json.parseToJsonElement(json).jsonObject.mapNotNull { (id, value) ->
                val entry = value as? JsonObject ?: return@mapNotNull null
                val overrides = entry["overrides"]?.jsonObject.orEmpty().mapNotNull { (year, quirks) ->
                    val sunrise = quirks.jsonObject["sunrise_elevation_m"] ?: return@mapNotNull null
                    year.toInt() to sunrise.jsonPrimitive.double
                }.toMap()
                id.toInt() to InmLocation(
                    latitude = entry.getValue("lat").jsonPrimitive.double,
                    longitude = entry.getValue("lng").jsonPrimitive.double,
                    elevationM = entry.getValue("elevation_m").jsonPrimitive.double,
                    sunriseElevationOverrides = overrides,
                )
            }.toMap()
    }
}

/**
 * INM's prayer-time computation, reproducing its published minutes (validated on every
 * delegation for 2020-2026; derivation in scripts/prayer_formula/README.md).
 *
 * Sun: Meeus, "Astronomical Formulae for Calculators" (1900 epoch), apparent longitude and
 * obliquity. Dhuhr/Asr use the sun at 0h UT; Fajr/Sunrise/Maghrib/Isha re-evaluate it at the
 * event's apparent solar time. Time zone UTC+1 without DST; minutes are rounded half up.
 */
object InmPrayerFormula {
    private const val DEG = PI / 180
    private const val TIME_ZONE_HOURS = 1.0
    private const val DHUHR_OFFSET_MIN = 7.0
    private const val MAGHRIB_OFFSET_MIN = 2.0
    private const val TWILIGHT_ANGLE = 18.0
    private const val HORIZON_ANGLE = 0.83
    private const val EARTH_RADIUS_M = 6378137.0
    private const val ITERATIONS = 5

    fun dayPrayerTimes(location: InmLocation, year: Int, month: Int, day: Int): DayPrayerTimes {
        val (fajr, sunrise, dhuhr, asr, maghrib, isha) = explain(location, year, month, day).events
            .map { it.shownMinutes }
        fun time(prayer: Prayer, minutes: Int) = PrayerTime(prayer, minutes / 60, minutes % 60)
        return DayPrayerTimes(
            day = day,
            fajr = time(Prayer.FAJR, fajr),
            shurukHour = sunrise / 60,
            shurukMinute = sunrise % 60,
            dhuhr = time(Prayer.DHUHR, dhuhr),
            asr = time(Prayer.ASR, asr),
            maghrib = time(Prayer.MAGHRIB, maghrib),
            isha = time(Prayer.ISHA, isha),
        )
    }

    /** Unrounded minutes after local midnight: Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha. */
    fun minutes(location: InmLocation, year: Int, month: Int, day: Int): List<Double> =
        explain(location, year, month, day).events.map { it.exactMinutes }

    /**
     * The whole computation for one day with its intermediate values, so the app can show
     * users the working behind each time. [minutes] is derived from it, so the two never drift.
     */
    fun explain(location: InmLocation, year: Int, month: Int, day: Int): InmDayExplanation {
        val jd0 = julianDay(year, month, day)
        val lat = location.latitude
        val lng = location.longitude
        val (decl0, eot0) = sun(jd0)
        val noon = 12 - eot0 / 60 - lng / 15 + TIME_ZONE_HOURS
        val asrAltitude = atan(1 / (1 + tan(abs(lat - decl0) * DEG))) / DEG
        val asrHourAngle = hourAngle(asrAltitude, lat, decl0)
        val dip = dipFromElevation(location.elevationM)
        val sunriseDip = location.sunriseElevationOverrides[year]?.let(::dipFromElevation) ?: dip
        val fajr = horizonEvent(jd0, lat, lng, -(TWILIGHT_ANGLE + dip), -1)
        val sunrise = horizonEvent(jd0, lat, lng, -(HORIZON_ANGLE + sunriseDip), -1)
        val maghrib = horizonEvent(jd0, lat, lng, -(HORIZON_ANGLE + dip), 1)
        val isha = horizonEvent(jd0, lat, lng, -(TWILIGHT_ANGLE + dip), 1)
        fun step(event: InmEvent, altitude: Double, hourAngleHours: Double?, exactMinutes: Double) =
            InmEventStep(event, altitude, hourAngleHours?.let { it * 15 }, exactMinutes, floor(exactMinutes + 0.5).toInt())
        return InmDayExplanation(
            julianDay = jd0,
            declinationDeg = decl0,
            equationOfTimeMin = eot0,
            solarNoonMinutes = noon * 60,
            dipDeg = dip,
            sunriseDipDeg = sunriseDip,
            asrAltitudeDeg = asrAltitude,
            events = listOf(
                step(InmEvent.FAJR, -(TWILIGHT_ANGLE + dip), fajr.hourAngle, fajr.localHours * 60),
                step(InmEvent.SUNRISE, -(HORIZON_ANGLE + sunriseDip), sunrise.hourAngle, sunrise.localHours * 60),
                step(InmEvent.DHUHR, 90 - abs(lat - decl0), null, noon * 60 + DHUHR_OFFSET_MIN),
                step(InmEvent.ASR, asrAltitude, asrHourAngle, (noon + asrHourAngle) * 60),
                step(InmEvent.MAGHRIB, -(HORIZON_ANGLE + dip), maghrib.hourAngle, maghrib.localHours * 60 + MAGHRIB_OFFSET_MIN),
                step(InmEvent.ISHA, -(TWILIGHT_ANGLE + dip), isha.hourAngle, isha.localHours * 60),
            ),
        )
    }

    /**
     * The sun's altitude in degrees at [localMinutes] after local midnight (UTC+1), with the
     * same sun model the times use. For drawing the sun's path, not for computing times.
     */
    fun sunAltitudeDeg(location: InmLocation, year: Int, month: Int, day: Int, localMinutes: Double): Double {
        val localHours = localMinutes / 60
        val (decl, eot) = sun(julianDay(year, month, day) + (localHours - TIME_ZONE_HOURS) / 24)
        val noon = 12 - eot / 60 - location.longitude / 15 + TIME_ZONE_HOURS
        val hourAngle = (localHours - noon) * 15
        val lat = location.latitude
        val sinAltitude = sin(lat * DEG) * sin(decl * DEG) + cos(lat * DEG) * cos(decl * DEG) * cos(hourAngle * DEG)
        return asin(sinAltitude.coerceIn(-1.0, 1.0)) / DEG
    }

    /** Julian day at 0h UT of a Gregorian civil date. */
    fun julianDay(year: Int, month: Int, day: Int): Double {
        val a = (14 - month) / 12
        val y = year + 4800 - a
        val m = month + 12 * a - 3
        val jdn = day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
        return jdn - 0.5
    }

    /**
     * Horizon dip in degrees. INM rounds R / (R + h) to single precision, which quantises
     * the dip; computing it in double precision misses hundreds of minutes a year.
     */
    fun dipFromElevation(elevationM: Double): Double =
        acos((EARTH_RADIUS_M / (EARTH_RADIUS_M + elevationM)).toFloat().toDouble()) / DEG

    /** Meeus 1900-epoch sun: declination (degrees) and equation of time (minutes). */
    internal fun sun(jd: Double): Pair<Double, Double> {
        val t = (jd - 2415020.0) / 36525
        val meanLong = (279.69668 + 36000.76892 * t + 0.0003025 * t * t) % 360
        val anomaly = 358.47583 + 35999.04975 * t - 0.000150 * t * t - 0.0000033 * t.pow(3)
        val e = 0.01675104 - 0.0000418 * t - 0.000000126 * t * t
        val center = ((1.919460 - 0.004789 * t - 0.000014 * t * t) * sin(anomaly * DEG)
            + (0.020094 - 0.000100 * t) * sin(2 * anomaly * DEG)
            + 0.000293 * sin(3 * anomaly * DEG))
        val omega = 259.18 - 1934.142 * t
        val apparentLong = meanLong + center - 0.00569 - 0.00479 * sin(omega * DEG)
        val obliquity = (23.452294 - 0.0130125 * t - 0.00000164 * t * t + 0.000000503 * t.pow(3)
            + 0.00256 * cos(omega * DEG))
        val decl = asin(sin(obliquity * DEG) * sin(apparentLong * DEG)) / DEG
        val y = tan(obliquity / 2 * DEG).pow(2)
        val eot = (y * sin(2 * meanLong * DEG) - 2 * e * sin(anomaly * DEG)
            + 4 * e * y * sin(anomaly * DEG) * cos(2 * meanLong * DEG)
            - 0.5 * y * y * sin(4 * meanLong * DEG) - 1.25 * e * e * sin(2 * anomaly * DEG))
        return decl to 4 * eot / DEG
    }

    /** Hours between solar noon and the moment the sun reaches [altitude]. */
    private fun hourAngle(altitude: Double, lat: Double, decl: Double): Double {
        val cosH = (sin(altitude * DEG) - sin(lat * DEG) * sin(decl * DEG)) /
            (cos(lat * DEG) * cos(decl * DEG))
        return acos(cosH.coerceIn(-1.0, 1.0)) / DEG / 15
    }

    private class HorizonEvent(val localHours: Double, val hourAngle: Double)

    /** Local time in hours, with the final hour angle in hours; [sign] -1 = morning, +1 = evening. */
    private fun horizonEvent(jd0: Double, lat: Double, lng: Double, altitude: Double, sign: Int): HorizonEvent {
        var (decl, eot) = sun(jd0)
        repeat(ITERATIONS) {
            val next = sun(jd0 + (12 + sign * hourAngle(altitude, lat, decl)) / 24)
            decl = next.first
            eot = next.second
        }
        val hourAngle = hourAngle(altitude, lat, decl)
        return HorizonEvent(12 + sign * hourAngle - eot / 60 - lng / 15 + TIME_ZONE_HOURS, hourAngle)
    }

    private operator fun <T> List<T>.component6(): T = this[5]
}

/** The six times INM publishes, in the order of the day. */
enum class InmEvent { FAJR, SUNRISE, DHUHR, ASR, MAGHRIB, ISHA }

/**
 * How one time was reached: the sun [altitudeDeg] that defines it (for Dhuhr, the noon
 * altitude), the hour angle from solar noon (null for Dhuhr), and the minutes after local
 * midnight before and after rounding.
 */
data class InmEventStep(
    val event: InmEvent,
    val altitudeDeg: Double,
    val hourAngleDeg: Double?,
    val exactMinutes: Double,
    val shownMinutes: Int,
)

/** One day's computation with the intermediate values [InmPrayerFormula.explain] used. */
data class InmDayExplanation(
    val julianDay: Double,
    val declinationDeg: Double,
    val equationOfTimeMin: Double,
    val solarNoonMinutes: Double,
    val dipDeg: Double,
    val sunriseDipDeg: Double,
    val asrAltitudeDeg: Double,
    val events: List<InmEventStep>,
)
