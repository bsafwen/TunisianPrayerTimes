package com.tunisianprayertimes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.DEFAULT_AWAKE_CHECK_DELAY_MINUTES
import com.tunisianprayertimes.MathDifficulty
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerRelativeOffset
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrayerWakeSubAlarm
import com.tunisianprayertimes.R
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.WakeMainAlarmConfig
import com.tunisianprayertimes.WakeMainAlarmMode
import com.tunisianprayertimes.WakePlaybackOptions
import com.tunisianprayertimes.WakeUpCheckStep
import com.tunisianprayertimes.WakeUpCheckType
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

/** How hard the suggested Fajr alarm works to get the user up, in the order the card lists them. */
internal enum class FajrAlarmPersistence { GENTLE, BALANCED, PERSISTENT, STRICT }

/** The strict level's soft wake-up before the alarm, and its three returns after it, in minutes. */
private const val StrictPreAlarmMinutes = 10
private val StrictReturnMinutes = listOf(5, 10, 15)

/** Minutes before Fajr the card offers; 20 is also the editor's default for a prayer alarm. */
private val FajrSuggestionMinutesBefore = listOf(10, 20, 30, 45, 60)
private const val FajrSuggestionDefaultMinutes = 20

/** Fixed-time and timer alarms carry FAJR only as a placeholder, so only an alarm tied to Fajr counts. */
internal fun List<PrayerWakeConfig>.hasFajrWakeAlarm(): Boolean = any { alarm ->
    // The one-night suhoor alarm wakes for a fast, not for Fajr every day.
    alarm.prayer == Prayer.FAJR && alarm.mainAlarm.mode == WakeMainAlarmMode.PRAYER_RELATIVE &&
        alarm.id != com.tunisianprayertimes.fasting.FastingReminder.SUHOOR_ALARM_ID
}

/** The alarm the card adds: every day, [minutesBefore] before Fajr, with the playback of [persistence]. */
internal fun fajrSuggestionAlarm(minutesBefore: Int, persistence: FajrAlarmPersistence): PrayerWakeConfig {
    // Same sound as an alarm added from the + button; its defaults already ask "are you awake?".
    val defaults = WakePlaybackOptions(ringtone = RingtonePreset.ADHAN_MADINAH_MARWAN_QASSAS)
    val playback = when (persistence) {
        FajrAlarmPersistence.GENTLE -> defaults.copy(awakeCheckEnabled = false)
        FajrAlarmPersistence.BALANCED -> defaults
        FajrAlarmPersistence.PERSISTENT -> defaults.withMathChallenge(MathDifficulty.INTERMEDIATE)
        FajrAlarmPersistence.STRICT -> defaults.withMathChallenge(MathDifficulty.HARD)
    }
    // Strict wakes the user softly first, then keeps coming back with the same hard problem.
    val subAlarms = if (persistence == FajrAlarmPersistence.STRICT) {
        listOf(
            PrayerWakeSubAlarm(
                id = UUID.randomUUID().toString(),
                minutesOffset = StrictPreAlarmMinutes,
                direction = OffsetDirection.BEFORE,
                playback = defaults.copy(ringtone = RingtonePreset.GENTLE_CHIME, awakeCheckEnabled = false),
            ),
        ) + StrictReturnMinutes.map { minutes ->
            PrayerWakeSubAlarm(
                id = UUID.randomUUID().toString(),
                minutesOffset = minutes,
                direction = OffsetDirection.AFTER,
                playback = playback,
            )
        }
    } else {
        emptyList()
    }
    return PrayerWakeConfig(
        id = UUID.randomUUID().toString(),
        enabled = true,
        prayer = Prayer.FAJR,
        mainAlarm = WakeMainAlarmConfig(
            mode = WakeMainAlarmMode.PRAYER_RELATIVE,
            prayerOffset = PrayerRelativeOffset(-minutesBefore),
        ),
        playback = playback,
        subAlarms = subAlarms,
    )
}

private fun WakePlaybackOptions.withMathChallenge(difficulty: MathDifficulty): WakePlaybackOptions = copy(
    wakeUpCheckEnabled = true,
    wakeUpCheckType = WakeUpCheckType.MATH,
    mathDifficulty = difficulty,
    wakeUpCheckSteps = listOf(WakeUpCheckStep(WakeUpCheckType.MATH, difficulty)),
)

/**
 * A one-line nudge on the alarms tab for someone without a Fajr alarm; tapping it opens
 * [FajrAlarmSetupSheet]. Closing it is for good; the caller remembers that.
 */
@Composable
internal fun FajrAlarmSuggestionRow(
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(Color.White)
            .border(BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.35f)), shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(GoldLight.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_prayer_fajr_twilight),
                contentDescription = null,
                tint = GreenPrimaryDark,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = stringResource(R.string.wake_fajr_suggestion_row),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = PrayerNameColor,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        )
        IconButton(onClick = onClose) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.wake_fajr_suggestion_close),
                tint = TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The same offer inside the alarms tab's start card, for a user with no alarm yet: the first and
 * strongest option, above the plain presets. It opens [FajrAlarmSetupSheet] too.
 */
@Composable
internal fun FajrAlarmStartOption(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GreenPrimary)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_prayer_fajr_twilight),
                contentDescription = null,
                tint = GoldLight,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = stringResource(R.string.wake_fajr_suggestion_row),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = stringResource(R.string.wake_fajr_suggestion_start_subtitle),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The two questions behind [FajrAlarmSuggestionRow] and [FajrAlarmStartOption], then one tap adds the alarm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FajrAlarmSetupSheet(
    delegationId: Int,
    nowMillis: Long,
    onAdd: (PrayerWakeConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var minutesBefore by rememberSaveable { mutableIntStateOf(FajrSuggestionDefaultMinutes) }
    var persistence by rememberSaveable { mutableStateOf(FajrAlarmPersistence.BALANCED) }
    // A second tap while the sheet slides away must not add another alarm.
    var added by remember { mutableStateOf(false) }
    val nextRing = remember(delegationId, minutesBefore, nowMillis) {
        nextWakeAlarmTrigger(
            context,
            delegationId,
            fajrSuggestionAlarm(minutesBefore, FajrAlarmPersistence.BALANCED),
        )?.triggerAtMillis
    }
    val awakeCheckDelay = pluralStringResource(
        R.plurals.prayer_silence_duration_minutes,
        DEFAULT_AWAKE_CHECK_DELAY_MINUTES,
        DEFAULT_AWAKE_CHECK_DELAY_MINUTES,
    )

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
                .nestedScroll(rememberSheetScrollGuard(scrollState))
                .verticalScroll(scrollState)
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.wake_fajr_suggestion_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor,
                modifier = Modifier.semantics { heading() },
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FajrSuggestionQuestion(stringResource(R.string.wake_fajr_suggestion_minutes_title))
                WakeSegmentedChoice(
                    options = FajrSuggestionMinutesBefore.map { minutes ->
                        stringResource(R.string.wake_editor_minutes_short, minutes)
                    },
                    selectedIndex = FajrSuggestionMinutesBefore.indexOf(minutesBefore),
                    onSelect = { index -> minutesBefore = FajrSuggestionMinutesBefore[index] },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FajrSuggestionQuestion(stringResource(R.string.wake_fajr_suggestion_persistence_title))
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FajrAlarmPersistence.entries.forEach { level ->
                        FajrPersistenceOption(
                            level = level,
                            title = stringResource(level.titleRes()),
                            body = stringResource(level.bodyRes(), awakeCheckDelay),
                            selected = level == persistence,
                            onClick = { persistence = level },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (nextRing != null) {
                    val fajrAt = nextRing + minutesBefore * 60_000L
                    Text(
                        text = stringResource(
                            if (isSameDay(nextRing, nowMillis)) {
                                R.string.wake_fajr_suggestion_preview_today
                            } else {
                                R.string.wake_fajr_suggestion_preview_tomorrow
                            },
                            formatClock(nextRing),
                            formatClock(fajrAt),
                        ),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GreenPrimaryDark,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(
                    onClick = {
                        if (!added) {
                            added = true
                            onAdd(fajrSuggestionAlarm(minutesBefore, persistence))
                            coroutineScope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.wake_fajr_suggestion_add),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FajrSuggestionQuestion(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = TextDark,
    )
}

@Composable
private fun FajrPersistenceOption(
    level: FajrAlarmPersistence,
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .background(if (selected) GreenPrimary.copy(alpha = 0.10f) else Color.White)
            .border(
                BorderStroke(
                    if (selected) 1.5.dp else 1.dp,
                    if (selected) GreenPrimary.copy(alpha = 0.70f) else Gold.copy(alpha = 0.25f),
                ),
                shape,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PersistenceLevelBars(filled = level.ordinal + 1, selected = selected)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) GreenPrimaryDark else TextDark,
            )
            Text(
                text = body,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = if (selected) GreenPrimaryDark.copy(alpha = 0.80f) else TextMuted,
            )
        }
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) GreenPrimary else Color.Transparent)
                .border(BorderStroke(1.5.dp, if (selected) GreenPrimary else TextMuted.copy(alpha = 0.45f)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** One rising bar per level, [filled] of them lit: how insistent the level is, at a glance. */
@Composable
private fun PersistenceLevelBars(filled: Int, selected: Boolean) {
    val litColor = if (selected) GreenPrimary else Gold
    val unlitColor = TextMuted.copy(alpha = 0.20f)
    val bars = FajrAlarmPersistence.entries.size
    Canvas(modifier = Modifier.size(width = 26.dp, height = 18.dp)) {
        val barWidth = 4.5.dp.toPx()
        val gap = (size.width - barWidth * bars) / (bars - 1)
        val radius = CornerRadius(barWidth / 2, barWidth / 2)
        // Bars rise towards the reading end, so the shortest sits where an RTL line starts.
        for (bar in 0 until bars) {
            val barHeight = size.height * (0.4f + 0.6f * bar / (bars - 1))
            val left = if (layoutDirection == LayoutDirection.Rtl) {
                size.width - barWidth - bar * (barWidth + gap)
            } else {
                bar * (barWidth + gap)
            }
            drawRoundRect(
                color = if (bar < filled) litColor else unlitColor,
                topLeft = Offset(left, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

private fun FajrAlarmPersistence.titleRes(): Int = when (this) {
    FajrAlarmPersistence.GENTLE -> R.string.wake_fajr_suggestion_gentle_title
    FajrAlarmPersistence.BALANCED -> R.string.wake_fajr_suggestion_balanced_title
    FajrAlarmPersistence.PERSISTENT -> R.string.wake_fajr_suggestion_persistent_title
    FajrAlarmPersistence.STRICT -> R.string.wake_fajr_suggestion_strict_title
}

private fun FajrAlarmPersistence.bodyRes(): Int = when (this) {
    FajrAlarmPersistence.GENTLE -> R.string.wake_fajr_suggestion_gentle_body
    FajrAlarmPersistence.BALANCED -> R.string.wake_fajr_suggestion_balanced_body
    FajrAlarmPersistence.PERSISTENT -> R.string.wake_fajr_suggestion_persistent_body
    FajrAlarmPersistence.STRICT -> R.string.wake_fajr_suggestion_strict_body
}

private fun formatClock(timeInMillis: Long): String {
    val calendar = Calendar.getInstance().apply { this.timeInMillis = timeInMillis }
    return String.format(Locale.ROOT, "%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
}

private fun isSameDay(firstMillis: Long, secondMillis: Long): Boolean {
    val first = Calendar.getInstance().apply { timeInMillis = firstMillis }
    val second = Calendar.getInstance().apply { timeInMillis = secondMillis }
    return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}

/** The level's name, «لطيف» to «صارم», for a line that recalls the choice. */
internal fun FajrAlarmPersistence.title(context: android.content.Context): String = context.getString(titleRes())

/**
 * The suhoor alarm's two questions, as the Fajr alarm asks them: asked once, after which the fasting
 * notification sets the alarm in one tap. [fajrAtMillis] is the night's Fajr for the preview, or null when only
 * the choice is changed. An alarm needs the exact-alarm access to ring on time, so the sheet asks for it first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SuhoorAlarmSetupSheet(
    fajrAtMillis: Long?,
    initialMinutes: Int,
    initialPersistence: FajrAlarmPersistence,
    buttonLabel: String,
    onSave: (Int, FajrAlarmPersistence) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    // Tonight, only the minutes whose ring is still ahead; the nearest to the reader's choice is preselected.
    val minuteOptions = remember(fajrAtMillis) {
        val all = com.tunisianprayertimes.fasting.FastingReminder.SUHOOR_MINUTES
        fajrAtMillis?.let { com.tunisianprayertimes.fasting.FastingReminder.suhoorOptions(java.time.Instant.ofEpochMilli(it)) }
            ?.takeIf { it.isNotEmpty() } ?: all
    }
    var minutesBefore by rememberSaveable {
        mutableIntStateOf(if (initialMinutes in minuteOptions) initialMinutes else minuteOptions.last())
    }
    var persistence by rememberSaveable { mutableStateOf(initialPersistence) }
    var saved by remember { mutableStateOf(false) }
    var canRing by remember { mutableStateOf(com.tunisianprayertimes.wake.WakeAlarmScheduler.canScheduleExactAlarms(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        canRing = com.tunisianprayertimes.wake.WakeAlarmScheduler.canScheduleExactAlarms(context)
        onPauseOrDispose { }
    }
    val awakeCheckDelay = pluralStringResource(
        R.plurals.prayer_silence_duration_minutes,
        DEFAULT_AWAKE_CHECK_DELAY_MINUTES,
        DEFAULT_AWAKE_CHECK_DELAY_MINUTES,
    )
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
                .nestedScroll(rememberSheetScrollGuard(scrollState))
                .verticalScroll(scrollState)
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "منبه السحور",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor,
                modifier = Modifier.semantics { heading() },
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FajrSuggestionQuestion("كم دقيقة قبل الفجر يرنّ؟")
                WakeSegmentedChoice(
                    options = minuteOptions.map { minutes ->
                        stringResource(R.string.wake_editor_minutes_short, minutes)
                    },
                    selectedIndex = minuteOptions.indexOf(minutesBefore),
                    onSelect = { index -> minutesBefore = minuteOptions[index] },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FajrSuggestionQuestion(stringResource(R.string.wake_fajr_suggestion_persistence_title))
                Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FajrAlarmPersistence.entries.forEach { level ->
                        FajrPersistenceOption(
                            level = level,
                            title = stringResource(level.titleRes()),
                            body = stringResource(level.bodyRes(), awakeCheckDelay),
                            selected = level == persistence,
                            onClick = { persistence = level },
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fajrAtMillis != null) {
                    Text(
                        text = "يرنّ الساعة ${formatClock(fajrAtMillis - minutesBefore * 60_000L)} • الفجر ${formatClock(fajrAtMillis)}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GreenPrimaryDark,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!canRing) {
                    Text(
                        text = "ليرنّ المنبه في وقته، اسمح للتطبيق بالمنبهات والتذكيرات.",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = TextMuted,
                    )
                }
                Button(
                    onClick = {
                        if (!canRing) {
                            val app = android.net.Uri.parse("package:" + context.packageName)
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(app)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        } else if (!saved) {
                            saved = true
                            onSave(minutesBefore, persistence)
                            coroutineScope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                ) {
                    Text(
                        text = if (canRing) buttonLabel else "اسمح بالمنبهات",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
