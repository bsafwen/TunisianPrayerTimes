package com.tunisianprayertimes

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

private const val KAABA_LATITUDE = 21.422487
private const val KAABA_LONGITUDE = 39.826206

fun calculateQiblaBearing(latitude: Double, longitude: Double): Double {
    val sourceLatitude = Math.toRadians(latitude)
    val kaabaLatitude = Math.toRadians(KAABA_LATITUDE)
    val longitudeDelta = Math.toRadians(KAABA_LONGITUDE - longitude)

    val yComponent = sin(longitudeDelta)
    val xComponent = cos(sourceLatitude) * tan(kaabaLatitude) -
        sin(sourceLatitude) * cos(longitudeDelta)

    return normalizeDegrees(Math.toDegrees(atan2(yComponent, xComponent)))
}

fun normalizeDegrees(degrees: Double): Double {
    return ((degrees % 360.0) + 360.0) % 360.0
}

fun shortestSignedAngleDegrees(currentDegrees: Double, targetDegrees: Double): Double {
    val delta = ((targetDegrees - currentDegrees + 540.0) % 360.0) - 180.0
    return if (delta == -180.0) 180.0 else delta
}