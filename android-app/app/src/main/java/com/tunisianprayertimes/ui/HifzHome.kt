package com.tunisianprayertimes.ui

import androidx.activity.compose.BackHandler
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
import com.tunisianprayertimes.quran.hifz.HifzFreshGapMs
import com.tunisianprayertimes.quran.hifz.HifzFreshRecalls
import com.tunisianprayertimes.quran.hifz.HifzSettings
import com.tunisianprayertimes.quran.hifz.dueToday
import com.tunisianprayertimes.ui.theme.*

/** What «المراجعة» can ask of the reader. */
internal interface HifzHomeActions {
    /** The launcher's «سجّل موضعا» opened its dialog: the request is done. */
    fun recordShown()
    fun review()
    /** Continue immediate practice without recording another scheduled check. */
    fun resumePractice(ids: List<Long>) = Unit
    /** Explicitly remove unfinished practice; the saved points and their next checks remain. */
    fun dismissPractice(ids: List<Long>) = Unit
    /** Choose a passage, return to the mushaf and recite it with its verses covered. */
    fun recite()
    /** The day's review, starting with [point]: tapped in the «اليوم» list before it was checked. */
    fun reviewFrom(point: HifzPoint)
    fun listen(points: List<HifzPoint>)
    fun briefing()
    fun open(point: HifzPoint)
    fun record(verse: QuranVerseReference)
    fun settings()
    /** «وضع المراجعة», the same as the eye in the reader's bar. */
    fun hideVerses(on: Boolean)
    fun dismiss()
}

/**
 * A change made from the notebook, said at its top with its undo: the reader's own snackbar would be under the sheet.
 * [again]: «سجّل موضعا آخر», after a record.
 */
@Immutable
internal data class HifzNotice(
    val text: String,
    val undo: (() -> Unit)?,
    val again: (() -> Unit)? = null,
    val practice: (() -> Unit)? = null,
)

/**
 * «المراجعة»: everything that helps keep what is memorised. Today's checks, which never give their answers before they
 * are taken; hiding the verses on the page; a look before reciting; positive counts only; then the notebook of slips.
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
    /** Opened from the launcher's «سجّل موضعا»: ask for the verse at once. */
    startRecording: Boolean = false,
    /** «وضع المراجعة» is on in the reader. */
    versesHidden: Boolean,
    /** «قبل التسميع · الحزب 5»: the passage the look before reciting covers, from the page in view. */
    briefingLabel: String = "قبل التسميع",
    notice: HifzNotice? = null,
    onNoticeClosed: () -> Unit = {},
    /** What plays, shown at the foot since the sheet hides the player. */
    audio: HifzAudio? = null,
    audioControls: HifzAudioControls? = null,
    /** Unfinished immediate practice, retained when a round is left or another batch starts. */
    pendingPractice: List<HifzPoint> = emptyList(),
    /** Already exposed answers may be due by date but cannot be fresh checks again today. */
    eligibleDueIds: Set<Long>? = null,
    practicedToday: Int = 0,
    actions: HifzHomeActions,
) {
    /** Every point due, the next review's first: «اليوم» is the day's, not one review's. */
    val eligible = remember(points, eligibleDueIds) { points.filter { eligibleDueIds == null || it.id in eligibleDueIds } }
    val dueAll = remember(eligible, today, now) { eligible.dueToday(today, now) }
    val batch = remember(eligible, today, now, settings.perDay) { eligible.dueToday(today, now, settings.perDay) }
    val pending = remember(points, pendingPractice) { pendingPractice.distinctBy { it.id }.filter { waiting -> points.any { it.id == waiting.id } } }
    var howItWorks by rememberSaveable { mutableStateOf(false) }
    // Preserve the first scheduled attempt for each point and day. Neither a later slip nor immediate practice
    // rewrites that result; migrated notebooks have no inferred history.
    val checkedRecently = remember(points, today) {
        points.flatMap { point -> point.checks.filter { it.day in (today - 6)..today }.groupBy { it.day }.values.map { it.minBy { check -> check.at } } }
    }
    val checkedToday = checkedRecently.filter { it.day == today }
    var notebook by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val homeScroll = rememberLazyListState()
    val notebookScroll = rememberLazyListState()
    var tab by rememberSaveable { mutableIntStateOf(1) }
    var query by rememberSaveable { mutableStateOf("") }
    var kindFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var surahFilter by rememberSaveable { mutableStateOf<Int?>(null) }
    var away by rememberSaveable { mutableStateOf(false) }
    var dismissPending by rememberSaveable { mutableStateOf(false) }
    /** A due point tapped before its check: seeing its answer now would spoil it, so the reader is asked first. */
    var spoilId by rememberSaveable { mutableStateOf<Long?>(null) }
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
    fun openRow(point: HifzPoint) {
        focus.clearFocus()
        if (point.isDue(today, now) && (eligibleDueIds == null || point.id in eligibleDueIds)) spoilId = point.id else actions.open(point)
    }
    fun returnHome() {
        focus.clearFocus()
        notebook = false
    }
    fun openNotebook(todayOnly: Boolean = false) {
        tab = if (todayOnly) 0 else 1
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
                Text(if (notebook) "دفتر المواضع" else "المراجعة", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
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
                                told.practice?.let { Button(onClick = it, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.testTag("hifz_notice_practice")) { Text("تدرّب الآن", fontSize = 13.sp) } }
                                told.again?.let { TextButton(onClick = it) { Text("سجّل موضعا آخر", fontSize = 13.sp) } }
                                told.undo?.let { TextButton(onClick = it, modifier = Modifier.testTag("hifz_notice_undo")) { Text("تراجع", fontSize = 13.sp) } }
                            }
                        }
                    }
                }
                if (!notebook) item(key = "today") {
                    Surface(shape = RoundedCornerShape(16.dp), color = GoldLight) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (dueAll.isNotEmpty()) {
                                // Care determines batch selection; the selected points are presented in mushaf order.
                                Text("اليوم ${quranPointsLabel(dueAll.size)} للمراجعة", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                                // Late is the spaced checks' word: a fresh slip is asked at every reminder, its day is no date.
                                val late = dueAll.count { it.fresh == 0 && it.dueDay < today }
                                Text(
                                    listOfNotNull(
                                        "نبدأ بـ ${hifzCountLabel(batch.size)}".takeIf { dueAll.size > batch.size },
                                        "نحو ${quranMinutesLabel((batch.size + 1).coerceAtLeast(2))}",
                                        "نختار ما يحتاج عناية للجولة".takeIf { batch.any { it.needsCare } },
                                        "منها ${hifzCountLabel(late)} متأخرة".takeIf { late > 0 },
                                    ).joinToString(" · "),
                                    fontSize = 13.sp, color = Color(0xFF6E5A2E),
                                )
                                // No «استمع إليها» here: hearing the answers first would be no check at all. They can be heard
                                // once the review is done.
                                Button(onClick = actions::review, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.testTag("hifz_start_review")) {
                                    Text("ابدأ مراجعة اليوم")
                                }
                            } else if (points.isEmpty()) {
                                Text("ابدأ بما تحفظه", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                                Text("سمّع مقطعًا من حفظك. إن توقفت أو أخطأت، سجّل الموضع لنتدرّب عليه ونراجعه لاحقًا.", fontSize = 13.sp, color = Color(0xFF6E5A2E))
                            } else {
                                // Held points come back too, to make sure of them.
                                val next = points.filter { it.fresh == 0 }.minOfOrNull { point ->
                                    // The date is unchanged after assisted practice, but another independent attempt
                                    // becomes available tomorrow when today's exposure is no longer carried forward.
                                    if (point.isDue(today, now) && eligibleDueIds != null && point.id !in eligibleDueIds) today + 1 else point.dueDay
                                }
                                val allHeld = points.all { it.mastered }
                                Text(if (practicedToday > 0) "لا مراجعات جديدة الآن" else "لا مواضع مستحقة الآن", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                                when {
                                    // A fresh point comes back a while after its slip, its answer or its answer seen.
                                    nextFresh != null -> Text("السؤال القادم $nextFresh", fontSize = 13.sp, color = Color(0xFF6E5A2E))
                                    next != null -> Text(
                                        (if (allHeld) "مراجعة للتأكد " else "المراجعة القادمة ") + hifzWhen(next - today),
                                        fontSize = 13.sp, color = Color(0xFF6E5A2E),
                                    )
                                }
                                Text("يمكنك تسميع مقطع من حفظك أو تدريب موضع من دفترك.", fontSize = 13.sp, color = Color(0xFF6E5A2E))
                            }
                            if (practicedToday > 0) Text(
                                "تدرّبت اليوم على ${hifzCountLabel(practicedToday)} بعد رؤية الإجابة؛ نختبرها دون مساعدة لاحقًا.",
                                fontSize = 12.sp, color = Color(0xFF6E5A2E), modifier = Modifier.testTag("hifz_practiced_today"),
                            )
                        }
                    }
                }
                if (!notebook && pending.isNotEmpty()) item(key = "pending_practice") {
                    Surface(shape = RoundedCornerShape(12.dp), color = QuranHifzViolet.copy(alpha = 0.07f)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("تدريب لم تكمله · ${hifzCountLabel(pending.size)}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                            Text("أكمله من حيث توقفت؛ تبقى مواعيد المراجعة كما هي.", fontSize = 12.sp, color = TextMuted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(
                                    onClick = { actions.resumePractice(pending.map { it.id }) },
                                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                                    modifier = Modifier.testTag("hifz_resume_practice"),
                                ) { Text("أكمل التدريب") }
                                TextButton(onClick = { dismissPending = true }, modifier = Modifier.testTag("hifz_dismiss_practice")) { Text("أزل من التدريب", fontSize = 12.sp) }
                            }
                        }
                    }
                }
                if (!notebook) item(key = "notebook_entry") {
                    Surface(
                        onClick = { openNotebook() }, modifier = Modifier.fillMaxWidth().testTag("hifz_open_notebook"),
                        shape = RoundedCornerShape(12.dp), color = Color.White,
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("دفتر المواضع · ${hifzCountLabel(points.size)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                            Text("ابحث عن موضع، وافتح تصحيحه أو سجله", fontSize = 12.sp, color = TextMuted)
                        }
                    }
                }
                if (!notebook) item(key = "passage_entry") {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color.White) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("تسميع من حفظك", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(onClick = actions::recite, modifier = Modifier.testTag("hifz_recite")) { Text("سمّع مقطعًا") }
                                if (points.isNotEmpty()) TextButton(onClick = actions::briefing, modifier = Modifier.testTag("hifz_briefing")) { Text(briefingLabel, fontSize = 13.sp) }
                            }
                            TextButton(onClick = { howItWorks = true }, modifier = Modifier.testTag("hifz_home_how_it_works")) { Text("كيف أراجع وأصحّح حفظي؟", fontSize = 13.sp) }
                        }
                    }
                }
                if (!notebook && checkedRecently.isNotEmpty()) item(key = "progress") {
                    Surface(shape = RoundedCornerShape(12.dp), color = AdhkarSoftGreen, modifier = Modifier.testTag("hifz_progress")) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("المحاولة الأولى في المراجعة", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                            Text(
                                "اليوم: ${checkedToday.size} · دون مساعدة: ${checkedToday.count { it.grade == HifzGrade.Held }}",
                                fontSize = 13.sp, color = GreenPrimaryDark, modifier = Modifier.testTag("hifz_progress_today"),
                            )
                            Text(
                                "آخر 7 أيام: ${checkedRecently.size} · دون مساعدة: ${checkedRecently.count { it.grade == HifzGrade.Held }}",
                                fontSize = 13.sp, color = GreenPrimaryDark, modifier = Modifier.testTag("hifz_progress_week"),
                            )
                            Text("نحسب أول محاولة لكل موضع في يوم مراجعته. التدريب بعدها لا يغيّر نتيجتها.", fontSize = 12.sp, color = TextMuted)
                        }
                    }
                }
                if (notebook) item(key = "notebook_filters") {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().testTag("hifz_notebook_search"),
                        label = { Text("ابحث في دفتر المواضع") },
                        placeholder = { Text("السورة والآية، كلمات منها، أو ملاحظتك", fontSize = 13.sp) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_adhkar_search), null) },
                        trailingIcon = if (query.isNotEmpty()) ({
                            IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_adhkar_close), "مسح البحث") }
                        }) else null,
                        singleLine = true, shape = RoundedCornerShape(12.dp),
                    )
                    val held = points.count { it.mastered }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("اليوم ${dueAll.size}", "الكل ${points.size}", "ثبتت $held").forEachIndexed { index, label ->
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
                val shown = if (!notebook) batch.take(2) else when (tab) {
                    // The next review's first, then the rest of the day's.
                    0 -> batch + dueAll.filterNot { it in batch }
                    2 -> points.filter { it.mastered }
                    else -> points
                }.filter {
                    (kindFilter == null || it.kind.key == kindFilter) &&
                        (surahFilter == null || it.verse.surah == surahFilter) && hifzNotebookMatches(catalog, it, query)
                }
                if (!notebook && shown.isNotEmpty()) item(key = "today_preview") {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("من مراجعات اليوم", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, modifier = Modifier.padding(vertical = 12.dp))
                        TextButton(onClick = { openNotebook(todayOnly = true) }, modifier = Modifier.testTag("hifz_today_notebook")) { Text("عرض الكل (${dueAll.size})", fontSize = 13.sp) }
                    }
                }
                val care = if (notebook) shown.filter { it.needsCare } else emptyList()
                if (care.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(top = 4.dp)) {
                            Text("يحتاج عناية", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = QuranHifzViolet)
                            Text("أخطاء أو تلميحات متكررة؛ تتراجع الأولوية بعد مراجعتين دون مساعدة", fontSize = 12.sp, color = TextMuted)
                        }
                    }
                    items(care, key = { "care${it.id}" }) { HifzPointRow(catalog, it, today, eligibleDueIds?.contains(it.id) ?: it.isDue(today, now)) { openRow(it) } }
                }
                shown.filterNot { it in care }.groupBy { it.verse.surah }.toSortedMap().forEach { (surah, rows) ->
                    if (notebook) item(key = "surah$surah") {
                        Text("${catalog.surahs.getOrNull(surah - 1)?.name.orEmpty()} (${rows.size})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMuted,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    items(rows, key = { it.id }) { HifzPointRow(catalog, it, today, eligibleDueIds?.contains(it.id) ?: it.isDue(today, now)) { openRow(it) } }
                }
                if (notebook && shown.isEmpty()) item(key = "empty_notebook") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (points.isEmpty()) "لم تسجّل مواضع بعد" else "لا مواضع تطابق اختيارك", fontSize = 14.sp, color = GreenPrimaryDark)
                        Text(if (points.isEmpty()) "سجّل الموضع الذي توقفت فيه لتجده هنا وتتابع مراجعته." else "جرّب اسم السورة أو رقم الآية أو كلمات من ملاحظتك.", fontSize = 13.sp, color = TextMuted)
                        if (points.isNotEmpty()) TextButton(onClick = { query = ""; kindFilter = null; surahFilter = null; tab = 1 }) { Text("اعرض كل المواضع") }
                    }
                }
                if (notebook && shown.isNotEmpty()) item(key = "notebook_listen") {
                    TextButton(onClick = { actions.listen(shown) }) { Text("استمع إلى هذه المواضع", fontSize = 13.sp) }
                }
                if (!notebook) item(key = "free_practice") {
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = CardBorder)
                    Row(
                        Modifier.fillMaxWidth().toggleable(versesHidden, role = Role.Switch, onValueChange = actions::hideVerses)
                            .padding(vertical = 4.dp).testTag("hifz_hide_verses"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("تسميع حرّ · إخفاء آيات المصحف", Modifier.weight(1f), fontSize = 13.sp, color = TextMuted)
                        Switch(checked = versesHidden, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = GreenPrimary))
                    }
                    HifzNotificationsWarning(listOfNotNull(HifzReminder.CHANNEL_ID.takeIf { settings.reminder && points.isNotEmpty() }))
                }
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                if (audio != null && audioControls != null) HifzAudioStrip(audio, audioControls)
                ExtendedFloatingActionButton(
                    onClick = { away = true }, containerColor = GreenPrimary, contentColor = Color.White,
                    modifier = Modifier.testTag("hifz_record_away"),
                ) { Text("سجّل موضعا") }
            }
        }
    }
    if (away) HifzAwayDialog(catalog, onChosen = { away = false; actions.record(it) }, onDismiss = { away = false })
    if (howItWorks) HifzHowItWorksDialog { howItWorks = false }
    if (dismissPending && pending.isNotEmpty()) AlertDialog(
        onDismissRequest = { dismissPending = false },
        title = { Text("إزالة التدريب غير المكتمل؟") },
        text = { Text("سيُزال ${hifzCountLabel(pending.size)} من قائمة التدريب. تبقى المواضع في دفترك وتبقى مواعيد مراجعتها كما هي.") },
        confirmButton = {
            TextButton(onClick = { dismissPending = false; actions.dismissPractice(pending.map { it.id }) }, modifier = Modifier.testTag("hifz_confirm_dismiss_practice")) { Text("أزل من التدريب") }
        },
        dismissButton = { TextButton(onClick = { dismissPending = false }) { Text("احتفظ بالتدريب") } },
    )
    points.firstOrNull { it.id == spoilId }?.let { point ->
        AlertDialog(
            onDismissRequest = { spoilId = null },
            title = { Text("${hifzVerseName(catalog, point.verse)} في مراجعة اليوم") },
            text = {
                Text(
                    if (point.fresh > 0) "حان سؤالها. إن رأيت تصحيحها الآن، نؤجل سؤالها ${HifzFreshGapMs / 60_000L} دقيقة لتقرأها بعدها من حفظك."
                    else "لم تراجعها بعد اليوم. إن رأيت تصحيحها الآن، فلن تكون مراجعتها اليوم اختبارًا لحفظك.",
                )
            },
            confirmButton = {
                TextButton(onClick = { spoilId = null; actions.reviewFrom(point) }, modifier = Modifier.testTag("hifz_spoil_review")) { Text("راجعها الآن") }
            },
            dismissButton = {
                TextButton(onClick = { spoilId = null; actions.open(point) }, modifier = Modifier.testTag("hifz_spoil_open")) { Text("اعرض التصحيح") }
            },
        )
    }
}

/** Search the saved notebook without showing answer excerpts before a due check. */
internal fun hifzNotebookMatches(catalog: QuranCatalog, point: HifzPoint, query: String): Boolean {
    if (query.isBlank()) return true
    val typed = hifzTypedVerse(query, catalog)
    if (typed != null) return point.verse == typed || point.other == typed
    fun searchable(value: String): String = normalizeQuranSearch(value.map { char -> char.digitToIntOrNull()?.digitToChar() ?: char }.joinToString(""))
    val needle = searchable(query).split(' ').filter { it.isNotEmpty() }
    if (needle.isEmpty()) return true
    val haystack = searchable(buildString {
        append(hifzPointTitle(catalog, point))
        append(' ')
        append("${point.verse.surah}:${point.verse.ayah}")
        append(' ')
        append(catalog.verse(point.verse)?.text.orEmpty())
        point.other?.let { other ->
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
private fun HifzPointRow(catalog: QuranCatalog, point: HifzPoint, today: Long, due: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp).testTag("hifz_point_${point.verse.surah}_${point.verse.ayah}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(hifzPointTitle(catalog, point), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            Text(hifzListDetail(point, today, due), fontSize = 12.sp, color = TextMuted)
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
    title: String = "سجّل موضعا",
    prompt: String = "في أي آية أخطأت؟ اكتب كلمات منها، أو صفحتها، أو مثل النساء 47",
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

/** What is said before a point while listening: nothing, a heads-up, or a stop to recite it from memory. */
private enum class HifzBeforePoint(val label: String, val detail: String) {
    Nothing("لا شيء", "تستمع دون تنبيه قبل مواضعك"),
    HeadsUp("نبّهني", "قبل الآية التي أخطأت فيها تظهر بطاقة تسألك عنها، والصواب خلف «أظهر الصواب»"),
    Stop("توقّف لأسمّعه", "يتوقف القارئ قبلها لتقرأها من حفظك، ثم تسمعها للتأكد · في التكرار بلا توقف لا يتوقف، فتظهر البطاقة"),
}

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
                    "يسألك عن الموضع الجديد في كل وقت منها حتى تقرأه صحيحًا ${HifzReminder.recallsPhrase(HifzFreshRecalls)}، " +
                        "ثم عن مراجعاته المتباعدة في أول وقت من يومها. لا يظهر الصواب في الإشعار: تقرأ من حفظك والآية مغطاة.",
                    fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )
            }
            HorizontalDivider(color = CardBorder)
            val stacked = LocalDensity.current.fontScale > 1.3f
            val perRound: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HifzSettings.PerDayChoices.forEach { n ->
                        FilterChip(selected = settings.perDay == n, onClick = { onChange(settings.copy(perDay = n)) }, label = { Text("$n") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = GreenPrimary, selectedLabelColor = Color.White))
                    }
                }
            }
            if (stacked) Column(Modifier.padding(vertical = 6.dp)) {
                Text("عدد المواضع في الجولة الواحدة", fontSize = 15.sp)
                perRound()
            } else Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("عدد المواضع في الجولة الواحدة", Modifier.weight(1f), fontSize = 15.sp)
                perRound()
            }
            HorizontalDivider(color = CardBorder)
            HifzSwitch("إظهار مواضعي على الصفحات", settings.markers) { onChange(settings.copy(markers = it)) }
            // One choice: a heads-up and a stop before the same verse would contradict each other.
            val before = when {
                settings.stopBefore -> HifzBeforePoint.Stop
                settings.headsUp -> HifzBeforePoint.HeadsUp
                else -> HifzBeforePoint.Nothing
            }
            HifzChoice("قبل الموضع أثناء الاستماع", before.label, HifzBeforePoint.entries.map { it to it.label }) { chosen ->
                onChange(settings.copy(headsUp = chosen == HifzBeforePoint.HeadsUp, stopBefore = chosen == HifzBeforePoint.Stop))
            }
            Text(before.detail, fontSize = 12.sp, color = TextMuted)
            HifzSwitch("اقترح «قبل التسميع» حين أبدأ حزبا فيه مواضع", settings.briefing) { onChange(settings.copy(briefing = it)) }
            HorizontalDivider(color = CardBorder)
            HifzChoice("مهلة للترديد في «درّب»", gapLabel(settings.drillGap), listOf(0f, 0.5f, 1f, 2f).map { it to gapLabel(it) }) {
                onChange(settings.copy(drillGap = it))
            }
            HifzChoice("السرعة في «درّب»", speedLabel(settings.drillSpeed), listOf(0.75f, 1f).map { it to speedLabel(it) }) {
                onChange(settings.copy(drillSpeed = it))
            }
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

@Composable
private fun <T> HifzChoice(label: String, value: String, options: List<Pair<T, String>>, onChosen: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val choice: @Composable () -> Unit = {
        Box {
            Text("$value ▾", fontSize = 14.sp, color = GreenPrimary)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (option, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onChosen(option) }) }
            }
        }
    }
    val row = Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 10.dp)
    // With large text the value goes under its label, which then keeps its whole width.
    if (LocalDensity.current.fontScale > 1.3f) Column(row) {
        Text(label, fontSize = 15.sp, color = TextDark)
        choice()
    } else Row(row, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp, color = TextDark)
        choice()
    }
}

/** A count standing alone after a colon: «موضع واحد», «موضعان», «3 مواضع». */
internal fun hifzCountLabel(count: Int): String = if (count == 1) "موضع واحد" else quranPointsLabel(count)
