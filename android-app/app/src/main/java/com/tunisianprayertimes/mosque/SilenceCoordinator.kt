package com.tunisianprayertimes.mosque

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import java.util.UUID

enum class SilenceOwner(val title: Int) {
    PRAYER(R.string.mosque_rule_prayer), MOSQUE(R.string.mosque_rule_combined),
    MANUAL(R.string.mosque_rule_manual), WAKE(R.string.mosque_rule_wake),
}

/**
 * The opt-in adapter owns four explicit contributions, never the aggregate DND/ringer baseline.
 * All production presence and rule effects share this lock. No scheduler/controller mutator is called
 * while it is held: those callers may already hold their legacy scheduling lock.
 */
object SilenceCoordinator {
    internal val serial = Any()
    private fun prefs(context: Context) = context.getSharedPreferences("silence_ownership", Context.MODE_PRIVATE)
    private fun notifications(context: Context) = context.getSystemService(NotificationManager::class.java)
    private fun component(context: Context) = ComponentName(context, MosqueSettingsActivity::class.java)
    private fun key(owner: SilenceOwner, suffix: String) = "${owner.name}:$suffix"

    /** Explicit setup only. Background callbacks never create, enable or replace a rule. */
    fun enrollOrRepair(context: Context): Boolean = synchronized(serial) {
        if (Build.VERSION.SDK_INT < 29) return@synchronized false
        val nm = notifications(context)
        if (!nm.isNotificationPolicyAccessGranted) return@synchronized false
        if (MosqueSilenceSettings.usesOwnedRules(context) && SilenceOwner.entries.any { requested(context, it) }) return@synchronized false
        if (!MosqueSilenceSettings.usesOwnedRules(context)) {
            // No implicit/global baseline is replayed into the new owner system. Finish an old test
            // contribution in Settings first, while the existing path can still restore its own state.
            if (PrefsManager.isAutoSilenceActive(context) || PrefsManager.isManualSilenceActive(context) ||
                WakeAlarmScheduler.isSilenceUntilAlarmActive(context) ||
                nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return@synchronized false
        }
        try {
            val state = prefs(context)
            for (owner in SilenceOwner.entries) {
                var uri = state.getString(key(owner, "uri"), null)?.let(Uri::parse)
                if (uri == null) {
                    uri = Uri.parse("condition://${context.packageName}/silence/${owner.name}/${UUID.randomUUID()}")
                    check(state.edit().putString(key(owner, "uri"), uri.toString()).commit())
                }
                val known = state.getString(key(owner, "id"), null)
                val current = known?.let { nm.getAutomaticZenRule(it) }
                var id = known
                if (current == null || current.conditionId != uri || current.configurationActivity != component(context)) {
                    // Recover an interrupted add from the pre-journaled exact URI; never enumerate/remove
                    // someone else's rule or adopt a package rule based on its display name.
                    id = nm.automaticZenRules.entries.firstOrNull {
                        it.value.conditionId == uri && it.value.configurationActivity == component(context)
                    }?.key
                    if (id == null) {
                        val rule = if (Build.VERSION.SDK_INT >= 35) {
                            AutomaticZenRule.Builder(context.getString(owner.title), uri)
                                .setConfigurationActivity(component(context))
                                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                                .setManualInvocationAllowed(false).setEnabled(true).build()
                        } else AutomaticZenRule(context.getString(owner.title), null, component(context), uri,
                            null, NotificationManager.INTERRUPTION_FILTER_NONE, true)
                        id = nm.addAutomaticZenRule(rule)
                    }
                    check(state.edit().putString(key(owner, "id"), id).commit())
                }
                // Repair is an explicit user action, but does not turn a disabled system rule on.
                val actual = nm.getAutomaticZenRule(requireNotNull(id)) ?: error("RULE_MISSING")
                check(actual.isEnabled && actual.interruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE)
                state.edit().putBoolean(key(owner, "desired"), false).putBoolean(key(owner, "blocked"), false).commit()
                nm.setAutomaticZenRuleState(id, Condition(uri, context.getString(owner.title), Condition.STATE_FALSE))
            }
            check(MosqueSilenceSettings.enroll(context))
            true
        } catch (_: Exception) { false }
    }

    fun ruleReady(context: Context, owner: SilenceOwner): Boolean = synchronized(serial) {
        if (Build.VERSION.SDK_INT < 29 || !notifications(context).isNotificationPolicyAccessGranted) return@synchronized false
        runCatching {
            val state = prefs(context)
            val id = state.getString(key(owner, "id"), null) ?: return@runCatching false
            val rule = notifications(context).getAutomaticZenRule(id) ?: return@runCatching false
            rule.isEnabled && rule.conditionId?.toString() == state.getString(key(owner, "uri"), null) &&
                rule.configurationActivity == component(context) && rule.interruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
        }.getOrDefault(false)
    }

    fun requested(context: Context, owner: SilenceOwner): Boolean = synchronized(serial) {
        prefs(context).getBoolean(key(owner, "desired"), false) &&
            !prefs(context).getBoolean(key(owner, "blocked"), false) && ruleReady(context, owner)
    }
    fun autoActive(context: Context) = synchronized(serial) {
        requested(context, SilenceOwner.PRAYER) || requested(context, SilenceOwner.MOSQUE)
    }
    fun anyActive(context: Context) = synchronized(serial) { SilenceOwner.entries.any { requested(context, it) } }
    fun releasePending(context: Context) = synchronized(serial) {
        SilenceOwner.entries.any { prefs(context).getBoolean(key(it, "release_pending"), false) }
    }

    /** Journal first. FALSE is retryable after a failed Binder call. Unchanged TRUE never resets snooze. */
    internal fun set(context: Context, owner: SilenceOwner, desired: Boolean): Boolean = synchronized(serial) {
        if (Build.VERSION.SDK_INT < 29 || !MosqueSilenceSettings.usesOwnedRules(context)) return@synchronized false
        // Keep observation and expiry running, but do not silence an escalating awake check.
        if (desired && (owner == SilenceOwner.PRAYER || owner == SilenceOwner.MOSQUE) &&
            SilenceModeController.isAutoSilencePausedForAwakeCheck) return@synchronized true
        val state = prefs(context)
        val old = state.getBoolean(key(owner, "desired"), false)
        if (desired && (state.getBoolean(key(owner, "blocked"), false) || !ruleReady(context, owner))) return@synchronized false
        if (desired && old) return@synchronized true
        // A failed release remains pending, independently of the logical request.
        val pending = state.getBoolean(key(owner, "release_pending"), false)
        if (!desired && !old && !pending) return@synchronized true
        check(state.edit().putBoolean(key(owner, "desired"), desired)
            .putBoolean(key(owner, "release_pending"), !desired).commit())
        try {
            val id = state.getString(key(owner, "id"), null) ?: return@synchronized !desired
            val rule = notifications(context).getAutomaticZenRule(id)
            if (rule == null) {
                state.edit().putBoolean(key(owner, "release_pending"), false).commit()
                return@synchronized !desired
            }
            val uri = state.getString(key(owner, "uri"), null)?.let(Uri::parse)
            if (rule.conditionId != uri || rule.configurationActivity != component(context)) return@synchronized false
            // Recheck AND authority at the final serialized platform effect.
            if (desired && owner == SilenceOwner.MOSQUE && !MosqueSilenceMonitor.canActivateLocked(context)) {
                state.edit().putBoolean(key(owner, "desired"), false).commit()
                return@synchronized false
            }
            notifications(context).setAutomaticZenRuleState(id, Condition(requireNotNull(uri), context.getString(owner.title),
                if (desired) Condition.STATE_TRUE else Condition.STATE_FALSE))
            state.edit().putBoolean(key(owner, "release_pending"), false).commit()
            // A stopped silence-until-alarm must not come back merely because another pending alarm
            // resumes it. Other owners end their own episode naturally at FALSE.
            if (!desired && owner != SilenceOwner.WAKE) state.edit().putBoolean(key(owner, "blocked"), false).commit()
            true
        } catch (_: Exception) {
            // The Binder result may be unknown. Withdraw logical authority and retain cleanup
            // rather than reporting a confirmed app contribution that was never established.
            state.edit().putBoolean(key(owner, "desired"), false)
                .putBoolean(key(owner, "release_pending"), true).putBoolean(key(owner, "blocked"), true).commit()
            false
        }
    }

    fun enableTime(context: Context): Boolean = synchronized(serial) {
        if (MosqueSilenceSettings.mode(context) != AutomaticSilenceMode.TIME_ONLY || !PrefsManager.isEnabled(context)) return@synchronized false
        // Legacy TIME_ONLY never marks automatic ownership while manual silence owns the phone.
        // That distinction matters at manual expiry outside a window: it must not invent a bridge.
        if (requested(context, SilenceOwner.MANUAL)) return@synchronized true
        set(context, SilenceOwner.PRAYER, true)
    }
    fun releaseAuto(context: Context): Boolean = synchronized(serial) {
        val active = autoActive(context)
        MosqueSilenceMonitor.stopLocked(context, needsRearm = true)
        val prayer = set(context, SilenceOwner.PRAYER, false)
        val mosque = set(context, SilenceOwner.MOSQUE, false)
        active && prayer && mosque
    }
    internal fun pauseAutoForAwakeCheck(context: Context): Boolean = synchronized(serial) {
        // Unlike a dismissal, this leaves the bounded visit alive for revalidation when playback ends.
        val active = autoActive(context)
        val prayer = set(context, SilenceOwner.PRAYER, false)
        val mosque = set(context, SilenceOwner.MOSQUE, false)
        active && prayer && mosque
    }
    fun setManual(context: Context, enabled: Boolean): Boolean = synchronized(serial) {
        // Combined mode can retain live bounded presence under manual ownership. TIME_ONLY preserves
        // the old automatic-flag hand-off, including its manual-expiry/bridge behavior.
        val changed = set(context, SilenceOwner.MANUAL, enabled)
        if (changed && enabled && MosqueSilenceSettings.mode(context) == AutomaticSilenceMode.TIME_ONLY)
            set(context, SilenceOwner.PRAYER, false)
        changed
    }
    fun setWake(context: Context, enabled: Boolean) = synchronized(serial) { set(context, SilenceOwner.WAKE, enabled) }
    fun stopAutomaticForUser(context: Context): Boolean = synchronized(serial) {
        SilenceScheduler.dismissCurrentWindows(context, SilenceScheduler.currentSilenceWindowPrayer(context))
        releaseAuto(context)
    }
    fun switchMode(context: Context, mode: AutomaticSilenceMode): Boolean = synchronized(serial) {
        if (mode == AutomaticSilenceMode.TIME_AND_MOSQUE && !MosqueSilenceSettings.usesOwnedRules(context)) return@synchronized false
        if (MosqueSilenceSettings.mode(context) == mode) return@synchronized true
        releaseAuto(context)
        MosqueSilenceSettings.saveMode(context, mode)
    }

    /** System-protected broadcast; IDs must be one of the exact pre-journaled app-owned rules. */
    fun onRuleStatus(context: Context, id: String, status: Int) = synchronized(serial) {
        val state = prefs(context)
        val owner = SilenceOwner.entries.firstOrNull { state.getString(key(it, "id"), null) == id } ?: return@synchronized
        val requested = state.getBoolean(key(owner, "desired"), false)
        // API 30 status values: enabled=1, disabled=2, removed=3; API 35 adds activated=4,
        // deactivated=5. Never turn a callback into an artificial FALSE/TRUE reset.
        if (status == 2 || status == 3 || status == 5 && requested) {
            state.edit().putBoolean(key(owner, "blocked"), true).commit()
            if (owner == SilenceOwner.MOSQUE) MosqueSilenceMonitor.stopLocked(context, needsRearm = true)
        }
    }

    fun recover(context: Context) = synchronized(serial) {
        if (!MosqueSilenceSettings.usesOwnedRules(context)) return@synchronized
        for (owner in SilenceOwner.entries) if (prefs(context).getBoolean(key(owner, "release_pending"), false)) set(context, owner, false)
        // Process/boot recovery never restores old confirmed presence. A fresh, legal in-window
        // user recheck is required after a lost active episode.
        MosqueSilenceMonitor.recoverLocked(context)
    }
}
