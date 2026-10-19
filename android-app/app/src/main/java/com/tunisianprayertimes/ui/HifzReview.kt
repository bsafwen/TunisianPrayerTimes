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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.tunisianprayertimes.quran.hifz.daysAfter
import com.tunisianprayertimes.quran.hifz.decodeHifzChecks
import com.tunisianprayertimes.quran.hifz.encodeHifzChecks
import com.tunisianprayertimes.quran.hifz.hifzFix
import com.tunisianprayertimes.quran.hifz.hifzWords
import com.tunisianprayertimes.quran.hifz.holdsNext
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
) {
    fun encode(): String = listOf(id, grade.name, step, dueDay, masteredAt ?: "", if (heldNow) 1 else 0, slips, lastSlipAt, encodeHifzChecks(checks), fresh).joinToString("|")

    companion object {
        fun of(point: HifzPoint, grade: HifzGrade) = HifzGiven(
            point.id, grade, point.step, point.dueDay, point.masteredAt, grade == HifzGrade.Held && point.holdsNext, point.slips, point.lastSlipAt, point.checks,
            point.fresh,
        )

        fun decode(value: String?): HifzGiven? {
            val parts = value?.split('|')?.takeIf { it.size in 8..10 } ?: return null
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
                HifzReviewEnd(state, queue, stillDue, nextCheckIn, actions, Modifier.weight(1f).padding(vertical = 32.dp), day, nextFresh)
                return@Column
            }
            val fix = remember(point, lookAlikes) { hifzFix(catalog, point, lookAlikes) }
            Column(Modifier.weight(1f).verticalScroll(contentScroll).padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HifzReviewHeading(catalog, point)
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
                    HifzFixBody(catalog, fix, lookAlikes)
                    HifzReviewAnswerActions(state, point, actions)
                    HifzReviewKey(point)
                }
                HifzReviewControls(state, catalog, queue, point, audio, audioControls, actions, compact = false, freshAgain = freshAgain)
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
                HifzReviewEnd(state, queue, stillDue, nextCheckIn, actions, Modifier.padding(end = 10.dp, top = 4.dp, bottom = 6.dp), day, nextFresh)
                return@Column
            }
            val fix = remember(point, lookAlikes) { hifzFix(catalog, point, lookAlikes) }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(contentScroll).padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HifzReviewHeading(catalog, point, onPage = true)
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
                    // The verse is on the page now; the card adds only what the page cannot show: the look-alike, the
                    // words to mind.
                    HifzFixBody(catalog, fix, lookAlikes, ownOnPage = false)
                    HifzReviewAnswerActions(state, point, actions)
                    HifzReviewKey(point)
                }
                Spacer(Modifier.height(2.dp))
                HifzReviewControls(state, catalog, queue, point, audio, audioControls, actions, compact = true, freshAgain = freshAgain)
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
    val begin = if (point.verse.ayah > 1) "أكمل بعد الآية السابقة من حفظك"
        else "اقرأ أول آية من سورة ${catalog.surahs.getOrNull(point.verse.surah - 1)?.name.orEmpty()} من حفظك"
    val attention = when (point.kind) {
        HifzKind.Ending -> "، وانتبه إلى خاتمتها"
        HifzKind.Similar -> " دون الانتقال إلى شبيهتها"
        HifzKind.Word -> when (point.slipWords.size) {
            0 -> "، وانتبه إلى الموضع الذي أخطأت فيه"
            1 -> "، وانتبه إلى الكلمة التي أخطأت فيها"
            else -> "، وانتبه إلى الكلمات التي أخطأت فيها"
        }
        HifzKind.Skip -> " دون تجاوز الآية المطلوبة"
        HifzKind.Start -> "، واستحضر بدايتها ثم أكملها"
    }
    return "$begin$attention، ثم اضغط «أظهر»."
}

/** «سؤال 3 من 5», the undo of the last answer, and the end of the review. */
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
        val name = when {
            atEnd -> "نتيجة المراجعة"
            state.training(point) -> "تدريب"
            state.revealed(point) -> "تقييم المحاولة"
            else -> "سؤال"
        }
        // «سؤال 2 من 5», «تقييم المحاولة · 2 من 5»; a review of one question is not numbered.
        val count = state.count(queue)
        val place = "${state.index(queue) + 1} من $count"
        Text(
            when {
                point == null || atEnd || count < 2 -> name
                name == "سؤال" -> "$name $place"
                else -> "$name · $place"
            },
            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark,
        )
        trailing()
      }
      if (state.undoable(queue) != null || !atEnd) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (state.undoable(queue) != null) TextButton(onClick = { state.undo(queue, actions) }, modifier = Modifier.testTag("hifz_review_undo")) { Text("تراجع عن التقييم") }
        if (!atEnd) {
            TextButton(onClick = { state.requestEnd(queue, actions) }, modifier = Modifier.testTag("hifz_review_end")) { Text("إنهاء") }
        }
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
            if (onPage) "${hifzVerseName(catalog, point.verse)} · ${point.kind.short}"
            else "${hifzVerseName(catalog, point.verse)} · ${hifzPage(catalog, point.verse).orEmpty()}",
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

/** After «أظهر»: the reader's «ضابطي», tied to the right wording now in view. */
@Composable
private fun HifzReviewKey(point: HifzPoint) {
    if (point.key.isNotBlank()) Text("ضابطي: ${point.key}", fontSize = 13.sp, color = QuranHifzViolet)
}

/** After «أظهر»: the right wording heard, and the whole fix. */
@Composable
private fun HifzReviewAnswerActions(state: HifzReviewState, point: HifzPoint, actions: HifzReviewActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { if (state.settled()) actions.listen(point) }, modifier = Modifier.testTag("hifz_review_listen")) {
            Text("استمع للصواب", maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { actions.fix(point) }) { Text("التصحيح كاملا") }
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
                "استمع إليها وكرّرها. " + if (point.fresh > 0) "نسألك عنها $freshAgain." else "تعود ${hifzWhen(point.dueDay - todayEpochDay())}.",
                fontSize = 13.sp, color = GreenPrimaryDark, modifier = Modifier.testTag("hifz_review_training"),
            )
            OutlinedButton(
                onClick = { state.drill(point, actions) },
                modifier = Modifier.fillMaxWidth().testTag("hifz_review_drill"),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (state.drilled(point)) "استمع وكرّر مجددًا" else "استمع وكرّر", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (compact) hifzCompactDrillLabel(point, HifzDrillGap)
                        else hifzDrillLabel(point, catalog.surahs.getOrNull(point.verse.surah - 1)?.verseCount, HifzDrillGap) { catalog.surahs.getOrNull(it - 1)?.name.orEmpty() },
                        fontSize = 12.sp, textAlign = TextAlign.Center,
                    )
                }
            }
            Button(
                onClick = { if (state.settled()) state.next(queue, actions) },
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                modifier = Modifier.fillMaxWidth().testTag("hifz_review_next"),
            ) {
                Text(if (state.index(queue) + 1 < state.count(queue)) "التالي" else "نتيجة المراجعة")
            }
        }
        else -> {
            Text("كيف استرجعتها قبل إظهار الصواب؟", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
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
            fun detail(grade: HifzGrade): String =
                if (compact) hifzGradeShort(point, grade, freshAgain) else hifzGradeDetail(point, grade, freshAgain)
            val sideBySide = compact && LocalDensity.current.fontScale <= 1.3f
            if (sideBySide) Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                grades.forEach { grade ->
                    HifzGradeChip(label(grade), detail(grade), tag = "hifz_grade_${grade.name.lowercase()}", modifier = Modifier.weight(1f)) {
                        state.grade(queue, actions, grade)
                    }
                }
            } else {
                grades.forEach { grade ->
                    HifzGradeButton(label(grade), detail(grade), tag = "hifz_grade_${grade.name.lowercase()}") { state.grade(queue, actions, grade) }
                }
            }
            if (!compact) TextButton(onClick = { state.howItWorks = true }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("hifz_how_it_works")) {
                Text("كيف تعمل مراجعة اليوم؟", fontSize = 13.sp)
            }
        }
    }
}

/** The verse references are already in view; keep the drill's purpose and repeat count beside its action. */
private fun hifzCompactDrillLabel(point: HifzPoint, gap: Float): String {
    val passage = when {
        point.other != null && (point.kind == HifzKind.Ending || point.kind == HifzKind.Similar) -> "الموضعان بالتناوب · 5 تلاوات"
        point.kind == HifzKind.Skip || point.kind == HifzKind.Start -> "الآيات المتصلة · 3 مرات"
        else -> "الآية · 3 مرات"
    }
    return if (gap > 0f) "$passage · وقفات للترديد" else passage
}

/**
 * What was answered: at the end of the review, or when it is stopped early, which keeps «تابع الآن». The day's count
 * adds up every review of the day; the failed points can be asked once more, and what was reviewed can be heard now
 * that the checks are done.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HifzReviewEnd(
    state: HifzReviewState,
    queue: List<HifzPoint>,
    stillDue: Int,
    nextCheckIn: Long?,
    actions: HifzReviewActions,
    modifier: Modifier = Modifier,
    /** The day's answers, a point counted once a day, a fresh one asked at several reminders too. */
    dayTally: HifzTally? = null,
    nextFresh: String? = null,
) {
    val stopped = state.stopping && state.point(queue) != null
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val day = dayTally ?: state.day
        val remaining = state.unanswered(queue)
        when {
            stopped -> {
                Text("توقفت المراجعة", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                Text("حُفظت إجاباتك: ${hifzCountLabel(state.graded)}", fontSize = 14.sp, color = TextDark)
                Text("لم تختبرها بعد في هذه الجولة: ${hifzCountLabel(remaining)}", fontSize = 14.sp, color = TextMuted)
                if (state.training(state.point(queue))) Text("يمكنك متابعة التدريب على الموضع الحالي.", fontSize = 13.sp, color = TextMuted)
            }
            day.graded > 0 -> {
                Text("بارك الله فيك", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                Text("راجعت اليوم: ${hifzCountLabel(day.graded)}", fontSize = 14.sp, color = TextMuted)
                Text(
                    listOfNotNull(
                        "دون مساعدة: ${day.held}".takeIf { day.held > 0 },
                        "بعد تردد أو تلميح: ${day.hesitated}".takeIf { day.hesitated > 0 },
                        "نسيت أو أخطأت: ${day.again}".takeIf { day.again > 0 },
                    ).joinToString(" · "),
                    fontSize = 14.sp, color = TextDark, textAlign = TextAlign.Center,
                )
                // The day counts each point once, by its first answer: a point asked again at a later reminder has
                // its answer of this round said apart.
                val round = state.round
                if (round.graded > 0 && (round.held != day.held || round.hesitated != day.hesitated || round.again != day.again)) Text(
                    "في هذه الجولة: " + listOfNotNull(
                        "دون مساعدة: ${round.held}".takeIf { round.held > 0 },
                        "بعد تردد أو تلميح: ${round.hesitated}".takeIf { round.hesitated > 0 },
                        "نسيت أو أخطأت: ${round.again}".takeIf { round.again > 0 },
                    ).joinToString(" · "),
                    fontSize = 13.sp, color = TextMuted, textAlign = TextAlign.Center,
                )
                if (day.heldNow > 0) Text("ثبت منها: ${hifzCountLabel(day.heldNow)}", fontSize = 14.sp, color = GreenPrimary)
            }
            else -> Text(if (stillDue > 0) "لم يبق موضع في هذه المراجعة" else "لم يبق موضع للمراجعة الآن", fontSize = 16.sp, color = GreenPrimaryDark)
        }
        // Points whose verse was shown in this review, by another point's answer: asked at the next reminder.
        val deferred = state.deferredIds.size
        if (!stopped && deferred > 0) Text(
            if (deferred == 1) "موضع واحد ظهرت آيته في هذه المراجعة؛ نسألك عنه في تذكير لاحق."
            else "${hifzCountLabel(deferred)} ظهرت آياتها في هذه المراجعة؛ نسألك عنها في تذكير لاحق.",
            fontSize = 13.sp, color = TextMuted, textAlign = TextAlign.Center,
        )
        if (!stopped) {
            if (stillDue > 0) Text("وبقي للمراجعة الآن: ${hifzCountLabel(stillDue)}", fontSize = 14.sp, color = TextDark)
            else if (nextFresh != null) Text("السؤال القادم $nextFresh", fontSize = 14.sp, color = TextMuted)
            else if (nextCheckIn != null) Text("المراجعة القادمة ${hifzWhen(nextCheckIn)}", fontSize = 14.sp, color = TextMuted)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (stopped) {
                Button(onClick = state::resume, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.testTag("hifz_review_resume")) {
                    Text("تابع الآن")
                }
                OutlinedButton(onClick = actions::dismiss, modifier = Modifier.testTag("hifz_review_later")) { Text("لاحقًا") }
                return@FlowRow
            }
            if (state.gradedIds.isNotEmpty()) OutlinedButton(onClick = { actions.listenTo(state.gradedIds) }, modifier = Modifier.testTag("hifz_review_listen_done")) {
                Text("استمع إلى ما راجعته")
            }
            if (stillDue > 0) OutlinedButton(onClick = actions::more, modifier = Modifier.testTag("hifz_review_more")) { Text("تابع مراجعات اليوم") }
            Button(onClick = actions::dismiss, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)) { Text("تم") }
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
 * What answering [grade] means for [point]: how the reading went, then when it comes back, said as such. A fresh point
 * is asked again [again], «اليوم بعد المغرب», until its last right answer spaces its checks out; a failed check makes
 * any point fresh again.
 */
internal fun hifzGradeDetail(point: HifzPoint, grade: HifzGrade, again: String = "في تذكير لاحق"): String {
    if (grade == HifzGrade.Again) return "أخطأت أو نسيت · " + when {
        point.mastered -> "تخرج من «ثبتت»، ونسألك عنها $again حتى تقرأها صحيحة ${HifzReminder.recallsPhrase(HifzFreshRecalls)}"
        point.restartsOnAgain -> "يبدأ العدّ من جديد: نسألك عنها $again حتى تقرأها صحيحة ${HifzReminder.recallsPhrase(HifzFreshRecalls)}"
        else -> "خطأ آخر، ونسألك عنها $again"
    }
    if (point.fresh > 0) return when {
        grade == HifzGrade.Hesitated -> "بتردد أو تلميح · لا تُحسب، ونسألك عنها $again"
        point.spacesNext -> "دون تردد · تتباعد مراجعاتها، وتعود غدًا"
        else -> "دون تردد · ${hifzFreshLeft(point.fresh - 1)}، ونسألك عنها $again"
    }
    val back = hifzWhen(point.daysAfter(grade).toLong())
    return when (grade) {
        HifzGrade.Held -> when {
            point.holdsNext -> "دون تردد · تثبت، وتعود $back للتأكد"
            point.mastered -> "دون تردد · تعود $back للتأكد"
            else -> "دون تردد · تعود $back"
        }
        else -> "بتردد أو تلميح · تعود $back" + if (point.mastered && point.step - 1 < HifzMasteredStep) " · تخرج من «ثبتت»" else ""
    }
}

/**
 * [hifzGradeDetail] in a few words, for the answers side by side: what the answer does, then when the verse comes back
 * when that is at a coming reminder: «تبقى قراءتان» over «اليوم بعد المغرب», «لا تُحسب», «خطأ آخر», «تعود بعد 3 أيام».
 */
internal fun hifzGradeShort(point: HifzPoint, grade: HifzGrade, again: String = "في تذكير لاحق"): String = when {
    grade == HifzGrade.Again -> (if (point.mastered || point.restartsOnAgain) "من جديد: ${HifzFreshRecalls} قراءات" else "خطأ آخر") + "\n$again"
    point.fresh > 0 -> when {
        grade == HifzGrade.Hesitated -> "لا تُحسب\n$again"
        point.spacesNext -> "تعود غدًا"
        else -> "${hifzFreshLeft(point.fresh - 1)}\n$again"
    }
    grade == HifzGrade.Held && point.holdsNext -> "تثبت"
    else -> "تعود ${hifzWhen(point.daysAfter(grade).toLong())}"
}

/** «تبقى قراءتان», «تبقى قراءة»: the right answers [left] still needed after this one. */
private fun hifzFreshLeft(left: Int): String = when (left) {
    1 -> "تبقى قراءة"
    2 -> "تبقى قراءتان"
    else -> "تبقى $left قراءات"
}

/**
 * «نسيت» now sets back counted progress: a right answer of the fresh phase, or a step of the spaced checks. A point
 * never answered right since its slip only adds a slip.
 */
private val HifzPoint.restartsOnAgain: Boolean get() = step > 0 || fresh in 1 until HifzFreshRecalls || (fresh == 0 && step == 0)

/** A grade: its name, and under it what it means and when the verse comes back. */
@Composable
private fun HifzGradeButton(label: String, detail: String, tag: String, onClick: () -> Unit) {
    val padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    val content: @Composable RowScope.() -> Unit = {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
    OutlinedButton(onClick = onClick, contentPadding = padding, modifier = Modifier.fillMaxWidth().testTag(tag), content = content)
}

/** A grade side by side with the others, on the page's card: its name and, under it, when the verse comes back. */
@Composable
private fun HifzGradeChip(label: String, detail: String, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val padding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
    val content: @Composable RowScope.() -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
            Text(detail, fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
    OutlinedButton(onClick = onClick, contentPadding = padding, shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxHeight().testTag(tag), content = content)
}
