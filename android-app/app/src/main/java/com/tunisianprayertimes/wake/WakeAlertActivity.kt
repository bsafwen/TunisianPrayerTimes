package com.tunisianprayertimes.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tunisianprayertimes.AnalyticsTracker
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
    private val queue get() = WakeAlarmQueueHolder.queue

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val eventId = intent.wakeEventId()
            android.util.Log.d("WakeFlow", "Activity.dismissReceiver eventId=$eventId current=${queue.current?.eventId}")
            if (!queue.handleDismiss(eventId)) {
                signalServiceCurrentChanged()
                finish()
            } else {
                payload = queue.current
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
        payload = queue.current
        return advanced
    }

    override fun attachBaseContext(newBase: Context) {
        val locale = Locale.forLanguageTag("ar-TN-u-nu-latn")
        Locale.setDefault(locale)
        val config = newBase.resources.configuration.apply { setLocale(locale) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindowForAlarm()

        android.util.Log.d(
            "WakeFlow",
            "Activity.onCreate intentEventId=${intent?.wakeEventId()} queueCurrent=${queue.current?.eventId} pending=${queue.pendingEventIds()}",
        )

        // The queue is a process-wide singleton populated by WakePlaybackService
        // before it launches us. Just read the current payload — never replay
        // the intent extras (which would risk duplicate / stale delivery).
        payload = queue.current

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
                WakeAlertScreen(
                    payload = payload,
                    onStop = { wakeupCheckCompleted ->
                        android.util.Log.d(
                            "WakeFlow",
                            "Activity.onStopButton payloadEventId=${payload?.eventId} queueCurrent=${queue.current?.eventId}",
                        )
                        payload?.let { p ->
                            AnalyticsTracker.wakeAlarmDismissed(
                                context = this@WakeAlertActivity,
                                payload = p,
                                stopSource = "alert_activity",
                                wakeupCheckCompleted = wakeupCheckCompleted,
                            )
                            scheduleAwakeCheckIfEnabled(p)
                        }
                        val advanced = advanceToNextPayload()
                        // Ask the service to refresh notification + ringtone
                        // for whatever is now current, or stop itself if the
                        // queue is empty.
                        signalServiceCurrentChanged()
                        if (!advanced) {
                            finish()
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
        payload = queue.current
        if (payload == null) {
            android.util.Log.d(
                "WakeFlow",
                "Activity.onNewIntent finishing — queue is empty (stale launch)",
            )
            finish()
        }
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
        payload = queue.current
        if (payload == null) {
            // Nothing to show — either the alarm was dismissed externally or
            // the process was just rebuilt with no queue state. Bail out.
            finish()
            return
        }
        ContextCompat.registerReceiver(
            this,
            dismissReceiver,
            IntentFilter(ACTION_DISMISS_WAKE_ALERT),
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

    private fun scheduleAwakeCheckIfEnabled(payload: WakeTriggerPayload) {
        if (!payload.awakeCheckEnabled) return

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val triggerAtMillis = System.currentTimeMillis() + payload.awakeCheckDelayMinutes * 60_000L

        val intent = Intent(this, AwakeCheckReceiver::class.java)
            .setAction(AwakeCheckReceiver.ACTION_START_AWAKE_CHECK)
            .putExtra(EXTRA_EVENT_ID, payload.eventId)
            .putExtra(EXTRA_RINGTONE, payload.ringtone.name)
            .apply {
                payload.customRingtoneUri?.let { putExtra(EXTRA_CUSTOM_RINGTONE_URI, it) }
            }

        val pendingIntent = android.app.PendingIntent.getBroadcast(
            this,
            "awake_check_schedule".hashCode(),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        try {
            if (canUseExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            }
        } catch (e: SecurityException) {
            android.util.Log.w(
                "WakeAlertActivity",
                "Exact alarm denied; falling back to inexact awake check",
                e,
            )
            alarmManager.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        }
    }
}

@Composable
private fun WakeAlertScreen(
    payload: WakeTriggerPayload?,
    onStop: (Boolean) -> Unit,
) {
    val context = LocalContext.current
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
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = payload?.title(context)
                            ?: stringResource(R.string.wake_alarm_fallback_title),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = payload?.let { formatWakeTime(it.hour, it.minute) }
                            ?: stringResource(R.string.wake_alarm_time_placeholder),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )

                    if (steps.size > 1 && !allDone) {
                        Text(
                            text = stringResource(
                                R.string.wake_alarm_step_progress,
                                completedSteps + 1,
                                steps.size,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        LinearProgressIndicator(
                            progress = { completedSteps.toFloat() / steps.size },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Text(
                        text = when {
                            payload == null -> stringResource(R.string.wake_alarm_now_ringing)
                            allDone -> payload.statusText(context)
                            currentStep == null -> payload.statusText(context)
                            currentStep.type == WakeUpCheckType.MATH ->
                                stringResource(R.string.wake_alarm_wake_up_check_required)
                            currentStep.type == WakeUpCheckType.WHACK_A_MOLE ->
                                stringResource(R.string.wake_alarm_whack_a_mole_required)
                            currentStep.type == WakeUpCheckType.GYROSCOPE_MAZE ->
                                stringResource(R.string.wake_alarm_gyroscope_maze_prompt)
                            else -> payload.statusText(context)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(
                        text = payload?.contentText(context)
                            ?: stringResource(R.string.wake_alarm_now_ringing),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )

                    if (currentStep != null) {
                        when (currentStep.type) {
                            WakeUpCheckType.MATH -> {
                                val challenge = remember(completedSteps) {
                                    wakeUpCheckChallengeForStep(eventId, completedSteps, currentStep.difficulty)
                                }
                                MathStepContent(
                                    stepKey = completedSteps,
                                    challenge = challenge,
                                    onSolved = { completedSteps++ },
                                )
                            }
                            WakeUpCheckType.WHACK_A_MOLE -> {
                                val killTarget = remember(currentStep.difficulty) {
                                    when (currentStep.difficulty) {
                                        MathDifficulty.EASY -> 5
                                        MathDifficulty.INTERMEDIATE -> 10
                                        MathDifficulty.HARD -> 15
                                    }
                                }
                                WhackAMoleGame(
                                    killTarget = killTarget,
                                    difficulty = currentStep.difficulty,
                                    onCompleted = { completedSteps++ },
                                )
                            }
                            WakeUpCheckType.GYROSCOPE_MAZE -> {
                                GyroscopeMazeGame(
                                    difficulty = currentStep.difficulty,
                                    onCompleted = { completedSteps++ },
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(
                        onClick = { onStop(allDone) },
                        enabled = allDone,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.wake_alarm_stop))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun MathStepContent(
    stepKey: Int,
    challenge: WakeUpCheckChallenge,
    onSolved: () -> Unit,
) {
    var answer by rememberSaveable(stepKey) { mutableStateOf("") }
    val solved = challenge.matches(answer)

    LaunchedEffect(solved) {
        if (solved) {
            delay(600)
            onSolved()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.wake_alarm_solve_wake_up_check_prompt),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Text(
            text = challenge.prompt,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = answer,
            onValueChange = { input ->
                answer = input.filterIndexed { index, character ->
                    character.isDigit() || (character == '-' && index == 0)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.wake_alarm_answer)) },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                textAlign = TextAlign.Right,
                textDirection = TextDirection.Ltr,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Text(
            text = if (solved) {
                stringResource(R.string.wake_alarm_wake_up_check_complete)
            } else {
                stringResource(R.string.wake_alarm_wake_up_check_incomplete)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}