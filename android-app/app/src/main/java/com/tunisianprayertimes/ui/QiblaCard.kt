package com.tunisianprayertimes.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.calculateQiblaBearing
import com.tunisianprayertimes.normalizeDegrees
import com.tunisianprayertimes.shortestSignedAngleDegrees
import com.tunisianprayertimes.ui.theme.CardBorder
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val QIBLA_HEADING_SMOOTHING_ALPHA = 0.18f
private const val QIBLA_TEXT_UPDATE_INTERVAL_MS = 750L
private const val QIBLA_STATUS_MIN_DISPLAY_MS = 1_600L
private const val QIBLA_WARNING_DISMISS_DELAY_MS = 3_500L
private const val QIBLA_STABILITY_WINDOW_MS = 2_000L
private const val QIBLA_STABILITY_DELTA_DEGREES = 3.0
private const val QIBLA_UNSTABLE_MESSAGE_MS = 800L
private const val QIBLA_VISIBLE_ALIGNMENT_DEGREES = 1.0

private val QIBLA_CARDINAL_LABELS = listOf(
    "شمال" to 0.0,
    "شرق" to 90.0,
    "جنوب" to 180.0,
    "غرب" to 270.0,
)

private data class QiblaLocationState(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val accuracyMeters: Float?,
)

private var cachedRealtimeQiblaLocation: QiblaLocationState? = null
private var qiblaLocationPermissionRequestedThisSession = false

private data class CompassState(
    val headingDegrees: Float?,
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
    val cachedLocation = remember { cachedRealtimeQiblaLocation }
    var locating by remember { mutableStateOf(false) }
    var locationPermissionGranted by remember { mutableStateOf(DelegationLocator.hasLocationPermission(context)) }
    var locationPermissionDeniedThisSession by rememberSaveable { mutableStateOf(false) }
    var currentLatitude by rememberSaveable { mutableStateOf(cachedLocation?.latitude) }
    var currentLongitude by rememberSaveable { mutableStateOf(cachedLocation?.longitude) }
    var currentAltitudeMeters by rememberSaveable { mutableStateOf(cachedLocation?.altitudeMeters ?: 0.0) }
    var currentAccuracyMeters by rememberSaveable { mutableStateOf(cachedLocation?.accuracyMeters) }

    val currentLocation = remember(
        currentLatitude,
        currentLongitude,
        currentAltitudeMeters,
        currentAccuracyMeters,
    ) {
        val latitude = currentLatitude
        val longitude = currentLongitude
        if (latitude != null && longitude != null) {
            QiblaLocationState(
                latitude = latitude,
                longitude = longitude,
                altitudeMeters = currentAltitudeMeters,
                accuracyMeters = currentAccuracyMeters,
            )
        } else {
            null
        }
    }
    val activeLocation = if (locationPermissionGranted) currentLocation else null

    LaunchedEffect(activeLocation) {
        activeLocation?.let { location ->
            cachedRealtimeQiblaLocation = location
        }
    }

    fun detectCurrentLocation() {
        if (locating) {
            return
        }

        locating = true
        scope.launch {
            val location = DelegationLocator.detectCurrentLocation(context)
            locating = false

            if (location == null) {
                AnalyticsTracker.qiblaComputeResult(
                    context = context,
                    source = "current_location",
                    result = "location_unavailable",
                    hasSensorSupport = hasQiblaSensorSupport(context),
                )
                Toast.makeText(
                    context,
                    context.getString(R.string.qibla_location_unavailable),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            currentLatitude = location.latitude
            currentLongitude = location.longitude
            currentAltitudeMeters = if (location.hasAltitude()) location.altitude else 0.0
            currentAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null
            AnalyticsTracker.qiblaComputeResult(
                context = context,
                source = "current_location",
                result = "success",
                hasSensorSupport = hasQiblaSensorSupport(context),
            )
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        locationPermissionGranted = granted
        if (granted) {
            locationPermissionDeniedThisSession = false
            AnalyticsTracker.permissionStepResult(
                context = context,
                permissionType = "location",
                result = "granted",
                entryPoint = "qibla",
            )
            detectCurrentLocation()
        } else {
            locationPermissionDeniedThisSession = true
            AnalyticsTracker.permissionStepResult(
                context = context,
                permissionType = "location",
                result = "denied",
                entryPoint = "qibla",
            )
            AnalyticsTracker.qiblaComputeResult(
                context = context,
                source = "current_location",
                result = "permission_denied",
                hasSensorSupport = hasQiblaSensorSupport(context),
            )
            Toast.makeText(
                context,
                context.getString(R.string.location_permission_denied),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun requestQiblaLocationPermission(fromWarning: Boolean = false) {
        val activity = context.findActivity()
        val shouldOpenSettings = fromWarning &&
            locationPermissionDeniedThisSession &&
            activity != null &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)

        qiblaLocationPermissionRequestedThisSession = true
        AnalyticsTracker.permissionStepResult(
            context = context,
            permissionType = "location",
            result = "request_opened",
            entryPoint = "qibla",
        )
        if (shouldOpenSettings) {
            context.openAppPermissionSettings()
        } else {
            locationPermissionLauncher.launch(DelegationLocator.requestedPermissions)
        }
    }

    LaunchedEffect(Unit) {
        if (locationPermissionGranted) {
            detectCurrentLocation()
        } else if (!qiblaLocationPermissionRequestedThisSession) {
            requestQiblaLocationPermission()
        }
    }

    val compassState = rememberCompassState(enabled = activeLocation != null)
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
    val liveTurnDegrees = if (qiblaBearing != null && headingDegrees != null) {
        shortestSignedAngleDegrees(headingDegrees, qiblaBearing)
    } else {
        null
    }
    val turnDegrees = liveTurnDegrees
    val hasLiveGuidance = turnDegrees != null
    var displayedHeadingDegrees by remember { mutableStateOf<Double?>(null) }
    var displayedTurnDegrees by remember { mutableStateOf<Double?>(null) }
    var lastTextDegreeUpdateMs by remember { mutableStateOf(0L) }
    var stabilityAnchorTurnDegrees by remember { mutableStateOf<Double?>(null) }
    var stabilityAnchorStartedAtMs by remember { mutableStateOf(0L) }
    var qiblaStabilityStatus by remember { mutableStateOf(QiblaStabilityStatus.Idle) }
    val displayedQiblaBearingDegrees = qiblaBearing?.let(::roundedCompassDegree)
    val displayedSignedTurnDegrees = if (turnDegrees != null) {
        roundedSignedTurnDegree(displayedTurnDegrees ?: turnDegrees)
    } else {
        null
    }
    val displayedHeadingCompassDegrees = if (activeLocation != null) {
        if (displayedQiblaBearingDegrees != null && displayedSignedTurnDegrees != null) {
            roundedCompassDegree(displayedQiblaBearingDegrees - displayedSignedTurnDegrees.toDouble())
        } else {
            displayedHeadingDegrees?.let(::roundedCompassDegree) ?: headingDegrees?.let(::roundedCompassDegree)
        }
    } else {
        null
    }
    val visibleTurnDegrees = if (displayedSignedTurnDegrees != null) {
        displayedSignedTurnDegrees.toDouble()
    } else {
        null
    }
    val visualTurnDegrees = turnDegrees ?: visibleTurnDegrees
    val qiblaRotation = visualTurnDegrees ?: 0.0
    val isQiblaAligned = qiblaStabilityStatus == QiblaStabilityStatus.Stable &&
        isExactVisibleQiblaDirection(visibleTurnDegrees)
    val rawDirectionText = qiblaDirectionText(
        compassState = compassState,
        turnDegrees = visibleTurnDegrees,
        hasLocation = activeLocation != null,
        stabilityStatus = qiblaStabilityStatus,
    )
    var displayedDirectionText by remember { mutableStateOf<String?>(null) }
    var directionTextShownAtMs by remember { mutableStateOf(0L) }
    val rawCalibrationMessage = if (!locationPermissionGranted) {
        stringResource(R.string.qibla_location_permission_required)
    } else {
        compassAccuracyMessage(compassState)
    }
    var displayedCalibrationMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(activeLocation, headingDegrees, turnDegrees) {
        if (activeLocation == null || headingDegrees == null || turnDegrees == null) {
            displayedHeadingDegrees = null
            displayedTurnDegrees = null
            lastTextDegreeUpdateMs = 0L
            return@LaunchedEffect
        }

        val now = SystemClock.elapsedRealtime()
        val textUpdateDue = lastTextDegreeUpdateMs == 0L ||
            now - lastTextDegreeUpdateMs >= QIBLA_TEXT_UPDATE_INTERVAL_MS
        val roundedTextChanged = roundedCompassDegreeChanged(displayedHeadingDegrees, headingDegrees) ||
            roundedTurnDegreeChanged(displayedTurnDegrees, turnDegrees)

        if (textUpdateDue && roundedTextChanged) {
            displayedHeadingDegrees = headingDegrees
            displayedTurnDegrees = turnDegrees
            lastTextDegreeUpdateMs = now
        }
    }

    LaunchedEffect(rawDirectionText) {
        val now = SystemClock.elapsedRealtime()
        if (displayedDirectionText == null) {
            displayedDirectionText = rawDirectionText
            directionTextShownAtMs = now
            return@LaunchedEffect
        }

        val remainingDisplayMs = QIBLA_STATUS_MIN_DISPLAY_MS - (now - directionTextShownAtMs)
        if (remainingDisplayMs > 0L) {
            delay(remainingDisplayMs)
        }
        displayedDirectionText = rawDirectionText
        directionTextShownAtMs = SystemClock.elapsedRealtime()
    }

    LaunchedEffect(rawCalibrationMessage) {
        if (rawCalibrationMessage != null) {
            displayedCalibrationMessage = rawCalibrationMessage
        } else {
            delay(QIBLA_WARNING_DISMISS_DELAY_MS)
            displayedCalibrationMessage = null
        }
    }

    LaunchedEffect(activeLocation, turnDegrees) {
        if (activeLocation == null || turnDegrees == null) {
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            QiblaCompassDial(
                rotationDegrees = qiblaRotation.toFloat(),
                displayDegrees = visibleTurnDegrees,
                phoneHeadingDegrees = headingDegrees?.toFloat(),
                hasBearing = qiblaBearing != null,
                hasGuidance = hasLiveGuidance,
                isAligned = isQiblaAligned,
            )
            Spacer(Modifier.height(14.dp))

            Text(
                text = displayedDirectionText ?: rawDirectionText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (isQiblaAligned) {
                    GreenPrimaryDark
                } else {
                    TextDark
                },
                textAlign = TextAlign.Center,
                lineHeight = 23.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 38.dp),
            )

            Spacer(Modifier.height(8.dp))
            QiblaGuidanceBar(
                message = displayedCalibrationMessage,
                onClick = if (!locationPermissionGranted) {
                    { requestQiblaLocationPermission(fromWarning = true) }
                } else {
                    null
                },
            )

            Spacer(Modifier.height(12.dp))

            QiblaDirectionDetailsStrip(
                title = stringResource(R.string.qibla_direction_details),
                bearingLabel = stringResource(R.string.qibla_bearing_label),
                bearingValue = displayedQiblaBearingDegrees?.let { bearingDegrees ->
                    stringResource(
                        R.string.qibla_degrees_value,
                        bearingDegrees.toDouble(),
                    )
                } ?: "--°",
                headingLabel = stringResource(R.string.qibla_heading_label),
                headingValue = displayedHeadingCompassDegrees?.let { headingDegrees ->
                    stringResource(
                        R.string.qibla_degrees_value,
                        headingDegrees.toDouble(),
                    )
                } ?: "--°",
                locationText = if (activeLocation != null) {
                    activeLocation.accuracyMeters?.let { accuracyMeters ->
                        stringResource(R.string.qibla_location_current_with_accuracy, accuracyMeters)
                    } ?: stringResource(R.string.qibla_location_current)
                } else {
                    stringResource(R.string.qibla_location_required)
                },
            )
        }
    }
}

@Composable
private fun QiblaCompassDial(
    rotationDegrees: Float,
    displayDegrees: Double?,
    phoneHeadingDegrees: Float?,
    hasBearing: Boolean,
    hasGuidance: Boolean,
    isAligned: Boolean,
) {
    val alignmentProgress by animateFloatAsState(
        targetValue = if (isAligned) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "qiblaAlignmentRing",
    )

    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(260.dp)) {
            val dialRadius = size.minDimension / 2f
            val tickOuterRadius = dialRadius - 14.dp.toPx()
            val majorTickLength = 18.dp.toPx()
            val minorTickLength = 8.dp.toPx()
            val tickStroke = 2.dp.toPx()
            val cardinalRadius = dialRadius - 45.dp.toPx()
            val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TextMuted.copy(alpha = if (hasGuidance) 0.78f else 0.34f).toArgb()
                textAlign = Paint.Align.CENTER
                textSize = 11.dp.toPx()
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val cardinalBaselineOffset = -(cardinalPaint.ascent() + cardinalPaint.descent()) / 2f

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

            if (alignmentProgress > 0f) {
                drawCircle(
                    color = GreenPrimaryDark.copy(alpha = 0.18f + alignmentProgress * 0.46f),
                    radius = dialRadius - 5.dp.toPx(),
                    center = center,
                    style = Stroke(width = (2.dp + 5.dp * alignmentProgress).toPx()),
                )
            }

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

            phoneHeadingDegrees?.let { headingDegrees ->
                QIBLA_CARDINAL_LABELS.forEach { (label, bearingDegrees) ->
                    val relativeDegrees = normalizeDegrees(bearingDegrees - headingDegrees)
                    val labelAngle = Math.toRadians(relativeDegrees - 90.0)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        center.x + cos(labelAngle).toFloat() * cardinalRadius,
                        center.y + sin(labelAngle).toFloat() * cardinalRadius + cardinalBaselineOffset,
                        cardinalPaint,
                    )
                }
            }

            drawLine(
                color = GreenPrimaryDark.copy(alpha = 0.45f),
                start = Offset(center.x, center.y - dialRadius + 20.dp.toPx()),
                end = Offset(center.x, center.y - dialRadius + 38.dp.toPx()),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )

            val markerColor = if (hasGuidance) Gold else TextMuted.copy(alpha = 0.35f)
            rotate(degrees = rotationDegrees, pivot = center) {
                val arrowPath = Path().apply {
                    moveTo(center.x, center.y - dialRadius + 38.dp.toPx())
                    lineTo(center.x - 17.dp.toPx(), center.y - 34.dp.toPx())
                    lineTo(center.x + 17.dp.toPx(), center.y - 34.dp.toPx())
                    close()
                }
                drawLine(
                    color = markerColor,
                    start = Offset(center.x, center.y - dialRadius + 56.dp.toPx()),
                    end = Offset(center.x, center.y - 46.dp.toPx()),
                    strokeWidth = 8.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawPath(
                    path = arrowPath,
                    color = markerColor,
                )
            }

            drawCircle(
                color = Color.White,
                radius = 46.dp.toPx(),
                center = center,
            )
            drawCircle(
                color = GreenPrimary.copy(alpha = 0.16f),
                radius = 46.dp.toPx(),
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        Box(
            modifier = Modifier
                .size(260.dp)
                .graphicsLayer { rotationZ = rotationDegrees },
        ) {
            Image(
                painter = painterResource(R.drawable.kaaba_marker),
                contentDescription = stringResource(R.string.qibla_kaaba_marker),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 9.dp)
                    .size(44.dp)
                    .graphicsLayer {
                        alpha = if (hasGuidance) 1f else 0.35f
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
                text = if (hasBearing && hasGuidance) {
                    "${visibleTurnAmountDegrees(displayDegrees ?: rotationDegrees.toDouble())}°"
                } else {
                    "--°"
                },
                fontSize = 16.sp,
                color = if (hasGuidance) GreenPrimaryDark else TextMuted,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun QiblaDirectionDetailsStrip(
    title: String,
    bearingLabel: String,
    bearingValue: String,
    headingLabel: String,
    headingValue: String,
    locationText: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(CardBorder.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            fontSize = 11.sp,
            color = TextMuted,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            QiblaDirectionDetailValue(
                label = bearingLabel,
                value = bearingValue,
                modifier = Modifier.weight(1f),
            )
            QiblaDirectionDetailValue(
                label = headingLabel,
                value = headingValue,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = locationText,
            fontSize = 10.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun QiblaDirectionDetailValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 18.sp,
            color = GreenPrimaryDark,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun QiblaGuidanceBar(message: String?, onClick: (() -> Unit)? = null) {
    val hasMessage = !message.isNullOrBlank()
    val shape = RoundedCornerShape(10.dp)
    val clickableModifier = if (hasMessage && onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (hasMessage) {
                    Modifier
                        .background(GoldLight.copy(alpha = 0.18f))
                        .border(1.dp, Gold.copy(alpha = 0.22f), shape)
                } else {
                    Modifier
                },
            )
            .then(clickableModifier)
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = message.orEmpty(),
            fontSize = 12.sp,
            color = if (hasMessage) TextDark else Color.Transparent,
            fontWeight = FontWeight.SemiBold,
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
        return stringResource(R.string.qibla_compass_waiting)
    }
    val roundedTurnDegrees = visibleTurnAmountDegrees(turnDegrees)
    if (roundedTurnDegrees == 0) {
        return when {
            stabilityStatus == QiblaStabilityStatus.Unstable -> stringResource(R.string.qibla_compass_unstable)
            stabilityStatus != QiblaStabilityStatus.Stable -> stringResource(R.string.qibla_compass_settling)
            else -> stringResource(R.string.qibla_aligned)
        }
    }
    return when {
        turnDegrees > 0 -> stringResource(R.string.qibla_turn_right, roundedTurnDegrees.toDouble())
        else -> stringResource(R.string.qibla_turn_left, roundedTurnDegrees.toDouble())
    }
}

private fun isExactVisibleQiblaDirection(turnDegrees: Double?): Boolean {
    return turnDegrees != null && visibleTurnAmountDegrees(turnDegrees) == 0
}

private fun visibleTurnAmountDegrees(turnDegrees: Double): Int {
    return abs(roundedSignedTurnDegree(turnDegrees))
}

private fun roundedSignedTurnDegree(turnDegrees: Double): Int {
    if (abs(turnDegrees) < QIBLA_VISIBLE_ALIGNMENT_DEGREES) {
        return 0
    }
    return turnDegrees.roundToInt()
}

@Composable
private fun compassAccuracyMessage(compassState: CompassState): String? {
    if (!compassState.hasCompass) return null
    return stringResource(R.string.qibla_compass_calibrate)
}

private fun hasQiblaSensorSupport(context: Context): Boolean {
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val hasMagneticCompass = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null &&
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
    val hasRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
        sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) != null
    return hasMagneticCompass || hasRotationSensor
}

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
    val hasRotationSensor = rotationVectorSensor != null || geomagneticRotationVectorSensor != null
    val hasCompass = hasRotationSensor || hasMagneticCompass

    var rotationVectorHeadingDegrees by remember { mutableStateOf<Float?>(null) }
    var geomagneticRotationHeadingDegrees by remember { mutableStateOf<Float?>(null) }
    var manualCompassHeadingDegrees by remember { mutableStateOf<Float?>(null) }

    val headingDegrees = rotationVectorHeadingDegrees
        ?: geomagneticRotationHeadingDegrees
        ?: manualCompassHeadingDegrees

    DisposableEffect(
        enabled,
        lifecycleOwner,
        sensorManager,
        rotationVectorSensor,
        geomagneticRotationVectorSensor,
        accelerometerSensor,
        magneticFieldSensor,
    ) {
        val gravityValues = FloatArray(3)
        val magneticValues = FloatArray(3)
        var hasGravityValues = false
        var hasMagneticValues = false
        var registered = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        val rotationMatrix = FloatArray(9)
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        val rawHeadingDegrees = azimuthDegreesFromRotationMatrix(context, rotationMatrix)
                        rotationVectorHeadingDegrees = smoothedCompassHeading(
                            currentHeadingDegrees = rotationVectorHeadingDegrees,
                            candidateHeadingDegrees = rawHeadingDegrees,
                        )
                    }

                    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                        val rotationMatrix = FloatArray(9)
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        val rawHeadingDegrees = azimuthDegreesFromRotationMatrix(context, rotationMatrix)
                        geomagneticRotationHeadingDegrees = smoothedCompassHeading(
                            currentHeadingDegrees = geomagneticRotationHeadingDegrees,
                            candidateHeadingDegrees = rawHeadingDegrees,
                        )
                    }

                    Sensor.TYPE_ACCELEROMETER -> {
                        hasGravityValues = copySmoothedSensorValues(
                            source = event.values,
                            destination = gravityValues,
                            hasPreviousValues = hasGravityValues,
                        )
                    }

                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        hasMagneticValues = copySmoothedSensorValues(
                            source = event.values,
                            destination = magneticValues,
                            hasPreviousValues = hasMagneticValues,
                        )
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
                        val rawHeadingDegrees = azimuthDegreesFromRotationMatrix(context, rotationMatrix)
                        manualCompassHeadingDegrees = smoothedCompassHeading(
                            currentHeadingDegrees = manualCompassHeadingDegrees,
                            candidateHeadingDegrees = rawHeadingDegrees,
                        )
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, sensorAccuracy: Int) = Unit
        }

        fun registerSensors() {
            if (registered || !enabled || !hasCompass) {
                return
            }

            rotationVectorSensor?.let { sensor ->
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            }
            geomagneticRotationVectorSensor?.let { sensor ->
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            }
            if (hasMagneticCompass) {
                accelerometerSensor?.let { sensor ->
                    sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                }
                magneticFieldSensor?.let { sensor ->
                    sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
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

private fun smoothedCompassHeading(currentHeadingDegrees: Float?, candidateHeadingDegrees: Float): Float {
    if (currentHeadingDegrees == null) {
        return candidateHeadingDegrees
    }

    val headingDelta = shortestSignedAngleDegrees(
        currentHeadingDegrees.toDouble(),
        candidateHeadingDegrees.toDouble(),
    ).toFloat()
    return normalizeDegrees(
        (currentHeadingDegrees + headingDelta * QIBLA_HEADING_SMOOTHING_ALPHA).toDouble(),
    ).toFloat()
}

private fun roundedCompassDegreeChanged(currentDegrees: Double?, candidateDegrees: Double): Boolean {
    return currentDegrees == null || roundedCompassDegree(currentDegrees) != roundedCompassDegree(candidateDegrees)
}

private fun roundedTurnDegreeChanged(currentDegrees: Double?, candidateDegrees: Double): Boolean {
    return currentDegrees == null || roundedSignedTurnDegree(currentDegrees) != roundedSignedTurnDegree(candidateDegrees)
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

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private fun Context.openAppPermissionSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$packageName")
        if (findActivity() == null) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    startActivity(intent)
}