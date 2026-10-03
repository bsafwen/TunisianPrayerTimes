package com.tunisianprayertimes

import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.acos

/** How far qibla guidance can rely on the compass heading. */
enum class CompassTrust {
    Good,
    NeedsCalibration,
    Interference,
}

// Heading accuracy estimated by the rotation vector sensor (event.values[4]).
internal const val HEADING_ACCURACY_WARNING_DEGREES = 30f
internal const val HEADING_ACCURACY_CLEAR_DEGREES = 20f

// Measured field strength compared with the World Magnetic Model at the location.
internal const val FIELD_DEVIATION_WARNING_RATIO = 0.25f
internal const val FIELD_DEVIATION_CLEAR_RATIO = 0.15f
internal const val FIELD_DISTURBANCE_CONFIRM_MS = 1_000L

// Screen tilt from horizontal beyond which the heading stops being usable.
internal const val TILT_WARNING_DEGREES = 60f
internal const val TILT_CLEAR_DEGREES = 50f

data class CompassTrustSample(
    /** Latest SensorManager.SENSOR_STATUS_* of the magnetometer, or null before one is reported. */
    val magnetometerAccuracy: Int?,
    /** Heading accuracy estimated by the rotation vector sensor, or null when it reports none. */
    val headingAccuracyDegrees: Float?,
    /** Magnitude of the measured magnetic field. */
    val fieldStrengthMicroTesla: Float?,
    /** Field magnitude the World Magnetic Model expects at the current location. */
    val expectedFieldStrengthMicroTesla: Float?,
)

/** Hysteresis state behind [CompassTrust]; feed every sensor update through [next]. */
data class CompassTrustState(
    val trust: CompassTrust = CompassTrust.Good,
    val headingAccuracyPoor: Boolean = false,
    val fieldDisturbed: Boolean = false,
    val fieldDeviationSinceMs: Long? = null,
) {
    fun next(sample: CompassTrustSample, nowMs: Long): CompassTrustState {
        val headingAccuracyLimit = if (headingAccuracyPoor) {
            HEADING_ACCURACY_CLEAR_DEGREES
        } else {
            HEADING_ACCURACY_WARNING_DEGREES
        }
        val nextHeadingAccuracyPoor = sample.headingAccuracyDegrees
            ?.let { accuracyDegrees -> accuracyDegrees > headingAccuracyLimit }
            ?: false

        val deviation = fieldStrengthDeviationRatio(
            measuredMicroTesla = sample.fieldStrengthMicroTesla,
            expectedMicroTesla = sample.expectedFieldStrengthMicroTesla,
        )
        val nextFieldDisturbed: Boolean
        val nextFieldDeviationSinceMs: Long?
        when {
            deviation == null -> {
                nextFieldDisturbed = false
                nextFieldDeviationSinceMs = null
            }

            fieldDisturbed -> {
                nextFieldDisturbed = deviation > FIELD_DEVIATION_CLEAR_RATIO
                nextFieldDeviationSinceMs = if (nextFieldDisturbed) fieldDeviationSinceMs else null
            }

            deviation > FIELD_DEVIATION_WARNING_RATIO -> {
                // A passing spike (a car, a speaker) should not raise the warning.
                val deviationSinceMs = fieldDeviationSinceMs ?: nowMs
                nextFieldDisturbed = nowMs - deviationSinceMs >= FIELD_DISTURBANCE_CONFIRM_MS
                nextFieldDeviationSinceMs = deviationSinceMs
            }

            else -> {
                nextFieldDisturbed = false
                nextFieldDeviationSinceMs = null
            }
        }

        val nextTrust = when {
            nextFieldDisturbed -> CompassTrust.Interference
            isMagnetometerUncalibrated(sample.magnetometerAccuracy) || nextHeadingAccuracyPoor ->
                CompassTrust.NeedsCalibration
            else -> CompassTrust.Good
        }
        return CompassTrustState(
            trust = nextTrust,
            headingAccuracyPoor = nextHeadingAccuracyPoor,
            fieldDisturbed = nextFieldDisturbed,
            fieldDeviationSinceMs = nextFieldDeviationSinceMs,
        )
    }
}

/** MEDIUM is a normal resting state for many magnetometers, so only LOW and UNRELIABLE count. */
internal fun isMagnetometerUncalibrated(accuracy: Int?): Boolean {
    return accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
        accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
}

internal fun fieldStrengthDeviationRatio(measuredMicroTesla: Float?, expectedMicroTesla: Float?): Float? {
    if (measuredMicroTesla == null || expectedMicroTesla == null) return null
    if (!measuredMicroTesla.isFinite() || !expectedMicroTesla.isFinite() || expectedMicroTesla <= 0f) return null
    return abs(measuredMicroTesla / expectedMicroTesla - 1f)
}

/** The rotation vector's values[4] is its estimated heading accuracy in radians, or -1 when unknown. */
fun headingAccuracyDegreesFromRotationVector(values: FloatArray): Float? {
    val accuracyRadians = values.getOrNull(4) ?: return null
    if (!accuracyRadians.isFinite() || accuracyRadians <= 0f) return null
    return Math.toDegrees(accuracyRadians.toDouble()).toFloat()
}

/** Angle between the screen and the horizontal: 0° lying flat face up, 90° upright. */
fun screenTiltDegrees(rotationMatrix: FloatArray): Float {
    // rotationMatrix[8] is the vertical component of the axis pointing out of the screen.
    val verticalComponent = rotationMatrix[8].coerceIn(-1f, 1f)
    return Math.toDegrees(acos(verticalComponent.toDouble())).toFloat()
}

/** Tilt check with hysteresis, so the warning does not flicker around the threshold. */
fun isPhoneTooTilted(tiltDegrees: Float, wasTooTilted: Boolean): Boolean {
    return tiltDegrees > if (wasTooTilted) TILT_CLEAR_DEGREES else TILT_WARNING_DEGREES
}
