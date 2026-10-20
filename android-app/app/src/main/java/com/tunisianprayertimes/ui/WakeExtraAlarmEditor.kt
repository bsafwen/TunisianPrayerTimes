package com.tunisianprayertimes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.PrayerWakeSubAlarm
import com.tunisianprayertimes.R
import com.tunisianprayertimes.WakePlaybackOptions
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.SilenceRed
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import kotlinx.serialization.json.Json

private val ExtraAlarmDraftSaver = Saver<PrayerWakeSubAlarm, String>(
    save = { Json.encodeToString(PrayerWakeSubAlarm.serializer(), it) },
    restore = { Json.decodeFromString(PrayerWakeSubAlarm.serializer(), it) },
)

/** Edits stay local until the explicit add/save action, including sound and challenge changes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WakeExtraAlarmEditor(
    subAlarm: PrayerWakeSubAlarm,
    isNew: Boolean,
    mainPlayback: WakePlaybackOptions,
    timeTextFor: (Int) -> String,
    onSave: (PrayerWakeSubAlarm) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var draft by rememberSaveable(subAlarm.id, stateSaver = ExtraAlarmDraftSaver) { mutableStateOf(subAlarm) }
    var minutesText by rememberSaveable(subAlarm.id) { mutableStateOf(subAlarm.minutesOffset.toString()) }
    var soundExpanded by rememberSaveable(subAlarm.id) { mutableStateOf(false) }
    var challengeExpanded by rememberSaveable(subAlarm.id) { mutableStateOf(false) }
    val minutes = minutesText.toIntOrNull()
    val validMinutes = minutes?.takeIf { it in 1..WAKE_EXTRA_ALARM_MAX_OFFSET_MINUTES }
    val isBefore = draft.direction == OffsetDirection.BEFORE
    val soundMatchesMain = draft.soundMatches(mainPlayback)

    fun setMinutes(value: Int) {
        minutesText = value.toString()
        draft = draft.copy(minutesOffset = value)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BgCream,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.testTag("wake_extra_alarm_editor"),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(if (isNew) R.string.wake_extra_new_title else R.string.wake_extra_edit_title),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    color = GreenPrimaryDark,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.wake_extra_cancel),
                        tint = TextMuted,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Surface(
                    color = GreenPrimary.copy(alpha = 0.065f),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(stringResource(R.string.wake_editor_extra_rings_at), color = TextMuted, fontSize = 12.sp)
                        Text(
                            text = if (validMinutes == null) "—" else timeTextFor(draft.signedOffsetMinutes),
                            color = GreenPrimaryDark,
                            fontSize = 42.sp,
                            lineHeight = 48.sp,
                            fontWeight = FontWeight.Bold,
                            style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
                            modifier = Modifier.testTag("wake_extra_time_preview"),
                        )
                        Text(
                            text = if (validMinutes == null) {
                                stringResource(R.string.wake_extra_time_invalid)
                            } else {
                                pluralStringResource(
                                    if (isBefore) R.plurals.wake_editor_extra_before_value else R.plurals.wake_editor_extra_after_value,
                                    validMinutes,
                                    validMinutes,
                                )
                            },
                            color = GreenPrimaryDark,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(R.string.wake_extra_main_time, "\u2066${timeTextFor(0)}\u2069"),
                            color = TextMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.wake_extra_when),
                        color = TextDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    WakeSegmentedChoice(
                        options = listOf(
                            stringResource(R.string.wake_editor_extra_direction_before),
                            stringResource(R.string.wake_editor_extra_direction_after),
                        ),
                        selectedIndex = if (isBefore) 0 else 1,
                        onSelect = { index ->
                            draft = draft.copy(direction = if (index == 0) OffsetDirection.BEFORE else OffsetDirection.AFTER)
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ExtraAlarmMinuteButton(
                            increase = false,
                            enabled = validMinutes != null && validMinutes > 1,
                            onClick = { validMinutes?.let { setMinutes(it - 1) } },
                        )
                        OutlinedTextField(
                            value = minutesText,
                            onValueChange = { typed ->
                                // Accept both Arabic and Western decimal keyboards, storing the same value.
                                val digits = typed.trim()
                                if (digits.length <= 3 && digits.all { Character.digit(it, 10) >= 0 }) {
                                    minutesText = digits.map { Character.digit(it, 10).digitToChar() }.joinToString("")
                                    minutesText.toIntOrNull()?.takeIf { it in 1..WAKE_EXTRA_ALARM_MAX_OFFSET_MINUTES }
                                        ?.let { draft = draft.copy(minutesOffset = it) }
                                }
                            },
                            modifier = Modifier.weight(1f).testTag("wake_extra_minutes"),
                            label = { Text(stringResource(R.string.wake_extra_minutes_label)) },
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                textDirection = TextDirection.Ltr,
                                color = GreenPrimaryDark,
                            ),
                            singleLine = true,
                            isError = minutesText.isNotEmpty() && validMinutes == null,
                            supportingText = {
                                Text(
                                    text = stringResource(
                                        if (minutesText.isNotEmpty() && validMinutes == null) R.string.wake_extra_minutes_error
                                        else R.string.wake_extra_minutes_range,
                                    ),
                                    color = if (minutesText.isNotEmpty() && validMinutes == null) SilenceRed else TextMuted,
                                    fontSize = 12.sp,
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GreenPrimary,
                                unfocusedBorderColor = Gold.copy(alpha = 0.45f),
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                            ),
                            shape = RoundedCornerShape(14.dp),
                        )
                        ExtraAlarmMinuteButton(
                            increase = true,
                            enabled = validMinutes != null && validMinutes < WAKE_EXTRA_ALARM_MAX_OFFSET_MINUTES,
                            onClick = { validMinutes?.let { setMinutes(it + 1) } },
                        )
                    }
                    Text(stringResource(R.string.wake_extra_quick_minutes), color = TextMuted, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(5, 10, 15, 30, 60).forEach { preset ->
                            val selected = validMinutes == preset
                            val description = pluralStringResource(R.plurals.prayer_silence_duration_minutes, preset, preset)
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .selectable(
                                        selected = selected,
                                        role = Role.RadioButton,
                                        onClick = { focusManager.clearFocus(); setMinutes(preset) },
                                    )
                                    .semantics { contentDescription = description },
                                color = if (selected) GreenPrimary else Color.White,
                                shape = RoundedCornerShape(12.dp),
                                border = if (selected) null else BorderStroke(1.dp, Gold.copy(alpha = 0.35f)),
                            ) {
                                Text(
                                    text = preset.toString(),
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    color = if (selected) Color.White else GreenPrimaryDark,
                                    textAlign = TextAlign.Center,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }

                Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(role = Role.Button, onClick = { soundExpanded = !soundExpanded }),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_adhkar_bell),
                                contentDescription = null,
                                tint = GreenPrimary,
                                modifier = Modifier.size(23.dp),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(stringResource(R.string.wake_editor_extra_sound), fontWeight = FontWeight.Bold, color = TextDark, fontSize = 14.sp)
                                Text(
                                    text = if (soundMatchesMain) stringResource(R.string.wake_editor_extra_sound_same)
                                    else wakeExtraAlarmSoundText(context, draft.playback),
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                painter = painterResource(if (soundExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                                contentDescription = stringResource(R.string.wake_extra_sound_customize),
                                tint = GreenPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        AnimatedVisibility(soundExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                HorizontalDivider(color = Gold.copy(alpha = 0.2f))
                                WakeSoundControls(
                                    ringtoneLabel = stringResource(R.string.wake_editor_subalarm_ringtone_label),
                                    playback = draft.playback,
                                    onPlaybackChange = { draft = draft.copy(playback = it) },
                                )
                                if (!soundMatchesMain) {
                                    TextButton(onClick = { draft = draft.copy(playback = draft.playback.withSoundOf(mainPlayback)) }) {
                                        Text(stringResource(R.string.wake_editor_extra_use_main_sound), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
                Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp).testTag("wake_extra_challenge"),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val challengeOn = draft.playback.wakeUpCheckEnabled
                        WakeSwitchSettingRow(
                            title = stringResource(R.string.wake_editor_wake_up_check_title),
                            subtitle = if (challengeOn) null else stringResource(R.string.wake_extra_challenge_subtitle),
                            checked = challengeOn,
                            onCheckedChange = { enabled ->
                                draft = draft.copy(playback = draft.playback.withChallengeEnabled(enabled, mainPlayback))
                                if (enabled) challengeExpanded = true
                            },
                        )
                        if (challengeOn) {
                            WakeChallengeEditLink(
                                summary = wakeChallengeSummary(draft.playback),
                                expanded = challengeExpanded,
                                onClick = { challengeExpanded = !challengeExpanded },
                            )
                        }
                        AnimatedVisibility(challengeOn && challengeExpanded) {
                            WakeUpCheckStepsEditor(
                                playback = draft.playback,
                                onPlaybackChange = { draft = draft.copy(playback = it) },
                            )
                        }
                        if (mainPlayback.wakeUpCheckEnabled && !draft.challengeMatches(mainPlayback)) {
                            TextButton(onClick = { draft = draft.copy(playback = draft.playback.withChallengeOf(mainPlayback)) }) {
                                Text(stringResource(R.string.wake_extra_use_main_challenge), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (!isBefore) {
                    Text(
                        text = stringResource(R.string.wake_editor_extra_after_note),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFFFF0D5)).padding(12.dp),
                        color = Color(0xFF6A4A00),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = SilenceRed)) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.wake_editor_extra_delete), modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Medium)
                    }
                }
            }
            HorizontalDivider(color = Gold.copy(alpha = 0.22f))
            Row(
                modifier = Modifier.fillMaxWidth().background(BgCream).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { validMinutes?.let { onSave(draft.copy(minutesOffset = it)) } },
                    enabled = validMinutes != null,
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp).testTag("wake_extra_save"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                ) {
                    Text(stringResource(if (isNew) R.string.wake_extra_add else R.string.wake_extra_save), fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 50.dp)) {
                    Text(stringResource(R.string.wake_extra_cancel), color = TextMuted, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun ExtraAlarmMinuteButton(increase: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        color = Color.White,
        border = BorderStroke(1.dp, Gold.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(
                painter = painterResource(if (increase) R.drawable.ic_add else R.drawable.ic_remove),
                contentDescription = stringResource(if (increase) R.string.wake_editor_subalarm_increase else R.string.wake_editor_subalarm_decrease),
                tint = if (enabled) GreenPrimary else TextMuted.copy(alpha = 0.35f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
