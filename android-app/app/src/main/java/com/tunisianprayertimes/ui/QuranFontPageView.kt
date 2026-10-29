package com.tunisianprayertimes.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.quran.QuranFontBook
import com.tunisianprayertimes.quran.QuranFontDraw
import com.tunisianprayertimes.quran.QuranFontPage
import com.tunisianprayertimes.quran.QuranHighlightRect
import com.tunisianprayertimes.quran.QuranReflowPlaced
import com.tunisianprayertimes.quran.QuranUnitKind
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.assets.QuranAssetSource
import com.tunisianprayertimes.quran.assets.QuranAssets
import com.tunisianprayertimes.quran.assets.open
import com.tunisianprayertimes.quran.quranReflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

        fun rasterAt(size: IntSize): ImageBitmap? = raster?.takeIf { it.first == size }?.second

        fun rasterize(size: IntSize): ImageBitmap {
            rasterAt(size)?.let { return it }
            val bitmap = android.graphics.Bitmap.createBitmap(size.width, size.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            draw(canvas, Offset.Zero, size.width / width.toFloat())
            return bitmap.asImageBitmap().also { raster = size to it }
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
 * ([zoomed], [moved] changing with each step of it) the part in view drawn again sharp from the glyphs.
 */
@Composable
internal fun QuranFontCanvas(art: QuranPageArt.Font, label: String, zoomed: Boolean, moved: Any) {
    var box by remember(art) { mutableStateOf(Size.Zero) }
    var rasterReady by remember(art) { mutableIntStateOf(0) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var detail by remember(art) { mutableStateOf<Pair<Rect, ImageBitmap>?>(null) }
    LaunchedEffect(art, box) {
        if (box.width <= 0f) return@LaunchedEffect
        val (_, drawn) = quranFitted(art, box)
        if (art.rasterAt(drawn) == null) {
            withContext(Dispatchers.Default) { art.rasterize(drawn) }
            rasterReady++
        }
    }
    LaunchedEffect(art, zoomed, moved) {
        if (!zoomed) { detail = null; return@LaunchedEffect }
        delay(160)
        val c = coordinates?.takeIf { it.isAttached } ?: return@LaunchedEffect
        val (at, drawn) = quranFitted(art, box)
        val f = drawn.width / art.width.toFloat()
        val window = c.boundsInWindow()
        if (window.width < 2f || window.height < 2f) return@LaunchedEffect
        val a = c.windowToLocal(window.topLeft)
        val b = c.windowToLocal(window.bottomRight)
        val region = Rect(
            ((minOf(a.x, b.x) - at.x) / f).coerceIn(0f, art.width.toFloat()), ((minOf(a.y, b.y) - at.y) / f).coerceIn(0f, art.height.toFloat()),
            ((maxOf(a.x, b.x) - at.x) / f).coerceIn(0f, art.width.toFloat()), ((maxOf(a.y, b.y) - at.y) / f).coerceIn(0f, art.height.toFloat()),
        )
        if (region.width < 1f || region.height < 1f) return@LaunchedEffect
        // As many pixels as the region takes on screen: the window's pixels over the part of the view it fills.
        val screenK = window.width / (maxOf(a.x, b.x) - minOf(a.x, b.x)) * f
        val size = IntSize((region.width * screenK).roundToInt().coerceAtLeast(1), (region.height * screenK).roundToInt().coerceAtLeast(1))
        detail = region to withContext(Dispatchers.Default) { art.render(region, size) }
    }
    Canvas(
        Modifier.fillMaxSize().semantics { contentDescription = label }
            .onSizeChanged { box = Size(it.width.toFloat(), it.height.toFloat()) }
            .onGloballyPositioned { coordinates = it },
    ) {
        rasterReady
        val (at, drawn) = quranFitted(art, size)
        // Until the page's bitmap is made (off the main thread, a few frames), nothing: its glyphs drawn here would be
        // recorded one by one on the main thread, several frames' time, for the page the pager readies beside each turn.
        art.rasterAt(drawn)?.let { drawImage(it, dstOffset = at, dstSize = drawn) }
        val sharp = detail
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
    val page = QuranFontPage.parse(layout.open(context).use { it.readBytes() })
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
 * page (selected, recited, found, a hifz point), a tap on a word giving its verse and on a heading its surah. A verse
 * in [bringIntoView] is scrolled to when it is out of view. Each row is drawn once into its own layer, which scrolling
 * only moves. [magnification]: how much larger than the page fitted to the width the words are drawn
 * ([quranTextScale]).
 */
@Composable
internal fun QuranReflowView(
    art: QuranPageArt.Font,
    label: String,
    active: Boolean,
    magnification: Float,
    highlightRects: List<QuranHighlightRect>,
    foundRects: List<QuranHighlightRect>,
    hifzRects: List<QuranHighlightRect>,
    selectedRects: List<QuranHighlightRect>,
    bringIntoView: List<QuranHighlightRect>,
    topInsetPx: () -> Int,
    bottomInsetPx: () -> Int,
    onTap: (QuranVerseReference?) -> Unit,
    onHeading: (Int) -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White).semantics { contentDescription = label }) {
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
        val keptVerses = remember(page, bringIntoView) { versesIn(bringIntoView) }
        fun inside(i: Int, verses: Set<QuranVerseReference>): Boolean {
            val u = page.units[i]
            return (u.kind == QuranUnitKind.Word || u.kind == QuranUnitKind.Rosette || u.kind == QuranUnitKind.Sajda) &&
                QuranVerseReference(u.surah, u.ayah) in verses
        }
        val list = rememberLazyListState()
        LaunchedEffect(active, keptVerses, reflow) {
            if (!active || keptVerses.isEmpty()) return@LaunchedEffect
            val row = reflow.rows.indexOfFirst { r -> r.placed.any { inside(it.unit, keptVerses) } }
            if (row >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == row }) list.animateScrollToItem(row)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = with(density) { PaddingValues(top = topInsetPx().toDp(), bottom = bottomInsetPx().toDp()) },
        ) {
            items(reflow.rows) { row ->
                val heightPx = (row.bottom - row.top) * k + 2 * rowPad
                Canvas(
                    Modifier.fillMaxWidth().height(with(density) { heightPx.toDp() })
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .pointerInput(row) {
                            detectTapGestures { at ->
                                val x = (at.x - margin) / k
                                val hit = row.placed.minByOrNull { distance(page, it, x) }?.takeIf { distance(page, it, x) < 30f }
                                val u = hit?.let { page.units[it.unit] }
                                when (u?.kind) {
                                    null, QuranUnitKind.Basmala -> onTap(null)
                                    QuranUnitKind.Heading -> onHeading(u.surah)
                                    else -> onTap(QuranVerseReference(u.surah, u.ayah))
                                }
                            }
                        },
                ) {
                    drawIntoCanvas { c ->
                        val canvas = c.nativeCanvas
                        canvas.save()
                        canvas.translate(margin, rowPad - row.top * k)
                        canvas.scale(k, k)
                        // The verse colours behind the words, as on the page: one band for words side by side in
                        // the same colour, the spaces between them included.
                        fun tintOf(p: QuranReflowPlaced): Color? = when {
                            inside(p.unit, selectedVerses) -> Color(0x5200695C)
                            inside(p.unit, recitedVerses) -> Color(0x5266BB6A)
                            inside(p.unit, foundVerses) -> QuranFoundColor
                            inside(p.unit, hifzVerses) -> QuranHifzViolet.copy(alpha = 0.16f)
                            else -> null
                        }
                        var k0 = 0
                        while (k0 < row.placed.size) {
                            val tint = tintOf(row.placed[k0])
                            var k1 = k0
                            while (k1 + 1 < row.placed.size && tintOf(row.placed[k1 + 1]) == tint) k1++
                            if (tint != null) {
                                var l = Float.MAX_VALUE
                                var r = -Float.MAX_VALUE
                                for (p in row.placed.subList(k0, k1 + 1)) {
                                    val u = page.units[p.unit]
                                    r = maxOf(r, u.right + p.dx)
                                    l = minOf(l, u.right + p.dx - (u.right - u.left) * p.scale)
                                }
                                tintPaint.color = tint.toArgb()
                                canvas.drawRoundRect(l - 8f, row.top - rowPad / k, r + 8f, row.bottom + rowPad / k, 6f, 6f, tintPaint)
                            }
                            k0 = k1 + 1
                        }
                        for (p in row.placed) drawUnit(canvas, page, p, paints, art.book.plates)
                        canvas.restore()
                    }
                }
            }
        }
    }
}

private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)

/** How far a tap at [x] (scan pixels across the row) is from a placed unit's ink: 0 inside it. */
private fun distance(page: QuranFontPage, p: QuranReflowPlaced, x: Float): Float {
    val u = page.units[p.unit]
    val r = u.right + p.dx
    val l = r - (u.right - u.left) * p.scale
    return maxOf(l - x, 0f, x - r)
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
