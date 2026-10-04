package com.tunisianprayertimes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tunisianprayertimes.GouvernoratRepository
import com.tunisianprayertimes.InmDayExplanation
import com.tunisianprayertimes.InmEvent
import com.tunisianprayertimes.InmEventStep
import com.tunisianprayertimes.InmLocation
import com.tunisianprayertimes.InmPrayerFormula
import com.tunisianprayertimes.InmPrayerTimes
import com.tunisianprayertimes.R
import com.tunisianprayertimes.platform.PrayerDataLoader
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Shows users how the prayer times of [selectedDate] are computed for their delegation:
 * the sun's path with each time on it, then INM's formula step by step with the real
 * numbers. Every value comes from [InmPrayerFormula.explain], the computation the app uses.
 */
@Composable
internal fun PrayerTimeMethodDialog(
    delegationId: Int,
    selectedDate: Long,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val date = remember(selectedDate) {
        Instant.ofEpochMilli(selectedDate).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val todayLocation = remember(delegationId, date) {
        PrayerDataLoader.prayerTimes(context).location(delegationId)
            ?.takeIf { date.year in InmPrayerTimes.SUPPORTED_YEARS }
    }
    // The steps are explained for Tunis on the first day of summer of the chosen year, where the
    // sun's position is easiest to picture; the chosen delegation and day get their result at the end.
    val sunYear = remember(date.year) { SunYear(date.year) }
    val exampleDate = sunYear.summer
    val location = remember(date.year) {
        PrayerDataLoader.prayerTimes(context).location(EXAMPLE_DELEGATION_ID)
            ?.takeIf { date.year in InmPrayerTimes.SUPPORTED_YEARS }
    }
    val explanation = remember(location, exampleDate) {
        location?.let { InmPrayerFormula.explain(it, exampleDate.year, exampleDate.monthValue, exampleDate.dayOfMonth) }
    }
    val todayExplanation = remember(todayLocation, date) {
        todayLocation?.let { InmPrayerFormula.explain(it, date.year, date.monthValue, date.dayOfMonth) }
    }
    val delegationName = remember(delegationId) {
        GouvernoratRepository.findDelegationById(context, delegationId)?.displayName().orEmpty()
    }
    val exampleName = remember {
        GouvernoratRepository.findDelegationById(context, EXAMPLE_DELEGATION_ID)?.displayName().orEmpty()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(
                Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxSize()
                        .testTag(TestTags.PRAYER_TIME_METHOD_DIALOG),
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        MethodHeader(
                            subtitle = stringResource(R.string.prayer_method_context, exampleName, gregorianDateLabel(exampleDate)),
                            onDismiss = onDismiss,
                        )
                        HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (location == null || explanation == null || todayExplanation == null) {
                                Text(stringResource(R.string.prayer_method_unavailable), fontSize = 14.sp, color = TextMuted)
                            } else {
                                MethodContent(location, exampleDate, explanation, sunYear, date, delegationName, todayExplanation)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MethodHeader(subtitle: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.prayer_method_title),
                modifier = Modifier.semantics { heading() },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerSilencePalette.PrimaryText,
            )
            Text(text = subtitle, fontSize = 13.sp, color = TextMuted)
        }
        IconButton(onClick = onDismiss) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.prayer_method_close),
                tint = PrayerSilencePalette.SecondaryText,
            )
        }
    }
}

@Composable
private fun MethodContent(
    location: InmLocation,
    date: LocalDate,
    explanation: InmDayExplanation,
    sunYear: SunYear,
    today: LocalDate,
    todayName: String,
    todayExplanation: InmDayExplanation,
) {
    val names = eventNames()
    val steps = explanation.events.associateBy { it.event }
    val noon = explanation.solarNoonMinutes

    TrustCard()
    Text(
        stringResource(R.string.prayer_method_example_note, gregorianDateLabel(date), gregorianDateLabel(today), todayName),
        fontSize = 13.sp,
        color = TextDark,
        lineHeight = 19.sp,
    )

    MethodSection(stringResource(R.string.prayer_method_chart_title), stringResource(R.string.prayer_method_chart_body)) {
        SunPathChart(location, date, explanation, names)
    }

    MethodSection(stringResource(R.string.prayer_method_inputs_title), stringResource(R.string.prayer_method_inputs_body)) {
        ValueRow(stringResource(R.string.prayer_method_input_latitude), "%.3f°".us(location.latitude))
        ValueRow(stringResource(R.string.prayer_method_input_longitude), "%.3f°".us(location.longitude))
        ValueRow(
            stringResource(R.string.prayer_method_input_elevation),
            stringResource(R.string.prayer_method_input_elevation_value, "%.0f".us(location.elevationM)),
            valueStyle = LtrMonospace.copy(textDirection = TextDirection.ContentOrRtl),
        )
    }

    MethodSection(stringResource(R.string.prayer_method_step_sun_title), stringResource(R.string.prayer_method_step_sun_body)) {
        val eotMinutes = "%.2f".us(abs(explanation.equationOfTimeMin))
        SunQuantity(
            name = stringResource(R.string.prayer_method_sun_jd_name),
            value = "%.1f".us(explanation.julianDay),
            meaning = stringResource(R.string.prayer_method_sun_jd_meaning),
        )
        val declination = explanation.declinationDeg
        SunQuantity(
            name = stringResource(R.string.prayer_method_sun_decl_name),
            value = "${signed(declination, 2)}°",
            meaning = stringResource(R.string.prayer_method_sun_decl_meaning, sunYear.summer.dayMonth(), sunYear.winter.dayMonth()),
            diagram = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DayAltitudeChart(location, sunYear)
                    AltitudeProtractor(location.latitude)
                    Text(
                        stringResource(R.string.prayer_method_sun_decl_definition, wholeDegreesOf(location.latitude)),
                        fontSize = 13.sp,
                        color = TextDark,
                        lineHeight = 19.sp,
                    )
                    SubsolarGlobes(location.latitude)
                    DeclinationYearChart(sunYear, today, todayExplanation.declinationDeg)
                }
            },
            today = stringResource(
                if (todayExplanation.declinationDeg < 0) R.string.prayer_method_sun_decl_today_south else R.string.prayer_method_sun_decl_today_north,
                gregorianDateLabel(today),
                "\u2066${signed(todayExplanation.declinationDeg, 2)}°\u2069",
                "\u2066${"%.2f".us(abs(todayExplanation.declinationDeg))}°\u2069",
                "\u2066${"%.0f".us(todayExplanation.events.first { it.event == InmEvent.DHUHR }.altitudeDeg)}°\u2069",
            ),
        )
        SunQuantity(
            name = stringResource(R.string.prayer_method_sun_eot_name),
            value = "${signed(explanation.equationOfTimeMin, 2)} min",
            meaning = stringResource(R.string.prayer_method_sun_eot_meaning),
            today = if (explanation.equationOfTimeMin < 0) {
                stringResource(R.string.prayer_method_sun_eot_behind, eotMinutes)
            } else {
                stringResource(R.string.prayer_method_sun_eot_ahead, eotMinutes)
            },
        )
        Text(stringResource(R.string.prayer_method_sun_usage), fontSize = 12.sp, color = TextMuted, lineHeight = 17.sp)
    }

    MethodSection(stringResource(R.string.prayer_method_step_dhuhr_title), stringResource(R.string.prayer_method_step_dhuhr_body)) {
        NoonShiftDiagram(location.longitude, explanation, steps.getValue(InmEvent.DHUHR))
        Formula(
            "noon = 12 + 1 − λ/15",
            "          − EoT/60",
            "     = 12 + 1 − %.3f/15".us(location.longitude),
            "          ${minusTerm(explanation.equationOfTimeMin)}/60",
            "     = ${clock(noon)}",
            "t    = noon + 7 min",
        )
        EventResult(names.getValue(InmEvent.DHUHR), steps.getValue(InmEvent.DHUHR))
    }

    MethodSection(
        stringResource(R.string.prayer_method_step_hour_angle_title),
        stringResource(R.string.prayer_method_step_hour_angle_body),
    ) {
        HourAngleDial(explanation, names)
        Formula(
            "cos H = (sin a − sin φ·sin δ)",
            "        / (cos φ·cos δ)",
            "t     = noon ± H/15",
        )
    }

    val asr = steps.getValue(InmEvent.ASR)
    // Taken from the two clock times shown, so noon + this reads exactly as Asr's exact time.
    val asrAfterNoon = duration((floor(asr.exactMinutes * 60) - floor(noon * 60) + 0.5) / 60)
    MethodSection(stringResource(R.string.prayer_method_step_asr_title), stringResource(R.string.prayer_method_step_asr_body)) {
        AsrShadowDiagram(location.latitude, explanation)
        Formula(
            "a = atan(1/(1 + tan|φ − δ|))",
            "  = atan(1/(1 + tan %.3f°))".us(abs(location.latitude - explanation.declinationDeg)),
            "  = %.3f°".us(explanation.asrAltitudeDeg),
            "H = %.3f°  →  %s".us(asr.hourAngleDeg ?: 0.0, asrAfterNoon),
            "t = ${clock(noon)} + $asrAfterNoon",
        )
        EventResult(names.getValue(InmEvent.ASR), asr)
    }

    MethodSection(stringResource(R.string.prayer_method_step_horizon_title), stringResource(R.string.prayer_method_step_horizon_body)) {
        val sunriseDip = if (explanation.sunriseDipDeg != explanation.dipDeg) {
            // INM uses another elevation for this delegation's sunrise in some years.
            listOfNotNull(
                location.sunriseElevationOverrides[date.year]?.let { "h(sunrise) = %s m".us(it.toString()) },
                "d(sunrise) = %.3f°".us(explanation.sunriseDipDeg),
                "a(sunrise) = ${signed(steps.getValue(InmEvent.SUNRISE).altitudeDeg, 3)}°",
            )
        } else {
            emptyList()
        }
        SunriseDiagram(explanation, steps.getValue(InmEvent.MAGHRIB))
        Formula(
            *(listOf(
                "d = acos(R / (R + h))",
                "  = %.3f°   (R = 6378137 m)".us(explanation.dipDeg),
                "a = −(0.83° + d) = ${signed(steps.getValue(InmEvent.MAGHRIB).altitudeDeg, 3)}°",
            ) + sunriseDip).toTypedArray(),
        )
        EventResult(names.getValue(InmEvent.SUNRISE), steps.getValue(InmEvent.SUNRISE))
        EventResult(names.getValue(InmEvent.MAGHRIB), steps.getValue(InmEvent.MAGHRIB))
    }

    MethodSection(stringResource(R.string.prayer_method_step_twilight_title), stringResource(R.string.prayer_method_step_twilight_body)) {
        TwilightDiagram(explanation, names)
        Formula("a = −(18° + d) = ${signed(steps.getValue(InmEvent.FAJR).altitudeDeg, 3)}°")
        EventResult(names.getValue(InmEvent.FAJR), steps.getValue(InmEvent.FAJR))
        EventResult(names.getValue(InmEvent.ISHA), steps.getValue(InmEvent.ISHA))
        Text(stringResource(R.string.prayer_method_step_iteration_note), fontSize = 12.sp, color = TextMuted, lineHeight = 17.sp)
    }

    MethodSection(stringResource(R.string.prayer_method_step_rounding_title), stringResource(R.string.prayer_method_step_rounding_body)) {
        RoundingDiagram(explanation.events, names)
        ResultTable(explanation.events, names)
    }

    MethodSection(
        stringResource(R.string.prayer_method_today_title, todayName, gregorianDateLabel(today)),
        stringResource(R.string.prayer_method_today_body),
    ) {
        ResultTable(todayExplanation.events, names)
    }

    Text(
        text = stringResource(R.string.prayer_method_source),
        fontSize = 12.sp,
        color = TextMuted,
        lineHeight = 17.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun eventNames(): Map<InmEvent, String> = mapOf(
    InmEvent.FAJR to stringResource(R.string.prayer_fajr),
    InmEvent.SUNRISE to stringResource(R.string.adhkar_shuruk),
    InmEvent.DHUHR to stringResource(R.string.prayer_dhuhr),
    InmEvent.ASR to stringResource(R.string.prayer_asr),
    InmEvent.MAGHRIB to stringResource(R.string.prayer_maghrib),
    InmEvent.ISHA to stringResource(R.string.prayer_isha),
)

@Composable
private fun TrustCard() {
    Column(
        Modifier.fillMaxWidth().background(BgCream, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.prayer_method_trust_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = GreenPrimaryDark,
        )
        Text(stringResource(R.string.prayer_method_trust_body), fontSize = 13.sp, color = TextDark, lineHeight = 19.sp)
    }
}

@Composable
private fun MethodSection(title: String, body: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, PrayerSilencePalette.SoftBorder, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = PrayerSilencePalette.PrimaryText,
        )
        Text(body, fontSize = 13.sp, color = TextDark, lineHeight = 19.sp)
        content()
    }
}

/** Math lines, always left to right whatever the app's layout direction. */
@Composable
private fun Formula(vararg lines: String) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(PrayerSilencePalette.TintedStrip, RoundedCornerShape(12.dp))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            lines.forEach { line ->
                Text(line, style = FormulaStyle, color = TextDark, softWrap = false, maxLines = 1)
            }
        }
    }
}

/** One of the sun's three numbers: its name and value, what it means, an optional drawing, and what today's value says. */
@Composable
private fun SunQuantity(
    name: String,
    value: String,
    meaning: String,
    today: String? = null,
    diagram: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(PrayerSilencePalette.TintedStrip, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextDark, modifier = Modifier.weight(1f))
            Text(value, style = LtrMonospace, color = GreenPrimaryDark, softWrap = false, maxLines = 1)
        }
        Text(meaning, fontSize = 13.sp, color = TextDark, lineHeight = 19.sp)
        diagram?.invoke()
        if (today != null) {
            Text(today, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = GreenPrimaryDark, lineHeight = 19.sp)
        }
    }
}

/** A short label and a short value on one line; values here are a few characters long. */
@Composable
private fun ValueRow(label: String, value: String, valueStyle: TextStyle = LtrMonospace) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, fontSize = 13.sp, color = PrayerSilencePalette.SecondaryText, modifier = Modifier.weight(1f))
        Text(value, style = valueStyle, color = TextDark, softWrap = false, maxLines = 1)
    }
}

/**
 * One time's result: the prayer name with the exact and rounded time, and below it the hour
 * angle when it has one. The time moves under the name when both don't fit on one line (narrow
 * phones, large fonts), so the name is never squeezed into a letter per line.
 */
@Composable
private fun EventResult(name: String, step: InmEventStep) {
    val nameStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextDark)
    val valueStyle = LtrMonospace.copy(color = GreenPrimaryDark)
    val value = "${clock(step.exactMinutes)} → ${hhmm(step.shownMinutes)}"
    val measurer = rememberTextMeasurer()
    Column(
        Modifier
            .fillMaxWidth()
            .background(BgCream, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val spacing = 12.dp
            val oneLine = with(LocalDensity.current) {
                measurer.measure(name, nameStyle).size.width + spacing.roundToPx() + measurer.measure(value, valueStyle).size.width
            } <= constraints.maxWidth
            if (oneLine) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    Text(name, style = nameStyle, modifier = Modifier.weight(1f))
                    Text(value, style = valueStyle, softWrap = false, maxLines = 1)
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = nameStyle)
                    Text(value, style = valueStyle, modifier = Modifier.align(Alignment.End))
                }
            }
        }
        step.hourAngleDeg?.let { angle ->
            Text(
                "H = %.3f°".us(angle),
                style = LtrMonospace.copy(fontSize = 12.sp),
                color = PrayerSilencePalette.SecondaryText,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/**
 * Every time, exact and as shown. A three-column table when the longest name fits beside the two
 * time columns; otherwise each time on two lines, so no name is ever squeezed.
 */
@Composable
private fun ResultTable(events: List<InmEventStep>, names: Map<InmEvent, String>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val headerStyle = TextStyle(fontSize = 11.sp)
        // Wide enough for the longest value and its header, whatever the font scale.
        fun columnWidth(sample: String, style: TextStyle, title: String) = with(density) {
            maxOf(measurer.measure(sample, style).size.width, measurer.measure(title, headerStyle).size.width).toDp() + 12.dp
        }
        val exactTitle = stringResource(R.string.prayer_method_table_exact)
        val shownTitle = stringResource(R.string.prayer_method_table_shown)
        val exactWidth = columnWidth("00:00:00", LtrMonospace, exactTitle)
        val shownWidth = columnWidth("00:00", LtrMonospace.copy(fontWeight = FontWeight.Bold), shownTitle)
        val nameWidth = with(density) {
            events.maxOf { measurer.measure(names.getValue(it.event), TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold)).size.width }.toDp()
        }
        if (nameWidth + exactWidth + shownWidth <= maxWidth) {
            Column(Modifier.fillMaxWidth()) {
                TableRow(stringResource(R.string.prayer_method_table_prayer), exactTitle, shownTitle, exactWidth, shownWidth, header = true)
                events.forEach { step ->
                    HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
                    TableRow(names.getValue(step.event), clock(step.exactMinutes), hhmm(step.shownMinutes), exactWidth, shownWidth)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                events.forEachIndexed { index, step ->
                    if (index > 0) HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(names.getValue(step.event), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextDark)
                        Text(
                            "${clock(step.exactMinutes)} → ${hhmm(step.shownMinutes)}",
                            style = LtrMonospace.copy(color = GreenPrimaryDark),
                            modifier = Modifier.align(Alignment.End),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableRow(
    name: String,
    exact: String,
    shown: String,
    exactWidth: Dp,
    shownWidth: Dp,
    header: Boolean = false,
) {
    val size = if (header) 11.sp else 13.sp
    val cellStyle = if (header) TextStyle(fontSize = size, color = TextMuted) else LtrMonospace.copy(color = TextDark)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            fontSize = size,
            color = if (header) TextMuted else TextDark,
            fontWeight = if (header) FontWeight.Normal else FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(exact, style = cellStyle, textAlign = TextAlign.Center, softWrap = false, maxLines = 1, modifier = Modifier.width(exactWidth))
        Text(
            shown,
            style = if (header) cellStyle else cellStyle.copy(color = GreenPrimaryDark, fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            softWrap = false,
            maxLines = 1,
            modifier = Modifier.width(shownWidth),
        )
    }
}

/**
 * The sun's altitude through the day, with the six times marked on it and the altitudes
 * that define them (horizon, 18° below it, Asr) as reference lines. Time runs left to right.
 */
@Composable
private fun SunPathChart(
    location: InmLocation,
    date: LocalDate,
    explanation: InmDayExplanation,
    names: Map<InmEvent, String>,
) {
    val steps = explanation.events.associateBy { it.event }
    val startMinutes = (steps.getValue(InmEvent.FAJR).exactMinutes - 30).coerceAtLeast(0.0)
    val endMinutes = (steps.getValue(InmEvent.ISHA).exactMinutes + 30).coerceAtMost(24.0 * 60)
    val path = remember(location, date) {
        generateSequence(startMinutes) { it + 5 }.takeWhile { it <= endMinutes }.map { minutes ->
            minutes to InmPrayerFormula.sunAltitudeDeg(location, date.year, date.monthValue, date.dayOfMonth, minutes)
        }.toList()
    }
    val markers = remember(location, date) {
        explanation.events.map { step ->
            step to InmPrayerFormula.sunAltitudeDeg(location, date.year, date.monthValue, date.dayOfMonth, step.exactMinutes)
        }
    }
    val highestMarker = markers.maxOf { it.second }
    val lowestMarker = markers.minOf { it.second }
    val twilightAltitude = steps.getValue(InmEvent.FAJR).altitudeDeg
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = TextDark, textAlign = TextAlign.Center)
    val lineLabelStyle = TextStyle(fontSize = 10.sp, color = PrayerSilencePalette.SecondaryText)
    val axisStyle = TextStyle(fontSize = 10.sp, color = TextMuted)
    val horizonLabel = stringResource(R.string.prayer_method_chart_horizon)
    val twilightLabel = stringResource(R.string.prayer_method_chart_twilight)
    val asrLabel = stringResource(R.string.prayer_method_chart_asr)
    val description = stringResource(
        R.string.prayer_method_chart_description,
        explanation.events.joinToString("، ") { "${names.getValue(it.event)} ${hhmm(it.shownMinutes)}" },
    )

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .semantics { contentDescription = description },
        ) {
            val tickLabelSize = measurer.measure("00:00", axisStyle).size
            val axisBand = tickLabelSize.height + 4.dp.toPx()
            val plotBottom = size.height - axisBand
            val gap = 6.dp.toPx()
            val dotRadius = 6.dp.toPx()
            // Room above Dhuhr's marker and below Fajr's and Isha's for their two-line labels,
            // measured at the user's font scale.
            val labelHeight = measurer.measure("${names.getValue(InmEvent.DHUHR)}\n00:00", labelStyle).size.height
            val pad = labelHeight + gap + dotRadius
            fun x(minutes: Double) = ((minutes - startMinutes) / (endMinutes - startMinutes) * size.width).toFloat()
            fun y(altitude: Double) =
                (pad + (highestMarker - altitude) / (highestMarker - lowestMarker) * (plotBottom - 2 * pad)).toFloat()
            val horizonY = y(0.0)
            val noonX = x(explanation.solarNoonMinutes)

            // Night and twilight: everything below the horizon.
            drawRect(
                color = PrayerSilencePalette.TintedStrip,
                topLeft = Offset(0f, horizonY),
                size = Size(size.width, plotBottom - horizonY),
            )

            val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
            fun referenceLine(altitude: Double, color: Color, dashed: Boolean) = drawLine(
                color = color,
                start = Offset(0f, y(altitude)),
                end = Offset(size.width, y(altitude)),
                strokeWidth = 1.dp.toPx(),
                pathEffect = if (dashed) dash else null,
            )
            referenceLine(0.0, PrayerSilencePalette.Tick, dashed = false)
            referenceLine(twilightAltitude, PrayerSilencePalette.Tick, dashed = true)
            referenceLine(explanation.asrAltitudeDeg, PrayerSilencePalette.GoldAccent, dashed = true)

            // Reference-line labels sit at solar noon, where the curve is far from the lines.
            fun centeredLabel(text: String, style: TextStyle, lineY: Float, above: Boolean) {
                val layout = measurer.measure(text, style)
                val left = (noonX - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                val top = if (above) lineY - layout.size.height - 2.dp.toPx() else lineY + 2.dp.toPx()
                drawText(layout, topLeft = Offset(left, top))
            }
            centeredLabel(horizonLabel, lineLabelStyle, horizonY, above = false)
            centeredLabel(twilightLabel, lineLabelStyle, y(twilightAltitude), above = false)
            centeredLabel(asrLabel, lineLabelStyle.copy(color = PrayerSilencePalette.GoldAccent), y(explanation.asrAltitudeDeg), above = true)

            // The sun's path: gold while it is up, teal below the horizon.
            val curve = Path().apply {
                path.forEachIndexed { index, (minutes, altitude) ->
                    if (index == 0) moveTo(x(minutes), y(altitude)) else lineTo(x(minutes), y(altitude))
                }
            }
            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            clipRect(top = 0f, bottom = horizonY) {
                drawPath(curve, PrayerSilencePalette.GoldAccent, style = stroke)
            }
            clipRect(top = horizonY, bottom = plotBottom) {
                drawPath(curve, PrayerSilencePalette.InteractiveTeal, style = stroke)
            }

            // Hour ticks every three hours, or six when three-hour labels would touch.
            val minutesPerPx = (endMinutes - startMinutes) / size.width
            val tickStep = if ((tickLabelSize.width + 8.dp.toPx()) * minutesPerPx < 180) 180 else 360
            var tick = ceil(startMinutes / tickStep) * tickStep
            while (tick <= endMinutes) {
                val layout = measurer.measure(hhmm(tick.toInt()), axisStyle)
                val left = (x(tick) - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                drawLine(
                    PrayerSilencePalette.SoftBorder,
                    Offset(x(tick), plotBottom - 4.dp.toPx()),
                    Offset(x(tick), plotBottom),
                    strokeWidth = 1.dp.toPx(),
                )
                drawText(layout, topLeft = Offset(left, plotBottom + 2.dp.toPx()))
                tick += tickStep
            }

            // Each time on the curve, labelled on the side the curve leaves empty: under the
            // curve for the morning and evening times, above it for Dhuhr and Asr.
            markers.forEach { (step, altitude) ->
                val center = Offset(x(step.exactMinutes), y(altitude))
                drawCircle(Color.White, radius = dotRadius, center = center)
                drawCircle(PrayerSilencePalette.PrimaryText, radius = 4.dp.toPx(), center = center)
                val layout = measurer.measure("${names.getValue(step.event)}\n${hhmm(step.shownMinutes)}", labelStyle)
                val w = layout.size.width.toFloat()
                val h = layout.size.height.toFloat()
                val below = center.y + dotRadius
                val topLeft = when (step.event) {
                    InmEvent.FAJR, InmEvent.SUNRISE -> Offset(center.x + gap, below)
                    InmEvent.MAGHRIB, InmEvent.ISHA -> Offset(center.x - w - gap, below)
                    InmEvent.DHUHR -> Offset(center.x - w / 2, center.y - h - gap)
                    InmEvent.ASR -> Offset(center.x + gap, center.y - h - gap / 2)
                }
                drawText(
                    layout,
                    topLeft = Offset(
                        topLeft.x.coerceIn(0f, size.width - w),
                        topLeft.y.coerceIn(0f, plotBottom - h),
                    ),
                )
            }
        }
    }
}

private val LtrMonospace = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    textDirection = TextDirection.Ltr,
)

/** Slightly smaller than [LtrMonospace] so a formula line fits a phone's width at most font scales. */
private val FormulaStyle = LtrMonospace.copy(fontSize = 12.sp)

private fun String.us(vararg args: Any): String = String.format(Locale.US, this, *args)

/** [value] with an explicit sign, using the minus sign the formulas use (−), not a hyphen. */
private fun signed(value: Double, decimals: Int): String =
    (if (value < 0) "−" else "+") + "%.${decimals}f".us(abs(value))

/** "− x" for a subtracted [value], folding a negative value into "+ |x|". */
private fun minusTerm(value: Double): String =
    (if (value < 0) "+ " else "− ") + "%.2f".us(abs(value))

/** A latitude or height in whole degrees, as the declination card writes them. */
/** The delegation the explainer's worked example uses: Tunis. */
private const val EXAMPLE_DELEGATION_ID = 615

private fun wholeDegreesOf(value: Double): String = "%.0f°".us(value)

private fun hhmm(minutes: Int): String = "%02d:%02d".us(minutes / 60, minutes % 60)

/** Exact time with seconds truncated, so 30 s or more is visibly what rounds up. */
private fun clock(minutes: Double): String {
    val seconds = floor(minutes * 60).toLong()
    return "%02d:%02d:%02d".us(seconds / 3600, seconds / 60 % 60, seconds % 60)
}

private fun duration(minutes: Double): String {
    val seconds = floor(minutes * 60).toLong()
    return "%d:%02d:%02d".us(seconds / 3600, seconds / 60 % 60, seconds % 60)
}
