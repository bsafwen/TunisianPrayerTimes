package com.tunisianprayertimes.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.hifz.HifzCheck
import com.tunisianprayertimes.quran.hifz.HifzDrillGap
import com.tunisianprayertimes.quran.hifz.HifzFreshGapMs
import com.tunisianprayertimes.quran.hifz.HifzFreshRecalls
import com.tunisianprayertimes.quran.hifz.HifzGrade
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzLookAlikes
import com.tunisianprayertimes.quran.hifz.HifzMasteredStep
import com.tunisianprayertimes.quran.hifz.HifzPoint
import com.tunisianprayertimes.quran.hifz.shows
import com.tunisianprayertimes.quran.hifz.HifzReminder
import com.tunisianprayertimes.quran.hifz.HifzReminder.NB
import com.tunisianprayertimes.quran.hifz.daysAfter
import com.tunisianprayertimes.quran.hifz.atStart
import com.tunisianprayertimes.quran.hifz.epochDay
import com.tunisianprayertimes.quran.hifz.HifzFix
import com.tunisianprayertimes.quran.hifz.decodeHifzChecks
import com.tunisianprayertimes.quran.hifz.encodeHifzChecks
import com.tunisianprayertimes.quran.hifz.hifzFix
import com.tunisianprayertimes.quran.hifz.hifzWords
import com.tunisianprayertimes.quran.hifz.holdsNext
import com.tunisianprayertimes.quran.hifz.recallsAfterAgain
import com.tunisianprayertimes.quran.hifz.HifzPace
import com.tunisianprayertimes.quran.hifz.HifzTimes
import com.tunisianprayertimes.quran.hifz.HifzMaxRecalls
import com.tunisianprayertimes.quran.hifz.spacesNext
import com.tunisianprayertimes.quran.hifz.todayEpochDay
import com.tunisianprayertimes.ui.theme.*

/** What a review card can ask of the reader. */
internal interface HifzReviewActions {
    fun grade(point: HifzPoint, grade: HifzGrade)
    /** Takes a grade back: the point's schedule returns to [given]'s; what was changed since in its fix stays. */
    fun undo(given: HifzGiven)
    /** The verse before the point, once, as a cue. */
    fun listenBefore(point: HifzPoint)
    /** The right wording, once. */
    fun listen(point: HifzPoint)
    fun drill(point: HifzPoint)
    fun fix(point: HifzPoint)
    /** The card is left, or the review ends: the audio it started stops. */
    fun leaveCard()
    /** Another review, of the points still due beyond this one's. */
    fun more()
    /** «استمع إلى ما راجعته», once the checks are done: hearing them before would have been no check. */
    fun listenTo(ids: List<Long>)
    /** Verses shown or heard on purpose: their points are not asked right after. */
    fun exposed(verses: Set<QuranVerseReference>) {}
    /** Each time an answer is shown or heard, already exposed or not: a fresh point is asked a while after it. */
    fun seen(verses: Set<QuranVerseReference>) {}
    fun dismiss()
}

/**
 * A grade given in this review, for its undo and the final tally: the schedule the point had before it, and its slips,
 * which a failed check counts. Kept as text, so that a re-created activity can still take it back.
 */
internal data class HifzGiven(
    val id: Long,
    val grade: HifzGrade,
    val step: Int,
    val dueDay: Long,
    val masteredAt: Long?,
    /** The grade made the point held («ثبتت»). */
    val heldNow: Boolean,
    val slips: Int,
    val lastSlipAt: Long,
    val checks: List<HifzCheck> = emptyList(),
    /** The right answers the point still needed at every reminder. */
    val fresh: Int = 0,
    /** Its plan, which a failed answer may change: a passing slip that came back is judged again. */
    val pace: HifzPace = HifzPace.Auto,
    val autoRecalls: Int = HifzFreshRecalls,
    /** Its own reminder times; null for the settings'. */
    val times: HifzTimes? = null,
) {
    fun encode(): String = listOf(
        id, grade.name, step, dueDay, masteredAt ?: "", if (heldNow) 1 else 0, slips, lastSlipAt, encodeHifzChecks(checks), fresh,
        pace.key, autoRecalls, times?.encode().orEmpty(),
    ).joinToString("|")

    companion object {
        fun of(point: HifzPoint, grade: HifzGrade) = HifzGiven(
            point.id, grade, point.step, point.dueDay, point.masteredAt, grade == HifzGrade.Held && point.holdsNext, point.slips, point.lastSlipAt, point.checks,
            point.fresh, point.pace, point.autoRecalls, point.times,
        )

        fun decode(value: String?): HifzGiven? {
            val parts = value?.split('|')?.takeIf { it.size in 8..13 } ?: return null
            return HifzGiven(
                id = parts[0].toLongOrNull() ?: return null,
                grade = HifzGrade.entries.firstOrNull { it.name == parts[1] } ?: return null,
                step = parts[2].toIntOrNull() ?: return null,
                dueDay = parts[3].toLongOrNull() ?: return null,
                masteredAt = parts[4].toLongOrNull(),
                heldNow = parts[5] == "1",
                slips = parts[6].toIntOrNull() ?: return null,
                lastSlipAt = parts[7].toLongOrNull() ?: return null,
                checks = decodeHifzChecks(parts.getOrNull(8)),
                fresh = parts.getOrNull(9)?.toIntOrNull() ?: 0,
                pace = HifzPace.of(parts.getOrNull(10)),
                autoRecalls = parts.getOrNull(11)?.toIntOrNull()?.coerceIn(1, HifzMaxRecalls) ?: HifzFreshRecalls,
                times = HifzTimes.decode(parts.getOrNull(12)?.ifEmpty { null }),
            )
        }
    }
}

/** The answers of a review: added up over «تابع المراجعة», so that the day's count is the day's. */
@Immutable
internal data class HifzTally(val held: Int = 0, val hesitated: Int = 0, val again: Int = 0, val heldNow: Int = 0) {
    val graded: Int get() = held + hesitated + again
    operator fun plus(other: HifzTally) = HifzTally(held + other.held, hesitated + other.hesitated, again + other.again, heldNow + other.heldNow)
    fun encode(): String = "$held|$hesitated|$again|$heldNow"

    companion object {
        fun decode(value: String?): HifzTally =
            value?.split('|')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 }?.let { HifzTally(it[0], it[1], it[2], it[3]) } ?: HifzTally()
    }
}

private fun List<String>?.intAt(i: Int): Int = this?.getOrNull(i)?.toIntOrNull() ?: 0
private fun List<String>?.longAt(i: Int): Long? = this?.getOrNull(i)?.toLongOrNull()
private fun List<String>?.idsAt(i: Int): List<Long> = this?.getOrNull(i)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
private fun List<String>?.versesAt(i: Int): Set<QuranVerseReference> = this?.getOrNull(i)?.split(',')?.mapNotNull { value ->
    val parts = value.split(':')
    val surah = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
    val ayah = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
    QuranVerseReference(surah, ayah).takeIf { surah > 0 && ayah > 0 }
}.orEmpty().toSet()
private fun Set<QuranVerseReference>.encodeReviewVerses(): String = joinToString(",") { "${it.surah}:${it.ayah}" }
private fun encodeSeenVerses(seen: Map<QuranVerseReference, Long>): String = seen.entries.joinToString(",") { (v, at) -> "${v.surah}:${v.ayah}:$at" }
private fun decodeSeenVerses(value: String): Map<QuranVerseReference, Long> = value.split(',').mapNotNull { row ->
    val parts = row.split(':')
    val surah = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
    val ayah = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
    val at = parts.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
    (QuranVerseReference(surah, ayah) to at).takeIf { surah > 0 && ayah > 0 }
}.toMap()
private fun mergeSeen(a: Map<QuranVerseReference, Long>, b: Map<QuranVerseReference, Long>): Map<QuranVerseReference, Long> =
    if (b.isEmpty()) a else (a.keys + b.keys).associateWith { maxOf(a[it] ?: Long.MIN_VALUE, b[it] ?: Long.MIN_VALUE) }

/**
 * The day's questions: the verse covered, recited from memory, then the answer shown and an honest grade. Every
 * question asked counts: a point whose verse was looked at a moment ago, in this review (a look-alike shown with another
 * point's answer, two points on one verse) or before it, is no recall now and waits for the next reminder. Hesitation
 * and forgetting open a short drill; the next reminder is the retest. Audio a card starts stops when the card is left,
 * and the last answer can be taken back. Every way out shows what was answered first.
 *
 * Kept by the reader rather than by a card: the review shows on the page or in a sheet, and goes from one to the other
 * with its place, its answers and its undo. Saved as text, so a re-created activity finds it again.
 */
@Stable
internal class HifzReviewState private constructor(
    saved: List<String>?,
    private val clock: () -> Long,
    earlier: HifzTally,
    exposedVerses: Set<QuranVerseReference>,
    seenVerses: Map<QuranVerseReference, Long>,
    private val wallClock: () -> Long,
) {
    /**
     * [clock]: [SystemClock.uptimeMillis], or a test's. [earlier]: the answers of the day's reviews before this one.
     * [exposedVerses]: the verses looked at today, whose spaced checks wait for tomorrow. [seenVerses]: when each verse's
     * answer was last seen, by [wallClock], for the fresh points.
     */
    constructor(
        clock: () -> Long = SystemClock::uptimeMillis,
        earlier: HifzTally = HifzTally(),
        exposedVerses: Set<QuranVerseReference> = emptySet(),
        seenVerses: Map<QuranVerseReference, Long> = emptyMap(),
        wallClock: () -> Long = System::currentTimeMillis,
    ) : this(null, clock, earlier, exposedVerses, seenVerses, wallClock)

    var held by mutableIntStateOf(saved.intAt(0))
        private set
    var hesitated by mutableIntStateOf(saved.intAt(1))
        private set
    var again by mutableIntStateOf(saved.intAt(2))
        private set
    /** Points that became «ثبتت» in this review. */
    var heldNow by mutableIntStateOf(saved.intAt(3))
        private set
    // The card in view, by its point: a point deleted before it in the queue (from a fix opened over the review)
    // does not move the review on. When the card itself goes, the next one at its place takes over.
    private var cardId by mutableStateOf(saved.longAt(4))
    private var position by mutableIntStateOf(saved.intAt(5))
    // Each card starts hidden: the one revealed is named, so a new card, or a deleted one replaced by the next
    // at the same place, is not.
    private var revealedId by mutableStateOf(saved.longAt(6))
    /** The card answered «بتردد» or «نسيت», kept for its drill until «التالي». */
    private var trainingId by mutableStateOf(saved.longAt(7))
    private var drilledId by mutableStateOf(saved.longAt(8))
    /** The last grade, as [HifzGiven.encode]. */
    private var lastGiven by mutableStateOf(saved?.getOrNull(9)?.ifEmpty { null })
    var howItWorks by mutableStateOf(saved?.getOrNull(10) == "1")
    /** On the page: the card folded down to its title, for the page under it. A new card opens unfolded. */
    var folded by mutableStateOf(saved?.getOrNull(11) == "1")
    /** The day's answers before this review. */
    val earlier: HifzTally = saved?.getOrNull(12)?.let(HifzTally::decode) ?: earlier
    /** Every point answered in this review, in order. */
    var gradedIds by mutableStateOf(saved.idsAt(13))
        private set
    /** «إنهاء» with answers given: what was answered shows first, with «تابع الآن». */
    var stopping by mutableStateOf(saved?.getOrNull(14) == "1")
        private set
    /** The card whose «ضابطي» the reader asked to see before «أظهر», as a hint. */
    private var hintId by mutableStateOf(saved.longAt(15))
    /** A hint or the page's eraser helped before the answer was revealed. */
    private var assistedId by mutableStateOf(saved.longAt(16))
    private var lastGradeAssisted by mutableStateOf(saved?.getOrNull(17) == "1")
    private var exposedVerses by mutableStateOf(saved?.getOrNull(18)?.let { saved.versesAt(18) } ?: exposedVerses)
    private var attemptId by mutableStateOf(saved.longAt(19))
    private var attemptVerse by mutableStateOf(saved.versesAt(20).firstOrNull())
    /** When each verse's answer was last seen, shown or heard, by the wall clock. */
    private var seenAt by mutableStateOf(saved?.getOrNull(21)?.let { decodeSeenVerses(it) } ?: seenVerses)
    /** Points no longer asked in this review: their verse was looked at in it, so they wait for the next reminder. */
    var deferredIds by mutableStateOf(saved.idsAt(22).toSet())
        private set
    private var shownKey: Any? = null
    private var shownAt = 0L

    fun index(queue: List<HifzPoint>): Int = cardId?.let { id -> queue.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: position
    fun point(queue: List<HifzPoint>): HifzPoint? = queue.getOrNull(index(queue))
    fun revealed(point: HifzPoint?): Boolean = point != null && revealedId == point.id
    fun training(point: HifzPoint?): Boolean = point != null && trainingId == point.id
    fun drilled(point: HifzPoint?): Boolean = point != null && drilledId == point.id
    fun hinted(point: HifzPoint?): Boolean = point != null && hintId == point.id
    fun assisted(point: HifzPoint?): Boolean = point != null && assistedId == point.id
    val tally: HifzTally get() = HifzTally(held, hesitated, again, heldNow)
    val graded: Int get() = tally.graded
    /** The day's answers, this review's with those before it. */
    val day: HifzTally get() = earlier + round
    /** This review's answers. */
    val round: HifzTally get() = tally
    /** The last grade, while its point is still in the review to be taken back. */
    fun undoable(queue: List<HifzPoint>): HifzGiven? = HifzGiven.decode(lastGiven)?.takeIf { given ->
        val shown = point(queue)
        queue.any { it.id == given.id } && (shown == null || shown.id == given.id || !(revealed(shown) || hinted(shown) || assisted(shown)))
    }
    /** The review shows what was answered: its last card is done, or the reader stopped it. */
    fun atEnd(queue: List<HifzPoint>): Boolean = point(queue) == null || stopping

    /** The points still asked, [queue] without those that wait for the next reminder. */
    fun questions(queue: List<HifzPoint>): List<HifzPoint> = if (deferredIds.isEmpty()) queue else queue.filterNot { it.id in deferredIds }

    /**
     * The round's questions as the reader will meet them: [queue] (already [questions]) up to the card in view, then
     * those after it whose answer has not been seen since, nor will be with the card in view or one asked before them;
     * the others are deferred when the review reaches them. For «سؤال 2 من 3» and for «التالي» or «نتيجة المراجعة».
     */
    fun count(queue: List<HifzPoint>): Int = kept(queue).size

    /** The questions from the card in view on that the reader has not answered yet, as [count] keeps them. */
    fun unanswered(queue: List<HifzPoint>): Int = kept(queue).drop(index(queue)).count { it.id !in gradedIds }

    private fun kept(queue: List<HifzPoint>): List<HifzPoint> {
        val at = index(queue)
        // What the card in view and those kept after it show, as the review reaches each, revealed or not yet.
        val showing = listOfNotNull(queue.getOrNull(at)).toMutableList()
        return queue.filterIndexed { i, point ->
            i <= at || (!spoiled(point) && showing.none { it.shows(point.verse) }).also { if (it) showing += point }
        }
    }

    /**
     * Notes the card drawn: a second tap meant for the button that was there a moment ago never acts on what
     * replaced it; see [settled].
     */
    fun shown(point: HifzPoint?) {
        beginAttempt(point)
        val key = listOf(point?.id, revealed(point), training(point), stopping)
        if (key != shownKey) {
            shownKey = key
            shownAt = clock()
        }
    }

    fun settled(): Boolean = clock() - shownAt >= 400L

    private fun beginAttempt(point: HifzPoint?) {
        if (point == null || attemptId == point.id) return
        attemptId = point.id
        attemptVerse = point.verse
    }

    /**
     * Its answer was looked at a moment ago, so a recall now would be none: a fresh point within [HifzFreshGapMs] of
     * its verse last seen, a spaced check whose verse was seen today.
     */
    private fun spoiled(point: HifzPoint): Boolean =
        if (point.fresh > 0) seenAt[point.verse]?.let { wallClock() - it < HifzFreshGapMs } == true else point.verse in exposedVerses

    /** The card at [from] in [queue], or the first after it still to ask; those passed wait for the next reminder. */
    private fun moveTo(queue: List<HifzPoint>, from: Int) {
        var at = from
        while (true) {
            val candidate = queue.getOrNull(at) ?: break
            if (candidate.id in deferredIds) { at++; continue }
            if (!spoiled(candidate)) break
            deferredIds = deferredIds + candidate.id
            at++
        }
        val next = queue.getOrNull(at)
        position = from - queue.take(from).count { it.id in deferredIds }
        cardId = next?.id
        beginAttempt(next)
    }

    /** Merge a restored day ledger before a new card starts, without rewriting an attempt already made. */
    fun seedExposed(verses: Set<QuranVerseReference>, seen: Map<QuranVerseReference, Long> = emptyMap()) {
        exposedVerses = exposedVerses + verses
        seenAt = mergeSeen(seenAt, seen)
    }

    /** New playback may have helped an attempt that was already open. */
    fun syncExposed(verses: Set<QuranVerseReference>, actions: HifzReviewActions, seen: Map<QuranVerseReference, Long> = emptyMap()) {
        seenAt = mergeSeen(seenAt, seen)
        noteExposed(verses - exposedVerses, actions)
    }

    /** A deleted notebook point leaves the review's lists. */
    fun forgetPoint(id: Long) {
        deferredIds = deferredIds - id
    }

    fun noteExposed(verses: Set<QuranVerseReference>, actions: HifzReviewActions) {
        if (verses.isEmpty()) return
        if (attemptVerse in verses && attemptId != null && revealedId != attemptId) assistedId = attemptId
        val added = verses - exposedVerses
        exposedVerses = exposedVerses + verses
        // A time seen within a minute stays: the eraser's every move is no new write.
        val at = wallClock()
        val moved = verses.filter { verse -> seenAt[verse]?.let { it > at - 60_000L } != true }.toSet()
        if (moved.isNotEmpty()) seenAt = seenAt + moved.associateWith { at }
        if (added.isNotEmpty()) actions.exposed(added)
        if (moved.isNotEmpty()) actions.seen(moved)
    }

    /** Another card without «التالي»: the one in view was deleted, and its audio stops with it. */
    fun follow(queue: List<HifzPoint>, actions: HifzReviewActions) {
        val index = index(queue)
        val point = queue.getOrNull(index)
        if (cardId != null && point?.id != cardId) actions.leaveCard()
        if (point?.id != cardId) folded = false
        if (point?.id == cardId) {
            // The card stays where it now is, for the one after it to take its place should it go.
            position = index
            beginAttempt(point)
        } else moveTo(queue, index)
    }

    fun reveal(queue: List<HifzPoint>, actions: HifzReviewActions) {
        val point = point(queue) ?: return
        if (!settled() || revealed(point)) return
        actions.leaveCard()
        revealedId = point.id
        folded = false
        noteExposed(setOf(point.verse), actions)
    }

    fun showHint(point: HifzPoint, actions: HifzReviewActions? = null) {
        hintId = point.id
        markAssisted(point)
        if (actions != null) noteExposed(setOf(point.verse), actions)
    }

    /** Called only when help actually exposes an answer before «أظهر», never for ordinary reveal. */
    fun markAssisted(point: HifzPoint) {
        if (!revealed(point)) assistedId = point.id
    }

    fun next(queue: List<HifzPoint>, actions: HifzReviewActions) {
        val index = index(queue)
        actions.leaveCard()
        trainingId = null
        folded = false
        moveTo(queue, index + 1)
    }

    fun grade(queue: List<HifzPoint>, actions: HifzReviewActions, grade: HifzGrade) {
        val shown = point(queue) ?: return
        if (!settled() || !revealed(shown) || training(shown)) return
        val answer = if (grade == HifzGrade.Held && assisted(shown)) HifzGrade.Hesitated else grade
        val given = HifzGiven.of(shown, answer)
        lastGiven = given.encode()
        lastGradeAssisted = assisted(shown)
        actions.grade(shown, answer)
        count(answer, 1)
        if (given.heldNow) heldNow++
        gradedIds = gradedIds - shown.id + shown.id
        if (answer != HifzGrade.Held) trainingId = shown.id else next(queue, actions)
    }

    fun undo(queue: List<HifzPoint>, actions: HifzReviewActions) {
        val given = undoable(queue) ?: return
        actions.leaveCard()
        actions.undo(given)
        count(given.grade, -1)
        if (given.heldNow) heldNow--
        gradedIds = gradedIds - given.id
        cardId = given.id
        attemptId = given.id
        attemptVerse = queue.firstOrNull { it.id == given.id }?.verse
        trainingId = null
        revealedId = given.id
        assistedId = if (lastGradeAssisted) given.id else null
        lastGiven = null
        folded = false
        stopping = false
    }

    fun drill(point: HifzPoint, actions: HifzReviewActions) {
        // A second tap on the grade lands here: it never starts the drill.
        if (!settled()) return
        drilledId = point.id
        actions.drill(point)
    }

    /**
     * «إنهاء», Back or a swipe: with nothing answered the review simply closes; otherwise what was answered shows
     * first, with «تابع الآن», and a second one leaves.
     */
    fun requestEnd(queue: List<HifzPoint>, actions: HifzReviewActions) {
        actions.leaveCard()
        if (atEnd(queue) || graded == 0) actions.dismiss() else {
            stopping = true
            folded = false
        }
    }

    /** «تابع الآن» after a stop: back to the card where the review stood. */
    fun resume() {
        stopping = false
    }

    private fun count(grade: HifzGrade, by: Int) = when (grade) {
        HifzGrade.Held -> held += by
        HifzGrade.Hesitated -> hesitated += by
        HifzGrade.Again -> again += by
    }

    companion object {
        val Saver: Saver<HifzReviewState, Any> = listSaver(
            save = { s ->
                listOf(
                    s.held, s.hesitated, s.again, s.heldNow, s.cardId, s.position, s.revealedId, s.trainingId, s.drilledId,
                    s.lastGiven, if (s.howItWorks) 1 else 0, if (s.folded) 1 else 0, s.earlier.encode(),
                    s.gradedIds.joinToString(","), if (s.stopping) 1 else 0, s.hintId, s.assistedId,
                    if (s.lastGradeAssisted) 1 else 0, s.exposedVerses.encodeReviewVerses(), s.attemptId,
                    setOfNotNull(s.attemptVerse).encodeReviewVerses(),
                    encodeSeenVerses(s.seenAt.filterValues { it > s.wallClock() - HifzFreshGapMs }),
                    s.deferredIds.joinToString(","),
                ).map { it?.toString().orEmpty() }
            },
            restore = {
                HifzReviewState(
                    it, SystemClock::uptimeMillis, earlier = HifzTally(), exposedVerses = emptySet(), seenVerses = emptyMap(),
                    wallClock = System::currentTimeMillis,
                )
            },
        )
    }
}

/** The day's review in a sheet: where the page cannot show it, with TalkBack, or with the page on its side. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HifzReviewSheet(
    state: HifzReviewState,
    catalog: QuranCatalog,
    lookAlikes: HifzLookAlikes?,
    queue: List<HifzPoint>,
    /** The recitation playing now, shown above the buttons since the sheet hides the player; null for none. */
    audio: HifzAudio?,
    audioControls: HifzAudioControls,
    /** Points still due once this review's are answered. */
    stillDue: Int,
    /** Days until the next check of any point, for the end; null without points. */
    nextCheckIn: Long?,
    actions: HifzReviewActions,
    /** When a fresh point answered now is asked again: «اليوم بعد المغرب»; see [HifzReminder.freshAgainLabel]. */
    freshAgain: String = "في تذكير لاحق",
    /** The day's answers, a point counted once a day; null for this review's own sum. */
    day: HifzTally? = null,
    /** When the next fresh point is asked, «اليوم بعد المغرب», when it comes before any spaced check; null for none. */
    nextFresh: String? = null,
    /** «غدًا بعد الفجر»: tomorrow's first reminder; see [HifzReminder.dayFirstLabel]. */
    tomorrow: String = "غدًا",
    /** «غدًا بعد الفجر»: the first reminder of the next spaced check's day; null to say its day only. */
    nextCheckLabel: String? = null,
    /** When each fresh point of the review is asked next, as the reminders will ask it, by its id; see [HifzReminder.nextAsk]. */
    nextFor: Map<Long, String> = emptyMap(),
) {
    val point = state.point(queue)
    state.shown(point)
    val contentScroll = rememberScrollState()
    LaunchedEffect(point?.id, state.revealed(point), state.training(point)) { contentScroll.scrollTo(0) }
    // A swipe down with answers given stops on what was answered first, as «إنهاء» does.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { value ->
        if (value == SheetValue.Hidden && !state.atEnd(queue) && state.graded > 0) {
            state.requestEnd(queue, actions)
            false
        } else true
    })
    ModalBottomSheet(onDismissRequest = { state.requestEnd(queue, actions) }, sheetState = sheetState, containerColor = BgCream) {
        BoxWithConstraints(Modifier.fillMaxWidth().quranSheetHeight().padding(horizontal = 16.dp).testTag("hifz_review")) {
          Column(Modifier.fillMaxSize()) {
            HifzReviewTitle(state, queue, point, actions)
            if (point == null || state.stopping) {
                HifzReviewEnd(
                    state, catalog, queue, stillDue, nextCheckIn, actions, Modifier.weight(1f).padding(vertical = 32.dp), day, nextFresh,
                    freshAgain, tomorrow, nextCheckLabel, nextFor,
                )
                return@Column
            }
            val fix = remember(point, lookAlikes) { hifzFix(catalog, point, lookAlikes) }
            val revealed = fix != null && state.revealed(point)
            // Once the answer is shown, the answers, or the training, come first: they are what this step asks. The
            // correction follows, to read.
            if (revealed) Column(
                Modifier.heightIn(max = this@BoxWithConstraints.maxHeight * 0.55f).verticalScroll(rememberScrollState()).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HifzReviewHeading(catalog, point)
                HifzReviewControls(
                    state, catalog, queue, point, audio, audioControls, actions, compact = false, freshAgain = freshAgain,
                    tomorrow = tomorrow, fix = fix, next = nextFor[point.id],
                )
            }
            Column(Modifier.weight(1f).verticalScroll(contentScroll).padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!revealed) HifzReviewHeading(catalog, point)
                if (fix == null) return@Column
                if (!state.revealed(point)) {
                    val talkBack = rememberTouchExploration()
                    Text(hifzReviewPageQuestion(catalog, point), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
                    val previous = point.verse.takeIf { it.ayah > 1 }?.let { QuranVerseReference(it.surah, it.ayah - 1) }
                    val inText: @Composable () -> Unit = {
                        Surface(shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, CardBorder)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                previous?.let {
                                    Text("الآية السابقة · ${it.ayah}", fontSize = 12.sp, color = TextMuted)
                                    HifzReviewCueText(catalog, it)
                                }
                                Text(
                                    if (previous == null) "أول آية في السورة · مغطاة" else "الآية التالية · مغطاة",
                                    fontSize = 12.sp, color = QuranHifzViolet,
                                )
                                HifzAnswerSlot()
                            }
                        }
                    }
                    // One representation of the question: the covered mushaf; text is solely the accessibility or
                    // missing-image fallback, never a duplicate below the image.
                    if (!talkBack) QuranVerseExcerpt(
                        catalog, point.verse, "${hifzVerseName(catalog, point.verse)} مغطاة في المصحف",
                        linesBefore = if (previous != null) 1 else 0, cover = true, fallback = inText,
                    )
                    if (talkBack) inText()
                    HifzReviewHint(state, point, actions)
                } else {
                    // The words to mind are said above the answers; a look-alike keeps its comparison here.
                    HifzFixBody(catalog, fix, lookAlikes, exact = point.kind.lookAlike, practice = false)
                    HifzReviewAnswerActions(state, point, actions)
                }
            }
            if (fix != null && !revealed) Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HifzReviewControls(state, catalog, queue, point, audio, audioControls, actions, compact = false, freshAgain = freshAgain, tomorrow = tomorrow, fix = null)
            }
          }
        }
    }
    if (state.howItWorks) HifzHowItWorksDialog { state.howItWorks = false }
}

/**
 * The day's review on the page itself: the reader opens each point's page, covers the verse asked and what follows it,
 * and this card floats over the foot of the page with the question and the answers. The cover erases under the
 * finger as in «مراجعة». The whole verse is recalled, with a prompt naming the recorded difficulty.
 */
@Composable
internal fun HifzReviewCard(
    state: HifzReviewState,
    catalog: QuranCatalog,
    lookAlikes: HifzLookAlikes?,
    queue: List<HifzPoint>,
    audio: HifzAudio?,
    audioControls: HifzAudioControls,
    stillDue: Int,
    nextCheckIn: Long?,
    actions: HifzReviewActions,
    /** When a fresh point answered now is asked again: «اليوم بعد المغرب»; see [HifzReminder.freshAgainLabel]. */
    freshAgain: String = "في تذكير لاحق",
    /** The day's answers, a point counted once a day; null for this review's own sum. */
    day: HifzTally? = null,
    /** When the next fresh point is asked, when it comes before any spaced check; null for none. */
    nextFresh: String? = null,
    /** The tallest the card grows; correction and actions share one scroll surface. */
    maxHeight: Dp,
    /**
     * «آخر الآية 35:»: the page cannot show the end of the verse before the one asked, and the card shows it under this
     * label; null when the page shows it.
     */
    beforeLabel: String?,
    /** The page's gestures are told once, until closed. */
    eraserHint: Boolean,
    onEraserHintClosed: () -> Unit,
    modifier: Modifier = Modifier,
    /** «غدًا بعد الفجر»: tomorrow's first reminder; see [HifzReminder.dayFirstLabel]. */
    tomorrow: String = "غدًا",
    /** «غدًا بعد الفجر»: the first reminder of the next spaced check's day; null to say its day only. */
    nextCheckLabel: String? = null,
    /** When each fresh point of the review is asked next, as the reminders will ask it, by its id; see [HifzReminder.nextAsk]. */
    nextFor: Map<Long, String> = emptyMap(),
) {
    val point = state.point(queue)
    state.shown(point)
    val contentScroll = rememberScrollState()
    LaunchedEffect(point?.id, state.revealed(point), state.training(point)) { contentScroll.scrollTo(0) }
    QuranFloatingCard(modifier.widthIn(max = 560.dp).testTag("hifz_review_card")) {
        Column(Modifier.heightIn(max = maxHeight).padding(start = 14.dp, end = 4.dp, bottom = 10.dp)) {
            HifzReviewTitle(state, queue, point, actions, fontSize = 13.sp) {
                IconButton(onClick = { state.howItWorks = true }, modifier = Modifier.size(36.dp).testTag("hifz_how_it_works")) {
                    Icon(painterResource(R.drawable.ic_adhkar_info), "كيف تعمل مراجعة اليوم؟", Modifier.size(18.dp), tint = GreenPrimaryDark)
                }
                IconButton(onClick = { state.folded = !state.folded }, modifier = Modifier.size(36.dp).testTag("hifz_review_fold")) {
                    Icon(
                        painterResource(if (state.folded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                        if (state.folded) "أظهر بطاقة المراجعة" else "اطوِ البطاقة لترى الصفحة", Modifier.size(22.dp), tint = GreenPrimaryDark,
                    )
                }
            }
            if (state.folded) return@Column
            if (point == null || state.stopping) {
                HifzReviewEnd(
                    state, catalog, queue, stillDue, nextCheckIn, actions, Modifier.padding(end = 10.dp, top = 4.dp, bottom = 6.dp), day, nextFresh,
                    freshAgain, tomorrow, nextCheckLabel, nextFor,
                )
                return@Column
            }
            val fix = remember(point, lookAlikes) { hifzFix(catalog, point, lookAlikes) }
            val revealed = fix != null && state.revealed(point)
            // The verse and its slip, then, once its answer is shown, the answers or the training first in what
            // scrolls, before anything to read: they are what this step asks, in view as the card opens on them, and
            // never squeezed out of a card that has reached its height.
            Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HifzReviewHeading(catalog, point, onPage = true)
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(contentScroll).padding(end = 10.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (fix == null) return@Column
                if (!state.revealed(point)) {
                    Text(hifzReviewPageQuestion(catalog, point), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
                    // What the reader goes on from, when the page cannot show it.
                    if (beforeLabel != null && point.verse.ayah > 1) {
                        val previous = QuranVerseReference(point.verse.surah, point.verse.ayah - 1)
                        Text("الآية السابقة · ${previous.ayah}", fontSize = 12.sp, color = TextMuted)
                        QuranVerseExcerpt(
                            catalog, previous, "الآية السابقة · ${hifzVerseName(catalog, previous)}",
                            fallback = { HifzReviewCueText(catalog, previous, fontSize = 18.sp) },
                        )
                    }
                    HifzReviewHint(state, point, actions)
                    if (eraserHint) Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AdhkarSoftGreen).padding(start = 10.dp).testTag("hifz_review_eraser_hint"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("المس الغطاء لتكشف كلمة، واسحب فوقه لتكشف أكثر. لتقليب الصفحة، اسحب من الهامش.", Modifier.weight(1f), fontSize = 12.sp, color = GreenPrimaryDark)
                        IconButton(onClick = onEraserHintClosed, modifier = Modifier.size(36.dp)) {
                            Icon(painterResource(R.drawable.ic_adhkar_close), "إخفاء التلميح", Modifier.size(14.dp), tint = GreenPrimaryDark)
                        }
                    }
                } else {
                    HifzReviewControls(
                        state, catalog, queue, point, audio, audioControls, actions, compact = true, freshAgain = freshAgain,
                        tomorrow = tomorrow, fix = fix, next = nextFor[point.id],
                    )
                    // The verse is on the page now; the card adds only what the page cannot show: where the look-alike
                    // parts from it, the words to mind. The look-alike's page opens on demand.
                    HifzFixBody(catalog, fix, lookAlikes, ownOnPage = false, otherFolded = true, exact = point.kind.lookAlike, practice = false)
                    HifzReviewAnswerActions(state, point, actions)
                }
            }
            // «أظهر» stays in reach under the question, however long.
            if (fix != null && !revealed) Column(Modifier.padding(end = 10.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HifzReviewControls(state, catalog, queue, point, audio, audioControls, actions, compact = true, freshAgain = freshAgain, tomorrow = tomorrow, fix = null)
            }
        }
    }
    if (state.howItWorks) HifzHowItWorksDialog { state.howItWorks = false }
}

/** A full preceding verse anchors recall even when a scan is missing or TalkBack uses text. */
@Composable
private fun HifzReviewCueText(catalog: QuranCatalog, verse: QuranVerseReference, fontSize: TextUnit = 19.sp) {
    val words = remember(catalog, verse) { catalog.verse(verse)?.text?.let(::hifzWords) } ?: return
    val onExposure by rememberUpdatedState(LocalQuranExposure.current)
    var visible by remember(verse) { mutableStateOf(false) }
    LaunchedEffect(verse, visible) { if (visible) onExposure(setOf(verse)) }
    HifzVerseText(words, fontSize = fontSize, modifier = Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = bounds.width > 0f && bounds.height > 0f
    })
}

/**
 * The question is a continuation from visible context; a verse number alone is not a recall cue. A word slip names
 * what was recorded: one word, several, or none chosen yet.
 */
internal fun hifzReviewPageQuestion(catalog: QuranCatalog, point: HifzPoint): String {
    val begin = if (point.verse.ayah > 1) "أكمل من حفظك بعد الآية السابقة"
        else "اقرأ من حفظك أول آية من سورة ${catalog.surahs.getOrNull(point.verse.surah - 1)?.name.orEmpty()}"
    val attention = when (point.kind) {
        HifzKind.Ending -> "، وانتبه إلى خاتمتها"
        HifzKind.Similar -> " دون الانتقال إلى شبيهتها"
        HifzKind.Word -> when {
            // Its opening forgotten: the verse before leads into it.
            0 in point.slipWords -> "، واستحضر أولها ثم أكملها"
            point.slipWords.isEmpty() -> "، وانتبه إلى الموضع الذي أخطأت فيه"
            point.slipWords.size == 1 -> "، وانتبه إلى الكلمة التي أخطأت فيها"
            else -> "، وانتبه إلى الكلمات التي أخطأت فيها"
        }
        HifzKind.Skip -> if (point.verse.ayah > 1) "، ولا تتخطَّ الآية ${point.verse.ayah}" else ""
        HifzKind.Start -> "، واستحضر بدايتها ثم أكملها"
    }
    return "$begin$attention."
}

/**
 * «سؤال 3 من 5», one title for the whole question, its answer and its drill: the step is said by what the card asks
 * under it. The undo of the last answer, «تراجع», beside the end of the review, whose own card holds the undo.
 */
@Composable
private fun HifzReviewTitle(
    state: HifzReviewState,
    queue: List<HifzPoint>,
    point: HifzPoint?,
    actions: HifzReviewActions,
    fontSize: TextUnit = 18.sp,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val atEnd = state.atEnd(queue)
    Column(Modifier.fillMaxWidth()) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // «سؤال 2 من 5»; a review of one question is not numbered.
        val count = state.count(queue)
        Text(
            when {
                atEnd -> "المراجعة"
                point == null || count < 2 -> "سؤال"
                else -> "سؤال ${state.index(queue) + 1} من $count"
            },
            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark,
        )
        if (!atEnd && state.undoable(queue) != null) TextButton(
            onClick = { state.undo(queue, actions) }, contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.testTag("hifz_review_undo").semantics { contentDescription = "تراجع عن آخر جواب" },
        ) { Text("تراجع") }
        if (!atEnd) TextButton(
            onClick = { state.requestEnd(queue, actions) }, contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.testTag("hifz_review_end"),
        ) { Text("إنهاء") }
        trailing()
      }
    }
}

/** The verse asked, with its state, and the slip recorded there. [onPage]: the page shows the verse in the points' colour. */
@Composable
private fun HifzReviewHeading(catalog: QuranCatalog, point: HifzPoint, onPage: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onPage) {
            QuranHifzSquare(12.dp)
            Spacer(Modifier.width(8.dp))
        }
        // On the page, the page is in view: the verse and the kind of slip are enough, on one line.
        Text(
            hifzPointTitle(catalog, point),
            Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark,
        )
        when {
            point.mastered -> Text("ثبتت · مراجعة للتأكد", fontSize = 12.sp, color = GreenPrimary)
            point.needsCare -> Text("يحتاج عناية", fontSize = 12.sp, color = QuranHifzViolet)
        }
    }
    if (!onPage) Text("الخطأ الذي سجّلته: ${point.kind.label}", fontSize = 12.sp, color = TextMuted)
}

/**
 * Before «أظهر», the reader's «ضابطي» waits behind a tap: shown first it would be a hint, and «أتقنتها» would then mean
 * «with my hint».
 */
@Composable
private fun HifzReviewHint(state: HifzReviewState, point: HifzPoint, actions: HifzReviewActions) {
    if (point.key.isBlank()) return
    if (state.hinted(point)) Text("ضابطي: ${point.key}", fontSize = 13.sp, color = QuranHifzViolet)
    else TextButton(onClick = { state.showHint(point, actions) }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("hifz_review_hint")) {
        Text("تلميح: اعرض ضابطي", fontSize = 13.sp, color = QuranHifzViolet)
    }
}

/**
 * After «أظهر»: the right wording heard, and the whole fix, in one quiet row under the answers. Not in the drill,
 * whose «استمع وكرّر» plays the right wording already.
 */
@Composable
private fun HifzReviewAnswerActions(state: HifzReviewState, point: HifzPoint, actions: HifzReviewActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!state.training(point)) TextButton(onClick = { if (state.settled()) actions.listen(point) }, modifier = Modifier.testTag("hifz_review_listen")) {
            Text("استمع للصواب", maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        // A second tap on «أظهر», which was where this row now is, never opens the correction over the review.
        TextButton(onClick = { if (state.settled()) actions.fix(point) }) { Text("التصحيح كاملًا") }
    }
}

/**
 * The right wording to mind, before the answers: the words that were missed, the ending, where the verse parts from
 * its look-alike, in the mushaf's script, the words to mind marked. The evidence is read before the verdict is given.
 */
@Composable
private fun HifzAnswerLine(fix: HifzFix) {
    val words = fix.words
    val shown = fix.wordingWords.filter { it in 0 until words.size }
    if (shown.isEmpty()) return
    val label = when (fix.point.kind) {
        HifzKind.Word -> "الصواب"
        HifzKind.Ending -> "خاتمتها"
        HifzKind.Similar -> fix.wordingLabel
        HifzKind.Skip, HifzKind.Start -> "أولها"
    }
    val text = remember(fix) {
        buildAnnotatedString {
            if (shown.first() > 0) append("… ")
            shown.forEachIndexed { n, i ->
                if (n > 0) append(if (i > shown[n - 1] + 1) " … " else " ")
                if (i in fix.stressed) withStyle(SpanStyle(color = GreenPrimary, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)) {
                    append(words.printed[i])
                } else append(words.printed[i])
            }
            if (fix.point.kind != HifzKind.Ending && shown.last() < words.size - 1) append(" …")
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(horizontal = 10.dp, vertical = 2.dp)
            .testTag("hifz_answer_line"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(withQuranQuotes("$label:"), fontSize = 12.sp, color = TextMuted)
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f), fontFamily = QuranMushafFont, fontSize = 20.sp, lineHeight = 34.sp, color = TextDark)
    }
}

/**
 * Under the card: what plays, then «أظهر», the answers, or the drill of a verse that still needs it. [compact], on the
 * page: the three answers side by side, so the verse above keeps its room.
 */
@Composable
private fun ColumnScope.HifzReviewControls(
    state: HifzReviewState,
    catalog: QuranCatalog,
    queue: List<HifzPoint>,
    point: HifzPoint,
    audio: HifzAudio?,
    audioControls: HifzAudioControls,
    actions: HifzReviewActions,
    compact: Boolean,
    freshAgain: String,
    /** «غدًا بعد الفجر»: the first reminder of tomorrow, where a spaced check or a once-a-day question comes. */
    tomorrow: String,
    /** The right wording, shown once the answer is, before the answers: what to judge by. */
    fix: HifzFix?,
    /** When this point, answered, is asked next, as the reminders will ask it; null before its answer. */
    next: String? = null,
) {
    if (audio != null) HifzAudioStrip(audio, audioControls)
    when {
        !state.revealed(point) -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (point.verse.ayah > 1) OutlinedButton(
                onClick = { if (state.settled()) actions.listenBefore(point) },
                // Two lines rather than a label cut short, with large text or on a narrow phone.
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).testTag("hifz_review_cue"),
            ) { Text("استمع إلى ما قبلها", maxLines = 2, textAlign = TextAlign.Center) }
            Button(
                onClick = { state.reveal(queue, actions) },
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.weight(1f).testTag("hifz_reveal"),
            ) { Text("أظهر") }
        }
        state.training(point) -> {
            // The answer just given has already set when it comes back: the drill is to hear the right wording now,
            // and the next reminder is the retest.
            Text(
                if (point.fresh > 0) "نسألك عنها ${next ?: freshAgain}." else "نسألك عنها ${hifzWhen(point.dueDay - todayEpochDay())}.",
                fontSize = 13.sp, color = GreenPrimaryDark, modifier = Modifier.testTag("hifz_review_training"),
            )
            // The drill is the step this card asks: filled until it was played, then the way on is.
            val drilled = state.drilled(point)
            val drill: @Composable ColumnScope.() -> Unit = {
                Text(if (drilled) "استمع وكرّر مجددًا" else "استمع وكرّر", fontWeight = FontWeight.SemiBold)
                Text(
                    if (compact) hifzCompactDrillLabel(point, hifzDrillVerses(point, catalog.surahs.getOrNull(point.verse.surah - 1)?.verseCount).size, HifzDrillGap)
                    else hifzDrillLabel(point, catalog.surahs.getOrNull(point.verse.surah - 1)?.verseCount, HifzDrillGap) { catalog.surahs.getOrNull(it - 1)?.name.orEmpty() },
                    fontSize = 12.sp, textAlign = TextAlign.Center,
                )
            }
            val drillModifier = Modifier.fillMaxWidth().testTag("hifz_review_drill")
            if (!drilled) Button(
                onClick = { state.drill(point, actions) }, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = drillModifier,
            ) { Column(horizontalAlignment = Alignment.CenterHorizontally, content = drill) }
            else OutlinedButton(onClick = { state.drill(point, actions) }, modifier = drillModifier) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, content = drill)
            }
            // «انتهيت» at the last question: it leads to what was answered, which ends the review.
            val nextLabel = if (state.index(queue) + 1 < state.count(queue)) "التالي" else "انتهيت"
            val nextModifier = Modifier.fillMaxWidth().testTag("hifz_review_next")
            if (drilled) Button(
                onClick = { if (state.settled()) state.next(queue, actions) },
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = nextModifier,
            ) { Text(nextLabel) }
            else OutlinedButton(onClick = { if (state.settled()) state.next(queue, actions) }, modifier = nextModifier) { Text(nextLabel) }
        }
        else -> {
            fix?.let { HifzAnswerLine(it) }
            Text("هل تذكّرتها قبل أن تكشفها؟", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
            if (state.assisted(point)) Text(
                "استعنت بتلميح؛ تُسجّل هذه المحاولة بمساعدة.",
                fontSize = 12.sp, color = QuranHifzViolet,
            )
            val grades = if (state.assisted(point)) listOf(HifzGrade.Hesitated, HifzGrade.Again) else HifzGrade.entries.toList()
            fun label(grade: HifzGrade): String = when (grade) {
                HifzGrade.Held -> "دون مساعدة"
                HifzGrade.Hesitated -> if (state.assisted(point)) "تذكّرتها بالمساعدة" else "بتردد أو تلميح"
                HifzGrade.Again -> if (state.assisted(point)) "لم أتذكّرها" else "نسيت أو أخطأت"
            }
            val outcomes = grades.associateWith { hifzGradeOutcome(point, it, freshAgain, tomorrow, short = compact) }
            // The same time on every answer is said once, under them; each answer then says only what it does.
            val shared = outcomes.values.map { it.back }.distinct().singleOrNull()
            fun detail(grade: HifzGrade): String = outcomes.getValue(grade).text(withBack = shared == null, short = compact)
            val sideBySide = compact && LocalDensity.current.fontScale <= 1.3f
            if (sideBySide) Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                grades.forEach { grade ->
                    HifzGradeChip(label(grade), detail(grade), grade, tag = "hifz_grade_${grade.name.lowercase()}", modifier = Modifier.weight(1f)) {
                        state.grade(queue, actions, grade)
                    }
                }
            } else {
                grades.forEach { grade ->
                    HifzGradeButton(label(grade), detail(grade), grade, tag = "hifz_grade_${grade.name.lowercase()}") { state.grade(queue, actions, grade) }
                }
            }
            if (shared != null) Text(
                "نسألك عنها $shared أيًّا كان جوابك.", fontSize = 12.sp, color = TextMuted,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("hifz_grade_shared_back"),
            )
            if (!compact) TextButton(onClick = { state.howItWorks = true }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("hifz_how_it_works")) {
                Text("كيف تعمل مراجعة اليوم؟", fontSize = 13.sp)
            }
        }
    }
}

/**
 * The verse references are already in view: what the drill plays, said as a sentence beside its action, [verses] being
 * how many it plays (see [hifzDrillVerses]).
 */
private fun hifzCompactDrillLabel(point: HifzPoint, verses: Int, gap: Float): String {
    val repeat = if (gap > 0f) "، وتردّد بعد كل آية" else ""
    return when {
        point.other != null && (point.kind == HifzKind.Ending || point.kind == HifzKind.Similar) -> "تسمع الموضعين بالتناوب 5${NB}مرات$repeat"
        verses == 2 -> "تسمع الآيتين متصلتين 3${NB}مرات$repeat"
        verses > 2 -> "تسمع الآيات متصلة 3${NB}مرات$repeat"
        else -> "تسمع الآية 3${NB}مرات" + if (gap > 0f) "، وتردّدها بعد كل مرة" else ""
    }
}

/**
 * Where an answered point stands now, for the end of the review, in sentences: «الملك 2: بقي أن تتذكّرها مرتين، ونسألك
 * عنها غدًا الساعة 15:35.», the step its last right answer reached, «الملك 3: تذكّرتها دون مساعدة. نسألك عنها غدًا بعد
 * الفجر، ثم تقلّ الأسئلة.», or its next spaced check. [next]: the time it names, to say it once when the review's own
 * next question is the same.
 */
@Immutable
internal data class HifzRoundLine(val text: String, val next: String?, val milestone: Boolean)

internal fun hifzRoundLine(
    catalog: QuranCatalog, point: HifzPoint, today: Long, again: String, tomorrow: String,
    /** When the reminders ask this point next, from its own answer and its verse last seen; see [HifzReminder.nextAsk]. */
    own: String? = null,
): HifzRoundLine {
    val name = hifzVerseName(catalog, point.verse)
    val last = point.checks.lastOrNull()
    return when {
        point.fresh > 0 -> {
            val next = own ?: if (point.daily) tomorrow else again
            HifzRoundLine("$name: بقي أن تتذكّرها ${HifzReminder.recallsPhrase(point.fresh)}، ونسألك عنها $next.", next, milestone = false)
        }
        // Its daily questions ended with this answer: the step it reached is said, and when it comes back.
        point.step == 0 && last?.day == today && last.grade == HifzGrade.Held -> HifzRoundLine(
            "$name: تذكّرتها ${if (point.recalls == 1) "" else HifzReminder.recallsPhrase(point.recalls) + " "}دون مساعدة. نسألك عنها $tomorrow، ثم تقلّ الأسئلة.",
            tomorrow, milestone = true,
        )
        point.mastered && point.masteredAt?.let { epochDay(it) } == today ->
            HifzRoundLine("$name: ثبتت. نسألك عنها ${hifzWhen(point.dueDay - today)} للتأكد.", null, milestone = true)
        else -> HifzRoundLine("$name: نسألك عنها ${hifzWhen(point.dueDay - today)}.", null, milestone = false)
    }
}

/**
 * What was answered: at the end of the review, or when it is stopped early, which keeps «تابع الآن». Each point
 * answered says where it stands, so that the right answers given show as progress and the last one as the step it is;
 * the day's count adds up every review of the day. The failed points can be asked once more, what was reviewed can be
 * heard now that the checks are done, and the last answer taken back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HifzReviewEnd(
    state: HifzReviewState,
    catalog: QuranCatalog,
    queue: List<HifzPoint>,
    stillDue: Int,
    nextCheckIn: Long?,
    actions: HifzReviewActions,
    modifier: Modifier = Modifier,
    /** The day's answers, a point counted once a day, a fresh one asked at several reminders too. */
    dayTally: HifzTally? = null,
    nextFresh: String? = null,
    freshAgain: String = "في تذكير لاحق",
    tomorrow: String = "غدًا",
    nextCheckLabel: String? = null,
    nextFor: Map<Long, String> = emptyMap(),
) {
    val stopped = state.stopping && state.point(queue) != null
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val day = dayTally ?: state.day
        val remaining = state.unanswered(queue)
        val today = todayEpochDay()
        val answered = state.gradedIds.mapNotNull { id -> queue.firstOrNull { it.id == id } }
        val lines = remember(answered, today, freshAgain, tomorrow, nextFor) {
            answered.map { hifzRoundLine(catalog, it, today, freshAgain, tomorrow, nextFor[it.id]) }
        }
        // What is said is judged by what shows: the first three lines.
        val shown = lines.take(3)
        when {
            stopped -> {
                Text("توقفت المراجعة", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                Text("حُفظت إجاباتك: ${hifzCountLabel(state.graded)}", fontSize = 14.sp, color = TextDark)
                Text("لم تُسأل عنها بعد في هذه الجولة: ${hifzCountLabel(remaining)}", fontSize = 14.sp, color = TextMuted)
                if (state.training(state.point(queue))) Text("يمكنك متابعة التدريب على الموضع الحالي.", fontSize = 13.sp, color = TextMuted)
            }
            day.graded > 0 -> {
                Text("بارك الله فيك", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                // Where each point answered now stands, the first few; the day's count when there were several.
                shown.forEach { line ->
                    Text(
                        line.text, fontSize = 14.sp, textAlign = TextAlign.Center,
                        fontWeight = if (line.milestone) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (line.milestone) GreenPrimary else TextDark, modifier = Modifier.testTag("hifz_review_progress"),
                    )
                }
                if (lines.size > 3) Text("وغيرها: ${hifzCountLabel(lines.size - 3)}", fontSize = 13.sp, color = TextMuted)
                // The day in a sentence: «راجعت اليوم 7 مواضع، تذكّرت منها 5 مواضع دون مساعدة، ونسيت موضعين.»
                if (day.graded > 1) Text(
                    "راجعت اليوم ${HifzReminder.pointsObject(day.graded)}، " + if (listOf(day.held, day.hesitated, day.again).count { it > 0 } == 1) {
                        val them = if (day.graded == 2) "هما" else "ها كلها"
                        when {
                            day.held > 0 -> "وتذكّرت$them دون مساعدة."
                            day.hesitated > 0 -> "وتردّدت في$them."
                            else -> "ونسيت$them."
                        }
                    } else listOfNotNull(
                        "تذكّرت منها ${HifzReminder.pointsObject(day.held)} دون مساعدة".takeIf { day.held > 0 },
                        "تردّدت في ${HifzReminder.pointsAbout(day.hesitated)}".takeIf { day.hesitated > 0 },
                        "نسيت ${HifzReminder.pointsObject(day.again)}".takeIf { day.again > 0 },
                    ).mapIndexed { i, part -> if (i == 0) part else "و$part" }.joinToString("، ") + ".",
                    fontSize = 13.sp, color = TextMuted, textAlign = TextAlign.Center,
                )
                if (day.heldNow > 0 && shown.none { it.milestone && it.next == null }) Text("ثبت منها اليوم: ${hifzCountLabel(day.heldNow)}", fontSize = 14.sp, color = GreenPrimary)
            }
            else -> Text(if (stillDue > 0) "لم يبق موضع في هذه المراجعة" else "لا أسئلة الآن", fontSize = 16.sp, color = GreenPrimaryDark)
        }
        // Points whose verse was shown in this review, by another point's answer: asked at the next reminder.
        val deferred = state.deferredIds.size
        if (!stopped && deferred > 0) Text(
            if (deferred == 1) "موضع واحد ظهرت آيته في هذه المراجعة؛ نسألك عنه في تذكير لاحق."
            else "${hifzCountLabel(deferred)} ظهرت آياتها في هذه المراجعة؛ نسألك عنها في تذكير لاحق.",
            fontSize = 13.sp, color = TextMuted, textAlign = TextAlign.Center,
        )
        if (!stopped) {
            // The review's next question, unless a point's line says that very time already.
            val next = when {
                stillDue > 0 -> null
                nextFresh != null -> nextFresh
                nextCheckIn != null -> nextCheckLabel ?: hifzWhen(nextCheckIn)
                else -> null
            }
            if (stillDue > 0) Text("وبقي لك الآن ${hifzCountLabel(stillDue)}.", fontSize = 14.sp, color = TextDark)
            else if (next != null && shown.none { it.next == next }) Text("السؤال القادم $next", fontSize = 14.sp, color = TextMuted)
        }
        if (stopped) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = state::resume, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.testTag("hifz_review_resume")) {
                    Text("تابع الآن")
                }
                OutlinedButton(onClick = actions::dismiss, modifier = Modifier.testTag("hifz_review_later")) { Text("لاحقًا") }
                // An answer given by mistake can still be taken back from here.
                if (state.undoable(queue) != null) TextButton(onClick = { state.undo(queue, actions) }, modifier = Modifier.testTag("hifz_review_undo")) {
                    Text("تراجع عن آخر جواب")
                }
            }
            return@Column
        }
        // The way on is the widest: more questions when some are due, else back to the reading.
        if (stillDue > 0) Button(
            onClick = actions::more, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
            modifier = Modifier.fillMaxWidth().testTag("hifz_review_more"),
        ) { Text("تابع مراجعات اليوم") }
        else Button(
            onClick = actions::dismiss, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
            modifier = Modifier.fillMaxWidth().testTag("hifz_review_done"),
        ) { Text("تم") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (state.gradedIds.isNotEmpty()) TextButton(onClick = { actions.listenTo(state.gradedIds) }, modifier = Modifier.testTag("hifz_review_listen_done")) {
                Text("استمع إلى ما راجعته")
            }
            if (stillDue > 0) TextButton(onClick = actions::dismiss, modifier = Modifier.testTag("hifz_review_done")) { Text("لاحقًا") }
            if (state.undoable(queue) != null) TextButton(onClick = { state.undo(queue, actions) }, modifier = Modifier.testTag("hifz_review_undo")) {
                Text("تراجع عن آخر جواب")
            }
        }
    }
}

/** The blank the reader fills aloud: a dashed box, never dots that read like a cut verse. */
@Composable
private fun HifzAnswerSlot() {
    Box(
        Modifier.fillMaxWidth().height(52.dp).testTag("hifz_answer_slot").drawBehind {
            val width = 1.5.dp.toPx()
            val corner = CornerRadius(10.dp.toPx())
            drawRoundRect(QuranHifzViolet.copy(alpha = 0.06f), cornerRadius = corner)
            drawRoundRect(
                QuranHifzViolet, Offset(width / 2, width / 2), Size(size.width - width, size.height - width), corner,
                style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
            )
        },
        contentAlignment = Alignment.Center,
    ) { Text("؟", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = QuranHifzViolet) }
}

/**
 * What an answer does to a point ([effect], none when it only comes back later), and when the verse comes back
 * after it ([back]): «اليوم بعد المغرب», «غدًا بعد الفجر», «بعد 3 أيام». Apart, so that a time every answer shares is
 * said once.
 */
@Immutable
internal data class HifzGradeOutcome(val effect: String?, val back: String) {
    /**
     * With [withBack], the time too: alone on its own line for the answers side by side ([short]), else as a sentence,
     * «…، ونسألك عنها اليوم بعد المغرب».
     */
    fun text(withBack: Boolean, short: Boolean): String = when {
        !withBack -> effect.orEmpty()
        short -> listOfNotNull(effect, back).joinToString("\n")
        effect == null -> "نسألك عنها $back"
        else -> "$effect، ونسألك عنها $back"
    }
}

/**
 * What answering [grade] means for [point]. A fresh point is asked again [again], «اليوم بعد المغرب», or, asked once a
 * day, [tomorrow], «غدًا بعد الفجر», until its last right answer spaces its questions out from [tomorrow]; a failed
 * answer makes any point fresh again, with more right answers when the mistake came back, and says why. [short]: in a
 * few words, for the answers side by side.
 */
internal fun hifzGradeOutcome(
    point: HifzPoint, grade: HifzGrade, again: String = "في تذكير لاحق", tomorrow: String = "غدًا", short: Boolean = false,
): HifzGradeOutcome {
    // [again] is the point's own next reminder, at its own times, once a day on another day.
    val next = again
    if (grade == HifzGrade.Again) {
        val count = point.recallsAfterAgain
        // A count that grows is said with its reason: it is no penalty, the mistake came back.
        val raised = !point.mastered && count > point.recalls
        val effect = when {
            point.mastered -> if (short) "تخرج من «ثبتت»" else "تخرج من «ثبتت»، ونذكّرك بها من جديد إلى أن تتذكّرها ${HifzReminder.recallsPhrase(count)} دون مساعدة"
            point.restartsOnAgain -> if (short) "من جديد: ${hifzRecallsLabel(count)}" + (if (raised) "\nلأن الخطأ عاد" else "")
                else "نذكّرك بها من جديد إلى أن تتذكّرها ${HifzReminder.recallsPhrase(count)} دون مساعدة" + (if (raised) " بدل ${point.recalls}، لأن الخطأ عاد" else "")
            else -> "خطأ آخر"
        }
        return HifzGradeOutcome(effect, next)
    }
    if (point.fresh > 0) return when {
        grade == HifzGrade.Hesitated -> HifzGradeOutcome("لا تُحسب", next)
        point.spacesNext -> HifzGradeOutcome(if (short) "بعدها تقلّ الأسئلة" else "هذه الأخيرة: بعدها تقلّ الأسئلة", tomorrow)
        else -> HifzGradeOutcome(if (short) "تبقى ${hifzRecallsLabel(point.fresh - 1)}" else hifzFreshLeft(point.fresh - 1), next)
    }
    val back = hifzWhen(point.daysAfter(grade).toLong())
    return when (grade) {
        HifzGrade.Held -> when {
            point.holdsNext -> HifzGradeOutcome("تثبت", "$back للتأكد")
            point.mastered -> HifzGradeOutcome(null, "$back للتأكد")
            else -> HifzGradeOutcome(null, back)
        }
        else -> HifzGradeOutcome("تخرج من «ثبتت»".takeIf { point.mastered && point.step - 1 < HifzMasteredStep }, back)
    }
}

/** [hifzGradeOutcome] said whole, with its time: «بقي أن تتذكّرها مرة واحدة، ونسألك عنها اليوم بعد المغرب», «نسألك عنها بعد 3 أيام». */
internal fun hifzGradeDetail(point: HifzPoint, grade: HifzGrade, again: String = "في تذكير لاحق", tomorrow: String = "غدًا"): String =
    hifzGradeOutcome(point, grade, again, tomorrow).text(withBack = true, short = false)

/**
 * [hifzGradeDetail] in a few words, for the answers side by side: what the answer does over when the verse comes
 * back: «تبقى مرتان» over «اليوم بعد المغرب», «من جديد: 4 مرات», «لا تُحسب», «خطأ آخر», «بعد 3 أيام».
 */
internal fun hifzGradeShort(point: HifzPoint, grade: HifzGrade, again: String = "في تذكير لاحق", tomorrow: String = "غدًا"): String =
    hifzGradeOutcome(point, grade, again, tomorrow, short = true).text(withBack = true, short = true)

/** «بقي أن تتذكّرها مرتين»: the right answers [left] still needed after this one, said as what is left to do. */
private fun hifzFreshLeft(left: Int): String = "بقي أن تتذكّرها ${HifzReminder.recallsPhrase(left)}"

/**
 * «نسيت» now starts the right answers over: it sets back counted progress (a right answer of the fresh phase, or a step
 * of the spaced checks), or, counted as the slip coming back, asks for more of them than the plan did. Only a point
 * whose count stays as it was, never answered right since its slip, just adds a slip («خطأ آخر»).
 */
private val HifzPoint.restartsOnAgain: Boolean get() = step > 0 || fresh in 1 until recalls || (fresh == 0 && step == 0) ||
    recallsAfterAgain != recalls

/**
 * Each answer in its own light tone, read at a glance and by more than its colour, its name being said: remembered
 * green, hesitated plain, forgotten warm.
 */
@Immutable
private data class HifzGradeTone(val container: Color, val border: Color, val content: Color)

private fun hifzGradeTone(grade: HifzGrade): HifzGradeTone = when (grade) {
    HifzGrade.Held -> HifzGradeTone(AdhkarSoftGreen, GreenPrimary.copy(alpha = 0.55f), GreenPrimaryDark)
    HifzGrade.Hesitated -> HifzGradeTone(Color.White, CardBorder, TextDark)
    HifzGrade.Again -> HifzGradeTone(BannerBg, BannerStroke.copy(alpha = 0.75f), Color(0xFF7A3B12))
}

/** A grade: its name, and under it what it means and, unless every answer shares it, when the verse comes back. */
@Composable
private fun HifzGradeButton(label: String, detail: String, grade: HifzGrade, tag: String, onClick: () -> Unit) {
    val tone = hifzGradeTone(grade)
    val padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    val content: @Composable RowScope.() -> Unit = {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            if (detail.isNotEmpty()) Text(detail, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
    OutlinedButton(
        onClick = onClick, contentPadding = padding, border = BorderStroke(1.dp, tone.border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = tone.container, contentColor = tone.content),
        modifier = Modifier.fillMaxWidth().testTag(tag), content = content,
    )
}

/** A grade side by side with the others, on the page's card: its name and, under it, what it does. */
@Composable
private fun HifzGradeChip(label: String, detail: String, grade: HifzGrade, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val tone = hifzGradeTone(grade)
    val padding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
    val content: @Composable RowScope.() -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
            // Whole, however many lines: the answers side by side grow together.
            if (detail.isNotEmpty()) Text(detail, fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
        }
    }
    OutlinedButton(
        onClick = onClick, contentPadding = padding, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, tone.border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = tone.container, contentColor = tone.content),
        modifier = modifier.fillMaxHeight().testTag(tag), content = content,
    )
}
