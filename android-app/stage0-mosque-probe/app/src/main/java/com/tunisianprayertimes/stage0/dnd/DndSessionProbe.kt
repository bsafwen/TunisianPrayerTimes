package com.tunisianprayertimes.stage0.dnd

import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.Condition
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A finite, durable session for genuine system Modes UI experiments; never plays or vibrates. */
class DndSessionProbe(context: Context) {
    private val app = context.applicationContext
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val alarms = app.getSystemService(AlarmManager::class.java)

    fun prepare(durationMillis: Long = 300_000L): JSONObject = synchronized(lock) {
        val output = base("prepare")
        try {
            check(Build.VERSION.SDK_INT >= 29)
            check(notifications.isNotificationPolicyAccessGranted) { "DND access missing" }
            check(Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) { "Exact expiry alarm access missing" }
            val old = finishLocked("explicit_prepare_replaces_previous_session")
            output.put("previousSessionCleanup", old)
            check(old.getBoolean("complete")) { "Previous session requires cleanup; refusing new ownership" }
            val otherRecovery = DndRuleProbe.recoverJournal(app)
            output.put("otherProbeJournalCleanup", otherRecovery)
            check(otherRecovery.getBoolean("complete")) { "Existing short-probe journal requires cleanup" }
            val baseline = DndRuleProbe.observeStableBaseline(app)
            output.put("baseline", baseline)
            check(baseline.getBoolean("stable")) { "Baseline did not settle" }
            val session = UUID.randomUUID().toString()
            val duration = durationMillis.coerceIn(30_000L, 300_000L)
            val elapsed = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            val uri = Uri.Builder().scheme(Condition.SCHEME).authority(app.packageName)
                .appendPath("durable-dnd-ui-probe").appendPath(session).build()
            val journal = JSONObject().put("package", app.packageName).put("session", session)
                .put("name", "Stage0 UI ${session.take(6)}")
                .put("conditionId", uri.toString()).put("bootCount", bootCount())
                .put("createdElapsedMillis", elapsed).put("createdAtMillis", wall)
                .put("expiresElapsedMillis", elapsed + duration).put("expiresAtMillis", wall + duration)
                .put("durationMillis", duration).put("baseline", baseline)
                .put("baselineOwnedIds", JSONArray(notifications.automaticZenRules.keys.toList()))
            check(journal.getInt("bootCount") >= 0) { "Boot identity unavailable; refusing unrecoverable session" }
            writeJournal(journal) // Pre-journal exact URI, before system generates an ID.
            armExpiry(journal) // Recovery route exists before activating a restrictive contribution.
            val component = ComponentName(app, DndRuleConfigurationActivity::class.java)
            val rule = if (Build.VERSION.SDK_INT >= 35) {
                AutomaticZenRule.Builder(journal.getString("name"), uri)
                    .setConfigurationActivity(component)
                    .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                    .setType(AutomaticZenRule.TYPE_THEATER)
                    .setManualInvocationAllowed(false)
                    .setTriggerDescription("Stage 0 real Modes stop test; finite five-minute maximum logical session")
                    .setEnabled(true).build()
            } else AutomaticZenRule(journal.getString("name"), null, component, uri, null,
                NotificationManager.INTERRUPTION_FILTER_NONE, true)
            val id = notifications.addAutomaticZenRule(rule)
            journal.put("ruleId", id)
            writeJournal(journal)
            notifications.setAutomaticZenRuleState(id, ordinaryTrue(journal))
            journal.put("trueWrites", 1)
            writeJournal(journal)
            output.put("result", "prepared").put("session", publicJournal(journal))
                .put("settledAfterPrepare", DndRuleProbe.observeStableBaseline(app))
        } catch (failure: Throwable) {
            output.put("result", "failed").put("error", failure.toString())
                .put("cleanup", finishLocked("prepare_failure"))
        }
        output.put("snapshot", snapshot())
        record(output)
    }

    /** Platform reads only; observed capability loss is latched locally so grant return cannot revive. */
    fun observe(): JSONObject = synchronized(lock) {
        val output = base("observe")
        try {
            val journal = readJournal()
            if (journal != null) {
                val capabilityLoss = capabilityLossReason()
                if (capabilityLoss != null && !journal.has("invalidated")) {
                    journal.put("invalidated", capabilityLoss)
                        .put("invalidationObservedAtMillis", System.currentTimeMillis())
                        .put("invalidationObservedElapsedMillis", SystemClock.elapsedRealtime())
                        .put("invalidationSource", "observe_capability_loss_local_journal_only")
                    writeJournal(journal)
                    output.put("localJournalInvalidationPersisted", true)
                        .put("invalidationReason", capabilityLoss)
                }
                output.put("session", publicJournal(journal)).put("lifecycle", lifecycle(journal))
                invalidReason(journal)?.let { output.put("cleanupPendingReason", it) }
            }
            output.put("result", "observed").put("platformMutation", false).put("snapshot", snapshot())
        } catch (failure: Throwable) { output.put("result", "failed").put("error", failure.toString()) }
        record(output)
    }

    /** Same ID and URI, ordinary source, no FALSE, definition update, enable write or recreation. */
    fun repeatTrue(): JSONObject = synchronized(lock) {
        val output = base("repeat_true")
        try {
            val journal = checkNotNull(readJournal()) { "No session is prepared" }
            output.put("session", publicJournal(journal)).put("before", snapshot())
            val invalid = invalidReason(journal)
            if (invalid != null) {
                output.put("result", "refused_invalid_session").put("reason", invalid)
                    .put("cleanup", finishLocked(invalid))
            } else {
                val id = journal.getString("ruleId")
                val rule = notifications.getAutomaticZenRule(id)
                when {
                    rule == null -> output.put("result", "refused_user_removed_rule").put("cleanup", finishLocked("user_removed"))
                    !rule.isEnabled -> output.put("result", "refused_user_disabled_rule")
                    rule.conditionId?.toString() != journal.getString("conditionId") ->
                        output.put("result", "refused_rule_definition_changed")
                    else -> {
                        notifications.setAutomaticZenRuleState(id, ordinaryTrue(journal))
                        journal.put("trueWrites", journal.optInt("trueWrites", 1) + 1)
                        writeJournal(journal)
                        output.put("result", "ordinary_true_repeated")
                            .put("session", publicJournal(journal))
                            .put("settledAfterRepeat", DndRuleProbe.observeStableBaseline(app))
                    }
                }
            }
            output.put("after", snapshot())
        } catch (failure: Throwable) { output.put("result", "failed").put("error", failure.toString()) }
        record(output)
    }

    /** Removes only this session's recorded rule; never replays a global baseline. */
    fun finish(reason: String = "explicit_finish"): JSONObject = synchronized(lock) {
        val output = base("finish").put("beforeCleanup", snapshot()).put("cleanup", finishLocked(reason))
            .put("snapshot", snapshot())
        record(output)
    }

    /** Generation guard rejects old alarm deliveries; a reboot invalidates instead of reactivating. */
    fun onDeadlineOrBoot(intent: Intent): JSONObject = synchronized(lock) {
        val output = base("deadline_or_boot").put("receivedAction", intent.action)
        try {
            val journal = readJournal()
            if (journal != null) output.put("sessionAtDelivery", publicJournal(journal))
                .put("lifecycleAtDelivery", lifecycle(journal)).put("beforeCleanup", snapshot())
            when {
                journal == null -> output.put("result", "no_session")
                intent.action == Intent.ACTION_BOOT_COMPLETED -> {
                    // A queued boot broadcast can arrive after a new current-boot session began.
                    // Invalidate old-boot/expired sessions, not a live session from this boot.
                    val invalid = invalidReason(journal)
                    if (invalid != null) output.put("cleanup", finishLocked(invalid))
                    else output.put("result", "current_boot_live_session_boot_broadcast_ignored")
                }
                intent.action != ACTION_EXPIRE -> output.put("result", "unrecognized_delivery_ignored")
                intent.getStringExtra("session") != journal.getString("session") -> output.put("result", "obsolete_alarm_ignored")
                else -> {
                    val invalid = invalidReason(journal)
                    if (invalid != null) output.put("cleanup", finishLocked(invalid))
                    else output.put("result", "early_alarm_no_rule_change")
                }
            }
        } catch (failure: Throwable) { output.put("result", "failed").put("error", failure.toString()) }
        output.put("snapshot", snapshot())
        record(output)
    }

    /** A status extra is evidence only; it never changes a condition or authorizes a new session. */
    fun noteStatus(intent: Intent, senderUid: Int = -1): JSONObject = synchronized(lock) {
        val output = base("rule_status").put("senderUid", senderUid)
        try {
            val journal = readJournal()
            val id = intent.getStringExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID)
            if (journal != null && journal.optString("ruleId") == id) {
                output.put("ruleId", id)
                    .put("status", intent.getIntExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_STATUS, -999))
                    .put("session", publicJournal(journal)).put("snapshot", snapshot())
            } else output.put("result", "unrelated_status_ignored")
        } catch (failure: Throwable) { output.put("error", failure.toString()) }
        record(output)
    }

    private fun invalidReason(journal: JSONObject): String? {
        if (journal.has("invalidated")) return journal.getString("invalidated")
        capabilityLossReason()?.let { return it }
        return when {
            journal.getInt("bootCount") != bootCount() -> "boot_identity_changed"
            SystemClock.elapsedRealtime() < journal.getLong("createdElapsedMillis") -> "monotonic_session_changed"
            SystemClock.elapsedRealtime() >= journal.getLong("expiresElapsedMillis") -> "logical_elapsed_expiry"
            System.currentTimeMillis() >= journal.getLong("expiresAtMillis") -> "logical_wall_expiry"
            else -> null
        }
    }

    private fun capabilityLossReason(): String? = when {
        !notifications.isNotificationPolicyAccessGranted -> "dnd_access_lost"
        Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms() -> "exact_alarm_access_lost"
        else -> null
    }

    private fun finishLocked(reason: String): JSONObject {
        val output = JSONObject().put("reason", reason).put("removedRuleIds", JSONArray())
        return try {
            val journal = readJournal() ?: return output.put("complete", true).put("journalPresent", false)
            journal.put("invalidated", reason)
            val atInvalidation = lifecycle(journal)
            journal.put("logical_expired", atInvalidation.getBoolean("logical_expired"))
                .put("lifecycleAtInvalidation", atInvalidation)
            writeJournal(journal) // Permission return cannot silently make this session valid again.
            output.put("journalPresent", true).put("session", publicJournal(journal))
            check(notifications.isNotificationPolicyAccessGranted) { "DND access missing; keep journal for scoped cleanup after access returns" }
            val baseline = journal.getJSONArray("baselineOwnedIds")
            val baselineIds = (0 until baseline.length()).map { baseline.getString(it) }.toSet()
            val existing = notifications.automaticZenRules
            val knownId = journal.optString("ruleId")
            val ids = if (knownId.isNotEmpty()) {
                check(knownId !in baselineIds) { "Refusing baseline-owned ID" }
                if (existing.containsKey(knownId)) setOf(knownId) else emptySet()
            } else {
                existing.filter { (id, rule) ->
                    id !in baselineIds && rule.conditionId?.toString() == journal.getString("conditionId") &&
                        rule.configurationActivity == ComponentName(app, DndRuleConfigurationActivity::class.java)
                }.keys.also { check(it.size <= 1) { "Pending creation is ambiguous; preserve evidence" } }
            }
            for (id in ids) {
                check(notifications.removeAutomaticZenRule(id)) { "Session rule removal failed" }
                output.getJSONArray("removedRuleIds").put(id)
            }
            check(ids.none { notifications.automaticZenRules.containsKey(it) }) { "Session rule still present" }
            expiryIntent(journal, PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() }
            journalFile().delete()
            output.put("complete", true)
        } catch (failure: Throwable) { output.put("complete", false).put("error", failure.toString()) }
    }

    private fun ordinaryTrue(journal: JSONObject) = Condition(Uri.parse(journal.getString("conditionId")),
        "Stage 0 ordinary automatic presence condition", Condition.STATE_TRUE)

    private fun armExpiry(journal: JSONObject) {
        val intent = checkNotNull(expiryIntent(journal, PendingIntent.FLAG_UPDATE_CURRENT))
        alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            journal.getLong("expiresElapsedMillis"), intent)
    }

    private fun expiryIntent(journal: JSONObject, flags: Int): PendingIntent? = PendingIntent.getBroadcast(app, 0,
        Intent(app, DndSessionExpiryReceiver::class.java).setAction(ACTION_EXPIRE)
            .setData(Uri.parse(journal.getString("conditionId")))
            .putExtra("session", journal.getString("session")), flags or PendingIntent.FLAG_IMMUTABLE)

    private fun bootCount() = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private fun lifecycle(journal: JSONObject): JSONObject {
        val elapsed = SystemClock.elapsedRealtime()
        val boot = bootCount()
        val sameBoot = journal.getInt("bootCount") == boot
        val expired = !sameBoot || elapsed < journal.getLong("createdElapsedMillis") ||
            elapsed >= journal.getLong("expiresElapsedMillis") || System.currentTimeMillis() >= journal.getLong("expiresAtMillis")
        return JSONObject().put("logical_expired", expired).put("currentBootCount", boot)
            .put("journalBootCount", journal.getInt("bootCount")).put("sameBoot", sameBoot)
            .put("observedElapsedMillis", elapsed).put("observedAtMillis", System.currentTimeMillis())
            .put("deliveryLateMillis", if (sameBoot) (elapsed - journal.getLong("expiresElapsedMillis")).coerceAtLeast(0) else JSONObject.NULL)
            .put("invalidated", journal.optString("invalidated", ""))
    }
    private fun journalFile() = AtomicFile(File(app.noBackupFilesDir, "dnd-session/active-session.json"))
    private fun readJournal(): JSONObject? {
        val file = journalFile()
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use { JSONObject(it.readText()) }.also {
            check(it.getString("package") == app.packageName) { "Session journal package mismatch" }
        }
    }
    private fun writeJournal(value: JSONObject) = writeJson(journalFile(), value)
    private fun publicJournal(journal: JSONObject): JSONObject = JSONObject(journal.toString())
        .put("logicalExpiryIsNotGuaranteedDelivery", true)
        .put("noLocationOrAttendanceData", true)

    private fun snapshot(): JSONObject {
        val value = JSONObject().put("atMillis", System.currentTimeMillis())
            .put("elapsedMillis", SystemClock.elapsedRealtime()).put("sdk", Build.VERSION.SDK_INT)
            .put("bootCount", bootCount()).put("policyAccess", notifications.isNotificationPolicyAccessGranted)
            .put("canScheduleExactAlarms", Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
            .put("getterIsNotSoleSnoozeProof", true)
        runCatching {
            value.put("aggregateFilter", notifications.currentInterruptionFilter)
                .put("ringerMode", app.getSystemService(AudioManager::class.java).ringerMode)
            val owned = JSONArray()
            for ((id, rule) in notifications.automaticZenRules.toSortedMap()) {
                val entry = JSONObject().put("id", id).put("name", rule.name).put("enabled", rule.isEnabled)
                    .put("conditionId", rule.conditionId?.toString()).put("filter", rule.interruptionFilter)
                    .put("policy", rule.zenPolicy?.toString())
                if (Build.VERSION.SDK_INT >= 35) entry.put("observedState", notifications.getAutomaticZenRuleState(id))
                owned.put(entry)
            }
            value.put("ownedRules", owned)
        }.onFailure { value.put("observationError", it.toString()) }
        return value
    }

    private fun base(operation: String) = JSONObject().put("operation", operation)
        .put("atMillis", System.currentTimeMillis()).put("elapsedMillis", SystemClock.elapsedRealtime())
        .put("physicalAudioOrVibrationTested", false).put("realSystemUiSnoozeInferredByCode", false)
        .put("capabilityObservationLimit", "Loss and restoration while this process/receivers do not observe them cannot be inferred from the current grant")
        .put("scope", "Only isolated-package session IDs; parent supplies actual Modes UI evidence and other-owner snapshots")

    private fun record(value: JSONObject): JSONObject {
        val folder = File(app.filesDir, "dnd-session").apply { mkdirs() }
        val file = File(folder, "${value.optLong("atMillis")}-${value.optString("operation")}-${UUID.randomUUID().toString().take(6)}.json")
        value.put("reportPath", file.absolutePath)
        writeJson(AtomicFile(file), value)
        writeJson(AtomicFile(File(folder, "latest.json")), value)
        val events = File(folder, "events.jsonl")
        if (events.exists() && events.length() > 256_000L) events.renameTo(File(folder, "events-previous.jsonl"))
        events.appendText(value.toString() + "\n")
        Log.i("Stage0DndSession", value.toString())
        return value
    }

    companion object {
        const val ACTION_PREPARE = "com.tunisianprayertimes.stage0.dnd.PREPARE"
        const val ACTION_OBSERVE = "com.tunisianprayertimes.stage0.dnd.OBSERVE"
        const val ACTION_REPEAT_TRUE = "com.tunisianprayertimes.stage0.dnd.REPEAT_TRUE"
        const val ACTION_FINISH = "com.tunisianprayertimes.stage0.dnd.FINISH"
        const val ACTION_EXPIRE = "com.tunisianprayertimes.stage0.dnd.EXPIRE"
        private val lock = Any()
        private fun writeJson(file: AtomicFile, value: JSONObject) {
            file.baseFile.parentFile?.mkdirs()
            val stream = file.startWrite()
            try {
                stream.write(value.toString(2).toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (failure: Throwable) { file.failWrite(stream); throw failure }
        }
    }
}
