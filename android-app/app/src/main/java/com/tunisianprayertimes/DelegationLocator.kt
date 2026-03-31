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
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val FUSED_TIMEOUT_MS = 8_000L
private const val LOCATION_PROVIDER_TIMEOUT_MS = 8_000L
private const val MAX_LAST_LOCATION_AGE_MS = 10 * 60 * 1_000L

sealed interface DelegationLocationResult {
    data class Success(val delegation: Delegation) : DelegationLocationResult
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
    val requestedPermissions: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    fun hasLocationPermission(context: Context): Boolean {
        return locationPermissionState(context).hasAny
    }

    suspend fun detectNearestDelegation(context: Context): DelegationLocationResult {
        val permissionState = locationPermissionState(context)
        if (!permissionState.hasAny) {
            return DelegationLocationResult.PermissionDenied
        }

        val location = findCurrentLocation(context, permissionState)
            ?: return DelegationLocationResult.LocationUnavailable

        val nearest = GouvernoratRepository.findNearestDelegation(
            context = context,
            lat = location.latitude,
            lng = location.longitude
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
        val fusedLocation = runCatching {
            currentFusedLocation(context, permissionState)
        }.getOrNull()

        if (fusedLocation != null) {
            return fusedLocation
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (fallbackProviders(permissionState).none { isProviderEnabled(locationManager, it) }) {
            return null
        }

        for (provider in fallbackProviders(permissionState)) {
            if (!isProviderEnabled(locationManager, provider)) {
                continue
            }

            val liveLocation = currentProviderLocation(locationManager, provider)
            if (liveLocation != null) {
                return liveLocation
            }
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
        val timeComparison = timeSelector(left).compareTo(timeSelector(right))
        if (timeComparison != 0) {
            timeComparison
        } else {
            accuracySelector(right).compareTo(accuracySelector(left))
        }
    }
}