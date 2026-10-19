package com.tunisianprayertimes.ui

import androidx.compose.ui.graphics.StrokeCap
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.LruCache
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranHighlightRect
import com.tunisianprayertimes.quran.QuranHighlights
import com.tunisianprayertimes.quran.QuranMushafText
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.assets.QuranAssets
import com.tunisianprayertimes.quran.assets.open
import com.tunisianprayertimes.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The verses' places on the scans, for the mushaf shown in the hifz sheets; null until they are read. */
internal val LocalQuranHighlights = staticCompositionLocalOf<QuranHighlights?> { null }

/** Visible Quran excerpts count as help for later recall, including recording and editing previews. */
internal val LocalQuranExposure = staticCompositionLocalOf<(Set<QuranVerseReference>) -> Unit> { {} }

/**
 * A band of one mushaf page, from [top] to [bottom] of the scan (0 at its top, 1 at its foot), as wide as the page.
 * [marked] stands out in the points' violet, [muted] is outlined in grey, [covered] is hidden under the «مراجعة» colour.
 */
@Immutable
internal data class QuranExcerpt(
    val page: Int,
    val top: Float,
    val bottom: Float,
    val marked: List<QuranHighlightRect> = emptyList(),
    val muted: List<QuranHighlightRect> = emptyList(),
    val covered: List<QuranHighlightRect> = emptyList(),
)

/** How the verse of an excerpt stands out: the point's own verse, or the one it is confused with. */
internal enum class QuranExcerptMark { Point, Other }

/**
 * Room above the first line of a band, a fifth of a line: the marks over it stay whole. Below, the band ends with its
 * last line: what lies under it is mostly the next line's marks.
 */
private const val QuranExcerptPad = 0.008f

/**
 * The verses an excerpt of [verse] shows: the verse, the one before it with [linesBefore], and those up to [after]
 * with [linesAfter], since a band holds every line between the first and the last it shows.
 */
internal fun quranExcerptVerses(
    verse: QuranVerseReference,
    linesBefore: Int = 0,
    linesAfter: Int = 0,
    after: QuranVerseReference = QuranVerseReference(verse.surah, verse.ayah + 1),
): Set<QuranVerseReference> = buildSet {
    if (linesBefore > 0 && verse.ayah > 1) add(QuranVerseReference(verse.surah, verse.ayah - 1))
    add(verse)
    if (linesAfter > 0) {
        if (after.surah == verse.surah && after.ayah > verse.ayah) (verse.ayah + 1..after.ayah).forEach { add(QuranVerseReference(verse.surah, it)) }
        else add(after)
    }
}

/**
 * The mushaf around [verse], as bands of its page or pages: the verse's lines, the last [linesBefore] lines of the verse
 * before it and the first [linesAfter] lines of [after]. A band holds every line between the first and the last it
 * shows, and the verse before or after may be on another page, with a band of its own. Any other verse on those lines
 * is covered: an excerpt shows the verses it names ([quranExcerptVerses]), never a neighbour's opening or ending, which
 * may be another point's answer.
 * [cover]: the verse and whatever follows it on those lines are hidden, for a question.
 */
internal fun quranExcerpts(
    highlights: QuranHighlights,
    catalog: QuranCatalog,
    verse: QuranVerseReference,
    linesBefore: Int = 0,
    linesAfter: Int = 0,
    after: QuranVerseReference = QuranVerseReference(verse.surah, verse.ayah + 1),
    mark: QuranExcerptMark = QuranExcerptMark.Point,
    cover: Boolean = false,
): List<QuranExcerpt> {
    fun lines(of: QuranVerseReference): List<Pair<Int, QuranHighlightRect>> = catalog.pagesForVerse(of.surah, of.ayah)
        .flatMap { page -> highlights.rectangles(page, of.surah, of.ayah).sortedBy { it.top }.map { page to it } }
    val own = lines(verse)
    if (own.isEmpty()) return emptyList()
    val around = buildList {
        if (linesBefore > 0 && verse.ayah > 1) addAll(lines(QuranVerseReference(verse.surah, verse.ayah - 1)).takeLast(linesBefore))
        if (linesAfter > 0) addAll(lines(after).take(linesAfter))
    }
    val named = quranExcerptVerses(verse, linesBefore, linesAfter, after)
    return (own + around).groupBy({ it.first }, { it.second }).toSortedMap().map { (page, shown) ->
        val firstLine = shown.minOf { it.top }
        val top = (firstLine - QuranExcerptPad).coerceAtLeast(0f)
        // No pad below: the pad above is for the first line's marks; below, it would only let in the next line's.
        val bottom = shown.maxOf { it.bottom }
        val ownHere = own.filter { it.first == page }.map { it.second }
        // Each cover reaches halfway into the gaps around its line, as on the page: no seam between covered lines.
        val covered = highlights.covers(page, catalog.versesOnPage(page).filter { it.ayah > 0 && (it.reference !in named || (cover && it.reference >= verse)) }
            .flatMap { highlights.rectangles(page, it.surah, it.ayah) }).filter { it.bottom > top && it.top < bottom }
        QuranExcerpt(
            page, top, bottom,
            marked = ownHere.takeIf { mark == QuranExcerptMark.Point }.orEmpty(),
            muted = ownHere.takeIf { mark == QuranExcerptMark.Other }.orEmpty(),
            covered = covered,
        )
    }
}

/** «أعلى», «وسط», «أسفل»: where a band sits on its page, as a reader remembers it, said before the page: «أعلى صفحة 562». */
internal fun quranExcerptPlace(excerpt: QuranExcerpt): String = when ((excerpt.top + excerpt.bottom) / 2f) {
    in 0f..0.36f -> "أعلى"
    in 0.36f..0.64f -> "وسط"
    else -> "أسفل"
}

/** Bands already read, by page, band and scale: a sheet opened again, or scrolled back, shows them at once. */
private val QuranExcerptCache = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
}

private sealed interface QuranBand {
    data object Reading : QuranBand
    /** The page is not on the device yet, or could not be read. */
    data object Missing : QuranBand
    data class Ready(val image: ImageBitmap) : QuranBand
}

/** A line of the enlarged excerpt's text view: words of the interface, or a verse in the source's spelling. */
internal data class QuranExcerptLine(val text: String, val verse: Boolean = false)

/**
 * [excerpts] as they look in the mushaf, one band under the other, each with where it sits on its page. [fallback]
 * shows instead without the verses' places or the pages on the device. [description] says what the bands show, for
 * TalkBack; [lines] are the enlarged view's text, the verses in the mushaf font and the rest in the interface's.
 */
@Composable
internal fun QuranExcerpts(
    catalog: QuranCatalog,
    excerpts: List<QuranExcerpt>,
    description: String,
    modifier: Modifier = Modifier,
    lines: List<QuranExcerptLine> = listOf(QuranExcerptLine(description)),
    fallback: @Composable () -> Unit = {},
) {
    if (excerpts.isEmpty()) {
        fallback()
        return
    }
    var missing by remember(excerpts) { mutableStateOf(false) }
    var enlarged by rememberSaveable(description) { mutableStateOf(false) }
    if (missing) {
        fallback()
        return
    }
    Column(
        modifier.fillMaxWidth().clickable(onClickLabel = "تكبير المقطع") { enlarged = true }.testTag("quran_excerpt"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        excerpts.forEachIndexed { i, excerpt ->
            Box {
                QuranBandView(catalog, excerpt, if (i == 0) description else null) { missing = true }
                // The Column's «تكبير المقطع» speaks for it.
                if (i == 0) Icon(
                    painterResource(R.drawable.ic_open_in_full), null,
                    Modifier.align(Alignment.TopEnd).size(14.dp), tint = GreenPrimary,
                )
            }
        }
    }
    if (enlarged) QuranExcerptViewer(catalog, excerpts, description, lines) { enlarged = false }
}

/** Zoom keeps the same masks and marks. The text alternative shows the same answer-safe [lines] as TalkBack hears. */
@Composable
private fun QuranExcerptViewer(
    catalog: QuranCatalog,
    excerpts: List<QuranExcerpt>,
    description: String,
    lines: List<QuranExcerptLine>,
    onDismiss: () -> Unit,
) {
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var textView by rememberSaveable { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().testTag("quran_excerpt_viewer"), color = BgCream) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("المقطع مكبّرًا", Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                    TextButton(onClick = onDismiss) { Text("إغلاق") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { zoom = (zoom - 0.5f).coerceAtLeast(1f) }, enabled = zoom > 1f, modifier = Modifier.testTag("quran_excerpt_zoom_out")) {
                        Text("تصغير")
                    }
                    Text(
                        "${(zoom * 100).toInt()}٪", fontSize = 14.sp, color = GreenPrimaryDark,
                        modifier = Modifier.semantics { stateDescription = "التكبير ${(zoom * 100).toInt()} بالمئة" },
                    )
                    TextButton(onClick = { zoom = (zoom + 0.5f).coerceAtMost(4f) }, enabled = zoom < 4f, modifier = Modifier.testTag("quran_excerpt_zoom_in")) {
                        Text("تكبير")
                    }
                }
                if (!missing) TextButton(onClick = { textView = !textView; zoom = 1f }, modifier = Modifier.testTag("quran_excerpt_text")) {
                    Text(if (textView) "عرض صورة المصحف" else "عرض النص القابل للتكبير")
                }
                if (textView || missing) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        lines.forEach { line ->
                            if (line.verse) Text(
                                QuranMushafText.text(line.text), fontFamily = QuranMushafFont,
                                fontSize = (21f * zoom).sp, lineHeight = (38f * zoom).sp, color = TextDark,
                            )
                            else Text(line.text, fontSize = (15f * zoom).sp, lineHeight = (24f * zoom).sp, color = TextMuted)
                        }
                    }
                } else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)) {
                    val width = maxWidth
                    Box(Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal)) {
                        Column(Modifier.width(width * zoom).padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            excerpts.forEachIndexed { i, excerpt ->
                                QuranBandView(catalog, excerpt, if (i == 0) description else null) { missing = true }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** [verse] on its mushaf page, as [quranExcerpts] cuts it; [fallback] without the pages or the verses' places. */
@Composable
internal fun QuranVerseExcerpt(
    catalog: QuranCatalog,
    verse: QuranVerseReference,
    description: String,
    modifier: Modifier = Modifier,
    linesBefore: Int = 0,
    linesAfter: Int = 0,
    after: QuranVerseReference = QuranVerseReference(verse.surah, verse.ayah + 1),
    mark: QuranExcerptMark = QuranExcerptMark.Point,
    cover: Boolean = false,
    fallback: (@Composable () -> Unit)? = null,
) {
    val highlights = LocalQuranHighlights.current
    val onExposure by rememberUpdatedState(LocalQuranExposure.current)
    var visible by remember { mutableStateOf(false) }
    val exposed = remember(catalog, verse, linesBefore, linesAfter, after, cover) {
        // A question shows only the end of the verse before it.
        quranExcerptVerses(verse, linesBefore, linesAfter, after).filter { (!cover || it < verse) && catalog.verse(it) != null }.toSet()
    }
    LaunchedEffect(visible, exposed) {
        if (visible && exposed.isNotEmpty()) onExposure(exposed)
    }
    val excerpts = remember(highlights, catalog, verse, linesBefore, linesAfter, after, mark, cover) {
        highlights?.let { quranExcerpts(it, catalog, verse, linesBefore, linesAfter, after, mark, cover) }.orEmpty()
    }
    // The scan is the visible verse; its accessible description carries the same words.
    // A covered question must not disclose its answer to TalkBack or when a page is missing.
    // The enlarged text view shows the same, each label in the interface's font and each verse in the mushaf's.
    val lines = remember(catalog, verse, description, linesBefore, linesAfter, after, cover) {
        buildList {
            add(QuranExcerptLine(description))
            if (linesBefore > 0 && verse.ayah > 1) {
                catalog.verse(QuranVerseReference(verse.surah, verse.ayah - 1))?.text?.let {
                    add(QuranExcerptLine("الآية السابقة"))
                    add(QuranExcerptLine(it, verse = true))
                }
            }
            if (cover) add(QuranExcerptLine("الآية مغطاة؛ اقرأها من حفظك ثم أظهرها"))
            else {
                catalog.verse(verse)?.text?.let { add(QuranExcerptLine(it, verse = true)) }
                if (linesAfter > 0) catalog.verse(after)?.text?.let {
                    add(QuranExcerptLine("ثم الآية ${after.ayah}"))
                    add(QuranExcerptLine(it, verse = true))
                }
            }
        }
    }
    val accessible = remember(lines) {
        // A verse after its label (any line but the description) reads «label: verse»; the rest are sentences.
        buildString {
            lines.forEachIndexed { i, line ->
                if (i > 0) append(if (line.verse && i > 1 && !lines[i - 1].verse) ": " else ". ")
                append(line.text)
            }
        }
    }
    Box(modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = bounds.width > 0f && bounds.height > 0f
    }) {
    QuranExcerpts(catalog, excerpts, accessible, lines = lines, fallback = fallback ?: {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (linesBefore > 0 && verse.ayah > 1) catalog.verse(QuranVerseReference(verse.surah, verse.ayah - 1))?.let {
                Text("الآية السابقة", fontSize = 12.sp, color = TextMuted)
                Text(QuranMushafText.text(it.text), fontFamily = QuranMushafFont, fontSize = 19.sp, lineHeight = 34.sp, color = TextMuted)
            }
            if (cover) Text("الآية مغطاة؛ اقرأها من حفظك ثم أظهرها", fontSize = 14.sp, color = QuranHifzViolet)
            else {
                catalog.verse(verse)?.let { Text(QuranMushafText.text(it.text), fontFamily = QuranMushafFont, fontSize = 19.sp, lineHeight = 34.sp, color = TextDark) }
                if (linesAfter > 0) catalog.verse(after)?.let {
                    Text("ثم الآية ${after.ayah}", fontSize = 12.sp, color = TextMuted)
                    Text(QuranMushafText.text(it.text), fontFamily = QuranMushafFont, fontSize = 19.sp, lineHeight = 34.sp, color = TextMuted)
                }
            }
        }
    })
    }
}

@Composable
private fun QuranBandView(catalog: QuranCatalog, excerpt: QuranExcerpt, description: String?, onMissing: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val page = remember(catalog, excerpt.page) {
        catalog.pages.getOrNull(excerpt.page - 1)?.takeIf { it.number == excerpt.page } ?: catalog.pages.firstOrNull { it.number == excerpt.page }
    }
    if (page == null) {
        LaunchedEffect(Unit) { onMissing() }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            QuranPageSpot(excerpt)
            Spacer(Modifier.width(6.dp))
            Text("${quranExcerptPlace(excerpt)} ${quranPageLabel(excerpt.page)}", fontSize = 11.sp, color = TextMuted)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = with(LocalDensity.current) { maxWidth.roundToPx() }
            val band by produceState<QuranBand>(QuranBand.Reading, page.assetPath, excerpt.top, excerpt.bottom, width) {
                value = try {
                    withContext(Dispatchers.IO) { readBand(context, page.assetPath, excerpt.top, excerpt.bottom, width) }
                        ?.let { QuranBand.Ready(it) } ?: QuranBand.Missing
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    QuranBand.Missing
                }
            }
            when (val shown = band) {
                is QuranBand.Ready -> Box(
                    Modifier.fillMaxWidth().aspectRatio(shown.image.width / shown.image.height.toFloat())
                        .clip(RoundedCornerShape(10.dp)).background(Color.White).border(1.dp, CardBorder, RoundedCornerShape(10.dp)),
                ) {
                    Image(shown.image, description, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    QuranBandMarks(excerpt)
                }
                // Room for the band while it is read, as tall as a scan's band of that height: nothing jumps once it shows.
                QuranBand.Reading -> Box(
                    Modifier.fillMaxWidth().aspectRatio(1f / ((excerpt.bottom - excerpt.top) * QuranScanAspect))
                        .clip(RoundedCornerShape(10.dp)).background(Color.White).border(1.dp, CardBorder, RoundedCornerShape(10.dp)),
                )
                QuranBand.Missing -> LaunchedEffect(Unit) { onMissing() }
            }
        }
    }
}

/** A scan's height over its width (2650 by 1830), for the room a band takes before it is read. */
private const val QuranScanAspect = 1.448f

/** The marks over a band, placed as on the page: the cover first, then the verse in violet, then the look-alike in grey. */
@Composable
private fun QuranBandMarks(excerpt: QuranExcerpt) {
    Canvas(Modifier.fillMaxSize()) {
        val span = excerpt.bottom - excerpt.top
        fun at(r: QuranHighlightRect) = Offset(r.left * size.width, (r.top - excerpt.top) / span * size.height)
        fun sizeOf(r: QuranHighlightRect) = Size((r.right - r.left) * size.width, (r.bottom - r.top) / span * size.height)
        val corner = CornerRadius(3.dp.toPx())
        // A cover on the band's first line reaches the band's top: the marks of the line above it, which reach into
        // the pad over that line, do not show over it.
        excerpt.covered.forEach { r ->
            val drawn = if (r.top <= excerpt.top + 2 * QuranExcerptPad) r.copy(top = minOf(r.top, excerpt.top)) else r
            drawRect(QuranReviewMask, at(drawn), sizeOf(drawn))
        }
        // As the page marks a point: a light tint and a line under each line, never a frame read as a selection.
        fun underline(r: QuranHighlightRect, color: Color, width: Float) {
            val topLeft = at(r)
            val box = sizeOf(r)
            val inset = 3.dp.toPx().coerceAtMost(box.width / 4f)
            val y = topLeft.y + box.height - width / 2f
            drawLine(color, Offset(topLeft.x + inset, y), Offset(topLeft.x + box.width - inset, y), width, cap = StrokeCap.Round)
        }
        excerpt.marked.forEach {
            drawRoundRect(QuranHifzViolet.copy(alpha = 0.16f), at(it), sizeOf(it), corner, blendMode = BlendMode.Multiply)
            underline(it, QuranHifzViolet, 2.dp.toPx())
        }
        excerpt.muted.forEach {
            drawRoundRect(TextMuted.copy(alpha = 0.14f), at(it), sizeOf(it), corner, blendMode = BlendMode.Multiply)
            underline(it, TextMuted, 1.5.dp.toPx())
        }
    }
}

/** A page in small, with the band on it: where on the page the verse is, at a glance. */
@Composable
private fun QuranPageSpot(excerpt: QuranExcerpt) {
    Canvas(Modifier.size(width = 12.dp, height = 17.dp)) {
        val line = 1.dp.toPx()
        val top = excerpt.top * size.height
        val height = ((excerpt.bottom - excerpt.top) * size.height).coerceAtLeast(2.dp.toPx())
        drawRect(QuranHifzViolet, Offset(0f, top.coerceAtMost(size.height - height)), Size(size.width, height))
        drawRoundRect(TextMuted, Offset(line / 2, line / 2), Size(size.width - line, size.height - line), CornerRadius(2.dp.toPx()), style = Stroke(line))
    }
}

/**
 * The band of the page at [assetPath] from [top] to [bottom], as wide as the page and no sharper than [width] pixels
 * need; null until the pages are downloaded. IO thread.
 */
private fun readBand(context: android.content.Context, assetPath: String, top: Float, bottom: Float, width: Int): ImageBitmap? {
    val key = "$assetPath|$top|$bottom|$width"
    QuranExcerptCache.get(key)?.let { return it }
    val scan = QuranAssets.resolve(context, assetPath) ?: return null
    return scan.open(context).use { stream ->
        val decoder = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(stream)
            else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(stream, false)
        decoder ?: return null
        try {
            // Halved while the band stays at least as wide as it is shown: a phone never needs the scan's every pixel.
            var sample = 1
            while (decoder.width / (sample * 2) >= width) sample *= 2
            val from = (top * decoder.height).toInt().coerceIn(0, decoder.height - 1)
            val to = (bottom * decoder.height).toInt().coerceIn(from + 1, decoder.height)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            decoder.decodeRegion(Rect(0, from, decoder.width, to), options)?.asImageBitmap()?.also { QuranExcerptCache.put(key, it) }
        } finally {
            decoder.recycle()
        }
    }
}

/** TalkBack, or another service that explores by touch, is on: then nothing on the page can be erased by finger. */
@Composable
internal fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var exploring by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { exploring = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return exploring
}
