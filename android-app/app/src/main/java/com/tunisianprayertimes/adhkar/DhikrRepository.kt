package com.tunisianprayertimes.adhkar

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One committed record keeps the reading session and its optional goal counter atomic. */
class DhikrRepository(context: Context) {
    private val app = context.applicationContext
    private val store = synchronized(stores) {
        stores.getOrPut(app.filesDir.absolutePath) { Store(app.getSharedPreferences("adhkar_reminders", Context.MODE_PRIVATE)) }
    }
    val state: StateFlow<DhikrState> = store.state.asStateFlow()
    private fun update(transform: (DhikrState) -> DhikrState) = synchronized(DhikrReminderScheduler.schedulingLock) {
        val next = transform(store.state.value)
        if (next != store.state.value) store.write(next)
    }
    fun save(rule: DhikrReminder, now: Long = System.currentTimeMillis()) {
        require(DhikrReminderScheduler.validate(app, rule) == null)
        update { old ->
            val previous = old.reminders.find { it.id == rule.id }
            val changed = previous != null && previous.copy(enabled = rule.enabled) != rule
            val current = old.occurrences.values.filter { it.ruleId == rule.id && now in it.startMillis until it.endMillis }
            val saved = if (changed) rule.copy(revision = previous!!.revision + 1,
                notBeforeMillis = maxOf(now, current.maxOfOrNull { it.endMillis } ?: now)) else rule
            old.copy(reminders = old.reminders.filterNot { it.id == saved.id } + saved,
                occurrences = if (!changed) old.occurrences else old.occurrences.mapValues { (_, occurrence) ->
                    if (occurrence.ruleId == rule.id && occurrence.status == DhikrOccurrenceStatus.OPEN)
                        occurrence.copy(status = DhikrOccurrenceStatus.REPLACED) else occurrence
                })
        }
        DhikrReminderScheduler.refresh(app)
    }
    /** Delivery stops; history and occurrence snapshots are retained for delete undo. */
    fun delete(id: String) {
        update { it.copy(reminders = it.reminders.filterNot { rule -> rule.id == id }) }
        DhikrReminderScheduler.refresh(app)
    }
    fun restore(rule: DhikrReminder) {
        update { it.copy(reminders = it.reminders.filterNot { item -> item.id == rule.id } + rule) }
        DhikrReminderScheduler.refresh(app)
    }
    fun setEnabled(id: String, enabled: Boolean) {
        update { it.copy(reminders = it.reminders.map { rule -> if (rule.id == id) rule.copy(enabled = enabled) else rule }) }
        DhikrReminderScheduler.refresh(app)
    }
    fun toggleFavourite(id: String) = update {
        it.copy(favourites = if (id in it.favourites) it.favourites - id else it.favourites + id)
    }
    fun setTextSize(size: Int) = update { it.copy(textSize = size.coerceIn(24, 40)) }
    fun setHaptics(enabled: Boolean) = update { it.copy(countHaptics = enabled) }

    fun ensureOccurrence(rule: DhikrReminder, window: DhikrWindow): DhikrOccurrence {
        update { old ->
            val existing = old.occurrences[window.progressKey]
            val legacy = if (rule.revision == 1) store.legacyCount(rule.id + "|" + window.date) else 0
            val occurrence = existing?.copy(startMillis = window.startMillis, endMillis = window.endMillis)
                ?: DhikrOccurrence(window.progressKey, rule.id, rule.revision, window.date.toString(), rule.dhikrId,
                    rule.targetCount, window.startMillis, window.endMillis, legacy.coerceAtMost(rule.targetCount),
                    if (legacy >= rule.targetCount) DhikrOccurrenceStatus.COMPLETED else DhikrOccurrenceStatus.OPEN)
            old.copy(occurrences = old.occurrences + (occurrence.id to occurrence))
        }
        return state.value.occurrences.getValue(window.progressKey)
    }
    fun skip(occurrenceId: String) {
        update { old -> old.occurrences[occurrenceId]?.let {
            old.copy(occurrences = old.occurrences + (it.id to it.copy(status = DhikrOccurrenceStatus.SKIPPED)))
        } ?: old }
        DhikrReminderScheduler.refresh(app)
    }
    fun snooze(occurrenceId: String, now: Long = System.currentTimeMillis()): Boolean {
        var accepted = false
        update { old ->
            val occurrence = old.occurrences[occurrenceId]
            if (occurrence == null || occurrence.status != DhikrOccurrenceStatus.OPEN ||
                now !in occurrence.startMillis until occurrence.endMillis || now + 30 * 60_000 >= occurrence.endMillis) old
            else {
                accepted = true
                old.copy(occurrences = old.occurrences + (occurrence.id to occurrence.copy(snoozedUntilMillis = now + 30 * 60_000)))
            }
        }
        DhikrReminderScheduler.refresh(app)
        return accepted
    }
    fun openSession(items: List<String>, category: DhikrCategory? = null, occurrenceId: String? = null,
                    fresh: Boolean = false): String {
        require(items.isNotEmpty() && items.all { DhikrCatalog.find(it) != null })
        var selected = ""
        update { old ->
            val occurrence = occurrenceId?.let { old.occurrences[it] }
            require(occurrenceId == null || occurrence != null)
            val existing = if (fresh) null else old.sessions.values.filter {
                it.itemIds == items && it.category == category && it.occurrenceId == occurrenceId
            }.maxByOrNull { it.updatedAtMillis }
            val migratedCounts = if (occurrence != null) mapOf(occurrence.dhikrId to occurrence.count)
                else if (fresh) emptyMap() else items.associateWith { store.legacyCount("reading|" + it).coerceAtMost(DhikrCatalog.find(it)!!.defaultCount) }
            val session = existing?.copy(updatedAtMillis = System.currentTimeMillis())
                ?: DhikrSession(itemIds = items, category = category, occurrenceId = occurrenceId, counts = migratedCounts)
            selected = session.id
            old.copy(sessions = old.sessions + (session.id to session), lastSessionId = session.id)
        }
        return selected
    }
    fun resumeSession(id: String) = update { old ->
        old.sessions[id]?.let { session ->
            old.copy(lastSessionId = id, sessions = old.sessions + (id to session.copy(updatedAtMillis = System.currentTimeMillis())))
        } ?: old
    }
    fun count(sessionId: String, delta: Int, now: Long = System.currentTimeMillis()) {
        if (delta != 1 && delta != -1) return
        update { old ->
            val session = old.sessions[sessionId] ?: return@update old
            val occurrence = session.occurrenceId?.let { old.occurrences[it] }
            if (session.occurrenceId != null && (occurrence == null || now !in occurrence.startMillis until occurrence.endMillis ||
                    occurrence.status == DhikrOccurrenceStatus.SKIPPED || occurrence.status == DhikrOccurrenceStatus.REPLACED)) return@update old
            val count = ((session.counts[session.itemId] ?: 0) + delta).coerceIn(0, old.target(session))
            val updated = session.copy(counts = session.counts + (session.itemId to count), updatedAtMillis = now)
            val occurrences = if (occurrence == null) old.occurrences else old.occurrences + (occurrence.id to occurrence.copy(
                count = count, status = if (count >= occurrence.target) DhikrOccurrenceStatus.COMPLETED else DhikrOccurrenceStatus.OPEN))
            old.copy(sessions = old.sessions + (session.id to updated), occurrences = occurrences, lastSessionId = session.id)
        }
        if (state.value.sessions[sessionId]?.occurrenceId != null) DhikrReminderScheduler.refresh(app)
    }
    fun move(sessionId: String, direction: Int) = update { old ->
        val session = old.sessions[sessionId] ?: return@update old
        if (direction > 0 && (session.counts[session.itemId] ?: 0) < old.target(session)) return@update old
        val updated = session.copy(index = (session.index + direction).coerceIn(0, session.itemIds.lastIndex), updatedAtMillis = System.currentTimeMillis())
        old.copy(sessions = old.sessions + (sessionId to updated), lastSessionId = sessionId)
    }
    private class Store(private val prefs: SharedPreferences) {
        val state = MutableStateFlow(read())
        fun legacyCount(key: String): Int = runCatching { JSONObject(prefs.getString("progress", "{}") ?: "{}").optInt(key, 0) }.getOrDefault(0)
        fun write(value: DhikrState) {
            check(prefs.edit().putString("state_v2", value.toJson().toString()).commit()) { "Could not save Adhkar state" }
            state.value = value
        }
        private fun read(): DhikrState {
            val json = prefs.getString("state_v2", null)
            if (json != null) return dhikrStateFromJson(JSONObject(json))
            val legacyRules = runCatching { JSONArray(prefs.getString("reminders", "[]") ?: "[]") }.getOrDefault(JSONArray())
            return DhikrState(reminders = legacyRules.objects().mapNotNull { runCatching { dhikrReminderFromJson(it) }.getOrNull() })
        }
    }
    companion object {
        private val stores = mutableMapOf<String, Store>()
        internal fun clearMemoryCache() = synchronized(stores) { stores.clear() }
    }
}
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.stringList(key: String) = optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
private fun JSONObject.countMap(key: String): Map<String, Int> = optJSONObject(key)?.let { json -> json.keys().asSequence().associateWith { json.optInt(it).coerceAtLeast(0) } }.orEmpty()
private fun DhikrState.toJson(): JSONObject = JSONObject()
    .put("rules", JSONArray(reminders.map { it.toJson() }))
    .put("favourites", JSONArray(favourites.toList())).put("lastSessionId", lastSessionId ?: JSONObject.NULL)
    .put("textSize", textSize).put("countHaptics", countHaptics)
    .put("occurrences", JSONArray(occurrences.values.map { o -> JSONObject()
        .put("id", o.id).put("ruleId", o.ruleId).put("revision", o.revision).put("date", o.date)
        .put("dhikrId", o.dhikrId).put("target", o.target).put("start", o.startMillis).put("end", o.endMillis)
        .put("count", o.count).put("status", o.status.name).put("snooze", o.snoozedUntilMillis) }))
    .put("sessions", JSONArray(sessions.values.map { s -> JSONObject()
        .put("id", s.id).put("items", JSONArray(s.itemIds)).put("counts", JSONObject(s.counts)).put("index", s.index)
        .put("category", s.category?.name ?: JSONObject.NULL).put("occurrenceId", s.occurrenceId ?: JSONObject.NULL)
        .put("updated", s.updatedAtMillis) }))
private fun dhikrStateFromJson(json: JSONObject): DhikrState {
    val occurrences = json.optJSONArray("occurrences")?.objects().orEmpty().mapNotNull { o -> runCatching {
        DhikrOccurrence(o.getString("id"), o.getString("ruleId"), o.optInt("revision", 1), o.getString("date"),
            o.getString("dhikrId"), o.getInt("target"), o.getLong("start"), o.getLong("end"), o.optInt("count"),
            DhikrOccurrenceStatus.valueOf(o.getString("status")), o.optLong("snooze"))
    }.getOrNull() }.associateBy { it.id }
    val sessions = json.optJSONArray("sessions")?.objects().orEmpty().mapNotNull { s -> runCatching {
        val items = s.stringList("items").filter { DhikrCatalog.find(it) != null }
        if (items.isEmpty()) return@runCatching null
        DhikrSession(s.getString("id"), items, s.countMap("counts"), s.optInt("index").coerceIn(0, items.lastIndex),
            if (s.isNull("category")) null else DhikrCategory.valueOf(s.getString("category")),
            if (s.isNull("occurrenceId")) null else s.getString("occurrenceId"), s.optLong("updated"))
    }.getOrNull() }.associateBy { it.id }
    return DhikrState(json.optJSONArray("rules")?.objects().orEmpty().mapNotNull { runCatching { dhikrReminderFromJson(it) }.getOrNull() },
        occurrences, sessions, json.stringList("favourites").toSet(),
        if (json.isNull("lastSessionId")) null else json.optString("lastSessionId"), json.optInt("textSize", 28).coerceIn(24, 40),
        json.optBoolean("countHaptics"))
}
/** Lifecycle-controlled presence, not a background timer. */
object DhikrReadingPresence {
    @Volatile var occurrenceId: String? = null
}
