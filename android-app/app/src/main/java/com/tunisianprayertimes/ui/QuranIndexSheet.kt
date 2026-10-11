package com.tunisianprayertimes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranDivisions
import com.tunisianprayertimes.quran.QuranGoTo
import com.tunisianprayertimes.quran.QuranLibrary
import com.tunisianprayertimes.quran.QuranThumn
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.quranGoTo
import com.tunisianprayertimes.quran.quranNameKey
import com.tunisianprayertimes.ui.theme.*

/** Where the index sends the reader. */
internal interface QuranIndexActions {
    fun openPage(scanPage: Int)
    fun openVerse(verse: QuranVerseReference, play: Boolean)
    fun playSurah(surah: Int)
    fun resumeListening()
    fun openHifz()
    fun dismiss()
}

/** «الفهرس»: continue cards, one field for any place, chapters, ahzab and bookmarks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuranIndexSheet(
    catalog: QuranCatalog,
    /** The tab to open on, by name («العلامات» after saving a bookmark). */
    initialTab: String? = null,
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
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(-1) }
    val printedPages = QuranScanCount + 1
    val surahName = { n: Int -> catalog.surahs.getOrNull(n - 1)?.name.orEmpty() }
    ModalBottomSheet(onDismissRequest = actions::dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        Column(Modifier.fillMaxWidth().quranSheetHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("الفهرس", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                    Text("مصحف برواية قالون عن نافع", fontSize = 12.sp, color = TextMuted)
                }
                IconButton(onClick = actions::dismiss) { Icon(painterResource(R.drawable.ic_adhkar_close), "إغلاق") }
            }
            val lastRead = remember(libraryRevision) { library.lastRead }
            if (query.isBlank() && (lastRead != null || lastListened != null)) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (lastRead != null) ContinueCard(
                    "آخر قراءة",
                    "${catalog.pages.getOrNull(lastRead - 1)?.surahNames?.firstOrNull().orEmpty()} · ${quranPageLabel(lastRead)}",
                    onClick = { actions.openPage(lastRead) }, onPlay = null, modifier = Modifier.weight(1f),
                )
                if (lastListened != null) ContinueCard(
                    if (listeningNow) "يُتلى الآن" else "آخر استماع", lastListened,
                    onClick = actions::resumeListening, onPlay = actions::resumeListening.takeUnless { listeningNow }, modifier = Modifier.weight(1f),
                )
            }
            if (query.isBlank() && hifzDue >= 0) Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color.White).border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                    .clickable(onClick = actions::openHifz).padding(horizontal = 12.dp, vertical = 12.dp).testTag("quran_index_hifz"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuranHifzSquare()
                Spacer(Modifier.width(10.dp))
                Text(
                    if (hifzDue > 0) "التثبيت · ${quranPointsLabel(hifzDue)} اليوم" else "التثبيت · دفتر المواضع",
                    Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark,
                )
                Text("‹", fontSize = 18.sp, color = TextMuted)
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("quran_index_field"),
                placeholder = { Text("سورة أو صفحة أو آية مثل 2:21") }, singleLine = true,
                leadingIcon = { Icon(painterResource(R.drawable.ic_adhkar_search), null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_adhkar_close), "مسح") } },
                shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { goToFirst(query, catalog, divisions, printedPages, actions) }),
            )
            if (query.isNotBlank()) {
                GoToResults(query, catalog, divisions, printedPages, surahName, actions)
                return@Column
            }
            val tabs = listOfNotNull("السور", if (divisions != null) "الأحزاب" else null, "العلامات")
            val selected = (if (tab >= 0) tab else tabs.indexOf(initialTab).coerceAtLeast(0)).coerceIn(0, tabs.lastIndex)
            PrimaryTabRow(selectedTabIndex = selected, containerColor = BgCream, contentColor = GreenPrimaryDark, modifier = Modifier.padding(horizontal = 16.dp)) {
                tabs.forEachIndexed { index, name ->
                    Tab(selected = selected == index, onClick = { tab = index }, text = { Text(name, fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal) })
                }
            }
            when (tabs[selected]) {
                "السور" -> SurahList(catalog, currentPage, missingSurahs, actions)
                "الأحزاب" -> AhzabList(catalog, divisions!!, currentPage, surahName, actions)
                else -> BookmarksList(catalog, library, libraryRevision, surahName, actions)
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
            Modifier.size(40.dp).clip(CircleShape).background(GreenPrimary).clickable(onClick = onPlay).semantics { contentDescription = "تابع الاستماع" },
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_play_arrow), null, Modifier.size(20.dp), tint = Color.White) }
    }
}

/** The keyboard's Go key opens what the first result row would. */
private fun goToFirst(query: String, catalog: QuranCatalog, divisions: QuranDivisions?, printedPages: Int, actions: QuranIndexActions) {
    when (val target = quranGoTo(query, catalog.surahs, printedPages).firstOrNull()) {
        is QuranGoTo.Page -> actions.openPage((target.printed - 1).coerceAtLeast(1))
        is QuranGoTo.Surah -> actions.openPage(catalog.surahs[target.number - 1].page)
        is QuranGoTo.Verse -> actions.openVerse(target.verse, play = false)
        is QuranGoTo.Hizb -> divisions?.hizbStart(target.number)?.let { actions.openVerse(it.start, play = false) }
        null -> {
            val key = quranNameKey(query)
            if (key.isNotEmpty() && !key.first().isDigit()) {
                catalog.surahs.firstOrNull { quranNameKey(it.name).contains(key) }?.let { actions.openPage(it.page) }
            }
        }
    }
}

@Composable
private fun ColumnScope.GoToResults(
    query: String,
    catalog: QuranCatalog,
    divisions: QuranDivisions?,
    printedPages: Int,
    surahName: (Int) -> String,
    actions: QuranIndexActions,
) {
    val targets = remember(query, catalog) { quranGoTo(query, catalog.surahs, printedPages) }
    val names = remember(query, catalog) {
        val key = quranNameKey(query)
        if (key.isEmpty() || key.first().isDigit()) emptyList() else catalog.surahs.filter { quranNameKey(it.name).contains(key) }
    }
    LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(targets) { target ->
            when (target) {
                is QuranGoTo.Page -> ResultRow(
                    "انتقال إلى صفحة ${target.printed}",
                    catalog.pages.getOrNull((target.printed - 1).coerceAtLeast(1) - 1)?.surahNames?.joinToString("، ").orEmpty(),
                    onClick = { actions.openPage((target.printed - 1).coerceAtLeast(1)) }, onPlay = null,
                )
                is QuranGoTo.Surah -> {
                    val surah = catalog.surahs[target.number - 1]
                    ResultRow("سورة ${surah.number} · ${surah.name}", surahDetail(surah.verseCount, surah.page),
                        onClick = { actions.openPage(surah.page) }, onPlay = { actions.playSurah(surah.number) })
                }
                is QuranGoTo.Verse -> ResultRow(
                    "${surahName(target.verse.surah)} · الآية ${target.verse.ayah}",
                    catalog.pagesForVerse(target.verse.surah, target.verse.ayah).firstOrNull()?.let(::quranPageLabel).orEmpty(),
                    onClick = { actions.openVerse(target.verse, play = false) }, onPlay = { actions.openVerse(target.verse, play = true) },
                )
                is QuranGoTo.Hizb -> {
                    val start = divisions?.hizbStart(target.number)
                    if (start != null) ResultRow(
                        "الحزب ${target.number}", "${surahName(start.start.surah)} ${start.start.ayah} · ${quranPageLabel(start.page)}",
                        onClick = { actions.openVerse(start.start, play = false) }, onPlay = { actions.openVerse(start.start, play = true) },
                    )
                }
            }
        }
        items(names, key = { "n${it.number}" }) { surah ->
            ResultRow("${surah.number} · ${surah.name}", surahDetail(surah.verseCount, surah.page),
                onClick = { actions.openPage(surah.page) }, onPlay = { actions.playSurah(surah.number) })
        }
        if (targets.isEmpty() && names.isEmpty()) item {
            Text("لا يوجد موضع بهذا الاسم أو الرقم", Modifier.padding(24.dp), color = TextMuted)
        }
    }
}

@Composable
private fun ResultRow(title: String, detail: String, onClick: () -> Unit, onPlay: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            if (detail.isNotEmpty()) Text(detail, fontSize = 12.sp, color = TextMuted)
        }
        if (onPlay != null) PlayRound(onPlay, "استمع")
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
private fun ColumnScope.SurahList(catalog: QuranCatalog, currentPage: Int, missing: Set<Int>, actions: QuranIndexActions) {
    val current = catalog.pages.getOrNull(currentPage - 1)?.surahNames.orEmpty()
    val state = rememberLazyListState(initialFirstVisibleItemIndex = catalog.surahs.indexOfFirst { it.name in current }.coerceAtLeast(0))
    LazyColumn(Modifier.weight(1f), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        items(catalog.surahs, key = { it.number }) { surah ->
            Row(
                Modifier.fillMaxWidth().background(if (surah.name in current) AdhkarSoftGreen else Color.Transparent)
                    .clickable { actions.openPage(surah.page) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                    .testTag("quran_index_surah_${surah.number}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).border(1.5.dp, Color(0xFFC9BFAF), CircleShape), contentAlignment = Alignment.Center) {
                    Text("${surah.number}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GreenPrimaryDark)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(surah.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                    Text(surahDetail(surah.verseCount, surah.page), fontSize = 12.sp, color = TextMuted)
                }
                if (surah.number in missing) Icon(
                    painterResource(R.drawable.ic_quran_cloud_off), "التلاوة غير محمّلة", Modifier.size(20.dp), tint = TextMuted,
                )
                PlayRound({ actions.playSurah(surah.number) }, "استمع إلى سورة ${surah.name}")
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
    actions: QuranIndexActions,
) {
    val top = catalog.versesOnPage(currentPage).firstOrNull { it.ayah > 0 }?.reference
    val currentHizb = top?.let { divisions.thumnOf(it).hizb }
    var open by rememberSaveable { mutableStateOf(currentHizb ?: 0) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = ((currentHizb ?: 1) - 1).coerceAtLeast(0))
    LazyColumn(Modifier.weight(1f), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
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
                        Text("${surahName(start.start.surah)} ${start.start.ayah} · ${quranPageLabel(start.page)}", fontSize = 12.sp, color = TextMuted)
                    }
                    Icon(painterResource(if (open == hizb) R.drawable.ic_expand_less else R.drawable.ic_expand_more), if (open == hizb) "طي" else "الأثمان", tint = TextMuted)
                    PlayRound({ actions.openVerse(start.start, play = true) }, "استمع إلى الحزب $hizb")
                }
                if (open == hizb) athman.drop(1).forEach { thumn -> ThumnRow(thumn, catalog, surahName, actions) }
            }
        }
    }
}

@Composable
private fun ThumnRow(thumn: QuranThumn, catalog: QuranCatalog, surahName: (Int) -> String, actions: QuranIndexActions) {
    val opening = remember(thumn) { catalog.verse(thumn.start)?.text?.split(' ')?.take(3)?.joinToString(" ").orEmpty() }
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFEEF5F1)).clickable { actions.openVerse(thumn.start, play = false) }
            .padding(start = 28.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(thumnName(thumn.thumn), Modifier.width(92.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
        Text("$opening…", Modifier.weight(1f), fontFamily = AdhkarReadingFont, fontSize = 15.sp, color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${surahName(thumn.start.surah)} ${thumn.start.ayah} · ص ${thumn.page + 1}", fontSize = 11.sp, color = TextMuted, maxLines = 1)
    }
}

/** The name the scan's margin gives each eighth of a hizb. */
internal fun thumnName(thumn: Int): String = when (thumn) {
    1 -> "بداية الحزب"
    3 -> "الربع"
    5 -> "النصف"
    7 -> "ثلاثة الأرباع"
    else -> "الثمن $thumn"
}

@Composable
private fun ColumnScope.BookmarksList(catalog: QuranCatalog, library: QuranLibrary, revision: Int, surahName: (Int) -> String, actions: QuranIndexActions) {
    val verses = remember(revision) { library.verseBookmarks }
    val pages = remember(revision) { library.pageBookmarks }
    val recent = remember(revision) { library.recentPages }
    LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (verses.isEmpty() && pages.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_adhkar_bookmark), null, Modifier.size(40.dp), tint = GreenPrimary)
                Text("لا توجد علامات بعد", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                Text("اضغط على آية ثم «علامة» لحفظها، أو على الشريط أعلى الصفحة.", fontSize = 13.sp, color = TextMuted)
            }
        }
        if (verses.isNotEmpty()) item { SectionLabel("الآيات المحفوظة") }
        items(verses, key = { "v${it.verse.surah}:${it.verse.ayah}" }) { mark ->
            val verse = catalog.verse(mark.verse)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { actions.openVerse(mark.verse, play = false) }.padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${surahName(mark.verse.surah)} · الآية ${mark.verse.ayah}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                    verse?.let {
                        Text(it.text.split(' ').take(4).joinToString(" ") + "…", fontFamily = AdhkarReadingFont, fontSize = 15.sp, color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(quranPageLabel(it.pages.first()), fontSize = 12.sp, color = TextMuted)
                    }
                }
            }
        }
        if (pages.isNotEmpty()) item { SectionLabel("الصفحات المحفوظة") }
        items(pages, key = { "p$it" }) { page ->
            ResultRow(quranPageLabel(page), catalog.pages.getOrNull(page - 1)?.surahNames?.joinToString("، ").orEmpty(), onClick = { actions.openPage(page) }, onPlay = null)
        }
        if (recent.isNotEmpty()) {
            item { SectionLabel("الصفحات الأخيرة") }
            item {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { page ->
                        AssistChip(onClick = { actions.openPage(page) }, label = {
                            Text(if (page <= QuranScanCount) "ص ${page + 1}" else "الملحق ${page - QuranScanCount}")
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMuted)
}

private fun surahDetail(verseCount: Int?, scanPage: Int): String =
    listOfNotNull(verseCount?.let { quranVerseCountLabel(it) }, quranPageLabel(scanPage)).joinToString(" · ")

/** «7 آيات», «285 آية», with Arabic number agreement. */
internal fun quranVerseCountLabel(count: Int): String = when {
    count == 1 -> "آية واحدة"
    count == 2 -> "آيتان"
    count % 100 in 3..10 -> "$count آيات"
    else -> "$count آية"
}

/** «موضع», «موضعان», «3 مواضع», «11 موضعا». */
internal fun quranPointsLabel(count: Int): String = when {
    count == 1 -> "موضع"
    count == 2 -> "موضعان"
    count % 100 in 3..10 -> "$count مواضع"
    else -> "$count موضعا"
}

/** The small dashed violet square that stands for hifz points. */
@Composable
internal fun QuranHifzSquare(size: androidx.compose.ui.unit.Dp = 14.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        drawRoundRect(QuranHifzViolet.copy(alpha = 0.16f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        drawRoundRect(
            QuranHifzViolet, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
        )
    }
}
