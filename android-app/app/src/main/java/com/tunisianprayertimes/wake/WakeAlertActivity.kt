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
import com.tunisianprayertimes.MainActivity
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
    private val pendingPayloads = ArrayDeque<WakeTriggerPayload>()

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val eventId = intent.wakeEventId()
            if (eventId == null || eventId == payload?.eventId) {
                if (!advanceToNextPayload()) {
                    finish()
                }
            }
        }
    }

    private fun advanceToNextPayload(): Boolean {
        val next = pendingPayloads.removeFirstOrNull() ?: return false
        payload = next
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
        configureWindowForAlarm()

        // Restore payloads that survived activity destruction (e.g. HOME press
        // while a challenge is active, then system kills the activity).
        if (savedInstanceState != null) {
            savedInstanceState.getBundle(KEY_CURRENT_PAYLOAD)
                ?.toWakeTriggerPayload()
                ?.let { payload = it }
            val count = savedInstanceState.getInt(KEY_PENDING_COUNT, 0)
            for (i in 0 until count) {
                savedInstanceState.getBundle("$KEY_PENDING_PREFIX$i")
                    ?.toWakeTriggerPayload()
                    ?.let { pendingPayloads.addLast(it) }
            }
        }

        // Handle the incoming intent payload (initial launch or re-creation
        // after the system delivered a new alarm while the activity was dead).
        val intentPayload = intent?.toWakeTriggerPayload()
        if (intentPayload != null) {
            val current = payload
            if (current == null || !current.wakeUpCheckEnabled) {
                payload = intentPayload
            } else if (intentPayload.eventId != current.eventId) {
                pendingPayloads.addLast(intentPayload)
            }
        }

        setContent {
            TunisianPrayerTimesTheme {
                WakeAlertScreen(
                    payload = payload,
                    onStop = {
                        payload?.let { p -> scheduleAwakeCheckIfEnabled(p) }
                        if (!advanceToNextPayload()) {
                            stopService(Intent(this, WakePlaybackService::class.java))
                            finish()
                        }
                    },
                    onOpenApp = {
                        startActivity(
                            Intent(this, MainActivity::class.java).addFlags(
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                            ),
                        )
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = intent.toWakeTriggerPayload() ?: return
        val current = payload
        if (current == null || !current.wakeUpCheckEnabled) {
            // No active challenge — show the new alarm immediately
            payload = incoming
        } else {
            // User is busy solving a challenge — queue the incoming alarm
            pendingPayloads.addLast(incoming)
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            dismissReceiver,
            IntentFilter(ACTION_DISMISS_WAKE_ALERT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(dismissReceiver) }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        payload?.let { outState.putBundle(KEY_CURRENT_PAYLOAD, it.toBundle()) }
        outState.putInt(KEY_PENDING_COUNT, pendingPayloads.size)
        pendingPayloads.forEachIndexed { i, p ->
            outState.putBundle("$KEY_PENDING_PREFIX$i", p.toBundle())
        }
    }

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

    private companion object {
        const val KEY_CURRENT_PAYLOAD = "current_payload"
        const val KEY_PENDING_COUNT = "pending_count"
        const val KEY_PENDING_PREFIX = "pending_"
    }
}

@Composable
private fun WakeAlertScreen(
    payload: WakeTriggerPayload?,
    onStop: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val context = LocalContext.current
    val checkEnabled = payload?.wakeUpCheckEnabled == true
    val eventId = payload?.eventId ?: ""

    val steps: List<WakeUpCheckStep> = if (!checkEnabled) {
        emptyList()
    } else if (payload != null && payload.wakeUpCheckSteps.isNotEmpty()) {
        payload.wakeUpCheckSteps
    } else if (payload != null) {
        listOf(WakeUpCheckStep(payload.wakeUpCheckType, payload.wakeUpCheckDifficulty))
    } else {
        emptyList()
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
                        onClick = onStop,
                        enabled = allDone,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.wake_alarm_stop))
                    }
                    OutlinedButton(
                        onClick = onOpenApp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.wake_alarm_open_app))
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