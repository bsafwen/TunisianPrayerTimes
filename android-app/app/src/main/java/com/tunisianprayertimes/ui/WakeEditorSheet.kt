package com.tunisianprayertimes.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.tunisianprayertimes.ClockTime
import com.tunisianprayertimes.MathDifficulty
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerRelativeOffset
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrayerWakeSubAlarm
import com.tunisianprayertimes.R
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.SilenceAlarmComputer
import com.tunisianprayertimes.WAKE_SUPPORTED_PRAYERS
import com.tunisianprayertimes.WAKE_RECURRING_LOOKAHEAD_DAYS
import com.tunisianprayertimes.WakeAlarmComputer
import com.tunisianprayertimes.WakeMainAlarmConfig
import com.tunisianprayertimes.WakeMainAlarmMode
import com.tunisianprayertimes.WakePlaybackOptions
import com.tunisianprayertimes.WakeRepeatMode
import com.tunisianprayertimes.WakeScheduleDay
import com.tunisianprayertimes.WakeUpCheckStep
import com.tunisianprayertimes.WakeUpCheckType
import com.tunisianprayertimes.formatArabicMinutes
import com.tunisianprayertimes.normalizedWakeScheduleDays
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.SilenceRed
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import com.tunisianprayertimes.wake.GyroscopeMazeSensorState
import com.tunisianprayertimes.wake.GyroscopeMazeGame
import com.tunisianprayertimes.wake.WhackAMoleGame
import com.tunisianprayertimes.wake.WakeRingtoneCatalog
import com.tunisianprayertimes.wake.WakeRingtonePreviewPlayer
import com.tunisianprayertimes.wake.hasGyroscopeMazeTiltSensor
import com.tunisianprayertimes.wake.rememberGyroscopeMazeSensorState
import com.tunisianprayertimes.wake.wakeUpCheckChallengeFor
import com.tunisianprayertimes.wake.wakeUpCheckChallengeForStep
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

data class WakeSilenceConflictSummary(
    val detail: String,
    val silencePrayer: Prayer,
    val silenceStartAtMillis: Long,
    val silenceEndAtMillis: Long,
    val triggerAtMillis: Long,
)

private data class WakeSilenceConflictKey(
    val silencePrayer: Prayer,
    val silenceStartAtMillis: Long,
    val silenceEndAtMillis: Long,
    val triggerAtMillis: Long,
)

private fun WakeSilenceConflictSummary.toKey(): WakeSilenceConflictKey = WakeSilenceConflictKey(
    silencePrayer = silencePrayer,
    silenceStartAtMillis = silenceStartAtMillis,
    silenceEndAtMillis = silenceEndAtMillis,
    triggerAtMillis = triggerAtMillis,
)

data class WakeSilenceConflictResolverState(
    val conflict: WakeSilenceConflictSummary,
    val resolved: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WakeEditorSheet(
    activity: AppCompatActivity,
    delegationId: Int,
    initialConfig: PrayerWakeConfig,
    isNewAlarm: Boolean = false,
    silenceConfigRevision: Int = 0,
    silenceConflictResolverState: WakeSilenceConflictResolverState? = null,
    silenceConflictResolverContent: @Composable (WakeSilenceConflictSummary) -> Unit = {},
    onDismissRequest: () -> Unit,
    onSave: (PrayerWakeConfig) -> Unit,
    onEditSilenceWindowRequest: (PrayerWakeConfig, WakeSilenceConflictSummary) -> Unit = { _, _ -> },
    onCancelSilenceWindowEdit: () -> Unit = {},
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    DisposableEffect(activity) {
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        val previousLightStatusBars = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = true
        onDispose {
            controller.isAppearanceLightStatusBars = previousLightStatusBars
        }
    }
    val enabled = initialConfig.enabled
    var mode by remember(initialConfig.id, initialConfig.mainAlarm.mode) {
        mutableStateOf(initialConfig.mainAlarm.mode)
    }
    var selectedPrayer by remember(initialConfig.id, initialConfig.prayer) {
        mutableStateOf(initialConfig.prayer)
    }
    var fixedHour by remember(initialConfig.id, initialConfig.mainAlarm.fixedTime.hour) {
        mutableIntStateOf(initialConfig.mainAlarm.fixedTime.hour)
    }
    var fixedMinute by remember(initialConfig.id, initialConfig.mainAlarm.fixedTime.minute) {
        mutableIntStateOf(initialConfig.mainAlarm.fixedTime.minute)
    }
    var relativeOffsetDirection by remember(initialConfig.id, initialConfig.mainAlarm.prayerOffset.minutes) {
        mutableStateOf(
            if (initialConfig.mainAlarm.prayerOffset.minutes > 0) {
                OffsetDirection.AFTER
            } else {
                OffsetDirection.BEFORE
            }
        )
    }
    var relativeOffsetText by remember(initialConfig.id, initialConfig.mainAlarm.prayerOffset.minutes) {
        mutableStateOf(initialConfig.mainAlarm.prayerOffset.absoluteMinutes.toString())
    }
    val initialFromNowOffsetMinutes = remember(initialConfig.id, initialConfig.mainAlarm.oneOffOffsetMinutes) {
        initialConfig.mainAlarm.oneOffOffsetMinutes.coerceAtLeast(1)
    }
    var fromNowHoursText by remember(initialConfig.id, initialFromNowOffsetMinutes) {
        mutableStateOf(
            (initialFromNowOffsetMinutes / 60)
                .takeIf { hours -> hours > 0 }
                ?.toString()
                ?: ""
        )
    }
    var fromNowMinutesText by remember(initialConfig.id, initialFromNowOffsetMinutes) {
        mutableStateOf((initialFromNowOffsetMinutes % 60).toString())
    }
    var fromNowTriggerAtMillis by remember(
        initialConfig.id,
        initialConfig.mainAlarm.oneOffTriggerAtMillis,
        initialFromNowOffsetMinutes,
    ) {
        mutableLongStateOf(
            initialConfig.mainAlarm.oneOffTriggerAtMillis
                .takeIf { triggerAtMillis -> triggerAtMillis > 0L }
                ?: System.currentTimeMillis() + initialFromNowOffsetMinutes.toMillis(),
        )
    }
    var mainPlayback by remember(initialConfig.id, initialConfig.playback) {
        mutableStateOf(initialConfig.playback)
    }
    var scheduledDays by remember(initialConfig.id, initialConfig.scheduledDays) {
        mutableStateOf(initialConfig.scheduledDays.normalizedWakeScheduleDays())
    }
    var repeatMode by remember(initialConfig.id, initialConfig.repeatMode, initialConfig.mainAlarm.mode) {
        mutableStateOf(
            if (initialConfig.mainAlarm.mode == WakeMainAlarmMode.FROM_NOW) {
                WakeRepeatMode.ONCE
            } else {
                initialConfig.repeatMode
            },
        )
    }
    var subAlarms by remember(initialConfig.id, initialConfig.subAlarms) {
        mutableStateOf(initialConfig.subAlarms)
    }
    var silenceUntilAlarm by remember(initialConfig.id, initialConfig.silenceUntilAlarm) {
        mutableStateOf(initialConfig.silenceUntilAlarm)
    }
    var ringDuringSilenceWindow by remember(initialConfig.id, initialConfig.ringDuringSilenceWindow) {
        mutableStateOf(initialConfig.ringDuringSilenceWindow)
    }

    val parsedRelativeOffset = relativeOffsetText.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val signedRelativeOffset = if (relativeOffsetDirection == OffsetDirection.BEFORE) {
        -parsedRelativeOffset
    } else {
        parsedRelativeOffset
    }
    val parsedFromNowOffsetMinutes = parseFromNowOffsetMinutes(
        hoursText = fromNowHoursText,
        minutesText = fromNowMinutesText,
        fallbackMinutes = initialFromNowOffsetMinutes,
    )
    val effectiveScheduledDays = scheduledDays.normalizedWakeScheduleDays()
    val effectiveRepeatMode = if (mode == WakeMainAlarmMode.FROM_NOW) WakeRepeatMode.ONCE else repeatMode
    val supportsSilenceUntilAlarm = mode == WakeMainAlarmMode.FROM_NOW
    val effectiveSilenceUntilAlarm = silenceUntilAlarm && supportsSilenceUntilAlarm

    fun rescheduleFromNowAlarm(totalMinutes: Int) {
        val safeTotalMinutes = totalMinutes.coerceAtLeast(1)
        val hours = safeTotalMinutes / 60
        val minutes = safeTotalMinutes % 60
        fromNowHoursText = hours.takeIf { it > 0 }?.toString().orEmpty()
        fromNowMinutesText = minutes.toString()
        fromNowTriggerAtMillis = System.currentTimeMillis() + safeTotalMinutes.toMillis()
    }

    val draftConfig = remember(
        initialConfig,
        mode,
        selectedPrayer,
        fixedHour,
        fixedMinute,
        signedRelativeOffset,
        parsedFromNowOffsetMinutes,
        fromNowTriggerAtMillis,
        effectiveRepeatMode,
        effectiveScheduledDays,
        mainPlayback,
        subAlarms,
        effectiveSilenceUntilAlarm,
        ringDuringSilenceWindow,
    ) {
        initialConfig.copy(
            title = "",
            prayer = selectedPrayer,
            enabled = enabled,
            mainAlarm = WakeMainAlarmConfig(
                mode = mode,
                fixedTime = ClockTime(fixedHour, fixedMinute),
                prayerOffset = PrayerRelativeOffset(signedRelativeOffset),
                oneOffOffsetMinutes = parsedFromNowOffsetMinutes,
                oneOffTriggerAtMillis = if (mode == WakeMainAlarmMode.FROM_NOW) fromNowTriggerAtMillis else 0L,
            ),
            repeatMode = effectiveRepeatMode,
            scheduledDays = effectiveScheduledDays,
            playback = mainPlayback,
            subAlarms = subAlarms,
            silenceUntilAlarm = effectiveSilenceUntilAlarm,
            ringDuringSilenceWindow = ringDuringSilenceWindow,
        )
    }
    val preview = remember(delegationId, draftConfig, silenceConfigRevision) {
        computeWakePreview(context, delegationId, draftConfig)
    }
    val activeRecurringSilenceConflict = preview?.warning?.conflict?.takeIf {
        draftConfig.enabled &&
            draftConfig.mainAlarm.mode != WakeMainAlarmMode.FROM_NOW &&
            draftConfig.repeatMode != WakeRepeatMode.ONCE
    }
    val timelineEntries = remember(delegationId, draftConfig) {
        computeWakeTimelineEntries(context, delegationId, draftConfig)
    }
    val behaviorSummary = remember(context, mainPlayback) {
        formatWakeBehaviorSummary(context, mainPlayback)
    }
    val advancedSummary = if (subAlarms.isEmpty()) {
        null
    } else {
        stringResource(R.string.wake_editor_subalarms_count_summary, subAlarms.size)
    }

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val newSubAlarmIds = remember { mutableStateListOf<String>() }
    var modePickerVisible by rememberSaveable(initialConfig.id) { mutableStateOf(false) }
    var silenceConflictDialogVisible by remember(initialConfig.id) { mutableStateOf(false) }
    var dismissedSilenceConflictKey by remember(initialConfig.id) { mutableStateOf<WakeSilenceConflictKey?>(null) }
    var behaviorExpanded by rememberSaveable(initialConfig.id) {
        mutableStateOf(!isNewAlarm && shouldExpandWakeBehavior(initialConfig.playback))
    }
    var advancedExpanded by rememberSaveable(initialConfig.id) {
        mutableStateOf(!isNewAlarm && initialConfig.subAlarms.isNotEmpty())
    }

    val unresolvedRecurringConflict = activeRecurringSilenceConflict
        ?.takeIf { !ringDuringSilenceWindow }
        ?.toSummary()
    val unresolvedRecurringConflictKey = unresolvedRecurringConflict?.toKey()
    val resolverConflictKey = silenceConflictResolverState?.conflict?.toKey()
    val resolverResolvedForCurrentConflict = silenceConflictResolverState?.resolved == true &&
        resolverConflictKey == unresolvedRecurringConflictKey
    val silenceConflictDialogConflict = silenceConflictResolverState?.conflict ?: unresolvedRecurringConflict

    LaunchedEffect(unresolvedRecurringConflictKey, ringDuringSilenceWindow) {
        if (unresolvedRecurringConflictKey == null || ringDuringSilenceWindow) {
            silenceConflictDialogVisible = false
            dismissedSilenceConflictKey = null
        }
    }

    LaunchedEffect(
        unresolvedRecurringConflictKey,
        ringDuringSilenceWindow,
        resolverResolvedForCurrentConflict,
        dismissedSilenceConflictKey,
    ) {
        val shouldOpenForConflict = unresolvedRecurringConflictKey != null &&
            !ringDuringSilenceWindow &&
            !resolverResolvedForCurrentConflict &&
            dismissedSilenceConflictKey != unresolvedRecurringConflictKey
        if (shouldOpenForConflict) {
            silenceConflictDialogVisible = true
        }
    }

    fun saveDraftConfig() {
        val configToSave = resolveOneTimeWakeTrigger(context, delegationId, draftConfig)
        val latestPreview = computeWakePreview(context, delegationId, configToSave)
        val recurringConflict = latestPreview?.warning?.conflict?.takeIf {
            configToSave.enabled &&
                configToSave.mainAlarm.mode != WakeMainAlarmMode.FROM_NOW &&
                configToSave.repeatMode != WakeRepeatMode.ONCE &&
                !configToSave.ringDuringSilenceWindow &&
                !resolverResolvedForCurrentConflict
        }
        if (recurringConflict != null) {
            silenceConflictDialogVisible = true
        } else {
            onSave(configToSave)
        }
    }

    fun dismissSilenceConflictDialog() {
        dismissedSilenceConflictKey = unresolvedRecurringConflictKey
        silenceConflictDialogVisible = false
        onCancelSilenceWindowEdit()
    }

    fun selectMainAlarmMode(nextMode: WakeMainAlarmMode) {
        val previousMode = mode
        mode = nextMode
        if (nextMode == WakeMainAlarmMode.FROM_NOW) {
            repeatMode = WakeRepeatMode.ONCE
            fromNowTriggerAtMillis = System.currentTimeMillis() + parsedFromNowOffsetMinutes.toMillis()
        } else if (previousMode == WakeMainAlarmMode.FROM_NOW) {
            repeatMode = WakeRepeatMode.RECURRING
        }
    }

    BackHandler(onBack = onDismissRequest)

    if (modePickerVisible) {
        WakeModePickerSheet(
            selectedMode = mode,
            onModeSelected = { nextMode ->
                selectMainAlarmMode(nextMode)
                modePickerVisible = false
            },
            onDismiss = { modePickerVisible = false },
        )
    }

    if (silenceConflictDialogVisible && silenceConflictDialogConflict != null) {
        WakeRecurringSilenceConflictDialog(
            conflict = silenceConflictDialogConflict,
            editorState = silenceConflictResolverState,
            editorContent = silenceConflictResolverContent,
            onAdjustSilenceWindow = {
                onEditSilenceWindowRequest(
                    draftConfig.copy(ringDuringSilenceWindow = false),
                    silenceConflictDialogConflict,
                )
            },
            onRingDuringSilence = {
                ringDuringSilenceWindow = true
                silenceConflictDialogVisible = false
            },
            onDone = { silenceConflictDialogVisible = false },
            onBackToChoices = { onCancelSilenceWindowEdit() },
            onDismiss = { dismissSilenceConflictDialog() },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        if (isNewAlarm) R.string.wake_editor_new_title else R.string.wake_editor_title,
                    ),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrayerNameColor,
                    modifier = Modifier.weight(1f),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onDismissRequest,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.heightIn(min = 38.dp),
                        border = BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.34f)),
                    ) {
                        Text(text = stringResource(R.string.wake_editor_cancel))
                    }
                    Button(
                        onClick = { saveDraftConfig() },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.heightIn(min = 38.dp),
                    ) {
                        Text(text = stringResource(R.string.wake_editor_save))
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WakeEditorPreviewCard(
                    enabled = enabled,
                    preview = preview,
                )

                WakeEditorSectionCard(
                    title = stringResource(R.string.wake_editor_main_section_title),
                ) {
                    WakeScheduleBuilder(
                        activity = activity,
                        alarmId = initialConfig.id,
                        mode = mode,
                        onChangeModeClick = { modePickerVisible = true },
                        selectedPrayer = selectedPrayer,
                        onPrayerSelected = { prayer -> selectedPrayer = prayer },
                        offsetDirection = relativeOffsetDirection,
                        onOffsetDirectionChange = { direction -> relativeOffsetDirection = direction },
                        offsetText = relativeOffsetText,
                        onOffsetTextChange = { newValue -> relativeOffsetText = sanitizeMinutesInput(newValue) },
                        fixedHour = fixedHour,
                        fixedMinute = fixedMinute,
                        onFixedTimePicked = { hour, minute ->
                            fixedHour = hour
                            fixedMinute = minute
                        },
                        selectedDays = effectiveScheduledDays,
                        onSelectedDaysChange = { days -> scheduledDays = days.normalizedWakeScheduleDays() },
                        repeatMode = effectiveRepeatMode,
                        onRepeatModeChange = { updated -> repeatMode = updated },
                        fromNowHoursText = fromNowHoursText,
                        fromNowMinutesText = fromNowMinutesText,
                        onFromNowDurationMinutesChange = { totalMinutes -> rescheduleFromNowAlarm(totalMinutes) },
                        silenceUntilAlarm = effectiveSilenceUntilAlarm,
                        onSilenceUntilAlarmChange = { updated -> silenceUntilAlarm = updated },
                    )
                }

                if (enabled) {
                    preview?.warning?.takeIf { warning -> warning.conflict == null }?.let { warning ->
                        WakeEditorWarningCard(warning = warning)
                    }
                }

                WakeEditorSectionCard(
                    title = stringResource(R.string.wake_editor_behavior_section_title),
                    subtitle = behaviorSummary,
                    expanded = behaviorExpanded,
                    onExpandedChange = { behaviorExpanded = !behaviorExpanded },
                ) {
                    WakeSoundControls(
                        ringtoneLabel = stringResource(R.string.wake_editor_main_ringtone_label),
                        playback = mainPlayback,
                        onPlaybackChange = { updated -> mainPlayback = updated },
                    )

                    HorizontalDivider(color = Gold.copy(alpha = 0.16f))

                    WakeWakeCheckControls(
                        playback = mainPlayback,
                        onPlaybackChange = { updated -> mainPlayback = updated },
                    )
                }

                WakeEditorSectionCard(
                    title = stringResource(R.string.wake_editor_advanced_section_title),
                    subtitle = advancedSummary,
                    expanded = advancedExpanded,
                    onExpandedChange = { advancedExpanded = !advancedExpanded },
                ) {
                    if (subAlarms.isEmpty()) {
                        Text(
                            text = stringResource(R.string.wake_editor_subalarms_empty),
                            fontSize = 12.sp,
                            color = TextMuted,
                            lineHeight = 17.sp,
                        )
                    } else {
                        WakeSubAlarmTimeline(
                            subAlarms = subAlarms,
                            entries = timelineEntries,
                        )

                        subAlarms.forEachIndexed { index, subAlarm ->
                            AnimatedVisibility(
                                visible = true,
                                enter = if (subAlarm.id in newSubAlarmIds) {
                                    androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn()
                                } else {
                                    androidx.compose.animation.EnterTransition.None
                                },
                            ) {
                                WakeSubAlarmEditorCard(
                                    index = index,
                                    subAlarm = subAlarm,
                                    onChange = { updated ->
                                        subAlarms = subAlarms.map { existing ->
                                            if (existing.id == updated.id) updated else existing
                                        }
                                    },
                                    onRemove = {
                                        newSubAlarmIds -= subAlarm.id
                                        subAlarms = subAlarms.filterNot { existing -> existing.id == subAlarm.id }
                                    },
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        OutlinedButton(
                            onClick = {
                                val newId = UUID.randomUUID().toString()
                                newSubAlarmIds += newId
                                subAlarms = subAlarms + PrayerWakeSubAlarm(
                                    id = newId,
                                    minutesOffset = 10,
                                    direction = OffsetDirection.BEFORE,
                                )
                                coroutineScope.launch {
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.34f)),
                        ) {
                            Text(text = stringResource(R.string.wake_editor_subalarms_add))
                        }
                    }
                }

                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(text = stringResource(R.string.wake_editor_cancel))
                }

                if (onDelete != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SilenceRed.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = SilenceRed,
                        ),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = stringResource(R.string.wake_editor_delete),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WakeEditorSectionCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    expanded: Boolean = true,
    onExpandedChange: (() -> Unit)? = null,
    containerColor: Color = GoldLight.copy(alpha = 0.08f),
    borderColor: Color = Gold.copy(alpha = 0.58f),
    content: @Composable ColumnScope.() -> Unit,
) {
    val collapsible = onExpandedChange != null
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
        border = BorderStroke(1.5.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val headerShape = RoundedCornerShape(10.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (collapsible) {
                            Modifier
                                .clip(headerShape)
                                .clickable { onExpandedChange?.invoke() }
                                .padding(horizontal = 2.dp, vertical = 2.dp)
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrayerNameColor,
                    )

                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            fontSize = 12.sp,
                            color = TextMuted,
                            lineHeight = 17.sp,
                        )
                    }
                }

                if (collapsible) {
                    Icon(
                        painter = painterResource(
                            if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                        ),
                        contentDescription = stringResource(
                            if (expanded) {
                                R.string.wake_editor_section_collapse
                            } else {
                                R.string.wake_editor_section_expand
                            },
                        ),
                        tint = GreenPrimaryDark,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun WakeScheduleBuilder(
    activity: AppCompatActivity,
    alarmId: String,
    mode: WakeMainAlarmMode,
    onChangeModeClick: () -> Unit,
    selectedPrayer: Prayer,
    onPrayerSelected: (Prayer) -> Unit,
    offsetDirection: OffsetDirection,
    onOffsetDirectionChange: (OffsetDirection) -> Unit,
    offsetText: String,
    onOffsetTextChange: (String) -> Unit,
    fixedHour: Int,
    fixedMinute: Int,
    onFixedTimePicked: (hour: Int, minute: Int) -> Unit,
    selectedDays: Set<WakeScheduleDay>,
    onSelectedDaysChange: (Set<WakeScheduleDay>) -> Unit,
    repeatMode: WakeRepeatMode,
    onRepeatModeChange: (WakeRepeatMode) -> Unit,
    fromNowHoursText: String,
    fromNowMinutesText: String,
    onFromNowDurationMinutesChange: (Int) -> Unit,
    silenceUntilAlarm: Boolean,
    onSilenceUntilAlarmChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val summary = formatWakeScheduleSummary(
        context = context,
        mode = mode,
        selectedPrayer = selectedPrayer,
        offsetDirection = offsetDirection,
        offsetText = offsetText,
        fixedHour = fixedHour,
        fixedMinute = fixedMinute,
        scheduledDays = selectedDays,
        repeatMode = repeatMode,
        fromNowHoursText = fromNowHoursText,
        fromNowMinutesText = fromNowMinutesText,
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WakeScheduleSummaryBand(summary = summary)
        WakeScheduleTypeRow(
            mode = mode,
            onChangeModeClick = onChangeModeClick,
        )

        when (mode) {
            WakeMainAlarmMode.PRAYER_RELATIVE -> {
                WakePrayerRelativeModeControls(
                    selectedPrayer = selectedPrayer,
                    onPrayerSelected = onPrayerSelected,
                    offsetDirection = offsetDirection,
                    onOffsetDirectionChange = onOffsetDirectionChange,
                    offsetText = offsetText,
                    onOffsetTextChange = onOffsetTextChange,
                )
            }

            WakeMainAlarmMode.FIXED_TIME -> {
                WakeFixedTimeModeControls(
                    activity = activity,
                    timePickerTag = "wake_main_time_$alarmId",
                    fixedHour = fixedHour,
                    fixedMinute = fixedMinute,
                    onTimePicked = onFixedTimePicked,
                )
            }

            WakeMainAlarmMode.FROM_NOW -> {
                WakeFromNowModeControls(
                    hoursText = fromNowHoursText,
                    minutesText = fromNowMinutesText,
                    onDurationMinutesChange = onFromNowDurationMinutesChange,
                    silenceUntilAlarm = silenceUntilAlarm,
                    onSilenceUntilAlarmChange = onSilenceUntilAlarmChange,
                )
            }
        }

        if (mode != WakeMainAlarmMode.FROM_NOW) {
            WakeRepetitionSelector(
                repeatMode = repeatMode,
                onRepeatModeChange = onRepeatModeChange,
                selectedDays = selectedDays,
                onSelectedDaysChange = onSelectedDaysChange,
            )
        }
    }
}

@Composable
private fun WakeScheduleSummaryBand(summary: String) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        text = summary,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GreenPrimary.copy(alpha = 0.10f))
            .border(BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.18f)), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = GreenPrimaryDark,
        lineHeight = 28.sp,
    )
}

@Composable
private fun WakeScheduleTypeRow(
    mode: WakeMainAlarmMode,
    onChangeModeClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onChangeModeClick)
            .background(Color.White.copy(alpha = 0.72f))
            .border(BorderStroke(1.dp, Gold.copy(alpha = 0.20f)), shape)
            .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.wake_editor_mode_type_label),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
            )
            Text(
                text = wakeModeTitle(mode),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark,
            )
            Text(
                text = wakeModeHint(mode),
                fontSize = 12.sp,
                color = TextMuted,
                lineHeight = 16.sp,
            )
        }

        TextButton(
            onClick = onChangeModeClick,
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                text = stringResource(R.string.wake_editor_mode_change),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeModePickerSheet(
    selectedMode: WakeMainAlarmMode,
    onModeSelected: (WakeMainAlarmMode) -> Unit,
    onDismiss: () -> Unit,
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
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.wake_editor_mode_sheet_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor,
            )

            WakeModePickerRow(
                mode = WakeMainAlarmMode.PRAYER_RELATIVE,
                selected = selectedMode == WakeMainAlarmMode.PRAYER_RELATIVE,
                onClick = { onModeSelected(WakeMainAlarmMode.PRAYER_RELATIVE) },
            )
            WakeModePickerRow(
                mode = WakeMainAlarmMode.FIXED_TIME,
                selected = selectedMode == WakeMainAlarmMode.FIXED_TIME,
                onClick = { onModeSelected(WakeMainAlarmMode.FIXED_TIME) },
            )
            WakeModePickerRow(
                mode = WakeMainAlarmMode.FROM_NOW,
                selected = selectedMode == WakeMainAlarmMode.FROM_NOW,
                onClick = { onModeSelected(WakeMainAlarmMode.FROM_NOW) },
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WakeModePickerRow(
    mode: WakeMainAlarmMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .background(if (selected) GreenPrimary.copy(alpha = 0.10f) else GoldLight.copy(alpha = 0.08f))
            .border(
                BorderStroke(1.dp, if (selected) GreenPrimary else Gold.copy(alpha = 0.20f)),
                shape,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = wakeModeTitle(mode),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) GreenPrimaryDark else TextDark,
            )
            Text(
                text = wakeModeHint(mode),
                fontSize = 12.sp,
                color = if (selected) GreenPrimaryDark.copy(alpha = 0.78f) else TextMuted,
                lineHeight = 17.sp,
            )
        }

        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = GreenPrimary,
                unselectedColor = GreenPrimary.copy(alpha = 0.55f),
            ),
        )
    }
}

@Composable
private fun wakeModeTitle(mode: WakeMainAlarmMode): String = when (mode) {
    WakeMainAlarmMode.PRAYER_RELATIVE -> stringResource(R.string.wake_editor_mode_relative)
    WakeMainAlarmMode.FIXED_TIME -> stringResource(R.string.wake_editor_mode_fixed)
    WakeMainAlarmMode.FROM_NOW -> stringResource(R.string.wake_editor_mode_from_now)
}

@Composable
private fun wakeModeHint(mode: WakeMainAlarmMode): String = when (mode) {
    WakeMainAlarmMode.PRAYER_RELATIVE -> stringResource(R.string.wake_editor_mode_relative_hint)
    WakeMainAlarmMode.FIXED_TIME -> stringResource(R.string.wake_editor_mode_fixed_hint)
    WakeMainAlarmMode.FROM_NOW -> stringResource(R.string.wake_editor_mode_from_now_hint)
}

private fun formatWakeScheduleSummary(
    context: android.content.Context,
    mode: WakeMainAlarmMode,
    selectedPrayer: Prayer,
    offsetDirection: OffsetDirection,
    offsetText: String,
    fixedHour: Int,
    fixedMinute: Int,
    scheduledDays: Set<WakeScheduleDay>,
    repeatMode: WakeRepeatMode,
    fromNowHoursText: String,
    fromNowMinutesText: String,
): String {
    val normalizedDays = scheduledDays.normalizedWakeScheduleDays()
    val selectedDaysSummary = formatWakeScheduleDaysSummary(context, normalizedDays)
    val allDaysSelected = isEveryWakeScheduleDay(normalizedDays)

    return when (mode) {
        WakeMainAlarmMode.PRAYER_RELATIVE -> {
            val prayerName = prayerDisplayName(context, selectedPrayer)
            val offsetMinutes = offsetText.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val baseSummary = if (offsetMinutes == 0) {
                context.getString(R.string.wake_editor_schedule_summary_relative_at, prayerName)
            } else {
                context.getString(
                    if (offsetDirection == OffsetDirection.BEFORE) {
                        R.string.wake_editor_schedule_summary_relative_before
                    } else {
                        R.string.wake_editor_schedule_summary_relative_after
                    },
                    prayerName,
                    formatArabicMinutes(offsetMinutes),
                )
            }

            if (repeatMode == WakeRepeatMode.ONCE) {
                context.getString(R.string.wake_editor_schedule_summary_once, baseSummary)
            } else if (allDaysSelected) {
                baseSummary
            } else {
                context.getString(R.string.wake_editor_schedule_summary_with_days, baseSummary, selectedDaysSummary)
            }
        }

        WakeMainAlarmMode.FIXED_TIME -> if (repeatMode == WakeRepeatMode.ONCE) {
            context.getString(
                R.string.wake_editor_schedule_summary_fixed_once,
                formatWakeEditorTime(fixedHour, fixedMinute),
            )
        } else if (allDaysSelected) {
            context.getString(
                R.string.wake_editor_schedule_summary_fixed,
                formatWakeEditorTime(fixedHour, fixedMinute),
            )
        } else {
            context.getString(
                R.string.wake_editor_schedule_summary_fixed_selected_days,
                formatWakeEditorTime(fixedHour, fixedMinute),
                selectedDaysSummary,
            )
        }

        WakeMainAlarmMode.FROM_NOW -> context.getString(
            R.string.wake_editor_schedule_summary_from_now,
            formatWakeScheduleDuration(
                context = context,
                hours = fromNowHoursText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                minutes = fromNowMinutesText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            ),
        )
    }
}

private fun formatWakeScheduleDuration(
    context: android.content.Context,
    hours: Int,
    minutes: Int,
): String = when {
    hours > 0 && minutes > 0 -> context.getString(
        R.string.wake_editor_duration_hours_minutes,
        hours,
        formatArabicMinutes(minutes),
    )

    hours > 0 -> context.getString(R.string.wake_editor_duration_hours, hours)
    else -> formatArabicMinutes(minutes)
}

@Composable
private fun WakeRepetitionSelector(
    repeatMode: WakeRepeatMode,
    onRepeatModeChange: (WakeRepeatMode) -> Unit,
    selectedDays: Set<WakeScheduleDay>,
    onSelectedDaysChange: (Set<WakeScheduleDay>) -> Unit,
) {
    val normalizedDays = selectedDays.normalizedWakeScheduleDays()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.wake_editor_repeat_days_title),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = PrayerNameColor,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WakeRepetitionChip(
                text = stringResource(R.string.wake_schedule_once),
                selected = repeatMode == WakeRepeatMode.ONCE,
                onClick = { onRepeatModeChange(WakeRepeatMode.ONCE) },
            )
            WakeRepetitionChip(
                text = stringResource(R.string.wake_schedule_days_every_day),
                selected = repeatMode == WakeRepeatMode.RECURRING && isEveryWakeScheduleDay(normalizedDays),
                onClick = {
                    onRepeatModeChange(WakeRepeatMode.RECURRING)
                    onSelectedDaysChange(com.tunisianprayertimes.ALL_WAKE_SCHEDULE_DAYS)
                },
            )
            com.tunisianprayertimes.ALL_WAKE_SCHEDULE_DAYS.forEach { day ->
                val selected = repeatMode != WakeRepeatMode.ONCE && day in normalizedDays
                WakeScheduleDayChip(
                    day = day,
                    selected = selected,
                    onClick = {
                        onRepeatModeChange(WakeRepeatMode.RECURRING)
                        val nextDays = if (repeatMode == WakeRepeatMode.ONCE) {
                            setOf(day)
                        } else if (selected) {
                            if (normalizedDays.size == 1) normalizedDays else normalizedDays - day
                        } else {
                            normalizedDays + day
                        }
                        onSelectedDaysChange(nextDays.normalizedWakeScheduleDays())
                    },
                )
            }
        }
    }
}

@Composable
private fun WakeRepetitionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    Surface(
        modifier = Modifier
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) GreenPrimary else Color.White,
        border = BorderStroke(
            1.dp,
            if (selected) GreenPrimary else Gold.copy(alpha = 0.28f),
        ),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else TextDark,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WakeScheduleDayChip(
    day: WakeScheduleDay,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    Surface(
        modifier = Modifier
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) GreenPrimary else Color.White,
        border = BorderStroke(
            1.dp,
            if (selected) GreenPrimary else Gold.copy(alpha = 0.28f),
        ),
    ) {
        Text(
            text = wakeScheduleDayLabel(day),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else TextDark,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WakePrayerRelativeModeControls(
    selectedPrayer: Prayer,
    onPrayerSelected: (Prayer) -> Unit,
    offsetDirection: OffsetDirection,
    onOffsetDirectionChange: (OffsetDirection) -> Unit,
    offsetText: String,
    onOffsetTextChange: (String) -> Unit,
) {
    Text(
        text = stringResource(R.string.wake_editor_anchor_prayer_title),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = PrayerNameColor,
    )
    WakeAnchorPrayerSelector(
        selectedPrayer = selectedPrayer,
        onPrayerSelected = onPrayerSelected,
    )

    Text(
        text = stringResource(R.string.wake_editor_relative_title),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = PrayerNameColor,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WakeChoiceButton(
            selected = offsetDirection == OffsetDirection.BEFORE,
            onClick = { onOffsetDirectionChange(OffsetDirection.BEFORE) },
            text = stringResource(R.string.wake_editor_subalarm_before),
            modifier = Modifier
                .width(82.dp)
                .height(48.dp),
            compact = true,
        )
        Spacer(modifier = Modifier.width(8.dp))
        WakeRelativeMinutesInput(
            value = offsetText,
            onValueChange = onOffsetTextChange,
            modifier = Modifier.width(118.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        WakeChoiceButton(
            selected = offsetDirection == OffsetDirection.AFTER,
            onClick = { onOffsetDirectionChange(OffsetDirection.AFTER) },
            text = stringResource(R.string.wake_editor_subalarm_after),
            modifier = Modifier
                .width(82.dp)
                .height(48.dp),
            compact = true,
        )
    }
}

@Composable
private fun WakeRelativeMinutesInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(GoldLight.copy(alpha = 0.12f))
            .border(1.dp, Gold.copy(alpha = 0.24f), shape)
            .padding(horizontal = 10.dp),
        textStyle = LocalTextStyle.current.copy(
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextDark,
            textAlign = TextAlign.Center,
            textDirection = TextDirection.Ltr,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        decorationBox = { innerTextField ->
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.width(42.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        innerTextField()
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = stringResource(R.string.wake_editor_relative_unit_minute),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMuted,
                        maxLines = 1,
                    )
                }
            }
        },
    )
}

@Composable
private fun WakeFixedTimeModeControls(
    activity: AppCompatActivity,
    timePickerTag: String,
    fixedHour: Int,
    fixedMinute: Int,
    onTimePicked: (hour: Int, minute: Int) -> Unit,
) {
    val context = LocalContext.current
    Text(
        text = stringResource(R.string.wake_editor_fixed_time_title),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = PrayerNameColor,
    )
    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(fixedHour)
                .setMinute(fixedMinute)
                .setTitleText(context.getString(R.string.wake_editor_pick_time))
                .build()
            picker.addOnPositiveButtonClickListener {
                onTimePicked(picker.hour, picker.minute)
            }
            picker.show(activity.supportFragmentManager, timePickerTag)
        },
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.34f)),
    ) {
        Text(
            text = formatWakeEditorTime(fixedHour, fixedMinute),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = GreenPrimaryDark,
        )
    }
}

@Composable
private fun WakeFromNowModeControls(
    hoursText: String,
    minutesText: String,
    onDurationMinutesChange: (Int) -> Unit,
    silenceUntilAlarm: Boolean,
    onSilenceUntilAlarmChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val totalMinutes = fromNowTotalMinutes(hoursText, minutesText).coerceAtLeast(1)
    val quickAdds = listOf(1, 5, 10, 15, 30, 60)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = formatWakeScheduleDuration(
                context = context,
                hours = totalMinutes / 60,
                minutes = totalMinutes % 60,
            ),
            modifier = Modifier.fillMaxWidth(),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = GreenPrimaryDark,
            textAlign = TextAlign.Center,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            quickAdds.chunked(3).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rowItems.forEach { minutesToAdd ->
                        WakeDurationAdjustChip(
                            text = formatWakeDurationShort(minutesToAdd),
                            canSubtract = totalMinutes > 1,
                            onSubtract = {
                                onDurationMinutesChange((totalMinutes - minutesToAdd).coerceAtLeast(1))
                            },
                            onAdd = { onDurationMinutesChange(totalMinutes + minutesToAdd) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    WakeSwitchSettingRow(
        title = stringResource(R.string.wake_editor_silence_toggle),
        checked = silenceUntilAlarm,
        onCheckedChange = onSilenceUntilAlarmChange,
    )
}

@Composable
private fun WakeDurationAdjustChip(
    text: String,
    canSubtract: Boolean,
    onSubtract: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp),
        shape = shape,
        color = GreenPrimary.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.20f)),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 7.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WakeDurationAdjustZone(
                    text = "−",
                    enabled = canSubtract,
                    onClick = onSubtract,
                )
                Text(
                    text = text,
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                    textAlign = TextAlign.Center,
                    style = LocalTextStyle.current.copy(textDirection = TextDirection.Rtl),
                    maxLines = 1,
                )
                WakeDurationAdjustZone(
                    text = "+",
                    enabled = true,
                    onClick = onAdd,
                )
            }
        }
    }
}

@Composable
private fun WakeDurationAdjustZone(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(30.dp)
            .height(32.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) GreenPrimaryDark else TextMuted.copy(alpha = 0.38f),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private fun fromNowTotalMinutes(hoursText: String, minutesText: String): Int {
    val hours = hoursText.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val minutes = minutesText.toIntOrNull()?.coerceIn(0, 59) ?: 0
    return (hours * 60) + minutes
}

private fun formatWakeDurationShort(totalMinutes: Int): String =
    if (totalMinutes >= 60 && totalMinutes % 60 == 0) {
        "${totalMinutes / 60} س"
    } else {
        "$totalMinutes د"
    }

@Composable
private fun WakeChoiceButton(
    selected: Boolean,
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = if (compact) 34.dp else 38.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            when {
                !enabled -> TextMuted.copy(alpha = 0.24f)
                selected -> GreenPrimary
                else -> GreenPrimary.copy(alpha = 0.34f)
            },
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) GreenPrimary else Color.White,
            contentColor = if (selected) Color.White else TextDark,
            disabledContainerColor = GoldLight.copy(alpha = 0.12f),
            disabledContentColor = TextMuted.copy(alpha = 0.62f),
        ),
    ) {
        Text(
            text = text,
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = if (selected && enabled) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = if (compact) 1 else Int.MAX_VALUE,
        )
    }
}

@Composable
private fun WakeEditorPreviewCard(
    enabled: Boolean,
    preview: WakePreview?,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = GreenPrimaryDark),
        border = BorderStroke(1.dp, GreenPrimaryDark),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when {
                !enabled -> Text(
                    text = stringResource(R.string.wake_editor_preview_disabled),
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.78f),
                )

                preview == null -> Text(
                    text = stringResource(R.string.wake_editor_preview_unavailable),
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.78f),
                )

                else -> {
                    Text(
                        text = preview.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = preview.detail,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.82f),
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun WakeEditorWarningCard(
    warning: WakeValidationWarning,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = SilenceRed.copy(alpha = 0.07f)),
        border = BorderStroke(1.dp, SilenceRed.copy(alpha = 0.18f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = warning.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = SilenceRed,
            )
            Text(
                text = warning.detail,
                fontSize = 12.sp,
                color = SilenceRed,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun WakeRecurringSilenceConflictDialog(
    conflict: WakeSilenceConflictSummary,
    editorState: WakeSilenceConflictResolverState?,
    editorContent: @Composable (WakeSilenceConflictSummary) -> Unit,
    onAdjustSilenceWindow: () -> Unit,
    onRingDuringSilence: () -> Unit,
    onDone: () -> Unit,
    onBackToChoices: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prayerName = prayerDisplayName(context, conflict.silencePrayer)
    val titleRes = when {
        editorState?.resolved == true -> R.string.wake_editor_silence_inline_resolved_title
        editorState != null -> R.string.wake_editor_silence_inline_title
        else -> R.string.wake_editor_conflict_choice_title
    }
    val detailText = when {
        editorState?.resolved == true -> stringResource(
            R.string.wake_editor_silence_inline_resolved_detail,
            formatWakeTimelineTime(conflict.triggerAtMillis),
        )
        editorState != null -> stringResource(
            R.string.wake_editor_silence_inline_conflict_detail,
            formatWakeTimelineTime(conflict.silenceStartAtMillis),
            formatWakeTimelineTime(conflict.silenceEndAtMillis),
            formatWakeTimelineTime(conflict.triggerAtMillis),
        )
        else -> stringResource(
            R.string.wake_editor_conflict_choice_detail,
            formatWakeTimelineTime(conflict.triggerAtMillis),
            prayerName,
            formatWakeTimelineTime(conflict.silenceStartAtMillis),
            formatWakeTimelineTime(conflict.silenceEndAtMillis),
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(titleRes),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrayerNameColor,
                )
                Text(
                    text = detailText,
                    fontSize = 13.sp,
                    color = TextDark,
                    lineHeight = 19.sp,
                )

                if (editorState == null) {
                    Text(
                        text = stringResource(R.string.wake_editor_conflict_choice_message),
                        fontSize = 12.sp,
                        color = TextMuted,
                        lineHeight = 18.sp,
                    )

                    Button(
                        onClick = onAdjustSilenceWindow,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.wake_editor_conflict_choice_adjust, prayerName),
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    OutlinedButton(
                        onClick = onRingDuringSilence,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SilenceRed.copy(alpha = 0.42f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SilenceRed),
                    ) {
                        Text(
                            text = stringResource(R.string.wake_editor_conflict_choice_allow),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    editorContent(editorState.conflict)

                    if (editorState.resolved) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = onDone,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                            ) {
                                Text(
                                    text = stringResource(R.string.wake_editor_conflict_choice_done),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.34f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = GreenPrimaryDark),
                            ) {
                                Text(
                                    text = stringResource(R.string.wake_editor_cancel),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = onBackToChoices,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.34f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = GreenPrimaryDark),
                            ) {
                                Text(
                                    text = stringResource(R.string.wake_editor_conflict_choice_back),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            TextButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(text = stringResource(R.string.wake_editor_cancel))
                            }
                        }
                    }
                }

                if (editorState == null) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.wake_editor_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun WakeSwitchSettingRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 17.sp,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun WakeDisclosureRow(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.28f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 17.sp,
                )
            }
            Icon(
                painter = painterResource(
                    if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                ),
                contentDescription = null,
                tint = GreenPrimaryDark,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(22.dp),
            )
        }
    }
}

@Composable
private fun WakeAnchorPrayerSelector(
    selectedPrayer: Prayer,
    onPrayerSelected: (Prayer) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember(selectedPrayer) { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = prayerDisplayName(context, selectedPrayer))
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            WAKE_SUPPORTED_PRAYERS.forEach { prayer ->
                DropdownMenuItem(
                    text = { Text(text = prayerDisplayName(context, prayer)) },
                    onClick = {
                        expanded = false
                        onPrayerSelected(prayer)
                    },
                )
            }
        }
    }
}

@Composable
private fun WakeSubAlarmEditorCard(
    index: Int,
    subAlarm: PrayerWakeSubAlarm,
    onChange: (PrayerWakeSubAlarm) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(10.dp)
    var soundExpanded by rememberSaveable(subAlarm.id) { mutableStateOf(false) }
    val offsetText = stringResource(
        if (subAlarm.direction == OffsetDirection.BEFORE) {
            R.string.wake_editor_subalarm_offset_before_value
        } else {
            R.string.wake_editor_subalarm_offset_after_value
        },
        formatArabicMinutes(subAlarm.minutesOffset),
    )
    val soundSummary = if (subAlarm.playback.vibrationOnly) {
        stringResource(R.string.wake_editor_vibration_only_title)
    } else {
        WakeRingtoneCatalog.titleFor(context, subAlarm.playback.ringtone, subAlarm.playback.customRingtoneUri)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.78f))
            .border(BorderStroke(1.dp, Gold.copy(alpha = 0.22f)), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.wake_editor_subalarm_title, index + 1),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrayerNameColor,
                )
                Text(
                    text = offsetText,
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 17.sp,
                )
            }

            OutlinedButton(
                onClick = onRemove,
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, SilenceRed.copy(alpha = 0.42f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = SilenceRed.copy(alpha = 0.06f),
                    contentColor = SilenceRed,
                ),
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.wake_editor_subalarm_remove),
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WakeChoiceButton(
                selected = subAlarm.direction == OffsetDirection.BEFORE,
                onClick = {
                    onChange(subAlarm.copy(direction = OffsetDirection.BEFORE))
                },
                text = stringResource(R.string.wake_editor_subalarm_before_main),
                modifier = Modifier.weight(1f),
                compact = true,
            )
            WakeChoiceButton(
                selected = subAlarm.direction == OffsetDirection.AFTER,
                onClick = {
                    onChange(subAlarm.copy(direction = OffsetDirection.AFTER))
                },
                text = stringResource(R.string.wake_editor_subalarm_after_main),
                modifier = Modifier.weight(1f),
                compact = true,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    onChange(subAlarm.copy(minutesOffset = maxOf(1, subAlarm.minutesOffset - 1)))
                },
                modifier = Modifier.heightIn(min = 38.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_remove),
                    contentDescription = stringResource(R.string.wake_editor_subalarm_decrease),
                    modifier = Modifier.size(18.dp),
                )
            }

            Text(
                text = stringResource(
                    R.string.wake_editor_subalarm_minutes_value,
                    formatArabicMinutes(subAlarm.minutesOffset),
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(GoldLight.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextDark,
            )

            OutlinedButton(
                onClick = {
                    onChange(subAlarm.copy(minutesOffset = subAlarm.minutesOffset + 1))
                },
                modifier = Modifier.heightIn(min = 38.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.wake_editor_subalarm_increase),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        HorizontalDivider(color = Gold.copy(alpha = 0.16f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.wake_editor_subalarm_sound_summary, soundSummary),
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                color = TextMuted,
                lineHeight = 17.sp,
            )
            TextButton(
                onClick = { soundExpanded = !soundExpanded },
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = stringResource(
                        if (soundExpanded) {
                            R.string.wake_editor_subalarm_hide_customization
                        } else {
                            R.string.wake_editor_subalarm_customize
                        },
                    ),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        AnimatedVisibility(visible = soundExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WakeSoundControls(
                    ringtoneLabel = stringResource(R.string.wake_editor_subalarm_ringtone_label),
                    playback = subAlarm.playback,
                    onPlaybackChange = { updated -> onChange(subAlarm.copy(playback = updated)) },
                )
            }
        }
    }
}

private data class WakeSubAlarmTimelineEntry(
    val sortAtMillis: Long,
    val stableOrder: Int,
    val title: String,
    val timing: String,
    val isMainAlarm: Boolean,
)

@Composable
private fun WakeSubAlarmTimeline(
    subAlarms: List<PrayerWakeSubAlarm>,
    entries: List<WakeSubAlarmTimelineEntry>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.92f))
            .border(BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.32f)), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.wake_editor_subalarms_timeline_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor,
            )
        }
        val shouldScroll = subAlarms.size > 3
        val timelineScrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (shouldScroll) {
                        Modifier.horizontalScroll(timelineScrollState)
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            entries.forEachIndexed { index, entry ->
                WakeSubAlarmTimelineNode(
                    entry = entry,
                    isFirst = index == 0,
                    isLast = index == entries.lastIndex,
                    modifier = if (shouldScroll) {
                        Modifier.width(92.dp)
                    } else {
                        Modifier.weight(1f)
                    },
                )
            }
        }
    }
}

@Composable
private fun WakeSubAlarmTimelineNode(
    entry: WakeSubAlarmTimelineEntry,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
) {
    val markerColor = if (entry.isMainAlarm) GreenPrimary else Gold
    val markerSize = if (entry.isMainAlarm) 16.dp else 12.dp
    val trackColor = Gold.copy(alpha = 0.36f)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.title,
                fontSize = 11.sp,
                fontWeight = if (entry.isMainAlarm) FontWeight.Bold else FontWeight.SemiBold,
                color = if (entry.isMainAlarm) GreenPrimaryDark else TextDark,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (isFirst) Color.Transparent else trackColor),
                )
                Spacer(modifier = Modifier.width(markerSize))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (isLast) Color.Transparent else trackColor),
                )
            }
            Box(
                modifier = Modifier
                    .size(markerSize)
                    .clip(RoundedCornerShape(50))
                    .background(markerColor)
                    .border(
                        BorderStroke(2.dp, Color.White),
                        RoundedCornerShape(50),
                    ),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.timing,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun WakePlaybackControls(
    title: String,
    subtitle: String,
    ringtoneLabel: String,
    playback: WakePlaybackOptions,
    onPlaybackChange: (WakePlaybackOptions) -> Unit,
    showAwakeCheck: Boolean = true,
    silenceUntilAlarm: Boolean = false,
    onSilenceUntilAlarmChange: ((Boolean) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor,
            )

            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextMuted,
                lineHeight = 17.sp,
            )
        }

        WakeSoundControls(
            ringtoneLabel = ringtoneLabel,
            playback = playback,
            onPlaybackChange = onPlaybackChange,
        )

        WakeWakeCheckControls(
            playback = playback,
            onPlaybackChange = onPlaybackChange,
            showAwakeCheck = showAwakeCheck,
        )

        if (onSilenceUntilAlarmChange != null) {
            WakeSwitchSettingRow(
                title = stringResource(R.string.wake_editor_silence_toggle),
                checked = silenceUntilAlarm,
                onCheckedChange = onSilenceUntilAlarmChange,
            )
        }
    }
}

private fun shouldExpandWakeBehavior(playback: WakePlaybackOptions): Boolean =
    playback.vibrationOnly ||
        playback.wakeUpCheckEnabled ||
        !playback.progressiveVolume ||
        !playback.awakeCheckEnabled ||
        playback.ringtone != RingtonePreset.ADHAN_MADINAH_MARWAN_QASSAS ||
        !playback.customRingtoneUri.isNullOrBlank()

private fun formatWakeBehaviorSummary(
    context: android.content.Context,
    playback: WakePlaybackOptions,
): String {
    val soundSummary = when {
        playback.vibrationOnly -> context.getString(R.string.wake_editor_vibration_only_title)
        playback.progressiveVolume -> context.getString(R.string.wake_editor_progressive_volume_title)
        else -> context.getString(R.string.wake_editor_sound_direct_summary)
    }
    val stopChallengeSummary = if (playback.wakeUpCheckEnabled) {
        context.getString(R.string.wake_editor_wake_up_check_title)
    } else {
        context.getString(R.string.wake_editor_stop_challenge_off_summary)
    }
    val awakeFollowUpSummary = if (playback.awakeCheckEnabled) {
        context.getString(R.string.wake_editor_awake_check_title)
    } else {
        context.getString(R.string.wake_editor_awake_check_off_summary)
    }
    return context.getString(
        R.string.wake_editor_behavior_summary,
        soundSummary,
        stopChallengeSummary,
        awakeFollowUpSummary,
    )
}

@Composable
private fun WakeSoundControls(
    ringtoneLabel: String,
    playback: WakePlaybackOptions,
    onPlaybackChange: (WakePlaybackOptions) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(visible = !playback.vibrationOnly) {
            WakeRingtoneSelector(
                label = ringtoneLabel,
                selected = playback.ringtone,
                customRingtoneUri = playback.customRingtoneUri,
                onSelected = { preset ->
                    onPlaybackChange(playback.copy(ringtone = preset))
                },
                onCustomSelected = { uri ->
                    onPlaybackChange(playback.copy(ringtone = RingtonePreset.CUSTOM, customRingtoneUri = uri))
                },
            )
        }

        WakeSwitchSettingRow(
            title = stringResource(R.string.wake_editor_vibration_only_title),
            checked = playback.vibrationOnly,
            onCheckedChange = { enabled ->
                onPlaybackChange(playback.copy(vibrationOnly = enabled))
            },
        )

        if (!playback.vibrationOnly) {
            WakeSwitchSettingRow(
                title = stringResource(R.string.wake_editor_progressive_volume_title),
                checked = playback.progressiveVolume,
                onCheckedChange = { enabled ->
                    onPlaybackChange(playback.copy(progressiveVolume = enabled))
                },
            )
        }
    }
}

@Composable
private fun WakeWakeCheckControls(
    playback: WakePlaybackOptions,
    onPlaybackChange: (WakePlaybackOptions) -> Unit,
    showAwakeCheck: Boolean = true,
) {
    val context = LocalContext.current
    val gyroscopeMazeSupported = remember(context) { hasGyroscopeMazeTiltSensor(context) }
    var previewStepIndex by remember { mutableStateOf<Int?>(null) }
    var wakeCheckExpanded by rememberSaveable { mutableStateOf(false) }
    val previewSteps = playback.wakeUpCheckSteps.ifEmpty {
        listOf(WakeUpCheckStep(playback.wakeUpCheckType, playback.mathDifficulty))
    }
    val wakeCheckSummary = if (!playback.wakeUpCheckEnabled) {
        null
    } else if (previewSteps.size == 1) {
        stringResource(
            R.string.wake_editor_wake_up_check_single_summary,
            wakeCheckTypeLabel(previewSteps.first().type),
            wakeCheckDifficultyLabel(previewSteps.first().difficulty),
        )
    } else {
        stringResource(R.string.wake_editor_wake_up_check_steps_summary, previewSteps.size)
    }

    if (previewStepIndex != null && previewStepIndex!! < previewSteps.size) {
        val step = previewSteps[previewStepIndex!!]
        WakeUpCheckPreviewDialog(
            checkType = step.type,
            difficulty = step.difficulty,
            onDismiss = { previewStepIndex = null },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WakeSwitchSettingRow(
            title = stringResource(R.string.wake_editor_wake_up_check_title),
            subtitle = wakeCheckSummary,
            checked = playback.wakeUpCheckEnabled,
            onCheckedChange = { enabled ->
                onPlaybackChange(playback.copy(wakeUpCheckEnabled = enabled))
                if (enabled) wakeCheckExpanded = true
            },
        )

        if (playback.wakeUpCheckEnabled) {
            WakeDisclosureRow(
                title = stringResource(R.string.wake_editor_wake_up_check_edit),
                subtitle = requireNotNull(wakeCheckSummary),
                expanded = wakeCheckExpanded,
                onClick = { wakeCheckExpanded = !wakeCheckExpanded },
            )
        }

        AnimatedVisibility(visible = playback.wakeUpCheckEnabled && wakeCheckExpanded) {
            val steps = playback.wakeUpCheckSteps.ifEmpty {
                listOf(WakeUpCheckStep(playback.wakeUpCheckType, playback.mathDifficulty))
            }

            Column(
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                steps.forEachIndexed { index, step ->
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.outlinedCardColors(containerColor = GoldLight.copy(alpha = 0.08f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.wake_editor_check_step_label, index + 1),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark,
                                )
                                if (steps.size > 1) {
                                    OutlinedButton(
                                        onClick = {
                                            val updated = steps.toMutableList().apply { removeAt(index) }
                                            onPlaybackChange(playback.copy(wakeUpCheckSteps = updated))
                                        },
                                        modifier = Modifier.size(32.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_close),
                                            contentDescription = stringResource(R.string.wake_editor_check_remove_step),
                                            modifier = Modifier.size(15.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = stringResource(R.string.wake_editor_check_type_title),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                WakeUpCheckType.entries.forEach { type ->
                                    val typeEnabled = type != WakeUpCheckType.GYROSCOPE_MAZE || gyroscopeMazeSupported
                                    WakeChoiceButton(
                                        selected = step.type == type,
                                        onClick = {
                                            val updated = steps.toMutableList().apply {
                                                set(index, step.copy(type = type))
                                            }
                                            onPlaybackChange(playback.copy(wakeUpCheckSteps = updated))
                                        },
                                        text = wakeCheckTypeShortLabel(type),
                                        modifier = Modifier.weight(1f),
                                        compact = true,
                                        enabled = typeEnabled,
                                    )
                                }
                            }
                            if (!gyroscopeMazeSupported) {
                                Text(
                                    text = stringResource(R.string.wake_editor_gyroscope_maze_not_supported),
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    lineHeight = 15.sp,
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                            Text(
                                text = stringResource(R.string.wake_editor_math_difficulty_title),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                MathDifficulty.entries.forEach { diff ->
                                    WakeChoiceButton(
                                        selected = step.difficulty == diff,
                                        onClick = {
                                            val updated = steps.toMutableList().apply {
                                                set(index, step.copy(difficulty = diff))
                                            }
                                            onPlaybackChange(playback.copy(wakeUpCheckSteps = updated))
                                        },
                                        text = when (diff) {
                                            MathDifficulty.EASY -> stringResource(R.string.wake_editor_math_difficulty_easy)
                                            MathDifficulty.INTERMEDIATE -> stringResource(R.string.wake_editor_math_difficulty_intermediate)
                                            MathDifficulty.HARD -> stringResource(R.string.wake_editor_math_difficulty_hard)
                                        },
                                        modifier = Modifier.weight(1f),
                                        compact = true,
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { previewStepIndex = index },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(stringResource(R.string.wake_editor_check_preview_button), fontSize = 11.sp)
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        val updated = steps + WakeUpCheckStep()
                        onPlaybackChange(playback.copy(wakeUpCheckSteps = updated))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(stringResource(R.string.wake_editor_check_add_step))
                }
            }
        }

        if (showAwakeCheck) {
            WakeSwitchSettingRow(
                title = stringResource(R.string.wake_editor_awake_check_title),
                subtitle = stringResource(R.string.wake_editor_awake_check_subtitle_compact),
                checked = playback.awakeCheckEnabled,
                onCheckedChange = { enabled ->
                    onPlaybackChange(playback.copy(awakeCheckEnabled = enabled))
                },
            )
        }
    }
}

@Composable
private fun wakeCheckTypeLabel(type: WakeUpCheckType): String = when (type) {
    WakeUpCheckType.MATH -> stringResource(R.string.wake_editor_check_type_math)
    WakeUpCheckType.WHACK_A_MOLE -> stringResource(R.string.wake_editor_check_type_whack_a_mole)
    WakeUpCheckType.GYROSCOPE_MAZE -> stringResource(R.string.wake_editor_check_type_gyroscope_maze)
}

@Composable
private fun wakeCheckTypeShortLabel(type: WakeUpCheckType): String = when (type) {
    WakeUpCheckType.MATH -> stringResource(R.string.wake_editor_check_type_math_short)
    WakeUpCheckType.WHACK_A_MOLE -> stringResource(R.string.wake_editor_check_type_whack_short)
    WakeUpCheckType.GYROSCOPE_MAZE -> stringResource(R.string.wake_editor_check_type_maze_short)
}

@Composable
private fun wakeCheckDifficultyLabel(difficulty: MathDifficulty): String = when (difficulty) {
    MathDifficulty.EASY -> stringResource(R.string.wake_editor_math_difficulty_easy)
    MathDifficulty.INTERMEDIATE -> stringResource(R.string.wake_editor_math_difficulty_intermediate)
    MathDifficulty.HARD -> stringResource(R.string.wake_editor_math_difficulty_hard)
}

@Composable
private fun WakeUpCheckPreviewDialog(
    checkType: WakeUpCheckType,
    difficulty: MathDifficulty,
    onDismiss: () -> Unit,
) {
    val challenge = remember(checkType, difficulty) {
        if (checkType == WakeUpCheckType.MATH || checkType == WakeUpCheckType.GYROSCOPE_MAZE) {
            wakeUpCheckChallengeFor("preview", System.currentTimeMillis(), difficulty)
        } else null
    }
    val gyroscopeMazeSensorState = if (checkType == WakeUpCheckType.GYROSCOPE_MAZE) {
        rememberGyroscopeMazeSensorState(probeKey = "preview:$difficulty")
    } else {
        null
    }
    val killTarget = remember(difficulty) {
        when (difficulty) {
            MathDifficulty.EASY -> 5
            MathDifficulty.INTERMEDIATE -> 10
            MathDifficulty.HARD -> 15
        }
    }
    val gradient = Brush.verticalGradient(listOf(GreenPrimaryDark, GreenPrimary, BgCream))

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(gradient)
                    .padding(24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = stringResource(R.string.wake_editor_check_preview_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )

                    if (checkType == WakeUpCheckType.MATH && challenge != null) {
                        WakeUpCheckMathPreview(challenge = challenge)
                    } else if (checkType == WakeUpCheckType.WHACK_A_MOLE) {
                        WhackAMoleGame(
                            killTarget = killTarget,
                            difficulty = difficulty,
                            onCompleted = { /* no-op in preview */ },
                        )
                    } else if (checkType == WakeUpCheckType.GYROSCOPE_MAZE) {
                        when (gyroscopeMazeSensorState) {
                            GyroscopeMazeSensorState.READY -> {
                                GyroscopeMazeGame(
                                    difficulty = difficulty,
                                    onCompleted = { /* no-op in preview */ },
                                )
                            }
                            GyroscopeMazeSensorState.UNAVAILABLE -> {
                                Text(
                                    text = stringResource(R.string.wake_alarm_gyroscope_maze_unavailable_fallback),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                if (challenge != null) {
                                    WakeUpCheckMathPreview(challenge = challenge)
                                }
                            }
                            GyroscopeMazeSensorState.CHECKING,
                            null -> {
                                Text(
                                    text = stringResource(R.string.wake_alarm_gyroscope_maze_checking),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = GreenPrimaryDark,
                        ),
                    ) {
                        Text(stringResource(R.string.wake_editor_check_preview_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun WakeUpCheckMathPreview(challenge: com.tunisianprayertimes.wake.WakeUpCheckChallenge) {
    var answer by rememberSaveable { mutableStateOf("") }
    val passed = challenge.matches(answer)

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
                val normalized = normalizeDigits(input)
                answer = normalized.filterIndexed { index, c ->
                    c in '0'..'9' || (c == '-' && index == 0)
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
            text = if (passed) {
                stringResource(R.string.wake_alarm_wake_up_check_complete)
            } else {
                stringResource(R.string.wake_alarm_wake_up_check_incomplete)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun WakeRingtoneSelector(
    label: String,
    selected: RingtonePreset,
    customRingtoneUri: String?,
    onSelected: (RingtonePreset) -> Unit,
    onCustomSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val previewPlayer = remember(context) { WakeRingtonePreviewPlayer(context) }
    val canPreview = remember(selected, customRingtoneUri) {
        selected == RingtonePreset.CUSTOM && !customRingtoneUri.isNullOrBlank() ||
            WakeRingtoneCatalog.rawResIdFor(selected) != null ||
            WakeRingtoneCatalog.systemTypeFor(selected) != null
    }

    DisposableEffect(selected, customRingtoneUri) {
        previewPlayer.stop()
        isPlaying = false
        onDispose { }
    }

    DisposableEffect(previewPlayer) {
        onDispose {
            previewPlayer.release()
        }
    }

    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (uri != null) {
                onCustomSelected(uri.toString())
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = PrayerNameColor,
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = previewButtonInset(canPreview)),
                ) {
                    Text(
                        text = WakeRingtoneCatalog.titleFor(context, selected, customRingtoneUri),
                        color = TextDark,
                    )
                    Text(
                        text = WakeRingtoneCatalog.summaryFor(context, selected),
                        fontSize = 11.sp,
                        color = TextMuted,
                    )
                }
            }

            TextButton(
                onClick = {
                    if (isPlaying) {
                        previewPlayer.stop()
                        isPlaying = false
                    } else {
                        isPlaying = previewPlayer.play(selected, customRingtoneUri)
                    }
                },
                enabled = canPreview,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = if (isPlaying) "■" else "▶",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (canPreview) GreenPrimaryDark else TextMuted,
                    textAlign = TextAlign.Center,
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                WakeRingtoneCatalog.selectableChoices.forEach { choice ->
                    DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(text = stringResource(choice.titleResId))
                                Text(
                                    text = stringResource(choice.summaryResId),
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelected(choice.preset)
                        },
                    )
                }

                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text = stringResource(R.string.wake_ringtone_custom_title))
                            Text(
                                text = stringResource(R.string.wake_ringtone_custom_summary),
                                fontSize = 11.sp,
                                color = TextMuted,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        ringtonePickerLauncher.launch(
                            Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                if (selected == RingtonePreset.CUSTOM && customRingtoneUri != null) {
                                    putExtra(
                                        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                        Uri.parse(customRingtoneUri),
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }
    }
}

private fun previewButtonInset(canPreview: Boolean): Dp =
    if (canPreview) 44.dp else 0.dp

private data class WakePreview(
    val title: String,
    val detail: String,
    val warning: WakeValidationWarning? = null,
)

private data class WakeValidationWarning(
    val title: String,
    val detail: String,
    val conflict: WakeSilenceConflict? = null,
)

private data class WakeSilenceConflict(
    val detail: String,
    val silencePrayer: Prayer,
    val silenceStartAtMillis: Long,
    val silenceEndAtMillis: Long,
    val triggerAtMillis: Long,
)

private fun WakeSilenceConflict.toSummary(): WakeSilenceConflictSummary = WakeSilenceConflictSummary(
    detail = detail,
    silencePrayer = silencePrayer,
    silenceStartAtMillis = silenceStartAtMillis,
    silenceEndAtMillis = silenceEndAtMillis,
    triggerAtMillis = triggerAtMillis,
)

private fun computeWakePreview(
    context: android.content.Context,
    delegationId: Int,
    config: PrayerWakeConfig,
): WakePreview? {
    if (!config.enabled) {
        return null
    }

    val now = Calendar.getInstance()
    val jomoaaHour = PrefsManager.getJomoaaTimeHour(context)
    val jomoaaMinute = PrefsManager.getJomoaaTimeMinute(context)
    val prayerDays = (-1..WAKE_RECURRING_LOOKAHEAD_DAYS).mapNotNull { dayOffset ->
        val date = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
        }
        PrayerTimesRepository.loadDayPrayerTimes(
            context = context,
            delegationId = delegationId,
            year = date.get(Calendar.YEAR),
            month = date.get(Calendar.MONTH) + 1,
            day = date.get(Calendar.DAY_OF_MONTH),
        )?.let { prayerTimes ->
            WakeAlarmComputer.PrayerDayContext(
                date = date,
                prayerTimes = prayerTimes,
                isFriday = date.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY,
                jomoaaHour = jomoaaHour,
                jomoaaMinute = jomoaaMinute,
            )
        }
    }

    val computeResult = WakeAlarmComputer.compute(now, config, prayerDays)
    val nextTrigger = computeResult.allTriggers.firstOrNull() ?: return null
    val previewTime = formatWakePreviewDateTime(nextTrigger.triggerAtMillis)
    val warning = computeWakeSilenceWarning(
        context = context,
        triggers = computeResult.allTriggers,
        prayerDays = prayerDays,
    )

    return WakePreview(
        title = context.getString(
            if (nextTrigger.isSubAlarm) {
                R.string.wake_editor_preview_subalarm
            } else {
                R.string.wake_editor_preview_main
            },
        ),
        detail = when {
            config.mainAlarm.mode == WakeMainAlarmMode.FROM_NOW && nextTrigger.isSubAlarm -> {
                context.getString(
                    R.string.wake_editor_preview_detail_from_now_subalarm,
                    formatWakePreviewOffset(
                        context,
                        nextTrigger.signedOffsetMinutes,
                    ),
                    previewTime,
                )
            }

            config.mainAlarm.mode == WakeMainAlarmMode.FROM_NOW -> {
                context.getString(
                    R.string.wake_editor_preview_detail_from_now,
                    formatArabicMinutes(config.mainAlarm.oneOffOffsetMinutes),
                    previewTime,
                )
            }

            config.mainAlarm.mode == WakeMainAlarmMode.FIXED_TIME && nextTrigger.isSubAlarm -> {
                context.getString(
                    R.string.wake_editor_preview_detail_fixed_subalarm,
                    formatWakePreviewOffset(
                        context,
                        nextTrigger.signedOffsetMinutes,
                    ),
                    previewTime,
                )
            }

            config.mainAlarm.mode == WakeMainAlarmMode.FIXED_TIME -> {
                context.getString(
                    R.string.wake_editor_preview_detail_fixed,
                    previewTime,
                )
            }

            else -> {
                context.getString(
                    R.string.wake_editor_preview_detail,
                    prayerDisplayName(context, nextTrigger.effectivePrayer),
                    previewTime,
                )
            }
        },
        warning = warning,
    )
}

private fun resolveOneTimeWakeTrigger(
    context: android.content.Context,
    delegationId: Int,
    config: PrayerWakeConfig,
): PrayerWakeConfig {
    if (config.mainAlarm.mode == WakeMainAlarmMode.FROM_NOW || config.repeatMode != WakeRepeatMode.ONCE) {
        return config
    }

    val now = Calendar.getInstance()
    val jomoaaHour = PrefsManager.getJomoaaTimeHour(context)
    val jomoaaMinute = PrefsManager.getJomoaaTimeMinute(context)
    val prayerDays = (0..WAKE_RECURRING_LOOKAHEAD_DAYS).mapNotNull { dayOffset ->
        val date = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
        }
        PrayerTimesRepository.loadDayPrayerTimes(
            context = context,
            delegationId = delegationId,
            year = date.get(Calendar.YEAR),
            month = date.get(Calendar.MONTH) + 1,
            day = date.get(Calendar.DAY_OF_MONTH),
        )?.let { prayerTimes ->
            WakeAlarmComputer.PrayerDayContext(
                date = date,
                prayerTimes = prayerTimes,
                isFriday = date.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY,
                jomoaaHour = jomoaaHour,
                jomoaaMinute = jomoaaMinute,
            )
        }
    }
    val mainTrigger = WakeAlarmComputer.compute(now, config, prayerDays).mainAlarm ?: return config
    return config.copy(
        mainAlarm = config.mainAlarm.copy(
            oneOffTriggerAtMillis = mainTrigger.triggerAtMillis,
        ),
    )
}

private fun computeWakeTimelineEntries(
    context: android.content.Context,
    delegationId: Int,
    config: PrayerWakeConfig,
): List<WakeSubAlarmTimelineEntry> {
    val now = Calendar.getInstance()
    val jomoaaHour = PrefsManager.getJomoaaTimeHour(context)
    val jomoaaMinute = PrefsManager.getJomoaaTimeMinute(context)
    val prayerDays = (-1..WAKE_RECURRING_LOOKAHEAD_DAYS).mapNotNull { dayOffset ->
        val date = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
        }
        PrayerTimesRepository.loadDayPrayerTimes(
            context = context,
            delegationId = delegationId,
            year = date.get(Calendar.YEAR),
            month = date.get(Calendar.MONTH) + 1,
            day = date.get(Calendar.DAY_OF_MONTH),
        )?.let { prayerTimes ->
            WakeAlarmComputer.PrayerDayContext(
                date = date,
                prayerTimes = prayerTimes,
                isFriday = date.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY,
                jomoaaHour = jomoaaHour,
                jomoaaMinute = jomoaaMinute,
            )
        }
    }

    val computeResult = WakeAlarmComputer.compute(now, config.copy(enabled = true), prayerDays)
    val mainTriggerAtMillis = computeResult.mainAlarm?.triggerAtMillis
        ?: return fallbackWakeTimelineEntries(context, config.subAlarms)

    return buildList {
        config.subAlarms.forEachIndexed { index, subAlarm ->
            val triggerAtMillis = mainTriggerAtMillis + subAlarm.signedOffsetMinutes.toMillis()
            add(
                WakeSubAlarmTimelineEntry(
                    sortAtMillis = triggerAtMillis,
                    stableOrder = index,
                    title = context.getString(R.string.wake_editor_subalarm_timeline_alarm, index + 1),
                    timing = formatWakeTimelineTime(triggerAtMillis),
                    isMainAlarm = false,
                ),
            )
        }
        add(
            WakeSubAlarmTimelineEntry(
                sortAtMillis = mainTriggerAtMillis,
                stableOrder = Int.MAX_VALUE,
                title = context.getString(R.string.wake_editor_subalarm_timeline_main),
                timing = formatWakeTimelineTime(mainTriggerAtMillis),
                isMainAlarm = true,
            ),
        )
    }.sortedWith(
        compareBy<WakeSubAlarmTimelineEntry> { entry -> entry.sortAtMillis }
            .thenBy { entry -> entry.stableOrder },
    )
}

private fun fallbackWakeTimelineEntries(
    context: android.content.Context,
    subAlarms: List<PrayerWakeSubAlarm>,
): List<WakeSubAlarmTimelineEntry> = buildList {
    subAlarms.forEachIndexed { index, subAlarm ->
        add(
            WakeSubAlarmTimelineEntry(
                sortAtMillis = subAlarm.signedOffsetMinutes.toLong(),
                stableOrder = index,
                title = context.getString(R.string.wake_editor_subalarm_timeline_alarm, index + 1),
                timing = "--:--",
                isMainAlarm = false,
            ),
        )
    }
    add(
        WakeSubAlarmTimelineEntry(
            sortAtMillis = 0L,
            stableOrder = Int.MAX_VALUE,
            title = context.getString(R.string.wake_editor_subalarm_timeline_main),
            timing = "--:--",
            isMainAlarm = true,
        ),
    )
}.sortedWith(
    compareBy<WakeSubAlarmTimelineEntry> { entry -> entry.sortAtMillis }
        .thenBy { entry -> entry.stableOrder },
)

private fun computeWakeSilenceWarning(
    context: android.content.Context,
    triggers: List<WakeAlarmComputer.ScheduledWakeTrigger>,
    prayerDays: List<WakeAlarmComputer.PrayerDayContext>,
): WakeValidationWarning? {
    if (!PrefsManager.isEnabled(context)) {
        return null
    }

    val silenceConfigs = Prayer.entries.associateWith { prayer ->
        PrefsManager.getConfig(context, prayer)
    }

    val triggerWithOverlap = triggers
        .asSequence()
        .mapNotNull { trigger ->
            findWakeSilenceOverlap(trigger, prayerDays, silenceConfigs)?.let { overlap ->
                trigger to overlap
            }
        }
        .firstOrNull()
        ?: return null

    val (trigger, overlap) = triggerWithOverlap
    val detail = context.getString(
        R.string.wake_editor_warning_silence_overlap,
        wakeTriggerLabel(context, trigger),
        formatWakePreviewDateTime(trigger.triggerAtMillis),
        prayerDisplayName(context, overlap.prayer),
        formatWakePreviewDateTime(overlap.startAtMillis),
        formatWakePreviewDateTime(overlap.endAtMillis),
    )
    return WakeValidationWarning(
        title = context.getString(R.string.wake_editor_warning_title),
        detail = detail,
        conflict = WakeSilenceConflict(
            detail = detail,
            silencePrayer = overlap.prayer,
            silenceStartAtMillis = overlap.startAtMillis,
            silenceEndAtMillis = overlap.endAtMillis,
            triggerAtMillis = trigger.triggerAtMillis,
        ),
    )
}

private fun findWakeSilenceOverlap(
    trigger: WakeAlarmComputer.ScheduledWakeTrigger,
    prayerDays: List<WakeAlarmComputer.PrayerDayContext>,
    silenceConfigs: Map<Prayer, PrayerSilenceConfig>,
): SilenceAlarmComputer.SilenceWindowOverlap? =
    prayerDays
        .asSequence()
        .mapNotNull { prayerDay ->
            SilenceAlarmComputer.overlapForTrigger(
                triggerAtMillis = trigger.triggerAtMillis,
                prayerDay = prayerDay.date,
                prayerTimes = prayerDay.prayerTimes,
                configs = silenceConfigs,
                isFriday = prayerDay.isFriday,
                jomoaaHour = prayerDay.jomoaaHour,
                jomoaaMinute = prayerDay.jomoaaMinute,
            )
        }
        .firstOrNull()

private fun wakeTriggerLabel(
    context: android.content.Context,
    trigger: WakeAlarmComputer.ScheduledWakeTrigger,
): String =
    if (trigger.isSubAlarm) {
        context.getString(
            R.string.wake_editor_warning_trigger_subalarm,
            formatWakePreviewOffset(context, trigger.signedOffsetMinutes),
        )
    } else {
        context.getString(R.string.wake_editor_warning_trigger_main)
    }

private fun formatWakePreviewDateTime(timeInMillis: Long): String {
    val formatter = SimpleDateFormat("EEEE d MMMM، HH:mm", Locale.forLanguageTag("ar-TN-u-nu-latn"))
    return formatter.format(Date(timeInMillis))
}

private fun formatWakeTimelineTime(timeInMillis: Long): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.forLanguageTag("ar-TN-u-nu-latn"))
    return formatter.format(Date(timeInMillis))
}

private fun sanitizeMinutesInput(input: String): String =
    normalizeDigits(input).filter { it in '0'..'9' }.take(3)

private fun sanitizeHoursInput(input: String): String =
    normalizeDigits(input).filter { it in '0'..'9' }.take(3)

private fun sanitizeHourMinutePartInput(input: String): String =
    normalizeDigits(input).filter { it in '0'..'9' }.take(2)

/** Normalizes Eastern Arabic (٠-٩) and Extended Arabic-Indic (۰-۹) digits to Western 0-9. */
private fun normalizeDigits(input: String): String = buildString(input.length) {
    for (c in input) {
        when (c) {
            in '\u0660'..'\u0669' -> append('0' + (c - '\u0660'))
            in '\u06F0'..'\u06F9' -> append('0' + (c - '\u06F0'))
            else -> append(c)
        }
    }
}

private fun parseFromNowOffsetMinutes(
    hoursText: String,
    minutesText: String,
    fallbackMinutes: Int,
): Int {
    val hours = hoursText.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val minutes = minutesText.toIntOrNull()?.coerceIn(0, 59) ?: 0
    val totalMinutes = (hours * 60) + minutes
    return if (totalMinutes > 0) totalMinutes else fallbackMinutes.coerceAtLeast(1)
}

private fun prayerDisplayName(context: android.content.Context, prayer: Prayer): String = when (prayer) {
    Prayer.FAJR -> context.getString(R.string.prayer_fajr)
    Prayer.DHUHR -> context.getString(R.string.prayer_dhuhr)
    Prayer.ASR -> context.getString(R.string.prayer_asr)
    Prayer.MAGHRIB -> context.getString(R.string.prayer_maghrib)
    Prayer.ISHA -> context.getString(R.string.prayer_isha)
    Prayer.JOMOAA -> context.getString(R.string.prayer_jomoaa)
    Prayer.AID_FITR -> context.getString(R.string.prayer_aid_fitr)
    Prayer.AID_ADHA -> context.getString(R.string.prayer_aid_adha)
}

private fun formatWakePreviewOffset(
    context: android.content.Context,
    signedOffsetMinutes: Int,
): String = context.getString(
    if (signedOffsetMinutes < 0) {
        R.string.wake_alarm_offset_before
    } else {
        R.string.wake_alarm_offset_after
    },
    formatArabicMinutes(abs(signedOffsetMinutes)),
)

private fun formatWakeEditorTime(hour: Int, minute: Int): String =
    String.format(Locale.US, "%02d:%02d", hour, minute)

private fun Int.toMillis(): Long = this * 60_000L