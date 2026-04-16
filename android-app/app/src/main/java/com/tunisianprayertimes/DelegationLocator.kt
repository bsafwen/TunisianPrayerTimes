package com.tunisianprayertimes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val FUSED_TIMEOUT_MS = 8_000L
private const val LOCATION_PROVIDER_TIMEOUT_MS = 8_000L
private const val MAX_LAST_LOCATION_AGE_MS = 5 * 60 * 1_000L

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
    data class Success(val delegation: Delegation) : DelegationLocationResult
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
    private const val TAG = "DelegationLocator"

    val requestedPermissions: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    fun hasLocationPermission(context: Context): Boolean {
        return locationPermissionState(context).hasAny
    }

    /**
     * Uses the cached last-known location to silently update the saved delegation
     * if the user has moved to a different one. Returns true if the delegation changed.
     */
    @SuppressLint("MissingPermission")
    suspend fun updateDelegationFromLastLocation(context: Context): Boolean {
        if (!hasLocationPermission(context)) return false

        val location: Location? = try {
            suspendCancellableCoroutine { continuation ->
                LocationServices.getFusedLocationProviderClient(context)
                    .lastLocation
                    .addOnSuccessListener { loc: Location? ->
                        if (continuation.isActive) continuation.resume(loc)
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(null)
                    }
            }
        } catch (_: Exception) {
            null
        }

        if (location == null) return false

        val lat = location.latitude
        val lng = location.longitude

        if (!isInsideTunisiaBounds(lat, lng)) return false

        val delegation = DelegationBoundaryRepository.findDelegationForLocation(
            context = context, lat = lat, lng = lng
        ) ?: GouvernoratRepository.findNearestDelegation(
            context = context, lat = lat, lng = lng
        ) ?: return false

        val currentId = PrefsManager.getDelegationId(context)
        if (delegation.id == currentId) return false

        Log.d(TAG, "Delegation changed: $currentId -> ${delegation.id} (${delegation.nomAr})")
        PrefsManager.setDelegationId(context, delegation.id)
        return true
    }

    suspend fun detectNearestDelegation(context: Context): DelegationLocationResult {
        val permissionState = locationPermissionState(context)
        if (!permissionState.hasAny) {
            return DelegationLocationResult.PermissionDenied
        }

        val location = findCurrentLocation(context, permissionState)
            ?: return DelegationLocationResult.LocationUnavailable

        val lat = location.latitude
        val lng = location.longitude

        if (!isInsideTunisiaBounds(lat, lng)) {
            return DelegationLocationResult.OutsideTunisia
        }

        val nearest = DelegationBoundaryRepository.findDelegationForLocation(
            context = context,
            lat = lat,
            lng = lng
        ) ?: GouvernoratRepository.findNearestDelegation(
            context = context,
            lat = lat,
            lng = lng
        )

        return if (nearest != null) {
            DelegationLocationResult.Success(nearest)
        } else {
            DelegationLocationResult.NoDelegationFound
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun findCurrentLocation(
        context: Context,
        permissionState: LocationPermissionState
    ): Location? {
        val candidates = mutableListOf<Location>()

        // Try fused location (may return a cached/coarse fix)
        runCatching { currentFusedLocation(context, permissionState) }
            .getOrNull()
            ?.let { candidates.add(it) }

        // Always also try NETWORK_PROVIDER — it consistently returns
        // a fresh, accurate fix that is good enough for delegation matching.
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val networkProvider = LocationManager.NETWORK_PROVIDER
        if (isProviderEnabled(locationManager, networkProvider) && permissionState.hasAny) {
            currentProviderLocation(locationManager, networkProvider)
                ?.let { candidates.add(it) }
        }

        // If we have candidates, pick the most accurate one
        if (candidates.isNotEmpty()) {
            return chooseBestLocation(candidates)
        }

        // Fall back to other providers (GPS, passive)
        for (provider in fallbackProviders(permissionState)) {
            if (provider == networkProvider) continue // already tried
            if (!isProviderEnabled(locationManager, provider)) continue

            val liveLocation = currentProviderLocation(locationManager, provider)
            if (liveLocation != null) return liveLocation
        }

        return freshestKnownLocation(locationManager, permissionState)
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
        val now = System.currentTimeMillis()
        val candidates = fallbackProviders(permissionState)
            .filter { isProviderEnabled(locationManager, it) }
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .filter { location -> now - location.time <= MAX_LAST_LOCATION_AGE_MS }

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

internal fun chooseBestLocation(candidates: List<Location>): Location? {
    return chooseBestCandidate(
        candidates = candidates,
        timeSelector = { it.time },
        accuracySelector = { it.accuracy }
    )
}

internal fun <T> chooseBestCandidate(
    candidates: List<T>,
    timeSelector: (T) -> Long,
    accuracySelector: (T) -> Float
): T? {
    return candidates.maxWithOrNull { left, right ->
        // Prefer better accuracy (lower value) first; break ties with recency
        val accuracyComparison = accuracySelector(right).compareTo(accuracySelector(left))
        if (accuracyComparison != 0) {
            accuracyComparison
        } else {
            timeSelector(left).compareTo(timeSelector(right))
        }
    }
}