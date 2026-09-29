package com.tunisianprayertimes.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle

/**
 * A short message over the bottom of the screen (a USB key, the clock): a dark card with a hairline
 * edge, big enough to be read from the back of the hall, as wide as its words and no wider.
 */
@Composable
fun ScreenNotice(message: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            message,
            style = midadStyle(18.sp, lineHeight = 1.5f).rtl(),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = 760.dp)
                .background(Midad.Surface, NOTICE_SHAPE)
                .border(1.dp, Midad.Keyline, NOTICE_SHAPE)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}

/** A discreet line at the top of the display, e.g. while someone manages the screen from a phone. */
@Composable
fun TopMark(message: String) {
    Box(Modifier.fillMaxSize().padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
        Text(message, style = midadStyle(12.sp, color = Midad.Dim).rtl())
    }
}

private val NOTICE_SHAPE = RoundedCornerShape(14.dp)
