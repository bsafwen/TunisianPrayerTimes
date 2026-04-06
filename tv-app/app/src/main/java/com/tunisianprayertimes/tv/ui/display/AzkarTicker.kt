package com.tunisianprayertimes.tv.ui.display

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.tv.data.AzkarData
import com.tunisianprayertimes.tv.ui.theme.*

/**
 * Scrolling ticker that displays azkar/ayat in a marquee style.
 * Continuously scrolls RTL, cycling through the items.
 */
@Composable
fun AzkarTicker(
    isRamadan: Boolean,
    modifier: Modifier = Modifier
) {
    val items = if (isRamadan) AzkarData.RAMADAN_TICKER_ITEMS else AzkarData.TICKER_ITEMS
    var currentIndex by remember { mutableIntStateOf(0) }
    var nextVisible by remember { mutableStateOf(true) }

    // Cycle through items with fade
    LaunchedEffect(currentIndex) {
        nextVisible = true
        kotlinx.coroutines.delay(12000L) // Show each item for 12 seconds
        nextVisible = false
        kotlinx.coroutines.delay(500L)
        currentIndex = (currentIndex + 1) % items.size
    }

    val bgColor = if (isRamadan) RamadanPurple.copy(alpha = 0.5f) else SurfaceDark.copy(alpha = 0.7f)
    val textColor = if (isRamadan) RamadanMoon else GoldLight
    val borderCol = if (isRamadan) RamadanGold.copy(alpha = 0.2f) else CardBorder

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(bgColor, RoundedCornerShape(14.dp))
            .border(1.dp, borderCol, RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp)
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = nextVisible,
            enter = androidx.compose.animation.fadeIn(animationSpec = tween(400)),
            exit = androidx.compose.animation.fadeOut(animationSpec = tween(400))
        ) {
            Text(
                text = items[currentIndex],
                style = MaterialTheme.typography.titleMedium,
                color = textColor,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                lineHeight = 32.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
