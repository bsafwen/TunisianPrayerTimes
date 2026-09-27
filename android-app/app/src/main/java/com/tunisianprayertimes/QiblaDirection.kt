package com.tunisianprayertimes

import net.sf.geographiclib.Constants
import net.sf.geographiclib.Geodesic
import net.sf.geographiclib.GeodesicMask
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal const val KAABA_LATITUDE = 21.422487
internal const val KAABA_LONGITUDE = 39.826206

private val WGS84_ECCENTRICITY = sqrt(Constants.WGS84_f * (2.0 - Constants.WGS84_f))

/** The two accepted ways of tracing the direction of the Kaaba. */
enum class QiblaMethod {
    /** Start of the shortest path over the Earth (great circle). Most authorities use it. */
    GreatCircle,

    /** The path that keeps one compass bearing all the way (rhumb line): straight on a Mercator map. */
    RhumbLine,
}

/** Where the Kaaba lies from a point on the WGS84 ellipsoid. */
data class QiblaSolution(
    val method: QiblaMethod,
    /** Degrees clockwise from true north. */
    val bearingDegrees: Double,
    /** Shortest distance to the Kaaba, whichever [method] gave the bearing. */
    val distanceMeters: Double,
)

/**
 * Solves the geodesic with Karney's algorithm (GeographicLib), for the distance and the
 * great-circle bearing. Unlike Vincenty's method it converges everywhere, including next
 * to the Kaaba's antipode in the South Pacific, where the direction also turns quickly
 * with position.
 */
fun calculateQibla(
    latitude: Double,
    longitude: Double,
    method: QiblaMethod = QiblaMethod.GreatCircle,
): QiblaSolution {
    val geodesic = Geodesic.WGS84.Inverse(
        latitude,
        longitude,
        KAABA_LATITUDE,
        KAABA_LONGITUDE,
        GeodesicMask.AZIMUTH or GeodesicMask.DISTANCE,
    )
    val bearingDegrees = when (method) {
        QiblaMethod.GreatCircle -> geodesic.azi1
        QiblaMethod.RhumbLine -> rhumbLineBearingDegrees(latitude, longitude, KAABA_LATITUDE, KAABA_LONGITUDE)
    }
    return QiblaSolution(
        method = method,
        bearingDegrees = normalizeDegrees(bearingDegrees),
        distanceMeters = geodesic.s12,
    )
}

fun calculateQiblaBearing(
    latitude: Double,
    longitude: Double,
    method: QiblaMethod = QiblaMethod.GreatCircle,
): Double {
    return calculateQibla(latitude, longitude, method).bearingDegrees
}

/**
 * Constant bearing from one point to another on the WGS84 ellipsoid, going the shorter way
 * around in longitude. On a Mercator map the rhumb line is straight, so its bearing follows
 * from the longitude difference and the difference in isometric latitude.
 */
internal fun rhumbLineBearingDegrees(
    fromLatitude: Double,
    fromLongitude: Double,
    toLatitude: Double,
    toLongitude: Double,
): Double {
    val longitudeDifference = Math.toRadians(shortestSignedAngleDegrees(fromLongitude, toLongitude))
    val isometricLatitudeDifference = isometricLatitude(toLatitude) - isometricLatitude(fromLatitude)
    return normalizeDegrees(Math.toDegrees(atan2(longitudeDifference, isometricLatitudeDifference)))
}

/** Mercator's northing on the WGS84 ellipsoid, in radians; finite even at the poles. */
private fun isometricLatitude(latitudeDegrees: Double): Double {
    val latitude = Math.toRadians(latitudeDegrees.coerceIn(-90.0, 90.0))
    return asinh(tan(latitude)) - WGS84_ECCENTRICITY * atanh(WGS84_ECCENTRICITY * sin(latitude))
}

/**
 * Worst-case bearing error caused by the location itself: a fix [accuracyMeters] to one
 * side of the true position, [distanceMeters] from the Kaaba. Null when the accuracy is
 * unknown; 180° when the Kaaba may lie inside the error circle.
 */
fun locationBearingUncertaintyDegrees(distanceMeters: Double, accuracyMeters: Float?): Double? {
    if (accuracyMeters == null || !accuracyMeters.isFinite() || accuracyMeters <= 0f) return null
    if (accuracyMeters >= distanceMeters) return 180.0
    return Math.toDegrees(asin(accuracyMeters / distanceMeters))
}

fun normalizeDegrees(degrees: Double): Double {
    return ((degrees % 360.0) + 360.0) % 360.0
}

fun shortestSignedAngleDegrees(currentDegrees: Double, targetDegrees: Double): Double {
    val delta = ((((targetDegrees - currentDegrees) % 360.0) + 540.0) % 360.0) - 180.0
    return if (delta == -180.0) 180.0 else delta
}
