package com.tunisianprayertimes.stage0.execution

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class ProbeLocationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var callback: LocationCallback? = null
    private var session = ""
    private var windowToken = ""
    private var deadline = 0L
    private var started = 0L
    private val timeout = Runnable {
        val live = WindowAuthority.peek(this)
        if (live?.token == windowToken && live.end > deadline &&
            WindowAuthority.reconcile(this, "continuous_window_timer")) {
            // A newly containing overlap/adjoining window supplies a new bounded service request.
        } else {
            finish("in_session_window_deadline")
            WindowAuthority.reconcile(this, "in_process_end")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val requestedSession = intent.getStringExtra(ExecutionProbe.EXTRA_SESSION).orEmpty()
        if (!ExecutionJournal.consumeServiceStart(this, requestedSession)) {
            ExecutionJournal.event(this, "service_start_ignored", "stopped_or_obsolete_enqueued_start", sessionId = requestedSession)
            if (ExecutionJournal.currentSession(this).isEmpty()) stopSelf(startId)
            return START_NOT_STICKY
        }
        val expectedWindow = intent.getStringExtra(WindowAuthority.EXTRA_TOKEN).orEmpty()
        val initialWindow = WindowAuthority.peek(this)
        if (initialWindow == null || initialWindow.token != expectedWindow) {
            ExecutionJournal.event(this, "service_start_ignored", "outside_or_obsolete_window", sessionId = requestedSession)
            if (session.isEmpty()) stopSelf(startId)
            return START_NOT_STICKY
        }
        removeObservation()
        ExecutionProbe.cancelRelease(this, session)
        ExecutionJournal.finish(this, "superseded_at_window_boundary", session)
        session = requestedSession
        windowToken = expectedWindow
        val route = intent.getStringExtra(ExecutionProbe.EXTRA_ROUTE).orEmpty()
        val duration = intent.getLongExtra(ExecutionProbe.EXTRA_DURATION, ExecutionProbe.DEFAULT_DURATION_MS).coerceIn(1L, ExecutionProbe.MAX_DURATION_MS)
        val requested = intent.getLongExtra(ExecutionProbe.EXTRA_REQUESTED_ELAPSED, SystemClock.elapsedRealtime())
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Bounded location feasibility", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                vibrationPattern = longArrayOf(0L)
                enableVibration(false)
                setShowBadge(false)
            })
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Location feasibility check")
                .setContentText("Bounded check; no sound, vibration, DND or ringer changes")
                .setOngoing(true).setOnlyAlertOnce(true).setDefaults(0).setSound(null).setVibrate(longArrayOf(0L))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop check", PendingIntent.getBroadcast(
                    this, 3104, ExecutionProbe.releaseIntent(this, session).putExtra("release_reason", "notification_stop"),
                    PendingIntent.FLAG_IMMUTABLE))
                .build()
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ExecutionJournal.event(this, "service_failed", "fine_location_missing", route, session)
                stopSelf()
                return START_NOT_STICKY
            }
            val live = WindowAuthority.peek(this)
            if (live == null || live.token != windowToken) { finish("window_ended_during_startup"); return START_NOT_STICKY }
            started = SystemClock.elapsedRealtime()
            deadline = ExecutionJournal.begin(this, session, duration, live.end)
            ExecutionJournal.event(this, "service_ready", "typed_location_foreground", route, session,
                mapOf("start_latency_ms" to (SystemClock.elapsedRealtime() - requested), "duration_ms" to (deadline - started),
                    "window_end_elapsed_ms" to live.end, "window_token" to windowToken, "deadline_elapsed_ms" to deadline))
            ExecutionProbe.armRelease(this, session, deadline)
            handler.postDelayed(timeout, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            beginObservation(route)
        } catch (failure: RuntimeException) {
            ExecutionJournal.event(this, "service_failed", failure.javaClass.simpleName, route, session)
            finish("service_creation_or_permission_failure")
        }
        return START_NOT_STICKY
    }

    private fun beginObservation(route: String) {
        val id = session
        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (id != session || ExecutionJournal.currentSession(this@ProbeLocationService) != id) return
                for (location in result.locations) recordFix(location, route, id)
            }
        }
        callback = listener
        val task = WindowAuthority.withLease(this, "provider_updates_request", windowToken) { live ->
            val at = SystemClock.elapsedRealtime()
            val until = minOf(deadline, live.end)
            if (until <= at) null else {
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
                    .setMinUpdateIntervalMillis(2_000L).setMaxUpdateAgeMillis(0)
                    .setDurationMillis(until - at).setWaitForAccurateLocation(false).build()
                val result = fused.requestLocationUpdates(request, listener, Looper.getMainLooper())
                ExecutionJournal.event(this, "provider_request", "finite_window_lifetime", route, id,
                    mapOf("native_call_elapsed_ms" to at, "request_duration_ms" to (until - at),
                        "window_start_elapsed_ms" to live.start, "window_end_elapsed_ms" to live.end, "window_token" to live.token))
                result
            }
        }
        if (task == null) { finish("window_ended_before_provider_request"); return }
        task.addOnSuccessListener { ExecutionJournal.event(this, "fix_request_accepted", "high_accuracy_not_accuracy_guarantee", route, id) }
            .addOnFailureListener {
                ExecutionJournal.event(this, "fix_request_failed", it.javaClass.simpleName, route, id)
                if (id == session) finish("provider_request_failed")
            }
    }

    @Suppress("DEPRECATION")
    private fun recordFix(location: Location, route: String, id: String) {
        WindowAuthority.withLease(this, "fix_callback", windowToken) { live ->
            val sample = location.elapsedRealtimeNanos / 1_000_000
            if (sample < maxOf(live.start, started)) {
                ExecutionJournal.event(this, "fix_ignored", "prewindow_or_previous_request_sample", route, id)
                return@withLease
            }
            val at = SystemClock.elapsedRealtime()
            val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
            val accuracy: Any = if (location.hasAccuracy() && location.accuracy.isFinite()) location.accuracy.toDouble() else "missing_or_nonfinite"
            ExecutionJournal.event(this, "fix", "redacted_no_containment_claim", route, id, mapOf(
                "accuracy_m" to accuracy, "age_ms" to ageMs,
                "fix_elapsed_ns" to location.elapsedRealtimeNanos, "mock" to location.isFromMockProvider,
                "evaluation_elapsed_ms" to at, "window_start_elapsed_ms" to live.start, "window_end_elapsed_ms" to live.end,
            ))
        }
    }

    private fun removeObservation() {
        handler.removeCallbacks(timeout)
        callback?.let { old ->
            val stoppedId = session
            val at = SystemClock.elapsedRealtime()
            runCatching { fused.removeLocationUpdates(old).addOnCompleteListener {
                ExecutionJournal.event(this, "provider_remove_complete", if (it.isSuccessful) "removed" else "failed", sessionId = stoppedId,
                    fields = mapOf("requested_elapsed_ms" to at))
            } }
            ExecutionJournal.event(this, "provider_remove_requested", "scoped_callback", sessionId = stoppedId)
        }
        callback = null
    }

    private fun finish(reason: String) {
        removeObservation()
        ExecutionProbe.cancelRelease(this, session)
        ExecutionJournal.finish(this, reason, session)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        removeObservation()
        ExecutionProbe.cancelRelease(this, session)
        ExecutionJournal.finish(this, "service_destroyed", session)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "bounded_execution_silent_v1"
        private const val NOTIFICATION = 3100
    }
}
