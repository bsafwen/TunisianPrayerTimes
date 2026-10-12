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
import com.tunisianprayertimes.quran.hifz.HifzGrade
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzLookAlikes
import com.tunisianprayertimes.quran.hifz.HifzMasteredStep
import com.tunisianprayertimes.quran.hifz.HifzPoint
import com.tunisianprayertimes.quran.hifz.daysAfter
import com.tunisianprayertimes.quran.hifz.hifzFix
import com.tunisianprayertimes.quran.hifz.holdsNext
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
    fun dismiss()
}

/**
 * A grade given in this review, for its undo and the final tally: the schedule the point had before it, which is all
 * a grade changes. Kept as text, so that a re-created activity can still take it back.
 */
internal data class HifzGiven(
    val id: Long,
    val grade: HifzGrade,
    val step: Int,
    val dueDay: Long,
    val masteredAt: Long?,
    /** The grade made the point held («ثبتت»). */
    val heldNow: Boolean,
) {
    fun encode(): String = listOf(id, grade.name, step, dueDay, masteredAt ?: "", if (heldNow) 1 else 0).joinToString("|")

    companion object {
        fun of(point: HifzPoint, grade: HifzGrade) =
            HifzGiven(point.id, grade, point.step, point.dueDay, point.masteredAt, grade == HifzGrade.Held && point.holdsNext)

        fun decode(value: String?): HifzGiven? {
            val parts = value?.split('|')?.takeIf { it.size == 6 } ?: return null
            return HifzGiven(
                id = parts[0].toLongOrNull() ?: return null,
                grade = HifzGrade.entries.firstOrNull { it.name == parts[1] } ?: return null,
                step = parts[2].toIntOrNull() ?: return null,
                dueDay = parts[3].toLongOrNull() ?: return null,
                masteredAt = parts[4].toLongOrNull(),
                heldNow = parts[5] == "1",
            )
        }
    }
}

private fun List<String>?.intAt(i: Int): Int = this?.getOrNull(i)?.toIntOrNull() ?: 0
private fun List<String>?.longAt(i: Int): Long? = this?.getOrNull(i)?.toLongOrNull()

/**
 * The day's checks: the question first, said aloud, then the fix and an honest answer. «أتقنتها» and «ترددت»
 * move on; «ما زالت تحتاج تدريبًا» stays on the card for its drill until «الموضع التالي». Audio a card starts stops
 * when the card is left, and the last answer can be taken back.
 *
 * Kept by the reader rather than by a card: the review shows on the page or in a sheet, and goes from one to the other
 * with its place, its answers and its undo. Saved as text, so a re-created activity finds it again.
 */
@Stable
internal class HifzReviewState private constructor(saved: List<String>?, private val clock: () -> Long) {
    /** [clock]: [SystemClock.uptimeMillis], or a test's. */
    constructor(clock: () -> Long = SystemClock::uptimeMillis) : this(null, clock)

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
    /** The card answered «ما زالت تحتاج تدريبًا», kept for its drill until «الموضع التالي». */
    private var trainingId by mutableStateOf(saved.longAt(7))
    private var drilledId by mutableStateOf(saved.longAt(8))
    /** The last grade, as [HifzGiven.encode]. */
    private var lastGiven by mutableStateOf(saved?.getOrNull(9)?.ifEmpty { null })
    var howItWorks by mutableStateOf(saved?.getOrNull(10) == "1")
    /** On the page: the card folded down to its title, for the page under it. A new card opens unfolded. */
    var folded by mutableStateOf(saved?.getOrNull(11) == "1")
    private var shownKey: Any? = null
    private var shownAt = 0L

    fun index(queue: List<HifzPoint>): Int = cardId?.let { id -> queue.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: position
    fun point(queue: List<HifzPoint>): HifzPoint? = queue.getOrNull(index(queue))
    fun revealed(point: HifzPoint?): Boolean = point != null && revealedId == point.id
    fun training(point: HifzPoint?): Boolean = point != null && trainingId == point.id
    fun drilled(point: HifzPoint?): Boolean = point != null && drilledId == point.id
    val graded: Int get() = held + hesitated + again
    /** The last grade, while its point is still in the review to be taken back. */
    fun undoable(queue: List<HifzPoint>): HifzGiven? = HifzGiven.decode(lastGiven)?.takeIf { given -> queue.any { it.id == given.id } }

    /**
     * Notes the card drawn: a second tap meant for the button that was there a moment ago never acts on what
     * replaced it; see [settled].
     */
    fun shown(point: HifzPoint?) {
        val key = Triple(point?.id, revealed(point), training(point))
        if (key != shownKey) {
            shownKey = key
            shownAt = clock()
        }
    }

    fun settled(): Boolean = clock() - shownAt >= 400L

    /** Another card without «الموضع التالي»: the one in view was deleted, and its audio stops with it. */
    fun follow(queue: List<HifzPoint>, actions: HifzReviewActions) {
        val index = index(queue)
        val point = queue.getOrNull(index)
        if (cardId != null && point?.id != cardId) actions.leaveCard()
        if (point?.id != cardId) folded = false
        cardId = point?.id
        position = index
    }

    fun reveal(queue: List<HifzPoint>, actions: HifzReviewActions) {
        val point = point(queue) ?: return
        if (!settled() || revealed(point)) return
        actions.leaveCard()
        revealedId = point.id
        folded = false
    }

    fun next(queue: List<HifzPoint>, actions: HifzReviewActions) {
        val index = index(queue)
        actions.leaveCard()
        trainingId = null
        position = index + 1
        cardId = queue.getOrNull(index + 1)?.id
        folded = false
    }

    fun grade(queue: List<HifzPoint>, actions: HifzReviewActions, grade: HifzGrade) {
        val shown = point(queue) ?: return
        if (!settled()) return
        val given = HifzGiven.of(shown, grade)
        lastGiven = given.encode()
        actions.grade(shown, grade)
        count(grade, 1)
        if (given.heldNow) heldNow++
        if (grade == HifzGrade.Again) trainingId = shown.id else next(queue, actions)
    }

    fun undo(queue: List<HifzPoint>, actions: HifzReviewActions) {
        val given = undoable(queue) ?: return
        actions.leaveCard()
        actions.undo(given)
        count(given.grade, -1)
        if (given.heldNow) heldNow--
        cardId = given.id
        trainingId = null
        revealedId = given.id
        lastGiven = null
        folded = false
    }

    fun drill(point: HifzPoint, actions: HifzReviewActions) {
        // A second tap on the grade lands here: it never starts the drill.
        if (!settled()) return
        drilledId = point.id
        actions.drill(point)
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
                    s.lastGiven, if (s.howItWorks) 1 else 0, if (s.folded) 1 else 0,
                ).map { it?.toString().orEmpty() }
            },
            restore = { HifzReviewState(it, SystemClock::uptimeMillis) },
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
    drillGap: Float,
    /** Points still due once this review's are answered. */
    stillDue: Int,
    /** Days until the next check of any point, for the end; null without points. */
    nextCheckIn: Long?,
    actions: HifzReviewActions,
) {
    val point = state.point(queue)
    state.shown(point)
    ModalBottomSheet(
        onDismissRequest = { actions.leaveCard(); actions.dismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream,
    ) {
        Column(Modifier.fillMaxWidth().quranSheetHeight().padding(horizontal = 16.dp).testTag("hifz_review")) {
            HifzReviewTitle(state, queue, point, actions)
            if (point == null) {
                HifzReviewEnd(state, stillDue, nextCheckIn, actions, Modifier.weight(1f).padding(vertical = 32.dp))
                return@Column
            }
            val fix = remember(point, lookAlikes) { hifzFix(catalog, point, lookAlikes) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HifzReviewHeading(catalog, point)
                if (fix == null) return@Column
                if (!state.revealed(point)) {
                    Text(fix.question, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
                    Surface(shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, CardBorder)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            when (point.kind) {
                                HifzKind.Skip, HifzKind.Start -> {
                                    // No «…» before the verse's end: its label says so, and the slot is the only blank.
                                    fix.before?.let { before ->
                                        Text("آخر الآية ${point.verse.ayah - 1}:", fontSize = 12.sp, color = TextMuted)
                                        HifzVerseText(before, from = (before.size - 5).coerceAtLeast(0))
                                    }
                                    Text(
                                        if (point.kind == HifzKind.Start) "أول الآية ${point.verse.ayah}:" else "الآية ${point.verse.ayah}:",
                                        fontSize = 12.sp, color = QuranHifzViolet,
                                    )
                                    HifzAnswerSlot()
                                }
                                else -> HifzVerseText(fix.words, hidden = fix.hidden)
                            }
                        }
                    }
                    if (point.key.isNotBlank()) Text("ضابطي: ${point.key}", fontSize = 13.sp, color = QuranHifzViolet)
                } else {
                    HifzFixBody(catalog, fix, lookAlikes)
                    HifzReviewAnswerActions(state, point, actions)
                }
            }
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HifzReviewControls(state, catalog, queue, point, audio, audioControls, drillGap, actions)
            }
        }
    }
    if (state.howItWorks) HifzHowItWorksDialog { state.howItWorks = false }
}

/**
 * The day's review on the page itself: the reader opens each point's page, covers the verse asked and what follows it,
 * and this card floats over the foot of the page with the question and the answers. The cover erases under the
 * finger as in «مراجعة». Where the page can hide no single word, the card shows the verse with its blank.
 */
@Composable
internal fun HifzReviewCard(
    state: HifzReviewState,
    catalog: QuranCatalog,
    lookAlikes: HifzLookAlikes?,
    queue: List<HifzPoint>,
    audio: HifzAudio?,
    audioControls: HifzAudioControls,
    drillGap: Float,
    stillDue: Int,
    nextCheckIn: Long?,
    actions: HifzReviewActions,
    /** The tallest the card grows; what does not fit scrolls, the buttons stay. */
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
    QuranFloatingCard(modifier.widthIn(max = 560.dp).testTag("hifz_review_card")) {
        Column(Modifier.heightIn(max = maxHeight).padding(start = 14.dp, end = 4.dp, bottom = 10.dp)) {
            HifzReviewTitle(state, queue, point, actions, fontSize = 14.sp) {
                IconButton(onClick = { state.folded = !state.folded }, modifier = Modifier.size(40.dp).testTag("hifz_review_fold")) {
                    Icon(
                        painterResource(if (state.folded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                        if (state.folded) "أظهر بطاقة المراجعة" else "اطوِ البطاقة لترى الصفحة", Modifier.size(22.dp), tint = GreenPrimaryDark,
                    )
                }
            }
            if (state.folded) return@Column
            if (point == null) {
                HifzReviewEnd(state, stillDue, nextCheckIn, actions, Modifier.padding(end = 10.dp, top = 4.dp, bottom = 6.dp))
                return@Column
            }
            val fix = remember(point, lookAlikes) { hifzFix(catalog, point, lookAlikes) }
            // Only a word the page cannot hide is asked in text: the verse whole, or its opening, is covered on the page.
            val inText = point.kind != HifzKind.Skip && point.kind != HifzKind.Start
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HifzReviewHeading(catalog, point, marker = true)
                if (fix == null) return@Column
                if (!state.revealed(point)) {
                    Text(fix.question, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
                    // What the reader goes on from, when the page cannot show it.
                    if (!inText && beforeLabel != null) fix.before?.let { before ->
                        Text(beforeLabel, fontSize = 12.sp, color = TextMuted)
                        HifzVerseText(before, from = (before.size - 5).coerceAtLeast(0), fontSize = 18.sp)
                    }
                    if (inText) Surface(shape = RoundedCornerShape(12.dp), color = Color.White, border = BorderStroke(1.dp, CardBorder)) {
                        HifzVerseText(fix.words, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), hidden = fix.hidden, fontSize = 18.sp)
                    }
                    if (point.key.isNotBlank()) Text("ضابطي: ${point.key}", fontSize = 13.sp, color = QuranHifzViolet)
                    if (eraserHint) Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AdhkarSoftGreen).padding(start = 10.dp).testTag("hifz_review_eraser_hint"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("المس الغطاء على الصفحة لتكشف كلمة، أو اضغط مطولًا ثم اسحب لتكشف أكثر", Modifier.weight(1f), fontSize = 12.sp, color = GreenPrimaryDark)
                        IconButton(onClick = onEraserHintClosed, modifier = Modifier.size(36.dp)) {
                            Icon(painterResource(R.drawable.ic_adhkar_close), "إخفاء التلميح", Modifier.size(14.dp), tint = GreenPrimaryDark)
                        }
                    }
                } else {
                    // The verse is on the page now; the card adds what the page cannot show: the look-alike, the words.
                    if (inText) HifzFixBody(catalog, fix, lookAlikes)
                    HifzReviewAnswerActions(state, point, actions)
                }
            }
            // The buttons stay in view under what scrolls, but never take the whole card: on a short screen or with
            // large text they scroll too, and the verse named above them keeps some room.
            Column(
                Modifier.fillMaxWidth().heightIn(max = (maxHeight - 160.dp).coerceAtLeast(120.dp)).verticalScroll(rememberScrollState())
                    .padding(top = 8.dp, end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HifzReviewControls(state, catalog, queue, point, audio, audioControls, drillGap, actions)
            }
        }
    }
    if (state.howItWorks) HifzHowItWorksDialog { state.howItWorks = false }
}

/** «مراجعة اليوم 3 من 5», the undo of the last answer, and the end of the review. */
@Composable
private fun HifzReviewTitle(
    state: HifzReviewState,
    queue: List<HifzPoint>,
    point: HifzPoint?,
    actions: HifzReviewActions,
    fontSize: TextUnit = 18.sp,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (point != null) "مراجعة اليوم ${state.index(queue) + 1} من ${queue.size}" else "مراجعة اليوم",
            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark,
        )
        if (state.undoable(queue) != null) TextButton(onClick = { state.undo(queue, actions) }, modifier = Modifier.testTag("hifz_review_undo")) { Text("تراجع") }
        TextButton(onClick = { actions.leaveCard(); actions.dismiss() }, modifier = Modifier.testTag("hifz_review_end")) { Text("إنهاء") }
        trailing()
    }
}

/** The verse asked, with its state, and the slip recorded there. [marker]: the page shows the verse in this colour. */
@Composable
private fun HifzReviewHeading(catalog: QuranCatalog, point: HifzPoint, marker: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (marker) {
            QuranHifzSquare(12.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text("${hifzVerseName(catalog, point.verse)} · ${hifzPage(catalog, point.verse).orEmpty()}", Modifier.weight(1f),
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
        when {
            point.mastered -> Text("ثبتت · مراجعة للتأكد", fontSize = 12.sp, color = GreenPrimary)
            point.needsCare -> Text("يحتاج عناية · ${hifzSlipsLabel(point.slips)}", fontSize = 12.sp, color = QuranHifzViolet)
        }
    }
    Text("الخطأ الذي سجّلته: ${point.kind.label}", fontSize = 12.sp, color = TextMuted)
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

/** Under the card: what plays, then «أظهر», the answers, or the drill of a verse that still needs it. */
@Composable
private fun ColumnScope.HifzReviewControls(
    state: HifzReviewState,
    catalog: QuranCatalog,
    queue: List<HifzPoint>,
    point: HifzPoint,
    audio: HifzAudio?,
    audioControls: HifzAudioControls,
    drillGap: Float,
    actions: HifzReviewActions,
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
            Text("سُجّلت: ما زالت تحتاج تدريبًا · تعود غدًا", fontSize = 13.sp, color = GreenPrimaryDark, modifier = Modifier.testTag("hifz_review_training"))
            Button(
                onClick = { state.drill(point, actions) },
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.fillMaxWidth().testTag("hifz_review_drill"),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (state.drilled(point)) "أعد التدريب" else "درّبها الآن", fontWeight = FontWeight.SemiBold)
                    Text(
                        hifzDrillLabel(point, catalog.surahs.getOrNull(point.verse.surah - 1)?.verseCount, drillGap) { catalog.surahs.getOrNull(it - 1)?.name.orEmpty() },
                        fontSize = 12.sp, textAlign = TextAlign.Center,
                    )
                }
            }
            OutlinedButton(onClick = { if (state.settled()) state.next(queue, actions) }, modifier = Modifier.fillMaxWidth().testTag("hifz_review_next")) {
                Text(if (state.index(queue) + 1 < queue.size) "الموضع التالي" else "إنهاء المراجعة")
            }
        }
        else -> {
            Text("كيف كانت قراءتك لها من حفظك؟", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
            HifzGradeButton("أتقنتها", hifzGradeDetail(point, HifzGrade.Held), primary = true, tag = "hifz_grade_held") { state.grade(queue, actions, HifzGrade.Held) }
            HifzGradeButton("ترددت", hifzGradeDetail(point, HifzGrade.Hesitated), tag = "hifz_grade_hesitated") { state.grade(queue, actions, HifzGrade.Hesitated) }
            HifzGradeButton("ما زالت تحتاج تدريبًا", hifzGradeDetail(point, HifzGrade.Again), tag = "hifz_grade_again") { state.grade(queue, actions, HifzGrade.Again) }
            TextButton(onClick = { state.howItWorks = true }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("hifz_how_it_works")) {
                Text("كيف تعمل المراجعة؟", fontSize = 13.sp)
            }
        }
    }
}

/** The review is over: what was answered, and what is left for today. */
@Composable
private fun HifzReviewEnd(state: HifzReviewState, stillDue: Int, nextCheckIn: Long?, actions: HifzReviewActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.graded > 0) {
            Text("بارك الله فيك", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            Text("راجعت اليوم: ${hifzCountLabel(state.graded)}", fontSize = 14.sp, color = TextMuted)
            Text(
                listOfNotNull(
                    "أتقنتها: ${state.held}".takeIf { state.held > 0 },
                    "ترددت: ${state.hesitated}".takeIf { state.hesitated > 0 },
                    "ما زالت تحتاج تدريبًا: ${state.again}".takeIf { state.again > 0 },
                ).joinToString(" · "),
                fontSize = 14.sp, color = TextDark, textAlign = TextAlign.Center,
            )
            if (state.heldNow > 0) Text("ثبت منها: ${hifzCountLabel(state.heldNow)}", fontSize = 14.sp, color = GreenPrimary)
        } else Text(if (stillDue > 0) "لم يبق موضع في هذه المراجعة" else "لم يبق موضع للمراجعة اليوم", fontSize = 16.sp, color = GreenPrimaryDark)
        if (stillDue > 0) Text("وبقي للمراجعة اليوم: ${hifzCountLabel(stillDue)}", fontSize = 14.sp, color = TextDark)
        else if (nextCheckIn != null) Text("المراجعة القادمة ${hifzWhen(nextCheckIn)}", fontSize = 14.sp, color = TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (stillDue > 0) OutlinedButton(onClick = actions::more, modifier = Modifier.testTag("hifz_review_more")) { Text("تابع المراجعة") }
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

/** What answering [grade] means for [point]: how the reading went, then when it comes back, said as such. */
internal fun hifzGradeDetail(point: HifzPoint, grade: HifzGrade): String {
    val back = hifzWhen(point.daysAfter(grade).toLong())
    return when (grade) {
        HifzGrade.Held -> when {
            point.holdsNext -> "دون تردد · تثبت، وتعود $back للتأكد"
            point.mastered -> "دون تردد · تعود $back للتأكد"
            else -> "دون تردد · تعود $back"
        }
        HifzGrade.Hesitated -> "صحيحة بعد تردد · تعود $back" + if (point.mastered && point.step - 1 < HifzMasteredStep) " · تخرج من «ثبتت»" else ""
        HifzGrade.Again -> "أخطأت أو نسيت · تعود $back" + when {
            point.mastered -> " · تخرج من «ثبتت»"
            point.step > 0 -> " · وتبدأ مددها من جديد"
            else -> ""
        }
    }
}

/** A grade: its name, and under it what it means and when the verse comes back. */
@Composable
private fun HifzGradeButton(label: String, detail: String, primary: Boolean = false, tag: String, onClick: () -> Unit) {
    val padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    val content: @Composable RowScope.() -> Unit = {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
    if (primary) Button(
        onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), contentPadding = padding,
        modifier = Modifier.fillMaxWidth().testTag(tag), content = content,
    )
    else OutlinedButton(onClick = onClick, contentPadding = padding, modifier = Modifier.fillMaxWidth().testTag(tag), content = content)
}
