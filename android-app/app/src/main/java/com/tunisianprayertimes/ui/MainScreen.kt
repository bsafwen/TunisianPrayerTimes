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
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.tunisianprayertimes.ClockTime
import com.tunisianprayertimes.DelayMode
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.Delegation
import com.tunisianprayertimes.DelegationLocationResult
import com.tunisianprayertimes.DelegationLocator
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.GouvernoratRepository
import com.tunisianprayertimes.ManualSilenceMode
import com.tunisianprayertimes.ManualSilenceScheduler
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerRelativeOffset
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.SilenceAlarmComputer
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.RamadanDetector
import com.tunisianprayertimes.RamadanOverrideChecker
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.ScheduleRefreshCoordinator
import com.tunisianprayertimes.SilenceMode
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.SilenceStatus
import com.tunisianprayertimes.WAKE_SUPPORTED_PRAYERS
import com.tunisianprayertimes.WakeMainAlarmConfig
import com.tunisianprayertimes.WakeMainAlarmMode
import com.tunisianprayertimes.WakePlaybackOptions
import com.tunisianprayertimes.formatArabicMinutes
import com.tunisianprayertimes.wake.PrayerWakeRepository
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import com.tunisianprayertimes.wake.AwakeCheckService
import java.time.LocalDate
import java.util.Date
import com.tunisianprayertimes.WakeAlarmComputer
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
import com.tunisianprayertimes.ui.theme.HeaderStart
import com.tunisianprayertimes.ui.theme.NextPrayerBg
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.RamadanBg
import com.tunisianprayertimes.ui.theme.SilenceRed
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class MainDestination(val labelRes: Int, val iconRes: Int) {
    Today(R.string.main_tab_today, R.drawable.ic_tab_today),
    Alarms(R.string.main_tab_alarms, R.drawable.ic_tab_alarms),
    Qibla(R.string.main_tab_qibla, R.drawable.ic_tab_qibla),
}

private enum class WakeQuickPreset {
    PRAYER_RELATIVE,
    FIXED_TIME,
    TIMER,
}

private const val DEFAULT_PRAYER_OFFSET_MINUTES = 20
private const val DEFAULT_TIMER_MINUTES = 15

private fun MainDestination.analyticsName(): String = when (this) {
    MainDestination.Today -> "prayers"
    MainDestination.Alarms -> "alarms"
    MainDestination.Qibla -> "qibla"
}

private fun MainDestination.testTag(): String = when (this) {
    MainDestination.Today -> TestTags.MAIN_TAB_TODAY
    MainDestination.Alarms -> TestTags.MAIN_TAB_ALARMS
    MainDestination.Qibla -> TestTags.MAIN_TAB_QIBLA
}

private fun WakeQuickPreset.testTag(): String = when (this) {
    WakeQuickPreset.PRAYER_RELATIVE -> TestTags.WAKE_QUICK_PRESET_PRAYER_RELATIVE
    WakeQuickPreset.FIXED_TIME -> TestTags.WAKE_QUICK_PRESET_FIXED_TIME
    WakeQuickPreset.TIMER -> TestTags.WAKE_QUICK_PRESET_TIMER
}

private data class NextPrayerCountdownInfo(
    val prayer: Prayer,
    val hour: Int,
    val minute: Int,
    val triggerAtMillis: Long,
    val isTomorrow: Boolean,
)

@Composable
fun MainScreen(
    activity: androidx.appcompat.app.AppCompatActivity,
    requestedDestination: String? = null,
    requestedDestinationSequence: Int = 0,
) {
    val context = LocalContext.current
    val notificationManager = remember { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    val schedulerScope = rememberCoroutineScope()

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
    val hasNotifications = remember(refreshTick) { hasPostNotificationsPermission(context) }
    val hasFullScreenIntent = remember(refreshTick) { hasFullScreenIntentPermission(context) }
    val hasBattery = remember(refreshTick) { isIgnoringBatteryOptimizations(context) }
    val hasPhoneState = remember(refreshTick) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }
    val phoneStatePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AnalyticsTracker.permissionStepResult(
            context = context,
            permissionType = "phone_state",
            result = if (granted) "granted" else "denied",
            entryPoint = "main_banner",
        )
        refreshTick++
    }

    fun ensureCallTrackingPermission() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            AnalyticsTracker.permissionStepResult(
                context = context,
                permissionType = "phone_state",
                result = "request_opened",
                entryPoint = "auto_silence",
            )
            phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    var autoSilenceEnabled by rememberSaveable { mutableStateOf(PrefsManager.isEnabled(context)) }
    var callEndVibrationEnabled by rememberSaveable {
        mutableStateOf(PrefsManager.isCallEndVibrationEnabled(context))
    }
    var autoLocationEnabled by rememberSaveable {
        mutableStateOf(PrefsManager.isAutoLocationUpdateEnabled(context))
    }
    var delegationId by rememberSaveable { mutableIntStateOf(PrefsManager.getDelegationId(context)) }
    var manualSilenceMode by rememberSaveable { mutableStateOf(PrefsManager.getManualSilenceMode(context)) }
    var manualTargetPrayer by rememberSaveable {
        mutableStateOf(
            if (PrefsManager.getManualSilenceMode(context) == ManualSilenceMode.UNTIL_PRAYER) {
                PrefsManager.getManualSilenceTargetPrayer(context)
            } else {
                resolveUpcomingManualSilencePrayer(context, delegationId)
                    ?: PrefsManager.getManualSilenceTargetPrayer(context)
            }
        )
    }
    var manualDurationHours by rememberSaveable {
        mutableStateOf((PrefsManager.getManualSilenceDurationMinutes(context) / 60).toString())
    }
    var manualDurationMinutes by rememberSaveable {
        mutableStateOf((PrefsManager.getManualSilenceDurationMinutes(context) % 60).toString())
    }
    var autoSilenceActive by remember { mutableStateOf(PrefsManager.isAutoSilenceActive(context)) }
    var manualSilenceActive by remember { mutableStateOf(PrefsManager.isManualSilenceActive(context)) }
    var appControlledSilenceActive by remember { mutableStateOf(SilenceStatus.isAppControlledSilenceActive(context)) }
    var manualSilenceEndsAtMillis by remember {
        mutableLongStateOf(PrefsManager.getManualSilenceEndsAtMillis(context))
    }

    fun refreshSilenceState() {
        autoSilenceActive = PrefsManager.isAutoSilenceActive(context)
        manualSilenceActive = PrefsManager.isManualSilenceActive(context)
        appControlledSilenceActive = SilenceStatus.isAppControlledSilenceActive(context)
        manualSilenceEndsAtMillis = PrefsManager.getManualSilenceEndsAtMillis(context)
    }

    suspend fun syncWakeScheduling() {
        ScheduleRefreshCoordinator.syncWake(context)
    }

    // Single resume-sync effect — merges all refreshTick observers into one
    // so execution order is deterministic and state is read only once.
    LaunchedEffect(refreshTick) {
        ManualSilenceScheduler.syncExpiredTimer(context)

        // Auto-update location from last known GPS fix
        if (PrefsManager.isAutoLocationUpdateEnabled(context) &&
            DelegationLocator.hasLocationPermission(context)
        ) {
            if (DelegationLocator.updateDelegationFromLastLocation(context)) {
                delegationId = PrefsManager.getDelegationId(context)
            }
        }

        ScheduleRefreshCoordinator.syncSilence(
            context = context,
            skipWhileManualSilence = true,
        )
        syncWakeScheduling()

        refreshSilenceState()
    }

    // Start Ramadan override polling on first composition
    LaunchedEffect(Unit) {
        AnalyticsTracker.installRamadanOverrideReporter(context)
        RamadanOverrideChecker.startPollingIfNeeded()
    }

    LaunchedEffect(hasDnd, hasAlarm, hasBattery) {
        AnalyticsTracker.activationCompletedIfNeeded(
            context = context,
            dndGranted = hasDnd,
            exactAlarmGranted = hasAlarm,
            batteryExempt = hasBattery,
        )
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
        ScheduleRefreshCoordinator.syncSilence(context)
        schedulerScope.launch {
            syncWakeScheduling()
            refreshTick++
        }
        // Sync UI after rescheduling — scheduleAll may have silenced the phone
        // if the current time now falls inside a silence window.
        refreshSilenceState()
    }

    var editingWakeAlarm by remember { mutableStateOf<PrayerWakeConfig?>(null) }
    var quickAddVisible by rememberSaveable { mutableStateOf(false) }

    fun createWakeAlarm(preset: WakeQuickPreset) {
        quickAddVisible = false
        editingWakeAlarm = newWakeAlarmConfig(preset)
    }

    val mainDestinations = remember { MainDestination.values().toList() }
    var selectedDestinationIndex by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(requestedDestination, requestedDestinationSequence) {
        val requestedIndex = when (requestedDestination) {
            MainTabNavigation.DESTINATION_PRAYERS -> mainDestinations.indexOf(MainDestination.Today)
            MainTabNavigation.DESTINATION_ALARMS -> mainDestinations.indexOf(MainDestination.Alarms)
            MainTabNavigation.DESTINATION_QIBLA -> mainDestinations.indexOf(MainDestination.Qibla)
            else -> -1
        }
        if (requestedIndex in mainDestinations.indices) {
            selectedDestinationIndex = requestedIndex
        }
    }
    val currentDestinationIndex = if (selectedDestinationIndex in mainDestinations.indices) {
        selectedDestinationIndex
    } else {
        0
    }
    val selectedDestination = mainDestinations[currentDestinationIndex]

    LaunchedEffect(selectedDestination) {
        AnalyticsTracker.tabViewed(context, selectedDestination.analyticsName())
    }

    fun requestFullScreenIntentIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                AnalyticsTracker.permissionStepResult(
                    context = context,
                    permissionType = "full_screen_intent",
                    result = "request_opened",
                    entryPoint = "wake_alarm",
                )
                Toast.makeText(context, context.getString(R.string.wake_alarm_full_screen_permission), Toast.LENGTH_LONG).show()
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            }
        }
    }

    var notificationPermissionPromptRequested by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AnalyticsTracker.permissionStepResult(
            context = context,
            permissionType = "notifications",
            result = if (granted) "granted" else "denied",
            entryPoint = "wake_alarm",
        )
        if (granted) {
            notificationPermissionPromptRequested = false
        }
        refreshTick++
        requestFullScreenIntentIfNeeded()
    }

    fun requestWakeNotificationPermission() {
        if (hasPostNotificationsPermission(context)) {
            refreshTick++
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionPromptRequested = true
            AnalyticsTracker.permissionStepResult(
                context = context,
                permissionType = "notifications",
                result = "request_opened",
                entryPoint = "wake_alarm",
            )
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val wakeRepository = remember(context) { com.tunisianprayertimes.wake.PrayerWakeRepository(context) }
    val wakeAlarmsForPermission: List<PrayerWakeConfig>? by wakeRepository.wakeAlarms.collectAsState(initial = null)
    val hasEnabledFutureWakeAlarm = wakeAlarmsForPermission
        ?.let { wakeAlarms -> WakeAlarmScheduler.schedulingSnapshot(context, wakeAlarms).enabledFutureAlarmCount > 0 }
        ?: false
    val mainScope = rememberCoroutineScope()
    val awakeCheckRunning by AwakeCheckService.isRunning.collectAsState()

    LaunchedEffect(selectedDestination, hasNotifications, hasEnabledFutureWakeAlarm, notificationPermissionPromptRequested) {
        if (selectedDestination == MainDestination.Alarms &&
            hasEnabledFutureWakeAlarm &&
            !hasNotifications &&
            !notificationPermissionPromptRequested
        ) {
            requestWakeNotificationPermission()
        }
    }

    if (quickAddVisible) {
        WakeQuickAddSheet(
            onDismiss = { quickAddVisible = false },
            onPresetSelected = ::createWakeAlarm,
        )
    }
    val showPermissionBanner = !hasDnd || !hasAlarm || !hasPhoneState
    val showBatteryBanner = !hasBattery

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgCream)
                .padding(bottom = 76.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            IslamicHeader()

            Image(
                painter = painterResource(R.drawable.islamic_border),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                contentScale = ContentScale.FillBounds
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                when (selectedDestination) {
                    MainDestination.Today -> {
                        TodayNextPrayerCard(
                            delegationId = delegationId,
                            isAppSilenced = appControlledSilenceActive,
                            hasDnd = hasDnd,
                        )

                        AnimatedVisibility(
                            visible = showPermissionBanner,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            PermissionBanner(
                                hasDnd = hasDnd,
                                hasAlarm = hasAlarm,
                                hasPhoneState = hasPhoneState,
                                onRequestPhoneState = {
                                    AnalyticsTracker.permissionStepResult(
                                        context = context,
                                        permissionType = "phone_state",
                                        result = "request_opened",
                                        entryPoint = "main_banner",
                                    )
                                    phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                                },
                                context = context
                            )
                        }

                        AnimatedVisibility(
                            visible = showBatteryBanner,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            BatteryBanner(context = context)
                        }

                        LocationPickerCard(
                            delegationId = delegationId,
                            onDelegationSelected = { delegation ->
                                delegationId = delegation.id
                                PrefsManager.setDelegationId(context, delegation.id)
                                rescheduleIfEnabled()
                            },
                            onOutsideTunisia = {
                                autoSilenceEnabled = false
                                PrefsManager.setEnabled(context, false)
                            }
                        )

                        PrayerSettingsCard(
                            delegationId = delegationId,
                            activity = activity,
                            onConfigChanged = { rescheduleIfEnabled() }
                        )

                        AutoSilenceCard(
                            enabled = autoSilenceEnabled,
                            onToggle = { enabled ->
                                autoSilenceEnabled = enabled
                                PrefsManager.setEnabled(context, enabled)
                                AnalyticsTracker.autoSilenceStateChanged(context, enabled)
                                if (enabled) {
                                    if (hasDnd && hasAlarm) {
                                        ensureCallTrackingPermission()
                                    }
                                    ScheduleRefreshCoordinator.syncSilence(context)
                                    Toast.makeText(context, context.getString(R.string.toast_auto_enabled), Toast.LENGTH_SHORT).show()
                                } else {
                                    ScheduleRefreshCoordinator.syncSilence(context)
                                    Toast.makeText(context, context.getString(R.string.toast_auto_disabled), Toast.LENGTH_SHORT).show()
                                }
                                refreshSilenceState()
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
                            enabled = autoLocationEnabled,
                            onToggle = { enabled ->
                                autoLocationEnabled = enabled
                                PrefsManager.setAutoLocationUpdateEnabled(context, enabled)
                            }
                        )

                        if (RamadanDetector.isRamadan()) {
                            RamadanBadge()
                        }

                        ManualSilenceButton(
                            hasDnd = hasDnd,
                            manualSilenceMode = manualSilenceMode,
                            manualTargetPrayer = manualTargetPrayer,
                            manualDurationHours = manualDurationHours,
                            manualDurationMinutes = manualDurationMinutes,
                            manualSilenceActive = manualSilenceActive,
                            autoSilenceActive = autoSilenceActive,
                            manualSilenceEndsAtMillis = manualSilenceEndsAtMillis,
                            onModeChange = { mode ->
                                manualSilenceMode = mode
                                PrefsManager.setManualSilenceMode(context, mode)
                                if (mode == ManualSilenceMode.UNTIL_PRAYER) {
                                    val upcomingPrayer = resolveUpcomingManualSilencePrayer(context, delegationId) ?: manualTargetPrayer
                                    manualTargetPrayer = upcomingPrayer
                                    PrefsManager.setManualSilenceTargetPrayer(context, upcomingPrayer)
                                }
                            },
                            onTargetPrayerChange = { prayer ->
                                manualTargetPrayer = prayer
                                PrefsManager.setManualSilenceTargetPrayer(context, prayer)
                            },
                            onDurationHoursChange = { value ->
                                manualDurationHours = value
                                val totalMinutes = (value.toIntOrNull() ?: 0) * 60 + (manualDurationMinutes.toIntOrNull() ?: 0)
                                if (totalMinutes > 0) {
                                    PrefsManager.setManualSilenceDurationMinutes(context, totalMinutes)
                                }
                            },
                            onDurationMinutesChange = { value ->
                                manualDurationMinutes = value
                                val totalMinutes = (manualDurationHours.toIntOrNull() ?: 0) * 60 + (value.toIntOrNull() ?: 0)
                                if (totalMinutes > 0) {
                                    PrefsManager.setManualSilenceDurationMinutes(context, totalMinutes)
                                }
                            },
                            onClick = {
                                if (!notificationManager.isNotificationPolicyAccessGranted) {
                                    Toast.makeText(context, context.getString(R.string.toast_dnd_permission), Toast.LENGTH_SHORT).show()
                                    return@ManualSilenceButton
                                }
                                if (manualSilenceActive || autoSilenceActive) {
                                    if (manualSilenceActive) {
                                        SilenceModeController.setManualNormal(context)
                                    } else {
                                        SilenceModeController.disableAutoSilence(context)
                                        val dismissedPrayer = SilenceScheduler.currentSilenceWindowPrayer(context)
                                        if (dismissedPrayer != null) {
                                            PrefsManager.setAutoSilenceDismissed(
                                                context, System.currentTimeMillis(), dismissedPrayer
                                            )
                                        }
                                    }
                                    SilenceModeController.notifyIfMissedCallDuringSilence(context)
                                    refreshSilenceState()
                                    Toast.makeText(context, context.getString(R.string.toast_normal_restored), Toast.LENGTH_SHORT).show()
                                } else {
                                    val totalMinutes = resolveManualTotalMinutes(
                                        manualDurationHours, manualDurationMinutes,
                                        PrefsManager.getManualSilenceDurationMinutes(context)
                                    )
                                    if (manualSilenceMode == ManualSilenceMode.DURATION && totalMinutes <= 0) {
                                        Toast.makeText(context, context.getString(R.string.error_manual_duration_required), Toast.LENGTH_SHORT).show()
                                        return@ManualSilenceButton
                                    }
                                    val prayerEndsAtMillis = if (manualSilenceMode == ManualSilenceMode.UNTIL_PRAYER) {
                                        resolveManualSilencePrayerEndMillis(context, delegationId, manualTargetPrayer)
                                    } else {
                                        null
                                    }
                                    if (manualSilenceMode == ManualSilenceMode.UNTIL_PRAYER && prayerEndsAtMillis == null) {
                                        Toast.makeText(context, context.getString(R.string.error_manual_prayer_time_unavailable), Toast.LENGTH_SHORT).show()
                                        return@ManualSilenceButton
                                    }

                                    ensureCallTrackingPermission()
                                    SilenceModeController.setManualSilent(context)
                                    when (manualSilenceMode) {
                                        ManualSilenceMode.DURATION -> {
                                            manualSilenceEndsAtMillis = ManualSilenceScheduler.schedule(context, totalMinutes)
                                            AnalyticsTracker.manualSilenceStarted(context, manualSilenceMode, totalMinutes)
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.toast_silent_enabled_timed, formatDurationText(totalMinutes)),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        ManualSilenceMode.UNTIL_PRAYER -> {
                                            val endsAtMillis = requireNotNull(prayerEndsAtMillis)
                                            ManualSilenceScheduler.scheduleAt(context, endsAtMillis)
                                            manualSilenceEndsAtMillis = endsAtMillis
                                            AnalyticsTracker.manualSilenceStarted(context, manualSilenceMode, null)
                                            Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.toast_silent_enabled_until_prayer,
                                                    prayerName(context, manualTargetPrayer)
                                                ),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        ManualSilenceMode.UNTIL_STOPPED -> {
                                            ManualSilenceScheduler.cancel(context)
                                            manualSilenceEndsAtMillis = -1L
                                            AnalyticsTracker.manualSilenceStarted(context, manualSilenceMode, null)
                                            Toast.makeText(context, context.getString(R.string.toast_silent_enabled), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    refreshSilenceState()
                                }
                            }
                        )

                        Text(
                            text = stringResource(R.string.info_text),
                            fontSize = 12.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(TestTags.INFO_TEXT)
                                .padding(top = 12.dp)
                        )
                    }

                    MainDestination.Alarms -> {
                        WakeAlarmCard(
                            wakeAlarms = wakeAlarmsForPermission,
                            delegationId = delegationId,
                            activity = activity,
                            awakeCheckRunning = awakeCheckRunning,
                            onConfirmAwake = {
                                AwakeCheckService.confirmAwake(context)
                            },
                            onConfigChanged = { rescheduleIfEnabled() },
                            onPresetSelected = ::createWakeAlarm,
                            onEditAlarm = { config -> editingWakeAlarm = config },
                        )
                    }

                    MainDestination.Qibla -> {
                        QiblaCard()
                    }
                }

            }
        }

        Spacer(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(HeaderStart, HeaderEnd),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    )
                )
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = Color.White,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    mainDestinations.forEachIndexed { index, destination ->
                        val selected = currentDestinationIndex == index
                        val label = stringResource(destination.labelRes)
                        val tabIndicatorColor by animateColorAsState(
                            targetValue = if (selected) GreenPrimary else Color.Transparent,
                            label = "bottomTabIndicator"
                        )
                        val tabContentColor by animateColorAsState(
                            targetValue = if (selected) GreenPrimaryDark else TextMuted,
                            label = "bottomTabContent"
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .padding(horizontal = 4.dp)
                                .testTag(destination.testTag())
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selectedDestinationIndex = index },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(26.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(tabIndicatorColor)
                            )

                            Spacer(Modifier.height(5.dp))

                            Icon(
                                painter = painterResource(destination.iconRes),
                                contentDescription = label,
                                tint = tabContentColor,
                                modifier = Modifier.size(18.dp)
                            )

                            Spacer(Modifier.height(3.dp))

                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                color = tabContentColor,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        if (selectedDestination == MainDestination.Alarms && wakeAlarmsForPermission?.isNotEmpty() == true && editingWakeAlarm == null) {
            WakeAlarmFloatingAddButton(
                onClick = { quickAddVisible = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 24.dp, bottom = 92.dp),
            )
        }

        // Full-screen wake alarm editor overlay
        editingWakeAlarm?.let { wakeAlarm ->
            val wakeAlarms by wakeRepository.wakeAlarms.collectAsState(initial = emptyList())
            val isPersistedAlarm = wakeAlarms.any { existing -> existing.id == wakeAlarm.id }
            WakeEditorSheet(
                activity = activity,
                delegationId = delegationId,
                initialConfig = wakeAlarm,
                isNewAlarm = !isPersistedAlarm,
                onDismissRequest = { editingWakeAlarm = null },
                onSave = { config ->
                    mainScope.launch {
                        wakeRepository.saveWakeConfig(config)
                        AnalyticsTracker.wakeAlarmSaved(context, config)
                        editingWakeAlarm = null
                        rescheduleIfEnabled()
                    }
                    if (config.enabled &&
                        config.mainAlarm.mode == WakeMainAlarmMode.FROM_NOW &&
                        config.silenceUntilAlarm
                    ) {
                        WakeAlarmScheduler.activateSilenceUntilAlarm(context, config.id)
                        refreshSilenceState()
                    } else if (com.tunisianprayertimes.wake.WakeAlarmScheduler.isSilencedAlarm(context, config.id)) {
                        WakeAlarmScheduler.removeSilenceUntilAlarm(context, config.id)
                        refreshSilenceState()
                    }
                    if (config.enabled) {
                        if (!hasPostNotificationsPermission(context)) {
                            requestWakeNotificationPermission()
                        } else {
                            requestFullScreenIntentIfNeeded()
                        }
                    }
                },
                onDelete = if (isPersistedAlarm) {
                    {
                        WakeAlarmScheduler.removeSilenceUntilAlarm(context, wakeAlarm.id)
                        mainScope.launch {
                            wakeRepository.deleteWakeAlarm(wakeAlarm.id)
                            editingWakeAlarm = null
                            rescheduleIfEnabled()
                        }
                    }
                } else {
                    null
                },
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
            .statusBarsPadding()
            .padding(top = 2.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Bismillah
        Image(
            painter = painterResource(R.drawable.basmalah),
            contentDescription = "بِسْمِ اللهِ الرَّحْمَٰنِ الرَّحِيمِ",
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(horizontal = 42.dp),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(Gold)
        )

        Image(
            painter = painterResource(R.drawable.mosque_silhouette),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(Color(0xB3FFFFFF))
        )

        Text(
            text = stringResource(R.string.subtitle),
            fontSize = 13.sp,
            color = Color(0xFFB2DFDB),
            textAlign = TextAlign.Center
        )

        Text(
            text = stringResource(R.string.source),
            fontSize = 10.sp,
            color = Color(0x80B2DFDB),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PhoneStatusNotice(
    isAppSilenced: Boolean,
    hasDnd: Boolean,
    modifier: Modifier = Modifier,
) {
    if (hasDnd && !isAppSilenced) return

    val accentColor by animateColorAsState(
        targetValue = if (!hasDnd) Color(0xFFFFD166) else Color(0xFFFFCDD2),
        label = "phoneStatusAccent"
    )
    val statusText = when {
        !hasDnd -> stringResource(R.string.phone_status_permission_short)
        else -> stringResource(R.string.phone_status_silent_short)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(50))
                .background(accentColor)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = statusText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = accentColor,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(maxFontSize = 11.sp),
        )
    }
}

@Composable
private fun TomorrowMarker() {
    Text(
        text = stringResource(R.string.next_prayer_countdown_tomorrow),
        fontSize = 11.sp,
        color = GreenPrimaryDark,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.82f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun AwakeCheckBanner(onConfirm: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "⏰",
                fontSize = 32.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.awake_check_confirm_prompt),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE65100),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)
            ) {
                Text(
                    text = stringResource(R.string.awake_check_confirm),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
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
                    AnalyticsTracker.permissionStepResult(
                        context = context,
                        permissionType = "dnd",
                        result = "request_opened",
                        entryPoint = "main_banner",
                    )
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                } else if (!hasAlarm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    AnalyticsTracker.permissionStepResult(
                        context = context,
                        permissionType = "exact_alarm",
                        result = "request_opened",
                        entryPoint = "main_banner",
                    )
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
                AnalyticsTracker.permissionStepResult(
                    context = context,
                    permissionType = "battery_optimization",
                    result = "request_opened",
                    entryPoint = "main_banner",
                )
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
    onDelegationSelected: (Delegation) -> Unit,
    onOutsideTunisia: () -> Unit = {}
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
                    AnalyticsTracker.markDelegationSource(context, "gps_success")
                    onDelegationSelected(result.delegation)
                }
                DelegationLocationResult.PermissionDenied -> {
                    AnalyticsTracker.permissionStepResult(
                        context = context,
                        permissionType = "location",
                        result = "denied",
                        entryPoint = "delegation_picker",
                    )
                    Toast.makeText(
                        context,
                        context.getString(R.string.location_permission_denied),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                DelegationLocationResult.LocationUnavailable -> {
                    AnalyticsTracker.permissionStepResult(
                        context = context,
                        permissionType = "location",
                        result = "location_unavailable",
                        entryPoint = "delegation_picker",
                    )
                    Toast.makeText(
                        context,
                        context.getString(R.string.location_lookup_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                DelegationLocationResult.NoDelegationFound -> {
                    AnalyticsTracker.permissionStepResult(
                        context = context,
                        permissionType = "location",
                        result = "no_delegation_found",
                        entryPoint = "delegation_picker",
                    )
                    Toast.makeText(
                        context,
                        context.getString(R.string.location_no_match),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                DelegationLocationResult.OutsideTunisia -> {
                    AnalyticsTracker.permissionStepResult(
                        context = context,
                        permissionType = "location",
                        result = "outside_tunisia",
                        entryPoint = "delegation_picker",
                    )
                    SilenceScheduler.cancelAll(context)
                    PrefsManager.setDisabledOutsideTunisia(context, true)
                    onOutsideTunisia()
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
            AnalyticsTracker.permissionStepResult(
                context = context,
                permissionType = "location",
                result = "granted",
                entryPoint = "delegation_picker",
            )
            startLocationLookup()
        } else {
            AnalyticsTracker.permissionStepResult(
                context = context,
                permissionType = "location",
                result = "denied",
                entryPoint = "delegation_picker",
            )
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
                                AnalyticsTracker.permissionStepResult(
                                    context = context,
                                    permissionType = "location",
                                    result = "request_opened",
                                    entryPoint = "delegation_picker",
                                )
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
                AnalyticsTracker.markDelegationSource(context, "manual")
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
private fun TodayNextPrayerCard(
    delegationId: Int,
    isAppSilenced: Boolean,
    hasDnd: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val currentCalendar = remember(currentTimeMillis) {
        Calendar.getInstance().apply { timeInMillis = currentTimeMillis }
    }
    val currentDayMillis = remember(currentTimeMillis) { startOfDayMillis(currentTimeMillis) }
    val todayTimes = remember(delegationId, currentDayMillis) {
        try {
            PrayerTimesRepository.loadDayPrayerTimes(
                context = context,
                delegationId = delegationId,
                year = currentCalendar.get(Calendar.YEAR),
                month = currentCalendar.get(Calendar.MONTH) + 1,
                day = currentCalendar.get(Calendar.DAY_OF_MONTH),
            )
        } catch (e: Exception) {
            null
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentTimeMillis = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val countdown = remember(delegationId, todayTimes, currentTimeMillis) {
        resolveNextPrayerCountdown(
            context = context,
            delegationId = delegationId,
            todayTimes = todayTimes,
            nowMillis = currentTimeMillis,
            jomoaaHour = PrefsManager.getJomoaaTimeHour(context),
            jomoaaMinute = PrefsManager.getJomoaaTimeMinute(context),
        )
    }

    LaunchedEffect(countdown?.triggerAtMillis) {
        while (true) {
            val now = System.currentTimeMillis()
            delay(nextCountdownRefreshDelayMillis(countdown?.triggerAtMillis, now))
            currentTimeMillis = System.currentTimeMillis()
        }
    }

    if (countdown != null) {
        NextPrayerHeroCard(
            countdown = countdown,
            prayerName = prayerName(context, countdown.prayer),
            currentTimeMillis = currentTimeMillis,
            isAppSilenced = isAppSilenced,
            hasDnd = hasDnd,
        )
    }
}

@Composable
private fun PrayerSettingsCard(
    delegationId: Int,
    activity: androidx.appcompat.app.AppCompatActivity,
    onConfigChanged: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val currentCalendar = remember(currentTimeMillis) {
        Calendar.getInstance().apply { timeInMillis = currentTimeMillis }
    }
    val currentDayMillis = remember(currentTimeMillis) { startOfDayMillis(currentTimeMillis) }
    var selectedDate by rememberSaveable { mutableLongStateOf(currentDayMillis) }
    var lastCurrentDayMillis by remember { mutableLongStateOf(currentDayMillis) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentTimeMillis = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            val nextMinuteMillis = ((now / 60_000L) + 1L) * 60_000L
            delay((nextMinuteMillis - now).coerceAtLeast(1L))
            currentTimeMillis = System.currentTimeMillis()
        }
    }

    LaunchedEffect(currentDayMillis) {
        // After process death, selectedDate is restored from rememberSaveable
        // but lastCurrentDayMillis is re-initialized to currentDayMillis.
        // Detect stale selectedDate that predates today and auto-advance it.
        if (currentDayMillis == lastCurrentDayMillis) {
            if (!isSameCalendarDay(selectedDate, currentDayMillis) &&
                selectedDate < currentDayMillis
            ) {
                selectedDate = currentDayMillis
            }
            return@LaunchedEffect
        }
        if (isSameCalendarDay(selectedDate, lastCurrentDayMillis)) {
            selectedDate = currentDayMillis
        }
        lastCurrentDayMillis = currentDayMillis
    }

    val selectedCal = remember(selectedDate) {
        Calendar.getInstance().apply { timeInMillis = selectedDate }
    }
    val isToday = remember(selectedDate, currentDayMillis) {
        isSameCalendarDay(selectedDate, currentDayMillis)
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

    val isAidFitr = remember(selectedDate, displayTimes, currentTimeMillis) {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDate }
        val gregDate = LocalDate.of(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        RamadanOverrideChecker.shouldShowEidFitrPrayer(
            date = gregDate,
            nowHour = currentCalendar.get(Calendar.HOUR_OF_DAY),
            nowMinute = currentCalendar.get(Calendar.MINUTE),
            dhuhrHour = displayTimes?.dhuhr?.hour ?: 13,
            dhuhrMinute = displayTimes?.dhuhr?.minute ?: 0,
            isToday = isToday,
        )
    }
    val isAidAdha = remember(selectedDate, displayTimes, currentTimeMillis) {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDate }
        val gregDate = LocalDate.of(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        RamadanOverrideChecker.shouldShowEidAdhaPrayer(
            date = gregDate,
            nowHour = currentCalendar.get(Calendar.HOUR_OF_DAY),
            nowMinute = currentCalendar.get(Calendar.MINUTE),
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
    val nextPrayer = if (isToday) {
        displayTimes?.nextPrayer(
            currentCalendar.get(Calendar.HOUR_OF_DAY),
            currentCalendar.get(Calendar.MINUTE),
            currentCalendar.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY,
            PrefsManager.getJomoaaTimeHour(context),
            PrefsManager.getJomoaaTimeMinute(context)
        )
    } else null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.prayer_settings_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrayerNameColor,
                    )
                    Text(
                        text = stringResource(R.string.prayer_settings_subtitle),
                        fontSize = 12.sp,
                        color = TextMuted,
                        lineHeight = 17.sp,
                    )
                }

            // Date navigation
            DateNavigationRow(
                delegationId = delegationId,
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

            if (displayTimes != null) {
                PrayerRowHeader()
                HorizontalDivider(color = Divider, thickness = 1.dp)

                val prayers = listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)
                prayers.forEachIndexed { index, prayer ->
                    val prayerTime = displayTimes.allPrayers().find { it.prayer == prayer }
                    val nextPrayerTime = if (index < prayers.size - 1) {
                        displayTimes.allPrayers().find { it.prayer == prayers[index + 1] }
                    } else null
                    PrayerRow(
                        prayer = prayer,
                        prayerName = prayerNames[prayer] ?: prayer.name,
                        prayerTime = prayerTime,
                        nextPrayerTime = nextPrayerTime,
                        isNextPrayer = prayer == nextPrayer,
                        activity = activity,
                        onConfigChanged = onConfigChanged,
                    )
                }

                // JOMOAA row — always shown as the last row, time editable
                HorizontalDivider(color = Divider, thickness = 1.dp)
                key("jomoaa", jomoaaH, jomoaaM) {
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
                    key("aid_fitr", aidFitrH, aidFitrM) {
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
                    key("aid_adha", aidAdhaH, aidAdhaM) {
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

}

@Composable
private fun WakeAlarmCard(
    wakeAlarms: List<PrayerWakeConfig>?,
    delegationId: Int,
    activity: androidx.appcompat.app.AppCompatActivity,
    awakeCheckRunning: Boolean,
    onConfirmAwake: () -> Unit,
    onConfigChanged: () -> Unit,
    onPresetSelected: (WakeQuickPreset) -> Unit,
    onEditAlarm: (PrayerWakeConfig) -> Unit,
) {
    val context = LocalContext.current
    val wakeRepository = remember(context) { PrayerWakeRepository(context) }
    val coroutineScope = rememberCoroutineScope()
    var alarmPendingDeletion by remember { mutableStateOf<PrayerWakeConfig?>(null) }
    val loadedWakeAlarms = wakeAlarms.orEmpty()
    val nextAlarm = remember(loadedWakeAlarms, delegationId) {
        loadedWakeAlarms
            .mapNotNull { alarm ->
                nextWakeAlarmMillis(context, delegationId, alarm)?.let { triggerAtMillis ->
                    alarm to triggerAtMillis
                }
            }
            .minByOrNull { (_, triggerAtMillis) -> triggerAtMillis }
    }

    alarmPendingDeletion?.let { alarm ->
        WakeAlarmDeleteDialog(
            alarmName = wakeAlarmDisplayName(alarm),
            onDismiss = { alarmPendingDeletion = null },
            onConfirm = {
                val alarmId = alarm.id
                alarmPendingDeletion = null
                WakeAlarmScheduler.removeSilenceUntilAlarm(context, alarmId)
                coroutineScope.launch {
                    wakeRepository.deleteWakeAlarm(alarmId)
                    onConfigChanged()
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (nextAlarm != null) {
            val (alarm, triggerAtMillis) = nextAlarm
            WakeNextAlarmPanel(
                wakeConfig = alarm,
                triggerAtMillis = triggerAtMillis,
            )
        }

        AnimatedVisibility(
            visible = awakeCheckRunning,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            AwakeCheckBanner(onConfirm = onConfirmAwake)
        }

        if (wakeAlarms != null && nextAlarm == null) {
            WakeAlarmEmptyState(onPresetSelected = onPresetSelected)
        }

        if (loadedWakeAlarms.isNotEmpty()) {
            WakeAlarmListPanel(
                wakeAlarms = loadedWakeAlarms,
                delegationId = delegationId,
                onEditAlarm = onEditAlarm,
                onEnabledChange = { wakeAlarm, enabled ->
                    coroutineScope.launch {
                        val updatedAlarm = wakeAlarm.copy(enabled = enabled)
                        wakeRepository.saveWakeConfig(updatedAlarm)
                        AnalyticsTracker.wakeAlarmSaved(context, updatedAlarm)
                        onConfigChanged()
                    }
                },
                onDeleteRequest = { alarm -> alarmPendingDeletion = alarm },
            )
        }
    }
}

@Composable
private fun WakeAlarmFloatingAddButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .testTag(TestTags.WAKE_ALARM_ADD_BUTTON)
            .size(64.dp),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
        contentPadding = PaddingValues(0.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
    ) {
        Text(
            text = "+",
            fontSize = 34.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Normal,
            color = Color.White,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeQuickAddSheet(
    onDismiss: () -> Unit,
    onPresetSelected: (WakeQuickPreset) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .testTag(TestTags.WAKE_QUICK_ADD_SHEET)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.wake_quick_add_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor,
            )
            WakeQuickPreset.values().forEach { preset ->
                WakeQuickPresetRow(
                    preset = preset,
                    onClick = { onPresetSelected(preset) },
                    modifier = Modifier.testTag(preset.testTag()),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WakeQuickPresetRow(
    preset: WakeQuickPreset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recommended = preset == WakeQuickPreset.PRAYER_RELATIVE
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .background(if (recommended) GreenPrimary.copy(alpha = 0.10f) else GoldLight.copy(alpha = 0.10f))
            .border(
                BorderStroke(1.dp, if (recommended) GreenPrimary.copy(alpha = 0.40f) else Gold.copy(alpha = 0.22f)),
                shape,
            )
            .padding(horizontal = 13.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = wakeQuickPresetTitle(preset),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (recommended) GreenPrimaryDark else TextDark,
            )
            Text(
                text = wakeQuickPresetSubtitle(preset),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = if (recommended) GreenPrimaryDark.copy(alpha = 0.78f) else TextMuted,
            )
        }
        Text(
            text = "+",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (recommended) GreenPrimary else TextMuted,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun wakeQuickPresetTitle(preset: WakeQuickPreset): String = when (preset) {
    WakeQuickPreset.PRAYER_RELATIVE -> stringResource(R.string.wake_quick_add_prayer_relative_title)
    WakeQuickPreset.FIXED_TIME -> stringResource(R.string.wake_quick_add_fixed_title)
    WakeQuickPreset.TIMER -> stringResource(R.string.wake_quick_add_timer_title)
}

@Composable
private fun wakeQuickPresetSubtitle(preset: WakeQuickPreset): String = when (preset) {
    WakeQuickPreset.PRAYER_RELATIVE -> stringResource(R.string.wake_quick_add_prayer_relative_subtitle)
    WakeQuickPreset.FIXED_TIME -> stringResource(R.string.wake_quick_add_fixed_subtitle)
    WakeQuickPreset.TIMER -> stringResource(R.string.wake_quick_add_timer_subtitle)
}

private fun newWakeAlarmConfig(preset: WakeQuickPreset): PrayerWakeConfig {
    val nowMillis = System.currentTimeMillis()
    val fixedTime = roundedClockTimeAfter(minutesFromNow = 30)
    val mainAlarm = when (preset) {
        WakeQuickPreset.PRAYER_RELATIVE -> WakeMainAlarmConfig(
            mode = WakeMainAlarmMode.PRAYER_RELATIVE,
            prayerOffset = PrayerRelativeOffset(-DEFAULT_PRAYER_OFFSET_MINUTES),
        )
        WakeQuickPreset.FIXED_TIME -> WakeMainAlarmConfig(
            mode = WakeMainAlarmMode.FIXED_TIME,
            fixedTime = fixedTime,
        )
        WakeQuickPreset.TIMER -> WakeMainAlarmConfig(
            mode = WakeMainAlarmMode.FROM_NOW,
            fixedTime = fixedTime,
            oneOffOffsetMinutes = DEFAULT_TIMER_MINUTES,
            oneOffTriggerAtMillis = nowMillis + DEFAULT_TIMER_MINUTES * 60_000L,
        )
    }

    return PrayerWakeConfig(
        id = UUID.randomUUID().toString(),
        enabled = true,
        prayer = Prayer.FAJR,
        mainAlarm = mainAlarm,
        playback = WakePlaybackOptions(
            ringtone = RingtonePreset.ADHAN_MADINAH_MARWAN_QASSAS,
        ),
    )
}

private fun roundedClockTimeAfter(minutesFromNow: Int): ClockTime {
    val calendar = Calendar.getInstance().apply {
        add(Calendar.MINUTE, minutesFromNow)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val roundedMinute = ((calendar.get(Calendar.MINUTE) + 4) / 5) * 5
    if (roundedMinute >= 60) {
        calendar.add(Calendar.HOUR_OF_DAY, 1)
        calendar.set(Calendar.MINUTE, 0)
    } else {
        calendar.set(Calendar.MINUTE, roundedMinute)
    }
    return ClockTime(
        hour = calendar.get(Calendar.HOUR_OF_DAY),
        minute = calendar.get(Calendar.MINUTE),
    )
}

@Composable
private fun WakeAlarmListPanel(
    wakeAlarms: List<PrayerWakeConfig>,
    delegationId: Int,
    onEditAlarm: (PrayerWakeConfig) -> Unit,
    onEnabledChange: (PrayerWakeConfig, Boolean) -> Unit,
    onDeleteRequest: (PrayerWakeConfig) -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.WAKE_ALARM_LIST),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        wakeAlarms.forEachIndexed { index, wakeAlarm ->
            val nextMillis = remember(wakeAlarm, delegationId) {
                nextWakeAlarmMillis(context, delegationId, wakeAlarm)
            }
            WakeAlarmRow(
                alarmName = context.getString(R.string.wake_alarm_row_title, index + 1),
                wakeConfig = wakeAlarm,
                nextAlarmMillis = nextMillis,
                onClick = { onEditAlarm(wakeAlarm) },
                onEnabledChange = { enabled -> onEnabledChange(wakeAlarm, enabled) },
                onDeleteRequest = { onDeleteRequest(wakeAlarm) },
            )
        }
    }
}

@Composable
private fun WakeNextAlarmPanel(
    wakeConfig: PrayerWakeConfig,
    triggerAtMillis: Long,
) {
    val prayerName = wakeAlarmPrayerName(wakeConfig.prayer)
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(GreenPrimaryDark, GreenPrimary),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                )
            )
            .border(BorderStroke(1.dp, Gold.copy(alpha = 0.24f)), shape),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.wake_alarm_next_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.78f),
            )
            Text(
                text = formatWakeAlarmDateTime(triggerAtMillis),
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                lineHeight = 30.sp,
            )
            Text(
                text = wakeSummaryText(prayerName, wakeConfig),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.82f),
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun WakeAlarmDeleteDialog(
    alarmName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = Color.White,
        title = {
            Text(
                text = stringResource(R.string.wake_alarm_delete_title),
                color = PrayerNameColor,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.wake_alarm_delete_message, alarmName),
                color = TextDark,
                lineHeight = 20.sp,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.wake_alarm_delete_keep))
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = SilenceRed),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(text = stringResource(R.string.wake_alarm_delete_confirm))
            }
        },
    )
}

@Composable
private fun wakeAlarmPrayerName(prayer: Prayer): String = when (prayer) {
    Prayer.FAJR -> stringResource(R.string.prayer_fajr)
    Prayer.DHUHR -> stringResource(R.string.prayer_dhuhr)
    Prayer.ASR -> stringResource(R.string.prayer_asr)
    Prayer.MAGHRIB -> stringResource(R.string.prayer_maghrib)
    Prayer.ISHA -> stringResource(R.string.prayer_isha)
    Prayer.JOMOAA -> stringResource(R.string.prayer_jomoaa)
    Prayer.AID_FITR -> stringResource(R.string.prayer_aid_fitr)
    Prayer.AID_ADHA -> stringResource(R.string.prayer_aid_adha)
}

@Composable
private fun NextPrayerHeroCard(
    countdown: NextPrayerCountdownInfo,
    prayerName: String,
    currentTimeMillis: Long,
    isAppSilenced: Boolean,
    hasDnd: Boolean,
) {
    val prayerTimeText = String.format(Locale.US, "%02d:%02d", countdown.hour, countdown.minute)
    val remainingText = stringResource(
        R.string.next_prayer_countdown_remaining,
        formatCountdownRemaining(countdown.triggerAtMillis, currentTimeMillis),
    )
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.STATUS_CARD)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(GreenPrimaryDark, GreenPrimary),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                )
            )
            .border(BorderStroke(1.dp, Gold.copy(alpha = 0.24f)), shape)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.next_prayer_countdown_title),
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.78f),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )

                if (countdown.isTomorrow) {
                    TomorrowMarker()
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = prayerName,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(maxFontSize = 28.sp),
                    )
                    Text(
                        text = stringResource(R.string.next_prayer_countdown_at, prayerTimeText),
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.82f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    PhoneStatusNotice(
                        isAppSilenced = isAppSilenced,
                        hasDnd = hasDnd,
                        modifier = Modifier.align(Alignment.Start),
                    )
                }

                Text(
                    text = remainingText,
                    fontSize = 14.sp,
                    color = GreenPrimaryDark,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(maxFontSize = 14.sp),
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.90f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun WakeAlarmEmptyState(onPresetSelected: (WakeQuickPreset) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.wake_alarm_empty_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor,
            )
            Text(
                text = stringResource(R.string.wake_alarm_empty_body_modern),
                fontSize = 13.sp,
                color = TextMuted,
                lineHeight = 18.sp,
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WakeQuickPreset.values().forEach { preset ->
                    WakeQuickPresetRow(
                        preset = preset,
                        onClick = { onPresetSelected(preset) },
                        modifier = Modifier.testTag(preset.testTag()),
                    )
                }
            }
        }
    }
}

@Composable
private fun WakeAlarmFeatureChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(GoldLight.copy(alpha = 0.26f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = PrayerNameColor,
            text = text,
        )
    }
}

@Composable
private fun DateNavigationRow(
    delegationId: Int,
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
    val dateRange = remember(delegationId) { PrayerTimesRepository.getDateRange(context, delegationId) }
    val openDatePicker = {
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
        ).apply {
            dateRange?.let { (minMs, maxMs) ->
                datePicker.minDate = minMs
                datePicker.maxDate = maxMs
            }
        }.show()
    }

    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GoldLight.copy(alpha = 0.18f))
            .border(BorderStroke(1.dp, Gold.copy(alpha = 0.18f)), shape)
            .padding(horizontal = 5.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Previous day
        Text(
            text = "‹",
            fontSize = 22.sp,
            color = if (canGoBack) GreenPrimary else TextMuted.copy(alpha = 0.3f),
            modifier = Modifier
                .testTag(TestTags.DATE_PREVIOUS_BUTTON)
                .clip(RoundedCornerShape(10.dp))
                .background(if (canGoBack) Color.White.copy(alpha = 0.70f) else Color.Transparent)
                .then(if (canGoBack) Modifier.clickable { onPrevious() } else Modifier)
                .padding(horizontal = 12.dp, vertical = 1.dp)
        )

        // Date label — tap to open date picker
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .testTag(TestTags.DATE_LABEL)
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = openDatePicker)
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            Text(
                text = dateText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = GreenPrimaryDark,
                textAlign = TextAlign.Center,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(maxFontSize = 12.sp),
            )
            if (!isToday) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.date_go_back_today),
                    fontSize = 10.sp,
                    color = GreenPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .testTag(TestTags.DATE_TODAY_BUTTON)
                        .clip(RoundedCornerShape(50))
                        .background(GreenPrimary.copy(alpha = 0.1f))
                        .clickable { onDateSelected(Calendar.getInstance().timeInMillis) }
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }

        // Next day
        Text(
            text = "›",
            fontSize = 22.sp,
            color = if (canGoForward) GreenPrimary else TextMuted.copy(alpha = 0.3f),
            modifier = Modifier
                .testTag(TestTags.DATE_NEXT_BUTTON)
                .clip(RoundedCornerShape(10.dp))
                .background(if (canGoForward) Color.White.copy(alpha = 0.70f) else Color.Transparent)
                .then(if (canGoForward) Modifier.clickable { onNext() } else Modifier)
                .padding(horizontal = 12.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun PrayerRowHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(GoldLight.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 7.dp),
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
    onPrayerTimeClick: (() -> Unit)? = null,
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

    fun logSilenceConfigChange(
        currentSilenceMode: SilenceMode = silenceMode,
        currentAfterMinutes: Int = afterMinutes.toIntOrNull() ?: 0,
        currentDelayMode: DelayMode = delayMode,
    ) {
        AnalyticsTracker.silenceConfigChanged(
            context = context,
            prayer = prayer,
            mode = currentSilenceMode,
            durationMinutes = currentAfterMinutes,
            delayMode = currentDelayMode,
        )
    }

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
                        logSilenceConfigChange()
                        onConfigChanged()
                    },
                    modifier = Modifier.weight(1f),
                    allowNegative = true,
                    keyboardType = KeyboardType.Number
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
                                if (fixedH >= 0 && fixedM >= 0 && (picker.hour > fixedH || (picker.hour == fixedH && picker.minute >= fixedM))) {
                                    Toast.makeText(context, context.getString(R.string.error_start_after_end), Toast.LENGTH_SHORT).show()
                                    return@addOnPositiveButtonClickListener
                                }
                            }
                            delayFixedH = picker.hour
                            delayFixedM = picker.minute
                            PrefsManager.setDelayFixedTime(context, prayer, picker.hour, picker.minute)
                            logSilenceConfigChange()
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
                        logSilenceConfigChange(currentDelayMode = newMode)
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
                        logSilenceConfigChange(currentAfterMinutes = it.toIntOrNull() ?: 0)
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
                                if (delayFixedH >= 0 && delayFixedM >= 0 && (picker.hour < delayFixedH || (picker.hour == delayFixedH && picker.minute <= delayFixedM))) {
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
                            logSilenceConfigChange()
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
                        logSilenceConfigChange(currentSilenceMode = newMode)
                        onConfigChanged()
                    }
                    .padding(2.dp)
            )
        }
    }

        if (overlapsNextPrayer) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1.5f))
                Spacer(modifier = Modifier.weight(1.5f))
                Spacer(modifier = Modifier.weight(2.2f))
                Box(
                    modifier = Modifier
                        .weight(2.5f)
                        .testTag(TestTags.OVERLAP_WARNING)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SilenceRed.copy(alpha = 0.08f))
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.warning_overlaps_next_prayer),
                        fontSize = 10.sp,
                        color = SilenceRed,
                        textAlign = TextAlign.Center,
                        lineHeight = 13.sp,
                    )
                }
            }
        }
    } // Column
}

@Composable
private fun WakeAlarmRow(
    alarmName: String,
    wakeConfig: PrayerWakeConfig,
    nextAlarmMillis: Long?,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val enabled = wakeConfig.enabled
    val alarmDisplayName = wakeAlarmDisplayName(wakeConfig)
    val summaryText = wakeSummaryText(alarmDisplayName, wakeConfig)
    val wakeCheckChip = stringResource(R.string.wake_alarm_feature_wake_check)
    val vibrationChip = stringResource(R.string.wake_alarm_feature_vibration)
    val progressiveChip = stringResource(R.string.wake_alarm_feature_progressive)
    val subAlarmsChip = stringResource(R.string.wake_alarm_feature_subalarms, wakeConfig.subAlarms.size)
    val featureChips = buildList {
        if (wakeConfig.playback.wakeUpCheckEnabled) add(wakeCheckChip)
        if (wakeConfig.playback.vibrationOnly) add(vibrationChip)
        if (wakeConfig.playback.progressiveVolume) add(progressiveChip)
        if (wakeConfig.subAlarms.isNotEmpty()) {
            add(subAlarmsChip)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.wakeAlarmRow(wakeConfig.id))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) Color.White else GoldLight.copy(alpha = 0.16f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) GreenPrimary.copy(alpha = 0.14f) else CardBorder.copy(alpha = 0.75f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = alarmDisplayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (enabled) GreenPrimaryDark else PrayerNameColor,
                    )
                    Text(
                        text = alarmName,
                        fontSize = 11.sp,
                        color = TextMuted,
                    )
                }
                Text(
                    text = summaryText,
                    fontSize = 12.sp,
                    color = TextDark,
                    lineHeight = 16.sp,
                )
                if (nextAlarmMillis != null) {
                    Text(
                        text = stringResource(
                            R.string.wake_alarm_row_next_at,
                            formatWakeAlarmDateTime(nextAlarmMillis),
                        ),
                        fontSize = 11.sp,
                        color = if (enabled) GreenPrimaryDark else TextMuted,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                if (featureChips.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        featureChips.forEach { chip -> WakeAlarmFeatureChip(text = chip) }
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.testTag(TestTags.wakeAlarmEnabledSwitch(wakeConfig.id)),
                )

                OutlinedButton(
                    onClick = onDeleteRequest,
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, SilenceRed.copy(alpha = 0.45f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SilenceRed.copy(alpha = 0.06f),
                        contentColor = SilenceRed,
                    ),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .testTag(TestTags.wakeAlarmDeleteButton(wakeConfig.id))
                        .size(36.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.wake_alarm_delete_action),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun wakeSummaryText(
    prayerName: String,
    wakeConfig: PrayerWakeConfig,
): String {
    if (!wakeConfig.enabled) {
        return stringResource(R.string.wake_summary_disabled_body)
    }

    val mainSummary = when (wakeConfig.mainAlarm.mode) {
        WakeMainAlarmMode.FIXED_TIME -> stringResource(
            R.string.wake_summary_fixed,
            formatClockTime(
                wakeConfig.mainAlarm.fixedTime.hour,
                wakeConfig.mainAlarm.fixedTime.minute,
            ),
        )

        WakeMainAlarmMode.FROM_NOW -> stringResource(
            R.string.wake_summary_from_now,
            formatArabicMinutes(wakeConfig.mainAlarm.oneOffOffsetMinutes),
        )

        WakeMainAlarmMode.PRAYER_RELATIVE -> {
            val offset = wakeConfig.mainAlarm.prayerOffset
            if (offset.minutes < 0) {
                stringResource(
                    R.string.wake_summary_relative_before,
                    prayerName,
                    formatArabicMinutes(offset.absoluteMinutes),
                )
            } else {
                stringResource(
                    R.string.wake_summary_relative_after,
                    prayerName,
                    formatArabicMinutes(offset.absoluteMinutes),
                )
            }
        }
    }

    return if (wakeConfig.subAlarms.isEmpty()) {
        mainSummary
    } else {
        stringResource(
            R.string.wake_summary_with_subalarms,
            mainSummary,
            wakeConfig.subAlarms.size,
        )
    }
}

@Composable
private fun wakeAlarmDisplayName(wakeConfig: PrayerWakeConfig): String = when (wakeConfig.mainAlarm.mode) {
    WakeMainAlarmMode.FROM_NOW -> stringResource(R.string.wake_editor_mode_from_now)
    WakeMainAlarmMode.FIXED_TIME -> stringResource(R.string.wake_editor_mode_fixed)
    WakeMainAlarmMode.PRAYER_RELATIVE -> wakeAlarmPrayerName(wakeConfig.prayer)
}

@Composable
private fun NumberInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Number,
    enabled: Boolean = true
) {
    val focusManager = LocalFocusManager.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BasicTextField(
            value = value,
            enabled = enabled,
            onValueChange = { new ->
                // Normalize Eastern Arabic (٠-٩) and Extended Arabic-Indic (۰-۹) to 0-9
                val normalized = normalizeDigits(new)
                val filtered = if (allowNegative) {
                    // Allow optional leading '-' followed by up to 3 digits
                    val negative = normalized.startsWith("-")
                    val digits = normalized.filter { it in '0'..'9' }.take(3)
                    if (negative && digits.isNotEmpty()) "-$digits" else digits
                } else {
                    // Only allow digits, max 3 chars
                    normalized.filter { it in '0'..'9' }.take(3)
                }
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
                textAlign = TextAlign.Center,
                textDirection = TextDirection.Ltr
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (allowNegative) KeyboardType.Phone else keyboardType
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
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
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = enabled,
                    role = Role.Switch,
                    onValueChange = onToggle,
                )
                .testTag(TestTags.AUTO_SILENCE_SWITCH)
                .padding(16.dp),
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
                onCheckedChange = null,
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = enabled,
                        role = Role.Switch,
                        onValueChange = onToggle,
                    )
                    .testTag(TestTags.CALL_END_VIBRATION_SWITCH),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.call_end_vibration_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = null,
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = enabled,
                        role = Role.Switch,
                        onValueChange = onToggle,
                    )
                    .testTag(TestTags.AUTO_LOCATION_SWITCH),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.auto_location_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = null,
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
    hasDnd: Boolean,
    manualSilenceMode: ManualSilenceMode,
    manualTargetPrayer: Prayer,
    manualDurationHours: String,
    manualDurationMinutes: String,
    manualSilenceActive: Boolean,
    autoSilenceActive: Boolean,
    manualSilenceEndsAtMillis: Long,
    onModeChange: (ManualSilenceMode) -> Unit,
    onTargetPrayerChange: (Prayer) -> Unit,
    onDurationHoursChange: (String) -> Unit,
    onDurationMinutesChange: (String) -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val anySilenceActive = manualSilenceActive || autoSilenceActive
    val manualUsesDuration = manualSilenceMode == ManualSilenceMode.DURATION
    val manualUsesPrayer = manualSilenceMode == ManualSilenceMode.UNTIL_PRAYER
    val bgColor by animateColorAsState(
        targetValue = if (anySilenceActive && hasDnd) SilenceRed else GreenPrimary,
        label = "buttonColor"
    )

    val resolvedTotalMinutes = resolveManualTotalMinutes(
        manualDurationHours, manualDurationMinutes,
        PrefsManager.getManualSilenceDurationMinutes(context)
    )
    val durationText = formatDurationText(resolvedTotalMinutes)
    val targetPrayerName = manualSilencePrayerName(manualTargetPrayer)
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
                    selected = manualSilenceMode == ManualSilenceMode.UNTIL_STOPPED,
                    testTag = TestTags.MANUAL_SILENCE_MODE_UNTIL,
                    onClick = { onModeChange(ManualSilenceMode.UNTIL_STOPPED) },
                    modifier = Modifier.weight(1f)
                )
                ManualSilenceModeChip(
                    text = stringResource(R.string.manual_silence_mode_duration),
                    selected = manualSilenceMode == ManualSilenceMode.DURATION,
                    testTag = TestTags.MANUAL_SILENCE_MODE_DURATION,
                    onClick = { onModeChange(ManualSilenceMode.DURATION) },
                    modifier = Modifier.weight(1f)
                )
                ManualSilenceModeChip(
                    text = stringResource(R.string.manual_silence_mode_prayer),
                    selected = manualUsesPrayer,
                    testTag = TestTags.MANUAL_SILENCE_MODE_PRAYER,
                    onClick = { onModeChange(ManualSilenceMode.UNTIL_PRAYER) },
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

            AnimatedVisibility(
                visible = manualUsesPrayer,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.manual_silence_prayer_label),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrayerNameColor
                    )
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WAKE_SUPPORTED_PRAYERS.forEach { prayer ->
                            ManualSilencePrayerChip(
                                text = manualSilencePrayerName(prayer),
                                selected = prayer == manualTargetPrayer,
                                testTag = TestTags.manualSilenceTargetPrayer(prayer.name),
                                onClick = { onTargetPrayerChange(prayer) }
                            )
                        }
                    }
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
                        anySilenceActive && hasDnd -> stringResource(R.string.btn_unsilence)
                        manualUsesDuration -> stringResource(R.string.btn_silence_for_duration, durationText)
                        manualUsesPrayer -> stringResource(R.string.btn_silence_until_prayer, targetPrayerName)
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
            .height(74.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) GreenPrimary.copy(alpha = 0.14f) else GoldLight.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (selected) GreenPrimaryDark else TextDark,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            maxLines = 2,
            autoSize = TextAutoSize.StepBased(maxFontSize = 12.sp)
        )
    }
}

@Composable
private fun ManualSilencePrayerChip(
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
            .background(if (selected) GreenPrimary.copy(alpha = 0.14f) else Color.White)
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 12.dp, vertical = 9.dp)
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

@Composable
private fun manualSilencePrayerName(prayer: Prayer): String = when (prayer) {
    Prayer.FAJR -> stringResource(R.string.prayer_fajr)
    Prayer.DHUHR -> stringResource(R.string.prayer_dhuhr)
    Prayer.ASR -> stringResource(R.string.prayer_asr)
    Prayer.MAGHRIB -> stringResource(R.string.prayer_maghrib)
    Prayer.ISHA -> stringResource(R.string.prayer_isha)
    Prayer.JOMOAA -> stringResource(R.string.prayer_jomoaa)
    Prayer.AID_FITR -> stringResource(R.string.prayer_aid_fitr)
    Prayer.AID_ADHA -> stringResource(R.string.prayer_aid_adha)
}

private fun prayerName(context: Context, prayer: Prayer): String = when (prayer) {
    Prayer.FAJR -> context.getString(R.string.prayer_fajr)
    Prayer.DHUHR -> context.getString(R.string.prayer_dhuhr)
    Prayer.ASR -> context.getString(R.string.prayer_asr)
    Prayer.MAGHRIB -> context.getString(R.string.prayer_maghrib)
    Prayer.ISHA -> context.getString(R.string.prayer_isha)
    Prayer.JOMOAA -> context.getString(R.string.prayer_jomoaa)
    Prayer.AID_FITR -> context.getString(R.string.prayer_aid_fitr)
    Prayer.AID_ADHA -> context.getString(R.string.prayer_aid_adha)
}

private fun resolveNextPrayerCountdown(
    context: Context,
    delegationId: Int,
    todayTimes: DayPrayerTimes?,
    nowMillis: Long,
    jomoaaHour: Int,
    jomoaaMinute: Int,
): NextPrayerCountdownInfo? {
    if (todayTimes == null) return null

    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val todayPrayer = todayTimes
        .scheduledPrayers(
            isFriday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY,
            jomoaaHour = jomoaaHour,
            jomoaaMinute = jomoaaMinute,
        )
        .firstOrNull { prayerTime -> prayerTimeMillis(now, prayerTime) > nowMillis }
    if (todayPrayer != null) {
        return NextPrayerCountdownInfo(
            prayer = todayPrayer.prayer,
            hour = todayPrayer.hour,
            minute = todayPrayer.minute,
            triggerAtMillis = prayerTimeMillis(now, todayPrayer),
            isTomorrow = false,
        )
    }

    val tomorrow = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    val tomorrowPrayer = loadDayPrayerTimesWithFallback(context, delegationId, tomorrow)
        ?.scheduledPrayers(
            isFriday = tomorrow.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY,
            jomoaaHour = jomoaaHour,
            jomoaaMinute = jomoaaMinute,
        )
        ?.firstOrNull()
        ?: return null

    return NextPrayerCountdownInfo(
        prayer = tomorrowPrayer.prayer,
        hour = tomorrowPrayer.hour,
        minute = tomorrowPrayer.minute,
        triggerAtMillis = prayerTimeMillis(tomorrow, tomorrowPrayer),
        isTomorrow = true,
    )
}

private fun resolveUpcomingManualSilencePrayer(
    context: Context,
    delegationId: Int,
    nowMillis: Long = System.currentTimeMillis()
): Prayer? {
    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val todayTimes = loadDayPrayerTimesWithFallback(context, delegationId, now)
    val upcomingToday = todayTimes?.allPrayers()?.firstOrNull { prayerTime ->
        prayerTimeMillis(now, prayerTime) > nowMillis
    }
    if (upcomingToday != null) return upcomingToday.prayer

    val tomorrow = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    return loadDayPrayerTimesWithFallback(context, delegationId, tomorrow)?.allPrayers()?.firstOrNull()?.prayer
}

private fun resolveManualSilencePrayerEndMillis(
    context: Context,
    delegationId: Int,
    prayer: Prayer,
    nowMillis: Long = System.currentTimeMillis()
): Long? {
    if (prayer !in WAKE_SUPPORTED_PRAYERS) return null

    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    for (dayOffset in 0..1) {
        val day = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
        val prayerTime = loadDayPrayerTimesWithFallback(context, delegationId, day)
            ?.allPrayers()
            ?.firstOrNull { it.prayer == prayer }
            ?: continue
        val targetMillis = prayerTimeMillis(day, prayerTime)
        if (targetMillis > nowMillis) return targetMillis
    }

    return null
}

private fun loadDayPrayerTimesWithFallback(
    context: Context,
    delegationId: Int,
    day: Calendar
) = PrayerTimesRepository.loadDayPrayerTimes(
    context,
    delegationId,
    day.get(Calendar.YEAR),
    day.get(Calendar.MONTH) + 1,
    day.get(Calendar.DAY_OF_MONTH)
) ?: PrayerTimesRepository.loadDayPrayerTimes(
    context,
    delegationId,
    day.get(Calendar.YEAR) - 1,
    day.get(Calendar.MONTH) + 1,
    day.get(Calendar.DAY_OF_MONTH)
)

private fun prayerTimeMillis(day: Calendar, prayerTime: PrayerTime): Long {
    return (day.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, prayerTime.hour)
        set(Calendar.MINUTE, prayerTime.minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun formatCountdownRemaining(targetAtMillis: Long, currentTimeMillis: Long): String {
    val remainingMillis = (targetAtMillis - currentTimeMillis).coerceAtLeast(0L)
    if (remainingMillis < 60_000L) {
        val remainingSeconds = ((remainingMillis + 999L) / 1_000L)
            .coerceAtLeast(0L)
            .toInt()
        return "${remainingSeconds} ث"
    }

    val remainingMinutes = ((remainingMillis + 59_999L) / 60_000L)
        .coerceAtLeast(1L)
        .toInt()
    return formatDurationText(remainingMinutes)
}

private fun nextCountdownRefreshDelayMillis(targetAtMillis: Long?, currentTimeMillis: Long): Long {
    val remainingMillis = targetAtMillis?.let { target -> target - currentTimeMillis }
    val nextTickMillis = if (remainingMillis != null && remainingMillis in 1L..60_000L) {
        ((currentTimeMillis / 1_000L) + 1L) * 1_000L
    } else {
        ((currentTimeMillis / 60_000L) + 1L) * 60_000L
    }
    return (nextTickMillis - currentTimeMillis).coerceAtLeast(1L)
}

// Helper functions

/** Normalizes Eastern Arabic (٠-٩) and Extended Arabic-Indic (۰-۹) digits to Western 0-9. */
private fun normalizeDigits(input: String): String = buildString(input.length) {
    for (c in input) {
        when (c) {
            in '\u0660'..'\u0669' -> append('0' + (c - '\u0660')) // ٠١٢٣٤٥٦٧٨٩
            in '\u06F0'..'\u06F9' -> append('0' + (c - '\u06F0')) // ۰۱۲۳۴۵۶۷۸۹
            else -> append(c)
        }
    }
}

private fun hasExactAlarmPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }
    return true
}

private fun hasPostNotificationsPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

private fun hasFullScreenIntentPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

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

private fun startOfDayMillis(sourceTimeInMillis: Long): Long {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = sourceTimeInMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return calendar.timeInMillis
}

private fun isSameCalendarDay(firstTimeInMillis: Long, secondTimeInMillis: Long): Boolean {
    val first = Calendar.getInstance().apply { timeInMillis = firstTimeInMillis }
    val second = Calendar.getInstance().apply { timeInMillis = secondTimeInMillis }
    return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
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

private fun nextWakeAlarmMillis(
    context: android.content.Context,
    delegationId: Int,
    config: com.tunisianprayertimes.PrayerWakeConfig,
): Long? {
    if (!config.enabled) return null
    val now = Calendar.getInstance()
    val jomoaaHour = PrefsManager.getJomoaaTimeHour(context)
    val jomoaaMinute = PrefsManager.getJomoaaTimeMinute(context)
    val prayerDays = (-1..2).mapNotNull { dayOffset ->
        val date = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
        PrayerTimesRepository.loadDayPrayerTimes(
            context = context,
            delegationId = delegationId,
            year = date.get(Calendar.YEAR),
            month = date.get(Calendar.MONTH) + 1,
            day = date.get(Calendar.DAY_OF_MONTH),
        )?.let { times ->
            WakeAlarmComputer.PrayerDayContext(
                date = date,
                prayerTimes = times,
                isFriday = date.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY,
                jomoaaHour = jomoaaHour,
                jomoaaMinute = jomoaaMinute,
            )
        }
    }
    return WakeAlarmComputer.compute(now, config, prayerDays)
        .allTriggers.firstOrNull()?.triggerAtMillis
}

private fun formatWakeAlarmDateTime(timeInMillis: Long): String {
    val formatter = SimpleDateFormat("EEE d MMM - HH:mm", Locale.forLanguageTag("ar-TN-u-nu-latn"))
    return formatter.format(Date(timeInMillis))
}

private fun formatClockTime(hour: Int, minute: Int): String =
    String.format(Locale.US, "%02d:%02d", hour, minute)

private fun formatTimeOfDay(targetTimeInMillis: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = targetTimeInMillis }
    return String.format(
        Locale.US,
        "%02d:%02d",
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE)
    )
}

