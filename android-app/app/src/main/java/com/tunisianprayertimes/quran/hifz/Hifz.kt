package com.tunisianprayertimes.quran.hifz

import android.content.Context
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.quran.QuranVerseReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What went wrong when reciting a verse from memory, in the order a verse goes: its start, the verse as a whole, its
 * middle, its end. [label] is how the reader says it when recording, [short] the one noun every list and title uses.
 */
enum class HifzKind(val key: String, val label: String, val short: String) {
    /** Could not recall how the verse begins. */
    Start("start", "نسيتها", "نسيان الآية"),
    /** Went from the verse before straight to the one after. */
    Skip("skip", "تخطيتها", "تخطٍّ"),
    /** Drifted into a similar verse, in the same chapter or another. */
    Similar("similar", "دخلت في آية شبيهة", "شبيهة"),
    /** A wrong word or vowel in the right verse. */
    Word("word", "أخطأت في كلمة أو حركة", "كلمة"),
    /** Closed the verse with another verse's ending. */
    Ending("ending", "ختمتها بخاتمة أخرى", "خاتمة");

    /** The slip is told by the verse it was confused with: a different look-alike is a slip of its own. */
    val lookAlike: Boolean get() = this == Ending || this == Similar

    companion object {
        fun of(key: String): HifzKind? = entries.firstOrNull { it.key == key }
    }
}

/** The five prayers a reminder may follow, in the day's order. */
val HifzPrayers = listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)

/** One time of the day a plan brings a point back: after one of the five prayers, or at a clock time. */
sealed interface HifzWhen {
    /** For storage and alarms: «FAJR», «at1290». */
    val key: String

    data class After(val prayer: Prayer) : HifzWhen {
        override val key: String get() = prayer.name
    }

    /** At [minute] of the day, by the clock. */
    data class At(val minute: Int) : HifzWhen {
        override val key: String get() = "at$minute"
    }

    companion object {
        fun of(key: String?): HifzWhen? = when {
            key == null -> null
            key.startsWith("at") -> key.drop(2).toIntOrNull()?.takeIf { it in 0 until 24 * 60 }?.let(::At)
            else -> HifzPrayers.firstOrNull { it.name == key }?.let(::After)
        }
    }
}

/**
 * When a point comes back besides its spaced checks, every day of its plan: after some of the five prayers
 * and at some clock times, as minutes of the day. Chosen when the slip is recorded; [None] brings it back
 * only on the days of its spaced checks, in the morning reminder.
 */
data class HifzRepeat(val prayers: Set<Prayer> = emptySet(), val minutes: Set<Int> = emptySet()) {
    val none: Boolean get() = prayers.isEmpty() && minutes.isEmpty()
    /** How many reminders a day. */
    val perDay: Int get() = prayers.size + minutes.size
    /** The day's times, the prayers first. */
    val times: List<HifzWhen> get() = HifzPrayers.filter { it in prayers }.map { HifzWhen.After(it) } + minutes.sorted().map { HifzWhen.At(it) }

    operator fun contains(time: HifzWhen): Boolean = when (time) {
        is HifzWhen.After -> time.prayer in prayers
        is HifzWhen.At -> time.minute in minutes
    }

    /** «FAJR,ISHA,at1290», for storage; empty for [None]. */
    fun encode(): String = times.joinToString(",") { it.key }

    companion object {
        val None = HifzRepeat()

        /** Null when [value] is missing or not a plan. */
        fun decode(value: String?): HifzRepeat? {
            value ?: return null
            val times = value.split(',').filter { it.isNotEmpty() }.map { HifzWhen.of(it) ?: return null }
            return HifzRepeat(
                times.filterIsInstance<HifzWhen.After>().map { it.prayer }.toSet(),
                times.filterIsInstance<HifzWhen.At>().map { it.minute }.toSet(),
            )
        }
    }
}

/** The quick choices of «ذكّرني»; any other plan is the reader's own, «مخصص». */
enum class HifzRepeatPreset(val key: String, val label: String, val repeat: HifzRepeat) {
    ChecksOnly("checks", "في المراجعات فقط", HifzRepeat.None),
    /** Once a day, after Fajr: the time of hifz and of review. */
    Once("once", "مرة في اليوم", HifzRepeat(setOf(Prayer.FAJR))),
    /** After Fajr, Dhuhr and Isha: morning, midday and before sleep. */
    Thrice("thrice", "3 مرات في اليوم", HifzRepeat(setOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ISHA))),
    Prayers("prayers", "بعد كل صلاة", HifzRepeat(HifzPrayers.toSet()));

    companion object {
        fun of(repeat: HifzRepeat): HifzRepeatPreset? = entries.firstOrNull { it.repeat == repeat }
    }
}

/** The quick lengths of a plan: «3 أيام» and «أسبوع». Its spaced checks go on after. */
val HifzRepeatDays = listOf(3, 7)
/** The longest plan, in days, and the most clock times in a day. */
const val HifzRepeatMaxDays = 30
const val HifzRepeatMaxTimes = 6

/**
 * One recurring slip, as references only: every text shown comes from the app's own Qaloun index.
 * Only the reader creates a point; nothing is recorded automatically.
 */
data class HifzPoint(
    val id: Long,
    /** The verse recited wrongly, or the verse skipped. */
    val verse: QuranVerseReference,
    val kind: HifzKind,
    /** The look-alike the recitation went into ([HifzKind.Ending], [HifzKind.Similar]), or where a skip landed. */
    val other: QuranVerseReference? = null,
    /** Zero-based words of [verse]: where it parts from [other] (one at most), or the wrong or forgotten words. */
    val slipWords: Set<Int> = emptySet(),
    /** The reader may confuse the two verses either way. */
    val bothWays: Boolean = false,
    /** A word read as in Hafs's riwaya. */
    val hafs: Boolean = false,
    /** «ضابطي»: the reader's own way to remember the difference. */
    val key: String = "",
    /** How many times the slip was recorded. */
    val slips: Int = 1,
    /** The review ladder's step; see [HifzLadder]. */
    val step: Int = 0,
    /** The next check, as an epoch day. */
    val dueDay: Long,
    val createdAt: Long,
    val lastSlipAt: Long = createdAt,
    /** When the point was last held for good, as wall-clock milliseconds. */
    val masteredAt: Long? = null,
    /** Shown above the dock while the verse before it is recited. */
    val headsUp: Boolean = true,
    /** Also brought back after prayers or at clock times, until [repeatUntil]. */
    val repeat: HifzRepeat = HifzRepeat.None,
    /** The last day, as an epoch day, the point is brought back by [repeat]. */
    val repeatUntil: Long? = null,
    /** How many days the reader chose for [repeat]: shown again when the plan is changed. */
    val repeatDays: Int = 0,
    /** Actual spaced checks, oldest first. Older notebooks have no inferred review history. */
    val checks: List<HifzCheck> = emptyList(),
) {
    val mastered: Boolean get() = step >= HifzMasteredStep
    /** The first of [slipWords]: where a look-alike parts, or the first wrong word. */
    val word: Int? get() = slipWords.minOrNull()
    /** Brought back by [repeat] on [day]. */
    fun repeatsOn(day: Long): Boolean = !repeat.none && repeatUntil != null && day <= repeatUntil
    /** Recent difficulty has priority until two unaided checks show recovery. */
    val needsCare: Boolean
        get() {
            if (checks.isEmpty()) return slips >= 3 && !mastered
            if (lastSlipAt > checks.last().at) return true
            val recent = checks.filter { it.at >= lastSlipAt }.takeLast(3)
            if (recent.size >= 2 && recent.takeLast(2).all { it.grade == HifzGrade.Held }) return false
            return recent.any { it.grade == HifzGrade.Again } ||
                recent.count { it.grade == HifzGrade.Hesitated } >= 2 ||
                (recent.size < 2 && lastSlipAt > checks.first().at) ||
                (checks.size < 2 && slips >= 3 && !mastered)
        }
    /** Feedback is about observed checks, never an invented history for an older notebook. */
    val recentFeedback: String?
        get() {
            val last = checks.lastOrNull() ?: return null
            if (lastSlipAt > last.at) return "سُجّل خطأ جديد بعد آخر مراجعة"
            val sinceSlip = checks.filter { it.at >= lastSlipAt }
            if (sinceSlip.size >= 2 && sinceSlip.takeLast(2).all { it.grade == HifzGrade.Held }) {
                return "تذكرتها دون مساعدة في آخر مراجعتين"
            }
            return when (last.grade) {
                HifzGrade.Held -> "آخر مراجعة: تذكرتها دون مساعدة"
                HifzGrade.Hesitated -> "آخر مراجعة: تردد أو احتجت تلميحًا"
                HifzGrade.Again -> "آخر مراجعة: تحتاج تدريبًا"
            }
        }
    /** Checks still to pass, each «أتقنتها», before the point is held («ثبتت»). */
    val checksToHold: Int get() = (HifzMasteredStep - step).coerceAtLeast(0)
    fun isDue(today: Long): Boolean = dueDay <= today
}

/** Days until the next check after each successful one: 1, 3, 7 and 14, then held at 30 and every 60. */
val HifzLadder = listOf(1, 3, 7, 14, 30, 60)
/** A point that passed its 14-day check is held («ثبتت»). */
const val HifzMasteredStep = 4

/** How a check went: «أتقنتها», «ترددت», «ما زالت تحتاج تدريبا». */
enum class HifzGrade { Held, Hesitated, Again }

/** One scheduled recall result; immediate practice does not write a second check. */
data class HifzCheck(val day: Long, val grade: HifzGrade, val at: Long)

private const val HifzCheckLimit = 60

/** Compact format shared by the notebook and the review's undo snapshot. */
fun encodeHifzChecks(checks: List<HifzCheck>): String = checks.takeLast(HifzCheckLimit)
    .joinToString(",") { "${it.day}:${it.grade.name}:${it.at}" }

fun decodeHifzChecks(value: String?): List<HifzCheck> = value.orEmpty().split(',').mapNotNull { row ->
    val parts = row.split(':')
    if (parts.size != 3) return@mapNotNull null
    val day = parts[0].toLongOrNull() ?: return@mapNotNull null
    val grade = HifzGrade.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
    val at = parts[2].toLongOrNull() ?: return@mapNotNull null
    HifzCheck(day, grade, at)
}.takeLast(HifzCheckLimit)

/**
 * The point after a review graded [grade] on [today]. A hesitation brings the point back in 2 days, one step
 * lower: the next «أتقنتها» then waits the same time as before instead of a longer one, and a held point hesitated
 * on leaves «ثبتت» until it is held again.
 */
fun HifzPoint.graded(grade: HifzGrade, today: Long, now: Long): HifzPoint = when (grade) {
    HifzGrade.Held -> {
        val next = step + 1
        copy(step = next, dueDay = today + HifzLadder[next.coerceAtMost(HifzLadder.lastIndex)],
            masteredAt = if (next == HifzMasteredStep) now else masteredAt)
    }
    HifzGrade.Hesitated -> {
        val back = (step - 1).coerceAtLeast(0)
        copy(step = back, dueDay = today + 2, masteredAt = masteredAt.takeIf { back >= HifzMasteredStep })
    }
    // Failing a check is a slip like any other: it counts towards «يحتاج عناية».
    HifzGrade.Again -> copy(step = 0, dueDay = today + 1, masteredAt = null, slips = slips + 1, lastSlipAt = now)
}.copy(checks = (checks + HifzCheck(today, grade, now)).takeLast(HifzCheckLimit))

/** A «أتقنتها» now makes the point held («ثبتت»). */
val HifzPoint.holdsNext: Boolean get() = step + 1 == HifzMasteredStep

/** Days until the next check if the review is graded [grade]. */
fun HifzPoint.daysAfter(grade: HifzGrade): Int = when (grade) {
    HifzGrade.Held -> HifzLadder[(step + 1).coerceAtMost(HifzLadder.lastIndex)]
    HifzGrade.Hesitated -> 2
    HifzGrade.Again -> 1
}

/** Today's checks, those needing care first, at most [limit]. */
fun List<HifzPoint>.dueToday(today: Long, limit: Int = Int.MAX_VALUE): List<HifzPoint> =
    filter { it.isDue(today) }
        .sortedWith(compareByDescending<HifzPoint> { it.needsCare }.thenBy { it.dueDay }.thenByDescending { it.slips }.thenBy { it.verse })
        .take(limit)

/**
 * A review's batch: the checks [dueToday] picks, then in mushaf order, as a hafiz goes through the pages. Two points
 * on one verse are kept apart, since the second would come right after its own answer.
 */
fun List<HifzPoint>.reviewBatch(today: Long, limit: Int): List<HifzPoint> {
    val batch = mutableListOf<HifzPoint>()
    val later = mutableListOf<HifzPoint>()
    dueToday(today, limit).sortedWith(compareBy<HifzPoint> { it.verse }.thenBy { it.id }).forEach { point ->
        if (batch.lastOrNull()?.verse == point.verse) later += point else batch += point
    }
    later.forEach { point ->
        val at = (0..batch.size).firstOrNull { i -> batch.getOrNull(i - 1)?.verse != point.verse && batch.getOrNull(i)?.verse != point.verse }
        batch.add(at ?: batch.size, point)
    }
    return batch
}

/**
 * The point a new record of [kind] on [verse] counts again: the same verse and kind, and for a look-alike the same
 * other verse, or one not known on either side. A second look-alike is a slip of its own, never written over the first.
 */
fun List<HifzPoint>.sameSlip(verse: QuranVerseReference, kind: HifzKind, other: QuranVerseReference? = null): HifzPoint? =
    firstOrNull { it.verse == verse && it.kind == kind && (!kind.lookAlike || other == null || it.other == null || it.other == other) }

/** A reminder of a plan: [time] on [day], an epoch day, coming at [at] in wall-clock milliseconds. */
data class HifzSlot(val day: Long, val time: HifzWhen, val at: Long)

/** The points the reminder at [time] on [day] brings back. */
fun List<HifzPoint>.repeatingAt(day: Long, time: HifzWhen): List<HifzPoint> =
    filter { it.repeatsOn(day) && time in it.repeat }

/**
 * The first reminder later than [after] that brings some point back. [slotAt] gives when a time of a day
 * comes, or null when that day's prayer times are not known.
 */
fun List<HifzPoint>.nextRepeat(today: Long, after: Long, slotAt: (day: Long, time: HifzWhen) -> Long?): HifzSlot? {
    val last = mapNotNull { point -> point.repeatUntil.takeIf { !point.repeat.none } }.maxOrNull() ?: return null
    // From yesterday, whose Isha may end after midnight; a plan lasts a month at most, so a date further off is a
    // broken value, not months of searching. A day's reminders come on that day or, for Isha, just after
    // midnight: two days past the earliest found, none can come before it.
    var best: HifzSlot? = null
    for (day in today - 1..minOf(last, today + HifzRepeatMaxDays)) {
        if (best != null && day > best.day + 1) break
        filter { it.repeatsOn(day) }.flatMap { it.repeat.times }.distinct()
            .mapNotNull { time -> slotAt(day, time)?.takeIf { it > after }?.let { HifzSlot(day, time, it) } }
            .minByOrNull { it.at }
            ?.let { if (best == null || it.at < best!!.at) best = it }
    }
    return best
}

/**
 * Where a plan [repeat] chosen now starts: its first reminder later than [after], from yesterday, whose Isha
 * may come after midnight (prayer day today − 1, plan 6.9), to the next two days.
 */
fun firstRepeat(repeat: HifzRepeat, today: Long, after: Long, slotAt: (day: Long, time: HifzWhen) -> Long?): HifzSlot? {
    for (day in today - 1..today + 2) {
        repeat.times.mapNotNull { time -> slotAt(day, time)?.takeIf { it > after }?.let { HifzSlot(day, time, it) } }
            .minByOrNull { it.at }
            ?.let { return it }
    }
    return null
}

fun epochDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

/** The notebook, kept in a small JSON file and observed by every screen. */
object HifzRepository {
    private val mutablePoints = MutableStateFlow<List<HifzPoint>>(emptyList())
    val points: StateFlow<List<HifzPoint>> = mutablePoints.asStateFlow()
    @Volatile private var loaded = false
    private val lock = Any()
    /** False until the notebook has been read: an empty list then means nothing yet, not no points. */
    val isLoaded: Boolean get() = loaded

    /** Reads the notebook once; call off the main thread. */
    fun load(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val target = file(context)
            // Always through AtomicFile: it brings back the previous copy when a write was cut short.
            val bytes = try {
                AtomicFile(target).readFully()
            } catch (_: java.io.FileNotFoundException) {
                null
            }
            mutablePoints.value = if (bytes == null) emptyList() else runCatching { decodeHifzPoints(bytes.decodeToString()) }
                .getOrElse {
                    // Kept for recovery: the next save must not replace every point with an empty notebook.
                    target.renameTo(File(target.parentFile, "hifz.json.bad-${System.currentTimeMillis()}"))
                    emptyList()
                }
            loaded = true
        }
    }

    /**
     * Records a slip, or counts it again on an existing point. Returns the point as saved. The reminders
     * after prayers are the ones chosen now, also for a point recorded before.
     */
    fun record(
        context: Context,
        verse: QuranVerseReference,
        kind: HifzKind,
        other: QuranVerseReference? = null,
        slipWords: Set<Int> = emptySet(),
        bothWays: Boolean = false,
        hafs: Boolean = false,
        repeat: HifzRepeat = HifzRepeat.None,
        repeatUntil: Long? = null,
        repeatDays: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): HifzPoint = change(context) { points ->
        val today = epochDay(now)
        val existing = points.sameSlip(verse, kind, other)
        val point = existing?.copy(
            other = other ?: existing.other,
            // A word slip adds the words missed this time; a look-alike keeps one parting word.
            slipWords = if (kind == HifzKind.Word) existing.slipWords + slipWords else slipWords.ifEmpty { existing.slipWords },
            bothWays = bothWays || existing.bothWays, hafs = hafs || existing.hafs,
            slips = existing.slips + 1, step = 0, dueDay = today + 1, lastSlipAt = now, masteredAt = null,
            repeat = repeat, repeatUntil = repeatUntil, repeatDays = repeatDays,
        ) ?: HifzPoint(
            id = now, verse = verse, kind = kind, other = other, slipWords = slipWords, bothWays = bothWays, hafs = hafs,
            dueDay = today + 1, createdAt = now, repeat = repeat, repeatUntil = repeatUntil, repeatDays = repeatDays,
        )
        (points.filterNot { it.id == point.id } + point) to point
    }

    fun update(context: Context, point: HifzPoint) {
        change(context) { points -> points.map { if (it.id == point.id) point else it } to Unit }
    }

    /** Puts back a point exactly as it was, e.g. to undo a record, a grade or a delete. */
    fun restore(context: Context, point: HifzPoint?, removeId: Long? = null) {
        change(context) { points ->
            val kept = points.filterNot { it.id == point?.id || it.id == removeId }
            (if (point != null) kept + point else kept) to Unit
        }
    }

    fun delete(context: Context, id: Long) {
        change(context) { points -> points.filterNot { it.id == id } to Unit }
    }

    private fun <T> change(context: Context, block: (List<HifzPoint>) -> Pair<List<HifzPoint>, T>): T = synchronized(lock) {
        load(context)
        val (next, result) = block(mutablePoints.value)
        val sorted = next.sortedBy { it.verse }
        mutablePoints.value = sorted
        val target = file(context)
        target.parentFile?.mkdirs()
        // Synced to disk and then moved over the notebook, so a crash or a power cut never leaves half a file.
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(encodeHifzPoints(sorted).toByteArray())
            atomic.finishWrite(stream)
        } catch (error: java.io.IOException) {
            atomic.failWrite(stream)
            throw error
        }
        result
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, "hifz/hifz.json")
}

internal fun encodeHifzPoints(points: List<HifzPoint>): String = JSONObject()
    .put("version", 1)
    .put("points", JSONArray(points.map { point ->
        JSONObject()
            .put("id", point.id)
            .put("surah", point.verse.surah)
            .put("ayah", point.verse.ayah)
            .put("kind", point.kind.key)
            .apply {
                point.other?.let { put("otherSurah", it.surah); put("otherAyah", it.ayah) }
                if (point.slipWords.isNotEmpty()) put("words", JSONArray(point.slipWords.sorted()))
                point.masteredAt?.let { put("masteredAt", it) }
                if (!point.repeat.none) {
                    put("repeat", point.repeat.encode())
                    point.repeatUntil?.let { put("repeatUntil", it) }
                    put("repeatDays", point.repeatDays)
                }
            }
            .put("bothWays", point.bothWays)
            .put("hafs", point.hafs)
            .put("key", point.key)
            .put("slips", point.slips)
            .put("step", point.step)
            .put("dueDay", point.dueDay)
            .put("createdAt", point.createdAt)
            .put("lastSlipAt", point.lastSlipAt)
            .put("headsUp", point.headsUp)
            .put("checks", encodeHifzChecks(point.checks))
    }))
    .toString()

internal fun decodeHifzPoints(value: String): List<HifzPoint> {
    val rows = JSONObject(value).getJSONArray("points")
    return List(rows.length()) { rows.getJSONObject(it) }.mapNotNull { row ->
        val kind = HifzKind.of(row.optString("kind")) ?: return@mapNotNull null
        val verse = QuranVerseReference(row.getInt("surah"), row.getInt("ayah"))
        if (verse.surah !in 1..114 || verse.ayah < 1) return@mapNotNull null
        HifzPoint(
            id = row.getLong("id"),
            verse = verse,
            kind = kind,
            other = if (row.has("otherSurah")) QuranVerseReference(row.getInt("otherSurah"), row.getInt("otherAyah")) else null,
            slipWords = row.optJSONArray("words")?.let { list -> List(list.length()) { list.getInt(it) }.filter { it >= 0 }.toSet() }.orEmpty(),
            bothWays = row.optBoolean("bothWays"),
            hafs = row.optBoolean("hafs"),
            key = row.optString("key"),
            slips = row.optInt("slips", 1).coerceAtLeast(1),
            step = row.optInt("step").coerceAtLeast(0),
            dueDay = row.getLong("dueDay"),
            createdAt = row.getLong("createdAt"),
            lastSlipAt = row.optLong("lastSlipAt", row.getLong("createdAt")),
            masteredAt = if (row.has("masteredAt")) row.getLong("masteredAt") else null,
            headsUp = row.optBoolean("headsUp", true),
            repeat = HifzRepeat.decode(row.optString("repeat")) ?: HifzRepeat.None,
            repeatUntil = if (row.has("repeatUntil")) row.getLong("repeatUntil") else null,
            repeatDays = row.optInt("repeatDays"),
            checks = decodeHifzChecks(row.optString("checks")),
        )
    }
}

/** How «المراجعة» behaves; kept in its own preferences. */
data class HifzSettings(
    val reminder: Boolean = true,
    /** Minutes after sunrise for the daily reminder, unless it comes [reminderAfterFajr]. */
    val reminderAfterSunriseMin: Int = 45,
    /** The daily reminder comes after Fajr, as the plans' «بعد الفجر», rather than after sunrise. */
    val reminderAfterFajr: Boolean = false,
    /** Points in one review and in the morning reminder; more due points wait for the next review. */
    val perDay: Int = 5,
    val markers: Boolean = true,
    val headsUp: Boolean = true,
    /** Pause before each point while listening, to recite it from memory first. */
    val stopBefore: Boolean = false,
    /** Offer «قبل التسميع» before a passage that holds points. */
    val briefing: Boolean = true,
    /** Silence after each verse in drills, in verse lengths. */
    val drillGap: Float = 1f,
    val drillSpeed: Float = 1f,
    /** What the «موضع» sheet offers first: a fresh slip is when reminders help most. */
    val newRepeat: HifzRepeat = HifzRepeatPreset.Prayers.repeat,
    val newRepeatDays: Int = 3,
) {
    companion object {
        private const val PREFS = "quran_hifz"
        val ReminderOffsets = listOf(15, 30, 45, 60, 90, 120)
        val PerDayChoices = listOf(3, 5, 8)

        fun read(context: Context): HifzSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return HifzSettings(
                reminder = prefs.getBoolean("reminder", true),
                reminderAfterSunriseMin = prefs.getInt("reminder_offset", 45).takeIf { it in ReminderOffsets } ?: 45,
                reminderAfterFajr = prefs.getBoolean("reminder_after_fajr", false),
                perDay = prefs.getInt("per_day", 5).takeIf { it in PerDayChoices } ?: 5,
                markers = prefs.getBoolean("markers", true),
                headsUp = prefs.getBoolean("heads_up", true),
                stopBefore = prefs.getBoolean("stop_before", false),
                briefing = prefs.getBoolean("briefing", true),
                drillGap = prefs.getFloat("drill_gap", 1f).takeIf { it in listOf(0f, 0.5f, 1f, 2f) } ?: 1f,
                drillSpeed = prefs.getFloat("drill_speed", 1f).takeIf { it in listOf(0.75f, 1f) } ?: 1f,
                newRepeat = HifzRepeat.decode(prefs.getString("new_repeat", null)) ?: HifzRepeatPreset.Prayers.repeat,
                newRepeatDays = prefs.getInt("new_repeat_days", 3).takeIf { it in 1..HifzRepeatMaxDays } ?: 3,
            )
        }

        fun write(context: Context, settings: HifzSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("reminder", settings.reminder)
                .putInt("reminder_offset", settings.reminderAfterSunriseMin)
                .putBoolean("reminder_after_fajr", settings.reminderAfterFajr)
                .putInt("per_day", settings.perDay)
                .putBoolean("markers", settings.markers)
                .putBoolean("heads_up", settings.headsUp)
                .putBoolean("stop_before", settings.stopBefore)
                .putBoolean("briefing", settings.briefing)
                .putFloat("drill_gap", settings.drillGap)
                .putFloat("drill_speed", settings.drillSpeed)
                .putString("new_repeat", settings.newRepeat.encode())
                .putInt("new_repeat_days", settings.newRepeatDays)
                .apply()
        }

        /** The day «قبل التسميع» was last offered for a chapter, so it shows at most once a day. */
        fun briefedToday(context: Context, surah: Int, today: Long): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("briefed_$surah", -1L) == today

        fun markBriefed(context: Context, surah: Int, today: Long) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("briefed_$surah", today).apply()
        }
    }
}
