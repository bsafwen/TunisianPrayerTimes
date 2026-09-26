package com.tunisianprayertimes.adhkar

import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class DhikrTimeKind { FIXED, FAJR, SHURUK, DHUHR, ASR, MAGHRIB, ISHA }
enum class DhikrCadence { GENTLE, BALANCED, HOURLY, CUSTOM }

data class DhikrTime(
    val kind: DhikrTimeKind = DhikrTimeKind.FIXED,
    val minuteOfDay: Int = 480,
    val offsetMinutes: Int = 0,
)

data class DhikrInterval(
    val start: DhikrTime,
    val end: DhikrTime,
    val endNextDay: Boolean? = false,
)

/** Weekdays use ISO numbering: Monday = 1, Sunday = 7. */
data class DhikrReminder(
    val id: String = UUID.randomUUID().toString(),
    val dhikrId: String,
    /** Set for a whole-collection reminder (morning/evening); personal repetition targets are omitted. */
    val collection: DhikrCategory? = null,
    val vibrate: Boolean = true,
    val targetCount: Int = 100,
    val daysOfWeek: Set<Int> = (1..7).toSet(),
    val start: DhikrTime = DhikrTime(),
    val end: DhikrTime = DhikrTime(kind = DhikrTimeKind.MAGHRIB),
    val intervalMinutes: Int = 60,
    val enabled: Boolean = true,
    val cadence: DhikrCadence = DhikrCadence.GENTLE,
    // Null retains the legacy implicit-overnight policy during migration only.
    val endNextDay: Boolean? = false,
    val revision: Int = 1,
    val notBeforeMillis: Long = 0,
    val extraIntervals: List<DhikrInterval> = emptyList(),
)

fun DhikrReminder.intervals(): List<DhikrInterval> =
    listOf(DhikrInterval(start, end, endNextDay)) + extraIntervals

/** The end is exclusive. An overnight window keeps the selected starting day's key. */
data class DhikrWindow(
    val startMillis: Long,
    val endMillis: Long,
    val progressKey: String,
    val date: LocalDate,
    val intervalIndex: Int = 0,
    val progressStartMillis: Long = startMillis,
    val progressEndMillis: Long = endMillis,
)

internal fun DhikrTime.toJson(): JSONObject = JSONObject()
    .put("kind", kind.name)
    .put("minuteOfDay", minuteOfDay)
    .put("offsetMinutes", offsetMinutes)

private fun DhikrInterval.toJson(): JSONObject = JSONObject()
    .put("start", start.toJson())
    .put("end", end.toJson())
    .put("endNextDay", endNextDay ?: JSONObject.NULL)

internal fun DhikrReminder.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("dhikrId", dhikrId)
    .put("collection", collection?.name ?: JSONObject.NULL)
    .put("vibrate", vibrate)
    .put("targetCount", targetCount)
    .put("daysOfWeek", JSONArray(daysOfWeek.sorted()))
    .put("start", start.toJson())
    .put("end", end.toJson())
    .put("intervalMinutes", intervalMinutes)
    .put("enabled", enabled)
    .put("cadence", cadence.name)
    .put("endNextDay", endNextDay ?: JSONObject.NULL)
    .put("revision", revision)
    .put("notBeforeMillis", notBeforeMillis)
    .apply {
        if (extraIntervals.isNotEmpty()) put("extraIntervals", JSONArray(extraIntervals.map { it.toJson() }))
    }

internal fun dhikrReminderFromJson(json: JSONObject): DhikrReminder {
    fun readTime(value: JSONObject) = DhikrTime(
        kind = DhikrTimeKind.valueOf(value.getString("kind")),
        minuteOfDay = value.optInt("minuteOfDay", 480),
        offsetMinutes = value.optInt("offsetMinutes", 0),
    )
    val days = json.getJSONArray("daysOfWeek")
    val extraIntervals = json.optJSONArray("extraIntervals")?.let { array ->
        (0 until array.length()).mapNotNull { index -> runCatching {
            val value = array.getJSONObject(index)
            DhikrInterval(
                start = readTime(value.getJSONObject("start")),
                end = readTime(value.getJSONObject("end")),
                endNextDay = when {
                    !value.has("endNextDay") -> false
                    value.isNull("endNextDay") -> null
                    else -> value.getBoolean("endNextDay")
                },
            )
        }.getOrNull() }
    }.orEmpty()
    return DhikrReminder(
        id = json.getString("id"),
        dhikrId = json.getString("dhikrId"),
        collection = if (json.isNull("collection")) null else runCatching { DhikrCategory.valueOf(json.getString("collection")) }.getOrNull(),
        vibrate = json.optBoolean("vibrate", true),
        targetCount = json.getInt("targetCount"),
        daysOfWeek = (0 until days.length()).map { days.getInt(it) }.toSet(),
        start = readTime(json.getJSONObject("start")),
        end = readTime(json.getJSONObject("end")),
        // Older versions offered 5–14 minute reminders. Keep those saved rules
        // active at the supported cadence instead of making validation reject them.
        intervalMinutes = json.getInt("intervalMinutes").let {
            if (it in 5 until MIN_DHIKR_INTERVAL_MINUTES) MIN_DHIKR_INTERVAL_MINUTES else it
        },
        enabled = json.optBoolean("enabled", true),
        cadence = runCatching { DhikrCadence.valueOf(json.getString("cadence")) }.getOrDefault(DhikrCadence.CUSTOM),
        endNextDay = if (json.has("endNextDay") && !json.isNull("endNextDay")) json.getBoolean("endNextDay") else null,
        revision = json.optInt("revision", 1),
        notBeforeMillis = json.optLong("notBeforeMillis", 0),
        extraIntervals = extraIntervals,
    )
}

internal fun DhikrEntry.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("text", text)
    .put("reference", reference)
    .put("defaultCount", defaultCount)

/** Personal entries always load as custom with no category; malformed records are dropped. */
internal fun dhikrEntryFromJson(json: JSONObject): DhikrEntry? = runCatching {
    DhikrEntry(
        id = json.getString("id"),
        title = json.getString("title"),
        text = json.getString("text"),
        reference = json.optString("reference"),
        defaultCount = json.optInt("defaultCount", 1).coerceIn(1, 100_000),
        categories = emptySet(),
        custom = true,
    )
}.getOrNull()

enum class DhikrOccurrenceStatus { OPEN, COMPLETED, SKIPPED, REPLACED }

/** Target and content are snapshots; prayer-derived timing can follow locality changes. */
data class DhikrOccurrence(
    val id: String, val ruleId: String, val revision: Int, val date: String,
    val dhikrId: String, val target: Int, val startMillis: Long, val endMillis: Long,
    val count: Int = 0, val status: DhikrOccurrenceStatus = DhikrOccurrenceStatus.OPEN,
    val snoozedUntilMillis: Long = 0,
)

data class DhikrSession(
    val id: String = UUID.randomUUID().toString(),
    val itemIds: List<String>, val counts: Map<String, Int> = emptyMap(), val index: Int = 0,
    val category: DhikrCategory? = null, val occurrenceId: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    /** Items the reader moved past without counting; a skip still satisfies list completion. */
    val skippedIds: Set<String> = emptySet(),
    /** A manual reminder reading can keep its chosen goal without joining scheduled progress. */
    val targetCountOverride: Int? = null,
    /** Stable occasion for a full collection reading; individual dhikr sessions leave this null. */
    val collectionPeriodKey: String? = null,
) {
    val itemId: String get() = itemIds[index.coerceIn(0, itemIds.lastIndex)]
}

data class DhikrState(
    val reminders: List<DhikrReminder> = emptyList(),
    val occurrences: Map<String, DhikrOccurrence> = emptyMap(),
    val sessions: Map<String, DhikrSession> = emptyMap(),
    val favourites: Set<String> = emptySet(),
    val lastSessionId: String? = null,
    val textSize: Int = 28,
    val countHaptics: Boolean = false,
    /** Personal entries written by the user, newest first. */
    val customEntries: List<DhikrEntry> = emptyList(),
    /** Ids the user explicitly added to a collection that does not contain them by default. */
    val collectionAdditions: Map<DhikrCategory, Set<String>> = emptyMap(),
    /** Ids the user explicitly removed from a collection that contains them by default. */
    val collectionRemovals: Map<DhikrCategory, Set<String>> = emptyMap(),
    /** User-defined ordering for collection entries; entries not listed here follow catalog order. */
    val collectionOrders: Map<DhikrCategory, List<String>> = emptyMap(),
)

/** Resolves built-in and personal entries through one lookup. */
fun DhikrState.findDhikr(id: String): DhikrEntry? =
    customEntries.firstOrNull { it.id == id } ?: DhikrCatalog.find(id)

val DhikrState.allEntries: List<DhikrEntry> get() = DhikrCatalog.entries + customEntries

/** Collection membership is the catalog default adjusted by the user's additions and removals. */
fun DhikrState.isInCollection(entry: DhikrEntry, category: DhikrCategory): Boolean = when {
    entry.id in collectionRemovals[category].orEmpty() -> false
    category in entry.categories -> true
    else -> entry.id in collectionAdditions[category].orEmpty()
}

fun DhikrState.collectionEntries(category: DhikrCategory): List<DhikrEntry> {
    val entries = allEntries.filter { isInCollection(it, category) }
    val order = collectionOrders[category].orEmpty().withIndex().associate { it.value to it.index }
    val orderedEntries = if (order.isEmpty()) entries else entries.withIndex()
        .sortedWith(compareBy<IndexedValue<DhikrEntry>> { order[it.value.id] ?: Int.MAX_VALUE }.thenBy { it.index })
        .map { it.value }
    return orderedEntries.map { entry ->
        val count = entry.countForCollection(category)
        if (count == entry.defaultCount) entry else entry.copy(defaultCount = count)
    }
}

/** Snapshot captured when a personal dhikr is deleted so the action can be undone. */
data class CustomDhikrRemoval(
    val entry: DhikrEntry,
    val reminders: List<DhikrReminder>,
    val sessions: List<DhikrSession>,
    val favourite: Boolean,
)

/** Matching only: never normalize the stored/displayed religious text. */
fun normalizeDhikrSearch(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
    .replace(Regex("[\\p{M}ـ]"), "")
    .replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي').replace('ة', 'ه').lowercase().trim()

fun DhikrState.target(session: DhikrSession, itemId: String = session.itemId): Int {
    val entry = findDhikr(itemId)
    return if (session.category == null)
        session.targetCountOverride ?: session.occurrenceId?.let { occurrences[it]?.target }
            ?: entry?.countForCollection(null) ?: 1
    else entry?.countForCollection(session.category) ?: 1
}

fun DhikrState.isComplete(session: DhikrSession): Boolean =
    session.itemIds.all { it in session.skippedIds || (session.counts[it] ?: 0) >= target(session, it) }

/** Android can defer closely spaced while-idle alarms; avoid offering a 5-minute cadence. */
const val MIN_DHIKR_INTERVAL_MINUTES = 15
/** Validation caps a window at one day; this comfortably exceeds the maximum custom count. */
private const val MAX_NUDGES_PER_WINDOW = 300

/**
 * Notification count is independent of recitation count. Custom and hourly cadences fire every
 * configured interval exactly; gentle/balanced spread at most 3/5 nudges across the window.
 */
fun dhikrNudgeTimes(reminder: DhikrReminder, window: DhikrWindow): List<Long> {
    val duration = window.endMillis - window.startMillis
    if (duration <= 0) return emptyList()
    val minimum = 15 * 60_000L
    return when (reminder.cadence) {
        DhikrCadence.GENTLE, DhikrCadence.BALANCED -> {
            val count = minOf(if (reminder.cadence == DhikrCadence.GENTLE) 3 else 5, ((duration - 1) / minimum + 1).toInt())
            (0 until count).map { window.startMillis + it * maxOf(minimum, duration / count) }.filter { it < window.endMillis }
        }
        else -> {
            val step = (if (reminder.cadence == DhikrCadence.HOURLY) 60 else reminder.intervalMinutes.coerceAtLeast(MIN_DHIKR_INTERVAL_MINUTES)) * 60_000L
            generateSequence(window.startMillis) { it + step }.takeWhile { it < window.endMillis }.take(MAX_NUDGES_PER_WINDOW).toList()
        }
    }
}

/** Spread gentle and balanced nudges across the day, with at least one in each chosen interval. */
fun dhikrNudgeTimes(reminder: DhikrReminder, windows: List<DhikrWindow>): List<Long> {
    val ordered = windows.filter { it.endMillis > it.startMillis }.sortedBy { it.startMillis }
    if (ordered.size == 1) return dhikrNudgeTimes(reminder, ordered.single())
    if (reminder.cadence == DhikrCadence.HOURLY || reminder.cadence == DhikrCadence.CUSTOM)
        return ordered.flatMap { dhikrNudgeTimes(reminder, it) }.sorted()

    val baseCap = if (reminder.cadence == DhikrCadence.GENTLE) 3 else 5
    val minimum = 15 * 60_000L
    return ordered.groupBy { it.date }.values.flatMap { dayWindows ->
        // Adding an interval is an explicit request for coverage, even above the usual cap.
        val dailyCap = maxOf(baseCap, dayWindows.size)
        val selected = dayWindows
        val counts = IntArray(selected.size) { 1 }
        val capacities = selected.map { window ->
            minOf(dailyCap, ((window.endMillis - window.startMillis - 1) / minimum + 1).toInt())
        }
        var remaining = dailyCap - selected.size
        while (remaining > 0) {
            val next = selected.indices.filter { counts[it] < capacities[it] }
                .maxByOrNull { (selected[it].endMillis - selected[it].startMillis) / (counts[it] + 1) }
                ?: break
            counts[next]++
            remaining--
        }
        selected.flatMapIndexed { index, window ->
            val duration = window.endMillis - window.startMillis
            val step = maxOf(minimum, duration / counts[index])
            (0 until counts[index]).map { window.startMillis + it * step }.filter { it < window.endMillis }
        }
    }.sorted()
}
