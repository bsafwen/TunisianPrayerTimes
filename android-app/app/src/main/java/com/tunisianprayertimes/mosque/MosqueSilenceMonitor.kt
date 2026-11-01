package com.tunisianprayertimes.mosque

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.mosque.presence.*
import java.util.UUID

data class MosqueObservationPlan(val token: String, val endElapsed: Long, val deadlineElapsed: Long,
    val highAccuracy: Boolean, val state: PresenceState, val areas: List<SilenceArea>)

/** Production authority: actual planner intervals, never the future-prayer/45-minute bridge lookup. */
object MosqueSilenceMonitor {
    private data class Runtime(val coverageStart: Long, val geometry: List<SilenceArea>, val machine: PresenceMachine,
        val token: String, val openedElapsed: Long, val wallToElapsedOffset: Long, var endWall: Long)
    private var runtime: Runtime? = null
    private var recoveryDone = false
    private fun prefs(context: Context) = context.getSharedPreferences("mosque_presence", Context.MODE_PRIVATE)
    private fun boot(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toString()

    private fun coverage(context: Context): WindowCoverage? {
        if (!PrefsManager.isEnabled(context) || !MosqueSilenceSettings.combined(context)) return null
        val now = SilenceScheduler.now()
        val windows = SilenceScheduler.planner(context).windows(now).filter {
            !SilenceScheduler.isDismissed(context, it.prayer, it.prayerDay)
        }.map { PrayerWindow("${it.prayer}@${it.prayerDay}", it.start.toEpochMilli(), it.end.toEpochMilli()) }
        return WindowScope.coverage(windows, emptySet(), now.toEpochMilli())
    }

    fun hasWindow(context: Context): Boolean = synchronized(SilenceCoordinator.serial) { coverage(context) != null }

    /** Stable prayer/day suppression survives the legacy place-change dismissal pruning. */
    fun recordDismissals(context: Context, occurrences: Collection<String>) = synchronized(SilenceCoordinator.serial) {
        val today = SilenceScheduler.now().atZone(com.tunisianprayertimes.PrayerTimesRepository.place(context).zone).toLocalDate()
        val state = prefs(context)
        val kept = (state.getStringSet("dismissed_occurrences", emptySet()).orEmpty() + occurrences).filter { key ->
            runCatching { !java.time.LocalDate.parse(key.substringAfter('@')).isBefore(today.minusDays(4)) }.getOrDefault(false)
        }
        state.edit().putStringSet("dismissed_occurrences", kept.toSet()).commit()
    }
    fun isDismissed(context: Context, occurrence: String): Boolean = synchronized(SilenceCoordinator.serial) {
        prefs(context).getStringSet("dismissed_occurrences", emptySet()).orEmpty().contains(occurrence)
    }

    /** May start only from a visible user action, exact user-requested prayer start, or GMS geofence. */
    fun reconcile(context: Context, canStart: Boolean = false, deliberate: Boolean = false) {
        val app = context.applicationContext
        val plan = synchronized(SilenceCoordinator.serial) {
            recoverLocked(app)
            reconcileLocked(app)
            var current = runtime
            if (canStart && current == null && coverage(app) != null && MosqueReadiness.ready(app)) {
                val support = requireNotNull(coverage(app))
                val areas = PrivateSilenceAreaStore.read(app).areas.map { it.geometry }.filter { it.enabled }
                val token = UUID.randomUUID().toString()
                val epoch = EventEpoch(boot(app), token)
                val previous = prefs(app)
                val blocked = previous.getBoolean("needs_rearm", false) && previous.getLong("coverage_start", -1) == support.start
                val now = SystemClock.elapsedRealtime()
                val machine = if (blocked) PresenceMachine.recovered(PresenceSnapshot(
                    EventEpoch(boot(app), "old-$token"), PresenceState.INACTIVE_NEEDS_REARM,
                    PresenceReason.RECOVERY_REARM, requiresDepartureOrExplicitRearm = true), epoch, areas, now)
                    else PresenceMachine(epoch, areas)
                current = Runtime(support.start, areas, machine, token, now, now - System.currentTimeMillis(), support.activeEnd)
                runtime = current
            }
            if (canStart && current != null) {
                val now = SystemClock.elapsedRealtime()
                if (current.endWall - System.currentTimeMillis() >= current.machine.config.entryDwellMillis)
                    current.machine.beginAcquisition(now, current.machine.snapshot.epoch, deliberate)
                persistLocked(app)
            }
            planLocked(app)
        }
        if (plan != null && canStart) MosqueObservationService.start(app, plan.token)
        else if (plan == null) MosqueObservationService.stop(app)
    }

    private fun reconcileLocked(context: Context) {
        val support = coverage(context)
        val current = runtime
        if (support == null || !MosqueReadiness.ready(context)) {
            stopLocked(context, needsRearm = support != null && current != null)
            return
        }
        if (current == null) { SilenceCoordinator.set(context, SilenceOwner.MOSQUE, false); return }
        val areas = PrivateSilenceAreaStore.read(context).areas.map { it.geometry }.filter { it.enabled }
        if (current.coverageStart != support.start || current.geometry != areas) {
            val unresolved = current.machine.snapshot.hasBoundedVisit || current.machine.snapshot.requiresDepartureOrExplicitRearm
            val rearm = unresolved && System.currentTimeMillis() < current.endWall
            stopLocked(context, needsRearm = rearm)
            if (rearm) prefs(context).edit().putLong("coverage_start", support.start).commit()
            return
        }
        current.endWall = support.activeEnd
        current.machine.tick(SystemClock.elapsedRealtime())
        val desired = current.machine.locationRequestActive
        if (!SilenceCoordinator.set(context, SilenceOwner.MOSQUE, desired) && desired) {
            stopLocked(context, needsRearm = true)
            return
        }
        persistLocked(context)
    }

    internal fun canActivateLocked(context: Context): Boolean {
        val current = runtime ?: return false
        val support = coverage(context) ?: return false
        current.machine.tick(SystemClock.elapsedRealtime())
        return current.coverageStart == support.start && current.machine.locationRequestActive && MosqueReadiness.ready(context)
    }

    fun onLocation(context: Context, token: String, location: Location) = synchronized(SilenceCoordinator.serial) {
        // Guard before even constructing/assessing a fix: late callbacks outside a window do no geometry work.
        reconcileLocked(context)
        val current = runtime ?: return@synchronized
        if (current.token != token || coverage(context) == null) return@synchronized
        val elapsed = location.elapsedRealtimeNanos / 1_000_000L
        if (elapsed < current.openedElapsed) return@synchronized
        val mock = if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider
        val fix = PresenceFix(GeoPoint(location.latitude, location.longitude),
            if (location.hasAccuracy()) location.accuracy.toDouble() else null, elapsed, mock)
        current.machine.observe(fix, SystemClock.elapsedRealtime(), current.machine.snapshot.epoch)
        reconcileLocked(context)
    }

    fun plan(context: Context, token: String): MosqueObservationPlan? = synchronized(SilenceCoordinator.serial) {
        reconcileLocked(context)
        if (runtime?.token != token) return@synchronized null
        planLocked(context)
    }

    private fun planLocked(context: Context): MosqueObservationPlan? {
        val current = runtime ?: return null
        val now = SystemClock.elapsedRealtime()
        val snapshot = current.machine.tick(now)
        val end = current.endWall + current.wallToElapsedOffset
        val deadlines = listOfNotNull(end, snapshot.acquisitionDeadlineMillis,
            snapshot.lastUsableDeliveredAtMillis?.let { it + current.machine.config.noObservationMillis },
            snapshot.lastStrictInsideAtMillis?.let { it + current.machine.config.ambiguityMillis })
        val deadline = deadlines.minOrNull() ?: end
        if (deadline <= now || snapshot.state != PresenceState.ACQUIRING && !snapshot.hasBoundedVisit) return null
        armDeadline(context, deadline)
        val freshStrictNeeded = now - (snapshot.lastStrictInsideAtMillis ?: 0) >= 180_000 ||
            now - (snapshot.lastUsableDeliveredAtMillis ?: 0) >= 90_000
        return MosqueObservationPlan(current.token, end, deadline,
            snapshot.state != PresenceState.VISIT_CONFIRMED || freshStrictNeeded, snapshot.state, current.geometry)
    }

    internal fun stopLocked(context: Context, needsRearm: Boolean) {
        val previous = runtime
        runtime = null // Invalidate tokens before cancelling any native work or releasing the rule.
        val support = previous?.coverageStart ?: prefs(context).getLong("coverage_start", -1)
        val barrier = needsRearm || prefs(context).getBoolean("needs_rearm", false)
        prefs(context).edit().putBoolean("live", false).putBoolean("needs_rearm", barrier)
            .putLong("coverage_start", support).remove("token").remove("deadline_elapsed").commit()
        deadlineManager(context).cancel(deadlineIntent(context))
        MosqueObservationService.cancelNative(context)
        SilenceCoordinator.set(context, SilenceOwner.MOSQUE, false)
    }

    internal fun recoverLocked(context: Context) {
        if (recoveryDone) return
        recoveryDone = true
        val state = prefs(context)
        if (state.getBoolean("live", false)) state.edit().putBoolean("needs_rearm", true).putBoolean("live", false).commit()
        SilenceCoordinator.set(context, SilenceOwner.MOSQUE, false)
        // A delivered GMS transition can itself cold-start this process. Preserve its finite, same-boot
        // registration until the receiver validates the actual current window; otherwise every cold
        // arrival would lose its legitimate start route in Application.onCreate.
        if (state.getString("boot", null) != boot(context) || state.getLong("window_end", 0) <= System.currentTimeMillis())
            MosqueObservationService.removeSavedGeofences(context)
        else MosqueObservationService.removeSavedGeofences(context, includeCurrent = false)
    }

    fun environmentChanged(context: Context) {
        synchronized(SilenceCoordinator.serial) { stopLocked(context, needsRearm = true) }
        // No implicit location start after edits/settings changes; the screen offers an explicit recheck.
    }
    fun skip(context: Context) {
        synchronized(SilenceCoordinator.serial) {
            SilenceScheduler.dismissCurrentWindows(context)
            stopLocked(context, needsRearm = true)
        }
        MosqueObservationService.stop(context)
    }
    fun status(context: Context): PresenceState? = synchronized(SilenceCoordinator.serial) {
        reconcileLocked(context)
        runtime?.machine?.snapshot?.state ?: if (prefs(context).getBoolean("needs_rearm", false) &&
            coverage(context)?.start == prefs(context).getLong("coverage_start", -1)) PresenceState.INACTIVE_NEEDS_REARM else null
    }
    fun endWall(context: Context): Long? = synchronized(SilenceCoordinator.serial) {
        val current = runtime ?: return@synchronized null
        val plan = planLocked(context) ?: return@synchronized null
        minOf(current.endWall, System.currentTimeMillis() + plan.deadlineElapsed - SystemClock.elapsedRealtime())
    }

    private fun persistLocked(context: Context) {
        val current = runtime ?: return
        val snapshot = current.machine.snapshot
        // Current state and authority only. No coordinates, fix history, visit log or activation analytics.
        prefs(context).edit().putBoolean("live", snapshot.hasBoundedVisit || snapshot.state == PresenceState.ACQUIRING)
            .putBoolean("needs_rearm", snapshot.requiresDepartureOrExplicitRearm)
            .putLong("coverage_start", current.coverageStart).putString("token", current.token)
            .putString("boot", boot(context)).putLong("window_end", current.endWall).commit()
    }
    private fun deadlineManager(context: Context) = context.getSystemService(AlarmManager::class.java)
    private fun deadlineIntent(context: Context) = PendingIntent.getBroadcast(context, 81_201,
        Intent(context, MosqueControlReceiver::class.java).setAction(MosqueControlReceiver.DEADLINE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun armDeadline(context: Context, elapsed: Long) {
        val state = prefs(context)
        if (state.getLong("deadline_elapsed", -1) == elapsed) return
        val manager = deadlineManager(context)
        if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) return
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsed, deadlineIntent(context))
            state.edit().putLong("deadline_elapsed", elapsed).commit()
        } catch (_: SecurityException) { /* Readiness will release on the next eligible callback. */ }
    }
}
