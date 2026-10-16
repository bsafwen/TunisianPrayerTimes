package com.tunisianprayertimes.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.R
import com.tunisianprayertimes.engine.RuleName
import com.tunisianprayertimes.place.PrayerPlace
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * The markers of the list of times (plan 6.11, D8): «قبل منتصف الليل» or «بعد منتصف الليل» for a time on another
 * civil date in the phone's clock, and «تقديري» for a time a rule set, which explains in one line which rule and why.
 */
@Composable
internal fun PrayerTimeMarkers(prayerName: String, anchor: PrayerTimelineAnchor) {
    val shift = prayerDayOffset(anchor.minutes)
    if (shift != 0) {
        MarkerChip(stringResource(if (shift < 0) R.string.marker_before_midnight else R.string.marker_after_midnight), "marker_day_${anchor.prayer.name}")
    }
    if (anchor.estimated) {
        var explain by remember { mutableStateOf(false) }
        MarkerChip(stringResource(R.string.marker_estimated), "marker_estimated_${anchor.prayer.name}", onClick = { explain = true })
        if (explain) {
            val context = LocalContext.current
            AlertDialog(
                onDismissRequest = { explain = false },
                confirmButton = { TextButton(onClick = { explain = false }) { Text(stringResource(R.string.marker_ok)) } },
                text = { Text(estimatedExplanation(context, prayerName, anchor.rule), fontSize = 15.sp, lineHeight = 22.sp) },
            )
        }
    }
}

@Composable
private fun MarkerChip(text: String, tag: String, onClick: (() -> Unit)? = null) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = PrayerSilencePalette.GoldAccent,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(PrayerSilencePalette.TintedStrip)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag(tag),
    )
}

/** The one-line reason of an estimated time (6.11); the wording is settled with the mockups. */
internal fun estimatedExplanation(context: Context, prayerName: String, rule: RuleName?): String = when (rule) {
    RuleName.MIN_DAY_NIGHT_5H, RuleName.POLAR_ASR_MIDPOINT, RuleName.DIYANET_CLAMP, RuleName.IRN_MECCA_CYCLE ->
        context.getString(R.string.estimated_polar, prayerName)
    RuleName.ISHA_LATEST -> context.getString(R.string.estimated_isha_latest, prayerName)
    RuleName.VALIDITY_ENVELOPE -> context.getString(R.string.estimated_envelope, prayerName)
    RuleName.ADJUSTMENT_HELD -> context.getString(R.string.estimated_adjustment, prayerName)
    RuleName.HELD_IN_ORDER -> context.getString(R.string.estimated_order, prayerName)
    RuleName.NIGHT_LOWEST_POINT -> context.getString(R.string.estimated_night_middle)
    null -> context.getString(R.string.estimated_short_night, prayerName, context.getString(R.string.estimated_rule_generic))
    else -> context.getString(R.string.estimated_short_night, prayerName, shortNightRuleName(rule))
}

private fun shortNightRuleName(rule: RuleName): String = when (rule) {
    RuleName.ANGLE_BASED, RuleName.ANGLE_BASED_ABSENT_NIGHTS -> "نسبة الزاوية من الليل"
    RuleName.LOCAL_RELATIVE_ESTIMATION -> "التقدير النسبي المحلي"
    RuleName.ONE_SEVENTH -> "سُبع الليل"
    RuleName.MIDDLE_OF_NIGHT -> "منتصف الليل"
    RuleName.NEAREST_DAY -> "أقرب يوم تظهر فيه العلامة"
    RuleName.CLOCK_CAPS -> "حدود بالساعة"
    RuleName.MOONSIGHTING_SEASONAL -> "منحنى لجنة رؤية الهلال"
    RuleName.DIYANET_TAKDIR -> "تقدير رئاسة الشؤون الدينية"
    RuleName.DUM_RF -> "قاعدة الإدارة الدينية لمسلمي روسيا"
    RuleName.DUM_RT -> "قاعدة الإدارة الدينية لمسلمي تتارستان"
    RuleName.SUMMER_FORM -> "الصيغة الصيفية للطريقة"
    RuleName.IRN_SOUTHERN_SUMMER -> "قاعدة المجلس الإسلامي في النرويج"
    else -> "الليالي القصيرة"
}

/** The lines above the list (6.11): the official zone the place maps to, and the place's UTC offset when the phone keeps another zone. */
internal data class TimesListNotes(val zoneName: String?, val placeOffset: String?)

internal fun timesListNotes(context: Context, date: LocalDate, phoneZone: ZoneId = ZoneId.systemDefault()): TimesListNotes {
    val place = PrayerTimesRepository.place(context)
    val zoneName = (place as? PrayerPlace.Coordinates)?.let { coords ->
        runCatching {
            val times = PrayerTimesRepository.placeTimesFor(context)
            times.mapping(coords, PrayerTimesRepository.choice(context, coords), date.year)?.zone?.nameAr
        }.getOrNull()
    }
    val noon = ZonedDateTime.of(date, LocalTime.NOON, place.zone).toInstant()
    val placeOffset = place.zone.rules.getOffset(noon)
    val offset = if (placeOffset == phoneZone.rules.getOffset(noon)) null else {
        val seconds = placeOffset.totalSeconds
        val sign = if (seconds < 0) "−" else "+"
        val hours = abs(seconds) / 3600
        val minutes = abs(seconds) % 3600 / 60
        "⁦UTC$sign$hours" + (if (minutes != 0) String.format(java.util.Locale.US, ":%02d", minutes) else "") + "⁩"
    }
    return TimesListNotes(zoneName, offset)
}
