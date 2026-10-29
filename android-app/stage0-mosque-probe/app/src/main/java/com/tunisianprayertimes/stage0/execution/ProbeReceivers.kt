package com.tunisianprayertimes.stage0.execution

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class ProbeStartAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ExecutionProbe.ACTION_START_ALARM) return
        val id = intent.getStringExtra(ExecutionProbe.EXTRA_SESSION) ?: return
        val trigger = intent.getLongExtra(ExecutionProbe.EXTRA_TRIGGER_ELAPSED, 0)
        if (intent.data != ExecutionProbe.identityUri("start", id) || !ExecutionJournal.consumeArmedStart(context, id, trigger)) {
            ExecutionJournal.event(context, "alarm_ignored", "unarmed_stale_or_already_consumed", "exact_alarm", id)
            return
        }
        val now = SystemClock.elapsedRealtime()
        ExecutionJournal.event(context, "alarm_received", "real_alarm_route_requires_system_delivery_evidence", "exact_alarm", id,
            mapOf("delivery_lateness_ms" to if (trigger > 0) now - trigger else -1L,
                "receiver_process_importance" to ExecutionProbe.processImportance(),
                "service_session_already_active" to ExecutionJournal.currentSession(context).isNotEmpty()))
        ExecutionProbe.requestStart(context, "exact_alarm", intent.getLongExtra(ExecutionProbe.EXTRA_DURATION, ExecutionProbe.DEFAULT_DURATION_MS), now, id)
    }
}

class ProbeReleaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ExecutionProbe.ACTION_RELEASE) return
        val id = intent.getStringExtra(ExecutionProbe.EXTRA_SESSION) ?: return
        if (intent.data != ExecutionProbe.identityUri("release", id) || id != ExecutionJournal.currentSession(context)) {
            ExecutionJournal.event(context, "release_ignored", "stale_session", sessionId = id)
            return
        }
        val reason = if (intent.getStringExtra("release_reason") == "notification_stop") "notification_stop" else "watchdog_release"
        ExecutionJournal.event(context, "release_receiver", reason, sessionId = id)
        ExecutionProbe.stop(context, reason)
    }
}

/** Only a real Play-services pending-intent payload counts as a geofence-route observation. */
class ProbeGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ExecutionProbe.ACTION_GEOFENCE) return
        val generation = intent.data?.lastPathSegment.orEmpty()
        if (intent.data != ExecutionProbe.identityUri("geofence", generation) || !ExecutionJournal.geofenceScopeMatches(context, generation)) {
            ExecutionJournal.event(context, "geofence_ignored", "stopped_expired_or_obsolete_scope")
            return
        }
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null || event.hasError()) {
            ExecutionJournal.event(context, "geofence_unusable_event", if (event == null) "missing_gms_payload" else "gms_error_${event.errorCode}")
            return
        }
        val transition = event.geofenceTransition
        ExecutionJournal.event(context, "geofence_received", "gms_payload_route_not_polygon_presence", "geofence", fields = mapOf(
            "transition" to transition, "receiver_process_importance" to ExecutionProbe.processImportance(),
            "service_session_already_active" to ExecutionJournal.currentSession(context).isNotEmpty()))
        if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            ExecutionProbe.stop(context, "coarse_exit_ends_execution_probe_only")
        } else if (transition == Geofence.GEOFENCE_TRANSITION_ENTER || transition == Geofence.GEOFENCE_TRANSITION_DWELL) {
            if (!ExecutionJournal.consumeGeofenceStart(context, generation)) {
                ExecutionJournal.event(context, "geofence_ignored", "scope_already_started_no_session_extension")
                return
            }
            ExecutionProbe.requestStart(context, "geofence", ExecutionProbe.DEFAULT_DURATION_MS, SystemClock.elapsedRealtime())
        }
    }
}
