package com.tunisianprayertimes.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The height of a tall bottom sheet's content: everything under the status bar and a small gap.
 *
 * Asking for a fraction of the screen instead leaves less room above the sheet than the status bar
 * needs on a phone with a tall one. The sheet then pads its content by the part of the status bar it
 * overlaps, which depends on where the sheet is, so its own height moves with it, and the target it
 * settles to moves too. A fling at the end of a list then sets it bobbing for good. Here the sheet
 * always ends [gap] below the status bar, wherever it is.
 */
@Composable
internal fun Modifier.quranSheetHeight(gap: Dp = 8.dp): Modifier {
    val density = LocalDensity.current
    val above = WindowInsets.safeDrawing.getTop(density) + with(density) { gap.roundToPx() }
    return layout { measurable, constraints ->
        val height = (constraints.maxHeight - above).coerceAtLeast(0)
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
