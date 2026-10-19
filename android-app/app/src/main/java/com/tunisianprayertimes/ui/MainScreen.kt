package com.tunisianprayertimes.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.tunisianprayertimes.ClockTime
import com.tunisianprayertimes.adhkar.MushafPassages
import com.tunisianprayertimes.mosque.DisplayTexts
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.Delegation
import com.tunisianprayertimes.DelegationLocationResult
import com.tunisianprayertimes.DelegationLocator
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.GouvernoratRepository
import com.tunisianprayertimes.Locality
import com.tunisianprayertimes.LocalityPickerCatalog
import com.tunisianprayertimes.LocalityRepository
import com.tunisianprayertimes.withAvailablePrayerSource
import com.tunisianprayertimes.ManualSilenceMode
import com.tunisianprayertimes.ManualSilenceScheduler
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerRelativeOffset
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.platform.PrayerDataLoader
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.PlaceResolution
import com.tunisianprayertimes.PlaceCatalogue
import com.tunisianprayertimes.CitySearch
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.silence.SilenceEnd
import com.tunisianprayertimes.silence.SilenceEndpoints
import com.tunisianprayertimes.silence.SilenceFit
import com.tunisianprayertimes.silence.SilenceRule
import com.tunisianprayertimes.silence.SilenceStart
import com.tunisianprayertimes.place.CountryResolver as PlaceCountry
import com.tunisianprayertimes.R
import com.tunisianprayertimes.RamadanDetector
import com.tunisianprayertimes.RamadanOverrideChecker
import com.tunisianprayertimes.OfficialIslamicDates
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.ScheduleRefreshCoordinator
import com.tunisianprayertimes.SilenceAlarmComputer
import com.tunisianprayertimes.SilenceMode
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.SilenceStatus
import com.tunisianprayertimes.WAKE_RECURRING_LOOKAHEAD_DAYS
import com.tunisianprayertimes.WAKE_SUPPORTED_PRAYERS
import com.tunisianprayertimes.WakeMainAlarmConfig
import com.tunisianprayertimes.WakeMainAlarmMode
import com.tunisianprayertimes.WakePlaybackOptions
import com.tunisianprayertimes.WakeRepeatMode
import com.tunisianprayertimes.hasPendingWakeOccurrenceSkip
import com.tunisianprayertimes.isRepeatingWakeAlarm
import com.tunisianprayertimes.MathDifficulty
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.WakeUpCheckType
import com.tunisianprayertimes.formatArabicMinutes
import com.tunisianprayertimes.wake.PrayerWakeRepository
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import com.tunisianprayertimes.wake.AwakeCheckService
import java.time.LocalDate
import java.time.LocalTime
import java.util.Date
import com.tunisianprayertimes.WakeAlarmComputer
import com.tunisianprayertimes.ui.theme.BannerBg
import com.tunisianprayertimes.ui.theme.BannerStroke
import com.tunisianprayertimes.ui.theme.BannerText
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.CardBorder
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
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
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class MainDestination(val labelRes: Int, val iconRes: Int) {
    Today(R.string.main_tab_today, R.drawable.ic_tab_today),
    Alarms(R.string.main_tab_alarms, R.drawable.ic_tab_alarms),
    Qibla(R.string.main_tab_qibla, R.drawable.ic_tab_qibla),
    Adhkar(R.string.adhkar_title, R.drawable.ic_tab_adhkar),
    Quran(R.string.main_tab_quran, R.drawable.ic_tab_quran),
}

private enum class WakeQuickPreset {
    PRAYER_RELATIVE,
    FIXED_TIME,
    TIMER,
}

private data class WakeSilenceConflictEditorState(
    val draftConfig: PrayerWakeConfig,
    val originalConflict: WakeSilenceConflictSummary,
    val draftSilenceConfig: PrayerSilenceConfig,
    val currentConflict: WakeSilenceConflictSummary? = originalConflict,
    val hasEditedSilenceWindow: Boolean = false,
    val editRevision: Int = 0,
)

private const val DEFAULT_PRAYER_OFFSET_MINUTES = 20
private const val DEFAULT_TIMER_MINUTES = 15
private const val DEFAULT_FIXED_ALARM_HOUR = 8
private const val DEFAULT_FIXED_ALARM_MINUTE = 0
private val MainContentBottomPadding = 76.dp
private val BottomNavigationMinHeight = 64.dp
private val BottomNavigationVerticalPadding = 6.dp
private val BottomNavigationItemFixedHeight = 29.dp
private val WakeAlarmAddButtonSize = 64.dp
private val WakeAlarmAddButtonBottomPadding = 92.dp
private val WakeAlarmAddButtonShadowElevation = 8.dp
private const val WakeAlarmAddButtonIdleRevealDelayMillis = 3_000L
private const val WakeAlarmAddButtonBottomPullRevealDelayMillis = 3_000L
private val MainHeroCardHeight = 148.dp
private val SilencedHeroStart = Color(0xFF3A1F2E)
private val SilencedHeroEnd = Color(0xFF6D3543)
private val SilencedHeroPillBackground = Color(0xFFF7E8EC)
private val SilencedHeroPillText = Color(0xFF3A1F2E)

private fun MainDestination.analyticsName(): String = when (this) {
    MainDestination.Today -> "prayers"
    MainDestination.Alarms -> "alarms"
    MainDestination.Qibla -> "qibla"
    MainDestination.Adhkar -> "adhkar"
    MainDestination.Quran -> "quran"
}

private fun MainDestination.testTag(): String = when (this) {
    MainDestination.Today -> TestTags.MAIN_TAB_TODAY
    MainDestination.Alarms -> TestTags.MAIN_TAB_ALARMS
    MainDestination.Qibla -> TestTags.MAIN_TAB_QIBLA
    MainDestination.Adhkar -> TestTags.MAIN_TAB_ADHKAR
    MainDestination.Quran -> TestTags.MAIN_TAB_QURAN
}

private fun WakeQuickPreset.testTag(): String = when (this) {
    WakeQuickPreset.PRAYER_RELATIVE -> TestTags.WAKE_QUICK_PRESET_PRAYER_RELATIVE
    WakeQuickPreset.FIXED_TIME -> TestTags.WAKE_QUICK_PRESET_FIXED_TIME
    WakeQuickPreset.TIMER -> TestTags.WAKE_QUICK_PRESET_TIMER
}

/** The next prayer (6.9): its instant, its hour and minute in the phone's clock, and its prayer day. */
private data class NextPrayerCountdownInfo(
    val prayer: Prayer,
    val hour: Int,
    val minute: Int,
    val triggerAtMillis: Long,
    val isTomorrow: Boolean,
    val prayerDay: LocalDate,
)

@Composable
fun MainScreen(
    activity: androidx.appcompat.app.AppCompatActivity,
    requestedDestination: String? = null,
    requestedDestinationSequence: Int = 0,
    requestedDhikrReminderId: String? = null,
    requestedDhikrOccurrenceId: String? = null,
    requestedQuranAction: String? = null,
    requestedPrayersAction: String? = null,
) {
    val context = LocalContext.current
    val notificationManager = remember { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    val officialDateRecords by OfficialIslamicDates.updates.collectAsStateWithLifecycle()
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
    var delegationId by rememberSaveable { mutableIntStateOf(screenTimesId(context)) }
    var locationPickerRequested by remember { mutableStateOf(PrefsManager.consumePlacePickerOnStart(context)) }
    // 6.8: a skipped first run's place, shown with «تأكيد الموقع» until the user confirms it or a fix arrives.
    var placeProvisional by remember(delegationId) { mutableStateOf(PrefsManager.isPlaceProvisional(context)) }
    // 6.4, 6.11: a chooser country's question, or an unknown country's «تأكيد الطريقة», until answered. The chooser
    // opens by itself once per country and session; dismissed, it leaves the banner.
    var methodQuestion by remember(delegationId) { mutableStateOf(runCatching { pendingMethodQuestion(context) }.getOrNull()) }
    var methodChooserSeen by rememberSaveable { mutableStateOf(setOf<String>()) }
    var methodChooserOpen by remember { mutableStateOf(false) }
    LaunchedEffect(methodQuestion) {
        val question = methodQuestion
        if (question != null && question.hasChooser && question.key !in methodChooserSeen) {
            methodChooserSeen = methodChooserSeen + question.key
            methodChooserOpen = true
        }
    }
    var prayerTabSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var prayerTimeMethodOpen by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(context) {
        // Background location updates must refresh the timetable alongside the label.
        val unsubscribe = PrefsManager.observeLocationSelection(context) {
            delegationId = screenTimesId(context)
        }
        delegationId = screenTimesId(context)
        onDispose { unsubscribe() }
    }
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
    var phoneSilenced by remember { mutableStateOf(SilenceStatus.isPhoneSilenced(context)) }
    var appControlledSilenceActive by remember { mutableStateOf(SilenceStatus.isAppControlledSilenceActive(context)) }
    var wakeSilenceUntilAlarmActive by remember { mutableStateOf(WakeAlarmScheduler.isSilenceUntilAlarmActive(context)) }
    var manualSilenceEndsAtMillis by remember {
        mutableLongStateOf(PrefsManager.getManualSilenceEndsAtMillis(context))
    }

    fun refreshSilenceState() {
        autoSilenceActive = PrefsManager.isAutoSilenceActive(context)
        manualSilenceActive = PrefsManager.isManualSilenceActive(context)
        phoneSilenced = SilenceStatus.isPhoneSilenced(context)
        appControlledSilenceActive = SilenceStatus.isAppControlledSilenceActive(context)
        wakeSilenceUntilAlarmActive = WakeAlarmScheduler.isSilenceUntilAlarmActive(context)
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
                delegationId = screenTimesId(context)
            }
        }

        ScheduleRefreshCoordinator.syncSilence(
            context = context,
            skipWhileManualSilence = true,
        )
        syncWakeScheduling()

        withContext(Dispatchers.IO) {
            com.tunisianprayertimes.adhkar.DhikrReminderScheduler.refresh(context, rearm = true)
        }

        refreshSilenceState()
    }

    // Start Ramadan override polling on first composition
    LaunchedEffect(Unit) {
        AnalyticsTracker.installRamadanOverrideReporter(context)
        RamadanOverrideChecker.startPollingIfNeeded()
    }

    // Scheduling follows announcements for the process lifetime in Application.
    // Refresh the visible controls here without scheduling the same update twice.
    val currentOfficialDates = officialDateRecords[algorithmicHijriYear(LocalDate.now())]
    LaunchedEffect(currentOfficialDates) {
        if (currentOfficialDates != null) {
            refreshSilenceState()
        }
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

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            refreshSilenceState()
        }
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
    var wakeSilenceConflictEditor by remember { mutableStateOf<WakeSilenceConflictEditorState?>(null) }
    var pendingReturnToPrayersAfterEdit by rememberSaveable { mutableStateOf(false) }

    fun createWakeAlarm(preset: WakeQuickPreset) {
        quickAddVisible = false
        wakeSilenceConflictEditor = null
        editingWakeAlarm = newWakeAlarmConfig(preset)
    }

    val mainDestinations = remember { MainDestination.values().toList() }
    var selectedDestinationIndex by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(requestedDestination, requestedDestinationSequence) {
        val requestedIndex = when (requestedDestination) {
            MainTabNavigation.DESTINATION_PRAYERS -> mainDestinations.indexOf(MainDestination.Today)
            MainTabNavigation.DESTINATION_ALARMS -> mainDestinations.indexOf(MainDestination.Alarms)
            MainTabNavigation.DESTINATION_QIBLA -> mainDestinations.indexOf(MainDestination.Qibla)
            MainTabNavigation.DESTINATION_ADHKAR -> mainDestinations.indexOf(MainDestination.Adhkar)
            MainTabNavigation.DESTINATION_QURAN -> mainDestinations.indexOf(MainDestination.Quran)
            else -> -1
        }
        if (requestedIndex in mainDestinations.indices) {
            selectedDestinationIndex = requestedIndex
        }
        // The travel notification opens the city picker (6.8).
        if (requestedDestination == MainTabNavigation.DESTINATION_PRAYERS &&
            requestedPrayersAction == MainTabNavigation.PRAYERS_ACTION_CHOOSE_PLACE
        ) locationPickerRequested = true
    }
    val currentDestinationIndex = if (selectedDestinationIndex in mainDestinations.indices) {
        selectedDestinationIndex
    } else {
        0
    }
    val selectedDestination = mainDestinations[currentDestinationIndex]
    val todayScrollState = rememberScrollState()
    val alarmsScrollState = rememberScrollState()
    val qiblaScrollState = rememberScrollState()
    val adhkarScrollState = rememberScrollState()
    var prayerTableSelectedDate by rememberSaveable {
        mutableLongStateOf(startOfDayMillis(System.currentTimeMillis()))
    }
    val selectedDestinationScrollState = when (selectedDestination) {
        MainDestination.Today -> todayScrollState
        MainDestination.Alarms -> alarmsScrollState
        MainDestination.Qibla -> qiblaScrollState
        MainDestination.Adhkar, MainDestination.Quran -> adhkarScrollState
    }

    LaunchedEffect(selectedDestination) {
        AnalyticsTracker.tabViewed(context, selectedDestination.analyticsName())
    }

    // When an alarm edit was launched from the prayer-tab timeline, return there once the editor closes.
    LaunchedEffect(editingWakeAlarm) {
        if (editingWakeAlarm == null && pendingReturnToPrayersAfterEdit) {
            selectedDestinationIndex = mainDestinations.indexOf(MainDestination.Today)
            pendingReturnToPrayersAfterEdit = false
        }
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

    // The Fajr suggestion saves straight away, as the editor's save would.
    fun addSuggestedFajrAlarm(config: PrayerWakeConfig) {
        if (hasUnresolvedWakeSilenceConflict(context, delegationId, config)) {
            // Only the editor can settle a silence-window conflict, so the alarm opens there.
            quickAddVisible = false
            wakeSilenceConflictEditor = null
            editingWakeAlarm = config
            return
        }
        mainScope.launch {
            wakeRepository.saveWakeConfig(config)
            AnalyticsTracker.wakeAlarmSaved(context, config)
            rescheduleIfEnabled()
        }
        if (!hasPostNotificationsPermission(context)) {
            requestWakeNotificationPermission()
        } else {
            requestFullScreenIntentIfNeeded()
        }
    }

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
    val showWakeAlarmAddButton = selectedDestination == MainDestination.Alarms &&
        wakeAlarmsForPermission?.isNotEmpty() == true &&
        editingWakeAlarm == null
    var wakeAlarmAddButtonVisible by remember { mutableStateOf(true) }
    var wakeAlarmBottomPullHideActive by remember { mutableStateOf(false) }

    LaunchedEffect(showWakeAlarmAddButton, alarmsScrollState) {
        if (!showWakeAlarmAddButton) {
            wakeAlarmAddButtonVisible = true
            return@LaunchedEffect
        }

        var previousScrollValue = alarmsScrollState.value
        snapshotFlow {
            Pair(
                alarmsScrollState.value,
                alarmsScrollState.isScrollInProgress,
            )
        }.collectLatest { (scrollValue, scrollInProgress) ->
            val scrollingDown = scrollInProgress && scrollValue > previousScrollValue
            val scrollingUp = scrollInProgress && scrollValue < previousScrollValue
            val atBottom = scrollValue >= alarmsScrollState.maxValue
            previousScrollValue = scrollValue

            when {
                scrollingDown -> {
                    wakeAlarmBottomPullHideActive = false
                    wakeAlarmAddButtonVisible = false
                }
                scrollInProgress && atBottom && !scrollingUp -> {
                    wakeAlarmBottomPullHideActive = true
                    wakeAlarmAddButtonVisible = false
                }
                scrollingUp -> {
                    wakeAlarmBottomPullHideActive = false
                    wakeAlarmAddButtonVisible = true
                }
                !scrollInProgress -> {
                    val revealDelayMillis = if (wakeAlarmBottomPullHideActive) {
                        WakeAlarmAddButtonBottomPullRevealDelayMillis
                    } else {
                        WakeAlarmAddButtonIdleRevealDelayMillis
                    }
                    delay(revealDelayMillis)
                    wakeAlarmAddButtonVisible = true
                    wakeAlarmBottomPullHideActive = false
                }
            }
        }
    }

    val density = LocalDensity.current
    val navigationBarInsetPx = WindowInsets.navigationBars.getBottom(density)
    // Height the scrolling content keeps clear of the bottom navigation. The surface
    // consumes the system navigation inset once; the measured surface height already
    // includes it, so the scrolling content must not add it again.
    var bottomNavigationOccupiedPx by remember {
        mutableIntStateOf(
            with(density) { MainContentBottomPadding.roundToPx() } + navigationBarInsetPx,
        )
    }
    // The Quran reader's dock sits right on the bar, and the bar slides away for its full player and focus mode.
    var bottomNavigationHeightPx by remember {
        mutableIntStateOf(with(density) { BottomNavigationMinHeight.roundToPx() } + navigationBarInsetPx)
    }
    var quranHidesNavigation by remember { mutableStateOf(false) }
    val navigationHidden = quranHidesNavigation && selectedDestination == MainDestination.Quran
    val quranBottomPadding by animateDpAsState(
        targetValue = with(density) { (if (navigationHidden) navigationBarInsetPx else bottomNavigationHeightPx).toDp() },
        label = "quranBottomPadding",
    )
    val quranPlayback by com.tunisianprayertimes.quran.audio.QuranAudioController.state.collectAsState()

    val adhkarStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val quranStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val isAdhkar = selectedDestination == MainDestination.Adhkar
    Box(modifier = Modifier.fillMaxSize().background(BgCream)) {
        if (isAdhkar) {
            adhkarStateHolder.SaveableStateProvider("adhkar") {
                AdhkarScreen(
                    activity = activity,
                    requestedReminderId = requestedDhikrReminderId,
                    requestedReminderSequence = requestedDestinationSequence,
                    requestedOccurrenceId = requestedDhikrOccurrenceId,
                    modifier = Modifier.fillMaxSize().padding(bottom = with(density) { bottomNavigationOccupiedPx.toDp() }),
                )
            }
        } else if (selectedDestination == MainDestination.Quran) {
            quranStateHolder.SaveableStateProvider("quran") {
                QuranScreen(
                    modifier = Modifier.fillMaxSize().padding(bottom = quranBottomPadding),
                    onHideNavigation = { quranHidesNavigation = it },
                    hifzRequest = requestedQuranAction?.let { it to requestedDestinationSequence },
                )
            }
        } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgCream)
                .padding(bottom = with(density) { bottomNavigationOccupiedPx.toDp() })
                .statusBarsPadding()
                .verticalScroll(selectedDestinationScrollState)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                if (selectedDestination == MainDestination.Today) {
                    // The location is the page header: the bottom tab already names the page,
                    // and the place is what decides every time shown below it.
                    // The two round buttons sit on the title's first line, whatever the name's lines and the notes
                    // under it (both rows are aligned at the top).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LocationPickerCard(
                            delegationId = delegationId,
                            openRequested = locationPickerRequested,
                            onOpenRequestHandled = { locationPickerRequested = false },
                            onDelegationSelected = { delegation ->
                                delegationId = delegation.id
                                PrefsManager.setDelegationId(context, delegation.id)
                                rescheduleIfEnabled()
                            },
                            modifier = Modifier.weight(1f),
                        )
                        PrayerTabSettingsButton(onClick = { prayerTabSettingsOpen = true })
                    }
                    // The place's and the method's questions, under the header they are about.
                    methodQuestion?.let { question ->
                        MethodConfirmBanner(
                            question = question,
                            onChoose = { if (question.hasChooser) methodChooserOpen = true else prayerTabSettingsOpen = true },
                            onConfirm = {
                                answerMethodQuestion(context, question, methodId = null, asrShadow = null)
                                methodQuestion = null
                            },
                        )
                    }
                    if (placeProvisional) {
                        ProvisionalPlaceBanner(
                            onConfirm = {
                                PrefsManager.setPlaceProvisional(context, false)
                                placeProvisional = false
                            },
                            onChange = { locationPickerRequested = true },
                        )
                    }
                } else {
                    // No page title: the selected bottom tab already names the page.
                    Spacer(Modifier.height(12.dp))
                }

                when (selectedDestination) {
                    MainDestination.Today -> {
                        TodayNextPrayerCard(
                            delegationId = delegationId,
                            selectedDate = prayerTableSelectedDate,
                            isPhoneSilenced = phoneSilenced,
                            hasDnd = hasDnd,
                            autoSilenceActive = autoSilenceActive,
                            manualSilenceActive = manualSilenceActive,
                            manualSilenceMode = manualSilenceMode,
                            manualTargetPrayer = manualTargetPrayer,
                            manualSilenceEndsAtMillis = manualSilenceEndsAtMillis,
                            wakeSilenceUntilAlarmActive = wakeSilenceUntilAlarmActive,
                            autoSilenceEnabled = autoSilenceEnabled,
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

                        PrayerSettingsCard(
                            delegationId = delegationId,
                            activity = activity,
                            onChooseLocation = { locationPickerRequested = true },
                            selectedDate = prayerTableSelectedDate,
                            onSelectedDateChange = { selectedDate ->
                                prayerTableSelectedDate = startOfDayMillis(selectedDate)
                            },
                            onConfigChanged = {
                                rescheduleIfEnabled()
                            },
                            silencePermissionsGranted = hasDnd && hasAlarm,
                            onEnableSilenceMaster = {
                                if (!PrefsManager.isEnabled(context)) {
                                    autoSilenceEnabled = true
                                    PrefsManager.setEnabled(context, true)
                                    AnalyticsTracker.autoSilenceStateChanged(context, true)
                                }
                                rescheduleIfEnabled()
                            }
                        )

                        ManualSilenceCard(
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
                            onDurationPresetSelected = { totalMinutes ->
                                manualDurationHours = (totalMinutes / 60).toString()
                                manualDurationMinutes = (totalMinutes % 60).toString()
                                PrefsManager.setManualSilenceDurationMinutes(context, totalMinutes)
                            },
                            onClick = {
                                if (!notificationManager.isNotificationPolicyAccessGranted) {
                                    Toast.makeText(context, context.getString(R.string.toast_dnd_permission), Toast.LENGTH_SHORT).show()
                                    return@ManualSilenceCard
                                }
                                if (manualSilenceActive || autoSilenceActive) {
                                    if (manualSilenceActive) {
                                        SilenceModeController.setManualNormal(context)
                                    } else {
                                        SilenceModeController.disableAutoSilence(context)
                                        SilenceScheduler.dismissCurrentWindows(context, SilenceScheduler.currentSilenceWindowPrayer(context))
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
                                        return@ManualSilenceCard
                                    }
                                    val prayerEndsAtMillis = if (manualSilenceMode == ManualSilenceMode.UNTIL_PRAYER) {
                                        resolveManualSilencePrayerEndMillis(context, delegationId, manualTargetPrayer)
                                    } else {
                                        null
                                    }
                                    if (manualSilenceMode == ManualSilenceMode.UNTIL_PRAYER && prayerEndsAtMillis == null) {
                                        Toast.makeText(context, context.getString(R.string.error_manual_prayer_time_unavailable), Toast.LENGTH_SHORT).show()
                                        return@ManualSilenceCard
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

                        SunriseAndNightTimesCard(
                            delegationId = delegationId,
                            selectedDate = prayerTableSelectedDate,
                            wakeAlarms = wakeAlarmsForPermission,
                            onEditWakeAlarm = { alarmId ->
                                wakeAlarmsForPermission
                                    ?.firstOrNull { alarm -> alarm.id == alarmId }
                                    ?.let { alarm ->
                                        pendingReturnToPrayersAfterEdit = true
                                        selectedDestinationIndex = mainDestinations.indexOf(MainDestination.Alarms)
                                        editingWakeAlarm = alarm
                                    }
                            },
                        )

                        if (RamadanDetector.isRamadan()) {
                            RamadanBadge()
                        }

                        // Automatic silencing and the other tab settings live behind the settings
                        // button beside the location header.
                        if (prayerTabSettingsOpen) {
                            PrayerTabSettingsSheet(
                                autoSilenceEnabled = autoSilenceEnabled,
                                onAutoSilenceChange = { enabled ->
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
                                },
                                callEndVibrationEnabled = callEndVibrationEnabled,
                                onCallEndVibrationChange = { enabled ->
                                    callEndVibrationEnabled = enabled
                                    PrefsManager.setCallEndVibrationEnabled(context, enabled)
                                },
                                autoLocationEnabled = autoLocationEnabled,
                                onAutoLocationChange = { enabled ->
                                    autoLocationEnabled = enabled
                                    PrefsManager.setAutoLocationUpdateEnabled(context, enabled)
                                },
                                onShowPrayerTimeMethod = {
                                    prayerTabSettingsOpen = false
                                    prayerTimeMethodOpen = true
                                },
                                onDismiss = { prayerTabSettingsOpen = false },
                                methodSection = { PrayerMethodSection(onTimesChanged = { delegationId = screenTimesId(context) }) },
                                inmMethodIsExample = PrayerTimesRepository.place(context) !is PrayerPlace.Inm,
                            )
                        }
                        if (prayerTimeMethodOpen) {
                            PrayerTimeMethodDialog(
                                // Abroad, INM's worked example is always Tunis's (615, 6.11).
                                delegationId = (PrayerTimesRepository.place(context) as? PrayerPlace.Inm)?.delegationId ?: PlaceResolution.TUNIS_DELEGATION_ID,
                                selectedDate = prayerTableSelectedDate,
                                onDismiss = { prayerTimeMethodOpen = false },
                            )
                        }

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
                            onAddFajrAlarm = ::addSuggestedFajrAlarm,
                        )
                    }

                    MainDestination.Qibla -> {
                        QiblaCard(placeRevision = delegationId)
                    }
                    MainDestination.Adhkar, MainDestination.Quran -> Unit
                }

            }
        }

        }
        AnimatedVisibility(
            visible = !navigationHidden,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    bottomNavigationHeightPx = size.height
                    bottomNavigationOccupiedPx = size.height + with(density) {
                        (MainContentBottomPadding - BottomNavigationMinHeight).roundToPx()
                    }
                },
            color = Color.White,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // Content-driven height: wrapped labels grow the bar instead of
                    // being clipped by a fixed 64dp row.
                    val textMeasurer = rememberTextMeasurer()
                    val labels = mainDestinations.map { stringResource(it.labelRes) }
                    val labelStyle = LocalTextStyle.current.merge(
                        TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    )
                    val itemWidthPx = (constraints.maxWidth - with(density) { 16.dp.toPx() }) /
                        mainDestinations.size
                    val labelWidthPx = (itemWidthPx - with(density) { 12.dp.toPx() }).coerceAtLeast(1f)
                    val labelHeightPx = labels.maxOf { label ->
                        textMeasurer.measure(
                            text = label,
                            style = labelStyle,
                            constraints = Constraints(maxWidth = labelWidthPx.toInt().coerceAtLeast(1)),
                        ).size.height
                    }
                    val minContentHeightPx = with(density) {
                        (BottomNavigationMinHeight - BottomNavigationVerticalPadding * 2).toPx()
                    }
                    val itemContentHeightPx = with(density) {
                        BottomNavigationItemFixedHeight.toPx()
                    } + labelHeightPx
                    val rowHeight = with(density) {
                        (maxOf(minContentHeightPx, itemContentHeightPx) +
                            BottomNavigationVerticalPadding.toPx() * 2f).toDp()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .padding(horizontal = 8.dp, vertical = BottomNavigationVerticalPadding),
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
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { selectedDestinationIndex = index },
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

                                Box {
                                    Icon(
                                        painter = painterResource(destination.iconRes),
                                        contentDescription = label,
                                        tint = tabContentColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    // A recitation plays on while another tab is open.
                                    if (destination == MainDestination.Quran && !selected && quranPlayback.playing) {
                                        Box(
                                            Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = 3.dp, y = (-2).dp)
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(GreenPrimary)
                                                .testTag("quran_tab_playing")
                                        )
                                    }
                                }

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
        }
        }

        if (showWakeAlarmAddButton && wakeAlarmAddButtonVisible) {
            WakeAlarmFloatingAddButton(
                onClick = { quickAddVisible = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 24.dp,
                        bottom = with(density) { bottomNavigationOccupiedPx.toDp() } +
                            (WakeAlarmAddButtonBottomPadding - MainContentBottomPadding),
                    ),
            )
        }

        methodQuestion?.takeIf { methodChooserOpen }?.let { question ->
            MethodChooserSheet(
                question = question,
                onAnswered = {
                    methodChooserOpen = false
                    methodQuestion = null
                    delegationId = screenTimesId(context)
                    mainScope.launch { ScheduleRefreshCoordinator.syncAll(context) }
                },
                onDismiss = { methodChooserOpen = false },
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
                silenceConfigRevision = refreshTick + (wakeSilenceConflictEditor?.editRevision ?: 0),
                silenceConflictResolverState = wakeSilenceConflictEditor?.let { editorState ->
                    WakeSilenceConflictResolverState(
                        conflict = editorState.currentConflict ?: editorState.originalConflict,
                        resolved = editorState.hasEditedSilenceWindow && editorState.currentConflict == null,
                    )
                },
                silenceConflictResolverContent = {
                    wakeSilenceConflictEditor?.let { editorState ->
                        WakeSilenceConflictPrayerEditor(
                            delegationId = delegationId,
                            editorState = editorState,
                            onConfigChanged = { updatedSilenceConfig ->
                                wakeSilenceConflictEditor = wakeSilenceConflictEditor?.let { state ->
                                    refreshWakeSilenceConflictEditor(
                                        context = context,
                                        delegationId = delegationId,
                                        editorState = state.copy(draftSilenceConfig = updatedSilenceConfig),
                                    )
                                }
                            },
                        )
                    }
                },
                onDismissRequest = {
                    editingWakeAlarm = null
                    wakeSilenceConflictEditor = null
                },
                onSave = { config ->
                    val stagedSilenceEditor = wakeSilenceConflictEditor
                    if (stagedSilenceEditor?.hasEditedSilenceWindow == true && !config.ringDuringSilenceWindow) {
                        val conflict = stagedSilenceEditor.originalConflict
                        persistPrayerSilenceConfig(
                            context = context,
                            prayer = conflict.silencePrayer,
                            config = stagedSilenceEditor.draftSilenceConfig,
                            anchor = conflict.silencePrayerDay?.let { timelineAnchorFor(context, conflict.silencePrayer, it) },
                        )
                    }
                    mainScope.launch {
                        wakeRepository.saveWakeConfig(config)
                        AnalyticsTracker.wakeAlarmSaved(context, config)
                        editingWakeAlarm = null
                        wakeSilenceConflictEditor = null
                        rescheduleIfEnabled()
                    }
                    if (config.enabled &&
                        config.mainAlarm.mode == WakeMainAlarmMode.FROM_NOW &&
                        config.silenceUntilAlarm &&
                        WakeAlarmComputer.compute(Calendar.getInstance(), config, emptyList()).allTriggers.isNotEmpty()
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
                onEditSilenceWindowRequest = { draftConfig, conflict ->
                    quickAddVisible = false
                    wakeSilenceConflictEditor = WakeSilenceConflictEditorState(
                        draftConfig = draftConfig,
                        originalConflict = conflict,
                        draftSilenceConfig = PrefsManager.getEffectiveConfig(context, conflict.silencePrayer),
                    )
                },
                onCancelSilenceWindowEdit = {
                    wakeSilenceConflictEditor = null
                },
                onDelete = if (isPersistedAlarm) {
                    {
                        WakeAlarmScheduler.removeSilenceUntilAlarm(context, wakeAlarm.id)
                        mainScope.launch {
                            wakeRepository.deleteWakeAlarm(wakeAlarm.id)
                            editingWakeAlarm = null
                            wakeSilenceConflictEditor = null
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
private fun PhoneStatusNotice(
    isPhoneSilenced: Boolean,
    hasDnd: Boolean,
    silenceReason: PhoneSilenceReason?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val statusText = phoneStatusNoticeText(
        context = context,
        isPhoneSilenced = isPhoneSilenced,
        hasDnd = hasDnd,
        silenceReason = silenceReason,
    ) ?: return

    val accentColor by animateColorAsState(
        targetValue = if (!hasDnd) Color(0xFFFFD166) else Color(0xFFFFCDD2),
        label = "phoneStatusAccent"
    )

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
            Icon(
                painter = painterResource(R.drawable.ic_tab_alarms),
                contentDescription = null,
                tint = Color(0xFFE65100),
                modifier = Modifier.size(32.dp),
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
                    // The app's own page, not the list of every app.
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:" + context.packageName)),
                    )
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
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = BannerText,
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(22.dp),
            )
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
            Icon(
                painter = painterResource(R.drawable.ic_battery_alert),
                contentDescription = null,
                tint = BannerText,
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(22.dp),
            )
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
    openRequested: Boolean = false,
    onOpenRequestHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val gouvernorats = remember { GouvernoratRepository.loadAll(context) }
    // Show the lightweight name list immediately while current-period timetable
    // availability is checked in the background. Selection always rechecks it.
    var allDelegations by remember { mutableStateOf(gouvernorats.flatMap { it.delegations }) }
    var sourcesChecked by remember { mutableStateOf(false) }
    val availableIds = remember(allDelegations) { allDelegations.map { it.id }.toSet() }
    val scope = rememberCoroutineScope()
    var sourceRefreshJob by remember { mutableStateOf<Job?>(null) }
    var selectionJob by remember { mutableStateOf<Job?>(null) }
    fun refreshSources() {
        sourceRefreshJob?.cancel()
        sourceRefreshJob = scope.launch {
            allDelegations = withContext(Dispatchers.IO) {
                GouvernoratRepository.loadAllDelegations(context)
            }
            sourcesChecked = true
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshSources()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refreshSources()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshSources()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    // Prepare names while the main screen is visible; opening the sheet never waits for polygons.
    val pickerCatalog by produceState(LocalityPickerCatalog.empty, allDelegations) {
        value = withContext(Dispatchers.Default) { LocalityRepository.preparePicker(context, allDelegations) }
    }
    val savedDelegation = remember(delegationId, allDelegations) { allDelegations.find { it.id == delegationId } }
    // Every country's cities (owner, 2026-10-19: all regions open), their names folded once, so the picker's search
    // never folds 26,000 names per key.
    val openRegionCities by produceState(CitySearch.Index.EMPTY) {
        value = withContext(Dispatchers.IO) { CitySearch.Index(PlaceCatalogue.get(context).listedRows()) }
    }

    var selectedLocation by remember { mutableStateOf(PrefsManager.getLocationSelection(context)) }
    DisposableEffect(context) {
        val unsubscribe = PrefsManager.observeLocationSelection(context) {
            selectedLocation = PrefsManager.getLocationSelection(context)
        }
        onDispose { unsubscribe() }
    }
    LaunchedEffect(sourcesChecked, allDelegations, selectedLocation) {
        val saved = selectedLocation
        // The source repair is for a Tunisian locality only (6.8).
        if (!sourcesChecked || saved.fromGps || PrefsManager.getPlace(context) !is PrayerPlace.Inm) return@LaunchedEffect
        val id = saved.localityId ?: return@LaunchedEffect
        val sources = allDelegations
        val repaired = withContext(Dispatchers.Default) {
            // Manual groups and raw localities both have retained representatives.
            // Recompute after source changes even when the old source is usable;
            // GPS selections keep the result of their actual fix and are skipped.
            runCatching { LocalityRepository.manualSelection(context, id, saved.manualReferenceId) }.getOrNull()
                ?.takeIf { it.lat != null && it.lng != null }
                ?.let { withAvailablePrayerSource(it, sources) }
        } ?: return@LaunchedEffect
        if (repaired.delegationId == saved.delegationId) return@LaunchedEffect
        val source = sources.find { it.id == repaired.delegationId } ?: return@LaunchedEffect
        if (PrefsManager.getLocationSelection(context) != saved) return@LaunchedEffect
        PrefsManager.setLocality(context, repaired)
        onDelegationSelected(source)
        selectedLocation = PrefsManager.getLocationSelection(context)
    }
    val savedSourceUnavailable = sourcesChecked && PrefsManager.getPlace(context) is PrayerPlace.Inm &&
        selectedLocation.delegationId !in availableIds
    var showSheet by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }

    LaunchedEffect(openRequested) {
        if (openRequested) {
            refreshSources()
            showSheet = true
            onOpenRequestHandled()
        }
    }

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
                    AnalyticsTracker.markDelegationSource(context, "gps_success")
                    onDelegationSelected(result.delegation)
                    PrefsManager.setGpsLocation(context, result)
                    selectedLocation = PrefsManager.getLocationSelection(context)
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

                is DelegationLocationResult.Abroad -> {
                    AnalyticsTracker.markDelegationSource(context, "gps_abroad")
                    val saved = PrefsManager.getPlace(context)
                    val savedLabel = PrefsManager.getPlaceNameSnapshot(context)
                    PrefsManager.setPlace(context, result.place, result.label, fromGps = true)
                    scope.launch { PlaceResolution.onPlaceChanged(context, saved, result.place, savedLabel, result.label) }
                    selectedLocation = PrefsManager.getLocationSelection(context)
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

    val savedPlace = remember(selectedLocation) { PrefsManager.getPlace(context) }
    val abroad = savedPlace as? PrayerPlace.Coordinates
    val locationTitle = if (abroad != null) {
        PrefsManager.getPlaceNameSnapshot(context) ?: stringResource(R.string.location_current_place)
    } else selectedLocation.name
        ?: if (selectedLocation.fromGps) stringResource(R.string.location_current_position)
        else stringResource(R.string.hint_search_delegation)
    val hasLocation = abroad != null || selectedLocation.name != null || selectedLocation.fromGps
    val governorateName = remember(gouvernorats, delegationId) {
        gouvernorats.firstOrNull { gov -> gov.delegations.any { it.id == delegationId } }?.nomAr
    }
    // Where the timetable comes from: the delegation (unless it is already the title)
    // and its governorate, so repeated neighbourhood names stay unambiguous.
    val placeContext = listOfNotNull(
        savedDelegation?.nomAr?.takeIf { it != locationTitle },
        governorateName?.takeIf { it != locationTitle && it != savedDelegation?.nomAr },
    ).joinToString("، ")
    // A place abroad found by GPS is named by its catalogue city («قرب X»), a delegation by its neighbourhood.
    val gpsSource = stringResource(R.string.location_header_from_gps)
        .takeIf { selectedLocation.fromGps && (selectedLocation.name != null || abroad != null) }
    val locationSubtitle = if (abroad != null) {
        listOfNotNull(gpsSource, abroad.countryCode?.let { CitySearch.countryName(PlaceCountry.normalize(it)) }).joinToString(" · ")
    } else listOfNotNull(gpsSource, placeContext.takeIf { it.isNotEmpty() })
        .joinToString(" · ")

    Column(modifier = modifier.testTag(TestTags.LOCATION_PICKER)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Full name, never truncated: a long name wraps instead of being cut off.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.location_change_action),
                    ) {
                        refreshSources()
                        showSheet = true
                    }
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_location),
                        contentDescription = null,
                        tint = GreenPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = locationTitle,
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (hasLocation) GreenPrimaryDark else TextMuted,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_expand_more),
                        contentDescription = null,
                        tint = PrayerSilencePalette.SecondaryText,
                        modifier = Modifier.size(22.dp),
                    )
                }
                if (locationSubtitle.isNotEmpty()) {
                    Text(
                        text = locationSubtitle,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = PrayerSilencePalette.SecondaryText,
                        modifier = Modifier.padding(start = 24.dp),
                    )
                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(GreenPrimary.copy(alpha = 0.08f))
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
                    painter = painterResource(R.drawable.ic_my_location),
                    contentDescription = stringResource(R.string.gps_auto_detect),
                    tint = GreenPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        if (savedSourceUnavailable) {
            Text(
                text = stringResource(if (allDelegations.isEmpty()) {
                    R.string.location_no_current_prayer_data
                } else R.string.location_saved_source_unavailable),
                fontSize = 12.sp,
                color = TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else if (abroad == null && selectedLocation.fromGps && selectedLocation.name == null) {
            // Tunisia only: a delegation found by GPS whose neighbourhood polygons have no name there. Abroad there are
            // no neighbourhoods, and the catalogue city is the name.
            Text(
                text = stringResource(R.string.location_neighborhood_unavailable),
                fontSize = 12.sp,
                color = TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    if (showSheet) {
        LocalityPickerSheet(
            catalog = pickerCatalog,
            gouvernorats = gouvernorats,
            selectedId = selectedLocation.localityId.orEmpty(),
            onDismiss = {
                selectionJob?.cancel()
                showSheet = false
            },
            onSelect = { locality ->
                // A sheet can stay open across a month boundary; selection must
                // use sources with prayer data for the current period.
                selectionJob?.cancel()
                selectionJob = scope.launch {
                    val currentDelegations = withContext(Dispatchers.IO) {
                        GouvernoratRepository.loadAllDelegations(context)
                    }
                    if (!showSheet) return@launch
                    allDelegations = currentDelegations
                    sourcesChecked = true
                    val mapped = withAvailablePrayerSource(locality, currentDelegations)
                    val delegation = mapped?.let { selection -> currentDelegations.firstOrNull { it.id == selection.delegationId } }
                    if (delegation != null) {
                        showSheet = false
                        AnalyticsTracker.markDelegationSource(context, "manual")
                        onDelegationSelected(delegation)
                        PrefsManager.setLocality(context, mapped)
                        selectedLocation = PrefsManager.getLocationSelection(context)
                    } else {
                        Toast.makeText(context, R.string.location_no_match, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            cities = openRegionCities,
            selectedCityId = (savedPlace as? PrayerPlace.Coordinates)?.catalogueId,
            onSelectCity = { row ->
                showSheet = false
                val old = PrefsManager.getPlace(context)
                val oldLabel = PrefsManager.getPlaceNameSnapshot(context)
                val place = PlaceResolution.placeFromCatalogue(context, row)
                val label = PlaceResolution.catalogueLabel(row)
                AnalyticsTracker.markDelegationSource(context, "manual_abroad")
                PrefsManager.setPlace(context, place, label, fromGps = false)
                scope.launch { PlaceResolution.onPlaceChanged(context, old, place, oldLabel, label) }
                selectedLocation = PrefsManager.getLocationSelection(context)
            },
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

@Composable
private fun TodayNextPrayerCard(
    delegationId: Int,
    selectedDate: Long,
    isPhoneSilenced: Boolean,
    hasDnd: Boolean,
    autoSilenceActive: Boolean,
    manualSilenceActive: Boolean,
    manualSilenceMode: ManualSilenceMode,
    manualTargetPrayer: Prayer,
    manualSilenceEndsAtMillis: Long,
    wakeSilenceUntilAlarmActive: Boolean,
    autoSilenceEnabled: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val currentCalendar = remember(currentTimeMillis) {
        Calendar.getInstance().apply { timeInMillis = currentTimeMillis }
    }
    val currentDayMillis = remember(currentTimeMillis) { startOfDayMillis(currentTimeMillis) }
    val timesKey = remember(delegationId, currentDayMillis) { PrayerTimesRepository.timesKey(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentTimeMillis = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val countdown = remember(timesKey, currentTimeMillis) {
        runCatching { resolveNextPrayerCountdown(context, currentTimeMillis) }.getOrNull()
    }
    val activeAutoSilencePrayer = remember(autoSilenceActive, currentTimeMillis) {
        if (autoSilenceActive) SilenceScheduler.currentSilenceWindowPrayer(context) else null
    }
    val silenceReason = remember(
        isPhoneSilenced,
        autoSilenceActive,
        activeAutoSilencePrayer,
        manualSilenceActive,
        manualSilenceMode,
        manualTargetPrayer,
        manualSilenceEndsAtMillis,
        wakeSilenceUntilAlarmActive,
        currentTimeMillis,
    ) {
        resolvePhoneSilenceReason(
            isPhoneSilenced = isPhoneSilenced,
            autoSilenceActive = autoSilenceActive,
            activeAutoSilencePrayer = activeAutoSilencePrayer,
            manualSilenceActive = manualSilenceActive,
            manualSilenceMode = manualSilenceMode,
            manualTargetPrayer = manualTargetPrayer,
            manualSilenceEndsAtMillis = manualSilenceEndsAtMillis,
            wakeSilenceUntilAlarmActive = wakeSilenceUntilAlarmActive,
            currentTimeMillis = currentTimeMillis,
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
            isPhoneSilenced = isPhoneSilenced,
            hasDnd = hasDnd,
            silenceReason = silenceReason,
            autoSilenceEnabled = autoSilenceEnabled,
        )
    }
}

@Composable
private fun PrayerSettingsCard(
    delegationId: Int,
    activity: androidx.appcompat.app.AppCompatActivity,
    onChooseLocation: () -> Unit,
    selectedDate: Long,
    onSelectedDateChange: (Long) -> Unit,
    onConfigChanged: () -> Unit,
    silencePermissionsGranted: Boolean,
    onEnableSilenceMaster: () -> Unit,
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    var sharedSheetOpen by rememberSaveable { mutableStateOf(false) }
    var sharedUndoBefore by remember { mutableStateOf<List<Pair<Prayer, PrayerSilenceConfig>>?>(null) }
    var sharedUndoAfter by remember { mutableStateOf<List<Pair<Prayer, PrayerSilenceConfig>>?>(null) }
    val correctedCalendar = rememberTunisianHijriCalendar()
    val selectedHijriYear = correctedCalendar.date(calendarLocalDate(selectedDate)).year
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val currentCalendar = remember(currentTimeMillis) {
        Calendar.getInstance().apply { timeInMillis = currentTimeMillis }
    }
    val currentDayMillis = remember(currentTimeMillis) { startOfDayMillis(currentTimeMillis) }
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
        // After process death, selectedDate is restored by the parent
        // but lastCurrentDayMillis is re-initialized to currentDayMillis.
        // Detect stale selectedDate that predates today and auto-advance it.
        if (currentDayMillis == lastCurrentDayMillis) {
            if (!isSameCalendarDay(selectedDate, currentDayMillis) &&
                selectedDate < currentDayMillis
            ) {
                onSelectedDateChange(currentDayMillis)
            }
            return@LaunchedEffect
        }
        if (isSameCalendarDay(selectedDate, lastCurrentDayMillis)) {
            onSelectedDateChange(currentDayMillis)
        }
        lastCurrentDayMillis = currentDayMillis
    }

    val selectedCal = remember(selectedDate) {
        Calendar.getInstance().apply { timeInMillis = selectedDate }
    }
    val isToday = remember(selectedDate, currentDayMillis) {
        isSameCalendarDay(selectedDate, currentDayMillis)
    }

    val selectedLocalDate = remember(selectedDate) {
        LocalDate.of(selectedCal.get(Calendar.YEAR), selectedCal.get(Calendar.MONTH) + 1, selectedCal.get(Calendar.DAY_OF_MONTH))
    }
    // The anchors show the phone's clock (D8): the zone is read again at each recomposition (every minute and on
    // resume), so a zone change re-anchors the rows as it does the hero and the night card.
    val phoneZone = java.time.ZoneId.systemDefault()
    // The five adhans of the prayer day shown, as instants (6.6).
    val dailyAnchors = remember(delegationId, selectedLocalDate, refreshTick, phoneZone) {
        runCatching { prayerTimelineAnchors(context, selectedLocalDate) }.getOrDefault(emptyMap())
    }
    // Dhuhr in the phone's clock, for the Eid rows' cut-off (Tunisia only, A10).
    val dhuhrClock = dailyAnchors[Prayer.DHUHR]?.let { Math.floorMod(it.minutes, 1440) } ?: (13 * 60)

    val canGoBack = remember(delegationId, selectedDate) {
        val prev = Calendar.getInstance().apply {
            timeInMillis = selectedDate
            add(Calendar.DAY_OF_MONTH, -1)
        }
        LocalDate.of(prev.get(Calendar.YEAR), prev.get(Calendar.MONTH) + 1, prev.get(Calendar.DAY_OF_MONTH)) in
            PrayerTimesRepository.getDateRange(PrayerTimesRepository.place(context))
    }
    val canGoForward = remember(delegationId, selectedDate) {
        val next = Calendar.getInstance().apply {
            timeInMillis = selectedDate
            add(Calendar.DAY_OF_MONTH, 1)
        }
        LocalDate.of(next.get(Calendar.YEAR), next.get(Calendar.MONTH) + 1, next.get(Calendar.DAY_OF_MONTH)) in
            PrayerTimesRepository.getDateRange(PrayerTimesRepository.place(context))
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

    val isAidFitr = remember(selectedDate, dailyAnchors, currentTimeMillis, correctedCalendar) {
        // Eid stays Tunisian (A10): a place abroad shows no Eid row.
        if (PrayerTimesRepository.place(context) !is com.tunisianprayertimes.place.PrayerPlace.Inm) return@remember false
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
            dhuhrHour = dhuhrClock / 60,
            dhuhrMinute = dhuhrClock % 60,
            isToday = isToday,
        )
    }
    val isAidAdha = remember(selectedDate, dailyAnchors, currentTimeMillis, correctedCalendar) {
        // Eid stays Tunisian (A10): a place abroad shows no Eid row.
        if (PrayerTimesRepository.place(context) !is com.tunisianprayertimes.place.PrayerPlace.Inm) return@remember false
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
            dhuhrHour = dhuhrClock / 60,
            dhuhrMinute = dhuhrClock % 60,
            isToday = isToday,
        )
    }

    // Jumu'a and Eid times are edited from their rows: shown in the phone's clock, stored in the place's (6.6).
    var silenceTimesRevision by remember { mutableIntStateOf(0) }
    // Jumu'a's chooser (6.9, A18): the default, an hour before Asr (about one Tunisian user in ten), or a fixed time.
    var jumuaChooserOpen by remember { mutableStateOf(false) }

    // The next of the day's prayers, today only, by instant (Jumu'a in Dhuhr's place on a Friday).
    val nextPrayer = if (isToday) {
        val now = java.time.Instant.ofEpochMilli(currentTimeMillis)
        PrayerTimesRepository.prayerOccurrences(context, selectedLocalDate).firstOrNull { it.at > now }?.prayer
    } else null

    val dailyPrayers = remember { listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA) }
    val sectionPrayers = remember(isAidFitr, isAidAdha) {
        buildList {
            addAll(dailyPrayers)
            add(Prayer.JOMOAA)
            if (isAidFitr) add(Prayer.AID_FITR)
            if (isAidAdha) add(Prayer.AID_ADHA)
        }
    }
    // The editors' anchors (6.9, D8): each adhan as an instant; Jumu'a on the Friday on or after the date shown.
    val sectionAnchors: Map<Prayer, PrayerTimelineAnchor> = remember(
        delegationId, selectedLocalDate, refreshTick, silenceTimesRevision, isAidFitr, isAidAdha, selectedHijriYear, correctedCalendar, phoneZone,
    ) {
        val place = PrayerTimesRepository.place(context)
        buildMap {
            putAll(dailyAnchors)
            jumuaTimelineAnchor(context, selectedLocalDate, place)?.let { put(Prayer.JOMOAA, it) }
            if (isAidFitr) {
                eidTimelineAnchor(context, Prayer.AID_FITR, RamadanOverrideChecker.getEidFitrDate(selectedHijriYear), place)?.let { put(Prayer.AID_FITR, it) }
            }
            if (isAidAdha) {
                eidTimelineAnchor(context, Prayer.AID_ADHA, RamadanOverrideChecker.getEidAdhaDate(selectedHijriYear), place)?.let { put(Prayer.AID_ADHA, it) }
            }
        }
    }
    // The next adhan after each row's, for the overlap warning.
    val nextAnchors: Map<Prayer, PrayerTimelineAnchor?> = remember(sectionAnchors) {
        sectionAnchors.mapValues { (_, anchor) -> nextTimelineAnchor(context, anchor) }
    }
    val timelineScale = remember(sectionAnchors, refreshTick, sectionPrayers) {
        val entries = sectionPrayers.mapNotNull { prayer ->
            sectionAnchors[prayer]
                ?.let { anchor -> anchor to resolvePrayerTimelineWindow(anchor, PrefsManager.getEffectiveConfig(context, prayer)) }
                ?.takeIf { (_, window) -> window.endMinutes >= window.startMinutes }
        }
        sharedPrayerTimelineScale(entries)
    }
    val anyPrayerEnabled = remember(selectedDate, refreshTick) {
        dailyPrayers.any { PrefsManager.isPrayerSilenceEnabled(context, it) }
    }
    LaunchedEffect(sharedUndoAfter) {
        if (sharedUndoAfter != null) {
            delay(6_000L)
            sharedUndoBefore = null
            sharedUndoAfter = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.PRAYER_SETTINGS)
                .clip(RoundedCornerShape(PrayerSilenceDimens.ContainerCorner))
                .background(PrayerSilencePalette.Surface)
                .border(
                    width = 1.dp,
                    color = PrayerSilencePalette.SoftBorder,
                    shape = RoundedCornerShape(PrayerSilenceDimens.ContainerCorner),
                ),
        ) {
            Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
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
                        onSelectedDateChange(cal.timeInMillis)
                    },
                    onNext = {
                        val cal = Calendar.getInstance().apply {
                            timeInMillis = selectedDate
                            add(Calendar.DAY_OF_MONTH, 1)
                        }
                        onSelectedDateChange(cal.timeInMillis)
                    },
                    onDateSelected = { millis -> onSelectedDateChange(millis) }
                )
            }
            // 6.11: the official zone the place maps to, and the place's UTC offset when the phone keeps another zone.
            val listNotes = remember(delegationId, selectedLocalDate) { runCatching { timesListNotes(context, selectedLocalDate) }.getOrNull() }
            listNotes?.zoneName?.let { zone ->
                Text(
                    text = stringResource(R.string.list_zone_line, zone),
                    fontSize = 12.sp,
                    color = PrayerSilencePalette.SecondaryText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("times_zone_line"),
                )
            }
            listNotes?.placeOffset?.let { offset ->
                Text(
                    text = stringResource(R.string.list_place_offset, offset),
                    fontSize = 12.sp,
                    color = PrayerSilencePalette.SecondaryText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("times_place_offset"),
                )
            }

            if (dailyAnchors.isNotEmpty()) {
                sectionPrayers.forEachIndexed { index, prayer ->
                    if (index > 0) {
                        HorizontalDivider(color = PrayerSilencePalette.SoftBorder, thickness = 1.dp)
                    }
                    val anchor = sectionAnchors[prayer]
                    val nextAnchor = nextAnchors[prayer]
                    val onTimeClick: (() -> Unit)? = when (prayer) {
                        Prayer.JOMOAA -> { { jumuaChooserOpen = true } }
                        Prayer.AID_FITR -> {
                            {
                                val eid = sectionAnchors[Prayer.AID_FITR]
                                val shown = eid?.let { Math.floorMod(it.minutes, 1440) } ?: (7 * 60)
                                val picker = MaterialTimePicker.Builder()
                                    .setTimeFormat(TimeFormat.CLOCK_24H)
                                    .setHour(shown / 60)
                                    .setMinute(shown % 60)
                                    .setTitleText(context.getString(R.string.pick_aid_fitr_time))
                                    .build()
                                picker.addOnPositiveButtonClickListener {
                                    val clock = pickedPlaceClock(eid, picker.hour, picker.minute)
                                    PrefsManager.setAidFitrTime(context, clock.hour, clock.minute)
                                    silenceTimesRevision++
                                    refreshTick++
                                    onConfigChanged()
                                }
                                picker.show(activity.supportFragmentManager, "aid_fitr_time_picker")
                            }
                        }
                        Prayer.AID_ADHA -> {
                            {
                                val eid = sectionAnchors[Prayer.AID_ADHA]
                                val shown = eid?.let { Math.floorMod(it.minutes, 1440) } ?: (7 * 60)
                                val picker = MaterialTimePicker.Builder()
                                    .setTimeFormat(TimeFormat.CLOCK_24H)
                                    .setHour(shown / 60)
                                    .setMinute(shown % 60)
                                    .setTitleText(context.getString(R.string.pick_aid_adha_time))
                                    .build()
                                picker.addOnPositiveButtonClickListener {
                                    val clock = pickedPlaceClock(eid, picker.hour, picker.minute)
                                    PrefsManager.setAidAdhaTime(context, clock.hour, clock.minute)
                                    silenceTimesRevision++
                                    refreshTick++
                                    onConfigChanged()
                                }
                                picker.show(activity.supportFragmentManager, "aid_adha_time_picker")
                            }
                        }
                        else -> null
                    }
                    key(prayer) {
                        PrayerRow(
                            prayer = prayer,
                            prayerName = prayerNames[prayer] ?: prayer.name,
                            anchor = anchor,
                            nextAnchor = nextAnchor,
                            isNextPrayer = prayer == nextPrayer && prayer != Prayer.JOMOAA,
                            onConfigChanged = {
                                refreshTick++
                                onConfigChanged()
                            },
                            onPrayerTimeClick = onTimeClick,
                            timelineScale = timelineScale,
                            silencePermissionsGranted = silencePermissionsGranted,
                            onEnableSilenceMaster = {
                                refreshTick++
                                onEnableSilenceMaster()
                            },
                        )
                    }
                }

                if (jumuaChooserOpen) {
                    // Per country abroad (6.9, A18), today's keys in Tunisia; times shown in the phone's clock, as the rows.
                    val place = PrayerTimesRepository.place(context)
                    val jumua = sectionAnchors[Prayer.JOMOAA]
                    val friday = jumua?.prayerDay ?: selectedLocalDate
                    val fixed = PrefsManager.getJumuaTime(context, place)
                    fun shown(at: java.time.Instant?) = at?.let { formatTimeOfDay(it.toEpochMilli()) }
                    fun saved() {
                        silenceTimesRevision++
                        refreshTick++
                        onConfigChanged()
                    }
                    JumuaTimeDialog(
                        current = when {
                            fixed != null -> JumuaMode.FIXED
                            PrefsManager.getJumuaMinutesBeforeAsr(context, place) != null -> JumuaMode.BEFORE_ASR
                            else -> JumuaMode.DEFAULT
                        },
                        defaultAt = shown(PrayerTimesRepository.defaultJumuaTime(context, place, friday)),
                        defaultHint = if (place is PrayerPlace.Inm) stringResource(R.string.jomoaa_time_default_tunisia_hint) else null,
                        beforeAsrAt = shown(PrayerTimesRepository.jumuaBeforeAsr(context, friday, place, PrayerTimesRepository.JUMUA_MINUTES_BEFORE_ASR)),
                        fixedAt = if (fixed != null) shown(jumua?.adhan) else null,
                        onPick = { mode ->
                            jumuaChooserOpen = false
                            when (mode) {
                                JumuaMode.DEFAULT -> {
                                    PrefsManager.setJumuaTime(context, place, null)
                                    saved()
                                }
                                JumuaMode.BEFORE_ASR -> {
                                    PrefsManager.setJumuaMinutesBeforeAsr(context, place, PrayerTimesRepository.JUMUA_MINUTES_BEFORE_ASR)
                                    saved()
                                }
                                JumuaMode.FIXED -> {
                                    val start = jumua?.let { Math.floorMod(it.minutes, 1440) } ?: (13 * 60)
                                    val picker = MaterialTimePicker.Builder()
                                        .setTimeFormat(TimeFormat.CLOCK_24H)
                                        .setHour(start / 60)
                                        .setMinute(start % 60)
                                        .setTitleText(context.getString(R.string.pick_jomoaa_time))
                                        .build()
                                    picker.addOnPositiveButtonClickListener {
                                        PrefsManager.setJumuaTime(context, place, pickedPlaceClock(jumua, picker.hour, picker.minute))
                                        saved()
                                    }
                                    picker.show(activity.supportFragmentManager, "jomoaa_time_picker")
                                }
                            }
                        },
                        onDismiss = { jumuaChooserOpen = false },
                    )
                }

                if (!silencePermissionsGranted && anyPrayerEnabled) {
                    Text(
                        text = stringResource(R.string.prayer_silence_permission_needed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SilenceRed.copy(alpha = 0.06f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = 12.sp,
                        color = SilenceRed,
                        lineHeight = 17.sp,
                    )
                }

                if (sharedUndoBefore != null && sharedUndoAfter != null) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(GreenPrimary.copy(alpha = 0.06f))
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.prayer_silence_shared_applied),
                            modifier = Modifier.align(Alignment.CenterVertically),
                            fontSize = 12.sp,
                            color = TextMuted,
                        )
                        TextButton(
                            onClick = {
                                sharedUndoBefore?.forEach { (prayer, config) ->
                                    persistPrayerSilenceConfig(context, prayer, config, sectionAnchors[prayer])
                                }
                                sharedUndoBefore = null
                                sharedUndoAfter = null
                                refreshTick++
                                onConfigChanged()
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.prayer_silence_undo), color = GreenPrimaryDark)
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.padding(vertical = 12.dp)) {
                    PrayerDataUnavailable(
                        isToday = isToday,
                        onReturnToToday = { onSelectedDateChange(startOfDayMillis(System.currentTimeMillis())) },
                        onChooseLocation = onChooseLocation,
                    )
                }
            }
        }
    }

    if (sharedSheetOpen) {
        val sharedAnchors = dailyPrayers.mapNotNull { prayer -> sectionAnchors[prayer]?.let { prayer to it } }.toMap()
        val sharedInitial = remember(sharedSheetOpen, refreshTick, selectedDate, delegationId) {
            val entries = sharedAnchors.map { (prayer, anchor) ->
                anchor to PrefsManager.getEffectiveConfig(context, prayer)
            }
            fun uniformStart(): Pair<EndpointRuleMode, Int>? {
                if (entries.isEmpty()) return null
                if (entries.all { (_, config) -> config.startRuleMode() == EndpointRuleMode.ADHAN }) {
                    val offsets = entries.map { (anchor, config) ->
                        anchor.offsetAt(resolvePrayerTimelineWindow(anchor, config).startMinutes)
                    }
                    return offsets.distinct().singleOrNull()?.let { EndpointRuleMode.ADHAN to it }
                }
                if (entries.all { (_, config) -> config.startRuleMode() == EndpointRuleMode.FIXED_TIME }) {
                    // Clock times as the phone shows them (D8).
                    val clocks = entries.map { (anchor, config) -> Math.floorMod(resolvePrayerTimelineWindow(anchor, config).startMinutes, 1440) }
                    return clocks.distinct().singleOrNull()?.let { EndpointRuleMode.FIXED_TIME to it }
                }
                return null
            }
            fun uniformEnd(): Pair<EndpointRuleMode, Int>? {
                if (entries.isEmpty()) return null
                if (entries.all { (_, config) -> config.endRuleMode() == EndpointRuleMode.ADHAN }) {
                    val offsets = entries.map { (anchor, config) ->
                        anchor.offsetAt(resolvePrayerTimelineWindow(anchor, config).endMinutes)
                    }
                    return offsets.distinct().singleOrNull()?.let { EndpointRuleMode.ADHAN to it }
                }
                if (entries.all { (_, config) -> config.endRuleMode() == EndpointRuleMode.FIXED_TIME }) {
                    val clocks = entries.map { (anchor, config) -> Math.floorMod(resolvePrayerTimelineWindow(anchor, config).endMinutes, 1440) }
                    return clocks.distinct().singleOrNull()?.let { EndpointRuleMode.FIXED_TIME to it }
                }
                return null
            }
            val start = uniformStart() ?: (EndpointRuleMode.ADHAN to 0)
            val end = uniformEnd() ?: (EndpointRuleMode.ADHAN to 30)
            val fallbackStartClock = entries.firstOrNull()?.let { (anchor, config) ->
                Math.floorMod(resolvePrayerTimelineWindow(anchor, config).startMinutes, 1440)
            } ?: 0
            val fallbackEndClock = entries.firstOrNull()?.let { (anchor, config) ->
                Math.floorMod(resolvePrayerTimelineWindow(anchor, config).endMinutes, 1440)
            } ?: 0
            SharedSilenceRuleDraft(
                startMode = start.first,
                startOffsetMinutes = if (start.first == EndpointRuleMode.ADHAN) start.second else 0,
                startClockMinutes = if (start.first == EndpointRuleMode.FIXED_TIME) start.second else fallbackStartClock,
                endMode = end.first,
                endOffsetMinutes = if (end.first == EndpointRuleMode.ADHAN) end.second else 30,
                endClockMinutes = if (end.first == EndpointRuleMode.FIXED_TIME) end.second else fallbackEndClock,
            )
        }
        SharedSilenceSettingsSheet(
            prayerNames = dailyPrayers.map { it to (prayerNames[it] ?: it.name) },
            anchors = sharedAnchors,
            initialDraft = sharedInitial,
            onDismiss = { sharedSheetOpen = false },
            onApply = { prayers, draft ->
                val previous = prayers.map { it to PrefsManager.getEffectiveConfig(context, it) }
                val intended = prayers.mapNotNull { prayer ->
                    val anchor = sharedAnchors[prayer] ?: return@mapNotNull null
                    prayer to draft.applyTo(PrefsManager.getEffectiveConfig(context, prayer), anchor)
                }.toMap()
                intended.forEach { (prayer, config) ->
                    persistPrayerSilenceConfig(context, prayer, config, sharedAnchors[prayer])
                }
                val persisted = intended.size == prayers.size && intended.all { (prayer, config) ->
                    val anchor = sharedAnchors.getValue(prayer)
                    resolvePrayerTimelineWindow(anchor, PrefsManager.getEffectiveConfig(context, prayer)) == resolvePrayerTimelineWindow(anchor, config)
                }
                if (!persisted) {
                    previous.forEach { (prayer, config) ->
                        persistPrayerSilenceConfig(context, prayer, config, sharedAnchors[prayer])
                    }
                    sharedUndoBefore = null
                    sharedUndoAfter = null
                    Toast.makeText(
                        context,
                        context.getString(R.string.prayer_silence_save_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    sharedUndoBefore = previous
                    sharedUndoAfter = intended.map { (prayer, config) -> prayer to config }
                }
                sharedSheetOpen = false
                refreshTick++
                onConfigChanged()
            },
        )
    }
}

@Composable
private fun SunriseAndNightTimesCard(
    delegationId: Int,
    selectedDate: Long,
    wakeAlarms: List<PrayerWakeConfig>? = null,
    onEditWakeAlarm: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val currentDayMillis = remember(currentTimeMillis) { startOfDayMillis(currentTimeMillis) }
    val selectedDayMillis = remember(selectedDate) { startOfDayMillis(selectedDate) }
    val selectedCalendar = remember(selectedDayMillis) {
        Calendar.getInstance().apply { timeInMillis = selectedDayMillis }
    }
    val previousCalendar = remember(selectedDayMillis) {
        (selectedCalendar.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    }
    val nextCalendar = remember(selectedDayMillis) {
        (selectedCalendar.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    }

    // The prayer days around the date shown, as instants (6.6), shown in the phone's clock (D8).
    val selectedLocal = remember(selectedDayMillis) {
        LocalDate.of(selectedCalendar.get(Calendar.YEAR), selectedCalendar.get(Calendar.MONTH) + 1, selectedCalendar.get(Calendar.DAY_OF_MONTH))
    }
    val selectedTimes = remember(delegationId, selectedDayMillis) {
        runCatching { PrayerTimesRepository.prayerDay(context, selectedLocal) }.getOrNull()
    }
    val previousTimes = remember(delegationId, selectedDayMillis) {
        runCatching { PrayerTimesRepository.prayerDay(context, selectedLocal.minusDays(1)) }.getOrNull()
    }
    val nextTimes = remember(delegationId, selectedDayMillis) {
        runCatching { PrayerTimesRepository.prayerDay(context, selectedLocal.plusDays(1)) }.getOrNull()
    }
    // The sun's altitude at the saved place: a delegation's INM point, or a place abroad's own point.
    val solarLocation = remember(context, delegationId) {
        when (val place = PrayerTimesRepository.place(context)) {
            is com.tunisianprayertimes.place.PrayerPlace.Inm -> PrayerDataLoader.prayerTimes(context).location(delegationId)
            is com.tunisianprayertimes.place.PrayerPlace.Coordinates ->
                com.tunisianprayertimes.InmLocation(place.point.latitude, place.point.longitude, (place.elevationM ?: 0).toDouble())
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = System.currentTimeMillis()
                currentTimeMillis = now
                // A tenth of a degree can change within a minute. Stop refreshing in the background.
                delay(5_000L - now % 5_000L)
            }
        }
    }

    val selectedTimesForDay = selectedTimes ?: return
    // A day overview retains earlier rings, including a one-off whose time has already passed.
    val timelineAlarms = remember(wakeAlarms, delegationId, selectedDayMillis) {
        wakeAlarms.orEmpty()
            .flatMap { wakeAlarm ->
                scheduledWakeAlarmTriggersForDay(context, delegationId, wakeAlarm, selectedCalendar).map { trigger ->
                    NightTimelineAlarm(
                        id = listOfNotNull(trigger.alarmId, trigger.subAlarmId, trigger.triggerAtMillis.toString()).joinToString(":"),
                        wakeAlarmId = trigger.alarmId,
                        title = wakeAlarm.title.ifBlank {
                            when (wakeAlarm.mainAlarm.mode) {
                                WakeMainAlarmMode.FROM_NOW -> context.getString(R.string.wake_editor_mode_from_now)
                                WakeMainAlarmMode.FIXED_TIME -> context.getString(R.string.wake_editor_mode_fixed)
                                WakeMainAlarmMode.PRAYER_RELATIVE -> prayerName(context, trigger.effectivePrayer)
                            }
                        },
                        time = formatTimeOfDay(trigger.triggerAtMillis),
                        minuteOfDay = minuteOfDay(trigger.triggerAtMillis),
                        isSubAlarm = trigger.isSubAlarm,
                    )
                }
            }
            .sortedBy { alarm -> alarm.minuteOfDay }
    }
    fun atMillis(day: com.tunisianprayertimes.engine.PrayerDay?, event: com.tunisianprayertimes.engine.TimeEvent): Long? =
        day?.get(event)?.at?.toEpochMilli()
    val selectedFajrMillis = atMillis(selectedTimesForDay, com.tunisianprayertimes.engine.TimeEvent.FAJR) ?: return
    val isToday = isSameCalendarDay(selectedDayMillis, currentDayMillis)
    val usePreviousNight = isToday &&
        currentTimeMillis < selectedFajrMillis && previousTimes != null
    val nightStartTimes = if (usePreviousNight) requireNotNull(previousTimes) else selectedTimesForDay
    val nightEndTimes = if (usePreviousNight) selectedTimesForDay else (nextTimes ?: return)

    val nightStartMillis = atMillis(nightStartTimes, com.tunisianprayertimes.engine.TimeEvent.MAGHRIB) ?: return
    val nightEndMillis = atMillis(nightEndTimes, com.tunisianprayertimes.engine.TimeEvent.FAJR) ?: return
    if (nightEndMillis <= nightStartMillis) return

    val thirdDurationMillis = (nightEndMillis - nightStartMillis) / 3L
    val firstEndMillis = nightStartMillis + thirdDurationMillis
    val secondEndMillis = nightStartMillis + thirdDurationMillis * 2L
    val activeThird = when {
        !isToday -> null
        currentTimeMillis >= nightStartMillis && currentTimeMillis < firstEndMillis -> 0
        currentTimeMillis >= firstEndMillis && currentTimeMillis < secondEndMillis -> 1
        currentTimeMillis >= secondEndMillis && currentTimeMillis < nightEndMillis -> 2
        else -> null
    }
    val statusText = if (!isToday) stringResource(R.string.prayer_timeline_selected_night) else when (activeThird) {
        0 -> "الآن: الثلث الأول"
        1 -> "الآن: الثلث الثاني"
        2 -> "الآن: الثلث الأخير"
        else -> "الليلة القادمة"
    }
    // A time's minute counted from the selected day's midnight in the phone's clock: an Isha after midnight
    // (Paris in June) sorts after Maghrib in the details, at the same place on the dial.
    fun minuteOfSelectedDay(millis: Long): Int {
        val date = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return minuteOfDay(millis) + 1440 * java.time.temporal.ChronoUnit.DAYS.between(selectedLocal, date).toInt()
    }
    fun timelinePrayer(prayer: Prayer, millis: Long): NightTimelinePrayer = NightTimelinePrayer(
        prayer = prayer,
        title = prayerName(context, prayer),
        time = formatTimeOfDay(millis),
        minuteOfDay = minuteOfSelectedDay(millis),
    )
    val isFriday = selectedCalendar.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
    val dhuhrMillis = atMillis(selectedTimesForDay, com.tunisianprayertimes.engine.TimeEvent.DHUHR) ?: return
    // Jumu'a at its resolved time on a Friday (A18).
    val jumuaMillis = if (isFriday) PrayerTimesRepository.jumuaTime(context, selectedLocal)?.toEpochMilli() else null
    // The day overview shows the selected day's own prayers.
    val timelinePrayers = listOfNotNull(
        timelinePrayer(Prayer.FAJR, selectedFajrMillis),
        timelinePrayer(if (jumuaMillis != null) Prayer.JOMOAA else Prayer.DHUHR, jumuaMillis ?: dhuhrMillis),
        atMillis(selectedTimesForDay, com.tunisianprayertimes.engine.TimeEvent.ASR)?.let { timelinePrayer(Prayer.ASR, it) },
        atMillis(selectedTimesForDay, com.tunisianprayertimes.engine.TimeEvent.MAGHRIB)?.let { timelinePrayer(Prayer.MAGHRIB, it) },
        atMillis(selectedTimesForDay, com.tunisianprayertimes.engine.TimeEvent.ISHA)?.let { timelinePrayer(Prayer.ISHA, it) },
    )
    val nightStartLabel = if (usePreviousNight) "أمس" else if (isToday) "اليوم" else formatWakeAlarmDate(nightStartMillis)
    val nightEndLabel = if (usePreviousNight) "اليوم" else if (isToday) "غدًا" else formatWakeAlarmDate(nightEndMillis)
    val sunriseMillis = atMillis(selectedTimesForDay, com.tunisianprayertimes.engine.TimeEvent.SUNRISE) ?: return
    val data = NightTimesCardData(
        selectedDateLabel = formatWakeAlarmDate(selectedDayMillis),
        currentTimeText = formatTimeOfDay(currentTimeMillis),
        sunAltitudeDeg = if (isToday) solarLocation?.let { solarAltitudeDegAt(it, currentTimeMillis) } else null,
        sunriseTime = formatTimeOfDay(sunriseMillis),
        sunriseMinuteOfDay = minuteOfSelectedDay(sunriseMillis),
        currentMinuteOfDay = minuteOfDay(currentTimeMillis),
        showCurrentTime = isToday,
        nightRange = "من ${formatTimeOfDay(nightStartMillis)} $nightStartLabel إلى ${formatTimeOfDay(nightEndMillis)} $nightEndLabel",
        statusText = statusText,
        activeThird = activeThird,
        thirds = listOf(
            NightThirdSegment(
                title = "الأول",
                startTime = formatTimeOfDay(nightStartMillis),
                endTime = formatTimeOfDay(firstEndMillis),
                startMinuteOfDay = minuteOfDay(nightStartMillis),
                endMinuteOfDay = minuteOfDay(firstEndMillis),
                active = activeThird == 0,
            ),
            NightThirdSegment(
                title = "الثاني",
                startTime = formatTimeOfDay(firstEndMillis),
                endTime = formatTimeOfDay(secondEndMillis),
                startMinuteOfDay = minuteOfDay(firstEndMillis),
                endMinuteOfDay = minuteOfDay(secondEndMillis),
                active = activeThird == 1,
            ),
            NightThirdSegment(
                title = "الأخير",
                startTime = formatTimeOfDay(secondEndMillis),
                endTime = formatTimeOfDay(nightEndMillis),
                startMinuteOfDay = minuteOfDay(secondEndMillis),
                endMinuteOfDay = minuteOfDay(nightEndMillis),
                active = activeThird == 2,
            ),
        ),
        timelinePrayers = timelinePrayers,
        timelineAlarms = timelineAlarms,
    )
    PrayerDayOverviewCard(data = data, onEditWakeAlarm = onEditWakeAlarm)
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
    onAddFajrAlarm: (PrayerWakeConfig) -> Unit,
) {
    val context = LocalContext.current
    val wakeRepository = remember(context) { PrayerWakeRepository(context) }
    var fajrSuggestionDismissed by remember {
        mutableStateOf(PrefsManager.isFajrAlarmSuggestionDismissed(context))
    }
    var fajrSetupOpen by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    // Moves on the minute, when rings are due, and whenever the state below changes, so countdowns count from
    // the moment shown.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var alarmPendingDeletion by remember { mutableStateOf<PrayerWakeConfig?>(null) }
    // Every stored alarm, a finished one-off included: its occurrence can still be in progress.
    val storedWakeAlarms = wakeAlarms.orEmpty()
    // An occurrence is in progress from its earliest ring to its latest, so the windows are worked out once a day.
    val todayStartMillis = startOfDayMillis(nowMillis)
    val windowsByAlarm = remember(storedWakeAlarms, delegationId, todayStartMillis) {
        storedWakeAlarms.associate { alarm ->
            alarm.id to WakeAlarmScheduler.occurrenceWindows(context, alarm)
        }
    }
    var nextTriggers by remember(storedWakeAlarms, delegationId) {
        mutableStateOf(nextWakeAlarmTriggersById(context, delegationId, storedWakeAlarms))
    }
    var runningOccurrences by remember(storedWakeAlarms, windowsByAlarm) {
        mutableStateOf(runningWakeOccurrences(context, storedWakeAlarms, windowsByAlarm))
    }
    // Checked every second: a ring time passing, an alert stopped, or an awake check scheduled, started or
    // confirmed moves the hero and the rows on together. The awake check's start and end re-check at once.
    LaunchedEffect(storedWakeAlarms, delegationId, windowsByAlarm, awakeCheckRunning) {
        while (true) {
            val now = System.currentTimeMillis()
            val running = runningWakeOccurrences(context, storedWakeAlarms, windowsByAlarm, now)
            val ringPassed = nextTriggers.values.any { trigger -> trigger != null && trigger.triggerAtMillis <= now }
            if (ringPassed) {
                nextTriggers = nextWakeAlarmTriggersById(context, delegationId, storedWakeAlarms)
            }
            if (ringPassed || running != runningOccurrences || now / 60_000L != nowMillis / 60_000L) {
                nowMillis = now
            }
            runningOccurrences = running
            delay(1_000L)
        }
    }
    val displayState = wakeAlarmDisplayState(wakeAlarms, nowMillis, inProgressAlarmIds = runningOccurrences.keys)
    val loadedWakeAlarms = displayState.visibleWakeAlarms
    // The hero and every row read the same state, so they cannot tell different stories.
    val nowByAlarm = loadedWakeAlarms.associate { alarm ->
        alarm.id to wakeAlarmNow(nextTriggers[alarm.id], runningOccurrences[alarm.id])
    }
    // The hero shows what happens next across the alarms: a ring going or a check asking first, then whatever is
    // late or nearest, whether it belongs to an occurrence in progress or to the next one.
    val heroAlarm = loadedWakeAlarms
        .filter { alarm -> alarm.enabled }
        .mapNotNull { alarm -> nowByAlarm[alarm.id]?.let { alarmNow -> alarm to alarmNow } }
        .minByOrNull { (_, alarmNow) -> alarmNow.focusAtMillis(nowMillis) }
    if (fajrSetupOpen) {
        FajrAlarmSetupSheet(
            delegationId = delegationId,
            nowMillis = nowMillis,
            onAdd = onAddFajrAlarm,
            onDismiss = { fajrSetupOpen = false },
        )
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

    // Each item above the list carries its own 12dp gap below it, so an item that animates away
    // takes its gap with it instead of leaving a spacedBy gap that snaps shut after the exit.
    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        if (heroAlarm != null) {
            val (alarm, alarmNow) = heroAlarm
            Box(modifier = Modifier.padding(bottom = 12.dp)) {
                WakeNextAlarmPanel(
                    wakeConfig = alarm,
                    alarmNow = alarmNow,
                    nowMillis = nowMillis,
                )
            }
        } else if (loadedWakeAlarms.isNotEmpty() && loadedWakeAlarms.none { alarm -> alarm.enabled }) {
            // The hero stays when every alarm is off, and says so rather than showing an alarm that won't ring.
            Box(modifier = Modifier.padding(bottom = 12.dp)) {
                WakeNoEnabledAlarmPanel()
            }
        }

        AnimatedVisibility(
            visible = awakeCheckRunning,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Box(modifier = Modifier.padding(bottom = 12.dp)) {
                AwakeCheckBanner(onConfirm = onConfirmAwake)
            }
        }

        // Waits for the stored alarms, so it never flashes before a Fajr alarm loads.
        val offerFajrAlarm = !fajrSuggestionDismissed && wakeAlarms?.hasFajrWakeAlarm() == false
        // A new user meets the offer inside the start card; anyone with alarms gets the small row.
        AnimatedVisibility(
            visible = offerFajrAlarm && !displayState.showEmptyState,
            enter = EnterTransition.None,
            // Fade first, then close the space in a fixed short time from the bottom, so the card is
            // gone before its rounded edge is clipped and there is no long spring tail.
            exit = fadeOut(tween(durationMillis = 120)) + shrinkVertically(
                animationSpec = tween(durationMillis = 220, delayMillis = 60, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top,
            ),
        ) {
            FajrAlarmSuggestionRow(
                modifier = Modifier.padding(bottom = 12.dp),
                onOpen = { fajrSetupOpen = true },
                onClose = {
                    PrefsManager.setFajrAlarmSuggestionDismissed(context)
                    fajrSuggestionDismissed = true
                },
            )
        }

        if (displayState.showEmptyState) {
            WakeAlarmEmptyState(
                onPresetSelected = onPresetSelected,
                onFajrSetup = if (offerFajrAlarm) {
                    { fajrSetupOpen = true }
                } else {
                    null
                },
            )
        }

        if (loadedWakeAlarms.isNotEmpty()) {
            WakeAlarmListPanel(
                wakeAlarms = loadedWakeAlarms,
                nowByAlarm = nowByAlarm,
                nowMillis = nowMillis,
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
                onSkipNext = { alarm ->
                    coroutineScope.launch {
                        WakeAlarmScheduler.toggleSkipNextWakeOccurrence(context, alarm.id)
                    }
                },
                onStop = { alarm ->
                    coroutineScope.launch {
                        WakeAlarmScheduler.stopWakeAlarm(context, alarm.id)
                        onConfigChanged()
                    }
                },
            )
            // Room for the floating + at the end of the list, so the last alarm's buttons can scroll clear of it.
            Spacer(
                modifier = Modifier.height(
                    WakeAlarmAddButtonBottomPadding - MainContentBottomPadding + WakeAlarmAddButtonSize,
                ),
            )
        }
    }
}

@Composable
private fun WakeAlarmFloatingAddButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)

    Button(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer {
                shadowElevation = WakeAlarmAddButtonShadowElevation.toPx()
                this.shape = shape
                clip = false
            }
            .testTag(TestTags.WAKE_ALARM_ADD_BUTTON)
            .size(WakeAlarmAddButtonSize),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = GreenPrimary,
            disabledContainerColor = GreenPrimary,
            disabledContentColor = Color.White,
        ),
        contentPadding = PaddingValues(0.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = stringResource(R.string.wake_quick_add_title),
            tint = Color.White,
            modifier = Modifier.size(30.dp),
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
        Icon(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = null,
            tint = if (recommended) GreenPrimary else TextMuted,
            modifier = Modifier
                .padding(start = 10.dp)
                .size(20.dp),
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
            fixedTime = ClockTime(DEFAULT_FIXED_ALARM_HOUR, DEFAULT_FIXED_ALARM_MINUTE),
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

// A second tap on the skip/stop button, meant for the action it just had, must not trigger the one it turns into.
private const val WAKE_ACTION_DEBOUNCE_MILLIS = 1_200L

/** The ring each alarm would sound next by the clock, an alarm that is off included so its row can still show it. */
private fun nextWakeAlarmTriggersById(
    context: android.content.Context,
    delegationId: Int,
    wakeAlarms: List<PrayerWakeConfig>,
): Map<String, WakeAlarmComputer.ScheduledWakeTrigger?> =
    wakeAlarms.associate { alarm ->
        alarm.id to nextWakeAlarmTrigger(context, delegationId, alarm.copy(enabled = true))
    }

private fun runningWakeOccurrences(
    context: android.content.Context,
    wakeAlarms: List<PrayerWakeConfig>,
    windowsByAlarm: Map<String, List<WakeAlarmScheduler.OccurrenceWindow>>,
    nowMillis: Long = System.currentTimeMillis(),
): Map<String, WakeAlarmScheduler.RunningOccurrence> =
    wakeAlarms
        .filter { alarm -> alarm.enabled }
        .mapNotNull { alarm ->
            WakeAlarmScheduler.runningOccurrence(context, alarm, windowsByAlarm[alarm.id].orEmpty(), nowMillis)
                ?.let { running -> alarm.id to running }
        }
        .toMap()

@Composable
private fun WakeAlarmListPanel(
    wakeAlarms: List<PrayerWakeConfig>,
    nowByAlarm: Map<String, WakeAlarmNow?>,
    nowMillis: Long,
    onEditAlarm: (PrayerWakeConfig) -> Unit,
    onEnabledChange: (PrayerWakeConfig, Boolean) -> Unit,
    onDeleteRequest: (PrayerWakeConfig) -> Unit,
    onSkipNext: (PrayerWakeConfig) -> Unit,
    onStop: (PrayerWakeConfig) -> Unit,
) {
    val context = LocalContext.current
    var lastActionAtMillis by remember { mutableLongStateOf(0L) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.WAKE_ALARM_LIST),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // By what comes next, like the hero, so the list reads in time order and an alarm in progress stays first;
        // alarms that are off go last.
        val sortedAlarms = wakeAlarms.sortedWith(
            compareBy<PrayerWakeConfig>(
                { alarm -> !alarm.enabled },
                { alarm -> nowByAlarm[alarm.id]?.focusAtMillis(nowMillis) ?: Long.MAX_VALUE },
            ),
        )
        sortedAlarms.forEachIndexed { index, wakeAlarm ->
            val alarmNow = nowByAlarm[wakeAlarm.id]
            val isRunning = alarmNow?.inProgress == true
            WakeAlarmRow(
                alarmName = context.getString(R.string.wake_alarm_row_title, index + 1),
                wakeConfig = wakeAlarm,
                alarmNow = alarmNow,
                nowMillis = nowMillis,
                onClick = { onEditAlarm(wakeAlarm) },
                onEnabledChange = { enabled -> onEnabledChange(wakeAlarm, enabled) },
                onDeleteRequest = { onDeleteRequest(wakeAlarm) },
                onSkipNext = {
                    val tappedAtMillis = SystemClock.elapsedRealtime()
                    if (tappedAtMillis - lastActionAtMillis >= WAKE_ACTION_DEBOUNCE_MILLIS) {
                        lastActionAtMillis = tappedAtMillis
                        // The label decides: a pending skip is undone, a running alarm is stopped, else the next one is skipped.
                        if (isRunning && !wakeAlarm.hasPendingWakeOccurrenceSkip(nowMillis)) {
                            onStop(wakeAlarm)
                        } else {
                            onSkipNext(wakeAlarm)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun WakeNextAlarmPanel(
    wakeConfig: PrayerWakeConfig,
    alarmNow: WakeAlarmNow,
    nowMillis: Long,
) {
    val prayerName = wakeAlarmPrayerName(wakeConfig.prayer)
    // The rings of the occurrence on show, as it really had them if the alarm was edited meanwhile.
    val ringOffsets = alarmNow.occurrenceRingOffsets(
        wakeAlarmRingOffsets(wakeConfig.subAlarms.map { extra -> extra.signedOffsetMinutes }),
        nowMillis,
    )
    // An alarm with extras shows every ring of the occurrence on show; its rule stays in the row below.
    val hasExtras = ringOffsets.size > 1
    val summaryText = if (hasExtras) null else wakeSummaryText(prayerName, wakeConfig)
    // The hero leads with what comes next: the ring going now, a ring still to come (late or ahead), or the awake
    // check once the last ring is stopped.
    val focus = alarmNow.focus
    val shownRing = alarmNow.highlightedRing
    val shownAtMillis = when (focus) {
        WakeAlarmFocus.AWAKE_CHECK -> alarmNow.awakeCheckAtMillis ?: alarmNow.occurrenceAtMillis
        else -> shownRing?.atMillis ?: alarmNow.occurrenceAtMillis
    }
    val shownIsExtra = focus != WakeAlarmFocus.AWAKE_CHECK && shownRing?.isExtra == true
    val titleRes = when (focus) {
        WakeAlarmFocus.RING ->
            if (shownIsExtra) R.string.wake_editor_hero_extra_title else R.string.wake_alarm_next_title
        WakeAlarmFocus.AWAKE_CHECK -> R.string.wake_alarm_hero_awake_check_title
        WakeAlarmFocus.RINGING ->
            if (alarmNow.ringWaiting) R.string.wake_alarm_hero_waiting_title else R.string.wake_alarm_hero_ringing_title
    }
    // The bell tells an extra alert from the alarm itself, the sun the awake check.
    val iconRes = when {
        focus == WakeAlarmFocus.AWAKE_CHECK -> R.drawable.ic_adhkar_sun
        shownIsExtra -> R.drawable.ic_adhkar_bell
        else -> R.drawable.ic_tab_alarms
    }
    // Counts to what comes next, in the minutes the clock shows; the title already says a ring is going. Android
    // can deliver a ring or start the check a little after its time.
    val countdown = formatWakeCountdownDuration(wakeClockCountdownMillis(shownAtMillis, nowMillis))
    val countdownText = when {
        focus == WakeAlarmFocus.RINGING || shownRing == null && focus == WakeAlarmFocus.RING -> null
        alarmNow.awakeCheckRunning -> stringResource(R.string.wake_alarm_row_in_progress)
        focus == WakeAlarmFocus.AWAKE_CHECK && shownAtMillis <= nowMillis ->
            stringResource(R.string.wake_alarm_hero_awake_check_due)
        focus == WakeAlarmFocus.AWAKE_CHECK -> stringResource(R.string.wake_alarm_hero_awake_check_countdown, countdown)
        shownAtMillis <= nowMillis -> stringResource(R.string.wake_alarm_hero_ring_due)
        else -> stringResource(R.string.wake_editor_hero_countdown, countdown)
    }
    HeroCardSurface(modifier = Modifier.heightIn(min = MainHeroCardHeight)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 17.dp, vertical = 13.dp)
                .heightIn(min = 122.dp),
        ) {
            val sideBySide = maxWidth >= 270.dp && LocalDensity.current.fontScale <= 1.3f
            val detailsWidth = maxWidth * 0.55f
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WakeNextAlarmDetails(
                    titleRes = titleRes,
                    iconRes = iconRes,
                    timeText = formatTimeOfDay(shownAtMillis),
                    dateText = formatWakeAlarmDate(shownAtMillis),
                    summaryText = summaryText,
                    modifier = if (sideBySide) Modifier.width(detailsWidth) else Modifier.fillMaxWidth(),
                )
                // Counts to the next ring, whether it is the alarm or one of its extras, or to the awake check.
                // It sits outside the narrower details column so it stays on one line, in the timeline's gold.
                if (countdownText != null) {
                    WakeNextAlarmCountdown(text = countdownText, color = Gold)
                }
                if (hasExtras) {
                    WakeNextAlarmTimeline(
                        stops = wakeTimelineStops(
                            offsets = ringOffsets,
                            occurrenceAtMillis = alarmNow.occurrenceAtMillis,
                            highlightedAtMillis = shownRing?.atMillis,
                            highlightedOngoing = focus == WakeAlarmFocus.RINGING && !alarmNow.ringWaiting,
                            awakeCheck = alarmNow.awakeCheckAtMillis?.let { atMillis ->
                                WakeTimelineAwakeCheck(
                                    atMillis = atMillis,
                                    fromMillis = alarmNow.awakeCheckFromMillis,
                                    running = alarmNow.awakeCheckRunning,
                                    isNext = focus == WakeAlarmFocus.AWAKE_CHECK,
                                )
                            },
                        ),
                        nowMillis = nowMillis,
                        formatTime = ::formatTimeOfDay,
                    )
                }
            }
        }
    }
}

/** The hero when alarms exist but all are off: it says so, in the same scene as the next-alarm hero. */
@Composable
private fun WakeNoEnabledAlarmPanel() {
    HeroCardSurface(modifier = Modifier.heightIn(min = MainHeroCardHeight)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 17.dp, vertical = 13.dp)
                .heightIn(min = 122.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroIconRing(iconRes = R.drawable.ic_tab_alarms)
            Text(
                text = stringResource(R.string.wake_alarm_hero_none_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

/**
 * How long until the next ring, as one gold line under the date. Like the time row above, the icon sits
 * in the bell's column and the text lines up with the title and the date.
 */
@Composable
private fun WakeNextAlarmCountdown(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.width(34.dp), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_adhkar_clock),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun WakeNextAlarmDetails(
    titleRes: Int,
    iconRes: Int,
    timeText: String,
    dateText: String,
    summaryText: String?,
    modifier: Modifier = Modifier,
) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    val alarmIconSize = 34.dp
    val alarmTextInset = alarmIconSize + 8.dp
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(titleRes),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.78f),
            modifier = Modifier.padding(start = alarmTextInset),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroIconRing(iconRes = iconRes)
            Text(
                text = timeText,
                fontSize = 29.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                softWrap = false,
                autoSize = if (largeText) null else TextAutoSize.StepBased(maxFontSize = 29.sp),
                style = TextStyle(textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
            )
        }
        Text(
            text = dateText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 2,
            modifier = Modifier.padding(start = alarmTextInset),
        )
        if (summaryText != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.91f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = summaryText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GreenPrimaryDark,
                    maxLines = if (largeText) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(R.drawable.ic_adhkar_bell),
                    contentDescription = null,
                    tint = Gold,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** How the place's country sets Jumu'a (6.9, A18). */
private enum class JumuaMode { DEFAULT, BEFORE_ASR, FIXED }

/**
 * Jumu'a's time: the default (Dhuhr in Tunisia, A18 abroad), an hour before each Friday's Asr (about one Tunisian user
 * in ten, whose mosque prays then), or a fixed time. Each choice shows this Friday's time.
 */
@Composable
private fun JumuaTimeDialog(
    current: JumuaMode,
    defaultAt: String?,
    defaultHint: String?,
    beforeAsrAt: String?,
    fixedAt: String?,
    onPick: (JumuaMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = Color.White,
        title = {
            Text(
                text = stringResource(R.string.jomoaa_time_choice_title),
                color = PrayerNameColor,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                JumuaChoice(stringResource(R.string.jomoaa_time_default), defaultHint, defaultAt, current == JumuaMode.DEFAULT) { onPick(JumuaMode.DEFAULT) }
                JumuaChoice(stringResource(R.string.jomoaa_time_before_asr), stringResource(R.string.jomoaa_time_before_asr_hint), beforeAsrAt, current == JumuaMode.BEFORE_ASR) {
                    onPick(JumuaMode.BEFORE_ASR)
                }
                JumuaChoice(stringResource(R.string.jomoaa_time_fixed), stringResource(R.string.jomoaa_time_fixed_hint), fixedAt, current == JumuaMode.FIXED) { onPick(JumuaMode.FIXED) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.jomoaa_time_cancel))
            }
        },
    )
}

@Composable
private fun JumuaChoice(title: String, hint: String?, time: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = TextDark, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            hint?.let { Text(text = it, color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp) }
        }
        // A clock in Arabic text, isolated left to right.
        time?.let { Text(text = "⁦$it⁩", color = PrayerNameColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp)) }
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
    isPhoneSilenced: Boolean,
    hasDnd: Boolean,
    silenceReason: PhoneSilenceReason?,
    autoSilenceEnabled: Boolean,
) {
    val context = LocalContext.current
    val prayerTimeText = String.format(Locale.US, "%02d:%02d", countdown.hour, countdown.minute)
    val remainingText = stringResource(
        R.string.next_prayer_countdown_remaining,
        formatCountdownRemaining(countdown.triggerAtMillis, currentTimeMillis),
    )
    // Re-read the upcoming prayer's silence rule whenever it is edited in the list below.
    var silenceRuleRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(context, countdown.prayer) {
        val stopObservingConfig = PrefsManager.observeConfigChanges(context, countdown.prayer) {
            silenceRuleRevision++
        }
        val stopObservingEnabled = PrefsManager.observePrayerEnabledChanges(context, countdown.prayer) {
            silenceRuleRevision++
        }
        onDispose {
            stopObservingConfig()
            stopObservingEnabled()
        }
    }
    val upcomingSilence = remember(countdown, autoSilenceEnabled, silenceRuleRevision) {
        resolveUpcomingSilence(context, countdown)
    }
    // Always say what will happen at the next prayer; a missing Do Not Disturb
    // permission is already explained by the phone-status notice above.
    val silenceLine = if (!hasDnd) null else when (val plan = upcomingSilence) {
        is UpcomingSilence.Window -> HeroSilenceLine(
            text = stringResource(
                R.string.next_prayer_silence_window,
                silenceClockText(plan.window.startMinutes),
                silenceClockText(plan.window.endMinutes),
            ),
            scheduled = true,
        )
        UpcomingSilence.AutoSilenceOff -> HeroSilenceLine(
            text = stringResource(R.string.next_prayer_silence_off_auto),
            scheduled = false,
        )
        UpcomingSilence.PrayerOff -> HeroSilenceLine(
            text = stringResource(R.string.next_prayer_silence_off_prayer, prayerName),
            scheduled = false,
        )
        null -> null
    }
    val silencedOverlayColor by animateColorAsState(
        targetValue = if (isPhoneSilenced) SilencedHeroStart.copy(alpha = 0.3f) else Color.Transparent,
        label = "nextPrayerHeroSilencedOverlay"
    )
    val countdownPillBackgroundColor by animateColorAsState(
        targetValue = if (isPhoneSilenced) SilencedHeroPillBackground else Color.White.copy(alpha = 0.90f),
        label = "nextPrayerHeroPillBackground"
    )
    val countdownPillTextColor by animateColorAsState(
        targetValue = if (isPhoneSilenced) SilencedHeroPillText else GreenPrimaryDark,
        label = "nextPrayerHeroPillText"
    )

    HeroCardSurface(
        modifier = Modifier
            .heightIn(min = 148.dp)
            .testTag(TestTags.STATUS_CARD),
        overlay = SolidColor(silencedOverlayColor),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 17.dp, vertical = 13.dp)
                .heightIn(min = 122.dp),
        ) {
            val sideBySide = maxWidth >= 270.dp && LocalDensity.current.fontScale <= 1.3f
            if (sideBySide) {
                NextPrayerHeroDetails(
                    prayer = countdown.prayer,
                    prayerName = prayerName,
                    prayerTimeText = prayerTimeText,
                    isTomorrow = countdown.isTomorrow,
                    silenceLine = silenceLine,
                    remainingText = remainingText,
                    countdownPillBackgroundColor = countdownPillBackgroundColor,
                    countdownPillTextColor = countdownPillTextColor,
                    isPhoneSilenced = isPhoneSilenced,
                    hasDnd = hasDnd,
                    silenceReason = silenceReason,
                    modifier = Modifier.width(maxWidth * 0.6f).align(Alignment.TopStart),
                )
                NextPrayerHeroVerse(
                    modifier = Modifier
                        .width(maxWidth * 0.4f)
                        .padding(bottom = 4.dp)
                        .align(Alignment.BottomEnd),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NextPrayerHeroDetails(
                        prayer = countdown.prayer,
                        prayerName = prayerName,
                        prayerTimeText = prayerTimeText,
                        isTomorrow = countdown.isTomorrow,
                        silenceLine = silenceLine,
                        remainingText = remainingText,
                        countdownPillBackgroundColor = countdownPillBackgroundColor,
                        countdownPillTextColor = countdownPillTextColor,
                        isPhoneSilenced = isPhoneSilenced,
                        hasDnd = hasDnd,
                        silenceReason = silenceReason,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NextPrayerHeroVerse(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun NextPrayerHeroDetails(
    prayer: Prayer,
    prayerName: String,
    prayerTimeText: String,
    isTomorrow: Boolean,
    remainingText: String,
    silenceLine: HeroSilenceLine?,
    countdownPillBackgroundColor: Color,
    countdownPillTextColor: Color,
    isPhoneSilenced: Boolean,
    hasDnd: Boolean,
    silenceReason: PhoneSilenceReason?,
    modifier: Modifier = Modifier,
) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    val prayerIconSize = 34.dp
    val prayerIconSpacing = 8.dp
    val iconRes = prayerHeroIconRes(prayer)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.next_prayer_countdown_title),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.78f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(prayerIconSpacing),
        ) {
            Box(
                modifier = Modifier
                    .size(prayerIconSize)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            // Prayer and adhan time always share one line: the time keeps its size and a
            // long name shrinks to fit instead of pushing the time onto a second line.
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = prayerName,
                    fontSize = 29.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = if (largeText) 2 else 1,
                    softWrap = largeText,
                    autoSize = if (largeText) null else TextAutoSize.StepBased(maxFontSize = 29.sp),
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = prayerTimeText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GoldLight,
                    maxLines = 1,
                    softWrap = false,
                )
                if (isTomorrow) {
                    Text(
                        text = stringResource(R.string.next_prayer_countdown_tomorrow),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(countdownPillBackgroundColor)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = remainingText,
                fontSize = 13.sp,
                color = countdownPillTextColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(maxFontSize = 13.sp),
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                painter = painterResource(R.drawable.ic_adhkar_clock),
                contentDescription = null,
                tint = countdownPillTextColor,
                modifier = Modifier.size(16.dp),
            )
        }
        // A single status line, most urgent first: what the phone is doing right now
        // (silenced, or missing Do Not Disturb access), otherwise what this prayer brings.
        val phoneStatusText = phoneStatusNoticeText(
            context = LocalContext.current,
            isPhoneSilenced = isPhoneSilenced,
            hasDnd = hasDnd,
            silenceReason = silenceReason,
        )
        if (phoneStatusText != null) {
            PhoneStatusNotice(
                isPhoneSilenced = isPhoneSilenced,
                hasDnd = hasDnd,
                silenceReason = silenceReason,
            )
        } else if (silenceLine != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (silenceLine.scheduled) R.drawable.ic_bell_off else R.drawable.ic_adhkar_bell,
                    ),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = if (silenceLine.scheduled) 0.86f else 0.7f),
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = silenceLine.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = if (silenceLine.scheduled) 0.9f else 0.72f),
                    maxLines = if (largeText) 2 else 1,
                    softWrap = largeText,
                    autoSize = if (largeText) null else TextAutoSize.StepBased(maxFontSize = 11.sp),
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/** Taha 13 as the mushaf prints it, in its font: a size up from the card's 13sp, its letters being smaller. */
@Composable
private fun NextPrayerHeroVerse(modifier: Modifier = Modifier) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    val verse = DisplayTexts.PRAYER_VERSES.first { it.id == "taha_13" }
    Text(
        text = "﴿ " + (MushafPassages.text(verse.id) ?: verse.text) + " ﴾",
        modifier = modifier.semantics { contentDescription = verse.text },
        fontFamily = QuranMushafFont,
        fontSize = 15.sp,
        color = Color.White.copy(alpha = 0.91f),
        textAlign = TextAlign.Center,
        maxLines = if (largeText) Int.MAX_VALUE else 1,
        softWrap = largeText,
        autoSize = if (largeText) null else TextAutoSize.StepBased(maxFontSize = 15.sp),
    )
}

@Composable
private fun WakeAlarmEmptyState(
    onPresetSelected: (WakeQuickPreset) -> Unit,
    onFajrSetup: (() -> Unit)?,
) {
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
                onFajrSetup?.let { onClick -> FajrAlarmStartOption(onClick = onClick) }
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
private fun PrayerRow(
    prayer: Prayer,
    prayerName: String,
    anchor: PrayerTimelineAnchor?,
    nextAnchor: PrayerTimelineAnchor?,
    isNextPrayer: Boolean,
    highlighted: Boolean = false,
    draftSilenceConfig: PrayerSilenceConfig? = null,
    onDraftSilenceConfigChange: ((PrayerSilenceConfig) -> Unit)? = null,
    onConfigChanged: () -> Unit,
    onPrayerTimeClick: (() -> Unit)? = null,
    timelineScale: PrayerTimelineScale? = null,
    showEnableSwitch: Boolean = true,
    silencePermissionsGranted: Boolean = true,
    onEnableSilenceMaster: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val usesDraft = draftSilenceConfig != null && onDraftSilenceConfigChange != null
    // The effective config takes the place's kind (D12's Tunisian defaults): a move between Tunisia and abroad reloads it.
    var savedConfig by remember(context, prayer, anchor?.inm) { mutableStateOf(PrefsManager.getEffectiveConfig(context, prayer)) }
    val config = if (usesDraft) requireNotNull(draftSilenceConfig) else savedConfig
    var prayerEnabled by remember(context, prayer) {
        mutableStateOf(PrefsManager.isPrayerSilenceEnabled(context, prayer))
    }
    var editorEndpoint by rememberSaveable(prayer) { mutableStateOf<SilenceEndpoint?>(null) }
    var previewWindow by remember(prayer) { mutableStateOf<PrayerTimelineWindow?>(null) }
    var undoBefore by remember(prayer) { mutableStateOf<PrayerSilenceConfig?>(null) }
    var undoAfter by remember(prayer) { mutableStateOf<PrayerSilenceConfig?>(null) }
    val currentConfig by rememberUpdatedState(config)
    // The overlap warning below uses the next prayer's saved window, which can change from that prayer's row.
    val nextPrayer = nextAnchor?.prayer
    var nextPrayerConfig by remember(context, nextPrayer, nextAnchor?.inm) {
        mutableStateOf(nextPrayer?.let { PrefsManager.getEffectiveConfig(context, it) })
    }

    DisposableEffect(context, prayer, usesDraft) {
        val stopObservingConfig = if (usesDraft) {
            ({})
        } else {
            PrefsManager.observeConfigChanges(context, prayer) {
                savedConfig = PrefsManager.getEffectiveConfig(context, prayer)
            }
        }
        val stopObservingEnabled = if (usesDraft) {
            ({})
        } else {
            PrefsManager.observePrayerEnabledChanges(context, prayer) {
                prayerEnabled = PrefsManager.isPrayerSilenceEnabled(context, prayer)
            }
        }
        onDispose {
            stopObservingConfig()
            stopObservingEnabled()
        }
    }
    DisposableEffect(context, nextPrayer) {
        val stopObservingNextConfig = if (nextPrayer == null) {
            ({})
        } else {
            PrefsManager.observeConfigChanges(context, nextPrayer) {
                nextPrayerConfig = PrefsManager.getEffectiveConfig(context, nextPrayer)
            }
        }
        onDispose { stopObservingNextConfig() }
    }
    LaunchedEffect(config) {
        previewWindow = null
        if (undoAfter != null && config != undoAfter) {
            undoBefore = null
            undoAfter = null
        }
    }
    LaunchedEffect(undoAfter) {
        if (undoAfter != null) {
            delay(6_000L)
            if (undoAfter == currentConfig) {
                undoBefore = null
                undoAfter = null
            }
        }
    }

    fun applyConfig(updated: PrayerSilenceConfig): Boolean {
        if (usesDraft) {
            onDraftSilenceConfigChange?.invoke(updated)
        } else {
            savedConfig = updated
            persistPrayerSilenceConfig(context, prayer, updated, anchor)
            val persisted = PrefsManager.getEffectiveConfig(context, prayer)
            // Compared by the window armed: an endpoint left at its default is stored as unset (6.9).
            val matches = anchor?.let { resolvePrayerTimelineWindow(it, persisted) == resolvePrayerTimelineWindow(it, updated) }
                ?: (persisted == updated)
            if (!matches) {
                savedConfig = persisted
                undoBefore = null
                undoAfter = null
                Toast.makeText(
                    context,
                    context.getString(R.string.prayer_silence_save_failed),
                    Toast.LENGTH_LONG,
                ).show()
                onConfigChanged()
                return false
            }
        }
        onConfigChanged()
        return true
    }

    fun commitConfig(updated: PrayerSilenceConfig): Boolean {
        val previous = if (usesDraft) config else PrefsManager.getEffectiveConfig(context, prayer)
        if (updated == previous) return true
        undoBefore = previous
        undoAfter = updated
        val applied = applyConfig(updated)
        if (!applied) {
            undoBefore = null
            undoAfter = null
        }
        return applied
    }

    // D16's year run (6.9): a window with a clock endpoint is checked over the coming year before it is saved.
    var pendingYearFit by remember(prayer) { mutableStateOf<Pair<PrayerSilenceConfig, SilenceFit>?>(null) }
    val rowScope = rememberCoroutineScope()
    fun commitChecked(updated: PrayerSilenceConfig) {
        val checkedAnchor = anchor
        val hasClock = updated.startRuleMode() == EndpointRuleMode.FIXED_TIME || updated.endRuleMode() == EndpointRuleMode.FIXED_TIME
        if (usesDraft || checkedAnchor == null || !hasClock) {
            commitConfig(updated)
            return
        }
        rowScope.launch {
            val fit = withContext(Dispatchers.Default) { silenceYearFit(context, checkedAnchor, updated) }
            if (fit.fitsAllYear) commitConfig(updated) else pendingYearFit = updated to fit
        }
    }

    val savedWindow = anchor?.let { resolvePrayerTimelineWindow(it, config) }
    val window = previewWindow ?: savedWindow
    val scale = timelineScale ?: remember(anchor, savedWindow) {
        if (anchor != null && savedWindow != null) {
            sharedPrayerTimelineScale(listOf(anchor to savedWindow))
        } else {
            PrayerTimelineScale(
                DEFAULT_TIMELINE_START_OFFSET_MINUTES,
                DEFAULT_TIMELINE_END_OFFSET_MINUTES,
            )
        }
    }
    val warningRes = when {
        window == null -> null
        window.endMinutes < window.startMinutes -> R.string.prayer_timeline_invalid_window
        anchor != null && nextAnchor != null -> {
            // The next prayer's silence can start before its adhan, so compare with whichever comes first.
            val nextSilenceStart = nextPrayerConfig?.let { silenceEndsAt(nextAnchor, it).start }
            val nextStart = minOf(nextAnchor.adhan, nextSilenceStart ?: nextAnchor.adhan)
            R.string.warning_overlaps_next_prayer.takeIf { window.endMinutes > anchor.axisOf(nextStart) }
        }
        else -> null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (highlighted) GoldLight.copy(alpha = 0.30f) else Color.Transparent),
    ) {
        if (anchor != null && window != null) {
            PrayerSilenceSection(
                prayer = prayer,
                prayerName = prayerName,
                anchor = anchor,
                config = config,
                window = window,
                scale = scale,
                enabled = if (usesDraft) true else prayerEnabled,
                showSwitch = showEnableSwitch && !usesDraft,
                isNextPrayer = isNextPrayer,
                onEnabledChange = { newValue ->
                    prayerEnabled = newValue
                    PrefsManager.setPrayerSilenceEnabled(context, prayer, newValue)
                    if (newValue && !PrefsManager.isEnabled(context)) {
                        onEnableSilenceMaster?.invoke()
                    }
                    onConfigChanged()
                    if (newValue && !silencePermissionsGranted) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.prayer_silence_permission_needed),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                onPreviewWindow = { previewWindow = it },
                onCommitWindow = { candidate ->
                    previewWindow = null
                    prayerTimelineConfigForWindow(anchor, config, candidate)?.let { updated ->
                        commitChecked(updated)
                    }
                },
                onEditEndpoint = { endpoint -> editorEndpoint = endpoint },
                onPrayerTimeClick = onPrayerTimeClick,
            )
        } else {
            Text(
                text = stringResource(R.string.no_prayer_data),
                fontSize = 13.sp,
                color = TextMuted,
                modifier = Modifier.padding(
                    horizontal = PrayerSilenceDimens.SectionHorizontalPadding,
                    vertical = PrayerSilenceDimens.SectionVerticalPadding,
                ),
            )
        }
        warningRes?.let {
            Text(
                text = stringResource(it),
                modifier = Modifier.fillMaxWidth().testTag(TestTags.OVERLAP_WARNING)
                    .clip(RoundedCornerShape(8.dp)).background(SilenceRed.copy(alpha = 0.06f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                fontSize = 12.sp,
                color = SilenceRed,
                lineHeight = 18.sp,
            )
        }
        if (undoBefore != null && undoAfter == config) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(GreenPrimary.copy(alpha = 0.06f)).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.prayer_silence_saved),
                    modifier = Modifier.align(Alignment.CenterVertically),
                    fontSize = 12.sp,
                    color = TextMuted,
                )
                TextButton(
                    onClick = {
                        val previous = undoBefore
                        val current = if (usesDraft) config else PrefsManager.getEffectiveConfig(context, prayer)
                        if (previous != null && current == undoAfter) applyConfig(previous)
                        undoBefore = null
                        undoAfter = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("prayer_timeline_undo_${prayer.name}"),
                ) {
                    Text(stringResource(R.string.prayer_silence_undo), color = GreenPrimaryDark)
                }
            }
        }
    }
    val currentEditorEndpoint = editorEndpoint
    if (currentEditorEndpoint != null && anchor != null) {
        PrayerSilenceEditorSheet(
            prayer = prayer,
            prayerName = prayerName,
            anchor = anchor,
            config = config,
            endpoint = currentEditorEndpoint,
            onDismiss = { editorEndpoint = null },
            onSave = { updated ->
                editorEndpoint = null
                commitChecked(updated)
                true
            },
        )
    }
    val yearFit = pendingYearFit
    if (yearFit != null && anchor != null) {
        val (draft, fit) = yearFit
        SilenceYearFitDialog(
            fit = fit,
            anchor = anchor,
            onAdhanRelative = fit.adhanRelativeEnd?.let { offset ->
                {
                    pendingYearFit = null
                    commitConfig(draft.copy(mode = SilenceMode.DURATION, endOffsetMinutes = offset).withLegacyDurationSynced(anchor))
                }
            },
            onLaterFixedEnd = fit.laterFixedEnd?.let { time ->
                {
                    pendingYearFit = null
                    commitConfig(draft.copy(mode = SilenceMode.FIXED_TIME, fixedHour = time.hour, fixedMinute = time.minute, endOffsetMinutes = null))
                }
            },
            onKeep = {
                pendingYearFit = null
                commitConfig(draft)
            },
            onDismiss = { pendingYearFit = null },
        )
    }
}

@Composable
private fun WakeSilenceConflictPrayerEditor(
    delegationId: Int,
    editorState: WakeSilenceConflictEditorState,
    onConfigChanged: (PrayerSilenceConfig) -> Unit,
) {
    val context = LocalContext.current
    val targetPrayer = editorState.originalConflict.silencePrayer
    val conflict = editorState.currentConflict ?: editorState.originalConflict
    // The conflicting window's own prayer day (6.9), else the day its silence starts on at the place.
    val prayerDay = conflict.silencePrayerDay ?: java.time.Instant.ofEpochMilli(conflict.silenceStartAtMillis)
        .atZone(PrayerTimesRepository.place(context).zone).toLocalDate()
    val anchors = remember(delegationId, prayerDay, targetPrayer) {
        val anchor = timelineAnchorFor(context, targetPrayer, prayerDay)
        anchor to anchor?.let { nextTimelineAnchor(context, it) }
    }
    val (anchor, nextAnchor) = anchors
    if (anchor == null) {
        Text(
            text = stringResource(R.string.no_prayer_data),
            fontSize = 12.sp,
            color = TextMuted,
            lineHeight = 17.sp,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PrayerRow(
            prayer = targetPrayer,
            prayerName = prayerName(context, targetPrayer),
            anchor = anchor,
            nextAnchor = nextAnchor,
            isNextPrayer = false,
            highlighted = false,
            draftSilenceConfig = editorState.draftSilenceConfig,
            onDraftSilenceConfigChange = onConfigChanged,
            onConfigChanged = {},
            showEnableSwitch = false,
        )
    }
}

@Composable
private fun WakeAlarmRow(
    alarmName: String,
    wakeConfig: PrayerWakeConfig,
    // The occurrence on show, the same as the hero's: the one in progress, else the next one.
    alarmNow: WakeAlarmNow?,
    nowMillis: Long,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDeleteRequest: () -> Unit,
    onSkipNext: () -> Unit,
) {
    val enabled = wakeConfig.enabled
    val isRunning = alarmNow?.inProgress == true
    // The ring still to come (late or ahead); null once every ring of an occurrence in progress has gone.
    val nextAlarmMillis = alarmNow?.nextRing?.atMillis
    // The ring lit in gold, as in the hero: the one going now, else the one coming.
    val highlightedRingAtMillis = alarmNow?.highlightedRing?.atMillis
    val mainAlarmMillis = alarmNow?.occurrenceAtMillis
    val isRepeatingAlarm = wakeConfig.isRepeatingWakeAlarm()
    val isSkipPending = wakeConfig.hasPendingWakeOccurrenceSkip(nowMillis)
    val showSkipNextAction = isRunning ||
        (isRepeatingAlarm && (isSkipPending || (enabled && nextAlarmMillis != null)))
    // A pending skip is undone first, so a stopped alarm offers undo for the rest of its occurrence.
    val skipActionDescription = stringResource(
        when {
            isSkipPending -> R.string.wake_alarm_undo_skip_next
            isRunning -> R.string.wake_alarm_stop
            else -> R.string.wake_alarm_skip_next
        },
    )
    val deleteActionDescription = stringResource(R.string.wake_alarm_delete_action)
    val context = LocalContext.current
    // The card names the alarm by its own time; the ring coming next, maybe an extra, is a detail below it.
    val alarmTimeMillis = mainAlarmMillis
    // What the alarm follows: its days for a fixed time, its rule for a prayer or a timer.
    val isTimer = wakeConfig.mainAlarm.mode == WakeMainAlarmMode.FROM_NOW
    val scheduleText = if (isTimer) {
        stringResource(R.string.wake_editor_mode_from_now)
    } else if (wakeConfig.mainAlarm.mode == WakeMainAlarmMode.FIXED_TIME) {
        if (wakeConfig.repeatMode == WakeRepeatMode.ONCE) {
            stringResource(R.string.wake_schedule_once)
        } else {
            formatWakeScheduleDaysSummary(context, wakeConfig.scheduledDays)
        }
    } else {
        wakeSummaryText(wakeAlarmDisplayName(wakeConfig), wakeConfig)
    }
    @Composable
    fun relativeDay(millis: Long): String = when (wakeHeroRelativeDay(millis, nowMillis)) {
        WakeHeroRelativeDay.TODAY -> stringResource(R.string.date_today)
        WakeHeroRelativeDay.TOMORROW -> stringResource(R.string.next_prayer_countdown_tomorrow)
        WakeHeroRelativeDay.LATER -> formatWakeAlarmDate(millis)
    }
    // A state the user must notice replaces the plain "next" note.
    val skippedOccurrenceAtMillis = wakeConfig.skipNextOccurrenceAtMillis.takeIf { isSkipPending }
    // Once the last ring is stopped, what is left of the occurrence is its awake check.
    val awakeCheckAtMillis = alarmNow?.takeIf { now -> now.focus == WakeAlarmFocus.AWAKE_CHECK }?.awakeCheckAtMillis
    val statusText = when {
        !enabled -> stringResource(R.string.wake_alarm_row_off)
        alarmNow?.awakeCheckRunning == true -> stringResource(R.string.wake_alarm_row_awake_check_running)
        awakeCheckAtMillis != null ->
            stringResource(R.string.wake_alarm_row_awake_check_at, formatTimeOfDay(awakeCheckAtMillis))
        isRunning -> stringResource(R.string.wake_alarm_row_in_progress)
        skippedOccurrenceAtMillis != null ->
            stringResource(R.string.wake_alarm_row_skipped, relativeDay(skippedOccurrenceAtMillis))
        else -> null
    }
    val nextDayText = nextAlarmMillis?.takeIf { statusText == null }?.let { millis ->
        if (isTimer) {
            stringResource(
                R.string.wake_editor_hero_countdown,
                formatWakeCountdownDuration(wakeClockCountdownMillis(millis, nowMillis)),
            )
        } else {
            stringResource(R.string.wake_alarm_row_next_at, relativeDay(millis))
        }
    }
    // Every ring of the occurrence on show, in time order, the way the hero's timeline lists them.
    val alarmRingOffsets = wakeAlarmRingOffsets(wakeConfig.subAlarms.map { extra -> extra.signedOffsetMinutes })
    val ringTimes = alarmNow?.let { now ->
        now.occurrenceRingOffsets(alarmRingOffsets, nowMillis)
            .takeIf { offsets -> offsets.size > 1 }
            ?.map { offset -> offset to now.occurrenceAtMillis + offset * 60_000L }
    }
    val featureChips = buildList {
        val playback = wakeConfig.playback
        if (playback.wakeUpCheckEnabled) {
            val mathLevels = playback.effectiveWakeUpCheckSteps
                .filter { step -> step.type == WakeUpCheckType.MATH }
                .map { step -> step.difficulty }
            add(
                when (mathLevels.maxOrNull()) {
                    MathDifficulty.HARD -> context.getString(R.string.wake_alarm_feature_challenge_hard)
                    MathDifficulty.INTERMEDIATE -> context.getString(R.string.wake_alarm_feature_challenge_medium)
                    MathDifficulty.EASY -> context.getString(R.string.wake_alarm_feature_challenge_easy)
                    null -> context.getString(R.string.wake_alarm_feature_wake_check)
                },
            )
        }
        if (playback.awakeCheckEnabled) add(context.getString(R.string.wake_alarm_feature_awake_check))
        if (playback.vibrationOnly) add(context.getString(R.string.wake_alarm_feature_vibration))
        if (wakeConfig.silenceUntilAlarm) add(context.getString(R.string.wake_alarm_feature_silence))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.wakeAlarmRow(wakeConfig.id))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(
            width = 1.dp,
            color = GreenPrimary.copy(alpha = 0.14f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // An alarm that is off keeps its details, greyed out.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (enabled) 1f else 0.5f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (alarmTimeMillis != null) {
                        Text(
                            text = formatTimeOfDay(alarmTimeMillis),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = GreenPrimaryDark,
                            maxLines = 1,
                            style = TextStyle(textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
                        )
                    }
                    Text(
                        text = listOfNotNull(scheduleText, nextDayText).joinToString(separator = " • "),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GreenPrimaryDark,
                        lineHeight = 18.sp,
                    )
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRunning && enabled) SilenceRed else PrayerNameColor,
                            lineHeight = 18.sp,
                        )
                    }
                    if (ringTimes != null) {
                        WakeAlarmRingStrip(
                            rings = ringTimes,
                            nextRingAtMillis = highlightedRingAtMillis.takeIf { enabled && !isSkipPending },
                            nowMillis = nowMillis,
                        )
                    }
                    if (featureChips.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            featureChips.forEach { chip -> WakeAlarmFeatureChip(text = chip) }
                        }
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(TestTags.wakeAlarmEnabledSwitch(wakeConfig.id))
                        .semantics { contentDescription = alarmName },
                )
            }
            HorizontalDivider(color = GreenPrimary.copy(alpha = 0.1f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (showSkipNextAction) {
                        TextButton(
                            onClick = onSkipNext,
                            colors = ButtonDefaults.textButtonColors(contentColor = GreenPrimaryDark),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag(TestTags.wakeAlarmSkipNextButton(wakeConfig.id))
                                .semantics { contentDescription = skipActionDescription },
                        ) {
                            Text(
                                text = stringResource(
                                    when {
                                        isSkipPending -> R.string.wake_alarm_undo_skip_next_short
                                        isRunning -> R.string.wake_alarm_stop_short
                                        else -> R.string.wake_alarm_skip_next_short
                                    },
                                ),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                TextButton(
                    onClick = onDeleteRequest,
                    colors = ButtonDefaults.textButtonColors(contentColor = SilenceRed),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(TestTags.wakeAlarmDeleteButton(wakeConfig.id))
                        .semantics { contentDescription = deleteActionDescription },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(text = deleteActionDescription, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * The rings of one occurrence as times, e.g. 04:50 • 04:55 • 05:00: the main alarm in bold, the ring
 * coming next in gold, the other extras muted.
 */
@Composable
private fun WakeAlarmRingStrip(rings: List<Pair<Int, Long>>, nextRingAtMillis: Long?, nowMillis: Long) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rings.forEachIndexed { index, (offset, atMillis) ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(TextMuted.copy(alpha = 0.45f)),
                )
            }
            val isMain = offset == 0
            val isNext = atMillis == nextRingAtMillis
            val isPast = atMillis < nowMillis && !isNext
            Text(
                modifier = Modifier.alpha(if (isPast) 0.4f else 1f),
                text = formatTimeOfDay(atMillis),
                fontSize = 14.sp,
                fontWeight = if (isMain || isNext) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    isNext -> Gold
                    isMain -> GreenPrimaryDark
                    else -> TextMuted
                },
                style = TextStyle(textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
            )
        }
    }
}

@Composable
private fun wakeSummaryText(
    prayerName: String,
    wakeConfig: PrayerWakeConfig,
    // The alarm row shows the fixed time big already, so it names only the mode.
    includeFixedTime: Boolean = true,
): String {
    val mainSummary = when (wakeConfig.mainAlarm.mode) {
        WakeMainAlarmMode.FIXED_TIME -> if (!includeFixedTime) stringResource(R.string.wake_editor_mode_fixed) else stringResource(
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

    val context = LocalContext.current
    val recurringSummary = if (
        wakeConfig.mainAlarm.mode != WakeMainAlarmMode.FROM_NOW &&
        wakeConfig.repeatMode == WakeRepeatMode.ONCE
    ) {
        stringResource(
            R.string.wake_summary_with_days,
            mainSummary,
            stringResource(R.string.wake_schedule_once),
        )
    } else if (
        wakeConfig.mainAlarm.mode != WakeMainAlarmMode.FROM_NOW &&
        !isEveryWakeScheduleDay(wakeConfig.scheduledDays)
    ) {
        stringResource(
            R.string.wake_summary_with_days,
            mainSummary,
            formatWakeScheduleDaysSummary(context, wakeConfig.scheduledDays),
        )
    } else {
        mainSummary
    }

    return recurringSummary
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
    enabled: Boolean = true,
    inputDescription: String? = null,
) {
    val focusManager = LocalFocusManager.current
    // Intermediate edits belong to the field, never to saved scheduling settings.
    // Keying by the committed value also reflects changes from another editor.
    var editingValue by remember(value) { mutableStateOf(value) }
    var lastValidValue by remember(value) { mutableStateOf(value) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BasicTextField(
            value = editingValue,
            enabled = enabled,
            onValueChange = { new ->
                // Normalize Eastern Arabic (٠-٩) and Extended Arabic-Indic (۰-۹) to 0-9
                val normalized = normalizeDigits(new)
                val filtered = if (allowNegative) {
                    // Allow optional leading '-' followed by up to 3 digits
                    val negative = normalized.startsWith("-")
                    val digits = normalized.filter { it in '0'..'9' }.take(3)
                    if (negative) "-$digits" else digits
                } else {
                    // Only allow digits, max 3 chars
                    normalized.filter { it in '0'..'9' }.take(3)
                }
                editingValue = filtered
                if (filtered.toIntOrNull() != null) {
                    lastValidValue = filtered
                    if (filtered != value) onValueChange(filtered)
                }
            },
            modifier = modifier
                .heightIn(min = 48.dp)
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused) editingValue = lastValidValue
                }
                .semantics { inputDescription?.let { contentDescription = it } }
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
                keyboardType = if (allowNegative) KeyboardType.Phone else keyboardType,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                editingValue = lastValidValue
                focusManager.clearFocus()
            }),
            singleLine = true,
            decorationBox = { innerTextField ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
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

/** Durations offered as one-tap choices before the custom hours/minutes fields. */
private val ManualSilenceDurationPresets = listOf(15, 30, 60, 120)

/**
 * Manual silencing: choose when ringing returns, then one clear action. While any
 * silence is active the card collapses to what is happening and a stop action.
 */
@Composable
private fun ManualSilenceCard(
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
    onDurationPresetSelected: (Int) -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val silenceActive = (manualSilenceActive || autoSilenceActive) && hasDnd
    val resolvedTotalMinutes = resolveManualTotalMinutes(
        manualDurationHours, manualDurationMinutes,
        PrefsManager.getManualSilenceDurationMinutes(context),
    )
    val durationText = formatDurationText(resolvedTotalMinutes)
    val targetPrayerName = manualSilencePrayerName(manualTargetPrayer)
    var customDurationOpen by rememberSaveable { mutableStateOf(false) }
    val showCustomDuration = customDurationOpen || resolvedTotalMinutes !in ManualSilenceDurationPresets
    val accent by animateColorAsState(
        targetValue = if (silenceActive) SilencedHeroEnd else PrayerSilencePalette.InteractiveTeal,
        label = "manualSilenceAccent",
    )
    val subtitle = when {
        !silenceActive -> stringResource(R.string.manual_silence_subtitle_idle)
        !manualSilenceActive -> stringResource(R.string.manual_silence_active_auto)
        manualSilenceMode == ManualSilenceMode.UNTIL_PRAYER ->
            stringResource(R.string.manual_silence_active_until_prayer, targetPrayerName)
        manualSilenceMode == ManualSilenceMode.DURATION && manualSilenceEndsAtMillis > 0L ->
            stringResource(R.string.manual_silence_active_until_time, formatTimeOfDay(manualSilenceEndsAtMillis))
        else -> stringResource(R.string.manual_silence_active_until_stopped)
    }
    val shape = RoundedCornerShape(PrayerSilenceDimens.ContainerCorner)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(shape)
            .background(PrayerSilencePalette.Surface)
            .border(1.dp, PrayerSilencePalette.SoftBorder, shape)
            .animateContentSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bell_off),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(
                        if (silenceActive) R.string.manual_silence_active_title else R.string.manual_silence_title,
                    ),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrayerSilencePalette.PrimaryText,
                )
                Text(
                    text = subtitle,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = PrayerSilencePalette.SecondaryText,
                )
            }
        }

        if (!silenceActive) {
            val modes = listOf(
                ManualSilenceMode.UNTIL_STOPPED,
                ManualSilenceMode.DURATION,
                ManualSilenceMode.UNTIL_PRAYER,
            )
            EndpointModeChoices(
                choices = listOf(
                    stringResource(R.string.manual_silence_mode_until_short),
                    stringResource(R.string.manual_silence_mode_duration),
                    stringResource(R.string.manual_silence_mode_prayer_short),
                ),
                selected = modes.indexOf(manualSilenceMode),
                enabled = true,
                onSelected = { index -> onModeChange(modes[index]) },
                optionTestTags = listOf(
                    TestTags.MANUAL_SILENCE_MODE_UNTIL,
                    TestTags.MANUAL_SILENCE_MODE_DURATION,
                    TestTags.MANUAL_SILENCE_MODE_PRAYER,
                ),
            )
            when (manualSilenceMode) {
                ManualSilenceMode.DURATION -> {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ManualSilenceDurationPresets.forEach { minutes ->
                            ManualSilenceOptionChip(
                                text = formatDurationText(minutes),
                                selected = !showCustomDuration && resolvedTotalMinutes == minutes,
                                onClick = {
                                    customDurationOpen = false
                                    onDurationPresetSelected(minutes)
                                },
                            )
                        }
                        ManualSilenceOptionChip(
                            text = stringResource(R.string.manual_silence_duration_custom),
                            selected = showCustomDuration,
                            onClick = { customDurationOpen = true },
                        )
                    }
                    if (showCustomDuration) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ManualSilenceDurationField(
                                label = stringResource(R.string.manual_silence_hours_label),
                                value = manualDurationHours,
                                onValueChange = onDurationHoursChange,
                                inputTestTag = TestTags.MANUAL_SILENCE_DURATION_INPUT,
                                modifier = Modifier.weight(1f),
                            )
                            ManualSilenceDurationField(
                                label = stringResource(R.string.manual_silence_minutes_label),
                                value = manualDurationMinutes,
                                onValueChange = onDurationMinutesChange,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                ManualSilenceMode.UNTIL_PRAYER -> {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WAKE_SUPPORTED_PRAYERS.forEach { prayer ->
                            ManualSilenceOptionChip(
                                text = manualSilencePrayerName(prayer),
                                selected = prayer == manualTargetPrayer,
                                onClick = { onTargetPrayerChange(prayer) },
                                modifier = Modifier.testTag(TestTags.manualSilenceTargetPrayer(prayer.name)),
                            )
                        }
                    }
                }
                ManualSilenceMode.UNTIL_STOPPED -> Unit
            }
        }

        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag(TestTags.MANUAL_SILENCE_BUTTON),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
        ) {
            Icon(
                painter = painterResource(if (silenceActive) R.drawable.ic_adhkar_bell else R.drawable.ic_bell_off),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    silenceActive -> stringResource(R.string.manual_silence_action_stop)
                    manualSilenceMode == ManualSilenceMode.DURATION ->
                        stringResource(R.string.manual_silence_action_duration, durationText)
                    manualSilenceMode == ManualSilenceMode.UNTIL_PRAYER ->
                        stringResource(R.string.manual_silence_action_prayer, targetPrayerName)
                    else -> stringResource(R.string.manual_silence_action_now)
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** One choice in the duration or prayer row; same visual language as [EndpointModeChoices]. */
@Composable
private fun ManualSilenceOptionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 56.dp)
            .clip(shape)
            .background(if (selected) PrayerSilencePalette.Surface else PrayerSilencePalette.TintedStrip)
            .border(1.dp, if (selected) PrayerSilencePalette.InteractiveTeal else Color.Transparent, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) PrayerSilencePalette.PrimaryText else PrayerSilencePalette.SecondaryText,
        )
    }
}

/** A custom-duration number field with its unit, e.g. "[ 1 ] ساعة". */
@Composable
private fun ManualSilenceDurationField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    inputTestTag: String? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(PrayerSilencePalette.TintedStrip)
            .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NumberInput(
            value = value,
            onValueChange = onValueChange,
            inputDescription = label,
            modifier = Modifier
                .weight(1f)
                .then(if (inputTestTag != null) Modifier.testTag(inputTestTag) else Modifier),
        )
        Text(
            text = label,
            fontSize = 13.sp,
            color = PrayerSilencePalette.SecondaryText,
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

/**
 * The next prayer by instant over prayer days D − 1..D + 1 (6.6, 6.9): at 00:30 yesterday's 01:07 Isha is
 * still next. On a Friday, Jumu'a at its resolved time stands in Dhuhr's place.
 */
private fun resolveNextPrayerCountdown(
    context: Context,
    nowMillis: Long,
): NextPrayerCountdownInfo? {
    val place = PrayerTimesRepository.place(context)
    val now = java.time.Instant.ofEpochMilli(nowMillis)
    val next = PrayerTimesRepository.upcomingPrayers(context, now, place).firstOrNull() ?: return null
    val local = next.at.atZone(java.time.ZoneId.systemDefault())
    return NextPrayerCountdownInfo(
        prayer = next.prayer,
        hour = local.hour,
        minute = local.minute,
        triggerAtMillis = next.at.toEpochMilli(),
        isTomorrow = next.prayerDay > now.atZone(place.zone).toLocalDate(),
        prayerDay = next.prayerDay,
    )
}

/**
 * The id the screen keys its remembered times on: the delegation in Tunisia, else a negative hash of the
 * times key (6.6), so a new place or method abroad recomputes every remembered time and matches no delegation.
 */
internal fun screenTimesId(context: Context): Int = when (val place = PrayerTimesRepository.place(context)) {
    is PrayerPlace.Inm -> place.delegationId
    is PrayerPlace.Coordinates -> -(PrayerTimesRepository.timesKey(context, place).hashCode() and Int.MAX_VALUE) - 1
}

/** The prayer the manual silence card targets next: on a Friday the card's Dhuhr stands for Jumu'a. */
private fun resolveUpcomingManualSilencePrayer(
    context: Context,
    @Suppress("UNUSED_PARAMETER") delegationId: Int,
    nowMillis: Long = System.currentTimeMillis()
): Prayer? = PrayerTimesRepository.upcomingPrayers(context, java.time.Instant.ofEpochMilli(nowMillis))
    .firstOrNull()?.prayer?.let { if (it == Prayer.JOMOAA) Prayer.DHUHR else it }

/** The next adhan of [prayer] after now (6.9), Jumu'a standing for Dhuhr on a Friday. */
private fun resolveManualSilencePrayerEndMillis(
    context: Context,
    @Suppress("UNUSED_PARAMETER") delegationId: Int,
    prayer: Prayer,
    nowMillis: Long = System.currentTimeMillis()
): Long? {
    if (prayer !in WAKE_SUPPORTED_PRAYERS) return null
    return PrayerTimesRepository.upcomingPrayers(context, java.time.Instant.ofEpochMilli(nowMillis))
        .firstOrNull { (if (it.prayer == Prayer.JOMOAA) Prayer.DHUHR else it.prayer) == prayer }
        ?.at?.toEpochMilli()
}

/** What the auto-silence scheduler will do at the upcoming prayer. */
private sealed interface UpcomingSilence {
    data class Window(val window: PrayerTimelineWindow) : UpcomingSilence
    /** Auto-silence is off globally or was stopped outside Tunisia. */
    data object AutoSilenceOff : UpcomingSilence
    /** This prayer's own silence switch is off. */
    data object PrayerOff : UpcomingSilence
}

/** One line under the hero countdown; [scheduled] is false when the phone will keep ringing. */
private data class HeroSilenceLine(val text: String, val scheduled: Boolean)

/** Null only when the saved window is invalid; the prayer list already warns about that. */
private fun resolveUpcomingSilence(
    context: Context,
    countdown: NextPrayerCountdownInfo,
): UpcomingSilence? {
    if (!PrefsManager.isEnabled(context)) {
        return UpcomingSilence.AutoSilenceOff
    }
    if (!PrefsManager.isPrayerSilenceEnabled(context, countdown.prayer)) return UpcomingSilence.PrayerOff
    // The window the scheduler arms for that prayer day (6.9), on the editors' axis: the phone's clock (D8).
    val window = SilenceScheduler.windowOf(context, countdown.prayer, countdown.prayerDay) ?: return null
    val place = PrayerTimesRepository.place(context)
    val anchor = PrayerTimelineAnchor(
        countdown.prayer, countdown.prayerDay, java.time.Instant.ofEpochMilli(countdown.triggerAtMillis), place.zone, place is PrayerPlace.Inm,
    )
    return UpcomingSilence.Window(PrayerTimelineWindow(anchor.axisOf(window.start), anchor.axisOf(window.end)))
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
    return nextWakeAlarmTrigger(context, delegationId, config)?.triggerAtMillis
}

internal fun nextWakeAlarmTrigger(
    context: android.content.Context,
    delegationId: Int,
    config: com.tunisianprayertimes.PrayerWakeConfig,
): WakeAlarmComputer.ScheduledWakeTrigger? {
    return nextWakeAlarmTriggers(context, delegationId, config).firstOrNull()
}

private fun nextWakeAlarmTriggers(
    context: android.content.Context,
    delegationId: Int,
    config: com.tunisianprayertimes.PrayerWakeConfig,
): List<WakeAlarmComputer.ScheduledWakeTrigger> {
    if (!config.enabled) return emptyList()
    val now = Calendar.getInstance()
    val prayerDays = WakeAlarmScheduler.loadPrayerDayContexts(context, now)
    return WakeAlarmComputer.compute(now, config, prayerDays)
        .allTriggers
}

internal fun scheduledWakeAlarmTriggersForDay(
    context: android.content.Context,
    delegationId: Int,
    config: com.tunisianprayertimes.PrayerWakeConfig,
    day: Calendar,
): List<WakeAlarmComputer.ScheduledWakeTrigger> {
    if (!config.enabled) return emptyList()
    val isOneOff = config.mainAlarm.mode == WakeMainAlarmMode.FROM_NOW || config.repeatMode == WakeRepeatMode.ONCE
    if (isOneOff && config.mainAlarm.oneOffTriggerAtMillis <= 0L) return emptyList()
    val dayStart = startOfDayMillis(day.timeInMillis)
    val dayEnd = (day.clone() as Calendar).apply {
        timeInMillis = dayStart
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
    // Work backwards from each ring's offset to find its possible prayer dates. A ring just after
    // midnight can belong to yesterday's occurrence; a pre-alarm can belong to tomorrow's. The prayer itself
    // can fall on another calendar day than its prayer day (an Isha after midnight in Paris in June), so each
    // range takes one more day on both sides; rings outside [day] are filtered out below.
    val prayerOffset = if (config.mainAlarm.mode == WakeMainAlarmMode.PRAYER_RELATIVE) {
        config.mainAlarm.prayerOffset.minutes.toLong()
    } else {
        0L
    }
    val candidateDays = linkedSetOf<Long>()
    (listOf(0L) + config.subAlarms.map { it.signedOffsetMinutes.toLong() }).forEach { ringOffset ->
        val totalOffsetMillis = (prayerOffset + ringOffset) * 60_000L
        val firstDay = startOfDayMillis(dayStart - totalOffsetMillis)
        val lastDay = (day.clone() as Calendar).apply {
            timeInMillis = startOfDayMillis(dayEnd - 1L - totalOffsetMillis)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val candidate = (day.clone() as Calendar).apply {
            timeInMillis = firstDay
            add(Calendar.DAY_OF_YEAR, -1)
        }
        while (candidate.timeInMillis <= lastDay) {
            candidateDays.add(candidate.timeInMillis)
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }
    }
    if (isOneOff) {
        candidateDays.add(startOfDayMillis(config.mainAlarm.oneOffTriggerAtMillis))
    }
    // Each candidate date as its prayer day of instants at the saved place (6.6), by its day offset from [day].
    val zone = java.time.ZoneId.systemDefault()
    val firstDate = java.time.Instant.ofEpochMilli(dayStart).atZone(zone).toLocalDate()
    val dayCalendar = (day.clone() as Calendar).apply { timeInMillis = dayStart }
    val prayerDays = candidateDays.sorted().flatMap { dateMillis ->
        val date = java.time.Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()
        val offset = java.time.temporal.ChronoUnit.DAYS.between(firstDate, date).toInt()
        WakeAlarmScheduler.loadPrayerDayContexts(context, dayCalendar, offset..offset)
    }
    return WakeAlarmComputer.scheduledTriggers(config, prayerDays)
        .filter { trigger -> trigger.triggerAtMillis >= dayStart && trigger.triggerAtMillis < dayEnd }
}

/** «تأكيد الموقع» (6.8): the place a skipped first run chose, until the user confirms it or picks another. */
@Composable
private fun ProvisionalPlaceBanner(onConfirm: () -> Unit, onChange: () -> Unit) {
    val context = LocalContext.current
    val label = remember { PrefsManager.getPlaceNameSnapshot(context) ?: context.getString(R.string.location_default_tunis) }
    // Inside the page's own 16 dp margins, under the place header.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BannerBg)
            .border(1.dp, BannerStroke, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("provisional_place_banner"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.location_confirm_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = BannerText)
        Text(stringResource(R.string.location_confirm_text, label), fontSize = 13.sp, color = TextDark, lineHeight = 19.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.location_confirm_yes), color = GreenPrimaryDark, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onChange, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.location_confirm_change), color = GreenPrimaryDark)
            }
        }
    }
}

/** Two ends are the same setting; a fixed end by its clock alone, its saved offset being the day's (D16). */
private fun sameSilenceEnd(a: SilenceEnd, b: SilenceEnd): Boolean =
    if (a is SilenceEnd.Clock && b is SilenceEnd.Clock) a.time == b.time else a == b

/** A clock picked in the phone's clock for [anchor]'s day, as the place's clock it is stored in (6.6). */
private fun pickedPlaceClock(anchor: PrayerTimelineAnchor?, hour: Int, minute: Int): LocalTime =
    anchor?.placeClockAt(Math.floorDiv(anchor.minutes, 1440) * 1440 + hour * 60 + minute) ?: LocalTime.of(hour, minute)

private fun refreshWakeSilenceConflictEditor(
    context: Context,
    delegationId: Int,
    editorState: WakeSilenceConflictEditorState,
): WakeSilenceConflictEditorState = editorState.copy(
    currentConflict = findWakeSilenceConflictForGuide(
        context = context,
        delegationId = delegationId,
        config = editorState.draftConfig,
        targetPrayer = editorState.originalConflict.silencePrayer,
        stagedSilenceConfig = editorState.draftSilenceConfig,
    ),
    hasEditedSilenceWindow = true,
    editRevision = editorState.editRevision + 1,
)

/**
 * Stores a prayer's window as the user left it (plan 6.9): only the endpoint that moved, so the other keeps following
 * its default (D12, A18); a Dhuhr or Jumu'a endpoint moved back to its default follows it again; and a fixed end's
 * offset from the adhan on the day shown ([anchor], else today), which D16 falls back on.
 */
private fun persistPrayerSilenceConfig(
    context: Context,
    prayer: Prayer,
    config: PrayerSilenceConfig,
    anchor: PrayerTimelineAnchor? = null,
) {
    val place = PrayerTimesRepository.place(context)
    val inm = place is PrayerPlace.Inm
    val previous = SilenceEndpoints.withDefaults(PrefsManager.getSilenceRule(context, prayer), prayer, inm)
    val defaults = SilenceEndpoints.withDefaults(SilenceRule(SilenceStart.Unset, SilenceEnd.Unset), prayer, inm)
    val next = SilenceRule.of(config)
    val followsDefaults = prayer == Prayer.DHUHR || prayer == Prayer.JOMOAA
    fun save(moved: Boolean, isDefault: Boolean) = when {
        !moved -> PrefsManager.EndpointSave.KEEP
        followsDefaults && isDefault -> PrefsManager.EndpointSave.CLEAR
        else -> PrefsManager.EndpointSave.WRITE
    }
    val shownOn = anchor ?: timelineAnchorFor(context, prayer, LocalDate.now(place.zone), place)
    val savedOffset = if (config.endRuleMode() == EndpointRuleMode.FIXED_TIME) {
        shownOn?.let { it.offsetAt(resolvePrayerTimelineWindow(it, config).endMinutes) }
    } else null
    PrefsManager.setConfig(
        context, prayer, config,
        start = save(next.start != previous.start, next.start == defaults.start),
        end = save(!sameSilenceEnd(next.end, previous.end), sameSilenceEnd(next.end, defaults.end)),
        savedEndOffsetMinutes = savedOffset,
    )
    AnalyticsTracker.silenceConfigChanged(
        context = context,
        prayer = prayer,
        mode = config.mode,
        durationMinutes = config.afterMinutes,
        delayMode = config.delayMode,
    )
}

private fun findWakeSilenceConflictForGuide(
    context: Context,
    delegationId: Int,
    config: PrayerWakeConfig,
    targetPrayer: Prayer,
    stagedSilenceConfig: PrayerSilenceConfig? = null,
): WakeSilenceConflictSummary? {
    if (!config.enabled || !PrefsManager.isEnabled(context)) return null

    val now = Calendar.getInstance()
    val prayerDays = WakeAlarmScheduler.loadPrayerDayContexts(context, now)
    // The windows the scheduler arms (6.9), with the editor's draft in place of the target prayer's.
    val planner = SilenceScheduler.plannerWithDraft(context, targetPrayer, stagedSilenceConfig)

    return WakeAlarmComputer.compute(now, config, prayerDays)
        .allTriggers
        .asSequence()
        .mapNotNull { trigger ->
            planner.containing(java.time.Instant.ofEpochMilli(trigger.triggerAtMillis))
                .firstOrNull { window -> window.prayer == targetPrayer }
                ?.let { window ->
                    WakeSilenceConflictSummary(
                        detail = context.getString(
                            R.string.wake_editor_warning_silence_overlap,
                            wakeGuideTriggerLabel(context, trigger),
                            formatWakeAlarmDateTime(trigger.triggerAtMillis),
                            prayerName(context, window.prayer),
                            formatWakeAlarmDateTime(window.start.toEpochMilli()),
                            formatWakeAlarmDateTime(window.end.toEpochMilli()),
                        ),
                        silencePrayer = window.prayer,
                        silenceStartAtMillis = window.start.toEpochMilli(),
                        silenceEndAtMillis = window.end.toEpochMilli(),
                        triggerAtMillis = trigger.triggerAtMillis,
                    )
                }
        }
        .firstOrNull()
}

private fun wakeGuideTriggerLabel(
    context: Context,
    trigger: WakeAlarmComputer.ScheduledWakeTrigger,
): String = if (trigger.isSubAlarm) {
    context.getString(
        R.string.wake_editor_warning_trigger_subalarm,
        context.getString(
            if (trigger.signedOffsetMinutes < 0) {
                R.string.wake_alarm_offset_before
            } else {
                R.string.wake_alarm_offset_after
            },
            formatArabicMinutes(abs(trigger.signedOffsetMinutes)),
        ),
    )
} else {
    context.getString(R.string.wake_editor_warning_trigger_main)
}

private fun formatWakeAlarmDateTime(timeInMillis: Long): String {
    val formatter = SimpleDateFormat("EEE d MMM - HH:mm", Locale.forLanguageTag("ar-TN-u-nu-latn"))
    return formatter.format(Date(timeInMillis))
}

private fun formatWakeAlarmDate(timeInMillis: Long): String {
    val formatter = SimpleDateFormat("EEE d MMM", Locale.forLanguageTag("ar-TN-u-nu-latn"))
    return formatter.format(Date(timeInMillis))
}

private fun formatClockTime(hour: Int, minute: Int): String =
    String.format(Locale.US, "%02d:%02d", hour, minute)

private fun minuteOfDay(targetTimeInMillis: Long): Int {
    val calendar = Calendar.getInstance().apply { timeInMillis = targetTimeInMillis }
    return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
}

private fun formatTimeOfDay(targetTimeInMillis: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = targetTimeInMillis }
    return String.format(
        Locale.US,
        "%02d:%02d",
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE)
    )
}
