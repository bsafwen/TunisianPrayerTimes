package com.tunisianprayertimes.tv.ui.display

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.mosque.AdhkarSlide
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.theme.BackgroundDark
import com.tunisianprayertimes.tv.ui.theme.CardBorder
import com.tunisianprayertimes.tv.ui.theme.Gold
import com.tunisianprayertimes.tv.ui.theme.GoldLight
import com.tunisianprayertimes.tv.ui.theme.SurfaceCard
import com.tunisianprayertimes.tv.ui.theme.SurfaceDark
import com.tunisianprayertimes.tv.ui.theme.TextMuted
import com.tunisianprayertimes.tv.ui.theme.TextWhite

/**
 * The adhkar after the prayer: [slide] is the one [MosqueAdhkar.slideAt][com.tunisianprayertimes.mosque.MosqueAdhkar.slideAt]
 * picked from the time since the prayer ended, so a restart resumes on the same text. Stateless and
 * without animation; minimal on purpose, the screen will be redesigned.
 */
@Composable
fun AfterSalahAzkarScreen(slide: AdhkarSlide, index: Int, total: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BackgroundDark, SurfaceDark, Color(0xFF081428), BackgroundDark)))
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(TvStrings.AFTER_SALAH_TITLE, color = Gold, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(TvStrings.progress(index + 1, total), color = TextMuted, fontSize = 16.sp)
            Spacer(Modifier.height(16.dp))
            DhikrCard(slide, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** One text with how many times to say it and its source; the font shrinks with the length so nothing is cut. */
@Composable
fun DhikrCard(slide: AdhkarSlide, modifier: Modifier = Modifier, maxFont: TextUnit = 52.sp) {
    Column(
        modifier = modifier
            .background(SurfaceCard.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(24.dp))
            .padding(horizontal = 40.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        val size = fontFor(slide.text, maxFont)
        Text(
            slide.text,
            color = TextWhite,
            fontSize = size,
            lineHeight = size * 1.6f,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        val count = TvStrings.times(slide.count)
        val part = if (slide.parts > 1) TvStrings.part(slide.part, slide.parts) else null
        listOfNotNull(count, part).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), color = GoldLight, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Text(slide.reference, color = TextMuted, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

/** Shorter texts large, longer ones smaller, never below what a congregation can read from the back. */
private fun fontFor(text: String, max: TextUnit): TextUnit = when {
    text.length <= 40 -> max
    text.length <= 90 -> max * 0.8f
    text.length <= 160 -> max * 0.66f
    text.length <= 240 -> max * 0.56f
    else -> max * 0.5f
}
