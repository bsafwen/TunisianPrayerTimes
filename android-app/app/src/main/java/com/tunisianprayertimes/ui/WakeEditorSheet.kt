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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
import com.tunisianprayertimes.WakeAlarmComputer
import com.tunisianprayertimes.WakeMainAlarmConfig
import com.tunisianprayertimes.WakeMainAlarmMode
import com.tunisianprayertimes.WakePlaybackOptions
import com.tunisianprayertimes.WakeUpCheckStep
import com.tunisianprayertimes.WakeUpCheckType
import com.tunisianprayertimes.formatArabicMinutes
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.SilenceRed
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import com.tunisianprayertimes.wake.GyroscopeMazeGame
import com.tunisianprayertimes.wake.WhackAMoleGame
import com.tunisianprayertimes.wake.WakeRingtoneCatalog
import com.tunisianprayertimes.wake.WakeRingtonePreviewPlayer
import com.tunisianprayertimes.wake.wakeUpCheckChallengeFor
import com.tunisianprayertimes.wake.wakeUpCheckChallengeForStep
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WakeEditorSheet(
    activity: AppCompatActivity,
    delegationId: Int,
    initialConfig: PrayerWakeConfig,
    isNewAlarm: Boolean = false,
    onDismissRequest: () -> Unit,
    onSave: (PrayerWakeConfig) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
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
    var subAlarms by remember(initialConfig.id, initialConfig.subAlarms) {
        mutableStateOf(initialConfig.subAlarms)
    }
    var silenceUntilAlarm by remember(initialConfig.id, initialConfig.silenceUntilAlarm) {
        mutableStateOf(initialConfig.silenceUntilAlarm)
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
    val supportsSilenceUntilAlarm = mode == WakeMainAlarmMode.FROM_NOW
    val effectiveSilenceUntilAlarm = silenceUntilAlarm && supportsSilenceUntilAlarm

    fun rescheduleFromNowAlarm(hoursText: String, minutesText: String) {
        fromNowHoursText = hoursText
        fromNowMinutesText = minutesText
        fromNowTriggerAtMillis = System.currentTimeMillis() + parseFromNowOffsetMinutes(
            hoursText = hoursText,
            minutesText = minutesText,
            fallbackMinutes = initialFromNowOffsetMinutes,
        ).toMillis()
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
        mainPlayback,
        subAlarms,
        effectiveSilenceUntilAlarm,
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
                oneOffTriggerAtMillis = fromNowTriggerAtMillis,
            ),
            playback = mainPlayback,
            subAlarms = subAlarms,
            silenceUntilAlarm = effectiveSilenceUntilAlarm,
        )
    }
    val preview = remember(delegationId, draftConfig) {
        computeWakePreview(context, delegationId, draftConfig)
    }

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val newSubAlarmIds = remember { mutableStateListOf<String>() }
    var modePickerVisible by rememberSaveable(initialConfig.id) { mutableStateOf(false) }

    fun selectMainAlarmMode(nextMode: WakeMainAlarmMode) {
        mode = nextMode
        if (nextMode == WakeMainAlarmMode.FROM_NOW) {
            fromNowTriggerAtMillis = System.currentTimeMillis() + parsedFromNowOffsetMinutes.toMillis()
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

    Surface(
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
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

                Button(
                    onClick = { onSave(draftConfig) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.heightIn(min = 38.dp),
                ) {
                    Text(text = stringResource(R.string.wake_editor_save))
                }
            }

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
                    fromNowHoursText = fromNowHoursText,
                    fromNowMinutesText = fromNowMinutesText,
                    onFromNowHoursTextChange = { updatedHours ->
                        rescheduleFromNowAlarm(
                            hoursText = updatedHours,
                            minutesText = fromNowMinutesText,
                        )
                    },
                    onFromNowMinutesTextChange = { updatedMinutes ->
                        rescheduleFromNowAlarm(
                            hoursText = fromNowHoursText,
                            minutesText = updatedMinutes,
                        )
                    },
                    silenceUntilAlarm = effectiveSilenceUntilAlarm,
                    onSilenceUntilAlarmChange = { updated -> silenceUntilAlarm = updated },
                )
            }

            if (enabled) {
                preview?.warning?.let { warning ->
                    WakeEditorWarningCard(warning = warning)
                }
            }


            WakeEditorSectionCard(
                title = stringResource(R.string.wake_editor_playback_title),
            ) {
                WakeSoundControls(
                    ringtoneLabel = stringResource(R.string.wake_editor_main_ringtone_label),
                    playback = mainPlayback,
                    onPlaybackChange = { updated -> mainPlayback = updated },
                )
            }

            WakeEditorSectionCard(
                title = stringResource(R.string.wake_editor_wake_up_check_title),
            ) {
                WakeWakeCheckControls(
                    playback = mainPlayback,
                    onPlaybackChange = { updated -> mainPlayback = updated },
                )
            }

            WakeEditorSectionCard(
                title = stringResource(R.string.wake_editor_subalarms_title),
                subtitle = stringResource(R.string.wake_editor_subalarms_relationship),
            ) {
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(text = stringResource(R.string.wake_editor_cancel))
                }

                Button(
                    onClick = { onSave(draftConfig) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(text = stringResource(R.string.wake_editor_save))
                }
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
                    Text(
                        text = "🗑️ " + stringResource(R.string.wake_editor_delete),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun WakeEditorSectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = GoldLight.copy(alpha = 0.08f)),
        border = BorderStroke(1.5.dp, Gold.copy(alpha = 0.58f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrayerNameColor,
                )
            }

            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 17.sp,
                )
            }
            content()
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
    fromNowHoursText: String,
    fromNowMinutesText: String,
    onFromNowHoursTextChange: (String) -> Unit,
    onFromNowMinutesTextChange: (String) -> Unit,
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
                    onHoursTextChange = onFromNowHoursTextChange,
                    onMinutesTextChange = onFromNowMinutesTextChange,
                    silenceUntilAlarm = silenceUntilAlarm,
                    onSilenceUntilAlarmChange = onSilenceUntilAlarmChange,
                )
            }
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
    fromNowHoursText: String,
    fromNowMinutesText: String,
): String = when (mode) {
    WakeMainAlarmMode.PRAYER_RELATIVE -> {
        val prayerName = prayerDisplayName(context, selectedPrayer)
        val offsetMinutes = offsetText.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (offsetMinutes == 0) {
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
    }

    WakeMainAlarmMode.FIXED_TIME -> context.getString(
        R.string.wake_editor_schedule_summary_fixed,
        formatWakeEditorTime(fixedHour, fixedMinute),
    )

    WakeMainAlarmMode.FROM_NOW -> context.getString(
        R.string.wake_editor_schedule_summary_from_now,
        formatWakeScheduleDuration(
            context = context,
            hours = fromNowHoursText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            minutes = fromNowMinutesText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        ),
    )
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WakeChoiceButton(
            selected = offsetDirection == OffsetDirection.BEFORE,
            onClick = { onOffsetDirectionChange(OffsetDirection.BEFORE) },
            text = stringResource(R.string.wake_editor_subalarm_before),
            compact = true,
        )
        WakeChoiceButton(
            selected = offsetDirection == OffsetDirection.AFTER,
            onClick = { onOffsetDirectionChange(OffsetDirection.AFTER) },
            text = stringResource(R.string.wake_editor_subalarm_after),
            compact = true,
        )
    }
    OutlinedTextField(
        value = offsetText,
        onValueChange = onOffsetTextChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.wake_editor_relative_label)) },
        textStyle = LocalTextStyle.current.copy(
            textAlign = TextAlign.Right,
            textDirection = TextDirection.Ltr,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
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
    onHoursTextChange: (String) -> Unit,
    onMinutesTextChange: (String) -> Unit,
    silenceUntilAlarm: Boolean,
    onSilenceUntilAlarmChange: (Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = hoursText,
            onValueChange = { newValue -> onHoursTextChange(sanitizeHoursInput(newValue)) },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.wake_editor_from_now_hours_label)) },
            textStyle = LocalTextStyle.current.copy(
                textAlign = TextAlign.Right,
                textDirection = TextDirection.Ltr,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )

        OutlinedTextField(
            value = minutesText,
            onValueChange = { newValue -> onMinutesTextChange(sanitizeHourMinutePartInput(newValue)) },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.wake_editor_from_now_minutes_label)) },
            textStyle = LocalTextStyle.current.copy(
                textAlign = TextAlign.Right,
                textDirection = TextDirection.Ltr,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
    }

    WakeSwitchSettingRow(
        title = stringResource(R.string.wake_editor_silence_toggle),
        checked = silenceUntilAlarm,
        onCheckedChange = onSilenceUntilAlarmChange,
    )
}

@Composable
private fun WakeChoiceButton(
    selected: Boolean,
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = if (compact) 34.dp else 38.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (selected) GreenPrimary else GreenPrimary.copy(alpha = 0.34f),
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) GreenPrimary else Color.White,
            contentColor = if (selected) Color.White else TextDark,
        ),
    ) {
        Text(
            text = text,
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
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
            Text(
                text = stringResource(R.string.wake_editor_preview_title),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.78f),
            )
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
            Text(
                text = if (expanded) "−" else "+",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = GreenPrimaryDark,
                modifier = Modifier.padding(start = 10.dp),
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
                Text(text = "-", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                Text(text = "+", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
    var previewStepIndex by remember { mutableStateOf<Int?>(null) }
    var wakeCheckExpanded by rememberSaveable { mutableStateOf(false) }
    val previewSteps = playback.wakeUpCheckSteps.ifEmpty {
        listOf(WakeUpCheckStep(playback.wakeUpCheckType, playback.mathDifficulty))
    }
    val wakeCheckSummary = if (!playback.wakeUpCheckEnabled) {
        stringResource(R.string.wake_editor_wake_up_check_off)
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
                subtitle = wakeCheckSummary,
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
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Text("✕", fontSize = 12.sp)
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
                                    )
                                }
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
        if (checkType == WakeUpCheckType.MATH) {
            wakeUpCheckChallengeFor("preview", System.currentTimeMillis(), difficulty)
        } else null
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
                        GyroscopeMazeGame(
                            difficulty = difficulty,
                            onCompleted = { /* no-op in preview */ },
                        )
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
    val prayerDays = (-1..2).mapNotNull { dayOffset ->
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
    return WakeValidationWarning(
        title = context.getString(R.string.wake_editor_warning_title),
        detail = context.getString(
            R.string.wake_editor_warning_silence_overlap,
            wakeTriggerLabel(context, trigger),
            formatWakePreviewDateTime(trigger.triggerAtMillis),
            prayerDisplayName(context, overlap.prayer),
            formatWakePreviewDateTime(overlap.startAtMillis),
            formatWakePreviewDateTime(overlap.endAtMillis),
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
    val formatter = SimpleDateFormat("EEEE d MMMM - HH:mm", Locale.forLanguageTag("ar-TN-u-nu-latn"))
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