package com.tunisianprayertimes.tv.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The four ornaments of «أفق», all from Kairouan and nothing more (see the «الزخرفة» board): the arcade
 * arch of the Great Mosque's courtyard for each prayer, the mihrab around the adhan, the silver
 * verse medallion of the Blue Qur'an, and the khatam, the 8-point star of two squares. Never behind a
 * number, and nothing at all during the prayer or the khutba.
 */

/** The khatam around [center]: two squares, one turned by 45°, [radius] to its four points. */
fun khatamPath(center: Offset, radius: Float): Path {
    val a = radius / sqrt(2f)
    val b = radius - a
    val points = listOf(
        0f to -radius, b to -a, a to -a, a to -b, radius to 0f, a to b, a to a, b to a,
        0f to radius, -b to a, -a to a, -a to b, -radius to 0f, -a to -b, -a to -a, -b to -a,
    )
    return Path().apply {
        points.forEachIndexed { index, (x, y) ->
            if (index == 0) moveTo(center.x + x, center.y + y) else lineTo(center.x + x, center.y + y)
        }
        close()
    }
}

/** An SVG path in its viewBox, parsed once. */
private class VectorShape(private val pathData: String, val width: Float, val height: Float) {
    /** Parsed on first draw: Android's Path does not exist where the file is only loaded (unit tests). */
    val path: Path by lazy { PathParser().parsePathString(pathData).toPath() }
}

/** A pointed arch of the courtyard arcade, on a low rounded base (the mockups' 274 × 320 tile). */
private val ARCH = VectorShape(
    "M0 296V160A161.9 161.9 0 0 1 137 0A161.9 161.9 0 0 1 274 160V296Q274 320 250 320H24Q0 320 0 296Z", 274f, 320f,
)
/** The carved line inside the arch, like cut plaster. */
private val ARCH_LINE = VectorShape("M10 312V160A151.9 151.9 0 0 1 137 10.2A151.9 151.9 0 0 1 264 160V312", 274f, 320f)
/** The mihrab of the prayer hall, open at the bottom; with its inner line. */
private val MIHRAB = VectorShape("M1.5 780V560A563.6 563.6 0 0 1 500 1.5A563.6 563.6 0 0 1 998.5 560V780", 1000f, 780f)
private val MIHRAB_LINE = VectorShape("M16 780V560A549.1 549.1 0 0 1 500 15.6A549.1 549.1 0 0 1 984 560V780", 1000f, 780f)
/** A phone, struck through: the congregation silences theirs. */
private val PHONE_OFF = VectorShape(
    "M8.5 2.5H15.5A2 2 0 0 1 17.5 4.5V19.5A2 2 0 0 1 15.5 21.5H8.5A2 2 0 0 1 6.5 19.5V4.5A2 2 0 0 1 8.5 2.5ZM11 18.5H13M3 3L21 21",
    24f, 24f,
)

/** Draws [shape] scaled to this box: stretched to fill it ([uniform] false), or fitted and centred. */
private fun DrawScope.draw(shape: VectorShape, uniform: Boolean, block: DrawScope.() -> Unit) {
    val sx = size.width / shape.width
    val sy = size.height / shape.height
    if (uniform) {
        val s = min(sx, sy)
        translate((size.width - shape.width * s) / 2f, (size.height - shape.height * s) / 2f) {
            scale(s, s, pivot = Offset.Zero, block = block)
        }
    } else {
        scale(sx, sy, pivot = Offset.Zero, block = block)
    }
}

/** A prayer's niche: the arch filled with [fill], its carved line in [line]. Stretched to the box. */
fun Modifier.archTile(fill: Color, line: Color): Modifier = drawBehind {
    draw(ARCH, uniform = false) {
        drawPath(ARCH.path, fill)
        drawPath(ARCH_LINE.path, line, style = Stroke(width = 2f))
    }
}

/**
 * The mihrab: the pointed niche, a double carved line, and a silver khatam as its keystone. Drawn
 * across the box's width from its top; its height is 0.78 of the width, the rest is left open below.
 */
fun Modifier.mihrab(): Modifier = drawBehind {
    val s = size.width / MIHRAB.width
    scale(s, s, pivot = Offset.Zero) {
        drawPath(MIHRAB.path, Midad.Niche)
        drawPath(MIHRAB.path, Midad.MihrabLine, style = Stroke(width = 3f))
        drawPath(MIHRAB_LINE.path, Midad.MihrabInner, style = Stroke(width = 2f))
        drawPath(khatamPath(Offset(500f, 91.5f), 33.5f), Midad.Silver)
    }
}

/** Which edge of a frieze is solid; it fades toward the other. */
enum class FriezeEdge { LEFT, RIGHT }

/**
 * Rows of khatam outlines, faint, fading from [solid] toward the middle of the screen: the ends of
 * the ticker. [cell] is one star's square.
 */
fun Modifier.starFrieze(solid: FriezeEdge, cell: Dp = 22.5.dp, color: Color = Midad.Frieze): Modifier = drawWithCache {
    val step = cell.toPx()
    val stroke = Stroke(width = step * 2f / 45f)
    // The mockup's star sits a little low in its 45-unit cell (centre at 23.8) with a radius of 20.8.
    val radius = step * 20.8f / 45f
    val stars = Path()
    var y = 0f
    while (y < size.height) {
        var x = 0f
        while (x < size.width) {
            stars.addPath(khatamPath(Offset(x + step / 2f, y + step * 23.8f / 45f), radius))
            x += step
        }
        y += step
    }
    val fade = listOf(color, color.copy(alpha = 0f)).let { if (solid == FriezeEdge.LEFT) it else it.reversed() }
    val brush = Brush.horizontalGradient(fade, startX = 0f, endX = size.width)
    onDrawBehind { drawPath(stars, brush, style = stroke) }
}

/** A filled khatam: the ticker's separators, the mark over the next prayer. */
@Composable
fun KhatamStar(size: Dp, modifier: Modifier = Modifier, color: Color = Midad.Silver) {
    Canvas(modifier.size(size)) {
        drawPath(khatamPath(center, this.size.minDimension * 11f / 24f), color)
    }
}

/** The Blue Qur'an's verse marker: a khatam outline around a silver disc. */
@Composable
fun Medallion(size: Dp, modifier: Modifier = Modifier, color: Color = Midad.Silver, strokeWidth: Float = 1.5f) {
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        drawPath(khatamPath(center, 11f * unit), color, style = Stroke(width = strokeWidth * unit))
        drawCircle(color, radius = 3.6f * unit, center = center)
    }
}

/** A khatam outline around a smaller filled one: the adhkar divider. */
@Composable
fun DoubleKhatam(size: Dp, modifier: Modifier = Modifier, color: Color = Midad.Silver) {
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        drawPath(khatamPath(center, 11f * unit), color, style = Stroke(width = 1.3f * unit))
        drawPath(khatamPath(center, 6.8f * unit), color)
    }
}

/** The moon by the Hijri date. Gold in Ramadan. */
@Composable
fun Crescent(size: Dp, modifier: Modifier = Modifier, color: Color = Midad.Silver) {
    Canvas(modifier.size(size)) {
        // One disc less another, shifted up and to the right: the young moon, «☾».
        val unit = this.size.minDimension / 24f
        val moon = Path().apply { addOval(Rect(Offset(11f * unit, 12f * unit), 9.5f * unit)) }
        val shadow = Path().apply { addOval(Rect(Offset(15f * unit, 10f * unit), 8f * unit)) }
        drawPath(Path.combine(PathOperation.Difference, moon, shadow), color)
    }
}

/** A phone struck through, for «الرجاء إغلاق الهاتف». */
@Composable
fun PhoneOffGlyph(size: Dp, modifier: Modifier = Modifier, color: Color = Midad.Dim) {
    Canvas(modifier.size(size)) {
        draw(PHONE_OFF, uniform = true) {
            drawPath(PHONE_OFF.path, color, style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * A silver medallion with rules that fade away from it: on both sides ([bothSides], the adhkar), or
 * only toward the end of the line (under an announcement's title). [width] is the whole ornament.
 */
@Composable
fun MedallionRule(
    width: Dp,
    modifier: Modifier = Modifier,
    starSize: Dp = 17.dp,
    bothSides: Boolean = true,
    gap: Dp = 9.dp,
    double: Boolean = false,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier.width(width),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // In a right-to-left row the first rule is on the right: its solid end is its left, by the star.
        if (bothSides) FadingRule(if (rtl) FriezeEdge.LEFT else FriezeEdge.RIGHT)
        if (double) DoubleKhatam(starSize) else Medallion(starSize, strokeWidth = 1.4f)
        FadingRule(if (rtl) FriezeEdge.RIGHT else FriezeEdge.LEFT)
    }
}

/** A 1 dp rule, solid at its [solid] end and gone at the other. */
@Composable
private fun RowScope.FadingRule(solid: FriezeEdge) {
    Box(
        Modifier
            .weight(1f)
            .height(1.dp)
            .drawBehind {
                val colors = listOf(Midad.DividerLine, Midad.DividerLine.copy(alpha = 0f))
                drawRect(Brush.horizontalGradient(if (solid == FriezeEdge.LEFT) colors else colors.reversed()))
            },
    )
}

/**
 * A row of round dots: a pager, or the studs of Kairouan's doors counting down to the iqamah.
 * [lit] tells which are on. [leftToRight] fixes the order whatever the screen's direction.
 */
@Composable
fun Dots(
    count: Int,
    lit: (Int) -> Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 7.dp,
    gap: Dp = 6.dp,
    on: Color = Midad.Text,
    off: Color = Midad.DotOff,
    leftToRight: Boolean = false,
) {
    val content = @Composable {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            repeat(count) { index ->
                Canvas(Modifier.size(size)) { drawCircle(if (lit(index)) on else off) }
            }
        }
    }
    if (leftToRight) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content) else content()
}
