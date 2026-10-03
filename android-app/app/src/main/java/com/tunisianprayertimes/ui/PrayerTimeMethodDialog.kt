package com.tunisianprayertimes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import com.tunisianprayertimes.R
import com.tunisianprayertimes.platform.PrayerDataLoader
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.GreenPrimary
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
    val location = remember(delegationId, date) {
        PrayerDataLoader.prayerTimes(context).location(delegationId, date.year)
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
                            subtitle = stringResource(R.string.prayer_method_context, delegationName, gregorianDateLabel(date)),
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
                                MethodContent(location, date, explanation)
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
private fun MethodContent(location: InmLocation, date: LocalDate, explanation: InmDayExplanation) {
    val names = eventNames()
    val steps = explanation.events.associateBy { it.event }
    val noon = explanation.solarNoonMinutes

    TrustCard()

    MethodSection(stringResource(R.string.prayer_method_chart_title), stringResource(R.string.prayer_method_chart_body)) {
        SunPathChart(location, date, explanation, names)
    }

    MethodSection(stringResource(R.string.prayer_method_inputs_title), stringResource(R.string.prayer_method_inputs_body)) {
        ValueRow(stringResource(R.string.prayer_method_input_latitude), "%.3f°".us(location.latitude))
        ValueRow(stringResource(R.string.prayer_method_input_longitude), "%.3f°".us(location.longitude))
        ValueRow(
            stringResource(R.string.prayer_method_input_elevation),
            stringResource(R.string.prayer_method_input_elevation_value, "%.0f".us(location.elevationM)),
        )
    }

    MethodSection(stringResource(R.string.prayer_method_step_sun_title), stringResource(R.string.prayer_method_step_sun_body)) {
        Formula(
            "JD  = %.1f".us(explanation.julianDay),
            "δ   = %+.3f°".us(explanation.declinationDeg),
            "EoT = %+.2f min".us(explanation.equationOfTimeMin),
        )
    }

    MethodSection(stringResource(R.string.prayer_method_step_dhuhr_title), stringResource(R.string.prayer_method_step_dhuhr_body)) {
        Formula(
            "noon = 12 + 1 − λ/15 − EoT/60",
            "     = 12 + 1 − %.3f/15 − %.2f/60".us(location.longitude, explanation.equationOfTimeMin),
            "     = ${clock(noon)}",
            "t    = noon + 7 min",
        )
        EventResult(names.getValue(InmEvent.DHUHR), steps.getValue(InmEvent.DHUHR))
    }

    MethodSection(
        stringResource(R.string.prayer_method_step_hour_angle_title),
        stringResource(R.string.prayer_method_step_hour_angle_body),
    ) {
        Formula(
            "cos H = (sin a − sin φ · sin δ)",
            "        / (cos φ · cos δ)",
            "t     = noon ± H / 15",
        )
    }

    val asr = steps.getValue(InmEvent.ASR)
    MethodSection(stringResource(R.string.prayer_method_step_asr_title), stringResource(R.string.prayer_method_step_asr_body)) {
        Formula(
            "a = atan(1 / (1 + tan|φ − δ|))",
            "  = atan(1 / (1 + tan %.3f°))".us(abs(location.latitude - explanation.declinationDeg)),
            "  = %.3f°".us(explanation.asrAltitudeDeg),
            "H = %.3f°  →  %s".us(asr.hourAngleDeg ?: 0.0, duration((asr.hourAngleDeg ?: 0.0) * 4)),
            "t = ${clock(noon)} + ${duration((asr.hourAngleDeg ?: 0.0) * 4)}",
        )
        EventResult(names.getValue(InmEvent.ASR), asr)
    }

    MethodSection(stringResource(R.string.prayer_method_step_horizon_title), stringResource(R.string.prayer_method_step_horizon_body)) {
        val sunriseDip = if (explanation.sunriseDipDeg != explanation.dipDeg) {
            listOf("d(sunrise) = %.3f°".us(explanation.sunriseDipDeg))
        } else {
            emptyList()
        }
        Formula(
            *(listOf(
                "d = acos(R / (R + h))",
                "  = %.3f°   (R = 6378137 m)".us(explanation.dipDeg),
                "a = −(0.83° + d) = %.3f°".us(steps.getValue(InmEvent.MAGHRIB).altitudeDeg),
            ) + sunriseDip).toTypedArray(),
        )
        EventResult(names.getValue(InmEvent.SUNRISE), steps.getValue(InmEvent.SUNRISE))
        EventResult(names.getValue(InmEvent.MAGHRIB), steps.getValue(InmEvent.MAGHRIB))
    }

    MethodSection(stringResource(R.string.prayer_method_step_twilight_title), stringResource(R.string.prayer_method_step_twilight_body)) {
        Formula("a = −(18° + d) = %.3f°".us(steps.getValue(InmEvent.FAJR).altitudeDeg))
        EventResult(names.getValue(InmEvent.FAJR), steps.getValue(InmEvent.FAJR))
        EventResult(names.getValue(InmEvent.ISHA), steps.getValue(InmEvent.ISHA))
        Text(stringResource(R.string.prayer_method_step_iteration_note), fontSize = 12.sp, color = TextMuted, lineHeight = 17.sp)
    }

    MethodSection(stringResource(R.string.prayer_method_step_rounding_title), stringResource(R.string.prayer_method_step_rounding_body)) {
        ResultTable(explanation.events, names)
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
        TrustLine(stringResource(R.string.prayer_method_trust_2026))
        TrustLine(stringResource(R.string.prayer_method_trust_past))
    }
}

@Composable
private fun TrustLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("✓", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GreenPrimary)
        Text(text, fontSize = 13.sp, color = TextDark, lineHeight = 19.sp, modifier = Modifier.weight(1f))
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            lines.forEach { line ->
                Text(line, style = LtrMonospace, color = TextDark)
            }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = PrayerSilencePalette.SecondaryText, modifier = Modifier.weight(1f))
        Text(value, style = LtrMonospace, color = TextDark)
    }
}

/** One time's result: its hour angle when it has one, the exact time and the rounded time shown in the app. */
@Composable
private fun EventResult(name: String, step: InmEventStep) {
    val angle = step.hourAngleDeg?.let { "H = %.3f°   ".us(it) }.orEmpty()
    ValueRow(name, "$angle${clock(step.exactMinutes)} → ${hhmm(step.shownMinutes)}")
}

@Composable
private fun ResultTable(events: List<InmEventStep>, names: Map<InmEvent, String>) {
    Column(Modifier.fillMaxWidth()) {
        TableRow(
            stringResource(R.string.prayer_method_table_prayer),
            stringResource(R.string.prayer_method_table_altitude),
            stringResource(R.string.prayer_method_table_exact),
            stringResource(R.string.prayer_method_table_shown),
            header = true,
        )
        events.forEach { step ->
            HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
            TableRow(
                names.getValue(step.event),
                "%.2f°".us(step.altitudeDeg),
                clock(step.exactMinutes),
                hhmm(step.shownMinutes),
            )
        }
    }
}

@Composable
private fun TableRow(name: String, altitude: String, exact: String, shown: String, header: Boolean = false) {
    val size = if (header) 11.sp else 13.sp
    val cellStyle = if (header) TextStyle(fontSize = size, color = TextMuted) else LtrMonospace.copy(color = TextDark)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            fontSize = size,
            color = if (header) TextMuted else TextDark,
            fontWeight = if (header) FontWeight.Normal else FontWeight.Bold,
            modifier = Modifier.weight(1.1f),
        )
        Text(altitude, style = cellStyle, textAlign = TextAlign.Center, modifier = Modifier.weight(1.1f))
        Text(exact, style = cellStyle, textAlign = TextAlign.Center, modifier = Modifier.weight(1.2f))
        Text(
            shown,
            style = if (header) cellStyle else cellStyle.copy(color = GreenPrimaryDark, fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(0.9f),
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
    val startMinutes = floor((steps.getValue(InmEvent.FAJR).exactMinutes - 60) / 60).coerceAtLeast(0.0) * 60
    val endMinutes = ceil((steps.getValue(InmEvent.ISHA).exactMinutes + 60) / 60).coerceAtMost(24.0) * 60
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
    val maxAltitude = path.maxOf { it.second }
    val minAltitude = -32.0
    val topAltitude = maxAltitude + 14
    val twilightAltitude = steps.getValue(InmEvent.FAJR).altitudeDeg
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = TextDark, textAlign = TextAlign.Center)
    val lineLabelStyle = TextStyle(fontSize = 10.sp, color = PrayerSilencePalette.SecondaryText)
    val axisStyle = TextStyle(fontSize = 10.sp, color = TextMuted)
    val horizonLabel = stringResource(R.string.prayer_method_chart_horizon)
    val twilightLabel = stringResource(R.string.prayer_method_chart_twilight)
    val asrLabel = stringResource(R.string.prayer_method_chart_asr)
    val description = stringResource(R.string.prayer_method_chart_description)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .semantics { contentDescription = description },
        ) {
            val axisBand = 18.dp.toPx()
            val plotBottom = size.height - axisBand
            fun x(minutes: Double) = ((minutes - startMinutes) / (endMinutes - startMinutes) * size.width).toFloat()
            fun y(altitude: Double) = ((topAltitude - altitude) / (topAltitude - minAltitude) * plotBottom).toFloat()
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

            // Hour ticks every three hours.
            var tick = ceil(startMinutes / 180) * 180
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
                tick += 180
            }

            // Each time on the curve, labelled where the curve leaves room.
            val gap = 6.dp.toPx()
            markers.forEach { (step, altitude) ->
                val center = Offset(x(step.exactMinutes), y(altitude))
                drawCircle(Color.White, radius = 6.dp.toPx(), center = center)
                drawCircle(PrayerSilencePalette.PrimaryText, radius = 4.dp.toPx(), center = center)
                val layout = measurer.measure("${names.getValue(step.event)}\n${hhmm(step.shownMinutes)}", labelStyle)
                val w = layout.size.width.toFloat()
                val h = layout.size.height.toFloat()
                val topLeft = when (step.event) {
                    InmEvent.FAJR, InmEvent.ISHA -> Offset(center.x - w / 2, center.y + gap)
                    InmEvent.DHUHR -> Offset(center.x - w / 2, center.y - h - gap)
                    InmEvent.SUNRISE -> Offset(center.x - w - gap, center.y - h - gap / 2)
                    InmEvent.ASR -> Offset(center.x + gap, center.y - h / 2)
                    InmEvent.MAGHRIB -> Offset(center.x + gap, center.y - h - gap / 2)
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

private fun String.us(vararg args: Any): String = String.format(Locale.US, this, *args)

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
