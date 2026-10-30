package com.tunisianprayertimes.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.quran.QuranFontBook
import com.tunisianprayertimes.quran.QuranFontDraw
import com.tunisianprayertimes.quran.QuranFontPage
import com.tunisianprayertimes.quran.QuranHighlightRect
import com.tunisianprayertimes.quran.QuranReflowPlaced
import com.tunisianprayertimes.quran.QuranRowsSeen
import com.tunisianprayertimes.quran.QuranUnitKind
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.assets.QuranAssetSource
import com.tunisianprayertimes.quran.assets.QuranAssets
import com.tunisianprayertimes.quran.assets.open
import com.tunisianprayertimes.quran.covers
import com.tunisianprayertimes.quran.distanceTo
import com.tunisianprayertimes.quran.hasTextNear
import com.tunisianprayertimes.quran.numberAt
import com.tunisianprayertimes.quran.quranReflow
import com.tunisianprayertimes.quran.rowVerses
import com.tunisianprayertimes.quran.rowsIn
import com.tunisianprayertimes.quran.span
import com.tunisianprayertimes.quran.versesUnder
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a mushaf page shows: the scan, or the page set in its own font ([QuranFontPage]). */
internal sealed interface QuranPageArt {
    /** The scan's size in pixels: both have it, so a rectangle of the scan lands on either. */
    val width: Int
    val height: Int

    class Scan(val image: ImageBitmap) : QuranPageArt {
        override val width get() = image.width
        override val height get() = image.height
    }

    class Font(val page: QuranFontPage, val book: QuranBookArt) : QuranPageArt {
        override val width get() = page.width
        override val height get() = page.height

        /** A paint for each of the book's fonts, drawn in scan pixels: the print's size. One set per thread. */
        fun paints(): Array<Paint> = book.paints(page.textSize)

        /**
         * The page drawn once into a bitmap of its size on screen, for page turns and a cover's fade, made off the main
         * thread ([rasterize]); a zoom draws the part in view again from the glyphs ([render]).
         */
        @Volatile private var raster: Pair<IntSize, ImageBitmap>? = null

        /**
         * The size last asked of [rasterize]. A page's box can change in steps (the day's review hides the header and
         * the bottom bar), each step making its bitmap on its own thread: one made for an earlier size, finishing
         * last, must not replace the bitmap of the size the page has now, which would then draw nothing.
         */
        @Volatile private var wanted: IntSize? = null

        fun rasterAt(size: IntSize): ImageBitmap? = raster?.takeIf { it.first == size }?.second

        /** The bitmap made last, whatever its size: drawn stretched while the one of the page's size is being made. */
        fun anyRaster(): ImageBitmap? = raster?.second

        fun rasterize(size: IntSize): ImageBitmap {
            rasterAt(size)?.let { return it }
            wanted = size
            val bitmap = android.graphics.Bitmap.createBitmap(size.width, size.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            draw(canvas, Offset.Zero, size.width / width.toFloat())
            val image = bitmap.asImageBitmap()
            synchronized(this) { if (wanted == size || raster == null) raster = size to image }
            return image
        }

        /** The part [region] of the page (scan pixels) drawn into a bitmap of [size]: the sharp view of a zoom. */
        fun render(region: Rect, size: IntSize): ImageBitmap {
            val bitmap = android.graphics.Bitmap.createBitmap(size.width, size.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            val k = size.width / region.width
            draw(canvas, Offset(-region.left * k, -region.top * k), k)
            return bitmap.asImageBitmap()
        }

        /** Draws the page with its top left at [origin], [scale] screen pixels to a scan pixel. Any thread. */
        fun draw(canvas: android.graphics.Canvas, origin: Offset, scale: Float, alpha: Float = 1f) {
            val paints = paints()
            val a = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
            canvas.save()
            canvas.translate(origin.x, origin.y)
            canvas.scale(scale, scale)
            drawGlyphs(canvas, page.furniture, paints, book.plates, a)
            for (u in page.units) drawGlyphs(canvas, u.draw, paints, book.plates, a)
            canvas.restore()
        }
    }
}

/** The book's fonts, read once, and its plates ([QuranFontBook]). */
internal class QuranBookArt(val book: QuranFontBook, private val typefaces: List<Typeface>) {
    val plates: List<QuranFontDraw> get() = book.plates

    fun paints(textSize: Float): Array<Paint> = Array(typefaces.size) { i ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typefaces[i]
            this.textSize = textSize
            isSubpixelText = true
            isLinearText = true
            hinting = Paint.HINTING_OFF
        }
    }
}

/** The plates of a unit or of the frame, each through its placement, then its glyphs, each in its font and colour. */
private fun drawGlyphs(canvas: android.graphics.Canvas, d: QuranFontDraw, paints: Array<Paint>, plates: List<QuranFontDraw>, alpha: Int = 255) {
    for (p in d.plates) {
        val m = p.matrix
        canvas.save()
        canvas.concat(android.graphics.Matrix().apply { setValues(floatArrayOf(m[0], m[1], m[2], m[3], m[4], m[5], 0f, 0f, 1f)) })
        drawGlyphs(canvas, plates[p.plate], paints, plates, alpha)
        canvas.restore()
    }
    for (k in d.codePoints.indices) {
        val paint = paints[d.fonts[k]]
        paint.color = d.colours[k]
        paint.alpha = alpha
        val chars = d.chars[k]
        canvas.drawText(chars, 0, chars.size, d.xs[k], d.ys[k], paint)
    }
}

/**
 * The page art drawn into [dstSize] at [dstOffset], as [androidx.compose.ui.graphics.drawscope.DrawScope.drawImage]
 * draws a scan there, at [alpha].
 */
internal fun DrawScope.drawQuranArt(art: QuranPageArt, dstOffset: IntOffset, dstSize: IntSize, alpha: Float = 1f) {
    when (art) {
        is QuranPageArt.Scan -> drawImage(art.image, dstOffset = dstOffset, dstSize = dstSize, alpha = alpha)
        is QuranPageArt.Font -> {
            val raster = art.rasterAt(dstSize)
            if (raster != null) drawImage(raster, dstOffset = dstOffset, dstSize = dstSize, alpha = alpha)
            else drawIntoCanvas { canvas ->
                art.draw(canvas.nativeCanvas, Offset(dstOffset.x.toFloat(), dstOffset.y.toFloat()), dstSize.width / art.width.toFloat(), alpha)
            }
        }
    }
}

/** Where a page of [art]'s size lands fitted into [box], as [androidx.compose.ui.layout.ContentScale.Fit] puts it. */
internal fun quranFitted(art: QuranPageArt, box: Size): Pair<IntOffset, IntSize> {
    val f = minOf(box.width / art.width, box.height / art.height)
    val drawn = IntSize((art.width * f).roundToInt(), (art.height * f).roundToInt())
    return IntOffset(((box.width.roundToInt() - drawn.width) / 2f).roundToInt(), ((box.height.roundToInt() - drawn.height) / 2f).roundToInt()) to drawn
}

/**
 * The page in its font, fitted into its box: from a bitmap of its size on screen, and once a zoom has settled
 * ([zoomed], [moved] giving where it is now) the part in view drawn again sharp from the glyphs, kept in [detail] for
 * what is drawn over the page too (the eraser's pieces). [onShown]: the page is on screen, from its bitmap.
 */
@Composable
internal fun QuranFontCanvas(
    art: QuranPageArt.Font,
    label: String,
    zoomed: Boolean,
    moved: () -> Any,
    detail: MutableState<Pair<Rect, ImageBitmap>?>,
    onShown: () -> Unit,
) {
    var box by remember(art) { mutableStateOf(Size.Zero) }
    var rasterReady by remember(art) { mutableIntStateOf(0) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentOnShown by rememberUpdatedState(onShown)
    LaunchedEffect(art, box) {
        if (box.width <= 0f) return@LaunchedEffect
        val (_, drawn) = quranFitted(art, box)
        if (art.rasterAt(drawn) == null) {
            // A box changing frame by frame (the dock sliding away as the day's review starts) shows the last bitmap
            // stretched meanwhile: only the size it settles on is drawn again, not one bitmap a frame.
            if (art.anyRaster() != null) {
                currentOnShown()
                delay(QuranRasterSettleMs)
            }
            withContext(Dispatchers.Default) { art.rasterize(drawn) }
            rasterReady++
        }
        currentOnShown()
    }
    LaunchedEffect(art, zoomed) {
        if (!zoomed) { detail.value = null; return@LaunchedEffect }
        // Each step of the zoom, a pan or a lift moves the page: drawn again once it has rested a moment.
        snapshotFlow(moved).collectLatest {
            delay(160)
            val c = coordinates?.takeIf { it.isAttached } ?: return@collectLatest
            val (at, drawn) = quranFitted(art, box)
            val f = drawn.width / art.width.toFloat()
            val window = c.boundsInWindow()
            if (window.width < 2f || window.height < 2f) return@collectLatest
            val a = c.windowToLocal(window.topLeft)
            val b = c.windowToLocal(window.bottomRight)
            val region = Rect(
                ((minOf(a.x, b.x) - at.x) / f).coerceIn(0f, art.width.toFloat()), ((minOf(a.y, b.y) - at.y) / f).coerceIn(0f, art.height.toFloat()),
                ((maxOf(a.x, b.x) - at.x) / f).coerceIn(0f, art.width.toFloat()), ((maxOf(a.y, b.y) - at.y) / f).coerceIn(0f, art.height.toFloat()),
            )
            if (region.width < 1f || region.height < 1f) return@collectLatest
            // As many pixels as the region takes on screen: the window's pixels over the part of the view it fills.
            val screenK = window.width / (maxOf(a.x, b.x) - minOf(a.x, b.x)) * f
            val size = IntSize((region.width * screenK).roundToInt().coerceAtLeast(1), (region.height * screenK).roundToInt().coerceAtLeast(1))
            detail.value = region to withContext(Dispatchers.Default) { art.render(region, size) }
        }
    }
    Canvas(
        Modifier.fillMaxSize().semantics { contentDescription = label }
            .onSizeChanged { box = Size(it.width.toFloat(), it.height.toFloat()) }
            .onGloballyPositioned { coordinates = it },
    ) {
        rasterReady
        val (at, drawn) = quranFitted(art, size)
        // Until the page's bitmap is made (off the main thread, a few frames), the one made for its last size,
        // stretched, and only before the first one nothing: its glyphs drawn here would be recorded one by one on the
        // main thread, several frames' time, for the page the pager readies beside each turn.
        (art.rasterAt(drawn) ?: art.anyRaster())?.let { drawImage(it, dstOffset = at, dstSize = drawn) }
        val sharp = detail.value
        if (zoomed && sharp != null) {
            val f = drawn.width / art.width.toFloat()
            val (r, image) = sharp
            drawImage(
                image,
                dstOffset = IntOffset((at.x + r.left * f).roundToInt(), (at.y + r.top * f).roundToInt()),
                dstSize = IntSize((r.width * f).roundToInt().coerceAtLeast(1), (r.height * f).roundToInt().coerceAtLeast(1)),
            )
        }
    }
}

/** The book's layout and fonts: quran/book/plates.json beside book_0.otf, book_1.otf, … */
private const val QuranBookFolder = "quran/book"

/** A page's layout in the book beside its scan: quran/pages/page_042.webp → quran/book/pages/page_042.bin. */
internal fun quranBookPagePath(scanAssetPath: String): String =
    scanAssetPath.replace("quran/pages/", "$QuranBookFolder/pages/").substringBeforeLast('.') + ".bin"

/** The book, once read, with the plates.json it was read from: a book downloaded again is read again. */
@Volatile private var quranBook: Pair<QuranAssetSource, QuranBookArt>? = null
private val quranBookLock = Any()

/** The book's fonts and plates, where this install has them; null otherwise. IO thread. */
private fun loadQuranBook(context: android.content.Context): QuranBookArt? {
    val layout = QuranAssets.resolve(context, "$QuranBookFolder/plates.json") ?: return null
    quranBook?.let { (source, art) -> if (source == layout) return art }
    synchronized(quranBookLock) {
        quranBook?.let { (source, art) -> if (source == layout) return art }
        val book = QuranFontBook.parse(layout.open(context).use { it.readBytes().decodeToString() })
        val typefaces = book.fonts.map { name ->
            when (val font = QuranAssets.resolve(context, "$QuranBookFolder/$name") ?: return null) {
                is QuranAssetSource.Bundled -> Typeface.createFromAsset(context.assets, font.assetPath)
                // Null for a font file that cannot be read, so the page falls back to its scan (createFromFile would
                // hand back the default typeface, which draws none of the book's glyphs).
                is QuranAssetSource.Downloaded -> Typeface.Builder(font.file).build() ?: return null
            }
        }
        // Pages read from another copy of the book number their glyphs as that copy did.
        QuranFontPageCache.evictAll()
        return QuranBookArt(book, typefaces).also { quranBook = layout to it }
    }
}

/** The last few pages read in the book's type, for the reader and the verse excerpts alike. */
private val QuranFontPageCache = android.util.LruCache<String, QuranPageArt.Font>(6)

/**
 * The page whose scan is at [scanAssetPath] in the book's type, where this install has the book; null otherwise (the
 * scan is then shown). IO thread.
 */
internal fun loadQuranFontPage(context: android.content.Context, scanAssetPath: String): QuranPageArt.Font? {
    val book = loadQuranBook(context) ?: return null
    QuranFontPageCache.get(scanAssetPath)?.takeIf { it.book === book }?.let { return it }
    val layout = QuranAssets.resolve(context, quranBookPagePath(scanAssetPath)) ?: return null
    val page = QuranFontPage.parse(layout.open(context).use { it.readBytes() }, book.book)
    return QuranPageArt.Font(page, book).also { QuranFontPageCache.put(scanAssetPath, it) }
}

/**
 * How much larger than the page fitted to the screen's width the rewrapped words are drawn: as much as the phone's own
 * text is enlarged past its stable size (font size times display size), within reason.
 */
@Composable
internal fun quranReflowMagnification(): Float =
    (LocalDensity.current.fontScale * LocalConfiguration.current.densityDpi / android.util.DisplayMetrics.DENSITY_DEVICE_STABLE)
        .coerceIn(1.4f, 3f)

/** The sizes «حجم الخط» offers, the page fitted to the screen's width being 1: the printed page, then larger rows. */
internal val QuranTextScales = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

/**
 * The size the Qur'an's text is read at: the reader's own choice in the tab ([chosen]), else the phone's: the printed
 * page, or with the phone's text large ([largeText]) its words rewrapped as much larger as the phone's text is. Above 1
 * the words are rewrapped into rows ([QuranReflowView]).
 */
@Composable
internal fun quranTextScale(chosen: Float?): Float {
    // The phone's size is read whether a size is chosen or not: composables called on one branch only change the
    // reader's composition when a size is chosen (the phone recomposed the reader into a crash).
    val large = largeText()
    val phone = quranReflowMagnification()
    return chosen ?: if (large) phone else 1f
}

/**
 * The page rewrapped for large text ([quranReflow]): its rows in a column that scrolls, the same verse colours as the
 * page (selected, recited, found, a hifz point, the surah whose card is open), a tap on a word giving its verse, on a
 * heading its surah and on the side margins paper. The rows holding [keepVisible] are kept in view, clear of the
 * header and of what covers the foot, as long as they are asked for and as that cover comes or grows; those holding
 * [bringIntoView] are scrolled to the top of the view once each time they change, when one of them is out of it. Each
 * row is drawn once into its own layer, which scrolling only moves. [magnification]: how much larger than the page
 * fitted to the width the words are drawn ([quranTextScale]); the word at the top of the view stays there as it changes.
 *
 * «مراجعة» as on the printed page: the words of covered verses under an opaque colour, their numbers in view, and with
 * [erasing] the finger erasing the cover a finger wide, row by row (see quranEraserTouch).
 */
@Composable
internal fun QuranReflowView(
    art: QuranPageArt.Font,
    label: String,
    active: Boolean,
    /** The pager rests on this page. Neither this nor [active], the page is off the screen: its rows go back to the top. */
    settled: Boolean = active,
    magnification: Float,
    highlightRects: List<QuranHighlightRect>,
    foundRects: List<QuranHighlightRect>,
    hifzRects: List<QuranHighlightRect>,
    selectedRects: List<QuranHighlightRect>,
    keepVisible: List<QuranHighlightRect> = emptyList(),
    bringIntoView: List<QuranHighlightRect>,
    /** Changes when the same [bringIntoView] is asked for again (a surah opened again from the index). */
    bringKey: Int = 0,
    topInsetPx: () -> Int,
    bottomInsetPx: () -> Int,
    /** «مراجعة»: the verses covered; a tap on a verse's number opens it. */
    hiddenRects: List<QuranHighlightRect> = emptyList(),
    /** The day's review: the verse asked, among [hiddenRects], covered in the points' colour with a fine outline. */
    askedRects: List<QuranHighlightRect> = emptyList(),
    erasing: Boolean = false,
    /** «مراجعة»: the verses whose words a stroke of the eraser went over. */
    onErased: (Set<QuranVerseReference>) -> Unit = {},
    /** «مراجعة»: a fast swipe over the cover, for the pages to follow. */
    onSwipePages: (moves: ReceiveChannel<Float>, release: Deferred<Float>) -> Unit = { _, _ -> },
    /** The rows in view count toward [onRead]: the page is in front of the reader, nothing over it. */
    counting: Boolean = false,
    /** Verses read, each once a visit: every row holding their words in view for [QuranPageSeenMs] in all. */
    onRead: (Set<QuranVerseReference>) -> Unit = {},
    onTap: (QuranVerseReference?) -> Unit,
    onHeading: (Int) -> Unit,
    /** The surahs whose heading on this page opens their card, with their names: TalkBack reaches them as actions. */
    headingNames: Map<Int, String> = emptyMap(),
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    // The rows' touch outlives recompositions: it must see what is covered now, and call the newest callbacks.
    val currentErasing by rememberUpdatedState(erasing)
    val currentOnErased by rememberUpdatedState(onErased)
    val currentOnSwipePages by rememberUpdatedState(onSwipePages)
    val currentOnRead by rememberUpdatedState(onRead)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnHeading by rememberUpdatedState(onHeading)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White).semantics {
        contentDescription = label
        // A double tap lands where TalkBack puts it, not on a heading: each one is an action of the page, as printed.
        customActions = headingNames.map { (surah, name) ->
            CustomAccessibilityAction("الاستماع إلى سورة $name أو تكرارها") { currentOnHeading(surah); true }
        }
    }) {
        val widthPx = constraints.maxWidth.toFloat()
        val margin = with(density) { 14.dp.toPx() }
        val rowPad = with(density) { 5.dp.toPx() }
        // Screen pixels to a scan pixel: the page fitted to the width, enlarged.
        val k = widthPx / art.width * magnification
        val page = art.page
        val reflow = remember(art, widthPx, magnification) { quranReflow(page, (widthPx - 2 * margin) / k) }
        val paints = remember(art) { art.paints() }
        // The verses the page's rectangles cover, read off the words they hold; a unit is coloured by its own verse:
        // a rosette and a sajda sign with the verse they end, a hizb ring never (it starts an eighth, not a verse).
        fun versesIn(rects: List<QuranHighlightRect>): Set<QuranVerseReference> = if (rects.isEmpty()) emptySet()
            else page.units.filter { u ->
                u.kind == QuranUnitKind.Word && run {
                    val x = (u.left + u.right) / 2f / page.width
                    val y = (u.top + u.bottom) / 2f / page.height
                    rects.any { x in it.left..it.right && y in it.top..it.bottom }
                }
            }.map { QuranVerseReference(it.surah, it.ayah) }.toSet()
        val selectedVerses = remember(page, selectedRects) { versesIn(selectedRects) }
        val recitedVerses = remember(page, highlightRects) { versesIn(highlightRects) }
        val foundVerses = remember(page, foundRects) { versesIn(foundRects) }
        val hifzVerses = remember(page, hifzRects) { versesIn(hifzRects) }
        val hiddenVerses = remember(page, hiddenRects) { versesIn(hiddenRects) }
        val askedVerses = remember(page, askedRects) { versesIn(askedRects) }
        val covers = remember(reflow, hiddenVerses, askedVerses) { reflow.rows.map { it.covers(page, hiddenVerses, askedVerses) } }
        val currentCovers by rememberUpdatedState(covers)
        // The heading whose surah's card is open, coloured as a verse selected is.
        val selectedHeadings = remember(page, selectedRects) {
            if (selectedRects.isEmpty()) emptySet() else page.units.indices.filter { i ->
                val u = page.units[i]
                u.kind == QuranUnitKind.Heading && run {
                    val x = (u.left + u.right) / 2f / page.width
                    val y = (u.top + u.bottom) / 2f / page.height
                    selectedRects.any { x in it.left..it.right && y in it.top..it.bottom }
                }
            }.toSet()
        }
        val keptRows = remember(reflow, keepVisible) { reflow.rowsIn(page, keepVisible) }
        val broughtRows = remember(reflow, bringIntoView) { reflow.rowsIn(page, bringIntoView) }
        fun inside(i: Int, verses: Set<QuranVerseReference>): Boolean {
            val u = page.units[i]
            return (u.kind == QuranUnitKind.Word || u.kind == QuranUnitKind.Rosette || u.kind == QuranUnitKind.Sajda) &&
                QuranVerseReference(u.surah, u.ayah) in verses
        }
        fun covered(i: Int) = page.units[i].kind == QuranUnitKind.Word && inside(i, hiddenVerses)
        // Not saved with the page: a page jumped away from (the pages beside it never composed) and back opens at its
        // top, as one swiped away from does.
        val list = remember { LazyListState() }
        /**
         * The rows wholly in view, clear of the header and of what covers the foot (the column's padding); a row taller
         * than that room while its box fills it (the phone on its side at the largest sizes), at the page's ends too.
         */
        fun rowsInView(): List<Int> {
            val info = list.layoutInfo
            val end = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
            return info.visibleItemsInfo.filter {
                (it.offset + rowPad >= 0 && it.offset + it.size - rowPad <= end) || (it.offset <= 0 && it.offset + it.size >= end)
            }.map { it.index }
        }
        // The room left between the header and what covers the foot (a card, the player, the dock), read here so that
        // a verse kept in view is looked at again as it changes.
        val topRoom = topInsetPx()
        val footRoom = bottomInsetPx()
        LaunchedEffect(active, keptRows, topRoom, footRoom) {
            if (!active || keptRows.isEmpty()) return@LaunchedEffect
            // Judged on the layout that has the new room, a frame later.
            withFrameMillis { }
            val shown = rowsInView()
            if (keptRows.any { it !in shown }) list.animateScrollToItem(keptRows.first())
        }
        // Brought into view once each time it changes (the verse recited while followed, a surah's heading just opened),
        // then the reader's own scrolling stands; a verse kept in view comes first. [bringKey]: the same asked again.
        LaunchedEffect(active, broughtRows, bringKey) {
            if (!active || broughtRows.isEmpty() || keptRows.isNotEmpty()) return@LaunchedEffect
            withFrameMillis { }
            val shown = rowsInView()
            if (broughtRows.any { it !in shown }) list.animateScrollToItem(broughtRows.first())
        }
        // The verse found: brought into view once a visit, as on the page filling the width, and not again when the
        // recitation followed meanwhile pauses; by its verses, so that another size is not another find.
        val foundRows = remember(reflow, foundRects) { reflow.rowsIn(page, foundRects) }
        val foundShown = remember { arrayOfNulls<Any>(1) }
        LaunchedEffect(active, foundVerses) {
            if (!active || foundVerses.isEmpty() || foundShown[0] == foundVerses || keptRows.isNotEmpty() || broughtRows.isNotEmpty()) {
                return@LaunchedEffect
            }
            withFrameMillis { }
            foundShown[0] = foundVerses
            val shown = rowsInView()
            if (foundRows.any { it !in shown }) list.animateScrollToItem(foundRows.first())
        }
        // Off the screen, the page goes back to its top for its next visit, as the printed page goes back to rest.
        LaunchedEffect(active, settled) {
            if (!active && !settled) {
                foundShown[0] = null
                list.scrollToItem(0)
            }
        }
        // The words at the top of the view stay there when the rows are made again (another size, another width): the
        // first unit of the first row in view, looked for in the new rows. Unless a verse is kept in view, which decides.
        val topUnit = remember { intArrayOf(-1) }
        val rowsShown = remember { arrayOfNulls<Any>(1) }
        LaunchedEffect(reflow) {
            val before = rowsShown[0]
            rowsShown[0] = reflow
            val unit = topUnit[0]
            if (before != null && before !== reflow && unit >= 0 && keptRows.isEmpty()) {
                val row = reflow.rows.indexOfFirst { r -> r.placed.any { it.unit == unit } }
                if (row >= 0) list.scrollToItem(row)
            }
            // Then the anchor follows the reader's scrolling: kept while the top row still holds it, so that each step
            // of the size starts from the same word, not from its row's first one.
            snapshotFlow { list.firstVisibleItemIndex }.collect { i ->
                reflow.rows.getOrNull(i)?.let { r -> if (r.placed.none { it.unit == topUnit[0] }) r.placed.firstOrNull()?.let { topUnit[0] = it.unit } }
            }
        }
        // Read in rows: a verse counts once the rows holding its words have been in view a while (QuranRowsSeen), from
        // the time the page comes in front of the reader again, a sheet closed, as the printed page counts from then.
        val rowVerses = remember(reflow, hiddenVerses) { reflow.rowVerses(page, hiddenVerses) }
        val currentRowVerses by rememberUpdatedState(rowVerses)
        val seen = remember(reflow, active, counting) { QuranRowsSeen(reflow.rows.size, QuranPageSeenMs) }
        LaunchedEffect(seen, counting) {
            if (!counting) return@LaunchedEffect
            while (true) {
                // The rows in view now are counted the time of a look, no more than has gone by.
                delay(QuranRowsLookMs)
                seen.shown(rowsInView(), QuranRowsLookMs)
                seen.read(currentRowVerses).takeIf { it.isNotEmpty() }?.let { currentOnRead(it) }
            }
        }
        // «مراجعة»: what the finger erased, and the piece under it while it rests there, which waits for it to leave.
        // Read only while drawing: erasing redraws the rows without recomposing anything.
        val erased = remember(reflow) { mutableStateListOf<QuranRowErased>() }
        var erasingUnder by remember(reflow) { mutableStateOf<QuranRowErased?>(null) }
        val eraseClock = remember { mutableLongStateOf(0L) }
        // A stroke under way on one row holds every other finger off every row until it lifts, as on the printed
        // page, where each finger reaches the one eraser.
        val stroking = remember(reflow) { booleanArrayOf(false) }
        val reach = with(density) { QuranEraserWidth.toPx() } / 2f / k
        /** The row under [point], on the view of row [from], and how far across it (scan pixels); null off the rows. */
        fun rowAt(from: Int, point: Offset): Pair<Int, Float>? {
            val items = list.layoutInfo.visibleItemsInfo
            val y = (items.firstOrNull { it.index == from } ?: return null).offset + point.y
            val item = items.firstOrNull { y >= it.offset && y < it.offset + it.size } ?: return null
            return item.index to (point.x - margin) / k
        }
        /** «مراجعة»: the piece of [row] at [x] a finger wide; null off its text. */
        fun erasedAt(row: Int, x: Float, at: Long): QuranRowErased? =
            if (reflow.rows[row].hasTextNear(page, x, QuranRowReach)) QuranRowErased(row, x - reach, x + reach, at) else null
        fun report(piece: QuranRowErased) {
            reflow.rows[piece.row].versesUnder(page, piece.left, piece.right).takeIf { it.isNotEmpty() }?.let { currentOnErased(it) }
        }
        /**
         * «مراجعة»: adds [piece], cut out of the older pieces it overlaps, so that each point fades on its own clock. The
         * pieces of one moment ([QuranEraseJoinMs]) make one: a stroke leaves a few pieces, not one a move.
         */
        fun addErased(piece: QuranRowErased) {
            report(piece)
            val moment = piece.at / QuranEraseJoinMs
            val joined = erased.filter { it.row == piece.row && it.left <= piece.right && piece.left <= it.right && it.at / QuranEraseJoinMs == moment }
            erased.removeAll(joined)
            val whole = if (joined.isEmpty()) piece else QuranRowErased(
                piece.row, minOf(piece.left, joined.minOf { it.left }), maxOf(piece.right, joined.maxOf { it.right }),
                maxOf(piece.at, joined.maxOf { it.at }),
            )
            val under = erased.filter { it.row == whole.row && it.left < whole.right && whole.left < it.right }
            if (under.isNotEmpty()) {
                erased.removeAll(under)
                under.forEach { old ->
                    if (old.left < whole.left) erased += QuranRowErased(old.row, old.left, whole.left, old.at)
                    if (old.right > whole.right) erased += QuranRowErased(old.row, whole.right, old.right, old.at)
                }
            }
            erased += whole
        }
        /** «مراجعة»: erases every row the finger crossed from [from] to [to], on the view of row [origin]. */
        fun eraseAlong(origin: Int, from: Offset, to: Offset) {
            if (to == from) return
            val now = SystemClock.uptimeMillis()
            val step = with(density) { QuranEraserWidth.toPx() } / 4f
            val steps = ((to - from).getDistance() / step).toInt().coerceAtLeast(1)
            // Each row crossed becomes one piece, as wide as the way across it.
            (0 until steps).mapNotNull { s -> rowAt(origin, from + (to - from) * (s / steps.toFloat()))?.let { (row, x) -> erasedAt(row, x, now) } }
                .groupBy { it.row }
                .forEach { (row, pieces) -> addErased(QuranRowErased(row, pieces.minOf { it.left }, pieces.maxOf { it.right }, now)) }
            erasingUnder = rowAt(origin, to)?.let { (row, x) -> erasedAt(row, x, now) }
        }
        fun releaseEraser() {
            erasingUnder?.let { addErased(QuranRowErased(it.row, it.left, it.right, SystemClock.uptimeMillis())) }
            erasingUnder = null
        }
        // «مراجعة»: erased pieces fade back, each on its own clock, in real time. Until the first starts to fade nothing
        // changes, and the rows are not drawn again for the clock.
        LaunchedEffect(erased) {
            snapshotFlow { erased.isNotEmpty() }.collect {
                while (erased.isNotEmpty()) {
                    val wait = erased.minOf { it.at } + QuranErasedMs - SystemClock.uptimeMillis()
                    if (wait > 0) {
                        delay(wait)
                        continue
                    }
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
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = with(density) { PaddingValues(top = topInsetPx().toDp(), bottom = bottomInsetPx().toDp()) },
        ) {
            itemsIndexed(reflow.rows) { index, row ->
                val heightPx = (row.bottom - row.top) * k + 2 * rowPad
                Canvas(
                    Modifier.fillMaxWidth().height(with(density) { heightPx.toDp() })
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .pointerInput(row) {
                            detectTapGestures { at ->
                                if (stroking[0]) return@detectTapGestures
                                // The side margins are paper at any size, as the printed page's margin is: a tap
                                // there brings the tools back or closes a card, and never opens the nearest verse.
                                if (at.x < margin || at.x > size.width - margin) {
                                    currentOnTap(null)
                                    return@detectTapGestures
                                }
                                val x = (at.x - margin) / k
                                if (currentErasing) {
                                    // «مراجعة»: a tap on a verse's number opens that verse; on the text it erases
                                    // there, and only there; off the text it is a tap on paper.
                                    val numbered = row.numberAt(page, x, QuranNumberTouchSlop * page.width)
                                    val piece = if (numbered == null) erasedAt(index, x, SystemClock.uptimeMillis()) else null
                                    if (piece != null) addErased(piece) else currentOnTap(numbered)
                                    return@detectTapGestures
                                }
                                val hit = row.placed.minByOrNull { it.distanceTo(page, x) }?.takeIf { it.distanceTo(page, x) < QuranRowReach }
                                val u = hit?.let { page.units[it.unit] }
                                when (u?.kind) {
                                    null, QuranUnitKind.Basmala -> currentOnTap(null)
                                    QuranUnitKind.Heading -> currentOnHeading(u.surah)
                                    else -> currentOnTap(QuranVerseReference(u.surah, u.ayah))
                                }
                            }
                        }
                        // Innermost: the eraser's touch (see quranEraserTouch).
                        .pointerInput(row) {
                            quranEraserTouch(object : QuranEraserTarget {
                                // Not for a finger that comes down while another row's stroke goes on.
                                override val erasing get() = currentErasing && !stroking[0]
                                // The column catches a touch that stops its fling: it comes down consumed.
                                override val stopped get() = false
                                override fun onCover(point: Offset) =
                                    ((point.x - margin) / k).let { x -> currentCovers[index].any { x in it.left..it.right } }
                                override fun onNumber(point: Offset) =
                                    row.numberAt(page, (point.x - margin) / k, QuranNumberTouchSlop * page.width) != null
                                // A drag up or down before the hold scrolls the rows.
                                override fun leavesDrag(way: Offset) = abs(way.y) > abs(way.x)
                                override fun start(point: Offset): Boolean {
                                    if (stroking[0]) return false
                                    val piece = erasedAt(index, (point.x - margin) / k, 0L) ?: return false
                                    stroking[0] = true
                                    erasingUnder = piece
                                    report(piece)
                                    return true
                                }
                                override fun along(from: Offset, to: Offset) = eraseAlong(index, from, to)
                                override fun release() {
                                    releaseEraser()
                                    stroking[0] = false
                                }
                                override fun swipePages(moves: ReceiveChannel<Float>, release: Deferred<Float>) =
                                    currentOnSwipePages(moves, release)
                            }, haptics)
                        },
                ) {
                    val rowCovers = covers[index]
                    // Only a row with covers reads the eraser's pieces, and only one with pieces the clock: the others
                    // never draw again for a stroke.
                    val pieces = if (rowCovers.isEmpty()) emptyList() else erased.filter { it.row == index }
                    val under = if (rowCovers.isEmpty()) null else erasingUnder?.takeIf { it.row == index }
                    val now = if (pieces.isEmpty()) 0L else eraseClock.longValue
                    val outline = 1.dp.toPx() / k
                    val corner = 3.dp.toPx() / k
                    drawIntoCanvas { c ->
                        val canvas = c.nativeCanvas
                        canvas.save()
                        canvas.translate(margin, rowPad - row.top * k)
                        canvas.scale(k, k)
                        // The covers reach past the row's layer, which cuts them: the covers of rows one under the
                        // other meet with no seam, as a verse's lines do on the printed page.
                        val top = row.top - (rowPad + 1f) / k
                        val bottom = row.top + (size.height - rowPad + 1f) / k
                        // The verse colours behind the words, as on the page: one band for words side by side in
                        // the same colour, the spaces between them included.
                        fun tintOf(p: QuranReflowPlaced): Color? = when {
                            inside(p.unit, selectedVerses) || p.unit in selectedHeadings -> Color(0x5200695C)
                            inside(p.unit, recitedVerses) -> Color(0x5266BB6A)
                            inside(p.unit, foundVerses) -> QuranFoundColor
                            inside(p.unit, hifzVerses) -> QuranHifzViolet.copy(alpha = 0.16f)
                            else -> null
                        }
                        fun drawTints() {
                            var k0 = 0
                            while (k0 < row.placed.size) {
                                val tint = tintOf(row.placed[k0])
                                var k1 = k0
                                while (k1 + 1 < row.placed.size && tintOf(row.placed[k1 + 1]) == tint) k1++
                                if (tint != null) {
                                    var l = Float.MAX_VALUE
                                    var r = -Float.MAX_VALUE
                                    for (p in row.placed.subList(k0, k1 + 1)) {
                                        val span = p.span(page)
                                        r = maxOf(r, span.endInclusive)
                                        l = minOf(l, span.start)
                                    }
                                    tintPaint.color = tint.toArgb()
                                    canvas.drawRoundRect(l - 8f, row.top - rowPad / k, r + 8f, row.bottom + rowPad / k, 6f, 6f, tintPaint)
                                }
                                k0 = k1 + 1
                            }
                        }
                        // «مراجعة»: the covers, then the verse colours over them as over the paper, then every word
                        // they leave in view; the words they hide are drawn only where the finger erased the cover.
                        for (cover in rowCovers) {
                            coverPaint.color = (if (cover.asked) QuranAskedMask else QuranReviewMask).toArgb()
                            canvas.drawRect(cover.left, top, cover.right, bottom, coverPaint)
                        }
                        drawTints()
                        for (p in row.placed) if (!covered(p.unit)) drawUnit(canvas, page, p, paints, art.book.plates)
                        // Where the finger erased: the row as it is uncovered, clear until the piece's time is up, then
                        // fading back. The piece under the finger waits for it to leave. The clear pieces are drawn
                        // together, through one clip (not smoothed, as the covers), a fading one at its own alpha.
                        fun clipOf(piece: QuranRowErased, into: android.graphics.Path = android.graphics.Path()) = into.apply {
                            for (cover in rowCovers) {
                                val l = maxOf(piece.left, cover.left)
                                val r = minOf(piece.right, cover.right)
                                if (l < r) addRect(l, top, r, bottom, android.graphics.Path.Direction.CW)
                            }
                        }
                        fun uncovered(clip: android.graphics.Path, alpha: Float) {
                            if (clip.isEmpty) return
                            val bounds = android.graphics.RectF().also { clip.computeBounds(it, true) }
                            canvas.save()
                            canvas.clipPath(clip)
                            if (alpha < 1f) canvas.saveLayerAlpha(bounds, (alpha * 255).roundToInt())
                            canvas.drawColor(android.graphics.Color.WHITE)
                            drawTints()
                            // Only the covered words the clip reaches: a piece is a word or two of the row.
                            for (p in row.placed) {
                                if (covered(p.unit) && p.span(page).let { it.start < bounds.right && it.endInclusive > bounds.left }) {
                                    drawUnit(canvas, page, p, paints, art.book.plates)
                                }
                            }
                            if (alpha < 1f) canvas.restore()
                            canvas.restore()
                        }
                        val clear = android.graphics.Path()
                        under?.let { clipOf(it, clear) }
                        pieces.forEach { piece ->
                            val amount = 1f - ((now - piece.at - QuranErasedMs).toFloat() / QuranEraseFadeMs).coerceIn(0f, 1f)
                            if (amount >= 1f) clipOf(piece, clear) else if (amount > 0f) uncovered(clipOf(piece), amount)
                        }
                        uncovered(clear, 1f)
                        // The verse asked: its covers outlined by a fine solid line, a cover, not a selection.
                        for (cover in rowCovers) if (cover.asked) {
                            outlinePaint.strokeWidth = outline
                            canvas.drawRoundRect(
                                cover.left + outline / 2f, row.top - rowPad / k + outline / 2f, cover.right - outline / 2f,
                                row.bottom + rowPad / k - outline / 2f, corner, corner, outlinePaint,
                            )
                        }
                        canvas.restore()
                    }
                }
            }
        }
    }
}

/** How long a page's box keeps one size before its bitmap is made again at it (see QuranFontCanvas). */
private const val QuranRasterSettleMs = 120L

/** «مراجعة»: a piece of a row the finger has erased, from [left] to [right] across it, from [at] on (as QuranErased). */
private class QuranRowErased(val row: Int, val left: Float, val right: Float, val at: Long)

/** How far from a unit's ink, in scan pixels, a touch on a row still reaches it. */
private const val QuranRowReach = 30f

/** How often the rows in view are looked at, to add up their time. */
private const val QuranRowsLookMs = 250L

/** «مراجعة»: pieces erased within one such moment are one piece, fading on its last touch's clock: no eye tells. */
private const val QuranEraseJoinMs = 200L

private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
/** Not smoothed: a cover's edges are whole pixels, as are the erased pieces cut out of it. */
private val coverPaint = Paint()
private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = QuranHifzViolet.copy(alpha = 0.8f).toArgb()
}

/** One placed unit: its glyphs scaled about its top right corner when shrunk, then moved (see [QuranReflowPlaced]). */
private fun drawUnit(canvas: android.graphics.Canvas, page: QuranFontPage, p: QuranReflowPlaced, paints: Array<Paint>, plates: List<QuranFontDraw>) {
    val u = page.units[p.unit]
    canvas.save()
    canvas.translate(u.right + p.dx, u.top + p.dy)
    canvas.scale(p.scale, p.scale)
    canvas.translate(-u.right.toFloat(), -u.top.toFloat())
    drawGlyphs(canvas, u.draw, paints, plates)
    canvas.restore()
}
