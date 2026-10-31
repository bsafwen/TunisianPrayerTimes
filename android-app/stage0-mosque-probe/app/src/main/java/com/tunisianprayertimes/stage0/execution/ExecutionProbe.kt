package com.tunisianprayertimes.stage0.execution

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.net.Uri
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.UUID
import org.json.JSONObject

/** Standalone, finite execution experiment. This package never changes DND or ringer state. */
object ExecutionProbe {
    const val DEFAULT_DURATION_MS = 60_000L
    const val MAX_DURATION_MS = 120_000L
    const val MAX_GEOFENCE_DURATION_MS = 300_000L
    const val ACTION_START_ALARM = "com.tunisianprayertimes.stage0.execution.START_ALARM"
    const val ACTION_RELEASE = "com.tunisianprayertimes.stage0.execution.RELEASE"
    const val ACTION_GEOFENCE = "com.tunisianprayertimes.stage0.execution.GEOFENCE"
    internal const val EXTRA_SESSION = "execution_session"
    internal const val EXTRA_DURATION = "execution_duration_ms"
    internal const val EXTRA_ROUTE = "execution_route"
    internal const val EXTRA_REQUESTED_ELAPSED = "execution_requested_elapsed_ms"
    internal const val EXTRA_TRIGGER_ELAPSED = "execution_trigger_elapsed_ms"
    private const val START_REQUEST = 3101
    private const val RELEASE_REQUEST = 3102
    private const val GEOFENCE_REQUEST = 3103
    private var centerCancellation: CancellationTokenSource? = null
    private const val GEOFENCE_ID = "explicit-bounded-private-probe"

    data class Outcome(val accepted: Boolean, val detail: String, val sessionId: String? = null)

    @JvmStatic
    fun startFromVisible(context: Context, durationMs: Long = DEFAULT_DURATION_MS): Outcome {
        stop(context, "new_explicit_visible_session")
        return requestStart(context, "visible_user_action", durationMs, SystemClock.elapsedRealtime())
    }

    internal fun requestStart(
        context: Context,
        route: String,
        durationMs: Long,
        requestedElapsed: Long,
        sessionId: String = UUID.randomUUID().toString(),
    ): Outcome {
        val lease = WindowAuthority.peek(context)
        if (lease == null) {
            ExecutionJournal.event(context, "window_guard_rejected", "service_start_request")
            return Outcome(false, "outside_window_or_inactive_mode", sessionId)
        }
        recoverExpired(context)
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ExecutionJournal.event(context, "start_rejected", "fine_location_missing", route, sessionId)
            return Outcome(false, "fine_location_missing", sessionId)
        }
        val intent = Intent(context, ProbeLocationService::class.java)
            .putExtra(EXTRA_ROUTE, route)
            .putExtra(EXTRA_SESSION, sessionId)
            .putExtra(EXTRA_DURATION, minOf(durationMs.coerceIn(1L, MAX_DURATION_MS), lease.end - SystemClock.elapsedRealtime()))
            .putExtra(WindowAuthority.EXTRA_TOKEN, lease.token)
            .putExtra(EXTRA_REQUESTED_ELAPSED, requestedElapsed)
        return try {
            ExecutionJournal.reserveServiceStart(context, sessionId)
            check(WindowAuthority.withLease(context, "start_foreground_service", lease.token) {
                context.startForegroundService(intent); true
            } == true) { "Window ended before service request" }
            ExecutionJournal.event(context, "start_request_accepted", "service_creation_not_yet_proven", route, sessionId)
            Outcome(true, "start_requested_wait_for_service_ready", sessionId)
        } catch (failure: RuntimeException) {
            ExecutionJournal.consumeServiceStart(context, sessionId)
            ExecutionJournal.event(context, "start_rejected", failure.javaClass.simpleName, route, sessionId)
            Outcome(false, failure.javaClass.simpleName, sessionId)
        }
    }

    /** Arms a real system exact alarm; an adb broadcast is not a successful alarm-route measurement. */
    @JvmStatic
    fun armAlarm(
        context: Context,
        delayMs: Long = 8_000L,
        durationMs: Long = DEFAULT_DURATION_MS,
    ): Outcome {
        stop(context, "new_explicit_alarm_scope")
        recoverExpired(context)
        val manager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
            ExecutionJournal.event(context, "alarm_not_armed", "exact_access_missing")
            return Outcome(false, "exact_access_missing")
        }
        val allowed = WindowAuthority.peek(context) ?: return Outcome(false, "outside_window_or_inactive_mode")
        val id = UUID.randomUUID().toString()
        val trigger = SystemClock.elapsedRealtime() + delayMs.coerceIn(2_000L, 60_000L)
        if (trigger >= allowed.end) return Outcome(false, "trigger_outside_current_window")
        val intent = Intent(context, ProbeStartAlarmReceiver::class.java)
            .setAction(ACTION_START_ALARM)
            .setData(identityUri("start", id))
            .putExtra(EXTRA_SESSION, id)
            .putExtra(EXTRA_DURATION, durationMs.coerceIn(1_000L, MAX_DURATION_MS))
            .putExtra(EXTRA_TRIGGER_ELAPSED, trigger)
            .putExtra(WindowAuthority.EXTRA_TOKEN, allowed.token)
        return try {
            ExecutionJournal.armStart(context, id, trigger)
            manager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger,
                PendingIntent.getBroadcast(context, START_REQUEST, intent, PendingIntent.FLAG_IMMUTABLE),
            )
            ExecutionJournal.event(context, "alarm_armed", "real_exact_idle_alarm", "exact_alarm", id, mapOf("trigger_elapsed_ms" to trigger))
            Outcome(true, "real_alarm_armed", id)
        } catch (failure: RuntimeException) {
            ExecutionJournal.consumeArmedStart(context, id, trigger)
            ExecutionJournal.event(context, "alarm_not_armed", failure.javaClass.simpleName, "exact_alarm", id)
            Outcome(false, failure.javaClass.simpleName, id)
        }
    }

    internal fun armRelease(context: Context, sessionId: String, deadlineElapsed: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending = releasePending(context, sessionId)
        try {
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadlineElapsed, pending)
                ExecutionJournal.event(context, "release_armed", "exact_idle_best_effort", sessionId = sessionId)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadlineElapsed, pending)
                ExecutionJournal.event(context, "release_armed", "inexact_idle_fallback_not_deadline_guarantee", sessionId = sessionId)
            }
        } catch (failure: RuntimeException) {
            ExecutionJournal.event(context, "release_arm_failed", failure.javaClass.simpleName, sessionId = sessionId)
        }
    }

    internal fun cancelRelease(context: Context, sessionId: String = ExecutionJournal.currentSession(context)) {
        if (sessionId.isEmpty()) return
        releasePending(context, sessionId).let {
            context.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
    }

    internal fun releaseIntent(context: Context, sessionId: String): Intent =
        Intent(context, ProbeReleaseReceiver::class.java).setAction(ACTION_RELEASE)
            .setData(identityUri("release", sessionId)).putExtra(EXTRA_SESSION, sessionId)

    internal fun releasePending(context: Context, sessionId: String): PendingIntent = PendingIntent.getBroadcast(
        context, RELEASE_REQUEST,
        releaseIntent(context, sessionId), PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun identityUri(kind: String, token: String): Uri = Uri.Builder().scheme("stage0-execution").authority(kind).appendPath(token).build()

    /** Only the caller can supply an area. Coordinates are neither logged nor written to this app's prefs. */
    @JvmStatic
    fun configureGeofence(
        context: Context,
        latitude: Double,
        longitude: Double,
        radiusMetres: Float,
        isSynthetic: Boolean,
        durationMs: Long = MAX_GEOFENCE_DURATION_MS,
        result: (Outcome) -> Unit,
    ) {
        val lease = WindowAuthority.peek(context)
        if (lease == null) {
            ExecutionJournal.event(context, "window_guard_rejected", "configure_geofence")
            result(Outcome(false, "outside_window_or_inactive_mode")); return
        }
        stop(context, "new_explicit_geofence_scope")
        val generation = UUID.randomUUID().toString()
        val lifetime = minOf(durationMs.coerceIn(1L, MAX_GEOFENCE_DURATION_MS), lease.end - SystemClock.elapsedRealtime())
        ExecutionJournal.beginGeofence(context, generation, lifetime)
        configureAuthorizedGeofence(context, latitude, longitude, radiusMetres, isSynthetic, lifetime, generation, result)
    }

    private fun configureAuthorizedGeofence(
        context: Context, latitude: Double, longitude: Double, radiusMetres: Float, isSynthetic: Boolean,
        durationMs: Long, generation: String, result: (Outcome) -> Unit,
    ) {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(radiusMetres.isFinite() && radiusMetres in 100f..1_000f)
        if (!ExecutionJournal.geofenceScopeMatches(context, generation)) {
            result(Outcome(false, "geofence_scope_stopped_or_expired"))
            return
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ExecutionJournal.event(context, "geofence_registration_rejected", "fine_or_background_location_missing")
            result(Outcome(false, "fine_or_background_location_missing"))
            return
        }
        val lease = WindowAuthority.peek(context)
        if (lease == null) { result(Outcome(false, "outside_window_or_inactive_mode")); return }
        val remaining = minOf(durationMs, lease.end - SystemClock.elapsedRealtime())
        if (remaining <= 0) { result(Outcome(false, "window_ended")); return }
        var registeredDuration = 0L
        try {
            val task = WindowAuthority.withLease(context, "geofence_registration", lease.token) { active ->
                val at = SystemClock.elapsedRealtime()
                registeredDuration = minOf(durationMs, active.end - at)
                if (registeredDuration <= 0) null else {
                    val fence = Geofence.Builder().setRequestId("$GEOFENCE_ID-$generation")
                        .setCircularRegion(latitude, longitude, radiusMetres).setExpirationDuration(registeredDuration)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_DWELL)
                        .setLoiteringDelay(30_000).setNotificationResponsiveness(10_000).build()
                    val request = GeofencingRequest.Builder().setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER).addGeofence(fence).build()
                    val added = LocationServices.getGeofencingClient(context).addGeofences(request, geofencePending(context, generation))
                    ExecutionJournal.event(context, "geofence_registration_request", "finite_window_lifetime", fields = mapOf(
                        "native_call_elapsed_ms" to at, "expiration_ms" to registeredDuration,
                        "window_start_elapsed_ms" to active.start, "window_end_elapsed_ms" to active.end))
                    added
                }
            }
            if (task == null) { result(Outcome(false, "window_ended_before_registration")); return }
            task.addOnSuccessListener {
                    if (!ExecutionJournal.geofenceScopeMatches(context, generation) || WindowAuthority.peek(context)?.token != lease.token) {
                        removeGeofence(context, generation)
                        result(Outcome(false, "registration_completed_after_stop_or_expiry"))
                        return@addOnSuccessListener
                    }
                    ExecutionJournal.event(context, "geofence_registered", if (isSynthetic) "explicit_synthetic_area" else "explicit_private_area", fields = mapOf("expiration_ms" to registeredDuration, "window_end_elapsed_ms" to lease.end))
                    result(Outcome(true, "registered_wait_for_real_gms_event_no_delivery_guarantee"))
                }.addOnFailureListener {
                    ExecutionJournal.event(context, "geofence_registration_rejected", it.javaClass.simpleName)
                    result(Outcome(false, it.javaClass.simpleName))
                }
        } catch (failure: RuntimeException) {
            ExecutionJournal.event(context, "geofence_registration_rejected", failure.javaClass.simpleName)
            result(Outcome(false, failure.javaClass.simpleName))
        }
    }

    /** Explicit visible-UI experiment: real fix remains ephemeral; the temporary circle is not mosque geometry. */
    @JvmStatic
    fun registerAtCurrentPosition(
        context: Context,
        radiusMetres: Float = 150f,
        result: (Outcome) -> Unit,
    ) {
        val lease = WindowAuthority.peek(context)
        if (lease == null) {
            ExecutionJournal.event(context, "window_guard_rejected", "geofence_center_request")
            result(Outcome(false, "outside_window_or_inactive_mode")); return
        }
        stop(context, "new_explicit_geofence_center_scope")
        val generation = UUID.randomUUID().toString()
        ExecutionJournal.beginGeofence(context, generation, minOf(MAX_GEOFENCE_DURATION_MS, lease.end - SystemClock.elapsedRealtime()))
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            result(Outcome(false, "fine_location_missing"))
            return
        }
        try {
            val cancellation = CancellationTokenSource()
            centerCancellation = cancellation
            val task = WindowAuthority.withLease(context, "geofence_center_provider", lease.token) { active ->
                val at = SystemClock.elapsedRealtime()
                val duration = minOf(15_000L, active.end - at)
                if (duration <= 0) null else {
                    val request = CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .setMaxUpdateAgeMillis(0).setDurationMillis(duration).build()
                    val requested = LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request, cancellation.token)
                    ExecutionJournal.event(context, "center_provider_request", "finite_window_lifetime", fields = mapOf(
                        "native_call_elapsed_ms" to at, "request_duration_ms" to duration,
                        "window_start_elapsed_ms" to active.start, "window_end_elapsed_ms" to active.end))
                    requested
                }
            }
            if (task == null) { cancellation.cancel(); result(Outcome(false, "window_ended_before_center_request")); return }
            task.addOnSuccessListener { location ->
                    if (!ExecutionJournal.geofenceScopeMatches(context, generation) || WindowAuthority.peek(context)?.token != lease.token) {
                        result(Outcome(false, "center_completed_after_stop_or_expiry"))
                        return@addOnSuccessListener
                    }
                    if (location == null) {
                        ExecutionJournal.event(context, "geofence_center_unavailable", "no_real_fresh_fix")
                        result(Outcome(false, "no_real_fresh_fix"))
                    } else if (location.elapsedRealtimeNanos / 1_000_000 < lease.start) {
                        result(Outcome(false, "prewindow_center_fix_rejected"))
                    } else {
                        ExecutionJournal.event(context, "geofence_center_acquired", "coordinates_ephemeral_not_logged_or_saved")
                        configureAuthorizedGeofence(context, location.latitude, location.longitude, radiusMetres,
                            isSynthetic = true, durationMs = MAX_GEOFENCE_DURATION_MS, generation = generation, result = result)
                    }
                }.addOnFailureListener {
                    ExecutionJournal.event(context, "geofence_center_unavailable", it.javaClass.simpleName)
                    result(Outcome(false, it.javaClass.simpleName))
                }
        } catch (failure: RuntimeException) {
            ExecutionJournal.event(context, "geofence_center_unavailable", failure.javaClass.simpleName)
            result(Outcome(false, failure.javaClass.simpleName))
        }
    }

    internal fun processImportance(): Int = ActivityManager.RunningAppProcessInfo().also {
        ActivityManager.getMyMemoryState(it)
    }.importance

    private fun geofencePending(context: Context, generation: String): PendingIntent {
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, GEOFENCE_REQUEST,
            Intent(context, ProbeGeofenceReceiver::class.java).setAction(ACTION_GEOFENCE)
                .setData(identityUri("geofence", generation)), mutable)
    }

    private fun removeGeofence(context: Context, generation: String) {
        if (generation.isEmpty()) return
        val pending = geofencePending(context, generation)
        try {
            LocationServices.getGeofencingClient(context).removeGeofences(listOf("$GEOFENCE_ID-$generation"))
                .addOnCompleteListener { ExecutionJournal.event(context, "geofence_remove_complete", if (it.isSuccessful) "removed" else "removal_failed_or_unavailable") }
        } catch (_: RuntimeException) { /* The expired authorization still rejects callbacks. */ }
        pending.cancel()
    }

    @JvmStatic
    fun stop(context: Context, reason: String = "explicit_stop") {
        centerCancellation?.cancel(); centerCancellation = null
        val armed = ExecutionJournal.armedStart(context)
        val generation = ExecutionJournal.geofenceGeneration(context)
        val active = ExecutionJournal.currentSession(context)
        ExecutionJournal.invalidateStarts(context)
        if (armed.isNotEmpty()) {
            val pending = PendingIntent.getBroadcast(context, START_REQUEST,
                Intent(context, ProbeStartAlarmReceiver::class.java).setAction(ACTION_START_ALARM)
                    .setData(identityUri("start", armed)), PendingIntent.FLAG_IMMUTABLE)
            context.getSystemService(AlarmManager::class.java).cancel(pending)
            pending.cancel()
        }
        removeGeofence(context, generation)
        cancelRelease(context, active)
        context.stopService(Intent(context, ProbeLocationService::class.java))
        ExecutionJournal.finish(context, reason)
    }

    /** Clears expired/reboot-stale state without reviving a location session. */
    @JvmStatic
    fun recoverExpired(context: Context): Boolean = ExecutionJournal.recoverExpired(context)

    @JvmStatic
    fun diagnostics(context: Context): String = JSONObject(ExecutionJournal.snapshot(context)).put("window", WindowAuthority.snapshot(context)).toString(2)

    @JvmStatic
    fun wipe(context: Context) {
        stop(context, "wipe")
        ExecutionJournal.clear(context)
    }
}
