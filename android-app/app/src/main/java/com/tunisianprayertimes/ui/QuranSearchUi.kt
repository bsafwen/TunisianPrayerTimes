package com.tunisianprayertimes.ui

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranMushafText
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranDivisions
import com.tunisianprayertimes.quran.QuranGoTo
import com.tunisianprayertimes.quran.QuranSearchHit
import com.tunisianprayertimes.quran.QuranSearchKind
import com.tunisianprayertimes.quran.QuranSearchResult
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.quranMatchRanges
import com.tunisianprayertimes.quran.quranGoTo
import com.tunisianprayertimes.quran.quranNameKey
import com.tunisianprayertimes.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The colour of a found verse, on the page and behind the matched words of a result. */
internal val QuranFoundColor = Color(0x575BB8E8)

private const val RecentSearchesKey = "recent_searches"
private const val FirstResults = 40

/**
 * What the reader and the search sheet share, so a result can be left and the list come back
 * as it was, and the results can be walked through from the page.
 */
@Stable
internal class QuranSearchState {
    var query by mutableStateOf("")
    /** Only the chapter picked from the chips; null lists every result. */
    var surah by mutableStateOf<String?>(null)
    var results by mutableStateOf<List<QuranSearchResult>>(emptyList())
    var searching by mutableStateOf(false)
    /** Where the result last opened stands in [visible]. */
    var current by mutableIntStateOf(-1)
    val listState = LazyListState()
    val visible: List<QuranSearchResult> by derivedStateOf {
        val name = surah
        if (name == null) results else results.filter { it.surahName == name }
    }

    fun updateQuery(value: String) {
        query = value
        surah = null
        current = -1
        results = emptyList()
        searching = value.isNotBlank()
    }
}

private val QuranSearchStateSaver = listSaver<QuranSearchState, Any>(
    save = { listOf(it.query, it.surah.orEmpty(), it.current) },
    restore = { saved ->
        QuranSearchState().apply {
            query = saved[0] as String
            surah = (saved[1] as String).ifEmpty { null }
            current = saved[2] as Int
        }
    },
)

/** Keeps the query across a re-created activity and searches it as soon as the verse index is read. */
@Composable
internal fun rememberQuranSearch(catalog: QuranCatalog?): QuranSearchState {
    val state = rememberSaveable(saver = QuranSearchStateSaver) { QuranSearchState() }
    LaunchedEffect(catalog) {
        val index = catalog ?: return@LaunchedEffect
        // The first search would otherwise pay for reading the whole text.
        withContext(Dispatchers.Default) { index.prepareSearch() }
        snapshotFlow { state.query }.collectLatest { query ->
            state.searching = query.isNotBlank()
            state.results = emptyList()
            if (query.isNotBlank()) {
                delay(180)
                val results = withContext(Dispatchers.Default) { index.search(query) }
                if (state.query != query) return@collectLatest
                state.results = results
            }
            if (state.query == query) state.searching = false
        }
    }
    return state
}

internal fun readRecentSearches(prefs: SharedPreferences): List<String> =
    prefs.getString(RecentSearchesKey, "").orEmpty().split('\n').filter { it.isNotBlank() }

internal fun writeRecentSearches(prefs: SharedPreferences, searches: List<String>) {
    prefs.edit().apply { if (searches.isEmpty()) remove(RecentSearchesKey) else putString(RecentSearchesKey, searches.joinToString("\n")) }.apply()
}

/** «نتيجة واحدة», «نتيجتان», «5 نتائج», «12 نتيجة». */
private fun quranResultsLabel(count: Int): String = when {
    count == 1 -> "نتيجة واحدة"
    count == 2 -> "نتيجتان"
    count % 100 in 3..10 -> "${quranNumber(count)}${com.tunisianprayertimes.quran.hifz.HifzReminder.NB}نتائج"
    else -> "${quranNumber(count)}${com.tunisianprayertimes.quran.hifz.HifzReminder.NB}نتيجة"
}

/** «الآية 4», «الآيتان 2 و3», «الآيات من 2 إلى 5», «البسملة». */
internal fun quranVerseLabel(verses: List<QuranVerseReference>): String {
    val numbered = verses.filter { it.ayah > 0 }
    val range = when (numbered.size) {
        0 -> null
        1 -> "الآية ${quranNumber(numbered.first().ayah)}"
        else -> if (numbered.size == 2) "الآيتان ${quranNumber(numbered.first().ayah)} و${quranNumber(numbered.last().ayah)}" else "الآيات من ${quranNumber(numbered.first().ayah)} إلى ${quranNumber(numbered.last().ayah)}"
    }
    return listOfNotNull(if (verses.any { it.ayah == 0 }) "البسملة" else null, range).joinToString(" و")
}

/** The bar under the page that walks through the results of the last search. */
@Composable
internal fun QuranResultStepper(
    search: QuranSearchState,
    found: QuranSearchHit?,
    onOpen: (Int) -> Unit,
    onList: () -> Unit,
    onClose: () -> Unit,
) {
    val visible = search.visible
    val index = search.current
    // Only while the page in view is the one the list's current result opened.
    if (found == null || visible.getOrNull(index)?.hit != found) return
    HorizontalDivider(color = CardBorder)
    Row(
        Modifier.fillMaxWidth().background(AdhkarSoftGreen).padding(horizontal = 4.dp).testTag("quran_result_stepper"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onOpen(index - 1) }, enabled = index > 0, modifier = Modifier.testTag("quran_result_previous")) { Text("السابق") }
        Text(
            "نتيجة ${quranNumber(index + 1)} من ${quranNumber(visible.size)}",
            Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 13.sp, color = GreenPrimaryDark,
        )
        TextButton(onClick = { onOpen(index + 1) }, enabled = index < visible.lastIndex, modifier = Modifier.testTag("quran_result_next")) { Text("التالي") }
        TextButton(onClick = onList) { Text("القائمة") }
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
            Icon(painterResource(R.drawable.ic_adhkar_close), "إخفاء النتيجة", Modifier.size(18.dp), tint = TextMuted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuranSearchSheet(
    catalog: QuranCatalog,
    divisions: QuranDivisions?,
    search: QuranSearchState,
    recents: List<String>,
    onClearRecents: () -> Unit,
    onDismiss: () -> Unit,
    /** The position of the tapped result among [QuranSearchState.visible]. */
    onResult: (Int) -> Unit,
    onPlayResult: (Int) -> Unit,
    onLocation: (QuranGoTo, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var showSources by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val visible = search.visible
    var shown by remember(search.query, search.surah) { mutableIntStateOf(maxOf(FirstResults, search.current + 1)) }
    // Verse references use the text results below, keeping one preview and one highlight path.
    val locations = remember(search.query, catalog, divisions) {
        val key = quranNameKey(search.query)
        val namedSurahs = if (key.isEmpty() || key.first().isDigit()) emptyList() else {
            catalog.surahs.filter { quranNameKey(it.name).contains(key) }.map { QuranGoTo.Surah(it.number) }
        }
        (quranGoTo(search.query, catalog.surahs, QuranScanCount + 1).filter {
            it !is QuranGoTo.Verse && (it !is QuranGoTo.Hizb || divisions?.hizbStart(it.number) != null)
        } + namedSurahs).distinct()
    }
    // Opened again from the results' stepper: the list comes back on the result in view, not where it was left.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(visible.isNotEmpty()) {
        if (placed || visible.isEmpty()) return@LaunchedEffect
        placed = true
        // The first result: the top of the list, the places found above it in view.
        if (search.current == 0) search.listState.scrollToItem(0)
        else if (search.current in 1..visible.lastIndex) search.listState.scrollToItem((if (search.surah == null) locations.size else 0) + search.current)
    }
    fun updateQuery(value: String) {
        if (value == search.query) return
        search.updateQuery(value)
        scope.launch { search.listState.scrollToItem(0) }
    }
    // A long list is narrowed by chapter; the most hit chapters come first.
    val chapters = remember(search.results) {
        search.results.groupingBy { it.surahName }.eachCount().entries
            .sortedWith(compareBy({ -it.value }, { it.key })).take(12)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        Column(Modifier.fillMaxWidth().quranSheetHeight().padding(horizontal = 16.dp)) {
            QuranPanelHeader("البحث في القرآن", onDismiss)
            OutlinedTextField(
                value = search.query, onValueChange = ::updateQuery,
                modifier = Modifier.fillMaxWidth().testTag("quran_search_input"),
                placeholder = { Text("سورة أو صفحة أو كلمات من آية") }, singleLine = true,
                leadingIcon = { Icon(painterResource(R.drawable.ic_adhkar_search), null) },
                trailingIcon = {
                    if (search.query.isNotEmpty()) IconButton(onClick = { updateQuery("") }) {
                        Icon(painterResource(R.drawable.ic_adhkar_close), "مسح البحث")
                    }
                },
                shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            )
            Text(
                when {
                    search.query.isBlank() -> "اكتب مثلًا «طه» أو «صفحة 321» أو «طه:130» أو «حزب 12» أو كلمات من آية مع التشكيل أو دونه."
                    search.searching -> "جارٍ البحث…"
                    search.results.isEmpty() && locations.isEmpty() -> "لا توجد نتائج. جرّب اسم سورة أو رقم صفحة أو كلمات من آية."
                    search.surah != null -> "${quranResultsLabel(visible.size)} في سورة ${search.surah}. اضغط على النتيجة لتفتح صفحتها."
                    else -> "${quranResultsLabel(search.results.size + locations.size)}. اضغط على النتيجة لتفتح صفحتها."
                },
                Modifier.padding(vertical = 12.dp), fontSize = 12.sp, color = TextMuted,
            )
            if (chapters.size > 1 && search.results.size >= 10) LazyRow(
                Modifier.padding(bottom = 8.dp).testTag("quran_search_chapters"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = search.surah == null,
                        onClick = { search.surah = null; scope.launch { search.listState.scrollToItem(0) } },
                        label = { Text("الكل (${quranNumber(search.results.size + locations.size)})") },
                    )
                }
                items(chapters, key = { it.key }) { (name, count) ->
                    FilterChip(
                        selected = search.surah == name,
                        onClick = { search.surah = name; scope.launch { search.listState.scrollToItem(0) } },
                        label = { Text("$name (${quranNumber(count)})") },
                    )
                }
            }
            LazyColumn(
                Modifier.weight(1f).testTag("quran_search_results"), state = search.listState,
                contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (search.query.isBlank() && recents.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("آخر ما بحثت عنه", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimary)
                            TextButton(onClick = onClearRecents) { Text("امسح السجل", fontSize = 12.sp) }
                        }
                    }
                    items(recents, key = { it }) { recent ->
                        Text(
                            recent,
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White)
                                .clickable { updateQuery(recent) }.padding(14.dp).testTag("quran_recent_search"),
                            fontSize = 16.sp, color = TextDark,
                        )
                    }
                }
                if (search.surah == null) {
                    items(locations, key = { "location:$it" }) { location ->
                        QuranSearchLocationRow(location, catalog, divisions) { play ->
                            focus.clearFocus()
                            onLocation(location, play)
                        }
                    }
                }
                itemsIndexed(visible.take(shown)) { position, result ->
                    QuranSearchResultRow(
                        result, search.query,
                        onClick = { focus.clearFocus(); onResult(position) },
                        onPlay = { focus.clearFocus(); onPlayResult(position) },
                    )
                }
                if (visible.size > shown) item {
                    TextButton(onClick = { shown += FirstResults }, modifier = Modifier.fillMaxWidth()) {
                        Text("اعرض المزيد (${quranNumber(visible.size - shown)})")
                    }
                }
            }
            TextButton(onClick = { showSources = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("مصادر النص") }
        }
    }
    if (showSources) AlertDialog(
        onDismissRequest = { showSources = false },
        title = { Text("مصادر النص") },
        text = {
            Column {
                Text("نبحث في نص رواية قالون من الموسوعة القرآنية، ونستعين ببيانات Tanzil لأرقام الصفحات وللبحث بالرسم الإملائي.", fontSize = 14.sp)
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://quranpedia.net/surah/7/1"))) }) { Text("الموسوعة القرآنية") }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://tanzil.net"))) }) { Text("Tanzil Project · CC BY 3.0") }
            }
        },
        confirmButton = { TextButton(onClick = { showSources = false }) { Text("أغلق") } },
    )
}

@Composable
private fun QuranSearchLocationRow(
    location: QuranGoTo,
    catalog: QuranCatalog,
    divisions: QuranDivisions?,
    onOpen: (Boolean) -> Unit,
) {
    val (title, detail) = when (location) {
        is QuranGoTo.Page -> "صفحة ${location.printed}" to
            catalog.pages.getOrNull((location.printed - 1).coerceAtLeast(1) - 1)?.surahNames?.joinToString("، ").orEmpty()
        is QuranGoTo.Surah -> {
            val surah = catalog.surahs[location.number - 1]
            "سورة ${surah.name}" to listOfNotNull(
                surah.verseCount?.let(::quranVerseCountLabel), quranPageLabel(surah.page),
            ).joinToString(" · ")
        }
        is QuranGoTo.Hizb -> {
            val start = divisions?.hizbStart(location.number) ?: return
            "الحزب ${location.number}" to
                "${catalog.surahs[start.start.surah - 1].name} · الآية ${start.start.ayah} · ${quranPageLabel(start.page)}"
        }
        is QuranGoTo.Verse -> return
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White)
            .clickable { onOpen(false) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimary)
            if (detail.isNotEmpty()) Text(detail, fontSize = 12.sp, color = TextMuted)
        }
        if (location !is QuranGoTo.Page) IconButton(onClick = { onOpen(true) }) {
            Icon(painterResource(R.drawable.ic_play_arrow), "الاستماع إلى $title", tint = GreenPrimary)
        }
    }
}

@Composable
private fun QuranSearchResultRow(result: QuranSearchResult, query: String, onClick: () -> Unit, onPlay: () -> Unit) {
    // The words that matched wear the colour the verse gets on the page. They are found in the bundled text and
    // shown in the mushaf's spelling, word for word.
    val text = remember(result, query) {
        val matched = if (result.kind == QuranSearchKind.Reference) emptyList()
            else quranMatchRanges(result.text, query, similar = result.kind == QuranSearchKind.Similar)
        buildAnnotatedString {
            var offset = 0
            val tokens = result.text.split(' ')
            tokens.zip(QuranMushafText.words(tokens)).forEach { (token, shown) ->
                val start = offset
                offset += token.length + 1
                if (shown.isEmpty()) return@forEach
                if (length > 0) append(' ')
                val from = length
                append(shown)
                if (matched.any { it.first < start + token.length && it.last >= start }) {
                    addStyle(SpanStyle(background = QuranFoundColor), from, length)
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).clickable(onClick = onClick).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${result.surahName} · ${quranVerseLabel(result.verses)}",
                Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimary,
            )
            Text(quranPageLabel(result.page), fontSize = 12.sp, color = TextMuted)
            IconButton(onClick = onPlay) {
                Icon(painterResource(R.drawable.ic_play_arrow), when (result.verses.size) { 1 -> "الاستماع إلى الآية"; 2 -> "الاستماع إلى الآيتين"; else -> "الاستماع إلى الآيات" }, tint = GreenPrimary)
            }
        }
        Text(text, Modifier.padding(top = 8.dp), fontFamily = QuranMushafFont, fontSize = 22.sp, lineHeight = 36.sp, color = TextDark)
        when (result.kind) {
            QuranSearchKind.Reference -> "الانتقال إلى الآية"
            QuranSearchKind.Words -> "الكلمات كلها في الآية نفسها"
            QuranSearchKind.Similar -> "نتيجة تقريبية"
            QuranSearchKind.Phrase -> null
        }?.let { Text(it, Modifier.padding(top = 6.dp), fontSize = 11.sp, color = TextMuted) }
    }
}
