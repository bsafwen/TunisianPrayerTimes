package com.tunisianprayertimes.tv.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Islamic decorative composables — ornamental dividers, arched frames,
 * and geometric motifs that give the app an authentic mosque feel.
 */

/**
 * Gold ornamental divider with a central star/diamond motif.
 * ════════ ✦ ════════
 */
@Composable
fun OrnamentalDivider(
    modifier: Modifier = Modifier,
    color: Color = Gold.copy(alpha = 0.6f),
    width: Dp = 200.dp
) {
    Row(
        modifier = modifier.width(width),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Left line
        Canvas(modifier = Modifier.weight(1f).height(2.dp)) {
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, color)
                ),
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
        }

        // Central ornament
        Text(
            text = "✦",
            color = Gold,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        // Right line
        Canvas(modifier = Modifier.weight(1f).height(2.dp)) {
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(color, Color.Transparent)
                ),
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Rich ornamental top border — a horizontal gold arc/arch pattern
 * drawn with Canvas to evoke mosque architecture.
 */
@Composable
fun MosqueArchBorder(
    modifier: Modifier = Modifier,
    color: Color = Gold.copy(alpha = 0.3f)
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
    ) {
        val archWidth = 60f
        val archCount = (size.width / archWidth).toInt()
        val stroke = Stroke(width = 2f, cap = StrokeCap.Round)

        for (i in 0 until archCount) {
            val startX = i * archWidth
            val path = Path().apply {
                moveTo(startX, size.height)
                quadraticTo(
                    startX + archWidth / 2, -size.height * 0.5f,
                    startX + archWidth, size.height
                )
            }
            drawPath(path, color, style = stroke)
        }
    }
}

/**
 * Bismillah header — ﷽ with ornamental framing.
 * Used at the top of major screens.
 */
@Composable
fun BismillahHeader(
    modifier: Modifier = Modifier,
    showOrnament: Boolean = true
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showOrnament) {
            MosqueArchBorder(color = Gold.copy(alpha = 0.25f))
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = "﷽",
            color = Gold.copy(alpha = 0.7f),
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (showOrnament) {
            Spacer(Modifier.height(2.dp))
        }
    }
}

/**
 * Islamic geometric corner pattern — draws a small repeated arch
 * motif in the corner of a card, giving cards an Islamic frame feel.
 */
@Composable
fun IslamicCornerOrnament(
    modifier: Modifier = Modifier,
    color: Color = Gold.copy(alpha = 0.15f)
) {
    Canvas(modifier = modifier.size(32.dp)) {
        val path = Path().apply {
            // Quarter-circle arc
            moveTo(0f, size.height)
            quadraticTo(0f, 0f, size.width, 0f)
        }
        drawPath(path, color, style = Stroke(width = 2f))

        // Inner smaller arc
        val innerPath = Path().apply {
            moveTo(0f, size.height * 0.6f)
            quadraticTo(0f, 0f, size.width * 0.6f, 0f)
        }
        drawPath(innerPath, color.copy(alpha = color.alpha * 0.6f), style = Stroke(width = 1.5f))
    }
}

/**
 * Glowing gold card background with warm green base.
 * Use for prayer time cards, dhikr cards, etc.
 */
@Composable
fun IslamicCardBackground(
    isHighlighted: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val bgBrush = if (isHighlighted) {
        Brush.verticalGradient(
            listOf(
                NextPrayerHighlight.copy(alpha = 0.6f),
                TealDark.copy(alpha = 0.8f),
                NextPrayerHighlight.copy(alpha = 0.5f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                SurfaceCard.copy(alpha = 0.8f),
                SurfaceElevated.copy(alpha = 0.6f),
                SurfaceCard.copy(alpha = 0.8f)
            )
        )
    }

    val borderColor = if (isHighlighted) GoldBorder else GlassBorder

    Box(
        modifier = modifier
            .background(bgBrush, RoundedCornerShape(20.dp))
            .background(Color.Transparent) // ensures brush is drawn
    ) {
        // Gold corner ornaments
        IslamicCornerOrnament(
            modifier = Modifier.align(Alignment.TopStart),
            color = if (isHighlighted) Gold.copy(alpha = 0.25f) else Gold.copy(alpha = 0.1f)
        )
        IslamicCornerOrnament(
            modifier = Modifier.align(Alignment.TopEnd),
            color = if (isHighlighted) Gold.copy(alpha = 0.25f) else Gold.copy(alpha = 0.1f)
        )

        content()
    }
}
