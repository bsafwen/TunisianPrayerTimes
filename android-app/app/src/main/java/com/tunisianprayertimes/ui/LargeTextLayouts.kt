package com.tunisianprayertimes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/*
 * Layouts for the largest text and display sizes, where a phone is 320 dp wide and a 12 sp label is drawn 24 dp tall.
 * Each one leaves the usual sizes as they are and only changes what no longer fits.
 */

/**
 * Text as large as its size asks, but never so large that a word breaks in two or the text runs past its lines: it
 * shrinks just enough, never below [floor], by default the size it has without the phone's large text (its sp value in
 * dp). Lines then break between words only, as Arabic must.
 */
@Stable
internal class WholeWords(
    private val size: TextUnit,
    private val maxLines: Int = Int.MAX_VALUE,
    private val floor: TextUnit = TextUnit.Unspecified,
) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        val largest = size.toPx()
        // The size it would have with the phone's text at its normal size, unless a floor is given.
        val smallest = minOf(largest, if (floor == TextUnit.Unspecified) size.value * density else floor.toPx())
        fun fits(px: Float): Boolean {
            val layout = performLayout(constraints, text, px.toSp())
            return !layout.splitsWord(text.text) && layout.lineCount <= maxLines && !layout.didOverflowHeight &&
                !(layout.lineCount > 0 && layout.isLineEllipsized(layout.lineCount - 1))
        }
        if (fits(largest)) return largest.toSp()
        var low = smallest
        var high = largest
        if (!fits(low)) return low.toSp()
        val step = 0.5.sp.toPx()
        while (high - low > step) {
            val mid = (low + high) / 2f
            if (fits(mid)) low = mid else high = mid
        }
        return low.toSp()
    }

    override fun equals(other: Any?): Boolean = other is WholeWords && other.size == size && other.maxLines == maxLines && other.floor == floor
    override fun hashCode(): Int = (size.hashCode() * 31 + maxLines) * 31 + floor.hashCode()
}

/**
 * The phone's text drawn more than 1.3 times its stable size, by the font size, the display size or both: past the usual
 * sizes, which keep their layouts as they were.
 */
@Composable
internal fun largeText(): Boolean =
    androidx.compose.ui.platform.LocalDensity.current.fontScale * androidx.compose.ui.platform.LocalConfiguration.current.densityDpi /
        android.util.DisplayMetrics.DENSITY_DEVICE_STABLE > 1.3f

/** A button's own [pill]; with large text, when it grows to several lines, rounded corners that never cut its words. */
@Composable
internal fun wordsSafeShape(pill: androidx.compose.ui.graphics.Shape): androidx.compose.ui.graphics.Shape =
    if (largeText()) androidx.compose.foundation.shape.RoundedCornerShape(28.dp) else pill

/** A line ends inside a word: the letters on both sides of a break belong to one word. */
internal fun TextLayoutResult.splitsWord(text: String): Boolean = (0 until lineCount - 1).any { line ->
    val end = getLineEnd(line)
    end in 1 until text.length && !text[end - 1].breaksLine() && !text[end].breaksLine()
}

private fun Char.breaksLine(): Boolean = isWhitespace() || this == '-' || this == '/' || this == '⁦' || this == '⁩'

/**
 * A header above a scrolling list that, when it would take more than [maxShare] of the height, scrolls away with the
 * list and comes back when the list is back at its top. Smaller, it stays where it is, as before.
 */
@Stable
internal class CollapsingHeaderState {
    internal var headerPx by mutableIntStateOf(0)
    internal var collapsible by mutableStateOf(false)
    /** How far the header has gone up, 0 to its height. */
    internal var hiddenPx by mutableFloatStateOf(0f)

    /** Put on the list, after any guard of the sheet's own drags: it is handed the list's scrolls first. */
    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!collapsible || available.y >= 0f) return Offset.Zero
            val used = (-available.y).coerceAtMost(headerPx - hiddenPx).coerceAtLeast(0f)
            hiddenPx += used
            return Offset(0f, -used)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (!collapsible || available.y <= 0f) return Offset.Zero
            val used = available.y.coerceAtMost(hiddenPx)
            hiddenPx -= used
            return Offset(0f, used)
        }
    }
}

@Composable
internal fun rememberCollapsingHeaderState(): CollapsingHeaderState = remember { CollapsingHeaderState() }

/** [header] over [content], which fills the rest; see [CollapsingHeaderState]. [content] carries [state]'s connection. */
@Composable
internal fun CollapsingHeaderColumn(
    state: CollapsingHeaderState,
    modifier: Modifier = Modifier,
    maxShare: Float = 0.4f,
    header: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Layout(
        contents = listOf({ Column(Modifier.fillMaxWidth(), content = header) }, { Column(Modifier.fillMaxWidth(), content = content) }),
        modifier = modifier.clipToBounds(),
    ) { (headerPart, contentPart), constraints ->
        val loose = constraints.copy(minHeight = 0)
        val head = headerPart.first().measure(loose)
        val total = constraints.maxHeight
        state.headerPx = head.height
        val collapsible = total != Constraints.Infinity && head.height > total * maxShare
        state.collapsible = collapsible
        if (!collapsible) state.hiddenPx = 0f
        else if (state.hiddenPx > head.height) state.hiddenPx = head.height.toFloat()
        val hidden = state.hiddenPx.coerceIn(0f, head.height.toFloat()).roundToInt()
        val shown = head.height - hidden
        val rest = if (total == Constraints.Infinity) Constraints.Infinity else (total - shown).coerceAtLeast(0)
        val body = contentPart.first().measure(
            constraints.copy(minHeight = if (rest == Constraints.Infinity) 0 else rest, maxHeight = rest),
        )
        val height = if (total == Constraints.Infinity) shown + body.height else total
        layout(constraints.maxWidth, height) {
            head.place(0, -hidden)
            body.place(0, shown)
        }
    }
}

/**
 * [leading] (text) with [trailing] (a button) at its end, as a row does, while the room [trailing] leaves holds [leading]'s
 * longest word and a line of at least [minLeadingEms] times a 14 sp letter (or all of it, when shorter): a line that
 * grows with the phone's text. Otherwise, as with large text, [trailing] goes under [leading], at its start, and
 * [leading] has the whole width.
 */
@Composable
internal fun BesideOrBelow(
    modifier: Modifier = Modifier,
    minLeadingEms: Float = 12f,
    /** Between [leading] and [trailing] side by side; under it, [trailing] lines up with the text. */
    gap: Dp = 0.dp,
    /**
     * False for words that change while the screen is used (a verse's number): only the room decides, not whether they
     * happen to fit whole, so that the layout does not change from one verse to the next.
     */
    fitWhole: Boolean = true,
    alignment: Alignment.Vertical = Alignment.CenterVertically,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Layout(contents = listOf(leading, trailing), modifier = modifier) { (lead, trail), constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val end = trail.firstOrNull()?.measure(loose)
        val text = lead.firstOrNull()
        val room = width - (end?.let { it.width + gap.roundToPx() } ?: 0)
        val beside = end == null || text == null || width == Constraints.Infinity ||
            (room >= text.minIntrinsicWidth(Constraints.Infinity) &&
                room >= (14.sp.toPx() * minLeadingEms).toInt().let { line -> if (fitWhole) minOf(text.maxIntrinsicWidth(Constraints.Infinity), line) else line })
        if (beside) {
            val rest = if (width == Constraints.Infinity) Constraints.Infinity else room.coerceAtLeast(0)
            val start = text?.measure(loose.copy(maxWidth = rest))
            val height = maxOf(start?.height ?: 0, end?.height ?: 0)
            val total = if (width == Constraints.Infinity) (start?.width ?: 0) + (end?.let { it.width + gap.roundToPx() } ?: 0) else width
            layout(total, height) {
                start?.placeRelative(0, alignment.align(start.height, height))
                end?.placeRelative(total - end.width, alignment.align(end.height, height))
            }
        } else {
            val start = text.measure(loose)
            layout(width, start.height + end!!.height) {
                start.placeRelative(0, 0)
                end.placeRelative(0, start.height)
            }
        }
    }
}
