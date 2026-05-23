package com.tunisianprayertimes

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import android.location.Location
import com.google.android.gms.location.LocationServices
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Periodic WorkManager worker that re-verifies and re-schedules silence alarms.
 * Acts as a safety net in case AlarmManager alarms are killed by OEM battery optimization.
 */
class SilenceVerifyWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SilenceVerifyWorker"
        private const val WORK_NAME = "silence_verify"
        private const val VERIFY_INTERVAL_HOURS = 6L
        private const val VERIFY_FLEX_HOURS = 2L

        fun enqueue(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
                val request = PeriodicWorkRequestBuilder<SilenceVerifyWorker>(
                    VERIFY_INTERVAL_HOURS,
                    TimeUnit.HOURS,
                    VERIFY_FLEX_HOURS,
                    TimeUnit.HOURS,
                )
                    .setConstraints(constraints)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "WorkManager not initialized, skipping enqueue")
            }
        }

        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "WorkManager not initialized, skipping cancel")
            }
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Verifying silence alarms")
        // Check for Ramadan date override from GitHub Pages
        RamadanOverrideChecker.startPollingIfNeeded()
        var refreshResult = "disabled"
        if (PrefsManager.isEnabled(applicationContext)) {
            if (isOutsideTunisia()) {
                refreshResult = "outside_tunisia"
                if (!PrefsManager.isDisabledOutsideTunisia(applicationContext)) {
                    Log.d(TAG, "User is outside Tunisia, cancelling silence alarms")
                    SilenceScheduler.cancelAll(applicationContext)
                    PrefsManager.setDisabledOutsideTunisia(applicationContext, true)
                }
            } else if (PrefsManager.isDisabledOutsideTunisia(applicationContext)) {
                refreshResult = "returned_to_tunisia"
                Log.d(TAG, "User returned to Tunisia, re-enabling silence alarms")
                PrefsManager.setDisabledOutsideTunisia(applicationContext, false)
                if (PrefsManager.isAutoLocationUpdateEnabled(applicationContext)) {
                    DelegationLocator.updateDelegationFromCurrentLocation(applicationContext)
                }
                SilenceScheduler.scheduleAll(applicationContext)
            } else {
                refreshResult = "success"
                if (PrefsManager.isAutoLocationUpdateEnabled(applicationContext)) {
                    if (DelegationLocator.updateDelegationFromCurrentLocation(applicationContext)) {
                        Log.d(TAG, "Delegation updated, rescheduling alarms")
                    }
                }
                SilenceScheduler.scheduleAll(applicationContext)
            }
        }
        AnalyticsTracker.scheduleRefreshResult(
            context = applicationContext,
            source = "silence_worker",
            result = refreshResult,
            silencePrayerCount = AnalyticsTracker.silencePrayerCount(applicationContext),
            wakeAlarmCount = AnalyticsTracker.enabledWakeAlarmCount(applicationContext),
        )
        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private suspend fun isOutsideTunisia(): Boolean {
        if (!DelegationLocator.hasLocationPermission(applicationContext)) {
            return false
        }

        val location: Location? = try {
            suspendCancellableCoroutine { continuation ->
                LocationServices.getFusedLocationProviderClient(applicationContext)
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

        return !isInsideTunisiaBounds(location.latitude, location.longitude)
    }
}
