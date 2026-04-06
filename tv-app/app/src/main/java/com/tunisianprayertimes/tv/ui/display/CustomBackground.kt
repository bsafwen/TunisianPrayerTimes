package com.tunisianprayertimes.tv.ui.display

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/**
 * Rotating background image layer.
 * Cycles through provided URIs with a slow crossfade.
 * Falls back to a solid color if no images available.
 */
@Composable
fun CustomBackground(
    images: List<Uri>,
    cycleIntervalMs: Long = 60_000L, // change every 60 seconds
    overlayAlpha: Float = 0.7f, // dark overlay so prayer text remains readable
    modifier: Modifier = Modifier
) {
    if (images.isEmpty()) return

    var currentIndex by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(true) }

    // Cycle through images
    LaunchedEffect(images.size) {
        if (images.size <= 1) return@LaunchedEffect
        while (true) {
            visible = true
            delay(cycleIntervalMs)
            visible = false
            delay(800L)
            currentIndex = (currentIndex + 1) % images.size
        }
    }

    val context = LocalContext.current

    Box(modifier = modifier.fillMaxSize()) {
        // Background image
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(800)),
            exit = fadeOut(tween(800))
        ) {
            val painter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    .data(images[currentIndex])
                    .crossfade(true)
                    .build()
            )
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // Dark overlay for text readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = overlayAlpha))
        )
    }
}
