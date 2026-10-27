package com.tunisianprayertimes

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.selects.select

private const val FUSED_TIMEOUT_MS = 8_000L
private const val LOCATION_PROVIDER_TIMEOUT_MS = 8_000L
private const val MAX_LAST_LOCATION_AGE_MS = 5 * 60 * 1_000L
private const val MAX_SILENT_UPDATE_ACCURACY_METERS = 10_000f
private const val LAST_FUSED_LOCATION_TIMEOUT_MS = 2_000L

/** How long [awaitFirstMatching] waits for a usable answer: the budget each live source gets. */
internal const val FIRST_LOCATION_TIMEOUT_MS = FUSED_TIMEOUT_MS

/**
 * Simplified polygon of Tunisia's border from OpenStreetMap / Nominatim,
 * expanded outward by ~0.3° to account for GPS inaccuracy near borders.
 * Uses the same ray-casting point-in-polygon algorithm as delegation boundaries.
 */
private val TUNISIA_BORDER: List<BoundaryPoint> = listOf(
    BoundaryPoint(lng = 7.2437, lat = 33.7929),
    BoundaryPoint(lng = 7.6051, lat = 32.992),
    BoundaryPoint(lng = 8.1525, lat = 32.5764),
    BoundaryPoint(lng = 8.2071, lat = 32.2426),
    BoundaryPoint(lng = 9.0065, lat = 31.8012),
    BoundaryPoint(lng = 9.5525, lat = 29.9291),
    BoundaryPoint(lng = 9.9248, lat = 30.0567),
    BoundaryPoint(lng = 10.3534, lat = 30.599),
    BoundaryPoint(lng = 10.1766, lat = 31.1818),
    BoundaryPoint(lng = 10.3348, lat = 31.3782),
    BoundaryPoint(lng = 11.7875, lat = 32.2243),
    BoundaryPoint(lng = 11.7068, lat = 32.7049),
    BoundaryPoint(lng = 11.9091, lat = 33.1755),
    BoundaryPoint(lng = 11.6089, lat = 33.2944),
    BoundaryPoint(lng = 11.4273, lat = 33.8709),
    BoundaryPoint(lng = 11.5667, lat = 34.3853),
    BoundaryPoint(lng = 12.0105, lat = 34.5485),
    BoundaryPoint(lng = 12.177, lat = 34.9036),
    BoundaryPoint(lng = 11.5735, lat = 35.5112),
    BoundaryPoint(lng = 11.4958, lat = 36.0836),
    BoundaryPoint(lng = 11.0235, lat = 36.2069),
    BoundaryPoint(lng = 10.8966, lat = 36.3866),
    BoundaryPoint(lng = 11.5498, lat = 36.9974),
    BoundaryPoint(lng = 11.4161, lat = 37.4508),
    BoundaryPoint(lng = 10.4576, lat = 37.6695),
    BoundaryPoint(lng = 10.2447, lat = 37.8475),
    BoundaryPoint(lng = 9.132, lat = 37.7349),
    BoundaryPoint(lng = 9.0037, lat = 38.0356),
    BoundaryPoint(lng = 8.602, lat = 37.9294),
    BoundaryPoint(lng = 8.5279, lat = 37.7013),
    BoundaryPoint(lng = 8.7791, lat = 37.5791),
    BoundaryPoint(lng = 8.4673, lat = 37.4166),
    BoundaryPoint(lng = 8.5492, lat = 37.1007),
    BoundaryPoint(lng = 7.9566, lat = 36.723),
    BoundaryPoint(lng = 8.228, lat = 36.6506),
    BoundaryPoint(lng = 8.0187, lat = 36.0732),
    BoundaryPoint(lng = 8.0336, lat = 35.4279),
    BoundaryPoint(lng = 8.1979, lat = 35.3509),
    BoundaryPoint(lng = 7.947, lat = 34.9534),
    BoundaryPoint(lng = 8.0243, lat = 34.7251),
    BoundaryPoint(lng = 7.3628, lat = 34.1195),
)

internal fun isInsideTunisiaBounds(lat: Double, lng: Double): Boolean {
    return ringContainsPoint(TUNISIA_BORDER, lat, lng)
}

/**
 * Whether a location fix counts as Tunisia: inside the country outline from neighborhoods.bin,
 * or inside the widened [TUNISIA_BORDER]. The widened border alone decides when the index
 * cannot load. Both the GPS gate and [SilenceVerifyWorker] use this one test, so one cannot
 * turn prayer silencing off while the other turns it back on.
 */
internal fun isInsideTunisia(context: Context, lat: Double, lng: Double): Boolean {
    return isInsideTunisiaBounds(lat, lng) || NeighborhoodRepository.isInsideCountry(context, lat, lng)
}

sealed interface DelegationLocationResult {
    /** A Tunisian delegation (INM). */
    data class Success(val delegation: Delegation, val locality: Locality? = null) : DelegationLocationResult

    /** A place abroad (6.8), stored rounded to 0.01°, with its «قرب X» label or none. */
    data class Abroad(val place: com.tunisianprayertimes.place.PrayerPlace.Coordinates, val label: String?) : DelegationLocationResult

    data object PermissionDenied : DelegationLocationResult
    data object LocationUnavailable : DelegationLocationResult
    data object NoDelegationFound : DelegationLocationResult
}

internal data class LocationPermissionState(
    val hasFine: Boolean,
    val hasCoarse: Boolean
) {
    val hasAny: Boolean
        get() = hasFine || hasCoarse
}

object DelegationLocator {
    internal var locationProvider: DelegationLocationProvider = AndroidDelegationLocationProvider

    val requestedPermissions: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    fun hasLocationPermission(context: Context): Boolean {
        return locationPermissionState(context).hasAny
    }

    /**
     * The location also while no screen of the app is open (6.8's travel rule): on Android 10 and later only with the
     * background-location permission, which the user grants by choosing «always» in the system's location page;
     * before Android 10 the location permission covers it.
     */
    fun hasBackgroundLocationPermission(context: Context): Boolean =
        hasLocationPermission(context) && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED)

    /**
     * Whether the background location request is still to be offered: auto-location on, the location granted for
     * the app's screens only, Android 10 or later.
     */
    fun needsBackgroundLocationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && PrefsManager.isAutoLocationUpdateEnabled(context) &&
            hasLocationPermission(context) && !hasBackgroundLocationPermission(context)

    /**
     * Whether a fix asked for now, from an alarm, a receiver or a job, can come: Android refuses it to an app granted
     * the location for its screens only while none of them is in front (seen on a Pixel 6, 2026-10-25: the app-op
     * history logs «Reject [bg]» and the request returns nothing). Without it a background check only wakes the phone.
     */
    fun canLocateFromBackground(context: Context): Boolean =
        hasBackgroundLocationPermission(context) || (hasLocationPermission(context) && isAppInFront())

    private fun isAppInFront(): Boolean = runCatching {
        ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState).importance <=
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    suspend fun detectCurrentLocation(context: Context): Location? {
        val permissionState = locationPermissionState(context)
        if (!permissionState.hasAny) return null

        return findCurrentLocation(context, permissionState)
    }

    /**
     * The first usable fix from any source. Unlike [detectCurrentLocation], it does not
     * keep waiting for GPS once fused or network location answers, so use it when speed
     * matters more than the last few metres of precision.
     */
    suspend fun detectFirstLocation(context: Context): Location? {
        val permissionState = locationPermissionState(context)
        if (!permissionState.hasAny) return null

        return locationProvider.findFirstLocation(context, permissionState)
            ?.takeIf { isUsableLocation(it) }
    }

    /** The best location already known to the phone, no older than [maxAgeMs]; starts no new fix. */
    suspend fun lastKnownLocation(context: Context, maxAgeMs: Long): Location? {
        val permissionState = locationPermissionState(context)
        if (!permissionState.hasAny) return null

        return locationProvider.findLastKnownLocation(context, permissionState, maxAgeMs)
            ?.takeIf { isUsableLocation(it, maxAgeMs = maxAgeMs) }
    }

    /** What a silent fix did (plan 6.8): none came, it kept the saved place, or it stored a new one. */
    enum class FixOutcome { NO_FIX, UNCHANGED, CHANGED }

    /**
     * Uses a recent cached or fused location to silently update the saved
     * delegation if the user has moved to a different one. Returns true if the
     * delegation changed.
     */
    suspend fun updateDelegationFromLastLocation(context: Context): Boolean =
        updatePlaceFromLastLocation(context) == FixOutcome.CHANGED

    /** [updateDelegationFromLastLocation], telling a missing fix apart from an unchanged place (6.8's travel rule). */
    suspend fun updatePlaceFromLastLocation(context: Context): FixOutcome =
        updateDelegationFromLocation(context) { permissionState ->
            locationProvider.findRecentLocation(context, permissionState)
        }

    /**
     * Uses a bounded fresh-location lookup before silently updating the saved
     * delegation. Use this from scheduled background checks where a short
     * location wait is acceptable.
     */
    suspend fun updateDelegationFromCurrentLocation(context: Context): Boolean =
        updatePlaceFromCurrentLocation(context) == FixOutcome.CHANGED

    /** [updateDelegationFromCurrentLocation], telling a missing fix apart from an unchanged place. */
    suspend fun updatePlaceFromCurrentLocation(context: Context): FixOutcome =
        updateDelegationFromLocation(context) { permissionState ->
            locationProvider.findFreshLocation(context, permissionState)
        }

    private suspend fun updateDelegationFromLocation(
        context: Context,
        locationSource: suspend (LocationPermissionState) -> Location?
    ): FixOutcome {
        val permissionState = locationPermissionState(context)
        if (!permissionState.hasAny) return FixOutcome.NO_FIX

        val location: Location? = try {
            locationSource(permissionState)
        } catch (_: Exception) {
            null
        }

        if (location == null) return FixOutcome.NO_FIX
        if (!isUsableSilentUpdateLocation(location)) return FixOutcome.NO_FIX

        val accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else null
        val result = withContext(Dispatchers.IO) {
            resolveGpsLocation(context, location.latitude, location.longitude, accuracyMeters)
        }
        val saved = PrefsManager.getPlace(context)
        val savedLabel = PrefsManager.getPlaceNameSnapshot(context)
        return when (result) {
            is DelegationLocationResult.Success -> {
                // Refresh the neighborhood even when travel stays within one timetable's area.
                PrefsManager.setGpsLocation(context, result)
                val changed = saved != com.tunisianprayertimes.place.PrayerPlace.Inm(result.delegation.id)
                if (changed) PlaceResolution.onPlaceChanged(context, saved, PrefsManager.getPlace(context), savedLabel, result.delegation.nomAr)
                if (changed) FixOutcome.CHANGED else FixOutcome.UNCHANGED
            }
            is DelegationLocationResult.Abroad -> {
                // 6.8: a semantic change at any distance, otherwise only a move of more than 3 km.
                // The fix's own mapping may scan a year (6.5), and the screen reads a stored place at once: both off the
                // main thread (this runs on the main thread when the app comes back to the front).
                val store = withContext(Dispatchers.Default) {
                    PlaceResolution.shouldStore(context, saved, result.place).also { store ->
                        if (store) runCatching { PrayerTimesRepository.warm(context, result.place) }
                    }
                }
                if (!store) return FixOutcome.UNCHANGED
                PrefsManager.setPlace(context, result.place, result.label, fromGps = true)
                PlaceResolution.onPlaceChanged(context, saved, result.place, savedLabel, result.label)
                FixOutcome.CHANGED
            }
            else -> FixOutcome.NO_FIX
        }
    }

    suspend fun detectNearestDelegation(context: Context): DelegationLocationResult {
        val permissionState = locationPermissionState(context)
        if (!permissionState.hasAny) {
            return DelegationLocationResult.PermissionDenied
        }

        // A deliberate tap: the first fine fix answers it, where waiting for GPS indoors used to take its whole timeout.
        val location = locationProvider.findPreciseEnoughLocation(context, permissionState)
            ?.takeIf { isUsableLocation(it) }
            ?: return DelegationLocationResult.LocationUnavailable

        val accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else null
        return withContext(Dispatchers.IO) {
            resolveGpsLocation(context, location.latitude, location.longitude, accuracyMeters)
        }
    }

    internal fun resetLocationProviderForTests() {
        locationProvider = AndroidDelegationLocationProvider
    }

    private suspend fun findCurrentLocation(
        context: Context,
        permissionState: LocationPermissionState
    ): Location? {
        return locationProvider.findCurrentLocation(context, permissionState)
            ?.takeIf { isUsableLocation(it) }
    }

    internal fun resolveGpsLocation(
        context: Context,
        lat: Double,
        lng: Double,
        accuracyMeters: Double? = null,
    ): DelegationLocationResult {
        if (!validCoordinates(lat, lng)) return DelegationLocationResult.LocationUnavailable
        // Outside the outline nothing is rejected (6.8): a place abroad, a closed region, or Tunisia's nearest delegation.
        if (!isInsideTunisia(context, lat, lng)) return PlaceResolution.resolveAbroad(context, lat, lng)
        val index = runCatching { NeighborhoodRepository.load(context) }.getOrNull()
        val nearest = GouvernoratRepository.findNearestDelegation(context, lat, lng)
            ?: return DelegationLocationResult.NoDelegationFound
        // Containment determines only the user's visible location. Timetables are
        // selected by distance from the GPS fix, never by an administrative alias.
        val locality = index?.findWithAccuracy(lat, lng, accuracyMeters)?.copy(delegationId = nearest.id)
        return DelegationLocationResult.Success(nearest, locality)
    }

    private fun isUsableSilentUpdateLocation(location: Location): Boolean {
        return isUsableLocation(location) && location.hasAccuracy() &&
            location.accuracy <= MAX_SILENT_UPDATE_ACCURACY_METERS
    }
}

internal interface DelegationLocationProvider {
    suspend fun findCurrentLocation(
        context: Context,
        permissionState: LocationPermissionState
    ): Location?

    suspend fun findRecentLocation(
        context: Context,
        permissionState: LocationPermissionState
    ): Location?

    suspend fun findFreshLocation(
        context: Context,
        permissionState: LocationPermissionState
    ): Location?

    suspend fun findFirstLocation(
        context: Context,
        permissionState: LocationPermissionState
    ): Location? = findCurrentLocation(context, permissionState)

    /** The locate button's fix: like [findCurrentLocation], but a fine fix ends the wait (see awaitPreciseEnough). */
    suspend fun findPreciseEnoughLocation(
        context: Context,
        permissionState: LocationPermissionState
    ): Location? = findCurrentLocation(context, permissionState)

    suspend fun findLastKnownLocation(
        context: Context,
        permissionState: LocationPermissionState,
        maxAgeMs: Long
    ): Location? = findRecentLocation(context, permissionState)
}

private object AndroidDelegationLocationProvider : DelegationLocationProvider {

    override suspend fun findCurrentLocation(
        context: Context,
        permissionState: LocationPermissionState
    ): Location? {
        return findCurrentLocationInternal(context, permissionState)
    }

    override suspend fun findRecentLocation(
        context: Context,
        permissionState: LocationPermissionState
    ): Location? {
        return chooseBestLocation(listOfNotNull(
            runCatching { lastFusedLocation(context) }.getOrNull(),
            recentKnownLocation(context, permissionState)
        ))
    }

    override suspend fun findFreshLocation(
        context: Context,
        permissionState: LocationPermissionState
    ): Location? {
        return chooseBestLocation(listOfNotNull(
            runCatching { currentFusedLocation(context, permissionState) }.getOrNull(),
            recentKnownLocation(context, permissionState)
        ))
    }

    @SuppressLint("MissingPermission")
    override suspend fun findFirstLocation(
        context: Context,
        permissionState: LocationPermissionState
    ): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Same sources as findCurrentLocationInternal, but the first usable answer wins.
        return coroutineScope {
            val requests = mutableListOf(async {
                runCatching { currentFusedLocation(context, permissionState) }.getOrNull()
            })
            fallbackProviders(permissionState)
                .filter { isProviderEnabled(locationManager, it) }
                .forEach { provider ->
                    requests += async { currentProviderLocation(locationManager, provider) }
                }
            awaitFirstMatching(requests) { isUsableLocation(it) }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun findLastKnownLocation(
        context: Context,
        permissionState: LocationPermissionState,
        maxAgeMs: Long
    ): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fusedLocation = withTimeoutOrNull(LAST_FUSED_LOCATION_TIMEOUT_MS) {
            runCatching { lastFusedLocation(context) }.getOrNull()
        }
        val knownLocations = fallbackProviders(permissionState)
            .filter { isProviderEnabled(locationManager, it) }
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
        return chooseBestLocation(listOfNotNull(fusedLocation) + knownLocations, maxAgeMs = maxAgeMs)
    }

    @SuppressLint("MissingPermission")
    override suspend fun findPreciseEnoughLocation(
        context: Context,
        permissionState: LocationPermissionState
    ): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val knownLocation = freshestKnownLocation(locationManager, permissionState)
        // Same sources as findCurrentLocationInternal, but a fine fix ends the wait at once and a coarse one still
        // leaves GPS its chance (awaitPreciseEnough).
        return coroutineScope {
            // Fused hands back its cached fix when it is recent enough: here a few seconds, not minutes, since the
            // place may have changed since then.
            val requests = mutableListOf(LiveSource.OTHER to async {
                runCatching { currentFusedLocation(context, permissionState, maxAgeMs = FRESH_FIX_MS) }.getOrNull()
            })
            fallbackProviders(permissionState)
                .filter { isProviderEnabled(locationManager, it) }
                .forEach { provider ->
                    val source = when (provider) {
                        LocationManager.GPS_PROVIDER -> LiveSource.GPS
                        LocationManager.PASSIVE_PROVIDER -> LiveSource.PASSIVE
                        else -> LiveSource.OTHER
                    }
                    requests += source to async { currentProviderLocation(locationManager, provider) }
                }
            awaitPreciseEnough(
                requests = requests,
                known = knownLocation,
                best = { chooseBestLocation(it) },
                accuracyMeters = { if (it.hasAccuracy()) it.accuracy else Float.POSITIVE_INFINITY },
                ageMs = { System.currentTimeMillis() - it.time },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun findCurrentLocationInternal(
        context: Context,
        permissionState: LocationPermissionState
    ): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val knownLocation = freshestKnownLocation(locationManager, permissionState)
        // Fine GPS must get a chance even when fused/network returns a coarse fix.
        // The requests run together, keeping the live lookup within one timeout.
        val liveLocations = coroutineScope {
            val requests = mutableListOf(async {
                runCatching { currentFusedLocation(context, permissionState) }.getOrNull()
            })
            fallbackProviders(permissionState)
                .filter { isProviderEnabled(locationManager, it) }
                .forEach { provider ->
                    requests += async { currentProviderLocation(locationManager, provider) }
                }
            requests.awaitAll().filterNotNull()
        }
        return chooseBestLocation(liveLocations + listOfNotNull(knownLocation))
    }

    @SuppressLint("MissingPermission")
    private fun recentKnownLocation(
        context: Context,
        permissionState: LocationPermissionState
    ): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return freshestKnownLocation(locationManager, permissionState)
    }

    @SuppressLint("MissingPermission")
    private suspend fun lastFusedLocation(context: Context): Location? {
        return suspendCancellableCoroutine { continuation ->
            LocationServices.getFusedLocationProviderClient(context)
                .lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (continuation.isActive) continuation.resume(location)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentFusedLocation(
        context: Context,
        permissionState: LocationPermissionState,
        maxAgeMs: Long = MAX_LAST_LOCATION_AGE_MS,
    ): Location? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val tokenSource = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(
                if (permissionState.hasFine) {
                    Priority.PRIORITY_HIGH_ACCURACY
                } else {
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY
                }
            )
            .setMaxUpdateAgeMillis(maxAgeMs)
            .setDurationMillis(FUSED_TIMEOUT_MS)
            .build()

        return try {
            withTimeoutOrNull(FUSED_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    client.getCurrentLocation(request, tokenSource.token)
                        .addOnSuccessListener { location ->
                            if (continuation.isActive) {
                                continuation.resume(location)
                            }
                        }
                        .addOnFailureListener {
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }

                    continuation.invokeOnCancellation {
                        tokenSource.cancel()
                    }
                }
            }
        } finally {
            tokenSource.cancel()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentProviderLocation(
        locationManager: LocationManager,
        provider: String
    ): Location? {
        var listener: LocationListener? = null

        return try {
            withTimeoutOrNull(LOCATION_PROVIDER_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val currentListener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (!isUsableLocation(location)) return
                            runCatching { locationManager.removeUpdates(this) }
                            if (continuation.isActive) {
                                continuation.resume(location)
                            }
                        }

                        override fun onProviderDisabled(providerName: String) {
                            runCatching { locationManager.removeUpdates(this) }
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }
                    listener = currentListener

                    val requestResult = runCatching {
                        locationManager.requestLocationUpdates(
                            provider,
                            0L,
                            0f,
                            currentListener,
                            Looper.getMainLooper()
                        )
                    }

                    if (requestResult.isFailure) {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                        return@suspendCancellableCoroutine
                    }

                    continuation.invokeOnCancellation {
                        runCatching { locationManager.removeUpdates(currentListener) }
                    }
                }
            }
        } finally {
            listener?.let { activeListener ->
                runCatching { locationManager.removeUpdates(activeListener) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun freshestKnownLocation(
        locationManager: LocationManager,
        permissionState: LocationPermissionState
    ): Location? {
        val candidates = fallbackProviders(permissionState)
            .filter { isProviderEnabled(locationManager, it) }
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }

        return chooseBestLocation(candidates)
    }

    private fun isProviderEnabled(locationManager: LocationManager, provider: String): Boolean {
        return runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
    }
}

internal fun locationPermissionState(context: Context): LocationPermissionState {
    return LocationPermissionState(
        hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED,
        hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    )
}

internal fun fallbackProviders(permissionState: LocationPermissionState): List<String> {
    return when {
        permissionState.hasFine -> listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        permissionState.hasCoarse -> listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        else -> emptyList()
    }
}

internal fun isUsableLocation(
    location: Location,
    nowMs: Long = System.currentTimeMillis(),
    maxAgeMs: Long = MAX_LAST_LOCATION_AGE_MS
): Boolean {
    if (!validCoordinates(location.latitude, location.longitude)) return false
    if (location.time <= 0L || location.time > nowMs || nowMs - location.time > maxAgeMs) return false
    // An omitted accuracy is unknown; a supplied nonpositive/nonfinite value is invalid.
    return !location.hasAccuracy() || (location.accuracy.isFinite() && location.accuracy > 0f)
}

internal fun chooseBestLocation(
    candidates: List<Location>,
    nowMs: Long = System.currentTimeMillis(),
    maxAgeMs: Long = MAX_LAST_LOCATION_AGE_MS
): Location? {
    return chooseBestCandidate(
        candidates = candidates.filter { isUsableLocation(it, nowMs, maxAgeMs) },
        timeSelector = { it.time },
        accuracySelector = { if (it.hasAccuracy()) it.accuracy else Float.POSITIVE_INFINITY }
    )
}

internal fun <T> chooseBestCandidate(
    candidates: List<T>,
    timeSelector: (T) -> Long,
    accuracySelector: (T) -> Float
): T? {
    return candidates.maxWithOrNull { left, right ->
        // Prefer better accuracy (lower value) first; break ties with recency
        val leftAccuracy = accuracySelector(left).takeIf { it.isFinite() && it > 0f } ?: Float.POSITIVE_INFINITY
        val rightAccuracy = accuracySelector(right).takeIf { it.isFinite() && it > 0f } ?: Float.POSITIVE_INFINITY
        val accuracyComparison = rightAccuracy.compareTo(leftAccuracy)
        if (accuracyComparison != 0) {
            accuracyComparison
        } else {
            timeSelector(left).compareTo(timeSelector(right))
        }
    }
}

/**
 * Returns the first result that [accept] takes, cancelling the requests still running.
 * Gives up with null after [timeoutMs], so a source that never answers cannot hold the caller.
 */
internal suspend fun <T : Any> awaitFirstMatching(
    requests: List<Deferred<T?>>,
    timeoutMs: Long = FIRST_LOCATION_TIMEOUT_MS,
    accept: (T) -> Boolean
): T? {
    val pending = requests.toMutableList()
    try {
        return withTimeoutOrNull(timeoutMs) {
            while (pending.isNotEmpty()) {
                val (finished, result) = select<Pair<Deferred<T?>, T?>> {
                    pending.forEach { request ->
                        request.onAwait { value -> request to value }
                    }
                }
                pending.remove(finished)
                if (result != null && accept(result)) return@withTimeoutOrNull result
            }
            null
        }
    } finally {
        pending.forEach { it.cancel() }
    }
}

/** Which live source a request of [awaitPreciseEnough] is. */
internal enum class LiveSource { GPS, PASSIVE, OTHER }

/**
 * The locate button's wait (the Qibla's refinement keeps [detectCurrentLocation][DelegationLocator.detectCurrentLocation],
 * which waits for GPS). A fresh fix (taken within [FRESH_FIX_MS]) from GPS, or within [GOOD_ENOUGH_ACCURACY_METERS] from
 * any source, ends the wait, and the best fresh live fix is the answer: an older one may be from a place left since.
 * Without such a fix, the best of the live fixes and [known] is, as before. Once fused and network have
 * answered (passive only relays them) with a fix within [COARSE_ENOUGH_ACCURACY_METERS], GPS gets [gpsGraceMs] more:
 * indoors it never answers, and it used to hold every tap for its whole timeout. A coarser best still leaves GPS
 * the full [timeoutMs], so a fine GPS fix beats a coarse network one. The requests still running are cancelled.
 */
internal suspend fun <T : Any> awaitPreciseEnough(
    requests: List<Pair<LiveSource, Deferred<T?>>>,
    known: T?,
    best: (List<T>) -> T?,
    accuracyMeters: (T) -> Float,
    ageMs: (T) -> Long,
    timeoutMs: Long = FIRST_LOCATION_TIMEOUT_MS,
    gpsGraceMs: Long = GPS_GRACE_MS,
): T? = coroutineScope {
    // The grace timer belongs to this scope: started under withTimeoutOrNull, it would hold the answer until it ran out.
    val scope = this
    var answer = best(listOfNotNull(known))
    // The grace and an early answer follow fresh live fixes only: the known fix, or a fix a source kept, may be minutes
    // old, and a place left since then must not win on accuracy over the fix that ended the wait.
    var liveAnswer: T? = null
    var settled = false
    val pending = requests.toMutableList()
    var grace: Deferred<Unit>? = null
    try {
        withTimeoutOrNull(timeoutMs) {
            while (pending.isNotEmpty()) {
                val finished = select<Pair<Pair<LiveSource, Deferred<T?>>, T?>?> {
                    pending.forEach { request -> request.second.onAwait { value -> request to value } }
                    grace?.onAwait { null }
                }
                if (finished == null) {
                    // GPS had its grace: the fresh fix that started it answers.
                    settled = true
                    break
                }
                val (request, fix) = finished
                pending.remove(request)
                // Only a usable live fix counts: best() drops stale and malformed ones.
                if (fix != null && best(listOf(fix)) != null) {
                    answer = best(listOfNotNull(answer, fix))
                    if (ageMs(fix) <= FRESH_FIX_MS) {
                        liveAnswer = best(listOfNotNull(liveAnswer, fix))
                        if (request.first == LiveSource.GPS || accuracyMeters(fix) <= GOOD_ENOUGH_ACCURACY_METERS) {
                            settled = true
                            break
                        }
                    }
                }
                val coarseEnough = liveAnswer?.let { accuracyMeters(it) <= COARSE_ENOUGH_ACCURACY_METERS } == true
                if (grace == null && coarseEnough && pending.none { it.first == LiveSource.OTHER }) {
                    grace = scope.async { delay(gpsGraceMs) }
                }
            }
        }
    } finally {
        pending.forEach { it.second.cancel() }
        grace?.cancel()
    }
    if (settled) liveAnswer else answer
}

// Indoor Wi-Fi fixes are about 15 m; a coarser one gives GPS its short grace, for the neighbourhood's name near an edge.
private const val GOOD_ENOUGH_ACCURACY_METERS = 30f
private const val COARSE_ENOUGH_ACCURACY_METERS = 100f
private const val GPS_GRACE_MS = 2_000L
/** How old a fix may be to answer the locate button before every source has had its chance. */
private const val FRESH_FIX_MS = 30_000L
