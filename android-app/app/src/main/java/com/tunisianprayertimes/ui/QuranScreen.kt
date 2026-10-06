package com.tunisianprayertimes.ui

import android.content.Context
import android.graphics.BitmapFactory
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
import com.tunisianprayertimes.quran.QuranSearchHit
import com.tunisianprayertimes.quran.withRecentSearch
import com.tunisianprayertimes.quran.QuranHighlights
import com.tunisianprayertimes.quran.QuranHighlightRect
import com.tunisianprayertimes.quran.QuranHighlightRepository
import com.tunisianprayertimes.quran.assets.QuranAssets
import com.tunisianprayertimes.quran.assets.QuranPackStatus
import com.tunisianprayertimes.quran.assets.open
import com.tunisianprayertimes.quran.audio.QuranAudioController
import com.tunisianprayertimes.quran.audio.QuranRepeatRange
import com.tunisianprayertimes.quran.audio.decodeQuranRepeat
import com.tunisianprayertimes.quran.audio.encode
import com.tunisianprayertimes.ui.theme.*
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// The first supplied scan includes Fatiha and Baqarah on printed page 2.
private const val QuranScanCount = 603
private const val QuranPrintedPageCount = 604
private const val QuranReaderPreferences = "quran_reader"

/**
 * The original scans are the reading surface, shown as soon as their list is read. The text index
 * and the verse geometry, which search, recitation and verse selection need, follow behind the page.
 */
@Composable
fun QuranScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    // A later visit in the same process finds everything already read.
    var pages by remember { mutableStateOf(QuranRepository.loadedPages()) }
    var catalog by remember { mutableStateOf(QuranRepository.loaded()) }
    var highlights by remember { mutableStateOf(QuranHighlightRepository.loaded()) }
    var failed by remember { mutableStateOf(false) }
    var detailsFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var pageSettled by remember { mutableStateOf(false) }
    LaunchedEffect(context, attempt) {
        failed = false
        detailsFailed = false
        try {
            if (pages == null) pages = withContext(Dispatchers.IO) {
                // Opening the saved page here keeps that disk read off the main thread.
                context.getSharedPreferences(QuranReaderPreferences, Context.MODE_PRIVATE).getInt("last_page", 1)
                QuranRepository.loadPages(context)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
            return@LaunchedEffect
        }
        if (catalog != null && highlights != null) return@LaunchedEffect
        // The visible page is decoded first: reading the index would compete with it for the processor.
        withTimeoutOrNull(2_000L) { snapshotFlow { pageSettled }.first { it } }
        try {
            coroutineScope {
                launch { catalog = withContext(Dispatchers.IO) { QuranRepository.load(context) } }
                launch { highlights = withContext(Dispatchers.IO) { QuranHighlightRepository.load(context) } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            detailsFailed = true
        }
    }
    AdhkarTheme {
        Box(modifier.fillMaxSize().statusBarsPadding()) {
            val scans = pages
            when {
                scans != null -> QuranReader(
                    // The index knows which chapters each page holds.
                    pages = catalog?.pages ?: scans,
                    catalog = catalog,
                    highlights = highlights,
                    detailsFailed = detailsFailed,
                    onRetry = { attempt++ },
                    onPageSettled = { pageSettled = true },
                )
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
private fun QuranReader(
    pages: List<QuranPage>,
    /** Null while the text index and the verse geometry are still being read. */
    catalog: QuranCatalog?,
    highlights: QuranHighlights?,
    detailsFailed: Boolean,
    onRetry: () -> Unit,
    /** The page in view has been drawn, or could not be. */
    onPageSettled: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { context.getSharedPreferences(QuranReaderPreferences, Context.MODE_PRIVATE) }
    val pager = rememberPagerState(
        initialPage = remember { prefs.getInt("last_page", 1).coerceIn(1, pages.size) - 1 },
        pageCount = { pages.size },
    )
    val scope = rememberCoroutineScope()
    val playback by QuranAudioController.state.collectAsStateWithLifecycle()
    val dragging by pager.interactionSource.collectIsDraggedAsState()
    var followAudio by rememberSaveable { mutableStateOf(true) }
    var lastFollowedSurah by remember { mutableStateOf<Int?>(null) }
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    // The verses the repeat sheet opens on, kept across a re-created activity.
    var repeatDraft by rememberSaveable { mutableStateOf<String?>(null) }
    // The verses a tapped search result found, coloured until the reader leaves their page(s).
    var foundDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val found = remember(foundDraft) { QuranSearchHit.decode(foundDraft) }
    // The last search, kept while the reader is open so its results can be come back to and walked through.
    val search = rememberQuranSearch(catalog)
    var recentSearches by remember { mutableStateOf(readRecentSearches(prefs)) }
    var zoomed by remember { mutableStateOf(false) }
    val page = pages[pager.currentPage]
    val haptics = LocalHapticFeedback.current
    val repeatSheet = remember(panel, repeatDraft) { if (panel == "repeat") decodeQuranRepeat(repeatDraft)?.first else null }
    var gestureHint by remember { mutableStateOf(!prefs.getBoolean("gesture_hint_seen", false)) }
    // Pages and recitations this install downloads rather than carries.
    val media = rememberQuranMedia()
    val starter = rememberQuranRecitationStarter(media)
    val mobileData = rememberMobileDataLauncher()
    // Unknown until the layout is read: a missing page waits quietly rather than showing an error.
    val pagesReady = media?.pagesReady ?: false
    var pagesRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(media, pagesReady) {
        // The first visit fetches the pages by itself, unless that would use mobile data.
        val pagesPack = media?.pages ?: return@LaunchedEffect
        if (!pagesReady && !pagesRequested && media.state(pagesPack).status == QuranPackStatus.Missing &&
            !withContext(Dispatchers.IO) { media.isMetered() }) {
            pagesRequested = true
            media.fetch(listOf(pagesPack), allowMetered = false)
        }
    }

    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect {
            prefs.edit().putInt("last_page", it + 1).apply()
        }
    }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    LaunchedEffect(pager.currentPage) {
        // Keyed on the page alone: a result tapped just now is set before the pager has moved to it.
        val hit = found ?: return@LaunchedEffect
        val index = catalog ?: return@LaunchedEffect
        val shownOn = hit.verses.flatMap { index.pagesForVerse(it.surah, it.ayah) } + hit.page
        if (pager.currentPage + 1 !in shownOn) foundDraft = null
    }
    LaunchedEffect(dragging) { if (dragging) followAudio = false }
    LaunchedEffect(playback.surah, playback.ayah, playback.loading, followAudio, catalog) {
        if (playback.surah == null) lastFollowedSurah = null
        // Following a recitation needs the verse index; it starts as soon as that is read.
        val index = catalog ?: return@LaunchedEffect
        if (followAudio && !playback.loading) {
            val surah = playback.surah
            val ayah = playback.ayah
            val versePages = if (surah != null && ayah != null) index.pagesForVerse(surah, ayah)
                else if (surah != lastFollowedSurah && playback.positionMs < playback.introEndMs)
                    index.surahs.firstOrNull { it.number == surah }?.let { listOf(it.page) }.orEmpty()
                else emptyList()
            if (versePages.isNotEmpty() && pager.currentPage + 1 !in versePages) {
                pager.scrollToPage(versePages.first() - 1)
            }
            lastFollowedSurah = surah
        }
    }
    fun openPage(number: Int, hit: QuranSearchHit? = null) {
        panel = null
        followAudio = false
        foundDraft = hit?.encode()
        scope.launch { pager.scrollToPage((number - 1).coerceIn(0, pages.lastIndex)) }
    }
    fun openResult(position: Int) {
        val result = search.visible.getOrNull(position) ?: return
        search.current = position
        recentSearches = recentSearches.withRecentSearch(search.query).also { writeRecentSearches(prefs, it) }
        openPage(result.page, result.hit)
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
        media?.let { downloads ->
            QuranPagesDownloadBanner(
                media = downloads,
                onDownload = { allowMetered -> downloads.fetch(listOfNotNull(downloads.pages), allowMetered) },
                onUseMobileData = { downloads.source?.confirmMobileData(listOfNotNull(downloads.pages), mobileData) },
            )
        }
        if (detailsFailed) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("تعذّر تجهيز البحث والتلاوة", Modifier.weight(1f), fontSize = 12.sp, color = TextMuted)
                TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp), contentAlignment = Alignment.BottomCenter) {
            // Search, recitation and verse selection are still being prepared behind the page.
            if (!detailsFailed && (catalog == null || highlights == null)) {
                LinearProgressIndicator(Modifier.fillMaxSize().testTag("quran_preparing"), color = GreenPrimary, trackColor = CardBorder)
            } else HorizontalDivider(color = CardBorder)
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("quran_pages"),
            key = { pages[it].number },
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
        ) { index ->
            val number = pages[index].number
            val chosen = repeatSheet?.from
            QuranPageImage(
                page = pages[index],
                active = index == pager.currentPage,
                pagesReady = pagesReady,
                highlightRects = highlights?.rectangles(number, playback.surah, playback.ayah).orEmpty(),
                foundRects = if (highlights != null && found != null) found.verses.flatMap { highlights.rectangles(number, it.surah, it.ayah) } else emptyList(),
                selectedRects = highlights?.rectangles(number, chosen?.surah, chosen?.ayah).orEmpty(),
                onVerseLongPress = { x, y ->
                    // Choosing a verse needs both its place on the page and the text index.
                    val verse = if (catalog != null) highlights?.verseAt(number, x, y) else null
                    if (verse != null) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        hideGestureHint()
                        // One pressed verse, repeated until the listener stops it.
                        openRepeat(QuranRepeatRange(verse, verse))
                    }
                    verse != null
                },
                onZoomChanged = { if (index == pager.currentPage) zoomed = it },
                onSettled = { if (index == pager.currentPage) onPageSettled() },
            )
        }
        QuranResultStepper(
            search, found, onOpen = ::openResult, onList = { panel = "search" },
            onClose = { foundDraft = null; search.current = -1 },
        )
        HorizontalDivider(color = CardBorder)
        QuranAudioControls(
            catalog, page.number, followAudio, onFollow = { followAudio = true }, onRepeat = ::openRepeat,
            media = media, starter = starter, mobileData = mobileData,
        )
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
        // A sheet asked for while the index is still being read opens as soon as it is there.
        "chapters" -> if (catalog != null) QuranChapterSheet(
            catalog, page.number, onDismiss = { panel = null }, onPage = ::openPage, onPageNumber = { panel = "page" },
        )
        "search" -> if (catalog != null) QuranSearchSheet(
            search, recentSearches,
            onClearRecents = { recentSearches = emptyList(); writeRecentSearches(prefs, emptyList()) },
            onDismiss = { panel = null }, onResult = ::openResult,
        )
        "page" -> QuranPageDialog(page.number, onDismiss = { panel = null }, onPage = ::openPage)
    }
    if (repeatSheet != null && catalog != null) {
        // A different pressed verse is a different sheet, never the previous one's saved fields.
        key(repeatDraft) {
            QuranRepeatSheet(
                catalog = catalog,
                initial = repeatSheet,
                onStart = { range ->
                    starter.start(QuranAudioController.state.value.reciterId, range.from.surah..range.to.surah) {
                        QuranAudioController.repeat(context, range)
                    }
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
    /** False while the pages are still to be downloaded; the page decodes again once they arrive. */
    pagesReady: Boolean,
    highlightRects: List<QuranHighlightRect>,
    /** The verses a search result found. */
    foundRects: List<QuranHighlightRect>,
    selectedRects: List<QuranHighlightRect>,
    /** A long press at a point of the original scan, each coordinate from 0 to 1; true when it chose a verse. */
    onVerseLongPress: (x: Float, y: Float) -> Boolean,
    onZoomChanged: (Boolean) -> Unit,
    /** The scan is on screen, or could not be read. */
    onSettled: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    // The gesture detector outlives recompositions; it must call the newest callback.
    val currentOnVerseLongPress by rememberUpdatedState(onVerseLongPress)
    val currentOnSettled by rememberUpdatedState(onSettled)
    var bitmap by remember(page.assetPath) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(page.assetPath) { mutableStateOf(false) }
    var missing by remember(page.assetPath) { mutableStateOf(false) }
    var attempt by remember(page.assetPath) { mutableIntStateOf(0) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(page.number) { mutableFloatStateOf(1f) }
    var offset by remember(page.number) { mutableStateOf(Offset.Zero) }
    val currentPagesReady by rememberUpdatedState(pagesReady)
    LaunchedEffect(page.assetPath, attempt) {
        if (bitmap != null) return@LaunchedEffect
        failed = false
        missing = false
        try {
            val decoded = withContext(Dispatchers.IO) {
                // Null until the pages are downloaded, on installs that do not carry them.
                val scan = QuranAssets.resolve(context, page.assetPath) ?: return@withContext null
                // Decode only the visible page and its two neighbours. Keep native scan detail for zoom.
                scan.open(context).use { stream ->
                    checkNotNull(BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    })).asImageBitmap()
                }
            }
            // Pages said to be on the device but not found is a failure the reader can retry.
            if (decoded == null) { if (currentPagesReady) failed = true else missing = true } else bitmap = decoded
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    // The pages arrived after this one was found missing: read it again. A page that failed because
    // its pack was damaged waits for the pack's new download the same way.
    LaunchedEffect(pagesReady, missing) { if (pagesReady && missing) attempt++ }
    LaunchedEffect(pagesReady) { if (!pagesReady && failed) { failed = false; missing = true } }
    LaunchedEffect(bitmap, failed, missing, active) { if (bitmap != null || failed || missing) currentOnSettled() }
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
                    // Only the found, recited and pressed verses are coloured; the rest of the page stays white.
                    tint(foundRects, QuranFoundColor)
                    tint(highlightRects, Color(0x5266BB6A))
                    tint(selectedRects, Color(0x5200695C))
                }
            }
            failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("تعذّر عرض الصفحة", color = TextMuted)
                TextButton(onClick = { attempt++ }) { Text("إعادة المحاولة") }
            }
            missing -> Text("الصفحة غير محمّلة بعد", Modifier.testTag("quran_page_missing"), color = TextMuted)
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
        Column(Modifier.fillMaxWidth().quranSheetHeight().padding(horizontal = 16.dp)) {
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

@Composable
internal fun QuranPanelHeader(title: String, onDismiss: () -> Unit) {
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

internal fun quranNumber(value: Int): String = value.toString()

internal fun quranInputNumber(value: String): Int? = value.trim()
    .map { c -> c.digitToIntOrNull()?.digitToChar() ?: c }.joinToString("").toIntOrNull()

internal fun quranPageLabel(scan: Int): String = if (scan <= QuranScanCount) "صفحة ${quranNumber(scan + 1)}"
    else "الملحق ${quranNumber(scan - QuranScanCount)}"

private fun quranFilter(value: String): String = value.trim()
    .replace(Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06EDـ]"), "")
    .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا').replace('ى', 'ي')
