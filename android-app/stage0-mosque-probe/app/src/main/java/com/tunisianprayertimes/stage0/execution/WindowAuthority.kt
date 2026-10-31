package com.tunisianprayertimes.stage0.execution

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.tunisianprayertimes.stage0.dnd.DndSessionProbe
import com.tunisianprayertimes.stage0.model.ProbeMode
import com.tunisianprayertimes.stage0.model.ProbeWindow
import com.tunisianprayertimes.stage0.model.WindowScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs

/** Finite test schedules only. Never reads/edits the production prayer app's preferences or times. */
object WindowAuthority {
    const val ACTION = "com.tunisianprayertimes.stage0.window.BOUNDARY"
    const val EXTRA_TOKEN = "window_token"
    private const val PREFS = "window_authority"
    private val lock = Any()
    private val endHandler = Handler(Looper.getMainLooper())
    private val endCallbacks = mutableListOf<Runnable>()
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun boot(c: Context) = Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)
    data class Lease(val token: String, val start: Long, val end: Long, val occurrences: Set<String>)

    private fun read(c: Context): JSONObject? = prefs(c).getString("schedule", null)?.let(::JSONObject)
    private fun windows(j: JSONObject): List<ProbeWindow> {
        val values = j.getJSONArray("windows")
        return (0 until values.length()).map { i -> values.getJSONObject(i).let {
            ProbeWindow(it.getString("id"), it.getLong("start"), it.getLong("end")) } }
    }
    private fun dismissed(j: JSONObject): Set<String> = j.getJSONArray("dismissed").let { a ->
        (0 until a.length()).map { a.getString(it) }.toSet() }

    fun peek(c: Context): Lease? {
        val j = read(c) ?: return null
        val now = SystemClock.elapsedRealtime()
        if (j.getInt("boot") != boot(c) || now < j.getLong("elapsed_anchor") ||
            abs((System.currentTimeMillis() - j.getLong("wall_anchor")) - (now - j.getLong("elapsed_anchor"))) > 2_000 ||
            j.getString("mode") != ProbeMode.TIME_AND_MOSQUE.name || !j.getBoolean("master")) return null
        val coverage = WindowScope.coverage(windows(j), dismissed(j), now) ?: return null
        return Lease("${j.getString("generation")}:${coverage.start}", coverage.start, coverage.activeEnd, coverage.occurrences)
    }

    /** All native provider/service/registration effects share this check-and-effect lock. */
    fun <T> withLease(c: Context, operation: String, expected: String? = null, block: (Lease) -> T): T? = synchronized(lock) {
        val live = peek(c)
        if (live == null || (expected != null && expected != live.token)) {
            ExecutionJournal.event(c, "window_guard_rejected", operation,
                fields = mapOf("active_window" to (live != null), "obsolete_token" to (expected != null && live?.token != expected)))
            null
        } else block(live)
    }

    fun configure(c: Context, delay: Long, duration: Long, secondDuration: Long = 0, gap: Long = 0,
                  mode: String = ProbeMode.TIME_AND_MOSQUE.name, armBoundaries: Boolean = true): JSONObject = synchronized(lock) {
        close(c, "explicit_scope_replacement")
        val selected = runCatching { ProbeMode.valueOf(mode) }.getOrNull()
            ?: return JSONObject().put("accepted", false).put("reason", "unsupported_mode")
        val manager = c.getSystemService(AlarmManager::class.java)
        check(Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) { "Exact boundary access missing" }
        val now = SystemClock.elapsedRealtime()
        val start = now + delay.coerceIn(0, 60_000)
        val end = start + duration.coerceIn(1_000, 120_000)
        val list = mutableListOf(ProbeWindow("fixture-a", start, end))
        if (secondDuration > 0) {
            val secondStart = end + gap
            require(secondStart >= start && secondStart <= end + 60_000)
            list += ProbeWindow("fixture-b", secondStart, secondStart + secondDuration.coerceIn(1_000, 120_000))
        }
        require(list.maxOf { it.end } - now <= 300_000)
        val j = JSONObject().put("generation", UUID.randomUUID().toString()).put("boot", boot(c))
            .put("elapsed_anchor", now).put("wall_anchor", System.currentTimeMillis())
            .put("mode", selected.name).put("master", true).put("dismissed", JSONArray())
            .put("system_boundaries_armed", armBoundaries)
            .put("windows", JSONArray(list.map { JSONObject().put("id", it.occurrence).put("start", it.start).put("end", it.end) }))
        prefs(c).edit().putString("schedule", j.toString()).commit()
        try {
            if (armBoundaries) list.flatMap { listOf(it.start, it.end) }.distinct().forEach { at ->
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at,
                    boundary(c, j.getString("generation"), at, PendingIntent.FLAG_UPDATE_CURRENT)!!)
            }
        } catch (failure: RuntimeException) { close(c, "boundary_arm_failure"); throw failure }
        val generation = j.getString("generation")
        list.map { it.end }.distinct().forEach { at ->
            val callback = Runnable {
                synchronized(lock) {
                    if (read(c)?.optString("generation") == generation && peek(c) == null)
                        reconcile(c, "in_process_boundary_end")
                }
            }
            endCallbacks += callback
            endHandler.postDelayed(callback, (at - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        }
        ExecutionJournal.event(c, "window_schedule_configured", "explicit_accelerated_fixture_not_production_times",
            fields = mapOf("schedule" to j))
        JSONObject().put("accepted", true).put("schedule", j)
    }

    /** Boundary callbacks are never permission to cross a gap or resurrect an obsolete schedule. */
    fun onBoundary(c: Context, intent: Intent) = synchronized(lock) {
        val j = read(c)
        val at = intent.getLongExtra("boundary_at", -1)
        if (j == null || intent.data != uri(j.getString("generation"), at) ||
            j.getInt("boot") != boot(c) || at > SystemClock.elapsedRealtime()) {
            ExecutionJournal.event(c, "window_boundary_ignored", "obsolete_or_early"); return@synchronized
        }
        val current = peek(c)
        // A late callback from the earlier component must not open a later component after a gap.
        if (current != null && at < current.start) {
            ExecutionJournal.event(c, "window_boundary_ignored", "old_component"); return@synchronized
        }
        ExecutionJournal.event(c, "window_boundary_received", "real_alarm_delivery", fields = mapOf(
            "scheduled_elapsed_ms" to at, "late_ms" to (SystemClock.elapsedRealtime() - at)))
        reconcile(c, "system_boundary")
    }

    /** Called also by the live service timer, so a delayed alarm does not extend its request. */
    fun reconcile(c: Context, actor: String): Boolean = synchronized(lock) {
        val live = peek(c)
        if (live == null) {
            ExecutionProbe.stop(c, "window_closed_$actor")
            finishDndFixture(c, "window_closed_$actor")
            ExecutionJournal.event(c, "window_closed", actor)
            false
        } else {
            if (ExecutionJournal.currentSession(c).isEmpty() || ExecutionJournal.currentDeadline(c) < live.end) {
                val result = ExecutionProbe.requestStart(c, "window_$actor", live.end - SystemClock.elapsedRealtime(), SystemClock.elapsedRealtime())
                result.accepted
            } else true
        }
    }

    fun skip(c: Context) = synchronized(lock) {
        val j = read(c) ?: return@synchronized
        val live = peek(c)
        j.put("dismissed", JSONArray((dismissed(j) + live?.occurrences.orEmpty()).toList()))
        prefs(c).edit().putString("schedule", j.toString()).commit()
        ExecutionJournal.event(c, "window_occurrences_dismissed", "skip_current_prayer_period",
            fields = mapOf("dismissed" to j.getJSONArray("dismissed")))
        reconcile(c, "skip")
    }

    /** Named synthetic-presence fixture: tests single-window DND release, not real mosque presence. */
    fun prepareDndFixture(c: Context): JSONObject = synchronized(lock) {
        withLease(c, "synthetic_dnd_fixture") { live ->
            check(windows(read(c)!!).size == 1) { "DND component fixture is single-window only" }
            val remaining = live.end - SystemClock.elapsedRealtime()
            check(remaining >= 30_000) { "Too little time for the historical DND helper's minimum duration" }
            val report = DndSessionProbe(c).prepare(remaining, live.end) { peek(c)?.token == live.token }
            report.optJSONObject("session")?.optString("session")?.takeIf { it.isNotEmpty() }?.let {
                prefs(c).edit().putString("dnd_session", it).commit()
                if (peek(c)?.token != live.token) finishDndFixture(c, "fixture_completed_after_window_end")
            }
            ExecutionJournal.event(c, "window_synthetic_dnd_fixture", "not_physical_presence_evidence")
            report
        } ?: JSONObject().put("result", "outside_window_refused")
    }

    private fun finishDndFixture(c: Context, reason: String) {
        val session = prefs(c).getString("dnd_session", null) ?: return
        val result = DndSessionProbe(c).finishIfSession(session, reason)
        if (result.optJSONObject("cleanup")?.optBoolean("complete") == true || result.optString("result") == "session_no_longer_current")
            prefs(c).edit().remove("dnd_session").commit()
    }

    fun close(c: Context, reason: String = "explicit_window_cleanup") = synchronized(lock) {
        val j = read(c)
        endCallbacks.forEach { endHandler.removeCallbacks(it) }; endCallbacks.clear()
        prefs(c).edit().remove("schedule").commit() // Invalidate before cancelling platform effects.
        if (j != null) windows(j).flatMap { listOf(it.start, it.end) }.distinct().forEach { at ->
            boundary(c, j.getString("generation"), at, PendingIntent.FLAG_NO_CREATE)?.let {
                c.getSystemService(AlarmManager::class.java).cancel(it); it.cancel()
            }
        }
        ExecutionProbe.stop(c, reason)
        finishDndFixture(c, reason)
    }

    fun snapshot(c: Context): JSONObject = JSONObject().put("schedule", read(c) ?: JSONObject.NULL)
        .put("live_token", peek(c)?.token ?: JSONObject.NULL).put("elapsed_ms", SystemClock.elapsedRealtime())

    private fun uri(generation: String, at: Long) = Uri.parse("stage0-window://boundary/$generation/$at")
    private fun boundary(c: Context, generation: String, at: Long, flags: Int): PendingIntent? = PendingIntent.getBroadcast(c, 4200,
        Intent(c, WindowBoundaryReceiver::class.java).setAction(ACTION).setData(uri(generation, at)).putExtra("boundary_at", at),
        flags or PendingIntent.FLAG_IMMUTABLE)
}

class WindowBoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WindowAuthority.ACTION) return
        val pending = goAsync()
        executor.execute { try { WindowAuthority.onBoundary(context, intent) } finally { pending.finish() } }
    }
    companion object { private val executor = Executors.newSingleThreadExecutor() }
}
