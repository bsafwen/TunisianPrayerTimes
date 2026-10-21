package com.tunisianprayertimes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.normalizeQuranSearch
import com.tunisianprayertimes.quran.hifz.HifzGrade
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzPoint
import com.tunisianprayertimes.quran.hifz.HifzReminder
import com.tunisianprayertimes.quran.hifz.HifzReminder.NB
import com.tunisianprayertimes.quran.hifz.HifzFreshGapMs
import com.tunisianprayertimes.quran.hifz.HifzFreshRecalls
import com.tunisianprayertimes.quran.hifz.HifzRoundSize
import com.tunisianprayertimes.quran.hifz.HifzSettings
import com.tunisianprayertimes.quran.hifz.dueToday
import com.tunisianprayertimes.quran.hifz.answerVerses
import com.tunisianprayertimes.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What «المراجعة» can ask of the reader. */
internal interface HifzHomeActions {
    /** The launcher's «سجّل موضعًا» opened its dialog: the request is done. */
    fun recordShown()
    fun review()
    /** The day's review, starting with [point]: tapped in the «اليوم» list before it was checked. */
    fun reviewFrom(point: HifzPoint)
    fun listen(points: List<HifzPoint>)
    /** Opens [point]'s correction; [order], the notebook's points as listed, goes on from it to the next. */
    fun open(point: HifzPoint, order: List<Long>)
    fun record(verse: QuranVerseReference)
    fun settings()
    fun dismiss()
}

/**
 * A change made from the notebook, said at its top with its undo: the reader's own snackbar would be under the sheet.
 * [again]: «سجّل موضعًا آخر», after a record; [correction]: its «التصحيح».
 */
@Immutable
internal data class HifzNotice(
    val text: String,
    val undo: (() -> Unit)?,
    val again: (() -> Unit)? = null,
    val correction: (() -> Unit)? = null,
    /** What the undo is called: «إلغاء التسجيل» for a mistake recorded, a bare «تراجع» reading as «تُراجِع» here. */
    val undoLabel: String = "تراجع",
)

/**
 * When the fresh points not asked now come, worked out off the main thread from their reminders: each row's line, and
 * the hub's next ones of today.
 */
@Immutable
internal data class HifzLaterTimes(
    /** A fresh point's next question, by id, as its row says it: «نسألك عنها اليوم الساعة 15:35». */
    val rows: Map<Long, String> = emptyMap(),
    /** How many fresh points the first later reminder of today asks, and when: 3 to «اليوم الساعة 15:35». */
    val laterToday: Pair<Int, String>? = null,
)

/**
 * «المراجعة»: the day's questions, which never give their answers before they are asked; the notebook of mistakes; and
 * recording a mistake made away from the mushaf. Nothing else: a mistake is recorded, then asked, verse covered, until
 * it holds.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HifzHomeSheet(
    catalog: QuranCatalog,
    points: List<HifzPoint>,
    settings: HifzSettings,
    today: Long,
    /** The time the points due are taken at; see [HifzPoint.isDue]. */
    now: Long,
    /** When the next fresh point is asked, «اليوم بعد المغرب», when it comes before any spaced check; null for none. */
    nextFresh: String? = null,
    /** «غدًا بعد الفجر»: the first reminder of the next spaced check's day, [nextCheckIn] days on; null to say its day only. */
    nextCheckLabel: String? = null,
    nextCheckIn: Long? = null,
    /** Opened from the launcher's «سجّل موضعًا»: ask for the verse at once. */
    startRecording: Boolean = false,
    notice: HifzNotice? = null,
    onNoticeClosed: () -> Unit = {},
    /** What plays, the reader's own recitation too, shown at the foot since the sheet hides the player. */
    audio: HifzAudio? = null,
    audioControls: HifzAudioControls? = null,
    /** The points that may be asked now: a point whose verse was just looked at is not. */
    eligibleDueIds: Set<Long>? = null,
    /** When the fresh points not asked now come. */
    later: HifzLaterTimes = HifzLaterTimes(),
    actions: HifzHomeActions,
) {
    /** Every point due, the next review's first: «اليوم» is the day's, not one review's. */
    val eligible = remember(points, eligibleDueIds) { points.filter { eligibleDueIds == null || it.id in eligibleDueIds } }
    val dueAll = remember(eligible, today, now) { eligible.dueToday(today, now) }
    val batch = remember(eligible, today, now) { eligible.dueToday(today, now, HifzRoundSize) }
    var howItWorks by rememberSaveable { mutableStateOf(false) }
    var notebook by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val homeScroll = rememberLazyListState()
    val notebookScroll = rememberLazyListState()
    var tab by rememberSaveable { mutableIntStateOf(1) }
    var query by rememberSaveable { mutableStateOf("") }
    var kindFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var surahFilter by rememberSaveable { mutableStateOf<Int?>(null) }
    var away by rememberSaveable { mutableStateOf(false) }
    /** A due point tapped before its check: seeing its answer now would spoil it, so the reader is asked first. */
    var spoilId by rememberSaveable { mutableStateOf<Long?>(null) }
    /** The point tapped, whose correction would answer [spoilId]'s question: itself, or one showing its verse. */
    var spoilOpenId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(startRecording) {
        if (startRecording) {
            away = true
            actions.recordShown()
        }
    }
    LaunchedEffect(notebook) {
        if (notebook) notebookScroll.scrollToItem(0)
    }
    // Saving returns here before the result arrives. Inserting the notice above the keyed first item keeps
    // that item anchored, so explicitly bring the new confirmation and its Undo into view.
    LaunchedEffect(notice, notebook) {
        if (notice != null) (if (notebook) notebookScroll else homeScroll).scrollToItem(0)
    }
    // The notebook as listed: its filters, then what needs care, then by surah. A correction opened from it goes on
    // through it in this order.
    val shown = if (!notebook) emptyList() else when (tab) {
        // The next review's first, then the rest of the day's.
        0 -> batch + dueAll.filterNot { it in batch }
        2 -> points.filter { it.mastered }
        else -> points
    }.filter {
        (kindFilter == null || it.mistakes.any { mistake -> mistake.kind.key == kindFilter }) &&
            (surahFilter == null || it.verse.surah == surahFilter) && hifzNotebookMatches(catalog, it, query)
    }
    val care = shown.filter { it.needsCare }
    val bySurah = shown.filterNot { it in care }.groupBy { it.verse.surah }.toSortedMap()
    val order = care.map { it.id } + bySurah.values.flatten().map { it.id }
    fun openRow(point: HifzPoint) {
        focus.clearFocus()
        // Its own question, or one whose verse its correction shows: asked first, as a correction opened from the page.
        val due = points.spoiledBy(point, today, now, eligibleDueIds)
        if (due != null) { spoilId = due.id; spoilOpenId = point.id } else actions.open(point, order)
    }
    fun returnHome() {
        focus.clearFocus()
        notebook = false
    }
    fun openNotebook() {
        // With questions due, the day's list first.
        tab = if (dueAll.isNotEmpty()) 0 else 1
        query = ""
        kindFilter = null
        surahFilter = null
        notebook = true
    }
    ModalBottomSheet(onDismissRequest = actions::dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        BackHandler(enabled = notebook) { returnHome() }
        Column(Modifier.fillMaxWidth().quranSheetHeight().testTag("hifz_home")) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (notebook) IconButton(onClick = { returnHome() }, modifier = Modifier.testTag("hifz_notebook_back")) {
                    Icon(painterResource(R.drawable.ic_adhkar_back), "العودة إلى المراجعة", tint = GreenPrimaryDark)
                } else {
                    QuranHifzSquare(18.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (notebook) "دفتر المراجعة" else "المراجعة", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                if (!notebook) TextButton(onClick = actions::settings, modifier = Modifier.testTag("hifz_settings")) { Text("الإعدادات") }
                IconButton(onClick = actions::dismiss) { Icon(painterResource(R.drawable.ic_adhkar_close), "إغلاق") }
            }
            LazyColumn(
                Modifier.weight(1f).testTag(if (notebook) "hifz_notebook" else "hifz_home_content"),
                state = if (notebook) notebookScroll else homeScroll,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                notice?.let { told ->
                    item(key = "notice") {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AdhkarSoftGreen).padding(start = 12.dp, end = 4.dp).testTag("hifz_notice"),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(told.text, Modifier.weight(1f).padding(vertical = 8.dp), fontSize = 13.sp, color = GreenPrimaryDark)
                                IconButton(onClick = onNoticeClosed, modifier = Modifier.size(48.dp)) {
                                    Icon(painterResource(R.drawable.ic_adhkar_close), "إخفاء", Modifier.size(14.dp), tint = GreenPrimaryDark)
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                told.correction?.let { TextButton(onClick = it, modifier = Modifier.testTag("hifz_notice_correction")) { Text("اعرض التصحيح", fontSize = 13.sp) } }
                                told.again?.let { TextButton(onClick = it) { Text("سجّل موضعًا آخر", fontSize = 13.sp) } }
                                told.undo?.let { TextButton(onClick = it, modifier = Modifier.testTag("hifz_notice_undo")) { Text(told.undoLabel, fontSize = 13.sp) } }
                            }
                        }
                    }
                }
                if (!notebook) item(key = "today") {
                    // Gold calls for the review: only when there is a question to answer now. Otherwise a plain card.
                    Surface(
                        shape = RoundedCornerShape(16.dp), color = if (dueAll.isNotEmpty()) GoldLight else Color.White,
                        border = if (dueAll.isNotEmpty()) null else BorderStroke(1.dp, CardBorder),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (dueAll.isNotEmpty()) {
                                // Care determines batch selection; the selected points are presented in mushaf order. «الآن»:
                                // what can be asked now, as the notebook's chip counts it; later ones are said under it.
                                Text("نسألك الآن عن ${HifzReminder.pointsAbout(dueAll.size)}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                                // Late is the spaced checks' word: a fresh slip is asked at every reminder, its day is no date.
                                val late = dueAll.count { it.fresh == 0 && it.dueDay < today }
                                val minutes = quranMinutesLabel((batch.size + 1).coerceAtLeast(2))
                                // «ونقدّم ما يحتاج عناية» only when care put a point ahead of another one left for later.
                                Text(
                                    (if (dueAll.size > batch.size) "نسألك أولًا عن ${batch.size} منها في نحو $minutes" else "تستغرق المراجعة نحو $minutes") +
                                        (if (batch.any { it.needsCare } && dueAll.any { it !in batch && !it.needsCare }) "، ونقدّم ما يحتاج عناية." else ".") +
                                        (if (late > 0) " وتأخّر منها ${hifzCountLabel(late)}." else ""),
                                    fontSize = 13.sp, color = Color(0xFF6E5A2E),
                                )
                                later.laterToday?.let { (count, at) ->
                                    Text("ونسألك عن ${HifzReminder.otherPointsAbout(count)} $at.", fontSize = 13.sp, color = Color(0xFF6E5A2E))
                                }
                                // No «استمع إليها» here: hearing the answers first would be no check at all. They can be heard
                                // once the review is done.
                                Button(onClick = actions::review, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.testTag("hifz_start_review")) {
                                    Text("ابدأ مراجعة اليوم")
                                }
                            } else if (points.isEmpty()) {
                                Text("ابدأ من أخطائك", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                                // When the questions come, as the settings say: with the reminder off, they wait in «مراجعة اليوم».
                                val asked = if (settings.reminder) "نسألك عنها ${HifzReminder.timesPhrase(settings.times)}"
                                    else "تجدها في «مراجعة اليوم» بعد ${HifzFreshGapMs / 60_000L}${NB}دقيقة أو بعد الصلاة التالية، أيّهما أقرب"
                                Text(
                                    "إذا أخطأت وأنت تقرأ، فاضغط على الآية ثم «أخطأت». $asked، والآية مغطاة، إلى أن تتذكّرها ${HifzReminder.recallsPhrase(HifzFreshRecalls)} دون مساعدة.",
                                    fontSize = 13.sp, color = TextMuted,
                                )
                            } else {
                                // Held points come back too, to make sure of them. A check whose verse was looked at today is
                                // tomorrow's.
                                val next = points.filter { it.fresh == 0 }.minOfOrNull { point ->
                                    if (point.isDue(today, now) && eligibleDueIds != null && point.id !in eligibleDueIds) today + 1 else point.dueDay
                                }
                                val allHeld = points.all { it.mastered }
                                Text("لا أسئلة الآن", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                                when {
                                    // A fresh point comes back a while after its slip, its answer or its answer seen.
                                    nextFresh != null -> Text("السؤال القادم $nextFresh", fontSize = 13.sp, color = TextMuted)
                                    next != null -> Text(
                                        (if (allHeld) "سؤال للتأكد " else "السؤال القادم ") +
                                            (nextCheckLabel?.takeIf { nextCheckIn == next - today } ?: hifzWhen(next - today)),
                                        fontSize = 13.sp, color = TextMuted,
                                    )
                                }
                            }
                        }
                    }
                }
                if (!notebook && points.isNotEmpty()) item(key = "notebook_entry") {
                    Surface(
                        onClick = { openNotebook() }, modifier = Modifier.fillMaxWidth().testTag("hifz_open_notebook"),
                        shape = RoundedCornerShape(12.dp), color = Color.White, border = BorderStroke(1.dp, CardBorder),
                    ) {
                        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("دفتر المراجعة · ${hifzCountLabel(points.size)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                                // How far the mistakes have come: those held; nothing to say before one is.
                                val held = points.count { it.mastered }
                                if (held > 0) Text("ثبت منها ${hifzCountLabel(held)}", fontSize = 12.sp, color = TextMuted)
                            }
                            // It opens, as a row in a list does.
                            Icon(painterResource(R.drawable.ic_adhkar_next), null, Modifier.size(18.dp), tint = GreenPrimaryDark)
                        }
                    }
                }
                if (!notebook) item(key = "how_it_works") {
                    TextButton(onClick = { howItWorks = true }, modifier = Modifier.testTag("hifz_home_how_it_works")) { Text("كيف تعمل المراجعة؟", fontSize = 14.sp) }
                    HifzNotificationsWarning(listOfNotNull(HifzReminder.CHANNEL_ID.takeIf { settings.reminder && points.isNotEmpty() }))
                }
                if (notebook) item(key = "notebook_filters") {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().testTag("hifz_notebook_search"),
                        label = { Text("ابحث في دفتر المراجعة") },
                        placeholder = { Text("السورة والآية أو كلمات منها أو ملاحظتك", fontSize = 13.sp) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_adhkar_search), null) },
                        trailingIcon = if (query.isNotEmpty()) ({
                            IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_adhkar_close), "مسح البحث") }
                        }) else null,
                        singleLine = true, shape = RoundedCornerShape(12.dp),
                    )
                    val held = points.count { it.mastered }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // «الآن»: the questions that can be asked now; one due later today is not among them yet.
                        listOf("الآن ${dueAll.size}", "الكل ${points.size}", "ثبتت $held").forEachIndexed { index, label ->
                            FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = GreenPrimary, selectedLabelColor = Color.White))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // The same name in the menu and on the chip once chosen.
                        HifzFilterMenu(
                            label = kindFilter?.let { HifzKind.of(it)?.short } ?: "كل الأنواع",
                            options = listOf(null to "كل الأنواع") + HifzKind.entries.map { it.key to it.short },
                        ) { kindFilter = it }
                        val surahs = points.map { it.verse.surah }.distinct().sorted()
                        HifzFilterMenu(
                            label = surahFilter?.let { catalog.surahs.getOrNull(it - 1)?.name } ?: "كل السور",
                            options = listOf(null to "كل السور") + surahs.map { it.toString() to catalog.surahs[it - 1].name },
                        ) { surahFilter = it?.toIntOrNull() }
                    }
                }
                if (care.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(top = 4.dp)) {
                            Text("يحتاج عناية", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = QuranHifzViolet)
                            Text("أخطأت فيها أو تردّدت أكثر من مرة، فنقدّمها على غيرها في الأسئلة إلى أن تتذكّرها دون مساعدة في يومين مختلفين.", fontSize = 12.sp, color = TextMuted)
                        }
                    }
                    items(care, key = { "care${it.id}" }) { HifzPointRow(catalog, it, today, eligibleDueIds?.contains(it.id) ?: it.isDue(today, now), later.rows[it.id]) { openRow(it) } }
                }
                bySurah.forEach { (surah, rows) ->
                    item(key = "surah$surah") {
                        Text("${catalog.surahs.getOrNull(surah - 1)?.name.orEmpty()} (${rows.size})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMuted,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    items(rows, key = { it.id }) { HifzPointRow(catalog, it, today, eligibleDueIds?.contains(it.id) ?: it.isDue(today, now), later.rows[it.id]) { openRow(it) } }
                }
                if (notebook && shown.isEmpty()) item(key = "empty_notebook") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (points.isEmpty()) "لم تسجّل مواضع بعد" else "لا مواضع تطابق اختيارك", fontSize = 14.sp, color = GreenPrimaryDark)
                        Text(if (points.isEmpty()) "سجّل الموضع الذي أخطأت فيه لتجده هنا وتتابع مراجعته." else "جرّب اسم السورة أو رقم الآية أو كلمات من ملاحظتك.", fontSize = 13.sp, color = TextMuted)
                        if (points.isNotEmpty()) TextButton(onClick = { query = ""; kindFilter = null; surahFilter = null; tab = 1 }) { Text("اعرض كل المواضع") }
                    }
                }
                // Only what is not waiting for its question: a new mistake, or a check of today, heard now would be no
                // question at all.
                // Nor one whose audio would answer another question still waiting: a look-alike, the verse before, a skip's.
                val audible = shown.filterNot { it.fresh > 0 || it.dueDay <= today || points.spoiledBy(it, today, now, eligibleDueIds) != null }
                if (notebook && audible.isNotEmpty()) item(key = "notebook_listen") {
                    TextButton(onClick = { actions.listen(audible) }) { Text("استمع إلى هذه المواضع", fontSize = 13.sp) }
                }
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                if (audio != null && audioControls != null) HifzAudioStrip(audio, audioControls)
                ExtendedFloatingActionButton(
                    onClick = { away = true }, containerColor = GreenPrimary, contentColor = Color.White,
                    modifier = Modifier.testTag("hifz_record_away"),
                ) { Text("سجّل موضعًا") }
            }
        }
    }
    if (away) HifzAwayDialog(catalog, onChosen = { away = false; actions.record(it) }, onDismiss = { away = false })
    if (howItWorks) HifzHowItWorksDialog { howItWorks = false }
    points.firstOrNull { it.id == spoilId }?.let { point ->
        val opened = points.firstOrNull { it.id == spoilOpenId } ?: point
        HifzSpoilDialog(
            catalog, point, opened = opened,
            onReview = { spoilId = null; actions.reviewFrom(point) },
            onOpen = { spoilId = null; actions.open(opened, order) },
            onDismiss = { spoilId = null },
        )
    }
}

/**
 * The question still waiting that [opened]'s correction would answer: [opened]'s own when it is due, else that of a point
 * due now whose verse the correction shows (the verse before an opening, a look-alike, the verses of a skip, the same
 * verse). Null when it answers none.
 */
internal fun List<HifzPoint>.spoiledBy(opened: HifzPoint, today: Long, now: Long, eligibleDueIds: Set<Long>?): HifzPoint? {
    fun waiting(point: HifzPoint) = point.isDue(today, now) && (eligibleDueIds == null || point.id in eligibleDueIds)
    if (waiting(opened)) return opened
    val shows = opened.answerVerses()
    return firstOrNull { it.id != opened.id && it.verse in shows && waiting(it) }
}

/**
 * «إن رأيت تصحيحها الآن، لا نسألك عنها قبل 45 دقيقة.»; when another point's correction would show it, «إن رأيت تصحيح
 * الملك 2 الآن، لا نسألك عن الملك 3 قبل 45 دقيقة.». «إلا بعد صلاة العصر» when that prayer is over first.
 */
@Composable
internal fun hifzSpoilSentence(catalog: QuranCatalog, due: HifzPoint, opened: HifzPoint): String {
    // Off the main thread: the prayers are read.
    val wait by produceState("قبل ${HifzFreshGapMs / 60_000L}${NB}دقيقة", due.id) {
        value = withContext(Dispatchers.IO) { HifzReminder.waitClause(System.currentTimeMillis()) }
    }
    if (opened.verse == due.verse) {
        return if (due.fresh > 0) "إن رأيت تصحيحها الآن، لا نسألك عنها $wait." else "إن رأيت تصحيحها الآن، نسألك عنها غدًا."
    }
    val seen = hifzVerseAbout(catalog, opened.verse)
    val asked = hifzVerseFrom(catalog, due.verse, opened.verse)
    return if (due.fresh > 0) "إن رأيت تصحيح $seen الآن، لا نسألك عن $asked $wait." else "إن رأيت تصحيح $seen الآن، نسألك عن $asked غدًا."
}

/**
 * A point whose question is due, opened before it is asked: its answer seen now would spoil the question, so the
 * question comes first, the correction only on purpose. [opened]: the point tapped, when its correction shows [point]'s
 * verse.
 */
@Composable
internal fun HifzSpoilDialog(
    catalog: QuranCatalog,
    point: HifzPoint,
    onReview: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    opened: HifzPoint = point,
) {
    val sentence = hifzSpoilSentence(catalog, point, opened)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("حان وقتُ السؤال عن ${hifzVerseAbout(catalog, point.verse)}") },
        text = { Text(sentence) },
        confirmButton = {
            Button(onClick = onReview, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.testTag("hifz_spoil_review")) { Text("اسألني الآن") }
        },
        dismissButton = {
            TextButton(onClick = onOpen, modifier = Modifier.testTag("hifz_spoil_open")) { Text("اعرض التصحيح") }
        },
    )
}

/** Search the saved notebook without showing answer excerpts before a due check. */
internal fun hifzNotebookMatches(catalog: QuranCatalog, point: HifzPoint, query: String): Boolean {
    if (query.isBlank()) return true
    val typed = hifzTypedVerse(query, catalog)
    // A skip is found by every verse it skipped, as it is remembered, and by where the recitation went, the next verse
    // when it was only this one; a look-alike by the verse it was confused with.
    val skipEnd = point.skipLanded
    if (typed != null) return point.verse == typed || typed in point.lookAlikes ||
        (skipEnd != null && typed.surah == point.verse.surah && typed.ayah in point.verse.ayah..skipEnd)
    fun searchable(value: String): String = normalizeQuranSearch(value.map { char -> char.digitToIntOrNull()?.digitToChar() ?: char }.joinToString(""))
    val needle = searchable(query).split(' ').filter { it.isNotEmpty() }
    if (needle.isEmpty()) return true
    val haystack = searchable(buildString {
        append(hifzPointTitle(catalog, point))
        append(' ')
        append("${point.verse.surah}:${point.verse.ayah}")
        append(' ')
        append(catalog.verse(point.verse)?.text.orEmpty())
        point.mistakes.mapNotNull { it.other }.distinct().forEach { other ->
            append(" ${other.surah}:${other.ayah} ")
            append(catalog.verse(other)?.text.orEmpty())
        }
        append(' ')
        append(point.key)
    })
    val numbers = haystack.split(' ').filter { it.all(Char::isDigit) }.toSet()
    return needle.all { term -> if (term.all(Char::isDigit)) term in numbers else term in haystack }
}

@Composable
private fun HifzFilterMenu(label: String, options: List<Pair<String?, String>>, onChosen: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("$label ▾") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onChosen(value) }) }
        }
    }
}

@Composable
private fun HifzPointRow(catalog: QuranCatalog, point: HifzPoint, today: Long, due: Boolean, next: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp).testTag("hifz_point_${point.verse.surah}_${point.verse.ayah}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(hifzPointTitle(catalog, point), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            Text(hifzListDetail(point, today, due, next), fontSize = 12.sp, color = TextMuted)
            point.recentFeedback?.let { Text(it, fontSize = 12.sp, color = if (point.needsCare) QuranHifzViolet else GreenPrimary) }
        }
        Spacer(Modifier.width(8.dp))
        HifzLadder(point)
    }
}

/**
 * A slip made away from the app (in prayer, with a teacher): the verse found as it is remembered, by words from it, its
 * page or its reference.
 */
@Composable
internal fun HifzAwayDialog(
    catalog: QuranCatalog,
    onChosen: (QuranVerseReference) -> Unit,
    onDismiss: () -> Unit,
    title: String = "سجّل موضعًا",
    prompt: String = "في أي آية أخطأت؟ اكتب كلمات منها أو رقم صفحتها أو اسم السورة ورقم الآية، مثل «النساء 47».",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(prompt, fontSize = 14.sp)
                HifzVerseFinder(catalog, Modifier.testTag("hifz_away_field"), onChosen = onChosen)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

/**
 * «إعدادات المراجعة»: when the questions come, and nothing else. The round's size, the drill's pause and speed are
 * fixed; the page markers are the reader menu's «مواضعي على الصفحات».
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HifzSettingsSheet(settings: HifzSettings, onChange: (HifzSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag("hifz_settings_sheet"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            QuranPanelHeader("إعدادات المراجعة", onDismiss)
            HifzNotificationsWarning(listOfNotNull(HifzReminder.CHANNEL_ID.takeIf { settings.reminder }))
            HifzSwitch("التذكير بالمراجعة", settings.reminder) { onChange(settings.copy(reminder = it)) }
            if (settings.reminder) {
                // One choice for every point: a fresh slip is asked at each of these times, a spaced check at the first of its day.
                HifzTimesChoice(settings.times) { onChange(settings.copy(times = it)) }
                Text("يأتي التذكير ${HifzReminder.timesPhrase(settings.times)}", fontSize = 13.sp, color = GreenPrimaryDark, modifier = Modifier.padding(top = 4.dp))
                Text(
                    "هذه أوقات التذكير لكل خطأ جديد، ويمكنك أن تختار لأي خطأ أوقاتًا أخرى. نذكّرك بالآية إلى أن تتذكّرها " +
                        "${HifzReminder.recallsPhrase(HifzFreshRecalls)} دون مساعدة، ثم بعد يوم، ثم بعد 3${NB}أيام، ثم بعد أسبوع، ثم بعد أسبوعين. " +
                        "لا يظهر الصواب في الإشعار: تقرأ من حفظك والآية مغطاة.",
                    fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )
            } else Text(
                "التذكير متوقّف: تجد أسئلتك في «مراجعة اليوم».",
                fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun HifzSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        Switch(checked = checked, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = GreenPrimary))
    }
}

/** A count as a subject: «موضع واحد», «موضعان», «3 مواضع», «11 موضعًا». */
internal fun hifzCountLabel(count: Int): String = HifzReminder.pointsLabel(count)
