package com.tunisianprayertimes.tv.ui.display

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.mosque.AdhkarSlide
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.theme.Amiri
import com.tunisianprayertimes.tv.ui.theme.Dots
import com.tunisianprayertimes.tv.ui.theme.KhatamStar
import com.tunisianprayertimes.tv.ui.theme.MedallionRule
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.SkyColors
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import com.tunisianprayertimes.tv.ui.theme.skyBackground

/**
 * The adhkar after the prayer. [slide] is the one [MosqueAdhkar.slideAt][com.tunisianprayertimes.mosque.MosqueAdhkar.slideAt]
 * picked from the time since the prayer ended, so a restart resumes on the same text; [index] is its
 * place among [total]. The sky shrinks to a band at the top ([sky] null on the «مداد» theme): the
 * text is what matters now.
 */
@Composable
fun AfterSalahAzkarScreen(slide: AdhkarSlide, index: Int, total: Int, sky: SkyColors?) {
    Column(Modifier.fillMaxSize().skyBackground(sky, 50.dp, 95.dp)) {
        Row(
            Modifier.fillMaxWidth().height(75.dp).padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(TvStrings.AFTER_SALAH_TITLE, style = midadStyle(19.sp, FontWeight.Medium))
            AdhkarPager(index, total)
        }
        Crossfade(IndexedValue(index, slide), Modifier.weight(1f).fillMaxWidth(), tween(TEXT_FADE_MILLIS), label = "dhikr") { shown ->
            Column(
                Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(23.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MedallionRule(450.dp, starSize = 23.dp, gap = 11.dp, double = true)
                FittedText(
                    shown.value.text,
                    midadStyle(44.sp, family = Amiri, lineHeight = 1.9f),
                    Modifier.weight(1f, fill = false),
                    maxWidth = 720.dp,
                )
                SourceRow(shown.value)
            }
        }
    }
}

/** A text fades into the next over half a second: no slide, no motion. */
internal const val TEXT_FADE_MILLIS = 500

/** More texts than this (a mosque's long list) and the pager is written out: a row of dots would not fit. */
private const val MAX_PAGER_DOTS = 16

/** Where the sequence is: the dots up to the current text, or "5 من 24" for a long list. */
@Composable
private fun AdhkarPager(index: Int, total: Int) {
    if (total <= MAX_PAGER_DOTS) {
        Dots(total, lit = { it <= index })
    } else {
        Text(TvStrings.progress(index + 1, total), style = midadStyle(17.sp, color = Midad.Muted))
    }
}

/**
 * How many times and which part, in ivory on a line of their own (a count is read from the back of the
 * hall), then the source between two stars, kept to the text's width: some sources are a sentence.
 */
@Composable
private fun SourceRow(slide: AdhkarSlide) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val count = listOfNotNull(
            TvStrings.times(slide.count),
            slide.parts.takeIf { it > 1 }?.let { TvStrings.part(slide.part, it) },
        )
        if (count.isNotEmpty()) Text(count.joinToString(" · "), style = midadStyle(22.sp, FontWeight.Medium))
        Row(
            Modifier.widthIn(max = 720.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KhatamStar(15.dp)
            Text(
                TvStrings.source(slide.reference),
                style = midadStyle(18.sp, color = Midad.Muted),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
            KhatamStar(15.dp)
        }
    }
}

/**
 * [text] centred, at [style]'s size or smaller until it fits the height it is given: a Quran, hadith
 * or dua text is never cut or ellipsized, however long a mosque's own text is. Measured rather than
 * guessed from the length, since vowel marks and Amiri's wide letters make lengths misleading.
 * [maxWidth] caps the length of a line.
 */
@Composable
internal fun FittedText(text: String, style: TextStyle, modifier: Modifier = Modifier, maxWidth: Dp = Dp.Unspecified) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val widthPx = if (maxWidth.isSpecified) minOf(constraints.maxWidth, with(density) { maxWidth.roundToPx() }) else constraints.maxWidth
        val heightPx = constraints.maxHeight
        val centred = style.copy(textAlign = TextAlign.Center)
        val fitted = remember(text, centred, widthPx, heightPx, density) { fit(measurer, text, centred, widthPx, heightPx) }
        val width = if (widthPx == Constraints.Infinity) Modifier else Modifier.widthIn(max = with(density) { widthPx.toDp() })
        Text(text, style = fitted, modifier = width)
    }
}

/** Below this share of the asked size a text would no longer be read from the back of the hall. */
private const val MIN_TEXT_SCALE = 0.4f

private fun fit(measurer: TextMeasurer, text: String, style: TextStyle, widthPx: Int, heightPx: Int): TextStyle {
    if (widthPx == Constraints.Infinity || heightPx == Constraints.Infinity) return style
    var scale = 1f
    while (scale > MIN_TEXT_SCALE) {
        val candidate = if (scale == 1f) style else style.copy(fontSize = style.fontSize * scale)
        val height = measurer.measure(text, candidate, constraints = Constraints(maxWidth = widthPx), skipCache = true).size.height
        if (height <= heightPx) return candidate
        scale *= 0.94f
    }
    // Still too tall: smaller would be unreadable, so it runs past its space rather than lose words.
    return style.copy(fontSize = style.fontSize * MIN_TEXT_SCALE)
}
