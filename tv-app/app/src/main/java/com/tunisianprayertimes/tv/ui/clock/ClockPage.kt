package com.tunisianprayertimes.tv.ui.clock

import android.os.SystemClock
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import com.tunisianprayertimes.tv.ui.common.onSurfaceMuted
import com.tunisianprayertimes.tv.ui.common.onSurfaceText
import com.tunisianprayertimes.tv.ui.common.rtl
import com.tunisianprayertimes.tv.ui.kiosk.HealthDot
import com.tunisianprayertimes.tv.ui.kiosk.HealthLevel
import com.tunisianprayertimes.tv.ui.kiosk.HealthRow
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoField
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
    /**
     * The zone must be set to Tunisia's first: it reads another time now, or it does part of the year
     * ([ClockGuard.zoneKeepsTunisTime]), which keeps the clock unconfirmed even while today agrees.
     */
    val zoneFirst: Boolean = zoneDiffers,
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
                zoneFirst = guard.zoneDiffers() || !guard.zoneKeepsTunisTime(),
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

/**
 * The clock's rows on the kiosk page and the phone's: its state and, on a box in another zone whose
 * time is confirmed, that the zone does not matter. Unconfirmed, the zone may be the very problem.
 */
fun clockRows(trust: ClockTrust, source: ClockSource?, deviceZone: ZoneId, zoneDiffers: Boolean): List<HealthRow> = listOfNotNull(
    when (trust) {
        ClockTrust.TRUSTED -> HealthRow(HealthLevel.GOOD, TvStrings.clockGood(source ?: ClockSource.ADMIN))
        ClockTrust.UNVERIFIED -> HealthRow(HealthLevel.WARNING, TvStrings.CLOCK_ROW_UNVERIFIED, fix = TvStrings.CLOCK_ROW_UNVERIFIED_FIX)
        ClockTrust.IMPLAUSIBLE -> HealthRow(HealthLevel.BAD, TvStrings.CLOCK_ROW_WRONG, fix = TvStrings.CLOCK_ROW_UNVERIFIED_FIX)
    },
    clockZoneNote(trust, deviceZone, zoneDiffers)?.let { HealthRow(HealthLevel.INFO, it) },
)

/** «لا أثر لها» about the device's zone, only once the time is confirmed: before that, it may be set to another country's time. */
internal fun clockZoneNote(trust: ClockTrust, deviceZone: ZoneId, zoneDiffers: Boolean): String? =
    TvStrings.clockZoneInfo(deviceZone.id).takeIf { zoneDiffers && trust == ClockTrust.TRUSTED }

/**
 * The clock's page. On the right what is known and the quick answers (the candidate times, the box's
 * own date settings, the phone); on the left another time, one stepper per field, and «اعتماد هذا
 * الوقت». Every answer is the time now in Tunisia: the guard keeps it as a correction of the device
 * clock, and the prayer times follow it at once.
 *
 * [BLOCKING][ClockPageMode.BLOCKING] replaces the prayer times, whose absence is better than wrong
 * ones: Back does nothing. [QUESTION][ClockPageMode.QUESTION] comes once, when an admin is at the
 * remote; Back is «لاحقًا», where the focus starts. In [SETTINGS][ClockPageMode.SETTINGS] Back is the
 * settings' own.
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
    // The steppers' time runs on by itself: its seconds go on, and «اعتماد هذا الوقت» sets the time of
    // the moment it is pressed. It does not follow the screen's time, which an answer (the phone's, the
    // network's, this page's own) moves while the page stays open.
    var stepped by remember(mode) {
        mutableStateOf(SteppedTime(if (clock.trust == ClockTrust.IMPLAUSIBLE) clock.suggested else clock.now, SystemClock.elapsedRealtime()))
    }
    val time = stepped.at(SystemClock.elapsedRealtime())
    fun step(field: ChronoField, by: Int) {
        stepped = stepped.step(field, by, SystemClock.elapsedRealtime())
    }
    // The question comes up under the presses that opened the settings: those still coming are not an answer.
    val okBurst = remember(mode) { if (mode == ClockPageMode.QUESTION) OkBurst(SystemClock.uptimeMillis()) else null }
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
        Row(Modifier.fillMaxSize().ignoringOkBurst(okBurst), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
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
                    )
                }
                // With what it confirms, date and all: the device's line above is in the box's own zone.
                if (mode == ClockPageMode.BLOCKING && clock.confirmable) CandidateRow(TvStrings.CLOCK_CONFIRM, clock.now, onClick = onConfirm, withDate = true)
                if (mode == ClockPageMode.SETTINGS && clock.trust == ClockTrust.IMPLAUSIBLE && clock.confirmable) {
                    FocusableListItem(TvStrings.CLOCK_CONFIRM, onClick = { answered(onConfirm) })
                }
                if (onOpenSystemSettings != null && mode != ClockPageMode.QUESTION) {
                    // On a box in another zone, setting only the time there brings the difference back.
                    if (clock.zoneFirst) Text(TvStrings.CLOCK_ZONE_FIRST, style = midadStyle(14.sp, color = Midad.Muted, lineHeight = 1.45f).rtl())
                    FocusableListItem(TvStrings.CLOCK_OPEN_SETTINGS, onClick = onOpenSystemSettings)
                }
                if (onOpenPhone != null) {
                    FocusableListItem(
                        TvStrings.CLOCK_FROM_PHONE,
                        onClick = onOpenPhone,
                        modifier = Modifier.focusRequester(staysFocus).initialFocus(mode == ClockPageMode.SETTINGS),
                    )
                }
                // The first focus: an answer is a deliberate move away from it.
                if (mode == ClockPageMode.QUESTION) FocusableListItem(TvStrings.CLOCK_LATER, onClick = onLater, modifier = Modifier.initialFocus())
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().adminPanel(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (mode != ClockPageMode.BLOCKING) Text(TvStrings.CLOCK_OTHER_TIME, style = midadStyle(15.sp, FontWeight.Medium, Midad.Muted))
                // What «اعتماد هذا الوقت» will set, written out: the weekday is the easiest check.
                Text(TvStrings.gregorianDate(time.toLocalDate()), style = midadStyle(17.sp, color = Midad.Muted))
                Digits(TvStrings.hm(time.toLocalTime()), midadStyle(36.sp, FontWeight.SemiBold))
                TimeRow(TvStrings.CLOCK_MONTH, TvStrings.monthYear(time.toLocalDate()), { step(ChronoField.MONTH_OF_YEAR, -1) }, { step(ChronoField.MONTH_OF_YEAR, 1) })
                TimeRow(
                    TvStrings.CLOCK_DATE, time.dayOfMonth.toString(), { step(ChronoField.DAY_OF_MONTH, -1) }, { step(ChronoField.DAY_OF_MONTH, 1) },
                    // Without a clock battery the date is the first thing to set.
                    minusModifier = Modifier.initialFocus(mode == ClockPageMode.BLOCKING),
                )
                TimeRow(TvStrings.CLOCK_HOUR, twoDigits(time.hour), { step(ChronoField.HOUR_OF_DAY, -1) }, { step(ChronoField.HOUR_OF_DAY, 1) })
                TimeRow(TvStrings.CLOCK_MINUTE, twoDigits(time.minute), { step(ChronoField.MINUTE_OF_HOUR, -1) }, { step(ChronoField.MINUTE_OF_HOUR, 1) })
                Spacer(Modifier.weight(1f))
                // Raised off the panel, as the rows above it: the page's one button on this side.
                FocusableSurface(
                    onClick = { answered { onPick(stepped.at(SystemClock.elapsedRealtime())) } },
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

/**
 * A candidate time: what it is on the right, the time itself on the left, large enough to compare with
 * a watch. [withDate] writes its date under what it is, where the date is in doubt.
 */
@Composable
private fun CandidateRow(label: String, time: LocalDateTime, onClick: () -> Unit, modifier: Modifier = Modifier, withDate: Boolean = false) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 50.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
    ) { focused ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = midadStyle(16.sp, if (focused) FontWeight.SemiBold else FontWeight.Normal, onSurfaceText(focused)).rtl())
                if (withDate) Text(TvStrings.gregorianDate(time.toLocalDate()), style = midadStyle(14.sp, color = onSurfaceMuted(focused)).rtl())
            }
            Digits(TvStrings.hm(time.toLocalTime()), midadStyle(26.sp, FontWeight.SemiBold, onSurfaceText(focused)))
        }
    }
}

/**
 * [time] with one field stepped [by] on its own: the minute wraps within its hour, the hour within its
 * day, the day within its month, so setting one never moves another. Only the month carries into the
 * year, which has no row of its own.
 */
internal fun stepTime(time: LocalDateTime, field: ChronoField, by: Int): LocalDateTime {
    if (field == ChronoField.MONTH_OF_YEAR) return time.plusMonths(by.toLong())
    val range = time.range(field)
    val size = range.maximum - range.minimum + 1
    return time.with(field, range.minimum + Math.floorMod(time.getLong(field) - range.minimum + by, size))
}

/** The steppers' time: [base] at [since] (elapsed millis since boot), running on from there. */
internal class SteppedTime(private val base: LocalDateTime, private val since: Long) {
    fun at(elapsed: Long): LocalDateTime = base.plus(Duration.ofMillis(elapsed - since))

    /** [field] stepped [by] on its own ([stepTime]), at [elapsed]. */
    fun step(field: ChronoField, by: Int, elapsed: Long): SteppedTime = SteppedTime(stepTime(at(elapsed), field, by), elapsed)
}

/**
 * The OK presses still coming as the clock question appears: the ones that opened the settings (five
 * OKs, a double press on onboarding's «تأكيد»). Every OK is ignored until one is pressed after
 * [QUIET_MILLIS] without any, from the moment the question was shown ([shownAt], uptime millis).
 */
internal class OkBurst(shownAt: Long) {
    private var lastAt = shownAt
    private var settled = false

    /** Whether the OK key event at [at] (uptime millis; [down] when pressed, else released) is ignored. */
    fun ignores(down: Boolean, at: Long): Boolean {
        if (settled) return false
        if (down && at - lastAt >= QUIET_MILLIS) {
            settled = true
            return false
        }
        lastAt = at
        return true
    }

    companion object {
        const val QUIET_MILLIS = 600L
    }
}

/** Swallows OK (or Enter) before any element of the page sees it, while [burst] ignores it; without a burst, nothing. */
private fun Modifier.ignoringOkBurst(burst: OkBurst?): Modifier = if (burst == null) this else onPreviewKeyEvent { event ->
    val ok = event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.NumPadEnter
    ok && burst.ignores(event.type == KeyEventType.KeyDown, event.nativeKeyEvent.eventTime)
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
