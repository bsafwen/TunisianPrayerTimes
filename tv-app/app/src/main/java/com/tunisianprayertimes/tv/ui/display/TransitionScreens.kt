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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Full-screen overlay when adhan time is reached.
 * Shows "الله أكبر", prayer name, and after-adhan duaa.
 */
@Composable
fun AdhanScreen(
    prayer: Prayer,
    onDismiss: () -> Unit
) {
    // Auto-dismiss after 4 minutes
    LaunchedEffect(prayer) {
        delay(4 * 60 * 1000L)
        onDismiss()
    }

    // Pulsing animation for the Allahu Akbar text
    val infiniteTransition = rememberInfiniteTransition(label = "adhanPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alphaAnim"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        BackgroundDark,
                        SurfaceDark,
                        Color(0xFF081428),
                        BackgroundDark
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(48.dp)
        ) {
            // الله أكبر
            Text(
                text = TvStrings.ALLAHU_AKBAR,
                style = MaterialTheme.typography.displayLarge,
                color = Gold,
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(alpha)
            )

            Spacer(Modifier.height(24.dp))

            // Thin decorative line
            Box(
                modifier = Modifier.width(120.dp).height(1.dp)
                    .background(Gold.copy(alpha = 0.5f))
            )

            Spacer(Modifier.height(24.dp))

            // Prayer name
            Text(
                text = "أذان ${TvStrings.prayerName(prayer)}",
                style = MaterialTheme.typography.headlineLarge,
                color = GoldLight,
                fontSize = 48.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(56.dp))

            // After-adhan duaa card
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .background(SurfaceCard.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
                    .padding(32.dp)
            ) {
                Text(
                    text = TvStrings.AFTER_ADHAN_DUA,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextWhite.copy(alpha = 0.9f),
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 40.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Countdown screen between adhan and iqamah.
 * Shows big countdown numbers.
 */
@Composable
fun IqamahCountdownScreen(
    prayer: Prayer,
    remainingSeconds: Int,
    onDismiss: () -> Unit
) {
    // Auto-dismiss when countdown reaches 0
    LaunchedEffect(remainingSeconds) {
        if (remainingSeconds <= 0) onDismiss()
    }

    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val countdownStr = String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)

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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "${TvStrings.IQAMAH_SOON}...",
                style = MaterialTheme.typography.headlineLarge,
                color = GoldLight,
                fontSize = 36.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = TvStrings.prayerName(prayer),
                style = MaterialTheme.typography.headlineMedium,
                color = Gold,
                fontSize = 32.sp
            )

            Spacer(Modifier.height(40.dp))

            // Big countdown pill
            Box(
                modifier = Modifier
                    .background(SurfaceCard.copy(alpha = 0.7f), RoundedCornerShape(28.dp))
                    .border(1.dp, CountdownAmber.copy(alpha = 0.4f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 56.dp, vertical = 20.dp)
            ) {
                Text(
                    text = countdownStr,
                    style = MaterialTheme.typography.displayLarge,
                    color = CountdownAmber,
                    fontSize = 160.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Screen shown when iqamah is called — prayer in progress.
 */
@Composable
fun PrayerInProgressScreen(
    prayer: Prayer,
    onDismiss: () -> Unit
) {
    // Auto-dismiss after estimated prayer duration
    val durationMs = when (prayer) {
        Prayer.FAJR -> 15 * 60 * 1000L
        Prayer.DHUHR, Prayer.ASR, Prayer.ISHA -> 20 * 60 * 1000L
        Prayer.MAGHRIB -> 12 * 60 * 1000L
        Prayer.JOMOAA -> 45 * 60 * 1000L
    }

    LaunchedEffect(prayer) {
        delay(durationMs)
        onDismiss()
    }

    val infiniteTransition = rememberInfiniteTransition(label = "prayerPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "prayerAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        BackgroundDark,
                        Color(0xFF081428),
                        SurfaceDark,
                        BackgroundDark
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = TvStrings.PRAYER_STARTED,
                style = MaterialTheme.typography.displayMedium,
                color = Gold,
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(alpha)
            )

            Spacer(Modifier.height(16.dp))

            // Thin line
            Box(
                modifier = Modifier.width(120.dp).height(1.dp)
                    .background(Gold.copy(alpha = 0.4f))
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = TvStrings.prayerName(prayer),
                style = MaterialTheme.typography.headlineLarge,
                color = GoldLight,
                fontSize = 48.sp
            )
        }
    }
}
