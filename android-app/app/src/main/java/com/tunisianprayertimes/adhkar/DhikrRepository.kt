package com.tunisianprayertimes.adhkar

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
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
    /** Adds or edits a personal dhikr. A blank id means a new entry; edits keep the stored id. */
    fun saveCustom(entry: DhikrEntry, now: Long = System.currentTimeMillis()): DhikrEntry {
        val title = entry.title.trim()
        val text = entry.text.trim()
        require(title.isNotEmpty() && text.isNotEmpty() && entry.defaultCount in 1..100_000)
        val id = entry.id.ifBlank { "personal_" + UUID.randomUUID() }
        require(DhikrCatalog.find(id) == null) { "معرّف الذكر محجوز" }
        val saved = entry.copy(id = id, title = title, text = text, reference = entry.reference.trim(),
            categories = emptySet(), custom = true)
        var countChanged = false
        update { old ->
            val previous = old.customEntries.find { it.id == id }
            countChanged = previous != null && previous.defaultCount != saved.defaultCount
            val changed = if (previous != null) old.copy(customEntries = old.customEntries.map { if (it.id == id) saved else it })
                else old.copy(customEntries = listOf(saved) + old.customEntries)
            if (!countChanged) return@update changed
            var occurrences = changed.occurrences
            changed.sessions.values.filter { it.category != null && id in it.itemIds && it.occurrenceId != null }
                .forEach { session ->
                    val occurrenceId = session.occurrenceId ?: return@forEach
                    val occurrence = occurrences[occurrenceId] ?: return@forEach
                    val active = now in occurrence.startMillis until occurrence.endMillis &&
                        occurrence.status != DhikrOccurrenceStatus.SKIPPED && occurrence.status != DhikrOccurrenceStatus.REPLACED &&
                        changed.reminders.any { rule -> rule.id == occurrence.ruleId && rule.enabled &&
                            rule.revision == occurrence.revision && rule.collection == session.category }
                    if (!active) return@forEach
                    val complete = changed.isComplete(session)
                    val allSkipped = session.itemIds.all { it in session.skippedIds }
                    occurrences = occurrences + (occurrence.id to occurrence.copy(
                        count = if (complete && !allSkipped) occurrence.target else 0,
                        status = when {
                            allSkipped -> DhikrOccurrenceStatus.SKIPPED
                            complete -> DhikrOccurrenceStatus.COMPLETED
                            else -> DhikrOccurrenceStatus.OPEN
                        },
                    ))
                }
            changed.copy(occurrences = occurrences)
        }
        if (countChanged) DhikrReminderScheduler.refresh(app)
        return saved
    }
    /** Removes the entry with its reminders and reading sessions; the snapshot supports undo. */
    fun deleteCustom(id: String): CustomDhikrRemoval? {
        val current = state.value
        val entry = current.customEntries.find { it.id == id } ?: return null
        val removal = CustomDhikrRemoval(entry, current.reminders.filter { it.dhikrId == id },
            current.sessions.values.filter { id in it.itemIds }, id in current.favourites)
        val sessionIds = removal.sessions.map { it.id }.toSet()
        update { old -> old.copy(
            customEntries = old.customEntries.filterNot { it.id == id },
            reminders = old.reminders.filterNot { it.dhikrId == id },
            sessions = old.sessions.filterKeys { it !in sessionIds },
            favourites = old.favourites - id,
            lastSessionId = old.lastSessionId?.takeIf { it !in sessionIds },
        ) }
        DhikrReminderScheduler.refresh(app)
        return removal
    }
    /** Adds or removes an entry from one collection; other collections are untouched. */
    fun setCollectionMembership(category: DhikrCategory, id: String, member: Boolean,
                                now: Long = System.currentTimeMillis()) {
        require(state.value.findDhikr(id) != null)
        update { old ->
            val additions = old.collectionAdditions[category].orEmpty().let { if (member) it + id else it - id }
            val removals = old.collectionRemovals[category].orEmpty().let { if (member) it - id else it + id }
            val order = if (member) old.collectionEntries(category).map { it.id }.let { ids ->
                if (id in ids) ids else ids + id
            } else null
            val changed = old.copy(
                collectionAdditions = old.collectionAdditions.updatedWith(category, additions),
                collectionRemovals = old.collectionRemovals.updatedWith(category, removals),
                collectionOrders = order?.let { old.collectionOrders + (category to it) } ?: old.collectionOrders,
            )
            val items = changed.collectionEntries(category).map { it.id }
            if (items.isEmpty()) return@update changed
            val itemSet = items.toSet()
            var sessions = changed.sessions
            var occurrences = changed.occurrences
            changed.sessions.values.filter { it.category == category && it.occurrenceId != null }.forEach { session ->
                val occurrenceId = session.occurrenceId ?: return@forEach
                val occurrence = occurrences[occurrenceId] ?: return@forEach
                val active = now in occurrence.startMillis until occurrence.endMillis &&
                    occurrence.status != DhikrOccurrenceStatus.SKIPPED && occurrence.status != DhikrOccurrenceStatus.REPLACED &&
                    changed.reminders.any { rule -> rule.id == occurrence.ruleId && rule.enabled &&
                        rule.revision == occurrence.revision && rule.collection == category }
                if (!active || session.itemIds == items) return@forEach
                val updated = session.copy(itemIds = items, counts = session.counts.filterKeys { it in itemSet },
                    skippedIds = session.skippedIds.intersect(itemSet),
                    index = items.indexOf(session.itemId).coerceAtLeast(0), updatedAtMillis = now)
                val complete = changed.isComplete(updated)
                val allSkipped = updated.itemIds.all { it in updated.skippedIds }
                sessions = sessions + (session.id to updated)
                occurrences = occurrences + (occurrence.id to occurrence.copy(
                    count = if (complete && !allSkipped) occurrence.target else 0,
                    status = when {
                        allSkipped -> DhikrOccurrenceStatus.SKIPPED
                        complete -> DhikrOccurrenceStatus.COMPLETED
                        else -> DhikrOccurrenceStatus.OPEN
                    },
                ))
            }
            changed.copy(sessions = sessions, occurrences = occurrences)
        }
        DhikrReminderScheduler.refresh(app)
    }
    /** Adds a dhikr to the current collection and its active reading list. */
    fun addToCollectionSession(sessionId: String, id: String, now: Long = System.currentTimeMillis()) {
        require(state.value.findDhikr(id) != null)
        update { old ->
            val session = old.sessions[sessionId] ?: return@update old
            val category = session.category ?: return@update old
            val additions = old.collectionAdditions[category].orEmpty() + id
            val removals = old.collectionRemovals[category].orEmpty() - id
            val order = old.collectionEntries(category).map { it.id }.let { ids -> if (id in ids) ids else ids + id }
            val updatedSession = if (id in session.itemIds) session else session.copy(
                itemIds = session.itemIds + id,
                updatedAtMillis = now,
            )
            val occurrence = session.occurrenceId?.let { old.occurrences[it] }
            val active = occurrence != null && now in occurrence.startMillis until occurrence.endMillis &&
                occurrence.status != DhikrOccurrenceStatus.SKIPPED && occurrence.status != DhikrOccurrenceStatus.REPLACED &&
                old.reminders.any { it.id == occurrence.ruleId && it.enabled && it.revision == occurrence.revision }
            val occurrences = if (!active || updatedSession == session) old.occurrences else {
                val complete = old.isComplete(updatedSession)
                old.occurrences + (occurrence.id to occurrence.copy(
                    count = if (complete) occurrence.target else 0,
                    status = if (complete) DhikrOccurrenceStatus.COMPLETED else DhikrOccurrenceStatus.OPEN,
                ))
            }
            old.copy(
                collectionAdditions = old.collectionAdditions.updatedWith(category, additions),
                collectionRemovals = old.collectionRemovals.updatedWith(category, removals),
                collectionOrders = old.collectionOrders + (category to order),
                sessions = old.sessions + (sessionId to updatedSession),
                occurrences = occurrences,
            )
        }
        DhikrReminderScheduler.refresh(app)
    }
    /** Moves an entry within its collection and keeps the current reading on the same dhikr. */
    fun moveCollectionEntry(sessionId: String, id: String, direction: Int) {
        if (direction != -1 && direction != 1) return
        update { old ->
            val session = old.sessions[sessionId] ?: return@update old
            val category = session.category ?: return@update old
            val ids = old.collectionEntries(category).map { it.id }.toMutableList()
            val from = ids.indexOf(id)
            val to = from + direction
            if (from < 0 || to !in ids.indices) return@update old

            val moved = ids.removeAt(from)
            ids.add(to, moved)
            val idSet = ids.toSet()
            val updatedSession = session.copy(
                itemIds = ids,
                counts = session.counts.filterKeys { it in idSet },
                index = ids.indexOf(session.itemId).coerceAtLeast(0),
                skippedIds = session.skippedIds.intersect(idSet),
                updatedAtMillis = System.currentTimeMillis(),
            )
            old.copy(
                collectionOrders = old.collectionOrders + (category to ids),
                sessions = old.sessions + (sessionId to updatedSession),
            )
        }
    }
    /** Saves a drag-reordered collection and preserves the currently selected dhikr. */
    fun reorderCollection(sessionId: String, orderedIds: List<String>) {
        update { old ->
            val session = old.sessions[sessionId] ?: return@update old
            val category = session.category ?: return@update old
            val currentIds = old.collectionEntries(category).map { it.id }
            if (orderedIds.size != currentIds.size || orderedIds.distinct().size != orderedIds.size ||
                orderedIds.toSet() != currentIds.toSet()) return@update old

            val idSet = orderedIds.toSet()
            val updatedSession = session.copy(
                itemIds = orderedIds,
                counts = session.counts.filterKeys { it in idSet },
                index = orderedIds.indexOf(session.itemId).coerceAtLeast(0),
                skippedIds = session.skippedIds.intersect(idSet),
                updatedAtMillis = System.currentTimeMillis(),
            )
            old.copy(
                collectionOrders = old.collectionOrders + (category to orderedIds),
                sessions = old.sessions + (sessionId to updatedSession),
            )
        }
    }
    fun restoreCustom(removal: CustomDhikrRemoval) {
        update { old -> old.copy(
            customEntries = old.customEntries.filterNot { it.id == removal.entry.id } + removal.entry,
            reminders = old.reminders.filterNot { rule -> removal.reminders.any { it.id == rule.id } } + removal.reminders,
            sessions = old.sessions + removal.sessions.associateBy { it.id },
            favourites = if (removal.favourite) old.favourites + removal.entry.id else old.favourites,
            lastSessionId = removal.sessions.maxByOrNull { it.updatedAtMillis }?.id ?: old.lastSessionId,
        ) }
        DhikrReminderScheduler.refresh(app)
    }
    fun setTextSize(size: Int) = update { it.copy(textSize = size.coerceIn(24, 40)) }
    fun setHaptics(enabled: Boolean) = update { it.copy(countHaptics = enabled) }

    fun ensureOccurrence(rule: DhikrReminder, window: DhikrWindow): DhikrOccurrence {
        update { old ->
            val existing = old.occurrences[window.progressKey]
            val legacy = if (rule.revision == 1) store.legacyCount(rule.id + "|" + window.date) else 0
            val occurrence = existing?.copy(startMillis = window.progressStartMillis, endMillis = window.progressEndMillis)
                ?: DhikrOccurrence(window.progressKey, rule.id, rule.revision, window.date.toString(), rule.dhikrId,
                    rule.targetCount, window.progressStartMillis, window.progressEndMillis, legacy.coerceAtMost(rule.targetCount),
                    if (legacy >= rule.targetCount) DhikrOccurrenceStatus.COMPLETED else DhikrOccurrenceStatus.OPEN)
            old.copy(occurrences = old.occurrences + (occurrence.id to occurrence))
        }
        return state.value.occurrences.getValue(window.progressKey)
    }
    fun skip(occurrenceId: String, now: Long = System.currentTimeMillis()) {
        update { old -> old.occurrences[occurrenceId]?.takeIf {
            it.status == DhikrOccurrenceStatus.OPEN && now < it.endMillis &&
                old.reminders.any { rule -> rule.id == it.ruleId && rule.enabled && rule.revision == it.revision }
        }?.let {
            old.copy(occurrences = old.occurrences + (it.id to it.copy(status = DhikrOccurrenceStatus.SKIPPED)))
        } ?: old }
        DhikrReminderScheduler.refresh(app)
    }
    fun snooze(occurrenceId: String, now: Long = System.currentTimeMillis()): Boolean {
        var accepted = false
        update { old ->
            val occurrence = old.occurrences[occurrenceId]
            val rule = occurrence?.let { current -> old.reminders.firstOrNull {
                it.id == current.ruleId && it.enabled && it.revision == current.revision
            } }
            val activeWindow = if (rule == null || occurrence == null) null else
                runCatching { LocalDate.parse(occurrence.date) }.getOrNull()
                    ?.let { DhikrReminderScheduler.resolveWindows(app, rule, it) }
                    ?.firstOrNull { it.progressKey == occurrence.id && now in it.startMillis until it.endMillis }
            val snoozeUntil = now + 30 * 60_000L
            if (occurrence == null || occurrence.status != DhikrOccurrenceStatus.OPEN ||
                activeWindow == null || snoozeUntil > activeWindow.endMillis - 5 * 60_000L) old else {
                accepted = true
                old.copy(occurrences = old.occurrences + (occurrence.id to occurrence.copy(
                    snoozedUntilMillis = snoozeUntil)))
            }
        }
        DhikrReminderScheduler.refresh(app, nowMillis = now)
        return accepted
    }
    fun openSession(items: List<String>, category: DhikrCategory? = null, occurrenceId: String? = null,
                    fresh: Boolean = false, targetCountOverride: Int? = null,
                    now: Long = System.currentTimeMillis(), collectionReading: Boolean = false): String {
        val known = state.value
        require(items.isNotEmpty() && items.all { known.findDhikr(it) != null })
        require(targetCountOverride == null || targetCountOverride in 1..100_000)
        require(!collectionReading || category != null)
        val periodKey = if (collectionReading) collectionReadingPeriodKey(app, category!!, occurrenceId, now) else null
        var selected = ""
        update { old ->
            val occurrence = occurrenceId?.let { old.occurrences[it] }
            require(occurrenceId == null || occurrence != null)
            // An occurrence has one reading session. If collection membership changes,
            // keep its progress and identity so an older session cannot reopen a goal.
            val existing = if (occurrence != null) old.sessions.values
                .filter { it.occurrenceId == occurrenceId }.maxByOrNull { it.updatedAtMillis }
                else if (fresh) null else if (periodKey != null) {
                    old.sessions.values.filter {
                        it.occurrenceId == null && it.category == category && it.collectionPeriodKey == periodKey &&
                            it.targetCountOverride == targetCountOverride
                    }.maxByOrNull { it.updatedAtMillis }
                        ?: if (category == DhikrCategory.MORNING || category == DhikrCategory.EVENING) null
                        else old.sessions.values.filter {
                            it.occurrenceId == null && it.category == category && it.collectionPeriodKey == null &&
                                it.itemIds == items && it.targetCountOverride == targetCountOverride
                        }.maxByOrNull { it.updatedAtMillis }
                }
                else old.sessions.values.filter {
                    it.itemIds == items && it.category == category && it.occurrenceId == null && it.collectionPeriodKey == null &&
                        it.targetCountOverride == targetCountOverride
                }.maxByOrNull { it.updatedAtMillis }
            val migratedCounts = if (occurrence != null && category == null) mapOf(occurrence.dhikrId to occurrence.count)
                else if (occurrence != null || fresh || periodKey != null) emptyMap() else items.associateWith {
                    store.legacyCount("reading|" + it).coerceAtMost(targetCountOverride ?: old.findDhikr(it)!!.countForCollection(category))
                }
            val savedAt = if (fresh && periodKey != null && occurrence == null) {
                val latest = old.sessions.values.filter { it.collectionPeriodKey == periodKey }
                    .maxOfOrNull { it.updatedAtMillis }
                if (latest != null && latest >= now && latest < Long.MAX_VALUE) latest + 1 else now
            } else now
            val session = existing?.copy(itemIds = items, category = category,
                counts = existing.counts.filterKeys { it in items },
                skippedIds = existing.skippedIds.intersect(items.toSet()),
                index = items.indexOf(existing.itemId).coerceAtLeast(0),
                targetCountOverride = targetCountOverride, collectionPeriodKey = periodKey, updatedAtMillis = savedAt)
                ?: DhikrSession(itemIds = items, category = category, occurrenceId = occurrenceId, counts = migratedCounts,
                    targetCountOverride = targetCountOverride, collectionPeriodKey = periodKey, updatedAtMillis = savedAt)
            selected = session.id
            val sessions = if (occurrenceId == null) old.sessions else old.sessions.filterValues {
                it.occurrenceId != occurrenceId || it.id == session.id
            }
            val membershipChanged = existing != null && existing.itemIds != items
            val active = membershipChanged && occurrence != null && category != null &&
                now in occurrence.startMillis until occurrence.endMillis &&
                occurrence.status != DhikrOccurrenceStatus.SKIPPED && occurrence.status != DhikrOccurrenceStatus.REPLACED &&
                old.reminders.any { it.id == occurrence.ruleId && it.enabled && it.revision == occurrence.revision }
            val occurrences = if (!active) old.occurrences else {
                val complete = old.isComplete(session)
                old.occurrences + (occurrence.id to occurrence.copy(
                    count = if (complete) occurrence.target else 0,
                    status = if (complete) DhikrOccurrenceStatus.COMPLETED else DhikrOccurrenceStatus.OPEN,
                ))
            }
            old.copy(sessions = sessions + (session.id to session), occurrences = occurrences, lastSessionId = session.id)
        }
        if (occurrenceId != null) DhikrReminderScheduler.refresh(app)
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
                    occurrence.status == DhikrOccurrenceStatus.SKIPPED || occurrence.status == DhikrOccurrenceStatus.REPLACED ||
                    old.reminders.none { it.id == occurrence.ruleId && it.enabled && it.revision == occurrence.revision })) return@update old
            val count = ((session.counts[session.itemId] ?: 0) + delta).coerceIn(0, old.target(session))
            val updated = session.copy(counts = session.counts + (session.itemId to count),
                skippedIds = if (delta > 0) session.skippedIds - session.itemId else session.skippedIds,
                updatedAtMillis = now)
            val occurrences = if (occurrence == null) old.occurrences else old.occurrences + (occurrence.id to occurrence.copy(
                count = if (session.category != null) if (old.isComplete(updated)) occurrence.target else 0 else count,
                status = if (session.category != null) {
                    if (old.isComplete(updated)) DhikrOccurrenceStatus.COMPLETED else DhikrOccurrenceStatus.OPEN
                } else if (count >= occurrence.target) DhikrOccurrenceStatus.COMPLETED else DhikrOccurrenceStatus.OPEN))
            old.copy(sessions = old.sessions + (session.id to updated), occurrences = occurrences, lastSessionId = session.id)
        }
        if (state.value.sessions[sessionId]?.occurrenceId != null) DhikrReminderScheduler.refresh(app)
    }
    /** Navigation is free and wraps around; completion is never a constraint. */
    fun move(sessionId: String, direction: Int) = update { old ->
        val session = old.sessions[sessionId] ?: return@update old
        val size = session.itemIds.size
        if (size <= 1 || direction == 0) return@update old
        val index = ((session.index + direction) % size + size) % size
        val updated = session.copy(index = index, updatedAtMillis = System.currentTimeMillis())
        old.copy(sessions = old.sessions + (sessionId to updated), lastSessionId = sessionId)
    }
    /** Marks the current item skipped and advances; skipped items no longer block list completion. */
    fun skipItem(sessionId: String, now: Long = System.currentTimeMillis()) {
        update { old ->
            val session = old.sessions[sessionId] ?: return@update old
            val updated = session.copy(
                skippedIds = if ((session.counts[session.itemId] ?: 0) >= old.target(session))
                    session.skippedIds else session.skippedIds + session.itemId,
                index = (session.index + 1).coerceAtMost(session.itemIds.lastIndex),
                updatedAtMillis = now,
            )
            val occurrence = session.occurrenceId?.let { old.occurrences[it] }
            val linked = occurrence != null && now in occurrence.startMillis until occurrence.endMillis &&
                occurrence.status != DhikrOccurrenceStatus.SKIPPED && occurrence.status != DhikrOccurrenceStatus.REPLACED &&
                old.reminders.any { it.id == occurrence.ruleId && it.enabled && it.revision == occurrence.revision }
            val occurrences = if (!linked) old.occurrences else {
                val complete = old.isComplete(updated)
                val allSkipped = updated.itemIds.all { it in updated.skippedIds }
                old.occurrences + (occurrence.id to occurrence.copy(
                    count = if (complete && !allSkipped) occurrence.target else 0,
                    status = when {
                        allSkipped -> DhikrOccurrenceStatus.SKIPPED
                        complete -> DhikrOccurrenceStatus.COMPLETED
                        else -> DhikrOccurrenceStatus.OPEN
                    },
                ))
            }
            old.copy(sessions = old.sessions + (sessionId to updated), occurrences = occurrences, lastSessionId = sessionId)
        }
        if (state.value.sessions[sessionId]?.occurrenceId != null) DhikrReminderScheduler.refresh(app)
    }
    /**
     * Removes the current item from this reading list. A collection session also removes it
     * from that collection so it does not return on the next read; the shared dhikr entry stays
     * in the library. Arbitrary lists only change for this session. Removing the last remaining
     * item closes the session.
     */
    fun removeItem(sessionId: String, now: Long = System.currentTimeMillis()) {
        update { old ->
            val session = old.sessions[sessionId] ?: return@update old
            val itemId = session.itemId
            val collection = session.category
            val items = session.itemIds.filterNot { it == itemId }
            val updatedSession = if (items.isEmpty()) null else session.copy(
                itemIds = items, counts = session.counts - itemId, skippedIds = session.skippedIds - itemId,
                index = session.index.coerceIn(0, items.lastIndex), updatedAtMillis = now)
            val sessions = if (updatedSession == null) old.sessions - sessionId else old.sessions + (sessionId to updatedSession)
            val occurrence = session.occurrenceId?.let { old.occurrences[it] }
            val active = occurrence != null && now in occurrence.startMillis until occurrence.endMillis &&
                occurrence.status != DhikrOccurrenceStatus.SKIPPED && occurrence.status != DhikrOccurrenceStatus.REPLACED &&
                old.reminders.any { it.id == occurrence.ruleId && it.enabled && it.revision == occurrence.revision }
            val occurrences = if (!active) old.occurrences else {
                val complete = updatedSession?.let { old.isComplete(it) } == true
                val wasCompleted = occurrence.status == DhikrOccurrenceStatus.COMPLETED
                old.occurrences + (occurrence.id to occurrence.copy(
                    count = if (complete || (updatedSession == null && wasCompleted)) occurrence.target else 0,
                    status = when {
                        updatedSession == null -> if (wasCompleted) DhikrOccurrenceStatus.COMPLETED else DhikrOccurrenceStatus.SKIPPED
                        complete -> DhikrOccurrenceStatus.COMPLETED
                        else -> DhikrOccurrenceStatus.OPEN
                    },
                ))
            }
            old.copy(
                sessions = sessions,
                occurrences = occurrences,
                collectionAdditions = if (collection == null) old.collectionAdditions
                    else old.collectionAdditions.updatedWith(collection, old.collectionAdditions[collection].orEmpty() - itemId),
                collectionRemovals = if (collection == null) old.collectionRemovals
                    else old.collectionRemovals.updatedWith(collection, old.collectionRemovals[collection].orEmpty() + itemId),
                lastSessionId = old.lastSessionId?.takeIf { it != sessionId || items.isNotEmpty() },
            )
        }
        DhikrReminderScheduler.refresh(app)
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
private fun Map<DhikrCategory, Set<String>>.updatedWith(category: DhikrCategory, ids: Set<String>): Map<DhikrCategory, Set<String>> =
    if (ids.isEmpty()) this - category else this + (category to ids)
private fun Map<DhikrCategory, Set<String>>.toJson(): JSONObject = JSONObject().apply {
    forEach { (category, ids) -> put(category.name, JSONArray(ids.toList())) }
}
private fun Map<DhikrCategory, List<String>>.toOrderJson(): JSONObject = JSONObject().apply {
    forEach { (category, ids) -> put(category.name, JSONArray(ids)) }
}
private fun JSONObject.stringList(key: String) = optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
private fun JSONObject.countMap(key: String): Map<String, Int> = optJSONObject(key)?.let { json -> json.keys().asSequence().associateWith { json.optInt(it).coerceAtLeast(0) } }.orEmpty()
private fun DhikrState.toJson(): JSONObject = JSONObject()
    .put("rules", JSONArray(reminders.map { it.toJson() }))
    .put("custom", JSONArray(customEntries.map { it.toJson() }))
    .put("collectionAdditions", collectionAdditions.toJson())
    .put("collectionRemovals", collectionRemovals.toJson())
    .put("collectionOrders", collectionOrders.toOrderJson())
    .put("favourites", JSONArray(favourites.toList())).put("lastSessionId", lastSessionId ?: JSONObject.NULL)
    .put("textSize", textSize).put("countHaptics", countHaptics)
    .put("occurrences", JSONArray(occurrences.values.map { o -> JSONObject()
        .put("id", o.id).put("ruleId", o.ruleId).put("revision", o.revision).put("date", o.date)
        .put("dhikrId", o.dhikrId).put("target", o.target).put("start", o.startMillis).put("end", o.endMillis)
        .put("count", o.count).put("status", o.status.name).put("snooze", o.snoozedUntilMillis) }))
    .put("sessions", JSONArray(sessions.values.map { s -> JSONObject()
        .put("id", s.id).put("items", JSONArray(s.itemIds)).put("counts", JSONObject(s.counts)).put("index", s.index)
        .put("category", s.category?.name ?: JSONObject.NULL).put("occurrenceId", s.occurrenceId ?: JSONObject.NULL)
        .put("updated", s.updatedAtMillis).put("skipped", JSONArray(s.skippedIds.toList()))
        .put("targetCountOverride", s.targetCountOverride ?: JSONObject.NULL)
        .put("collectionPeriodKey", s.collectionPeriodKey ?: JSONObject.NULL) }))
private fun dhikrStateFromJson(json: JSONObject): DhikrState {
    val customEntries = json.optJSONArray("custom")?.objects().orEmpty().mapNotNull { dhikrEntryFromJson(it) }
    val knownIds = (DhikrCatalog.entries.map { it.id } + customEntries.map { it.id }).toSet()
    val occurrences = json.optJSONArray("occurrences")?.objects().orEmpty().mapNotNull { o -> runCatching {
        DhikrOccurrence(o.getString("id"), o.getString("ruleId"), o.optInt("revision", 1), o.getString("date"),
            o.getString("dhikrId"), o.getInt("target"), o.getLong("start"), o.getLong("end"), o.optInt("count"),
            DhikrOccurrenceStatus.valueOf(o.getString("status")), o.optLong("snooze"))
    }.getOrNull() }.associateBy { it.id }
    val sessions = json.optJSONArray("sessions")?.objects().orEmpty().mapNotNull { s -> runCatching {
        val items = s.stringList("items").filter { it in knownIds }
        if (items.isEmpty()) return@runCatching null
        DhikrSession(s.getString("id"), items, s.countMap("counts"), s.optInt("index").coerceIn(0, items.lastIndex),
            if (s.isNull("category")) null else DhikrCategory.valueOf(s.getString("category")),
            if (s.isNull("occurrenceId")) null else s.getString("occurrenceId"), s.optLong("updated"),
            s.stringList("skipped").filter { it in items }.toSet(),
            if (s.has("targetCountOverride") && !s.isNull("targetCountOverride"))
                s.optInt("targetCountOverride").takeIf { it in 1..100_000 } else null,
            if (s.has("collectionPeriodKey") && !s.isNull("collectionPeriodKey"))
                s.optString("collectionPeriodKey").takeIf { it.isNotBlank() } else null)
    }.getOrNull() }.associateBy { it.id }
    fun readMembership(key: String): Map<DhikrCategory, Set<String>> = json.optJSONObject(key)?.let { obj ->
        obj.keys().asSequence().mapNotNull { name ->
            val category = runCatching { DhikrCategory.valueOf(name) }.getOrNull() ?: return@mapNotNull null
            val ids = obj.optJSONArray(name)?.let { array -> (0 until array.length()).mapNotNull { runCatching { array.getString(it) }.getOrNull() } }
                .orEmpty().filter { it in knownIds }.toSet()
            if (ids.isEmpty()) null else category to ids
        }.toMap()
    }.orEmpty()
    val collectionOrders = json.optJSONObject("collectionOrders")?.let { obj ->
        obj.keys().asSequence().mapNotNull { name ->
            val category = runCatching { DhikrCategory.valueOf(name) }.getOrNull() ?: return@mapNotNull null
            val ids = obj.optJSONArray(name)?.let { array ->
                (0 until array.length()).mapNotNull { index -> runCatching { array.getString(index) }.getOrNull() }
            }.orEmpty().filter { it in knownIds }.distinct()
            if (ids.isEmpty()) null else category to ids
        }.toMap()
    }.orEmpty()
    return DhikrState(json.optJSONArray("rules")?.objects().orEmpty().mapNotNull { runCatching { dhikrReminderFromJson(it) }.getOrNull() },
        occurrences, sessions, json.stringList("favourites").toSet(),
        if (json.isNull("lastSessionId")) null else json.optString("lastSessionId"), json.optInt("textSize", 28).coerceIn(24, 40),
        json.optBoolean("countHaptics"), customEntries,
        readMembership("collectionAdditions"), readMembership("collectionRemovals"), collectionOrders)
}
/** Full-list readings resume within their occasion; other collections reset only on New Session. */
internal fun collectionReadingPeriodKey(context: Context, category: DhikrCategory, occurrenceId: String?, now: Long): String {
    if (occurrenceId != null) return "linked:$occurrenceId"
    val startKind = when (category) {
        DhikrCategory.MORNING -> DhikrTimeKind.FAJR
        DhikrCategory.EVENING -> DhikrTimeKind.ASR
        else -> return "manual:${category.name}"
    }
    val date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val start = DhikrReminderScheduler.resolveTime(context, DhikrTime(startKind), date)
    val occasionDate = if (start != null && now < start) date.minusDays(1) else date
    return "${category.name}:$occasionDate"
}
/** Lifecycle-controlled presence, not a background timer. */
object DhikrReadingPresence {
    @Volatile var occurrenceId: String? = null
}
