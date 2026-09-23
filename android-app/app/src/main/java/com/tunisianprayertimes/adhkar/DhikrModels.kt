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
)

/** The end is exclusive. An overnight window keeps the selected starting day's key. */
data class DhikrWindow(
    val startMillis: Long,
    val endMillis: Long,
    val progressKey: String,
    val date: LocalDate,
)

internal fun DhikrTime.toJson(): JSONObject = JSONObject()
    .put("kind", kind.name)
    .put("minuteOfDay", minuteOfDay)
    .put("offsetMinutes", offsetMinutes)

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

internal fun dhikrReminderFromJson(json: JSONObject): DhikrReminder {
    fun readTime(value: JSONObject) = DhikrTime(
        kind = DhikrTimeKind.valueOf(value.getString("kind")),
        minuteOfDay = value.optInt("minuteOfDay", 480),
        offsetMinutes = value.optInt("offsetMinutes", 0),
    )
    val days = json.getJSONArray("daysOfWeek")
    return DhikrReminder(
        id = json.getString("id"),
        dhikrId = json.getString("dhikrId"),
        collection = if (json.isNull("collection")) null else runCatching { DhikrCategory.valueOf(json.getString("collection")) }.getOrNull(),
        vibrate = json.optBoolean("vibrate", true),
        targetCount = json.getInt("targetCount"),
        daysOfWeek = (0 until days.length()).map { days.getInt(it) }.toSet(),
        start = readTime(json.getJSONObject("start")),
        end = readTime(json.getJSONObject("end")),
        intervalMinutes = json.getInt("intervalMinutes"),
        enabled = json.optBoolean("enabled", true),
        cadence = runCatching { DhikrCadence.valueOf(json.getString("cadence")) }.getOrDefault(DhikrCadence.CUSTOM),
        endNextDay = if (json.has("endNextDay") && !json.isNull("endNextDay")) json.getBoolean("endNextDay") else null,
        revision = json.optInt("revision", 1),
        notBeforeMillis = json.optLong("notBeforeMillis", 0),
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

fun DhikrState.collectionEntries(category: DhikrCategory): List<DhikrEntry> =
    allEntries.filter { isInCollection(it, category) }

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

fun DhikrState.target(session: DhikrSession, itemId: String = session.itemId): Int =
    if (session.category == null) session.occurrenceId?.let { occurrences[it]?.target }
        ?: findDhikr(itemId)?.defaultCount ?: 1
    else findDhikr(itemId)?.defaultCount ?: 1

fun DhikrState.isComplete(session: DhikrSession): Boolean =
    session.itemIds.all { it in session.skippedIds || (session.counts[it] ?: 0) >= target(session, it) }

/** Custom cadences honor the configured interval down to this floor; receive-time burst spacing is 2 minutes. */
const val MIN_DHIKR_INTERVAL_MINUTES = 5
/** Validation caps a window at one day, so 5-minute nudges need at most 289 entries. */
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
