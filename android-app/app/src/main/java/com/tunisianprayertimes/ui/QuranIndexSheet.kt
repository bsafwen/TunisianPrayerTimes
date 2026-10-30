package com.tunisianprayertimes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranDivisions
import com.tunisianprayertimes.quran.QuranLibrary
import com.tunisianprayertimes.quran.QuranThumn
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.quranVerseLabel
import com.tunisianprayertimes.ui.theme.*
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/** Where the index sends the reader. */
internal interface QuranIndexActions {
    fun openPage(scanPage: Int)
    fun openVerse(verse: QuranVerseReference, play: Boolean)
    fun playSurah(surah: Int)
    fun resumeListening()
    fun openHifz()
    fun search()
    fun dismiss()
}

/** «الفهرس»: continue cards, the shared search, chapters, ahzab and recent pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuranIndexSheet(
    catalog: QuranCatalog,
    divisions: QuranDivisions?,
    library: QuranLibrary,
    libraryRevision: Int,
    currentPage: Int,
    /** «البقرة · الآية 19 · الحصري» when a recitation can be continued. */
    lastListened: String?,
    /** The recitation of [lastListened] is playing now: its card opens its page rather than resuming it. */
    listeningNow: Boolean,
    hifzDue: Int,
    missingSurahs: Set<Int>,
    actions: QuranIndexActions,
) {
    var tab by rememberSaveable { mutableIntStateOf(-1) }
    // Where the reader scrolled the surah or hizb list: the other list opens at the same place.
    var scrolledTo by rememberSaveable(stateSaver = VerseOrNullSaver) { mutableStateOf<QuranVerseReference?>(null) }
    val surahName = { n: Int -> catalog.surahs.getOrNull(n - 1)?.name.orEmpty() }
    // With large text, all above the tabs would leave the list a sliver: it scrolls away with the list then.
    val collapse = rememberCollapsingHeaderState()
    ModalBottomSheet(onDismissRequest = actions::dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        CollapsingHeaderColumn(collapse, Modifier.fillMaxWidth().quranSheetHeight(), header = {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("الفهرس", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                    Text("مصحف برواية قالون عن نافع", fontSize = 12.sp, color = TextMuted)
                }
                IconButton(onClick = actions::dismiss) { Icon(painterResource(R.drawable.ic_adhkar_close), "إغلاق") }
            }
            // The page already open is no place to go back to.
            val lastRead = remember(libraryRevision) { library.lastRead }?.takeIf { it != currentPage }
            if (lastRead != null || lastListened != null) BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                // Side by side while each card holds a line of its words; with large text, one under the other.
                val stacked = (maxWidth - 8.dp) / 2 < with(LocalDensity.current) { 13.sp.toDp() } * 9
                val cards = @Composable { card: Modifier ->
                    if (lastRead != null) ContinueCard(
                        "آخر قراءة",
                        "${catalog.pages.getOrNull(lastRead - 1)?.surahNames?.firstOrNull().orEmpty()} · ${quranPageLabel(lastRead)}",
                        onClick = { actions.openPage(lastRead) }, onPlay = null, modifier = card,
                    )
                    if (lastListened != null) ContinueCard(
                        if (listeningNow) "يُتلى الآن" else "آخر استماع", lastListened,
                        onClick = actions::resumeListening, onPlay = actions::resumeListening.takeUnless { listeningNow }, modifier = card,
                    )
                }
                if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { cards(Modifier.fillMaxWidth()) }
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { cards(Modifier.weight(1f)) }
            }
            if (hifzDue >= 0) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color.White).border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                    .clickable(onClick = actions::openHifz).padding(horizontal = 12.dp, vertical = 12.dp).testTag("quran_index_hifz"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuranHifzSquare()
                Spacer(Modifier.width(10.dp))
                Text(
                    if (hifzDue > 0) "المراجعة · ${hifzCountLabel(hifzDue)} الآن" else "المراجعة",
                    Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark,
                )
                Text("‹", fontSize = 18.sp, color = TextMuted)
            }
            TextButton(
                onClick = actions::search,
                modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp, vertical = 6.dp).testTag("quran_index_search"),
            ) {
                Icon(painterResource(R.drawable.ic_adhkar_search), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("البحث في القرآن")
            }
        }) {
            val tabs = listOfNotNull("السور", if (divisions != null) "الأحزاب" else null, "الأخيرة")
            val selected = tab.coerceIn(0, tabs.lastIndex)
            PrimaryTabRow(selectedTabIndex = selected, containerColor = BgCream, contentColor = GreenPrimaryDark, modifier = Modifier.padding(horizontal = 16.dp)) {
                tabs.forEachIndexed { index, name ->
                    // Each name whole, on one line: large text shrinks it a little rather than break it.
                    Tab(selected = selected == index, onClick = { tab = index; collapse.hiddenPx = 0f }, text = {
                        Text(name, fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal, maxLines = 1, autoSize = WholeWords(14.sp, maxLines = 1))
                    })
                }
            }
            when (tabs[selected]) {
                "السور" -> SurahList(catalog, currentPage, missingSurahs, scrolledTo, { scrolledTo = it }, actions, collapse.connection)
                "الأحزاب" -> AhzabList(catalog, divisions!!, currentPage, surahName, scrolledTo, { scrolledTo = it }, actions, collapse.connection)
                else -> RecentList(library, libraryRevision, actions, collapse.connection)
            }
        }
    }
}

@Composable
private fun ContinueCard(title: String, detail: String, onClick: () -> Unit, onPlay: (() -> Unit)?, modifier: Modifier) {
    Row(
        modifier.heightIn(min = 72.dp).clip(RoundedCornerShape(14.dp)).background(GoldLight).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF6E5A2E))
            Text(detail, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onPlay != null) Box(
            Modifier.size(40.dp).clip(CircleShape).background(GreenPrimary).clickable(onClick = onPlay).semantics { contentDescription = "متابعة الاستماع" },
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_play_arrow), null, Modifier.size(20.dp), tint = Color.White) }
    }
}

@Composable
private fun PlayRound(onClick: () -> Unit, description: String) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Box(Modifier.size(32.dp).clip(CircleShape).border(1.5.dp, GreenPrimary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_play_arrow), null, Modifier.size(16.dp), tint = GreenPrimary)
        }
    }
}

@Composable
private fun ColumnScope.SurahList(
    catalog: QuranCatalog,
    currentPage: Int,
    missing: Set<Int>,
    scrolledTo: QuranVerseReference?,
    onScrolled: (QuranVerseReference) -> Unit,
    actions: QuranIndexActions,
    collapse: NestedScrollConnection,
) {
    val current = catalog.pages.getOrNull(currentPage - 1)?.surahNames.orEmpty()
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = scrolledTo?.let { it.surah - 1 } ?: catalog.surahs.indexOfFirst { it.name in current }.coerceAtLeast(0),
    )
    OnScrolled(state) { index -> catalog.surahs.getOrNull(index)?.let { onScrolled(QuranVerseReference(it.number, 1)) } }
    LazyColumn(Modifier.weight(1f).nestedScroll(rememberSheetScrollGuard(state)).nestedScroll(collapse), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        items(catalog.surahs, key = { it.number }) { surah ->
            Row(
                Modifier.fillMaxWidth().background(if (surah.name in current) AdhkarSoftGreen else Color.Transparent)
                    .clickable { actions.openPage(surah.page) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                    .testTag("quran_index_surah_${surah.number}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).border(1.5.dp, Color(0xFFC9BFAF), CircleShape), contentAlignment = Alignment.Center) {
                    Text("${surah.number}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GreenPrimaryDark, maxLines = 1, autoSize = WholeWords(12.sp, maxLines = 1))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(surah.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                    Text(surahDetail(surah.verseCount, surah.page), fontSize = 12.sp, color = TextMuted)
                }
                if (surah.number in missing) Icon(
                    painterResource(R.drawable.ic_quran_cloud_off), "التلاوة غير محمّلة", Modifier.size(20.dp), tint = TextMuted,
                )
                PlayRound({ actions.playSurah(surah.number) }, "الاستماع إلى سورة ${surah.name}")
            }
        }
        item {
            TextButton(onClick = { actions.openPage(QuranScanCount + 1) }, modifier = Modifier.fillMaxWidth()) { Text("ملحق أحكام القراءة") }
        }
    }
}

@Composable
private fun ColumnScope.AhzabList(
    catalog: QuranCatalog,
    divisions: QuranDivisions,
    currentPage: Int,
    surahName: (Int) -> String,
    scrolledTo: QuranVerseReference?,
    onScrolled: (QuranVerseReference) -> Unit,
    actions: QuranIndexActions,
    collapse: NestedScrollConnection,
) {
    val top = catalog.versesOnPage(currentPage).firstOrNull { it.ayah > 0 }?.reference
    val currentHizb = top?.let { divisions.thumnOf(it).hizb }
    var open by rememberSaveable { mutableStateOf(currentHizb ?: 0) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = ((scrolledTo?.let { divisions.thumnOf(it).hizb } ?: currentHizb ?: 1) - 1).coerceAtLeast(0))
    OnScrolled(state) { index -> divisions.hizbStart(index + 1)?.let { onScrolled(it.start) } }
    LazyColumn(Modifier.weight(1f).nestedScroll(rememberSheetScrollGuard(state)).nestedScroll(collapse), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        items((1..60).toList(), key = { it }) { hizb ->
            val athman = divisions.athmanOf(hizb)
            val start = athman.first()
            Column {
                if (hizb % 2 == 1) Text(
                    "الجزء ${start.juz}", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMuted,
                )
                Row(
                    Modifier.fillMaxWidth().background(if (hizb == currentHizb) AdhkarSoftGreen else Color.Transparent)
                        .clickable { open = if (open == hizb) 0 else hizb }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                        .testTag("quran_index_hizb_$hizb"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("الحزب $hizb", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                        Text("${quranVerseLabel(surahName(start.start.surah), start.start.ayah)} · ${quranPageLabel(start.page)}", fontSize = 12.sp, color = TextMuted)
                    }
                    Icon(
                        painterResource(if (open == hizb) R.drawable.ic_expand_less else R.drawable.ic_expand_more), if (open == hizb) "إخفاء الأثمان" else "عرض الأثمان",
                        Modifier.padding(start = 4.dp), tint = TextMuted,
                    )
                    PlayRound({ actions.openVerse(start.start, play = true) }, "الاستماع إلى الحزب $hizb")
                }
                // From «بداية الحزب»: the hizb opens to read, without the recitation its round button starts.
                if (open == hizb) athman.forEach { thumn -> ThumnRow(thumn, catalog, surahName, actions) }
            }
        }
    }
}

@Composable
private fun ThumnRow(thumn: QuranThumn, catalog: QuranCatalog, surahName: (Int) -> String, actions: QuranIndexActions) {
    val opening = remember(thumn) { catalog.verse(thumn.start)?.mushafText?.split(' ')?.take(3)?.joinToString(" ").orEmpty() }
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFEEF5F1)).clickable { actions.openVerse(thumn.start, play = false) }
            .padding(start = 28.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(thumnName(thumn.thumn), Modifier.widthIn(min = 92.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
        BesideOrBelow(Modifier.weight(1f), minLeadingEms = 0f, alignment = Alignment.CenterVertically, leading = {
            Text("$opening…", fontFamily = QuranMushafFont, fontSize = 15.sp, color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }) {
            Text("${quranVerseLabel(surahName(thumn.start.surah), thumn.start.ayah)} · ${quranPageLabel(thumn.page)}", fontSize = 11.sp, color = TextMuted)
        }
    }
}

/** The name the scan's margin gives each eighth of a hizb. */
internal fun thumnName(thumn: Int): String = when (thumn) {
    1 -> "بداية الحزب"
    3 -> "الربع"
    5 -> "النصف"
    7 -> "ثلاثة أرباع"
    else -> "الثمن $thumn"
}

@Composable
private fun ColumnScope.RecentList(
    library: QuranLibrary, revision: Int, actions: QuranIndexActions, collapse: NestedScrollConnection,
) {
    val recent = remember(revision) { library.recentPages }
    val state = rememberLazyListState()
    LazyColumn(Modifier.weight(1f).nestedScroll(rememberSheetScrollGuard(state)).nestedScroll(collapse).padding(horizontal = 8.dp), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        if (recent.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("لا توجد صفحات فتحتها مؤخرًا", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                Text("ستظهر هنا آخر عشر صفحات تفتحها.", fontSize = 13.sp, color = TextMuted)
            }
        } else item {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.forEach { page ->
                    AssistChip(onClick = { actions.openPage(page) }, label = {
                        Text(if (page <= QuranScanCount) "صفحة ${page + 1}" else "الملحق ${page - QuranScanCount}")
                    })
                }
            }
        }
    }
}

/** The top row each time the reader's own scroll stops; the list's opening position is not reported back, so tab switches never drift. */
@Composable
private fun OnScrolled(state: LazyListState, onTop: (Int) -> Unit) {
    val latest by rememberUpdatedState(onTop)
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.filter { !it }.drop(1).collect { latest(state.firstVisibleItemIndex) }
    }
}

private val VerseOrNullSaver = listSaver<QuranVerseReference?, Int>(
    save = { listOfNotNull(it?.surah, it?.ayah) },
    restore = { if (it.size == 2) QuranVerseReference(it[0], it[1]) else null },
)

private fun surahDetail(verseCount: Int?, scanPage: Int): String =
    listOfNotNull(verseCount?.let { quranVerseCountLabel(it) }, quranPageLabel(scanPage)).joinToString(" · ")

/** «7 آيات», «285 آية», with Arabic number agreement. */
internal fun quranVerseCountLabel(count: Int): String = when {
    count == 1 -> "آية واحدة"
    count == 2 -> "آيتان"
    count % 100 in 3..10 -> "$count${com.tunisianprayertimes.quran.hifz.HifzReminder.NB}آيات"
    else -> "$count${com.tunisianprayertimes.quran.hifz.HifzReminder.NB}آية"
}

/** The small violet mark that stands for hifz points, as the page marks them: a light tint, underlined. */
@Composable
internal fun QuranHifzSquare(size: androidx.compose.ui.unit.Dp = 14.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        drawRoundRect(QuranHifzViolet.copy(alpha = 0.2f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        val stroke = 2.dp.toPx()
        val y = this.size.height - stroke / 2f
        drawLine(
            QuranHifzViolet, androidx.compose.ui.geometry.Offset(stroke / 2f, y), androidx.compose.ui.geometry.Offset(this.size.width - stroke / 2f, y),
            stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}
