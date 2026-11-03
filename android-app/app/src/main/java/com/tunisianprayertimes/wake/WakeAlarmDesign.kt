package com.tunisianprayertimes.wake

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.tunisianprayertimes.R

internal object WakeAlarmColors {
    val Background = Color(0xFF0B211F)
    val BackgroundTop = Color(0xFF183B34)
    val Halo = Color(0xFF315A46)
    val Ink = Color(0xFFF2F7F3)
    val Muted = Color(0xFFB5C9BE)
    val Card = Color(0xFF204139)
    val Border = Color(0xFF3C5A4E)
    val Accent = Color(0xFFD8C596)
    val Stop = Color(0xFFD7EBDC)
    val OnStop = Color(0xFF183B2E)
    val LockedStop = Color(0xFF2C443B)
    val OnLockedStop = Color(0xFFA6B8AB)
}

internal val WakeAlarmFont = FontFamily(
    Font(R.font.noto_sans_arabic),
    Font(R.font.noto_sans_arabic_semibold, FontWeight.SemiBold),
)

/** A quiet, continuous background also keeps every challenge's light text legible. */
internal fun Modifier.wakeAlarmBackground(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(WakeAlarmColors.BackgroundTop, WakeAlarmColors.Background)))
    drawRect(
        Brush.radialGradient(
            colors = listOf(WakeAlarmColors.Halo.copy(alpha = 0.5f), Color.Transparent),
            center = Offset(size.width * 0.85f, size.height * 0.12f),
            radius = size.width * 1.15f,
        ),
    )
}
