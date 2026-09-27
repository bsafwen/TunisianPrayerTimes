package com.tunisianprayertimes

import net.sf.geographiclib.Geodesic
import net.sf.geographiclib.GeodesicMask
import kotlin.math.asin

internal const val KAABA_LATITUDE = 21.422487
internal const val KAABA_LONGITUDE = 39.826206

/** Where the Kaaba lies from a point, along the shortest path on the WGS84 ellipsoid. */
data class QiblaSolution(
    /** Degrees clockwise from true north. */
    val bearingDegrees: Double,
    val distanceMeters: Double,
)

/**
 * Solves the geodesic with Karney's algorithm (GeographicLib). Unlike Vincenty's
 * method it converges everywhere, including next to the Kaaba's antipode in the
 * South Pacific, where the direction also turns quickly with position.
 */
fun calculateQibla(latitude: Double, longitude: Double): QiblaSolution {
    val geodesic = Geodesic.WGS84.Inverse(
        latitude,
        longitude,
        KAABA_LATITUDE,
        KAABA_LONGITUDE,
        GeodesicMask.AZIMUTH or GeodesicMask.DISTANCE,
    )
    return QiblaSolution(
        bearingDegrees = normalizeDegrees(geodesic.azi1),
        distanceMeters = geodesic.s12,
    )
}

fun calculateQiblaBearing(latitude: Double, longitude: Double): Double {
    return calculateQibla(latitude, longitude).bearingDegrees
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
