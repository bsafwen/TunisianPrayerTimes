package com.tunisianprayertimes.tv.ui.display

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.Digits
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import java.time.LocalDateTime

/**
 * The night, while the hall is empty ([NightWindow.isNight]): black, and one small block with a dim
 * clock and the next Fajr ([fajrAdhan], and its iqamah when known). The block moves to another of
 * nine places every few minutes ([NightWindow.anchorAt]), fading out and in, so a panel left on all
 * night keeps no clock burnt into it; nothing else on the screen moves.
 */
@Composable
fun NightScreen(now: LocalDateTime, fajrAdhan: LocalDateTime, fajrIqamah: LocalDateTime?) {
    val target = NightWindow.anchorAt(now)
    var shown by remember { mutableIntStateOf(target) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target != shown) {
            alpha.animateTo(0f, tween(MOVE_FADE_MILLIS / 2, easing = LinearEasing))
            shown = target
        }
        alpha.animateTo(1f, tween(MOVE_FADE_MILLIS / 2, easing = LinearEasing))
    }
    // Inside the TV's safe area, as every other screen.
    Box(Modifier.fillMaxSize().background(Midad.Prayer).padding(horizontal = 48.dp, vertical = 27.dp)) {
        NightBlock(
            now,
            fajrAdhan,
            fajrIqamah,
            // The fade is read in the draw phase: the clock's tick does not restart it, nor it the layout.
            Modifier.align(anchorAlignment(shown)).graphicsLayer { this.alpha = alpha.value },
        )
    }
}

/** Out, then in at the new place: never two clocks on the screen at once. */
private const val MOVE_FADE_MILLIS = 2000

/** The place [anchor] (0..8, row by row) in a 3 × 3 grid. */
private fun anchorAlignment(anchor: Int): Alignment =
    BiasAlignment(horizontalBias = (anchor % 3 - 1).toFloat(), verticalBias = (anchor / 3 - 1).toFloat())

@Composable
private fun NightBlock(now: LocalDateTime, fajrAdhan: LocalDateTime, fajrIqamah: LocalDateTime?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Digits(
            TvStrings.hm(now.toLocalTime()),
            midadStyle(100.sp, FontWeight.SemiBold, Midad.NightClock, lineHeight = 0.9f),
            tracking = (-2).dp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Text(TvStrings.FAJR, style = midadStyle(26.sp, FontWeight.Medium, Midad.NightText), modifier = Modifier.alignByBaseline())
            Text(
                TvStrings.hm(fajrAdhan.toLocalTime()),
                style = midadStyle(32.sp, FontWeight.Medium, Midad.NightText),
                modifier = Modifier.alignByBaseline(),
            )
            fajrIqamah?.let {
                Text(
                    "${TvStrings.IQAMAH_LABEL} ${TvStrings.hm(it.toLocalTime())}",
                    style = midadStyle(18.sp, color = Midad.NightText),
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}
