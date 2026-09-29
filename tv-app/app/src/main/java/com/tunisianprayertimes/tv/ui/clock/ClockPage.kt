package com.tunisianprayertimes.tv.ui.clock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.time.ClockGuard
import com.tunisianprayertimes.time.ClockReading
import com.tunisianprayertimes.time.ClockSource
import com.tunisianprayertimes.time.ClockTrust
import com.tunisianprayertimes.time.TunisTime
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.AdminPage
import com.tunisianprayertimes.tv.ui.common.Digits
import com.tunisianprayertimes.tv.ui.common.FocusableListItem
import com.tunisianprayertimes.tv.ui.common.FocusableSurface
import com.tunisianprayertimes.tv.ui.common.Stepper
import com.tunisianprayertimes.tv.ui.common.adminPanel
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.common.onSurfaceText
import com.tunisianprayertimes.tv.ui.common.rtl
import com.tunisianprayertimes.tv.ui.kiosk.HealthDot
import com.tunisianprayertimes.tv.ui.kiosk.HealthLevel
import com.tunisianprayertimes.tv.ui.kiosk.HealthRow
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.launch

/** Why the clock page is on screen. */
enum class ClockPageMode {
    /** The clock cannot be right: no prayer times until it is set, and Back does not leave. */
    BLOCKING,

    /** The time is plausible but nothing confirmed it on a box in another zone: asked once, «لاحقًا» leaves. */
    QUESTION,

    /** The «الساعة» section of the settings. */
    SETTINGS,
}

/** What the clock page shows, read from the [ClockGuard] at each tick. */
data class ClockView(
    /** The screen's time, in Tunisia. */
    val now: LocalDateTime,
    val trust: ClockTrust,
    val source: ClockSource?,
    /** [ClockGuard.candidates]: the screen's time, then the device clock read as Tunisia's time when it differs. */
    val candidates: List<LocalDateTime>,
    /** Where the steppers start when the clock cannot be right ([ClockGuard.suggestedTime]). */
    val suggested: LocalDateTime,
    /** The device clock as the device shows it, with its zone: «2026-10-01 19:00 (Asia/Shanghai)». */
    val deviceTime: String,
    val deviceZone: ZoneId,
    val zoneDiffers: Boolean,
) {
    /** «هذا الوقت صحيح» can be accepted: the time is not confirmed, and not impossible by itself. */
    val confirmable: Boolean
        get() = trust != ClockTrust.TRUSTED && now.atZone(TunisTime.ZONE).toInstant().let { !it.isBefore(ClockGuard.EARLIEST) && !it.isAfter(ClockGuard.LATEST) }

    companion object {
        /** From the [reading] just taken: reading the guard again here would move its references from the composition. */
        fun of(guard: ClockGuard, reading: ClockReading, deviceTime: String): ClockView =
            ClockView(
                now = reading.now,
                trust = reading.trust,
                source = reading.source,
                candidates = guard.candidates(),
                suggested = guard.suggestedTime(),
                deviceTime = deviceTime,
                deviceZone = guard.deviceZone(),
                zoneDiffers = guard.zoneDiffers(),
            )
    }
}

/** The name of a candidate time: the first is the screen's own, the second the device clock as it is. */
internal fun candidateLabel(index: Int): String = if (index == 0) TvStrings.CLOCK_CANDIDATE_SCREEN else TvStrings.CLOCK_CANDIDATE_DEVICE

/** One line on the state of the clock: «مؤكَّدة (من الإنترنت)», «غير مؤكَّدة», or that it is wrong. */
internal fun clockStatus(trust: ClockTrust, source: ClockSource?): String = when {
    trust == ClockTrust.TRUSTED && source != null -> "${TvStrings.CLOCK_CONFIRMED} (${TvStrings.clockSource(source)})"
    trust == ClockTrust.IMPLAUSIBLE -> TvStrings.CLOCK_WRONG_TITLE
    else -> TvStrings.CLOCK_UNCONFIRMED
}

internal fun clockLevel(trust: ClockTrust): HealthLevel = when (trust) {
    ClockTrust.TRUSTED -> HealthLevel.GOOD
    ClockTrust.UNVERIFIED -> HealthLevel.WARNING
    ClockTrust.IMPLAUSIBLE -> HealthLevel.BAD
}

/** The clock's rows on the kiosk page and the phone's: its state and, on a box in another zone, that the zone does not matter. */
fun clockRows(trust: ClockTrust, source: ClockSource?, deviceZone: ZoneId, zoneDiffers: Boolean): List<HealthRow> = listOfNotNull(
    when (trust) {
        ClockTrust.TRUSTED -> HealthRow(HealthLevel.GOOD, TvStrings.clockGood(source ?: ClockSource.ADMIN))
        ClockTrust.UNVERIFIED -> HealthRow(HealthLevel.WARNING, TvStrings.CLOCK_ROW_UNVERIFIED, fix = TvStrings.CLOCK_ROW_UNVERIFIED_FIX)
        ClockTrust.IMPLAUSIBLE -> HealthRow(HealthLevel.BAD, TvStrings.CLOCK_ROW_WRONG, fix = TvStrings.CLOCK_ROW_UNVERIFIED_FIX)
    },
    HealthRow(HealthLevel.INFO, TvStrings.clockZoneInfo(deviceZone.id)).takeIf { zoneDiffers },
)

/**
 * The clock's page. On the right what is known and the quick answers (the candidate times, the box's
 * own date settings, the phone); on the left another time, one stepper per field, and «اعتماد هذا
 * الوقت». Every answer is the time now in Tunisia: the guard keeps it as a correction of the device
 * clock, and the prayer times follow it at once.
 *
 * [BLOCKING][ClockPageMode.BLOCKING] replaces the prayer times, whose absence is better than wrong
 * ones: Back does nothing. [QUESTION][ClockPageMode.QUESTION] comes once, when an admin is at the
 * remote; Back is «لاحقًا». In [SETTINGS][ClockPageMode.SETTINGS] Back is the settings' own.
 */
@Composable
fun ClockPage(
    mode: ClockPageMode,
    clock: ClockView,
    onPick: (LocalDateTime) -> Unit,
    onConfirm: () -> Unit,
    onOpenSystemSettings: (() -> Unit)?,
    onOpenPhone: (() -> Unit)?,
    onLater: () -> Unit = {},
) {
    BackHandler(enabled = mode != ClockPageMode.SETTINGS) { if (mode == ClockPageMode.QUESTION) onLater() }
    val start = if (clock.trust == ClockTrust.IMPLAUSIBLE) clock.suggested else clock.now
    var time by remember(mode) { mutableStateOf(start.withSecond(0).withNano(0)) }
    // In the settings an answer removes the row that had the focus (the candidates, «هذا الوقت صحيح»): it goes to a row that stays.
    val staysFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    fun answered(action: () -> Unit) {
        action()
        if (mode == ClockPageMode.SETTINGS) scope.launch {
            withFrameNanos { }
            runCatching { staysFocus.requestFocus() }
        }
    }
    val asking = mode == ClockPageMode.QUESTION || (mode == ClockPageMode.SETTINGS && clock.trust == ClockTrust.UNVERIFIED)
    val candidates = if (asking) clock.candidates else emptyList()
    val title = when (mode) {
        ClockPageMode.BLOCKING -> TvStrings.CLOCK_WRONG_TITLE
        ClockPageMode.QUESTION -> TvStrings.CLOCK_QUESTION_TITLE
        ClockPageMode.SETTINGS -> TvStrings.SECTION_CLOCK
    }
    AdminPage(
        title = title,
        modifier = if (mode == ClockPageMode.SETTINGS) Modifier else Modifier.background(Midad.Ground).padding(horizontal = 48.dp, vertical = 27.dp),
        hints = if (mode == ClockPageMode.SETTINGS) listOf(TvStrings.HINT_BACK_TO_SETTINGS) else emptyList(),
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                when (mode) {
                    ClockPageMode.BLOCKING -> Text(TvStrings.CLOCK_WRONG_HINT, style = midadStyle(17.sp, color = Midad.Muted, lineHeight = 1.5f).rtl())
                    ClockPageMode.QUESTION -> Text(TvStrings.CLOCK_QUESTION_HINT, style = midadStyle(15.sp, color = Midad.Muted, lineHeight = 1.5f).rtl())
                    ClockPageMode.SETTINGS -> ClockStatus(clock, withHint = candidates.isEmpty())
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(TvStrings.CLOCK_DEVICE_TIME, style = midadStyle(14.sp, color = Midad.Muted))
                    // Its own paragraph, left to right: "2001-01-01 00:03 (Africa/Tunis)" must not turn around.
                    Text(
                        clock.deviceTime,
                        style = midadStyle(15.sp, FontWeight.Medium, if (clock.trust == ClockTrust.IMPLAUSIBLE) Midad.Alert else Midad.Text)
                            .copy(textDirection = TextDirection.Ltr),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                candidates.forEachIndexed { index, candidate ->
                    CandidateRow(
                        label = candidateLabel(index),
                        time = candidate,
                        onClick = { answered { onPick(candidate) } },
                        modifier = Modifier.initialFocus(mode == ClockPageMode.QUESTION && index == 0),
                    )
                }
                if (mode == ClockPageMode.BLOCKING && clock.confirmable) FocusableListItem(TvStrings.CLOCK_CONFIRM, onClick = onConfirm)
                if (mode == ClockPageMode.SETTINGS && clock.trust == ClockTrust.IMPLAUSIBLE && clock.confirmable) {
                    FocusableListItem(TvStrings.CLOCK_CONFIRM, onClick = { answered(onConfirm) })
                }
                if (onOpenSystemSettings != null && mode != ClockPageMode.QUESTION) {
                    // On a box in another zone, setting only the time there brings the difference back.
                    if (clock.zoneDiffers) Text(TvStrings.CLOCK_ZONE_FIRST, style = midadStyle(14.sp, color = Midad.Muted, lineHeight = 1.45f).rtl())
                    FocusableListItem(TvStrings.CLOCK_OPEN_SETTINGS, onClick = onOpenSystemSettings)
                }
                if (onOpenPhone != null) {
                    FocusableListItem(
                        TvStrings.CLOCK_FROM_PHONE,
                        onClick = onOpenPhone,
                        modifier = Modifier.focusRequester(staysFocus).initialFocus(mode == ClockPageMode.SETTINGS),
                    )
                }
                if (mode == ClockPageMode.QUESTION) FocusableListItem(TvStrings.CLOCK_LATER, onClick = onLater)
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().adminPanel(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (mode != ClockPageMode.BLOCKING) Text(TvStrings.CLOCK_OTHER_TIME, style = midadStyle(15.sp, FontWeight.Medium, Midad.Muted))
                // What «اعتماد هذا الوقت» will set, written out: the weekday is the easiest check.
                Text(TvStrings.gregorianDate(time.toLocalDate()), style = midadStyle(17.sp, color = Midad.Muted))
                Digits(TvStrings.hm(time.toLocalTime()), midadStyle(36.sp, FontWeight.SemiBold))
                TimeRow(TvStrings.CLOCK_MONTH, TvStrings.monthYear(time.toLocalDate()), { time = time.minusMonths(1) }, { time = time.plusMonths(1) })
                TimeRow(
                    TvStrings.CLOCK_DATE, time.dayOfMonth.toString(), { time = time.minusDays(1) }, { time = time.plusDays(1) },
                    // Without a clock battery the date is the first thing to set.
                    minusModifier = Modifier.initialFocus(mode == ClockPageMode.BLOCKING),
                )
                TimeRow(TvStrings.CLOCK_HOUR, twoDigits(time.hour), { time = time.minusHours(1) }, { time = time.plusHours(1) })
                TimeRow(TvStrings.CLOCK_MINUTE, twoDigits(time.minute), { time = time.minusMinutes(1) }, { time = time.plusMinutes(1) })
                Spacer(Modifier.weight(1f))
                // Raised off the panel, as the rows above it: the page's one button on this side.
                FocusableSurface(
                    onClick = { answered { onPick(time) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp),
                    rest = Midad.SurfaceRaised,
                ) { focused ->
                    Text(TvStrings.CLOCK_SET_HERE, style = midadStyle(17.sp, if (focused) FontWeight.SemiBold else FontWeight.Medium, onSurfaceText(focused)).rtl())
                }
            }
        }
    }
}

/**
 * In the settings: the screen's time now, in Tunisia, and whether it is confirmed. [withHint] adds
 * how it gets confirmed; while candidates are offered they say it, and the page has no room for both.
 */
@Composable
private fun ClockStatus(clock: ClockView, withHint: Boolean) {
    Text(
        "${TvStrings.CLOCK_TIME_IN_TUNIS} · ${TvStrings.gregorianDate(clock.now.toLocalDate())}",
        style = midadStyle(14.sp, color = Midad.Muted).rtl(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Digits(TvStrings.hms(clock.now.toLocalTime()), midadStyle(30.sp, FontWeight.SemiBold))
        HealthDot(clockLevel(clock.trust))
        Text(clockStatus(clock.trust, clock.source), style = midadStyle(17.sp, FontWeight.Medium).rtl())
    }
    if (withHint && clock.trust != ClockTrust.IMPLAUSIBLE) {
        Text(TvStrings.CLOCK_SETTINGS_HINT, style = midadStyle(14.sp, color = Midad.Muted, lineHeight = 1.45f).rtl())
    }
}

/** A candidate time: what it is on the right, the time itself on the left, large enough to compare with a watch. */
@Composable
private fun CandidateRow(label: String, time: LocalDateTime, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 50.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
    ) { focused ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = midadStyle(16.sp, if (focused) FontWeight.SemiBold else FontWeight.Normal, onSurfaceText(focused)).rtl(),
                modifier = Modifier.weight(1f),
            )
            Digits(TvStrings.hm(time.toLocalTime()), midadStyle(26.sp, FontWeight.SemiBold, onSurfaceText(focused)))
        }
    }
}

/** A field of the time on a row of the panel, as in the settings tables: its name, then − value +. */
@Composable
private fun TimeRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, minusModifier: Modifier = Modifier) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Midad.SurfaceRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = midadStyle(16.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
        Stepper(value = value, onMinus = onMinus, onPlus = onPlus, valueWidth = 150.dp, minusModifier = minusModifier)
    }
}

private fun twoDigits(value: Int): String = String.format(Locale.ROOT, "%02d", value)
