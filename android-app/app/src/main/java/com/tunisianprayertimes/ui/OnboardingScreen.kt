package com.tunisianprayertimes.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tunisianprayertimes.DelayMode
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.R
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.SilenceMode
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    val totalSteps = 8
    val permissionsStep = 6

    // Track navigation direction for animation
    var goingForward by remember { mutableStateOf(true) }

    // Permission refresh on resume
    var refreshTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationManager = remember { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    val phoneStatePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AnalyticsTracker.permissionStepResult(
            context = context,
            permissionType = "phone_state",
            result = if (granted) "granted" else "denied",
            entryPoint = "onboarding",
        )
        refreshTick++
    }

    val hasDnd = remember(refreshTick) { notificationManager.isNotificationPolicyAccessGranted }
    val hasAlarm = remember(refreshTick) { hasExactAlarmPerm(context) }
    val hasBattery = remember(refreshTick) { isBatteryOptimized(context) }
    val hasPhoneState = remember(refreshTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }
    val allPermsGranted = hasDnd && hasAlarm && hasBattery && hasPhoneState

    // Auto-advance from permissions step once all granted
    LaunchedEffect(allPermsGranted, currentStep) {
        if (allPermsGranted && currentStep == permissionsStep) {
            delay(600)
            goingForward = true
            currentStep = permissionsStep + 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgCream)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Progress bar at top
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (i in 0 until totalSteps) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i <= currentStep) Gold else GoldLight)
                )
            }
        }

        // Step content with animated transitions, scrolling above the button band.
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    val slideOffset = { size: Int -> size / 4 }
                    val fadeEnter = tween<Float>(300, easing = FastOutSlowInEasing)
                    val fadeExit = tween<Float>(250, easing = FastOutSlowInEasing)
                    val slideEnter = tween<IntOffset>(300, easing = FastOutSlowInEasing)
                    val slideExit = tween<IntOffset>(250, easing = FastOutSlowInEasing)
                    if (goingForward) {
                        (slideInHorizontally(slideEnter) { -slideOffset(it) } + fadeIn(fadeEnter)) togetherWith
                                (slideOutHorizontally(slideExit) { slideOffset(it) } + fadeOut(fadeExit))
                    } else {
                        (slideInHorizontally(slideEnter) { slideOffset(it) } + fadeIn(fadeEnter)) togetherWith
                                (slideOutHorizontally(slideExit) { -slideOffset(it) } + fadeOut(fadeExit))
                    }
                },
                modifier = Modifier.fillMaxSize().padding(top = 14.dp),
                label = "onboarding_step"
            ) { step ->
                when (step) {
                    0 -> WelcomeStep()
                    1 -> SilencePeriodStep()
                    2 -> DragEndpointsStep()
                    3 -> EndpointRulesStep()
                    4 -> JomoaaExplanationStep()
                    5 -> WakeAlarmIntroStep()
                    6 -> PermissionsStep(
                        hasDnd = hasDnd,
                        hasAlarm = hasAlarm,
                        hasBattery = hasBattery,
                        hasPhoneState = hasPhoneState,
                        onRequestPhoneState = {
                            AnalyticsTracker.permissionStepResult(
                                context = context,
                                permissionType = "phone_state",
                                result = "request_opened",
                                entryPoint = "onboarding",
                            )
                            phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                        },
                        context = context
                    )
                    7 -> ReadyStep()
                }
            }
        }

        // Navigation buttons occupy their own band, so they can never overlap
        // the scrollable step content.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentStep > 0) {
                OutlinedButton(
                    onClick = {
                        goingForward = false
                        currentStep--
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(TestTags.ONBOARDING_PREV),
                    shape = RoundedCornerShape(28.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GreenPrimary)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_prev),
                        fontSize = 16.sp,
                        color = GreenPrimary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Spacer(Modifier.width(12.dp))
            }

            if (currentStep == totalSteps - 1) {
                Button(
                    onClick = onFinish,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(TestTags.ONBOARDING_START),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_start),
                        fontSize = 18.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            } else {
                Button(
                    onClick = {
                        goingForward = true
                        currentStep++
                    },
                    enabled = currentStep != permissionsStep || allPermsGranted,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(TestTags.ONBOARDING_NEXT).alpha(if (currentStep == permissionsStep && !allPermsGranted) 0.4f else 1f),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_next),
                        fontSize = 18.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

// --- Steps ---

private val OnboardingTitleSlotHeight = 48.dp
private val OnboardingNarrationSlotHeight = 36.dp
private val OnboardingStageHeight = 376.dp

/**
 * Shared page geometry for every onboarding step: the title, the optional
 * narration chip, the animation/content stage and the description always sit in
 * the same slots, so nothing shifts between steps or between a step's animations.
 */
@Composable
private fun OnboardingStepLayout(
    title: @Composable () -> Unit,
    description: @Composable () -> Unit,
    narration: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp)
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(OnboardingTitleSlotHeight),
            contentAlignment = Alignment.Center,
        ) {
            title()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(OnboardingNarrationSlotHeight),
            contentAlignment = Alignment.Center,
        ) {
            narration?.invoke()
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(OnboardingStageHeight),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            description()
        }
    }
}

@Composable
private fun WelcomeStep() {
    val flourish = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        flourish.animateTo(1f, tween(1000, easing = FastOutSlowInEasing))
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 32.dp)
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Gilded basmalah: same artwork as the app header, painted with a
        // metallic gold gradient so it reads like embossed gold leaf.
        Image(
            painter = painterResource(R.drawable.basmalah),
            contentDescription = "بِسْمِ اللهِ الرَّحْمَٰنِ الرَّحِيمِ",
            modifier = Modifier
                .fillMaxWidth(0.98f)
                .height(74.dp)
                .graphicsLayer {
                    alpha = flourish.value
                    val scale = 0.94f + flourish.value * 0.06f
                    scaleX = scale
                    scaleY = scale
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFF8E7A0),
                                Color(0xFFE3BE55),
                                Color(0xFFC79B2F),
                                Color(0xFF8F6A1C),
                            ),
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                },
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(30.dp))
        Image(
            painter = painterResource(R.drawable.mosque_silhouette),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(120.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = GreenPrimaryDark
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome),
            fontSize = 17.sp,
            color = TextDark,
            textAlign = TextAlign.Center,
            lineHeight = 26.sp
        )
    }
}

@Composable
private fun SilencePeriodStep() {
    OnboardingStepLayout(
        title = {
            Text(
                text = stringResource(R.string.onboarding_period_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = GreenPrimaryDark,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )
        },
        description = {
            Text(
                text = stringResource(R.string.onboarding_period_desc),
                fontSize = 14.sp,
                color = TextDark,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        },
    ) {
        DemoPrayerSilenceRow(
            prayer = Prayer.DHUHR,
            prayerName = stringResource(R.string.prayer_dhuhr),
            prayerHour = 12,
            prayerMinute = 21,
            initialStartOffset = 0,
            initialEndOffset = 47,
        )
    }
}

@Composable
private fun DragEndpointsStep() {
    OnboardingStepLayout(
        title = {
            Text(
                text = stringResource(R.string.onboarding_drag_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = GreenPrimaryDark,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )
        },
        description = {
            Text(
                text = stringResource(R.string.onboarding_drag_desc),
                fontSize = 14.sp,
                color = TextDark,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        },
    ) {
        DemoPrayerSilenceRow(
            prayer = Prayer.DHUHR,
            prayerName = stringResource(R.string.prayer_dhuhr),
            prayerHour = 12,
            prayerMinute = 21,
            initialStartOffset = 0,
            initialEndOffset = 47,
            autoDemo = true,
        )
    }
}

private enum class EndpointRulePhase {
    TAP_START, CHOOSE_START, APPLY_START,
    TAP_END, CHOOSE_END, APPLY_END,
    IMPACT,
}

@Composable
private fun EndpointRulesStep() {
    var editorEndpoint by remember { mutableStateOf(SilenceEndpoint.START) }
    var editorFixed by remember { mutableStateOf(false) }
    var rowStartFixed by remember { mutableStateOf(false) }
    var rowEndFixed by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(EndpointRulePhase.TAP_START) }
    val startGlow = remember { Animatable(0f) }
    val endGlow = remember { Animatable(0f) }
    val applyPress = remember { Animatable(0f) }
    val sheetProgress = remember { Animatable(0f) }

    // Dhuhr at 12:21. Initially the silence follows the adhan (starts at 12:21,
    // ends 47 minutes later at 13:08); the demo pins it to 12:45 - 13:15.
    val config = remember(rowStartFixed, rowEndFixed) {
        PrayerSilenceConfig(
            mode = if (rowEndFixed) SilenceMode.FIXED_TIME else SilenceMode.DURATION,
            afterMinutes = if (rowEndFixed) 30 else 47,
            fixedHour = if (rowEndFixed) 13 else -1,
            fixedMinute = if (rowEndFixed) 15 else -1,
            delayMode = if (rowStartFixed) DelayMode.FIXED_TIME else DelayMode.MINUTES,
            delayMinutes = 0,
            delayFixedHour = if (rowStartFixed) 12 else -1,
            delayFixedMinute = if (rowStartFixed) 45 else -1,
            endOffsetMinutes = if (rowEndFixed) null else 47,
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            editorEndpoint = SilenceEndpoint.START
            editorFixed = false
            rowStartFixed = false
            rowEndFixed = false
            phase = EndpointRulePhase.TAP_START
            startGlow.snapTo(0f)
            endGlow.snapTo(0f)
            applyPress.snapTo(0f)
            sheetProgress.snapTo(0f)
            delay(1000)
            // 1. Tap the start endpoint (currently at adhan).
            startGlow.animateTo(0.4f, tween(450))
            startGlow.animateTo(0f, tween(450))
            startGlow.animateTo(0.85f, tween(110))
            delay(140)
            startGlow.animateTo(0.3f, tween(200))
            editorEndpoint = SilenceEndpoint.START
            sheetProgress.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
            phase = EndpointRulePhase.CHOOSE_START
            delay(1000)
            editorFixed = true
            delay(1300)
            phase = EndpointRulePhase.APPLY_START
            delay(700)
            // 2. Apply; the start is pinned to 12:45.
            applyPress.animateTo(1f, tween(110))
            delay(130)
            applyPress.animateTo(0f, tween(180))
            sheetProgress.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
            startGlow.snapTo(0f)
            delay(260)
            rowStartFixed = true
            phase = EndpointRulePhase.TAP_END
            startGlow.animateTo(0.6f, tween(300))
            startGlow.animateTo(0f, tween(1000))
            delay(1200)
            // 3. Tap the end endpoint (currently 47 minutes after adhan).
            endGlow.animateTo(0.4f, tween(450))
            endGlow.animateTo(0f, tween(450))
            endGlow.animateTo(0.85f, tween(110))
            delay(140)
            endGlow.animateTo(0.3f, tween(200))
            editorEndpoint = SilenceEndpoint.END
            editorFixed = false
            sheetProgress.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
            phase = EndpointRulePhase.CHOOSE_END
            delay(1000)
            editorFixed = true
            delay(1300)
            phase = EndpointRulePhase.APPLY_END
            delay(700)
            // 4. Apply; the end is pinned to 13:15.
            applyPress.animateTo(1f, tween(110))
            delay(130)
            applyPress.animateTo(0f, tween(180))
            sheetProgress.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
            endGlow.snapTo(0f)
            delay(260)
            rowEndFixed = true
            phase = EndpointRulePhase.IMPACT
            endGlow.animateTo(0.6f, tween(300))
            endGlow.animateTo(0f, tween(1100))
            delay(2300)
        }
    }

    val editorIsStart = editorEndpoint == SilenceEndpoint.START

    OnboardingStepLayout(
        title = {
            Text(
                text = stringResource(R.string.onboarding_rule_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = GreenPrimaryDark,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )
        },
        narration = {
            AnimatedContent(
                targetState = phase,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label = "onboarding_rule_phase",
            ) { current ->
                Text(
                    text = stringResource(
                        when (current) {
                            EndpointRulePhase.TAP_START -> R.string.onboarding_rule_step_tap
                            EndpointRulePhase.CHOOSE_START -> R.string.onboarding_rule_step_choose
                            EndpointRulePhase.APPLY_START -> R.string.onboarding_rule_step_apply
                            EndpointRulePhase.TAP_END -> R.string.onboarding_rule_step_tap_end
                            EndpointRulePhase.CHOOSE_END -> R.string.onboarding_rule_step_choose_end
                            EndpointRulePhase.APPLY_END -> R.string.onboarding_rule_step_apply_end
                            EndpointRulePhase.IMPACT -> R.string.onboarding_rule_step_impact
                        }
                    ),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GreenPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(GoldLight.copy(alpha = 0.35f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        },
        description = {
            Text(
                text = stringResource(R.string.onboarding_rule_desc),
                fontSize = 14.sp,
                color = TextDark,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp)),
        ) {
            DemoPrayerSilenceRow(
                prayer = Prayer.DHUHR,
                prayerName = stringResource(R.string.prayer_dhuhr),
                prayerHour = 12,
                prayerMinute = 21,
                initialStartOffset = 0,
                initialEndOffset = 47,
                controlledConfig = config,
                interactive = false,
                startHighlight = startGlow.value,
                endHighlight = endGlow.value,
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { alpha = 1f - sheetProgress.value },
            )
            DemoEndpointRuleCard(
                endpoint = editorEndpoint,
                fixedMode = editorFixed,
                fixedClock = if (editorIsStart) "12:45" else "13:15",
                fixedHour = if (editorIsStart) "12" else "13",
                fixedMinute = if (editorIsStart) "45" else "15",
                relativeDirection = if (editorIsStart) EndpointDirection.AT_ADHAN else EndpointDirection.AFTER,
                relativeMinutes = if (editorIsStart) null else "47",
                resolvedTime = if (editorIsStart) "12:21" else "13:08",
                sheet = true,
                applyPressed = applyPress.value,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { translationY = (1f - sheetProgress.value) * size.height },
            )
        }
    }
}

@Composable
private fun JomoaaExplanationStep() {
    OnboardingStepLayout(
        title = {
            Text(
                text = stringResource(R.string.onboarding_jomoaa_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = GreenPrimaryDark,
                textAlign = TextAlign.Center
            )
        },
        description = {
            Text(
                text = stringResource(R.string.onboarding_jomoaa_desc),
                fontSize = 14.sp,
                color = TextDark,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        },
    ) {
        DemoPrayerSilenceRow(
            prayer = Prayer.JOMOAA,
            prayerName = stringResource(R.string.prayer_jomoaa),
            prayerHour = 12,
            prayerMinute = 21,
            initialStartOffset = 0,
            initialEndOffset = 60,
            pulseTime = true,
        )
    }
}

@Composable
private fun WakeAlarmIntroStep() {
    OnboardingStepLayout(
        title = {
            Text(
                text = stringResource(R.string.onboarding_wake_alarm_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = GreenPrimaryDark,
                textAlign = TextAlign.Center
            )
        },
        description = {
            Text(
                text = stringResource(R.string.onboarding_wake_alarm_desc),
                fontSize = 14.sp,
                color = TextDark,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
        },
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_tab_alarms),
            contentDescription = null,
            tint = GreenPrimary,
            modifier = Modifier.size(72.dp),
        )
    }
}

@Composable
private fun PermissionsStep(
    hasDnd: Boolean,
    hasAlarm: Boolean,
    hasBattery: Boolean,
    hasPhoneState: Boolean,
    onRequestPhoneState: () -> Unit,
    context: Context
) {
    OnboardingStepLayout(
        title = {
            Text(
                text = stringResource(R.string.onboarding_perm_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = GreenPrimaryDark
            )
        },
        description = {},
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_perm_desc),
                fontSize = 12.sp,
                color = TextDark,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            PermissionRow(
                title = stringResource(R.string.onboarding_perm_dnd),
                description = stringResource(R.string.onboarding_perm_dnd_desc),
                isGranted = hasDnd,
                grantLabel = stringResource(R.string.onboarding_perm_grant),
                grantedLabel = stringResource(R.string.onboarding_perm_granted),
                onClick = {
                    AnalyticsTracker.permissionStepResult(
                        context = context,
                        permissionType = "dnd",
                        result = "request_opened",
                        entryPoint = "onboarding",
                    )
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            )

            PermissionRow(
                title = stringResource(R.string.onboarding_perm_alarm),
                description = stringResource(R.string.onboarding_perm_alarm_desc),
                isGranted = hasAlarm,
                grantLabel = stringResource(R.string.onboarding_perm_grant),
                grantedLabel = stringResource(R.string.onboarding_perm_granted),
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        AnalyticsTracker.permissionStepResult(
                            context = context,
                            permissionType = "exact_alarm",
                            result = "request_opened",
                            entryPoint = "onboarding",
                        )
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    }
                }
            )

            PermissionRow(
                title = stringResource(R.string.onboarding_perm_battery),
                description = stringResource(R.string.onboarding_perm_battery_desc),
                isGranted = hasBattery,
                grantLabel = stringResource(R.string.onboarding_perm_grant),
                grantedLabel = stringResource(R.string.onboarding_perm_granted),
                onClick = {
                    AnalyticsTracker.permissionStepResult(
                        context = context,
                        permissionType = "battery_optimization",
                        result = "request_opened",
                        entryPoint = "onboarding",
                    )
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:${context.packageName}")
                    context.startActivity(intent)
                }
            )

            PermissionRow(
                title = stringResource(R.string.onboarding_perm_phone_state),
                description = stringResource(R.string.onboarding_perm_phone_state_desc),
                isGranted = hasPhoneState,
                grantLabel = stringResource(R.string.onboarding_perm_grant),
                grantedLabel = stringResource(R.string.onboarding_perm_granted),
                onClick = onRequestPhoneState
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    grantLabel: String,
    grantedLabel: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, GoldLight)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = GreenPrimaryDark,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onClick,
                    enabled = !isGranted,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isGranted) TextMuted else GreenPrimary,
                        disabledContainerColor = TextMuted
                    ),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                    modifier = Modifier.heightIn(min = 32.dp)
                ) {
                    Text(
                        text = if (isGranted) grantedLabel else grantLabel,
                        fontSize = 10.sp,
                        color = Color.White
                    )
                }
            }
            Text(
                text = description,
                fontSize = 10.sp,
                color = TextMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun ReadyStep() {
    OnboardingStepLayout(
        title = {
            Text(
                text = stringResource(R.string.onboarding_ready_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = GreenPrimaryDark,
                textAlign = TextAlign.Center
            )
        },
        description = {
            Text(
                text = stringResource(R.string.onboarding_ready_desc),
                fontSize = 15.sp,
                color = TextDark,
                textAlign = TextAlign.Center,
                lineHeight = 23.sp
            )
        },
    ) {
        // Visible "all set" badge; the cream mosque artwork would disappear here.
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(GoldLight.copy(alpha = 0.55f))
                .border(
                    width = 1.dp,
                    color = Gold.copy(alpha = 0.45f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = GreenPrimary,
                modifier = Modifier.size(52.dp),
            )
        }
    }
}

// --- Demo prayer silence row ---

@Composable
private fun DemoPrayerSilenceRow(
    prayer: Prayer,
    prayerName: String,
    prayerHour: Int,
    prayerMinute: Int,
    initialStartOffset: Int,
    initialEndOffset: Int,
    modifier: Modifier = Modifier,
    autoDemo: Boolean = false,
    pulseTime: Boolean = false,
    controlledConfig: PrayerSilenceConfig? = null,
    interactive: Boolean = true,
    startHighlight: Float = 0f,
    endHighlight: Float = 0f,
) {
    val prayerTime = remember(prayerHour, prayerMinute) { PrayerTime(prayer, prayerHour, prayerMinute) }
    val prayerMinutes = prayerMinutesOfDay(prayerTime)
    var enabled by remember { mutableStateOf(true) }
    var startOffset by remember { mutableIntStateOf(initialStartOffset) }
    var endOffset by remember { mutableIntStateOf(initialEndOffset) }
    var autoRunning by remember(autoDemo) { mutableStateOf(autoDemo) }
    val scale = remember {
        PrayerTimelineScale(DEFAULT_TIMELINE_START_OFFSET_MINUTES, DEFAULT_TIMELINE_END_OFFSET_MINUTES)
    }
    val baseConfig = remember(startOffset, endOffset) {
        PrayerSilenceConfig(
            mode = SilenceMode.DURATION,
            afterMinutes = (endOffset - startOffset).coerceAtLeast(1),
            delayMode = DelayMode.MINUTES,
            delayMinutes = startOffset,
            endOffsetMinutes = endOffset,
        )
    }
    val config = controlledConfig ?: baseConfig
    val targetWindow = remember(prayerTime, config) { resolvePrayerTimelineWindow(prayerTime, config) }
    val animatedStartMinutes by animateIntAsState(
        targetValue = targetWindow.startMinutes,
        animationSpec = tween(520, easing = FastOutSlowInEasing),
    )
    val animatedEndMinutes by animateIntAsState(
        targetValue = targetWindow.endMinutes,
        animationSpec = tween(520, easing = FastOutSlowInEasing),
    )
    val window = if (controlledConfig != null) {
        PrayerTimelineWindow(animatedStartMinutes, animatedEndMinutes)
    } else {
        targetWindow
    }
    val timePulse = remember { Animatable(0f) }

    LaunchedEffect(autoDemo, autoRunning) {
        if (!autoDemo || !autoRunning) return@LaunchedEffect
        val startAnimation = Animatable(initialStartOffset.toFloat())
        while (autoRunning) {
            delay(1200)
            startAnimation.animateTo(
                (initialStartOffset + 10).toFloat(),
                tween(650, easing = FastOutSlowInEasing),
            ) { startOffset = value.roundToInt() }
            delay(500)
            startAnimation.animateTo(
                initialStartOffset.toFloat(),
                tween(650, easing = FastOutSlowInEasing),
            ) { startOffset = value.roundToInt() }
        }
    }

    LaunchedEffect(pulseTime) {
        if (!pulseTime) return@LaunchedEffect
        while (true) {
            timePulse.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
            timePulse.animateTo(0f, tween(700, easing = FastOutSlowInEasing))
            delay(400)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PrayerSilencePalette.Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PrayerSilencePalette.SoftBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = prayerName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrayerSilencePalette.PrimaryText,
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(GoldLight.copy(alpha = timePulse.value * 0.5f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = prayerClockText(prayerMinutes),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PrayerSilencePalette.GoldAccent,
                        style = TextStyle(textDirection = TextDirection.Ltr),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.prayer_silence_switch),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = PrayerSilencePalette.PrimaryText,
                )
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    thumbContent = if (enabled) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = PrayerSilencePalette.InteractiveTeal,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PrayerSilencePalette.InteractiveTeal,
                        checkedBorderColor = PrayerSilencePalette.InteractiveTeal,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = PrayerSilencePalette.InactiveTrack,
                        uncheckedBorderColor = PrayerSilencePalette.InactiveTrack,
                    ),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (enabled) 1f else PrayerSilencePalette.DeemphasisAlpha),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DemoEndpointSummary(
                    prayerTime = prayerTime,
                    config = config,
                    window = window,
                    startHighlight = startHighlight,
                    endHighlight = endHighlight,
                )
                PrayerSilenceRangeSlider(
                    prayer = prayer,
                    prayerName = prayerName,
                    prayerTime = prayerTime,
                    config = config,
                    scale = scale,
                    window = window,
                    enabled = enabled && interactive,
                    onPreviewWindow = { autoRunning = false },
                    onCommitWindow = { candidate ->
                        autoRunning = false
                        val candidateStart = candidate.startMinutes - prayerMinutes
                        val candidateEnd = candidate.endMinutes - prayerMinutes
                        if (candidateEnd - candidateStart >= 1) {
                            startOffset = candidateStart
                            endOffset = candidateEnd
                        }
                    },
                    onEditEndpoint = {},
                )
            }
        }
    }
}

@Composable
private fun DemoEndpointSummary(
    prayerTime: PrayerTime,
    config: PrayerSilenceConfig,
    window: PrayerTimelineWindow,
    startHighlight: Float = 0f,
    endHighlight: Float = 0f,
) {
    val prayerMinutes = prayerMinutesOfDay(prayerTime)
    val startCaption = stringResource(
        R.string.prayer_silence_endpoint_caption,
        stringResource(R.string.prayer_silence_start_label),
        endpointRuleCaption(config, window, SilenceEndpoint.START, prayerMinutes),
    )
    val endCaption = stringResource(
        R.string.prayer_silence_endpoint_caption,
        stringResource(R.string.prayer_silence_end_label),
        endpointRuleCaption(config, window, SilenceEndpoint.END, prayerMinutes),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DemoEndpointColumn(
            time = silenceClockText(window.startMinutes),
            caption = startCaption,
            modifier = Modifier
                .weight(1f)
                .scale(1f - startHighlight * 0.04f)
                .clip(RoundedCornerShape(10.dp))
                .background(GoldLight.copy(alpha = startHighlight * 0.55f))
                .padding(vertical = 2.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.prayer_silence_duration_minutes,
                    window.durationMinutes,
                    window.durationMinutes,
                ),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = PrayerSilencePalette.InteractiveTeal,
                maxLines = 1,
                softWrap = false,
            )
            Icon(
                painter = painterResource(R.drawable.ic_bell_off),
                contentDescription = null,
                tint = PrayerSilencePalette.SecondaryText,
                modifier = Modifier.size(15.dp),
            )
        }
        DemoEndpointColumn(
            time = silenceClockText(window.endMinutes),
            caption = endCaption,
            modifier = Modifier
                .weight(1f)
                .scale(1f - endHighlight * 0.04f)
                .clip(RoundedCornerShape(10.dp))
                .background(GoldLight.copy(alpha = endHighlight * 0.55f))
                .padding(vertical = 2.dp),
        )
    }
}

@Composable
private fun DemoEndpointColumn(
    time: String,
    caption: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = time,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            color = PrayerSilencePalette.PrimaryText,
            style = TextStyle(textDirection = TextDirection.Ltr),
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = caption,
            fontSize = 11.sp,
            color = PrayerSilencePalette.SecondaryText,
            textAlign = TextAlign.Center,
            lineHeight = 15.sp,
            maxLines = 2,
        )
    }
}

@Composable
private fun DemoEndpointRuleCard(
    endpoint: SilenceEndpoint,
    fixedMode: Boolean,
    fixedClock: String,
    fixedHour: String,
    fixedMinute: String,
    relativeDirection: EndpointDirection,
    relativeMinutes: String?,
    resolvedTime: String,
    sheet: Boolean = false,
    applyPressed: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val startEndpoint = endpoint == SilenceEndpoint.START
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (sheet) 0.dp else 8.dp),
        shape = if (sheet) {
            RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        } else {
            RoundedCornerShape(16.dp)
        },
        colors = CardDefaults.cardColors(containerColor = PrayerSilencePalette.Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (sheet) 6.dp else 2.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PrayerSilencePalette.SoftBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (sheet) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(PrayerSilencePalette.SoftBorder),
                    )
                }
            }
            Text(
                text = stringResource(
                    R.string.prayer_editor_endpoint_title,
                    stringResource(
                        if (startEndpoint) R.string.prayer_silence_start_group
                        else R.string.prayer_silence_end_group
                    ),
                    stringResource(R.string.prayer_dhuhr),
                ),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = GreenPrimaryDark,
            )
            EndpointModeChoices(
                choices = listOf(
                    stringResource(R.string.prayer_silence_mode_adhan),
                    stringResource(R.string.prayer_silence_mode_fixed),
                ),
                selected = if (fixedMode) 1 else 0,
                enabled = false,
                onSelected = {},
            )
            AnimatedContent(
                targetState = fixedMode,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
                label = "onboarding_rule_mode",
            ) { fixed ->
                if (fixed) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.prayer_silence_explain_fixed, fixedClock),
                            fontSize = 13.sp,
                            color = PrayerSilencePalette.SecondaryText,
                            lineHeight = 19.sp,
                        )
                        EndpointInlineClockInput(
                            hourValue = fixedHour,
                            minuteValue = fixedMinute,
                            enabled = false,
                            onHourValueChange = {},
                            onMinuteValueChange = {},
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = if (relativeDirection == EndpointDirection.AT_ADHAN) {
                                stringResource(R.string.prayer_silence_explain_follows_adhan)
                            } else {
                                endpointRelationText(relativeMinutes?.toIntOrNull() ?: 0)
                            },
                            fontSize = 13.sp,
                            color = PrayerSilencePalette.SecondaryText,
                            lineHeight = 19.sp,
                        )
                        EndpointDirectionChoices(
                            selected = relativeDirection,
                            enabled = false,
                            onSelected = {},
                        )
                        if (relativeDirection == EndpointDirection.AT_ADHAN) {
                            DemoResolvedTime(resolvedTime)
                        } else {
                            EndpointMinuteStepper(
                                value = relativeMinutes ?: "0",
                                enabled = false,
                                decreaseLabel = stringResource(R.string.prayer_silence_decrease_minute),
                                increaseLabel = stringResource(R.string.prayer_silence_increase_minute),
                                unitLabel = stringResource(R.string.prayer_editor_minutes_unit),
                                onDecrease = {},
                                onIncrease = {},
                                onValueChange = {},
                            )
                        }
                    }
                }
            }
            if (sheet) {
                HorizontalDivider(color = GreenPrimaryDark.copy(alpha = 0.10f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {},
                        modifier = Modifier
                            .weight(1.4f)
                            .heightIn(min = 48.dp)
                            .scale(1f - applyPressed * 0.05f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GreenPrimaryDark),
                    ) {
                        Text(
                            text = stringResource(R.string.prayer_editor_apply),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    TextButton(
                        onClick = {},
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.prayer_editor_cancel),
                            color = GreenPrimaryDark,
                        )
                    }
                }
            }
        }
    }
}

/** Compact resolved clock shown for the at-adhan rule, matching the real editor. */
@Composable
private fun DemoResolvedTime(resolvedTime: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = resolvedTime,
            color = PrayerSilencePalette.PrimaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(textDirection = TextDirection.Ltr),
            maxLines = 1,
            softWrap = false,
        )
    }
}

// --- Utilities ---

private fun hasExactAlarmPerm(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }
    return true
}

private fun isBatteryOptimized(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
