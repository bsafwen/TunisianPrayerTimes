package com.tunisianprayertimes.tv.ui.common

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * Takes the remote's focus when the element first appears, so every page opens with something
 * selected and OK or the arrows work at once (otherwise the first press only finds a starting point).
 * Only one element of a page should ask for it.
 */
fun Modifier.initialFocus(enabled: Boolean = true): Modifier = if (!enabled) this else composed {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // An element laid out a frame late (in a scrolled column, a page that swaps in) is not attached
        // yet on the first try: try again on the next frames rather than leave the page without focus.
        repeat(FOCUS_ATTEMPTS) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    focusRequester(requester)
}

private const val FOCUS_ATTEMPTS = 3
