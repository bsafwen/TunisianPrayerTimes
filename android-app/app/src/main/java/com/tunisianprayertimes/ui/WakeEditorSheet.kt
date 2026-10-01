package com.tunisianprayertimes.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    // Silence conflicts follow the draft and the silence settings only, never the clock tick below,
    // so a conflict the user dismissed or resolved can't reopen by itself.
    val silenceWarning = remember(delegationId, draftConfig, silenceConfigRevision) {
        computeWakeDraftSilenceWarning(context, delegationId, draftConfig)
    }
    // Bumped by the hero once a shown ring time passes, so the next occurrence is computed.
    var previewRefreshTick by remember { mutableIntStateOf(0) }
    val heroTimes = remember(delegationId, draftConfig, previewRefreshTick) {
        computeWakeHeroTimes(context, delegationId, draftConfig)
    }
    val activeRecurringSilenceConflict = silenceWarning?.conflict?.takeIf {
        draftConfig.enabled &&
            draftConfig.mainAlarm.mode != WakeMainAlarmMode.FROM_NOW &&
            draftConfig.repeatMode != WakeRepeatMode.ONCE
    }
    val behaviorSummary = remember(context, mainPlayback) {
        formatWakeBehaviorSummary(context, mainPlayback)
    }

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingExtraAlarmId by rememberSaveable(initialConfig.id) { mutableStateOf<String?>(null) }
    var modePickerVisible by rememberSaveable(initialConfig.id) { mutableStateOf(false) }
    var silenceConflictDialogVisible by remember(initialConfig.id) { mutableStateOf(false) }
    var dismissedSilenceConflictKey by remember(initialConfig.id) { mutableStateOf<WakeSilenceConflictKey?>(null) }
    var behaviorExpanded by rememberSaveable(initialConfig.id) {
        mutableStateOf(!isNewAlarm && shouldExpandWakeBehavior(initialConfig.playback))
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
        val latestWarning = computeWakeDraftSilenceWarning(context, delegationId, configToSave)
        val recurringConflict = latestWarning?.conflict?.takeIf {
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

    // The main alarm's next ring, which every extra alarm is measured from.
    val extraAlarmMainAtMillis: Long? = when {
        mode == WakeMainAlarmMode.FROM_NOW -> fromNowTriggerAtMillis
        !heroTimes.anchorIsExtraAlarm -> heroTimes.anchorAtMillis
        else -> null
    }

    fun extraAlarmTimeText(signedOffsetMinutes: Int): String = when {
        extraAlarmMainAtMillis != null ->
            formatWakeTimelineTime(extraAlarmMainAtMillis + signedOffsetMinutes.toMillis())
        mode == WakeMainAlarmMode.FIXED_TIME ->
            formatWakeMinuteOfDay(fixedHour * 60 + fixedMinute + signedOffsetMinutes)
        else -> "--:--"
    }

    val extraAlarmDeletedMessage = stringResource(R.string.wake_editor_extra_deleted)
    val extraAlarmUndoLabel = stringResource(R.string.wake_editor_extra_undo)

    fun deleteExtraAlarm(id: String) {
        val index = subAlarms.indexOfFirst { subAlarm -> subAlarm.id == id }
        if (index < 0) return
        val removed = subAlarms[index]
        subAlarms = subAlarms.filterNot { subAlarm -> subAlarm.id == id }
        editingExtraAlarmId = null
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = extraAlarmDeletedMessage,
                actionLabel = extraAlarmUndoLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && subAlarms.none { subAlarm -> subAlarm.id == removed.id }) {
                subAlarms = subAlarms.toMutableList().apply { add(index.coerceAtMost(size), removed) }
            }
        }
    }

    editingExtraAlarmId
        ?.let { id -> subAlarms.firstOrNull { subAlarm -> subAlarm.id == id } }
        ?.let { editing ->
            WakeExtraAlarmSheet(
                subAlarm = editing,
                resultTimeText = extraAlarmTimeText(editing.signedOffsetMinutes),
                mainPlayback = mainPlayback,
                onChange = { updated ->
                    subAlarms = subAlarms.map { existing -> if (existing.id == updated.id) updated else existing }
                },
                onDelete = { deleteExtraAlarm(editing.id) },
                onDismiss = { editingExtraAlarmId = null },
            )
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
        Box(modifier = Modifier.fillMaxSize()) {
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
                    val heroAnchorAtMillis = heroTimes.anchorAtMillis
                    val heroEffectivePrayer = heroTimes.anchorEffectivePrayer
                    WakeEditorHeroCard(
                        enabled = enabled,
                        timeText = when {
                            mode == WakeMainAlarmMode.FIXED_TIME && !heroTimes.anchorIsExtraAlarm ->
                                formatWakeEditorTime(fixedHour, fixedMinute)
                            heroAnchorAtMillis != null -> formatWakeTimelineTime(heroAnchorAtMillis)
                            else -> "--:--"
                        },
                        ruleText = formatWakeHeroRule(
                            context = context,
                            mode = mode,
                            prayer = selectedPrayer,
                            offsetDirection = relativeOffsetDirection,
                            offsetMinutes = parsedRelativeOffset,
                            repeatMode = effectiveRepeatMode,
                            scheduledDays = effectiveScheduledDays,
                        ),
                        ringIconRes = when (mode) {
                            WakeMainAlarmMode.FIXED_TIME -> R.drawable.ic_edit
                            WakeMainAlarmMode.PRAYER_RELATIVE -> prayerHeroIconRes(heroEffectivePrayer ?: selectedPrayer)
                            WakeMainAlarmMode.FROM_NOW -> R.drawable.ic_hourglass
                        },
                        anchorAtMillis = heroAnchorAtMillis,
                        anchorIsExtraAlarm = heroTimes.anchorIsExtraAlarm,
                        // Refresh as soon as anything the hero shows has rung, not only the main alarm.
                        refreshAtMillis = listOfNotNull(
                            heroAnchorAtMillis,
                            heroTimes.firstSubAlarm?.triggerAtMillis,
                        ).minOrNull(),
                        firstAlertText = heroTimes.firstSubAlarm?.let { firstSubAlarm ->
                            stringResource(
                                R.string.wake_editor_hero_first_alert,
                                formatWakeTimelineTime(firstSubAlarm.triggerAtMillis),
                                formatWakePreviewOffset(context, firstSubAlarm.signedOffsetMinutes),
                            )
                        },
                        // e.g. a Dhuhr-linked alarm whose next ring is a Friday follows Jumu'ah.
                        noteText = heroEffectivePrayer
                            ?.takeIf { effective ->
                                mode == WakeMainAlarmMode.PRAYER_RELATIVE && effective != selectedPrayer
                            }
                            ?.let { effective ->
                                stringResource(
                                    R.string.wake_editor_hero_effective_prayer_note,
                                    prayerDisplayName(context, effective),
                                )
                            },
                        onEditTime = if (mode == WakeMainAlarmMode.FIXED_TIME) {
                            {
                                showWakeTimePicker(
                                    activity = activity,
                                    tag = "wake_main_time_${initialConfig.id}",
                                    hour = fixedHour,
                                    minute = fixedMinute,
                                ) { hour, minute ->
                                    fixedHour = hour
                                    fixedMinute = minute
                                }
                            }
                        } else {
                            null
                        },
                        onTriggerReached = { previewRefreshTick += 1 },
                    )

                    WakeEditorSectionCard(
                        title = stringResource(R.string.wake_editor_main_section_title),
                    ) {
                        WakeScheduleBuilder(
                            mode = mode,
                            onChangeModeClick = { modePickerVisible = true },
                            selectedPrayer = selectedPrayer,
                            onPrayerSelected = { prayer -> selectedPrayer = prayer },
                            offsetDirection = relativeOffsetDirection,
                            onOffsetDirectionChange = { direction -> relativeOffsetDirection = direction },
                            offsetText = relativeOffsetText,
                            onOffsetTextChange = { newValue -> relativeOffsetText = sanitizeMinutesInput(newValue) },
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
                        silenceWarning?.takeIf { warning -> warning.conflict == null }?.let { warning ->
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

                    WakeExtraAlarmsSection(
                        subAlarms = subAlarms,
                        mainPlayback = mainPlayback,
                        timeTextFor = { signedOffsetMinutes -> extraAlarmTimeText(signedOffsetMinutes) },
                        onAdd = { direction ->
                            subAlarms = subAlarms + newExtraAlarm(
                                id = UUID.randomUUID().toString(),
                                existing = subAlarms,
                                direction = direction,
                                mainPlayback = mainPlayback,
                            )
                        },
                        onOpen = { id -> editingExtraAlarmId = id },
                    )

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

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )
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

                    // Once open, the rows say the same thing as the summary.
                    if (!subtitle.isNullOrBlank() && !(collapsible && expanded)) {
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
    mode: WakeMainAlarmMode,
    onChangeModeClick: () -> Unit,
    selectedPrayer: Prayer,
    onPrayerSelected: (Prayer) -> Unit,
    offsetDirection: OffsetDirection,
    onOffsetDirectionChange: (OffsetDirection) -> Unit,
    offsetText: String,
    onOffsetTextChange: (String) -> Unit,
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

            // The hero card above is the time control for this mode.
            WakeMainAlarmMode.FIXED_TIME -> Unit

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

/** The hero's "how it repeats" line; the time itself is shown large above it. */
private fun formatWakeHeroRule(
    context: android.content.Context,
    mode: WakeMainAlarmMode,
    prayer: Prayer,
    offsetDirection: OffsetDirection,
    offsetMinutes: Int,
    repeatMode: WakeRepeatMode,
    scheduledDays: Set<WakeScheduleDay>,
): String {
    val repeatText = if (repeatMode == WakeRepeatMode.ONCE) {
        context.getString(R.string.wake_schedule_once)
    } else {
        formatWakeScheduleDaysSummary(context, scheduledDays)
    }

    return when (mode) {
        WakeMainAlarmMode.FIXED_TIME -> repeatText
        WakeMainAlarmMode.FROM_NOW -> context.getString(R.string.wake_editor_mode_from_now)
        WakeMainAlarmMode.PRAYER_RELATIVE -> {
            val prayerName = prayerDisplayName(context, prayer)
            val relation = when {
                offsetMinutes == 0 -> context.getString(R.string.wake_editor_hero_rule_prayer_at, prayerName)
                offsetDirection == OffsetDirection.BEFORE -> context.getString(
                    R.string.wake_editor_hero_rule_prayer_before,
                    prayerName,
                    formatArabicMinutes(offsetMinutes),
                )
                else -> context.getString(
                    R.string.wake_editor_hero_rule_prayer_after,
                    prayerName,
                    formatArabicMinutes(offsetMinutes),
                )
            }
            context.getString(R.string.wake_editor_hero_rule_with_repeat, relation, repeatText)
        }
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

private fun showWakeTimePicker(
    activity: AppCompatActivity,
    tag: String,
    hour: Int,
    minute: Int,
    onTimePicked: (hour: Int, minute: Int) -> Unit,
) {
    val fragmentManager = activity.supportFragmentManager
    if (fragmentManager.isStateSaved || fragmentManager.findFragmentByTag(tag) != null) {
        return
    }
    val picker = MaterialTimePicker.Builder()
        .setTimeFormat(TimeFormat.CLOCK_24H)
        .setHour(hour)
        .setMinute(minute)
        .setTitleText(activity.getString(R.string.wake_editor_pick_time))
        .build()
    picker.addOnPositiveButtonClickListener {
        onTimePicked(picker.hour, picker.minute)
    }
    // Synchronous so a fast double tap finds the first picker and doesn't open a second one.
    picker.showNow(fragmentManager, tag)
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

private const val WAKE_HERO_TICK_MILLIS = 60_000L
private val WakeHeroRingSpacing = 8.dp
// The ring is 34dp; text lines up with the time next to it, as on the Alarms tab hero.
private val WakeHeroTextInset = 34.dp + WakeHeroRingSpacing

// Darker toward the text (the right edge; the UI is Arabic-only) so the extra lines stay readable
// while the skyline still shows on the left.
private val WakeHeroDim = Brush.horizontalGradient(
    0f to Color(0xFF003A32).copy(alpha = 0.12f),
    0.5f to Color(0xFF003A32).copy(alpha = 0.3f),
    1f to Color(0xFF003A32).copy(alpha = 0.5f),
)
private val WakeHeroPausedOverlay = SolidColor(Color(0xFF3B4543).copy(alpha = 0.55f))
private val WakeHeroPausedArtwork = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.2f) })

/**
 * The one place the editor shows the alarm time. It shares the Alarms tab hero's surface and
 * reading order; the gold ring shows what kind of time this is (a pencil when it's set directly)
 * and the pill shows the live countdown, so edits visibly change when it rings.
 * In fixed-time mode the whole card opens the time picker.
 */
@Composable
private fun WakeEditorHeroCard(
    enabled: Boolean,
    timeText: String,
    ruleText: String,
    @DrawableRes ringIconRes: Int,
    anchorAtMillis: Long?,
    anchorIsExtraAlarm: Boolean,
    refreshAtMillis: Long?,
    firstAlertText: String?,
    noteText: String?,
    onEditTime: (() -> Unit)?,
    onTriggerReached: () -> Unit,
) {
    val context = LocalContext.current
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // delay() doesn't advance while the device sleeps, so catch up as soon as the screen is back.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) nowMillis = System.currentTimeMillis()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(anchorAtMillis, refreshAtMillis) {
        while (true) {
            val current = System.currentTimeMillis()
            nowMillis = current
            // Tick on minute boundaries, and exactly when the next shown ring time passes.
            val untilNextMinute = WAKE_HERO_TICK_MILLIS - current % WAKE_HERO_TICK_MILLIS
            val untilRefresh = refreshAtMillis?.minus(current)?.takeIf { it > 0L } ?: untilNextMinute
            delay(minOf(untilNextMinute, untilRefresh))
        }
    }
    val triggerReached = refreshAtMillis != null && nowMillis >= refreshAtMillis
    LaunchedEffect(triggerReached) {
        if (triggerReached) onTriggerReached()
    }

    val editTimeLabel = stringResource(R.string.wake_editor_hero_edit_time)
    val alignWithTime = Modifier.padding(start = WakeHeroTextInset)
    HeroCardSurface(
        overlay = if (enabled) WakeHeroDim else WakeHeroPausedOverlay,
        artworkColorFilter = if (enabled) null else WakeHeroPausedArtwork,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onEditTime != null) {
                        Modifier.clickable(onClickLabel = editTimeLabel, role = Role.Button, onClick = onEditTime)
                    } else {
                        Modifier.semantics(mergeDescendants = true) {}
                    },
                )
                .padding(horizontal = 17.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    when {
                        !enabled -> R.string.wake_editor_hero_paused_title
                        anchorIsExtraAlarm -> R.string.wake_editor_hero_extra_title
                        else -> R.string.wake_alarm_next_title
                    },
                ),
                modifier = alignWithTime,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.78f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WakeHeroRingSpacing),
            ) {
                HeroIconRing(iconRes = ringIconRes)
                Text(
                    text = timeText,
                    fontSize = 44.sp,
                    lineHeight = 50.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
                )
            }
            Text(
                text = ruleText,
                modifier = alignWithTime,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.92f),
                lineHeight = 21.sp,
            )
            when {
                !enabled -> Text(
                    text = stringResource(R.string.wake_editor_hero_paused_hint),
                    modifier = alignWithTime,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.88f),
                    lineHeight = 17.sp,
                )

                anchorAtMillis == null -> Text(
                    text = stringResource(R.string.wake_editor_preview_unavailable),
                    modifier = alignWithTime,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.88f),
                    lineHeight = 17.sp,
                )

                else -> {
                    Text(
                        text = formatWakeHeroDate(context, anchorAtMillis, nowMillis),
                        modifier = alignWithTime,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                    if (noteText != null) {
                        Text(
                            text = noteText,
                            modifier = alignWithTime,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.8f),
                            lineHeight = 17.sp,
                        )
                    }
                    // Same pill as the Prayer tab's "متبقي" countdown.
                    Row(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.90f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.wake_editor_hero_countdown,
                                formatWakeCountdownDuration(anchorAtMillis - nowMillis),
                            ),
                            modifier = Modifier.weight(1f, fill = false),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = GreenPrimaryDark,
                            maxLines = 2,
                        )
                        Icon(
                            painter = painterResource(R.drawable.ic_adhkar_clock),
                            contentDescription = null,
                            tint = GreenPrimaryDark,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    if (firstAlertText != null) {
                        Text(
                            text = firstAlertText,
                            modifier = Modifier.padding(top = 2.dp),
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.82f),
                        )
                    }
                }
            }
        }
    }
}

private fun formatWakeHeroDate(
    context: android.content.Context,
    triggerAtMillis: Long,
    nowMillis: Long,
): String {
    val date = SimpleDateFormat("EEEE d MMMM", Locale.forLanguageTag("ar-TN-u-nu-latn"))
        .format(Date(triggerAtMillis))
    return when (wakeHeroRelativeDay(triggerAtMillis, nowMillis)) {
        WakeHeroRelativeDay.TODAY -> context.getString(R.string.wake_editor_hero_date_today, date)
        WakeHeroRelativeDay.TOMORROW -> context.getString(R.string.wake_editor_hero_date_tomorrow, date)
        WakeHeroRelativeDay.LATER -> date
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

/** A setting row where the whole row toggles, so the label (nearest the thumb in RTL) works too. */
@Composable
private fun WakeSwitchSettingRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) TextDark else TextMuted,
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
        WakeSwitch(checked = checked, enabled = enabled)
    }
}

/**
 * Display-only switch; its row handles the toggle. The off state uses a solid outline and thumb
 * so it doesn't read as disabled (the theme's default outline is a faint beige).
 */
@Composable
private fun WakeSwitch(
    checked: Boolean,
    enabled: Boolean,
) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        thumbContent = if (checked) {
            {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            null
        },
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = GreenPrimary,
            checkedBorderColor = GreenPrimary,
            checkedIconColor = GreenPrimary,
            uncheckedThumbColor = TextMuted,
            uncheckedTrackColor = Color.White,
            uncheckedBorderColor = TextMuted,
        ),
    )
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

/**
 * Extra alarms as one list in the order they ring, with the main alarm as its anchor. Each time
 * appears once; editing happens in [WakeExtraAlarmSheet], and new alarms are added on the side of
 * the main alarm they belong to.
 */
@Composable
private fun WakeExtraAlarmsSection(
    subAlarms: List<PrayerWakeSubAlarm>,
    mainPlayback: WakePlaybackOptions,
    timeTextFor: (signedOffsetMinutes: Int) -> String,
    onAdd: (OffsetDirection) -> Unit,
    onOpen: (String) -> Unit,
) {
    val ordered = subAlarms.inRingOrder()
    val beforeAlarms = ordered.filter { subAlarm -> subAlarm.direction == OffsetDirection.BEFORE }
    val afterAlarms = ordered.filter { subAlarm -> subAlarm.direction == OffsetDirection.AFTER }
    val canAdd = subAlarms.size < WAKE_MAX_EXTRA_ALARMS
    val summary = listOfNotNull(
        beforeAlarms.size.takeIf { count -> count > 0 }?.let { count ->
            pluralStringResource(R.plurals.wake_editor_extra_before_count, count, count)
        },
        afterAlarms.size.takeIf { count -> count > 0 }?.let { count ->
            pluralStringResource(R.plurals.wake_editor_extra_after_count, count, count)
        },
    ).joinToString(separator = " · ").ifEmpty { null }
    val addBeforeLabel = stringResource(R.string.wake_editor_extra_add_before)
    val addAfterLabel = stringResource(R.string.wake_editor_extra_add_after)

    WakeEditorSectionCard(
        title = stringResource(R.string.wake_editor_extra_section_title),
        subtitle = summary,
    ) {
        if (subAlarms.isEmpty()) {
            Text(
                text = stringResource(R.string.wake_editor_extra_empty),
                fontSize = 13.sp,
                color = TextDark,
                lineHeight = 20.sp,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WakeExtraAddButton(label = addBeforeLabel, onClick = { onAdd(OffsetDirection.BEFORE) })
                WakeExtraAddButton(label = addAfterLabel, onClick = { onAdd(OffsetDirection.AFTER) })
            }
        } else {
            WakeExtraAlarmTimeline(
                beforeAlarms = beforeAlarms,
                afterAlarms = afterAlarms,
                mainPlayback = mainPlayback,
                canAdd = canAdd,
                addBeforeLabel = addBeforeLabel,
                addAfterLabel = addAfterLabel,
                timeTextFor = timeTextFor,
                onAdd = onAdd,
                onOpen = onOpen,
            )
        }
    }
}

@Composable
private fun WakeExtraAlarmTimeline(
    beforeAlarms: List<PrayerWakeSubAlarm>,
    afterAlarms: List<PrayerWakeSubAlarm>,
    mainPlayback: WakePlaybackOptions,
    canAdd: Boolean,
    addBeforeLabel: String,
    addAfterLabel: String,
    timeTextFor: (signedOffsetMinutes: Int) -> String,
    onAdd: (OffsetDirection) -> Unit,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White)
            .border(BorderStroke(1.dp, Gold.copy(alpha = 0.22f)), shape),
    ) {
        if (canAdd) {
            WakeExtraAddRow(label = addBeforeLabel, onClick = { onAdd(OffsetDirection.BEFORE) })
            HorizontalDivider(color = Gold.copy(alpha = 0.16f))
        }

        // null marks the main alarm between the "before" and "after" ones.
        val timeline: List<PrayerWakeSubAlarm?> = beforeAlarms + listOf(null) + afterAlarms
        timeline.forEachIndexed { index, subAlarm ->
            if (subAlarm == null) {
                WakeExtraTimelineRow(
                    timeText = timeTextFor(0),
                    description = stringResource(R.string.wake_editor_extra_main_label),
                    detail = null,
                    isMain = true,
                    isFirst = index == 0,
                    isLast = index == timeline.lastIndex,
                    onClick = null,
                )
            } else {
                WakeExtraTimelineRow(
                    timeText = timeTextFor(subAlarm.signedOffsetMinutes),
                    description = wakeExtraAlarmOffsetText(subAlarm),
                    detail = if (subAlarm.soundMatches(mainPlayback)) {
                        null
                    } else {
                        wakeExtraAlarmSoundText(context, subAlarm.playback)
                    },
                    isMain = false,
                    isFirst = index == 0,
                    isLast = index == timeline.lastIndex,
                    onClick = { onOpen(subAlarm.id) },
                )
            }
        }

        HorizontalDivider(color = Gold.copy(alpha = 0.16f))
        if (canAdd) {
            WakeExtraAddRow(label = addAfterLabel, onClick = { onAdd(OffsetDirection.AFTER) })
        } else {
            Text(
                text = stringResource(R.string.wake_editor_extra_limit, WAKE_MAX_EXTRA_ALARMS),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                fontSize = 12.sp,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun wakeExtraAlarmOffsetText(subAlarm: PrayerWakeSubAlarm): String = pluralStringResource(
    if (subAlarm.direction == OffsetDirection.BEFORE) {
        R.plurals.wake_editor_extra_before_value
    } else {
        R.plurals.wake_editor_extra_after_value
    },
    subAlarm.minutesOffset,
    subAlarm.minutesOffset,
)

private fun wakeExtraAlarmSoundText(
    context: android.content.Context,
    playback: WakePlaybackOptions,
): String = if (playback.vibrationOnly) {
    context.getString(R.string.wake_editor_vibration_only_title)
} else {
    WakeRingtoneCatalog.titleFor(context, playback.ringtone, playback.customRingtoneUri)
}

private val WAKE_EXTRA_ROW_PADDING = 16.dp
private val WAKE_EXTRA_MARKER_COLUMN = 22.dp

/** One stop on the vertical timeline; the rail is drawn so rows stay simple to measure. */
@Composable
private fun WakeExtraTimelineRow(
    timeText: String,
    description: String,
    detail: String?,
    isMain: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: (() -> Unit)?,
) {
    val railColor = Gold.copy(alpha = 0.45f)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (isMain) Modifier.background(GreenPrimary.copy(alpha = 0.05f)) else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        onClickLabel = stringResource(R.string.wake_editor_extra_edit),
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            )
            .drawBehind {
                val railCenter = (WAKE_EXTRA_ROW_PADDING + WAKE_EXTRA_MARKER_COLUMN / 2).toPx()
                val x = if (isRtl) size.width - railCenter else railCenter
                val middle = size.height / 2
                val stroke = 2.dp.toPx()
                if (!isFirst) drawLine(railColor, Offset(x, 0f), Offset(x, middle), stroke)
                if (!isLast) drawLine(railColor, Offset(x, middle), Offset(x, size.height), stroke)
            }
            .padding(horizontal = WAKE_EXTRA_ROW_PADDING, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(WAKE_EXTRA_MARKER_COLUMN),
            contentAlignment = Alignment.Center,
        ) {
            if (isMain) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(GreenPrimary)
                        .border(BorderStroke(3.dp, Color.White), CircleShape),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(BorderStroke(2.5.dp, Gold), CircleShape),
                )
            }
        }
        Text(
            text = timeText,
            modifier = Modifier.widthIn(min = 50.dp),
            fontSize = if (isMain) 17.sp else 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (isMain) GreenPrimaryDark else TextDark,
            maxLines = 1,
            style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = description,
                fontSize = 13.sp,
                fontWeight = if (isMain) FontWeight.Bold else FontWeight.Medium,
                color = if (isMain) GreenPrimaryDark else TextDark,
                lineHeight = 18.sp,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    fontSize = 11.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClick != null) {
            Icon(
                painter = painterResource(R.drawable.ic_qibla_chevron),
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun WakeExtraAddRow(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = WAKE_EXTRA_ROW_PADDING),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(WAKE_EXTRA_MARKER_COLUMN)
                .border(BorderStroke(1.5.dp, GreenPrimary.copy(alpha = 0.45f)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
                tint = GreenPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = GreenPrimary,
        )
    }
}

@Composable
private fun WakeExtraAddButton(
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = GreenPrimary),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Edits one extra alarm: when it rings (shown first, in clock time), which side of the main alarm,
 * how far, and its sound. Every change applies to the draft immediately; "تم" just closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeExtraAlarmSheet(
    subAlarm: PrayerWakeSubAlarm,
    resultTimeText: String,
    mainPlayback: WakePlaybackOptions,
    onChange: (PrayerWakeSubAlarm) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var soundExpanded by rememberSaveable(subAlarm.id) { mutableStateOf(false) }
    val soundMatchesMain = subAlarm.soundMatches(mainPlayback)
    val isBefore = subAlarm.direction == OffsetDirection.BEFORE

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
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(
                    if (isBefore) R.string.wake_editor_extra_add_before else R.string.wake_editor_extra_add_after,
                ),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor,
            )

            Row(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = stringResource(R.string.wake_editor_extra_rings_at),
                    modifier = Modifier.padding(bottom = 6.dp),
                    fontSize = 13.sp,
                    color = TextMuted,
                )
                Text(
                    text = resultTimeText,
                    fontSize = 40.sp,
                    lineHeight = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = GreenPrimaryDark,
                    style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
                )
            }

            WakeSegmentedChoice(
                options = listOf(
                    stringResource(R.string.wake_editor_extra_direction_before),
                    stringResource(R.string.wake_editor_extra_direction_after),
                ),
                selectedIndex = if (isBefore) 0 else 1,
                onSelect = { index ->
                    onChange(
                        subAlarm.copy(direction = if (index == 0) OffsetDirection.BEFORE else OffsetDirection.AFTER),
                    )
                },
            )

            WakeOffsetStepper(
                minutes = subAlarm.minutesOffset,
                onChange = { minutes -> onChange(subAlarm.copy(minutesOffset = minutes)) },
            )

            HorizontalDivider(color = Gold.copy(alpha = 0.16f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClick = { soundExpanded = !soundExpanded }),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.wake_editor_extra_sound),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDark,
                    )
                    Text(
                        text = if (soundMatchesMain) {
                            stringResource(R.string.wake_editor_extra_sound_same)
                        } else {
                            wakeExtraAlarmSoundText(context, subAlarm.playback)
                        },
                        fontSize = 12.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    painter = painterResource(if (soundExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(visible = soundExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WakeSoundControls(
                        ringtoneLabel = stringResource(R.string.wake_editor_subalarm_ringtone_label),
                        playback = subAlarm.playback,
                        onPlaybackChange = { updated -> onChange(subAlarm.copy(playback = updated)) },
                    )
                    if (!soundMatchesMain) {
                        TextButton(
                            onClick = {
                                onChange(subAlarm.copy(playback = subAlarm.playback.withSoundOf(mainPlayback)))
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.wake_editor_extra_use_main_sound),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            if (!isBefore) {
                // Stopping the main alarm doesn't cancel pending "after" alarms, so say so.
                Text(
                    text = stringResource(R.string.wake_editor_extra_after_note),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFFF4DF))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    fontSize = 12.sp,
                    color = Color(0xFF6A4A00),
                    lineHeight = 17.sp,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = SilenceRed),
                ) {
                    Text(
                        text = stringResource(R.string.wake_editor_extra_delete),
                        fontWeight = FontWeight.Bold,
                    )
                }
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text(
                        text = stringResource(R.string.wake_editor_extra_done),
                        modifier = Modifier.padding(horizontal = 12.dp),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun WakeSegmentedChoice(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .height(IntrinsicSize.Min)
            .clip(shape)
            .border(BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.35f)), shape)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(GreenPrimary.copy(alpha = 0.25f)),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (selected) GreenPrimary else Color.White)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(index) })
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (selected) Color.White else TextDark,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun WakeOffsetStepper(
    minutes: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WakeRepeatingStepButton(
            iconRes = R.drawable.ic_remove,
            contentDescription = stringResource(R.string.wake_editor_subalarm_decrease),
            enabled = minutes > 1,
            onStep = { onChange(stepExtraAlarmOffset(minutes, increase = false)) },
        )
        Text(
            text = pluralStringResource(R.plurals.prayer_silence_duration_minutes, minutes, minutes),
            modifier = Modifier.weight(1f),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = GreenPrimaryDark,
            textAlign = TextAlign.Center,
        )
        WakeRepeatingStepButton(
            iconRes = R.drawable.ic_add,
            contentDescription = stringResource(R.string.wake_editor_subalarm_increase),
            enabled = minutes < WAKE_EXTRA_ALARM_MAX_OFFSET_MINUTES,
            onStep = { onChange(stepExtraAlarmOffset(minutes, increase = true)) },
        )
    }
}

/** A round step button that repeats while held, so long jumps don't need many taps. */
@Composable
private fun WakeRepeatingStepButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    onStep: () -> Unit,
) {
    val currentOnStep by rememberUpdatedState(onStep)
    val currentEnabled by rememberUpdatedState(enabled)
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .border(
                BorderStroke(1.5.dp, if (enabled) GreenPrimary.copy(alpha = 0.45f) else TextMuted.copy(alpha = 0.25f)),
                CircleShape,
            )
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                if (!enabled) disabled()
                onClick {
                    if (currentEnabled) currentOnStep()
                    currentEnabled
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (!currentEnabled) return@detectTapGestures
                        currentOnStep()
                        coroutineScope {
                            val repeat = launch {
                                delay(WAKE_STEP_REPEAT_START_MILLIS)
                                while (isActive && currentEnabled) {
                                    currentOnStep()
                                    delay(WAKE_STEP_REPEAT_INTERVAL_MILLIS)
                                }
                            }
                            tryAwaitRelease()
                            repeat.cancel()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (enabled) GreenPrimary else TextMuted.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp),
        )
    }
}

private const val WAKE_STEP_REPEAT_START_MILLIS = 450L
private const val WAKE_STEP_REPEAT_INTERVAL_MILLIS = 110L

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
    // Vibration-only disables the sound rows in place rather than removing them,
    // so nothing moves under the finger that just toggled it.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            enabled = !playback.vibrationOnly,
        )

        WakeSwitchSettingRow(
            title = stringResource(R.string.wake_editor_vibration_only_title),
            subtitle = stringResource(R.string.wake_editor_vibration_only_subtitle),
            checked = playback.vibrationOnly,
            onCheckedChange = { enabled ->
                onPlaybackChange(playback.copy(vibrationOnly = enabled))
            },
        )

        WakeSwitchSettingRow(
            title = stringResource(R.string.wake_editor_progressive_volume_title),
            subtitle = stringResource(R.string.wake_editor_progressive_volume_subtitle),
            checked = playback.progressiveVolume,
            onCheckedChange = { enabled ->
                onPlaybackChange(playback.copy(progressiveVolume = enabled))
            },
            enabled = !playback.vibrationOnly,
        )
    }
}

// Never offer 7: persistence migrates a stored 7 (the old default) back to 3.
private val WAKE_AWAKE_CHECK_DELAY_CHOICES = listOf(1, 3, 5, 10)
private val WAKE_STEP_MARKER_COLUMN = 28.dp
private val WAKE_STEP_MARKER_SIZE = 24.dp

/**
 * What happens once the alarm rings, in time order: it rings, the stop challenge gates the
 * stop button, then the awake check asks again a few minutes after the last alert is stopped.
 */
@Composable
private fun WakeWakeCheckControls(
    playback: WakePlaybackOptions,
    onPlaybackChange: (WakePlaybackOptions) -> Unit,
) {
    var wakeCheckExpanded by rememberSaveable { mutableStateOf(false) }
    val challengeSteps = playback.effectiveWakeUpCheckSteps
    val challengeSummary = if (challengeSteps.size == 1) {
        stringResource(
            R.string.wake_editor_wake_up_check_single_summary,
            wakeCheckTypeLabel(challengeSteps.first().type),
            wakeCheckDifficultyLabel(challengeSteps.first().difficulty),
        )
    } else {
        stringResource(R.string.wake_editor_wake_up_check_steps_summary, challengeSteps.size)
    }
    val awakeCheckDelayChoices = (WAKE_AWAKE_CHECK_DELAY_CHOICES + playback.awakeCheckDelayMinutes)
        .distinct()
        .sorted()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.wake_editor_after_ring_title),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = PrayerNameColor,
        )

        WakeAfterRingStep(
            number = 1,
            caption = stringResource(R.string.wake_editor_after_ring_ring_caption),
            active = true,
        ) {
            Text(
                text = stringResource(R.string.wake_editor_after_ring_ring_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark,
            )
        }

        WakeAfterRingStep(
            number = 2,
            caption = stringResource(R.string.wake_editor_after_ring_stop_caption),
            active = playback.wakeUpCheckEnabled,
        ) {
            WakeSwitchSettingRow(
                title = stringResource(R.string.wake_editor_wake_up_check_title),
                subtitle = if (playback.wakeUpCheckEnabled) {
                    null
                } else {
                    stringResource(R.string.wake_editor_wake_up_check_subtitle)
                },
                checked = playback.wakeUpCheckEnabled,
                onCheckedChange = { enabled ->
                    onPlaybackChange(playback.copy(wakeUpCheckEnabled = enabled))
                    if (enabled) wakeCheckExpanded = true
                },
            )

            if (playback.wakeUpCheckEnabled) {
                WakeChallengeEditLink(
                    summary = challengeSummary,
                    expanded = wakeCheckExpanded,
                    onClick = { wakeCheckExpanded = !wakeCheckExpanded },
                )
            }

            AnimatedVisibility(visible = playback.wakeUpCheckEnabled && wakeCheckExpanded) {
                WakeUpCheckStepsEditor(
                    playback = playback,
                    onPlaybackChange = onPlaybackChange,
                )
            }
        }

        WakeAfterRingStep(
            number = 3,
            caption = stringResource(
                R.string.wake_editor_after_ring_awake_caption,
                formatArabicMinutes(playback.awakeCheckDelayMinutes),
            ),
            active = playback.awakeCheckEnabled,
            isLast = true,
        ) {
            WakeSwitchSettingRow(
                title = stringResource(R.string.wake_editor_awake_check_title),
                subtitle = stringResource(R.string.wake_editor_awake_check_subtitle_compact),
                checked = playback.awakeCheckEnabled,
                onCheckedChange = { enabled ->
                    onPlaybackChange(playback.copy(awakeCheckEnabled = enabled))
                },
            )

            if (playback.awakeCheckEnabled) {
                // One row; the caption above already spells the delay out in full.
                WakeSegmentedChoice(
                    options = awakeCheckDelayChoices.map { minutes ->
                        stringResource(R.string.wake_editor_minutes_short, minutes)
                    },
                    selectedIndex = awakeCheckDelayChoices.indexOf(playback.awakeCheckDelayMinutes),
                    onSelect = { index ->
                        onPlaybackChange(playback.copy(awakeCheckDelayMinutes = awakeCheckDelayChoices[index]))
                    },
                )
            }
        }
    }
}

/** A numbered step with a connector line down to the next one (drawn, so no intrinsic measuring). */
@Composable
private fun WakeAfterRingStep(
    number: Int,
    caption: String,
    active: Boolean,
    isLast: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val connectorColor = Gold.copy(alpha = 0.35f)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (!isLast) {
                    val markerCenter = (WAKE_STEP_MARKER_COLUMN / 2).toPx()
                    val x = if (isRtl) size.width - markerCenter else markerCenter
                    drawLine(
                        color = connectorColor,
                        start = Offset(x, WAKE_STEP_MARKER_SIZE.toPx() + 4.dp.toPx()),
                        end = Offset(x, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.width(WAKE_STEP_MARKER_COLUMN),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .size(WAKE_STEP_MARKER_SIZE)
                    .clip(CircleShape)
                    .background(if (active) GreenPrimary else GoldLight)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = number.toString(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (active) Color.White else TextDark,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 3.dp, bottom = if (isLast) 0.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = caption,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMuted,
            )
            content()
        }
    }
}

@Composable
private fun WakeChallengeEditLink(
    summary: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = summary,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = GreenPrimaryDark,
        )
        Text(
            text = stringResource(
                if (expanded) R.string.wake_editor_challenge_hide else R.string.wake_editor_challenge_edit,
            ),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = GreenPrimary,
        )
        Icon(
            painter = painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
            contentDescription = null,
            tint = GreenPrimary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun WakeUpCheckStepsEditor(
    playback: WakePlaybackOptions,
    onPlaybackChange: (WakePlaybackOptions) -> Unit,
) {
    val context = LocalContext.current
    val gyroscopeMazeSupported = remember(context) { hasGyroscopeMazeTiltSensor(context) }
    var previewStepIndex by remember { mutableStateOf<Int?>(null) }
    val steps = playback.effectiveWakeUpCheckSteps

    previewStepIndex?.let { index -> steps.getOrNull(index) }?.let { step ->
        WakeUpCheckPreviewDialog(
            checkType = step.type,
            difficulty = step.difficulty,
            onDismiss = { previewStepIndex = null },
        )
    }

    Column(
        modifier = Modifier.padding(bottom = 8.dp),
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
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val previewPlayer = remember(context) { WakeRingtonePreviewPlayer(context) }
    var previewingPreset by remember { mutableStateOf<RingtonePreset?>(null) }
    var pickerVisible by rememberSaveable { mutableStateOf(false) }

    fun stopPreview() {
        previewPlayer.stop()
        previewingPreset = null
    }

    fun startPreview(preset: RingtonePreset) {
        val started = previewPlayer.play(preset, customRingtoneUri.takeIf { preset == RingtonePreset.CUSTOM })
        previewingPreset = preset.takeIf { started }
    }

    fun togglePreview(preset: RingtonePreset) {
        if (previewingPreset == preset) stopPreview() else startPreview(preset)
    }

    DisposableEffect(previewPlayer) {
        onDispose {
            previewPlayer.release()
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled) {
            stopPreview()
            pickerVisible = false
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
            color = if (enabled) PrayerNameColor else TextMuted,
        )

        WakeRingtoneRow(
            title = WakeRingtoneCatalog.titleFor(context, selected, customRingtoneUri),
            summary = WakeRingtoneCatalog.summaryFor(context, selected),
            enabled = enabled,
            canPreview = canPreviewRingtone(selected, customRingtoneUri),
            isPreviewing = previewingPreset == selected,
            onOpenPicker = { pickerVisible = true },
            onTogglePreview = { togglePreview(selected) },
        )
    }

    if (pickerVisible) {
        WakeRingtonePickerSheet(
            title = label,
            selected = selected,
            customRingtoneUri = customRingtoneUri,
            previewingPreset = previewingPreset,
            onChoose = { preset ->
                onSelected(preset)
                startPreview(preset)
            },
            onTogglePreview = { preset -> togglePreview(preset) },
            onPickFromPhone = {
                stopPreview()
                pickerVisible = false
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
            onDismiss = {
                stopPreview()
                pickerVisible = false
            },
        )
    }
}

private fun canPreviewRingtone(preset: RingtonePreset, customRingtoneUri: String?): Boolean =
    preset == RingtonePreset.CUSTOM && !customRingtoneUri.isNullOrBlank() ||
        WakeRingtoneCatalog.rawResIdFor(preset) != null ||
        WakeRingtoneCatalog.systemTypeFor(preset) != null

/** The current tone as a list row: tapping the row opens the picker, the ▶ beside it previews. */
@Composable
private fun WakeRingtoneRow(
    title: String,
    summary: String,
    enabled: Boolean,
    canPreview: Boolean,
    isPreviewing: Boolean,
    onOpenPicker: () -> Unit,
    onTogglePreview: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(Color.White)
            .border(BorderStroke(1.dp, Gold.copy(alpha = 0.28f)), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 64.dp)
                .clickable(
                    enabled = enabled,
                    onClickLabel = stringResource(R.string.wake_ringtone_change),
                    role = Role.Button,
                    onClick = onOpenPicker,
                )
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(GreenPrimary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_volume_on),
                    contentDescription = null,
                    tint = GreenPrimaryDark,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextDark,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(20.dp),
            )
        }

        if (canPreview) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(Gold.copy(alpha = 0.28f)),
            )
            WakeRingtonePreviewButton(
                isPreviewing = isPreviewing,
                enabled = enabled,
                onClick = onTogglePreview,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun WakeRingtonePreviewButton(
    isPreviewing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(GreenPrimary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            // Playback icons keep their direction in RTL.
            Icon(
                painter = painterResource(if (isPreviewing) R.drawable.ic_stop else R.drawable.ic_play_arrow),
                contentDescription = stringResource(
                    if (isPreviewing) R.string.wake_ringtone_preview_stop else R.string.wake_ringtone_preview_play,
                ),
                tint = GreenPrimaryDark,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Every tone can be heard before choosing it. Tapping a row selects it and plays it; the sheet
 * stays open so tones can be compared, and closing it stops the preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeRingtonePickerSheet(
    title: String,
    selected: RingtonePreset,
    customRingtoneUri: String?,
    previewingPreset: RingtonePreset?,
    onChoose: (RingtonePreset) -> Unit,
    onTogglePreview: (RingtonePreset) -> Unit,
    onPickFromPhone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val (adhanChoices, phoneChoices) = WakeRingtoneCatalog.selectableChoices.partition { choice ->
        WakeRingtoneCatalog.rawResIdFor(choice.preset) != null
    }
    val customChosen = selected == RingtonePreset.CUSTOM && !customRingtoneUri.isNullOrBlank()

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
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor,
            )

            WakeRingtoneGroupHeader(text = stringResource(R.string.wake_ringtone_group_adhan))
            adhanChoices.forEach { choice ->
                WakeRingtoneOptionRow(
                    title = stringResource(choice.titleResId),
                    // The group header already says these are bundled adhans.
                    summary = null,
                    selected = selected == choice.preset,
                    isPreviewing = previewingPreset == choice.preset,
                    canPreview = true,
                    onClick = { onChoose(choice.preset) },
                    onTogglePreview = { onTogglePreview(choice.preset) },
                )
            }

            WakeRingtoneGroupHeader(text = stringResource(R.string.wake_ringtone_group_phone))
            phoneChoices.forEach { choice ->
                WakeRingtoneOptionRow(
                    title = stringResource(choice.titleResId),
                    summary = stringResource(choice.summaryResId),
                    selected = selected == choice.preset,
                    isPreviewing = previewingPreset == choice.preset,
                    canPreview = canPreviewRingtone(choice.preset, customRingtoneUri = null),
                    onClick = { onChoose(choice.preset) },
                    onTogglePreview = { onTogglePreview(choice.preset) },
                )
            }
            WakeRingtoneOptionRow(
                title = if (customChosen) {
                    WakeRingtoneCatalog.titleFor(context, RingtonePreset.CUSTOM, customRingtoneUri)
                } else {
                    stringResource(R.string.wake_ringtone_pick_from_phone)
                },
                summary = stringResource(R.string.wake_ringtone_custom_summary),
                selected = selected == RingtonePreset.CUSTOM,
                isPreviewing = previewingPreset == RingtonePreset.CUSTOM,
                canPreview = customChosen,
                onClick = onPickFromPhone,
                onTogglePreview = { onTogglePreview(RingtonePreset.CUSTOM) },
            )

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(
                    text = stringResource(R.string.wake_ringtone_done),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun WakeRingtoneGroupHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
            .semantics { heading() },
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = TextMuted,
    )
}

@Composable
private fun WakeRingtoneOptionRow(
    title: String,
    summary: String?,
    selected: Boolean,
    isPreviewing: Boolean,
    canPreview: Boolean,
    onClick: () -> Unit,
    onTogglePreview: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) GreenPrimary.copy(alpha = 0.08f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                modifier = Modifier.padding(horizontal = 8.dp),
                colors = RadioButtonDefaults.colors(
                    selectedColor = GreenPrimary,
                    unselectedColor = GreenPrimary.copy(alpha = 0.55f),
                ),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) GreenPrimaryDark else TextDark,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summary != null) {
                    Text(
                        text = summary,
                        fontSize = 12.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (canPreview) {
            WakeRingtonePreviewButton(
                isPreviewing = isPreviewing,
                enabled = true,
                onClick = onTogglePreview,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

private data class WakeHeroTimes(
    /**
     * What the hero shows: the main alarm's next ring (computed even while paused), or a still-pending
     * extra alarm once a one-off main alarm has already rung. Null when it can't be computed.
     */
    val anchorAtMillis: Long?,
    val anchorIsExtraAlarm: Boolean,
    val anchorEffectivePrayer: Prayer?,
    /** The earliest extra alarm of the same occurrence that rings before the main one. */
    val firstSubAlarm: WakeAlarmComputer.ScheduledWakeTrigger?,
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

private fun loadWakePreviewPrayerDays(
    context: android.content.Context,
    delegationId: Int,
    now: Calendar,
): List<WakeAlarmComputer.PrayerDayContext> {
    val jomoaaHour = PrefsManager.getJomoaaTimeHour(context)
    val jomoaaMinute = PrefsManager.getJomoaaTimeMinute(context)
    return (-1..WAKE_RECURRING_LOOKAHEAD_DAYS).mapNotNull { dayOffset ->
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
}

private fun computeWakeHeroTimes(
    context: android.content.Context,
    delegationId: Int,
    config: PrayerWakeConfig,
): WakeHeroTimes {
    val now = Calendar.getInstance()
    val prayerDays = loadWakePreviewPrayerDays(context, delegationId, now)
    val computeResult = WakeAlarmComputer.compute(now, config.copy(enabled = true), prayerDays)
    val mainTrigger = computeResult.mainAlarm
        ?: return computeResult.subAlarms.firstOrNull().let { pendingSubAlarm ->
            WakeHeroTimes(
                anchorAtMillis = pendingSubAlarm?.triggerAtMillis,
                anchorIsExtraAlarm = pendingSubAlarm != null,
                anchorEffectivePrayer = pendingSubAlarm?.effectivePrayer,
                firstSubAlarm = null,
            )
        }

    return WakeHeroTimes(
        anchorAtMillis = mainTrigger.triggerAtMillis,
        anchorIsExtraAlarm = false,
        anchorEffectivePrayer = mainTrigger.effectivePrayer,
        // An "after" extra left over from the occurrence that already rang doesn't belong here.
        firstSubAlarm = computeResult.subAlarms.firstOrNull { subAlarm ->
            subAlarm.occurrenceAtMillis == mainTrigger.occurrenceAtMillis &&
                subAlarm.triggerAtMillis < mainTrigger.triggerAtMillis
        },
    )
}

/** A paused alarm never rings, so it can't conflict with a silence window. */
private fun computeWakeDraftSilenceWarning(
    context: android.content.Context,
    delegationId: Int,
    config: PrayerWakeConfig,
): WakeValidationWarning? {
    if (!config.enabled) {
        return null
    }
    val now = Calendar.getInstance()
    val prayerDays = loadWakePreviewPrayerDays(context, delegationId, now)
    return computeWakeSilenceWarning(
        context = context,
        triggers = WakeAlarmComputer.compute(now, config, prayerDays).allTriggers,
        prayerDays = prayerDays,
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

private fun formatWakeMinuteOfDay(minuteOfDay: Int): String =
    Math.floorMod(minuteOfDay, 24 * 60).let { minutes -> formatWakeEditorTime(minutes / 60, minutes % 60) }

private fun Int.toMillis(): Long = this * 60_000L