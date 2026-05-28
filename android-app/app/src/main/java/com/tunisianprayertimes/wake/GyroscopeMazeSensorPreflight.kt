package com.tunisianprayertimes.wake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlin.math.sqrt

internal enum class GyroscopeMazeSensorState {
    CHECKING,
    READY,
    UNAVAILABLE,
}

@Composable
internal fun rememberGyroscopeMazeSensorState(
    probeKey: Any?,
    timeoutMillis: Long = 1_500L,
): GyroscopeMazeSensorState {
    val context = LocalContext.current
    var state by remember(context, probeKey) {
        mutableStateOf(
            if (hasGyroscopeMazeTiltSensor(context)) {
                GyroscopeMazeSensorState.CHECKING
            } else {
                GyroscopeMazeSensorState.UNAVAILABLE
            },
        )
    }

    DisposableEffect(context, probeKey) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val tiltSensor = sensorManager?.gyroscopeMazeTiltSensor()

        if (sensorManager == null || tiltSensor == null) {
            state = GyroscopeMazeSensorState.UNAVAILABLE
            return@DisposableEffect onDispose { }
        }

        state = GyroscopeMazeSensorState.CHECKING
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.hasUsableTiltValues()) {
                    state = GyroscopeMazeSensorState.READY
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        val registered = sensorManager.registerListener(
            listener,
            tiltSensor,
            SensorManager.SENSOR_DELAY_GAME,
        )
        if (!registered) {
            state = GyroscopeMazeSensorState.UNAVAILABLE
        }

        onDispose {
            if (registered) {
                sensorManager.unregisterListener(listener)
            }
        }
    }

    LaunchedEffect(probeKey, state) {
        if (state == GyroscopeMazeSensorState.CHECKING) {
            delay(timeoutMillis)
            if (state == GyroscopeMazeSensorState.CHECKING) {
                state = GyroscopeMazeSensorState.UNAVAILABLE
            }
        }
    }

    return state
}

internal fun hasGyroscopeMazeTiltSensor(context: Context): Boolean {
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    return sensorManager?.gyroscopeMazeTiltSensor() != null
}

private fun SensorManager.gyroscopeMazeTiltSensor(): Sensor? =
    getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

private fun SensorEvent.hasUsableTiltValues(): Boolean {
    if (values.size < 3) {
        return false
    }

    val x = values[0]
    val y = values[1]
    val z = values[2]
    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
        return false
    }

    val magnitude = sqrt(x * x + y * y + z * z)
    return magnitude in 0.1f..40f
}