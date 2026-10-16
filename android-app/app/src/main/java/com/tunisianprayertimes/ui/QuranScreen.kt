package com.tunisianprayertimes.ui

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
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
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
import com.tunisianprayertimes.quran.hifz.HifzGrade
import com.tunisianprayertimes.quran.hifz.HifzLearning
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzLookAlikes
import com.tunisianprayertimes.quran.hifz.HifzLookAlikesCache
import com.tunisianprayertimes.quran.hifz.hifzWords
import com.tunisianprayertimes.quran.hifz.HifzPoint
import com.tunisianprayertimes.quran.hifz.HifzReminder
import com.tunisianprayertimes.quran.hifz.HifzRepeat
import com.tunisianprayertimes.quran.hifz.epochDay
import com.tunisianprayertimes.quran.hifz.HifzRepository
import com.tunisianprayertimes.quran.hifz.HifzSettings
import com.tunisianprayertimes.quran.hifz.dueToday
import com.tunisianprayertimes.quran.hifz.reviewBatch
import com.tunisianprayertimes.quran.hifz.sameSlip
import com.tunisianprayertimes.quran.hifz.graded
import com.tunisianprayertimes.quran.hifz.hifzCue
import com.tunisianprayertimes.quran.hifz.hifzDrill
import com.tunisianprayertimes.quran.hifz.hifzFix
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
/** A verse recited faster than this leaves no time to read a heads-up: it comes a verse earlier. */
private const val QuranShortVerseMs = 6_000L
/** «قبل التسميع» offered for a hizb is noted by day under this plus its number, apart from the chapters' notes. */
private const val BriefedHizbKey = 1000
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
/** «مراجعة»: an erased piece stays clear this long after the finger left it, then fades back over [QuranEraseFadeMs]. */
private const val QuranErasedMs = 3_400L
private const val QuranEraseFadeMs = 600L
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
    val selection = remember(selectionDraft) { decodeVerse(selectionDraft) }
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
    var keepAwake by remember { mutableStateOf(prefs.getBoolean("keep_awake", true)) }
    /** «مراجعة»: every verse is covered; the finger erases the cover where it touches, and it comes back by itself. */
    var reviewMode by rememberSaveable { mutableStateOf(prefs.getBoolean("review_mode", false)) }
    var nightOn by remember { mutableStateOf(prefs.getBoolean("night_on", false)) }
    var nightLevel by remember { mutableFloatStateOf(prefs.getFloat("night_level", 0.3f)) }
    // Read only by the keep-awake effect, outside composition: a touch must not recompose the reader.
    val lastTouch = remember { mutableLongStateOf(System.currentTimeMillis()) }
    var overlayHeightPx by remember { mutableIntStateOf(0) }
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
    val reviewOnPage = panel == "hifzReview" && catalog != null && highlights != null && (media?.pagesReady ?: true) &&
        !pageFillsWidth && !touchExploration && density.fontScale <= 1.3f
    // The review on the page has the whole screen: no header, no player, no bottom bar.
    val focus = ((zoomed || focusChosen) && panel == null) || reviewOnPage
    val hifzPoints by HifzRepository.points.collectAsStateWithLifecycle()
    var hifzSettings by remember { mutableStateOf(HifzSettings.read(context)) }
    val pointsByVerse = remember(hifzPoints) { hifzPoints.groupBy { it.verse } }
    var lookAlikes by remember { mutableStateOf<HifzLookAlikes?>(HifzLookAlikesCache.loaded) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val today by produceState(todayEpochDay(), resumes) {
        while (true) {
            value = todayEpochDay()
            delay(60_000L)
        }
    }
    var learning by remember { mutableStateOf(HifzLearning.read(context, todayEpochDay())) }
    LaunchedEffect(resumes) { learning = HifzLearning.read(context, todayEpochDay()) }
    fun updateLearning(change: (HifzLearning) -> HifzLearning) {
        val next = change(learning.onDay(todayEpochDay()))
        if (next != learning) {
            learning = next.write(context)
        }
    }
    LaunchedEffect(today, resumes, hifzPoints) {
        if (HifzRepository.isLoaded) updateLearning { it.retain(HifzRepository.points.value.map { p -> p.id }.toSet()) }
    }
    val eligibleDueIds = hifzPoints.filter { it.isDue(today) && it.id !in learning.onDay(today).practised }.map { it.id }.toSet()
    var passageDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val passage = remember(passageDraft) { HifzPassage.decode(passageDraft) }
    var passagePriorMask by rememberSaveable { mutableStateOf(false) }
    var passageRecorded by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    var passageSummary by rememberSaveable { mutableStateOf(false) }
    var passageFinished by rememberSaveable { mutableStateOf(false) }
    /** The verse whose heads-up the reader closed, so it stays closed for this pass. */
    var headsUpClosedDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val headsUpClosed = remember(headsUpClosedDraft) { decodeVerse(headsUpClosedDraft) }
    /** The verse after which a stop was set for a point; the stop is taken back once the recitation leaves it. */
    var armedDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val armedBefore = remember(armedDraft) { decodeVerse(armedDraft) }
    /** «أوقف قبلها»: the verse to recite from memory once the one before it ends. */
    var recitePromptDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val recitePrompt = remember(recitePromptDraft) { decodeVerse(recitePromptDraft) }
    /** «أكمل» at the turn: the point's verse is heard now, against what the reader recited. */
    var turnHeardDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val turnHeard = remember(turnHeardDraft) { decodeVerse(turnHeardDraft) }
    /** Once it is heard: «هل قرأت الآية 47 صحيحة؟». */
    var turnResultDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val turnResult = remember(turnResultDraft) { decodeVerse(turnResultDraft) }
    /** The day's review, fixed when it opens so grading does not reorder it. */
    var reviewIds by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    /** Each review opened starts over from its first point. */
    var reviewSession by rememberSaveable { mutableIntStateOf(0) }
    var reviewStartedDay by rememberSaveable { mutableLongStateOf(todayEpochDay()) }
    /** «أعد ما أخطأت فيه»: the review asks again what was failed, and changes no schedule. */
    var reviewPractice by rememberSaveable { mutableStateOf(false) }
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
        HifzReviewState(practice = reviewPractice, earlier = if (reviewPractice) HifzTally() else reviewDayTally(),
            pendingPracticeIds = learning.pending, exposedVerses = learning.onDay(todayEpochDay()).exposed)
    }
    var practiceUndoBefore by rememberSaveable(reviewSession) { mutableStateOf<Map<Long, Boolean>>(emptyMap()) }
    /** The answers of the review in view count for the day's next one; a practice changes nothing. */
    fun keepReviewDay() {
        if (panel == "hifzReview" && !reviewState.practice) reviewDayDraft = "${todayEpochDay()}:${reviewState.day.encode()}"
    }
    val reviewQueue = remember(reviewIds, hifzPoints) { reviewIds.mapNotNull { id -> hifzPoints.firstOrNull { it.id == id } } }
    val reviewIndex = reviewState.index(reviewQueue)
    val reviewPoint = if (panel == "hifzReview") reviewQueue.getOrNull(reviewIndex) else null
    /**
     * The verse asked: covered on its page with what follows it until «أظهر», also behind the sheet, where the page
     * shows at its sides or as the sheet goes.
     */
    val reviewVerse = reviewPoint?.verse
    /** On the page: the verse asked, the page turned to it and the card over it. */
    val reviewTarget = reviewVerse?.takeIf { reviewOnPage }
    val reviewRevealed = reviewState.revealed(reviewPoint)
    val reviewPages = remember(catalog, reviewVerse) { reviewVerse?.let { catalog?.pagesForVerse(it.surah, it.ayah) }.orEmpty() }
    /** The verses of the points still to come in this review: covered wherever they are, so none is seen before it is asked. */
    val reviewUpcoming = remember(panel, reviewQueue, reviewIndex, reviewVerse, hifzPoints, today) {
        if (panel != "hifzReview") emptySet() else
            (reviewQueue.drop(reviewIndex + 1).map { it.verse } + hifzPoints.filter { it.isDue(today) }.map { it.verse }).toSet() - setOfNotNull(reviewVerse)
    }
    val reviewStillDue = eligibleDueIds.size
    val reviewNextCheckIn = remember(hifzPoints, today, learning.practised) {
        hifzPoints.minOfOrNull { if (it.id in learning.onDay(today).practised && it.isDue(today)) today + 1 else it.dueDay }?.minus(today)
    }
    /** The page the reader was on when the review opened: the review on the page turns back to it at the end. */
    var reviewFromPage by rememberSaveable { mutableStateOf<Int?>(null) }
    /** A fix opened over the review or the notebook, which stay where they were. */
    var stackedFix by rememberSaveable { mutableStateOf<Long?>(null) }
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
    /** A page reached by a jump that plays or shows a verse: no «قبل التسميع» over it. */
    var skipBriefingPage by remember { mutableStateOf<Int?>(null) }
    /** The hizb whose «قبل التسميع» is offered over the page, once a day, rather than opened over it. */
    var briefingOffer by rememberSaveable { mutableStateOf<Int?>(null) }
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
        val due = if (action == "hifz_review") HifzRepository.points.value
            .filter { it.id !in learning.onDay(todayEpochDay()).practised }
            .reviewBatch(todayEpochDay(), hifzSettings.perDay).map { it.id } else emptyList()
        if (panel == "hifzReview") endReviewNow[0]?.invoke(due.isEmpty())
        stackedFix = null
        when (action) {
            "hifz_review" -> {
                HifzReminder.clearDigest(context)
                if (passage != null) {
                    passageDraft = null
                    passageSummary = false
                    reviewMode = passagePriorMask
                }
                reviewPractice = false
                reviewStartedDay = todayEpochDay()
                reviewSession++
                reviewIds = due
                panel = if (reviewIds.isEmpty()) "hifz" else "hifzReview"
                if (reviewIds.isNotEmpty()) {
                    keepPlaceForReview()
                    reviewFromPage = from
                }
            }
            "hifz_record" -> panel = "hifz:record"
            else -> panel = when {
                // From a reminder about one point: its fix.
                action.startsWith(MainTabNavigation.QURAN_ACTION_HIFZ_FIX_PREFIX) -> "hifzFix:" + action.removePrefix(MainTabNavigation.QURAN_ACTION_HIFZ_FIX_PREFIX)
                // From a reminder about several: those points, not the notebook's other tabs.
                action.startsWith(MainTabNavigation.QURAN_ACTION_HIFZ_POINTS_PREFIX) -> "hifzPoints:" + action.removePrefix(MainTabNavigation.QURAN_ACTION_HIFZ_POINTS_PREFIX)
                else -> "hifz"
            }
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
    /** «قبل التسميع» for the page in view: its hizb, or its chapter while the ahzab are not read; with its name. */
    fun briefingScope(): Pair<String, String?> {
        val top = pageTop?.reference
        val hizb = top?.let { divisions?.thumnOf(it)?.hizb }
        return when {
            hizb != null -> "h$hizb" to "الحزب $hizb"
            top != null -> "s${top.surah}" to surahName(top.surah)
            else -> "0" to null
        }
    }
    // The offer goes with the hizb it was made for.
    LaunchedEffect(pageTop, divisions) {
        val offered = briefingOffer ?: return@LaunchedEffect
        if (pageTop?.let { divisions?.thumnOf(it.reference)?.hizb } != offered) briefingOffer = null
    }
    val sessionVerse = playback.surah?.let { s -> (playback.ayah ?: playback.gapAfter)?.takeIf { it > 0 }?.let { QuranVerseReference(s, it) } }
    val sessionOnPage = sessionVerse != null && catalog?.pagesForVerse(sessionVerse.surah, sessionVerse.ayah)?.contains(page.number) == true
    val hasSession = playback.surah != null
    // The verse after the one recited has a point: a heads-up asks it, and a stop before it can be asked for. It comes a
    // verse earlier when the verse before the point is short, and stays while the point's own verse is recited, to check
    // it against. With «توقف قبل الموضع» on, the playback service stops before every point by itself, also with the
    // screen off, and the card only asks.
    val stopsItself = hifzSettings.stopBefore && listening.verseTimes == 0
    val headsUpPoint = sessionVerse?.takeIf { (hifzSettings.headsUp || stopsItself) && playback.playing && playback.playlist == null }?.let { v ->
        fun pointAt(ayah: Int) = QuranVerseReference(v.surah, ayah).takeIf { at -> playback.repeat?.contains(at) != false }
            ?.let { at -> pointsByVerse[at]?.firstOrNull { p -> p.headsUp } }
        val nextIsShort = track?.takeIf { it.number == v.surah }?.let { t ->
            val start = t.startOf(v.ayah + 1)
            val end = t.endOf(v.ayah + 1)
            start != null && end != null && end - start < QuranShortVerseMs
        } == true
        pointAt(v.ayah + 1) ?: pointAt(v.ayah + 2)?.takeIf { nextIsShort } ?: pointAt(v.ayah)
    }?.takeIf { it.verse != headsUpClosed && it.verse != recitePrompt }
    val headsUp = remember(headsUpPoint, catalog) { headsUpPoint?.let { p -> catalog?.let { hifzFix(it, p) } } }
    /** The point's own verse is recited now: the card shows what to check it against. */
    val headsUpDuring = headsUpPoint != null && headsUpPoint.verse == sessionVerse
    /** The stop that a point arms: after the verse before it. */
    fun stopAfter(before: QuranVerseReference) = QuranSleep(QuranSleep.Kind.Verse, before)
    /** Stops after [before] so that the reader recites [point] from memory first. */
    fun armStopBefore(before: QuranVerseReference, point: QuranVerseReference) {
        QuranAudioController.updateListening(context) { it.copy(sleep = stopAfter(before)) }
        armedDraft = encodeVerse(before)
        recitePromptDraft = encodeVerse(point)
    }
    val listeningSleep = listening.sleep
    /** Another timer of the listener's is set: the stop before a point does not replace it. */
    val otherTimer = listeningSleep != null && listeningSleep != armedBefore?.let(::stopAfter)
    val beforeEnd = armedBefore?.let { b -> track?.takeIf { it.number == b.surah }?.endOf(b.ayah) }
    /** The verse recited now has already ended (its silence, or its last moments): too late to stop after it. */
    val verseEndBehind = playback.gapAfter != null || sessionVerse?.let { v ->
        track?.takeIf { it.number == v.surah }?.endOf(v.ayah)?.let { playback.positionMs >= it - 300L }
    } == true
    /** Paused at the end of the verse before the point: the reader's turn to recite it. */
    val atTurn = recitePrompt != null && armedBefore != null && !playback.playing && sessionVerse == armedBefore &&
        playback.error == null && playback.interruption == null && starter.pendingPacks.isEmpty() &&
        listeningSleep != armedBefore.let(::stopAfter) && beforeEnd != null && playback.positionMs >= beforeEnd - 300L
    /** The point to recite from memory now: where the service stopped by itself, or where the reader asked it to. */
    val turnPoint = recitePrompt?.takeIf { atTurn } ?: playback.stoppedBefore?.takeIf {
        !playback.playing && !playback.loading && playback.error == null && playback.interruption == null && starter.pendingPacks.isEmpty()
    }
    // No place yet is not the end of one: after process death the saved place is published only once
    // the controller has read it, and a saved «دورك» prompt must wait for it.
    var sessionSeen by remember { mutableStateOf(false) }
    if (hasSession) sessionSeen = true
    // One effect, in this order: release what the recitation has left behind, then re-arm a turn asked for on the
    // heads-up card when the verse before is heard again from its start.
    LaunchedEffect(sessionVerse, hasSession, playback.playing, playback.repeat, playback.playlist, track, listeningSleep == null) {
        fun left(before: QuranVerseReference) = (!hasSession && sessionSeen) || (sessionVerse != null && sessionVerse != before)
        // A stop asked a verse early waits through the verse before the one it follows.
        fun leftArmed(before: QuranVerseReference) = (!hasSession && sessionSeen) ||
            (sessionVerse != null && !(sessionVerse.surah == before.surah && sessionVerse.ayah in before.ayah - 1..before.ayah))
        var armed = armedBefore
        var prompt = recitePrompt
        // Into the point or elsewhere, a repetition that no longer reaches the point, or a drill: the turn is over.
        if (armed != null && (leftArmed(armed) || playback.playlist != null || (prompt != null && playback.repeat?.contains(prompt) == false))) {
            val sleep = QuranAudioController.listening.value.sleep
            if (sleep == stopAfter(armed)) QuranAudioController.updateListening(context) { it.copy(sleep = null) }
            armedDraft = null
            recitePromptDraft = null
            armed = null
            prompt = null
        }
        // A heads-up closed for this pass comes back on the next one, once the recitation is past its point.
        headsUpClosed?.let { closed ->
            val near = sessionVerse != null && sessionVerse.surah == closed.surah && sessionVerse.ayah in closed.ayah - 2..closed.ayah
            if ((!hasSession && sessionSeen) || (sessionVerse != null && !near)) headsUpClosedDraft = null
        }
        val before = sessionVerse ?: return@LaunchedEffect
        val end = track?.takeIf { it.number == before.surah }?.endOf(before.ayah)
        val position = QuranAudioController.state.value.positionMs
        // Still before the verse's last moments: just after «أكمل» its end is already behind.
        val underWay = end == null || position < end - 300L
        // The reader went back to hear the verse before again: stop after it again.
        if (armed == before && prompt != null && playback.playing && underWay && playback.playlist == null &&
            QuranAudioController.listening.value.sleep == null) armStopBefore(before, prompt)
    }
    // At the turn the prompt is in the dock, which the expanded player covers.
    LaunchedEffect(turnPoint) { if (turnPoint != null) expanded = false }
    // The point's verse heard after the reader's turn: once it ends, the reader says how their recitation went.
    LaunchedEffect(sessionVerse, playback.playing, hasSession) {
        val heard = turnHeard ?: return@LaunchedEffect
        val v = sessionVerse
        val endedOnIt = !playback.playing && v == heard &&
            track?.takeIf { it.number == heard.surah }?.endOf(heard.ayah)?.let { QuranAudioController.state.value.positionMs >= it - 300L } == true
        when {
            (v != null && v.surah == heard.surah && v.ayah == heard.ayah + 1) || endedOnIt -> {
                turnHeardDraft = null
                turnResultDraft = encodeVerse(heard)
            }
            // Elsewhere, or over: no question about a verse not heard.
            (!hasSession && sessionSeen) || (v != null && (v.surah != heard.surah || v.ayah !in heard.ayah - 1..heard.ayah)) -> turnHeardDraft = null
        }
    }
    LaunchedEffect(turnResult) {
        if (turnResult == null) return@LaunchedEffect
        delay(15_000L)
        turnResultDraft = null
    }
    // A recitation that ended elsewhere (the notification, a headset) closes the player with it.
    LaunchedEffect(hasSession) { if (!hasSession) expanded = false }
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
            // Not over a jump made to play or to show a verse, nor over a recitation: the reader asked for that.
            val jumped = skipBriefingPage == it
            skipBriefingPage = null
            // Offered over the page, never opened over it, and not while the verses are hidden: «قبل التسميع» would show
            // what the reader is about to recite.
            if (index != null && hifzSettings.briefing && panel == null && !jumped && !reviewMode && !QuranAudioController.state.value.playing) {
                val day = todayEpochDay()
                val parts = divisions
                val hizb = index.versesOnPage(it + 1).firstOrNull { v -> v.ayah > 0 }?.reference?.let { v -> parts?.thumnOf(v)?.hizb }
                if (hizb != null && !HifzSettings.briefedToday(context, BriefedHizbKey + hizb, day) &&
                    HifzRepository.points.value.any { p -> !p.mastered && parts?.thumnOf(p.verse)?.hizb == hizb }) {
                    HifzSettings.markBriefed(context, BriefedHizbKey + hizb, day)
                    briefingOffer = hizb
                }
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
    /** [brief]: offer «قبل التسميع» there; not when the jump is made to play or to show a verse. */
    fun goToPage(number: Int, hit: QuranSearchHit? = null, brief: Boolean = true) {
        panel = null
        clearTransient()
        userNavigated()
        foundDraft = hit?.encode()
        val target = (number - 1).coerceIn(0, pages.lastIndex)
        skipBriefingPage = if (brief || target == pager.settledPage) null else target
        scope.launch { pager.scrollToPage(target) }
    }
    fun showVerse(verse: QuranVerseReference) {
        val target = catalog?.pagesForVerse(verse.surah, verse.ayah)?.firstOrNull() ?: return
        goToPage(target, brief = false)
        selectionDraft = encodeVerse(verse)
    }
    fun openResult(position: Int) {
        val result = search.visible.getOrNull(position) ?: return
        search.current = position
        recentSearches = recentSearches.withRecentSearch(search.query).also { writeRecentSearches(prefs, it) }
        goToPage(result.page, result.hit, brief = false)
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
        }
    }
    /** «وضع المراجعة», from the eye in the bar or from the «المراجعة» sheet. */
    fun setVersesHidden(on: Boolean) {
        reviewMode = on
        if (passage == null) prefs.edit().putBoolean("review_mode", on).apply()
        selectionDraft = null
    }
    fun startReciting(chosen: HifzPassage) {
        starter.cancel()
        QuranAudioController.pause(context)
        clearTransient()
        if (passage == null) passagePriorMask = reviewMode
        passageDraft = chosen.encode()
        passageRecorded = emptyList()
        passageSummary = false
        passageFinished = false
        reviewMode = true
        focusChosen = false
        panel = null
        catalog?.pagesForVerse(chosen.from.surah, chosen.from.ayah)?.firstOrNull()?.let { goToPage(it, brief = false) }
    }
    fun endPassage() {
        passageDraft = null
        passageSummary = false
        reviewMode = passagePriorMask
        selectionDraft = null
        panel = "hifz"
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
        recitePrompt?.let { headsUpClosedDraft = encodeVerse(it) }
        armedDraft = null
        recitePromptDraft = null
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
        val text = catalog?.verse(verse)?.let { quranShareText(surahName(verse.surah), listOf(it)) } ?: return
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
        verse: QuranVerseReference, kind: HifzKind, other: QuranVerseReference?, slipWords: Set<Int>, bothWays: Boolean, hafs: Boolean,
        repeat: HifzRepeat, days: Int, home: Boolean = false,
    ) {
        val pendingBefore = learning.pending
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
                val first = HifzReminder.firstRepeatSlot(context, repeat, now)
                val until = if (repeat.none) null else HifzReminder.repeatUntil(first, days, now)
                val saved = HifzRepository.record(context, verse, kind, other, slipWords, bothWays, hafs, repeat, until, if (until == null) 0 else days, now)
                val mirrored = mirror?.let {
                    HifzRepository.record(context, it, kind, verse, emptySet(), true, hafs, repeat, until, if (until == null) 0 else days, now + 1)
                }
                HifzReminder.refresh(context)
                Triple(saved, first, mirrored)
            }
            val (saved, first, mirrored) = recorded
            if (passage != null && saved.verse in passage) passageRecorded = (passageRecorded + saved.id + listOfNotNull(mirrored?.id)).distinct()
            updateLearning { state -> state.pending(saved.id, true).let { next -> mirrored?.let { next.pending(it.id, true) } ?: next } }
            // Says when the slip comes back: the first reminder chosen, or the next morning's.
            val next = when {
                first != null -> "أول تذكير ${HifzReminder.slotLabel(first, todayEpochDay())}"
                repeat.none && hifzSettings.reminder -> "تذكير الصباح غدًا"
                else -> null
            }
            val message = listOfNotNull(
                // A slip recorded again says so: its point starts its checks over.
                if (before != null) "سُجّل مرة أخرى" else "حُفظ في دفتر المواضع",
                "${surahName(verse.surah)} ${verse.ayah}",
                before?.let { hifzSlipsLabel(saved.slips) },
                mirrored?.let { "والشبيهة ${if (it.verse.surah == verse.surah) "${it.verse.ayah}" else "${surahName(it.verse.surah)} ${it.verse.ayah}"}" },
                next,
            ).joinToString(" · ")
            val undo = {
                updateLearning { state -> state.pending(saved.id, saved.id in pendingBefore).let { next -> mirrored?.let { next.pending(it.id, it.id in pendingBefore) } ?: next } }
                passageRecorded = passageRecorded - saved.id - listOfNotNull(mirrored?.id).toSet()
                saveHifz {
                    HifzRepository.restore(context, before, removeId = saved.id)
                    mirrored?.let { HifzRepository.delete(context, it.id) }
                }
            }
            if (home) {
                hifzNotice = HifzNotice(message, undo = { undo(); hifzNotice = null }, again = { panel = "hifz:record" }, practice = { stackedFix = saved.id })
                return@launch
            }
            val result = snackbar.showSnackbar(QuranSnackbar(
                message = message,
                // The undo stays while the right wording is heard.
                actionLabel = "تراجع",
                secondLabel = "تدرّب الآن",
                onSecond = { panel = "hifzFix:${saved.id}" },
                dismissOnSecond = false,
            ))
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }
    fun openFix(point: HifzPoint) { panel = "hifzFix:${point.id}" }
    /**
     * The day's review: the checks due, in mushaf order; [first], tapped in the notebook before its check, asked first.
     * [again]: «أعد ما أخطأت فيه», those points asked once more, no schedule changed. The day's answers add up.
     */
    fun startReview(first: HifzPoint? = null, again: List<Long>? = null) {
        HifzReminder.clearDigest(context)
        keepReviewDay()
        val batch = again ?: run {
            val due = HifzRepository.points.value.filter { it.id !in learning.onDay(todayEpochDay()).practised }.reviewBatch(todayEpochDay(), hifzSettings.perDay)
            // A point on the first one's verse waits for the end: it would come right after its own answer.
            val ordered = if (first == null) due
                else listOf(first) + due.filter { it.id != first.id }.take(hifzSettings.perDay - 1).sortedBy { it.verse == first.verse }
            ordered.map { it.id }
        }
        if (passage != null) endPassage()
        reviewPractice = again != null
        reviewStartedDay = todayEpochDay()
        reviewSession++
        reviewIds = batch
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
        override fun pendingPracticeChanged(id: Long, pending: Boolean) { updateLearning { it.pending(id, pending) } }
        override fun exposed(verses: Set<QuranVerseReference>) { updateLearning { it.expose(verses) } }
        override fun practiceResult(point: HifzPoint, grade: HifzGrade?) {
            if (grade == null) {
                practiceUndoBefore[point.id]?.let { before -> updateLearning { it.practised(point.id, before) } }
                practiceUndoBefore = practiceUndoBefore - point.id
            } else {
                if (point.id !in practiceUndoBefore) practiceUndoBefore = practiceUndoBefore + (point.id to (point.id in learning.onDay(todayEpochDay()).practised))
                updateLearning { it.practised(point.id, true) }
            }
        }
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
                    now.copy(step = given.step, dueDay = given.dueDay, masteredAt = given.masteredAt, slips = given.slips, lastSlipAt = given.lastSlipAt, checks = given.checks),
                )
            }
        }
        override fun listenBefore(point: HifzPoint) {
            catalog?.let { reviewPlay(hifzCue(it, point, hifzSettings)) }
        }
        override fun listen(point: HifzPoint) {
            catalog?.let { reviewPlay(hifzListen(it, listOf(point), hifzSettings.copy(drillGap = 0f))) }
        }
        override fun drill(point: HifzPoint) {
            catalog?.let { reviewPlay(hifzDrill(it, point, hifzSettings)) }
        }
        override fun fix(point: HifzPoint) { stackedFix = point.id }
        override fun leaveCard() = leaveReviewCard()
        override fun more() = startReview()
        override fun retry(ids: List<Long>) = startReview(again = ids)
        override fun listenTo(ids: List<Long>) {
            // The checks are done: the review gives way to the reader, whose page follows the recitation, and the
            // listener's own place comes back once it ends.
            val index = catalog ?: return
            val points = HifzRepository.points.value.filter { it.id in ids }
            endReview(backToPage = false)
            playList(hifzListen(index, points, hifzSettings))
        }
        override fun dismiss() = endReview()
    }
    LaunchedEffect(learning.exposed, reviewSession) {
        if (panel == "hifzReview") reviewState.syncExposed(learning.onDay(todayEpochDay()).exposed, reviewActions)
        else reviewState.seedExposed(learning.onDay(todayEpochDay()).exposed)
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
                hifzNotice = HifzNotice("بدأ يوم جديد؛ حُفظ تدريبك المتبقي وجُدّدت قائمة المراجعة.", undo = null)
            }
        }
    }
    // Count heard audio, not a download request that might never start playing.
    LaunchedEffect(playback.playing, playback.surah, playback.ayah, panel) {
        val heard = if (playback.playing) playback.surah?.let { surah -> playback.ayah?.takeIf { it > 0 }?.let { QuranVerseReference(surah, it) } } else null
        if (heard != null) {
            if (panel == "hifzReview") reviewState.noteExposed(setOf(heard), reviewActions)
            else updateLearning { it.expose(setOf(heard)) }
        }
    }
    // Visible reader answers and recording previews also count as study for today's checks.
    LaunchedEffect(selection, panel, pager.settledPage, reviewMode, passageDraft, pagesReady) {
        if (panel == null && catalog != null && pagesReady) {
            val visible = if (reviewMode) setOfNotNull(selection) else catalog.versesOnPage(pager.settledPage + 1)
                .filter { it.ayah > 0 }.map { it.reference }.toSet()
            if (visible.isNotEmpty()) updateLearning { it.expose(visible) }
        }
        if (panel?.startsWith("hifzRecord:") == true) {
            val parts = panel!!.removePrefix("hifzRecord:").split(':')
            val surah = parts.getOrNull(0)?.toIntOrNull()
            val ayah = parts.getOrNull(1)?.toIntOrNull()
            if (surah != null && ayah != null) updateLearning { it.expose(setOf(QuranVerseReference(surah, ayah))) }
        }
    }
    // A comparison is visible even when the same verse remains covered in the background reader.
    LaunchedEffect(reviewPoint?.id, reviewRevealed, stackedFix, panel) {
        val correction = stackedFix?.let { id -> hifzPoints.firstOrNull { it.id == id } }
            ?: panel?.removePrefix("hifzFix:")?.toLongOrNull()?.let { id -> hifzPoints.firstOrNull { it.id == id } }
            ?: reviewPoint?.takeIf { reviewRevealed }
        val visible = buildSet {
            correction?.let { point ->
                add(point.verse)
                point.other?.let(::add)
                if (point.kind == HifzKind.Start || point.kind == HifzKind.Skip) {
                    if (point.verse.ayah > 1) add(QuranVerseReference(point.verse.surah, point.verse.ayah - 1))
                    add(QuranVerseReference(point.verse.surah, point.verse.ayah + 1))
                }
            }
            reviewPoint?.takeIf { !reviewRevealed && (it.kind == HifzKind.Start || it.kind == HifzKind.Skip) && it.verse.ayah > 1 }
                ?.let { add(QuranVerseReference(it.verse.surah, it.verse.ayah - 1)) }
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
    }
    LaunchedEffect(reviewOnPage) {
        // Nothing of the reader's own stays over the page the review uses.
        if (!reviewOnPage) return@LaunchedEffect
        expanded = false
        clearTransient()
    }

    /** Points still to hold: those held keep their checks, but no longer mark the page. */
    val pageHifzCount = remember(catalog, page.number, pointsByVerse) {
        catalog?.versesOnPage(page.number)?.count { it.ayah > 0 && pointsByVerse[it.reference]?.any { p -> !p.mastered } == true } ?: 0
    }
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
    fun slipAgain(point: HifzPoint) = saveHifz {
        val now = System.currentTimeMillis()
        val current = HifzRepository.points.value.firstOrNull { it.id == point.id } ?: return@saveHifz
        // A plan that still runs goes on as it is; otherwise the plan for new slips starts now.
        if (current.repeatsOn(epochDay(now))) {
            HifzRepository.record(context, current.verse, current.kind, current.other, emptySet(), current.bothWays, current.hafs,
                current.repeat, current.repeatUntil, current.repeatDays, now)
        } else {
            val repeat = hifzSettings.newRepeat
            val until = if (repeat.none) null else HifzReminder.repeatUntil(HifzReminder.firstRepeatSlot(context, repeat, now), hifzSettings.newRepeatDays, now)
            HifzRepository.record(context, current.verse, current.kind, current.other, emptySet(), current.bothWays, current.hafs,
                repeat, until, if (until == null) 0 else hifzSettings.newRepeatDays, now)
        }
    }
    /** Audio-assisted turns are practice; scheduled checks use the shared recall flow. */
    fun answerTurn(verse: QuranVerseReference, right: Boolean) {
        turnResultDraft = null
        val points = pointsByVerse[verse].orEmpty()
        if (!right && points.size != 1) {
            panel = "hifzRecord:${verse.surah}:${verse.ayah}"
            return
        }
        if (points.isEmpty()) return
        val before = learning.onDay(todayEpochDay())
        updateLearning { state -> points.fold(state.expose(setOf(verse))) { next, point -> next.pending(point.id, !right).practised(point.id, true) } }
        scope.launch {
            val result = snackbar.showSnackbar(QuranSnackbar(
                message = if (right) "استرجعتها بعد الاستماع · تبقى المراجعة في موعدها" else "أُضيفت إلى التدريب · تبقى المراجعة في موعدها",
                actionLabel = "تراجع", secondLabel = "تدرّب الآن".takeIf { !right }, onSecond = { startReview(again = points.map { it.id }) },
                duration = SnackbarDuration.Long,
            ))
            if (result == SnackbarResult.ActionPerformed) updateLearning { state ->
                points.fold(state) { next, point -> next.pending(point.id, point.id in before.pending).practised(point.id, point.id in before.practised) }
            }
        }
    }
    /** «حفظتها» in a reminder's list: those points' reminders stop; their checks go on. */
    fun stopReminders(points: List<HifzPoint>) {
        saveHifz {
            points.forEach { p ->
                HifzRepository.points.value.firstOrNull { it.id == p.id }?.let { HifzRepository.update(context, it.copy(repeat = HifzRepeat.None, repeatUntil = null, repeatDays = 0)) }
            }
        }
        scope.launch {
            val text = if (points.size == 1) "توقف تذكير ${surahName(points[0].verse.surah)} ${points[0].verse.ayah} · تبقى في مراجعاتك"
                else "توقفت تذكيرات ${quranPointsLabel(points.size)} · تبقى في مراجعاتك"
            val result = snackbar.showSnackbar(text, actionLabel = "تراجع", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) saveHifz { points.forEach { HifzRepository.restore(context, it) } }
        }
    }
    val hifzScope = QuranReaderScope(
        context = context,
        save = ::saveHifz,
        play = { list, review -> if (review) reviewPlay(list) else playList(list) },
        playBehind = { list -> playList(list, follow = false) },
        recall = { point ->
            starter.cancel()
            stackedFix = null
            startReview(again = listOf(point.id))
        },
        edit = { point -> editingPoint = point.id },
        openSettings = { stackedFix = null; panel = "hifzSettings" },
        notice = { hifzNotice = it },
        settings = { hifzSettings },
        undo = { message, action ->
            scope.launch {
                if (snackbar.showSnackbar(message, actionLabel = "تراجع", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) action()
            }
        },
        audio = activeHifzAudio,
        audioControls = hifzAudioControls,
        slipAgain = { point -> updateLearning { it.pending(point.id, true) }; slipAgain(point) },
        pendingPractice = learning.pending,
        practisedToday = learning.onDay(today).practised,
        restoreLearning = { id, pending, practised -> updateLearning { it.pending(id, pending).practised(id, practised) } },
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
    BackHandler(enabled = passage != null && panel == null && !passageSummary) {
        passageFinished = false
        passageSummary = true
    }

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
        Column(Modifier.fillMaxSize().testTag("quran_reader")) {
            AnimatedVisibility(visible = !focus, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
                Column {
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
                            "${page.number}",
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
                        onDownloads = { if (media?.layout != null) panel = "downloads" },
                        onHints = {
                            prefs.edit().remove("hint_tap_seen").remove("hint_strip_seen").remove("hint_focus_seen").remove("hint_review_eraser_seen").apply()
                            tapHint = true; stripHint = true; focusHint = true; reviewEraserHint = true
                        },
                        catalogReady = catalog != null,
                        hifzCount = if (hifzSettings.markers) pageHifzCount else 0,
                        markers = hifzSettings.markers,
                        onHifz = { if (catalog != null) panel = "hifz" },
                        onMarkers = {
                            hifzSettings = hifzSettings.copy(markers = !hifzSettings.markers)
                            HifzSettings.write(context, hifzSettings)
                        },
                    )
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
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Read by the page alone: the card or panel growing must not recompose the whole reader.
                val bottomInset = { if (expanded) panelHeightPx else overlayHeightPx }
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
                            it.ayah > 0 && (passage == null || it.reference in passage) && (it.reference != selection || it.reference == recovered)
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
                    val keep = when {
                        index != pager.currentPage -> emptyList()
                        // The verse asked, and the end of the one before it, stay above the review's card.
                        reviewTarget != null && reviewHere -> buildList {
                            highlights?.rectangles(number, reviewTarget.surah, reviewTarget.ayah - 1)?.lastOrNull()?.let(::add)
                            addAll(highlights?.rectangles(number, reviewTarget.surah, reviewTarget.ayah).orEmpty())
                        }
                        selection != null -> highlights?.rectangles(number, selection.surah, selection.ayah).orEmpty()
                        passage != null -> highlights?.rectangles(number, passage.from.surah, passage.from.ayah).orEmpty()
                        rangeFrom != null -> selected
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
                        hiddenRects = hidden.flatMap { highlights?.rectangles(number, it.surah, it.ayah).orEmpty() },
                        numberHoles = numberHoles,
                        // The verse asked, its cover in the points' colour: the card names it, the page shows it.
                        askedRects = if (reviewVerse != null && reviewHere && reviewCovered) {
                            highlights?.rectangles(number, reviewVerse.surah, reviewVerse.ayah).orEmpty()
                        } else if (passage != null && reviewMode) {
                            highlights?.rectangles(number, passage.from.surah, passage.from.ayah).orEmpty()
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
                        bringIntoView = reveal,
                        bottomInsetPx = bottomInset,
                        verseAt = { x, y -> if (catalog != null) highlights?.verseAt(number, x, y) else null },
                        rectsOf = { v -> highlights?.rectangles(number, v.surah, v.ayah).orEmpty() },
                        // «مراجعة» gives taps and long presses to the eraser, unless a passage is being chosen; so does the
                        // review's cover, until «أظهر».
                        erasing = highlights != null && catalog != null && if (reviewHere) reviewCovered else reviewMode && rangeFrom == null,
                        lineAt = { x, y -> highlights?.lineAt(number, x, y) },
                        onErased = { piece ->
                            val exposed = catalog?.versesOnPage(number).orEmpty().filter { verse ->
                                verse.ayah > 0 && highlights?.rectangles(number, verse.surah, verse.ayah).orEmpty()
                                    .any { r -> piece.left < r.right && piece.right > r.left && piece.top < r.bottom && piece.bottom > r.top }
                            }.map { it.reference }.toSet()
                            if (panel == "hifzReview") reviewState.noteExposed(exposed, reviewActions)
                            else updateLearning { it.expose(exposed) }
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
                if (((panel == "hifzReview" && !detailsFailed) || passage != null) && (catalog == null || highlights == null)) Box(
                    Modifier.matchParentSize().background(BgCream).pointerInput(Unit) { awaitEachGesture { awaitFirstDown() } },
                    contentAlignment = Alignment.Center,
                ) {
                    if (detailsFailed && passage != null) Text("تعذّر تجهيز غطاء الآيات. عد إلى المراجعة وحاول فتح المصحف مجددًا.", Modifier.padding(24.dp), color = TextMuted)
                    else CircularProgressIndicator(color = GreenPrimary)
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
                        .onSizeChanged { overlayHeightPx = it.height },
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
                        reviewOnPage && catalog != null -> {
                            // Looked away from the point's page: one tap back to it.
                            if (reviewPages.isNotEmpty() && pager.currentPage + 1 !in reviewPages) OutlinedButton(
                                onClick = { scope.launch { autoTurned = reviewPages.first() - 1; pager.animateScrollToPage(reviewPages.first() - 1) } },
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                                border = BorderStroke(1.dp, GreenPrimary),
                                modifier = Modifier.padding(top = 4.dp).testTag("hifz_review_back_to_point"),
                            ) {
                                Icon(painterResource(R.drawable.ic_quran_locate), null, Modifier.size(18.dp), tint = GreenPrimary)
                                Spacer(Modifier.width(8.dp))
                                Text("عد إلى الموضع", color = GreenPrimary, fontWeight = FontWeight.SemiBold)
                            }
                            HifzReviewCard(
                                state = reviewState, catalog = catalog, lookAlikes = lookAlikes, queue = reviewQueue,
                                audio = reviewHifzAudio, audioControls = hifzAudioControls,
                                drillGap = hifzSettings.drillGap, stillDue = reviewStillDue, nextCheckIn = reviewNextCheckIn,
                                actions = reviewActions,
                                maxHeight = (configuration.screenHeightDp * 0.6f).dp,
                                // The end of the verse before, when the page cannot show it: on the page before, for a verse
                                // that opens its page, or covered as a point still to come.
                                beforeLabel = reviewTarget?.takeIf { it.ayah > 1 && reviewPages.isNotEmpty() }?.let { target ->
                                    val before = QuranVerseReference(target.surah, target.ayah - 1)
                                    when {
                                        highlights?.rectangles(reviewPages.first(), before.surah, before.ayah).isNullOrEmpty() ->
                                            "آخر الآية ${before.ayah}، في الصفحة السابقة:"
                                        before in reviewUpcoming -> "آخر الآية ${before.ayah}:"
                                        else -> null
                                    }
                                },
                                eraserHint = reviewEraserHint, onEraserHintClosed = { hideHint("hint_review_eraser_seen") },
                            )
                        }
                        passage != null && catalog != null && selection == null && rangeFrom == null && !expanded && panel == null -> HifzPassageCard(
                            catalog, passage,
                            onRecord = { panel = "hifzPassageRecord" },
                            onFinish = { passageFinished = true; passageSummary = true },
                            onReturn = { catalog.pagesForVerse(passage.from.surah, passage.from.ayah).firstOrNull()?.let { goToPage(it, brief = false) } },
                        )
                        rangeFrom != null -> QuranRangeCard(
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
                        // The verse card, below, takes this place.
                        selection != null && !expanded -> Unit
                        headsUp != null && !expanded && catalog != null -> {
                            val point = headsUp.point.verse
                            val before = QuranVerseReference(point.surah, point.ayah - 1)
                            HifzHeadsUpCard(
                                fix = headsUp,
                                during = headsUpDuring,
                                stopsItself = stopsItself,
                                // Too late once the verse before has ended; not over another timer of the listener's.
                                onStop = if (headsUpDuring || stopsItself || point.ayah <= 1 || otherTimer || (sessionVerse == before && verseEndBehind)) null else {
                                    { armStopBefore(before, point) }
                                },
                                onFix = { openFix(headsUp.point) },
                                onClose = { headsUpClosedDraft = encodeVerse(point) },
                                onExposed = { updateLearning { it.expose(setOf(point)) } },
                            )
                        }
                        briefingOffer != null && !expanded && !reviewMode && catalog != null -> QuranOfferChip(
                            text = "قبل التسميع · مواضعك في الحزب $briefingOffer",
                            action = "اعرضها",
                            onAction = { panel = "hifzBriefing:h$briefingOffer"; briefingOffer = null },
                            onClose = { briefingOffer = null },
                        )
                        // «مراجعة» gives the tap to the eraser.
                        tapHint && catalog != null && !expanded && !reviewMode -> QuranHint("اضغط على أي آية لتستمع منها أو تكرّرها أو تنسخها") { hideHint("hint_tap_seen") }
                        stripHint && hasSession && !expanded && !focus -> QuranHint("اسحب الخط فوق أزرار المشغّل للانتقال إلى آية") { hideHint("hint_strip_seen") }
                        focusChosen && focus && focusHint -> QuranHint("اضغط على هامش الصفحة أو زر الرجوع لإظهار الأدوات") { hideHint("hint_focus_seen") }
                    }
                    // Built as soon as the reader can select a verse, and kept with the last verse while none is:
                    // choosing a verse then only places the card, without building it in the frame of the tap.
                    if (selection != null) lastCardVerse[0] = selection
                    val cardVerse = selection ?: lastCardVerse[0] ?: QuranVerseReference(1, 1).takeIf { catalog != null }
                    if (cardVerse != null) QuranCardSlot(shown = selection != null && rangeFrom == null && !expanded) {
                        val cardDue = if (reviewMode) pointsByVerse[cardVerse]?.firstOrNull { it.isDue(today) } else null
                        QuranVerseCard(
                            onReview = cardDue?.let { due -> { updateLearning { it.expose(setOf(cardVerse)) }; selectionDraft = null; startReview(first = due) } },
                            hidesAfterMs = if (reviewMode && selection != null) hosaryMs(cardVerse).coerceAtLeast(QuranReviewMinMs) else null,
                            title = "${surahName(cardVerse.surah)} · الآية ${cardVerse.ayah}",
                            bookmarked = remember(libraryRevision, cardVerse) { library.isBookmarked(cardVerse) },
                            hasPoint = cardVerse in pointsByVerse,
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
                            onPoint = {
                                // The point due first, when a verse has several.
                                val existing = pointsByVerse[cardVerse]?.let { points -> points.firstOrNull { it.isDue(today) } ?: points.firstOrNull() }
                                if (existing != null) { selectionDraft = null; openFix(existing) }
                                else panel = "hifzRecord:${cardVerse.surah}:${cardVerse.ayah}"
                            },
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
                    SnackbarHost(snackbar) { data ->
                        val extra = data.visuals as? QuranSnackbar
                        Snackbar(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
            }
            // The review on the page has its own audio strip in its card.
            val showDock = !reviewOnPage && (!focus || hasSession)
            if (showDock && !expanded) {
                val pending = starter.pendingPacks
                val download = media?.takeIf { pending.isNotEmpty() }?.summary(pending)
                val reciteNotice = turnPoint?.let { v ->
                    QuranDockNotice("سمّع الآية ${v.ayah} من حفظك", "ثم استمع إليها للتأكد", primary = "أكمل", secondary = "أعد الآية ${v.ayah - 1}".takeIf { v.ayah > 1 })
                }
                val resultNotice = turnResult?.takeIf { reciteNotice == null }?.let { v ->
                    QuranDockNotice("هل قرأت الآية ${v.ayah} صحيحة؟", "كما سمعتها الآن", primary = "نعم", secondary = "أخطأت")
                }
                val notice = reciteNotice ?: resultNotice ?: quranDockNotice(playback, media, pending, download, reciter.shortName, surahName)
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
                            // The turn ends once the point itself is recited, not now: see the disarming effect.
                            reciteNotice != null -> {
                                turnPoint?.let { turnHeardDraft = encodeVerse(it) }
                                resumeSession()
                            }
                            resultNotice != null -> turnResult?.let { answerTurn(it, right = true) }
                            download?.status == QuranPackStatus.WaitingForWifi || download?.status == QuranPackStatus.NeedsConfirmation ->
                                media?.source?.confirmMobileData(pending, mobileData)
                            download?.status == QuranPackStatus.Failed -> media?.fetch(pending, allowMetered = true)
                            else -> resumeSession()
                        }
                    },
                    onNoticeSecondary = {
                        val point = turnPoint
                        // At the turn: the verse before once more; the stop after it comes again as it ends.
                        if (reciteNotice != null && point != null && point.ayah > 1) {
                            QuranAudioController.seekVerse(context, point.surah, point.ayah - 1)
                            QuranAudioController.resume(context)
                        } else if (resultNotice != null) turnResult?.let { answerTurn(it, right = false) }
                        else starter.cancel()
                    },
                    onScrub = { scrub = it; if (it != null && stripHint) hideHint("hint_strip_seen") },
                    onScrubEnd = { target ->
                        QuranAudioController.seekTo(context, target.positionMs)
                        followAudio = true
                    },
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
            hifzDue = eligibleDueIds.size.coerceAtMost(hifzSettings.perDay),
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
                    catalog.surahs.getOrNull(surah - 1)?.let { goToPage(it.page, brief = false) }
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
                        goToPage(catalog.surahs[target.number - 1].page, brief = !play)
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
                onSave = { verse, kind, other, words, both, hafs, repeat, days -> recordSlip(verse, kind, other, words, both, hafs, repeat, days, home) },
                onDismiss = { panel = if (home) "hifz" else null },
                defaultRepeat = hifzSettings.newRepeat, defaultDays = hifzSettings.newRepeatDays,
                offerPrevious = !typed,
                points = hifzPoints, drillGap = hifzSettings.drillGap,
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
            catalog = catalog, points = hifzPoints, settings = hifzSettings, today = today,
            startRecording = currentPanel == "hifz:record",
            versesHidden = reviewMode,
            briefingLabel = briefingScope().second?.let { "قبل التسميع · $it" } ?: "قبل التسميع",
            notice = hifzNotice, onNoticeClosed = { hifzNotice = null },
            audio = activeHifzAudio, audioControls = hifzAudioControls,
            pendingPractice = hifzPoints.filter { it.id in learning.pending },
            eligibleDueIds = eligibleDueIds,
            practicedToday = hifzPoints.count { it.isDue(today) && it.id in learning.onDay(today).practised },
            actions = object : HifzHomeActions {
                override fun recordShown() { if (panel == "hifz:record") panel = "hifz" }
                override fun review() = startReview()
                override fun resumePractice(ids: List<Long>) = startReview(again = ids)
                override fun dismissPractice(ids: List<Long>) {
                    updateLearning { it.copy(pending = it.pending - ids.toSet()) }
                    ids.forEach(reviewState::forgetPoint)
                }
                override fun recite() { panel = "hifzRecite" }
                override fun reviewFrom(point: HifzPoint) = startReview(first = point)
                override fun listen(points: List<HifzPoint>) { panel = null; playList(hifzListen(catalog, points, hifzSettings)) }
                // The passage of the page in view; closing it comes back here.
                override fun briefing() { panel = "hifzBriefing:${briefingScope().first}:home" }
                // Over the notebook, which keeps its tab, filters and place when the fix closes.
                override fun open(point: HifzPoint) { stackedFix = point.id }
                override fun record(verse: QuranVerseReference) { panel = "hifzRecord:${verse.surah}:${verse.ayah}:typed" }
                override fun settings() { panel = "hifzSettings" }
                override fun hideVerses(on: Boolean) = setVersesHidden(on)
                override fun dismiss() { panel = null }
            },
        )
        currentPanel == "hifzRecite" -> if (catalog != null) HifzPassageSetup(
            catalog = catalog, page = page.number, onStart = ::startReciting, onDismiss = { panel = "hifz" },
        )
        currentPanel == "hifzPassageRecord" -> if (catalog != null && passage != null) HifzPassageRecordSheet(
            catalog = catalog, passage = passage, page = page.number,
            onRecord = { panel = "hifzRecord:${it.surah}:${it.ayah}" }, onDismiss = { panel = null },
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
            drillGap = hifzSettings.drillGap, stillDue = reviewStillDue, nextCheckIn = reviewNextCheckIn,
            actions = reviewActions,
        )
        currentPanel.startsWith("hifzBriefing:") -> {
            // «hifzBriefing:h5», a hizb; «:s2», a chapter; «:0», every point. «:home»: opened from the notebook.
            val home = currentPanel.endsWith(":home")
            val scopeKey = currentPanel.removePrefix("hifzBriefing:").removeSuffix(":home")
            val hizb = scopeKey.takeIf { it.startsWith("h") }?.drop(1)?.toIntOrNull()
            val surah = scopeKey.takeIf { it.startsWith("s") }?.drop(1)?.toIntOrNull() ?: scopeKey.toIntOrNull()?.takeIf { it > 0 }
            // The points still to hold: a held one needs no look before reciting.
            val points = remember(hifzPoints, scopeKey, divisions) {
                hifzPoints.filter { p ->
                    !p.mastered && when {
                        hizb != null -> divisions?.thumnOf(p.verse)?.hizb == hizb
                        surah != null -> p.verse.surah == surah
                        else -> true
                    }
                }
            }
            if (catalog != null) HifzBriefingSheet(
                catalog = catalog,
                title = when {
                    hizb != null -> "الحزب $hizb"
                    surah != null -> surahName(surah)
                    else -> "كل المواضع"
                },
                points = points,
                always = hifzSettings.briefing,
                onAlways = { hifzSettings = hifzSettings.copy(briefing = it); HifzSettings.write(context, hifzSettings) },
                onListen = { panel = null; playList(hifzListen(catalog, points, hifzSettings)) },
                onOpen = { stackedFix = it.id },
                onDismiss = { panel = if (home) "hifz" else null },
                // With the verses hidden, each waits under its cover, as on the page.
                covered = reviewMode,
                onExposed = { verse -> updateLearning { it.expose(setOf(verse)) } },
            )
        }
        currentPanel.startsWith("hifzPoints:") -> {
            // A reminder about several points: those points and their right wordings.
            val ids = currentPanel.removePrefix("hifzPoints:").split(',').mapNotNull { it.toLongOrNull() }.toSet()
            val points = hifzPoints.filter { it.id in ids }
            if (catalog != null && points.isNotEmpty()) HifzBriefingSheet(
                catalog = catalog, title = "مواضع التذكير", points = points,
                onExposed = { verse -> updateLearning { it.expose(setOf(verse)) } },
                header = "تذكير", hint = "الصواب في كل موضع · اضغط على موضع لتفتح تصحيحه",
                always = false, onAlways = null,
                onListen = { panel = null; playList(hifzListen(catalog, points, hifzSettings.copy(drillGap = 0f))) },
                onOpen = { stackedFix = it.id },
                onDismiss = { panel = null },
                // «حفظتها»: its reminders stop, its checks go on. For all of them, the list has done its work.
                onLearned = { learned ->
                    if (learned.size > 1) panel = null
                    stopReminders(learned)
                },
                planLine = { p ->
                    p.takeIf { it.repeatsOn(today) }?.let {
                        "تذكير ${HifzReminder.repeatShortPhrase(it.repeat)} حتى ${HifzReminder.dayLabel(it.repeatUntil!!, today)}"
                    }
                },
            ) else if (catalog != null && HifzRepository.isLoaded) panel = "hifz"
        }
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
                onReviewRecall = if (overReview && reviewState.training(reviewPoint) && reviewPoint?.id == point.id) ({
                    stackedFix = null
                    reviewState.retest(point, reviewActions)
                }) else null,
                onDeleted = { id ->
                    reviewState.forgetPoint(id)
                    updateLearning { it.copy(pending = it.pending - id, practised = it.practised - id) }
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
                onSave = { _, _, _, _, _, _, _, _ -> }, onDismiss = { editingPoint = null },
                points = hifzPoints, drillGap = hifzSettings.drillGap,
                editing = point,
                onEdit = { changed ->
                    editingPoint = null
                    saveHifz { HifzRepository.update(context, changed) }
                },
            )
        } else if (catalog != null && HifzRepository.isLoaded) editingPoint = null
    }
    if (passageSummary && passage != null && catalog != null) {
        val recorded = passageRecorded.filter { id -> hifzPoints.any { it.id == id } }
        HifzPassageSummary(
            catalog, passage, recorded.size, passageFinished,
            onContinue = { passageSummary = false },
            onPractice = if (recorded.isEmpty()) null else ({ endPassage(); startReview(again = recorded) }),
            onDone = ::endPassage,
        )
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
    onReviewRecall: (() -> Unit)? = null,
    onDeleted: (Long) -> Unit = {},
) {
    val pendingBeforeSlip = rememberSaveable(point.id) { point.id in pendingPractice }
    val practisedBeforeSlip = rememberSaveable(point.id) { point.id in practisedToday }
    HifzFixSheet(
        catalog = catalog, lookAlikes = lookAlikes, point = point, today = today, undoable = !overReview,
        onEdit = { edit(point) },
        // The settings replace the sheet below: not over the review, which they would end.
        onSettings = if (overReview) null else openSettings,
        audio = audio, audioControls = audioControls, drillGap = settings().drillGap,
        onSlipAgain = if (overReview) null else ({ slipAgain(point) }),
        onUndoSlipAgain = { before ->
            save { HifzRepository.restore(context, before) }
            restoreLearning(point.id, pendingBeforeSlip, practisedBeforeSlip)
        },
        onOtherSlip = if (overReview) null else ({ recordOther(point.verse) }),
        siblings = siblings(point), onSibling = onSibling,
        headsUpOn = settings().headsUp, stopBeforeOn = settings().stopBefore,
        onChange = { changed -> save { HifzRepository.update(context, changed) } },
        onRepeat = { repeat, days ->
            save {
                // From now: a new plan starts at its first reminder; «في المراجعات فقط» stops the plan's reminders.
                HifzRepository.points.value.firstOrNull { it.id == point.id }?.let { current ->
                    val now = System.currentTimeMillis()
                    val until = when {
                        repeat.none -> null
                        // Only how often changed: the plan keeps its end.
                        current.repeatsOn(epochDay(now)) && days == current.repeatDays -> current.repeatUntil
                        else -> HifzReminder.repeatUntil(HifzReminder.firstRepeatSlot(context, repeat, now), days, now)
                    }
                    HifzRepository.update(context, current.copy(repeat = repeat, repeatUntil = until, repeatDays = if (until == null) 0 else days))
                }
            }
        },
        defaultDays = settings().newRepeatDays,
        // Over the review, back to its card, whose strip shows the drill; over the notebook, back to it, whose strip
        // shows it too; elsewhere to the page and its player.
        onDrill = {
            val drill = hifzDrill(catalog, point, settings())
            // Keep the correction and its recall action available while listening and repeating.
            if (overReview) play(drill, true) else playBehind(drill)
        },
        onRecall = if (overReview) onReviewRecall else ({ recall(point) }),
        // Heard with the fix open, its strip in view: the page does not turn behind it.
        onListen = {
            val list = hifzListen(catalog, listOf(point), settings().copy(drillGap = 0f))
            if (overReview) play(list, true) else playBehind(list)
        },
        onOpen = if (pageInView) null else ({ onOpenVerse(point.verse) }),
        onDelete = {
            val wasPending = point.id in pendingPractice
            val wasPractised = point.id in practisedToday
            // Over the review, its card shows the change and no snackbar could be seen; over the notebook, its top says
            // so with the undo; elsewhere the sheets close so that the snackbar's «تراجع» can be seen.
            if (overReview || overHub) onBack() else onLeave()
            save { HifzRepository.delete(context, point.id) }
            onDeleted(point.id)
            val message = "حُذف الموضع · ${hifzVerseName(catalog, point.verse)}"
            val restore = {
                save { HifzRepository.restore(context, point) }
                restoreLearning(point.id, wasPending, wasPractised)
            }
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
    /** A fresh unaided attempt; practice never changes the scheduled check twice. */
    val recall: (HifzPoint) -> Unit,
    /** «تعديل الموضع» over the fix. */
    val edit: (HifzPoint) -> Unit,
    /** The settings of «المراجعة», in place of the sheets open. */
    val openSettings: () -> Unit,
    /** Said at the top of the notebook, with its undo. */
    val notice: (HifzNotice) -> Unit,
    val settings: () -> HifzSettings,
    val undo: (message: String, action: () -> Unit) -> Unit,
    /** The recitation playing, shown in the sheets that cover the player. */
    val audio: HifzAudio?,
    val audioControls: HifzAudioControls,
    /** «أخطأت فيها مرة أخرى»: the slip counted again, its plan kept while it runs. */
    val slipAgain: (HifzPoint) -> Unit,
    val pendingPractice: Set<Long>,
    val practisedToday: Set<Long>,
    val restoreLearning: (Long, Boolean, Boolean) -> Unit,
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

/**
 * Above the dock as a point nears: the question first, as the review asks it, so that the reader recites before seeing;
 * the right wording behind «أظهر الصواب»; «أوقف قبلها» to recite it from memory at the turn. While the point's own verse
 * is recited ([during]), the wording to check it against. Never the slip itself.
 */
@Composable
private fun HifzHeadsUpCard(
    fix: com.tunisianprayertimes.quran.hifz.HifzFix,
    during: Boolean,
    /** The recitation stops before the point by itself («توقف قبل الموضع»). */
    stopsItself: Boolean,
    onStop: (() -> Unit)?,
    onFix: () -> Unit,
    onClose: () -> Unit,
    onExposed: () -> Unit,
) {
    var shown by remember(fix.point.id) { mutableStateOf(false) }
    val open = shown || during
    LaunchedEffect(open) { if (open) onExposed() }
    QuranFloatingCard(Modifier.testTag("hifz_heads_up")) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            QuranHifzSquare(12.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                withQuranQuotes(when {
                    during -> "الآية ${fix.point.verse.ayah} الآن · ${fix.wordingLabel}:"
                    open -> "بعد قليل الآية ${fix.point.verse.ayah} · ${fix.wordingLabel}:"
                    else -> "بعد قليل الآية ${fix.point.verse.ayah} · ${fix.point.kind.short}"
                }),
                Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark,
            )
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(painterResource(R.drawable.ic_adhkar_close), "إخفاء التنبيه", Modifier.size(16.dp), tint = TextMuted)
            }
        }
        if (open) Text(fix.wording, Modifier.padding(horizontal = 14.dp), fontFamily = QuranMushafFont, fontSize = 18.sp, lineHeight = 34.sp, color = TextDark)
        else {
            Text(fix.question, Modifier.padding(horizontal = 14.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
            if (stopsItself) Text("تتوقف التلاوة قبلها لتسمّعها من حفظك", Modifier.padding(horizontal = 14.dp), fontSize = 12.sp, color = TextMuted)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 6.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (open) TextButton(onClick = onFix) { Text("التصحيح") }
            else TextButton(onClick = { shown = true }, modifier = Modifier.testTag("hifz_heads_up_reveal")) { Text("أظهر الصواب") }
            if (onStop != null && !open) Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.testTag("hifz_stop_before")) {
                Text("أوقف قبلها")
            }
        }
    }
}

/** Offered over the page rather than opened over it, as «قبل التسميع»: a tap shows it; it goes with the page. */
@Composable
private fun QuranOfferChip(text: String, action: String, onAction: () -> Unit, onClose: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp), color = Color.White, border = BorderStroke(1.dp, QuranHifzViolet),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).testTag("hifz_briefing_offer"),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            QuranHifzSquare(10.dp)
            Spacer(Modifier.width(8.dp))
            Text(text, Modifier.weight(1f, fill = false), color = TextDark, fontSize = 13.sp)
            TextButton(onClick = onAction) { Text(action, color = QuranHifzViolet, fontWeight = FontWeight.SemiBold) }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(painterResource(R.drawable.ic_adhkar_close), "إخفاء", Modifier.size(16.dp), tint = TextMuted)
            }
        }
    }
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
    onDownloads: () -> Unit,
    onHints: () -> Unit,
    hifzCount: Int,
    markers: Boolean,
    onHifz: () -> Unit,
    onMarkers: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(BgCream).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(enabled = catalogReady, onClickLabel = "فتح الفهرس", onClick = onLocation)
                .padding(vertical = 2.dp).testTag("quran_location"),
        ) {
            Text("$title ▾", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // With large text the full line would cut off the hizb: the shorter one then.
                var compact by remember(subtitle) { mutableStateOf(false) }
                Text(
                    if (compact) compactSubtitle else subtitle, Modifier.weight(1f, fill = false), fontSize = 12.sp, color = TextMuted,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (it.hasVisualOverflow && !compact) compact = true },
                )
                if (hifzCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    QuranHifzSquare(10.dp)
                    Text(" $hifzCount", fontSize = 12.sp, color = QuranHifzViolet, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { contentDescription = "${quranPointsLabel(hifzCount)} في هذه الصفحة" })
                }
            }
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
                .semantics { contentDescription = "إخفاء الآيات للتسميع" }.testTag("quran_review_mode"),
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
        IconButton(onClick = onFocus, modifier = button.testTag("quran_focus")) {
            Icon(painterResource(R.drawable.ic_quran_focus), "وضع التركيز", icon, tint = GreenPrimary)
        }
        IconButton(onClick = onSearch, enabled = catalogReady, modifier = button.testTag("quran_search")) {
            Icon(painterResource(R.drawable.ic_adhkar_search), "بحث", icon, tint = if (catalogReady) GreenPrimary else TextMuted)
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = button.testTag("quran_menu")) {
                BadgedBox(badge = { if (hifzDue > 0) Badge(containerColor = QuranHifzViolet) }) {
                    Icon(
                        painterResource(R.drawable.ic_quran_more_vert),
                        if (hifzDue > 0) "خيارات القراءة · ${quranPointsLabel(hifzDue)} للمراجعة اليوم" else "خيارات القراءة", icon, tint = GreenPrimary,
                    )
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("إبقاء الشاشة مضاءة") },
                    trailingIcon = { Switch(checked = keepAwake, onCheckedChange = null) },
                    onClick = { onKeepAwake() },
                )
                DropdownMenuItem(text = { Text("إضاءة القراءة الليلية…") }, onClick = { menu = false; onNight() })
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
                else "ابدأ من هذه الصفحة · ${surahName(action.verse.surah)} ${action.verse.ayah}"
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
    minutes % 100 in 3..10 -> "$minutes دقائق"
    else -> "$minutes دقيقة"
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
     * Where the page fills the width: the verse recited, scrolled to the top of the view when it is out of it, once
     * each time it changes. [keepVisible] comes first, and [foundRects] after.
     */
    bringIntoView: List<QuranHighlightRect> = emptyList(),
    bottomInsetPx: () -> Int,
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
    /** The text line at a point of the original scan, as a verse rectangle on it: its top and bottom bound the line. */
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
    fun fit(image: ImageBitmap): Float {
        val byWidth = size.width.toFloat() / image.width
        return if (fillsWidth) byWidth else minOf(byWidth, size.height.toFloat() / image.height)
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
    // Pans the page up just enough for [keepVisible] to clear what floats over the bottom of the page. A page
    // that fills the width scrolls instead, below, and is lifted only where its end stops the scroll.
    val targetShift = run {
        val image = bitmap
        if (image == null || scale > 1.01f || size.height == 0) 0f
        else if (fillWidth) endLift
        else if (keepVisible.isEmpty()) 0f
        else {
            val f = fit(image)
            val top = (size.height - image.height * f) / 2f
            val bottom = top + keepVisible.maxOf { it.bottom } * image.height * f
            val first = top + keepVisible.minOf { it.top } * image.height * f
            val margin = with(density) { 16.dp.toPx() }
            val limit = size.height - bottomInsetPx() - margin
            (-quranRevealScroll(first, bottom, margin, limit, chosen = true)).coerceAtLeast(0f)
        }
    }
    // Moves with the verse card coming in or going.
    val shift by animateFloatAsState(targetShift, tween(QuranCardMillis, easing = FastOutSlowInEasing), label = "quranPageShift")
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
        val y = .5f + (point.y - size.height / 2f - offset.y - restY() + shift) / (scale * image.height * f)
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
                        val height = if (!fillWidth) constraints.maxHeight
                            else maxOf(constraints.maxHeight, ceil(width * loaded.height / loaded.width.toFloat()).toInt())
                        val placeable = measurable.measure(Constraints.fixed(width, height))
                        layout(width, constraints.maxHeight) { placeable.place(0, (constraints.maxHeight - height) / 2) }
                    }
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y + restY() - shift
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
                        askedRects.forEach { rect ->
                            drawRoundRect(
                                QuranHifzViolet, origin + Offset(rect.left * imageWidth, rect.top * imageHeight),
                                Size((rect.right - rect.left) * imageWidth, (rect.bottom - rect.top) * imageHeight), CornerRadius(3.dp.toPx()),
                                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))),
                            )
                        }
                    }
                    // Only the found, recited and pressed verses are coloured; the rest of the page stays white.
                    // A point's marker is a shape as well as a colour; a pressed verse shows only as pressed.
                    hifzRects.filterNot { it in pressedRects }.forEach { rect ->
                        val topLeft = origin + Offset(rect.left * imageWidth, rect.top * imageHeight)
                        val box = Size((rect.right - rect.left) * imageWidth, (rect.bottom - rect.top) * imageHeight)
                        drawRoundRect(QuranHifzViolet.copy(alpha = 0.22f), topLeft, box, CornerRadius(3.dp.toPx()), blendMode = BlendMode.Multiply)
                        drawRoundRect(
                            QuranHifzViolet, topLeft, box, CornerRadius(3.dp.toPx()),
                            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))),
                        )
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

internal fun quranPageLabel(scan: Int): String = if (scan <= QuranScanCount) "صفحة ${quranNumber(scan + 1)}"
    else "الملحق ${quranNumber(scan - QuranScanCount)}"

@Composable
internal fun QuranPanelHeader(title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
        IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_adhkar_close), "إغلاق") }
    }
}
