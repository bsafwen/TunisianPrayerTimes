package com.tunisianprayertimes.tv.ui.usb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.AdminPage
import com.tunisianprayertimes.tv.ui.common.FocusableListItem
import com.tunisianprayertimes.tv.ui.common.rtl
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import com.tunisianprayertimes.tv.usb.UsbSettingsFound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Settings found on a USB key: the changes to confirm, or why the file was not applied, in a panel
 * as on the settings pages. [preview] is the file read against the current settings. Back
 * dismisses, it never leaves the app. The focus stays on the buttons; a list longer than its panel
 * scrolls with ▲ and ▼.
 */
@Composable
fun UsbImportScreen(
    found: UsbSettingsFound,
    preview: ParseResult,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    title: String = TvStrings.USB_FOUND_TITLE,
) {
    BackHandler(onBack = onDismiss)
    // The first button has focus, so OK on the remote answers without hunting for it.
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(preview) { runCatching { firstButton.requestFocus() } }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { SCROLL_STEP.toPx() }
    AdminPage(
        title = if (preview is ParseResult.Failure) TvStrings.USB_ERROR_TITLE else title,
        hints = if (scroll.maxValue > 0) listOf(TvStrings.HINT_SCROLL_LIST) else emptyList(),
        modifier = Modifier
            .background(Midad.Ground)
            .scrollsWithArrows(scroll, scope, step)
            .padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        // Its own paragraph, left to right, so the slashes stay where they belong.
        Text(found.file.path, style = midadStyle(13.sp, color = Midad.Dim).copy(textDirection = TextDirection.Ltr))
        when (val result = preview) {
            is ParseResult.Success -> if (!result.hasChanges) {
                LinesPanel(listOf(TvStrings.USB_NO_CHANGES), scroll, Midad.Text)
                DialogButtons { FocusableListItem(TvStrings.USB_OK, onDismiss, Modifier.focusRequester(firstButton).width(BUTTON_WIDTH)) }
            } else {
                // Every change stays readable above the buttons, however many the file makes.
                LinesPanel(SettingsChangeLines.of(result), scroll, Midad.Text)
                DialogButtons {
                    FocusableListItem(TvStrings.USB_APPLY, onApply, Modifier.focusRequester(firstButton).width(BUTTON_WIDTH))
                    FocusableListItem(TvStrings.CANCEL, onDismiss, Modifier.width(BUTTON_WIDTH))
                }
            }
            is ParseResult.Failure -> {
                LinesPanel(result.errors.map { it.message }, scroll, Midad.Alert, footnote = TvStrings.USB_ERROR_HINT)
                DialogButtons { FocusableListItem(TvStrings.USB_OK, onDismiss, Modifier.focusRequester(firstButton).width(BUTTON_WIDTH)) }
            }
        }
    }
}

/**
 * The lines on raised rows in a dark panel, as the settings tables: as tall as they need, up to the
 * room above the buttons, then scrolled by [scroll]. [footnote] follows them, quieter.
 */
@Composable
internal fun ColumnScope.LinesPanel(lines: List<String>, scroll: ScrollState, color: Color, footnote: String? = null) {
    Column(
        Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .background(Midad.Surface, PANEL_SHAPE)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lines.forEach { line ->
            Text(
                line,
                style = midadStyle(17.sp, color = color, lineHeight = 1.4f).rtl(),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Midad.SurfaceRaised, ROW_SHAPE)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        if (footnote != null) {
            Text(footnote, style = midadStyle(17.sp, color = Midad.Muted, lineHeight = 1.4f).rtl(), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** The answers, side by side at the start of the line, under the panel. */
@Composable
internal fun DialogButtons(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** ▲ and ▼ scroll [scroll] by [step] pixels while it has more to show; the focus never leaves the buttons. */
private fun Modifier.scrollsWithArrows(scroll: ScrollState, scope: CoroutineScope, step: Float): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown || scroll.maxValue == 0) return@onPreviewKeyEvent false
    val by = when (event.key) {
        Key.DirectionDown -> step
        Key.DirectionUp -> -step
        else -> return@onPreviewKeyEvent false
    }
    scope.launch { scroll.animateScrollBy(by) }
    true
}

internal val BUTTON_WIDTH = 180.dp
private val SCROLL_STEP = 120.dp
private val PANEL_SHAPE = RoundedCornerShape(14.dp)
private val ROW_SHAPE = RoundedCornerShape(8.dp)
