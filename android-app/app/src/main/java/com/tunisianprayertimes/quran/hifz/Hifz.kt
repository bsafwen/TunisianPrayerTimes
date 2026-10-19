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

/**
 * The quick choices of the reminder's times; any other is the reader's own, «مخصص». Named by their times, «أوقات»:
 * «مرات» and «قراءات» count a mistake's answers, and «مرة في اليوم» is a mistake's own choice.
 */
enum class HifzTimesPreset(val key: String, val label: String, val times: HifzTimes) {
    /** Once a day, after Fajr: the time of hifz and of review. */
    Once("once", "بعد الفجر فقط", HifzTimes(setOf(Prayer.FAJR))),
    /** After Fajr, Dhuhr and Isha: morning, midday and before sleep. */
    Thrice("thrice", "3 أوقات في اليوم", HifzTimes(setOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ISHA))),
    Prayers("prayers", "بعد كل صلاة", HifzTimes(HifzPrayers.toSet()));

    companion object {
        fun of(times: HifzTimes): HifzTimesPreset? = entries.firstOrNull { it.times == times }
    }
}

/** The most clock times in a day. */
const val HifzMaxClockTimes = 6

/**
 * Right answers a first slip needs, each at its own reminder, before its checks space out: about 3 recalls before
 * spacing is the best-supported number (Rawson & Dunlosky 2011). A slip that comes back needs more; see [hifzAutoRecalls].
 */
const val HifzFreshRecalls = 3
/** The most right answers a point asks for: beyond 5 in a row, more add little (Pyc & Rawson 2009). */
const val HifzMaxRecalls = 5

/**
 * The right answers the app asks of a slip from its history: 3 for a first slip, 4 when it comes back, 5 once it has
 * come back twice, and 2 for a verse that had held («ثبتت»), which is relearned faster than it was learned.
 */
fun hifzAutoRecalls(slips: Int, heldBefore: Boolean): Int = when {
    slips >= 3 -> HifzMaxRecalls
    heldBefore -> 2
    slips == 2 -> 4
    else -> HifzFreshRecalls
}

/**
 * How many right answers a point asks for before its checks space out: as the app judges from its history ([Auto]),
 * one for a passing slip ([Light]), or the most ([Intense]). The reader's choice on «أخطأت» or in the correction.
 */
enum class HifzPace(val key: String) {
    Auto("auto"), Light("light"), Intense("intense");

    companion object {
        fun of(key: String?): HifzPace = entries.firstOrNull { it.key == key } ?: Auto
    }
}
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
    /**
     * Right answers still needed before the checks space out ([HifzFreshRecalls] after a slip, 0 once on the ladder):
     * until then the point is asked at every reminder, [HifzFreshGapMs] at least after its slip or its last answer.
     */
    val fresh: Int = 0,
    /** Actual spaced checks, oldest first. Older notebooks have no inferred review history. */
    val checks: List<HifzCheck> = emptyList(),
    /** How many right answers it asks for while fresh; see [HifzPace]. */
    val pace: HifzPace = HifzPace.Auto,
    /** While fresh, asked once a day, at the first reminder of a day it was not answered, rather than at every one. */
    val daily: Boolean = false,
    /** What [HifzPace.Auto] asks for in this fresh phase, judged from the history when it began; see [hifzAutoRecalls]. */
    val autoRecalls: Int = HifzFreshRecalls,
) {
    val mastered: Boolean get() = step >= HifzMasteredStep
    /** The right answers this fresh phase asks for in all, as [pace] says. */
    val recalls: Int get() = when (pace) {
        HifzPace.Auto -> autoRecalls
        HifzPace.Light -> 1
        HifzPace.Intense -> HifzMaxRecalls
    }
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
     * When a fresh point may next be asked: [freshDueAt], and for one asked [daily], not before the day after it was
     * answered.
     */
    fun freshAskAt(seen: Long?, zone: ZoneId = ZoneId.systemDefault()): Long =
        maxOf(freshDueAt(seen), LocalDate.ofEpochDay(dueDay).atStartOfDay(zone).toInstant().toEpochMilli())
    /**
     * To be asked now, at [now] on [today]: a fresh point once its gap has passed, after its slip, its last answer and
     * its verse last [seen], a [daily] one on a day it was not answered; any other on its day or after, unless its
     * verse was looked at today ([seenToday]), which puts its check to the next day. A point is asked only when its
     * answer counts: never one just seen.
     */
    fun isDue(
        today: Long, now: Long, seen: Map<QuranVerseReference, Long> = emptyMap(),
        seenToday: Set<QuranVerseReference> = emptySet(),
    ): Boolean = if (fresh > 0) now >= freshDueAt(seen[verse]) && dueDay <= today else dueDay <= today && verse !in seenToday
}

/**
 * A slip again, recorded or a failed answer: fresh again, its right answers asked anew, as many as its history now
 * calls for. A passing slip that came back was not one: the app judges again, unless the reader chooses ([pace],
 * [daily]). A [daily] point already asked today ([askedToday]) waits for the next day.
 */
fun HifzPoint.relapsed(
    today: Long, now: Long, askedToday: Boolean, pace: HifzPace? = null, daily: Boolean? = null,
): HifzPoint {
    val count = slips + 1
    val next = copy(
        slips = count, autoRecalls = hifzAutoRecalls(count, mastered),
        pace = pace ?: if (this.pace == HifzPace.Light) HifzPace.Auto else this.pace,
        daily = daily ?: this.daily,
    )
    return next.copy(
        fresh = next.recalls, step = 0, dueDay = if (next.daily && askedToday) today + 1 else today, masteredAt = null,
        lastSlipAt = now,
    )
}

/** The right answers asked after a failed answer now; see [relapsed]. */
val HifzPoint.recallsAfterAgain: Int
    get() = copy(slips = slips + 1, autoRecalls = hifzAutoRecalls(slips + 1, mastered),
        pace = if (pace == HifzPace.Light) HifzPace.Auto else pace).recalls

/** Right answers given since the slip: they count toward any count the plan asks for. */
val HifzPoint.rightSinceSlip: Int get() = checks.count { it.at >= lastSlipAt && it.grade == HifzGrade.Held }

/**
 * The plan changed by the reader on [today]: a fresh point keeps the right answers it already gave ([rightSinceSlip]);
 * a count they already reach ends its questions, its checks spacing out from the next day. Asked once a day, one asked
 * today waits for tomorrow; asked at every reminder, it may come at the next one.
 */
fun HifzPoint.withPlan(pace: HifzPace, daily: Boolean, today: Long): HifzPoint {
    val changed = copy(pace = pace, daily = daily)
    if (fresh == 0) return changed
    val left = changed.recalls - rightSinceSlip
    if (left <= 0) return changed.copy(fresh = 0, step = 0, dueDay = today + HifzLadder.first())
    val askedToday = checks.any { it.day == today }
    return changed.copy(
        fresh = left,
        dueDay = when {
            daily && askedToday -> maxOf(dueDay, today + 1)
            daily -> dueDay
            else -> minOf(dueDay, today)
        },
    )
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
    grade == HifzGrade.Again -> relapsed(today, now, askedToday = true)
    // Asked once a day: the next question is tomorrow's.
    fresh > 0 -> when {
        grade == HifzGrade.Hesitated -> copy(dueDay = if (daily) today + 1 else today)
        fresh == 1 -> copy(fresh = 0, step = 0, dueDay = today + HifzLadder.first())
        else -> copy(fresh = fresh - 1, dueDay = if (daily) today + 1 else today)
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
    grade == HifzGrade.Again -> if (daily) 1 else 0
    fresh > 0 -> if ((grade == HifzGrade.Held && fresh == 1) || daily) HifzLadder.first() else 0
    grade == HifzGrade.Held -> HifzLadder[(step + 1).coerceAtMost(HifzLadder.lastIndex)]
    else -> 2
}

/**
 * The points a review may ask at [now] on [today]: those due, a fresh point a while after its answer was last [seen],
 * a spaced check only on a day its verse was not looked at ([seenToday]).
 */
fun List<HifzPoint>.askable(
    today: Long, now: Long, seen: Map<QuranVerseReference, Long> = emptyMap(),
    seenToday: Set<QuranVerseReference> = emptySet(),
): List<HifzPoint> = filter { it.isDue(today, now, seen, seenToday) }

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
 * A review's batch, at most [limit] points, taken by [dueToday]'s priority; [first], chosen by the reader, asked first.
 * No point is asked after another one shows its verse (R3): a point whose question or answer [shows] another one's verse
 * comes after it, so that both are asked, a look-alike after the verse it was confused with, a verse after the one
 * before it; otherwise mushaf order, as a hafiz goes through the pages. Points that would each see the other's answer
 * first (two on one verse, a look-alike pair both ways, or a longer loop) cannot all be asked: the one answered last
 * waits for the next reminder, also against one ranked lower, so that they take turns.
 */
fun List<HifzPoint>.reviewBatch(today: Long, now: Long, limit: Int, first: HifzPoint? = null): List<HifzPoint> {
    val answered = { point: HifzPoint -> maxOf(point.lastSlipAt, point.checks.lastOrNull()?.at ?: Long.MIN_VALUE) }
    val sees = { a: HifzPoint, b: HifzPoint -> a.id != b.id && a.shows(b.verse) }
    val chosen = mutableListOf<HifzPoint>().apply { first?.let(::add) }
    /** The chosen points on a loop with [point]: each shows the next one's verse, back to [point]. */
    fun loopWith(point: HifzPoint): List<HifzPoint> {
        val all = chosen + point
        fun reach(step: (HifzPoint, HifzPoint) -> Boolean): Set<Long> {
            val found = mutableSetOf(point.id)
            val todo = ArrayDeque(listOf(point))
            while (todo.isNotEmpty()) {
                val from = todo.removeFirst()
                all.filter { it.id !in found && step(from, it) }.forEach { found += it.id; todo += it }
            }
            return found
        }
        val ahead = reach(sees)
        val behind = reach { a, b -> sees(b, a) }
        return chosen.filter { it.id in ahead && it.id in behind }
    }
    /** Takes [point] if it fits as the round stands: room left, and no loop. */
    fun offer(point: HifzPoint) {
        if (chosen.none { it.id == point.id } && chosen.size < limit && loopWith(point).isEmpty()) chosen += point
    }
    val passed = mutableListOf<HifzPoint>()
    for (point in dueToday(today, now)) {
        if (chosen.any { it.id == point.id }) continue
        // The reader's first question would show it before it is asked.
        if (first != null && sees(first, point)) continue
        val dropped = mutableListOf<HifzPoint>()
        while (true) {
            val loop = loopWith(point).filter { it.id != first?.id }
            if (loop.isEmpty()) break
            val latest = loop.maxWith(compareBy<HifzPoint>({ answered(it) }, { it.id }))
            if (answered(latest) <= answered(point)) break
            chosen.remove(latest)
            dropped += latest
        }
        if (loopWith(point).isEmpty() && chosen.size < limit) {
            chosen += point
            // Places freed, or a point no longer in a loop: those passed come first, by rank, as they now fit.
            if (dropped.isNotEmpty()) passed.forEach(::offer)
        } else chosen += dropped
        passed += point
    }
    val rest = chosen.filter { it.id != first?.id }.sortedWith(compareBy<HifzPoint>({ it.verse }, { it.id })).toMutableList()
    return buildList {
        first?.let(::add)
        while (rest.isNotEmpty()) {
            // A point once every one whose verse it shows is asked; with none left, in mushaf order.
            val next = rest.firstOrNull { point -> rest.none { sees(point, it) } } ?: rest.first()
            add(next)
            rest.remove(next)
        }
    }
}

/**
 * The verses a point's correction shows, in its excerpts and its audio: its own verse and its look-alike; for a
 * forgotten start, the verse before it too; for a skipped verse, the verse before it and every verse to where the
 * recitation landed.
 */
internal fun HifzPoint.answerVerses(): Set<QuranVerseReference> = buildSet {
    add(verse)
    other?.let(::add)
    val before = QuranVerseReference(verse.surah, verse.ayah - 1).takeIf { verse.ayah > 1 }
    when (kind) {
        HifzKind.Start -> before?.let(::add)
        HifzKind.Skip -> {
            before?.let(::add)
            val landed = other?.takeIf { it.surah == verse.surah && it.ayah > verse.ayah }?.ayah ?: (verse.ayah + 1)
            (verse.ayah + 1..landed).forEach { add(QuranVerseReference(verse.surah, it)) }
        }
        else -> Unit
    }
}

/** A verse a point's question shows, the end of the verse before it, or its answer ([answerVerses]). */
internal fun HifzPoint.shows(other: QuranVerseReference): Boolean =
    other in answerVerses() || (other.surah == verse.surah && other.ayah == verse.ayah - 1)

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
 * reminded of them ([remindedDay], the last prayer day that was). A check whose verse was looked at today
 * ([seenToday]) is the next day's.
 */
fun List<HifzPoint>.nextReminder(
    times: HifzTimes, today: Long, after: Long, remindedDay: Long, seenToday: Set<QuranVerseReference> = emptySet(),
    seen: Map<QuranVerseReference, Long> = emptyMap(), zone: ZoneId = ZoneId.systemDefault(),
    /** The prayer day each point asked once a day was last asked by a reminder, answered or not. */
    dailyAsked: Map<Long, Long> = emptyMap(),
    slotAt: (day: Long, time: HifzWhen) -> Long?,
): HifzSlot? {
    val (daily, often) = filter { it.fresh > 0 }.partition { it.daily }
    val fresh = listOfNotNull(
        often.minOfOrNull { it.freshDueAt(seen[it.verse]) }?.let { due -> times.firstAfter(maxOf(after, due - 1), zone = zone, slotAt = slotAt) },
    ) + daily.mapNotNull {
        val from = maxOf(it.dueDay, dailyAsked[it.id]?.plus(1) ?: Long.MIN_VALUE)
        times.firstAfter(maxOf(after, it.freshDueAt(seen[it.verse]) - 1), from, zone, slotAt)
    }
    val checksDay = filter { it.fresh == 0 }.minOfOrNull { if (it.verse in seenToday && it.dueDay <= today) today + 1 else it.dueDay }
        ?.let { maxOf(it, remindedDay + 1) }
    val checks = checksDay?.let { times.firstAfter(after, it, zone, slotAt) }
    return (fresh + listOfNotNull(checks)).minByOrNull { it.at }
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
     * reminders from [HifzFreshGapMs] on, until it is answered right as many times as its plan says ([pace], [daily]:
     * the reader's choice; null keeps the point's, or the app's for a new one).
     */
    fun record(
        context: Context,
        verse: QuranVerseReference,
        kind: HifzKind,
        other: QuranVerseReference? = null,
        slipWords: Set<Int> = emptySet(),
        bothWays: Boolean = false,
        now: Long = System.currentTimeMillis(),
        pace: HifzPace? = null,
        daily: Boolean? = null,
    ): HifzPoint = change(context) { points ->
        val today = epochDay(now)
        val existing = points.sameSlip(verse, kind, other)
        val point = existing?.copy(
            other = other ?: existing.other,
            // The words chosen now, which the sheet starts from the earlier ones; none chosen keeps those.
            slipWords = slipWords.ifEmpty { existing.slipWords },
            bothWays = bothWays || existing.bothWays,
        )?.relapsed(today, now, askedToday = existing.checks.any { it.day == today }, pace = pace, daily = daily)
            ?: HifzPoint(
                id = now, verse = verse, kind = kind, other = other, slipWords = slipWords, bothWays = bothWays,
                dueDay = today, createdAt = now, pace = pace ?: HifzPace.Auto, daily = daily ?: false,
            ).let { it.copy(fresh = it.recalls) }
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
                if (point.pace != HifzPace.Auto) put("pace", point.pace.key)
                if (point.daily) put("daily", true)
                if (point.autoRecalls != HifzFreshRecalls) put("autoRecalls", point.autoRecalls)
            }
            .put("bothWays", point.bothWays)
            .put("key", point.key)
            .put("slips", point.slips)
            .put("step", point.step)
            .put("dueDay", point.dueDay)
            .put("createdAt", point.createdAt)
            .put("lastSlipAt", point.lastSlipAt)
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
            key = row.optString("key"),
            slips = row.optInt("slips", 1).coerceAtLeast(1),
            step = row.optInt("step").coerceAtLeast(0),
            dueDay = row.getLong("dueDay"),
            createdAt = row.getLong("createdAt"),
            lastSlipAt = row.optLong("lastSlipAt", row.getLong("createdAt")),
            masteredAt = if (row.has("masteredAt")) row.getLong("masteredAt") else null,
            fresh = row.optInt("fresh").coerceIn(0, HifzMaxRecalls),
            checks = decodeHifzChecks(row.optString("checks")),
            pace = HifzPace.of(row.optString("pace").ifEmpty { null }),
            daily = row.optBoolean("daily"),
            autoRecalls = row.optInt("autoRecalls", HifzFreshRecalls).coerceIn(1, HifzMaxRecalls),
        )
    }
}

/** Points in one review and in a reminder; more due points wait for the next review, «تابع مراجعات اليوم». */
const val HifzRoundSize = 5

/** The drill after a wrong answer, and the listening of points: a pause the length of each verse, at the reciter's pace. */
const val HifzDrillGap = 1f
const val HifzDrillSpeed = 1f

/**
 * How «المراجعة» behaves; kept in its own preferences. Only when it asks: the reminder and its times. [markers], the
 * reader's «مواضعي على الصفحات», is set from the reader's menu.
 */
data class HifzSettings(
    /** The reminder that asks about the points due, at [times]. */
    val reminder: Boolean = true,
    /** After each prayer by default: a fresh slip is asked at each, and that is when reminders help most. */
    val times: HifzTimes = HifzTimesPreset.Prayers.times,
    val markers: Boolean = true,
) {
    companion object {
        private const val PREFS = "quran_hifz"
        /**
         * Settings that are no more: the round's size, the listening aids, the briefing, the drill's pause and speed;
         * and the briefing's daily notes, «briefed_» and a chapter or hizb.
         */
        private val RemovedKeys = listOf("per_day", "heads_up", "stop_before", "briefing", "drill_gap", "drill_speed")
        private fun removed(key: String) = key in RemovedKeys || key.startsWith("briefed_")

        fun read(context: Context): HifzSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stale = prefs.all.keys.filter(::removed)
            if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach { remove(it) } }.apply()
            return HifzSettings(
                reminder = prefs.getBoolean("reminder", true),
                times = HifzTimes.decode(prefs.getString("times", null)) ?: HifzTimesPreset.Prayers.times,
                markers = prefs.getBoolean("markers", true),
            )
        }

        fun write(context: Context, settings: HifzSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("reminder", settings.reminder)
                .putString("times", settings.times.encode())
                .putBoolean("markers", settings.markers)
                .apply()
        }
    }
}
