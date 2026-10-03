package com.tunisianprayertimes.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/**
 * Keeps a `ModalBottomSheet` still while its scrolling content is dragged at an edge.
 *
 * Material 3 hands any scroll delta the inner list cannot consume to the sheet, so
 * over-dragging or flinging at the end of a list drags the whole sheet (toward dismiss)
 * and springs it back. On tall sheets that reads as the list jumping/bouncing at the
 * boundary. This connection consumes those leftover deltas and velocities so the sheet
 * only moves from its drag handle, non-scrolling areas and the scrim. Scrolling and
 * flinging inside the list are untouched.
 */
@Composable
internal fun rememberSheetScrollGuard(state: ScrollableState): NestedScrollConnection =
    remember(state) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                return if (atEdge(state, available.y)) available else Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return if (atEdge(state, available.y)) available else Velocity.Zero
            }

            private fun atEdge(state: ScrollableState, delta: Float): Boolean = when {
                delta > 0f -> !state.canScrollBackward
                delta < 0f -> !state.canScrollForward
                else -> false
            }
        }
    }
