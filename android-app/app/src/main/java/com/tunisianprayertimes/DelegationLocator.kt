package com.tunisianprayertimes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.selects.select

private const val FUSED_TIMEOUT_MS = 8_000L
private const val LOCATION_PROVIDER_TIMEOUT_MS = 8_000L
private const val MAX_LAST_LOCATION_AGE_MS = 5 * 60 * 1_000L
private const val MAX_SILENT_UPDATE_ACCURACY_METERS = 10_000f
private const val LAST_FUSED_LOCATION_TIMEOUT_MS = 2_000L

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

sealed interface DelegationLocationResult {
    data class Success(val delegation: Delegation, val locality: Locality? = null) : DelegationLocationResult
    data object PermissionDenied : DelegationLocationResult
    data object LocationUnavailable : DelegationLocationResult
    data object NoDelegationFound : DelegationLocationResult
    data object OutsideTunisia : DelegationLocationResult
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

    /**
     * Uses a recent cached or fused location to silently update the saved
     * delegation if the user has moved to a different one. Returns true if the
     * delegation changed.
     */
    suspend fun updateDelegationFromLastLocation(context: Context): Boolean {
        return updateDelegationFromLocation(context) { permissionState ->
            locationProvider.findRecentLocation(context, permissionState)
        }
    }

    /**
     * Uses a bounded fresh-location lookup before silently updating the saved
     * delegation. Use this from scheduled background checks where a short
     * location wait is acceptable.
     */
    suspend fun updateDelegationFromCurrentLocation(context: Context): Boolean {
        return updateDelegationFromLocation(context) { permissionState ->
            locationProvider.findFreshLocation(context, permissionState)
        }
    }

    private suspend fun updateDelegationFromLocation(
        context: Context,
        locationSource: suspend (LocationPermissionState) -> Location?
    ): Boolean {
        val permissionState = locationPermissionState(context)
        if (!permissionState.hasAny) return false

        val location: Location? = try {
            locationSource(permissionState)
        } catch (_: Exception) {
            null
        }

        if (location == null) return false
        if (!isUsableSilentUpdateLocation(location)) return false

        val accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else null
        val result = withContext(Dispatchers.IO) {
            resolveGpsLocation(context, location.latitude, location.longitude, accuracyMeters)
        } as? DelegationLocationResult.Success ?: return false
        val currentId = PrefsManager.getDelegationId(context)
        // Refresh the neighborhood even when travel stays within one timetable's area.
        PrefsManager.setGpsLocation(context, result)
        return result.delegation.id != currentId
    }

    suspend fun detectNearestDelegation(context: Context): DelegationLocationResult {
        val permissionState = locationPermissionState(context)
        if (!permissionState.hasAny) {
            return DelegationLocationResult.PermissionDenied
        }

        val location = findCurrentLocation(context, permissionState)
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
        val index = runCatching { NeighborhoodRepository.load(context) }.getOrNull()
        val insideCountry = index?.isInsideCountry(lat, lng) ?: isInsideTunisiaBounds(lat, lng)
        if (!insideCountry) return DelegationLocationResult.OutsideTunisia
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
        permissionState: LocationPermissionState
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
            .setMaxUpdateAgeMillis(MAX_LAST_LOCATION_AGE_MS)
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

/** Returns the first result that [accept] takes, cancelling the requests still running. */
internal suspend fun <T : Any> awaitFirstMatching(
    requests: List<Deferred<T?>>,
    accept: (T) -> Boolean
): T? {
    val pending = requests.toMutableList()
    try {
        while (pending.isNotEmpty()) {
            val (finished, result) = select<Pair<Deferred<T?>, T?>> {
                pending.forEach { request ->
                    request.onAwait { value -> request to value }
                }
            }
            pending.remove(finished)
            if (result != null && accept(result)) return result
        }
        return null
    } finally {
        pending.forEach { it.cancel() }
    }
}
