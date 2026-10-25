package com.tunisianprayertimes.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tunisianprayertimes.MathDifficulty
import com.tunisianprayertimes.R
import com.tunisianprayertimes.WakeUpCheckStep
import com.tunisianprayertimes.WakeUpCheckType
import com.tunisianprayertimes.ui.theme.BgCream
import kotlinx.coroutines.delay
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import java.util.Locale

class WakeAlertActivity : AppCompatActivity() {
    private var payload by mutableStateOf<WakeTriggerPayload?>(null)
    private var pendingCount by mutableIntStateOf(0)
    private val queue get() = WakeAlarmQueueHolder.queue

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_SYNC_WAKE_ALERT) {
                showQueueCurrentOrFinish()
                return
            }
            val eventId = intent.wakeEventId()
            android.util.Log.d("WakeFlow", "Activity.dismissReceiver eventId=$eventId current=${queue.current?.eventId}")
            if (!queue.handleDismiss(eventId)) {
                signalServiceCurrentChanged()
                finish()
            } else {
                payload = queue.current
                pendingCount = queue.pendingCount
                signalServiceCurrentChanged()
            }
        }
    }

    /**
     * Notify the playback service that the queue has advanced so it can
     * refresh its notification and ringtone for the new current alarm
     * (or stop itself if the queue is now empty).
     */
    private fun signalServiceCurrentChanged() {
        startService(
            Intent(this, WakePlaybackService::class.java)
                .setAction(WakePlaybackService.ACTION_REFRESH_FOR_CURRENT),
        )
    }

    private fun advanceToNextPayload(): Boolean {
        val advanced = queue.advance()
        if (advanced) showQueueCurrentOrFinish()
        return advanced
    }

    /**
     * Shows the queue's current alarm, or finishes when there is none. A finishing window keeps the
     * alarm it showed: clearing [payload] would recompose it into the empty fallback layout while it
     * animates away, which reads as a flicker on Stop.
     */
    private fun showQueueCurrentOrFinish(): Boolean {
        val current = queue.current
        if (current == null) {
            finish()
            return false
        }
        payload = current
        pendingCount = queue.pendingCount
        return true
    }

    override fun attachBaseContext(newBase: Context) {
        val locale = Locale.forLanguageTag("ar-TN-u-nu-latn")
        Locale.setDefault(locale)
        val config = newBase.resources.configuration.apply { setLocale(locale) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        configureWindowForAlarm()

        android.util.Log.d(
            "WakeFlow",
            "Activity.onCreate intentEventId=${intent?.wakeEventId()} queueCurrent=${queue.current?.eventId} pending=${queue.pendingEventIds()}",
        )

        // The queue is a process-wide singleton populated by WakePlaybackService
        // before it launches us. Just read the current payload — never replay
        // the intent extras (which would risk duplicate / stale delivery).
        payload = queue.current
        pendingCount = queue.pendingCount

        // If the singleton has nothing, the alarm session is already over (we
        // were probably re-launched by a stale PendingIntent — e.g. a deferred
        // fullScreenIntent for a long-since-cancelled notification). Bail out
        // without resurrecting the dead alarm.
        if (payload == null) {
            android.util.Log.d(
                "WakeFlow",
                "Activity.onCreate finishing immediately — queue is empty (stale launch)",
            )
            finish()
            return
        }

        setContent {
            TunisianPrayerTimesTheme {
                val displayedPayload = payload
                WakeAlertScreen(
                    payload = displayedPayload,
                    pendingCount = pendingCount,
                    onStop = { wakeupCheckCompleted ->
                        android.util.Log.d(
                            "WakeFlow",
                            "Activity.onStopButton payloadEventId=${displayedPayload?.eventId} queueCurrent=${queue.current?.eventId}",
                        )
                        if (displayedPayload !== queue.current) {
                            // Skip can remove the displayed alarm before its sync broadcast arrives.
                            // A Stop tap for that stale screen must not dismiss the promoted alarm.
                            signalServiceCurrentChanged()
                            showQueueCurrentOrFinish()
                        } else if (displayedPayload != null) {
                            val p = displayedPayload
                            WakeDismissalCoordinator.recordDismissal(
                                context = this@WakeAlertActivity,
                                payload = p,
                                stopSource = "alert_activity",
                                wakeupCheckCompleted = wakeupCheckCompleted,
                            )
                            val advanced = advanceToNextPayload()
                            WakeDismissalCoordinator.removeExpiredOneOffAlarmAfterDismissalAsync(
                                context = this@WakeAlertActivity,
                                payload = p,
                            )
                            // Ask the service to refresh notification + ringtone
                            // for whatever is now current, or stop itself if the
                            // queue is empty.
                            signalServiceCurrentChanged()
                            if (!advanced) {
                                finish()
                            }
                        }
                    },

                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        android.util.Log.d(
            "WakeFlow",
            "Activity.onNewIntent intentEventId=${intent.wakeEventId()} queueCurrent=${queue.current?.eventId}",
        )
        // Only trust the queue (populated by the service). Never feed intent
        // extras into the queue from here — a stale PendingIntent (e.g. a
        // deferred fullScreenIntent) could otherwise resurrect a dead alarm.
        if (queue.current == null) {
            android.util.Log.d(
                "WakeFlow",
                "Activity.onNewIntent finishing — queue is empty (stale launch)",
            )
        }
        showQueueCurrentOrFinish()
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.d(
            "WakeFlow",
            "Activity.onStart queueCurrent=${queue.current?.eventId} pending=${queue.pendingEventIds()}",
        )
        // Resync from the singleton in case the queue advanced or was cleared
        // while this activity was paused/stopped (e.g., user opened another app
        // and a sub-alarm fired).
        // Nothing to show — either the alarm was dismissed externally or
        // the process was just rebuilt with no queue state. Bail out.
        if (!showQueueCurrentOrFinish()) return
        ContextCompat.registerReceiver(
            this,
            dismissReceiver,
            IntentFilter(ACTION_DISMISS_WAKE_ALERT).apply { addAction(ACTION_SYNC_WAKE_ALERT) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        android.util.Log.d("WakeFlow", "Activity.onStop queueCurrent=${queue.current?.eventId}")
        runCatching { unregisterReceiver(dismissReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        android.util.Log.d("WakeFlow", "Activity.onDestroy isFinishing=$isFinishing queueCurrent=${queue.current?.eventId}")
        super.onDestroy()
    }

    // No onSaveInstanceState needed — the queue is a process-wide singleton
    // (WakeAlarmQueueHolder) that survives activity destruction naturally.

    private fun configureWindowForAlarm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

}

@Composable
private fun WakeAlertScreen(
    payload: WakeTriggerPayload?,
    pendingCount: Int,
    onStop: (Boolean) -> Unit,
) {
    val checkEnabled = payload?.wakeUpCheckEnabled == true
    val eventId = payload?.eventId ?: ""

    // payload is guaranteed non-null when checkEnabled is true (since checkEnabled
    // is derived from payload?.wakeUpCheckEnabled == true).
    val steps: List<WakeUpCheckStep> = if (!checkEnabled) {
        emptyList()
    } else if (payload!!.wakeUpCheckSteps.isNotEmpty()) {
        payload.wakeUpCheckSteps
    } else {
        listOf(WakeUpCheckStep(payload.wakeUpCheckType, payload.wakeUpCheckDifficulty))
    }

    var completedSteps by rememberSaveable(eventId) { mutableIntStateOf(0) }
    val allDone = !checkEnabled || completedSteps >= steps.size
    val currentStep = if (!allDone) steps[completedSteps] else null

    // Back would close the screen while the alarm keeps ringing, and the progress would be lost.
    // A challenge alarm has no Stop in its notification, so Back is swallowed even once the steps are done.
    BackHandler(enabled = checkEnabled) { }

    val gradient = Brush.verticalGradient(
        colors = listOf(GreenPrimaryDark, GreenPrimary, BgCream),
    )

    Surface {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WakeAlarmHeader(payload = payload, pendingCount = pendingCount)

                if (steps.size > 1 && !allDone) {
                    WakeStepProgress(completed = completedSteps, total = steps.size)
                }

                // Whatever the challenge is, it lives in this one box: the screen is laid out once
                // and nothing around the game moves while it is played.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    if (payload == null) {
                        WakeChallengeNote(stringResource(R.string.wake_alarm_now_ringing))
                    } else if (currentStep != null) {
                        // A new composition per step and per alarm, so two steps of the same
                        // game never share their state.
                        key(eventId, completedSteps) {
                            WakeChallengeStep(
                                payload = payload,
                                step = currentStep,
                                stepIndex = completedSteps,
                                onSolved = { completedSteps++ },
                            )
                        }
                    } else if (checkEnabled) {
                        WakeChallengeNote(stringResource(R.string.wake_alarm_stop_challenge_done))
                    }
                }

                Button(
                    onClick = { onStop(allDone) },
                    enabled = allDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    // Solid dark when it works, a flat grey pill when it is locked: both read on every
                    // part of the gradient, with or without the keyboard under the button.
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GreenPrimaryDark,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = LockedStopContainer,
                        disabledContentColor = LockedStopContent,
                    ),
                ) {
                    Text(text = stringResource(R.string.wake_alarm_stop))
                }
            }
        }
    }
}

private val LockedStopContainer = androidx.compose.ui.graphics.Color(0xFFC9D3CF)
private val LockedStopContent = androidx.compose.ui.graphics.Color(0xFF4A5A55)

/**
 * The alarm's name and time. Next to the time, only what those two do not already say: that this is
 * an extra alert, and how many alarms are waiting. The lines share the clock's height, so a
 * queued alarm arriving during a game does not push the game down.
 */
@Composable
private fun WakeAlarmHeader(
    payload: WakeTriggerPayload?,
    pendingCount: Int,
) {
    val context = LocalContext.current
    val onGradient = MaterialTheme.colorScheme.onPrimary
    val queueText = if (pendingCount > 0) {
        pluralStringResource(R.plurals.wake_alarm_queue, pendingCount, pendingCount)
    } else {
        null
    }
    val infoLines = listOfNotNull(
        payload?.takeIf { it.isSubAlarm }?.alertTypeLabel(context),
        queueText,
    )

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = payload?.title(context) ?: stringResource(R.string.wake_alarm_fallback_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = onGradient,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = payload?.let { formatWakeTime(it.hour, it.minute) }
                    ?: stringResource(R.string.wake_alarm_time_placeholder),
                style = MaterialTheme.typography.displayMedium,
                color = onGradient,
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
            ) {
                infoLines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onGradient.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** "Challenge 2 of 3" with one segment per challenge. */
@Composable
private fun WakeStepProgress(completed: Int, total: Int) {
    val onGradient = MaterialTheme.colorScheme.onPrimary
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.wake_alarm_step_progress, completed + 1, total),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = onGradient,
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(total) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(
                            onGradient.copy(
                                alpha = when {
                                    index < completed -> 1f
                                    index == completed -> 0.6f
                                    else -> 0.25f
                                },
                            ),
                        ),
                )
            }
        }
    }
}

@Composable
private fun WakeChallengeNote(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WakeChallengeStep(
    payload: WakeTriggerPayload,
    step: WakeUpCheckStep,
    stepIndex: Int,
    onSolved: () -> Unit,
) {
    when (step.type) {
        WakeUpCheckType.MATH -> {
            val challenge = rememberWakeMathChallenge(payload, stepIndex, step.difficulty)
            MathStepContent(
                stepKey = stepIndex,
                challenge = challenge,
                onSolved = onSolved,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            )
        }
        WakeUpCheckType.WHACK_A_MOLE -> WhackAMoleGame(
            difficulty = step.difficulty,
            onCompleted = onSolved,
            modifier = Modifier.fillMaxSize(),
            fitAvailableHeight = true,
        )
        WakeUpCheckType.GYROSCOPE_MAZE -> {
            val sensorState = rememberGyroscopeMazeSensorState(probeKey = "${payload.eventId}:$stepIndex")
            when (sensorState) {
                GyroscopeMazeSensorState.READY -> GyroscopeMazeGame(
                    difficulty = step.difficulty,
                    onCompleted = onSolved,
                    modifier = Modifier.fillMaxSize(),
                    fitAvailableHeight = true,
                )
                GyroscopeMazeSensorState.UNAVAILABLE -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.wake_alarm_gyroscope_maze_unavailable_fallback),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    MathStepContent(
                        stepKey = stepIndex,
                        challenge = rememberWakeMathChallenge(payload, stepIndex, step.difficulty),
                        onSolved = onSolved,
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                GyroscopeMazeSensorState.CHECKING ->
                    WakeChallengeNote(stringResource(R.string.wake_alarm_gyroscope_maze_checking))
            }
        }
    }
}

@Composable
private fun rememberWakeMathChallenge(
    payload: WakeTriggerPayload,
    stepIndex: Int,
    difficulty: MathDifficulty,
): WakeUpCheckChallenge {
    val eventId = payload.eventId
    val challengeSeed = payload.wakeUpCheckSeed
    val scheduledChallenge = payload.wakeUpCheckChallenge
    return remember(eventId, challengeSeed, scheduledChallenge, stepIndex, difficulty) {
        if (
            challengeSeed == null &&
            stepIndex == 0 &&
            scheduledChallenge != null &&
            payload.wakeUpCheckSteps.isEmpty()
        ) {
            scheduledChallenge
        } else {
            wakeUpCheckChallengeForStep(
                eventId = eventId,
                triggerAtMillis = challengeSeed,
                stepIndex = stepIndex,
                difficulty = difficulty,
            )
        }
    }
}

/**
 * The question and a field that takes the keyboard straight away. The step is solved as soon as the
 * typed number is the answer; the field is the only thing to touch.
 */
@Composable
internal fun MathStepContent(
    stepKey: Int,
    challenge: WakeUpCheckChallenge,
    onSolved: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    var answer by rememberSaveable(stepKey) { mutableStateOf("") }
    val solved = challenge.matches(answer)
    val onGradient = MaterialTheme.colorScheme.onPrimary
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(solved) {
        if (solved) {
            delay(600)
            onSolved()
        }
    }

    LaunchedEffect(stepKey, autoFocus) {
        if (autoFocus) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    // Scrolling is the caller's call: a screen that may be short makes it scroll, a dialog that
    // already scrolls must not nest a second one (an infinite height would crash it).
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.wake_alarm_solve_wake_up_check_prompt),
            style = MaterialTheme.typography.bodyLarge,
            color = onGradient,
        )
        Text(
            text = challenge.prompt,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = onGradient,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = answer,
            onValueChange = { input -> answer = answerDigits(input) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            label = { Text(stringResource(R.string.wake_alarm_answer)) },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                textAlign = TextAlign.Right,
                textDirection = TextDirection.Ltr,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = onGradient,
                unfocusedTextColor = onGradient,
                focusedBorderColor = onGradient,
                unfocusedBorderColor = onGradient.copy(alpha = 0.7f),
                focusedLabelColor = onGradient,
                unfocusedLabelColor = onGradient.copy(alpha = 0.8f),
                cursorColor = onGradient,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

/** Western digits only: Arabic-Indic digits typed on an Arabic keyboard are converted, not refused. */
private fun answerDigits(input: String): String = buildString(input.length) {
    for (c in input) {
        when (c) {
            in '0'..'9' -> append(c)
            in '٠'..'٩' -> append('0' + (c - '٠'))
            in '۰'..'۹' -> append('0' + (c - '۰'))
        }
    }
}
