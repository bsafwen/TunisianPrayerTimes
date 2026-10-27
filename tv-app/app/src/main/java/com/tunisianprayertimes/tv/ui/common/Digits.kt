package com.tunisianprayertimes.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.tv.ui.theme.lineBoxPx
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A number that ticks (the clock, a countdown) in cells of one width, so the line never shifts as its
 * digits change: Readex Pro's figures are proportional and the font has no tabular feature. Laid out
 * left to right whatever the screen's direction ("14:07", never "07:14"). [tracking] is added between
 * characters: negative tightens a big clock, as the mockups' letter-spacing does. Its baseline is
 * exposed, so it lines up with the text beside it in a row.
 */
@Composable
fun Digits(text: String, style: TextStyle, modifier: Modifier = Modifier, tracking: Dp = 0.dp) {
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val density = LocalDensity.current
    val trackPx = with(density) { tracking.toPx() }
    val cell = remember(measurer, style, density) { digitCell(measurer, style) }
    val placed = remember(text, style, cell, trackPx, density) {
        var x = 0f
        val glyphs = text.mapIndexed { i, char ->
            val result = measurer.measure(char.toString(), style, softWrap = false, maxLines = 1)
            val width = if (char.isDigit()) cell.toFloat() else result.size.width.toFloat()
            val left = x + (width - result.size.width) / 2f
            x += width + trackPx
            // Readex Pro's colon stands on the baseline, at the height of small letters, and the font has
            // no form of it for figures: between two digits it is raised to their middle. "http:" keeps its place.
            val between = char == ':' && text.getOrNull(i - 1)?.isDigit() == true && text.getOrNull(i + 1)?.isDigit() == true
            val lift = if (between) with(density) { COLON_LIFT_EM * style.fontSize.toPx() } else 0f
            PlacedGlyph(left, lift, result)
        }
        // The line box of the style's line height, as CSS gives it (see lineBox); the digits centred on it.
        val natural = glyphs.maxOfOrNull { it.result.size.height } ?: 0
        val box = style.lineBoxPx(density)?.takeIf { it < natural } ?: natural
        PlacedDigits(glyphs, width = ceil((x - trackPx).coerceAtLeast(0f)).toInt(), height = box, shift = (box - natural) / 2f)
    }
    Layout(
        modifier = modifier.drawBehind {
            placed.glyphs.forEach { glyph -> drawText(glyph.result, topLeft = Offset(glyph.left, placed.shift - glyph.lift)) }
        },
    ) { _, constraints ->
        val first = placed.glyphs.firstOrNull()?.result
        val width = placed.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = placed.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        val baseline = first?.let { (it.firstBaseline + placed.shift).roundToInt() } ?: height
        layout(width, height, mapOf(FirstBaseline to baseline, LastBaseline to baseline)) {}
    }
}

/**
 * How wide [Digits] lays [text] out. Every digit takes the same cell, so "00:00:00" is the room any
 * countdown of hours needs: a slot of that width keeps what stands beside it from moving.
 */
@Composable
fun digitsWidth(text: String, style: TextStyle, tracking: Dp = 0.dp): Dp {
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val density = LocalDensity.current
    return remember(text, style, tracking, density) {
        val cell = digitCell(measurer, style)
        val widths = text.map { if (it.isDigit()) cell else measurer.measure(it.toString(), style, softWrap = false, maxLines = 1).size.width }
        val px = widths.sum() + with(density) { tracking.toPx() } * (text.length - 1).coerceAtLeast(0)
        with(density) { ceil(px.coerceAtLeast(0f)).toDp() }
    }
}

/** The widest digit's width: the cell every digit is centred in. */
private fun digitCell(measurer: TextMeasurer, style: TextStyle): Int =
    ('0'..'9').maxOf { measurer.measure(it.toString(), style, softWrap = false, maxLines = 1).size.width }

/** The middle of Readex Pro's digits (0..0.71 em) less the middle of its colon (-0.01..0.54 em). */
private const val COLON_LIFT_EM = 0.09f

private class PlacedGlyph(val left: Float, val lift: Float, val result: TextLayoutResult)

private class PlacedDigits(
    val glyphs: List<PlacedGlyph>,
    val width: Int,
    val height: Int,
    /** How far the glyphs are drawn from the box's top: negative when the box is tighter than the font. */
    val shift: Float,
)
