package com.tunisianprayertimes.stage0.execution

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/** Bounded redacted evidence: no coordinates, area names, paths or location-provider messages. */
internal object ExecutionJournal {
    private const val PREFS = "bounded_execution_probe"
    private const val LIMIT = 240
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun boot(context: Context): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    @Synchronized
    fun event(context: Context, event: String, detail: String = "", route: String = "", sessionId: String = "", fields: Map<String, Any> = emptyMap()) {
        val storage = prefs(context)
        val previous = runCatching { JSONArray(storage.getString("events", "[]")) }.getOrDefault(JSONArray())
        val entry = JSONObject().put("event", event).put("detail", detail).put("route", route)
            .put("session", sessionId).put("elapsed_ms", SystemClock.elapsedRealtime())
        for ((key, value) in fields) entry.put(key, value)
        val updated = JSONArray()
        for (index in maxOf(0, previous.length() - LIMIT + 1) until previous.length()) updated.put(previous.get(index))
        updated.put(entry)
        storage.edit().putString("events", updated.toString()).commit()
    }

    @Synchronized
    fun begin(context: Context, id: String, durationMs: Long): Long {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        prefs(context).edit().putString("session", id).putLong("deadline_elapsed", deadline)
            .putInt("boot", boot(context)).commit()
        return deadline
    }

    @Synchronized
    fun currentSession(context: Context): String = prefs(context).getString("session", "").orEmpty()

    @Synchronized
    fun armStart(context: Context, id: String, trigger: Long) {
        prefs(context).edit().putString("armed_start", id).putLong("armed_trigger", trigger)
            .putInt("armed_boot", boot(context)).commit()
    }

    @Synchronized
    fun armedStart(context: Context): String = prefs(context).getString("armed_start", "").orEmpty()

    @Synchronized
    fun consumeArmedStart(context: Context, id: String, trigger: Long): Boolean {
        val storage = prefs(context)
        if (id.isEmpty() || armedStart(context) != id || trigger <= 0L ||
            trigger != storage.getLong("armed_trigger", 0L) || storage.getInt("armed_boot", -2) != boot(context)) return false
        storage.edit().remove("armed_start").remove("armed_trigger").remove("armed_boot").commit()
        return true
    }

    @Synchronized
    fun clearArmedStart(context: Context) {
        prefs(context).edit().remove("armed_start").remove("armed_trigger").remove("armed_boot").commit()
    }

    @Synchronized
    fun reserveServiceStart(context: Context, id: String) {
        prefs(context).edit().putString("pending_service", id).commit()
    }

    @Synchronized
    fun consumeServiceStart(context: Context, id: String): Boolean {
        val storage = prefs(context)
        if (id.isEmpty() || storage.getString("pending_service", "") != id) return false
        storage.edit().remove("pending_service").commit()
        return true
    }

    @Synchronized
    fun beginGeofence(context: Context, generation: String, lifetime: Long) {
        prefs(context).edit().putString("geofence_generation", generation)
            .putLong("geofence_deadline", SystemClock.elapsedRealtime() + lifetime)
            .putInt("geofence_boot", boot(context)).putBoolean("geofence_used", false).commit()
    }

    @Synchronized
    fun geofenceGeneration(context: Context): String = prefs(context).getString("geofence_generation", "").orEmpty()

    @Synchronized
    fun geofenceScopeMatches(context: Context, generation: String): Boolean = generation.isNotEmpty() &&
        geofenceGeneration(context) == generation && prefs(context).getInt("geofence_boot", -2) == boot(context) &&
        prefs(context).getLong("geofence_deadline", 0L) > SystemClock.elapsedRealtime()

    @Synchronized
    fun consumeGeofenceStart(context: Context, generation: String): Boolean {
        if (!geofenceScopeMatches(context, generation) || prefs(context).getBoolean("geofence_used", true)) return false
        prefs(context).edit().putBoolean("geofence_used", true).commit()
        return true
    }

    @Synchronized
    fun invalidateStarts(context: Context) {
        prefs(context).edit().remove("armed_start").remove("armed_trigger").remove("armed_boot")
            .remove("pending_service").remove("geofence_generation").remove("geofence_deadline")
            .remove("geofence_boot").remove("geofence_used").commit()
    }

    @Synchronized
    fun finish(context: Context, reason: String, expectedSession: String? = null) {
        val id = currentSession(context)
        if (expectedSession != null && id != expectedSession) return
        prefs(context).edit().remove("session").remove("deadline_elapsed").remove("boot").commit()
        if (id.isNotEmpty()) event(context, "session_finished", reason, sessionId = id)
    }

    @Synchronized
    fun recoverExpired(context: Context): Boolean {
        val storage = prefs(context)
        if (currentSession(context).isEmpty()) return false
        val expired = storage.getInt("boot", -2) != boot(context) ||
            storage.getLong("deadline_elapsed", 0) <= SystemClock.elapsedRealtime()
        if (!expired) return false
        val expiredId = currentSession(context)
        finish(context, "expired_or_previous_boot_recovery")
        context.stopService(android.content.Intent(context, ProbeLocationService::class.java))
        ExecutionProbe.cancelRelease(context, expiredId)
        return true
    }

    @Synchronized
    fun snapshot(context: Context): String = JSONObject()
        .put("active_session", currentSession(context))
        .put("deadline_elapsed_ms", prefs(context).getLong("deadline_elapsed", 0))
        .put("armed_start", armedStart(context))
        .put("geofence_generation", geofenceGeneration(context))
        .put("geofence_start_consumed", prefs(context).getBoolean("geofence_used", true))
        .put("events", runCatching { JSONArray(prefs(context).getString("events", "[]")) }.getOrDefault(JSONArray()))
        .toString(2)

    @Synchronized
    fun clear(context: Context) { prefs(context).edit().clear().commit() }
}
