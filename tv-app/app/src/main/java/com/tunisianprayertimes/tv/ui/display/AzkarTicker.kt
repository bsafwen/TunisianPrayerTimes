package com.tunisianprayertimes.tv.ui.display

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.mosque.AdhkarSlide
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.theme.Amiri
import com.tunisianprayertimes.tv.ui.theme.FriezeEdge
import com.tunisianprayertimes.tv.ui.theme.KhatamStar
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.Readex
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import com.tunisianprayertimes.tv.ui.theme.starFrieze
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * The ticker under the arcade: one short text at a time with its source, between two silver khatam
 * stars, and a faint frieze of stars at each end. Paged, never scrolled: each text stays for as long
 * as it takes to read and gives way to the next with a crossfade. A text too long for the line is
 * set smaller, then on two lines, then page by page; it is never cut, and never stands over the arcade.
 */
@Composable
fun AzkarTicker(items: List<AdhkarSlide>, modifier: Modifier = Modifier) {
    // The screen gives way to the adhan and the announcements many times a day; starting anywhere in
    // the round, rather than always at its first text, keeps every text on the wall as often.
    var index by remember(items) { mutableIntStateOf(MainScreenModel.tickerStart(items, Random)) }
    val slide = items.getOrNull(index)
    if (slide != null && items.size > 1) {
        LaunchedEffect(items, index) {
            delay(MainScreenModel.tickerDwellMillis(slide))
            index = (index + 1) % items.size
        }
    }
    Box(modifier.fillMaxWidth().height(TICKER_HEIGHT + RULE)) {
        Box(Modifier.fillMaxWidth().height(RULE).background(Midad.Rule))
        Box(Modifier.align(AbsoluteAlignment.BottomRight).size(FRIEZE_WIDTH, TICKER_HEIGHT).starFrieze(FriezeEdge.RIGHT))
        Box(Modifier.align(AbsoluteAlignment.BottomLeft).size(FRIEZE_WIDTH, TICKER_HEIGHT).starFrieze(FriezeEdge.LEFT))
        if (slide != null) {
            BoxWithConstraints(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(TICKER_HEIGHT)
                    .padding(horizontal = TEXT_MARGIN),
            ) {
                val width = constraints.maxWidth
                Crossfade(targetState = slide, animationSpec = tween(FADE_MILLIS), label = "ticker") { shown ->
                    TickerLine(shown, width, stays = items.size == 1)
                }
            }
        }
    }
}

/** [slide] on the line; [stays] when it is the ticker's only one, so the line never moves on from it. */
@Composable
private fun TickerLine(slide: AdhkarSlide, widthPx: Int, stays: Boolean) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val fit = remember(slide, widthPx, density) { fitLine(measurer, density, slide, widthPx) }
    // A text too long for the smallest lines shows its pages in turn, in the time the slide has. It
    // stops on its last page as the next slide comes, or its first would fade back in over it.
    var page by remember(fit) { mutableIntStateOf(0) }
    if (fit.pages.size > 1) {
        LaunchedEffect(fit, stays) {
            while (stays || page < fit.pages.lastIndex) {
                delay(MainScreenModel.tickerDwellMillis(slide) / fit.pages.size)
                page = (page + 1) % fit.pages.size
            }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Two or three lines may stand a little taller than the ticker: into the space around it, never
        // clipped, and never past MAX_TEXT_HEIGHT.
        Row(
            Modifier.fillMaxWidth().wrapContentHeight(unbounded = true),
            horizontalArrangement = Arrangement.spacedBy(GAP, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KhatamStar(STAR)
            Crossfade(targetState = fit.pages[page], animationSpec = tween(FADE_MILLIS), label = "ticker page") { shown ->
                Text(
                    shown,
                    style = fit.text,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier.widthIn(max = fit.textMaxWidth),
                )
            }
            if (fit.source != null) {
                Text(
                    fit.source,
                    style = fit.reference,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = fit.referenceMaxWidth),
                )
            }
            KhatamStar(STAR)
        }
    }
}

/**
 * How a slide is set: its text (an announcement's with its numbers kept left to right), or its pages
 * when it is too long for the smallest lines, and its source line, or none.
 */
private class TickerFit(
    val pages: List<String>,
    val text: TextStyle,
    val textMaxWidth: Dp,
    val source: String?,
    val reference: TextStyle,
    val referenceMaxWidth: Dp,
)

/** How a kind of text is set, how far apart its lines stand, and how far it may shrink. */
private class TickerType(val family: FontFamily, val lineHeight: Float, val size: Int, val minOneLine: Int, val minTwoLines: Int) {
    fun style(size: Int): TextStyle = midadStyle(size.sp, family = family, lineHeight = lineHeight)
}

/**
 * The adhkar in Amiri with their vowel marks, as on the board. Its marks reach far above and below
 * the letters: closer lines than this put the second line's marks into the first one's.
 */
private val SACRED = TickerType(Amiri, lineHeight = 1.75f, size = 25, minOneLine = 19, minTwoLines = 14)
/** A mosque's written announcement: its own words, in the interface's face. */
private val ANNOUNCEMENT = TickerType(Readex, lineHeight = 1.3f, size = 20, minOneLine = 16, minTwoLines = 13)

/** The source's place on the line: [width] px at [style]; [text] is null for a slide without one. */
private class SourceFit(val text: String?, val style: TextStyle, val width: Int)

/**
 * The sizes for [slide] on a line [widthPx] wide, all within [MAX_TEXT_HEIGHT]. The source takes what
 * it needs, up to a third of the line (smaller, then on two lines past that), a long one on two lines
 * up to half of it, else by its first clause; the text has the rest: at its size if it fits on
 * one line, else a little smaller, else on two lines, and at the smallest size page by page.
 */
private fun fitLine(measurer: TextMeasurer, density: Density, slide: AdhkarSlide, widthPx: Int): TickerFit = with(density) {
    val available = (widthPx - (STAR * 2 + GAP * 3).roundToPx()).coerceAtLeast(1)
    val maxHeight = MAX_TEXT_HEIGHT.roundToPx()
    fun layout(text: String, style: TextStyle, width: Int) = measurer.measure(text, style, constraints = Constraints(maxWidth = width.coerceAtLeast(1)))
    fun oneLine(text: String, style: TextStyle) = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width

    val sources = MainScreenModel.tickerSources(slide)
    val source = sources.firstNotNullOfOrNull { text ->
        val third = available / 3
        fun twoLines(width: Int) = layout(text, REFERENCE_SMALL, width).let { it.lineCount <= 2 && it.size.height <= maxHeight }
        val large = oneLine(text, REFERENCE)
        val small = oneLine(text, REFERENCE_SMALL)
        when {
            large <= third -> SourceFit(text, REFERENCE, large)
            small <= third -> SourceFit(text, REFERENCE_SMALL, small)
            twoLines(third) -> SourceFit(text, REFERENCE_SMALL, third)
            twoLines(available / 2) -> SourceFit(text, REFERENCE_SMALL, available / 2)
            else -> null
        }
    } ?: sources.lastOrNull()?.let { SourceFit(it, REFERENCE_SMALL, available / 2) } ?: SourceFit(null, REFERENCE, 0)
    val textMax = (available - source.width).coerceAtLeast(1)

    val announcement = MainScreenModel.isAnnouncement(slide)
    val type = if (announcement) ANNOUNCEMENT else SACRED
    val text = if (announcement) TvStrings.mosqueText(slide.text) else slide.text
    val style = (type.size downTo type.minOneLine).map(type::style).firstOrNull { layout(text, it, textMax).lineCount == 1 }
        ?: (type.minOneLine downTo type.minTwoLines).map(type::style).firstOrNull { style ->
            layout(text, style, textMax).let { it.lineCount <= 2 && it.size.height <= maxHeight }
        }
    val pages = if (style != null) listOf(text) else {
        // At the smallest size, as many lines at a time as the ticker holds.
        val lines = layout(text, type.style(type.minTwoLines), textMax)
        val perPage = (0 until lines.lineCount).count { lines.getLineBottom(it) <= maxHeight }
        MainScreenModel.tickerPages(text, (0 until lines.lineCount).map(lines::getLineEnd), perPage)
    }
    TickerFit(pages, style ?: type.style(type.minTwoLines), textMax.toDp(), source.text, source.style, source.width.coerceAtLeast(1).toDp())
}

private val TICKER_HEIGHT = 45.dp
private val RULE = 1.dp
private val FRIEZE_WIDTH = 230.dp
/** The text keeps clear of the frieze's solid ends. */
private val TEXT_MARGIN = 60.dp
private val STAR = 13.dp
private val GAP = 14.dp
/** The lines may overflow the ticker by a few dp into the space above and below it, clear of the niches and the screen's edge. */
private val MAX_TEXT_HEIGHT = 56.dp
private const val FADE_MILLIS = 500
private val REFERENCE = midadStyle(14.sp, color = Midad.Muted)
private val REFERENCE_SMALL = midadStyle(11.sp, color = Midad.Muted)
