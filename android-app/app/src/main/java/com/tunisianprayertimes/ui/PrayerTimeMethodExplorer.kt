package com.tunisianprayertimes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.InmDayExplanation
import com.tunisianprayertimes.InmEvent
import com.tunisianprayertimes.InmEventStep
import com.tunisianprayertimes.InmLocation
import com.tunisianprayertimes.InmPrayerFormula
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.tan

/** Explore one INM event at a time, with the solar event separated from prayer-time offsets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrayerTimeMethodExplorer(location: InmLocation, date: LocalDate, explanation: InmDayExplanation) {
    var selectedName by rememberSaveable(location, date) { mutableStateOf(InmEvent.FAJR.name) }
    val selected = InmEvent.valueOf(selectedName)
    val step = explanation.events.first { it.event == selected }
    val name = explorerEventName(selected)

    Column(Modifier.fillMaxWidth().testTag("prayer_method_explorer"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            explanation.events.forEach { event ->
                FilterChip(
                    selected = selected == event.event,
                    onClick = { selectedName = event.event.name },
                    label = {
                        Column(Modifier.padding(vertical = 5.dp)) {
                            Text(explorerEventName(event.event), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(explorerClock(event.shownMinutes), style = ExplorerTimeStyle.copy(fontSize = 13.sp))
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrayerSilencePalette.PrimaryText,
                        selectedLabelColor = Color.White,
                        labelColor = TextDark,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("prayer_method_event_${event.event.name}"),
                )
            }
        }

        ExplorerSunChart(location, date, explanation, step, name)
        Text(stringResource(R.string.prayer_explorer_chart_caption), fontSize = 12.sp, color = TextMuted, lineHeight = 18.sp)

        Column(
            Modifier.fillMaxWidth().background(PrayerSilencePalette.TintedStrip, RoundedCornerShape(14.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = GreenPrimaryDark, modifier = Modifier.semantics { heading() })
                Text(explorerClock(step.shownMinutes), style = ExplorerTimeStyle.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = GreenPrimaryDark)
            }
            Text(
                stringResource(
                    when (selected) {
                        InmEvent.FAJR -> R.string.prayer_explorer_fajr
                        InmEvent.SUNRISE -> R.string.prayer_explorer_sunrise
                        InmEvent.DHUHR -> R.string.prayer_explorer_dhuhr
                        InmEvent.ASR -> R.string.prayer_explorer_asr
                        InmEvent.MAGHRIB -> R.string.prayer_explorer_maghrib
                        InmEvent.ISHA -> R.string.prayer_explorer_isha
                    },
                ),
                fontSize = 15.sp,
                color = TextDark,
                lineHeight = 24.sp,
                modifier = Modifier.testTag("prayer_method_event_explanation"),
            )
            when (selected) {
                InmEvent.DHUHR -> ExplorerOffset(
                    stringResource(R.string.prayer_explorer_noon), explanation.solarNoonMinutes,
                    stringResource(R.string.prayer_explorer_dhuhr_addition),
                )
                InmEvent.MAGHRIB -> ExplorerOffset(
                    stringResource(R.string.prayer_explorer_sunset), step.exactMinutes - 2.0,
                    stringResource(R.string.prayer_explorer_maghrib_addition),
                )
                InmEvent.ASR -> ExplorerShadow(location.latitude, explanation.declinationDeg)
                else -> Unit
            }
        }
    }
}

@Composable
private fun ExplorerOffset(label: String, minutes: Double, addition: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.prayer_explorer_marker_time, label, explorerSeconds(minutes)), fontSize = 13.sp, color = TextMuted, lineHeight = 20.sp)
        Text(addition, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = GreenPrimaryDark, lineHeight = 21.sp)
    }
}

@Composable
private fun ExplorerSunChart(
    location: InmLocation,
    date: LocalDate,
    explanation: InmDayExplanation,
    step: InmEventStep,
    name: String,
) {
    val samples = remember(location, date) {
        (0..288).map { index ->
            val minutes = index * 5.0
            minutes to InmPrayerFormula.sunAltitudeDeg(location, date.year, date.monthValue, date.dayOfMonth, minutes)
        }
    }
    val markerMinutes = when (step.event) {
        InmEvent.DHUHR -> explanation.solarNoonMinutes
        InmEvent.MAGHRIB -> step.exactMinutes - 2.0
        else -> step.exactMinutes
    }
    val markerAltitude = remember(location, date, markerMinutes) {
        InmPrayerFormula.sunAltitudeDeg(location, date.year, date.monthValue, date.dayOfMonth, markerMinutes)
    }
    val markerName = when (step.event) {
        InmEvent.DHUHR -> stringResource(R.string.prayer_explorer_noon)
        InmEvent.MAGHRIB -> stringResource(R.string.prayer_explorer_sunset)
        else -> name
    }
    val description = stringResource(
        R.string.prayer_explorer_chart_description, markerName, explorerSeconds(markerMinutes),
        String.format(Locale.US, "%.1f", abs(markerAltitude)),
        stringResource(if (markerAltitude >= 0) R.string.prayer_explorer_above else R.string.prayer_explorer_below),
    )
    val noonLabel = stringResource(R.string.prayer_explorer_noon)
    val horizonLabel = stringResource(R.string.prayer_explorer_horizon)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 12.sp, color = TextMuted, textDirection = TextDirection.ContentOrRtl)
    val timeStyle = ExplorerTimeStyle.copy(fontSize = 11.sp, color = TextMuted)
    val chartHeight = with(LocalDensity.current) { 204.dp + 54.sp.toDp() }

    // Coordinates are chronological, so the time axis stays left-to-right inside the Arabic page.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(Modifier.fillMaxWidth().height(chartHeight).semantics { contentDescription = description }) {
            val tickSize = measurer.measure("00:00", timeStyle).size
            val noonText = measurer.measure(noonLabel, labelStyle)
            val horizonText = measurer.measure(horizonLabel, labelStyle)
            val gap = 8.dp.toPx()
            val left = tickSize.width / 2f + 2.dp.toPx()
            val right = size.width - left
            val top = noonText.size.height + gap * 2
            val bottom = size.height - tickSize.height - gap
            val highest = maxOf(10.0, samples.maxOf { it.second } + 8.0)
            val lowest = minOf(-25.0, samples.minOf { it.second } - 5.0)
            fun x(minutes: Double) = left + (minutes / 1440 * (right - left)).toFloat()
            fun y(altitude: Double) = top + ((highest - altitude) / (highest - lowest) * (bottom - top)).toFloat()
            val horizonY = y(0.0)
            val noonX = x(explanation.solarNoonMinutes)
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))

            drawRect(Color(0xFFFFF5DE), Offset(left, top), Size(right - left, horizonY - top))
            drawRect(Color(0xFFE8F2EF), Offset(left, horizonY), Size(right - left, bottom - horizonY))
            drawLine(PrayerSilencePalette.Tick, Offset(left, horizonY), Offset(right, horizonY), 1.dp.toPx())
            drawLine(PrayerSilencePalette.Tick, Offset(noonX, top), Offset(noonX, bottom), 1.dp.toPx(), pathEffect = dash)
            drawText(noonText, topLeft = Offset((noonX - noonText.size.width / 2f).coerceIn(0f, maxOf(0f, size.width - noonText.size.width)), 0f))
            drawText(horizonText, topLeft = Offset(left + 4.dp.toPx(), horizonY - horizonText.size.height - 4.dp.toPx()))

            val curve = Path().apply {
                samples.forEachIndexed { index, (minutes, altitude) ->
                    if (index == 0) moveTo(x(minutes), y(altitude)) else lineTo(x(minutes), y(altitude))
                }
            }
            val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
            clipRect(left, top, right, horizonY) { drawPath(curve, PrayerSilencePalette.GoldAccent, style = stroke) }
            clipRect(left, horizonY, right, bottom) { drawPath(curve, PrayerSilencePalette.InteractiveTeal, style = stroke) }

            val selectedPoint = Offset(x(markerMinutes), y(markerAltitude))
            drawLine(PrayerSilencePalette.InteractiveTeal.copy(alpha = 0.45f), selectedPoint, Offset(selectedPoint.x, bottom), 1.dp.toPx(), pathEffect = dash)
            drawCircle(PrayerSilencePalette.InteractiveTeal.copy(alpha = 0.14f), 13.dp.toPx(), selectedPoint)
            drawCircle(Color.White, 8.dp.toPx(), selectedPoint)
            drawCircle(PrayerSilencePalette.PrimaryText, 5.dp.toPx(), selectedPoint)

            val hourStep = if ((right - left) / 4 >= tickSize.width + gap) 6 else 12
            for (hour in 0..24 step hourStep) {
                val tickX = x(hour * 60.0)
                val tick = measurer.measure(String.format(Locale.US, "%02d:00", hour), timeStyle)
                drawLine(PrayerSilencePalette.Tick, Offset(tickX, bottom), Offset(tickX, bottom + 3.dp.toPx()), 1.dp.toPx())
                drawText(tick, topLeft = Offset(tickX - tick.size.width / 2f, bottom + 5.dp.toPx()))
            }
        }
    }
    Text(stringResource(R.string.prayer_explorer_marker_label, markerName, explorerSeconds(markerMinutes)), fontSize = 13.sp, color = GreenPrimaryDark, lineHeight = 20.sp)
}

/** A one-metre object makes the shadow rule concrete; the added metre is a separate colour. */
@Composable
private fun ExplorerShadow(latitude: Double, declination: Double) {
    val noonShadow = tan(Math.toRadians(abs(latitude - declination))).toFloat().coerceAtLeast(0f)
    val total = noonShadow + 1f
    val formattedNoon = String.format(Locale.US, "%.2f", noonShadow)
    val formattedTotal = String.format(Locale.US, "%.2f", total)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(stringResource(R.string.prayer_explorer_shadow_example), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = GreenPrimaryDark)
        Text(stringResource(R.string.prayer_explorer_shadow_noon, formattedNoon), fontSize = 13.sp, color = TextDark, lineHeight = 20.sp)
        Box(Modifier.fillMaxWidth().height(10.dp)) {
            Box(Modifier.fillMaxWidth((noonShadow / total).coerceIn(0f, 1f)).height(10.dp).background(PrayerSilencePalette.InteractiveTeal, RoundedCornerShape(3.dp)))
        }
        Text(stringResource(R.string.prayer_explorer_shadow_asr, formattedTotal), fontSize = 13.sp, color = TextDark, lineHeight = 20.sp)
        Row(Modifier.fillMaxWidth().height(10.dp)) {
            if (noonShadow > 0) Box(Modifier.weight(noonShadow).height(10.dp).background(PrayerSilencePalette.InteractiveTeal))
            Box(Modifier.weight(1f).height(10.dp).background(PrayerSilencePalette.GoldAccent))
        }
        Text(stringResource(R.string.prayer_explorer_shadow_key), fontSize = 12.sp, color = TextMuted, lineHeight = 18.sp)
    }
}

@Composable
private fun explorerEventName(event: InmEvent): String = stringResource(
    when (event) {
        InmEvent.FAJR -> R.string.prayer_fajr
        InmEvent.SUNRISE -> R.string.adhkar_shuruk
        InmEvent.DHUHR -> R.string.prayer_dhuhr
        InmEvent.ASR -> R.string.prayer_asr
        InmEvent.MAGHRIB -> R.string.prayer_maghrib
        InmEvent.ISHA -> R.string.prayer_isha
    },
)

private val ExplorerTimeStyle = TextStyle(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)

private fun explorerClock(minutes: Int): String = String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

private fun explorerSeconds(minutes: Double): String {
    val seconds = floor(minutes * 60).toInt()
    return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
