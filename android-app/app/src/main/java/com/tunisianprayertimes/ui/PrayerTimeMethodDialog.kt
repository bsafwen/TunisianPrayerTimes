package com.tunisianprayertimes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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
import kotlin.math.floor

/**
 * Shows users how the prayer times of [selectedDate] are computed for their delegation:
 * a guided explanation of each solar sign, method offsets and rounding, with optional
 * worked mathematics. Values come from [InmPrayerFormula.explain], in Tunisian local time.
 */
@Composable
internal fun PrayerTimeMethodDialog(
    delegationId: Int,
    selectedDate: Long,
    onDismiss: () -> Unit,
    asExample: Boolean = false,
) {
    val context = LocalContext.current
    val date = remember(selectedDate) {
        Instant.ofEpochMilli(selectedDate).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val location = remember(delegationId, date) {
        PrayerDataLoader.prayerTimes(context).location(delegationId)
            ?.takeIf { date.year in InmPrayerTimes.SUPPORTED_YEARS }
    }
    val explanation = remember(location, date) {
        location?.let { InmPrayerFormula.explain(it, date.year, date.monthValue, date.dayOfMonth) }
    }
    val delegationName = remember(delegationId) {
        GouvernoratRepository.findDelegationById(context, delegationId)?.displayName().orEmpty()
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
                            subtitle = stringResource(R.string.prayer_method_guide_context, delegationName, gregorianDateLabel(date)),
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
                            if (location == null || explanation == null) {
                                Text(stringResource(R.string.prayer_method_unavailable), fontSize = 14.sp, color = TextMuted)
                            } else {
                                GuidedMethodContent(location, date, delegationName, explanation, asExample)
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
private fun GuidedMethodContent(
    location: InmLocation,
    date: LocalDate,
    delegationName: String,
    explanation: InmDayExplanation,
    asExample: Boolean,
) {
    val names = eventNames()
    Column(
        Modifier.fillMaxWidth().background(BgCream, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.prayer_method_guide_intro_title), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = GreenPrimaryDark)
        GuideText(stringResource(R.string.prayer_method_guide_intro_body))
        Text(
            stringResource(if (asExample) R.string.prayer_method_guide_example_scope else R.string.prayer_method_guide_scope),
            fontSize = 13.sp, lineHeight = 20.sp, color = PrayerSilencePalette.SecondaryText,
        )
    }

    GuideStep("1", stringResource(R.string.prayer_method_guide_inputs_title)) {
        GuideText(stringResource(R.string.prayer_method_guide_inputs_body))
        GuideDisclosure(stringResource(R.string.prayer_method_guide_coordinates), "coordinates") {
            ValueRow(stringResource(R.string.prayer_method_input_latitude), "%.3f°".us(location.latitude))
            ValueRow(stringResource(R.string.prayer_method_input_longitude), "%.3f°".us(location.longitude))
            ValueRow(stringResource(R.string.prayer_method_input_elevation), stringResource(R.string.prayer_method_input_elevation_value, "%.0f".us(location.elevationM)), LtrMonospace.copy(textDirection = TextDirection.ContentOrRtl))
            GuideText(stringResource(R.string.prayer_method_guide_elevation))
        }
    }

    GuideStep("2", stringResource(R.string.prayer_method_guide_signs_title)) {
        GuideText(stringResource(R.string.prayer_method_guide_signs_body))
        PrayerTimeMethodExplorer(location, date, explanation)
    }

    GuideStep("3", stringResource(R.string.prayer_method_guide_result_title)) {
        GuideText(stringResource(R.string.prayer_method_guide_adjustments))
        GuideTimeTransition(
            stringResource(R.string.prayer_method_guide_solar_noon), clock(explanation.solarNoonMinutes),
            stringResource(R.string.prayer_method_guide_plus_seven), names.getValue(InmEvent.DHUHR),
            clock(explanation.events.first { it.event == InmEvent.DHUHR }.exactMinutes),
        )
        val maghrib = explanation.events.first { it.event == InmEvent.MAGHRIB }
        GuideTimeTransition(
            stringResource(R.string.prayer_method_guide_sunset), clock(maghrib.exactMinutes - 2),
            stringResource(R.string.prayer_method_guide_plus_two), names.getValue(InmEvent.MAGHRIB), clock(maghrib.exactMinutes),
        )
        GuideText(stringResource(R.string.prayer_method_guide_rounding))
        val fajr = explanation.events.first { it.event == InmEvent.FAJR }
        val seconds = (floor(fajr.exactMinutes * 60).toLong() % 60).toInt()
        GuideTimeTransition(
            stringResource(R.string.prayer_method_guide_rounding_example), clock(fajr.exactMinutes),
            stringResource(if (seconds >= 30) R.string.prayer_method_guide_round_up else R.string.prayer_method_guide_round_down, seconds),
            stringResource(R.string.prayer_method_guide_final_time), hhmm(fajr.shownMinutes),
        )
        GuideDisclosure(stringResource(R.string.prayer_method_guide_all_results), "results") {
            ResultTable(explanation.events, names)
        }
    }

    MethodSection(stringResource(R.string.prayer_method_guide_changes_title), stringResource(R.string.prayer_method_guide_changes_body)) {
        GuideDisclosure(stringResource(R.string.prayer_method_guide_seasons), "seasons") {
            // These existing illustrations explicitly use Tunis; keep their example separate from the selected place.
            val context = LocalContext.current
            val tunis = remember { PrayerDataLoader.prayerTimes(context).location(EXAMPLE_DELEGATION_ID) }
            if (tunis != null) {
                val sunYear = remember(date.year) { SunYear(date.year) }
                GuideText(stringResource(R.string.prayer_method_guide_seasons_context))
                DayAltitudeChart(tunis, sunYear)
                AltitudeProtractor(tunis.latitude)
            }
        }
    }

    GuideDisclosure(stringResource(R.string.prayer_method_guide_details), "details") {
        GuideText(stringResource(R.string.prayer_method_guide_details_intro))
        val context = LocalContext.current
        val sunYear = remember(date.year) { SunYear(date.year) }
        val exampleLocation = remember { PrayerDataLoader.prayerTimes(context).location(EXAMPLE_DELEGATION_ID) }
        val example = remember(exampleLocation, date.year) {
            exampleLocation?.let { InmPrayerFormula.explain(it, sunYear.summer.year, sunYear.summer.monthValue, sunYear.summer.dayOfMonth) }
        }
        if (exampleLocation != null && example != null) {
            DetailedMethodContent(exampleLocation, sunYear.summer, example, sunYear, date, delegationName, explanation)
        }
    }
    Text(stringResource(R.string.prayer_method_trust_body), fontSize = 12.sp, lineHeight = 19.sp, color = TextMuted)
}

@Composable
private fun GuideStep(number: String, title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, PrayerSilencePalette.SoftBorder, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).background(PrayerSilencePalette.PrimaryText, CircleShape), contentAlignment = Alignment.Center) {
                Text(number, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Text(title, modifier = Modifier.weight(1f).semantics { heading() }, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextDark)
        }
        content()
    }
}

@Composable
private fun GuideText(text: String) {
    Text(text, fontSize = 14.sp, lineHeight = 22.sp, color = TextDark)
}

/** A vertical reading order keeps the calculation readable in RTL and at larger font scales. */
@Composable
private fun GuideTimeTransition(from: String, initial: String, change: String, to: String, result: String) {
    Column(
        Modifier.fillMaxWidth().background(PrayerSilencePalette.TintedStrip, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ValueRow(from, initial)
        Text(change, fontSize = 13.sp, lineHeight = 20.sp, color = PrayerSilencePalette.SecondaryText)
        HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
        ValueRow(to, result, LtrMonospace.copy(fontWeight = FontWeight.Bold, color = GreenPrimaryDark))
    }
}

@Composable
private fun GuideDisclosure(title: String, key: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    val state = stringResource(if (expanded) R.string.prayer_method_guide_expanded else R.string.prayer_method_guide_collapsed)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .background(PrayerSilencePalette.TintedStrip, RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = state }
                .testTag("prayer_method_$key")
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = GreenPrimaryDark)
            Text(if (expanded) "−" else "+", fontSize = 22.sp, color = GreenPrimaryDark)
        }
        if (expanded) content()
    }
}

@Composable
private fun DetailedMethodContent(
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

    Text(
        stringResource(R.string.prayer_method_example_note, gregorianDateLabel(date), gregorianDateLabel(today), todayName),
        fontSize = 13.sp,
        color = TextDark,
        lineHeight = 19.sp,
    )

    MethodSection(stringResource(R.string.prayer_method_chart_title), stringResource(R.string.prayer_method_chart_body)) {
        PrayerTimeMethodExplorer(location, date, explanation)
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
        TwilightDiagram(explanation, names + (InmEvent.MAGHRIB to stringResource(R.string.prayer_explorer_sunset)))
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

/** Keep a time intact without squeezing its Arabic label on a narrow screen or with large text. */
@Composable
private fun ValueRow(label: String, value: String, valueStyle: TextStyle = LtrMonospace) {
    val labelStyle = TextStyle(fontSize = 13.sp, color = PrayerSilencePalette.SecondaryText)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fits = with(LocalDensity.current) {
            measurer.measure(label, labelStyle).size.width + 12.dp.toPx() + measurer.measure(value, valueStyle).size.width
        } <= constraints.maxWidth
        if (fits) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, style = labelStyle, modifier = Modifier.weight(1f))
                Text(value, style = valueStyle, color = TextDark, softWrap = false, maxLines = 1)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = labelStyle)
                Text(value, style = valueStyle, color = TextDark, softWrap = false, maxLines = 1)
            }
        }
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
