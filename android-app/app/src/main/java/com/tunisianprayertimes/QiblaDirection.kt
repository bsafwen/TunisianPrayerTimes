package com.tunisianprayertimes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

private const val KAABA_LATITUDE = 21.422487
private const val KAABA_LONGITUDE = 39.826206

// WGS84, the datum of GPS fixes and of the delegation coordinates.
private const val WGS84_FLATTENING = 1.0 / 298.257223563
private const val VINCENTY_MAX_ITERATIONS = 200
private const val VINCENTY_CONVERGENCE_RADIANS = 1e-12

/**
 * Qibla direction in degrees clockwise from true north: the initial bearing of the
 * shortest path to the Kaaba on the WGS84 ellipsoid. The spherical great circle is
 * used only where the ellipsoidal solution does not converge, which happens within
 * about 70 km of the Kaaba's antipode in the South Pacific.
 */
fun calculateQiblaBearing(latitude: Double, longitude: Double): Double {
    return ellipsoidalInitialBearingDegrees(latitude, longitude, KAABA_LATITUDE, KAABA_LONGITUDE)
        ?: sphericalQiblaBearing(latitude, longitude)
}

/** Initial great-circle bearing to the Kaaba on a spherical Earth. */
internal fun sphericalQiblaBearing(latitude: Double, longitude: Double): Double {
    val sourceLatitude = Math.toRadians(latitude)
    val kaabaLatitude = Math.toRadians(KAABA_LATITUDE)
    val longitudeDelta = Math.toRadians(KAABA_LONGITUDE - longitude)

    val yComponent = sin(longitudeDelta)
    val xComponent = cos(sourceLatitude) * tan(kaabaLatitude) -
        sin(sourceLatitude) * cos(longitudeDelta)

    return normalizeDegrees(Math.toDegrees(atan2(yComponent, xComponent)))
}

/**
 * Forward azimuth from Vincenty's inverse solution on the WGS84 ellipsoid, in
 * degrees clockwise from true north. Returns null when the iteration does not
 * converge, which only happens for nearly antipodal points.
 */
internal fun ellipsoidalInitialBearingDegrees(
    fromLatitude: Double,
    fromLongitude: Double,
    toLatitude: Double,
    toLongitude: Double,
): Double? {
    val f = WGS84_FLATTENING
    val fromLatitudeRadians = Math.toRadians(fromLatitude)
    val toLatitudeRadians = Math.toRadians(toLatitude)
    val longitudeDelta = Math.toRadians(shortestSignedAngleDegrees(fromLongitude, toLongitude))
    val reducedFromLatitude = atan((1 - f) * tan(fromLatitudeRadians))
    val reducedToLatitude = atan((1 - f) * tan(toLatitudeRadians))
    val sinU1 = sin(reducedFromLatitude)
    val cosU1 = cos(reducedFromLatitude)
    val sinU2 = sin(reducedToLatitude)
    val cosU2 = cos(reducedToLatitude)
    val nearlyAntipodal = abs(longitudeDelta) > PI / 2 ||
        abs(toLatitudeRadians - fromLatitudeRadians) > PI / 2

    var lambda = longitudeDelta
    repeat(VINCENTY_MAX_ITERATIONS) {
        val sinLambda = sin(lambda)
        val cosLambda = cos(lambda)
        val eastTerm = cosU2 * sinLambda
        val northTerm = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda
        val sinSigma = sqrt(eastTerm * eastTerm + northTerm * northTerm)
        if (sinSigma == 0.0) {
            return 0.0 // Coincident points: every direction is equally valid.
        }
        val cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda
        val sigma = atan2(sinSigma, cosSigma)
        val sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma
        val cosSquaredAlpha = 1 - sinAlpha * sinAlpha
        val cos2SigmaM = if (cosSquaredAlpha != 0.0) {
            cosSigma - 2 * sinU1 * sinU2 / cosSquaredAlpha
        } else {
            0.0 // Both points on the equator.
        }
        val c = f / 16 * cosSquaredAlpha * (4 + f * (4 - 3 * cosSquaredAlpha))
        val previousLambda = lambda
        lambda = longitudeDelta + (1 - c) * f * sinAlpha *
            (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)))

        val divergence = if (nearlyAntipodal) abs(lambda) - PI else abs(lambda)
        if (divergence > PI) {
            return null
        }
        if (abs(lambda - previousLambda) < VINCENTY_CONVERGENCE_RADIANS) {
            val azimuth = atan2(
                cosU2 * sin(lambda),
                cosU1 * sinU2 - sinU1 * cosU2 * cos(lambda),
            )
            return normalizeDegrees(Math.toDegrees(azimuth))
        }
    }
    return null
}

fun normalizeDegrees(degrees: Double): Double {
    return ((degrees % 360.0) + 360.0) % 360.0
}

fun shortestSignedAngleDegrees(currentDegrees: Double, targetDegrees: Double): Double {
    val delta = ((((targetDegrees - currentDegrees) % 360.0) + 540.0) % 360.0) - 180.0
    return if (delta == -180.0) 180.0 else delta
}
