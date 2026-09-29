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
import com.tunisianprayertimes.mosque.AdhkarSlide
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.theme.*

/**
 * Full-screen overlay when adhan time is reached: the prayer's name and what is said with the
 * muezzin and after the call ([companion], from the reviewed catalog, paced over the adhan screen).
 */
@Composable
fun AdhanScreen(
    prayer: Prayer,
    companion: AdhkarSlide?,
) {
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

            companion?.let { DhikrCard(it, Modifier.fillMaxWidth(0.8f), maxFont = 30.sp) }
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
) {
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
                // The Eid prayer has no adhan or iqamah: the countdown is to the prayer itself.
                text = if (prayer in com.tunisianprayertimes.mosque.MosqueSchedule.EID) TvStrings.EID_PRAYER_IN else "${TvStrings.IQAMAH_SOON}...",
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

            // In the last moments before the iqamah, under the countdown (which stays visible).
            if (remainingSeconds <= SILENCE_PHONES_SECONDS) {
                Spacer(Modifier.height(28.dp))
                Text(
                    text = TvStrings.SILENCE_PHONES,
                    color = TextWhite,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color(0xFF8B1E1E), RoundedCornerShape(16.dp))
                        .padding(horizontal = 32.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** How long before the iqamah the congregation is asked to silence their phones. */
const val SILENCE_PHONES_SECONDS = 90

/**
 * After the iqamah the screen goes fully black for the prayer's duration, so nothing on
 * the wall distracts the congregation. The prayer flow decides when it ends.
 */
@Composable
fun PrayerBlackScreen() {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
}

/**
 * The Friday sermon, between the Jumu'a adhan and its iqamah: a dim, still screen asking for
 * silence, so nothing on the wall competes with the khatib. Minimal; to be redesigned.
 */
@Composable
fun KhutbaScreen() {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(TvStrings.KHUTBA_TIME, color = Color(0xFF6B6B6B), fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(TvStrings.KHUTBA_LISTEN, color = Color(0xFF4A4A4A), fontSize = 26.sp)
        }
    }
}
