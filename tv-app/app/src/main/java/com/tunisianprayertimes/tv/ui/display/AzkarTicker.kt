package com.tunisianprayertimes.tv.ui.display

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.mosque.AdhkarSlide
import com.tunisianprayertimes.mosque.MosqueAdhkar
import com.tunisianprayertimes.tv.ui.theme.CardBorder
import com.tunisianprayertimes.tv.ui.theme.GoldLight
import com.tunisianprayertimes.tv.ui.theme.RamadanGold
import com.tunisianprayertimes.tv.ui.theme.RamadanMoon
import com.tunisianprayertimes.tv.ui.theme.RamadanPurple
import com.tunisianprayertimes.tv.ui.theme.SurfaceDark
import com.tunisianprayertimes.tv.ui.theme.TextMuted
import kotlinx.coroutines.delay

/**
 * The short daily texts under the prayer times, one at a time, each for as long as it takes to read,
 * with its source. A text too long for the line scrolls instead of being cut.
 */
@Composable
fun AzkarTicker(
    items: List<AdhkarSlide>,
    isRamadan: Boolean,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    var index by remember(items) { mutableIntStateOf(0) }
    // The list can change (a new USB file): never index past it.
    val slide = items[index % items.size]
    val textStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // A text wider than the line scrolls; it stays until its end has been on screen for a moment.
        val measurer = rememberTextMeasurer()
        val lineWidth = constraints.maxWidth - with(LocalDensity.current) { 48.dp.roundToPx() }
        val textWidth = remember(slide.text, textStyle) {
            measurer.measure(slide.text, textStyle, maxLines = 1, softWrap = false).size.width
        }
        val pxPerSecond = with(LocalDensity.current) { MARQUEE_DP_PER_SECOND.dp.toPx() }
        val scrollMillis = if (textWidth <= lineWidth) 0L else (MARQUEE_DELAY_MILLIS + (textWidth - lineWidth) / pxPerSecond * 1000 + END_PAUSE_MILLIS).toLong()
        LaunchedEffect(items, index) {
            delay(maxOf(MosqueAdhkar.TICKER_MIN_SLIDE_MILLIS, slide.durationMillis, scrollMillis))
            index = (index + 1) % items.size
        }
        TickerBox(slide, isRamadan, textStyle)
    }
}

@Composable
private fun TickerBox(slide: AdhkarSlide, isRamadan: Boolean, textStyle: androidx.compose.ui.text.TextStyle) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(
                if (isRamadan) RamadanPurple.copy(alpha = 0.5f) else SurfaceDark.copy(alpha = 0.7f),
                RoundedCornerShape(14.dp),
            )
            .border(1.dp, if (isRamadan) RamadanGold.copy(alpha = 0.2f) else CardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = slide.text,
            style = textStyle,
            color = if (isRamadan) RamadanMoon else GoldLight,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, velocity = MARQUEE_DP_PER_SECOND.dp),
        )
        Text(slide.reference, color = TextMuted, fontSize = 13.sp, maxLines = 1)
    }
}

private const val MARQUEE_DP_PER_SECOND = 30
private const val MARQUEE_DELAY_MILLIS = 1_200
private const val END_PAUSE_MILLIS = 3_000
