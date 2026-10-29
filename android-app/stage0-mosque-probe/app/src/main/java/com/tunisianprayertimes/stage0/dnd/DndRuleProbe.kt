package com.tunisianprayertimes.stage0.dnd

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.service.notification.Condition
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Isolated, silent reference-platform experiment. Never writes ringer/global DND or plays media.
 * Rule state does not prove physical audio output or user snooze behavior in the real Modes UI.
 */
class DndRuleProbe(context: Context) {
    private val app = context.applicationContext
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val session = UUID.randomUUID().toString()
    private val records = JSONArray()
    private val events = JSONArray()
    private val checkpoints = JSONArray()
    private val checks = JSONArray()
    private val eventLock = Any()
    private val reportFile = File(app.filesDir, "dnd-probe/dnd-$session.json")
    private val report = JSONObject()
        .put("session", session)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("startedAtMillis", System.currentTimeMillis())
        .put("physicalAudioOrVibrationTested", false)
        .put("realSystemUiSnoozeTested", false)
        .put("ruleVisibility", "NotificationManager exposes only this package's owned rules; parent must snapshot other owners separately")
        .put("checkpoints", checkpoints)
        .put("checks", checks)
        .put("statusEvents", events)

    /** Blocks briefly while observing state; call off the application's main thread. */
    fun runChecks(includeSyntheticUserOrigin: Boolean = false): JSONObject = synchronized(processLock) {
        var registered = false
        var baselineRules: Map<String, AutomaticZenRule>? = null
        var baselineFilter: Int? = null
        var baselineRinger: Int? = null
        var journal: JSONObject? = null
        val observer = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getStringExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID)
                synchronized(eventLock) {
                    if ((0 until records.length()).none { records.getJSONObject(it).optString("id") == id }) return
                    // Observational only: an exported broadcast never authorizes state changes.
                    events.put(JSONObject()
                        .put("elapsedRealtimeMillis", SystemClock.elapsedRealtime())
                        .put("ruleId", id)
                        .put("status", intent.getIntExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_STATUS, -999)))
                }
            }
        }
        try {
            check(Build.VERSION.SDK_INT >= 29) { "This probe needs API 29 or newer" }
            check(notifications.isNotificationPolicyAccessGranted) { "DND access was not granted" }
            val recovery = recoverJournalLocked(app)
            report.put("preRunRecovery", recovery)
            check(recovery.getBoolean("complete")) { "Previous journal could not be cleaned; refusing to overwrite it" }
            val stableBaseline = observeStableState(app)
            report.put("baselineStability", stableBaseline.evidence)
            requireCheck("baseline_filter_ringer_and_owned_rules_stable_750ms", stableBaseline.stable)
            baselineRules = stableBaseline.rules
            val initialFilter = stableBaseline.filter
            baselineFilter = initialFilter
            baselineRinger = stableBaseline.ringer
            report.put("baselineFilter", initialFilter).put("baselineRingerMode", baselineRinger)
            report.put("aggregateComparisonUnconfounded", initialFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
            checkpoint("before_creation")
            journal = JSONObject()
                .put("package", app.packageName)
                .put("session", session)
                .put("createdAtMillis", System.currentTimeMillis())
                .put("baselineFilter", initialFilter).put("baselineRingerMode", baselineRinger)
                .put("baselineOwnedIds", JSONArray(baselineRules.keys.toList()))
                .put("rules", records)
            writeJson(journalFile(app), journal)
            val filter = IntentFilter(NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(observer, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(observer, filter)
            registered = true

            val alarms = createRule("alarms", NotificationManager.INTERRUPTION_FILTER_ALARMS, journal)
            val none = createRule("none", NotificationManager.INTERRUPTION_FILTER_NONE, journal)
            checkpoint("two_rules_created")
            requireCheck("two_independent_created_ids", alarms != none)

            state(alarms, Condition.STATE_TRUE)
            awaitRule(alarms, Condition.STATE_TRUE)
            awaitAggregateIfUnconfounded(initialFilter, NotificationManager.INTERRUPTION_FILTER_ALARMS)
            checkpoint("alarms_active")

            state(none, Condition.STATE_TRUE)
            awaitRule(none, Condition.STATE_TRUE)
            awaitAggregateIfUnconfounded(initialFilter, NotificationManager.INTERRUPTION_FILTER_NONE)
            checkpoint("none_and_alarms_active")

            // A genuine unchanged TRUE, without changing definition, URI, enabled state or rule ID.
            repeat(3) { state(none, Condition.STATE_TRUE) }
            awaitRule(none, Condition.STATE_TRUE)
            requireCheck("ordinary_true_preserves_id_and_definition",
                notifications.getAutomaticZenRule(none)?.conditionId == recordFor(none).uri())
            checkpoint("ordinary_true_repeated_without_false")

            state(none, Condition.STATE_FALSE)
            awaitRule(none, Condition.STATE_FALSE)
            awaitRule(alarms, Condition.STATE_TRUE)
            awaitAggregateIfUnconfounded(initialFilter, NotificationManager.INTERRUPTION_FILTER_ALARMS)
            checkpoint("none_released_alarms_still_active")
            requireCheck("remove_only_none_rule", notifications.removeAutomaticZenRule(none))
            requireCheck("alarms_rule_survives_other_removal", notifications.getAutomaticZenRule(alarms) != null)
            awaitRule(alarms, Condition.STATE_TRUE)
            checkpoint("none_removed_alarms_still_active")

            if (includeSyntheticUserOrigin && Build.VERSION.SDK_INT >= 35) {
                report.put("syntheticUserOrigin", "App-provided SOURCE_USER_ACTION false then ordinary true; NOT a real System UI snooze or proof of its override lifecycle")
                state(alarms, Condition.STATE_FALSE, syntheticUserOrigin = true)
                checkpoint("synthetic_app_user_origin_false")
                state(alarms, Condition.STATE_TRUE)
                checkpoint("ordinary_true_after_synthetic_app_user_origin_false")
            }
            state(alarms, Condition.STATE_FALSE)
            awaitRule(alarms, Condition.STATE_FALSE)
            if (initialFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                awaitAggregateIfUnconfounded(initialFilter, initialFilter)
            }
            checkpoint("own_conditions_released")
            report.put("result", "passed")
        } catch (failure: Throwable) {
            report.put("result", "failed")
                .put("failure", failure.toString())
                .put("failureStack", Log.getStackTraceString(failure))
        } finally {
            if (registered) runCatching { app.unregisterReceiver(observer) }
            val cleanup = recoverJournalLocked(app)
            report.put("cleanup", cleanup)
            if (!cleanup.optBoolean("complete")) report.put("result", "failed")
            runCatching {
                val stableAfter = observeStableState(app)
                report.put("cleanupStability", stableAfter.evidence)
                requireCheck("cleanup_filter_ringer_and_owned_rules_stable_750ms", stableAfter.stable)
                val after = stableAfter.rules
                val unchanged = baselineRules?.all { (id, rule) -> after[id] == rule } ?: true
                report.put("preexistingOwnedRuleDefinitionsUnchanged", unchanged)
                val finalFilter = stableAfter.filter
                val finalRinger = stableAfter.ringer
                report.put("finalFilter", finalFilter).put("finalRingerMode", finalRinger)
                if (baselineFilter != null && baselineRinger != null) {
                    val sameFilter = finalFilter == baselineFilter
                    val sameRinger = finalRinger == baselineRinger
                    report.put("finalFilterMatchesBaseline", sameFilter)
                        .put("finalRingerMatchesBaseline", sameRinger)
                        .put("externalContextVisibility", "Other owners/user ringer changes require parent system snapshots; this app never restores global state")
                    if (!sameFilter || !unchanged) {
                        report.put("baselineComparison", "confounded: global filter or preexisting owned definitions changed")
                        if (report.optString("result") == "passed") report.put("result", "confounded")
                    } else {
                        report.put("baselineComparison", "aggregate filter and owned definitions match; parent must confirm no external ringer change")
                        await("ringer_baseline_restored_in_stable_global_context") { audio.ringerMode == baselineRinger }
                        report.put("finalRingerMode", audio.ringerMode)
                            .put("finalRingerMatchesBaseline", audio.ringerMode == baselineRinger)
                    }
                }
                checkpoint("after_cleanup")
            }.onFailure {
                report.put("afterCleanupObservationError", it.toString()).put("result", "failed")
            }
            report.put("finishedAtMillis", System.currentTimeMillis()).put("reportPath", reportFile.absolutePath)
            synchronized(eventLock) { writeJson(AtomicFile(reportFile), report) }
            Log.i(TAG, "DND_PROBE_REPORT " + report.toString())
        }
        report
    }

    /**
     * Deliberately leaves one journaled NONE rule for an explicit next-launch recovery experiment.
     * It performs no process killing. The caller must invoke recoverJournal even after failures.
     */
    fun prepareRecoveryFixture(): JSONObject = synchronized(processLock) {
        try {
            check(Build.VERSION.SDK_INT >= 29)
            check(notifications.isNotificationPolicyAccessGranted) { "DND access was not granted" }
            val recovery = recoverJournalLocked(app)
            check(recovery.getBoolean("complete")) { "Earlier journal requires cleanup first" }
            val baseline = observeStableState(app)
            report.put("baselineStability", baseline.evidence)
            requireCheck("recovery_fixture_baseline_stable_750ms", baseline.stable)
            report.put("baselineFilter", baseline.filter).put("baselineRingerMode", baseline.ringer)
            checkpoint("before_recovery_fixture")
            val journal = JSONObject().put("package", app.packageName).put("session", session)
                .put("createdAtMillis", System.currentTimeMillis())
                .put("baselineFilter", baseline.filter).put("baselineRingerMode", baseline.ringer)
                .put("baselineOwnedIds", JSONArray(baseline.rules.keys.toList()))
                .put("rules", records)
            writeJson(journalFile(app), journal)
            val id = createRule("recovery-none", NotificationManager.INTERRUPTION_FILTER_NONE, journal)
            state(id, Condition.STATE_TRUE)
            awaitRule(id, Condition.STATE_TRUE)
            checkpoint("journaled_recovery_fixture_active")
            report.put("result", "recovery_fixture_left_active").put("fixtureRuleId", id)
                .put("processDeathTested", false)
                .put("nextAction", "Explicitly relaunch the activity and inspect recoverJournal output, then verify this ID is absent")
        } catch (failure: Throwable) {
            report.put("result", "failed").put("failure", failure.toString())
                .put("cleanup", recoverJournalLocked(app))
        }
        report.put("reportPath", reportFile.absolutePath)
        writeJson(AtomicFile(reportFile), report)
        Log.i(TAG, "DND_RECOVERY_FIXTURE $report")
        report
    }

    private fun createRule(slot: String, interruptionFilter: Int, journal: JSONObject): String {
        val uri = Uri.Builder().scheme(Condition.SCHEME).authority(app.packageName)
            .appendPath("stage0").appendPath(session).appendPath(slot).build()
        val record = JSONObject().put("slot", slot).put("conditionId", uri.toString())
        synchronized(eventLock) { records.put(record) }
        // Persist creation intent before the system call. If killed immediately after add, recover
        // only this pre-journaled unique URI/config component and exclude all baseline rule IDs.
        writeJson(journalFile(app), journal)
        val component = ComponentName(app, DndRuleConfigurationActivity::class.java)
        val rule = if (Build.VERSION.SDK_INT >= 35) {
            AutomaticZenRule.Builder("Stage 0 $slot", uri)
                .setConfigurationActivity(component)
                .setInterruptionFilter(interruptionFilter)
                .setManualInvocationAllowed(false)
                .setType(AutomaticZenRule.TYPE_OTHER)
                .setTriggerDescription("Isolated silent DND feasibility check")
                .setEnabled(true)
                .build()
        } else {
            AutomaticZenRule("Stage 0 $slot", null, component, uri, null, interruptionFilter, true)
        }
        val id = notifications.addAutomaticZenRule(rule)
        synchronized(eventLock) { record.put("id", id) }
        writeJson(journalFile(app), journal)
        return id
    }

    private fun recordFor(id: String): JSONObject = synchronized(eventLock) {
        (0 until records.length()).map { records.getJSONObject(it) }.first { it.optString("id") == id }
    }

    private fun state(id: String, value: Int, syntheticUserOrigin: Boolean = false) {
        val uri = recordFor(id).uri()
        val condition = if (syntheticUserOrigin && Build.VERSION.SDK_INT >= 35) {
            Condition(uri, "Stage 0 synthetic app user origin", value, Condition.SOURCE_USER_ACTION)
        } else Condition(uri, "Stage 0 ordinary automatic condition", value)
        notifications.setAutomaticZenRuleState(id, condition)
    }

    private fun awaitRule(id: String, expected: Int) {
        if (Build.VERSION.SDK_INT >= 35) {
            await("rule ${recordFor(id).optString("slot")} state $expected") {
                notifications.getAutomaticZenRuleState(id) == expected
            }
        } else checks.put(JSONObject().put("check", "activation_getter_unavailable").put("sdk", Build.VERSION.SDK_INT))
    }

    private fun awaitAggregateIfUnconfounded(baseline: Int, expected: Int) {
        if (baseline == NotificationManager.INTERRUPTION_FILTER_ALL) {
            await("aggregate filter $expected") { notifications.currentInterruptionFilter == expected }
        } else checks.put(JSONObject().put("check", "aggregate_comparison_not_asserted")
            .put("reason", "Preexisting DND may restrict the aggregate; no independent rule is changed")
            .put("baselineFilter", baseline).put("candidateExpectedFilter", expected))
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        requireCheck(label, predicate())
    }

    private fun requireCheck(label: String, passed: Boolean) {
        checks.put(JSONObject().put("check", label).put("passed", passed))
        check(passed) { label }
    }

    private fun checkpoint(label: String) {
        val snapshot = capture(app).put("label", label)
        checkpoints.put(snapshot)
        synchronized(eventLock) { writeJson(AtomicFile(reportFile), report) }
    }

    companion object {
        private const val TAG = "Stage0DndProbe"
        private val processLock = Any()
        private fun JSONObject.uri(): Uri = Uri.parse(getString("conditionId"))
        private fun journalFile(context: Context) = AtomicFile(File(context.noBackupFilesDir, "dnd-probe/owned-rule-journal.json"))

        private data class StableState(
            val filter: Int,
            val ringer: Int,
            val rules: Map<String, AutomaticZenRule>,
            val stable: Boolean,
            val evidence: JSONObject
        )

        /** Observation only: no caller should force a ringer/filter baseline to satisfy a test. */
        fun observeStableBaseline(context: Context): JSONObject = synchronized(processLock) {
            observeStableState(context.applicationContext).evidence
        }

        private fun observeStableState(context: Context): StableState {
            val notifications = context.getSystemService(NotificationManager::class.java)
            val audio = context.getSystemService(AudioManager::class.java)
            val started = SystemClock.elapsedRealtime()
            val deadline = started + 5_000
            var unchangedSince = started
            var previousFilter: Int? = null
            var previousRinger: Int? = null
            var previousRules: Map<String, AutomaticZenRule>? = null
            val transitions = JSONArray()
            while (true) {
                val filter = notifications.currentInterruptionFilter
                val ringer = audio.ringerMode
                val rules = notifications.automaticZenRules
                val now = SystemClock.elapsedRealtime()
                if (previousFilter != filter || previousRinger != ringer || previousRules != rules) {
                    unchangedSince = now
                    transitions.put(JSONObject().put("elapsedSinceStartMillis", now - started)
                        .put("aggregateFilter", filter).put("ringerMode", ringer)
                        .put("ownedRuleIds", JSONArray(rules.keys.sorted())))
                    previousFilter = filter
                    previousRinger = ringer
                    previousRules = rules
                }
                val stable = now - unchangedSince >= 750
                if (stable || now >= deadline) {
                    val evidence = JSONObject().put("stable", stable)
                        .put("requiredStableMillis", 750).put("timeoutMillis", 5_000)
                        .put("observedForMillis", now - started).put("unchangedForMillis", now - unchangedSince)
                        .put("aggregateFilter", filter).put("ringerMode", ringer)
                        .put("ownedRuleIds", JSONArray(rules.keys.sorted())).put("transitions", transitions)
                    return StableState(filter, ringer, rules, stable, evidence)
                }
                SystemClock.sleep(50)
            }
        }

        /** MainActivity should invoke this on every launch, before enabling a new experiment. */
        fun recoverJournal(context: Context): JSONObject = synchronized(processLock) {
            recoverJournalLocked(context.applicationContext).also {
                val output = File(context.filesDir, "dnd-probe/recovery-${System.currentTimeMillis()}.json")
                writeJson(AtomicFile(output), it)
                Log.i(TAG, "DND_PROBE_RECOVERY $it")
            }
        }

        private fun recoverJournalLocked(context: Context): JSONObject {
            val output = JSONObject().put("atMillis", System.currentTimeMillis()).put("removedRuleIds", JSONArray())
            val file = journalFile(context)
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
                return output.put("complete", true).put("journalPresent", false)
            }
            return runCatching {
                val notifications = context.getSystemService(NotificationManager::class.java)
                check(notifications.isNotificationPolicyAccessGranted) { "DND access missing; preserve journal for later cleanup" }
                val journal = file.openRead().bufferedReader().use { JSONObject(it.readText()) }
                check(journal.getString("package") == context.packageName) { "Journal package mismatch" }
                if (journal.has("baselineFilter")) output.put("journalBaselineFilter", journal.getInt("baselineFilter"))
                if (journal.has("baselineRingerMode")) output.put("journalBaselineRingerMode", journal.getInt("baselineRingerMode"))
                val baseline = journal.getJSONArray("baselineOwnedIds")
                val baselineIds = (0 until baseline.length()).map { baseline.getString(it) }.toSet()
                val rules = journal.getJSONArray("rules")
                val existing = notifications.automaticZenRules
                val component = ComponentName(context, DndRuleConfigurationActivity::class.java)
                val toRemove = linkedSetOf<String>()
                for (index in 0 until rules.length()) {
                    val record = rules.getJSONObject(index)
                    val knownId = record.optString("id")
                    if (knownId.isNotEmpty()) {
                        check(knownId !in baselineIds) { "Refusing baseline rule ID in cleanup journal" }
                        if (existing.containsKey(knownId)) toRemove += knownId
                    } else {
                        // Only a unique pre-journaled creation intent can bridge add->ID-write death.
                        val found = existing.filter { (id, rule) ->
                            id !in baselineIds && rule.conditionId == record.uri() && rule.configurationActivity == component
                        }.keys
                        check(found.size <= 1) { "Ambiguous pending rule; preserve journal rather than delete broadly" }
                        toRemove += found
                    }
                }
                val removed = output.getJSONArray("removedRuleIds")
                for (id in toRemove) {
                    check(notifications.removeAutomaticZenRule(id)) { "Removal failed for test-created rule $id" }
                    removed.put(id)
                }
                val remaining = notifications.automaticZenRules
                check(toRemove.none { remaining.containsKey(it) }) { "A test-created rule remains" }
                file.delete()
                output.put("complete", true).put("journalPresent", true)
            }.getOrElse { output.put("complete", false).put("error", it.toString()) }
        }

        private fun capture(context: Context): JSONObject {
            val notifications = context.getSystemService(NotificationManager::class.java)
            val audio = context.getSystemService(AudioManager::class.java)
            val rules = JSONArray()
            for ((id, rule) in notifications.automaticZenRules.toSortedMap()) {
                val item = JSONObject().put("id", id).put("name", rule.name)
                    .put("enabled", rule.isEnabled).put("filter", rule.interruptionFilter)
                    .put("conditionId", rule.conditionId?.toString())
                    .put("configurationActivity", rule.configurationActivity?.flattenToString())
                    .put("owner", rule.owner?.flattenToString()).put("policy", rule.zenPolicy?.toString())
                if (Build.VERSION.SDK_INT >= 35) {
                    item.put("observedState", notifications.getAutomaticZenRuleState(id))
                        .put("manualInvocationAllowed", rule.isManualInvocationAllowed).put("type", rule.type)
                }
                rules.put(item)
            }
            return JSONObject().put("atMillis", System.currentTimeMillis())
                .put("elapsedRealtimeMillis", SystemClock.elapsedRealtime())
                .put("aggregateFilter", notifications.currentInterruptionFilter)
                .put("ringerMode", audio.ringerMode).put("ownedRules", rules)
        }

        private fun writeJson(file: AtomicFile, value: JSONObject) {
            file.baseFile.parentFile?.mkdirs()
            val stream = file.startWrite()
            try {
                stream.write(value.toString(2).toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (failure: Throwable) {
                file.failWrite(stream)
                throw failure
            }
        }
    }
}
