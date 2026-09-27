package com.tunisianprayertimes.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.tunisianprayertimes.CompassTrust
import com.tunisianprayertimes.CompassTrustSample
import com.tunisianprayertimes.CompassTrustState
import com.tunisianprayertimes.Delegation
import com.tunisianprayertimes.DelegationLocator
import com.tunisianprayertimes.GeomagneticFieldValues
import com.tunisianprayertimes.GouvernoratRepository
import com.tunisianprayertimes.MagneticFieldZone
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.QiblaMethod
import com.tunisianprayertimes.R
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.WorldMagneticModel
import com.tunisianprayertimes.calculateQibla
import com.tunisianprayertimes.headingAccuracyDegreesFromRotationVector
import com.tunisianprayertimes.isPhoneLikelyInTunisia
import com.tunisianprayertimes.isPhoneTooTilted
import com.tunisianprayertimes.locationBearingUncertaintyDegrees
import com.tunisianprayertimes.magneticFieldZone
import com.tunisianprayertimes.normalizeDegrees
import com.tunisianprayertimes.screenTiltDegrees
import com.tunisianprayertimes.shortestSignedAngleDegrees
import com.tunisianprayertimes.validCoordinates
import com.tunisianprayertimes.ui.theme.CardBorder
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val QIBLA_HEADING_SMOOTHING_ALPHA = 0.18f
private const val QIBLA_TEXT_UPDATE_INTERVAL_MS = 750L
private const val QIBLA_STATUS_MIN_DISPLAY_MS = 1_600L
private const val QIBLA_WARNING_DISMISS_DELAY_MS = 3_500L
private const val QIBLA_STABILITY_WINDOW_MS = 2_000L
private const val QIBLA_STABILITY_DELTA_DEGREES = 3.0
private const val QIBLA_UNSTABLE_MESSAGE_MS = 800L
// Phone compasses are good to a few degrees at best; a tighter target only measures steadiness.
private const val QIBLA_VISIBLE_ALIGNMENT_DEGREES = 3.0
// A last-known position is shown while a live fix is found, but only if it is this recent.
private const val QIBLA_LAST_KNOWN_LOCATION_MAX_AGE_MS = 60 * 60 * 1_000L
// A first fix whose own error moves the bearing by less than this is kept as it is.
private const val QIBLA_PRECISE_FIX_TARGET_DEGREES = 1.0
// Within this distance the Kaaba is usually in sight, and GPS error dominates the bearing.
private const val QIBLA_NEAR_KAABA_METERS = 500.0

private val QIBLA_CARDINAL_LABELS = listOf(
    "شمال" to 0.0,
    "شرق" to 90.0,
    "جنوب" to 180.0,
    "غرب" to 270.0,
)

private enum class QiblaLocationSource {
    CurrentLocation,
    LastKnownLocation,
    SelectedDelegation,
}

private data class QiblaLocationState(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val accuracyMeters: Float?,
    val source: QiblaLocationSource = QiblaLocationSource.CurrentLocation,
    val delegationName: String? = null,
)

private var cachedRealtimeQiblaLocation: QiblaLocationState? = null
private var qiblaLocationPermissionRequestedThisSession = false

private data class CompassState(
    val headingDegrees: Float?,
    val hasCompass: Boolean,
    val isTooTilted: Boolean,
    val trust: CompassTrust,
)

private data class CompassReading(
    val headingDegrees: Float,
    val isTooTilted: Boolean,
    val headingAccuracyDegrees: Float?,
)

private data class QiblaBannerMessage(
    val text: String,
    val requestsLocationPermission: Boolean = false,
)

private enum class QiblaStabilityStatus {
    Idle,
    Settling,
    Unstable,
    Stable,
}

@Composable
fun QiblaCard(selectedDelegationId: Int) {
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
    var currentLocationIsLastKnown by rememberSaveable { mutableStateOf(false) }
    var qiblaMethod by remember { mutableStateOf(PrefsManager.getQiblaMethod(context)) }

    val currentLocation = remember(
        currentLatitude,
        currentLongitude,
        currentAltitudeMeters,
        currentAccuracyMeters,
        currentLocationIsLastKnown,
    ) {
        val latitude = currentLatitude
        val longitude = currentLongitude
        if (latitude != null && longitude != null) {
            QiblaLocationState(
                latitude = latitude,
                longitude = longitude,
                altitudeMeters = currentAltitudeMeters,
                accuracyMeters = currentAccuracyMeters,
                source = if (currentLocationIsLastKnown) {
                    QiblaLocationSource.LastKnownLocation
                } else {
                    QiblaLocationSource.CurrentLocation
                },
            )
        } else {
            null
        }
    }
    // Abroad, a Tunisian delegation would point the qibla the wrong way.
    val phoneLikelyInTunisia = remember { isPhoneLikelyInTunisia(context) }
    var delegationLookupFinished by remember(selectedDelegationId, phoneLikelyInTunisia) {
        mutableStateOf(!phoneLikelyInTunisia)
    }
    val selectedDelegation by produceState<Delegation?>(
        initialValue = null,
        selectedDelegationId,
        phoneLikelyInTunisia,
    ) {
        if (!phoneLikelyInTunisia) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { GouvernoratRepository.loadAll(context) }.getOrNull()
                ?.asSequence()
                ?.flatMap { gouvernorat -> gouvernorat.delegations.asSequence() }
                ?.firstOrNull { delegation ->
                    delegation.id == selectedDelegationId &&
                        !(delegation.lat == 0.0 && delegation.lng == 0.0) &&
                        validCoordinates(delegation.lat, delegation.lng)
                }
        }
        delegationLookupFinished = true
    }
    val delegationLocation = remember(selectedDelegation) {
        selectedDelegation?.let { delegation ->
            QiblaLocationState(
                latitude = delegation.lat,
                longitude = delegation.lng,
                altitudeMeters = 0.0,
                accuracyMeters = null,
                source = QiblaLocationSource.SelectedDelegation,
                delegationName = delegation.nomAr,
            )
        }
    }
    val deviceLocation = if (locationPermissionGranted) currentLocation else null
    // The bearing moves only about 0.2° per 10 km, so the selected delegation gives usable
    // guidance straight away, and whenever the phone's own location is unavailable.
    val activeLocation = deviceLocation ?: delegationLocation

    LaunchedEffect(deviceLocation) {
        deviceLocation
            ?.takeIf { location -> location.source == QiblaLocationSource.CurrentLocation }
            ?.let { location -> cachedRealtimeQiblaLocation = location }
    }

    fun applyDeviceLocation(location: Location, lastKnown: Boolean) {
        currentLatitude = location.latitude
        currentLongitude = location.longitude
        currentAltitudeMeters = if (location.hasAltitude()) location.altitude else 0.0
        currentAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null
        currentLocationIsLastKnown = lastKnown
    }

    fun detectCurrentLocation() {
        if (locating) {
            return
        }

        locating = true
        scope.launch {
            if (currentLatitude == null) {
                // Show a recent known position at once while a live fix is found.
                DelegationLocator.lastKnownLocation(context, QIBLA_LAST_KNOWN_LOCATION_MAX_AGE_MS)
                    ?.let { location -> applyDeviceLocation(location, lastKnown = true) }
            }
            // The first source to answer wins: the bearing barely needs GPS precision.
            val firstFix = DelegationLocator.detectFirstLocation(context)
            if (firstFix != null) {
                applyDeviceLocation(firstFix, lastKnown = false)
            }
            // Close to Mecca a coarse fix skews the bearing, so wait for the most accurate one.
            val refinedFix = if (firstFix != null && needsPreciserQiblaFix(firstFix)) {
                DelegationLocator.detectCurrentLocation(context)
                    ?.takeIf { location -> isMoreAccurate(location, than = firstFix) }
            } else {
                null
            }
            if (refinedFix != null) {
                applyDeviceLocation(refinedFix, lastKnown = false)
            }
            locating = false

            if (firstFix == null) {
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

    val magneticField = remember(activeLocation) {
        activeLocation?.let(::magneticFieldAt)
    }
    val magneticDeclinationDegrees = magneticField?.declinationDegrees ?: 0.0
    // The model reports nanotesla; the magnetometer reports microtesla.
    val expectedFieldStrengthMicroTesla = magneticField?.totalIntensityNanoTesla?.div(1_000.0)?.toFloat()
    val magneticZone = magneticField
        ?.let { field -> magneticFieldZone(field.horizontalIntensityNanoTesla) }
        ?: MagneticFieldZone.Normal
    val qiblaSolution = remember(activeLocation, qiblaMethod) {
        activeLocation?.let { location -> calculateQibla(location.latitude, location.longitude, qiblaMethod) }
    }
    val qiblaBearing = qiblaSolution?.bearingDegrees
    val nearKaabaDistanceMeters = qiblaSolution?.distanceMeters
        ?.takeIf { distanceMeters -> distanceMeters < QIBLA_NEAR_KAABA_METERS }
    val locationBearingUncertainty = if (qiblaSolution != null && activeLocation != null) {
        locationBearingUncertaintyDegrees(qiblaSolution.distanceMeters, activeLocation.accuracyMeters)
    } else {
        null
    }
    val locationTooCoarse = (locationBearingUncertainty ?: 0.0) > QIBLA_VISIBLE_ALIGNMENT_DEGREES
    // Near the magnetic poles, or with the Kaaba in sight, a compass arrow misleads more than it helps.
    val compassGuidanceAvailable = magneticZone != MagneticFieldZone.Blackout && nearKaabaDistanceMeters == null
    val compassState = rememberCompassState(
        enabled = activeLocation != null && compassGuidanceAvailable,
        expectedFieldStrengthMicroTesla = expectedFieldStrengthMicroTesla,
    )
    // A heading taken with the phone held upright is unreliable, so guidance pauses until it is flat.
    val headingDegrees = compassState.headingDegrees
        ?.takeIf { compassGuidanceAvailable && !compassState.isTooTilted }
        ?.let { magneticHeadingDegrees ->
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

    fun selectQiblaMethod(method: QiblaMethod) {
        if (method == qiblaMethod) return
        activeLocation?.let { location ->
            // The target moved, not the phone: shift the turn history with it so the switch
            // is not taken for compass jitter, and the heading text stays put.
            val bearingShift = shortestSignedAngleDegrees(
                calculateQibla(location.latitude, location.longitude, qiblaMethod).bearingDegrees,
                calculateQibla(location.latitude, location.longitude, method).bearingDegrees,
            )
            stabilityAnchorTurnDegrees = stabilityAnchorTurnDegrees
                ?.let { turn -> shiftedTurnDegrees(turn, bearingShift) }
            displayedTurnDegrees = displayedTurnDegrees
                ?.let { turn -> shiftedTurnDegrees(turn, bearingShift) }
        }
        qiblaMethod = method
        PrefsManager.setQiblaMethod(context, method)
        AnalyticsTracker.qiblaMethodSelected(context, method)
    }
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
    // A weak magnetic field or a coarse fix: the arrow still helps, but "aligned" would overclaim.
    val alignmentIsApproximate = magneticZone != MagneticFieldZone.Normal || locationTooCoarse
    val isQiblaAligned = qiblaStabilityStatus == QiblaStabilityStatus.Stable &&
        compassState.trust == CompassTrust.Good &&
        !alignmentIsApproximate &&
        isExactVisibleQiblaDirection(visibleTurnDegrees)
    val rawDirectionText = qiblaDirectionText(
        compassState = compassState,
        turnDegrees = visibleTurnDegrees,
        // While the delegation is still loading, say the compass is starting rather than
        // flashing the "location needed" message for the status text's minimum display time.
        hasLocation = activeLocation != null || !delegationLookupFinished,
        locating = locating,
        nearKaabaDistanceMeters = nearKaabaDistanceMeters,
        magneticZone = magneticZone,
        alignmentIsApproximate = alignmentIsApproximate,
        stabilityStatus = qiblaStabilityStatus,
    )
    var displayedDirectionText by remember { mutableStateOf<String?>(null) }
    var directionTextShownAtMs by remember { mutableStateOf(0L) }
    val rawBannerMessage = compassAccuracyMessage(compassState).takeIf { compassGuidanceAvailable }
        ?: locationPrecisionMessage(locationTooCoarse = locationTooCoarse && nearKaabaDistanceMeters == null)
        ?: magneticZoneMessage(magneticZone)
        ?: if (!locationPermissionGranted) {
            QiblaBannerMessage(
                text = stringResource(R.string.qibla_location_permission_required),
                requestsLocationPermission = true,
            )
        } else {
            null
        }
    var displayedBannerMessage by remember { mutableStateOf<QiblaBannerMessage?>(null) }

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

    LaunchedEffect(rawBannerMessage) {
        if (rawBannerMessage != null) {
            displayedBannerMessage = rawBannerMessage
        } else {
            delay(QIBLA_WARNING_DISMISS_DELAY_MS)
            displayedBannerMessage = null
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
            val bannerMessage = displayedBannerMessage
            QiblaGuidanceBar(
                message = bannerMessage?.text,
                onClick = if (bannerMessage?.requestsLocationPermission == true) {
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
                locationText = qiblaLocationText(location = activeLocation, locating = locating),
            )

            Spacer(Modifier.height(12.dp))

            QiblaMethodSelector(
                selected = qiblaMethod,
                onSelected = ::selectQiblaMethod,
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
private fun QiblaMethodSelector(
    selected: QiblaMethod,
    onSelected: (QiblaMethod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val methods = listOf(QiblaMethod.GreatCircle, QiblaMethod.RhumbLine)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.qibla_method_label),
            fontSize = 11.sp,
            color = TextMuted,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        EndpointModeChoices(
            choices = listOf(
                stringResource(R.string.qibla_method_great_circle),
                stringResource(R.string.qibla_method_rhumb_line),
            ),
            selected = methods.indexOf(selected),
            enabled = true,
            onSelected = { index -> onSelected(methods[index]) },
            optionTestTags = listOf(
                TestTags.QIBLA_METHOD_GREAT_CIRCLE,
                TestTags.QIBLA_METHOD_RHUMB_LINE,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                when (selected) {
                    QiblaMethod.GreatCircle -> R.string.qibla_method_great_circle_description
                    QiblaMethod.RhumbLine -> R.string.qibla_method_rhumb_line_description
                },
            ),
            fontSize = 10.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth(),
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
    locating: Boolean,
    nearKaabaDistanceMeters: Double?,
    magneticZone: MagneticFieldZone,
    alignmentIsApproximate: Boolean,
    stabilityStatus: QiblaStabilityStatus,
): String {
    if (!hasLocation) {
        return stringResource(if (locating) R.string.qibla_locating else R.string.qibla_location_required)
    }
    if (nearKaabaDistanceMeters != null) {
        return stringResource(R.string.qibla_near_kaaba, nearKaabaDistanceMeters.roundToInt())
    }
    if (!compassState.hasCompass) {
        return stringResource(R.string.qibla_compass_unavailable)
    }
    if (magneticZone == MagneticFieldZone.Blackout) {
        return stringResource(R.string.qibla_weak_field_blackout)
    }
    if (compassState.isTooTilted) {
        return stringResource(R.string.qibla_hold_phone_flat)
    }
    if (turnDegrees == null) {
        return stringResource(R.string.qibla_compass_waiting)
    }
    val roundedTurnDegrees = visibleTurnAmountDegrees(turnDegrees)
    if (roundedTurnDegrees == 0) {
        return when {
            stabilityStatus == QiblaStabilityStatus.Unstable -> stringResource(R.string.qibla_compass_unstable)
            stabilityStatus != QiblaStabilityStatus.Stable -> stringResource(R.string.qibla_compass_settling)
            compassState.trust != CompassTrust.Good -> stringResource(R.string.qibla_aligned_unverified)
            alignmentIsApproximate -> stringResource(R.string.qibla_aligned_approximate)
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

private fun shiftedTurnDegrees(turnDegrees: Double, bearingShiftDegrees: Double): Double {
    return shortestSignedAngleDegrees(0.0, turnDegrees + bearingShiftDegrees)
}

private fun roundedSignedTurnDegree(turnDegrees: Double): Int {
    if (abs(turnDegrees) < QIBLA_VISIBLE_ALIGNMENT_DEGREES) {
        return 0
    }
    return turnDegrees.roundToInt()
}

@Composable
private fun compassAccuracyMessage(compassState: CompassState): QiblaBannerMessage? {
    if (!compassState.hasCompass) return null
    return when (compassState.trust) {
        CompassTrust.Interference -> QiblaBannerMessage(stringResource(R.string.qibla_compass_interference))
        CompassTrust.NeedsCalibration -> QiblaBannerMessage(stringResource(R.string.qibla_compass_calibrate))
        CompassTrust.Good -> null
    }
}

@Composable
private fun locationPrecisionMessage(locationTooCoarse: Boolean): QiblaBannerMessage? {
    if (!locationTooCoarse) return null
    return QiblaBannerMessage(
        text = stringResource(R.string.qibla_location_too_coarse),
        requestsLocationPermission = true,
    )
}

@Composable
private fun magneticZoneMessage(magneticZone: MagneticFieldZone): QiblaBannerMessage? {
    if (magneticZone != MagneticFieldZone.Caution) return null
    return QiblaBannerMessage(stringResource(R.string.qibla_weak_field_caution))
}

@Composable
private fun qiblaLocationText(location: QiblaLocationState?, locating: Boolean): String {
    return when {
        location == null -> stringResource(R.string.qibla_location_required)
        location.source == QiblaLocationSource.SelectedDelegation -> {
            val delegationName = location.delegationName.orEmpty()
            if (locating) {
                stringResource(R.string.qibla_location_selected_locating, delegationName)
            } else {
                stringResource(R.string.qibla_location_selected, delegationName)
            }
        }
        location.source == QiblaLocationSource.LastKnownLocation -> {
            if (locating) {
                stringResource(R.string.qibla_location_last_known_locating)
            } else {
                stringResource(R.string.qibla_location_last_known)
            }
        }
        location.accuracyMeters != null ->
            stringResource(R.string.qibla_location_current_with_accuracy, location.accuracyMeters)
        else -> stringResource(R.string.qibla_location_current)
    }
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
private fun rememberCompassState(
    enabled: Boolean,
    expectedFieldStrengthMicroTesla: Float?,
): CompassState {
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

    var rotationVectorReading by remember { mutableStateOf<CompassReading?>(null) }
    var geomagneticRotationReading by remember { mutableStateOf<CompassReading?>(null) }
    var manualCompassReading by remember { mutableStateOf<CompassReading?>(null) }
    var compassTrust by remember { mutableStateOf(CompassTrust.Good) }
    val currentExpectedFieldStrengthMicroTesla by rememberUpdatedState(expectedFieldStrengthMicroTesla)

    val reading = rotationVectorReading
        ?: geomagneticRotationReading
        ?: manualCompassReading

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
        var magnetometerAccuracy: Int? = null
        var trustState = CompassTrustState()
        var registered = false

        fun refreshCompassTrust() {
            val activeReading = rotationVectorReading ?: geomagneticRotationReading ?: manualCompassReading
            trustState = trustState.next(
                sample = CompassTrustSample(
                    magnetometerAccuracy = magnetometerAccuracy,
                    headingAccuracyDegrees = activeReading?.headingAccuracyDegrees,
                    fieldStrengthMicroTesla = if (hasMagneticValues) vectorMagnitude(magneticValues) else null,
                    expectedFieldStrengthMicroTesla = currentExpectedFieldStrengthMicroTesla,
                ),
                nowMs = SystemClock.elapsedRealtime(),
            )
            if (compassTrust != trustState.trust) {
                compassTrust = trustState.trust
            }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        rotationVectorReading = compassReadingFromRotationVector(
                            context = context,
                            values = event.values,
                            previousReading = rotationVectorReading,
                        )
                    }

                    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                        geomagneticRotationReading = compassReadingFromRotationVector(
                            context = context,
                            values = event.values,
                            previousReading = geomagneticRotationReading,
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
                        magnetometerAccuracy = event.accuracy
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
                        manualCompassReading = compassReadingFromRotationMatrix(
                            context = context,
                            rotationMatrix = rotationMatrix,
                            previousReading = manualCompassReading,
                            headingAccuracyDegrees = null,
                        )
                    }
                }
                refreshCompassTrust()
            }

            override fun onAccuracyChanged(sensor: Sensor?, sensorAccuracy: Int) {
                // The fused rotation vectors' own accuracy field differs between vendors;
                // the magnetometer's calibration status is the dependable signal.
                if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    magnetometerAccuracy = sensorAccuracy
                    refreshCompassTrust()
                }
            }
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
        headingDegrees = reading?.headingDegrees,
        hasCompass = hasCompass,
        isTooTilted = reading?.isTooTilted == true,
        trust = compassTrust,
    )
}

private fun compassReadingFromRotationVector(
    context: Context,
    values: FloatArray,
    previousReading: CompassReading?,
): CompassReading {
    val rotationMatrix = FloatArray(9)
    SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
    return compassReadingFromRotationMatrix(
        context = context,
        rotationMatrix = rotationMatrix,
        previousReading = previousReading,
        headingAccuracyDegrees = headingAccuracyDegreesFromRotationVector(values),
    )
}

private fun compassReadingFromRotationMatrix(
    context: Context,
    rotationMatrix: FloatArray,
    previousReading: CompassReading?,
    headingAccuracyDegrees: Float?,
): CompassReading {
    val rawHeadingDegrees = azimuthDegreesFromRotationMatrix(context, rotationMatrix)
    return CompassReading(
        headingDegrees = smoothedCompassHeading(
            currentHeadingDegrees = previousReading?.headingDegrees,
            candidateHeadingDegrees = rawHeadingDegrees,
        ),
        isTooTilted = isPhoneTooTilted(
            tiltDegrees = screenTiltDegrees(rotationMatrix),
            wasTooTilted = previousReading?.isTooTilted == true,
        ),
        headingAccuracyDegrees = headingAccuracyDegrees,
    )
}

private fun magneticFieldAt(location: QiblaLocationState): GeomagneticFieldValues {
    return WorldMagneticModel.fieldAt(
        latitudeDegrees = location.latitude,
        longitudeDegrees = location.longitude,
        altitudeMeters = location.altitudeMeters,
        timeMillis = System.currentTimeMillis(),
    )
}

private fun needsPreciserQiblaFix(location: Location): Boolean {
    if (!location.hasAccuracy()) return true
    val distanceMeters = calculateQibla(location.latitude, location.longitude).distanceMeters
    val uncertaintyDegrees = locationBearingUncertaintyDegrees(distanceMeters, location.accuracy) ?: return true
    return uncertaintyDegrees > QIBLA_PRECISE_FIX_TARGET_DEGREES
}

private fun isMoreAccurate(candidate: Location, than: Location): Boolean {
    if (!candidate.hasAccuracy()) return false
    return !than.hasAccuracy() || candidate.accuracy < than.accuracy
}

private fun vectorMagnitude(values: FloatArray): Float {
    return sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
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