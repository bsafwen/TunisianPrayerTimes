package com.tunisianprayertimes.tv.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle

/*
 * The remote's controls, as on the settings board: at rest a dark surface with ivory text; with the
 * focus an ivory fill, ink text, a thick gold ring just outside and a slight lift, so the admin sees
 * from their chair where they are. The ring is the only gold on the admin pages.
 */

/** The gold ring of the focused control, [offset] outside its bounds. */
fun Modifier.focusRing(focused: Boolean, radius: Dp = 10.dp, width: Dp = 2.5.dp, offset: Dp = 2.dp): Modifier =
    if (!focused) this else drawBehind {
        val out = offset.toPx() + width.toPx() / 2f
        drawRoundRect(
            color = Midad.Gold,
            topLeft = Offset(-out, -out),
            size = Size(size.width + 2 * out, size.height + 2 * out),
            cornerRadius = CornerRadius(radius.toPx() + out),
            style = Stroke(width.toPx()),
        )
    }

/** OK (or Enter) released on the focused element. */
fun Modifier.onOk(action: () -> Unit): Modifier = onKeyEvent { event ->
    if ((event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.NumPadEnter) &&
        event.type == KeyEventType.KeyUp
    ) {
        action(); true
    } else false
}

/** Text on a focusable surface: ink on the ivory of the focus, [rest] otherwise. */
fun onSurfaceText(focused: Boolean, rest: Color = Midad.Text): Color = if (focused) Midad.OnGold else rest

/** A second line on a focusable surface, quieter on either fill. */
fun onSurfaceMuted(focused: Boolean): Color = if (focused) Midad.OnGoldMuted else Midad.Muted

/**
 * A text style that reads right to left even when the text opens with a Latin word ("OK: فتح",
 * "Fire OS 7: …"), which would otherwise turn the whole line around and push it to the left edge.
 */
fun TextStyle.rtl(): TextStyle = copy(textDirection = TextDirection.Rtl)

/**
 * Anything the remote can reach and press: a menu row, a two-line toggle, a theme card. [content]
 * is told whether it has the focus, to turn its text to ink on the ivory fill ([onSurfaceText]).
 */
@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    radius: Dp = 9.dp,
    rest: Color = Midad.Surface,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    contentAlignment: Alignment = Alignment.CenterStart,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .graphicsLayer {
                val lift = if (focused) 1.03f else 1f
                scaleX = lift
                scaleY = lift
            }
            .focusRing(focused, radius = radius)
            .background(if (focused) Midad.Text else rest, RoundedCornerShape(radius))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onOk(onClick)
            .padding(contentPadding),
        contentAlignment = contentAlignment,
    ) { content(focused) }
}

/** A focusable row of text: a menu entry, a choice in a list. */
@Composable
fun FocusableListItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 38.dp)) { focused ->
        Text(
            text = text,
            style = midadStyle(
                17.sp,
                if (focused) FontWeight.SemiBold else FontWeight.Normal,
                onSurfaceText(focused),
            ).rtl(),
        )
    }
}

/** A small square key: − and + beside a value. A size in [modifier] replaces the default 44 dp. */
@Composable
fun FocusableButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = modifier
            .size(44.dp)
            .focusRing(focused, radius = 9.dp)
            .background(if (focused) Midad.Text else Midad.SurfaceRaised, shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onOk(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = midadStyle(22.sp, FontWeight.Medium, onSurfaceText(focused)))
    }
}

/**
 * − value +: a number the remote changes one step at a time. The value keeps [valueWidth] whatever
 * it says, so the keys never move under the admin's thumb. [minusModifier] reaches the − key (to
 * give it the page's first focus).
 */
@Composable
fun Stepper(
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    valueWidth: Dp = 110.dp,
    keySize: Dp = 34.dp,
    minusModifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusableButton(text = "−", onClick = onMinus, modifier = minusModifier.size(keySize))
        Text(
            value,
            style = midadStyle(17.sp, FontWeight.Medium),
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(valueWidth),
        )
        FocusableButton(text = "+", onClick = onPlus, modifier = Modifier.size(keySize))
    }
}

/** A setting that is on or off: OK flips it. [detail] says in one line what it does or holds. */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    FocusableSurface(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth().heightIn(min = 46.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
    ) { focused ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = midadStyle(17.sp, FontWeight.Medium, onSurfaceText(focused)).rtl())
                if (detail != null) Text(detail, style = midadStyle(14.sp, color = onSurfaceMuted(focused)).rtl())
            }
            Text(if (checked) TvStrings.ON else TvStrings.OFF, style = midadStyle(15.sp, color = onSurfaceMuted(focused)))
            Switch(checked, focused)
        }
    }
}

/** The switch of a [ToggleRow]: on, its knob sits at the end of the line (the left, in Arabic). */
@Composable
private fun Switch(checked: Boolean, focused: Boolean) {
    val track = when {
        checked && focused -> Midad.OnGold
        checked -> Midad.Text
        focused -> Midad.Dim
        else -> Midad.DotOff
    }
    val knob = when {
        checked && !focused -> Midad.Ground
        focused -> Midad.Text
        else -> Midad.Dim
    }
    Canvas(Modifier.size(width = 38.dp, height = 22.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2f))
        val radius = size.height / 2f - 3.dp.toPx()
        val atEnd = checked
        val atLeft = atEnd == (layoutDirection == LayoutDirection.Rtl)
        val x = if (atLeft) size.height / 2f else size.width - size.height / 2f
        drawCircle(knob, radius, Offset(x, size.height / 2f))
    }
}

/** The title of an admin page, 26 sp, with an optional quiet line at the other end of it. */
@Composable
fun PageTitle(title: String, modifier: Modifier = Modifier, aside: String? = null) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = midadStyle(26.sp, FontWeight.SemiBold).rtl(), modifier = Modifier.alignByBaseline())
        if (aside != null) Text(aside, style = midadStyle(14.sp, color = Midad.Muted).rtl(), modifier = Modifier.alignByBaseline())
    }
}

/** What the remote's keys do here, at the foot of a page: «OK: فتح», «الرجوع: …». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyHints(hints: List<String>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        hints.forEach { Text(it, style = midadStyle(14.sp, color = Midad.Muted).rtl()) }
    }
}

/** The dark panel of a master-detail page: the preview beside the settings menu, the kiosk's rows. */
fun Modifier.adminPanel(): Modifier =
    background(Midad.Surface, RoundedCornerShape(14.dp)).padding(horizontal = 28.dp, vertical = 22.dp)

/**
 * An admin page: its title (and a quiet line beside it), the page itself, and the keys at its foot.
 * [scroll] lets a long page follow the focus down.
 */
@Composable
fun AdminPage(
    title: String,
    modifier: Modifier = Modifier,
    aside: String? = null,
    hints: List<String> = emptyList(),
    scroll: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle(title, aside = aside)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                // Room for the focus ring, which the scrolling would otherwise cut at the edges.
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()).padding(6.dp) else Modifier),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
        if (hints.isNotEmpty()) KeyHints(hints)
    }
}

/**
 * A grid of choices (the gouvernorats, a gouvernorat's delegations), all in sight at once so the
 * remote reaches any of them in a few presses. [focusFirst] takes the focus when the grid appears
 * (the current choice), else the first one. Not a lazy grid: a row scrolled away and back must not
 * take the focus again.
 */
@Composable
fun <T> ChoiceGrid(
    choices: List<T>,
    label: (T) -> String,
    onChoose: (T) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 4,
    focusFirst: T? = null,
) {
    val start = choices.indexOf(focusFirst).coerceAtLeast(0)
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { column, choice ->
                    FocusableListItem(
                        text = label(choice),
                        onClick = { onChoose(choice) },
                        modifier = Modifier.weight(1f).initialFocus(rowIndex * columns + column == start),
                    )
                }
                // The last row keeps the others' column widths.
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}
