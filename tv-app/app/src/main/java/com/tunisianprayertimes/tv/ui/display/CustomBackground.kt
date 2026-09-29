package com.tunisianprayertimes.tv.ui.display

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.tunisianprayertimes.tv.ui.theme.Midad
import kotlinx.coroutines.delay

/**
 * The mosque's own images in place of the sky: across the top of the screen down to [groundAt],
 * under a veil of the ground so the ivory text above them stays readable, and fading into the ground
 * from [horizon] on, where the timetable begins. One image after another, slowly, with a crossfade.
 */
@Composable
fun CustomBackground(
    images: List<Uri>,
    modifier: Modifier = Modifier,
    horizon: Dp = 300.dp,
    groundAt: Dp = 365.dp,
    cycleMillis: Long = 60_000L,
) {
    if (images.isEmpty()) return
    var index by remember(images) { mutableIntStateOf(0) }
    LaunchedEffect(images) {
        if (images.size <= 1) return@LaunchedEffect
        while (true) {
            delay(cycleMillis)
            index = (index + 1) % images.size
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(groundAt)
            .drawWithCache {
                val start = (horizon.toPx() / size.height).coerceIn(0f, 1f)
                val fade = Brush.verticalGradient(
                    0f to Midad.Ground.copy(alpha = VEIL),
                    start to Midad.Ground.copy(alpha = VEIL),
                    1f to Midad.Ground,
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(fade)
                }
            },
    ) {
        Crossfade(targetState = images[index % images.size], animationSpec = tween(FADE_MILLIS), label = "background") { uri ->
            Image(
                painter = rememberAsyncImagePainter(uri),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * How much of the ground lies over an image. A mosque's photo can be bright (a white minaret, a pale
 * sky): at 0.8 even pure white stays dark enough for the gold countdown and the grey labels (over
 * 5.5:1) and the ivory clock (about 9:1), while the picture still shows through.
 */
private const val VEIL = 0.8f
private const val FADE_MILLIS = 800
