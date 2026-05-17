package com.tunisianprayertimes.ui

import android.Manifest
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tunisianprayertimes.DelegationLocator
import com.tunisianprayertimes.R
import com.tunisianprayertimes.calculateQiblaBearing
import com.tunisianprayertimes.normalizeDegrees
import com.tunisianprayertimes.shortestSignedAngleDegrees
import com.tunisianprayertimes.ui.theme.CardBorder
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val QIBLA_ALIGNMENT_THRESHOLD_DEGREES = 5.0
private const val QIBLA_HEADING_SMOOTHING_ALPHA = 0.18f
private const val QIBLA_TEXT_UPDATE_INTERVAL_MS = 250L
private const val QIBLA_STABILITY_WINDOW_MS = 2_000L
private const val QIBLA_STABILITY_DELTA_DEGREES = 3.0
private const val QIBLA_UNSTABLE_MESSAGE_MS = 800L
private const val MIN_NORMAL_MAGNETIC_FIELD_MICROTESLA = 25f
private const val MAX_NORMAL_MAGNETIC_FIELD_MICROTESLA = 65f

private data class QiblaLocationState(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val accuracyMeters: Float?,
)

private data class CompassState(
    val headingDegrees: Float?,
    val accuracy: Int,
    val magneticFieldStrengthMicroTesla: Float?,
    val hasCompass: Boolean,
)

private enum class QiblaStabilityStatus {
    Idle,
    Settling,
    Unstable,
    Stable,
}

@Composable
fun QiblaCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeLocation by remember { mutableStateOf<QiblaLocationState?>(null) }
    var qiblaRequested by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }

    fun detectCurrentLocation() {
        if (locating) return

        qiblaRequested = true
        locating = true
        scope.launch {
            val location = DelegationLocator.detectCurrentLocation(context)
            locating = false

            if (location == null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.qibla_location_unavailable),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            activeLocation = QiblaLocationState(
                latitude = location.latitude,
                longitude = location.longitude,
                altitudeMeters = if (location.hasAltitude()) location.altitude else 0.0,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            )
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            detectCurrentLocation()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.location_permission_denied),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val compassState = rememberCompassState(enabled = qiblaRequested)
    val qiblaBearing = remember(activeLocation) {
        activeLocation?.let { location ->
            calculateQiblaBearing(location.latitude, location.longitude)
        }
    }
    val magneticDeclinationDegrees = remember(activeLocation) {
        activeLocation?.let(::magneticDeclinationDegrees) ?: 0f
    }
    val headingDegrees = compassState.headingDegrees?.let { magneticHeadingDegrees ->
        normalizeDegrees(magneticHeadingDegrees.toDouble() + magneticDeclinationDegrees)
    }
    val hasReliableCompassHeading = headingDegrees != null && !compassNeedsCalibration(compassState)
    val liveTurnDegrees = if (qiblaBearing != null && headingDegrees != null) {
        shortestSignedAngleDegrees(headingDegrees, qiblaBearing)
    } else {
        null
    }
    val turnDegrees = if (hasReliableCompassHeading) liveTurnDegrees else null
    val qiblaRotation = liveTurnDegrees ?: qiblaBearing ?: 0.0
    var displayedHeadingDegrees by remember { mutableStateOf<Double?>(null) }
    var displayedTurnDegrees by remember { mutableStateOf<Double?>(null) }
    var lastTextDegreeUpdateMs by remember { mutableStateOf(0L) }
    var stabilityAnchorTurnDegrees by remember { mutableStateOf<Double?>(null) }
    var stabilityAnchorStartedAtMs by remember { mutableStateOf(0L) }
    var qiblaStabilityStatus by remember { mutableStateOf(QiblaStabilityStatus.Idle) }

    LaunchedEffect(qiblaRequested, activeLocation, headingDegrees, turnDegrees) {
        if (!qiblaRequested || activeLocation == null || headingDegrees == null || turnDegrees == null) {
            displayedHeadingDegrees = null
            displayedTurnDegrees = null
            lastTextDegreeUpdateMs = 0L
            return@LaunchedEffect
        }

        val now = SystemClock.elapsedRealtime()
        val textUpdateDue = lastTextDegreeUpdateMs == 0L ||
            now - lastTextDegreeUpdateMs >= QIBLA_TEXT_UPDATE_INTERVAL_MS
        val roundedTextChanged = roundedDegreeChanged(displayedHeadingDegrees, headingDegrees) ||
            roundedDegreeChanged(displayedTurnDegrees, turnDegrees)

        if (textUpdateDue && roundedTextChanged) {
            displayedHeadingDegrees = headingDegrees
            displayedTurnDegrees = turnDegrees
            lastTextDegreeUpdateMs = now
        }
    }

    LaunchedEffect(qiblaRequested, activeLocation, hasReliableCompassHeading, turnDegrees) {
        if (!qiblaRequested || activeLocation == null || turnDegrees == null || !hasReliableCompassHeading) {
            stabilityAnchorTurnDegrees = null
            stabilityAnchorStartedAtMs = 0L
            qiblaStabilityStatus = QiblaStabilityStatus.Idle
            return@LaunchedEffect
        }

        val now = SystemClock.elapsedRealtime()
        val anchorTurnDegrees = stabilityAnchorTurnDegrees
        if (anchorTurnDegrees == null) {
            stabilityAnchorTurnDegrees = turnDegrees
            stabilityAnchorStartedAtMs = now
            qiblaStabilityStatus = QiblaStabilityStatus.Settling
            delay(QIBLA_STABILITY_WINDOW_MS)
            qiblaStabilityStatus = QiblaStabilityStatus.Stable
            return@LaunchedEffect
        }

        val turnDelta = abs(shortestSignedAngleDegrees(anchorTurnDegrees, turnDegrees))
        if (turnDelta > QIBLA_STABILITY_DELTA_DEGREES) {
            stabilityAnchorTurnDegrees = turnDegrees
            stabilityAnchorStartedAtMs = now
            qiblaStabilityStatus = QiblaStabilityStatus.Unstable
            delay(QIBLA_UNSTABLE_MESSAGE_MS)
            qiblaStabilityStatus = QiblaStabilityStatus.Settling
            delay(QIBLA_STABILITY_WINDOW_MS - QIBLA_UNSTABLE_MESSAGE_MS)
            qiblaStabilityStatus = QiblaStabilityStatus.Stable
            return@LaunchedEffect
        }

        val remainingStabilityMs = QIBLA_STABILITY_WINDOW_MS - (now - stabilityAnchorStartedAtMs)
        if (remainingStabilityMs <= 0L) {
            qiblaStabilityStatus = QiblaStabilityStatus.Stable
        } else {
            qiblaStabilityStatus = QiblaStabilityStatus.Settling
            delay(remainingStabilityMs)
            qiblaStabilityStatus = QiblaStabilityStatus.Stable
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.QIBLA_CARD)
            .padding(top = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.qibla_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.qibla_subtitle),
                fontSize = 12.sp,
                color = TextMuted,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))

            QiblaCompassDial(
                rotationDegrees = qiblaRotation.toFloat(),
                displayDegrees = displayedTurnDegrees ?: liveTurnDegrees ?: qiblaBearing,
                hasBearing = qiblaBearing != null,
            )
            Spacer(Modifier.height(12.dp))


            Text(
                text = qiblaDirectionText(
                    compassState = compassState,
                    turnDegrees = displayedTurnDegrees ?: turnDegrees,
                    hasLocation = activeLocation != null,
                    stabilityStatus = qiblaStabilityStatus,
                ),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (
                    qiblaStabilityStatus == QiblaStabilityStatus.Stable &&
                    (displayedTurnDegrees ?: turnDegrees) != null &&
                    abs(displayedTurnDegrees ?: turnDegrees ?: 0.0) <= QIBLA_ALIGNMENT_THRESHOLD_DEGREES
                ) {
                    GreenPrimaryDark
                } else {
                    TextDark
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            compassAccuracyMessage(compassState)?.let { message ->
                Spacer(Modifier.height(6.dp))
                QiblaCalibrationPrompt(message = message)
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QiblaMetricChip(
                    text = when {
                        !qiblaRequested -> stringResource(R.string.qibla_not_computed)
                        qiblaBearing != null -> stringResource(
                            R.string.qibla_bearing,
                            roundedCompassDegree(qiblaBearing).toDouble(),
                        )
                        else -> stringResource(R.string.qibla_location_required)
                    },
                    modifier = Modifier.weight(1f),
                )
                QiblaMetricChip(
                    text = if (!qiblaRequested) {
                        stringResource(R.string.qibla_not_computed)
                    } else {
                        displayedHeadingDegrees?.let { displayedHeading ->
                            stringResource(
                                R.string.qibla_heading,
                                roundedCompassDegree(displayedHeading).toDouble(),
                            )
                        } ?: headingDegrees?.let { liveHeading ->
                            stringResource(
                                R.string.qibla_heading,
                                roundedCompassDegree(liveHeading).toDouble(),
                            )
                        } ?: stringResource(R.string.qibla_compass_waiting)
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = if (activeLocation != null) {
                    stringResource(R.string.qibla_location_current)
                } else {
                    stringResource(R.string.qibla_location_required)
                },
                fontSize = 12.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            activeLocation?.accuracyMeters?.let { accuracyMeters ->
                Text(
                    text = stringResource(R.string.qibla_location_accuracy, accuracyMeters),
                    fontSize = 11.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    if (DelegationLocator.hasLocationPermission(context)) {
                        detectCurrentLocation()
                    } else {
                        locationPermissionLauncher.launch(DelegationLocator.requestedPermissions)
                    }
                },
                enabled = !locating,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GreenPrimary),
                border = BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.5f)),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_location),
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (locating) {
                        stringResource(R.string.qibla_locating)
                    } else {
                        stringResource(R.string.qibla_use_current_location)
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun QiblaCompassDial(rotationDegrees: Float, displayDegrees: Double?, hasBearing: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(220.dp)) {
            val dialRadius = size.minDimension / 2f
            val tickOuterRadius = dialRadius - 12.dp.toPx()
            val majorTickLength = 16.dp.toPx()
            val minorTickLength = 8.dp.toPx()
            val tickStroke = 2.dp.toPx()

            drawCircle(
                color = GoldLight.copy(alpha = 0.42f),
                radius = dialRadius,
                center = center,
            )
            drawCircle(
                color = GreenPrimary.copy(alpha = 0.2f),
                radius = dialRadius - 1.dp.toPx(),
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )

            for (tickIndex in 0 until 36) {
                val tickAngle = Math.toRadians(tickIndex * 10.0 - 90.0)
                val isMajorTick = tickIndex % 3 == 0
                val tickInnerRadius = tickOuterRadius - if (isMajorTick) majorTickLength else minorTickLength
                val start = Offset(
                    x = center.x + cos(tickAngle).toFloat() * tickInnerRadius,
                    y = center.y + sin(tickAngle).toFloat() * tickInnerRadius,
                )
                val end = Offset(
                    x = center.x + cos(tickAngle).toFloat() * tickOuterRadius,
                    y = center.y + sin(tickAngle).toFloat() * tickOuterRadius,
                )
                drawLine(
                    color = if (isMajorTick) GreenPrimary else CardBorder,
                    start = start,
                    end = end,
                    strokeWidth = if (isMajorTick) tickStroke else 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            drawLine(
                color = GreenPrimaryDark.copy(alpha = 0.45f),
                start = Offset(center.x, center.y - dialRadius + 18.dp.toPx()),
                end = Offset(center.x, center.y - dialRadius + 34.dp.toPx()),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )

            rotate(degrees = rotationDegrees, pivot = center) {
                val arrowPath = Path().apply {
                    moveTo(center.x, center.y - dialRadius + 34.dp.toPx())
                    lineTo(center.x - 15.dp.toPx(), center.y - 32.dp.toPx())
                    lineTo(center.x + 15.dp.toPx(), center.y - 32.dp.toPx())
                    close()
                }
                drawLine(
                    color = if (hasBearing) Gold else TextMuted.copy(alpha = 0.35f),
                    start = Offset(center.x, center.y - dialRadius + 50.dp.toPx()),
                    end = Offset(center.x, center.y - 42.dp.toPx()),
                    strokeWidth = 8.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawPath(
                    path = arrowPath,
                    color = if (hasBearing) Gold else TextMuted.copy(alpha = 0.35f),
                )
            }

            drawCircle(
                color = Color.White,
                radius = 42.dp.toPx(),
                center = center,
            )
            drawCircle(
                color = GreenPrimary.copy(alpha = 0.16f),
                radius = 42.dp.toPx(),
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        Box(
            modifier = Modifier
                .size(220.dp)
                .graphicsLayer { rotationZ = rotationDegrees },
        ) {
            Image(
                painter = painterResource(R.drawable.kaaba_marker),
                contentDescription = stringResource(R.string.qibla_kaaba_marker),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .size(38.dp)
                    .graphicsLayer {
                        alpha = if (hasBearing) 1f else 0.35f
                        rotationZ = -rotationDegrees
                    },
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.qibla_title),
                fontSize = 13.sp,
                color = GreenPrimaryDark,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (hasBearing) {
                    "${roundedCompassDegree(displayDegrees ?: rotationDegrees.toDouble())}°"
                } else {
                    "--°"
                },
                fontSize = 12.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun QiblaMetricChip(text: String, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(GoldLight.copy(alpha = 0.25f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = TextDark,
            textAlign = TextAlign.Center,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun QiblaCalibrationPrompt(message: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(GoldLight.copy(alpha = 0.32f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = message,
            fontSize = 12.sp,
            color = TextDark,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun qiblaDirectionText(
    compassState: CompassState,
    turnDegrees: Double?,
    hasLocation: Boolean,
    stabilityStatus: QiblaStabilityStatus,
): String {
    if (!hasLocation) {
        return stringResource(R.string.qibla_location_required)
    }
    if (!compassState.hasCompass) {
        return stringResource(R.string.qibla_compass_unavailable)
    }
    if (turnDegrees == null) {
        if (compassNeedsCalibration(compassState)) {
            return stringResource(R.string.qibla_compass_waiting)
        }
        return stringResource(R.string.qibla_compass_waiting)
    }
    if (stabilityStatus == QiblaStabilityStatus.Unstable) {
        return stringResource(R.string.qibla_compass_unstable)
    }
    if (stabilityStatus != QiblaStabilityStatus.Stable) {
        return stringResource(R.string.qibla_compass_settling)
    }
    val absoluteTurnDegrees = abs(turnDegrees)
    return when {
        absoluteTurnDegrees <= QIBLA_ALIGNMENT_THRESHOLD_DEGREES -> stringResource(R.string.qibla_aligned)
        turnDegrees > 0 -> stringResource(R.string.qibla_turn_right, absoluteTurnDegrees)
        else -> stringResource(R.string.qibla_turn_left, absoluteTurnDegrees)
    }
}

@Composable
private fun compassAccuracyMessage(compassState: CompassState): String? {
    if (!compassState.hasCompass || compassState.headingDegrees == null) return null
    if (compassState.hasAbnormalMagneticField) {
        return stringResource(R.string.qibla_compass_interference)
    }
    return when (compassState.accuracy) {
        SensorManager.SENSOR_STATUS_UNRELIABLE,
        SensorManager.SENSOR_STATUS_ACCURACY_LOW,
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> stringResource(R.string.qibla_compass_calibrate)
        else -> null
    }
}

private fun compassNeedsCalibration(compassState: CompassState): Boolean {
    if (!compassState.hasCompass || compassState.headingDegrees == null) return false
    return compassState.accuracy != SensorManager.SENSOR_STATUS_ACCURACY_HIGH ||
        compassState.hasAbnormalMagneticField
}

private val CompassState.hasAbnormalMagneticField: Boolean
    get() = magneticFieldStrengthMicroTesla?.let { strength ->
        strength < MIN_NORMAL_MAGNETIC_FIELD_MICROTESLA ||
            strength > MAX_NORMAL_MAGNETIC_FIELD_MICROTESLA
    } ?: false

@Composable
private fun rememberCompassState(enabled: Boolean): CompassState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val rotationVectorSensor = remember {
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }
    val geomagneticRotationVectorSensor = remember {
        sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    }
    val accelerometerSensor = remember {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }
    val magneticFieldSensor = remember {
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    }
    val hasMagneticCompass = accelerometerSensor != null && magneticFieldSensor != null
    val fallbackRotationSensor = geomagneticRotationVectorSensor ?: rotationVectorSensor
    val hasCompass = hasMagneticCompass || fallbackRotationSensor != null

    var headingDegrees by remember { mutableStateOf<Float?>(null) }
    var accuracy by remember { mutableIntStateOf(SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) }
    var magneticFieldStrengthMicroTesla by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(enabled, lifecycleOwner, sensorManager, fallbackRotationSensor, accelerometerSensor, magneticFieldSensor) {
        val gravityValues = FloatArray(3)
        val magneticValues = FloatArray(3)
        var hasGravityValues = false
        var hasMagneticValues = false
        var registered = false

        fun publishHeading(candidateHeadingDegrees: Float) {
            val currentHeadingDegrees = headingDegrees
            if (currentHeadingDegrees == null) {
                headingDegrees = candidateHeadingDegrees
                return
            }

            val headingDelta = shortestSignedAngleDegrees(
                currentHeadingDegrees.toDouble(),
                candidateHeadingDegrees.toDouble(),
            ).toFloat()
            headingDegrees = normalizeDegrees(
                (currentHeadingDegrees + headingDelta * QIBLA_HEADING_SMOOTHING_ALPHA).toDouble(),
            ).toFloat()
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        val rotationMatrix = FloatArray(9)
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        publishHeading(azimuthDegreesFromRotationMatrix(context, rotationMatrix))
                        accuracy = event.accuracy
                    }

                    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                        val rotationMatrix = FloatArray(9)
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        publishHeading(azimuthDegreesFromRotationMatrix(context, rotationMatrix))
                        accuracy = event.accuracy
                    }

                    Sensor.TYPE_ACCELEROMETER -> {
                        hasGravityValues = copySmoothedSensorValues(
                            source = event.values,
                            destination = gravityValues,
                            hasPreviousValues = hasGravityValues,
                        )
                    }

                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        magneticFieldStrengthMicroTesla = magneticFieldStrengthMicroTesla(event.values)
                        hasMagneticValues = copySmoothedSensorValues(
                            source = event.values,
                            destination = magneticValues,
                            hasPreviousValues = hasMagneticValues,
                        )
                        accuracy = event.accuracy
                    }
                }

                if (hasMagneticCompass && hasGravityValues && hasMagneticValues) {
                    val rotationMatrix = FloatArray(9)
                    val matrixReady = SensorManager.getRotationMatrix(
                        rotationMatrix,
                        null,
                        gravityValues,
                        magneticValues,
                    )
                    if (matrixReady) {
                        publishHeading(azimuthDegreesFromRotationMatrix(context, rotationMatrix))
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, sensorAccuracy: Int) {
                if (sensor != null && isCompassAccuracySensor(sensor, hasMagneticCompass)) {
                    accuracy = sensorAccuracy
                }
            }
        }

        fun registerSensors() {
            if (registered || !enabled || !hasCompass) return

            if (hasMagneticCompass) {
                accelerometerSensor?.let { sensor ->
                    sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                }
                magneticFieldSensor?.let { sensor ->
                    sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                }
            } else {
                fallbackRotationSensor?.let { sensor ->
                    sensorManager.registerListener(
                        listener,
                        sensor,
                        SensorManager.SENSOR_DELAY_UI,
                    )
                }
            }
            registered = true
        }

        fun unregisterSensors() {
            if (!registered) return
            sensorManager.unregisterListener(listener)
            registered = false
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> registerSensors()
                Lifecycle.Event.ON_PAUSE -> unregisterSensors()
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            registerSensors()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregisterSensors()
        }
    }

    return CompassState(
        headingDegrees = headingDegrees,
        accuracy = accuracy,
        magneticFieldStrengthMicroTesla = magneticFieldStrengthMicroTesla,
        hasCompass = hasCompass,
    )
}

private fun magneticDeclinationDegrees(location: QiblaLocationState): Float {
    return GeomagneticField(
        location.latitude.toFloat(),
        location.longitude.toFloat(),
        location.altitudeMeters.toFloat(),
        System.currentTimeMillis(),
    ).declination
}

private fun isCompassAccuracySensor(sensor: Sensor, hasMagneticCompass: Boolean): Boolean {
    return if (hasMagneticCompass) {
        sensor.type == Sensor.TYPE_MAGNETIC_FIELD
    } else {
        sensor.type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR ||
            sensor.type == Sensor.TYPE_ROTATION_VECTOR
    }
}

private fun magneticFieldStrengthMicroTesla(values: FloatArray): Float {
    return sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
}

private fun roundedDegreeChanged(currentDegrees: Double?, candidateDegrees: Double): Boolean {
    return currentDegrees == null || currentDegrees.roundToInt() != candidateDegrees.roundToInt()
}

private fun roundedCompassDegree(degrees: Double): Int {
    val roundedDegrees = normalizeDegrees(degrees).roundToInt()
    return if (roundedDegrees == 360) 0 else roundedDegrees
}

private fun copySmoothedSensorValues(
    source: FloatArray,
    destination: FloatArray,
    hasPreviousValues: Boolean,
): Boolean {
    for (index in 0 until 3) {
        destination[index] = if (hasPreviousValues) {
            destination[index] + (source[index] - destination[index]) * 0.18f
        } else {
            source[index]
        }
    }
    return true
}

private fun azimuthDegreesFromRotationMatrix(context: Context, rotationMatrix: FloatArray): Float {
    val adjustedMatrix = FloatArray(9)
    val displayRotation = currentDisplayRotation(context)
    val axisPair = when (displayRotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }

    SensorManager.remapCoordinateSystem(
        rotationMatrix,
        axisPair.first,
        axisPair.second,
        adjustedMatrix,
    )

    val orientationValues = FloatArray(3)
    SensorManager.getOrientation(adjustedMatrix, orientationValues)
    return normalizeDegrees(Math.toDegrees(orientationValues[0].toDouble())).toFloat()
}

private fun currentDisplayRotation(context: Context): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }
}