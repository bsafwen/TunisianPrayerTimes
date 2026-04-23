package com.tunisianprayertimes.wake

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.MathDifficulty
import com.tunisianprayertimes.R
import kotlinx.coroutines.delay
import kotlin.random.Random

private const val GRID_SIZE = 3
private const val TOTAL_HOLES = GRID_SIZE * GRID_SIZE

@Composable
internal fun WhackAMoleGame(
    killTarget: Int,
    difficulty: MathDifficulty,
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timing = when (difficulty) {
        MathDifficulty.EASY -> GameTiming(1600L, 700L, 150, 100)
        MathDifficulty.INTERMEDIATE -> GameTiming(950L, 350L, 120, 80)
        MathDifficulty.HARD -> GameTiming(500L, 150L, 80, 60)
    }

    var kills by rememberSaveable { mutableIntStateOf(0) }
    var completed by rememberSaveable { mutableStateOf(false) }
    var activeHole by remember { mutableStateOf(-1) }
    var whackedHole by remember { mutableStateOf(-1) }

    LaunchedEffect(completed, timing) {
        if (completed) return@LaunchedEffect
        while (true) {
            val hole = Random.nextInt(TOTAL_HOLES)
            whackedHole = -1
            activeHole = hole
            delay(timing.visibilityMs)
            activeHole = -1
            delay(timing.spawnDelayMs)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.wake_alarm_whack_a_mole_required),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Text(
            text = stringResource(R.string.wake_alarm_whack_a_mole_progress, kills, killTarget),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (row in 0 until GRID_SIZE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (col in 0 until GRID_SIZE) {
                        val index = row * GRID_SIZE + col
                        MoleHole(
                            isActive = activeHole == index && !completed,
                            wasWhacked = whackedHole == index,
                            popUpAnimMs = timing.popUpAnimMs,
                            hideAnimMs = timing.hideAnimMs,
                            modifier = Modifier.weight(1f),
                            onWhack = {
                                if (activeHole == index && !completed) {
                                    activeHole = -1
                                    whackedHole = index
                                    val newKills = kills + 1
                                    kills = newKills
                                    if (newKills >= killTarget) {
                                        completed = true
                                        onCompleted()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        if (completed) {
            Text(
                text = stringResource(R.string.wake_alarm_whack_a_mole_complete),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

private data class GameTiming(
    val visibilityMs: Long,
    val spawnDelayMs: Long,
    val popUpAnimMs: Int,
    val hideAnimMs: Int,
)

private val HoleColor = Color(0xFF5D4037)
private val MoleColor = Color(0xFF8D6E63)
private val MoleNoseColor = Color(0xFFD81B60)

@Composable
private fun MoleHole(
    isActive: Boolean,
    wasWhacked: Boolean,
    popUpAnimMs: Int,
    hideAnimMs: Int,
    modifier: Modifier = Modifier,
    onWhack: () -> Unit,
) {
    val density = LocalDensity.current
    val offsetPx = with(density) { 48.dp.toPx() }
    val animatedOffset = remember { Animatable(offsetPx) }

    LaunchedEffect(isActive) {
        if (isActive) {
            animatedOffset.snapTo(offsetPx)
            animatedOffset.animateTo(0f, animationSpec = tween(popUpAnimMs))
        } else {
            animatedOffset.animateTo(offsetPx, animationSpec = tween(hideAnimMs))
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(HoleColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onWhack,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isActive || animatedOffset.value < offsetPx) {
            Box(
                modifier = Modifier
                    .fillMaxSize(0.65f)
                    .offset { IntOffset(0, animatedOffset.value.toInt()) }
                    .clip(CircleShape)
                    .background(MoleColor),
                contentAlignment = Alignment.Center,
            ) {
                // Mole nose — only visible when user tapped the hamster
                if (wasWhacked) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(0.25f)
                            .clip(CircleShape)
                            .background(MoleNoseColor),
                    )
                }
            }
        }
        // Emoji eyes on top of mole
        if (isActive) {
            Text(
                text = "🐹",
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .offset { IntOffset(0, animatedOffset.value.toInt()) }
                    .padding(4.dp),
            )
        }
    }
}
