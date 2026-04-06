package com.tunisianprayertimes.tv.ui.display

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.tunisianprayertimes.tv.data.Announcement
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Full-screen slideshow of mosque announcements.
 * Cycles through images and text announcements with fade transitions.
 * Shown between prayers when announcements are available.
 */
@Composable
fun AnnouncementsSlideshow(
    announcements: List<Announcement>,
    displaySeconds: Int = 15,
    onDismiss: () -> Unit
) {
    if (announcements.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    var currentIndex by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(true) }

    // Auto-dismiss after showing all announcements once
    var cycleCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(currentIndex) {
        visible = true
        delay(displaySeconds * 1000L)
        visible = false
        delay(600L)
        val nextIndex = (currentIndex + 1) % announcements.size
        if (nextIndex == 0) cycleCount++
        if (cycleCount >= 1) {
            onDismiss()
        } else {
            currentIndex = nextIndex
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(500)),
            exit = fadeOut(tween(500))
        ) {
            when (val item = announcements[currentIndex]) {
                is Announcement.Image -> AnnouncementImageSlide(uri = item.uri)
                is Announcement.Text -> AnnouncementTextSlide(
                    text = item.content,
                    title = item.title
                )
            }
        }

        // Progress indicator at bottom
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            announcements.forEachIndexed { index, _ ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (index == currentIndex) 12.dp else 8.dp)
                        .background(
                            if (index == currentIndex) Gold else TextMuted.copy(alpha = 0.3f),
                            CircleShape
                        )
                )
            }
        }
    }
}

@Composable
private fun AnnouncementImageSlide(uri: Uri) {
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(uri)
            .crossfade(true)
            .build()
    )

    Image(
        painter = painter,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun AnnouncementTextSlide(text: String, title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(BackgroundDark, SurfaceDark, BackgroundDark)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .background(SurfaceCard.copy(alpha = 0.85f), RoundedCornerShape(24.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(24.dp))
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Gold,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }

            Text(
                text = text,
                style = MaterialTheme.typography.headlineLarge,
                color = TextWhite,
                fontSize = 32.sp,
                textAlign = TextAlign.Center,
                lineHeight = 48.sp
            )
        }
    }
}
