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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import com.tunisianprayertimes.ui.theme.BgCream
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

// Without a strong character, an RTL paragraph would show "--°" as "°--".
private const val QIBLA_NO_DEGREES = "\u200E--°"

private val QIBLA_DIAL_SIZE = 300.dp
// From the dial's edge to the bezel, which carries the Kaaba badge on its line.
private val QIBLA_BEZEL_INSET = 34.dp
private val QIBLA_KAABA_BADGE_SIZE = 40.dp

private val QiblaHeroBottom = Color(0xFF00352D)
private val QiblaDialInk = Color(0xFFFFF8F0)
private val QiblaHubAligned = Color(0xFFE0F2F1)
private val QiblaToggleTrack = Color(0xFFE6F0EA)
private val QiblaNoticeBackground = Color(0xFFFFF6E0)
private val QiblaNoticeIcon = Color(0xFFB7862A)
private val QiblaChoiceSelected = Color(0xFFF1F7F4)

// Explains the methods with a real route when the phone's own position is not known yet.
private const val QIBLA_EXAMPLE_LATITUDE = 36.8
private const val QIBLA_EXAMPLE_LONGITUDE = 10.183

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
    var methodSheetOpen by rememberSaveable { mutableStateOf(false) }

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
    val methodBearings = remember(activeLocation) {
        activeLocation?.let { location ->
            QiblaMethod.entries.associateWith { method ->
                calculateQibla(location.latitude, location.longitude, method).bearingDegrees
            }
        }
    }
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

    val bannerMessage = displayedBannerMessage
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.QIBLA_CARD)
            .padding(top = 4.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QiblaHeroCard(
            locationText = qiblaLocationText(location = activeLocation, locating = locating),
            onMethodChipClick = if (qiblaMethod != QiblaMethod.GreatCircle) {
                { methodSheetOpen = true }
            } else {
                null
            },
            directionText = displayedDirectionText ?: rawDirectionText,
            isAligned = isQiblaAligned,
            bearingValue = displayedQiblaBearingDegrees
                ?.let { bearingDegrees -> stringResource(R.string.qibla_degrees_value, bearingDegrees.toDouble()) }
                ?: QIBLA_NO_DEGREES,
            headingValue = displayedHeadingCompassDegrees
                ?.let { headingDegrees -> stringResource(R.string.qibla_degrees_value, headingDegrees.toDouble()) }
                ?: QIBLA_NO_DEGREES,
            distanceValue = qiblaSolution?.let { solution -> qiblaDistanceText(solution.distanceMeters) }
                ?: AnnotatedString("--"),
        ) {
            QiblaCompassDial(
                rotationDegrees = qiblaRotation.toFloat(),
                turnDegrees = visibleTurnDegrees,
                phoneHeadingDegrees = headingDegrees?.toFloat(),
                hasGuidance = hasLiveGuidance,
                isAligned = isQiblaAligned,
            )
        }

        if (bannerMessage != null) {
            QiblaGuidanceBar(
                message = bannerMessage.text,
                onClick = if (bannerMessage.requestsLocationPermission) {
                    { requestQiblaLocationPermission(fromWarning = true) }
                } else {
                    null
                },
            )
        }

        QiblaMethodRow(selected = qiblaMethod, onClick = { methodSheetOpen = true })
    }

    if (methodSheetOpen) {
        QiblaMethodSheet(
            selected = qiblaMethod,
            onSelected = ::selectQiblaMethod,
            latitude = activeLocation?.latitude,
            longitude = activeLocation?.longitude,
            bearings = methodBearings,
            onDismiss = { methodSheetOpen = false },
        )
    }
}

@Composable
private fun QiblaHeroCard(
    locationText: String,
    onMethodChipClick: (() -> Unit)?,
    directionText: String,
    isAligned: Boolean,
    bearingValue: String,
    headingValue: String,
    distanceValue: AnnotatedString,
    dial: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(GreenPrimary, GreenPrimaryDark, QiblaHeroBottom)))
            .border(1.dp, Gold.copy(alpha = 0.24f), shape)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_location),
                contentDescription = null,
                tint = GoldLight,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = locationText,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
            )
        }

        if (onMethodChipClick != null) {
            Spacer(Modifier.height(10.dp))
            QiblaMethodChip(onClick = onMethodChipClick)
        }

        Spacer(Modifier.height(6.dp))
        dial()
        Spacer(Modifier.height(10.dp))

        Text(
            text = directionText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (isAligned) GoldLight else Color.White,
            textAlign = TextAlign.Center,
            lineHeight = 25.sp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .padding(horizontal = 8.dp),
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                .height(IntrinsicSize.Min)
                .padding(vertical = 12.dp),
        ) {
            QiblaHeroStat(
                label = stringResource(R.string.qibla_bearing_label),
                value = bearingValue,
                modifier = Modifier.weight(1f),
                valueTestTag = TestTags.QIBLA_BEARING_VALUE,
            )
            QiblaHeroStatDivider()
            QiblaHeroStat(
                label = stringResource(R.string.qibla_heading_label),
                value = headingValue,
                modifier = Modifier.weight(1f),
            )
            QiblaHeroStatDivider()
            QiblaHeroStat(
                label = stringResource(R.string.qibla_distance_label),
                value = distanceValue,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QiblaHeroStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueTestTag: String? = null,
) {
    QiblaHeroStat(label = label, value = AnnotatedString(value), modifier = modifier, valueTestTag = valueTestTag)
}

@Composable
private fun QiblaHeroStat(
    label: String,
    value: AnnotatedString,
    modifier: Modifier = Modifier,
    valueTestTag: String? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = 4.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.66f),
            textAlign = TextAlign.Center,
            lineHeight = 14.sp,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            fontSize = 19.sp,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            lineHeight = 23.sp,
            modifier = if (valueTestTag != null) Modifier.testTag(valueTestTag) else Modifier,
        )
    }
}

@Composable
private fun QiblaHeroStatDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .padding(vertical = 4.dp)
            .width(1.dp)
            .background(Color.White.copy(alpha = 0.14f)),
    )
}

@Composable
private fun QiblaCompassDial(
    rotationDegrees: Float,
    turnDegrees: Double?,
    phoneHeadingDegrees: Float?,
    hasGuidance: Boolean,
    isAligned: Boolean,
) {
    val alignmentProgress by animateFloatAsState(
        targetValue = if (isAligned) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "qiblaAlignmentRing",
    )

    BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
        val dialSize = minOf(maxWidth, QIBLA_DIAL_SIZE)
        val scale = dialSize / QIBLA_DIAL_SIZE
        val badgeSize = QIBLA_KAABA_BADGE_SIZE * scale
        val bezelInset = QIBLA_BEZEL_INSET * scale
        val roseRotation = -(phoneHeadingDegrees ?: 0f)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                // Only the top needs room outside the bezel, for the badge and the phone marker.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val unusedBottom = ((QIBLA_BEZEL_INSET - 6.dp) * scale).roundToPx()
                    layout(placeable.width, placeable.height - unusedBottom) { placeable.place(0, 0) }
                }
                .size(dialSize),
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val px = { value: Dp -> value.toPx() * scale }
                val bezelRadius = size.minDimension / 2f - px(QIBLA_BEZEL_INSET)
                val hubRadius = px(46.dp)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.03f)),
                        center = center,
                        radius = bezelRadius,
                    ),
                    radius = bezelRadius,
                    center = center,
                )
                drawCircle(
                    color = Gold.copy(alpha = 0.55f),
                    radius = bezelRadius,
                    center = center,
                    style = Stroke(width = px(1.5.dp)),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.08f),
                    radius = bezelRadius - px(22.dp),
                    center = center,
                    style = Stroke(width = px(1.dp)),
                )
                if (alignmentProgress > 0f) {
                    drawCircle(
                        color = GoldLight.copy(alpha = 0.25f + alignmentProgress * 0.55f),
                        radius = bezelRadius,
                        center = center,
                        style = Stroke(width = px(2.dp) + px(4.dp) * alignmentProgress),
                    )
                }

                // The rose turns with the phone, so north stays north.
                rotate(degrees = roseRotation, pivot = center) {
                    val tickAlpha = if (phoneHeadingDegrees != null) 1f else 0.45f
                    for (tickIndex in 0 until 72) {
                        val tickDegrees = tickIndex * 5
                        val (tickLength, tickWidth, alpha) = when {
                            tickDegrees % 90 == 0 -> Triple(px(13.dp), px(2.5.dp), 0.95f)
                            tickDegrees % 30 == 0 -> Triple(px(11.dp), px(2.dp), 0.8f)
                            tickDegrees % 10 == 0 -> Triple(px(7.dp), px(1.2.dp), 0.45f)
                            else -> Triple(px(4.dp), px(1.dp), 0.25f)
                        }
                        val angle = Math.toRadians(tickDegrees - 90.0)
                        val outer = bezelRadius - px(4.dp)
                        val inner = outer - tickLength
                        drawLine(
                            color = QiblaDialInk.copy(alpha = alpha * tickAlpha),
                            start = Offset(
                                center.x + cos(angle).toFloat() * inner,
                                center.y + sin(angle).toFloat() * inner,
                            ),
                            end = Offset(
                                center.x + cos(angle).toFloat() * outer,
                                center.y + sin(angle).toFloat() * outer,
                            ),
                            strokeWidth = tickWidth,
                            cap = StrokeCap.Round,
                        )
                    }
                }

                if (phoneHeadingDegrees != null) {
                    val labelRadius = bezelRadius - px(34.dp)
                    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textAlign = Paint.Align.CENTER
                        textSize = 12.sp.toPx() * scale
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    val baselineOffset = -(labelPaint.ascent() + labelPaint.descent()) / 2f
                    QIBLA_CARDINAL_LABELS.forEach { (label, bearingDegrees) ->
                        labelPaint.color = if (bearingDegrees == 0.0) {
                            Gold.toArgb()
                        } else {
                            QiblaDialInk.copy(alpha = 0.72f).toArgb()
                        }
                        val labelAngle = Math.toRadians(bearingDegrees + roseRotation - 90.0)
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            center.x + cos(labelAngle).toFloat() * labelRadius,
                            center.y + sin(labelAngle).toFloat() * labelRadius + baselineOffset,
                            labelPaint,
                        )
                    }
                }

                // Where the top of the phone points.
                val lubberTip = size.minDimension / 2f - bezelRadius - px(QIBLA_KAABA_BADGE_SIZE) / 2f - px(3.dp)
                drawPath(
                    path = Path().apply {
                        moveTo(center.x, lubberTip)
                        lineTo(center.x - px(7.dp), lubberTip - px(9.dp))
                        lineTo(center.x + px(7.dp), lubberTip - px(9.dp))
                        close()
                    },
                    color = if (isAligned) GoldLight else Color.White.copy(alpha = 0.9f),
                )

                val needleColor = if (hasGuidance) Gold else Color.White.copy(alpha = 0.22f)
                rotate(degrees = rotationDegrees, pivot = center) {
                    val needleTip = center.y - bezelRadius + px(QIBLA_KAABA_BADGE_SIZE) / 2f + px(2.dp)
                    drawPath(
                        path = Path().apply {
                            moveTo(center.x, needleTip)
                            lineTo(center.x - px(13.dp), center.y)
                            lineTo(center.x + px(13.dp), center.y)
                            close()
                        },
                        brush = Brush.verticalGradient(
                            colors = listOf(needleColor, needleColor.copy(alpha = needleColor.alpha * 0.55f)),
                            startY = needleTip,
                            endY = center.y,
                        ),
                    )
                }

                drawCircle(
                    color = if (isAligned) QiblaHubAligned else BgCream,
                    radius = hubRadius,
                    center = center,
                )
                drawCircle(
                    color = if (isAligned) GreenPrimary else Gold.copy(alpha = 0.6f),
                    radius = hubRadius,
                    center = center,
                    style = Stroke(width = px(1.5.dp)),
                )
            }

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { rotationZ = rotationDegrees },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = bezelInset - badgeSize / 2)
                        .size(badgeSize)
                        .graphicsLayer {
                            rotationZ = -rotationDegrees
                            alpha = if (hasGuidance) 1f else 0.55f
                        }
                        .clip(CircleShape)
                        .background(BgCream)
                        .border(1.5.dp, if (isAligned) GoldLight else Gold, CircleShape),
                ) {
                    Image(
                        painter = painterResource(R.drawable.kaaba_marker),
                        contentDescription = stringResource(R.string.qibla_kaaba_marker),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(badgeSize * 0.66f),
                    )
                }
            }

            QiblaDialHub(turnDegrees = turnDegrees, hasGuidance = hasGuidance, isAligned = isAligned)
        }
    }
}

@Composable
private fun QiblaDialHub(turnDegrees: Double?, hasGuidance: Boolean, isAligned: Boolean) {
    val turnAmount = turnDegrees?.takeIf { hasGuidance }?.let(::visibleTurnAmountDegrees)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (turnAmount == 0) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = if (isAligned) GreenPrimary else TextMuted,
                modifier = Modifier.size(30.dp),
            )
        } else {
            Text(
                text = turnAmount?.let { amount -> "$amount°" } ?: QIBLA_NO_DEGREES,
                fontSize = 26.sp,
                color = if (turnAmount != null) GreenPrimaryDark else TextMuted,
                fontWeight = FontWeight.Bold,
                lineHeight = 30.sp,
            )
        }
        Text(
            text = stringResource(
                when {
                    turnAmount == null -> R.string.qibla_hub_idle
                    turnAmount == 0 -> R.string.qibla_hub_ahead
                    (turnDegrees ?: 0.0) > 0 -> R.string.qibla_hub_turn_right
                    else -> R.string.qibla_hub_turn_left
                },
            ),
            fontSize = 11.sp,
            color = if (isAligned) GreenPrimary else TextMuted,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 14.sp,
        )
    }
}

/** The method stays out of the way: one quiet row that opens the explanation and the choice. */
@Composable
private fun QiblaMethodRow(selected: QiblaMethod, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.QIBLA_METHOD_ROW)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, CardBorder, shape)
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.qibla_method_label),
                fontSize = 11.sp,
                color = TextMuted,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(qiblaMethodNameRes(selected)),
                    fontSize = 15.sp,
                    color = GreenPrimaryDark,
                    fontWeight = FontWeight.Bold,
                )
                if (selected == QiblaMethod.GreatCircle) {
                    Spacer(Modifier.width(8.dp))
                    QiblaStandardBadge()
                }
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_qibla_chevron),
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun QiblaStandardBadge() {
    Text(
        text = stringResource(R.string.qibla_method_standard),
        fontSize = 11.sp,
        color = GreenPrimary,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(QiblaToggleTrack)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Shown on the compass only while the less common method is on, so it is never on unnoticed. */
@Composable
private fun QiblaMethodChip(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .testTag(TestTags.QIBLA_METHOD_CHIP)
            .clip(RoundedCornerShape(50))
            .background(Gold.copy(alpha = 0.18f))
            .border(1.dp, Gold.copy(alpha = 0.55f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 14.dp, height = 3.dp)
                .clip(RoundedCornerShape(50))
                .background(Gold),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.qibla_method_active_chip),
            fontSize = 12.sp,
            color = GoldLight,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 16.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QiblaMethodSheet(
    selected: QiblaMethod,
    onSelected: (QiblaMethod) -> Unit,
    latitude: Double?,
    longitude: Double?,
    bearings: Map<QiblaMethod, Double>?,
    onDismiss: () -> Unit,
) {
    val routes = remember(latitude, longitude) {
        qiblaRoutes(latitude ?: QIBLA_EXAMPLE_LATITUDE, longitude ?: QIBLA_EXAMPLE_LONGITUDE)
    }
    val methodDifference = bearings?.let { methodBearings ->
        abs(
            shortestSignedAngleDegrees(
                methodBearings.getValue(QiblaMethod.GreatCircle),
                methodBearings.getValue(QiblaMethod.RhumbLine),
            ),
        ).roundToInt()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.qibla_method_sheet_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = GreenPrimaryDark,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.qibla_method_sheet_intro),
                fontSize = 13.sp,
                color = TextMuted,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(16.dp))
            QiblaRoutesIllustration(routes = routes, selected = selected)
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QiblaMethodChoice(
                    method = QiblaMethod.GreatCircle,
                    selected = selected == QiblaMethod.GreatCircle,
                    bearingDegrees = bearings?.get(QiblaMethod.GreatCircle),
                    note = null,
                    onClick = { onSelected(QiblaMethod.GreatCircle) },
                )
                QiblaMethodChoice(
                    method = QiblaMethod.RhumbLine,
                    selected = selected == QiblaMethod.RhumbLine,
                    bearingDegrees = bearings?.get(QiblaMethod.RhumbLine),
                    note = methodDifference?.let { difference ->
                        if (difference < 1) {
                            stringResource(R.string.qibla_method_difference_negligible)
                        } else {
                            stringResource(R.string.qibla_method_difference, difference)
                        }
                    },
                    onClick = { onSelected(QiblaMethod.RhumbLine) },
                )
            }
        }
    }
}

@Composable
private fun QiblaMethodChoice(
    method: QiblaMethod,
    selected: Boolean,
    bearingDegrees: Double?,
    note: String?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(
                when (method) {
                    QiblaMethod.GreatCircle -> TestTags.QIBLA_METHOD_GREAT_CIRCLE
                    QiblaMethod.RhumbLine -> TestTags.QIBLA_METHOD_RHUMB_LINE
                },
            )
            .clip(shape)
            .background(if (selected) QiblaChoiceSelected else Color.White)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) GreenPrimary else CardBorder, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
    ) {
        QiblaRadioMark(selected = selected)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 16.dp, height = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (method == QiblaMethod.GreatCircle) QiblaGreatCircleColor else QiblaRhumbLineColor,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(qiblaMethodNameRes(method)),
                    fontSize = 15.sp,
                    color = GreenPrimaryDark,
                    fontWeight = FontWeight.Bold,
                )
                if (method == QiblaMethod.GreatCircle) {
                    Spacer(Modifier.width(8.dp))
                    QiblaStandardBadge()
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    when (method) {
                        QiblaMethod.GreatCircle -> R.string.qibla_method_great_circle_description
                        QiblaMethod.RhumbLine -> R.string.qibla_method_rhumb_line_description
                    },
                ),
                fontSize = 12.5.sp,
                color = TextMuted,
                lineHeight = 18.sp,
            )
            if (note != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = note,
                    fontSize = 12.5.sp,
                    color = QiblaNoticeIcon,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 18.sp,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = bearingDegrees
                ?.let { degrees -> stringResource(R.string.qibla_degrees_value, roundedCompassDegree(degrees).toDouble()) }
                ?: QIBLA_NO_DEGREES,
            fontSize = 18.sp,
            color = if (selected) GreenPrimaryDark else TextMuted,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun QiblaRadioMark(selected: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(20.dp)
            .border(2.dp, if (selected) GreenPrimaryDark else CardBorder, CircleShape),
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(GreenPrimaryDark),
            )
        }
    }
}

private fun qiblaMethodNameRes(method: QiblaMethod): Int = when (method) {
    QiblaMethod.GreatCircle -> R.string.qibla_method_great_circle
    QiblaMethod.RhumbLine -> R.string.qibla_method_rhumb_line
}

@Composable
private fun QiblaGuidanceBar(message: String, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(QiblaNoticeBackground)
            .border(1.dp, Gold.copy(alpha = 0.35f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = QiblaNoticeIcon,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = message,
            fontSize = 12.5.sp,
            color = TextDark,
            fontWeight = FontWeight.Medium,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun qiblaDistanceText(distanceMeters: Double): AnnotatedString {
    val (amount, unit) = if (distanceMeters < 1_000.0) {
        distanceMeters.roundToInt() to stringResource(R.string.qibla_distance_unit_meters)
    } else {
        (distanceMeters / 1_000.0).roundToInt() to stringResource(R.string.qibla_distance_unit_kilometers)
    }
    return buildAnnotatedString {
        append(amount.toString())
        append(' ')
        withStyle(SpanStyle(fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))) {
            append(unit)
        }
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