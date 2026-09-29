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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    val cell = remember(measurer, style, density) {
        ('0'..'9').maxOf { measurer.measure(it.toString(), style, softWrap = false, maxLines = 1).size.width }
    }
    val placed = remember(text, style, cell, trackPx) {
        var x = 0f
        val glyphs = text.map { char ->
            val result = measurer.measure(char.toString(), style, softWrap = false, maxLines = 1)
            val width = if (char.isDigit()) cell.toFloat() else result.size.width.toFloat()
            val left = x + (width - result.size.width) / 2f
            x += width + trackPx
            left to result
        }
        PlacedDigits(glyphs, width = ceil((x - trackPx).coerceAtLeast(0f)).toInt())
    }
    Layout(
        modifier = modifier.drawBehind {
            placed.glyphs.forEach { (left, result) -> drawText(result, topLeft = Offset(left, 0f)) }
        },
    ) { _, constraints ->
        val first = placed.glyphs.firstOrNull()?.second
        val width = placed.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (placed.glyphs.maxOfOrNull { it.second.size.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        val baseline = first?.firstBaseline?.roundToInt() ?: height
        layout(width, height, mapOf(FirstBaseline to baseline, LastBaseline to baseline)) {}
    }
}

private class PlacedDigits(val glyphs: List<Pair<Float, androidx.compose.ui.text.TextLayoutResult>>, val width: Int)
