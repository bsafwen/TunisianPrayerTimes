package com.tunisianprayertimes.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.tunisianprayertimes.quran.quranAyah
import com.tunisianprayertimes.quran.hifz.HifzFreshGapMs
import com.tunisianprayertimes.quran.hifz.HifzGrade
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzLookAlikes
import com.tunisianprayertimes.quran.hifz.HifzMasteredStep
import com.tunisianprayertimes.quran.hifz.HifzPoint
import com.tunisianprayertimes.quran.hifz.shows
import com.tunisianprayertimes.quran.hifz.HifzReminder
import com.tunisianprayertimes.quran.hifz.daysAfter
import com.tunisianprayertimes.quran.hifz.epochDay
import com.tunisianprayertimes.quran.hifz.HifzFix
import com.tunisianprayertimes.quran.hifz.hifzFixes
import com.tunisianprayertimes.quran.hifz.hifzQuestion
import com.tunisianprayertimes.quran.hifz.hifzQuestionAbout
import com.tunisianprayertimes.quran.hifz.hifzWaitEnd
import com.tunisianprayertimes.quran.hifz.hifzWords
import com.tunisianprayertimes.quran.hifz.holdsNext
import com.tunisianprayertimes.quran.hifz.recallsAfterAgain
import com.tunisianprayertimes.quran.hifz.spacesNext
import com.tunisianprayertimes.quran.hifz.todayEpochDay
import com.tunisianprayertimes.ui.theme.*

/** What a review card can ask of the reader. */
internal interface HifzReviewActions {
    fun grade(point: HifzPoint, grade: HifzGrade)
    /** The verse before the point, once, as a cue. */
    fun listenBefore(point: HifzPoint)
    /** The right wording, once. */
    fun listen(point: HifzPoint)
    fun drill(point: HifzPoint)
    /** The reader's note on the point, «ما يذكّرك بها», written once the answer is seen. */
    fun note(point: HifzPoint, key: String) {}
    /** Words the verse went wrong at, told after «نسيت أو أخطأت»: added to it, uncounted, the answer having counted. */
    fun addWords(point: HifzPoint, words: Set<Int>) {}
    /** The card is left, or the review ends: the audio it started stops. */
    fun leaveCard()
    /** Another review, of the points still due beyond this one's. */
    fun more()
    /**
     * «استمع إلى ما راجعته للتوّ», once the checks are done: hearing them before would have been no check. The points of
     * this review only, those its end lists: an earlier one may be due again.
     */
    fun listenTo(ids: List<Long>)
    /** Verses shown or heard on purpose: their points are not asked right after. */
    fun exposed(verses: Set<QuranVerseReference>) {}
    /** Each time an answer is shown or heard, already exposed or not: a fresh point is asked a while after it. */
    fun seen(verses: Set<QuranVerseReference>) {}
    fun dismiss()
}

/**
 * The answers of a review: added up over «تابع المراجعة», so that the day's count is the day's. [points]: the points
 * answered, when known, for the day's count to say those of earlier reviews apart; not saved with it.
 */
@Immutable
internal data class HifzTally(
    val held: Int = 0, val hesitated: Int = 0, val again: Int = 0, val heldNow: Int = 0, val points: Set<Long> = emptySet(),
) {
    val graded: Int get() = held + hesitated + again
    operator fun plus(other: HifzTally) =
        HifzTally(held + other.held, hesitated + other.hesitated, again + other.again, heldNow + other.heldNow, points + other.points)
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
 * and every answer given stands. Every way out shows what was answered first.
 *
 * Kept by the reader rather than by a card: the review shows on the page or in a sheet, and goes from one to the other
 * with its place and its answers. Saved as text, so a re-created activity finds it again.
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
    /** The card answered «نسيت أو أخطأت», for the words it went wrong at to be added on its drill. */
    private var missedId by mutableStateOf(saved.longAt(9))
    var howItWorks by mutableStateOf(saved?.getOrNull(10) == "1")
    /** On the page: the card folded down to its title, for the page under it. A new card opens unfolded. */
    var folded by mutableStateOf(saved?.getOrNull(11) == "1")
    /**
     * The note being written on the drill, its point's id and its text: here rather than where it is drawn, so that it
     * stays open, as typed, when the review moves between the page and the sheet as the phone turns.
     */
    var noteDraft by mutableStateOf(saved.longAt(22)?.let { it to saved?.getOrNull(23).orEmpty() })
    /** An open word picker and its selection follow the review between the page and sheet, like the note. */
    private var wordDraft by mutableStateOf(saved.longAt(24)?.let { id ->
        id to saved?.getOrNull(25)?.split(',')?.mapNotNull { it.toIntOrNull()?.takeIf { word -> word >= 0 } }.orEmpty().toSet()
    })
    private var wordsAddedId by mutableStateOf(saved.longAt(26))
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
    private var exposedVerses by mutableStateOf(saved?.getOrNull(17)?.let { saved.versesAt(17) } ?: exposedVerses)
    private var attemptId by mutableStateOf(saved.longAt(18))
    private var attemptVerse by mutableStateOf(saved.versesAt(19).firstOrNull())
    /** When each verse's answer was last seen, shown or heard, by the wall clock. */
    private var seenAt by mutableStateOf(saved?.getOrNull(20)?.let { decodeSeenVerses(it) } ?: seenVerses)
    /** Points no longer asked in this review: their verse was looked at in it, so they wait for the next reminder. */
    var deferredIds by mutableStateOf(saved.idsAt(21).toSet())
        private set
    private var shownKey: Any? = null
    private var shownAt = 0L

    fun index(queue: List<HifzPoint>): Int = cardId?.let { id -> queue.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: position
    fun point(queue: List<HifzPoint>): HifzPoint? = queue.getOrNull(index(queue))
    fun revealed(point: HifzPoint?): Boolean = point != null && revealedId == point.id
    fun training(point: HifzPoint?): Boolean = point != null && trainingId == point.id
    /** Answered «نسيت أو أخطأت», now in its drill: a word it went wrong at this time may be added to it. */
    fun missed(point: HifzPoint?): Boolean = training(point) && missedId == point?.id
    fun wordsOpen(point: HifzPoint): Boolean = wordDraft?.first == point.id
    fun chosenWords(point: HifzPoint): Set<Int> = wordDraft?.takeIf { it.first == point.id }?.second.orEmpty()
    fun wordsAdded(point: HifzPoint): Boolean = wordsAddedId == point.id

    fun openWords(point: HifzPoint) {
        if (missed(point) && !wordsAdded(point) && !wordsOpen(point)) wordDraft = point.id to emptySet()
    }

    fun toggleWord(point: HifzPoint, word: Int) {
        if (!wordsOpen(point) || word < 0) return
        val chosen = chosenWords(point)
        wordDraft = point.id to if (word in chosen) chosen - word else chosen + word
    }

    fun cancelWords(point: HifzPoint) {
        if (wordsOpen(point)) wordDraft = null
    }

    fun addWords(point: HifzPoint, actions: HifzReviewActions) {
        val chosen = chosenWords(point)
        if (!missed(point) || wordsAdded(point) || chosen.isEmpty()) return
        actions.addWords(point, chosen)
        wordsAddedId = point.id
        wordDraft = null
    }

    private fun keepEditorsFor(id: Long?) {
        if (noteDraft?.first != id) noteDraft = null
        if (wordDraft?.first != id) wordDraft = null
        if (wordsAddedId != id) wordsAddedId = null
    }

    fun drilled(point: HifzPoint?): Boolean = point != null && drilledId == point.id
    /** Its drill was started and [audio] still plays or is paused: the strip is its control, in place of its button. */
    fun drillRunning(point: HifzPoint?, audio: HifzAudio?): Boolean = drilled(point) && audio != null && audio.stoppable
    fun hinted(point: HifzPoint?): Boolean = point != null && hintId == point.id
    fun assisted(point: HifzPoint?): Boolean = point != null && assistedId == point.id
    val tally: HifzTally get() = HifzTally(held, hesitated, again, heldNow)
    val graded: Int get() = tally.graded
    /** The day's answers, this review's with those before it. */
    val day: HifzTally get() = earlier + round
    /** This review's answers. */
    val round: HifzTally get() = tally
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
     * replaced it; see [settled]. [audio], what plays: the drill's controls give way to its strip while it plays.
     */
    fun shown(point: HifzPoint?, audio: HifzAudio? = null) {
        beginAttempt(point)
        val key = listOf(point?.id, revealed(point), training(point), drilled(point), drillRunning(point, audio), stopping)
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
     * Its answer was looked at a moment ago, so a recall now would be none: a fresh point whose wait from its verse last
     * seen is not over ([hifzWaitEnd]), a spaced check whose verse was seen today.
     */
    private fun spoiled(point: HifzPoint): Boolean =
        if (point.fresh > 0) seenAt[point.verse]?.let { wallClock() < hifzWaitEnd(it) } == true else point.verse in exposedVerses

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
        keepEditorsFor(cardId)
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
        if (noteDraft?.first == id) noteDraft = null
        if (wordDraft?.first == id) wordDraft = null
        if (wordsAddedId == id) wordsAddedId = null
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
        actions.grade(shown, answer)
        count(answer)
        if (answer == HifzGrade.Held && shown.holdsNext) heldNow++
        missedId = shown.id.takeIf { answer == HifzGrade.Again }
        gradedIds = gradedIds - shown.id + shown.id
        if (answer != HifzGrade.Held) trainingId = shown.id else next(queue, actions)
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

    private fun count(grade: HifzGrade) = when (grade) {
        HifzGrade.Held -> held++
        HifzGrade.Hesitated -> hesitated++
        HifzGrade.Again -> again++
    }

    companion object {
        val Saver: Saver<HifzReviewState, Any> = listSaver(
            save = { s ->
                listOf(
                    s.held, s.hesitated, s.again, s.heldNow, s.cardId, s.position, s.revealedId, s.trainingId, s.drilledId,
                    s.missedId, if (s.howItWorks) 1 else 0, if (s.folded) 1 else 0, s.earlier.encode(),
                    s.gradedIds.joinToString(","), if (s.stopping) 1 else 0, s.hintId, s.assistedId,
                    s.exposedVerses.encodeReviewVerses(), s.attemptId,
                    setOfNotNull(s.attemptVerse).encodeReviewVerses(),
                    encodeSeenVerses(s.seenAt.filterValues { it > s.wallClock() - HifzFreshGapMs }),
                    s.deferredIds.joinToString(","),
                    // Optional trailing editor fields keep states saved by earlier versions readable.
                    s.noteDraft?.first, s.noteDraft?.second, s.wordDraft?.first,
                    s.wordDraft?.second?.sorted()?.joinToString(","), s.wordsAddedId,
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
    /** The points due now, this review's answered ones being due again only once their wait is over. */
    due: Set<Long>,
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
    state.shown(point, audio)
    val contentScroll = rememberScrollState()
    LaunchedEffect(point?.id, state.revealed(point), state.training(point)) { contentScroll.scrollTo(0) }
    // A swipe down with answers given stops on what was answered first, as «إنهاء» does. One lambda for the sheet's
    // life: a new one, at each answer or playback tick, would build a new sheet state and slide the sheet in again.
    val currentQueue by rememberUpdatedState(queue)
    val currentActions by rememberUpdatedState(actions)
    val confirm = remember(state) {
        { value: SheetValue ->
            if (value == SheetValue.Hidden && !state.atEnd(currentQueue) && state.graded > 0) {
                state.requestEnd(currentQueue, currentActions)
                false
            } else true
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirm)
    // Closed only once it is hidden: a Back the sheet refused shows what was answered, with «تابع الآن».
    ModalBottomSheet(onDismissRequest = { if (!sheetState.isVisible) actions.dismiss() }, sheetState = sheetState, containerColor = BgCream) {
        // Back, predictive or not, stops on what was answered first, without leaving the sheet shrunk.
        BackHandler(enabled = !state.atEnd(queue) && state.graded > 0) { state.requestEnd(queue, actions) }
        BoxWithConstraints(Modifier.fillMaxWidth().quranSheetHeight().padding(horizontal = 16.dp).testTag("hifz_review")) {
          Column(Modifier.fillMaxSize()) {
            HifzReviewTitle(state, queue, point, actions)
            if (point == null || state.stopping) {
                HifzReviewEnd(
                    state, catalog, queue, due, nextCheckIn, actions, Modifier.weight(1f).padding(vertical = 32.dp), day, nextFresh,
                    freshAgain, tomorrow, nextCheckLabel, nextFor,
                )
                return@Column
            }
            val fixes = remember(point, lookAlikes) { hifzFixes(catalog, point, lookAlikes) }
            val revealed = fixes.isNotEmpty() && state.revealed(point)
            // Once the answer is shown, the answers, or the training, come first: they are what this step asks. The
            // correction follows, to read.
            if (revealed) Column(
                Modifier.heightIn(max = this@BoxWithConstraints.maxHeight * 0.55f).verticalScroll(rememberScrollState()).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HifzReviewHeading(catalog, point, revealed = true, training = state.training(point))
                HifzReviewControls(
                    state, catalog, queue, point, audio, audioControls, actions, compact = false, freshAgain = freshAgain,
                    tomorrow = tomorrow, fixes = fixes,
                )
            }
            Column(Modifier.weight(1f).verticalScroll(contentScroll).padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!revealed) HifzReviewHeading(catalog, point, revealed = false)
                if (fixes.isEmpty()) return@Column
                if (!state.revealed(point)) {
                    val talkBack = rememberTouchExploration()
                    val previous = point.verse.takeIf { it.ayah > 1 }?.let { QuranVerseReference(it.surah, it.ayah - 1) }
                    val inText: @Composable () -> Unit = {
                        Surface(shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, CardBorder)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                // The question above names the verse before: its text needs no label of its own.
                                previous?.let { HifzReviewCueText(catalog, it) }
                                Text(
                                    if (previous == null) "أول آية في السورة مغطاة." else "الآية التي بعدها مغطاة.",
                                    fontSize = 12.sp, color = QuranHifzViolet,
                                )
                                HifzAnswerSlot()
                            }
                        }
                    }
                    // One representation of the question: the covered mushaf; text is solely the accessibility or
                    // missing-image fallback, never a duplicate below the image.
                    if (!talkBack) QuranVerseExcerpt(
                        catalog, point.verse, "${hifzVerseAbout(catalog, point.verse)} مغطاة في المصحف",
                        linesBefore = if (previous != null) 1 else 0, cover = true, fallback = inText,
                    )
                    if (talkBack) inText()
                    HifzReviewHint(state, point, actions)
                } else {
                    // The words to mind are said above the answers; a look-alike keeps its comparison here.
                    // In the drill, the verse and a look-alike's comparison stay, to read along; «لماذا يلتبس؟», read at
                    // its answer, goes.
                    HifzFixBody(catalog, fixes, lookAlikes, exactWords = false, practice = false, learningNotes = !state.training(point), note = false)
                    HifzReviewAnswerActions(state, point, actions)
                }
            }
            if (fixes.isNotEmpty() && !revealed) Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HifzReviewControls(state, catalog, queue, point, audio, audioControls, actions, compact = false, freshAgain = freshAgain, tomorrow = tomorrow, fixes = emptyList())
            }
          }
        }
    }
    if (state.howItWorks) HifzHowItWorksDialog { state.howItWorks = false }
}

/**
 * The day's review on the page itself: the reader opens each point's page, covers the verse asked and what follows it,
 * and this card floats over the foot of the page with the question and the answers. The cover erases under the
 * finger as in «مراجعة». The whole verse is recalled: the card only asks, and what went wrong is named once «أظهر» is
 * pressed.
 */
@Composable
internal fun HifzReviewCard(
    state: HifzReviewState,
    catalog: QuranCatalog,
    lookAlikes: HifzLookAlikes?,
    queue: List<HifzPoint>,
    audio: HifzAudio?,
    audioControls: HifzAudioControls,
    /** The points due now; see [HifzReviewSheet]. */
    due: Set<Long>,
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
    /** The page cannot show the end of the verse before the one asked: the card shows it, under the question. */
    beforeHidden: Boolean,
    /**
     * The page in view is the verse's: its header names the surah, and its cover is what the card's violet square stands
     * for. Paged away, the question names its surah.
     */
    pointInView: Boolean,
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
    state.shown(point, audio)
    val contentScroll = rememberScrollState()
    LaunchedEffect(point?.id, state.revealed(point), state.training(point)) { contentScroll.scrollTo(0) }
    QuranFloatingCard(modifier.widthIn(max = 560.dp).testTag("hifz_review_card")) {
        Column(Modifier.heightIn(max = maxHeight).padding(start = 14.dp, end = 4.dp, bottom = 10.dp)) {
            // Quiet over the question, which is what the card asks.
            HifzReviewTitle(state, queue, point, actions, fontSize = 13.sp, color = TextMuted, weight = FontWeight.Medium) {
                IconButton(onClick = { state.howItWorks = true }, modifier = Modifier.size(36.dp).testTag("hifz_how_it_works")) {
                    Icon(painterResource(R.drawable.ic_adhkar_info), "كيف تعمل مراجعة اليوم؟", Modifier.size(18.dp), tint = GreenPrimaryDark)
                }
                IconButton(onClick = { state.folded = !state.folded }, modifier = Modifier.size(36.dp).testTag("hifz_review_fold")) {
                    Icon(
                        painterResource(if (state.folded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                        if (state.folded) "عرض بطاقة المراجعة" else "طيّ البطاقة لرؤية الصفحة", Modifier.size(22.dp), tint = GreenPrimaryDark,
                    )
                }
            }
            if (state.folded) return@Column
            if (point == null || state.stopping) {
                HifzReviewEnd(
                    state, catalog, queue, due, nextCheckIn, actions, Modifier.padding(end = 10.dp, top = 4.dp, bottom = 6.dp), day, nextFresh,
                    freshAgain, tomorrow, nextCheckLabel, nextFor,
                )
                return@Column
            }
            val fixes = remember(point, lookAlikes) { hifzFixes(catalog, point, lookAlikes) }
            val revealed = fixes.isNotEmpty() && state.revealed(point)
            // The question, or once its answer is shown the verse and its slips, then the answers or the training first
            // in what scrolls, before anything to read: they are what this step asks, in view as the card opens on them,
            // and never squeezed out of a card that has reached its height.
            Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HifzReviewHeading(catalog, point, onPage = pointInView, revealed = revealed, training = state.training(point))
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(contentScroll).padding(end = 10.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (fixes.isEmpty()) return@Column
                if (!state.revealed(point)) {
                    // What the reader goes on from, when the page cannot show it: the question above names it already.
                    if (beforeHidden && point.verse.ayah > 1) {
                        val previous = QuranVerseReference(point.verse.surah, point.verse.ayah - 1)
                        QuranVerseExcerpt(
                            catalog, previous, "الآية السابقة، ${hifzVerseAbout(catalog, previous)}",
                            fallback = { HifzReviewCueText(catalog, previous, fontSize = 18.sp) },
                        )
                    }
                    HifzReviewHint(state, point, actions)
                    if (eraserHint) Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AdhkarSoftGreen).padding(start = 10.dp).testTag("hifz_review_eraser_hint"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("المس الغطاء لتكشف كلمة، ومرّر إصبعك عليه ببطء لتكشف أكثر، أو بسرعة لتقلب الصفحة.", Modifier.weight(1f).padding(vertical = 6.dp), fontSize = 12.sp, color = GreenPrimaryDark)
                        IconButton(onClick = onEraserHintClosed, modifier = Modifier.size(36.dp)) {
                            Icon(painterResource(R.drawable.ic_adhkar_close), "إخفاء الإرشاد", Modifier.size(14.dp), tint = GreenPrimaryDark)
                        }
                    }
                } else {
                    HifzReviewControls(
                        state, catalog, queue, point, audio, audioControls, actions, compact = true, freshAgain = freshAgain,
                        tomorrow = tomorrow, fixes = fixes,
                    )
                    // The verse is on the page now; the card adds only what the page cannot show: where a look-alike
                    // parts from it, the words to mind. A look-alike's page opens on demand. Not in the drill: read at
                    // the answer a moment ago, it would hold the card over the page the drill is followed on.
                    if (!state.training(point)) HifzFixBody(
                        catalog, fixes, lookAlikes, ownOnPage = false, otherFolded = true, exactWords = false, practice = false, note = false,
                    )
                    HifzReviewAnswerActions(state, point, actions)
                }
            }
            // «أظهر» stays in reach under the question, however long.
            if (fixes.isNotEmpty() && !revealed) Column(Modifier.padding(end = 10.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HifzReviewControls(state, catalog, queue, point, audio, audioControls, actions, compact = true, freshAgain = freshAgain, tomorrow = tomorrow, fixes = emptyList())
            }
        }
    }
    if (state.howItWorks) HifzHowItWorksDialog { state.howItWorks = false }
}

/** A full preceding verse anchors recall even when a scan is missing or TalkBack uses text. */
@Composable
internal fun HifzReviewCueText(catalog: QuranCatalog, verse: QuranVerseReference, fontSize: TextUnit = 19.sp) {
    val words = remember(catalog, verse) { catalog.verse(verse)?.let(::hifzWords) } ?: return
    val onExposure by rememberUpdatedState(LocalQuranExposure.current)
    var visible by remember(verse) { mutableStateOf(false) }
    LaunchedEffect(verse, visible) { if (visible) onExposure(setOf(verse)) }
    HifzVerseText(words, fontSize = fontSize, modifier = Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = bounds.width > 0f && bounds.height > 0f
    })
}


/**
 * «سؤال 3 من 5», one title for the whole question, its answer and its drill: the step is said by what the card asks
 * under it. On one line, beside «إنهاء».
 */
@Composable
private fun HifzReviewTitle(
    state: HifzReviewState,
    queue: List<HifzPoint>,
    point: HifzPoint?,
    actions: HifzReviewActions,
    fontSize: TextUnit = 18.sp,
    color: Color = GreenPrimaryDark,
    weight: FontWeight = FontWeight.SemiBold,
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
            fontSize = fontSize, fontWeight = weight, color = color, maxLines = 1,
        )
        if (!atEnd) TextButton(
            onClick = { state.requestEnd(queue, actions) }, contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.testTag("hifz_review_end"),
        ) { Text("إنهاء") }
        trailing()
      }
    }
}

/**
 * Before «أظهر», the question and nothing else: «ماذا بعد الآية 39؟», its surah named only off the page, whose header
 * names it ([onPage]: the page shows the verse covered in the points' colour, which the square after the text stands
 * for). A name of the verse asked above it would give the answer to its own question, and a state such as «يحتاج عناية»
 * would say where to take care, which no one says in prayer. Once the answer is shown ([revealed]): the verse and what
 * went wrong in it, which the answers below already weigh; in the drill after it ([training]), the verse alone, what
 * went wrong having been read. The text starts on the edge every line of the card shares.
 */
@Composable
internal fun HifzReviewHeading(catalog: QuranCatalog, point: HifzPoint, onPage: Boolean = false, revealed: Boolean, training: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (revealed) Text(
            if (training) hifzVerseName(catalog, point.verse) else hifzPointTitle(catalog, point), Modifier.weight(1f, fill = false),
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark,
        ) else Text(
            if (onPage) hifzQuestion(catalog, point) else hifzQuestionAbout(catalog, point),
            Modifier.weight(1f, fill = false), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextDark,
        )
        if (onPage) {
            Spacer(Modifier.width(8.dp))
            QuranHifzSquare(12.dp)
        }
    }
}

/**
 * Before «أظهر», the reader's note waits behind «استعن بملاحظتك»: shown first it would be a hint, and «دون مساعدة» would
 * then mean «with my note».
 */
@Composable
private fun HifzReviewHint(state: HifzReviewState, point: HifzPoint, actions: HifzReviewActions) {
    if (point.key.isBlank()) return
    if (state.hinted(point)) Text("ملاحظتك: ${point.key}", fontSize = 13.sp, color = QuranHifzViolet)
    // On the edge the question starts on.
    else TextButton(onClick = { state.showHint(point, actions) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("hifz_review_hint")) {
        Text("استعن بملاحظتك", fontSize = 13.sp, color = QuranHifzViolet)
    }
}

/**
 * After «أظهر»: the right wording heard, in a quiet line under the answers. Not in the drill, whose «استمع وكرّر» plays
 * the right wording already. The correction is the notebook's: opened here, it would show the verses of questions still
 * to come in this review.
 */
@Composable
private fun HifzReviewAnswerActions(state: HifzReviewState, point: HifzPoint, actions: HifzReviewActions) {
    // A second tap on «أظهر», which was where this line now is, waits for the card to settle.
    if (!state.training(point)) TextButton(onClick = { if (state.settled()) actions.listen(point) }, modifier = Modifier.testTag("hifz_review_listen")) {
        Text("استمع للصواب", maxLines = 1)
    }
}

/**
 * The reader's note, «ملاحظتك», under the answer once it is shown; on the drill, after a slip, the moment to write one:
 * «أضف ملاحظة» or «عدّل ملاحظتك», in a dialog over the card.
 */
@Composable
private fun HifzReviewNote(state: HifzReviewState, point: HifzPoint, actions: HifzReviewActions, editable: Boolean) {
    // At the answer, the note as written; on the drill, which no longer shows that answer, the way to write or change
    // it, the dialog opening on it.
    if (!editable) {
        if (point.key.isNotBlank()) Text(withQuranQuotes("ملاحظتك: ${point.key}"), fontSize = 13.sp, color = QuranHifzViolet, modifier = Modifier.testTag("hifz_review_note"))
        return
    }
    // A second tap on the answer that led here waits for the card to settle, as the drill's own buttons do.
    TextButton(
        onClick = { if (state.settled()) state.noteDraft = point.id to point.key },
        contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("hifz_review_note_edit"),
    ) { Text(if (point.key.isBlank()) "أضف ملاحظة" else "عدّل ملاحظتك", fontSize = 13.sp) }
    val draft = state.noteDraft?.takeIf { it.first == point.id } ?: return
    AlertDialog(
        onDismissRequest = { state.noteDraft = null },
        title = { Text("ملاحظتك") },
        text = {
            OutlinedTextField(
                value = draft.second, onValueChange = { state.noteDraft = point.id to it },
                modifier = Modifier.fillMaxWidth().testTag("hifz_review_note_field"),
                placeholder = { Text(hifzKeyPlaceholder(point)) }, shape = RoundedCornerShape(12.dp),
            )
        },
        confirmButton = { TextButton(onClick = { state.noteDraft = null; actions.note(point, draft.second) }) { Text("احفظ") } },
        dismissButton = { TextButton(onClick = { state.noteDraft = null }) { Text("إلغاء") } },
    )
}

/**
 * The right wording to mind, before the answers, one line for each mistake of the verse: the words that were missed,
 * the ending, where the verse parts from its look-alike, in the mushaf's script, the words to mind marked. The evidence
 * is read before the verdict is given.
 */
@Composable
internal fun HifzAnswerLine(fix: HifzFix) {
    val words = fix.words
    val shown = fix.wordingWords.filter { it in 0 until words.size }
    if (shown.isEmpty()) return
    val label = when (fix.mistake.kind) {
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
            if (fix.mistake.kind != HifzKind.Ending && shown.last() < words.size - 1) append(" …")
        }
    }
    // On the verse's first baseline: the mushaf font is far taller than the label's, and centred they would not line up.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(horizontal = 10.dp, vertical = 2.dp)
            .testTag("hifz_answer_line"),
    ) {
        Text(withQuranQuotes("$label:"), Modifier.alignByBaseline(), fontSize = 12.sp, color = TextMuted)
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f).alignByBaseline(), fontFamily = QuranMushafFont, fontSize = 20.sp, lineHeight = 34.sp, color = TextDark)
    }
}

/**
 * Under the card: what plays, then «أظهر», the answers, or the drill of a verse that still needs it. [compact], on the
 * page: the three answers side by side, so the verse above keeps its room.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    /** The right wording of each mistake, shown once the answer is, before the answers: what to judge by. */
    fixes: List<HifzFix>,
) {
    // A drill recited to its end has no strip: its replay and «التالي» below say it is over.
    if (audio != null) HifzAudioStrip(audio, audioControls)
    when {
        // The two side by side as tall as each other, the label of the first on two lines with large text.
        !state.revealed(point) -> Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The verse the question names, which is all it plays: «استمع إلى الآية 39».
            if (point.verse.ayah > 1) OutlinedButton(
                onClick = { if (state.settled()) actions.listenBefore(point) },
                // The label takes the lines it needs, with large text or on a narrow phone, never cut short: «أظهر» grows
                // with it.
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("hifz_review_cue"),
            ) { Text("استمع إلى ${quranAyah(point.verse.ayah - 1)}", textAlign = TextAlign.Center) }
            Button(
                onClick = { state.reveal(queue, actions) },
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("hifz_reveal"),
            ) { Text("أظهر") }
        }
        state.training(point) -> {
            // The drill is the step this card asks, filled until it was played. While it plays, the strip above is its
            // control and says what to do; once it is over, the way on is. When the verse comes back was said by the
            // answer just given, and the end of the review says it again.
            val drilled = state.drilled(point)
            val running = state.drillRunning(point, audio)
            if (!running) {
                val label = if (drilled) "استمع وكرّر مرة أخرى" else "استمع وكرّر"
                val drillModifier = Modifier.fillMaxWidth().testTag("hifz_review_drill")
                if (!drilled) Button(
                    onClick = { state.drill(point, actions) }, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = drillModifier,
                ) { Text(label) }
                else OutlinedButton(onClick = { state.drill(point, actions) }, modifier = drillModifier) { Text(label) }
            }
            // «انتهيت» at the last question: it leads to what was answered, which ends the review.
            val nextLabel = if (state.index(queue) + 1 < state.count(queue)) "التالي" else "انتهيت"
            val nextModifier = Modifier.fillMaxWidth().testTag("hifz_review_next")
            if (drilled && !running) Button(
                onClick = { if (state.settled()) state.next(queue, actions) },
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = nextModifier,
            ) { Text(nextLabel) }
            else OutlinedButton(onClick = { if (state.settled()) state.next(queue, actions) }, modifier = nextModifier) { Text(nextLabel) }
            // The word it went wrong at this time, after «نسيت أو أخطأت», and the note: side by side, one row.
            val missed = state.missed(point)
            val wordsOpen = state.wordsOpen(point)
            val wordsAdded = state.wordsAdded(point)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (missed && wordsAdded) Text(
                    "أضفناها.", Modifier.align(Alignment.CenterVertically).testTag("hifz_review_words_added"),
                    fontSize = 12.sp, color = QuranHifzViolet,
                ) else if (missed && !wordsOpen) TextButton(
                    onClick = { state.openWords(point) }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("hifz_review_new_words"),
                ) { Text("أخطأت في كلمة أخرى؟", fontSize = 13.sp) }
                HifzReviewNote(state, point, actions, editable = true)
            }
            if (missed && wordsOpen && !wordsAdded) HifzReviewWordPicker(
                catalog, point, chosen = state.chosenWords(point), onChoose = { state.toggleWord(point, it) },
                onAdd = { state.addWords(point, actions) }, onCancel = { state.cancelWords(point) },
            )
        }
        else -> {
            fixes.forEach { HifzAnswerLine(it) }
            HifzReviewNote(state, point, actions, editable = false)
            Text("هل تذكّرتها قبل أن تكشفها؟", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
            // Why «دون مساعدة» is not offered: the note opened, or some of the verse seen, before «أظهر».
            if (state.assisted(point)) Text(
                if (state.hinted(point)) "استعنت بملاحظتك قبل أن تكشفها." else "رأيت منها شيئًا قبل أن تكشفها.",
                fontSize = 12.sp, color = QuranHifzViolet,
            )
            val grades = if (state.assisted(point)) listOf(HifzGrade.Hesitated, HifzGrade.Again) else HifzGrade.entries.toList()
            fun label(grade: HifzGrade): String = when (grade) {
                HifzGrade.Held -> "دون مساعدة"
                HifzGrade.Hesitated -> if (state.assisted(point)) "تذكّرتها بمساعدة" else "بتردّد أو تلميح"
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
 * «أخطأت في كلمة أخرى؟», after «نسيت أو أخطأت»: the words the verse went wrong at this time, its answer in view, added to
 * what it has; uncounted, the answer having counted. The next correction marks them with the others. What is chosen
 * goes with the picker when it closes.
 */
@Composable
internal fun HifzReviewWordPicker(catalog: QuranCatalog, point: HifzPoint, onAdd: (Set<Int>) -> Unit, onAdded: () -> Unit, onCancel: () -> Unit) {
    var chosen by rememberSaveable(point.id) { mutableStateOf(emptyList<Int>()) }
    HifzReviewWordPicker(
        catalog, point, chosen.toSet(), onChoose = { i -> chosen = if (i in chosen) chosen - i else chosen + i },
        onAdd = { onAdd(chosen.toSet()); onAdded() }, onCancel = onCancel,
    )
}

/** The caller owns this editor so changing the review layout does not discard a selection. */
@Composable
internal fun HifzReviewWordPicker(
    catalog: QuranCatalog,
    point: HifzPoint,
    chosen: Set<Int>,
    onChoose: (Int) -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    val words = remember(catalog, point.verse) { catalog.verse(point.verse)?.let { hifzWords(it) } } ?: return
    Text("أي كلمة؟", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
    HifzWordPicker(words, chosen, suggested = null, multi = true, onSelect = onChoose)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onAdd, enabled = chosen.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.testTag("hifz_review_words_add"),
        ) { Text("أضفها") }
        TextButton(onClick = onCancel) { Text("إلغاء") }
    }
}

/**
 * Where an answered point stands now, for the end of the review, in sentences: «الملك · الآية 2: بقي أن تتذكّرها مرتين،
 * ونسألك عنها غدًا الساعة 15:35.», the step its last right answer of the daily questions reached, «الملك · الآية 3:
 * تذكّرتها 3 مرات دون مساعدة. نسألك عنها غدًا بعد الفجر، ثم تتباعد مواعيد مراجعتها.», or its next spaced check. That
 * check says why it comes when it does: a right answer puts it further off, with what is left before «ثبتت» in the
 * notebook's words, «… تثبت إن تذكّرتها في 3 أسئلة أخرى، أولها بعد 3 أيام.»; a hesitation brings it closer,
 * «… تذكّرتها بتردّد أو بمساعدة، فنسألك عنها بعد يومين.». [next]: the time it names, to say it once when the review's
 * own next question is the same.
 */
@Immutable
internal data class HifzRoundLine(val text: String, val next: String?, val milestone: Boolean)

internal fun hifzRoundLine(
    catalog: QuranCatalog, point: HifzPoint, today: Long, again: String, tomorrow: String,
    /** When the reminders ask this point next, from its own answer and its verse last seen; see [HifzReminder.nextAsk]. */
    own: String? = null,
): HifzRoundLine {
    val name = hifzVerseName(catalog, point.verse)
    // The answer the line is about: the last one, given today.
    val last = point.checks.lastOrNull()?.takeIf { it.day == today }
    val back = hifzWhen(point.dueDay - today)
    return when {
        point.fresh > 0 -> {
            val next = own ?: if (point.daily) tomorrow else again
            HifzRoundLine("$name: بقي أن تتذكّرها ${HifzReminder.recallsPhrase(point.fresh)}، ونسألك عنها $next.", next, milestone = false)
        }
        // Its daily questions ended with this answer: the step it reached is said, and when it comes back.
        point.step == 0 && last?.grade == HifzGrade.Held -> HifzRoundLine(
            "$name: تذكّرتها ${if (point.recalls == 1) "" else HifzReminder.recallsPhrase(point.recalls) + " "}دون مساعدة. نسألك عنها $tomorrow، ثم تتباعد مواعيد مراجعتها.",
            tomorrow, milestone = true,
        )
        point.mastered && point.masteredAt?.let { epochDay(it) } == today ->
            HifzRoundLine("$name: ثبتت. نسألك عنها $back للتأكد.", null, milestone = true)
        // A step down: from the first step of «ثبتت», a hesitation takes the point out of it.
        last?.grade == HifzGrade.Hesitated -> HifzRoundLine(
            "$name: تذكّرتها بتردّد أو بمساعدة، " + when {
                point.mastered -> "فنسألك عنها $back للتأكد."
                point.step == HifzMasteredStep - 1 -> "فخرجت من «ثبتت»، ونسألك عنها $back."
                else -> "فنسألك عنها $back."
            },
            null, milestone = false,
        )
        point.mastered -> HifzRoundLine("$name: نسألك عنها $back للتأكد.", null, milestone = false)
        // The check it names is the first of those left: said as such, or it reads as one more.
        last?.grade == HifzGrade.Held -> HifzRoundLine(
            "$name: " + when (val left = point.checksToHold) {
                1 -> "تثبت إن تذكّرتها حين نسألك عنها $back."
                2 -> "تثبت إن تذكّرتها في سؤالين آخرين، أولهما $back."
                else -> "تثبت إن تذكّرتها في $left${HifzReminder.NB}أسئلة أخرى، أولها $back."
            },
            null, milestone = false,
        )
        else -> HifzRoundLine("$name: نسألك عنها $back.", null, milestone = false)
    }
}

/**
 * The day in a sentence, each point counted once: «راجعت اليوم 5 مواضع، وتذكّرتها كلها دون مساعدة.», «راجعت اليوم 7
 * مواضع، تذكّرت منها 5 مواضع دون مساعدة، ونسيت أو أخطأت في موضعين.». The [before] of them answered before this review
 * are said apart, so that the count meets the review's lines above it: «راجعت اليوم 7 مواضع، منها موضعان قبل هذه
 * المراجعة. تذكّرتها كلها دون مساعدة.»
 */
internal fun hifzDaySummary(day: HifzTally, before: Int): String {
    val dual = day.graded == 2
    val single = listOf(day.held, day.hesitated, day.again).count { it > 0 } == 1
    val grades = if (single) {
        val them = if (dual) "هما" else "ها كلها"
        when {
            day.held > 0 -> "تذكّرت$them دون مساعدة"
            day.hesitated > 0 -> "تذكّرت$them بتردّد أو بمساعدة"
            else -> "نسيت أو أخطأت في$them"
        }
    } else listOfNotNull(
        "تذكّرت ${if (dual) "منهما" else "منها"} ${HifzReminder.pointsObject(day.held)} دون مساعدة".takeIf { day.held > 0 },
        "تذكّرت ${HifzReminder.pointsObject(day.hesitated)} بتردّد أو بمساعدة".takeIf { day.hesitated > 0 },
        "نسيت أو أخطأت في ${HifzReminder.pointsAbout(day.again)}".takeIf { day.again > 0 },
    ).mapIndexed { i, part -> if (i == 0) part else "و$part" }.joinToString("، ")
    val head = "راجعت اليوم ${HifzReminder.pointsObject(day.graded)}"
    return if (before > 0) "$head، ${if (dual) "منهما" else "منها"} ${HifzReminder.pointsLabel(before)} قبل هذه المراجعة. $grades."
    else "$head، ${if (single) "و" else ""}$grades."
}

/**
 * What was answered: at the end of the review, or when it is stopped early, which keeps «تابع الآن». Every point
 * answered in this review says where it stands, so that the right answers given show as progress and the last one as
 * the step it is; the last of them is the one on the page above the card. The day's count adds up every review of the
 * day, those before this one said apart; the points due now are said as others. What was reviewed can be heard now that
 * the checks are done. The ways on stay in view under what is said, however long it is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HifzReviewEnd(
    state: HifzReviewState,
    catalog: QuranCatalog,
    queue: List<HifzPoint>,
    due: Set<Long>,
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
    val stillDue = due.size
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Column(
          Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
          horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        val day = dayTally ?: state.day
        val remaining = state.unanswered(queue)
        val today = todayEpochDay()
        val answered = state.gradedIds.mapNotNull { id -> queue.firstOrNull { it.id == id } }
        val lines = remember(answered, today, freshAgain, tomorrow, nextFor) {
            answered.map { hifzRoundLine(catalog, it, today, freshAgain, tomorrow, nextFor[it.id]) }
        }
        when {
            stopped -> {
                Text("توقّفت المراجعة", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                Text("أجبت عن ${HifzReminder.pointsAbout(state.graded)}.", fontSize = 14.sp, color = TextDark)
                if (remaining > 0) Text("بقي في هذه المراجعة ${hifzCountLabel(remaining)}.", fontSize = 14.sp, color = TextMuted)
            }
            lines.isNotEmpty() -> {
                Text("بارك الله فيك", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                // Where each point answered now stands, all of them: a review asks a few, and the last is on the page.
                lines.forEach { line ->
                    Text(
                        line.text, fontSize = 14.sp, textAlign = TextAlign.Center,
                        fontWeight = if (line.milestone) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (line.milestone) GreenPrimary else TextDark, modifier = Modifier.testTag("hifz_review_progress"),
                    )
                }
                // The day's count when there were several, those of earlier reviews said apart.
                if (day.graded > 1) Text(
                    hifzDaySummary(day, before = day.points.count { it !in state.gradedIds }),
                    fontSize = 13.sp, color = TextMuted, textAlign = TextAlign.Center,
                )
                if (day.heldNow > 0 && lines.none { it.milestone && it.next == null }) Text("ثبت منها اليوم ${hifzCountLabel(day.heldNow)}.", fontSize = 14.sp, color = GreenPrimary)
            }
            else -> Text(if (stillDue > 0) "لم يبق موضع في هذه المراجعة" else "لا أسئلة الآن", fontSize = 16.sp, color = GreenPrimaryDark)
        }
        // Points whose verse was shown in this review, by another point's answer: asked at the next reminder.
        val deferred = state.deferredIds.size
        if (!stopped && deferred > 0) Text(
            if (deferred == 1) "موضع واحد ظهرت آيته في هذه المراجعة؛ نسألك عنه في تذكير لاحق."
            else if (deferred == 2) "موضعان ظهرت آيتاهما في هذه المراجعة؛ نسألك عنهما في تذكير لاحق." else "${hifzCountLabel(deferred)} ظهرت آياتها في هذه المراجعة؛ نسألك عنها في تذكير لاحق.",
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
            // The points due now are others than those above, unless the card stayed open past a fresh one's wait.
            val others = due.none { it in state.gradedIds }
            if (stillDue > 0) Text(
                "وحان وقتُ السؤال عن ${if (others) HifzReminder.otherPointsAbout(stillDue) else HifzReminder.pointsAbout(stillDue)}.",
                fontSize = 14.sp, color = TextDark,
            )
            else if (next != null && lines.none { it.next == next }) Text("السؤال القادم $next", fontSize = 14.sp, color = TextMuted)
        }
      }
        if (stopped) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = state::resume, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.testTag("hifz_review_resume")) {
                    Text("تابع الآن")
                }
                OutlinedButton(onClick = actions::dismiss, modifier = Modifier.testTag("hifz_review_later")) { Text("لاحقًا") }
            }
            return@Column
        }
        // The way on is the widest: more questions when some are due, else back to the reading.
        if (stillDue > 0) Button(
            onClick = actions::more, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
            modifier = Modifier.fillMaxWidth().testTag("hifz_review_more"),
        ) { Text("تابع مراجعة اليوم") }
        else Button(
            onClick = actions::dismiss, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
            modifier = Modifier.fillMaxWidth().testTag("hifz_review_done"),
        ) { Text("تم") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // This review's points, those listed above, not the day's: «للتوّ» says so beside the day's count.
            if (state.gradedIds.isNotEmpty()) TextButton(onClick = { actions.listenTo(state.gradedIds) }, modifier = Modifier.testTag("hifz_review_listen_done")) {
                Text("استمع إلى ما راجعته للتوّ")
            }
            if (stillDue > 0) TextButton(onClick = actions::dismiss, modifier = Modifier.testTag("hifz_review_done")) { Text("لاحقًا") }
        }
    }
}

/** The blank the reader fills aloud: a dashed box, never dots that read like a cut verse. */
@Composable
internal fun HifzAnswerSlot() {
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
                else "نذكّرك بها من جديد إلى أن تتذكّرها ${HifzReminder.recallsPhrase(count)} دون مساعدة" + (if (raised) " بدل ${HifzReminder.recallsPhrase(point.recalls)}، لأن الخطأ عاد" else "")
            else -> "تُحسب خطأً آخر"
        }
        return HifzGradeOutcome(effect, next)
    }
    if (point.fresh > 0) return when {
        grade == HifzGrade.Hesitated -> HifzGradeOutcome(if (short) "لا تُحسب تذكّرًا دون مساعدة" else "تُسجَّل إجابتك، لكنها لا تزيد عدد مرات التذكّر دون مساعدة", next)
        point.spacesNext -> HifzGradeOutcome(if (short) "بعدها تتباعد مواعيد مراجعتها" else "هذه الأخيرة: بعدها تتباعد مواعيد مراجعتها", tomorrow)
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
internal fun HifzGradeButton(label: String, detail: String, grade: HifzGrade, tag: String, onClick: () -> Unit) {
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
