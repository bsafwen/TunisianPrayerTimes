package com.tunisianprayertimes.ui

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Brush
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranDivisions
import com.tunisianprayertimes.quran.QuranDivisionsRepository
import com.tunisianprayertimes.quran.QuranGoTo
import com.tunisianprayertimes.quran.QuranHighlightRect
import com.tunisianprayertimes.quran.QuranHighlightRepository
import com.tunisianprayertimes.quran.QuranHighlights
import com.tunisianprayertimes.quran.QuranLibrary
import com.tunisianprayertimes.quran.QuranPage
import com.tunisianprayertimes.quran.QuranPlayAction
import com.tunisianprayertimes.quran.QuranRepository
import com.tunisianprayertimes.quran.QuranSearchHit
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.assets.QuranAssets
import com.tunisianprayertimes.quran.assets.QuranPackStatus
import com.tunisianprayertimes.quran.assets.open
import com.tunisianprayertimes.quran.audio.QuranAudioController
import com.tunisianprayertimes.quran.audio.QuranInterruption
import com.tunisianprayertimes.quran.audio.QuranPlaylist
import com.tunisianprayertimes.quran.audio.QuranRecitationTimings
import com.tunisianprayertimes.quran.audio.QuranRepeatRange
import com.tunisianprayertimes.quran.audio.QuranSleep
import com.tunisianprayertimes.quran.audio.QuranSurahRecording
import com.tunisianprayertimes.quran.audio.decodeQuranRepeat
import com.tunisianprayertimes.quran.audio.encode
import com.tunisianprayertimes.quran.quranPlayAction
import com.tunisianprayertimes.quran.quranShareText
import com.tunisianprayertimes.quran.withRecentSearch
import com.tunisianprayertimes.quran.hifz.HifzPlan
import com.tunisianprayertimes.quran.hifz.HifzGrade
import com.tunisianprayertimes.quran.hifz.HifzLearning
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzLookAlikes
import com.tunisianprayertimes.quran.hifz.HifzLookAlikesCache
import com.tunisianprayertimes.quran.hifz.hifzWords
import com.tunisianprayertimes.quran.hifz.HifzPoint
import com.tunisianprayertimes.quran.hifz.HifzReminder
import com.tunisianprayertimes.quran.hifz.HifzRoundSize
import com.tunisianprayertimes.quran.hifz.HifzPace
import com.tunisianprayertimes.quran.hifz.epochDay
import com.tunisianprayertimes.quran.hifz.HifzRepository
import com.tunisianprayertimes.quran.hifz.HifzSettings
import com.tunisianprayertimes.quran.hifz.askable
import com.tunisianprayertimes.quran.hifz.reviewBatch
import com.tunisianprayertimes.quran.hifz.answerVerses
import com.tunisianprayertimes.quran.hifz.sameSlip
import com.tunisianprayertimes.quran.hifz.graded
import com.tunisianprayertimes.quran.hifz.hifzCue
import com.tunisianprayertimes.quran.hifz.hifzDrill
import com.tunisianprayertimes.quran.hifz.hifzListen
import com.tunisianprayertimes.quran.hifz.HifzCueTitle
import com.tunisianprayertimes.quran.hifz.HifzDrillTitle
import com.tunisianprayertimes.quran.hifz.HifzListenOneTitle
import com.tunisianprayertimes.quran.hifz.todayEpochDay
import com.tunisianprayertimes.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sign

// The first supplied scan includes Fatiha and Baqarah on printed page 2.
internal const val QuranScanCount = 603
/** «مراجعة»: the colour that covers a verse. */
internal val QuranReviewMask = Color(0xFFEFE8DA)
/** The day's review: the cover of the verse asked, in the points' colour, so that the page says which verse it is. */
private val QuranAskedMask = Color(0xFFE6DCF7)
/** A page in front of the reader this long, no sheet over it, counts as read: its points wait before they are asked. */
private const val QuranPageSeenMs = 20_000L
/** Half the height of a verse number on the scan (59 of 1450), with a little room. */
private const val QuranNumberHalfHeight = 0.0233f
/** «مراجعة»: how far around a verse number a tap still opens its verse, rather than erasing. */
private const val QuranNumberTouchSlop = 0.008f

/** How long the verse card takes to slide in, and the page to make room for it. */
private const val QuranCardMillis = 220

/**
 * «مراجعة»: a piece of a line the finger has erased, in the scan's coordinates, from [at]
 * ([android.os.SystemClock.uptimeMillis]) on. It covers itself again a moment later.
 */
private class QuranErased(val left: Float, val top: Float, val right: Float, val bottom: Float, val at: Long)

/** «مراجعة»: how wide the finger erases, on screen: about a word with the page fitted, a letter or two zoomed in. */
private val QuranEraserWidth = 32.dp
/** The header's height until it is first measured: its bar, its divider and the progress line under it. */
private val QuranHeaderHeight = 55.dp
/** «مراجعة»: an erased piece stays clear this long after the finger left it, then fades back over [QuranEraseFadeMs]. */
private const val QuranErasedMs = 6_800L
private const val QuranEraseFadeMs = 1_200L
/** A verse «مراجعة» shows whole, opened by its number, a search or a bookmark, does not hide before this. */
private const val QuranReviewMinMs = 1_500L
/** Hosary's pace, for a verse whose timing is not read yet. */
private const val QuranMsPerLetter = 110L
private const val QuranReaderPreferences = "quran_reader"
/** The screen stays on this long after the last touch, unless a recitation plays. */
private const val KeepAwakeAfterTouchMs = 10 * 60_000L

/**
 * The original scans are the reading surface, shown as soon as their list is read. The text index
 * and the verse geometry, which search, recitation and verse selection need, follow behind the page.
 * [onHideNavigation] asks the app to slide its bottom bar away, for the expanded player and focus mode.
 */
@Composable
fun QuranScreen(
    modifier: Modifier = Modifier,
    /** How much of the reader's foot the app's bottom bar covers now, sliding in or out; read while laying out. */
    bottomBar: () -> androidx.compose.ui.unit.Dp = { 0.dp },
    /** How much of it the bar covers when shown: the page rests clear of it, shown or not. */
    bottomBarRoom: () -> androidx.compose.ui.unit.Dp = { 0.dp },
    onHideNavigation: (Boolean) -> Unit = {},
    /** «hifz» or «hifz_review», numbered so each request is handled once. */
    hifzRequest: Pair<String, Int>? = null,
) {
    val context = LocalContext.current.applicationContext
    // A later visit in the same process finds everything already read.
    var pages by remember { mutableStateOf(QuranRepository.loadedPages()) }
    var catalog by remember { mutableStateOf(QuranRepository.loaded()) }
    var highlights by remember { mutableStateOf(QuranHighlightRepository.loaded()) }
    var divisions by remember { mutableStateOf(QuranDivisionsRepository.loaded()) }
    var failed by remember { mutableStateOf(false) }
    var detailsFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var pageSettled by remember { mutableStateOf(false) }
    LaunchedEffect(context, attempt) {
        launch(Dispatchers.IO) { HifzRepository.load(context) }
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
        if (catalog != null && highlights != null && divisions != null) return@LaunchedEffect
        // The visible page is decoded first: reading the index would compete with it for the processor.
        withTimeoutOrNull(2_000L) { snapshotFlow { pageSettled }.first { it } }
        try {
            coroutineScope {
                launch { catalog = withContext(Dispatchers.IO) { QuranRepository.load(context) } }
                launch { highlights = withContext(Dispatchers.IO) { QuranHighlightRepository.load(context) } }
                launch { divisions = withContext(Dispatchers.IO) { runCatching { QuranDivisionsRepository.load(context) }.getOrNull() } }
                launch(Dispatchers.IO) { QuranAudioController.initialize(context) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            detailsFailed = true
        }
    }
    DisposableEffect(Unit) { onDispose { onHideNavigation(false) } }
    // On its side, the phone's camera cutout or its button bar is at one end of the reader: the same margin at
    // both ends keeps the page in the middle.
    val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    val layoutDirection = LocalLayoutDirection.current
    val side = with(LocalDensity.current) {
        maxOf(sideInsets.getLeft(this, layoutDirection), sideInsets.getRight(this, layoutDirection)).toDp()
    }
    AdhkarTheme {
        Box(
            modifier.fillMaxSize().background(BgCream).statusBarsPadding()
                .padding(horizontal = side).consumeWindowInsets(PaddingValues(horizontal = side)),
        ) {
            val scans = pages
            when {
                // The hifz sheets show their verses on the mushaf too, once the verses' places are read.
                scans != null -> CompositionLocalProvider(LocalQuranHighlights provides highlights) {
                    QuranReader(
                        // The index knows which chapters each page holds.
                        pages = catalog?.pages ?: scans,
                        catalog = catalog,
                        highlights = highlights,
                        divisions = divisions,
                        detailsFailed = detailsFailed,
                        onRetry = { attempt++ },
                        onPageSettled = { pageSettled = true },
                        onHideNavigation = onHideNavigation,
                        bottomBar = bottomBar,
                        bottomBarRoom = bottomBarRoom,
                        hifzRequest = hifzRequest,
                    )
                }
                failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("تعذّر فتح المصحف", color = TextMuted)
                    TextButton(onClick = { attempt++ }) { Text("إعادة المحاولة") }
                }
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

/** The notebook's writes, one at a time and in the order they were asked for. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val HifzWrites = Dispatchers.IO.limitedParallelism(1)

private fun encodeVerse(verse: QuranVerseReference?): String? = verse?.let { "${it.surah}:${it.ayah}" }
private fun decodeVerse(value: String?): QuranVerseReference? = value?.split(':')?.takeIf { it.size == 2 }?.let { parts ->
    val s = parts[0].toIntOrNull() ?: return null
    val a = parts[1].toIntOrNull() ?: return null
    QuranVerseReference(s, a)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuranReader(
    pages: List<QuranPage>,
    /** Null while the text index and the verse geometry are still being read. */
    catalog: QuranCatalog?,
    highlights: QuranHighlights?,
    divisions: QuranDivisions?,
    detailsFailed: Boolean,
    onRetry: () -> Unit,
    /** The page in view has been drawn, or could not be. */
    onPageSettled: () -> Unit,
    onHideNavigation: (Boolean) -> Unit,
    bottomBar: () -> androidx.compose.ui.unit.Dp,
    bottomBarRoom: () -> androidx.compose.ui.unit.Dp,
    hifzRequest: Pair<String, Int>?,
) {
    val context = LocalContext.current.applicationContext
    val activityContext = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(QuranReaderPreferences, Context.MODE_PRIVATE) }
    val library = remember(context) { QuranLibrary.of(context) }
    var libraryRevision by remember { mutableIntStateOf(0) }
    val pager = rememberPagerState(
        initialPage = remember { prefs.getInt("last_page", 1).coerceIn(1, pages.size) - 1 },
        pageCount = { pages.size },
    )
    val scope = rememberCoroutineScope()
    val playback by QuranAudioController.state.collectAsStateWithLifecycle()
    val listening by QuranAudioController.listening.collectAsStateWithLifecycle()
    val dragging by pager.interactionSource.collectIsDraggedAsState()
    var followAudio by rememberSaveable { mutableStateOf(true) }
    var lastFollowedSurah by remember { mutableStateOf<Int?>(null) }
    /** The page an auto-following recitation turned to: settling there is not the reader's choice. */
    var autoTurned by remember { mutableStateOf<Int?>(null) }
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionDraft by rememberSaveable { mutableStateOf<String?>(null) }
    // Read through a State, a call at each use: past 256 registers, D8 lost reads of the plain value in the reader's
    // main lambda (see reviewOnPage), as it did of the recited verse's two values below.
    val selection by rememberUpdatedState(remember(selectionDraft) { decodeVerse(selectionDraft) })
    var rangeFromDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var rangeToDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val rangeFrom = remember(rangeFromDraft) { decodeVerse(rangeFromDraft) }
    val rangeTo = remember(rangeToDraft) { decodeVerse(rangeToDraft) }
    var rangeTimes by rememberSaveable { mutableStateOf(listening.lastRepeatTimes ?: 0) }
    var typedRangeDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Every visit to the tab opens in focus mode: the page alone, tools one tap on the margin away. Not saved,
    // so that leaving the tab forgets the tools shown; turning the phone or another app in between keeps them.
    var focusChosen by remember { mutableStateOf(true) }
    var navigatedSincePause by rememberSaveable { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<com.tunisianprayertimes.ui.QuranScrub?>(null) }
    var holdDelta by remember { mutableStateOf<Long?>(null) }
    // The verses a tapped search result found, coloured until the reader leaves their page(s).
    var foundDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val found = remember(foundDraft) { QuranSearchHit.decode(foundDraft) }
    // The last search, kept while the reader is open so its results can be come back to and walked through.
    val search = rememberQuranSearch(catalog)
    var recentSearches by remember { mutableStateOf(readRecentSearches(prefs)) }
    var zoomed by remember { mutableStateOf(false) }
    val page = pages[pager.currentPage]
    val clipboard = LocalClipboard.current
    val snackbar = remember { SnackbarHostState() }
    var tapHint by remember { mutableStateOf(!prefs.getBoolean("hint_tap_seen", false)) }
    var stripHint by remember { mutableStateOf(!prefs.getBoolean("hint_strip_seen", false)) }
    var focusHint by remember { mutableStateOf(!prefs.getBoolean("hint_focus_seen", false)) }
    var reviewEraserHint by remember { mutableStateOf(!prefs.getBoolean("hint_review_eraser_seen", false)) }
    var fineSeekHint by remember { mutableStateOf(!prefs.getBoolean("hint_fine_seek_seen", false)) }
    var keepAwake by remember { mutableStateOf(prefs.getBoolean("keep_awake", true)) }
    /** «مراجعة»: every verse is covered; the finger erases the cover where it touches, and it comes back by itself. */
    var reviewMode by rememberSaveable { mutableStateOf(prefs.getBoolean("review_mode", false)) }
    var nightOn by remember { mutableStateOf(prefs.getBoolean("night_on", false)) }
    var nightLevel by remember { mutableFloatStateOf(prefs.getFloat("night_level", 0.3f)) }
    // Read only by the keep-awake effect, outside composition: a touch must not recompose the reader.
    val lastTouch = remember { mutableLongStateOf(System.currentTimeMillis()) }
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    /** The header's height, kept while it is hidden: where the page is fitted whole, the header comes over it. */
    var headerHeightPx by remember { mutableIntStateOf(0) }
    /** The page at rest, fitted whole and unzoomed: its top and its height in the reader (x, y); unknown at zero height. */
    var pageFrame by remember { mutableStateOf(Offset.Zero) }
    /** The card about a verse or a passage, while at the page's foot: its height, for the page that scrolls under it. */
    var cardAtFootPx by remember { mutableIntStateOf(0) }
    /** The verse the hidden verse card keeps: not state, it only spares rebuilding the card. */
    val lastCardVerse = remember { arrayOfNulls<QuranVerseReference>(1) }
    var panelHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val pageFillsWidth = quranPageFillsWidth(configuration.screenWidthDp, configuration.screenHeightDp, configuration.smallestScreenWidthDp)
    // Pages and recitations this install downloads rather than carries.
    val media = rememberQuranMedia()
    val starter = rememberQuranRecitationStarter(media)
    val mobileData = rememberMobileDataLauncher()
    // Unknown until the layout is read: a missing page waits quietly rather than showing an error.
    val pagesReady = media?.pagesReady ?: false
    var pagesRequested by rememberSaveable { mutableStateOf(false) }
    val reciter = QuranAudioController.reciter(playback.reciterId)
    val surahName = { n: Int -> catalog?.surahs?.getOrNull(n - 1)?.name.orEmpty() }
    val touchExploration = rememberTouchExploration()
    /**
     * The day's review shows on the page itself, the verse asked covered there, once the pages and the verses' places
     * are read. Not with TalkBack, which cannot erase, nor with the page on its side, too low for the card: a sheet
     * takes the review there, and it goes on where it was.
     */
    // Pages not known yet, as the activity comes back, are taken as there: the sheet would only flash in and out.
    // Read through a State, a call at each use: debug builds (D8) lost the plain flag inside the reader's main
    // lambda, past 256 registers, and showed the review card, with no review running, on every visit to the tab.
    // The same for the details' failure: a later edit made D8 lose one of the plain flag's two reads there.
    val detailsFailing by rememberUpdatedState(detailsFailed)
    val reviewOnPage by rememberUpdatedState(
        panel == "hifzReview" && catalog != null && highlights != null && (media?.pagesReady ?: true) &&
            !pageFillsWidth && !touchExploration && density.fontScale <= 1.3f
    )
    // The review on the page has the whole screen: no header, no player, no bottom bar.
    val focus = ((zoomed || focusChosen) && panel == null) || reviewOnPage
    val hifzPoints by HifzRepository.points.collectAsStateWithLifecycle()
    var hifzSettings by remember { mutableStateOf(HifzSettings.read(context)) }
    val pointsByVerse = remember(hifzPoints) { hifzPoints.groupBy { it.verse } }
    var lookAlikes by remember { mutableStateOf<HifzLookAlikes?>(HifzLookAlikesCache.loaded) }
    var resumes by remember { mutableIntStateOf(0) }
    /** The reader is on screen: a page read counts only while it is in front of the reader. */
    var inFront by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumes++
        inFront = true
        onPauseOrDispose { inFront = false }
    }
    val today by produceState(todayEpochDay(), resumes) {
        while (true) {
            value = todayEpochDay()
            delay(60_000L)
        }
    }
    var learning by remember { mutableStateOf(HifzLearning.read(context, todayEpochDay())) }
    LaunchedEffect(resumes) { learning = HifzLearning.read(context, todayEpochDay()) }
    /**
     * A verse looked at on purpose: its points wait before they are asked, and the reminder in the shade drops them, or
     * goes, at once rather than at its next alarm.
     */
    fun updateLearning(change: (HifzLearning) -> HifzLearning) {
        val current = learning.onDay(todayEpochDay())
        val next = change(current)
        if (next != learning) {
            learning = next.write(context)
            if (next.exposed != current.exposed || next.seen != current.seen) scope.launch(HifzWrites) { HifzReminder.refresh(context) }
        }
    }
    /**
     * The time the points due are taken at: moved on as a fresh point falls due, its gap after its slip or its answer
     * over, and with [today] at midnight; not every minute, which would draw the reader again for nothing.
     */
    val hifzNow by produceState(System.currentTimeMillis(), resumes, hifzPoints, today, learning.seen) {
        while (true) {
            value = System.currentTimeMillis()
            val next = hifzPoints.filter { it.fresh > 0 }.map { it.freshAskAt(learning.seen[it.verse]) }.filter { it > value }.minOrNull() ?: break
            delay(next - value + 1_000L)
        }
    }
    val eligibleDueIds = hifzPoints.askable(today, hifzNow, learning.seen, learning.onDay(today).exposed).map { it.id }.toSet()
    /** When the next fresh point may be asked, its gap after its slip, its answer and its answer last seen over. */
    val nextFreshAt = remember(hifzPoints, learning.seen) {
        hifzPoints.filter { it.fresh > 0 }.minOfOrNull { it.freshAskAt(learning.seen[it.verse]) }
    }
    /** «اليوم بعد المغرب»: when the next fresh point not due yet is asked, at a reminder or, with it off, in the review. */
    val nextFreshLabel by produceState<String?>(null, nextFreshAt, hifzNow, hifzSettings, today, hifzPoints, learning.seen) {
        val at = nextFreshAt?.takeIf { it > hifzNow }
        value = if (at == null) null else withContext(Dispatchers.IO) {
            // Each point at the reminder that will ask it: one asked once a day, already asked today, is tomorrow's.
            hifzPoints.filter { it.fresh > 0 }.mapNotNull { HifzReminder.nextAsk(context, it, learning.seen[it.verse], hifzNow) }
                .minByOrNull { it.at }?.let { HifzReminder.slotLabel(it, today) } ?: HifzReminder.dayAndClock(at, today)
        }
    }
    /**
     * When the fresh points not asked now come, from the reminders that will ask them: each notebook row's line, and how
     * many the first later reminder of today asks, for the hub.
     */
    val hifzLaterTimes by produceState(HifzLaterTimes(), hifzNow, hifzSettings, today, hifzPoints, learning.seen, learning.exposed) {
        val waiting = hifzPoints.filter { it.fresh > 0 && it.id !in eligibleDueIds }
        value = if (waiting.isEmpty()) HifzLaterTimes() else withContext(Dispatchers.IO) {
            val remind = hifzSettings.reminder
            // When each is asked, the day its label names, and the row's line.
            val at = waiting.associate { point ->
                point.id to if (remind) HifzReminder.nextAsk(context, point, learning.seen[point.verse], hifzNow)?.let {
                    Triple(it.at, maxOf(it.day, today), "نسألك عنها ${HifzReminder.slotLabel(it, today)}")
                } else point.freshAskAt(learning.seen[point.verse]).let {
                    Triple(it, epochDay(it), "تجدها في «مراجعة اليوم» ${HifzReminder.inReviewLabel(it, today)}")
                }
            }
            val rows = at.mapNotNull { (id, next) -> next?.let { id to it.third } }.toMap()
            // The hub's next ones of today: those of the first later reminder, or with the reminder off all of today's,
            // from the first one's time.
            val todays = at.values.filterNotNull().filter { it.second == today }
            val first = todays.minByOrNull { it.first }
            HifzLaterTimes(rows, first?.let { f ->
                if (remind) todays.count { it.first == f.first } to f.third.removePrefix("نسألك عنها ")
                else todays.size to "اليوم " + HifzReminder.inReviewLabel(f.first, today)
            })
        }
    }
    /** The day's review, fixed when it opens so grading does not reorder it. */
    var reviewIds by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    /** Each review opened starts over from its first point. */
    var reviewSession by rememberSaveable { mutableIntStateOf(0) }
    var reviewStartedDay by rememberSaveable { mutableLongStateOf(todayEpochDay()) }
    /** The day's answers in its reviews ended, «day:tally»: the day's next review adds to them. */
    var reviewDayDraft by rememberSaveable { mutableStateOf<String?>(null) }
    fun reviewDayTally(): HifzTally = reviewDayDraft?.split(':', limit = 2)
        ?.takeIf { it.size == 2 && it[0] == todayEpochDay().toString() }?.let { HifzTally.decode(it[1]) } ?: run {
            val day = todayEpochDay()
            val points = HifzRepository.points.value
            val checks = points.mapNotNull { it.checks.firstOrNull { check -> check.day == day } }
            HifzTally(
                held = checks.count { it.grade == HifzGrade.Held },
                hesitated = checks.count { it.grade == HifzGrade.Hesitated },
                again = checks.count { it.grade == HifzGrade.Again },
                heldNow = points.count { it.mastered && it.masteredAt?.let { at -> epochDay(at) == day } == true },
            )
        }
    /** The review's place and answers, whichever way it shows. */
    val reviewState = rememberSaveable(reviewSession, saver = HifzReviewState.Saver) {
        HifzReviewState(earlier = reviewDayTally(), exposedVerses = learning.onDay(todayEpochDay()).exposed, seenVerses = learning.seen)
    }
    /** The answers of the review in view count for the day's next one. */
    fun keepReviewDay() {
        if (panel == "hifzReview") reviewDayDraft = "${todayEpochDay()}:${reviewState.day.encode()}"
    }
    /** The points the review still asks: those whose verse it showed before their turn wait for the next reminder. */
    val reviewQueue = remember(reviewIds, hifzPoints, reviewState.deferredIds) {
        reviewState.questions(reviewIds.mapNotNull { id -> hifzPoints.firstOrNull { it.id == id } })
    }
    val reviewIndex = reviewState.index(reviewQueue)
    val reviewPoint = if (panel == "hifzReview") reviewQueue.getOrNull(reviewIndex) else null
    /**
     * The verse asked: covered on its page with what follows it until «أظهر», also behind the sheet, where the page
     * shows at its sides or as the sheet goes.
     */
    val reviewVerse = reviewPoint?.verse
    /** On the page: the verse asked, the page turned to it and the card over it. */
    val reviewTarget = reviewVerse?.takeIf { reviewOnPage }
    /**
     * The verse last asked on the page: when the review shows what was answered, the page stays where it was, that
     * verse above the card, rather than jump back to its top.
     */
    var reviewLastVerse by remember { mutableStateOf<QuranVerseReference?>(null) }
    LaunchedEffect(reviewTarget, panel) {
        if (panel != "hifzReview") reviewLastVerse = null else if (reviewTarget != null) reviewLastVerse = reviewTarget
    }
    val reviewRevealed = reviewState.revealed(reviewPoint)
    /** «اليوم بعد المغرب»: when a fresh point answered now is asked again, said on the review's answers, for each card. */
    val freshAgain by produceState("في تذكير لاحق", hifzSettings, today, reviewPoint?.id, reviewRevealed) {
        // Again every minute while shown: a prayer that begins, or a reminder now less than 45 minutes off, changes it.
        while (true) {
            value = withContext(Dispatchers.IO) { HifzReminder.freshAgainLabel(context, point = reviewPoint) }
            delay(60_000L)
        }
    }
    val reviewPages = remember(catalog, reviewVerse) { reviewVerse?.let { catalog?.pagesForVerse(it.surah, it.ayah) }.orEmpty() }
    /** The verses of the points still to come in this review: covered wherever they are, so none is seen before it is asked. */
    val reviewUpcoming = remember(panel, reviewQueue, reviewIndex, reviewVerse, hifzPoints, today, hifzNow) {
        if (panel != "hifzReview") emptySet() else
            (reviewQueue.drop(reviewIndex + 1).map { it.verse } + hifzPoints.filter { it.isDue(today, hifzNow, learning.seen, learning.onDay(today).exposed) }.map { it.verse }).toSet() - setOfNotNull(reviewVerse)
    }
    val reviewStillDue = eligibleDueIds.size
    /** Days until the next spaced check, one seen today waiting for tomorrow; a fresh point's next question is [nextFreshLabel]. */
    val reviewNextCheckIn = remember(hifzPoints, today, learning.exposed) {
        hifzPoints.filter { it.fresh == 0 }.minOfOrNull {
            if (it.verse in learning.onDay(today).exposed && it.dueDay <= today) today + 1 else it.dueDay
        }?.minus(today)?.coerceAtLeast(0L)
    }
    /** The next fresh point's question, at the end of a review, when it comes before any spaced check. */
    val reviewNextFresh = nextFreshLabel?.takeIf { nextFreshAt != null && (reviewNextCheckIn == null || epochDay(nextFreshAt) <= today + reviewNextCheckIn) }
    /**
     * When each fresh point of the review is asked next, from its own answer and its verse last seen (a drill pushes
     * it on), as the reminders will ask it: said on its drill and at the review's end.
     */
    val reviewNextFor by produceState(emptyMap<Long, String>(), panel, reviewIds, hifzPoints, learning.seen, hifzSettings, today) {
        if (panel != "hifzReview") { value = emptyMap(); return@produceState }
        val ids = reviewIds.toSet()
        value = withContext(Dispatchers.IO) {
            hifzPoints.filter { it.id in ids && it.fresh > 0 }.mapNotNull { point ->
                HifzReminder.nextAsk(context, point, learning.seen[point.verse])?.let { point.id to HifzReminder.slotLabel(it, today) }
            }.toMap()
        }
    }
    /** «غدًا بعد الفجر»: tomorrow's first reminder, where a spaced check and a once-a-day question come. */
    val tomorrowLabel by produceState("غدًا", hifzSettings, today) {
        value = withContext(Dispatchers.IO) { HifzReminder.dayFirstLabel(context, today + 1) } ?: "غدًا"
    }
    /** «الأحد بعد الفجر»: the first reminder of the next spaced check's day, said as the next question's time. */
    val nextCheckLabel by produceState<String?>(null, hifzSettings, today, reviewNextCheckIn) {
        val days = reviewNextCheckIn
        value = if (days == null || days <= 0L) null else withContext(Dispatchers.IO) { HifzReminder.dayFirstLabel(context, today + days) }
    }
    /**
     * The day's answers at the end of a review: the first answer of each point today, as the notebook counts them, so
     * that a fresh point asked at several reminders counts once.
     */
    val reviewDayAnswers = remember(hifzPoints, today) {
        val firsts = hifzPoints.mapNotNull { point -> point.checks.filter { it.day == today }.minByOrNull { it.at } }
        HifzTally(
            held = firsts.count { it.grade == HifzGrade.Held },
            hesitated = firsts.count { it.grade == HifzGrade.Hesitated },
            again = firsts.count { it.grade == HifzGrade.Again },
            heldNow = hifzPoints.count { it.mastered && it.masteredAt?.let { at -> epochDay(at) == today } == true },
        )
    }
    /** The page the reader was on when the review opened: the review on the page turns back to it at the end. */
    var reviewFromPage by rememberSaveable { mutableStateOf<Int?>(null) }
    /** A fix opened over the review or the notebook, which stay where they were. */
    var stackedFix by rememberSaveable { mutableStateOf<Long?>(null) }
    /** A point whose question is due, opened from the verse card: the question is offered before its correction. */
    var spoilPointId by rememberSaveable { mutableStateOf<Long?>(null) }
    /** A point corrected from its fix, over it: «تعديل الموضع». */
    var editingPoint by rememberSaveable { mutableStateOf<Long?>(null) }
    /** A change made over the notebook, said at its top with its undo, since a snackbar would be under it. */
    var hifzNotice by remember { mutableStateOf<HifzNotice?>(null) }
    /** The audio playing was started by the review card in view: it stops when the card is left. */
    var reviewAudio by rememberSaveable { mutableStateOf(false) }
    /** The review played something, so the listener's own place is put back when it ends. */
    var reviewPlayed by rememberSaveable { mutableStateOf(false) }
    /** The listener's place when the review opened, «reciter|surah|position|repeat|playing»; see [keepPlaceForReview]. */
    var reviewReturn by rememberSaveable { mutableStateOf<String?>(null) }
    /**
     * The review asks to recite aloud: a recitation under way pauses, and its place is kept, so that whatever the
     * review plays, the listener finds it again when the review ends. A second review keeps the first place.
     */
    fun keepPlaceForReview() {
        val now = QuranAudioController.state.value
        val surah = now.surah ?: return
        val sounding = now.playing || now.loading || now.gapAfter != null
        if (now.playlist != null || now.finishedPlaylist != null || reviewPlayed) {
            // A drill or a hifz listening, under way or just ended, or what the review itself played, is no place to
            // come back to: it only pauses.
            if (sounding && !reviewAudio) QuranAudioController.pause(context)
            return
        }
        if (reviewReturn != null) return
        reviewReturn = listOf(now.reciterId, surah, now.positionMs, now.repeat?.encode(now.repeatRound).orEmpty(), if (now.playing) 1 else 0).joinToString("|")
        if (sounding) QuranAudioController.pause(context)
    }
    var handledHifzRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(catalog, panel, stackedFix) {
        // The look-alikes are needed only by «المراجعة»; building them reads every verse once.
        val index = catalog ?: return@LaunchedEffect
        if (lookAlikes == null && (panel?.startsWith("hifz") == true || stackedFix != null)) {
            lookAlikes = withContext(Dispatchers.Default) { HifzLookAlikesCache.get(index) }
        }
    }
    /** Hosary's verse timings: «مراجعة» shows a verse opened whole for as long as he recites it. */
    var hosaryTimings by remember { mutableStateOf<List<QuranSurahRecording>?>(null) }
    LaunchedEffect(reviewMode, catalog) {
        // Read once the index is: like it, after the page in view.
        if (!reviewMode || catalog == null || hosaryTimings != null) return@LaunchedEffect
        hosaryTimings = try {
            withContext(Dispatchers.IO) { QuranRecitationTimings.load(context, QuranAudioController.reciter(QuranAudioController.DEFAULT_RECITER_ID)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    /** How long Hosary recites [verse]; from its letters until his timings are read. */
    fun hosaryMs(verse: QuranVerseReference): Long = hosaryTimings?.getOrNull(verse.surah - 1)?.timings?.getOrNull(verse.ayah - 1)
        ?.takeIf { it.ayah == verse.ayah }?.let { it.endMs - it.startMs }
        ?: ((catalog?.verse(verse)?.let { v -> hifzWords(v.text).keys.sumOf { it.length } } ?: 40) * QuranMsPerLetter)
    /** «مراجعة»: the verse opened, covered again; its card stays, so that «أخطأت» or an answer is no race. */
    var recoveredDraft by remember { mutableStateOf<String?>(null) }
    val recovered = remember(recoveredDraft) { decodeVerse(recoveredDraft) }
    // «مراجعة»: a verse shown whole is covered again once Hosary would have recited it.
    LaunchedEffect(selection, reviewMode, hosaryTimings) {
        recoveredDraft = null
        val verse = selection ?: return@LaunchedEffect
        if (!reviewMode) return@LaunchedEffect
        delay(hosaryMs(verse).coerceAtLeast(QuranReviewMinMs))
        if (selectionDraft == encodeVerse(verse)) recoveredDraft = encodeVerse(verse)
    }
    /**
     * Set below, once the review's audio helpers exist: a reminder tapped during a review ends it first, turning back
     * to the reader's page unless another review takes over at once.
     */
    val endReviewNow = remember { arrayOfNulls<(backToPage: Boolean) -> Unit>(1) }
    LaunchedEffect(hifzRequest, catalog, highlights, media, detailsFailed) {
        val (action, sequence) = hifzRequest ?: return@LaunchedEffect
        if (catalog == null || sequence == handledHifzRequest) return@LaunchedEffect
        // The review waits for what lets it show on the page, rather than opening in a sheet and moving a moment later.
        if (action == "hifz_review" && (media == null || (highlights == null && !detailsFailed))) return@LaunchedEffect
        handledHifzRequest = sequence
        // A reminder tapped while the review is open: the review ends as by «إنهاء», and nothing stays over the new panel.
        // A new review takes the page over without turning back in between, and still comes back to the page the
        // reader left for the first one.
        val from = reviewFromPage ?: pager.currentPage
        val now = System.currentTimeMillis()
        val day = epochDay(now)
        val stored = HifzLearning.read(context, day)
        // The review a reminder opens asks what the reminder asked: a slip set for other times waits for its own, or for
        // «تابع مراجعات اليوم».
        val posted = if (action == "hifz_review") HifzReminder.postedIds(context) else emptySet()
        val due = if (action == "hifz_review") HifzRepository.points.value
            .askable(day, now, stored.seen, stored.exposed)
            .let { all -> all.filter { it.id in posted }.ifEmpty { all } }
            .reviewBatch(day, now, HifzRoundSize).map { it.id } else emptyList()
        if (panel == "hifzReview") endReviewNow[0]?.invoke(due.isEmpty())
        stackedFix = null
        spoilPointId = null
        editingPoint = null
        when (action) {
            "hifz_review" -> {
                HifzReminder.clearReminder(context)
                reviewStartedDay = todayEpochDay()
                reviewSession++
                reviewIds = due
                panel = if (reviewIds.isEmpty()) "hifz" else "hifzReview"
                if (reviewIds.isNotEmpty()) {
                    keepPlaceForReview()
                    reviewFromPage = from
                } else {
                    // Its questions were answered since, or their verses looked at: the hub says so, and when the next comes.
                    val points = HifzRepository.points.value
                    val freshAt = points.filter { it.fresh > 0 }.minOfOrNull { it.freshAskAt(stored.seen[it.verse]) }?.takeIf { it > now }
                    val checkDay = points.filter { it.fresh == 0 }.minOfOrNull { if (it.verse in stored.exposed && it.dueDay <= day) day + 1 else it.dueDay }
                    val next = when {
                        // Each slip at the reminder that will ask it, at its own times.
                        freshAt != null && (checkDay == null || epochDay(freshAt) <= checkDay) -> withContext(Dispatchers.IO) {
                            points.filter { it.fresh > 0 }.mapNotNull { HifzReminder.nextAsk(context, it, stored.seen[it.verse], now) }
                                .minByOrNull { it.at }?.let { HifzReminder.slotLabel(it, day) } ?: HifzReminder.dayAndClock(freshAt, day)
                        }
                        checkDay != null -> hifzWhen(checkDay - day)
                        else -> null
                    }
                    if (points.isNotEmpty()) hifzNotice = HifzNotice(
                        "لا سؤال الآن: أجبت عمّا سأل عنه التذكير، أو رأيت آيته منذ قليل." + (next?.let { " السؤال القادم $it." } ?: ""),
                        undo = null,
                    )
                }
            }
            "hifz_record" -> panel = "hifz:record"
            else -> panel = "hifz"
        }
    }
    LaunchedEffect(media, pagesReady) {
        // The first visit fetches the pages by itself, unless that would use mobile data.
        val pagesPack = media?.pages ?: return@LaunchedEffect
        if (!pagesReady && !pagesRequested && media.state(pagesPack).status == QuranPackStatus.Missing &&
            !withContext(Dispatchers.IO) { media.isMetered() }) {
            pagesRequested = true
            media.fetch(listOf(pagesPack), allowMetered = false)
        }
    }
    LaunchedEffect(expanded, focus) { onHideNavigation(expanded || focus) }

    // The recording of the recited chapter, for the dock's verse-by-verse strip and the player's bubble.
    var track by remember { mutableStateOf<QuranSurahRecording?>(null) }
    LaunchedEffect(playback.reciterId, playback.surah) {
        val surah = playback.surah
        track = if (surah == null) null else withContext(Dispatchers.IO) {
            runCatching { QuranRecitationTimings.load(context, reciter)[surah - 1] }.getOrNull()
        }
    }

    // The verse at the top of the page in view, for the header, and the first verse that begins on it.
    val pageTop = remember(catalog, page.number) { catalog?.versesOnPage(page.number)?.firstOrNull { it.ayah > 0 } }
    val pageStart = remember(catalog, page.number) {
        catalog?.versesOnPage(page.number)?.let { verses ->
            (verses.firstOrNull { it.ayah > 0 && it.pages.first() == page.number } ?: verses.firstOrNull { it.ayah > 0 })?.reference
        }
    }
    val sessionVerse by rememberUpdatedState(
        playback.surah?.let { s -> (playback.ayah ?: playback.gapAfter)?.takeIf { it > 0 }?.let { QuranVerseReference(s, it) } }
    )
    val sessionOnPage by rememberUpdatedState(
        sessionVerse?.let { v -> catalog?.pagesForVerse(v.surah, v.ayah)?.contains(page.number) } == true
    )
    val hasSession by rememberUpdatedState(playback.surah != null)
    // A recitation that ended elsewhere (the notification, a headset) closes the player with it.
    LaunchedEffect(hasSession) { if (!hasSession) expanded = false }
    // The dock comes with a recitation, tools shown or not: there while it plays, starts or downloads, while a finger
    // is on the dock or its expanded player is open, and a few seconds after, so that it can go on. A place merely
    // saved, from this visit or an earlier one, leaves the page alone: the bar's «استمع» takes it up again.
    var dockLingers by remember { mutableStateOf(false) }
    var dockHeld by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Watched here, not read by the reader: a finger on the dock must not recompose it.
        snapshotFlow { playback.playing || playback.loading || expanded || dockHeld }.collectLatest { inUse ->
            if (inUse) dockLingers = true
            else { delay(8_000L); dockLingers = false }
        }
    }
    val playAction = quranPlayAction(
        playing = playback.playing,
        hasSession = hasSession && playback.error == null,
        selection = selection?.takeUnless { hasSession && !playback.playing && it == sessionVerse },
        sessionVerseOnPage = sessionOnPage,
        navigatedSincePause = navigatedSincePause,
        pageStart = pageStart,
    )

    // The page collector outlives recompositions; it reads the index as it is now.
    val currentCatalog by rememberUpdatedState(catalog)
    val currentPointsByVerse by rememberUpdatedState(pointsByVerse)
    val currentDueIds by rememberUpdatedState(eligibleDueIds)
    /** Due-page banners closed by the reader: not shown again for that point while the reader stays open. */
    var dueBannerClosed by remember { mutableStateOf(emptySet<Long>()) }
    val currentPagesReady by rememberUpdatedState(pagesReady)
    LaunchedEffect(pager) {
        var first = true
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect {
            // The review turns to its points' pages, and the reader may look around them: neither is where reading
            // stands, nor the page to open on next time. Its end turns back to the reader's page, which is kept again.
            if (panel == "hifzReview") { first = false; return@collect }
            prefs.edit().putInt("last_page", it + 1).apply()
            if (first) { first = false; return@collect }
            if (autoTurned == it) return@collect
            // The reader chose this page: it is where reading stands, and play now starts here.
            library.read(it + 1)
            val index = currentCatalog
            // A page turned to is read: its points wait before they are asked. Not under the hidden verses' covers. A
            // page holding a question due now is read only once it stayed in view, as a restored page is: its banner
            // offers the question first.
            val holdsDue = index != null && index.versesOnPage(it + 1).any { v ->
                v.ayah > 0 && currentPointsByVerse[v.reference].orEmpty().any { p -> p.id in currentDueIds && p.id !in dueBannerClosed }
            }
            if (index != null && panel == null && !reviewMode && currentPagesReady && !holdsDue) {
                updateLearning { learned -> learned.expose(index.versesOnPage(it + 1).filter { v -> v.ayah > 0 }.map { v -> v.reference }.toSet()) }
            }
            libraryRevision++
            if (!QuranAudioController.state.value.playing) navigatedSincePause = true
        }
    }
    LaunchedEffect(playback.playing) { if (playback.playing) navigatedSincePause = false }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    LaunchedEffect(pager.currentPage) {
        // Keyed on the page alone: a result tapped just now is set before the pager has moved to it. The review's own
        // page turns keep it, for the page it turns back to at its end.
        if (panel == "hifzReview") return@LaunchedEffect
        val hit = found ?: return@LaunchedEffect
        val index = catalog ?: return@LaunchedEffect
        val shownOn = hit.verses.flatMap { index.pagesForVerse(it.surah, it.ayah) } + hit.page
        if (pager.currentPage + 1 !in shownOn) foundDraft = null
    }
    LaunchedEffect(dragging) {
        if (dragging) {
            followAudio = false
            autoTurned = null
            selectionDraft = null
        }
    }
    LaunchedEffect(playback.surah, playback.ayah, playback.gapAfter, playback.loading, followAudio, catalog) {
        if (playback.surah == null) lastFollowedSurah = null
        // Following a recitation needs the verse index; it starts as soon as that is read.
        val index = catalog ?: return@LaunchedEffect
        // The review keeps its point's page: a recitation resumed meanwhile, from the notification or a headset, plays
        // without turning it.
        if (followAudio && !playback.loading && playback.playing && panel != "hifzReview") {
            val surah = playback.surah
            val ayah = playback.ayah ?: playback.gapAfter
            val versePages = if (surah != null && ayah != null) index.pagesForVerse(surah, ayah)
                else if (surah != lastFollowedSurah && playback.positionMs < playback.introEndMs)
                    index.surahs.firstOrNull { it.number == surah }?.let { listOf(it.page) }.orEmpty()
                else emptyList()
            if (versePages.isNotEmpty() && pager.currentPage + 1 !in versePages) {
                autoTurned = versePages.first() - 1
                pager.scrollToPage(versePages.first() - 1)
            }
            lastFollowedSurah = surah
        }
    }
    // The screen stays on while reading: until 10 minutes after the last touch, or while a recitation plays.
    val view = LocalView.current
    LaunchedEffect(keepAwake, playback.playing) {
        if (!keepAwake) { view.keepScreenOn = false; return@LaunchedEffect }
        view.keepScreenOn = true
        if (!playback.playing) snapshotFlow { lastTouch.longValue }.collectLatest { touched ->
            view.keepScreenOn = true
            delay((touched + KeepAwakeAfterTouchMs - System.currentTimeMillis()).coerceAtLeast(0L))
            view.keepScreenOn = false
        }
    }
    DisposableEffect(view) { onDispose { view.keepScreenOn = false } }
    // Night reading only dims this screen; the page stays white with its tajweed colours.
    val window = (activityContext as? Activity)?.window
    DisposableEffect(window, nightOn, nightLevel) {
        window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = if (nightOn) nightLevel.coerceIn(0.05f, 1f) else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        onDispose {
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }

    fun clearTransient() {
        selectionDraft = null
        rangeFromDraft = null
        rangeToDraft = null
    }
    fun userNavigated() {
        followAudio = false
        autoTurned = null
        if (!QuranAudioController.state.value.playing) navigatedSincePause = true
    }
    fun goToPage(number: Int, hit: QuranSearchHit? = null) {
        panel = null
        clearTransient()
        userNavigated()
        foundDraft = hit?.encode()
        val target = (number - 1).coerceIn(0, pages.lastIndex)
        scope.launch { pager.scrollToPage(target) }
    }
    fun showVerse(verse: QuranVerseReference) {
        val target = catalog?.pagesForVerse(verse.surah, verse.ayah)?.firstOrNull() ?: return
        goToPage(target)
        selectionDraft = encodeVerse(verse)
    }
    fun openResult(position: Int) {
        val result = search.visible.getOrNull(position) ?: return
        search.current = position
        recentSearches = recentSearches.withRecentSearch(search.query).also { writeRecentSearches(prefs, it) }
        goToPage(result.page, result.hit)
    }
    fun startFrom(verse: QuranVerseReference) {
        starter.start(playback.reciterId, verse.surah..verse.surah) {
            QuranAudioController.play(context, playback.reciterId, verse.surah, verse.ayah.takeIf { it > 1 })
        }
        followAudio = true
        navigatedSincePause = false
        selectionDraft = null
    }
    fun resumeSession() {
        val surah = playback.surah ?: return
        val repeat = playback.repeat
        val surahs = repeat?.let { it.from.surah..it.to.surah } ?: surah..surah
        if (playback.error != null && repeat == null) {
            starter.start(playback.reciterId, surahs) { QuranAudioController.play(context, playback.reciterId, surah, playback.ayah) }
        } else starter.start(playback.reciterId, surahs) { QuranAudioController.resume(context) }
        followAudio = true
        navigatedSincePause = false
        selectionDraft = null
        val target = sessionVerse?.let { catalog?.pagesForVerse(it.surah, it.ayah)?.firstOrNull() }
        if (target != null && target != page.number) scope.launch { autoTurned = target - 1; pager.scrollToPage(target - 1) }
    }
    fun pressPlay() {
        when (val action = playAction) {
            QuranPlayAction.Pause -> QuranAudioController.pause(context)
            QuranPlayAction.Resume -> resumeSession()
            is QuranPlayAction.PlayFrom -> startFrom(action.verse)
            QuranPlayAction.None -> Unit
        }
    }
    fun hideHint(key: String) {
        prefs.edit().putBoolean(key, true).apply()
        when (key) {
            "hint_tap_seen" -> tapHint = false
            "hint_strip_seen" -> stripHint = false
            "hint_focus_seen" -> focusHint = false
            "hint_review_eraser_seen" -> reviewEraserHint = false
            "hint_fine_seek_seen" -> fineSeekHint = false
        }
    }
    /** «وضع المراجعة», from the eye in the bar or from the «المراجعة» sheet. */
    fun setVersesHidden(on: Boolean) {
        reviewMode = on
        prefs.edit().putBoolean("review_mode", on).apply()
        selectionDraft = null
    }
    fun stopWithUndo() {
        val before = QuranAudioController.state.value
        val surah = before.surah ?: return
        starter.cancel()
        QuranAudioController.stop(context)
        expanded = false
        scope.launch {
            val result = snackbar.showSnackbar("تم إيقاف الاستماع", actionLabel = "تراجع", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) {
                // A drill comes back as itself, not as its current passage alone.
                val list = before.playlist
                val playing = before.playing || before.loading
                if (list != null) QuranAudioController.playPlaylist(context, list, before.reciterId, before.playlistIndex, before.playlistRound, autoPlay = playing)
                else QuranAudioController.playAt(context, before.reciterId, surah, before.positionMs, before.repeat, before.repeatRound, autoPlay = playing)
            }
        }
    }
    /** «أُلغي التكرار» or «أُوقف التدريب», with an undo that puts the passage or the drill back as it was. */
    fun offerUndoLeaving(before: com.tunisianprayertimes.quran.audio.QuranPlaybackState) {
        val range = before.repeat ?: return
        val surah = before.surah ?: return
        val drill = before.playlist
        scope.launch {
            val ended = when (drill?.title) {
                null -> "أُلغي التكرار"
                HifzDrillTitle -> "أُوقف التدريب"
                HifzListenOneTitle -> "أُوقف الاستماع إلى الموضع"
                HifzCueTitle -> "أُوقف الاستماع"
                else -> "أُوقف الاستماع إلى المواضع"
            }
            val result = snackbar.showSnackbar(ended, actionLabel = "تراجع", duration = SnackbarDuration.Short)
            if (result != SnackbarResult.ActionPerformed) return@launch
            if (drill != null) QuranAudioController.playPlaylist(context, drill, before.reciterId, before.playlistIndex, before.playlistRound, autoPlay = before.playing || before.loading)
            else QuranAudioController.playAt(context, before.reciterId, surah, before.positionMs, range, before.repeatRound, autoPlay = before.playing)
        }
    }
    fun toggleRepeat() {
        val before = QuranAudioController.state.value
        if (before.repeat != null) {
            // A drill is separate passages: ending it silences it, rather than reciting on from wherever it stood.
            // Paused first, so that its silence cannot end into the next verse in between.
            if (before.playlist != null) QuranAudioController.pause(context)
            QuranAudioController.cancelRepeat(context)
            offerUndoLeaving(before)
        } else QuranAudioController.repeatCurrentVerse(context)
        followAudio = true
    }
    fun beginRange(from: QuranVerseReference) {
        selectionDraft = null
        rangeFromDraft = encodeVerse(from)
        rangeToDraft = null
        rangeTimes = listening.lastRepeatTimes ?: 0
        expanded = false
    }
    fun startRange() {
        val from = rangeFrom ?: return
        val to = rangeTo ?: from
        val (a, b) = if (to < from) to to from else from to to
        val times = rangeTimes.takeIf { it > 0 }
        val range = QuranRepeatRange(a, b, times)
        QuranAudioController.updateListening(context) { it.copy(lastRepeatTimes = times) }
        starter.start(playback.reciterId, a.surah..b.surah) { QuranAudioController.repeat(context, range) }
        followAudio = true
        navigatedSincePause = false
        rangeFromDraft = null
        rangeToDraft = null
    }
    fun setSleep(kind: QuranSleep.Kind?, minutes: Int) {
        val current = QuranAudioController.state.value
        val surah = current.surah
        val verse = (current.ayah ?: current.gapAfter)?.let { a -> surah?.let { QuranVerseReference(it, a) } }
        val sleep = when (kind) {
            null -> null
            QuranSleep.Kind.Verse -> verse?.let { QuranSleep(kind, it) }
            QuranSleep.Kind.Page -> verse?.let { v ->
                val recitedPage = catalog?.pagesForVerse(v.surah, v.ayah)?.firstOrNull()
                val last = recitedPage?.let { p -> catalog?.versesOnPage(p)?.lastOrNull { it.ayah > 0 && it.surah == v.surah } }
                QuranSleep(kind, last?.reference ?: v)
            }
            QuranSleep.Kind.Surah -> surah?.let { s -> catalog?.surahs?.getOrNull(s - 1)?.verseCount?.let { QuranSleep(kind, QuranVerseReference(s, it)) } }
            QuranSleep.Kind.Minutes -> QuranSleep(kind, untilElapsedMs = QuranAudioController.elapsedNow() + minutes * 60_000L)
        }
        QuranAudioController.updateListening(context) { it.copy(sleep = sleep) }
    }
    fun copyOrShare(verse: QuranVerseReference, share: Boolean) {
        val text = catalog?.let { index -> index.verse(verse)?.let { quranShareText(surahName(verse.surah), listOf(it), index.waqf) } }
            ?.takeIf { it.isNotEmpty() } ?: return
        if (share) {
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            activityContext.startActivity(Intent.createChooser(intent, "مشاركة الآية"))
        } else {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("آية", text)))
                snackbar.showSnackbar("نُسخت الآية", duration = SnackbarDuration.Short)
            }
        }
    }

    /** [follow]: the pages turn with it; not behind a sheet that stays open. */
    fun playList(list: QuranPlaylist?, follow: Boolean = true) {
        list ?: return
        // Only the chapters its passages are in: a look-alike far away does not fetch every chapter in between.
        val surahs = list.items.flatMap { it.from.surah..it.to.surah }.toSortedSet()
        starter.start(playback.reciterId, surahs) { QuranAudioController.playPlaylist(context, list) }
        followAudio = follow
        navigatedSincePause = false
    }
    fun saveHifz(block: () -> Unit) {
        // One at a time and in order: an undo never lands before the change it takes back.
        scope.launch(HifzWrites) {
            block()
            HifzReminder.refresh(context)
        }
    }
    /** [home]: recorded from the notebook, which opens again with the record said at its top. */
    fun recordSlip(
        verse: QuranVerseReference, kind: HifzKind, other: QuranVerseReference?, slipWords: Set<Int>, bothWays: Boolean, plan: HifzPlan,
        home: Boolean = false,
    ) {
        panel = if (home) "hifz" else null
        selectionDraft = null
        val known = HifzRepository.points.value
        // The same slip: same verse and kind, and for a look-alike the same other verse; another look-alike is its own.
        val before = known.sameSlip(verse, kind, other)
        // «في الاتجاهين»: the look-alike is a slip too, checked on its own, unless it already is one.
        val mirror = other?.takeIf { bothWays && kind.lookAlike && known.sameSlip(it, kind, verse) == null }
        scope.launch {
            val recorded = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val saved = HifzRepository.record(context, verse, kind, other, slipWords, bothWays, now, plan)
                // As saved: a point asked once a day, already asked today, starts tomorrow.
                val first = HifzReminder.nextAsk(context, saved, null, now)
                val mirrored = mirror?.let { HifzRepository.record(context, it, kind, verse, emptySet(), true, now + 1, plan) }
                HifzReminder.refresh(context)
                Triple(saved, first, mirrored)
            }
            val (saved, first, mirrored) = recorded
            // The verse by its number where its page is in view; from the notebook, or another surah, with its surah.
            fun named(of: QuranVerseReference) = if (home || of.surah != verse.surah) "الآية ${of.ayah} من ${surahName(of.surah)}" else "الآية ${of.ayah}"
            // Its look-alike recorded with it: named with its surah only when that is another.
            val mirrorName = mirrored?.verse?.let { m -> "الآية ${m.ayah}" + if (m.surah != verse.surah) " من ${surahName(m.surah)}" else "" }
            val today = todayEpochDay()
            // Two sentences, as the plan said it: what was recorded (again, it says so), then when it is asked first. From
            // the notebook, the hub under the notice says today's time already.
            val message = when {
                before != null -> "سُجّلت ${named(verse)} مرة أخرى، ${hifzSlipsLabel(saved.slips)}" + (mirrorName?.let { "، وسُجّلت معها $it" } ?: "") + "."
                mirrorName != null -> "سُجّلت ${named(verse)} و$mirrorName في دفتر المواضع."
                else -> "سُجّلت ${named(verse)} في دفتر المواضع."
            } + (first?.takeIf { !home || maxOf(it.day, today) != today }?.let {
                " نذكّرك ${if (mirrored != null) "بهما" else "بها"} ${HifzReminder.slotLabel(it, today)}."
            } ?: "")
            val undo = {
                saveHifz {
                    HifzRepository.restore(context, before, removeId = saved.id)
                    mirrored?.let { HifzRepository.delete(context, it.id) }
                }
            }
            if (home) {
                hifzNotice = HifzNotice(message, undo = { undo(); hifzNotice = null }, again = { panel = "hifz:record" }, correction = { stackedFix = saved.id })
                return@launch
            }
            val result = snackbar.showSnackbar(QuranSnackbar(
                message = message,
                // The undo stays while the correction is open. Said as what it undoes: a bare «تراجع» reads «تُراجِع»
                // in a feature named «المراجعة».
                actionLabel = "إلغاء الحفظ",
                secondLabel = "التصحيح",
                onSecond = { panel = "hifzFix:${saved.id}" },
                dismissOnSecond = false,
            ))
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }
    fun openFix(point: HifzPoint) { panel = "hifzFix:${point.id}" }
    /**
     * The day's review: the questions due, in mushaf order, linked points together; [first], chosen in the notebook or
     * on the page, asked first. The day's answers add up.
     */
    fun startReview(first: HifzPoint? = null) {
        HifzReminder.clearReminder(context)
        keepReviewDay()
        val now = System.currentTimeMillis()
        val day = epochDay(now)
        val stored = learning.onDay(day)
        val askable = HifzRepository.points.value.askable(day, now, stored.seen, stored.exposed)
        // A point whose verse the first one shows waits for the next reminder: its answer would just have been seen.
        val batch = askable.reviewBatch(day, now, HifzRoundSize, first)
        reviewStartedDay = todayEpochDay()
        reviewSession++
        reviewIds = batch.map { it.id }
        panel = "hifzReview"
        keepPlaceForReview()
        // «تابع المراجعة» keeps the page the first review left.
        if (reviewFromPage == null) reviewFromPage = pager.currentPage
    }
    /** Plays [list] for the review card in view: it stops when the card is left, and the page does not follow it behind the sheet. */
    fun reviewPlay(list: QuranPlaylist?) {
        list ?: return
        // Again here: a review opened as the app starts may come before the saved place has been read.
        keepPlaceForReview()
        reviewAudio = true
        reviewPlayed = true
        playList(list, follow = false)
    }
    /** The review card is left: the audio it started stops, and a start still waiting for its download is dropped. */
    fun leaveReviewCard() {
        if (!reviewAudio) return
        reviewAudio = false
        starter.cancel()
        val now = QuranAudioController.state.value
        if (now.playlist != null && (now.playing || now.loading || now.gapAfter != null)) QuranAudioController.pause(context)
    }
    /**
     * The review ends. What it played gives way to the listener's own place, paused where it was, or stops when there
     * was none; a recitation the review paused can go on from the snackbar. The page turns back to where the reader
     * was, unless [backToPage] is false because the end opens a verse elsewhere.
     */
    fun endReview(backToPage: Boolean = true) {
        leaveReviewCard()
        keepReviewDay()
        panel = null
        reviewIds = emptyList()
        stackedFix = null
        val back = reviewFromPage
        reviewFromPage = null
        if (backToPage && back != null && back != pager.currentPage) scope.launch {
            autoTurned = back
            pager.scrollToPage(back)
        }
        val saved = reviewReturn?.split('|')?.takeIf { it.size == 5 }
        val played = reviewPlayed
        reviewReturn = null
        reviewPlayed = false
        val surah = saved?.get(1)?.toIntOrNull()
        if (saved == null || surah == null) {
            if (played) {
                starter.cancel()
                QuranAudioController.stop(context)
            }
            return
        }
        if (played) {
            val repeat = decodeQuranRepeat(saved[3].ifEmpty { null })
            QuranAudioController.playAt(context, saved[0], surah, saved[2].toLongOrNull() ?: 0L, repeat?.first, repeat?.second ?: 0, autoPlay = false)
        }
        if (saved[4] == "1") scope.launch {
            val result = snackbar.showSnackbar("توقّف استماعك إلى سورة ${surahName(surah)} أثناء المراجعة", actionLabel = "تابع", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                QuranAudioController.resume(context)
                followAudio = true
            }
        }
    }

    endReviewNow[0] = { back -> endReview(backToPage = back) }
    val reviewActions = object : HifzReviewActions {
        override fun exposed(verses: Set<QuranVerseReference>) { updateLearning { it.expose(verses) } }
        override fun seen(verses: Set<QuranVerseReference>) { updateLearning { it.expose(verses) } }
        override fun grade(point: HifzPoint, grade: HifzGrade) {
            val now = System.currentTimeMillis()
            saveHifz {
                val current = HifzRepository.points.value.firstOrNull { it.id == point.id } ?: point
                HifzRepository.update(context, current.graded(grade, todayEpochDay(), now))
            }
        }
        override fun undo(given: HifzGiven) = saveHifz {
            HifzRepository.points.value.firstOrNull { it.id == given.id }?.let { now ->
                HifzRepository.update(
                    context,
                    now.copy(
                        step = given.step, dueDay = given.dueDay, masteredAt = given.masteredAt, slips = given.slips, lastSlipAt = given.lastSlipAt,
                        checks = given.checks, fresh = given.fresh, pace = given.pace, autoRecalls = given.autoRecalls, times = given.times,
                    ),
                )
            }
        }
        override fun listenBefore(point: HifzPoint) {
            catalog?.let { reviewPlay(hifzCue(it, point)) }
        }
        override fun listen(point: HifzPoint) {
            catalog?.let { reviewPlay(hifzListen(it, listOf(point))) }
        }
        override fun drill(point: HifzPoint) {
            catalog?.let { reviewPlay(hifzDrill(it, point)) }
        }
        override fun fix(point: HifzPoint) { stackedFix = point.id }
        override fun leaveCard() = leaveReviewCard()
        override fun more() = startReview()
        override fun listenTo(ids: List<Long>) {
            // The checks are done: the review gives way to the reader, whose page follows the recitation, and the
            // listener's own place comes back once it ends.
            val index = catalog ?: return
            val points = HifzRepository.points.value.filter { it.id in ids }
            endReview(backToPage = false)
            playList(hifzListen(index, points))
        }
        override fun dismiss() = endReview()
    }
    LaunchedEffect(learning.exposed, learning.seen, reviewSession) {
        if (panel == "hifzReview") reviewState.syncExposed(learning.onDay(todayEpochDay()).exposed, reviewActions, learning.seen)
        else reviewState.seedExposed(learning.onDay(todayEpochDay()).exposed, learning.seen)
    }
    LaunchedEffect(today) {
        if (reviewStartedDay != today) {
            val wasReviewing = panel == "hifzReview"
            if (wasReviewing) endReview()
            reviewDayDraft = null
            reviewIds = emptyList()
            reviewSession++
            reviewStartedDay = today
            if (wasReviewing) {
                panel = "hifz"
                hifzNotice = HifzNotice("بدأ يوم جديد؛ جُدّدت قائمة المراجعة.", undo = null)
            }
        }
    }
    // A point's own audio heard (its listening, its drill, its cue), not a download request that might never start
    // playing; a recitation going on in the background is no look at a verse.
    LaunchedEffect(playback.playing, playback.surah, playback.ayah, panel, playback.playlist != null) {
        val heard = if (playback.playing && playback.playlist != null) playback.surah?.let { surah -> playback.ayah?.takeIf { it > 0 }?.let { QuranVerseReference(surah, it) } } else null
        if (heard != null) {
            if (panel == "hifzReview") reviewState.noteExposed(setOf(heard), reviewActions)
            else updateLearning { it.expose(setOf(heard)) }
        }
    }
    // A page stayed on, in front of the reader with no sheet over it, is read: the page restored as the reader opens, or
    // left in view as a sheet closes, counts only once it has been there a while.
    val overPage = panel != null || spoilPointId != null || stackedFix != null || editingPoint != null
    LaunchedEffect(pager.settledPage, overPage, reviewMode, pagesReady, catalog, inFront) {
        val index = catalog ?: return@LaunchedEffect
        if (overPage || reviewMode || !pagesReady || !inFront) return@LaunchedEffect
        delay(QuranPageSeenMs)
        updateLearning { it.expose(index.versesOnPage(pager.settledPage + 1).filter { v -> v.ayah > 0 }.map { v -> v.reference }.toSet()) }
    }
    // With the verses hidden, the one opened by its number is shown, once, as it opens.
    LaunchedEffect(selection, reviewMode) {
        val verse = selection
        if (reviewMode && panel == null && verse != null && verse != recovered) updateLearning { it.expose(setOf(verse)) }
    }
    // A comparison is visible even when the same verse remains covered in the background reader.
    LaunchedEffect(reviewPoint?.id, reviewRevealed, stackedFix, panel) {
        val correction = stackedFix?.let { id -> hifzPoints.firstOrNull { it.id == id } }
            ?: panel?.removePrefix("hifzFix:")?.toLongOrNull()?.let { id -> hifzPoints.firstOrNull { it.id == id } }
            ?: reviewPoint?.takeIf { reviewRevealed }
        // The answer revealed on the page, its card over it: the page shows what is not covered, and the card the
        // look-alike's words; the verses of a skip on the next page are seen only when heard.
        val onPage = reviewOnPage && correction != null && correction.id == reviewPoint?.id && stackedFix == null
        val pageVerses = if (!onPage) emptySet() else reviewPages.flatMap { catalog?.versesOnPage(it).orEmpty() }.map { it.reference }.toSet()
        val visible = buildSet {
            // What the correction shows, in its excerpts and its audio; the review orders its points by the same.
            correction?.answerVerses()?.forEach { verse ->
                if (!onPage || verse == correction.verse || (verse == correction.other && correction.kind.lookAlike) ||
                    (verse in pageVerses && verse !in reviewUpcoming)) add(verse)
            }
            // The question shows the end of the verse before it.
            reviewPoint?.takeIf { !reviewRevealed && it.verse.ayah > 1 }?.let { add(QuranVerseReference(it.verse.surah, it.verse.ayah - 1)) }
        }
        if (visible.isNotEmpty()) {
            if (panel == "hifzReview") reviewState.noteExposed(visible, reviewActions)
            else updateLearning { it.expose(visible) }
        }
    }
    // The card follows its point in the queue, wherever the review shows.
    LaunchedEffect(panel == "hifzReview", reviewSession, reviewPoint?.id, reviewState.index(reviewQueue)) {
        if (panel == "hifzReview") reviewState.follow(reviewQueue, reviewActions)
    }
    // On the page, each card turns to its point's page, where the verse is already covered: to the verse's first page,
    // where it begins, also when the reader had looked elsewhere or the card before was on the same verse. Not the
    // reader's own choice of page.
    LaunchedEffect(reviewSession, reviewPoint?.id, reviewTarget) {
        val target = reviewTarget ?: return@LaunchedEffect
        val first = catalog?.pagesForVerse(target.surah, target.ayah)?.firstOrNull() ?: return@LaunchedEffect
        if (pager.currentPage + 1 == first) return@LaunchedEffect
        autoTurned = first - 1
        pager.scrollToPage(first - 1)
    }
    LaunchedEffect(panel) {
        // A review closed some other way than its end, as when another panel replaces one brought back with the app:
        // its page to turn back to must not be the next review's.
        if (panel != "hifzReview") reviewFromPage = null
        // The notebook's note goes with it.
        if (panel != "hifz") hifzNotice = null
        // The question offered over a verse card goes with the card.
        if (panel != null) spoilPointId = null
    }
    LaunchedEffect(reviewOnPage) {
        // Nothing of the reader's own stays over the page the review uses.
        if (!reviewOnPage) return@LaunchedEffect
        expanded = false
        clearTransient()
    }

    /**
     * A question that can be asked now whose verse is on the page in view: said over the page, since reading the page
     * a while counts as seeing the answer, which moves the question on.
     */
    val duePagePoints = if (panel != null || reviewMode || catalog == null || spoilPointId != null || stackedFix != null || editingPoint != null) emptyList()
        else catalog.versesOnPage(page.number).asSequence().filter { it.ayah > 0 }.flatMap { pointsByVerse[it.reference].orEmpty().asSequence() }
            .filter { it.id in eligibleDueIds && it.id !in dueBannerClosed }.toList()
    val hifzDueAll = eligibleDueIds.size
    /** What the hifz sheets show of the recitation, since they cover the player: a hifz playlist, or anything playing. */
    val hifzAudio: HifzAudio? = playback.playlist.let { list ->
        when {
            starter.pendingPacks.isNotEmpty() -> HifzAudio("يبدأ الاستماع بعد تنزيل التلاوة", reciter.shortName, playing = false, waiting = true)
            playback.finishedPlaylist != null && !playback.playing -> playback.finishedPlaylist?.takeIf { it.title == HifzDrillTitle }?.let {
                HifzAudio(com.tunisianprayertimes.quran.audio.quranPlaylistEnded(it.title), it.labels.firstOrNull().orEmpty(), playing = false, stoppable = false, finished = true)
            }
            list != null -> HifzAudio(
                title = if (list.items.size > 1) "${list.title} · ${playback.playlistIndex + 1} من ${list.items.size}" else list.title,
                line = playback.gapAfter?.let { hifzGapLine(list, playback.playlistIndex, it) } ?: list.labels.getOrNull(playback.playlistIndex).orEmpty(),
                playing = playback.playing || playback.loading,
            )
            // The listener's own recitation, paused or not, while a service holds it: kept, never stopped from a sheet.
            playback.surah != null && !playback.restored && !playback.notDownloaded -> HifzAudio(
                title = "${surahName(playback.surah ?: 1)} · " + (playback.ayah?.let { "الآية $it" } ?: "التلاوة"),
                line = reciter.shortName, playing = playback.playing || playback.loading, stoppable = false,
            )
            else -> null
        }
    }
    // Resolve nullable audio before the composition-local content lambda captures it.
    val activeHifzAudio = hifzAudio?.let { audio -> if (audio.finished) null else audio }
    val reviewHifzAudio = hifzAudio?.let { audio -> if (reviewAudio || audio.playing) audio else null }
    val hifzAudioControls = remember(context, starter) {
        HifzAudioControls(
            pause = { QuranAudioController.pause(context) },
            resume = { QuranAudioController.resume(context) },
            stop = {
                if (starter.pendingPacks.isNotEmpty()) starter.cancel()
                else {
                    QuranAudioController.stop(context)
                    reviewAudio = false
                }
            },
        )
    }
    /** «أخطأت فيها مرة أخرى»: the slip counted again, and the point asked at the reminders until it is read right again. */
    fun slipAgain(point: HifzPoint) = saveHifz {
        val current = HifzRepository.points.value.firstOrNull { it.id == point.id } ?: return@saveHifz
        HifzRepository.record(context, current.verse, current.kind, current.other, emptySet(), current.bothWays)
    }
    val hifzScope = QuranReaderScope(
        context = context,
        save = ::saveHifz,
        play = { list, review -> if (review) reviewPlay(list) else playList(list) },
        playBehind = { list -> playList(list, follow = false) },
        edit = { point -> editingPoint = point.id },
        notice = { hifzNotice = it },
        settings = { hifzSettings },
        undo = { message, action ->
            scope.launch {
                if (snackbar.showSnackbar(message, actionLabel = "تراجع", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) action()
            }
        },
        audio = activeHifzAudio,
        audioControls = hifzAudioControls,
        slipAgain = ::slipAgain,
        recordOther = { verse ->
            val home = panel == "hifz"
            stackedFix = null
            panel = "hifzRecord:${verse.surah}:${verse.ayah}" + if (home) ":home" else ""
        },
        siblings = { point -> hifzPoints.filter { it.verse == point.verse && it.id != point.id } },
    )

    val playerActions = run {
        object : QuranPlayerActions {
            override fun play() = pressPlay()
            override fun previous() { QuranAudioController.previousVerse(context); followAudio = true }
            override fun next() { QuranAudioController.nextVerse(context); followAudio = true }
            override fun skipBy(deltaMs: Long) = QuranAudioController.skipBy(context, deltaMs)
            // The line held: to that second; the service keeps it inside a passage being repeated.
            override fun seekTo(positionMs: Long) {
                QuranAudioController.seekTo(context, positionMs)
                followAudio = true
            }
            override fun hold(deltaMs: Long?) { holdDelta = deltaMs }
            override fun seekVerse(ayah: Int) {
                // A verse outside a repeated passage ends the repetition rather than restarting its pass;
                // a saved place with no service only moves.
                val before = QuranAudioController.state.value
                val surah = before.surah ?: return
                val leaving = before.repeat?.contains(QuranVerseReference(surah, ayah)) == false
                if (QuranAudioController.serviceRunning) QuranAudioController.seekVerse(context, surah, ayah)
                else {
                    if (leaving) QuranAudioController.cancelRepeat(context)
                    track?.startOf(ayah)?.let { QuranAudioController.seekTo(context, it) }
                }
                if (leaving) offerUndoLeaving(before)
                followAudio = true
            }
            override fun stop() = stopWithUndo()
            override fun repeat() = toggleRepeat()
            override fun collapse() { expanded = false }
            override fun reciter() { panel = "reciter" }
            override fun pickRangeOnPage() {
                val current = QuranAudioController.state.value
                val from = current.surah?.let { s -> (current.ayah ?: current.gapAfter)?.let { QuranVerseReference(s, it) } } ?: pageStart
                if (from != null) beginRange(from)
            }
            override fun typedRange() {
                val current = QuranAudioController.state.value
                val from = current.repeat?.from ?: current.surah?.let { s -> (current.ayah ?: 1).let { QuranVerseReference(s, it) } } ?: pageStart
                typedRangeDraft = from?.let { "${it.surah}:${it.ayah}:${(current.repeat?.to ?: it).surah}:${(current.repeat?.to ?: it).ayah}" }
                panel = "typedRange"
            }
            override fun cancelRange() = toggleRepeat()
            override fun listening(change: (com.tunisianprayertimes.quran.audio.QuranListening) -> com.tunisianprayertimes.quran.audio.QuranListening) =
                QuranAudioController.updateListening(context, change)
            override fun sleep(kind: QuranSleep.Kind?, minutes: Int) = setSleep(kind, minutes)
        }
    }

    BackHandler(enabled = selection != null || rangeFrom != null || expanded || found != null || focusChosen) {
        when {
            selection != null -> selectionDraft = null
            rangeFrom != null -> { rangeFromDraft = null; rangeToDraft = null }
            expanded -> expanded = false
            found != null -> { foundDraft = null; search.current = -1 }
            focusChosen -> focusChosen = false
        }
    }
    // The review on the page stops with Back, as its sheet does: what was answered shows first, and a second Back leaves.
    BackHandler(enabled = reviewOnPage) { reviewState.requestEnd(reviewQueue, reviewActions) }

    CompositionLocalProvider(LocalQuranExposure provides { exposed ->
        if (panel == "hifzReview") reviewState.noteExposed(exposed, reviewActions)
        else updateLearning { it.expose(exposed) }
    }) {
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // Any touch keeps the screen awake for a while; nothing is consumed here.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                lastTouch.longValue = System.currentTimeMillis()
            }
        },
    ) {
        // The dock comes with a recitation (see dockLingers). The review on the page has its own audio strip in its card.
        val showDock = !reviewOnPage && (playback.playing || playback.loading || dockLingers || starter.pendingPacks.isNotEmpty())
        // Fitted whole, the page has the whole reader: the header, the dock, the app's bar and the cards come over it,
        // and showing or hiding them never moves it. Filling the width, it scrolls, and the header, the dock and the bar
        // take their room from it, for the end of the page to scroll into view above them.
        val headerOverlap = !pageFillsWidth && !focus
        val dockRoomPx: androidx.compose.ui.unit.Density.() -> Int = {
            (if (showDock && !expanded) QuranDockHeight.roundToPx() else 0) + bottomBar().roundToPx()
        }
        Box(Modifier.fillMaxSize().testTag("quran_reader")) {
            AnimatedVisibility(
                visible = !focus, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
                // Over the page, which is laid out after it; its height kept for when it is hidden.
                modifier = Modifier.zIndex(1f).onSizeChanged { if (it.height > 0) headerHeightPx = it.height },
            ) {
                // A touch on the bar between its buttons stays on the bar: it must not reach the page under it.
                Column(Modifier.pointerInput(Unit) {}) {
                    val stepperOn = found != null && search.visible.getOrNull(search.current)?.hit == found
                    if (stepperOn) QuranSearchHeader(
                        index = search.current, total = search.visible.size, query = search.query,
                        onOpen = ::openResult, onList = { panel = "search" },
                        onClose = { foundDraft = null; search.current = -1 },
                    ) else QuranHeader(
                        title = pageTop?.let { surahName(it.surah) } ?: page.surahNames.firstOrNull() ?: "القرآن الكريم",
                        subtitle = listOfNotNull(
                            quranPageLabel(page.number),
                            pageTop?.let { divisions?.thumnOf(it.reference)?.hizb }?.let { "الحزب $it" },
                        ).joinToString(" · "),
                        // Where the full one does not fit: the page's number alone, never a hizb cut off.
                        compactSubtitle = listOfNotNull(
                            quranShortPageLabel(page.number),
                            pageTop?.let { divisions?.thumnOf(it.reference)?.hizb }?.let { "الحزب $it" },
                        ).joinToString(" · "),
                        hifzDue = hifzDueAll,
                        reviewMode = reviewMode,
                        onReviewMode = { setVersesHidden(!reviewMode) },
                        pageBookmarked = remember(libraryRevision, page.number) { library.isPageBookmarked(page.number) },
                        keepAwake = keepAwake,
                        onLocation = { panel = "chapters" },
                        onBookmark = {
                            val saved = library.togglePage(page.number)
                            libraryRevision++
                            scope.launch { snackbar.showSnackbar(if (saved) "حُفظت الصفحة في العلامات" else "أُزيلت علامة الصفحة", duration = SnackbarDuration.Short) }
                        },
                        onSearch = { panel = "search" },
                        onKeepAwake = { keepAwake = !keepAwake; prefs.edit().putBoolean("keep_awake", keepAwake).apply() },
                        onNight = { panel = "night" },
                        onFocus = { focusChosen = true },
                        listen = playAction,
                        listenBusy = playback.loading || starter.pendingPacks.isNotEmpty(),
                        listenLabel = quranListenLabel(playAction, sessionVerse, pageStart, surahName),
                        onListen = ::pressPlay,
                        reciterShortName = reciter.shortName,
                        onReciter = { panel = "reciter" },
                        onDownloads = { if (media?.layout != null) panel = "downloads" },
                        onHints = {
                            prefs.edit().remove("hint_tap_seen").remove("hint_strip_seen").remove("hint_focus_seen").remove("hint_review_eraser_seen")
                                .remove("hint_fine_seek_seen").apply()
                            tapHint = true; stripHint = true; focusHint = true; reviewEraserHint = true; fineSeekHint = true
                        },
                        catalogReady = catalog != null,
                        markers = hifzSettings.markers,
                        onHifz = { if (catalog != null) panel = "hifz" },
                        onMarkers = {
                            hifzSettings = hifzSettings.copy(markers = !hifzSettings.markers)
                            HifzSettings.write(context, hifzSettings)
                        },
                    )
                    if (detailsFailing) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("تعذّر تجهيز البحث والتلاوة", Modifier.weight(1f), fontSize = 12.sp, color = TextMuted)
                            TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp), contentAlignment = Alignment.BottomCenter) {
                        // Search, recitation and verse selection are still being prepared behind the page.
                        if (!detailsFailing && (catalog == null || highlights == null)) {
                            LinearProgressIndicator(Modifier.fillMaxSize().testTag("quran_preparing"), color = GreenPrimary, trackColor = CardBorder)
                        }
                    }
                }
            }
            Box(
                Modifier.fillMaxSize().layout { measurable, constraints ->
                    val top = if (pageFillsWidth && !focus) headerHeightPx else 0
                    val bottom = if (pageFillsWidth) dockRoomPx() else 0
                    val placeable = measurable.measure(constraints.offset(vertical = -(top + bottom)))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, top) }
                },
            ) {
                // Read by the page alone: the card or panel growing must not recompose the whole reader.
                val bottomInset = { if (expanded) panelHeightPx else overlayHeightPx + cardAtFootPx }
                val topInset = { if (headerOverlap) headerHeightPx else 0 }
                // The most that can come over the page's head and foot: the header, and the app's bar with the dock on
                // it. Fixed, so that the page fitted whole rests clear of them and never moves for them.
                val headRoom = { with(density) { headerHeightPx.takeIf { it > 0 } ?: QuranHeaderHeight.roundToPx() } }
                val footRoom = { with(density) { (bottomBarRoom() + QuranDockHeight).roundToPx() } }
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize().testTag("quran_pages"),
                    key = { pages[it].number },
                    userScrollEnabled = !zoomed,
                    beyondViewportPageCount = 1,
                ) { index ->
                    val number = pages[index].number
                    val selected = buildList {
                        selection?.let { addAll(highlights?.rectangles(number, it.surah, it.ayah).orEmpty()) }
                        val from = rangeFrom
                        if (from != null && highlights != null && catalog != null) {
                            val to = rangeTo ?: from
                            val (a, b) = if (to < from) to to from else from to to
                            catalog.versesOnPage(number).filter { it.ayah > 0 && it.reference >= a && it.reference <= b }
                                .forEach { addAll(highlights.rectangles(number, it.surah, it.ayah)) }
                        }
                        scrub?.let { s -> playback.surah?.let { addAll(highlights?.rectangles(number, it, s.ayah).orEmpty()) } }
                    }
                    val recitedAyah = playback.ayah ?: playback.gapAfter
                    /** The page of the verse the review asks. */
                    val reviewHere = reviewVerse != null && number in reviewPages
                    /**
                     * The verse asked is covered until «أظهر», and again for the drill after «ما زالت تحتاج تدريبًا»: it
                     * is recited from memory, the eraser at hand.
                     */
                    val reviewCovered = !reviewRevealed
                    val hidden = when {
                        highlights == null || catalog == null -> emptyList()
                        // The review: the verse asked and all after it, until «أظهر»; the verses before it stay clear,
                        // but for those of points still to come.
                        reviewVerse != null && reviewHere -> catalog.versesOnPage(number).filter {
                            it.ayah > 0 && ((reviewCovered && it.reference >= reviewVerse) || it.reference in reviewUpcoming)
                        }
                        // «مراجعة»: every numbered verse on the page is covered, except one opened by its number, a search or
                        // a bookmark, until it covers itself again.
                        reviewMode -> catalog.versesOnPage(number).filter {
                            it.ayah > 0 && (it.reference != selection || it.reference == recovered)
                        }
                        reviewUpcoming.isNotEmpty() -> catalog.versesOnPage(number).filter { it.ayah > 0 && it.reference in reviewUpcoming }
                        else -> emptyList()
                    }
                    // The verse number stays visible: it ends the verse, at the left end of its last line. Every verse
                    // has its hole, the one shown too, so that covering it again leaves its number.
                    val holes = reviewMode || hidden.isNotEmpty()
                    val numberHoles = remember(holes, highlights, catalog, number) {
                        if (!holes || highlights == null || catalog == null) emptyMap()
                        else catalog.versesOnPage(number).filter { it.ayah > 0 && it.pages.last() == number }.mapNotNull { v ->
                            highlights.rectangles(number, v.surah, v.ayah).lastOrNull()?.let { r ->
                                val cy = (r.top + r.bottom) / 2f
                                v.reference to QuranHighlightRect(r.left - 0.0015f, cy - QuranNumberHalfHeight, r.left + 0.059f + 0.0015f, cy + QuranNumberHalfHeight)
                            }
                        }.toMap()
                    }
                    // The verse asked, or at the review's end the one last asked, with the end of the one before it.
                    val kept = reviewTarget ?: reviewLastVerse?.takeIf { reviewOnPage }
                    val keep = when {
                        index != pager.currentPage -> emptyList()
                        // The verse asked, and the end of the one before it, stay above the review's card.
                        kept != null && highlights?.rectangles(number, kept.surah, kept.ayah).isNullOrEmpty() == false -> buildList {
                            highlights?.rectangles(number, kept.surah, kept.ayah - 1)?.lastOrNull()?.let(::add)
                            addAll(highlights?.rectangles(number, kept.surah, kept.ayah).orEmpty())
                        }
                        // Fitted whole, the page never moves for the card about a verse or a passage: the card goes to the
                        // page's head instead (see cardAbout). Filling the width, the page scrolls them into view.
                        selection != null -> if (pageFillsWidth) selection?.let { highlights?.rectangles(number, it.surah, it.ayah) }.orEmpty() else emptyList()
                        rangeFrom != null -> if (pageFillsWidth) selected else emptyList()
                        expanded -> highlights?.rectangles(number, playback.surah, recitedAyah).orEmpty()
                        else -> emptyList()
                    }
                    // A page that fills the width shows only a part of itself: the verse recited while the reader
                    // follows it is scrolled to when it is out of view, as the found verse is once.
                    val reveal = when {
                        index != pager.currentPage || !pageFillsWidth || highlights == null -> emptyList()
                        followAudio && playback.playing -> highlights.rectangles(number, playback.surah, recitedAyah)
                        else -> emptyList()
                    }
                    QuranPageImage(
                        page = pages[index],
                        active = index == pager.currentPage,
                        settled = index == pager.settledPage,
                        pagesReady = pagesReady,
                        highlightRects = highlights?.rectangles(number, playback.surah, recitedAyah).orEmpty(),
                        foundRects = if (highlights != null && found != null) found.verses.flatMap { highlights.rectangles(number, it.surah, it.ayah) } else emptyList(),
                        hiddenRects = highlights?.covers(number, hidden.flatMap { highlights.rectangles(number, it.surah, it.ayah) }).orEmpty(),
                        numberHoles = numberHoles,
                        // The verse asked, its cover in the points' colour: the card names it, the page shows it.
                        askedRects = if (reviewVerse != null && reviewHere && reviewCovered) {
                            highlights?.covers(number, highlights.rectangles(number, reviewVerse.surah, reviewVerse.ayah)).orEmpty()
                        } else emptyList(),
                        hifzRects = if (reviewVerse != null && reviewHere) {
                            // Shown, in the points' colour.
                            if (reviewCovered) emptyList() else highlights?.rectangles(number, reviewVerse.surah, reviewVerse.ayah).orEmpty()
                        } else if (panel == "hifzReview") {
                            // No other point is marked while the review goes: the ones to come are covered.
                            emptyList()
                        } else if (hifzSettings.markers && highlights != null && catalog != null && pointsByVerse.isNotEmpty()) {
                            // Hidden while the verse is recited, found or selected: one tint at a time.
                            val recited = playback.surah?.let { s -> recitedAyah?.let { QuranVerseReference(s, it) } }
                            // Points still to hold: a held one keeps its checks, not its mark.
                            catalog.versesOnPage(number).filter { v ->
                                v.ayah > 0 && pointsByVerse[v.reference]?.any { !it.mastered } == true && v.reference != recited &&
                                    v.reference != selection && found?.verses?.contains(v.reference) != true
                            }.flatMap { highlights.rectangles(number, it.surah, it.ayah) }
                        } else emptyList(),
                        selectedRects = selected,
                        fillWidth = pageFillsWidth,
                        keepVisible = keep,
                        restAtTop = kept != null,
                        bringIntoView = reveal,
                        bottomInsetPx = bottomInset,
                        topInsetPx = topInset,
                        headRoomPx = headRoom,
                        footRoomPx = footRoom,
                        onFrame = { if (index == pager.currentPage) pageFrame = it },
                        verseAt = { x, y -> if (catalog != null) highlights?.verseAt(number, x, y) else null },
                        rectsOf = { v -> highlights?.rectangles(number, v.surah, v.ayah).orEmpty() },
                        // «مراجعة» gives taps and long presses to the eraser, unless a passage is being chosen; so does the
                        // review's cover, until «أظهر».
                        erasing = highlights != null && catalog != null && if (reviewHere) reviewCovered else reviewMode && rangeFrom == null,
                        lineAt = { x, y -> highlights?.lineAt(number, x, y)?.let { line -> highlights.covers(number, listOf(line)).first() } },
                        onErased = { piece ->
                            val exposed = catalog?.versesOnPage(number).orEmpty().filter { verse ->
                                verse.ayah > 0 && highlights?.rectangles(number, verse.surah, verse.ayah).orEmpty()
                                    .any { r -> piece.left < r.right && piece.right > r.left && piece.top < r.bottom && piece.bottom > r.top }
                            }.map { it.reference }.toSet()
                            if (panel == "hifzReview") reviewState.noteExposed(exposed, reviewActions)
                            else updateLearning { it.expose(exposed) }
                            // «مراجعة»: erasing another verse is reciting on, and the card of the verse opened before goes
                            // with it; no timer, so that «أخطأت» is never a race. Erasing that verse itself keeps it.
                            val opened = selection
                            if (opened != null && exposed.isNotEmpty() && opened !in exposed) selectionDraft = null
                            val point = reviewPoint
                            if (point != null && !reviewRevealed && highlights?.rectangles(number, point.verse.surah, point.verse.ayah)
                                    .orEmpty().any { r -> piece.left < r.right && piece.right > r.left && piece.top < r.bottom && piece.bottom > r.top }) {
                                reviewState.markAssisted(point)
                            }
                        },
                        onTap = { verse ->
                            when {
                                // The review on the page chooses nothing, and only «أظهر» shows the answer: a tap meant
                                // for the eraser on the verse's number must not give it away.
                                reviewOnPage -> Unit
                                verse == null -> {
                                    // A tap on paper outside the text clears what was shown over the page; with
                                    // nothing shown, it brings the tools back, and the next one hides them again.
                                    if (selection == null && rangeFrom == null && !expanded && !zoomed) focusChosen = !focusChosen
                                    selectionDraft = null
                                    if (expanded) expanded = false
                                }
                                rangeFrom != null -> rangeToDraft = encodeVerse(verse)
                                else -> {
                                    expanded = false
                                    selectionDraft = if (selection == verse) null else encodeVerse(verse)
                                    if (tapHint) hideHint("hint_tap_seen")
                                }
                            }
                        },
                        onZoomChanged = { if (index == pager.currentPage) zoomed = it },
                        onSettled = { if (index == pager.currentPage) onPageSettled() },
                    )
                }
                // A review brought back before the verses' places are read again: the page waits under a plain cover
                // rather than show the verse it asks.
                if (panel == "hifzReview" && !detailsFailing && (catalog == null || highlights == null)) Box(
                    Modifier.matchParentSize().background(BgCream).pointerInput(Unit) { awaitEachGesture { awaitFirstDown() } },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = GreenPrimary)
                }
                if (media != null && !pagesReady && media.pages != null) QuranPagesEmptyState(
                    media,
                    onDownload = { media.fetch(listOfNotNull(media.pages), allowMetered = true) },
                    onUseMobileData = { media.source?.confirmMobileData(listOfNotNull(media.pages), mobileData) },
                    modifier = Modifier.align(Alignment.Center),
                )
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        // Snackbars and the hold bubble stay above the expanded player, which covers the bottom.
                        .padding(bottom = if (expanded) with(density) { panelHeightPx.toDp() } else 0.dp)
                        .onSizeChanged { overlayHeightPx = it.height }
                        // Fitted whole, the page goes on under the dock and the bar: what comes over its foot stays above
                        // them, and their height counts in what covers the page.
                        .layout { measurable, constraints ->
                            val pad = if (pageFillsWidth) 0 else dockRoomPx()
                            val placeable = measurable.measure(constraints.offset(vertical = -pad))
                            layout(placeable.width, placeable.height + pad) { placeable.place(0, 0) }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val bubble = scrub?.let { "الآية ${it.ayah} · ${ltr(quranClock(it.positionMs))}" }
                        ?: holdDelta?.let { delta -> "${quranSignedClock(delta)}" + ((track?.ayahAt(playback.positionMs) ?: playback.ayah)?.let { " · الآية $it" } ?: "") }
                    if (bubble != null) QuranBubble(bubble)
                    val pillVisible = playback.playing && sessionVerse != null && !sessionOnPage && scrub == null && selection == null && rangeFrom == null &&
                        !expanded && !reviewOnPage
                    AnimatedVisibility(pillVisible) {
                        OutlinedButton(
                            onClick = {
                                followAudio = true
                                val target = sessionVerse?.let { catalog?.pagesForVerse(it.surah, it.ayah)?.firstOrNull() }
                                if (target != null) scope.launch { autoTurned = target - 1; pager.animateScrollToPage(target - 1) }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, GreenPrimary),
                            modifier = Modifier.padding(bottom = 12.dp).testTag("quran_return_pill"),
                        ) {
                            Icon(painterResource(R.drawable.ic_quran_locate), null, Modifier.size(18.dp), tint = GreenPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("العودة إلى الآية المتلوّة", color = GreenPrimary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    when {
                        reviewOnPage && catalog != null -> QuranReviewOnPage(
                            // Looked away from the point's page: one tap back to it.
                            backToPoint = if (reviewPages.isNotEmpty() && pager.currentPage + 1 !in reviewPages) ({
                                scope.launch { autoTurned = reviewPages.first() - 1; pager.animateScrollToPage(reviewPages.first() - 1) }
                            }) else null,
                        ) {
                            HifzReviewCard(
                                state = reviewState, catalog = catalog, lookAlikes = lookAlikes, queue = reviewQueue,
                                audio = reviewHifzAudio, audioControls = hifzAudioControls,
                                stillDue = reviewStillDue, nextCheckIn = reviewNextCheckIn,
                                actions = reviewActions, freshAgain = freshAgain, day = reviewDayAnswers, nextFresh = reviewNextFresh,
                                tomorrow = tomorrowLabel, nextCheckLabel = nextCheckLabel, nextFor = reviewNextFor,
                                // About half the screen at most: the verse asked and what leads to it keep the rest.
                                maxHeight = (configuration.screenHeightDp * 0.55f).dp,
                                // The end of the verse before, when the page cannot show it: on the page before, for a verse
                                // that opens its page, or covered as a point still to come.
                                beforeLabel = hifzBeforeLabel(reviewTarget, reviewPages, reviewUpcoming, highlights),
                                eraserHint = reviewEraserHint, onEraserHintClosed = { hideHint("hint_review_eraser_seen") },
                            )
                        }
                        // The passage's card and the verse card, below, take this place.
                        rangeFrom != null -> Unit
                        selection != null && !expanded -> Unit
                        duePagePoints.isNotEmpty() && !expanded && !reviewOnPage && snackbar.currentSnackbarData == null -> QuranDueBanner(
                            title = duePagePoints.singleOrNull()?.let { "حان سؤال ${surahName(it.verse.surah)} ${it.verse.ayah}" }
                                ?: "حان سؤال ${HifzReminder.pointsAbout(duePagePoints.size)} في هذه الصفحة",
                            line = if (duePagePoints.size == 1) "إن قرأتها هنا الآن، نؤجّل سؤالها." else "إن قرأت الصفحة الآن، نؤجّل أسئلتها.",
                            onAsk = { startReview(first = duePagePoints.first()) },
                            onClose = { dueBannerClosed = dueBannerClosed + duePagePoints.map { it.id } },
                        )
                        // «مراجعة» gives the tap to the eraser.
                        tapHint && catalog != null && !expanded && !reviewMode -> QuranHint("اضغط على أي آية لتستمع منها أو تكرّرها أو تنسخها") { hideHint("hint_tap_seen") }
                        stripHint && hasSession && showDock && !expanded -> QuranHint("اسحب الخط فوق أزرار المشغّل للانتقال إلى آية") { hideHint("hint_strip_seen") }
                        // One message at a time: the hint waits for the snackbar, as after a mistake is saved.
                        focusChosen && focus && focusHint && snackbar.currentSnackbarData == null ->
                            QuranHint("اضغط على هامش الصفحة أو زر الرجوع لإظهار الأدوات") { hideHint("hint_focus_seen") }
                    }
                    SnackbarHost(snackbar) { data ->
                        val extra = data.visuals as? QuranSnackbar
                        Snackbar(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            actionOnNewLine = extra?.secondLabel != null,
                            action = {
                                Row {
                                    extra?.secondLabel?.let { label ->
                                        TextButton(onClick = { extra.onSecond(); if (extra.dismissOnSecond) data.dismiss() }) { Text(label, color = GoldLight) }
                                    }
                                    data.visuals.actionLabel?.let { label ->
                                        TextButton(onClick = data::performAction) { Text(label, color = GoldLight) }
                                    }
                                }
                            },
                        ) { Text(data.visuals.message) }
                    }
                }
                // The cards about a verse or a passage: at the page's foot, or at its head where at the foot they would hide
                // more of what they are about. Fitted whole, the page never moves for them; filling the width, it scrolls
                // what they are about into view above them.
                val cardAbout = remember(selection, rangeFrom, rangeTo, page.number, highlights, catalog, pageFillsWidth) {
                    val from = rangeFrom
                    when {
                        pageFillsWidth || highlights == null -> emptyList()
                        from != null -> {
                            val to = rangeTo ?: from
                            val (a, b) = if (to < from) to to from else from to to
                            catalog?.versesOnPage(page.number).orEmpty().filter { it.ayah > 0 && it.reference >= a && it.reference <= b }
                                .flatMap { highlights.rectangles(page.number, it.surah, it.ayah) }
                        }
                        selection != null -> selection?.let { highlights.rectangles(page.number, it.surah, it.ayah) }.orEmpty()
                        else -> emptyList()
                    }
                }
                Column(
                    Modifier.fillMaxWidth().layout { measurable, constraints ->
                        val head = if (headerOverlap) headerHeightPx else 0
                        val foot = overlayHeightPx + if (expanded) panelHeightPx else 0
                        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = (constraints.maxHeight - head - foot).coerceAtLeast(0)))
                        val atFoot = constraints.maxHeight - foot - placeable.height
                        val onHead = placeable.height > 0 &&
                            quranCardOnHead(pageFrame, cardAbout, head = (head + placeable.height).toFloat(), foot = atFoot.toFloat(), margin = 8.dp.toPx())
                        val atFootHeight = if (onHead) 0 else placeable.height
                        if (cardAtFootPx != atFootHeight) cardAtFootPx = atFootHeight
                        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, if (onHead) head else atFoot) }
                    },
                ) {
                    if (rangeFrom != null) QuranRangeCard(
                        title = quranRangeTitle(surahName, (rangeTo?.let { if (it < rangeFrom) it else rangeFrom } ?: rangeFrom), rangeTo?.let { if (it < rangeFrom) rangeFrom else it }),
                        endChosen = rangeTo != null,
                        times = rangeTimes.takeIf { it > 0 },
                        onTimes = { rangeTimes = it ?: 0 },
                        onToPageEnd = {
                            val last = catalog?.versesOnPage(page.number)?.lastOrNull { it.ayah > 0 && it.surah == rangeFrom.surah }
                            if (last != null) rangeToDraft = encodeVerse(last.reference)
                        },
                        onToSurahEnd = {
                            catalog?.surahs?.getOrNull(rangeFrom.surah - 1)?.verseCount?.let { rangeToDraft = encodeVerse(QuranVerseReference(rangeFrom.surah, it)) }
                        },
                        onTyped = {
                            val to = rangeTo ?: rangeFrom
                            typedRangeDraft = "${rangeFrom.surah}:${rangeFrom.ayah}:${to.surah}:${to.ayah}"
                            rangeFromDraft = null; rangeToDraft = null
                            panel = "typedRange"
                        },
                        onStart = ::startRange,
                        onCancel = { rangeFromDraft = null; rangeToDraft = null },
                    )
                    // Built as soon as the reader can select a verse, and kept with the last verse while none is:
                    // choosing a verse then only places the card, without building it in the frame of the tap.
                    if (selection != null) lastCardVerse[0] = selection
                    val cardVerse = selection ?: lastCardVerse[0] ?: QuranVerseReference(1, 1).takeIf { catalog != null }
                    if (cardVerse != null) QuranCardSlot(shown = selection != null && rangeFrom == null && !expanded) {
                        QuranVerseCard(
                            hidesAfterMs = if (reviewMode && selection != null) hosaryMs(cardVerse).coerceAtLeast(QuranReviewMinMs) else null,
                            review = reviewMode,
                            title = "${surahName(cardVerse.surah)} · الآية ${cardVerse.ayah}",
                            bookmarked = remember(libraryRevision, cardVerse) { library.isBookmarked(cardVerse) },
                            hasPoint = cardVerse in pointsByVerse,
                            onNotebook = {
                                // The point asked first, when a verse has several; one still waiting for its question asks
                                // before its correction is shown.
                                val points = pointsByVerse[cardVerse].orEmpty()
                                val waiting = points.firstOrNull { it.id in eligibleDueIds }
                                val shown = waiting ?: points.firstOrNull { it.isDue(today, hifzNow) } ?: points.firstOrNull()
                                if (shown != null) {
                                    selectionDraft = null
                                    if (waiting != null) spoilPointId = waiting.id else openFix(shown)
                                }
                            },
                            onListen = {
                                val current = QuranAudioController.state.value
                                if (current.surah != null && !current.restored && QuranAudioController.serviceRunning) {
                                    if (current.repeat?.contains(cardVerse) == false) {
                                        QuranAudioController.cancelRepeat(context)
                                        scope.launch { snackbar.showSnackbar("أُلغي التكرار", duration = SnackbarDuration.Short) }
                                    }
                                    starter.start(current.reciterId, cardVerse.surah..cardVerse.surah) {
                                        QuranAudioController.seekVerse(context, cardVerse.surah, cardVerse.ayah)
                                        if (!current.playing) QuranAudioController.resume(context)
                                    }
                                    followAudio = true
                                    selectionDraft = null
                                } else startFrom(cardVerse)
                            },
                            onRepeat = { beginRange(cardVerse) },
                            // «أخطأت» records a slip, also on a verse that has one: the record sheet says so.
                            onPoint = { panel = "hifzRecord:${cardVerse.surah}:${cardVerse.ayah}" },
                            onBookmark = {
                                val saved = library.toggleVerse(cardVerse, System.currentTimeMillis())
                                libraryRevision++
                                scope.launch {
                                    val result = snackbar.showSnackbar(
                                        if (saved) "حُفظت العلامة · ${surahName(cardVerse.surah)} ${cardVerse.ayah}" else "أُزيلت العلامة",
                                        actionLabel = if (saved) "العلامات" else null, duration = SnackbarDuration.Short,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) panel = "chapters:bookmarks"
                                }
                            },
                            onCopy = { copyOrShare(cardVerse, share = false) },
                            onShare = { copyOrShare(cardVerse, share = true) },
                            onDismiss = { selectionDraft = null },
                        )
                    }
                }
            }
            if (showDock && !expanded) {
                val pending = starter.pendingPacks
                val download = media?.takeIf { pending.isNotEmpty() }?.summary(pending)
                val notice = quranDockNotice(playback, media, pending, download, reciter.shortName, surahName)
                QuranDock(
                    playback = playback,
                    track = track,
                    text = quranDockText(playback, listening, playAction, selection, pageStart, sessionOnPage, reciter.name, reciter.shortName, surahName, catalog),
                    notice = notice,
                    playAction = playAction,
                    session = hasSession,
                    reciterShortName = reciter.shortName,
                    onPlay = ::pressPlay,
                    onLink = {
                        // The same order as the dock's text: a place elsewhere first, then a playlist just ended.
                        val ended = playback.finishedPlaylist?.takeIf { !playback.playing }
                        when {
                            playAction is QuranPlayAction.PlayFrom -> if (hasSession) resumeSession() else Unit
                            ended != null -> playList(ended)
                            playAction == QuranPlayAction.Resume -> pageStart?.let(::startFrom)
                            else -> Unit
                        }
                    },
                    onExpand = { if (hasSession) { expanded = true; selectionDraft = null } },
                    onPrevious = { QuranAudioController.previousVerse(context); followAudio = true },
                    onNext = { QuranAudioController.nextVerse(context); followAudio = true },
                    onSkipBy = { QuranAudioController.skipBy(context, it) },
                    onHold = { holdDelta = it },
                    onRepeat = ::toggleRepeat,
                    onRepeatLong = { expanded = true },
                    onReciter = { panel = "reciter" },
                    onNoticePrimary = {
                        when {
                            download?.status == QuranPackStatus.WaitingForWifi || download?.status == QuranPackStatus.NeedsConfirmation ->
                                media?.source?.confirmMobileData(pending, mobileData)
                            download?.status == QuranPackStatus.Failed -> media?.fetch(pending, allowMetered = true)
                            else -> resumeSession()
                        }
                    },
                    onNoticeSecondary = { starter.cancel() },
                    onScrub = { scrub = it; if (it != null && stripHint) hideHint("hint_strip_seen") },
                    onScrubEnd = { target ->
                        QuranAudioController.seekTo(context, target.positionMs)
                        followAudio = true
                    },
                    onHeld = { dockHeld = it },
                    // Over the page's foot, on the app's bar while it shows.
                    modifier = Modifier.align(Alignment.BottomCenter).offset { IntOffset(0, -bottomBar().roundToPx()) },
                )
            }
        }
        AnimatedVisibility(
            visible = expanded && hasSession,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it }, exit = slideOutVertically { it },
        ) {
            val surah = playback.surah ?: 1
            QuranExpandedPlayer(
                playback = playback,
                listening = listening,
                track = track,
                surahName = surahName(surah),
                verseCount = catalog?.surahs?.getOrNull(surah - 1)?.verseCount,
                pageLabel = sessionVerse?.let { catalog?.pagesForVerse(it.surah, it.ayah)?.firstOrNull() }?.let(::quranPageLabel).orEmpty(),
                reciterShortName = reciter.shortName,
                playAction = playAction,
                nextSurahName = catalog?.surahs?.getOrNull(surah)?.name,
                actions = playerActions,
                modifier = Modifier.onSizeChanged { panelHeightPx = it.height },
                fineSeekHint = fineSeekHint,
                onFineSeekHintSeen = { if (fineSeekHint) hideHint("hint_fine_seek_seen") },
            )
        }
    }

    val currentPanel = panel
    when {
        currentPanel == null -> Unit
        currentPanel.startsWith("chapters") -> if (catalog != null) QuranIndexSheet(
            catalog = catalog,
            initialTab = if (currentPanel == "chapters:bookmarks") "العلامات" else null,
            divisions = divisions,
            library = library,
            libraryRevision = libraryRevision,
            currentPage = page.number,
            lastListened = playback.surah?.let { s -> "${surahName(s)} · ${playback.ayah?.takeIf { it > 0 }?.let { "الآية $it" } ?: "البداية"} · ${reciter.shortName}" },
            listeningNow = playback.playing,
            hifzDue = eligibleDueIds.size,
            missingSurahs = remember(media?.states, playback.reciterId) {
                val layout = media?.layout ?: return@remember emptySet<Int>()
                layout.audioPacks(playback.reciterId).filterNot { media.state(it).available }.flatMap { it.surahs?.toList().orEmpty() }.toSet()
            },
            actions = object : QuranIndexActions {
                override fun openPage(scanPage: Int) = goToPage(scanPage)
                override fun openVerse(verse: QuranVerseReference, play: Boolean) {
                    showVerse(verse)
                    if (play) startFrom(verse)
                }
                override fun playSurah(surah: Int) {
                    catalog.surahs.getOrNull(surah - 1)?.let { goToPage(it.page) }
                    startFrom(QuranVerseReference(surah, 1))
                }
                override fun resumeListening() { panel = null; resumeSession() }
                override fun openHifz() { panel = "hifz" }
                override fun search() { panel = "search" }
                override fun dismiss() { panel = null }
            },
        )
        currentPanel == "search" -> if (catalog != null) QuranSearchSheet(
            catalog = catalog, divisions = divisions, search = search, recents = recentSearches,
            onClearRecents = { recentSearches = emptyList(); writeRecentSearches(prefs, emptyList()) },
            onDismiss = { panel = null }, onResult = ::openResult,
            onPlayResult = { position ->
                search.visible.getOrNull(position)?.verses?.firstOrNull()?.let { verse ->
                    openResult(position)
                    startFrom(verse)
                }
            },
            onLocation = { target, play ->
                recentSearches = recentSearches.withRecentSearch(search.query).also { writeRecentSearches(prefs, it) }
                when (target) {
                    is QuranGoTo.Page -> goToPage((target.printed - 1).coerceAtLeast(1))
                    is QuranGoTo.Surah -> {
                        goToPage(catalog.surahs[target.number - 1].page)
                        if (play) startFrom(QuranVerseReference(target.number, 1))
                    }
                    is QuranGoTo.Hizb -> divisions?.hizbStart(target.number)?.let { start ->
                        showVerse(start.start)
                        if (play) startFrom(start.start)
                    }
                    is QuranGoTo.Verse -> {
                        showVerse(target.verse)
                        if (play) startFrom(target.verse)
                    }
                }
            },
        )
        currentPanel == "reciter" -> QuranReciterSheet(
            selected = playback.reciterId,
            media = media,
            onSelect = { QuranAudioController.selectReciter(context, it); panel = null },
            onManage = { panel = "downloads" },
            onDismiss = { panel = null },
        )
        currentPanel == "downloads" -> if (media != null) QuranDownloadsSheet(
            media = media, mobileData = mobileData,
            onDeleted = { starter.cancel() },
            onDismiss = { panel = null },
        )
        currentPanel == "night" -> QuranNightDialog(
            enabled = nightOn, level = nightLevel,
            onEnabled = { nightOn = it; prefs.edit().putBoolean("night_on", it).apply() },
            onLevel = { nightLevel = it; prefs.edit().putFloat("night_level", it).apply() },
            onDismiss = { panel = null },
        )
        currentPanel.startsWith("hifzRecord:") -> {
            // Opened from the notebook («:typed» when the reader typed the verse there): closing goes back there.
            val typed = currentPanel.endsWith(":typed")
            val home = typed || currentPanel.endsWith(":home")
            val parts = currentPanel.removePrefix("hifzRecord:").removeSuffix(":typed").removeSuffix(":home").split(':').mapNotNull { it.toIntOrNull() }
            if (catalog != null && parts.size == 2) HifzRecordSheet(
                catalog = catalog, lookAlikes = lookAlikes, initialVerse = QuranVerseReference(parts[0], parts[1]),
                onSave = { verse, kind, other, words, both, plan -> recordSlip(verse, kind, other, words, both, plan, home) },
                onDismiss = { panel = if (home) "hifz" else null },
                remind = hifzSettings.reminder,
                offerPrevious = !typed,
                points = hifzPoints,
            ) else if (catalog != null) panel = null
        }
        currentPanel.startsWith("hifzFix:") -> {
            val point = hifzPoints.firstOrNull { it.id.toString() == currentPanel.removePrefix("hifzFix:") }
            if (catalog != null && point != null) with(hifzScope) {
                key(point.id) {
                    HifzFixPanel(
                        catalog, lookAlikes, point, today, onBack = { panel = null }, onLeave = { panel = null },
                        onOpenVerse = { panel = null; showVerse(it) }, onSibling = { panel = "hifzFix:${it.id}" },
                    )
                }
            } else if (catalog != null && HifzRepository.isLoaded) panel = null
        }
        currentPanel == "hifz" || currentPanel == "hifz:record" -> if (catalog != null) HifzHomeSheet(
            catalog = catalog, points = hifzPoints, settings = hifzSettings, today = today, now = hifzNow, nextFresh = reviewNextFresh,
            nextCheckLabel = nextCheckLabel, nextCheckIn = reviewNextCheckIn,
            startRecording = currentPanel == "hifz:record",
            notice = hifzNotice, onNoticeClosed = { hifzNotice = null },
            audio = activeHifzAudio, audioControls = hifzAudioControls,
            eligibleDueIds = eligibleDueIds,
            later = hifzLaterTimes,
            actions = object : HifzHomeActions {
                override fun recordShown() { if (panel == "hifz:record") panel = "hifz" }
                override fun review() = startReview()
                override fun reviewFrom(point: HifzPoint) = startReview(first = point)
                override fun listen(points: List<HifzPoint>) { panel = null; playList(hifzListen(catalog, points)) }
                // Over the notebook, which keeps its tab, filters and place when the fix closes.
                override fun open(point: HifzPoint) { stackedFix = point.id }
                override fun record(verse: QuranVerseReference) { panel = "hifzRecord:${verse.surah}:${verse.ayah}:typed" }
                override fun settings() { panel = "hifzSettings" }
                override fun dismiss() { panel = null }
            },
        )
        currentPanel == "hifzSettings" -> HifzSettingsSheet(
            settings = hifzSettings,
            onChange = {
                hifzSettings = it
                HifzSettings.write(context, it)
                scope.launch(Dispatchers.IO) { HifzReminder.refresh(context) }
            },
            onDismiss = { panel = "hifz" },
        )
        // The review on the page is drawn with the page; the sheet takes it wherever the page cannot.
        currentPanel == "hifzReview" -> if (catalog != null && !reviewOnPage) HifzReviewSheet(
            state = reviewState, catalog = catalog, lookAlikes = lookAlikes, queue = reviewQueue,
            audio = reviewHifzAudio, audioControls = hifzAudioControls,
            stillDue = reviewStillDue, nextCheckIn = reviewNextCheckIn,
            actions = reviewActions, freshAgain = freshAgain, day = reviewDayAnswers, nextFresh = reviewNextFresh,
            tomorrow = tomorrowLabel, nextCheckLabel = nextCheckLabel, nextFor = reviewNextFor,
        )
        currentPanel == "typedRange" -> {
            val parts = typedRangeDraft?.split(':')?.mapNotNull { it.toIntOrNull() }
            if (catalog != null && parts != null && parts.size == 4) key(typedRangeDraft) {
                QuranRepeatSheet(
                    catalog = catalog,
                    initial = QuranRepeatRange(QuranVerseReference(parts[0], parts[1]), QuranVerseReference(parts[2], parts[3]), listening.lastRepeatTimes),
                    onStart = { range ->
                        QuranAudioController.updateListening(context) { it.copy(lastRepeatTimes = range.times) }
                        starter.start(playback.reciterId, range.from.surah..range.to.surah) { QuranAudioController.repeat(context, range) }
                        followAudio = true
                        panel = null
                    },
                    onDismiss = { panel = null },
                )
            } else if (catalog != null) panel = null
        }
        else -> Unit
    }
    // A fix opened from the review, the notebook or a list of points opens over it, which keeps its place.
    stackedFix?.let { id ->
        val point = hifzPoints.firstOrNull { it.id == id }
        val overReview = panel == "hifzReview"
        if (catalog != null && point != null) with(hifzScope) { key(point.id) {
            HifzFixPanel(
                catalog, lookAlikes, point, today, onBack = { stackedFix = null },
                onLeave = { stackedFix = null; panel = null },
                pageInView = overReview && reviewOnPage,
                overHub = panel == "hifz",
                onOpenVerse = { verse ->
                    if (overReview) {
                        endReview(backToPage = false)
                        // The review's own page turns were not the reader's: the verse opened now is where reading
                        // stands, also on the page already in view, which no settle will report.
                        catalog.pagesForVerse(verse.surah, verse.ayah).firstOrNull()?.takeIf { it - 1 == pager.settledPage }?.let { here ->
                            prefs.edit().putInt("last_page", here).apply()
                            library.read(here)
                            libraryRevision++
                        }
                    } else { stackedFix = null; panel = null }
                    showVerse(verse)
                },
                onSibling = { stackedFix = it.id },
                // Under a sheet, an undo snackbar could not be seen: a point deleted from the review simply leaves it.
                overReview = overReview,
                onDeleted = { id ->
                    reviewState.forgetPoint(id)
                    if (overReview) leaveReviewCard()
                    reviewIds = reviewIds - id
                },
            )
        } } else if (catalog != null && HifzRepository.isLoaded) stackedFix = null
    }
    // «تعديل الموضع», over the fix it was opened from, which shows the change when it closes.
    editingPoint?.let { id ->
        val point = hifzPoints.firstOrNull { it.id == id }
        if (catalog != null && point != null) key(id) {
            HifzRecordSheet(
                catalog = catalog, lookAlikes = lookAlikes, initialVerse = point.verse,
                onSave = { _, _, _, _, _, _ -> }, onDismiss = { editingPoint = null },
                points = hifzPoints,
                editing = point,
                onEdit = { changed ->
                    editingPoint = null
                    saveHifz { HifzRepository.update(context, changed) }
                },
            )
        } else if (catalog != null && HifzRepository.isLoaded) editingPoint = null
    }
    // A question still waiting on the verse whose point is opened: seeing its answer now sends it to the next reminder.
    spoilPointId?.let { id ->
        val point = hifzPoints.firstOrNull { it.id == id }
        if (catalog != null && point != null) HifzSpoilDialog(
            catalog = catalog, point = point,
            onReview = { spoilPointId = null; startReview(first = point) },
            onOpen = { spoilPointId = null; openFix(point) },
            onDismiss = { spoilPointId = null },
        ) else if (catalog != null && HifzRepository.isLoaded) spoilPointId = null
    }
    }
}

/** A recorded point's fix, with drill, listening, editing and deleting. */
@Composable
private fun QuranReaderScope.HifzFixPanel(
    catalog: QuranCatalog,
    lookAlikes: HifzLookAlikes?,
    point: HifzPoint,
    today: Long,
    onBack: () -> Unit,
    /** For «درّب»: closes the fix and the sheet it is over, so that the page and the player show. */
    onLeave: () -> Unit,
    onOpenVerse: (QuranVerseReference) -> Unit,
    onSibling: (HifzPoint) -> Unit,
    /** Over the review: its card owns the audio, and the review's answers say how a slip went. */
    overReview: Boolean = false,
    /** The point's page is in view behind the fix, under the review's card: no «في المصحف». */
    pageInView: Boolean = false,
    /** Over the notebook, which stays open: a drill plays under it, and a delete is said at its top. */
    overHub: Boolean = false,
    onDeleted: (Long) -> Unit = {},
) {
    HifzFixSheet(
        catalog = catalog, lookAlikes = lookAlikes, point = point, today = today, undoable = !overReview, remind = settings().reminder,
        onEdit = { edit(point) },
        audio = audio, audioControls = audioControls,
        onSlipAgain = if (overReview) null else ({ slipAgain(point) }),
        onUndoSlipAgain = { before -> save { HifzRepository.restore(context, before) } },
        onOtherSlip = if (overReview) null else ({ recordOther(point.verse) }),
        siblings = siblings(point), onSibling = onSibling,
        onChange = { changed -> save { HifzRepository.update(context, changed) } },
        // Over the review, back to its card, whose strip shows the drill; over the notebook, back to it, whose strip
        // shows it too; elsewhere to the page and its player.
        onDrill = {
            val drill = hifzDrill(catalog, point)
            // Keep the correction open while listening and repeating.
            if (overReview) play(drill, true) else playBehind(drill)
        },
        // Heard with the fix open, its strip in view: the page does not turn behind it.
        onListen = {
            val list = hifzListen(catalog, listOf(point))
            if (overReview) play(list, true) else playBehind(list)
        },
        onOpen = if (pageInView) null else ({ onOpenVerse(point.verse) }),
        planChangeable = !overReview,
        onDelete = {
            // Over the review, its card shows the change and no snackbar could be seen; over the notebook, its top says
            // so with the undo; elsewhere the sheets close so that the snackbar's «تراجع» can be seen.
            if (overReview || overHub) onBack() else onLeave()
            save { HifzRepository.delete(context, point.id) }
            onDeleted(point.id)
            val message = "حُذف الموضع · ${hifzVerseName(catalog, point.verse)}"
            val restore = { save { HifzRepository.restore(context, point) } }
            when {
                overReview -> Unit
                overHub -> notice(HifzNotice(message, restore))
                else -> undo(message, restore)
            }
        },
        onDismiss = onBack,
    )
}

/** What the hifz sheets need from the reader. */
private class QuranReaderScope(
    val context: android.content.Context,
    val save: (() -> Unit) -> Unit,
    /** Plays a fix's audio; `review` when the fix is over the review, whose card then owns it. */
    val play: (list: QuranPlaylist?, review: Boolean) -> Unit,
    /** Plays behind a sheet that stays open: the page does not follow it. */
    val playBehind: (QuranPlaylist?) -> Unit,
    /** «تعديل الموضع» over the fix. */
    val edit: (HifzPoint) -> Unit,
    /** Said at the top of the notebook, with its undo. */
    val notice: (HifzNotice) -> Unit,
    val settings: () -> HifzSettings,
    val undo: (message: String, action: () -> Unit) -> Unit,
    /** The recitation playing, shown in the sheets that cover the player. */
    val audio: HifzAudio?,
    val audioControls: HifzAudioControls,
    /** «أخطأت فيها مرة أخرى»: the slip counted again, and the point asked at the reminders until it is read right again. */
    val slipAgain: (HifzPoint) -> Unit,
    /** «خطأ آخر في هذه الآية». */
    val recordOther: (QuranVerseReference) -> Unit,
    /** The other points of a point's verse. */
    val siblings: (HifzPoint) -> List<HifzPoint>,
)

/** A snackbar with a second action, e.g. «استمع للصواب» and «تراجع». */
private class QuranSnackbar(
    override val message: String,
    override val actionLabel: String?,
    val secondLabel: String?,
    val onSecond: () -> Unit,
    override val duration: SnackbarDuration = SnackbarDuration.Long,
    /** The second button closes the snackbar; false keeps the main action, e.g. «تراجع», within reach. */
    val dismissOnSecond: Boolean = true,
) : SnackbarVisuals {
    override val withDismissAction: Boolean = false
}

/** The location in view, opening the index, and the reader's own actions. */
@Composable
private fun QuranHeader(
    title: String,
    subtitle: String,
    compactSubtitle: String,
    /** Checks due today: a dot on the menu, and their number beside «المراجعة» in it. */
    hifzDue: Int,
    pageBookmarked: Boolean,
    keepAwake: Boolean,
    reviewMode: Boolean,
    onReviewMode: () -> Unit,
    catalogReady: Boolean,
    onLocation: () -> Unit,
    onBookmark: () -> Unit,
    onSearch: () -> Unit,
    onKeepAwake: () -> Unit,
    onNight: () -> Unit,
    onFocus: () -> Unit,
    /** What «استمع» does now: the dock's own rule. */
    listen: QuranPlayAction,
    /** The recitation is starting or its recording downloading: the dock says how far. */
    listenBusy: Boolean,
    listenLabel: String,
    onListen: () -> Unit,
    reciterShortName: String,
    onReciter: () -> Unit,
    onDownloads: () -> Unit,
    onHints: () -> Unit,
    markers: Boolean,
    onHifz: () -> Unit,
    onMarkers: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        // Taller only with large text, which would otherwise cut the page's line off.
        Modifier.fillMaxWidth().heightIn(min = 52.dp).background(BgCream).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(enabled = catalogReady, onClickLabel = "فتح الفهرس", onClick = onLocation)
                .padding(vertical = 2.dp).testTag("quran_location"),
        ) {
            Text("$title ▾", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // With large text the full line would cut off the hizb: the shorter one then. The page's own violet marks
            // show its points: no count of them here.
            var compact by remember(subtitle) { mutableStateOf(false) }
            Text(
                if (compact) compactSubtitle else subtitle, fontSize = 12.sp, color = TextMuted,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (it.hasVisualOverflow && !compact) compact = true },
            )
        }
        // Five actions share the bar, so they sit a little smaller than the 48 dp default.
        val button = Modifier.size(42.dp)
        val icon = Modifier.size(21.dp)
        IconButton(onClick = onBookmark, modifier = button.testTag("quran_page_bookmark")) {
            Icon(
                painterResource(if (pageBookmarked) R.drawable.ic_quran_bookmark_filled else R.drawable.ic_adhkar_bookmark),
                if (pageBookmarked) "إزالة علامة الصفحة" else "علامة على هذه الصفحة", icon, tint = GreenPrimary,
            )
        }
        // «مراجعة» is used often: one tap in the bar, filled while it is on.
        Box(
            button.toggleable(value = reviewMode, enabled = catalogReady, role = Role.Switch, onValueChange = { onReviewMode() })
                .semantics { contentDescription = "إخفاء الآيات" }.testTag("quran_review_mode"),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(33.dp).clip(CircleShape).background(if (reviewMode) GreenPrimary else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_quran_review), null, Modifier.size(20.dp),
                    tint = when { reviewMode -> Color.White; catalogReady -> GreenPrimary; else -> TextMuted })
            }
        }
        // «استمع» in the bar, rather than a player always there: the dock comes with the recitation this starts. Filled
        // while it plays, as «مراجعة» is while it is on; a tap then pauses it.
        IconButton(
            onClick = onListen, enabled = !listenBusy && listen != QuranPlayAction.None,
            modifier = button.semantics { contentDescription = if (listenBusy) "جارٍ تجهيز التلاوة" else listenLabel }.testTag("quran_listen"),
        ) {
            when {
                listenBusy -> CircularProgressIndicator(Modifier.size(20.dp), color = GreenPrimary, strokeWidth = 2.dp)
                listen == QuranPlayAction.Pause -> Box(
                    Modifier.size(33.dp).clip(CircleShape).background(GreenPrimary), contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_quran_pause), null, Modifier.size(20.dp), tint = Color.White)
                }
                else -> Icon(
                    painterResource(R.drawable.ic_quran_headphones), null, icon,
                    tint = if (listen == QuranPlayAction.None) TextMuted else GreenPrimary,
                )
            }
        }
        IconButton(onClick = onSearch, enabled = catalogReady, modifier = button.testTag("quran_search")) {
            Icon(painterResource(R.drawable.ic_adhkar_search), "بحث", icon, tint = if (catalogReady) GreenPrimary else TextMuted)
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = button.testTag("quran_menu")) {
                BadgedBox(badge = { if (hifzDue > 0) Badge(containerColor = QuranHifzViolet) }) {
                    Icon(
                        painterResource(R.drawable.ic_quran_more_vert),
                        if (hifzDue > 0) "خيارات القراءة · ${quranPointsLabel(hifzDue)} للمراجعة الآن" else "خيارات القراءة", icon, tint = GreenPrimary,
                    )
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // A tap on the page's margin hides the tools too; «استمع» took this button's place in the bar.
                DropdownMenuItem(text = { Text("وضع التركيز") }, onClick = { menu = false; onFocus() }, modifier = Modifier.testTag("quran_focus"))
                DropdownMenuItem(
                    text = { Text("إبقاء الشاشة مضاءة") },
                    trailingIcon = { Switch(checked = keepAwake, onCheckedChange = null) },
                    onClick = { onKeepAwake() },
                )
                DropdownMenuItem(text = { Text("إضاءة القراءة الليلية…") }, onClick = { menu = false; onNight() })
                // Chosen before listening too, now that no player waits at the foot of the page.
                DropdownMenuItem(
                    text = { Text("القارئ") },
                    trailingIcon = { Text(reciterShortName, color = TextMuted, fontSize = 13.sp) },
                    onClick = { menu = false; onReciter() },
                    modifier = Modifier.testTag("quran_menu_reciter"),
                )
                DropdownMenuItem(text = { Text("التنزيلات") }, onClick = { menu = false; onDownloads() })
                DropdownMenuItem(
                    text = { Text("المراجعة") },
                    trailingIcon = { if (hifzDue > 0) Badge(containerColor = QuranHifzViolet, contentColor = Color.White) { Text("$hifzDue") } },
                    onClick = { menu = false; onHifz() }, enabled = catalogReady,
                )
                DropdownMenuItem(
                    text = { Text("مواضعي على الصفحات") },
                    trailingIcon = { Switch(checked = markers, onCheckedChange = null) },
                    onClick = { onMarkers() },
                )
                DropdownMenuItem(text = { Text("إعادة عرض الإرشادات") }, onClick = { menu = false; onHints() })
            }
        }
    }
    HorizontalDivider(color = CardBorder)
}

/** After a search result opened: the header walks through the results instead of a second bar. */
@Composable
private fun QuranSearchHeader(index: Int, total: Int, query: String, onOpen: (Int) -> Unit, onList: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(AdhkarSoftGreen).padding(start = 16.dp, end = 4.dp).testTag("quran_result_stepper"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "قائمة النتائج", onClick = onList)) {
            Text("نتيجة ${index + 1} من $total", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            Text("“$query”", fontSize = 12.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { onOpen(index - 1) }, enabled = index > 0, modifier = Modifier.testTag("quran_result_previous")) {
            Icon(painterResource(R.drawable.ic_adhkar_back), "النتيجة السابقة", tint = if (index > 0) GreenPrimary else TextMuted)
        }
        IconButton(onClick = { onOpen(index + 1) }, enabled = index < total - 1, modifier = Modifier.testTag("quran_result_next")) {
            Icon(painterResource(R.drawable.ic_adhkar_next), "النتيجة التالية", tint = if (index < total - 1) GreenPrimary else TextMuted)
        }
        IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_adhkar_close), "إنهاء البحث", tint = TextMuted) }
    }
    HorizontalDivider(color = CardBorder)
}

@Composable
private fun QuranBubble(text: String) {
    Text(
        text,
        Modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(8.dp)).background(GreenPrimaryDark).padding(horizontal = 10.dp, vertical = 6.dp),
        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
    )
}

/** A one-time hint over the page, never a row that takes the page's height. */
/**
 * The review on the page, over its foot: «عد إلى الموضع» when the reader looked away ([backToPoint]), then the page
 * fading into the paper the card rests on, so that no line of the mushaf is cut in half by the card's edge. Neither the
 * fade nor the paper lets a touch through to the covered page under them, where it would erase what cannot be seen.
 * Apart from the reader's main lambda, which is past the registers D8 handles well.
 */
@Composable
private fun QuranReviewOnPage(backToPoint: (() -> Unit)?, card: @Composable () -> Unit) {
    if (backToPoint != null) OutlinedButton(
        onClick = backToPoint,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
        border = BorderStroke(1.dp, GreenPrimary),
        modifier = Modifier.padding(top = 4.dp).testTag("hifz_review_back_to_point"),
    ) {
        Icon(painterResource(R.drawable.ic_quran_locate), null, Modifier.size(18.dp), tint = GreenPrimary)
        Spacer(Modifier.width(8.dp))
        Text("عد إلى الموضع", color = GreenPrimary, fontWeight = FontWeight.SemiBold)
    }
    Box(
        Modifier.fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0f), Color.White)))
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown() } },
    )
    Box(
        Modifier.fillMaxWidth().background(Color.White).pointerInput(Unit) { awaitEachGesture { awaitFirstDown() } },
        contentAlignment = Alignment.Center,
    ) { card() }
}

/**
 * «آخر الآية 35:»: the end of the verse before [target], when its page cannot show it: on the page before, for a verse
 * that opens its page, or covered as a point still to come; null when the page shows it.
 */
private fun hifzBeforeLabel(
    target: QuranVerseReference?, pages: List<Int>, upcoming: Set<QuranVerseReference>, highlights: QuranHighlights?,
): String? {
    if (target == null || target.ayah <= 1 || pages.isEmpty()) return null
    val before = QuranVerseReference(target.surah, target.ayah - 1)
    return when {
        highlights?.rectangles(pages.first(), before.surah, before.ayah).isNullOrEmpty() -> "آخر الآية ${before.ayah}، في الصفحة السابقة:"
        before in upcoming -> "آخر الآية ${before.ayah}:"
        else -> null
    }
}

/**
 * A question due now whose verse is on the page in view: asked now, from memory, before the page is read, which would
 * count as seeing its answer and move it on. Closed, it stays closed for that point.
 */
@Composable
private fun QuranDueBanner(title: String, line: String, onAsk: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp).clip(RoundedCornerShape(14.dp)).background(GoldLight)
            .padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp).testTag("quran_due_banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            Text(line, fontSize = 12.sp, color = Color(0xFF6E5A2E))
        }
        TextButton(onClick = onAsk, modifier = Modifier.testTag("quran_due_banner_ask")) { Text("اسألني الآن") }
        IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
            Icon(painterResource(R.drawable.ic_adhkar_close), "إخفاء", Modifier.size(16.dp), tint = GreenPrimaryDark)
        }
    }
}

@Composable
private fun QuranHint(text: String, onClose: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 24.dp, vertical = 12.dp).clip(RoundedCornerShape(12.dp)).background(GreenPrimaryDark)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).testTag("quran_hint"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f, fill = false), color = Color.White, fontSize = 13.sp)
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(painterResource(R.drawable.ic_adhkar_close), "إخفاء التلميح", Modifier.size(16.dp), tint = Color.White)
        }
    }
}

@Composable
private fun QuranNightDialog(enabled: Boolean, level: Float, onEnabled: (Boolean) -> Unit, onLevel: (Float) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضاءة القراءة الليلية") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("تخفيف إضاءة الشاشة", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = onEnabled, modifier = Modifier.testTag("quran_night_switch"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("الإضاءة", Modifier.width(64.dp))
                    Slider(value = level, onValueChange = onLevel, valueRange = 0.05f..1f, enabled = enabled, modifier = Modifier.weight(1f))
                    Text(ltr("${(level * 100).toInt()}%"), Modifier.width(48.dp), fontSize = 13.sp)
                }
                Text("تبقى الصفحة بيضاء، وتخفّ إضاءة الشاشة في المصحف فقط", fontSize = 12.sp, color = TextMuted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } },
    )
}

/**
 * A drill's silence: what the reader says in it. After the right verse, it again; after its look-alike, the right one
 * from memory, which comes next to check it.
 */
private fun hifzGapLine(list: QuranPlaylist, index: Int, ayah: Int): String {
    val label = list.labels.getOrNull(index).orEmpty()
    return when {
        label.startsWith("الشبيهة") -> "دورك: اقرأ الصواب من حفظك"
        label.startsWith("الصواب") -> "دورك: ردّد $label"
        else -> "دورك: ردّد الآية $ayah"
    }
}

/** What the bar's «استمع» does now, for TalkBack: the dock's play button, with no dock there to say it. */
private fun quranListenLabel(
    action: QuranPlayAction,
    session: QuranVerseReference?,
    pageStart: QuranVerseReference?,
    surahName: (Int) -> String,
): String = when (action) {
    QuranPlayAction.Pause -> "إيقاف الاستماع مؤقتًا"
    QuranPlayAction.Resume -> session?.let { "تابع الاستماع من ${surahName(it.surah)} ${it.ayah}" } ?: "تابع الاستماع"
    is QuranPlayAction.PlayFrom -> if (action.verse == pageStart) "استمع من هذه الصفحة"
        else "استمع من ${surahName(action.verse.surah)} ${action.verse.ayah}"
    QuranPlayAction.None -> "استمع"
}

/** What the dock says beside its controls. */
private fun quranDockText(
    playback: com.tunisianprayertimes.quran.audio.QuranPlaybackState,
    listening: com.tunisianprayertimes.quran.audio.QuranListening,
    action: QuranPlayAction,
    selection: QuranVerseReference?,
    pageStart: QuranVerseReference?,
    sessionOnPage: Boolean,
    reciterName: String,
    reciterShort: String,
    surahName: (Int) -> String,
    catalog: QuranCatalog?,
): QuranDockText {
    val surah = playback.surah
    val verse = (playback.ayah ?: playback.gapAfter)?.takeIf { it > 0 }
    if (surah == null) {
        return if (selection != null) QuranDockText("استمع من الآية ${selection.ayah}", "${surahName(selection.surah)} · $reciterShort")
        else QuranDockText("استمع من هذه الصفحة", pageStart?.let { "تبدأ من ${surahName(it.surah)} ${it.ayah}" } ?: reciterName)
    }
    val here = "${surahName(surah)} ${verse ?: ""}".trim()
    if (!playback.playing) {
        if (action is QuranPlayAction.PlayFrom) {
            val title = if (selection != null && action.verse == selection) "استمع من الآية ${selection.ayah}"
                else "ابدأ من ${surahName(action.verse.surah)} ${action.verse.ayah}"
            return QuranDockText(title, "", link = "أو تابع $here")
        }
        playback.interruption?.let {
            return QuranDockText(
                if (it == QuranInterruption.Alarm) "توقفت التلاوة للمنبّه" else "توقفت التلاوة لصوت آخر",
                "${surahName(surah)} · ${verse?.let { v -> "الآية $v" } ?: "التلاوة"}",
            )
        }
        // A drill or a listening recited to its end: over, play goes on after it; «أعده» recites it again.
        playback.finishedPlaylist?.let { list ->
            return QuranDockText("${com.tunisianprayertimes.quran.audio.quranPlaylistEnded(list.title)} · $here", "اضغط تشغيل للمتابعة", link = "أعده")
        }
        if (playback.restored) {
            val link = if (!sessionOnPage && pageStart != null) "أو من هذه الصفحة" else null
            return QuranDockText(
                "${surahName(surah)} · ${verse?.let { "الآية $it" } ?: "البداية"}",
                if (link != null) "تابع الاستماع" else listOfNotNull("تابع الاستماع", reciterShort, quranSavedWhen(playback.savedAtMs)).joinToString(" · "),
                link = link,
            )
        }
    }
    playback.playlist?.let { list ->
        val round = if (list.rounds > 1) " · المرة ${playback.playlistRound} من ${list.rounds}" else ""
        val line = playback.gapAfter?.let { hifzGapLine(list, playback.playlistIndex, it) } ?: "${list.labels.getOrNull(playback.playlistIndex).orEmpty()} · $reciterShort"
        return QuranDockText("${list.title} · ${playback.playlistIndex + 1} من ${list.items.size}", line + round)
    }
    val title = "${surahName(surah)} · " + when {
        verse != null -> "الآية $verse"
        playback.positionMs < playback.introEndMs -> "قبل الآية 1"
        else -> "التلاوة"
    }
    val range = playback.repeat
    val count = catalog?.surahs?.getOrNull(surah - 1)?.verseCount
    val sleep = listening.sleep
    val subtitle = when {
        playback.gapAfter != null -> "دورك: ردّد الآية ${playback.gapAfter}"
        range != null -> {
            // The title already names the verse; a passage names its bounds.
            if (range.from == range.to) {
                if (range.times == null) "تكرار · بلا توقف" else "تكرار · المرة ${playback.repeatRound} من ${range.times}"
            } else {
                val span = quranRangeShort(range.from, range.to)
                if (range.times == null) "تكرار $span · بلا توقف" else "تكرار $span · ${playback.repeatRound} من ${range.times}"
            }
        }
        playback.verseRound > 0 -> if (listening.verseTimes == 0) "المرة ${playback.verseRound} · بلا توقف" else "المرة ${playback.verseRound} من ${listening.verseTimes}"
        sleep != null -> when (sleep.kind) {
            QuranSleep.Kind.Minutes -> "يتوقف بعد ${quranMinutesLabel(((sleep.untilElapsedMs - android.os.SystemClock.elapsedRealtime()) / 60_000L + 1).coerceAtLeast(1).toInt())}"
            QuranSleep.Kind.Verse -> "يتوقف بعد الآية ${sleep.verse?.ayah}"
            QuranSleep.Kind.Page -> "يتوقف في نهاية الصفحة"
            QuranSleep.Kind.Surah -> "يتوقف في نهاية السورة"
        }
        listening.continueAfterSurah && verse != null && count != null && verse >= count - 1 && surah < 114 ->
            "بعدها: سورة ${surahName(surah + 1)}"
        else -> reciterName
    }
    return QuranDockText(title, subtitle)
}

/** «دقيقة», «دقيقتين», «4 دقائق», «12 دقيقة». */
internal fun quranMinutesLabel(minutes: Int): String = when {
    minutes == 1 -> "دقيقة"
    minutes == 2 -> "دقيقتين"
    minutes % 100 in 3..10 -> "$minutes${HifzReminder.NB}دقائق"
    else -> "$minutes${HifzReminder.NB}دقيقة"
}

/** «أمس», «اليوم», «قبل 3 أيام», by calendar days; nothing when unknown. */
private fun quranSavedWhen(savedAt: Long): String? {
    if (savedAt <= 0L) return null
    val zone = java.time.ZoneId.systemDefault()
    val days = java.time.temporal.ChronoUnit.DAYS.between(
        java.time.Instant.ofEpochMilli(savedAt).atZone(zone).toLocalDate(), java.time.LocalDate.now(zone),
    ).toInt()
    return when {
        days <= 0 -> "اليوم"
        days == 1 -> "أمس"
        days == 2 -> "قبل يومين"
        days in 3..10 -> "قبل $days أيام"
        else -> "قبل $days يوما"
    }
}

/** A recitation that cannot play yet: its download, a mobile-data question, or an error. */
private fun quranDockNotice(
    playback: com.tunisianprayertimes.quran.audio.QuranPlaybackState,
    media: QuranMedia?,
    pending: List<com.tunisianprayertimes.quran.assets.QuranPack>,
    download: com.tunisianprayertimes.quran.assets.QuranPackState?,
    reciterShort: String,
    surahName: (Int) -> String,
): QuranDockNotice? {
    if (download != null && media != null) {
        val size = pending.sumOf { it.archive.bytes }
        val progress = media.progress(pending) ?: 0f
        return when (download.status) {
            QuranPackStatus.Failed -> QuranDockNotice(quranDownloadProblemText(download.problem), "تلاوة $reciterShort · ${quranSize(size)}", primary = "إعادة المحاولة", secondary = "إلغاء")
            QuranPackStatus.WaitingForWifi, QuranPackStatus.NeedsConfirmation ->
                QuranDockNotice("لا توجد شبكة واي فاي", "تنزيل ${quranSize(size)} عبر بيانات الجوال؟", primary = "تنزيل", secondary = "ليس الآن")
            else -> QuranDockNotice(
                "جارٍ تنزيل تلاوة $reciterShort · ${quranPercent(progress)}",
                "${quranSize((size * progress).toLong())} من ${quranSize(size)} · يبدأ التشغيل بعدها",
                secondary = "إلغاء", progress = progress, busy = true,
            )
        }
    }
    val surah = playback.surah ?: return null
    if (playback.notDownloaded) {
        val packs = media?.layout?.audioPacks(playback.reciterId).orEmpty()
        val pack = media?.recitationPacks(playback.reciterId, surah..surah)?.firstOrNull()
        val where = pack?.let { "الحزمة ${packs.indexOf(it) + 1} من ${packs.size} · ${quranSize(it.archive.bytes)}" }
        return QuranDockNotice("تلاوة ${surahName(surah)} غير محمّلة", listOfNotNull(reciterShort, where).joinToString(" · "), primary = "تنزيل وتشغيل")
    }
    if (playback.error != null) return QuranDockNotice("تعذّر تشغيل التلاوة", playback.error, primary = "إعادة المحاولة")
    return null
}

/**
 * A phone on its side: fitted whole, the page would be a narrow strip between two white bands, so it fills the
 * width and scrolls. A tablet, 600dp or more across its short side, shows the page whole on its side too.
 */
internal fun quranPageFillsWidth(widthDp: Int, heightDp: Int, smallestWidthDp: Int): Boolean =
    widthDp > heightDp && smallestWidthDp < 600

/**
 * Whether a card about [rects] goes to the head of the page rather than its foot: where at the foot, its top at
 * [foot], it would hide more of them than at the head, its bottom at [head]. [frame] is the page fitted whole, its
 * top and its height (x, y); with no height known, the foot.
 */
internal fun quranCardOnHead(frame: Offset, rects: List<QuranHighlightRect>, head: Float, foot: Float, margin: Float): Boolean {
    if (frame.y <= 0f || rects.isEmpty()) return false
    val first = frame.x + rects.minOf { it.top } * frame.y
    val last = frame.x + rects.maxOf { it.bottom } * frame.y
    val hiddenAtFoot = (last + margin - foot).coerceAtLeast(0f)
    val hiddenAtHead = (head + margin - first).coerceAtLeast(0f)
    return hiddenAtFoot > hiddenAtHead
}

/**
 * How far to move a page (down when positive) for the verse running from [first] to [last] on screen to be seen
 * between [top] and [bottom]. A verse that begins above comes down to the top. Otherwise a verse the reader
 * [chosen] moves up just enough to clear what floats over the page, never past its first line, and any other
 * verse out of view comes to the top, the passage after it below it.
 */
internal fun quranRevealScroll(first: Float, last: Float, top: Float, bottom: Float, chosen: Boolean): Float = when {
    first < top -> top - first
    chosen -> -(last - bottom).coerceAtLeast(0f).coerceAtMost(first - top)
    last <= bottom -> 0f
    else -> top - first
}

@Composable
private fun QuranPageImage(
    page: QuranPage,
    active: Boolean,
    /** The pager rests on this page. Neither this nor [active], the page is off the screen. */
    settled: Boolean = active,
    /** False while the pages are still to be downloaded; the page decodes again once they arrive. */
    pagesReady: Boolean,
    highlightRects: List<QuranHighlightRect>,
    /** The verses a search result found; where the page fills the width, scrolled to once a visit. */
    foundRects: List<QuranHighlightRect>,
    /** Verses with a hifz point: a light violet tint and a dashed outline. */
    hifzRects: List<QuranHighlightRect>,
    /**
     * «مراجعة»: verses covered by an opaque colour, with their numbers left visible through [numberHoles].
     * The finger erases the cover line by line: see [erasing].
     */
    hiddenRects: List<QuranHighlightRect> = emptyList(),
    /** «مراجعة»: where each verse's number shows through the cover; a tap on one opens that verse. */
    numberHoles: Map<QuranVerseReference, QuranHighlightRect> = emptyMap(),
    /** The day's review: the verse asked, among [hiddenRects], covered in the points' colour with their dashed outline. */
    askedRects: List<QuranHighlightRect> = emptyList(),
    selectedRects: List<QuranHighlightRect>,
    /**
     * The page fills the width of the view and scrolls up and down in it, from its top: see [quranPageFillsWidth].
     * Otherwise it is fitted whole.
     */
    fillWidth: Boolean = false,
    /**
     * Kept in view above [bottomInsetPx] by panning the page up, never by scaling it; by scrolling it where it
     * fills the width.
     */
    keepVisible: List<QuranHighlightRect>,
    /**
     * Fitted whole, with [keepVisible]: the page rests with its top under the header, as the review's card covers its
     * foot, rather than in the middle with paper above it and its last lines under the card.
     */
    restAtTop: Boolean = false,
    /**
     * Where the page fills the width: the verse recited, scrolled to the top of the view when it is out of it, once
     * each time it changes. [keepVisible] comes first, and [foundRects] after.
     */
    bringIntoView: List<QuranHighlightRect> = emptyList(),
    bottomInsetPx: () -> Int,
    /** What comes over the top of the page, the header: what is kept or brought into view stays below it. */
    topInsetPx: () -> Int = { 0 },
    /**
     * Fitted whole, the most that can come over the page's head and its foot, shown or not: the page rests clear of
     * them, and showing them never moves it (see restRaise).
     */
    headRoomPx: () -> Int = { 0 },
    footRoomPx: () -> Int = { 0 },
    /**
     * The page at rest, fitted whole and unzoomed, while it is the active one: its top and its height in this view (x,
     * y), or zero height when it fills the width, is zoomed or not drawn yet.
     */
    onFrame: (Offset) -> Unit = {},
    /** The verse under a point of the original scan, each coordinate from 0 to 1. */
    verseAt: (x: Float, y: Float) -> QuranVerseReference?,
    /** Where a verse lies on this page, to show it pressed under the finger. */
    rectsOf: (QuranVerseReference) -> List<QuranHighlightRect>,
    /**
     * «مراجعة»: a tap erases the cover of the line under it, a finger wide. A drag or long press starting on
     * covered text erases all the finger crosses, line by line; swipes starting off the cover navigate.
     * A tap on a verse's number still reaches [onTap] with that verse, and a tap off the text as paper (null).
     */
    erasing: Boolean = false,
    /**
     * The text line at a point of the original scan, as a verse rectangle on it, grown as covers are drawn (halfway into
     * the gaps, see QuranHighlights.covers): its top and bottom bound the line and its marks.
     */
    lineAt: (x: Float, y: Float) -> QuranHighlightRect? = { _, _ -> null },
    onErased: (QuranErased) -> Unit = {},
    /** A tap, or out of «مراجعة» a long press, at its release: on a verse, or on paper (null). */
    onTap: (QuranVerseReference?) -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    /** The scan is on screen, or could not be read. */
    onSettled: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    // The gesture detector outlives recompositions; it must call the newest callback.
    val currentOnTap by rememberUpdatedState(onTap)
    val currentErasing by rememberUpdatedState(erasing)
    val currentLineAt by rememberUpdatedState(lineAt)
    val currentOnErased by rememberUpdatedState(onErased)
    val currentNumberHoles by rememberUpdatedState(numberHoles)
    val currentHiddenRects by rememberUpdatedState(hiddenRects)
    val currentVerseAt by rememberUpdatedState(verseAt)
    val currentOnSettled by rememberUpdatedState(onSettled)
    var bitmap by remember(page.assetPath) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(page.assetPath) { mutableStateOf(false) }
    var missing by remember(page.assetPath) { mutableStateOf(false) }
    var attempt by remember(page.assetPath) { mutableIntStateOf(0) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(page.number) { mutableFloatStateOf(1f) }
    var offset by remember(page.number) { mutableStateOf(Offset.Zero) }
    var pressedVerse by remember(page.number) { mutableStateOf<QuranVerseReference?>(null) }
    // «مراجعة»: what the finger erased, and the piece under it while it rests there, which waits for it to leave.
    // Read only while drawing: erasing redraws the page without recomposing anything.
    val erased = remember(page.number) { mutableStateListOf<QuranErased>() }
    var erasingUnder by remember(page.number) { mutableStateOf<QuranErased?>(null) }
    val eraseClock = remember { mutableLongStateOf(0L) }
    val currentPagesReady by rememberUpdatedState(pagesReady)
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
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
    // The gesture detectors outlive recompositions: they must see the page turned on its side.
    val fillsWidth by rememberUpdatedState(fillWidth)
    val currentBottomInsetPx by rememberUpdatedState(bottomInsetPx)
    LaunchedEffect(scale, active) { if (active) onZoomChanged(scale > 1.01f) }
    /** Fitted whole, the height the page keeps clear of what can come over its head and its foot. */
    fun roomHeight(): Int = if (fillsWidth) size.height
        else (size.height - headRoomPx() - footRoomPx()).coerceAtLeast(size.height / 2)
    fun fit(image: ImageBitmap): Float {
        val byWidth = size.width.toFloat() / image.width
        return if (fillsWidth) byWidth else minOf(byWidth, roomHeight().toFloat() / image.height)
    }
    /**
     * Fitted whole, how far above the middle of the view the page rests: just enough for what can come over its foot
     * never to cover it, nor the header its head. It does not depend on what is shown, so that showing it never moves
     * the page.
     */
    fun restRaise(image: ImageBitmap): Float {
        if (fillsWidth || size.height == 0) return 0f
        val height = image.height * fit(image)
        val middle = (size.height - height) / 2f
        val highest = headRoomPx().toFloat()
        val lowest = (size.height - footRoomPx() - height).coerceAtLeast(highest)
        return middle - middle.coerceIn(highest, lowest)
    }
    val raise = bitmap?.let { restRaise(it) } ?: 0f
    // The cards over the page choose its foot or its head from where it lies: said as that changes, by the page in front.
    val currentOnFrame by rememberUpdatedState(onFrame)
    LaunchedEffect(active, fillWidth, bitmap, size, scale > 1.01f, raise) {
        if (!active) return@LaunchedEffect
        val image = bitmap
        currentOnFrame(
            if (image == null || fillWidth || scale > 1.01f || size.height == 0) Offset.Zero
            else (image.height * fit(image)).let { height -> Offset((size.height - height) / 2f - raise, height) },
        )
    }
    /**
     * How far below its centre the page rests: a page taller than the view rests with its top on the top of the
     * view. [offset] is counted from there, so that no offset is the page at rest.
     */
    fun restY(): Float {
        val image = bitmap ?: return 0f
        return ((image.height * fit(image) - size.height) / 2f).coerceAtLeast(0f)
    }
    fun boundedOffset(value: Offset, zoom: Float): Offset {
        val image = bitmap ?: return Offset.Zero
        val f = fit(image)
        val maxX = ((image.width * f * zoom - size.width) / 2f).coerceAtLeast(0f)
        val maxY = ((image.height * f * zoom - size.height) / 2f).coerceAtLeast(0f)
        val rest = restY()
        return Offset(value.x.coerceIn(-maxX, maxX), (value.y + rest).coerceIn(-maxY, maxY) - rest)
    }
    /** Zooms to [zoom] about the middle of the view, which keeps what is there, then moves the page by [pan]. */
    fun zoomTo(zoom: Float, pan: Offset = Offset.Zero) {
        val rest = restY()
        val ratio = zoom / scale
        offset = boundedOffset(Offset(offset.x * ratio, (offset.y + rest) * ratio - rest) + pan, zoom)
        scale = zoom
    }
    BackHandler(enabled = active && scale > 1f) { zoomTo(1f) }
    /** A page that fills the width: how far it is lifted past its end, as one that fits is lifted, for a verse at its foot. */
    var endLift by remember(page.number) { mutableFloatStateOf(0f) }
    // Pans the page up, from where it rests, just enough for [keepVisible] to clear what floats over the bottom of the
    // page. A page that fills the width scrolls instead, below, and is lifted only where its end stops the scroll.
    val targetLift = run {
        val image = bitmap
        if (image == null || scale > 1.01f || size.height == 0) 0f
        else if (fillWidth) endLift
        else if (keepVisible.isEmpty()) 0f
        else {
            val f = fit(image)
            val height = image.height * f
            val top = (size.height - height) / 2f - raise
            val bottom = top + keepVisible.maxOf { it.bottom } * height
            val first = top + keepVisible.minOf { it.top } * height
            val margin = with(density) { 16.dp.toPx() }
            val limit = size.height - bottomInsetPx() - margin
            val needed = (-quranRevealScroll(first, bottom, topInsetPx() + margin, limit, chosen = true)).coerceAtLeast(0f)
            val toTop = if (restAtTop) (top - topInsetPx()).coerceAtLeast(0f) else 0f
            var by = maxOf(needed, toTop)
            // Lifted further for a verse low on the page, the view's top edge falls between two lines, never across one,
            // and never past the first line kept in view.
            if (by > toTop) {
                val edge = (topInsetPx() - (top - by)) / height
                lineAt(0.5f, edge)?.takeIf { it.top < edge && it.bottom > edge }?.let { line ->
                    by = minOf(by + (line.bottom - edge) * height, maxOf(by, first - topInsetPx() - margin))
                }
            }
            by
        }
    }
    // Moves with the panel coming in or going; the rest raise does not move, and is read by the gestures as it is now.
    val lift by animateFloatAsState(targetLift, tween(QuranCardMillis, easing = FastOutSlowInEasing), label = "quranPageShift")
    val restLift by rememberUpdatedState(raise)
    val scroll = rememberScrollableState { delta ->
        val before = offset.y
        offset = boundedOffset(Offset(offset.x, before + delta), scale)
        offset.y - before
    }
    val currentFling by rememberUpdatedState(ScrollableDefaults.flingBehavior())
    val scope = rememberCoroutineScope()
    /** Whether the touch under way stopped the page moving: then it is no tap. */
    val stoppedScroll = remember { booleanArrayOf(false) }
    /** The page still moves from a finger's fling: a touch stops it, and a verse asked for interrupts it. */
    val flinging = remember { booleanArrayOf(false) }
    // A page that fills the width scrolls to what must be seen. A verse chosen is kept clear of what comes over
    // the page, lifted past the page's end if need be, for as long as it is chosen. The verse recited is scrolled
    // to once each time it changes, and the found verse once a visit; then the reader's own scrolling stands, even
    // when a card that covered them closes or the recitation pauses.
    val chosen = keepVisible.isNotEmpty()
    val following = bringIntoView.isNotEmpty()
    val target = when {
        chosen -> keepVisible
        following -> bringIntoView
        fillWidth -> foundRects
        else -> emptyList()
    }
    /** The verse recited and the verse found last scrolled to on this visit to the page. */
    val shown = remember(page.number, fillWidth) { arrayOfNulls<List<QuranHighlightRect>>(2) }
    // Off the screen, the page goes back to its top, unzoomed, for its next visit: once the pager has left it, not
    // halfway through the swipe, and with a fling still going stopped first.
    LaunchedEffect(active, settled) {
        if (active || settled) return@LaunchedEffect
        scroll.stopScroll(MutatePriority.PreventUserInput)
        scale = 1f
        offset = Offset.Zero
        shown.fill(null)
    }
    LaunchedEffect(active, fillWidth, target, chosen, following, bitmap) {
        val image = bitmap
        // Played again after a pause, the recitation is followed again.
        if (!following) shown[0] = null
        if (!active || !fillWidth || image == null || target.isEmpty()) {
            endLift = 0f
            return@LaunchedEffect
        }
        val slot = if (following) 0 else 1
        if (!chosen && shown[slot] == target) { endLift = 0f; return@LaunchedEffect }
        suspend fun reveal(area: IntSize, inset: Int) {
            if (scale > 1.01f || area.height == 0) return
            // A verse asked for, chosen or found, stops a fling; the recitation followed gives way to it.
            if (flinging[0] && (chosen || !following)) scroll.stopScroll(MutatePriority.UserInput)
            val pageHeight = image.height * fit(image)
            val margin = with(density) { 16.dp.toPx() }
            val bottom = area.height - inset - margin
            // Where the page's middle is, from the middle of the view, and how far it can go either way.
            val moved = offset.y + restY()
            val reach = ((pageHeight - area.height) / 2f).coerceAtLeast(0f)
            val top = (area.height - pageHeight) / 2f + moved
            val first = top + target.minOf { it.top } * pageHeight
            val last = top + target.maxOf { it.bottom } * pageHeight
            // No further than the page goes, from its top on the top of the view to its end on the bottom.
            val by = quranRevealScroll(first, last, margin, bottom, chosen).coerceAtMost(reach - moved).coerceAtLeast(-reach - moved)
            // Only a verse chosen is lifted: the card or the player over it goes with it.
            endLift = if (chosen) (-quranRevealScroll(first + by, last + by, margin, bottom, chosen = true)).coerceAtLeast(0f) else 0f
            if (!chosen) shown[slot] = target
            if (abs(by) < 1f) return
            try {
                scroll.animateScrollBy(by, tween(QuranCardMillis, easing = FastOutSlowInEasing))
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                // A finger on the page, or its fling, has the scroll: the page stays where the reader takes it.
                endLift = 0f
            }
        }
        val view = snapshotFlow { size to currentBottomInsetPx() }.distinctUntilChanged()
        if (chosen) view.collectLatest { (area, inset) -> reveal(area, inset) }
        else view.first { (area, _) -> area.height > 0 }.let { (area, inset) -> reveal(area, inset) }
    }
    fun toScan(point: Offset): Pair<Float, Float>? {
        val image = bitmap ?: return null
        if (size.width == 0 || size.height == 0) return null
        // Undo the zoom, the pan or the scroll and the lift, then the letterboxing of the fitted scan.
        val f = fit(image)
        val x = .5f + (point.x - size.width / 2f - offset.x) / (scale * image.width * f)
        val y = .5f + (point.y - size.height / 2f - offset.y - restY() + restLift + lift) / (scale * image.height * f)
        return if (x in 0f..1f && y in 0f..1f) x to y else null
    }
    /**
     * «مراجعة»: the piece of the text line under [point], on screen, a finger wide; null off the text. On the
     * text means where a touch would choose a verse outside «مراجعة».
     */
    fun erasedAt(point: Offset, at: Long): QuranErased? {
        val image = bitmap ?: return null
        val (x, y) = toScan(point) ?: return null
        val line = currentLineAt(x, y) ?: return null
        val reach = with(density) { QuranEraserWidth.toPx() } / 2f / (scale * image.width * fit(image))
        return QuranErased(x - reach, line.top, x + reach, line.bottom, at)
    }
    /**
     * «مراجعة»: adds [piece], cut out of the older pieces it overlaps. Overlapping pieces would add up while
     * fading, and the cover would come back all at once at the end; this way each point fades on the clock of
     * the last touch over it.
     */
    fun addErased(piece: QuranErased) {
        currentOnErased(piece)
        val under = erased.filter { it.left < piece.right && piece.left < it.right && it.top < piece.bottom && piece.top < it.bottom }
        if (under.isNotEmpty()) {
            erased.removeAll(under)
            under.forEach { old ->
                if (old.left < piece.left) erased += QuranErased(old.left, old.top, piece.left, old.bottom, old.at)
                if (old.right > piece.right) erased += QuranErased(piece.right, old.top, old.right, old.bottom, old.at)
            }
        }
        erased += piece
    }
    /** «مراجعة»: erases every line the finger crossed from [from] to [to]; the piece under it waits for it to leave. */
    fun eraseAlong(from: Offset, to: Offset) {
        if (to == from) return
        val now = SystemClock.uptimeMillis()
        val step = with(density) { QuranEraserWidth.toPx() } / 4f
        val steps = ((to - from).getDistance() / step).toInt().coerceAtLeast(1)
        // Each line crossed becomes one piece, as wide as the way across it.
        (0 until steps).mapNotNull { erasedAt(from + (to - from) * (it / steps.toFloat()), now) }
            .groupBy { it.top to it.bottom }
            .forEach { (line, pieces) -> addErased(QuranErased(pieces.minOf { it.left }, line.first, pieces.maxOf { it.right }, line.second, now)) }
        erasingUnder = erasedAt(to, now)
    }
    /** «مراجعة»: the finger lifted; the piece it rested on starts its time now. */
    fun releaseEraser() {
        erasingUnder?.let { addErased(QuranErased(it.left, it.top, it.right, it.bottom, SystemClock.uptimeMillis())) }
        erasingUnder = null
    }
    fun numberAt(point: Offset): QuranVerseReference? = toScan(point)?.let { (x, y) ->
        currentNumberHoles.entries.firstOrNull { (_, hole) ->
            x in hole.left - QuranNumberTouchSlop..hole.right + QuranNumberTouchSlop &&
                y in hole.top - QuranNumberTouchSlop..hole.bottom + QuranNumberTouchSlop
        }?.key
    }
    fun tap(point: Offset) {
        pressedVerse = null
        if (stoppedScroll[0]) return
        if (currentErasing) {
            // «مراجعة»: a tap on a verse's number opens that verse; on the text it erases there, and only
            // there; off the text it is a tap on paper.
            val numbered = numberAt(point)
            val piece = if (numbered == null) erasedAt(point, SystemClock.uptimeMillis()) else null
            if (piece != null) addErased(piece) else currentOnTap(numbered)
            return
        }
        currentOnTap(toScan(point)?.let { (x, y) -> currentVerseAt(x, y) })
    }
    // «مراجعة»: erased pieces fade back, each on its own clock, in real time.
    LaunchedEffect(erased) {
        snapshotFlow { erased.isNotEmpty() }.collect {
            while (erased.isNotEmpty()) {
                withFrameMillis { }
                val now = SystemClock.uptimeMillis()
                eraseClock.longValue = now
                erased.removeAll { now - it.at >= QuranErasedMs + QuranEraseFadeMs }
            }
        }
    }
    LaunchedEffect(erasing) {
        if (!erasing) { erased.clear(); erasingUnder = null }
    }
    val transform = rememberTransformableState { zoom, pan, _ -> zoomTo((scale * zoom).coerceIn(1f, 4f), pan) }
    Box(
        Modifier.fillMaxSize().background(Color.White).clip(RoundedCornerShape(2.dp))
            .onSizeChanged { size = it; offset = boundedOffset(offset, scale) }
            .semantics(mergeDescendants = true) {
                val zoomOut = if (fillWidth) "تصغير الصفحة" else "عرض الصفحة كاملة"
                customActions = listOf(CustomAccessibilityAction(if (scale > 1f) zoomOut else "تكبير الصفحة") {
                    zoomTo(if (scale > 1f) 1f else 2.5f)
                    true
                })
            }
            // A page that fills the width: one finger dragged up or down scrolls it, and flings it. Placed outside
            // the zoom, which sees each move first, it leaves a pinch to the zoom as soon as a second finger is down,
            // and a swipe to the pages once they take it; once it scrolls it keeps every move until the last finger
            // lifts, so neither can take the scroll over.
            .pointerInput(page.number) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // A touch stops a page still flung, and is no tap then. A page scrolling by itself to a verse
                    // goes on, and the touch is a tap as any other; a drag would take the scroll from it.
                    stoppedScroll[0] = flinging[0]
                    if (stoppedScroll[0]) scope.launch { scroll.stopScroll(MutatePriority.UserInput) }
                    awaitPointerEvent(PointerEventPass.Final)
                    if (!fillsWidth || scale > 1.01f) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    var tracker = VelocityTracker().apply { addPointerInputChange(down) }
                    var moved = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed || change.isConsumed || event.changes.count { it.pressed } > 1) {
                            return@awaitEachGesture
                        }
                        tracker.addPointerInputChange(change)
                        moved += change.positionChangeIgnoreConsumed().y
                        if (abs(moved) > slop) { change.consume(); break }
                        // The pages take a swipe after this, in the same pass.
                        if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) return@awaitEachGesture
                    }
                    // The finger holds the page's scroll until it lifts, then its fling does: a verse to scroll to
                    // meanwhile gives way instead of moving the page under the finger.
                    endLift = 0f
                    val drag = Channel<Float>(Channel.UNLIMITED)
                    val release = CompletableDeferred<Float>()
                    scope.launch {
                        scroll.scroll(MutatePriority.UserInput) {
                            for (delta in drag) scrollBy(delta)
                            val velocity = release.await()
                            flinging[0] = true
                            try {
                                with(currentFling) { performFling(velocity) }
                            } finally {
                                flinging[0] = false
                            }
                        }
                    }
                    drag.trySend(moved - slop * sign(moved))
                    var pointer = down.id
                    var velocity = 0f
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            val change = event.changes.firstOrNull { it.id == pointer } ?: break
                            tracker.addPointerInputChange(change)
                            if (change.pressed) {
                                drag.trySend(change.positionChangeIgnoreConsumed().y)
                                continue
                            }
                            // Another finger still on the page carries the scroll on.
                            val other = event.changes.firstOrNull { it.pressed }
                            if (other == null) {
                                val most = viewConfiguration.maximumFlingVelocity
                                velocity = tracker.calculateVelocity(Velocity(most, most)).y
                                break
                            }
                            pointer = other.id
                            tracker = VelocityTracker().apply { addPointerInputChange(other) }
                        }
                    } finally {
                        drag.close()
                        release.complete(velocity)
                    }
                }
            }
            .transformable(transform, canPan = { scale > 1f })
            // Pinching zooms, as in a photo; a double tap is two taps, so that a tap acts at its release.
            .pointerInput(page.number) {
                detectTapGestures(
                    onPress = { point ->
                        // The verse shows as pressed under the finger, until the tap acts or the touch becomes a swipe.
                        // «مراجعة» shows nothing while pressed: the eraser acts at the release.
                        if (currentErasing || stoppedScroll[0]) return@detectTapGestures
                        pressedVerse = toScan(point)?.let { (x, y) -> currentVerseAt(x, y) }
                        if (!tryAwaitRelease()) pressedVerse = null
                    },
                    onTap = ::tap,
                )
            }
            // Innermost so a drag starting on covered text claims its first move past slop before the pager,
            // scroll or zoom can take it. Margins, visible text and verse numbers leave navigation available;
            // a second finger before activation leaves the gesture to pinch zoom.
            .pointerInput(page.number) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentErasing || stoppedScroll[0] || numberAt(down.position) != null) return@awaitEachGesture
                    val (x, y) = toScan(down.position) ?: return@awaitEachGesture
                    if (currentHiddenRects.none { x in it.left..it.right && y in it.top..it.bottom }) return@awaitEachGesture
                    if (currentEvent.changes.count { it.pressed } != 1) return@awaitEachGesture
                    var pressed = down
                    var cancelled = false
                    // Keep tap-to-peek and hold-to-peek, but do not require a hold before rubbing the cover.
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed || event.changes.any { it.isConsumed } ||
                                event.changes.count { it.pressed } != 1 || !currentErasing) {
                                cancelled = true
                                break
                            }
                            pressed = change
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                            if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) {
                                cancelled = true
                                break
                            }
                        }
                    }
                    if (cancelled || !currentErasing || currentEvent.changes.count { it.pressed } != 1) return@awaitEachGesture
                    val firstPiece = erasedAt(down.position, 0L) ?: return@awaitEachGesture
                    erasingUnder = firstPiece
                    currentEvent.changes.forEach { it.consume() }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentOnErased(firstPiece)
                    eraseAlong(down.position, pressed.position)
                    // Nothing else acts on what is left of this gesture, not even another finger.
                    var finger: Offset? = pressed.position
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                            val from = finger ?: continue
                            val change = event.changes.firstOrNull { it.id == pressed.id }
                            if (change != null && change.pressed) {
                                eraseAlong(from, change.position)
                                finger = change.position
                            } else {
                                releaseEraser()
                                finger = null
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        if (finger != null) releaseEraser()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        when {
            loaded != null -> Box(
                Modifier
                    .layout { measurable, constraints ->
                        // Filling the width, the page is taller than the view: laid out whole about its middle,
                        // the view shows the part [offset] brings into it.
                        val width = constraints.maxWidth
                        // Fitted whole, the page keeps clear of what can come over its head and its foot (see restRaise).
                        val height = if (!fillWidth) (constraints.maxHeight - headRoomPx() - footRoomPx()).coerceAtLeast(constraints.maxHeight / 2)
                            else maxOf(constraints.maxHeight, ceil(width * loaded.height / loaded.width.toFloat()).toInt())
                        val placeable = measurable.measure(Constraints.fixed(width, height))
                        layout(width, constraints.maxHeight) { placeable.place(0, (constraints.maxHeight - height) / 2) }
                    }
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y + restY() - restLift - lift
                    },
            ) {
                Image(
                    bitmap = loaded,
                    contentDescription = "${quranPageLabel(page.number)} ${page.surahNames.joinToString("، ")}",
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                )
                val pressedRects = pressedVerse?.let(rectsOf).orEmpty()
                Canvas(Modifier.fillMaxSize()) {
                    val f = minOf(this.size.width / loaded.width, this.size.height / loaded.height)
                    val imageWidth = loaded.width * f
                    val imageHeight = loaded.height * f
                    val origin = Offset((this.size.width - imageWidth) / 2f, (this.size.height - imageHeight) / 2f)
                    fun tint(rects: List<QuranHighlightRect>, color: Color) = rects.forEach { rect ->
                        drawRoundRect(
                            color = color,
                            topLeft = origin + Offset(rect.left * imageWidth, rect.top * imageHeight),
                            size = Size((rect.right - rect.left) * imageWidth, (rect.bottom - rect.top) * imageHeight),
                            cornerRadius = CornerRadius(3.dp.toPx()), blendMode = BlendMode.Multiply,
                        )
                    }
                    // «مراجعة»: the cover, and the page painted back over it where the finger erased it.
                    if (hiddenRects.isNotEmpty()) {
                        val holes = Path().apply {
                            numberHoles.values.forEach { rect ->
                                addOval(androidx.compose.ui.geometry.Rect(
                                    origin + Offset(rect.left * imageWidth, rect.top * imageHeight),
                                    Size((rect.right - rect.left) * imageWidth, (rect.bottom - rect.top) * imageHeight),
                                ))
                            }
                        }
                        // Each rectangle filled through a clip, which snaps to whole pixels as the erased pieces do: a
                        // smoothed edge would leave a faint line of cover along the top or bottom of a piece.
                        clipPath(holes, ClipOp.Difference) {
                            hiddenRects.forEach { rect ->
                                clipRect(
                                    origin.x + rect.left * imageWidth, origin.y + rect.top * imageHeight,
                                    origin.x + rect.right * imageWidth, origin.y + rect.bottom * imageHeight,
                                ) { drawRect(if (rect in askedRects) QuranAskedMask else QuranReviewMask) }
                            }
                        }
                        // The scan where the Image draws it, rounded as it rounds, so that it lines up to the pixel. Its
                        // pieces are clipped to rectangles, which are not smoothed: pieces that meet leave no seam.
                        val scanSize = IntSize(imageWidth.roundToInt(), imageHeight.roundToInt())
                        val scanAt = IntOffset(
                            ((this.size.width.roundToInt() - scanSize.width) / 2f).roundToInt(),
                            ((this.size.height.roundToInt() - scanSize.height) / 2f).roundToInt(),
                        )
                        fun uncover(piece: QuranErased, amount: Float) = clipRect(
                            origin.x + piece.left * imageWidth, origin.y + piece.top * imageHeight,
                            origin.x + piece.right * imageWidth, origin.y + piece.bottom * imageHeight,
                        ) { drawImage(loaded, dstOffset = scanAt, dstSize = scanSize, alpha = amount) }
                        // Clear until its time is up, then fading back; the piece under the finger waits for it to leave.
                        val now = eraseClock.longValue
                        erased.forEach { piece ->
                            val amount = 1f - ((now - piece.at - QuranErasedMs).toFloat() / QuranEraseFadeMs).coerceIn(0f, 1f)
                            if (amount > 0f) uncover(piece, amount)
                        }
                        erasingUnder?.let { uncover(it, 1f) }
                        // The verse asked: its cover outlined by a fine solid line, a cover, not a selection.
                        askedRects.forEach { rect ->
                            drawRoundRect(
                                QuranHifzViolet.copy(alpha = 0.8f), origin + Offset(rect.left * imageWidth, rect.top * imageHeight),
                                Size((rect.right - rect.left) * imageWidth, (rect.bottom - rect.top) * imageHeight), CornerRadius(3.dp.toPx()),
                                style = Stroke(1.dp.toPx()),
                            )
                        }
                    }
                    // Only the found, recited and pressed verses are coloured; the rest of the page stays white.
                    // A point's marker is a shape as well as a colour: a light tint and a line under each of its lines,
                    // as a reader underlines, never a frame that reads as a selection. A pressed verse shows only as pressed.
                    hifzRects.filterNot { it in pressedRects }.forEach { rect ->
                        val topLeft = origin + Offset(rect.left * imageWidth, rect.top * imageHeight)
                        val box = Size((rect.right - rect.left) * imageWidth, (rect.bottom - rect.top) * imageHeight)
                        drawRoundRect(QuranHifzViolet.copy(alpha = 0.16f), topLeft, box, CornerRadius(3.dp.toPx()), blendMode = BlendMode.Multiply)
                        val stroke = 2.dp.toPx()
                        val inset = 3.dp.toPx().coerceAtMost(box.width / 4f)
                        val y = topLeft.y + box.height - stroke / 2f
                        drawLine(QuranHifzViolet, Offset(topLeft.x + inset, y), Offset(topLeft.x + box.width - inset, y), stroke, cap = StrokeCap.Round)
                    }
                    tint(foundRects, QuranFoundColor)
                    tint(highlightRects, Color(0x5266BB6A))
                    tint((selectedRects + pressedRects).distinct(), Color(0x5200695C))
                }
            }
            failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("تعذّر عرض الصفحة", color = TextMuted)
                TextButton(onClick = { attempt++ }) { Text("إعادة المحاولة") }
            }
            missing -> Unit
            else -> CircularProgressIndicator()
        }
    }
}

/**
 * Keeps [content] built and measured while hidden, but not placed: it is neither drawn nor touched and
 * takes no room. Showing it only places it, sliding up and fading in with the page that makes room.
 */
@Composable
private fun QuranCardSlot(shown: Boolean, content: @Composable () -> Unit) {
    val appear = remember { Animatable(if (shown) 1f else 0f) }
    LaunchedEffect(shown) {
        if (shown) appear.animateTo(1f, tween(QuranCardMillis, easing = FastOutSlowInEasing)) else appear.snapTo(0f)
    }
    Box(
        // Hidden, it keeps no keyboard focus: a key would press a button nobody sees.
        Modifier.focusProperties { canFocus = shown }.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            if (!shown) layout(placeable.width, 0) {}
            else layout(placeable.width, placeable.height) {
                placeable.placeWithLayer(0, 0) {
                    alpha = appear.value
                    translationY = (1f - appear.value) * placeable.height / 3f
                }
            }
        },
    ) { content() }
}

internal fun quranNumber(value: Int): String = value.toString()

internal fun quranInputNumber(value: String): Int? = value.trim()
    .map { c -> c.digitToIntOrNull()?.digitToChar() ?: c }.joinToString("").toIntOrNull()

internal fun quranPageLabel(scan: Int): String = if (scan <= QuranScanCount) "صفحة ${quranShortPageLabel(scan)}"
    else quranShortPageLabel(scan)

/** The printed page's number alone, «3» on scan 2; an appendix page keeps its word. */
internal fun quranShortPageLabel(scan: Int): String = if (scan <= QuranScanCount) quranNumber(scan + 1)
    else "الملحق ${quranNumber(scan - QuranScanCount)}"

@Composable
internal fun QuranPanelHeader(title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
        IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_adhkar_close), "إغلاق") }
    }
}
