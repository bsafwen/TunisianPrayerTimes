package com.tunisianprayertimes

import android.hardware.SensorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QiblaCompassTest {

    private val expectedFieldMicroTesla = 44f

    @Test
    fun trust_isGoodWithoutWarningSignals() {
        assertEquals(CompassTrust.Good, CompassTrustState().next(sample(), nowMs = 0L).trust)
    }

    @Test
    fun trust_flagsOnlyLowOrUnreliableMagnetometerAccuracy() {
        assertEquals(CompassTrust.NeedsCalibration, trustFor(sample(accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE)))
        assertEquals(CompassTrust.NeedsCalibration, trustFor(sample(accuracy = SensorManager.SENSOR_STATUS_ACCURACY_LOW)))
        assertEquals(CompassTrust.Good, trustFor(sample(accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)))
        assertEquals(CompassTrust.Good, trustFor(sample(accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH)))
        assertEquals(CompassTrust.Good, trustFor(sample(accuracy = null)))
    }

    @Test
    fun trust_headingAccuracyWarningUsesHysteresis() {
        var state = CompassTrustState().next(sample(headingAccuracy = 25f), 0L)
        assertEquals(CompassTrust.Good, state.trust)
        state = state.next(sample(headingAccuracy = 35f), 100L)
        assertEquals(CompassTrust.NeedsCalibration, state.trust)
        state = state.next(sample(headingAccuracy = 25f), 200L)
        assertEquals(CompassTrust.NeedsCalibration, state.trust)
        state = state.next(sample(headingAccuracy = 15f), 300L)
        assertEquals(CompassTrust.Good, state.trust)
    }

    @Test
    fun trust_fieldDisturbanceMustLastBeforeItIsFlagged() {
        var state = CompassTrustState().next(sample(field = 60f), 0L)
        assertEquals(CompassTrust.Good, state.trust)
        state = state.next(sample(field = 60f), 999L)
        assertEquals(CompassTrust.Good, state.trust)
        state = state.next(sample(field = 60f), 1_000L)
        assertEquals(CompassTrust.Interference, state.trust)
    }

    @Test
    fun trust_briefFieldSpikeIsIgnored() {
        var state = CompassTrustState().next(sample(field = 60f), 0L)
        state = state.next(sample(field = 45f), 500L)
        state = state.next(sample(field = 60f), 1_200L)
        assertEquals(CompassTrust.Good, state.trust)
    }

    @Test
    fun trust_fieldDisturbanceClearsOnlyInsideTighterBand() {
        var state = CompassTrustState().next(sample(field = 60f), 0L)
        state = state.next(sample(field = 60f), 1_000L)
        assertEquals(CompassTrust.Interference, state.trust)
        state = state.next(sample(field = 52f), 1_100L) // 18% off the expected field
        assertEquals(CompassTrust.Interference, state.trust)
        state = state.next(sample(field = 48f), 1_200L) // 9% off
        assertEquals(CompassTrust.Good, state.trust)
    }

    @Test
    fun trust_interferenceOutranksCalibration() {
        val lowAccuracyNearMagnet = sample(accuracy = SensorManager.SENSOR_STATUS_ACCURACY_LOW, field = 20f)
        var state = CompassTrustState().next(lowAccuracyNearMagnet, 0L)
        assertEquals(CompassTrust.NeedsCalibration, state.trust)
        state = state.next(lowAccuracyNearMagnet, 1_500L)
        assertEquals(CompassTrust.Interference, state.trust)
    }

    @Test
    fun trust_skipsFieldCheckWithoutExpectedStrength() {
        var state = CompassTrustState().next(sample(field = 90f, expected = null), 0L)
        state = state.next(sample(field = 90f, expected = null), 5_000L)
        assertEquals(CompassTrust.Good, state.trust)
    }

    @Test
    fun headingAccuracy_readsRotationVectorFifthValue() {
        assertNull(headingAccuracyDegreesFromRotationVector(floatArrayOf(0f, 0f, 0f, 1f)))
        assertNull(headingAccuracyDegreesFromRotationVector(floatArrayOf(0f, 0f, 0f, 1f, -1f)))
        assertNull(headingAccuracyDegreesFromRotationVector(floatArrayOf(0f, 0f, 0f, 1f, 0f)))
        val thirtyDegrees = (Math.PI / 6).toFloat()
        assertEquals(30f, headingAccuracyDegreesFromRotationVector(floatArrayOf(0f, 0f, 0f, 1f, thirtyDegrees))!!, 1e-3f)
    }

    @Test
    fun screenTilt_isZeroFlatAndNinetyUpright() {
        val flatFaceUp = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertEquals(0f, screenTiltDegrees(flatFaceUp), 1e-3f)
        // Upright in portrait: the phone's top points at the sky and the screen faces the user.
        val upright = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        assertEquals(90f, screenTiltDegrees(upright), 1e-3f)
        val flatFaceDown = floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, -1f)
        assertEquals(180f, screenTiltDegrees(flatFaceDown), 1e-3f)
    }

    @Test
    fun tiltWarning_usesHysteresis() {
        assertFalse(isPhoneTooTilted(55f, wasTooTilted = false))
        assertTrue(isPhoneTooTilted(65f, wasTooTilted = false))
        assertTrue(isPhoneTooTilted(55f, wasTooTilted = true))
        assertFalse(isPhoneTooTilted(45f, wasTooTilted = true))
    }

    private fun trustFor(sample: CompassTrustSample): CompassTrust {
        return CompassTrustState().next(sample, nowMs = 0L).trust
    }

    private fun sample(
        accuracy: Int? = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
        headingAccuracy: Float? = null,
        field: Float? = expectedFieldMicroTesla,
        expected: Float? = expectedFieldMicroTesla,
    ) = CompassTrustSample(
        magnetometerAccuracy = accuracy,
        headingAccuracyDegrees = headingAccuracy,
        fieldStrengthMicroTesla = field,
        expectedFieldStrengthMicroTesla = expected,
    )
}
