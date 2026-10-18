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

/** One time of the day the reminder may come: after one of the five prayers, or at a clock time. */
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
 * When the reminder of «المراجعة» comes, every day: after some of the five prayers and at some clock times, as
 * minutes of the day. One choice for every point, in the settings; never empty, the reminder being turned off apart.
 */
data class HifzTimes(val prayers: Set<Prayer> = emptySet(), val minutes: Set<Int> = emptySet()) {
    val none: Boolean get() = prayers.isEmpty() && minutes.isEmpty()
    /** How many reminders a day. */
    val count: Int get() = prayers.size + minutes.size
    /** The day's times, the prayers first. */
    val times: List<HifzWhen> get() = HifzPrayers.filter { it in prayers }.map { HifzWhen.After(it) } + minutes.sorted().map { HifzWhen.At(it) }

    operator fun contains(time: HifzWhen): Boolean = when (time) {
        is HifzWhen.After -> time.prayer in prayers
        is HifzWhen.At -> time.minute in minutes
    }

    /** «FAJR,ISHA,at1290», for storage. */
    fun encode(): String = times.joinToString(",") { it.key }

    companion object {
        /** Null when [value] is missing, empty or not a list of times. */
        fun decode(value: String?): HifzTimes? {
            value ?: return null
            val times = value.split(',').filter { it.isNotEmpty() }.map { HifzWhen.of(it) ?: return null }
            return HifzTimes(
                times.filterIsInstance<HifzWhen.After>().map { it.prayer }.toSet(),
                times.filterIsInstance<HifzWhen.At>().map { it.minute }.toSet(),
            ).takeIf { !it.none }
        }
    }
}

/** The quick choices of the reminder's times; any other is the reader's own, «مخصص». */
enum class HifzTimesPreset(val key: String, val label: String, val times: HifzTimes) {
    /** Once a day, after Fajr: the time of hifz and of review. */
    Once("once", "مرة في اليوم", HifzTimes(setOf(Prayer.FAJR))),
    /** After Fajr, Dhuhr and Isha: morning, midday and before sleep. */
    Thrice("thrice", "3 مرات في اليوم", HifzTimes(setOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ISHA))),
    Prayers("prayers", "بعد كل صلاة", HifzTimes(HifzPrayers.toSet()));

    companion object {
        fun of(times: HifzTimes): HifzTimesPreset? = entries.firstOrNull { it.times == times }
    }
}

/** The most clock times in a day. */
const val HifzMaxClockTimes = 6

/**
 * Right answers a slip needs, each at its own reminder, before its checks space out: a fresh slip, or one failed in a
 * check, is asked at every reminder until then.
 */
const val HifzFreshRecalls = 3
/**
 * The least time between a fresh point's slip or answer and its next question: long enough that the answer is
 * recalled, not still in mind. Shorter than the time between Maghrib and Isha, so that one comes after the other.
 */
const val HifzFreshGapMs = 45 * 60_000L

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
    /**
     * Right answers still needed before the checks space out ([HifzFreshRecalls] after a slip, 0 once on the ladder):
     * until then the point is asked at every reminder, [HifzFreshGapMs] at least after its slip or its last answer.
     */
    val fresh: Int = 0,
    /** Actual spaced checks, oldest first. Older notebooks have no inferred review history. */
    val checks: List<HifzCheck> = emptyList(),
) {
    val mastered: Boolean get() = step >= HifzMasteredStep
    /** The first of [slipWords]: where a look-alike parts, or the first wrong word. */
    val word: Int? get() = slipWords.minOrNull()
    /** When a [fresh] point may be asked again: a while after its slip or its last answer, whichever came later. */
    val freshDueAt: Long get() = maxOf(lastSlipAt, checks.lastOrNull()?.at ?: 0L) + HifzFreshGapMs
    /** Recent difficulty has priority until two unaided checks show recovery. */
    val needsCare: Boolean
        get() {
            if (checks.isEmpty()) return slips >= 3 && !mastered
            if (lastSlipAt > checks.last().at) return true
            val recent = dailyChecksSinceSlip.takeLast(3)
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
            val sinceSlip = dailyChecksSinceSlip
            if (sinceSlip.size >= 2 && sinceSlip.takeLast(2).all { it.grade == HifzGrade.Held }) {
                return "تذكرتها دون مساعدة في آخر مراجعتين"
            }
            return when (last.grade) {
                HifzGrade.Held -> "آخر مراجعة: تذكرتها دون مساعدة"
                HifzGrade.Hesitated -> "آخر مراجعة: تردد أو احتجت تلميحًا"
                HifzGrade.Again -> "آخر مراجعة: تحتاج تدريبًا"
            }
        }
    /**
     * The checks since the last slip, the first of each day only: a fresh point answered at several reminders of one
     * day shows recovery once, not as many times.
     */
    private val dailyChecksSinceSlip: List<HifzCheck>
        get() = checks.filter { it.at >= lastSlipAt }.groupBy { it.day }.values.map { day -> day.minBy { it.at } }.sortedBy { it.at }
    /** Checks still to pass, each «أتقنتها», before the point is held («ثبتت»). */
    val checksToHold: Int get() = (HifzMasteredStep - step).coerceAtLeast(0)
    /**
     * When a fresh point may be asked, its answer [seen] last at that time (the verse shown, heard or corrected), also
     * a while ago: a recall right after seeing the answer would be none.
     */
    fun freshDueAt(seen: Long?): Long = if (seen == null) freshDueAt else maxOf(freshDueAt, seen + HifzFreshGapMs)
    /**
     * To be asked now, at [now] on [today]: a fresh point once its gap has passed, after its slip, its last answer and
     * its verse last [seen], any other on its day or after.
     */
    fun isDue(today: Long, now: Long, seen: Map<QuranVerseReference, Long> = emptyMap()): Boolean =
        if (fresh > 0) now >= freshDueAt(seen[verse]) else dueDay <= today
}

/**
 * Days until the next check after each successful one: 1, 3, 7 and 14, then held at 30 and every 60. The first, a day,
 * follows the last of the [HifzFreshRecalls] right answers.
 */
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
 * The point after a review graded [grade] on [today]. A fresh point counts its right answers down, at the next reminders,
 * and its checks space out after the last: from the next day, then along [HifzLadder]. Later, a hesitation brings the
 * point back in 2 days, one step lower: the next «أتقنتها» then waits the same time as before instead of a longer one,
 * and a held point hesitated on leaves «ثبتت» until it is held again. A failed check is a slip like any other: the point
 * is fresh again, asked at every reminder until it is answered right [HifzFreshRecalls] times.
 */
fun HifzPoint.graded(grade: HifzGrade, today: Long, now: Long): HifzPoint = when {
    grade == HifzGrade.Again -> copy(
        fresh = HifzFreshRecalls, step = 0, dueDay = today, masteredAt = null, slips = slips + 1, lastSlipAt = now,
    )
    fresh > 0 -> when {
        grade == HifzGrade.Hesitated -> copy(dueDay = today)
        fresh == 1 -> copy(fresh = 0, step = 0, dueDay = today + HifzLadder.first())
        else -> copy(fresh = fresh - 1, dueDay = today)
    }
    grade == HifzGrade.Held -> {
        val next = step + 1
        copy(step = next, dueDay = today + HifzLadder[next.coerceAtMost(HifzLadder.lastIndex)],
            masteredAt = if (next == HifzMasteredStep) now else masteredAt)
    }
    else -> {
        val back = (step - 1).coerceAtLeast(0)
        copy(step = back, dueDay = today + 2, masteredAt = masteredAt.takeIf { back >= HifzMasteredStep })
    }
}.copy(checks = (checks + HifzCheck(today, grade, now)).takeLast(HifzCheckLimit))

/** A «أتقنتها» now makes the point held («ثبتت»). */
val HifzPoint.holdsNext: Boolean get() = fresh == 0 && step + 1 == HifzMasteredStep

/** A «أتقنتها» now ends the fresh point's questions at every reminder: its checks space out from the next day. */
val HifzPoint.spacesNext: Boolean get() = fresh == 1

/**
 * Days until the next check if the review is graded [grade]; 0 for a point asked again at the next reminders, which
 * every failed check makes.
 */
fun HifzPoint.daysAfter(grade: HifzGrade): Int = when {
    grade == HifzGrade.Again -> 0
    fresh > 0 -> if (grade == HifzGrade.Held && fresh == 1) HifzLadder.first() else 0
    grade == HifzGrade.Held -> HifzLadder[(step + 1).coerceAtMost(HifzLadder.lastIndex)]
    else -> 2
}

/**
 * The points a review may ask at [now] on [today]: those due, but not one practised today with its answer in view,
 * whose check waits for the next day. A fresh point is asked again the same day, a while after its answer was last
 * [seen].
 */
fun List<HifzPoint>.askable(
    today: Long, now: Long, practised: Set<Long>, seen: Map<QuranVerseReference, Long> = emptyMap(),
): List<HifzPoint> = filter { it.isDue(today, now, seen) && (it.fresh > 0 || it.id !in practised) }

/**
 * The checks due now, at most [limit]: the fresh slips first, which the reminders are for, then those needing care.
 */
fun List<HifzPoint>.dueToday(today: Long, now: Long, limit: Int = Int.MAX_VALUE): List<HifzPoint> =
    filter { it.isDue(today, now) }
        .sortedWith(
            compareByDescending<HifzPoint> { it.fresh > 0 }.thenByDescending { it.needsCare }.thenBy { it.dueDay }
                .thenByDescending { it.slips }.thenBy { it.verse },
        )
        .take(limit)

/**
 * A review's batch: the checks [dueToday] picks, then in mushaf order, as a hafiz goes through the pages. Two points
 * on one verse are kept apart, since the second would come right after its own answer, and the one answered longest
 * ago comes first: the second's answer, seen in the first's, is practice, so they take turns at being counted.
 */
fun List<HifzPoint>.reviewBatch(today: Long, now: Long, limit: Int): List<HifzPoint> {
    val batch = mutableListOf<HifzPoint>()
    val later = mutableListOf<HifzPoint>()
    dueToday(today, now, limit)
        .sortedWith(compareBy<HifzPoint> { it.verse }.thenBy { maxOf(it.lastSlipAt, it.checks.lastOrNull()?.at ?: Long.MIN_VALUE) }.thenBy { it.id })
        .forEach { point -> if (batch.lastOrNull()?.verse == point.verse) later += point else batch += point }
    later.forEach { point ->
        // After the first of its verse, never before it.
        val from = batch.indexOfLast { it.verse == point.verse } + 1
        val at = (from..batch.size).firstOrNull { i -> batch.getOrNull(i - 1)?.verse != point.verse && batch.getOrNull(i)?.verse != point.verse }
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

/** A reminder: [time] of the prayer day [day], an epoch day, coming at [at] in wall-clock milliseconds. */
data class HifzSlot(val day: Long, val time: HifzWhen, val at: Long)

/** Prayer days looked through for a reminder: prayer times further off may not be known yet. */
private const val HifzSlotScanDays = 4

/**
 * The first of these times later than [after], of the prayer day [fromDay] or a later one: a day's Isha that ends after
 * midnight (plan 6.9) is still that day's, never the next day's first reminder. Prayer days are taken from the one
 * before [after], and a day's reminders come on that day or, for Isha, just after midnight: two prayer days past the
 * earliest found, none can come before it. [slotAt] gives when a time of a prayer day comes, or null when that day's
 * prayer times are not known.
 */
fun HifzTimes.firstAfter(
    after: Long, fromDay: Long = Long.MIN_VALUE, zone: ZoneId = ZoneId.systemDefault(),
    slotAt: (day: Long, time: HifzWhen) -> Long?,
): HifzSlot? {
    if (none) return null
    val start = maxOf(epochDay(after, zone) - 1, fromDay)
    var best: HifzSlot? = null
    for (day in start..start + HifzSlotScanDays) {
        if (best != null && day > best.day + 1) break
        for (time in times) {
            val at = slotAt(day, time) ?: continue
            if (at > after && (best == null || at < best.at)) best = HifzSlot(day, time, at)
        }
    }
    return best
}

/**
 * The next reminder later than [after]: the first of [times] by which a fresh point may be asked again, its answer
 * last [seen] a while before, or, for the spaced checks, the first of [times] of their prayer day, and of no day already
 * reminded of them ([remindedDay], the last prayer day that was). A point practised today with its answer in view
 * ([practised]) has its check the next day.
 */
fun List<HifzPoint>.nextReminder(
    times: HifzTimes, today: Long, after: Long, remindedDay: Long, practised: Set<Long> = emptySet(),
    seen: Map<QuranVerseReference, Long> = emptyMap(), zone: ZoneId = ZoneId.systemDefault(),
    slotAt: (day: Long, time: HifzWhen) -> Long?,
): HifzSlot? {
    val fresh = filter { it.fresh > 0 }.minOfOrNull { it.freshDueAt(seen[it.verse]) }
        ?.let { due -> times.firstAfter(maxOf(after, due - 1), zone = zone, slotAt = slotAt) }
    val checksDay = filter { it.fresh == 0 }.minOfOrNull { if (it.id in practised && it.dueDay <= today) today + 1 else it.dueDay }
        ?.let { maxOf(it, remindedDay + 1) }
    val checks = checksDay?.let { times.firstAfter(after, it, zone, slotAt) }
    return listOfNotNull(fresh, checks).minByOrNull { it.at }
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
     * Records a slip, or counts it again on an existing point. Returns the point as saved: fresh, asked at the
     * reminders from [HifzFreshGapMs] on, until it is answered right [HifzFreshRecalls] times.
     */
    fun record(
        context: Context,
        verse: QuranVerseReference,
        kind: HifzKind,
        other: QuranVerseReference? = null,
        slipWords: Set<Int> = emptySet(),
        bothWays: Boolean = false,
        hafs: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): HifzPoint = change(context) { points ->
        val today = epochDay(now)
        val existing = points.sameSlip(verse, kind, other)
        val point = existing?.copy(
            other = other ?: existing.other,
            // A word slip adds the words missed this time; a look-alike keeps one parting word.
            slipWords = if (kind == HifzKind.Word) existing.slipWords + slipWords else slipWords.ifEmpty { existing.slipWords },
            bothWays = bothWays || existing.bothWays, hafs = hafs || existing.hafs,
            slips = existing.slips + 1, step = 0, dueDay = today, lastSlipAt = now, masteredAt = null, fresh = HifzFreshRecalls,
        ) ?: HifzPoint(
            id = now, verse = verse, kind = kind, other = other, slipWords = slipWords, bothWays = bothWays, hafs = hafs,
            dueDay = today, createdAt = now, fresh = HifzFreshRecalls,
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
                if (point.fresh > 0) put("fresh", point.fresh)
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
            fresh = row.optInt("fresh").coerceIn(0, HifzFreshRecalls),
            checks = decodeHifzChecks(row.optString("checks")),
        )
    }
}

/** How «المراجعة» behaves; kept in its own preferences. */
data class HifzSettings(
    /** The reminder that asks about the points due, at [times]. */
    val reminder: Boolean = true,
    /** After each prayer by default: a fresh slip is asked at each, and that is when reminders help most. */
    val times: HifzTimes = HifzTimesPreset.Prayers.times,
    /** Points in one review and in a reminder; more due points wait for the next review. */
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
) {
    companion object {
        private const val PREFS = "quran_hifz"
        val PerDayChoices = listOf(3, 5, 8)

        fun read(context: Context): HifzSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return HifzSettings(
                reminder = prefs.getBoolean("reminder", true),
                times = HifzTimes.decode(prefs.getString("times", null)) ?: HifzTimesPreset.Prayers.times,
                perDay = prefs.getInt("per_day", 5).takeIf { it in PerDayChoices } ?: 5,
                markers = prefs.getBoolean("markers", true),
                headsUp = prefs.getBoolean("heads_up", true),
                stopBefore = prefs.getBoolean("stop_before", false),
                briefing = prefs.getBoolean("briefing", true),
                drillGap = prefs.getFloat("drill_gap", 1f).takeIf { it in listOf(0f, 0.5f, 1f, 2f) } ?: 1f,
                drillSpeed = prefs.getFloat("drill_speed", 1f).takeIf { it in listOf(0.75f, 1f) } ?: 1f,
            )
        }

        fun write(context: Context, settings: HifzSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("reminder", settings.reminder)
                .putString("times", settings.times.encode())
                .putInt("per_day", settings.perDay)
                .putBoolean("markers", settings.markers)
                .putBoolean("heads_up", settings.headsUp)
                .putBoolean("stop_before", settings.stopBefore)
                .putBoolean("briefing", settings.briefing)
                .putFloat("drill_gap", settings.drillGap)
                .putFloat("drill_speed", settings.drillSpeed)
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
