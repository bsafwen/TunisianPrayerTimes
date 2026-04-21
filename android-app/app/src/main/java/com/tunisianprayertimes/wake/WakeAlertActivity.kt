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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import java.util.Locale

class WakeAlertActivity : AppCompatActivity() {
    private var payload by mutableStateOf<WakeTriggerPayload?>(null)

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val eventId = intent.wakeEventId()
            if (eventId == null || eventId == payload?.eventId) {
                finish()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val locale = Locale("ar", "TN")
        Locale.setDefault(locale)
        val config = newBase.resources.configuration.apply { setLocale(locale) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindowForAlarm()
        payload = intent?.toWakeTriggerPayload()

        setContent {
            TunisianPrayerTimesTheme {
                WakeAlertScreen(
                    payload = payload,
                    onStop = {
                        stopService(Intent(this, WakePlaybackService::class.java))
                        finish()
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
        payload = intent.toWakeTriggerPayload()
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
    onStop: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val context = LocalContext.current
    val wakeUpCheck = payload?.takeIf { it.wakeUpCheckEnabled }?.wakeUpCheckChallenge
    var wakeUpAnswer by rememberSaveable(payload?.eventId, wakeUpCheck?.prompt) { mutableStateOf("") }
    val wakeUpCheckPassed = wakeUpCheck?.matches(wakeUpAnswer) ?: true
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
                    Text(
                        text = when {
                            payload == null -> stringResource(R.string.wake_alarm_now_ringing)
                            wakeUpCheck != null -> stringResource(R.string.wake_alarm_wake_up_check_required)
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

                    if (wakeUpCheck != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = stringResource(
                                    R.string.wake_alarm_solve_wake_up_check_prompt,
                                    wakeUpCheck.prompt,
                                ),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    textDirection = TextDirection.ContentOrLtr,
                                ),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            OutlinedTextField(
                                value = wakeUpAnswer,
                                onValueChange = { answer ->
                                    wakeUpAnswer = answer.filterIndexed { index, character ->
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
                                text = if (wakeUpCheckPassed) {
                                    stringResource(R.string.wake_alarm_wake_up_check_complete)
                                } else {
                                    stringResource(R.string.wake_alarm_wake_up_check_incomplete)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
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
                        enabled = wakeUpCheckPassed,
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