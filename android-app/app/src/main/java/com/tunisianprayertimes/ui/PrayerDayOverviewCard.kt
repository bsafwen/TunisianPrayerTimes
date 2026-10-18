package com.tunisianprayertimes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal data class NightThirdSegment(
    val title: String,
    val startTime: String,
    val endTime: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val active: Boolean,
)

internal data class NightTimelineAlarm(
    val id: String,
    val wakeAlarmId: String,
    val title: String,
    val time: String,
    val minuteOfDay: Int,
    val isSubAlarm: Boolean,
)

internal data class NightTimelinePrayer(
    val prayer: Prayer,
    val title: String,
    val time: String,
    /** Counted from the selected day's midnight, so past 1440 for an Isha after midnight. */
    val minuteOfDay: Int,
)

internal data class NightTimesCardData(
    val sunriseTime: String,
    val sunriseMinuteOfDay: Int,
    val currentMinuteOfDay: Int,
    val showCurrentTime: Boolean,
    val nightRange: String,
    val statusText: String,
    val activeThird: Int?,
    val thirds: List<NightThirdSegment>,
    val timelinePrayers: List<NightTimelinePrayer>,
    val timelineAlarms: List<NightTimelineAlarm>,
    val selectedDateLabel: String,
    val currentTimeText: String,
    val sunAltitudeDeg: Double?,
)

private val OverviewCream = Color(0xFFFAF8F1)
private val OverviewBorder = Color(0xFFE7E8DF)
private val OverviewDaylight = Color(0xFFE5C780)
private val OverviewNight = Color(0xFF234C4C)
private val OverviewAlarm = Color(0xFFAA7B27)
private val OverviewShape = RoundedCornerShape(16.dp)

/** A readable daily summary; the dial is a map, while alarm actions live in the list. */
@Composable
internal fun PrayerDayOverviewCard(
    data: NightTimesCardData,
    onEditWakeAlarm: (String) -> Unit,
) {
    var showAlarms by remember { mutableStateOf(false) }
    val alarms = remember(data.timelineAlarms) { data.timelineAlarms.sortedBy { it.minuteOfDay } }
    val sunset = data.timelinePrayers.firstOrNull { it.prayer == Prayer.MAGHRIB }
    val sunsetTime = sunset?.time ?: data.thirds.firstOrNull()?.startTime.orEmpty()
    val sunsetMinute = sunset?.minuteOfDay ?: data.thirds.firstOrNull()?.startMinuteOfDay ?: data.sunriseMinuteOfDay

    if (showAlarms) {
        DayOverviewAlarmsSheet(
            data = data,
            alarms = alarms,
            selectedDateLabel = data.selectedDateLabel,
            onDismiss = { showAlarms = false },
            onEditWakeAlarm = { alarmId ->
                showAlarms = false
                onEditWakeAlarm(alarmId)
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, OverviewBorder),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            val roomyText = LocalDensity.current.fontScale > 1.3f || maxWidth < 280.dp
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = stringResource(
                            if (data.showCurrentTime) R.string.prayer_day_overview_today
                            else R.string.prayer_day_overview_selected,
                        ),
                        fontSize = 19.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = GreenPrimaryDark,
                    )
                    Text(data.selectedDateLabel, color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                }
                Column(
                    modifier = Modifier.fillMaxWidth().clip(OverviewShape).background(OverviewCream).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (roomyText) DayOverviewClock(data, Modifier.fillMaxWidth())
                    DayOverviewDial(data, sunsetMinute, sunsetTime, roomyText)
                    if (data.showCurrentTime) {
                        data.sunAltitudeDeg?.let { altitude ->
                            DayOverviewSunAngle(altitude, stacked = roomyText)
                        }
                    }
                    DayOverviewSunTimes(data.sunriseTime, sunsetTime, stacked = roomyText)
                    DayOverviewLegend(stacked = roomyText)
                }
                DayOverviewAlarmSummary(data, alarms, roomyText, onShowAlarms = { showAlarms = true })
                if (data.thirds.isNotEmpty()) {
                    HorizontalDivider(color = OverviewBorder)
                    DayOverviewNight(data, stacked = roomyText)
                }
            }
        }
    }
}

@Composable
private fun DayOverviewClock(data: NightTimesCardData, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(if (data.showCurrentTime) R.string.prayer_day_overview_now else R.string.prayer_day_overview_24_hours),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (data.showCurrentTime) data.currentTimeText else data.selectedDateLabel,
            fontSize = if (data.showCurrentTime) 34.sp else 15.sp,
            lineHeight = if (data.showCurrentTime) 43.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            color = GreenPrimaryDark,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge.copy(
                textDirection = if (data.showCurrentTime) TextDirection.Ltr else TextDirection.Content,
            ),
        )
        if (data.showCurrentTime) {
            val sunset = data.timelinePrayers.firstOrNull { it.prayer == Prayer.MAGHRIB }?.minuteOfDay
            val isDaytime = sunset != null && data.currentMinuteOfDay >= data.sunriseMinuteOfDay && data.currentMinuteOfDay < sunset
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(if (isDaytime) R.drawable.ic_adhkar_sun else R.drawable.ic_adhkar_moon),
                    null, tint = if (isDaytime) OverviewAlarm else OverviewNight, modifier = Modifier.size(15.dp),
                )
                Text(
                    stringResource(if (isDaytime) R.string.prayer_day_overview_daytime else R.string.prayer_day_overview_nighttime),
                    color = TextMuted, fontSize = 11.sp, lineHeight = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun DayOverviewDial(
    data: NightTimesCardData,
    sunsetMinute: Int,
    sunsetTime: String,
    externalClock: Boolean,
) {
    val description = stringResource(
        R.string.prayer_day_overview_dial_description,
        data.sunriseTime,
        sunsetTime,
        data.timelinePrayers.joinToString("، ") { "${it.title} ${it.time}" },
        data.timelineAlarms.size,
    ) + if (data.showCurrentTime) " " + stringResource(R.string.prayer_day_overview_current_description, data.currentTimeText) else ""
    val sunDescription = if (data.showCurrentTime && data.sunAltitudeDeg != null) {
        " " + stringResource(R.string.prayer_day_overview_sun_angle_description, formatSolarAngle(data.sunAltitudeDeg))
    } else ""
    Box(
        modifier = Modifier.widthIn(max = 252.dp).fillMaxWidth().aspectRatio(1f)
            .clearAndSetSemantics { contentDescription = description + sunDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.40f
            val ringWidth = 13.dp.toPx()
            fun angle(minute: Int) = (minute / 1440f * 360f + 90f)
            fun point(minute: Int, distance: Float): Offset {
                val radians = angle(minute) * PI / 180.0
                return center + Offset(cos(radians).toFloat() * distance, sin(radians).toFloat() * distance)
            }
            drawCircle(Color.White, radius = radius - ringWidth / 2f - 4.dp.toPx(), center = center)
            drawCircle(OverviewNight, radius, center, style = Stroke(ringWidth))
            val daylightMinutes = Math.floorMod(sunsetMinute - data.sunriseMinuteOfDay, 1440)
            drawArc(
                color = OverviewDaylight,
                startAngle = angle(data.sunriseMinuteOfDay),
                sweepAngle = daylightMinutes / 1440f * 360f,
                useCenter = false,
                topLeft = center - Offset(radius, radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(ringWidth, cap = StrokeCap.Butt),
            )
            for (hour in 0 until 24) {
                val major = hour % 6 == 0
                drawLine(
                    color = if (major) GreenPrimaryDark.copy(alpha = 0.55f) else GreenPrimaryDark.copy(alpha = 0.18f),
                    start = point(hour * 60, radius + ringWidth / 2f + 4.dp.toPx()),
                    end = point(hour * 60, radius + ringWidth / 2f + (if (major) 10.dp else 7.dp).toPx()),
                    strokeWidth = (if (major) 1.5.dp else 1.dp).toPx(),
                    cap = StrokeCap.Round,
                )
            }
            // Separate radii keep prayer and alarm markers legible when their times coincide.
            data.timelinePrayers.forEach { prayer ->
                val marker = point(prayer.minuteOfDay, radius)
                drawCircle(Color.White, 5.5.dp.toPx(), marker)
                drawCircle(GreenPrimary, 3.5.dp.toPx(), marker)
            }
            data.timelineAlarms.forEach { alarm ->
                val marker = point(alarm.minuteOfDay, radius - ringWidth / 2f - 7.dp.toPx())
                drawCircle(Color.White, 4.dp.toPx(), marker)
                drawCircle(OverviewAlarm, 2.7.dp.toPx(), marker)
            }
            if (data.showCurrentTime) {
                val tip = point(data.currentMinuteOfDay, radius + ringWidth / 2f + 2.dp.toPx())
                drawLine(
                    color = GreenPrimaryDark,
                    start = point(data.currentMinuteOfDay, radius - ringWidth / 2f - 17.dp.toPx()),
                    end = tip,
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                if (data.sunAltitudeDeg != null) {
                    // The Sun moves around this time dial; its actual elevation is shown below.
                    val sunCenter = point(data.currentMinuteOfDay, radius)
                    drawCircle(Color.White, 10.dp.toPx(), sunCenter)
                    for (ray in 0 until 8) {
                        val radians = ray * PI / 4.0
                        val direction = Offset(cos(radians).toFloat(), sin(radians).toFloat())
                        drawLine(
                            color = OverviewAlarm,
                            start = sunCenter + direction * 6.dp.toPx(),
                            end = sunCenter + direction * 8.dp.toPx(),
                            strokeWidth = 1.4.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                    drawCircle(OverviewAlarm, 4.dp.toPx(), sunCenter)
                } else {
                    drawCircle(Color.White, 5.dp.toPx(), tip)
                    drawCircle(GreenPrimaryDark, 3.dp.toPx(), tip)
                }
            }
        }
        if (externalClock) {
            Icon(
                painterResource(R.drawable.ic_adhkar_clock),
                contentDescription = null,
                tint = GreenPrimaryDark.copy(alpha = 0.35f),
                modifier = Modifier.size(44.dp),
            )
        } else {
            DayOverviewClock(data, Modifier.fillMaxWidth(0.56f))
        }
        // Fixed clock anchors are decorative; the dial exposes one complete spoken summary.
        if (!externalClock) {
            DialHour("12", Modifier.align(Alignment.TopCenter))
            DialHour("18", Modifier.align(AbsoluteAlignment.CenterRight))
            DialHour("00", Modifier.align(Alignment.BottomCenter))
            DialHour("06", Modifier.align(AbsoluteAlignment.CenterLeft))
        }
    }
}

@Composable
private fun DayOverviewSunAngle(altitude: Double, stacked: Boolean) {
    val angle = formatSolarAngle(altitude)
    val description = stringResource(R.string.prayer_day_overview_sun_angle_description, angle)
    val modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }
    if (stacked) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.prayer_day_overview_sun_angle), color = TextMuted, fontSize = 12.sp)
            OverviewTime(angle, fontSize = 22, color = OverviewAlarm)
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.prayer_day_overview_sun_angle), color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.width(10.dp))
            OverviewTime(angle, fontSize = 22, color = OverviewAlarm)
        }
    }
}

private fun formatSolarAngle(degrees: Double): String =
    String.format(Locale.US, "%.1f°", if (abs(degrees) < 0.05) 0.0 else degrees)

@Composable
private fun DialHour(value: String, modifier: Modifier) {
    Text(
        value,
        modifier = modifier,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        color = TextMuted,
        fontWeight = FontWeight.Medium,
        style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr),
    )
}

@Composable
private fun DayOverviewSunTimes(sunrise: String, sunset: String, stacked: Boolean) {
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SunTime(R.drawable.ic_prayer_dhuhr_sun, stringResource(R.string.prayer_day_overview_sunrise), sunrise)
            SunTime(R.drawable.ic_prayer_maghrib_sunset, stringResource(R.string.prayer_day_overview_sunset), sunset)
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SunTime(R.drawable.ic_prayer_dhuhr_sun, stringResource(R.string.prayer_day_overview_sunrise), sunrise, Modifier.weight(1f))
            SunTime(R.drawable.ic_prayer_maghrib_sunset, stringResource(R.string.prayer_day_overview_sunset), sunset, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SunTime(icon: Int, label: String, time: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, tint = OverviewAlarm, modifier = Modifier.size(23.dp))
        Column {
            Text(label, color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp)
            OverviewTime(time, fontSize = 17)
        }
    }
}

@Composable
private fun DayOverviewLegend(stacked: Boolean) {
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            LegendDot(GreenPrimary, stringResource(R.string.prayer_day_overview_prayers))
            LegendDot(OverviewAlarm, stringResource(R.string.prayer_day_overview_alarms))
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendDot(GreenPrimary, stringResource(R.string.prayer_day_overview_prayers))
            LegendDot(OverviewAlarm, stringResource(R.string.prayer_day_overview_alarms))
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(label, fontSize = 11.sp, lineHeight = 17.sp, color = TextMuted)
    }
}

@Composable
private fun DayOverviewAlarmSummary(
    data: NightTimesCardData,
    alarms: List<NightTimelineAlarm>,
    stacked: Boolean,
    onShowAlarms: () -> Unit,
) {
    val nextAlarm = if (data.showCurrentTime) alarms.firstOrNull { it.minuteOfDay >= data.currentMinuteOfDay } else null
    Column(
        modifier = Modifier.fillMaxWidth().clip(OverviewShape).background(GreenPrimary.copy(alpha = 0.05f))
            .border(BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.10f)), OverviewShape).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (nextAlarm != null) {
            Text(stringResource(R.string.prayer_day_overview_next_alarm), color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
            if (stacked) {
                OverviewTime(nextAlarm.time, fontSize = 22)
                Text(nextAlarm.title, color = GreenPrimaryDark, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 21.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nextAlarm.title, modifier = Modifier.weight(1f), color = GreenPrimaryDark, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 21.sp)
                    OverviewTime(nextAlarm.time, fontSize = 22)
                }
            }
        } else if (alarms.isNotEmpty()) {
            Text(
                stringResource(if (data.showCurrentTime) R.string.prayer_day_overview_no_more_alarms else R.string.prayer_day_overview_alarms),
                color = GreenPrimaryDark, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium,
            )
        } else {
            Text(stringResource(R.string.prayer_day_overview_no_alarms), fontSize = 13.sp, lineHeight = 21.sp, color = TextMuted)
        }
        if (alarms.isNotEmpty()) {
            Text(stringResource(R.string.prayer_day_overview_alarm_count, alarms.size), color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        TextButton(onClick = onShowAlarms, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.ic_adhkar_clock), null, tint = GreenPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.prayer_day_overview_show_details), color = GreenPrimary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DayOverviewNight(data: NightTimesCardData, stacked: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_adhkar_moon), null, tint = GreenPrimary, modifier = Modifier.size(21.dp))
            Text(stringResource(R.string.prayer_day_overview_night), color = GreenPrimaryDark, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "${data.statusText} · ${data.nightRange}",
            fontSize = 12.sp, lineHeight = 19.sp, color = TextMuted,
        )
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                data.thirds.forEach { NightThirdTile(it, Modifier.fillMaxWidth(), stacked = true) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                data.thirds.forEach { NightThirdTile(it, Modifier.weight(1f), stacked = false) }
            }
        }
    }
}

@Composable
private fun NightThirdTile(third: NightThirdSegment, modifier: Modifier, stacked: Boolean) {
    val description = stringResource(
        if (third.active) R.string.prayer_day_overview_third_active_description else R.string.prayer_day_overview_third_description,
        third.title, third.startTime, third.endTime,
    )
    val foreground = if (third.active) Color.White else GreenPrimaryDark
    val tileModifier = modifier.clip(RoundedCornerShape(12.dp))
        .background(if (third.active) GreenPrimaryDark else OverviewCream)
        .border(BorderStroke(1.dp, if (third.active) GreenPrimaryDark else OverviewBorder), RoundedCornerShape(12.dp))
        .clearAndSetSemantics { contentDescription = description }
        .padding(horizontal = 8.dp, vertical = 9.dp)
    if (stacked) {
        Column(tileModifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(third.title, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = foreground)
            OverviewTime("${third.startTime} – ${third.endTime}", fontSize = 14, color = foreground)
        }
    } else {
        Column(tileModifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(third.title, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = foreground, textAlign = TextAlign.Center)
            OverviewTime(third.startTime, fontSize = 14, color = foreground)
            Text(
                stringResource(R.string.prayer_day_overview_until, third.endTime),
                fontSize = 12.sp, lineHeight = 17.sp, color = foreground.copy(alpha = 0.72f), textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayOverviewAlarmsSheet(
    data: NightTimesCardData,
    alarms: List<NightTimelineAlarm>,
    selectedDateLabel: String,
    onDismiss: () -> Unit,
    onEditWakeAlarm: (String) -> Unit,
) {
    val sunriseTitle = stringResource(R.string.prayer_day_overview_sunrise)
    val events = remember(data.timelinePrayers, alarms, data.sunriseTime, data.sunriseMinuteOfDay, sunriseTitle) {
        (data.timelinePrayers.map { prayer ->
            DayOverviewEvent(
                id = "prayer:${prayer.prayer.name}", title = prayer.title,
                time = prayer.time, minuteOfDay = prayer.minuteOfDay,
                icon = when (prayer.prayer) {
                    Prayer.FAJR -> R.drawable.ic_prayer_fajr_twilight
                    Prayer.DHUHR, Prayer.JOMOAA -> R.drawable.ic_prayer_dhuhr_sun
                    Prayer.ASR -> R.drawable.ic_prayer_asr_afternoon
                    Prayer.MAGHRIB -> R.drawable.ic_prayer_maghrib_sunset
                    else -> R.drawable.ic_prayer_isha_night
                },
            )
        } + DayOverviewEvent(
            id = "sunrise", title = sunriseTitle, time = data.sunriseTime,
            minuteOfDay = data.sunriseMinuteOfDay, icon = R.drawable.ic_prayer_dhuhr_sun,
        ) + alarms.map { alarm ->
            DayOverviewEvent(
                id = "alarm:${alarm.id}", title = alarm.title, time = alarm.time,
                minuteOfDay = alarm.minuteOfDay, icon = R.drawable.ic_tab_alarms, alarm = alarm,
            )
        }).sortedBy { it.minuteOfDay }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.prayer_day_overview_details), color = GreenPrimaryDark, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                    Text(selectedDateLabel, color = TextMuted, fontSize = 14.sp, lineHeight = 21.sp)
                    Text(stringResource(R.string.prayer_day_overview_alarm_count, alarms.size), color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp)
                }
            }
            items(events, key = { it.id }) { event ->
                if (event.alarm != null) DayOverviewAlarmRow(event.alarm, onEditWakeAlarm)
                else DayOverviewPrayerRow(event)
            }
        }
    }
}

private data class DayOverviewEvent(
    val id: String,
    val title: String,
    val time: String,
    val minuteOfDay: Int,
    val icon: Int,
    val alarm: NightTimelineAlarm? = null,
)

@Composable
private fun DayOverviewPrayerRow(event: DayOverviewEvent) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val stacked = LocalDensity.current.fontScale > 1.3f || maxWidth < 280.dp
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(event.icon), null, tint = GreenPrimary, modifier = Modifier.size(24.dp))
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(event.title, color = GreenPrimaryDark, fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
                    OverviewTime(event.time, fontSize = 20)
                }
            } else {
                Text(event.title, modifier = Modifier.weight(1f), color = GreenPrimaryDark, fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
                OverviewTime(event.time, fontSize = 20)
            }
        }
    }
}

@Composable
private fun DayOverviewAlarmRow(alarm: NightTimelineAlarm, onEditWakeAlarm: (String) -> Unit) {
    val actionLabel = stringResource(R.string.prayer_day_overview_edit_named_alarm, alarm.title, alarm.time)
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().clip(OverviewShape)
            .background(if (alarm.isSubAlarm) OverviewCream else GreenPrimary.copy(alpha = 0.05f))
            .border(BorderStroke(1.dp, OverviewBorder), OverviewShape)
            .clickable(role = Role.Button, onClickLabel = actionLabel) { onEditWakeAlarm(alarm.wakeAlarmId) }
            .heightIn(min = 72.dp).padding(12.dp),
    ) {
        val stacked = LocalDensity.current.fontScale > 1.3f || maxWidth < 280.dp
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (stacked) {
                OverviewTime(alarm.time, fontSize = 22)
                Text(alarm.title, color = GreenPrimaryDark, fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(alarm.title, modifier = Modifier.weight(1f), color = GreenPrimaryDark, fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
                    OverviewTime(alarm.time, fontSize = 22)
                }
            }
            Text(
                stringResource(if (alarm.isSubAlarm) R.string.prayer_day_overview_extra_alarm else R.string.prayer_day_overview_main_alarm),
                color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp,
            )
            Text(stringResource(R.string.prayer_day_overview_edit_alarm), color = GreenPrimary, fontSize = 12.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun OverviewTime(time: String, fontSize: Int, color: Color = GreenPrimaryDark) {
    Text(
        text = time,
        fontSize = fontSize.sp,
        lineHeight = (fontSize + 5).sp,
        fontWeight = FontWeight.Bold,
        color = color,
        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
    )
}
