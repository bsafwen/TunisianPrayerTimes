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
 * set smaller, then on two lines; it is never cut.
 */
@Composable
fun AzkarTicker(items: List<AdhkarSlide>, modifier: Modifier = Modifier) {
    // The screen gives way to the adhan and the announcements many times a day; starting anywhere in
    // the round, rather than always at its first text, keeps every text on the wall as often.
    var index by remember(items) { mutableIntStateOf(if (items.size > 1) Random.nextInt(items.size) else 0) }
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
                    TickerLine(shown, width)
                }
            }
        }
    }
}

@Composable
private fun TickerLine(slide: AdhkarSlide, widthPx: Int) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val fit = remember(slide, widthPx, density) { fitLine(measurer, density, slide, widthPx) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Two lines may stand a little taller than the ticker: into the space around it, never clipped.
        Row(
            Modifier.fillMaxWidth().wrapContentHeight(unbounded = true),
            horizontalArrangement = Arrangement.spacedBy(GAP, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KhatamStar(STAR)
            Text(
                slide.text,
                style = fit.text,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Visible,
                modifier = Modifier.widthIn(max = fit.textMaxWidth),
            )
            if (slide.reference.isNotBlank()) {
                Text(
                    TvStrings.source(slide.reference),
                    style = fit.reference,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier.widthIn(max = fit.referenceMaxWidth),
                )
            }
            KhatamStar(STAR)
        }
    }
}

private class TickerFit(val text: TextStyle, val textMaxWidth: Dp, val reference: TextStyle, val referenceMaxWidth: Dp)

/** How a kind of text is set, and how far it may shrink. */
private class TickerType(val family: FontFamily, val size: Int, val minOneLine: Int, val minTwoLines: Int) {
    fun style(size: Int): TextStyle = midadStyle(size.sp, family = family, lineHeight = LINE_HEIGHT)
}

/** The adhkar in Amiri with their vowel marks, as on the board. */
private val SACRED = TickerType(Amiri, size = 25, minOneLine = 19, minTwoLines = 14)
/** A mosque's written announcement: its own words, in the interface's face. */
private val ANNOUNCEMENT = TickerType(Readex, size = 20, minOneLine = 16, minTwoLines = 13)

/**
 * The sizes for [slide] on a line [widthPx] wide. The source takes what it needs, up to a third of
 * the line (smaller, then on two lines past that); the text has the rest: at its size if it fits,
 * else a little smaller, else on two lines, and at the smallest size on as many lines as it needs.
 */
private fun fitLine(measurer: TextMeasurer, density: Density, slide: AdhkarSlide, widthPx: Int): TickerFit = with(density) {
    val available = (widthPx - (STAR * 2 + GAP * 3).roundToPx()).coerceAtLeast(1)
    fun oneLine(text: String, style: TextStyle) = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width

    var reference = REFERENCE
    val referenceMax = available / 3
    var referenceWidth = if (slide.reference.isBlank()) 0 else oneLine(slide.reference, reference)
    if (referenceWidth > referenceMax) {
        reference = REFERENCE_SMALL
        referenceWidth = oneLine(slide.reference, reference).coerceAtMost(referenceMax)
    }
    val referenceSpace = if (slide.reference.isBlank()) 0 else referenceWidth
    val textMax = (available - referenceSpace).coerceAtLeast(1)

    val type = if (MainScreenModel.isAnnouncement(slide)) ANNOUNCEMENT else SACRED
    val maxHeight = MAX_TEXT_HEIGHT.roundToPx()
    val style = (type.size downTo type.minOneLine).map(type::style).firstOrNull { oneLine(slide.text, it) <= textMax }
        ?: (type.minOneLine downTo type.minTwoLines).map(type::style).firstOrNull { style ->
            val layout = measurer.measure(slide.text, style, constraints = Constraints(maxWidth = textMax))
            layout.lineCount <= 2 && layout.size.height <= maxHeight
        }
        ?: type.style(type.minTwoLines)
    TickerFit(style, textMax.toDp(), reference, referenceWidth.coerceAtLeast(1).toDp())
}

private val TICKER_HEIGHT = 45.dp
private val RULE = 1.dp
private val FRIEZE_WIDTH = 230.dp
/** The text keeps clear of the frieze's solid ends. */
private val TEXT_MARGIN = 60.dp
private val STAR = 13.dp
private val GAP = 14.dp
/** Two lines may overflow the ticker by a few dp into the space above and below it. */
private val MAX_TEXT_HEIGHT = 56.dp
private const val LINE_HEIGHT = 1.3f
private const val FADE_MILLIS = 500
private val REFERENCE = midadStyle(14.sp, color = Midad.Muted)
private val REFERENCE_SMALL = midadStyle(11.sp, color = Midad.Muted)
