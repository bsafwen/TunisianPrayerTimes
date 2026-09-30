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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.tunisianprayertimes.tv.ui.theme.Midad
import java.time.LocalDateTime

/**
 * The mosque's own images in place of the sky: across the top of the screen down to [groundAt],
 * under a veil of the ground so the ivory text above them stays readable, and fading into the ground
 * from [horizon] on, where the timetable begins. One image after another, slowly, with a crossfade:
 * which one is shown follows the clock ([now], [backgroundIndex]).
 */
@Composable
fun CustomBackground(
    images: List<Uri>,
    now: LocalDateTime,
    modifier: Modifier = Modifier,
    horizon: Dp = 300.dp,
    groundAt: Dp = 365.dp,
) {
    if (images.isEmpty()) return
    val index = backgroundIndex(now, images.size)
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
        Crossfade(targetState = images[index], animationSpec = tween(FADE_MILLIS), label = "background") { uri ->
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
 * Which of [count] images shows at [now]: the next one every [cycleMinutes], counted on the clock
 * rather than from when the main screen came back. The screen gives way to the adhan and the
 * announcements many times a day; the round goes on across them and a restart, and the last images
 * come up as often as the first.
 */
internal fun backgroundIndex(now: LocalDateTime, count: Int, cycleMinutes: Long = CYCLE_MINUTES): Int {
    if (count <= 1) return 0
    val minutes = now.toLocalDate().toEpochDay() * 24 * 60 + now.hour * 60 + now.minute
    return Math.floorMod(Math.floorDiv(minutes, cycleMinutes), count.toLong()).toInt()
}

private const val CYCLE_MINUTES = 1L

/**
 * How much of the ground lies over an image. A mosque's photo can be bright (a white minaret, a pale
 * sky): at 0.8 even pure white stays dark enough for the gold countdown and the grey labels (over
 * 5.5:1) and the ivory clock (about 9:1), while the picture still shows through.
 */
private const val VEIL = 0.8f
private const val FADE_MILLIS = 800
