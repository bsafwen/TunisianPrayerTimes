package com.tunisianprayertimes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.ZoneId
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A change of the phone's time zone (plan 6.8, D20). With auto-location on, a last-location fix within 3 s goes
 * through the travel comparison at once; a slower fix is left to this job, which tries a fresh one. With no fix
 * from either, and the phone now in another time zone than the saved place's (even one with the same UTC offset:
 * Tunis and Paris share UTC+1 in winter, not their prayer times), the job posts one notification that opens the
 * city picker. It never guesses a city from the zone alone; without auto-location nothing moves.
 * Without the background-location permission no fix can come while no screen of the app is open, so the receiver
 * posts that notification itself, without the wait or the job.
 */
class TravelPlaceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val outcome = runCatching { DelegationLocator.updatePlaceFromCurrentLocation(app) }
            .onFailure { Log.w(TAG, "No fresh fix after a time-zone change", it) }
            .getOrDefault(DelegationLocator.FixOutcome.NO_FIX)
        if (outcome == DelegationLocator.FixOutcome.NO_FIX && placeZoneDiffers(app)) notifyChoosePlace(app)
        return Result.success()
    }

    companion object {
        private const val TAG = "TravelPlace"
        private const val WORK_NAME = "travel_place_after_zone_change"
        private const val CHANNEL = "location"
        private const val NOTIFICATION_ID = 7_302
        private const val FAST_FIX_MS = 3_000L

        /** The receiver's half (6.8): a quick last-location fix, or this job when none comes in time. */
        suspend fun checkAfterZoneChange(context: Context) {
            if (!PrefsManager.isAutoLocationUpdateEnabled(context) || !DelegationLocator.hasLocationPermission(context)) return
            if (!DelegationLocator.canLocateFromBackground(context)) {
                if (placeZoneDiffers(context)) notifyChoosePlace(context)
                return
            }
            val outcome = withTimeoutOrNull(FAST_FIX_MS) { DelegationLocator.updatePlaceFromLastLocation(context) }
            if (outcome == null || outcome == DelegationLocator.FixOutcome.NO_FIX) enqueue(context)
        }

        fun enqueue(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<TravelPlaceWorker>().build(),
                )
            }.onFailure { Log.w(TAG, "WorkManager unavailable, no slow fix", it) }
        }

        /**
         * True when the phone is now in another time zone than the saved place's, even one with the same UTC offset
         * now: Tunis and Paris share UTC+1 in winter, and Paris and Brussels all year, but not their prayer times.
         */
        internal fun placeZoneDiffers(context: Context, phoneZone: ZoneId = ZoneId.systemDefault()): Boolean =
            PrefsManager.getPlace(context).zone.normalized().id != phoneZone.normalized().id

        private fun notifyChoosePlace(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.location_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.mosque_silhouette)
                .setContentTitle(context.getString(R.string.travel_zone_changed_title))
                .setContentText(context.getString(R.string.travel_zone_changed_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.travel_zone_changed_text)))
                .setAutoCancel(true)
                .setContentIntent(MainActivityPendingIntents.choosePlace(context))
                .build()
            runCatching { manager.notify(NOTIFICATION_ID, notification) }
        }
    }
}
