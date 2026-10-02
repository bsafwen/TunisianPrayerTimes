package com.tunisianprayertimes.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranPage
import com.tunisianprayertimes.quran.QuranRepository
import com.tunisianprayertimes.quran.QuranSearchResult
import com.tunisianprayertimes.quran.QuranHighlights
import com.tunisianprayertimes.quran.QuranHighlightRect
import com.tunisianprayertimes.quran.QuranHighlightRepository
import com.tunisianprayertimes.quran.audio.QuranAudioController
import com.tunisianprayertimes.quran.audio.QuranRepeatRange
import com.tunisianprayertimes.quran.audio.decodeQuranRepeat
import com.tunisianprayertimes.quran.audio.encode
import com.tunisianprayertimes.ui.theme.*
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The first supplied scan includes Fatiha and Baqarah on printed page 2.
private const val QuranScanCount = 603
private const val QuranPrintedPageCount = 604

/** The original scans are the reading surface; the separate text index is only for finding pages. */
@Composable
fun QuranScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var catalog by remember { mutableStateOf<QuranCatalog?>(null) }
    var highlights by remember { mutableStateOf<QuranHighlights?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(context, attempt) {
        failed = false
        try {
            val assets = withContext(Dispatchers.IO) {
                QuranRepository.load(context) to QuranHighlightRepository.load(context)
            }
            highlights = assets.second
            catalog = assets.first
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    AdhkarTheme {
        Box(modifier.fillMaxSize().statusBarsPadding()) {
            val loaded = catalog
            when {
                loaded != null -> QuranReader(loaded, checkNotNull(highlights))
                failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("تعذّر فتح المصحف", color = TextMuted)
                    TextButton(onClick = { attempt++ }) { Text("إعادة المحاولة") }
                }
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun QuranReader(catalog: QuranCatalog, highlights: QuranHighlights) {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { context.getSharedPreferences("quran_reader", Context.MODE_PRIVATE) }
    val pager = rememberPagerState(
        initialPage = remember { prefs.getInt("last_page", 1).coerceIn(1, catalog.pages.size) - 1 },
        pageCount = { catalog.pages.size },
    )
    val scope = rememberCoroutineScope()
    val playback by QuranAudioController.state.collectAsStateWithLifecycle()
    val dragging by pager.interactionSource.collectIsDraggedAsState()
    var followAudio by rememberSaveable { mutableStateOf(true) }
    var lastFollowedSurah by remember { mutableStateOf<Int?>(null) }
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    // The verses the repeat sheet opens on, kept across a re-created activity.
    var repeatDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var zoomed by remember { mutableStateOf(false) }
    val page = catalog.pages[pager.currentPage]
    val haptics = LocalHapticFeedback.current
    val repeatSheet = remember(panel, repeatDraft) { if (panel == "repeat") decodeQuranRepeat(repeatDraft)?.first else null }
    var gestureHint by remember { mutableStateOf(!prefs.getBoolean("gesture_hint_seen", false)) }

    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect {
            prefs.edit().putInt("last_page", it + 1).apply()
        }
    }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    LaunchedEffect(dragging) { if (dragging) followAudio = false }
    LaunchedEffect(playback.surah, playback.ayah, playback.loading, followAudio) {
        if (playback.surah == null) lastFollowedSurah = null
        if (followAudio && !playback.loading) {
            val surah = playback.surah
            val ayah = playback.ayah
            val versePages = if (surah != null && ayah != null) catalog.pagesForVerse(surah, ayah)
                else if (surah != lastFollowedSurah && playback.positionMs < playback.introEndMs)
                    catalog.surahs.firstOrNull { it.number == surah }?.let { listOf(it.page) }.orEmpty()
                else emptyList()
            if (versePages.isNotEmpty() && pager.currentPage + 1 !in versePages) {
                pager.scrollToPage(versePages.first() - 1)
            }
            lastFollowedSurah = surah
        }
    }
    fun openPage(number: Int) {
        panel = null
        followAudio = false
        scope.launch { pager.scrollToPage((number - 1).coerceIn(0, catalog.pages.lastIndex)) }
    }
    fun openRepeat(range: QuranRepeatRange) {
        repeatDraft = range.encode(1)
        panel = "repeat"
    }
    fun hideGestureHint() {
        gestureHint = false
        prefs.edit().putBoolean("gesture_hint_seen", true).apply()
    }

    Column(Modifier.fillMaxSize().testTag("quran_reader")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("القرآن الكريم", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                Text("برواية قالون عن نافع", fontSize = 12.sp, color = TextMuted)
            }
            IconButton(onClick = { panel = "search" }, modifier = Modifier.testTag("quran_search")) {
                Icon(painterResource(R.drawable.ic_adhkar_search), "البحث في القرآن", tint = GreenPrimary)
            }
            IconButton(onClick = { panel = "chapters" }, modifier = Modifier.testTag("quran_chapters")) {
                Icon(painterResource(R.drawable.ic_adhkar_list), "فهرس السور", tint = GreenPrimary)
            }
        }
        HorizontalDivider(color = CardBorder)
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("quran_pages"),
            key = { catalog.pages[it].number },
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
        ) { index ->
            val number = catalog.pages[index].number
            val chosen = repeatSheet?.from
            QuranPageImage(
                page = catalog.pages[index],
                active = index == pager.currentPage,
                highlightRects = highlights.rectangles(number, playback.surah, playback.ayah),
                selectedRects = highlights.rectangles(number, chosen?.surah, chosen?.ayah),
                onVerseLongPress = { x, y ->
                    val verse = highlights.verseAt(number, x, y)
                    if (verse != null) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        hideGestureHint()
                        // One pressed verse, repeated until the listener stops it.
                        openRepeat(QuranRepeatRange(verse, verse))
                    }
                    verse != null
                },
                onZoomChanged = { if (index == pager.currentPage) zoomed = it },
            )
        }
        HorizontalDivider(color = CardBorder)
        QuranAudioControls(catalog, page.number, followAudio, onFollow = { followAudio = true }, onRepeat = ::openRepeat)
        if (gestureHint) {
            // Until it is dismissed or a verse is first pressed; afterwards the page keeps this space.
            HorizontalDivider(color = CardBorder)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "انقر مرتين للتكبير · اضغط مطولًا على آية لتكرارها",
                    Modifier.weight(1f).testTag("quran_gesture_hint"), fontSize = 11.sp, color = TextMuted,
                )
                IconButton(onClick = ::hideGestureHint, modifier = Modifier.size(36.dp)) {
                    Icon(painterResource(R.drawable.ic_adhkar_close), "إخفاء التلميح", Modifier.size(16.dp), tint = TextMuted)
                }
            }
        }
    }
    when (panel) {
        "chapters" -> QuranChapterSheet(
            catalog, page.number, onDismiss = { panel = null }, onPage = ::openPage, onPageNumber = { panel = "page" },
        )
        "search" -> QuranSearchSheet(catalog, onDismiss = { panel = null }, onPage = ::openPage)
        "page" -> QuranPageDialog(page.number, onDismiss = { panel = null }, onPage = ::openPage)
    }
    if (repeatSheet != null) {
        // A different pressed verse is a different sheet, never the previous one's saved fields.
        key(repeatDraft) {
            QuranRepeatSheet(
                catalog = catalog,
                initial = repeatSheet,
                onStart = { range ->
                    QuranAudioController.repeat(context, range)
                    followAudio = true
                    panel = null
                },
                onDismiss = { panel = null },
            )
        }
    }
}

@Composable
private fun QuranPageImage(
    page: QuranPage,
    active: Boolean,
    highlightRects: List<QuranHighlightRect>,
    selectedRects: List<QuranHighlightRect>,
    /** A long press at a point of the original scan, each coordinate from 0 to 1; true when it chose a verse. */
    onVerseLongPress: (x: Float, y: Float) -> Boolean,
    onZoomChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    // The gesture detector outlives recompositions; it must call the newest callback.
    val currentOnVerseLongPress by rememberUpdatedState(onVerseLongPress)
    var bitmap by remember(page.assetPath) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(page.assetPath) { mutableStateOf(false) }
    var attempt by remember(page.assetPath) { mutableIntStateOf(0) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(page.number) { mutableFloatStateOf(1f) }
    var offset by remember(page.number) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(page.assetPath, attempt) {
        failed = false
        try {
            bitmap = withContext(Dispatchers.IO) {
                // Decode only the visible page and its two neighbours. Keep native scan detail for zoom.
                context.assets.open(page.assetPath).use { stream ->
                    checkNotNull(BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    })).asImageBitmap()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero } }
    LaunchedEffect(scale, active) { if (active) onZoomChanged(scale > 1.01f) }
    BackHandler(enabled = active && scale > 1f) { scale = 1f; offset = Offset.Zero }
    fun boundedOffset(value: Offset, zoom: Float): Offset {
        val image = bitmap ?: return Offset.Zero
        val fit = minOf(size.width.toFloat() / image.width, size.height.toFloat() / image.height)
        val maxX = ((image.width * fit * zoom - size.width) / 2f).coerceAtLeast(0f)
        val maxY = ((image.height * fit * zoom - size.height) / 2f).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
        offset = boundedOffset(offset + pan, scale)
    }
    Box(
        Modifier.fillMaxSize().background(Color.White).clip(RoundedCornerShape(2.dp))
            .onSizeChanged { size = it; offset = boundedOffset(offset, scale) }
            .semantics(mergeDescendants = true) {
                customActions = listOf(CustomAccessibilityAction(if (scale > 1f) "عرض الصفحة كاملة" else "تكبير الصفحة") {
                    scale = if (scale > 1f) 1f else 2.5f
                    offset = Offset.Zero
                    true
                })
            }
            .transformable(transform, canPan = { scale > 1f })
            .pointerInput(page.number) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                        offset = boundedOffset((Offset(size.width / 2f, size.height / 2f) - tap) * (scale - 1f), scale)
                    }
                })
            }
            // Its own detector: a long press on a margin, or with two fingers resting before a pinch,
            // must leave the rest of the gesture to the zoom, the pan and the page swipe.
            .pointerInput(page.number) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Null when the finger lifts or another gesture takes over first.
                    val press = awaitLongPressOrCancellation(down.id)?.position ?: return@awaitEachGesture
                    val image = bitmap ?: return@awaitEachGesture
                    if (currentEvent.changes.count { it.pressed } != 1 || size.width == 0 || size.height == 0) return@awaitEachGesture
                    // Undo the zoom and pan, then the letterboxing of the fitted scan.
                    val fit = minOf(size.width.toFloat() / image.width, size.height.toFloat() / image.height)
                    val x = .5f + (press.x - size.width / 2f - offset.x) / (scale * image.width * fit)
                    val y = .5f + (press.y - size.height / 2f - offset.y) / (scale * image.height * fit)
                    if (x in 0f..1f && y in 0f..1f && currentOnVerseLongPress(x, y)) {
                        // The press chose a verse: nothing else acts on what is left of this gesture.
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        when {
            loaded != null -> Box(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
            ) {
                Image(
                    bitmap = loaded,
                    contentDescription = "${quranPageLabel(page.number)} ${page.surahNames.joinToString("، ")}",
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                )
                Canvas(Modifier.fillMaxSize()) {
                    val fit = minOf(this.size.width / loaded.width, this.size.height / loaded.height)
                    val imageWidth = loaded.width * fit
                    val imageHeight = loaded.height * fit
                    val origin = Offset((this.size.width - imageWidth) / 2f, (this.size.height - imageHeight) / 2f)
                    fun tint(rects: List<QuranHighlightRect>, color: Color) = rects.forEach { rect ->
                        drawRoundRect(
                            color = color,
                            topLeft = origin + Offset(rect.left * imageWidth, rect.top * imageHeight),
                            size = Size((rect.right - rect.left) * imageWidth, (rect.bottom - rect.top) * imageHeight),
                            cornerRadius = CornerRadius(3.dp.toPx()), blendMode = BlendMode.Multiply,
                        )
                    }
                    // Only the recited verse and the pressed one are coloured; the rest of the page stays white.
                    tint(highlightRects, Color(0x5266BB6A))
                    tint(selectedRects, Color(0x5200695C))
                }
            }
            failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("تعذّر عرض الصفحة", color = TextMuted)
                TextButton(onClick = { attempt++ }) { Text("إعادة المحاولة") }
            }
            else -> CircularProgressIndicator()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuranChapterSheet(
    catalog: QuranCatalog,
    currentPage: Int,
    onDismiss: () -> Unit,
    onPage: (Int) -> Unit,
    onPageNumber: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val currentSurahNames = catalog.pages[currentPage - 1].surahNames
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = catalog.surahs.indexOfFirst { it.name in currentSurahNames }.coerceAtLeast(0),
    )
    LaunchedEffect(query) { if (query.isNotEmpty()) listState.scrollToItem(0) }
    val chapters = remember(catalog, query) {
        catalog.surahs.filter { query.isBlank() || quranFilter(it.name).contains(quranFilter(query)) || it.number == quranInputNumber(query) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).imePadding().padding(horizontal = 16.dp)) {
            QuranPanelHeader("فهرس السور", onDismiss)
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("اسم السورة أو رقمها") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            TextButton(onClick = onPageNumber, modifier = Modifier.testTag("quran_page_picker")) {
                Text("الانتقال إلى رقم صفحة")
            }
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                items(chapters, key = { it.number }) { surah ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (surah.name in currentSurahNames) AdhkarSoftGreen else Color.Transparent)
                            .clickable { onPage(surah.page) }.padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(quranNumber(surah.number), Modifier.width(36.dp), color = TextMuted, fontSize = 13.sp)
                        Text(surah.name, Modifier.weight(1f), color = GreenPrimaryDark, fontWeight = FontWeight.SemiBold)
                        Text(quranPageLabel(surah.page), color = TextMuted, fontSize = 12.sp)
                    }
                }
                if (query.isBlank()) item {
                    TextButton(onClick = { onPage(QuranScanCount + 1) }, modifier = Modifier.fillMaxWidth()) { Text("ملحق أحكام القراءة") }
                }
                if (chapters.isEmpty()) item { Text("لا توجد سورة بهذا الاسم", Modifier.padding(24.dp), color = TextMuted) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuranSearchSheet(catalog: QuranCatalog, onDismiss: () -> Unit, onPage: (Int) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<QuranSearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(query, catalog) {
        results = emptyList()
        searching = query.isNotBlank()
        if (query.isNotBlank()) {
            delay(180)
            results = withContext(Dispatchers.Default) { catalog.search(query) }
        }
        searching = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).imePadding().padding(horizontal = 16.dp)) {
            QuranPanelHeader("البحث في القرآن", onDismiss)
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().testTag("quran_search_input"),
                placeholder = { Text("اكتب كلمة أو كلمات من الآية") }, singleLine = true,
                leadingIcon = { Icon(painterResource(R.drawable.ic_adhkar_search), null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(painterResource(R.drawable.ic_adhkar_close), "مسح البحث")
                    }
                },
                shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            )
            Text(
                when {
                    query.isBlank() -> "ابحث مع التشكيل أو بدونه"
                    searching -> "جارٍ البحث…"
                    results.isEmpty() -> "لا توجد نتائج. جرّب كلمة أخرى."
                    else -> "${quranNumber(results.size)} نتيجة · اضغط لفتح الصفحة"
                },
                Modifier.padding(vertical = 12.dp), fontSize = 12.sp, color = TextMuted,
            )
            LazyColumn(Modifier.weight(1f).testTag("quran_search_results"), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results) { result ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White)
                            .clickable { focus.clearFocus(); onPage(result.page) }.padding(14.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(result.surahName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimary)
                            Text(quranPageLabel(result.page), fontSize = 12.sp, color = TextMuted)
                        }
                        Text(result.text, Modifier.padding(top = 8.dp), fontFamily = AdhkarReadingFont, fontSize = 22.sp, lineHeight = 36.sp, color = TextDark)
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
                Text("نص البحث برواية قالون من الموسوعة القرآنية، مع الاستعانة ببيانات Tanzil لأرقام الصفحات والبحث بالإملاء المعتاد.", fontSize = 14.sp)
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://quranpedia.net/surah/7/1"))) }) { Text("الموسوعة القرآنية") }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://tanzil.net"))) }) { Text("Tanzil Project · CC BY 3.0") }
            }
        },
        confirmButton = { TextButton(onClick = { showSources = false }) { Text("إغلاق") } },
    )
}

@Composable
private fun QuranPanelHeader(title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
        IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_adhkar_close), "إغلاق") }
    }
}

@Composable
private fun QuranPageDialog(current: Int, onDismiss: () -> Unit, onPage: (Int) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val number = quranInputNumber(input)
    val valid = number != null && number in 1..QuranPrintedPageCount
    fun go() { if (valid) onPage((number!! - 1).coerceAtLeast(1)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("الانتقال إلى صفحة") },
        text = {
            Column {
                Text(quranPageLabel(current), color = TextMuted, fontSize = 13.sp)
                OutlinedTextField(
                    value = input, onValueChange = { input = it.take(4) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    label = { Text("رقم صفحة المصحف") }, singleLine = true, isError = input.isNotBlank() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                    supportingText = { Text("من ${quranNumber(1)} إلى ${quranNumber(QuranPrintedPageCount)}") },
                )
            }
        },
        confirmButton = { TextButton(onClick = ::go, enabled = valid) { Text("انتقال") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

private fun quranNumber(value: Int): String = String.format(Locale.forLanguageTag("ar"), "%d", value)

internal fun quranInputNumber(value: String): Int? = value.trim()
    .map { c -> c.digitToIntOrNull()?.digitToChar() ?: c }.joinToString("").toIntOrNull()

private fun quranPageLabel(scan: Int): String = if (scan <= QuranScanCount) "صفحة ${quranNumber(scan + 1)}"
    else "الملحق ${quranNumber(scan - QuranScanCount)}"

private fun quranFilter(value: String): String = value.trim()
    .replace(Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06EDـ]"), "")
    .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا').replace('ى', 'ي')
