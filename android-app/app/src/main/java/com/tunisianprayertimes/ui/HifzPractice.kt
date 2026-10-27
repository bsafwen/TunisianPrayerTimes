package com.tunisianprayertimes.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.quranAyah
import com.tunisianprayertimes.quran.hifz.HifzGrade
import com.tunisianprayertimes.quran.hifz.HifzLookAlikes
import com.tunisianprayertimes.quran.hifz.HifzPoint
import com.tunisianprayertimes.quran.hifz.HifzReminder
import com.tunisianprayertimes.quran.hifz.answerSafeOrder
import com.tunisianprayertimes.quran.hifz.answerVerses
import com.tunisianprayertimes.quran.hifz.hifzFixes
import com.tunisianprayertimes.quran.hifz.shows
import com.tunisianprayertimes.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What «مراجعة الدفتر» can ask of the reader. */
internal interface HifzPracticeActions {
    /**
     * Whether [point]'s question is really due now: asked as its answer is about to be shown, before that look puts it
     * off. Such an answer counts as in «مراجعة اليوم».
     */
    fun dueNow(point: HifzPoint): Boolean
    /** Verses whose answer is shown: their questions wait, as after any look at an answer. */
    fun seen(verses: Set<QuranVerseReference>)
    /**
     * [counted]: the point was due, and its answer counts as in «مراجعة اليوم». Otherwise only a verse forgotten is
     * written, as the slip it is; the other answers leave its plan as it was.
     */
    fun grade(point: HifzPoint, grade: HifzGrade, counted: Boolean)
    /** The verse before the point, once, as a cue. */
    fun listenBefore(point: HifzPoint)
    /** The right wording, once. */
    fun listen(point: HifzPoint)
    fun drill(point: HifzPoint)
    /** Words the verse went wrong at, told after «نسيت أو أخطأت». */
    fun addWords(point: HifzPoint, words: Set<Int>)
    /** The card is left: the audio it started stops. */
    fun leaveCard()
    fun close()
}

/** The same selection and linked questions are used for the notebook's preview and the practice it opens. */
internal data class HifzPracticePlan(
    val points: List<HifzPoint>,
    val selectedCount: Int,
) {
    val addedDueCount: Int get() = points.size - selectedCount
}

internal fun hifzPracticePlan(points: List<HifzPoint>, order: List<Long>, dueIds: Set<Long>): HifzPracticePlan {
    val byId = points.associateBy { it.id }
    val selected = order.distinct().mapNotNull { byId[it] }.toMutableList()
    val selectedCount = selected.size
    val due = points.filter { it.id in dueIds }
    while (true) {
        val linked = due.filter { question -> selected.none { it.id == question.id } && selected.any { it.shows(question.verse) } }
        if (linked.isEmpty()) break
        selected += linked
    }
    return HifzPracticePlan(selected.answerSafeOrder(), selectedCount)
}

private fun List<String>?.idsAt(i: Int): Set<Long> = this?.getOrNull(i)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty().toSet()

/**
 * «مراجعة الدفتر»: the notebook's points, as its list showed them, asked one after another whenever the reader wishes,
 * due or not: each verse covered first, its answer only after «أظهر». The reader goes from one to the next, or back, by
 * a swipe or the arrows, at any step. A card keeps what was done on it: shown, answered, drilled. One answer a card.
 *
 * An answer counts as in «مراجعة اليوم» only for a point whose question was due when its answer was shown; for the
 * others, only a verse forgotten changes anything, as the slip it is.
 */
@Stable
internal class HifzPracticeState private constructor(saved: List<String>?, ids: List<Long>, private val clock: () -> Long) {
    constructor(ids: List<Long>, clock: () -> Long = SystemClock::uptimeMillis) : this(null, ids, clock)

    /** The points, in the order asked. */
    val ids: List<Long> = saved?.getOrNull(0)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: ids
    /** The card in view; one past the last is the end. */
    var index by mutableIntStateOf(saved?.getOrNull(1)?.toIntOrNull() ?: 0)
        private set
    /** Which way the last step went: the next card comes from the side it lies on. */
    var forward by mutableStateOf(saved?.getOrNull(2) != "0")
        private set
    private var revealed by mutableStateOf(saved.idsAt(3))
    /** The cards whose question was due when their answer was shown. */
    private var counted by mutableStateOf(saved.idsAt(4))
    private var answers by mutableStateOf(
        saved?.getOrNull(5)?.split(',')?.mapNotNull { row ->
            val parts = row.split(':')
            val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val grade = parts.getOrNull(1)?.let { name -> HifzGrade.entries.firstOrNull { it.name == name } } ?: return@mapNotNull null
            id to grade
        }.orEmpty().toMap(),
    )
    /** What each answer did, as said when it was given: the point changes once it is written. */
    private var outcomes by mutableStateOf(
        saved?.getOrNull(6)?.split(RowSeparator)?.mapNotNull { row ->
            val parts = row.split(FieldSeparator, limit = 2)
            val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            id to parts.getOrNull(1).orEmpty()
        }.orEmpty().toMap(),
    )
    private var drilled by mutableStateOf(saved.idsAt(7))
    private var wordsAdded by mutableStateOf(saved.idsAt(8))
    private var shownKey: Any? = null
    private var shownAt = 0L

    /** The points still in the notebook, in the order asked. */
    fun cards(points: List<HifzPoint>): List<HifzPoint> = ids.mapNotNull { id -> points.firstOrNull { it.id == id } }

    fun revealed(point: HifzPoint): Boolean = point.id in revealed
    fun counted(point: HifzPoint): Boolean = point.id in counted
    /** Preview while covered; after reveal keep the decision made before showing the answer. */
    fun countsNow(point: HifzPoint, actions: HifzPracticeActions): Boolean =
        if (revealed(point)) counted(point) else actions.dueNow(point)
    fun answer(point: HifzPoint): HifzGrade? = answers[point.id]
    fun outcome(point: HifzPoint): String? = outcomes[point.id]
    fun drilled(point: HifzPoint): Boolean = point.id in drilled
    fun wordsAdded(point: HifzPoint): Boolean = point.id in wordsAdded
    val tally: HifzTally get() = HifzTally(
        held = answers.values.count { it == HifzGrade.Held },
        hesitated = answers.values.count { it == HifzGrade.Hesitated },
        again = answers.values.count { it == HifzGrade.Again },
    )

    /**
     * Notes the card drawn: a second tap meant for the button that was there a moment ago never acts on what replaced
     * it; see [settled]. [audioRunning]: the drill plays, its strip in place of its button.
     */
    fun shown(point: HifzPoint?, audioRunning: Boolean = false) {
        val key = listOf(index, point?.id, point?.let(::revealed), point?.let(::answer), point?.let(::drilled), audioRunning)
        if (key != shownKey) {
            shownKey = key
            shownAt = clock()
        }
    }

    fun settled(): Boolean = clock() - shownAt >= 400L

    /** To the card [delta] away, among [count] and the end after them; what the card in view played stops. */
    fun step(delta: Int, count: Int, actions: HifzPracticeActions) {
        val to = (index + delta).coerceIn(0, count)
        if (to == index) return
        actions.leaveCard()
        forward = delta > 0
        index = to
    }

    /** The cards were fewer than the place in view, some deleted meanwhile: the end, or the last one. */
    fun keepWithin(count: Int) {
        if (index > count) index = count
    }

    fun reveal(point: HifzPoint, actions: HifzPracticeActions) {
        if (!settled() || revealed(point)) return
        actions.leaveCard()
        // Before the look this is, which would put a due question off.
        if (actions.dueNow(point)) counted = counted + point.id
        revealed = revealed + point.id
        actions.seen(point.answerVerses())
    }

    /** [outcome]: what the answer does, said now. Remembered, the next card comes. */
    fun answer(point: HifzPoint, grade: HifzGrade, outcome: String, count: Int, actions: HifzPracticeActions) {
        if (!settled() || !revealed(point) || answer(point) != null) return
        answers = answers + (point.id to grade)
        outcomes = outcomes + (point.id to outcome)
        actions.grade(point, grade, counted(point))
        if (grade == HifzGrade.Held) step(1, count, actions)
    }

    fun drill(point: HifzPoint, actions: HifzPracticeActions) {
        if (!settled()) return
        drilled = drilled + point.id
        actions.drill(point)
    }

    fun addWords(point: HifzPoint, words: Set<Int>, actions: HifzPracticeActions) {
        if (wordsAdded(point)) return
        wordsAdded = wordsAdded + point.id
        actions.addWords(point, words)
    }

    companion object {
        private const val RowSeparator = "\u001E"
        private const val FieldSeparator = "\u001F"

        val Saver: Saver<HifzPracticeState, Any> = listSaver(
            save = { s ->
                listOf(
                    s.ids.joinToString(","), s.index.toString(), if (s.forward) "1" else "0", s.revealed.joinToString(","),
                    s.counted.joinToString(","), s.answers.entries.joinToString(",") { "${it.key}:${it.value.name}" },
                    s.outcomes.entries.joinToString(RowSeparator) { "${it.key}$FieldSeparator${it.value}" },
                    s.drilled.joinToString(","), s.wordsAdded.joinToString(","),
                )
            },
            restore = { HifzPracticeState(it, emptyList(), SystemClock::uptimeMillis) },
        )
    }
}

/**
 * What an answer in «مراجعة الدفتر» does, said under it: as in «مراجعة اليوم» for a point whose question was due
 * ([counted]) and for a verse forgotten, counted as the slip it is; null for the others, which change nothing.
 */
internal fun hifzPracticeOutcome(point: HifzPoint, grade: HifzGrade, counted: Boolean, again: String, tomorrow: String): HifzGradeOutcome? =
    if (counted || grade == HifzGrade.Again) hifzGradeOutcome(point, grade, again, tomorrow) else null

/** «تذكّرتها دون مساعدة»: an answer given, said back. */
private fun hifzPracticeAnswered(grade: HifzGrade): String = when (grade) {
    HifzGrade.Held -> "تذكّرتها دون مساعدة"
    HifzGrade.Hesitated -> "تذكّرتها بتردّد أو بمساعدة"
    HifzGrade.Again -> "نسيتها أو أخطأت فيها"
}

/**
 * «مراجعة الدفتر» in a sheet over the notebook, which it goes back to as it was. A swipe, or the arrows, go from one
 * card to the next or back; the cards slide as the notebook's corrections do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HifzPracticeSheet(
    state: HifzPracticeState,
    catalog: QuranCatalog,
    lookAlikes: HifzLookAlikes?,
    points: List<HifzPoint>,
    /** The recitation the practice started, shown above its buttons since the sheet hides the player; null for none. */
    audio: HifzAudio?,
    audioControls: HifzAudioControls,
    /** «غدًا بعد الفجر»: tomorrow's first reminder, where a spaced check or a once-a-day question comes. */
    tomorrow: String,
    actions: HifzPracticeActions,
) {
    val cards = remember(state.ids, points) { state.cards(points) }
    val count = cards.size
    LaunchedEffect(count) { state.keepWithin(count) }
    val index = state.index.coerceAtMost(count)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val step: (Int) -> Unit = { delta -> state.step(delta, count, actions) }
    ModalBottomSheet(onDismissRequest = actions::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        Column(
            Modifier.fillMaxWidth().quranSheetHeight().hifzStepSwipe(canBack = index > 0, canNext = index < count, onStep = step)
                .testTag("hifz_practice"),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("مراجعة الدفتر", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                IconButton(onClick = actions::close, modifier = Modifier.testTag("hifz_practice_close")) {
                    Icon(painterResource(R.drawable.ic_adhkar_close), "إغلاق")
                }
            }
            // Where the reader is, and the way on or back, as in the notebook's corrections.
            if (count > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { step(-1) }, enabled = index > 0, modifier = Modifier.testTag("hifz_practice_previous")) {
                    Icon(painterResource(R.drawable.ic_adhkar_back), "الموضع السابق", Modifier.size(20.dp))
                }
                Text(
                    if (index < count) "${index + 1} من $count" else "انتهت المواضع", fontSize = 13.sp, color = TextMuted,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        if (index < count) contentDescription = "الموضع ${index + 1} من $count"
                    }.testTag("hifz_practice_position"),
                )
                IconButton(onClick = { step(1) }, enabled = index < count, modifier = Modifier.testTag("hifz_practice_next")) {
                    Icon(painterResource(R.drawable.ic_adhkar_next), "الموضع التالي", Modifier.size(20.dp))
                }
            }
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    // In Arabic the next card comes from the left, as the mushaf's next page does.
                    val from = if (state.forward == rtl) -1 else 1
                    slideInHorizontally { it * from } togetherWith slideOutHorizontally { -it * from }
                },
                modifier = Modifier.weight(1f),
                label = "hifz_practice_card",
            ) { at ->
                // The card sliding out takes no tap: its buttons are the incoming card's now.
                val leaving = at != index
                Box(
                    if (!leaving) Modifier.fillMaxSize() else Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                    },
                ) {
                    val point = cards.getOrNull(at)
                    if (point == null) HifzPracticeEnd(state, count, actions)
                    else HifzPracticeCard(state, catalog, lookAlikes, point, at, count, audio?.takeIf { !leaving }, audioControls, tomorrow, actions, leaving)
                }
            }
        }
    }
}

/**
 * One card: the question, the verse covered; once «أظهر» is pressed, the answer, «هل تذكّرتها قبل أن تكشفها؟» and the
 * three answers; after a hesitation or a slip, the drill; the correction under all of it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HifzPracticeCard(
    state: HifzPracticeState,
    catalog: QuranCatalog,
    lookAlikes: HifzLookAlikes?,
    point: HifzPoint,
    at: Int,
    count: Int,
    audio: HifzAudio?,
    audioControls: HifzAudioControls,
    tomorrow: String,
    actions: HifzPracticeActions,
    leaving: Boolean,
) {
    val revealed = state.revealed(point)
    val answer = state.answer(point)
    val drill = answer != null && answer != HifzGrade.Held
    val running = state.drilled(point) && audio != null && audio.stoppable
    if (!leaving) state.shown(point, running)
    val fixes = remember(point, lookAlikes) { hifzFixes(catalog, point, lookAlikes) }
    val scroll = rememberScrollState()
    LaunchedEffect(revealed) { scroll.scrollTo(0) }
    // When this point would be asked again after a slip: its own next reminder, said with its answers.
    val context = LocalContext.current.applicationContext
    val again by produceState("في تذكير لاحق", point.id) {
        while (true) {
            value = withContext(Dispatchers.IO) { HifzReminder.freshAgainLabel(context, point = point) }
            delay(60_000L)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HifzReviewHeading(catalog, point, revealed = revealed, training = drill)
        if (fixes.isEmpty()) {
            Text("هذه الآية غير موجودة في الفهرس.", color = TextMuted)
            return@Column
        }
        if (!revealed) {
            val countedNow = state.countsNow(point, actions)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.testTag("hifz_practice_before_reveal")) {
                Text(
                    if (countedNow) "سؤال حان وقته" else "تدريب اختياري",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark,
                )
                Text(
                    if (countedNow) "يُحسب جوابك في متابعة الحفظ. إن نسيت أو أخطأت، نُسجّل ذلك ونبدأ تذكيرك من جديد."
                    else "التذكّر هنا لا يغيّر موعد المراجعة. إن نسيت أو أخطأت، نُسجّل ذلك ونبدأ تذكيرك من جديد.",
                    fontSize = 12.sp, color = TextMuted,
                )
            }
            val talkBack = rememberTouchExploration()
            val previous = point.verse.takeIf { it.ayah > 1 }?.let { QuranVerseReference(it.surah, it.ayah - 1) }
            val inText: @Composable () -> Unit = {
                Surface(shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, CardBorder)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        previous?.let { HifzReviewCueText(catalog, it) }
                        Text(if (previous == null) "أول آية في السورة مغطاة." else "الآية التي بعدها مغطاة.", fontSize = 12.sp, color = QuranHifzViolet)
                        HifzAnswerSlot()
                    }
                }
            }
            if (!talkBack) QuranVerseExcerpt(
                catalog, point.verse, "${hifzVerseAbout(catalog, point.verse)} مغطاة في المصحف",
                linesBefore = if (previous != null) 1 else 0, cover = true, fallback = inText,
            )
            if (talkBack) inText()
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (previous != null) OutlinedButton(
                    onClick = { if (state.settled()) actions.listenBefore(point) },
                    // Pills; with large text, taller, rounded corners that never cut the words.
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), shape = wordsSafeShape(ButtonDefaults.outlinedShape),
                    modifier = Modifier.weight(1f).fillMaxHeight().testTag("hifz_practice_cue"),
                ) { Text("استمع إلى ${quranAyah(previous.ayah)}", textAlign = TextAlign.Center) }
                Button(
                    onClick = { state.reveal(point, actions) }, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                    shape = wordsSafeShape(ButtonDefaults.shape),
                    modifier = Modifier.weight(1f).fillMaxHeight().testTag("hifz_practice_reveal"),
                ) { Text("أظهر") }
            }
            if (audio != null) HifzAudioStrip(audio, audioControls)
            return@Column
        }
        fixes.forEach { HifzAnswerLine(it) }
        if (point.key.isNotBlank()) Text(withQuranQuotes("ملاحظتك: ${point.key}"), fontSize = 13.sp, color = QuranHifzViolet)
        if (audio != null) HifzAudioStrip(audio, audioControls)
        if (answer == null) {
            Text("هل تذكّرتها قبل أن تكشفها؟", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
            val counted = state.counted(point)
            // Whether the answer counts, said once: a question due now counts as in «مراجعة اليوم»; the others
            // change only when the verse was forgotten.
            Text(
                if (counted) "حان موعد السؤال عنها، فتُحسب إجابتك في متابعة الحفظ." else "هذا تدريب اختياري: التذكّر لا يغيّر موعد المراجعة، والنسيان أو الخطأ يُسجّل.",
                fontSize = 12.sp, color = TextMuted, modifier = Modifier.testTag("hifz_practice_counts"),
            )
            val outcomes = HifzGrade.entries.associateWith { hifzPracticeOutcome(point, it, counted, again, tomorrow) }
            // The same time on every answer is said once, under them, as in «مراجعة اليوم».
            val shared = outcomes.values.map { it?.back }.distinct().singleOrNull()
            HifzGrade.entries.forEach { grade ->
                val outcome = outcomes.getValue(grade)
                val label = when (grade) {
                    HifzGrade.Held -> "دون مساعدة"
                    HifzGrade.Hesitated -> "بتردّد أو تلميح"
                    HifzGrade.Again -> "نسيت أو أخطأت"
                }
                HifzGradeButton(label, outcome?.text(withBack = shared == null, short = false).orEmpty(), grade, tag = "hifz_practice_grade_${grade.name.lowercase()}") {
                    state.answer(point, grade, outcome?.text(withBack = true, short = false) ?: "لم تُحسب إجابتك في متابعة الحفظ، لأن موعد السؤال عنها لم يحن.", count, actions)
                }
            }
            if (shared != null) Text(
                "نسألك عنها $shared أيًّا كان جوابك.", fontSize = 12.sp, color = TextMuted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("hifz_practice_answered")) {
                Text(hifzPracticeAnswered(answer), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                state.outcome(point)?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = TextMuted) }
            }
        }
        if (drill) {
            val drilled = state.drilled(point)
            if (!running) {
                val label = if (drilled) "استمع وكرّر مرة أخرى" else "استمع وكرّر"
                val drillModifier = Modifier.fillMaxWidth().testTag("hifz_practice_drill")
                if (!drilled) Button(
                    onClick = { state.drill(point, actions) }, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = drillModifier,
                ) { Text(label) }
                else OutlinedButton(onClick = { state.drill(point, actions) }, modifier = drillModifier) { Text(label) }
            }
            val nextLabel = if (at + 1 < count) "التالي" else "انتهيت"
            val nextModifier = Modifier.fillMaxWidth().testTag("hifz_practice_go_on")
            if (drilled && !running) Button(
                onClick = { if (state.settled()) state.step(1, count, actions) },
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = nextModifier,
            ) { Text(nextLabel) }
            else OutlinedButton(onClick = { if (state.settled()) state.step(1, count, actions) }, modifier = nextModifier) { Text(nextLabel) }
            // The word it went wrong at this time, after «نسيت أو أخطأت».
            if (answer == HifzGrade.Again) {
                var wordsOpen by rememberSaveable(point.id) { mutableStateOf(false) }
                if (state.wordsAdded(point)) Text("أضفناها.", fontSize = 12.sp, color = QuranHifzViolet)
                else if (!wordsOpen) TextButton(onClick = { wordsOpen = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("أخطأت في كلمة أخرى؟", fontSize = 13.sp)
                } else HifzReviewWordPicker(
                    catalog, point, onAdd = { state.addWords(point, it, actions) }, onAdded = { wordsOpen = false }, onCancel = { wordsOpen = false },
                )
            }
        } else TextButton(onClick = { if (state.settled()) actions.listen(point) }, modifier = Modifier.testTag("hifz_practice_listen")) {
            Text("استمع للصواب", maxLines = 1)
        }
        HifzFixBody(catalog, fixes, lookAlikes, exactWords = false, practice = false, learningNotes = !drill, note = false)
    }
}

/** After the last card: what was answered, and the way back to the notebook. */
@Composable
private fun HifzPracticeEnd(state: HifzPracticeState, count: Int, actions: HifzPracticeActions) {
    val tally = state.tally
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // «انتهت المواضع» is said above, between the arrows.
        if (tally.graded > 0) Text("بارك الله فيك", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
        // As the end of «مراجعة اليوم» says it: one answer for all is said once.
        if (tally.graded > 0) Text(
            "راجعت ${HifzReminder.pointsObject(tally.graded)}" + if (listOf(tally.held, tally.hesitated, tally.again).count { it > 0 } == 1) {
                val them = when (tally.graded) {
                    1 -> "ه"
                    2 -> "هما"
                    else -> "ها كلها"
                }
                "، " + when {
                    tally.held > 0 -> "وتذكّرت$them دون مساعدة."
                    tally.hesitated > 0 -> "وتذكّرت$them بتردّد أو بمساعدة."
                    else -> "ونسيت أو أخطأت في$them."
                }
            } else ": " + listOfNotNull(
                "تذكّرت ${HifzReminder.pointsObject(tally.held)} دون مساعدة".takeIf { tally.held > 0 },
                "تذكّرت ${HifzReminder.pointsObject(tally.hesitated)} بتردّد أو بمساعدة".takeIf { tally.hesitated > 0 },
                "نسيت أو أخطأت في ${HifzReminder.pointsAbout(tally.again)}".takeIf { tally.again > 0 },
            ).mapIndexed { i, part -> if (i == 0) part else "و$part" }.joinToString("، ") + ".",
            fontSize = 14.sp, color = TextDark, textAlign = TextAlign.Center, modifier = Modifier.testTag("hifz_practice_summary"),
        )
        val unanswered = count - tally.graded
        if (unanswered > 0) Text("لم تجب عن ${HifzReminder.pointsAbout(unanswered)}.", fontSize = 13.sp, color = TextMuted, textAlign = TextAlign.Center)
        Button(
            onClick = actions::close, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
            modifier = Modifier.fillMaxWidth().testTag("hifz_practice_done"),
        ) { Text("تم") }
    }
}
