package com.tunisianprayertimes.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle

/*
 * The remote's controls, as on the settings board: at rest a dark surface with ivory text; with the
 * focus an ivory fill, ink text, a thick gold ring just outside and a slight lift, so the admin sees
 * from their chair where they are.
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

/** A focusable row of text: a menu entry, a choice in a list. */
@Composable
fun FocusableListItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp)
            .graphicsLayer {
                val lift = if (focused) 1.03f else 1f
                scaleX = lift
                scaleY = lift
            }
            .focusRing(focused, radius = 9.dp)
            .background(if (focused) Midad.Text else Midad.Surface, shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onOk(onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = midadStyle(
                17.sp,
                if (focused) FontWeight.SemiBold else FontWeight.Normal,
                if (focused) Midad.OnGold else Midad.Text,
            ),
        )
    }
}

/** A small square key: − and + beside a value. */
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
        Text(text = text, style = midadStyle(22.sp, FontWeight.Medium, if (focused) Midad.OnGold else Midad.Text))
    }
}
