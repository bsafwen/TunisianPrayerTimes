package com.tunisianprayertimes.ui

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import kotlinx.coroutines.delay

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    val totalSteps = 6

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
    val hasDnd = remember(refreshTick) { notificationManager.isNotificationPolicyAccessGranted }
    val hasAlarm = remember(refreshTick) { hasExactAlarmPerm(context) }
    val hasBattery = remember(refreshTick) { isBatteryOptimized(context) }
    val allPermsGranted = hasDnd && hasAlarm && hasBattery

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgCream)
    ) {
        // Progress bar at top
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 36.dp)
                .align(Alignment.TopCenter),
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

        // Step content with animated transitions
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
            modifier = Modifier.fillMaxSize(),
            label = "onboarding_step"
        ) { step ->
            when (step) {
                0 -> WelcomeStep()
                1 -> DurationExplanationStep()
                2 -> DelayExplanationStep()
                3 -> FixedTimeSwitchStep()
                4 -> PermissionsStep(
                    hasDnd = hasDnd,
                    hasAlarm = hasAlarm,
                    hasBattery = hasBattery,
                    context = context
                )
                5 -> ReadyStep()
            }
        }

        // Navigation buttons
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp, start = 32.dp, end = 32.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentStep > 0) {
                OutlinedButton(
                    onClick = {
                        goingForward = false
                        currentStep--
                    },
                    modifier = Modifier.height(56.dp).testTag(TestTags.ONBOARDING_PREV),
                    shape = RoundedCornerShape(28.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GreenPrimary)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_prev),
                        fontSize = 16.sp,
                        color = GreenPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
            }

            if (currentStep == totalSteps - 1) {
                Button(
                    onClick = onFinish,
                    modifier = Modifier.height(56.dp).testTag(TestTags.ONBOARDING_START),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_start),
                        fontSize = 18.sp,
                        modifier = Modifier.padding(horizontal = 36.dp)
                    )
                }
            } else {
                Button(
                    onClick = {
                        goingForward = true
                        currentStep++
                    },
                    enabled = currentStep != 4 || allPermsGranted,
                    modifier = Modifier.height(56.dp).testTag(TestTags.ONBOARDING_NEXT).alpha(if (currentStep == 4 && !allPermsGranted) 0.4f else 1f),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_next),
                        fontSize = 18.sp,
                        modifier = Modifier.padding(horizontal = 36.dp)
                    )
                }
            }
        }
    }
}

// --- Steps ---

@Composable
private fun WelcomeStep() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 32.dp)
            .padding(bottom = 160.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("﷽", fontSize = 28.sp, color = GreenPrimaryDark)
        Spacer(Modifier.height(24.dp))
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
private fun DurationExplanationStep() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .padding(bottom = 160.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.onboarding_step2_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = GreenPrimaryDark,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )
        Spacer(Modifier.height(16.dp))
        DemoPrayerRowCard(
            delayValue = "0",
            delayLabel = stringResource(R.string.label_delay_minutes),
            durationValue = "60",
            durationLabel = stringResource(R.string.label_duration),
            delayLabelColor = TextMuted,
            durationLabelColor = Gold,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_step2_desc),
            fontSize = 14.sp,
            color = TextDark,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun DelayExplanationStep() {
    var showAfter by remember { mutableStateOf(false) }
    val tapScale = remember { Animatable(1f) }
    val tapHighlight = remember { Animatable(0f) }
    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        showAfter = false
        delay(1400)
        // Simulate tap: press down
        tapHighlight.animateTo(1f, tween(100))
        tapScale.animateTo(0.85f, tween(100))
        delay(150)
        // Release
        tapScale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 300f))
        tapHighlight.animateTo(0f, tween(200))
        delay(200)
        // Shake the value + swap at midpoint
        shakeOffset.animateTo(20f, tween(50))
        shakeOffset.animateTo(-15f, tween(50))
        showAfter = true
        shakeOffset.animateTo(10f, tween(50))
        shakeOffset.animateTo(-5f, tween(50))
        shakeOffset.animateTo(0f, tween(60))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .padding(bottom = 160.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.onboarding_delay_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = GreenPrimaryDark,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))

        DemoPrayerRowCard(
            delayValue = if (showAfter) "05:20" else "5",
            delayLabel = stringResource(if (showAfter) R.string.label_delay_at else R.string.label_delay_minutes),
            durationValue = "60",
            durationLabel = stringResource(R.string.label_duration),
            delayLabelColor = if (showAfter) GreenPrimary else Gold,
            delayValueColor = GreenPrimary,
            durationLabelColor = Gold,
            tapTargetDelay = true,
            tapScale = tapScale.value,
            tapHighlight = tapHighlight.value,
            shakeOffset = shakeOffset.value
        )

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_delay_desc),
            fontSize = 14.sp,
            color = TextDark,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun FixedTimeSwitchStep() {
    var showAfter by remember { mutableStateOf(false) }
    val tapScale = remember { Animatable(1f) }
    val tapHighlight = remember { Animatable(0f) }
    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        showAfter = false
        delay(1400)
        // Simulate tap: press down
        tapHighlight.animateTo(1f, tween(100))
        tapScale.animateTo(0.85f, tween(100))
        delay(150)
        // Release
        tapScale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 300f))
        tapHighlight.animateTo(0f, tween(200))
        delay(200)
        // Shake the value + swap at midpoint
        shakeOffset.animateTo(20f, tween(50))
        shakeOffset.animateTo(-15f, tween(50))
        showAfter = true
        shakeOffset.animateTo(10f, tween(50))
        shakeOffset.animateTo(-5f, tween(50))
        shakeOffset.animateTo(0f, tween(60))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .padding(bottom = 160.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.onboarding_step3_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = GreenPrimaryDark,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))

        DemoPrayerRowCard(
            delayValue = "0",
            delayLabel = stringResource(R.string.label_delay_minutes),
            durationValue = if (showAfter) "06:15" else "60",
            durationLabel = stringResource(if (showAfter) R.string.label_fixed_time else R.string.label_duration),
            delayLabelColor = TextMuted,
            durationLabelColor = if (showAfter) GreenPrimary else Gold,
            durationValueColor = if (showAfter) GreenPrimary else TextDark,
            tapTargetDuration = true,
            tapScale = tapScale.value,
            tapHighlight = tapHighlight.value,
            shakeOffset = shakeOffset.value
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_step3_desc),
            fontSize = 14.sp,
            color = TextDark,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun PermissionsStep(
    hasDnd: Boolean,
    hasAlarm: Boolean,
    hasBattery: Boolean,
    context: Context
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp)
            .padding(bottom = 160.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.onboarding_perm_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = GreenPrimaryDark
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.onboarding_perm_desc),
            fontSize = 12.sp,
            color = TextDark,
            textAlign = TextAlign.Center,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(8.dp))

        PermissionRow(
            title = stringResource(R.string.onboarding_perm_dnd),
            description = stringResource(R.string.onboarding_perm_dnd_desc),
            isGranted = hasDnd,
            grantLabel = stringResource(R.string.onboarding_perm_grant),
            grantedLabel = stringResource(R.string.onboarding_perm_granted),
            onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        )
        Spacer(Modifier.height(4.dp))

        PermissionRow(
            title = stringResource(R.string.onboarding_perm_alarm),
            description = stringResource(R.string.onboarding_perm_alarm_desc),
            isGranted = hasAlarm,
            grantLabel = stringResource(R.string.onboarding_perm_grant),
            grantedLabel = stringResource(R.string.onboarding_perm_granted),
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                }
            }
        )
        Spacer(Modifier.height(4.dp))

        PermissionRow(
            title = stringResource(R.string.onboarding_perm_battery),
            description = stringResource(R.string.onboarding_perm_battery_desc),
            isGranted = hasBattery,
            grantLabel = stringResource(R.string.onboarding_perm_grant),
            grantedLabel = stringResource(R.string.onboarding_perm_granted),
            onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:${context.packageName}")
                context.startActivity(intent)
            }
        )
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
                    modifier = Modifier.height(32.dp)
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 32.dp)
            .padding(bottom = 160.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.onboarding_ready_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = GreenPrimaryDark
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_ready_desc),
            fontSize = 16.sp,
            color = TextDark,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )
    }
}

// --- Demo prayer row card ---

@Composable
private fun DemoPrayerRowCard(
    delayValue: String,
    delayLabel: String,
    durationValue: String,
    durationLabel: String,
    delayLabelColor: Color = TextMuted,
    durationLabelColor: Color = Gold,
    delayValueColor: Color = TextDark,
    durationValueColor: Color = TextDark,
    tapTargetDelay: Boolean = false,
    tapTargetDuration: Boolean = false,
    tapScale: Float = 1f,
    tapHighlight: Float = 0f,
    shakeOffset: Float = 0f
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, GoldLight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Prayer name
            Text(
                text = stringResource(R.string.prayer_fajr),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor,
                modifier = Modifier.weight(2f)
            )
            // Time
            Text(
                text = "05:15",
                fontSize = 13.sp,
                color = TextDark,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1.5f)
            )
            // Delay
            Row(
                modifier = Modifier.weight(1.8f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val delayShake = if (tapTargetDelay) shakeOffset else 0f
                DemoInputBox(
                    text = delayValue,
                    textColor = delayValueColor,
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer { translationX = delayShake }
                )
                Box(modifier = Modifier.weight(0.8f)) {
                    val bgAlpha = if (tapTargetDelay) tapHighlight * 0.3f else 0f
                    val labelScale = if (tapTargetDelay) tapScale else 1f
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = bgAlpha }
                            .clip(RoundedCornerShape(6.dp))
                            .background(Gold)
                    )
                    Text(
                        text = delayLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = delayLabelColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                scaleX = labelScale
                                scaleY = labelScale
                                translationX = delayShake
                            }
                            .padding(2.dp)
                    )
                }
            }
            // Duration
            Row(
                modifier = Modifier.weight(2.2f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val durShake = if (tapTargetDuration) shakeOffset else 0f
                DemoInputBox(
                    text = durationValue,
                    textColor = durationValueColor,
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer { translationX = durShake }
                )
                Box(modifier = Modifier.weight(0.8f)) {
                    val bgAlpha = if (tapTargetDuration) tapHighlight * 0.3f else 0f
                    val labelScale = if (tapTargetDuration) tapScale else 1f
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = bgAlpha }
                            .clip(RoundedCornerShape(6.dp))
                            .background(Gold)
                    )
                    Text(
                        text = durationLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = durationLabelColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                scaleX = labelScale
                                scaleY = labelScale
                                translationX = durShake
                            }
                            .padding(2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DemoInputBox(
    text: String,
    textColor: Color = TextDark,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(GoldLight.copy(alpha = 0.3f))
            .padding(horizontal = 2.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 1
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
