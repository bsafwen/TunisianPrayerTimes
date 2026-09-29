package com.tunisianprayertimes.tv.ui.common

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * Takes the remote's focus when the element first appears, so every page opens with something
 * selected and OK or the arrows work at once (otherwise the first press only finds a starting point).
 */
fun Modifier.initialFocus(enabled: Boolean = true): Modifier = if (!enabled) this else composed {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    focusRequester(requester)
}
