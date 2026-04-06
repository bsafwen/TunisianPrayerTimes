package com.tunisianprayertimes.tv.ui.display

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.tv.data.AzkarData
import com.tunisianprayertimes.tv.data.Dhikr
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.theme.*
import kotlinx.coroutines.delay

/**
 * After-salah adhkar screen — shows rotating adhkar one by one with fade transitions.
 * Auto-advances every ~8 seconds. Dismisses after all adhkar shown or configurable duration.
 */
@Composable
fun AfterSalahAzkarScreen(
    prayer: Prayer,
    durationMinutes: Int = 10,
    onDismiss: () -> Unit
) {
    val azkar = AzkarData.AFTER_SALAH_AZKAR
    var currentIndex by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(true) }

    // Auto-dismiss after duration
    LaunchedEffect(Unit) {
        delay(durationMinutes * 60 * 1000L)
        onDismiss()
    }

    // Rotate adhkar
    LaunchedEffect(currentIndex) {
        visible = true
        delay(8000L) // Show each dhikr for 8 seconds
        visible = false
        delay(600L) // Fade out duration
        currentIndex = (currentIndex + 1) % azkar.size
    }

    val dhikr = azkar[currentIndex]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(BackgroundDark, SurfaceDark, Color(0xFF081428), BackgroundDark)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = TvStrings.AFTER_SALAH_TITLE,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Gold,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Progress dots
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                azkar.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (index == currentIndex) 10.dp else 6.dp)
                            .background(
                                if (index == currentIndex) Gold
                                else if (index < currentIndex) GoldMuted.copy(alpha = 0.5f)
                                else TextDim.copy(alpha = 0.25f),
                                CircleShape
                            )
                    )
                }
            }

            // Dhikr card with fade animation
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 64.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(500)),
                    exit = fadeOut(tween(500))
                ) {
                    DhikrCard(dhikr = dhikr)
                }
            }

            // Counter: X / total
            Text(
                text = "${currentIndex + 1} / ${azkar.size}",
                style = MaterialTheme.typography.bodyLarge,
                color = TextMuted,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 32.dp)
            )
        }
    }
}

@Composable
private fun DhikrCard(dhikr: Dhikr) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard.copy(alpha = 0.8f), RoundedCornerShape(24.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(24.dp))
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main dhikr text
        Text(
            text = dhikr.text,
            style = MaterialTheme.typography.headlineLarge,
            color = TextWhite,
            fontSize = 32.sp,
            textAlign = TextAlign.Center,
            lineHeight = 52.sp
        )

        Spacer(Modifier.height(28.dp))

        // Thin decorative line
        Box(
            modifier = Modifier.width(80.dp).height(1.dp)
                .background(Gold.copy(alpha = 0.4f))
        )

        Spacer(Modifier.height(20.dp))

        // Repetition badge
        Box(
            modifier = Modifier
                .background(Gold.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                .border(1.dp, Gold.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text(
                text = dhikr.repetition,
                style = MaterialTheme.typography.titleMedium,
                color = Gold,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(12.dp))

        // Source
        Text(
            text = dhikr.source,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            fontSize = 16.sp
        )
    }
}
