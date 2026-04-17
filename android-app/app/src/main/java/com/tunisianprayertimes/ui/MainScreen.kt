package com.tunisianprayertimes.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.tunisianprayertimes.DelayMode
import com.tunisianprayertimes.Delegation
import com.tunisianprayertimes.DelegationLocationResult
import com.tunisianprayertimes.DelegationLocator
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.GouvernoratRepository
import com.tunisianprayertimes.ManualSilenceScheduler
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.SilenceAlarmComputer
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.RamadanDetector
import com.tunisianprayertimes.RamadanOverrideChecker
import com.tunisianprayertimes.SilenceMode
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.SilenceVerifyWorker
import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import com.tunisianprayertimes.ui.theme.BannerBg
import com.tunisianprayertimes.ui.theme.BannerStroke
import com.tunisianprayertimes.ui.theme.BannerText
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.CardBorder
import com.tunisianprayertimes.ui.theme.Divider
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.HeaderEnd
import com.tunisianprayertimes.ui.theme.NextPrayerBg
import com.tunisianprayertimes.ui.theme.HeaderStart
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.RamadanBg
import com.tunisianprayertimes.ui.theme.SilenceRed
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    activity: androidx.appcompat.app.AppCompatActivity
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val notificationManager = remember { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    // Reactive state that gets refreshed on resume
    var refreshTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // State derived from system/prefs — re-evaluated on refreshTick
    val hasDnd = remember(refreshTick) { notificationManager.isNotificationPolicyAccessGranted }
    val hasAlarm = remember(refreshTick) { hasExactAlarmPermission(context) }
    val hasBattery = remember(refreshTick) { isIgnoringBatteryOptimizations(context) }
    val hasPhoneState = remember(refreshTick) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }
    val phoneStatePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTick++ }

    fun ensureCallTrackingPermission() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    var isSilent by remember { mutableStateOf(audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) }
    // Re-sync isSilent on resume
    LaunchedEffect(refreshTick) {
        isSilent = audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT
    }

    var autoSilenceEnabled by rememberSaveable { mutableStateOf(PrefsManager.isEnabled(context)) }
    var callEndVibrationEnabled by rememberSaveable {
        mutableStateOf(PrefsManager.isCallEndVibrationEnabled(context))
    }
    var delegationId by rememberSaveable { mutableIntStateOf(PrefsManager.getDelegationId(context)) }
    var manualUsesDuration by rememberSaveable { mutableStateOf(PrefsManager.usesManualSilenceDuration(context)) }
    var manualDurationHours by rememberSaveable {
        mutableStateOf((PrefsManager.getManualSilenceDurationMinutes(context) / 60).toString())
    }
    var manualDurationMinutes by rememberSaveable {
        mutableStateOf((PrefsManager.getManualSilenceDurationMinutes(context) % 60).toString())
    }
    var manualSilenceActive by remember { mutableStateOf(PrefsManager.isManualSilenceActive(context)) }
    var manualSilenceEndsAtMillis by remember {
        mutableLongStateOf(PrefsManager.getManualSilenceEndsAtMillis(context))
    }

    LaunchedEffect(refreshTick) {
        ManualSilenceScheduler.syncExpiredTimer(context)
        isSilent = audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT
        manualSilenceActive = PrefsManager.isManualSilenceActive(context)
        manualSilenceEndsAtMillis = PrefsManager.getManualSilenceEndsAtMillis(context)
    }

    // Start Ramadan override polling on first composition
    LaunchedEffect(Unit) {
        RamadanOverrideChecker.startPollingIfNeeded()
    }

    LaunchedEffect(manualSilenceEndsAtMillis) {
        if (manualSilenceEndsAtMillis <= 0L) return@LaunchedEffect

        val waitMillis = manualSilenceEndsAtMillis - System.currentTimeMillis()
        if (waitMillis <= 0L) {
            refreshTick++
            return@LaunchedEffect
        }

        delay(waitMillis)
        refreshTick++
    }

    // Reschedule helper
    fun rescheduleIfEnabled() {
        if (PrefsManager.isEnabled(context) && hasDnd && hasAlarm) {
            SilenceScheduler.scheduleAll(context)
        }
    }

    // Sync on resume
    LaunchedEffect(refreshTick) {
        // Auto-update location from last known GPS fix
        if (PrefsManager.isAutoLocationUpdateEnabled(context) &&
            DelegationLocator.hasLocationPermission(context)
        ) {
            if (DelegationLocator.updateDelegationFromLastLocation(context)) {
                delegationId = PrefsManager.getDelegationId(context)
            }
        }

        val hasAll = notificationManager.isNotificationPolicyAccessGranted && hasExactAlarmPermission(context)
        if (PrefsManager.isEnabled(context) && hasAll) {
            if (!PrefsManager.isDisabledOutsideTunisia(context)) {
                SilenceScheduler.scheduleAll(context)
            }
            SilenceVerifyWorker.enqueue(context)
        } else if (PrefsManager.isEnabled(context) && !hasAll) {
            SilenceScheduler.cancelAll(context)
            SilenceVerifyWorker.cancel(context)
        }
        isSilent = audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT
        manualSilenceActive = PrefsManager.isManualSilenceActive(context)
        manualSilenceEndsAtMillis = PrefsManager.getManualSilenceEndsAtMillis(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgCream)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        IslamicHeader()

        // Islamic border strip
        Image(
            painter = painterResource(R.drawable.islamic_border),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            contentScale = ContentScale.FillBounds
        )

        // Content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
                Spacer(Modifier.height(12.dp))

                // Status card
                StatusCard(isSilent = isSilent, hasDnd = hasDnd)

                // Permission banner
                AnimatedVisibility(
                    visible = !hasDnd || !hasAlarm || !hasPhoneState,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    PermissionBanner(
                        hasDnd = hasDnd,
                        hasAlarm = hasAlarm,
                        hasPhoneState = hasPhoneState,
                        onRequestPhoneState = {
                            phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                        },
                        context = context
                    )
                }

                // Battery banner
                AnimatedVisibility(
                    visible = !hasBattery,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    BatteryBanner(context = context)
                }

                // Location picker
                LocationPickerCard(
                    delegationId = delegationId,
                    onDelegationSelected = { delegation ->
                        delegationId = delegation.id
                        PrefsManager.setDelegationId(context, delegation.id)
                        rescheduleIfEnabled()
                    }
                )

                // Prayer settings
                PrayerSettingsCard(
                    delegationId = delegationId,
                    activity = activity,
                    onConfigChanged = { rescheduleIfEnabled() }
                )

                // Auto-silence toggle
                AutoSilenceCard(
                    enabled = autoSilenceEnabled,
                    onToggle = { enabled ->
                        autoSilenceEnabled = enabled
                        PrefsManager.setEnabled(context, enabled)
                        if (enabled) {
                            ensureCallTrackingPermission()
                            if (hasDnd && hasAlarm) {
                                SilenceScheduler.scheduleAll(context)
                                SilenceVerifyWorker.enqueue(context)
                            }
                            Toast.makeText(context, context.getString(R.string.toast_auto_enabled), Toast.LENGTH_SHORT).show()
                        } else {
                            SilenceScheduler.cancelAll(context)
                            SilenceVerifyWorker.cancel(context)
                            Toast.makeText(context, context.getString(R.string.toast_auto_disabled), Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                CallEndVibrationCard(
                    enabled = callEndVibrationEnabled,
                    onToggle = { enabled ->
                        callEndVibrationEnabled = enabled
                        PrefsManager.setCallEndVibrationEnabled(context, enabled)
                    }
                )

                AutoLocationCard(
                    enabled = PrefsManager.isAutoLocationUpdateEnabled(context),
                    onToggle = { enabled ->
                        PrefsManager.setAutoLocationUpdateEnabled(context, enabled)
                    }
                )

                // Ramadan indicator
                if (RamadanDetector.isRamadan()) {
                    RamadanBadge()
                }

                // Manual toggle button
                ManualSilenceButton(
                    isSilent = isSilent,
                    hasDnd = hasDnd,
                    manualUsesDuration = manualUsesDuration,
                    manualDurationHours = manualDurationHours,
                    manualDurationMinutes = manualDurationMinutes,
                    manualSilenceActive = manualSilenceActive,
                    manualSilenceEndsAtMillis = manualSilenceEndsAtMillis,
                    onUseDurationChange = { usesDuration ->
                        manualUsesDuration = usesDuration
                        PrefsManager.setManualSilenceUsesDuration(context, usesDuration)
                    },
                    onDurationHoursChange = { value ->
                        manualDurationHours = value
                        val totalMinutes = (value.toIntOrNull() ?: 0) * 60 + (manualDurationMinutes.toIntOrNull() ?: 0)
                        PrefsManager.setManualSilenceDurationMinutes(context, totalMinutes)
                    },
                    onDurationMinutesChange = { value ->
                        manualDurationMinutes = value
                        val totalMinutes = (manualDurationHours.toIntOrNull() ?: 0) * 60 + (value.toIntOrNull() ?: 0)
                        PrefsManager.setManualSilenceDurationMinutes(context, totalMinutes)
                    },
                    onClick = {
                        if (!notificationManager.isNotificationPolicyAccessGranted) {
                            Toast.makeText(context, context.getString(R.string.toast_dnd_permission), Toast.LENGTH_SHORT).show()
                            return@ManualSilenceButton
                        }
                        if (isSilent) {
                            SilenceModeController.setManualNormal(context)
                            SilenceModeController.notifyIfMissedCallDuringSilence(context)
                            isSilent = audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT
                            manualSilenceActive = PrefsManager.isManualSilenceActive(context)
                            manualSilenceEndsAtMillis = PrefsManager.getManualSilenceEndsAtMillis(context)
                            Toast.makeText(context, context.getString(R.string.toast_normal_restored), Toast.LENGTH_SHORT).show()
                        } else {
                            val totalMinutes = resolveManualTotalMinutes(
                                manualDurationHours, manualDurationMinutes,
                                PrefsManager.getManualSilenceDurationMinutes(context)
                            )
                            if (manualUsesDuration && totalMinutes <= 0) {
                                Toast.makeText(context, context.getString(R.string.error_manual_duration_required), Toast.LENGTH_SHORT).show()
                                return@ManualSilenceButton
                            }

                            ensureCallTrackingPermission()
                            SilenceModeController.setManualSilent(context)
                            if (manualUsesDuration) {
                                manualSilenceEndsAtMillis = ManualSilenceScheduler.schedule(context, totalMinutes)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_silent_enabled_timed, formatDurationText(totalMinutes)),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                ManualSilenceScheduler.cancel(context)
                                manualSilenceEndsAtMillis = -1L
                                Toast.makeText(context, context.getString(R.string.toast_silent_enabled), Toast.LENGTH_SHORT).show()
                            }
                            isSilent = true
                            manualSilenceActive = PrefsManager.isManualSilenceActive(context)
                        }
                    }
                )

                // Bottom border
                Image(
                    painter = painterResource(R.drawable.islamic_border),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .height(6.dp),
                    contentScale = ContentScale.FillBounds
                )

                // Info text
                Text(
                    text = stringResource(R.string.info_text),
                    fontSize = 12.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.INFO_TEXT)
                        .padding(top = 12.dp, bottom = 16.dp)
                )
            }
        }
}

@Composable
private fun IslamicHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(HeaderStart, HeaderEnd),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            )
            .padding(top = 10.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Bismillah
        Image(
            painter = painterResource(R.drawable.basmalah),
            contentDescription = "بِسْمِ اللهِ الرَّحْمَٰنِ الرَّحِيمِ",
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(horizontal = 32.dp),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(Gold)
        )

        Image(
            painter = painterResource(R.drawable.mosque_silhouette),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(Color(0xB3FFFFFF))
        )

        Text(
            text = stringResource(R.string.subtitle),
            fontSize = 14.sp,
            color = Color(0xFFB2DFDB),
            textAlign = TextAlign.Center
        )

        Text(
            text = stringResource(R.string.source),
            fontSize = 11.sp,
            color = Color(0x80B2DFDB),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StatusCard(isSilent: Boolean, hasDnd: Boolean) {
    val bgColor by animateColorAsState(
        targetValue = when {
            !hasDnd -> Color(0xFFFFF3E0) // warm amber bg
            isSilent -> Color(0xFFFFEBEE) // soft red bg
            else -> Color(0xFFE8F5E9) // soft green bg
        },
        label = "statusBg"
    )
    val accentColor = when {
        !hasDnd -> Color(0xFFFF9800)
        isSilent -> SilenceRed
        else -> GreenPrimary
    }
    val statusText = when {
        !hasDnd -> stringResource(R.string.status_no_permission)
        isSilent -> stringResource(R.string.status_silent)
        else -> stringResource(R.string.status_normal)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.STATUS_CARD)
            .clip(RoundedCornerShape(24.dp))
            .background(bgColor)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(accentColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = statusText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = accentColor
        )
    }
}

@Composable
private fun PermissionBanner(
    hasDnd: Boolean,
    hasAlarm: Boolean,
    hasPhoneState: Boolean,
    onRequestPhoneState: () -> Unit,
    context: Context
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.PERMISSION_BANNER)
            .padding(top = 12.dp)
            .clickable {
                if (!hasDnd) {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                } else if (!hasAlarm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                } else if (!hasPhoneState) {
                    onRequestPhoneState()
                }
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = BannerBg),
        border = androidx.compose.foundation.BorderStroke(1.dp, BannerStroke)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚠\uFE0F", fontSize = 20.sp, modifier = Modifier.padding(end = 10.dp))
            Text(
                text = when {
                    !hasDnd && !hasAlarm -> stringResource(R.string.banner_both_missing)
                    !hasDnd -> stringResource(R.string.banner_dnd_missing)
                    !hasAlarm -> stringResource(R.string.banner_alarm_missing)
                    else -> stringResource(R.string.banner_phone_state_missing)
                },
                fontSize = 13.sp,
                color = BannerText,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BatteryBanner(context: Context) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clickable {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:${context.packageName}")
                context.startActivity(intent)
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = BannerBg),
        border = androidx.compose.foundation.BorderStroke(1.dp, BannerStroke)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🔋", fontSize = 20.sp, modifier = Modifier.padding(end = 10.dp))
            Text(
                text = stringResource(R.string.banner_battery_missing),
                fontSize = 13.sp,
                color = BannerText,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationPickerCard(
    delegationId: Int,
    onDelegationSelected: (Delegation) -> Unit
) {
    val context = LocalContext.current
    val gouvernorats = remember { GouvernoratRepository.loadAll(context) }
    val allDelegations = remember { GouvernoratRepository.loadAllDelegations(context) }
    val availableIds = remember { allDelegations.map { it.id }.toSet() }
    val savedDelegation = remember(delegationId) { GouvernoratRepository.findDelegationById(context, delegationId) }

    var showSheet by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun startLocationLookup() {
        if (locating) {
            return
        }

        locating = true
        scope.launch {
            val result = DelegationLocator.detectNearestDelegation(context)
            locating = false

            when (result) {
                is DelegationLocationResult.Success -> {
                    if (PrefsManager.isDisabledOutsideTunisia(context)) {
                        PrefsManager.setDisabledOutsideTunisia(context, false)
                    }
                    onDelegationSelected(result.delegation)
                }
                DelegationLocationResult.PermissionDenied -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.location_permission_denied),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                DelegationLocationResult.LocationUnavailable -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.location_lookup_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                DelegationLocationResult.NoDelegationFound -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.location_no_match),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                DelegationLocationResult.OutsideTunisia -> {
                    SilenceScheduler.cancelAll(context)
                    PrefsManager.setDisabledOutsideTunisia(context, true)
                    Toast.makeText(
                        context,
                        context.getString(R.string.location_outside_tunisia),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (granted) {
            startLocationLookup()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.location_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.LOCATION_PICKER)
            .padding(top = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.location_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrayerNameColor
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = savedDelegation?.displayName()
                        ?: stringResource(R.string.hint_search_delegation),
                    fontSize = 14.sp,
                    color = if (savedDelegation != null) TextDark else TextMuted,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(GoldLight.copy(alpha = 0.25f))
                        .clickable { showSheet = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(GreenPrimary.copy(alpha = 0.1f))
                        .clickable(enabled = !locating) {
                            if (DelegationLocator.hasLocationPermission(context)) {
                                startLocationLookup()
                            } else {
                                locationPermissionLauncher.launch(DelegationLocator.requestedPermissions)
                            }
                        }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_location),
                        contentDescription = stringResource(R.string.gps_auto_detect),
                        tint = GreenPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    if (showSheet) {
        DelegationPickerSheet(
            gouvernorats = gouvernorats,
            availableIds = availableIds,
            currentDelegationId = delegationId,
            onDismiss = { showSheet = false },
            onSelect = { delegation ->
                showSheet = false
                onDelegationSelected(delegation)
            }
        )
    }
}

/**
 * Scores a delegation against search terms for ranking.
 * 4 = exact name match, 3 = name starts with query, 2 = name contains a term, 0 = no name match.
 */
internal fun delegationSearchScore(delegation: Delegation, terms: List<String>): Int {
    if (terms.isEmpty()) return 0
    val query = terms.joinToString(" ")
    val names = listOf(delegation.nomFr.lowercase(), delegation.nomAr, delegation.nomEn.lowercase())
    return when {
        names.any { it == query } -> 4
        names.any { it.startsWith(query) } -> 3
        names.any { n -> terms.any { n.contains(it) } } -> 2
        else -> 0
    }
}

/** Flat list item: either a gouvernorat header or a delegation row. */
private sealed class PickerItem {
    data class Header(val govName: String) : PickerItem()
    data class DelegationRow(val delegation: Delegation, val isSelected: Boolean) : PickerItem()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DelegationPickerSheet(
    gouvernorats: List<Gouvernorat>,
    availableIds: Set<Int>,
    currentDelegationId: Int,
    onDismiss: () -> Unit,
    onSelect: (Delegation) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchText by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // Build flat list of headers + delegation rows, filtering by search
    val items by remember(searchText) {
        derivedStateOf {
            val terms = searchText.lowercase().trim().split(" ").filter { it.isNotEmpty() }
            val result = mutableListOf<PickerItem>()
            gouvernorats.forEach { gov ->
                val govSearchable = "${gov.nomFr} ${gov.nomAr} ${gov.nomEn}".lowercase()
                val govMatches = terms.isEmpty() || terms.all { govSearchable.contains(it) }
                val filtered = gov.delegations
                    .filter { it.id in availableIds }
                    .filter { d ->
                        if (terms.isEmpty()) true
                        else if (govMatches) true
                        else terms.all { term -> d.searchableText().contains(term) }
                    }
                    .let { list ->
                        if (terms.isEmpty()) list
                        else list.sortedByDescending { delegationSearchScore(it, terms) }
                    }
                if (filtered.isNotEmpty()) {
                    result.add(PickerItem.Header(gov.nomAr))
                    filtered.forEach { d ->
                        result.add(PickerItem.DelegationRow(d, d.id == currentDelegationId))
                    }
                }
            }
            result as List<PickerItem>
        }
    }

    // Scroll to selected delegation on first open
    LaunchedEffect(Unit) {
        val idx = items.indexOfFirst { it is PickerItem.DelegationRow && it.isSelected }
        if (idx > 0) listState.scrollToItem((idx - 1).coerceAtLeast(0))
    }

    // Scroll to top when search text changes
    LaunchedEffect(searchText) {
        if (searchText.isNotEmpty()) listState.scrollToItem(0)
    }

    // Auto-focus search field
    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // Search bar
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GoldLight)
            ) {
                BasicTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        color = TextDark,
                        textAlign = TextAlign.Start
                    ),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        if (searchText.isEmpty()) {
                            Text(
                                text = stringResource(R.string.hint_search_delegation),
                                fontSize = 15.sp,
                                color = TextMuted
                            )
                        }
                        innerTextField()
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            if (items.isEmpty()) {
                // No results
                Text(
                    text = "لا توجد نتائج",
                    fontSize = 14.sp,
                    color = TextMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                // Grouped delegation list — fill remaining space
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(
                        count = items.size,
                        key = { i ->
                            when (val item = items[i]) {
                                is PickerItem.Header -> "h_${item.govName}"
                                is PickerItem.DelegationRow -> item.delegation.id
                            }
                        }
                    ) { index ->
                        when (val item = items[index]) {
                            is PickerItem.Header -> {
                                Text(
                                    text = item.govName,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GreenPrimary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(BgCream)
                                        .padding(horizontal = 20.dp, vertical = 8.dp)
                                )
                            }
                            is PickerItem.DelegationRow -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(item.delegation) }
                                        .background(
                                            if (item.isSelected) GoldLight.copy(alpha = 0.2f)
                                            else Color.Transparent
                                        )
                                        .padding(horizontal = 28.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.delegation.nomAr,
                                        fontSize = 15.sp,
                                        color = if (item.isSelected) GreenPrimaryDark else TextDark,
                                        fontWeight = if (item.isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (item.isSelected) {
                                        Text(
                                            text = "✓",
                                            fontSize = 16.sp,
                                            color = GreenPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    color = Divider,
                                    modifier = Modifier.padding(horizontal = 20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrayerSettingsCard(
    delegationId: Int,
    activity: androidx.appcompat.app.AppCompatActivity,
    onConfigChanged: () -> Unit
) {
    val context = LocalContext.current
    var today by remember { mutableStateOf(Calendar.getInstance()) }
    var selectedDate by rememberSaveable { mutableStateOf(today.timeInMillis) }

    // Reset to today at midnight (fixes stale date when app stays alive)
    LaunchedEffect(Unit) {
        while (true) {
            val now = Calendar.getInstance()
            val midnight = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val delayMs = midnight.timeInMillis - now.timeInMillis
            kotlinx.coroutines.delay(delayMs)
            val newToday = Calendar.getInstance()
            today = newToday
            selectedDate = newToday.timeInMillis
        }
    }

    val selectedCal = remember(selectedDate) {
        Calendar.getInstance().apply { timeInMillis = selectedDate }
    }
    val isToday = remember(selectedDate) {
        val sel = Calendar.getInstance().apply { timeInMillis = selectedDate }
        val now = Calendar.getInstance()
        sel.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                sel.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    }

    val displayTimes = remember(delegationId, selectedDate) {
        try {
            PrayerTimesRepository.loadDayPrayerTimes(
                context, delegationId,
                selectedCal.get(Calendar.YEAR),
                selectedCal.get(Calendar.MONTH) + 1,
                selectedCal.get(Calendar.DAY_OF_MONTH)
            )
        } catch (e: Exception) { null }
    }

    val canGoBack = remember(delegationId, selectedDate) {
        val prev = Calendar.getInstance().apply {
            timeInMillis = selectedDate
            add(Calendar.DAY_OF_MONTH, -1)
        }
        PrayerTimesRepository.hasPrayerData(
            context, delegationId,
            prev.get(Calendar.YEAR),
            prev.get(Calendar.MONTH) + 1
        )
    }
    val canGoForward = remember(delegationId, selectedDate) {
        val next = Calendar.getInstance().apply {
            timeInMillis = selectedDate
            add(Calendar.DAY_OF_MONTH, 1)
        }
        PrayerTimesRepository.hasPrayerData(
            context, delegationId,
            next.get(Calendar.YEAR),
            next.get(Calendar.MONTH) + 1
        )
    }

    val prayerNames = mapOf(
        Prayer.FAJR to stringResource(R.string.prayer_fajr),
        Prayer.DHUHR to stringResource(R.string.prayer_dhuhr),
        Prayer.ASR to stringResource(R.string.prayer_asr),
        Prayer.MAGHRIB to stringResource(R.string.prayer_maghrib),
        Prayer.ISHA to stringResource(R.string.prayer_isha),
        Prayer.JOMOAA to stringResource(R.string.prayer_jomoaa),
        Prayer.AID_FITR to stringResource(R.string.prayer_aid_fitr),
        Prayer.AID_ADHA to stringResource(R.string.prayer_aid_adha)
    )

    val isFriday = remember(selectedDate) {
        Calendar.getInstance().apply { timeInMillis = selectedDate }
            .get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
    }

    // Hijri date for the selected day — used for Eid detection
    val hijriDate = remember(selectedDate) {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDate }
        val localDate = LocalDate.of(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        HijrahDate.from(localDate)
    }
    val isAidFitr = remember(selectedDate, displayTimes) {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDate }
        val gregDate = LocalDate.of(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        val now = Calendar.getInstance()
        RamadanOverrideChecker.shouldShowEidFitrPrayer(
            date = gregDate,
            nowHour = now.get(Calendar.HOUR_OF_DAY),
            nowMinute = now.get(Calendar.MINUTE),
            dhuhrHour = displayTimes?.dhuhr?.hour ?: 13,
            dhuhrMinute = displayTimes?.dhuhr?.minute ?: 0,
            isToday = isToday,
        )
    }
    val isAidAdha = remember(selectedDate, displayTimes) {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDate }
        val gregDate = LocalDate.of(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        val now = Calendar.getInstance()
        RamadanOverrideChecker.shouldShowEidAdhaPrayer(
            date = gregDate,
            nowHour = now.get(Calendar.HOUR_OF_DAY),
            nowMinute = now.get(Calendar.MINUTE),
            dhuhrHour = displayTimes?.dhuhr?.hour ?: 13,
            dhuhrMinute = displayTimes?.dhuhr?.minute ?: 0,
            isToday = isToday,
        )
    }

    // Jomoaa custom time from prefs (or Dhuhr as fallback)
    var jomoaaH by rememberSaveable { mutableIntStateOf(PrefsManager.getJomoaaTimeHour(context)) }
    var jomoaaM by rememberSaveable { mutableIntStateOf(PrefsManager.getJomoaaTimeMinute(context)) }
    val resolvedJomoaaH = if (jomoaaH >= 0) jomoaaH else displayTimes?.dhuhr?.hour ?: -1
    val resolvedJomoaaM = if (jomoaaM >= 0) jomoaaM else displayTimes?.dhuhr?.minute ?: -1

    // Aid Fitr custom time from prefs (or Shuruk of Eid day as fallback)
    var aidFitrH by rememberSaveable { mutableIntStateOf(PrefsManager.getAidFitrTimeHour(context)) }
    var aidFitrM by rememberSaveable { mutableIntStateOf(PrefsManager.getAidFitrTimeMinute(context)) }
    val defaultAidFitrTime = remember(delegationId) {
        RamadanOverrideChecker.getDefaultEidPrayerTime(delegationId, RamadanOverrideChecker.getEidFitrDate())
    }
    val resolvedAidFitrH = if (aidFitrH >= 0) aidFitrH else defaultAidFitrTime?.first ?: -1
    val resolvedAidFitrM = if (aidFitrM >= 0) aidFitrM else defaultAidFitrTime?.second ?: -1

    // Aid Adha custom time from prefs (or Shuruk of Eid day as fallback)
    var aidAdhaH by rememberSaveable { mutableIntStateOf(PrefsManager.getAidAdhaTimeHour(context)) }
    var aidAdhaM by rememberSaveable { mutableIntStateOf(PrefsManager.getAidAdhaTimeMinute(context)) }
    val defaultAidAdhaTime = remember(delegationId) {
        RamadanOverrideChecker.getDefaultEidPrayerTime(delegationId, RamadanOverrideChecker.getEidAdhaDate())
    }
    val resolvedAidAdhaH = if (aidAdhaH >= 0) aidAdhaH else defaultAidAdhaTime?.first ?: -1
    val resolvedAidAdhaM = if (aidAdhaM >= 0) aidAdhaM else defaultAidAdhaTime?.second ?: -1

    // Next prayer logic — only for today, Friday-aware
    val nextPrayerFromToday = remember(delegationId, jomoaaH, jomoaaM) {
        if (!isToday) return@remember null
        displayTimes?.nextPrayer(
            today.get(Calendar.HOUR_OF_DAY), today.get(Calendar.MINUTE),
            today.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY,
            PrefsManager.getJomoaaTimeHour(context),
            PrefsManager.getJomoaaTimeMinute(context)
        )
    }

    val tomorrowFajr = remember(delegationId) {
        if (!isToday || nextPrayerFromToday != null) return@remember null
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
        try {
            PrayerTimesRepository.loadDayPrayerTimes(
                context, delegationId,
                tomorrow.get(Calendar.YEAR),
                tomorrow.get(Calendar.MONTH) + 1,
                tomorrow.get(Calendar.DAY_OF_MONTH)
            )?.fajr
        } catch (_: Exception) { null }
    }

    val nextPrayer = if (isToday) {
        nextPrayerFromToday ?: if (tomorrowFajr != null) Prayer.FAJR else null
    } else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.prayer_settings_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.prayer_settings_subtitle),
                fontSize = 12.sp,
                color = TextMuted
            )

            Spacer(Modifier.height(12.dp))

            // Date navigation
            DateNavigationRow(
                selectedDate = selectedDate,
                isToday = isToday,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                onPrevious = {
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = selectedDate
                        add(Calendar.DAY_OF_MONTH, -1)
                    }
                    selectedDate = cal.timeInMillis
                },
                onNext = {
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = selectedDate
                        add(Calendar.DAY_OF_MONTH, 1)
                    }
                    selectedDate = cal.timeInMillis
                },
                onDateSelected = { millis -> selectedDate = millis }
            )

            Spacer(Modifier.height(8.dp))

            if (displayTimes != null) {
                PrayerRowHeader()
                HorizontalDivider(color = Divider, thickness = 1.dp)

                val prayers = listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)
                prayers.forEachIndexed { index, prayer ->
                    val prayerTime = if (isToday && prayer == Prayer.FAJR && tomorrowFajr != null)
                        tomorrowFajr
                    else
                        displayTimes.allPrayers().find { it.prayer == prayer }
                    val nextPrayerTime = if (index < prayers.size - 1) {
                        displayTimes.allPrayers().find { it.prayer == prayers[index + 1] }
                    } else null
                    key(delegationId) {
                        PrayerRow(
                            prayer = prayer,
                            prayerName = prayerNames[prayer] ?: prayer.name,
                            prayerTime = prayerTime,
                            nextPrayerTime = nextPrayerTime,
                            isNextPrayer = prayer == nextPrayer,
                            activity = activity,
                            onConfigChanged = onConfigChanged
                        )
                    }
                }

                // JOMOAA row — always shown as the last row, time editable
                HorizontalDivider(color = Divider, thickness = 1.dp)
                key(delegationId, "jomoaa", jomoaaH, jomoaaM) {
                    PrayerRow(
                        prayer = Prayer.JOMOAA,
                        prayerName = prayerNames[Prayer.JOMOAA] ?: Prayer.JOMOAA.name,
                        prayerTime = PrayerTime(Prayer.JOMOAA, resolvedJomoaaH, resolvedJomoaaM),
                        nextPrayerTime = displayTimes.allPrayers().find { it.prayer == Prayer.ASR },
                        isNextPrayer = Prayer.JOMOAA == nextPrayer,
                        activity = activity,
                        onConfigChanged = onConfigChanged,
                        onPrayerTimeClick = {
                            val picker = MaterialTimePicker.Builder()
                                .setTimeFormat(TimeFormat.CLOCK_24H)
                                .setHour(resolvedJomoaaH.coerceAtLeast(0))
                                .setMinute(resolvedJomoaaM.coerceAtLeast(0))
                                .setTitleText(context.getString(R.string.pick_jomoaa_time))
                                .build()
                            picker.addOnPositiveButtonClickListener {
                                jomoaaH = picker.hour
                                jomoaaM = picker.minute
                                PrefsManager.setJomoaaTime(context, picker.hour, picker.minute)
                                onConfigChanged()
                            }
                            picker.show(activity.supportFragmentManager, "jomoaa_time_picker")
                        }
                    )
                }

                // AID FITR row — shown only on 1 Shawwal
                if (isAidFitr) {
                    HorizontalDivider(color = Divider, thickness = 1.dp)
                    key(delegationId, "aid_fitr", aidFitrH, aidFitrM) {
                        PrayerRow(
                            prayer = Prayer.AID_FITR,
                            prayerName = prayerNames[Prayer.AID_FITR] ?: Prayer.AID_FITR.name,
                            prayerTime = PrayerTime(Prayer.AID_FITR, resolvedAidFitrH, resolvedAidFitrM),
                            nextPrayerTime = displayTimes.allPrayers().find { it.prayer == Prayer.DHUHR },
                            isNextPrayer = false,
                            activity = activity,
                            onConfigChanged = onConfigChanged,
                            onPrayerTimeClick = {
                                val picker = MaterialTimePicker.Builder()
                                    .setTimeFormat(TimeFormat.CLOCK_24H)
                                    .setHour(resolvedAidFitrH.coerceAtLeast(0))
                                    .setMinute(resolvedAidFitrM.coerceAtLeast(0))
                                    .setTitleText(context.getString(R.string.pick_aid_fitr_time))
                                    .build()
                                picker.addOnPositiveButtonClickListener {
                                    aidFitrH = picker.hour
                                    aidFitrM = picker.minute
                                    PrefsManager.setAidFitrTime(context, picker.hour, picker.minute)
                                    onConfigChanged()
                                }
                                picker.show(activity.supportFragmentManager, "aid_fitr_time_picker")
                            }
                        )
                    }
                }

                // AID ADHA row — shown only on 10 Dhul Hijjah
                if (isAidAdha) {
                    HorizontalDivider(color = Divider, thickness = 1.dp)
                    key(delegationId, "aid_adha", aidAdhaH, aidAdhaM) {
                        PrayerRow(
                            prayer = Prayer.AID_ADHA,
                            prayerName = prayerNames[Prayer.AID_ADHA] ?: Prayer.AID_ADHA.name,
                            prayerTime = PrayerTime(Prayer.AID_ADHA, resolvedAidAdhaH, resolvedAidAdhaM),
                            nextPrayerTime = displayTimes.allPrayers().find { it.prayer == Prayer.DHUHR },
                            isNextPrayer = false,
                            activity = activity,
                            onConfigChanged = onConfigChanged,
                            onPrayerTimeClick = {
                                val picker = MaterialTimePicker.Builder()
                                    .setTimeFormat(TimeFormat.CLOCK_24H)
                                    .setHour(resolvedAidAdhaH.coerceAtLeast(0))
                                    .setMinute(resolvedAidAdhaM.coerceAtLeast(0))
                                    .setTitleText(context.getString(R.string.pick_aid_adha_time))
                                    .build()
                                picker.addOnPositiveButtonClickListener {
                                    aidAdhaH = picker.hour
                                    aidAdhaM = picker.minute
                                    PrefsManager.setAidAdhaTime(context, picker.hour, picker.minute)
                                    onConfigChanged()
                                }
                                picker.show(activity.supportFragmentManager, "aid_adha_time_picker")
                            }
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.no_prayer_data),
                    fontSize = 13.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DateNavigationRow(
    selectedDate: Long,
    isToday: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDateSelected: (Long) -> Unit
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("EEEE d MMMM yyyy", Locale.forLanguageTag("ar-TN-u-nu-latn")) }
    val dateText = remember(selectedDate) { dateFormat.format(selectedDate) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(GoldLight.copy(alpha = 0.4f))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Right arrow → previous day (RTL: right = back)
        Text(
            text = "▸",
            fontSize = 18.sp,
            color = if (canGoBack) GreenPrimary else TextMuted.copy(alpha = 0.3f),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .then(if (canGoBack) Modifier.clickable { onPrevious() } else Modifier)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )

        // Date label — tap to open date picker
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .testTag(TestTags.DATE_LABEL)
                .clickable {
                val cal = Calendar.getInstance().apply { timeInMillis = selectedDate }
                val arabicLocale = Locale.forLanguageTag("ar-TN-u-nu-latn")
                val config = android.content.res.Configuration(context.resources.configuration).apply {
                    setLocale(arabicLocale)
                }
                val arabicContext = android.view.ContextThemeWrapper(context, R.style.Theme_TunisianPrayerTimes)
                arabicContext.applyOverrideConfiguration(config)
                android.app.DatePickerDialog(
                    arabicContext,
                    { _, year, month, dayOfMonth ->
                        val picked = Calendar.getInstance().apply {
                            set(Calendar.YEAR, year)
                            set(Calendar.MONTH, month)
                            set(Calendar.DAY_OF_MONTH, dayOfMonth)
                        }
                        onDateSelected(picked.timeInMillis)
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        ) {
            Text(
                text = dateText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = GreenPrimaryDark,
                textAlign = TextAlign.Center
            )
            if (isToday) {
                Text(
                    text = stringResource(R.string.date_today),
                    fontSize = 11.sp,
                    color = Gold,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    text = stringResource(R.string.date_go_back_today),
                    fontSize = 11.sp,
                    color = GreenPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(GreenPrimary.copy(alpha = 0.1f))
                        .clickable { onDateSelected(Calendar.getInstance().timeInMillis) }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        // Left arrow → next day (RTL: left = forward)
        Text(
            text = "◂",
            fontSize = 18.sp,
            color = if (canGoForward) GreenPrimary else TextMuted.copy(alpha = 0.3f),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .then(if (canGoForward) Modifier.clickable { onNext() } else Modifier)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PrayerRowHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.col_prayer),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(maxFontSize = 11.sp),
            modifier = Modifier.weight(1.5f)
        )
        Text(
            text = stringResource(R.string.col_time),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(maxFontSize = 11.sp),
            modifier = Modifier.weight(1.5f)
        )
        Row(modifier = Modifier.weight(2.2f)) {
            Text(
                text = stringResource(R.string.col_delay),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(maxFontSize = 11.sp),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.weight(0.4f))
        }
        Row(modifier = Modifier.weight(2.5f)) {
            Text(
                text = stringResource(R.string.col_duration),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(maxFontSize = 11.sp),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.weight(0.4f))
        }
    }
}

@Composable
private fun PrayerRow(
    prayer: Prayer,
    prayerName: String,
    prayerTime: PrayerTime?,
    nextPrayerTime: PrayerTime?,
    isNextPrayer: Boolean,
    activity: androidx.appcompat.app.AppCompatActivity,
    onConfigChanged: () -> Unit,
    onPrayerTimeClick: (() -> Unit)? = null
) {
    val context = LocalContext.current

    // Delay state
    var delayMode by rememberSaveable { mutableStateOf(PrefsManager.getDelayMode(context, prayer)) }
    var delayMinutes by rememberSaveable { mutableStateOf(PrefsManager.getDelayMinutes(context, prayer).toString()) }
    var delayFixedH by rememberSaveable { mutableIntStateOf(initDelayFixedHour(context, prayer, prayerTime)) }
    var delayFixedM by rememberSaveable { mutableIntStateOf(initDelayFixedMinute(context, prayer, prayerTime)) }

    // Duration/end state
    var silenceMode by rememberSaveable { mutableStateOf(PrefsManager.getSilenceMode(context, prayer)) }
    var afterMinutes by rememberSaveable { mutableStateOf(PrefsManager.getAfterMinutes(context, prayer).toString()) }
    var fixedH by rememberSaveable { mutableIntStateOf(initFixedHour(context, prayer, prayerTime)) }
    var fixedM by rememberSaveable { mutableIntStateOf(initFixedMinute(context, prayer, prayerTime)) }

    // Compute overlap with next prayer
    val overlapsNextPrayer = remember(prayerTime, nextPrayerTime, silenceMode, afterMinutes, fixedH, fixedM, delayMode, delayMinutes, delayFixedH, delayFixedM) {
        if (prayerTime == null || nextPrayerTime == null) false
        else {
            val config = PrayerSilenceConfig(
                mode = silenceMode,
                afterMinutes = afterMinutes.toIntOrNull() ?: 0,
                fixedHour = fixedH,
                fixedMinute = fixedM,
                delayMode = delayMode,
                delayMinutes = delayMinutes.toIntOrNull() ?: 0,
                delayFixedHour = delayFixedH,
                delayFixedMinute = delayFixedM
            )
            SilenceAlarmComputer.overlapsNextPrayer(prayerTime, nextPrayerTime, config)
        }
    }

    Column {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(
                if (isNextPrayer) Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(NextPrayerBg)
                else Modifier
            )
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Prayer name
        Text(
            text = prayerName,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (isNextPrayer) GreenPrimary else PrayerNameColor,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(maxFontSize = 14.sp),
            modifier = Modifier.weight(1.5f)
        )

        // Prayer time
        Text(
            text = if (prayerTime != null) {
                String.format(Locale.US, "%02d:%02d", prayerTime.hour, prayerTime.minute)
            } else "--:--",
            fontSize = 14.sp,
            color = TextDark,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(maxFontSize = 14.sp),
            modifier = Modifier
                .weight(1.5f)
                .then(
                    if (onPrayerTimeClick != null) Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(GoldLight.copy(alpha = 0.3f))
                        .clickable(onClick = onPrayerTimeClick)
                        .padding(vertical = 4.dp)
                    else Modifier
                )
        )

        // Delay control
        Row(
            modifier = Modifier.weight(2.2f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (delayMode == DelayMode.MINUTES) {
                NumberInput(
                    value = delayMinutes,
                    onValueChange = {
                        delayMinutes = it
                        PrefsManager.setDelayMinutes(context, prayer, it.toIntOrNull() ?: 0)
                        onConfigChanged()
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                TimeDisplay(
                    hour = delayFixedH,
                    minute = delayFixedM,
                    onClick = {
                        val picker = MaterialTimePicker.Builder()
                            .setTimeFormat(TimeFormat.CLOCK_24H)
                            .setHour(if (delayFixedH >= 0) delayFixedH else 12)
                            .setMinute(if (delayFixedM >= 0) delayFixedM else 0)
                            .setTitleText(context.getString(R.string.pick_delay_time))
                            .build()
                        picker.addOnPositiveButtonClickListener {
                            if (prayerTime != null && (picker.hour < prayerTime.hour || (picker.hour == prayerTime.hour && picker.minute < prayerTime.minute))) {
                                Toast.makeText(context, context.getString(R.string.error_start_before_athan), Toast.LENGTH_SHORT).show()
                                return@addOnPositiveButtonClickListener
                            }
                            if (silenceMode == SilenceMode.FIXED_TIME) {
                                val endH = PrefsManager.getFixedTimeHour(context, prayer)
                                val endM = PrefsManager.getFixedTimeMinute(context, prayer)
                                if (endH >= 0 && endM >= 0 && (picker.hour > endH || (picker.hour == endH && picker.minute >= endM))) {
                                    Toast.makeText(context, context.getString(R.string.error_start_after_end), Toast.LENGTH_SHORT).show()
                                    return@addOnPositiveButtonClickListener
                                }
                            }
                            delayFixedH = picker.hour
                            delayFixedM = picker.minute
                            PrefsManager.setDelayFixedTime(context, prayer, picker.hour, picker.minute)
                            onConfigChanged()
                        }
                        picker.show(activity.supportFragmentManager, "delay_picker_${prayer.name}")
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = if (delayMode == DelayMode.MINUTES) stringResource(R.string.label_delay_minutes)
                else stringResource(R.string.label_delay_at),
                fontSize = 12.sp,
                color = Gold,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(maxFontSize = 12.sp),
                modifier = Modifier
                    .weight(0.4f)
                    .clickable {
                        val newMode = if (delayMode == DelayMode.MINUTES) DelayMode.FIXED_TIME else DelayMode.MINUTES
                        delayMode = newMode
                        PrefsManager.setDelayMode(context, prayer, newMode)
                        onConfigChanged()
                    }
                    .padding(2.dp)
            )
        }

        // Duration/end control
        Row(
            modifier = Modifier.weight(2.5f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (silenceMode == SilenceMode.DURATION) {
                NumberInput(
                    value = afterMinutes,
                    onValueChange = {
                        afterMinutes = it
                        PrefsManager.setAfterMinutes(context, prayer, it.toIntOrNull() ?: 0)
                        onConfigChanged()
                    },
                    modifier = Modifier.weight(1f).testTag(TestTags.durationInput(prayer.name))
                )
            } else {
                TimeDisplay(
                    hour = fixedH,
                    minute = fixedM,
                    onClick = {
                        val picker = MaterialTimePicker.Builder()
                            .setTimeFormat(TimeFormat.CLOCK_24H)
                            .setHour(if (fixedH >= 0) fixedH else 12)
                            .setMinute(if (fixedM >= 0) fixedM else 0)
                            .setTitleText(context.getString(R.string.pick_end_time))
                            .build()
                        picker.addOnPositiveButtonClickListener {
                            if (prayerTime != null && (picker.hour < prayerTime.hour || (picker.hour == prayerTime.hour && picker.minute < prayerTime.minute))) {
                                Toast.makeText(context, context.getString(R.string.error_end_before_athan), Toast.LENGTH_SHORT).show()
                                return@addOnPositiveButtonClickListener
                            }
                            if (delayMode == DelayMode.FIXED_TIME) {
                                val startH = PrefsManager.getDelayFixedHour(context, prayer)
                                val startM = PrefsManager.getDelayFixedMinute(context, prayer)
                                if (startH >= 0 && startM >= 0 && (picker.hour < startH || (picker.hour == startH && picker.minute <= startM))) {
                                    Toast.makeText(context, context.getString(R.string.error_start_after_end), Toast.LENGTH_SHORT).show()
                                    return@addOnPositiveButtonClickListener
                                }
                            }
                            if (nextPrayerTime != null) {
                                val pickedMinutes = picker.hour * 60 + picker.minute
                                val nextMinutes = nextPrayerTime.hour * 60 + nextPrayerTime.minute
                                if (pickedMinutes > nextMinutes) {
                                    Toast.makeText(context, context.getString(R.string.error_overlaps_next_prayer), Toast.LENGTH_SHORT).show()
                                    return@addOnPositiveButtonClickListener
                                }
                            }
                            fixedH = picker.hour
                            fixedM = picker.minute
                            PrefsManager.setFixedTime(context, prayer, picker.hour, picker.minute)
                            onConfigChanged()
                        }
                        picker.show(activity.supportFragmentManager, "picker_${prayer.name}")
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = if (silenceMode == SilenceMode.DURATION) stringResource(R.string.label_duration)
                else stringResource(R.string.label_fixed_time),
                fontSize = 12.sp,
                color = Gold,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(maxFontSize = 12.sp),
                modifier = Modifier
                    .weight(0.4f)
                    .clickable {
                        val newMode = if (silenceMode == SilenceMode.DURATION) SilenceMode.FIXED_TIME else SilenceMode.DURATION
                        silenceMode = newMode
                        PrefsManager.setSilenceMode(context, prayer, newMode)
                        onConfigChanged()
                    }
                    .padding(2.dp)
            )
        }
    }

    if (overlapsNextPrayer) {
        Text(
            text = stringResource(R.string.warning_overlaps_next_prayer),
            fontSize = 11.sp,
            color = SilenceRed,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.OVERLAP_WARNING)
                .padding(bottom = 4.dp)
        )
    }
    } // Column
}

@Composable
private fun NumberInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BasicTextField(
            value = value,
            onValueChange = { new ->
                // Only allow digits, max 3 chars
                val filtered = new.filter { it.isDigit() }.take(3)
                onValueChange(filtered)
            },
            modifier = modifier
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .then(
                    Modifier.background(
                        color = Color.White,
                        shape = RoundedCornerShape(6.dp)
                    )
                )
                .padding(4.dp),
            textStyle = TextStyle(
                fontSize = 14.sp,
                color = TextDark,
                textAlign = TextAlign.Center
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            decorationBox = { innerTextField ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            color = Color.White,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .then(
                            Modifier
                                .background(
                                    color = GoldLight.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                        )
                ) {
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun TimeDisplay(
    hour: Int,
    minute: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(GoldLight.copy(alpha = 0.3f))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp)
    ) {
        Text(
            text = if (hour >= 0 && minute >= 0) String.format(Locale.US, "%02d:%02d", hour, minute) else "--:--",
            fontSize = 13.sp,
            color = TextDark,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(maxFontSize = 13.sp)
        )
    }
}

@Composable
private fun AutoSilenceCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.auto_silence),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag(TestTags.AUTO_SILENCE_SWITCH),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = GreenPrimary
                )
            )
        }
    }
}

@Composable
private fun CallEndVibrationCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.call_end_vibration_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = GreenPrimary
                    )
                )
            }
        }
    }
}

@Composable
private fun AutoLocationCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.auto_location_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = GreenPrimary
                    )
                )
            }
        }
    }
}

@Composable
private fun RamadanBadge() {
    Text(
        text = stringResource(R.string.ramadan_active),
        fontSize = 14.sp,
        color = Gold,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(RamadanBg, RoundedCornerShape(8.dp))
            .padding(12.dp)
    )
}

@Composable
private fun ManualSilenceButton(
    isSilent: Boolean,
    hasDnd: Boolean,
    manualUsesDuration: Boolean,
    manualDurationHours: String,
    manualDurationMinutes: String,
    manualSilenceActive: Boolean,
    manualSilenceEndsAtMillis: Long,
    onUseDurationChange: (Boolean) -> Unit,
    onDurationHoursChange: (String) -> Unit,
    onDurationMinutesChange: (String) -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val bgColor by animateColorAsState(
        targetValue = if (isSilent && hasDnd) SilenceRed else GreenPrimary,
        label = "buttonColor"
    )

    val resolvedTotalMinutes = resolveManualTotalMinutes(
        manualDurationHours, manualDurationMinutes,
        PrefsManager.getManualSilenceDurationMinutes(context)
    )
    val durationText = formatDurationText(resolvedTotalMinutes)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.manual_silence_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ManualSilenceModeChip(
                    text = stringResource(R.string.manual_silence_mode_until),
                    selected = !manualUsesDuration,
                    testTag = TestTags.MANUAL_SILENCE_MODE_UNTIL,
                    onClick = { onUseDurationChange(false) },
                    modifier = Modifier.weight(1f)
                )
                ManualSilenceModeChip(
                    text = stringResource(R.string.manual_silence_mode_duration),
                    selected = manualUsesDuration,
                    testTag = TestTags.MANUAL_SILENCE_MODE_DURATION,
                    onClick = { onUseDurationChange(true) },
                    modifier = Modifier.weight(1f)
                )
            }

            AnimatedVisibility(
                visible = manualUsesDuration,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.manual_silence_duration_label),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrayerNameColor,
                        modifier = Modifier.width(48.dp)
                    )
                    NumberInput(
                        value = manualDurationHours,
                        onValueChange = onDurationHoursChange,
                        modifier = Modifier
                            .width(56.dp)
                            .testTag(TestTags.MANUAL_SILENCE_DURATION_INPUT)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.manual_silence_hours_label),
                        fontSize = 13.sp,
                        color = TextMuted
                    )
                    Spacer(Modifier.width(8.dp))
                    NumberInput(
                        value = manualDurationMinutes,
                        onValueChange = onDurationMinutesChange,
                        modifier = Modifier.width(56.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.manual_silence_minutes_label),
                        fontSize = 13.sp,
                        color = TextMuted
                    )
                }
            }

            Button(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(56.dp)
                    .testTag(TestTags.MANUAL_SILENCE_BUTTON),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = bgColor)
            ) {
                Text(
                    text = when {
                        isSilent && hasDnd -> stringResource(R.string.btn_unsilence)
                        else -> stringResource(R.string.btn_silence)
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ManualSilenceModeChip(
    text: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) GreenPrimary.copy(alpha = 0.14f) else GoldLight.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (selected) GreenPrimaryDark else TextDark,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

// Helper functions

private fun hasExactAlarmPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }
    return true
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun initDelayFixedHour(context: Context, prayer: Prayer, prayerTime: PrayerTime?): Int {
    val h = PrefsManager.getDelayFixedHour(context, prayer)
    if (h >= 0) return h
    if (prayerTime != null) {
        PrefsManager.setDelayFixedTime(context, prayer, prayerTime.hour, prayerTime.minute)
        return prayerTime.hour
    }
    return 12
}

private fun initDelayFixedMinute(context: Context, prayer: Prayer, prayerTime: PrayerTime?): Int {
    val m = PrefsManager.getDelayFixedMinute(context, prayer)
    if (m >= 0) return m
    if (prayerTime != null) return prayerTime.minute
    return 0
}

private fun initFixedHour(context: Context, prayer: Prayer, prayerTime: PrayerTime?): Int {
    val h = PrefsManager.getFixedTimeHour(context, prayer)
    if (h >= 0) return h
    if (prayerTime != null) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, prayerTime.hour)
            set(Calendar.MINUTE, prayerTime.minute)
            add(Calendar.MINUTE, PrefsManager.getAfterMinutes(context, prayer))
        }
        val fh = cal.get(Calendar.HOUR_OF_DAY)
        val fm = cal.get(Calendar.MINUTE)
        PrefsManager.setFixedTime(context, prayer, fh, fm)
        return fh
    }
    return 12
}

private fun initFixedMinute(context: Context, prayer: Prayer, prayerTime: PrayerTime?): Int {
    val m = PrefsManager.getFixedTimeMinute(context, prayer)
    if (m >= 0) return m
    if (prayerTime != null) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, prayerTime.hour)
            set(Calendar.MINUTE, prayerTime.minute)
            add(Calendar.MINUTE, PrefsManager.getAfterMinutes(context, prayer))
        }
        return cal.get(Calendar.MINUTE)
    }
    return 0
}

private fun resolveManualDurationMinutes(value: String, fallback: Int): Int {
    return value.toIntOrNull()?.coerceAtLeast(1) ?: fallback.coerceAtLeast(1)
}

private fun resolveManualTotalMinutes(hours: String, minutes: String, fallback: Int): Int {
    val h = hours.toIntOrNull() ?: 0
    val m = minutes.toIntOrNull() ?: 0
    val total = h * 60 + m
    return if (total > 0) total else fallback.coerceAtLeast(1)
}

private fun formatDurationText(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> "${h} س و ${m} د"
        h > 0 -> "${h} س"
        else -> "${m} د"
    }
}

private fun formatTimeOfDay(targetTimeInMillis: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = targetTimeInMillis }
    return String.format(
        Locale.forLanguageTag("ar-TN"),
        "%02d:%02d",
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE)
    )
}
