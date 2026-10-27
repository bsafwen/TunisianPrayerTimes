package com.tunisianprayertimes.ui

import android.graphics.Paint
import android.graphics.Path
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.quran.QuranHighlightRect
import com.tunisianprayertimes.quran.QuranReflowPlaced
import com.tunisianprayertimes.quran.QuranVectorLayer
import com.tunisianprayertimes.quran.QuranVectorPage
import com.tunisianprayertimes.quran.QuranVectorUnitKind
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.quranReflow
import kotlin.math.roundToInt

/** What a mushaf page shows: the scan, or the page redrawn from the print's own ink ([QuranVectorPage]). */
internal sealed interface QuranPageArt {
    /** The scan's size in pixels: both have it, so a rectangle of the scan lands on either. */
    val width: Int
    val height: Int

    class Scan(val image: ImageBitmap) : QuranPageArt {
        override val width get() = image.width
        override val height get() = image.height
    }

    class Redrawn(val page: QuranVectorPage) : QuranPageArt {
        override val width get() = page.width
        override val height get() = page.height

        /** The page's ink by colour, one even-odd path each, in scan pixels; the ornament tiles apart, with their boxes. */
        private val inks: List<Pair<Int, Path>>
        private val tiles: List<Triple<Int, Path, IntArray>>

        init {
            val byColour = LinkedHashMap<Int, Path>()
            val tileList = mutableListOf<Triple<Int, Path, IntArray>>()
            for (piece in page.pieces) for (layer in piece.layers) {
                val clip = layer.clip
                if (clip == null) {
                    layer.appendTo(byColour.getOrPut(layer.colour) { Path().apply { fillType = Path.FillType.EVEN_ODD } })
                } else {
                    tileList += Triple(layer.colour, Path().apply { fillType = Path.FillType.EVEN_ODD; layer.appendTo(this) }, clip)
                }
            }
            inks = byColour.map { (colour, path) -> colour to path }
            tiles = tileList
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        /** Draws the page with its top left at [origin], [scale] screen pixels to a scan pixel. */
        fun draw(canvas: android.graphics.Canvas, origin: Offset, scale: Float, alpha: Float = 1f) {
            canvas.save()
            canvas.translate(origin.x, origin.y)
            canvas.scale(scale, scale)
            val a = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
            for ((colour, path) in inks) {
                paint.color = colour
                paint.alpha = a
                canvas.drawPath(path, paint)
            }
            // A tile is clipped to its box, which snaps to whole pixels: neighbouring tiles meet without a seam.
            for ((colour, path, box) in tiles) {
                canvas.save()
                canvas.clipRect(box[0].toFloat(), box[1].toFloat(), box[2].toFloat(), box[3].toFloat())
                paint.color = colour
                paint.alpha = a
                canvas.drawPath(path, paint)
                canvas.restore()
            }
            canvas.restore()
        }

        /** The pieces of one unit (a word, a ring, a heading) by colour, in scan pixels; made when first asked for. */
        private val unitInks = HashMap<Int, List<Pair<Int, Path>>>()

        fun unitInks(unit: Int): List<Pair<Int, Path>> = unitInks.getOrPut(unit) {
            val byColour = LinkedHashMap<Int, Path>()
            for (i in page.units[unit].pieces) for (layer in page.pieces[i].layers) {
                layer.appendTo(byColour.getOrPut(layer.colour) { Path().apply { fillType = Path.FillType.EVEN_ODD } })
            }
            byColour.map { (colour, path) -> colour to path }
        }
    }
}

/** The layer's contours added to [path], in scan pixels. */
private fun QuranVectorLayer.appendTo(path: Path) {
    val k = 1f / QuranVectorPage.STEPS
    var p = 0
    var seg = 0
    for (c in segments.indices) {
        path.moveTo(starts[2 * c] * k, starts[2 * c + 1] * k)
        repeat(segments[c]) {
            if (kinds[seg]) {
                path.cubicTo(points[p] * k, points[p + 1] * k, points[p + 2] * k, points[p + 3] * k, points[p + 4] * k, points[p + 5] * k)
                p += 6
            } else {
                // A corner: a line to the corner, then a line on.
                path.lineTo(points[p] * k, points[p + 1] * k)
                path.lineTo(points[p + 2] * k, points[p + 3] * k)
                p += 4
            }
            seg++
        }
        path.close()
    }
}

/**
 * The page art drawn into [dstSize] at [dstOffset], as [androidx.compose.ui.graphics.drawscope.DrawScope.drawImage]
 * draws a scan there, at [alpha].
 */
internal fun DrawScope.drawQuranArt(art: QuranPageArt, dstOffset: IntOffset, dstSize: IntSize, alpha: Float = 1f) {
    when (art) {
        is QuranPageArt.Scan -> drawImage(art.image, dstOffset = dstOffset, dstSize = dstSize, alpha = alpha)
        is QuranPageArt.Redrawn -> drawIntoCanvas { canvas ->
            art.draw(canvas.nativeCanvas, Offset(dstOffset.x.toFloat(), dstOffset.y.toFloat()), dstSize.width / art.width.toFloat(), alpha)
        }
    }
}

/** The redrawn page's file beside its scan's: quran/pages/page_042.webp → quran/vectors/page_042.qvp. */
internal fun quranVectorAssetPath(scanAssetPath: String): String =
    scanAssetPath.replace("quran/pages/", "quran/vectors/").substringBeforeLast('.') + ".qvp"

/**
 * How much larger than the page fitted to the screen's width the rewrapped words are drawn: as much as the phone's own
 * text is enlarged past its stable size (font size times display size), within reason.
 */
@Composable
internal fun quranReflowMagnification(): Float =
    (LocalDensity.current.fontScale * LocalConfiguration.current.densityDpi / android.util.DisplayMetrics.DENSITY_DEVICE_STABLE)
        .coerceIn(1.4f, 3f)

/**
 * The redrawn page rewrapped for large text ([quranReflow]): its rows in a column that scrolls, the same verse colours
 * as the page (selected, recited, found, a hifz point), a tap on a word giving its verse and on a heading its surah.
 * A verse in [bringIntoView] is scrolled to when it is out of view.
 */
@Composable
internal fun QuranReflowView(
    art: QuranPageArt.Redrawn,
    label: String,
    active: Boolean,
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
    val magnification = quranReflowMagnification()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White).semantics { contentDescription = label }) {
        val widthPx = constraints.maxWidth.toFloat()
        val margin = with(density) { 14.dp.toPx() }
        val rowPad = with(density) { 5.dp.toPx() }
        // Screen pixels to a scan pixel: the page fitted to the width, enlarged.
        val k = widthPx / art.width * magnification
        val page = art.page
        val reflow = remember(art, widthPx, magnification) { quranReflow(page, (widthPx - 2 * margin) / k) }
        fun inside(i: Int, rects: List<QuranHighlightRect>): Boolean {
            if (rects.isEmpty()) return false
            val u = page.units[i]
            val x = (u.left + u.right) / 2f / page.width
            val y = (u.top + u.bottom) / 2f / page.height
            return rects.any { x in it.left..it.right && y in it.top..it.bottom }
        }
        val list = rememberLazyListState()
        LaunchedEffect(active, bringIntoView, reflow) {
            if (!active || bringIntoView.isEmpty()) return@LaunchedEffect
            val row = reflow.rows.indexOfFirst { r -> r.placed.any { inside(it.unit, bringIntoView) } }
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
                        .pointerInput(row) {
                            detectTapGestures { at ->
                                val x = (at.x - margin) / k
                                val hit = row.placed.minByOrNull { distance(page, it, x) }?.takeIf { distance(page, it, x) < 30f }
                                val u = hit?.let { page.units[it.unit] }
                                when (u?.kind) {
                                    null, QuranVectorUnitKind.Basmala -> onTap(null)
                                    QuranVectorUnitKind.Heading -> onHeading(u.surah)
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
                        // The verse colours behind each word, as on the page.
                        for (p in row.placed) {
                            val tint = when {
                                inside(p.unit, selectedRects) -> Color(0x5200695C)
                                inside(p.unit, highlightRects) -> Color(0x5266BB6A)
                                inside(p.unit, foundRects) -> QuranFoundColor
                                inside(p.unit, hifzRects) -> QuranHifzViolet.copy(alpha = 0.16f)
                                else -> null
                            } ?: continue
                            val u = page.units[p.unit]
                            val r = u.right + p.dx
                            val l = r - (u.right - u.left) * p.scale
                            tintPaint.color = tint.toArgb()
                            canvas.drawRoundRect(l - 8f, row.top - rowPad / k, r + 8f, row.bottom + rowPad / k, 6f, 6f, tintPaint)
                        }
                        for (p in row.placed) drawUnit(canvas, art, p)
                        canvas.restore()
                    }
                }
            }
        }
    }
}

private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG)

/** How far a tap at [x] (scan pixels across the row) is from a placed unit's ink: 0 inside it. */
private fun distance(page: QuranVectorPage, p: QuranReflowPlaced, x: Float): Float {
    val u = page.units[p.unit]
    val r = u.right + p.dx
    val l = r - (u.right - u.left) * p.scale
    return maxOf(l - x, 0f, x - r)
}

/** One placed unit: its pieces scaled about its top right corner when shrunk, then moved (see [QuranReflowPlaced]). */
private fun drawUnit(canvas: android.graphics.Canvas, art: QuranPageArt.Redrawn, p: QuranReflowPlaced) {
    val u = art.page.units[p.unit]
    canvas.save()
    canvas.translate(u.right + p.dx, u.top + p.dy)
    canvas.scale(p.scale, p.scale)
    canvas.translate(-u.right.toFloat(), -u.top.toFloat())
    for ((colour, path) in art.unitInks(p.unit)) {
        inkPaint.color = colour
        canvas.drawPath(path, inkPaint)
    }
    canvas.restore()
}
