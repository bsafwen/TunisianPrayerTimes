package com.tunisianprayertimes.tv.ui.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** The size every screen is laid out for, in dp: 16:9, as a 720p TV at its usual density. */
const val CANVAS_WIDTH_DP = 960f
const val CANVAS_HEIGHT_DP = 540f

/**
 * Lays the app out on the same [CANVAS_WIDTH_DP] × [CANVAS_HEIGHT_DP] canvas on every TV: 720p,
 * 1080p or 4K, whatever density the box reports, and with the system font size ignored (a large
 * font setting would push the prayer times off the wall). On a screen that is not 16:9 the side that
 * runs out first sets the scale, and the other side gets a little more room.
 */
@Composable
fun VirtualCanvas(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = canvasDensity(constraints.maxWidth, constraints.maxHeight)
        CompositionLocalProvider(LocalDensity provides Density(scale, fontScale = 1f)) { content() }
    }
}

/** Pixels per dp so that the canvas fits the screen. */
fun canvasDensity(widthPx: Int, heightPx: Int): Float =
    minOf(widthPx / CANVAS_WIDTH_DP, heightPx / CANVAS_HEIGHT_DP).takeIf { it > 0f && it.isFinite() } ?: 1f
