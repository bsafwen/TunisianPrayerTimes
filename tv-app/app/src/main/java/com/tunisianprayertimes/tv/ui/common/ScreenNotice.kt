package com.tunisianprayertimes.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A short message over the bottom of the screen (USB key, clock). Minimal on purpose; it will be redesigned. */
@Composable
fun ScreenNotice(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            message,
            color = Color.White,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xE6000000), RoundedCornerShape(12.dp))
                .padding(16.dp),
        )
    }
}

/** A discreet mark at the top of the display, e.g. while someone manages the screen from a phone. */
@Composable
fun TopMark(message: String) {
    Box(Modifier.fillMaxSize().padding(top = 2.dp), contentAlignment = Alignment.TopCenter) {
        Text(message, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
    }
}
