package com.tunisianprayertimes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.InmDayExplanation
import com.tunisianprayertimes.InmEvent
import com.tunisianprayertimes.InmEventStep
import com.tunisianprayertimes.InmPrayerFormula
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/*
 * The diagrams of the prayer-time explainer, one per step, drawn from the day's real numbers.
 * Time and directions run left to right like the sun-path chart (morning and east on the left);
 * every label is measured at the user's font scale before space is given to it.
 */

private val DayFill = Color(0xFFFFF3D9)
private val TwilightFill = Color(0xFFDCEDE9)
private val NightFill = Color(0xFFBFD9D4)
private val GroundFill = Color(0xFFF3ECE0)

private val LabelStyle = TextStyle(fontSize = 11.sp, color = TextDark)
private val NumberStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    color = TextDark,
    textDirection = TextDirection.Ltr,
)

/** Step 2: how 12:00 at Greenwich becomes solar noon in Tunisia, then Dhuhr. */
@Composable
internal fun NoonShiftDiagram(longitude: Double, explanation: InmDayExplanation, dhuhr: InmEventStep) {
    val greenwichNoon = 12.0 * 60
    val zoneNoon = greenwichNoon + 60
    val longitudeNoon = zoneNoon - longitude * 4
    val noon = explanation.solarNoonMinutes
    val shifts = listOf(
        NoonShift(stringResource(R.string.prayer_method_noon_zone), greenwichNoon, zoneNoon, PrayerSilencePalette.InteractiveTeal),
        NoonShift(stringResource(R.string.prayer_method_noon_longitude), zoneNoon, longitudeNoon, PrayerSilencePalette.InteractiveTeal),
        NoonShift(stringResource(R.string.prayer_method_noon_eot), longitudeNoon, noon, PrayerSilencePalette.InteractiveTeal),
        NoonShift(stringResource(R.string.prayer_method_noon_margin), noon, dhuhr.exactMinutes, GreenPrimary),
    )
    val low = shifts.minOf { minOf(it.from, it.to) } - 4
    val high = shifts.maxOf { maxOf(it.from, it.to) } + 4
    val description = stringResource(
        R.string.prayer_method_noon_description,
        shifts[1].change(),
        shifts[2].change(),
        clockSeconds(noon),
        clockSeconds(dhuhr.exactMinutes),
    )

    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        shifts.forEach { shift ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(shift.label, fontSize = 12.sp, color = PrayerSilencePalette.SecondaryText, modifier = Modifier.weight(1f))
                Text(shift.change(), style = NumberStyle.copy(fontSize = 12.sp, color = shift.color), softWrap = false, maxLines = 1)
            }
            LeftToRight {
                Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                    fun x(minutes: Double) = ((minutes - low) / (high - low) * size.width).toFloat()
                    val y = size.height / 2
                    drawLine(PrayerSilencePalette.SoftBorder, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    val start = Offset(x(shift.from), y)
                    val end = Offset(x(shift.to), y)
                    drawCircle(shift.color, 3.dp.toPx(), start)
                    drawLine(shift.color, start, end, 2.dp.toPx(), StrokeCap.Round)
                    drawArrowHead(end, if (shift.to >= shift.from) 1f else -1f, shift.color)
                }
            }
        }
        ClockAxis(low, high)
    }
}

private class NoonShift(val label: String, val from: Double, val to: Double, val color: Color) {
    /** The change as the clock shows it, so consecutive rows add up to the times shown elsewhere. */
    fun change(): String {
        val seconds = floor(to * 60).toLong() - floor(from * 60).toLong()
        val sign = if (seconds < 0) "−" else "+"
        val s = abs(seconds)
        return sign + "%d:%02d:%02d".format(Locale.US, s / 3600, s / 60 % 60, s % 60)
    }
}

/** Clock ticks under the noon rows, every 15 minutes or every 30 when 15 would crowd them. */
@Composable
private fun ClockAxis(low: Double, high: Double) {
    val measurer = rememberTextMeasurer()
    val style = NumberStyle.copy(fontSize = 10.sp, color = TextMuted)
    val labelHeight = with(LocalDensity.current) { measurer.measure("00:00", style).size.height.toDp() }
    LeftToRight {
        Canvas(Modifier.fillMaxWidth().height(labelHeight + 8.dp)) {
            fun x(minutes: Double) = ((minutes - low) / (high - low) * size.width).toFloat()
            val labelWidth = measurer.measure("00:00", style).size.width
            val step = if ((labelWidth + 8.dp.toPx()) * (high - low) / size.width < 15) 15 else 30
            var tick = ceil(low / step) * step
            while (tick <= high) {
                drawLine(PrayerSilencePalette.Tick, Offset(x(tick), 0f), Offset(x(tick), 4.dp.toPx()), 1.dp.toPx())
                val layout = measurer.measure(hhmmOf(tick.toInt()), style)
                val left = (x(tick) - layout.size.width / 2f).fitIn(size.width, layout.size.width)
                drawText(layout, topLeft = Offset(left, 6.dp.toPx()))
                tick += step
            }
        }
    }
}

/**
 * Step 3: the sun's daily turn as a dial with solar noon at the top. Each tick is 15°, one hour;
 * each time sits at its hour angle H, before noon on the left and after it on the right.
 */
@Composable
internal fun HourAngleDial(explanation: InmDayExplanation, names: Map<InmEvent, String>) {
    val steps = explanation.events.associateBy { it.event }
    fun angle(event: InmEvent): Float {
        val h = steps.getValue(event).hourAngleDeg ?: 0.0
        return (if (event == InmEvent.FAJR || event == InmEvent.SUNRISE) -h else h).toFloat()
    }
    val events = listOf(InmEvent.FAJR, InmEvent.SUNRISE, InmEvent.ASR, InmEvent.MAGHRIB, InmEvent.ISHA)
    val noonLabel = stringResource(R.string.prayer_method_dial_noon)
    val description = stringResource(
        R.string.prayer_method_dial_description,
        events.joinToString("، ") { "${names.getValue(it)} %.1f°".format(Locale.US, abs(angle(it))) },
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.toFloat()
        val labels = events.associateWith { measurer.measure(names.getValue(it), LabelStyle) }
        val noonLayout = measurer.measure(noonLabel, LabelStyle.copy(color = PrayerSilencePalette.GoldAccent, fontWeight = FontWeight.Bold))
        val hLayout = measurer.measure("H", NumberStyle.copy(color = PrayerSilencePalette.GoldAccent, fontWeight = FontWeight.Bold))
        val gap = with(density) { 8.dp.toPx() }
        val labelWidth = labels.values.maxOf { it.size.width }
        val labelHeight = labels.values.maxOf { it.size.height }
        val radius = min((width - 2 * (labelWidth + gap)) / 2, with(density) { 120.dp.toPx() })
            .coerceAtLeast(with(density) { 48.dp.toPx() })
        val top = noonLayout.size.height + gap
        val center = Offset(width / 2, top + radius)
        fun pointAt(degrees: Float, r: Float) = Offset(
            center.x + r * sin(Math.toRadians(degrees.toDouble())).toFloat(),
            center.y - r * cos(Math.toRadians(degrees.toDouble())).toFloat(),
        )
        // Names outside the circle, pushed apart on each side when two times are close.
        val placed = listOf(events.filter { angle(it) < 0 }, events.filter { angle(it) > 0 }).flatMap { side ->
            separated(
                side.map { event ->
                    val anchor = pointAt(angle(event), radius + gap)
                    val layout = labels.getValue(event)
                    val left = if (angle(event) < 0) anchor.x - layout.size.width else anchor.x
                    PlacedLabel(layout, left, anchor.y - layout.size.height / 2f)
                },
                with(density) { 2.dp.toPx() },
            )
        }
        val height = maxOf(top + 2 * radius + labelHeight / 2, placed.maxOf { it.bottom }) + gap

        LeftToRight {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { height.toDp() })
                    .semantics { contentDescription = description },
            ) {
                // Compose measures arcs clockwise from 3 o'clock; the dial from 12 o'clock.
                fun sector(color: Color, from: Float, to: Float) = drawArc(
                    color = color,
                    startAngle = from - 90,
                    sweepAngle = to - from,
                    useCenter = true,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(2 * radius, 2 * radius),
                )
                drawCircle(NightFill, radius, center)
                sector(TwilightFill, angle(InmEvent.FAJR), angle(InmEvent.SUNRISE))
                sector(DayFill, angle(InmEvent.SUNRISE), angle(InmEvent.MAGHRIB))
                sector(TwilightFill, angle(InmEvent.MAGHRIB), angle(InmEvent.ISHA))
                drawCircle(PrayerSilencePalette.Tick, radius, center, style = Stroke(1.dp.toPx()))
                for (hour in 0 until 24) {
                    val length = if (hour % 6 == 0) 9.dp.toPx() else 5.dp.toPx()
                    drawLine(
                        PrayerSilencePalette.Tick,
                        pointAt(hour * 15f, radius - length),
                        pointAt(hour * 15f, radius),
                        1.dp.toPx(),
                    )
                }

                // H for Asr, as an example of the angle every time is placed by.
                val asr = angle(InmEvent.ASR)
                val arcRadius = radius * 0.45f
                drawArc(
                    color = PrayerSilencePalette.GoldAccent,
                    startAngle = -90f,
                    sweepAngle = asr,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = Size(2 * arcRadius, 2 * arcRadius),
                    style = Stroke(2.dp.toPx()),
                )
                val hAt = pointAt(asr / 2, arcRadius + gap + hLayout.size.width / 2f)
                drawText(hLayout, topLeft = Offset(hAt.x - hLayout.size.width / 2f, hAt.y - hLayout.size.height / 2f))

                drawLine(PrayerSilencePalette.GoldAccent, center, pointAt(0f, radius), 2.dp.toPx())
                events.forEach { drawLine(PrayerSilencePalette.PrimaryText, center, pointAt(angle(it), radius), 1.5.dp.toPx()) }
                drawCircle(PrayerSilencePalette.PrimaryText, 3.dp.toPx(), center)
                drawMarker(pointAt(0f, radius), PrayerSilencePalette.GoldAccent)
                events.forEach { drawMarker(pointAt(angle(it), radius), PrayerSilencePalette.PrimaryText) }

                drawText(noonLayout, topLeft = Offset(center.x - noonLayout.size.width / 2f, 0f))
                placed.forEach { drawLabel(it) }
            }
        }
    }
    Text(stringResource(R.string.prayer_method_dial_caption), fontSize = 12.sp, color = TextMuted, lineHeight = 17.sp)
}

/** Dates in the explainer's diagrams: "21 جوان". */
private val dayMonthFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM", calendarLocale).withDecimalStyle(DecimalStyle.STANDARD)

private fun LocalDate.dayMonth(): String = dayMonthFormatter.format(this)

/**
 * The sun's declination on every day of one year, and the four days the seasons turn on: the
 * highest and the lowest δ, and the two days it crosses zero, when night and day are equal.
 */
internal class SunYear(year: Int) {
    val days: List<LocalDate> = LocalDate.of(year, 1, 1).let { first ->
        List(first.lengthOfYear()) { first.plusDays(it.toLong()) }
    }

    /** δ at 0h UT of each day, as the formula computes it for that day's times. */
    val declinations: List<Double> = days.map {
        InmPrayerFormula.declinationDeg(InmPrayerFormula.julianDay(it.year, it.monthValue, it.dayOfMonth))
    }

    val summer: LocalDate = dayOf(extremeNear(declinations.indices.maxBy { declinations[it] }))
    val winter: LocalDate = dayOf(extremeNear(declinations.indices.minBy { declinations[it] }))
    val spring: LocalDate = dayOf(crossing(upward = true))
    val autumn: LocalDate = dayOf(crossing(upward = false))

    /** The day in Tunisia (UTC+1) of a moment given in days after 0h UT of 1 January. */
    private fun dayOf(moment: Double): LocalDate = days[floor(moment + 1.0 / 24).toInt().coerceIn(days.indices)]

    /** When δ peaks near sample [i]: the vertex of the parabola through it and its neighbours. */
    private fun extremeNear(i: Int): Double {
        val before = declinations[i - 1]
        val at = declinations[i]
        val after = declinations[i + 1]
        return i + (before - after) / (2 * (before - 2 * at + after))
    }

    /** When δ crosses zero, going up if [upward], interpolated between the samples either side. */
    private fun crossing(upward: Boolean): Double {
        val i = (1 until declinations.size).first {
            if (upward) declinations[it - 1] < 0 && declinations[it] >= 0 else declinations[it - 1] > 0 && declinations[it] <= 0
        }
        return i - 1 + declinations[i - 1] / (declinations[i - 1] - declinations[i])
    }
}

/**
 * Step 1, the sky: facing south at noon, the sun stands highest on the first day of summer and
 * lowest on the first day of winter; halfway between are the two days night equals day. δ is
 * today's angle above or below that halfway sun.
 */
@Composable
internal fun DeclinationDiagram(latitude: Double, declination: Double, sunYear: SunYear) {
    val references = listOf(
        Triple(stringResource(R.string.prayer_method_decl_summer), sunYear.summer.dayMonth(), MAX_DECLINATION),
        Triple(
            stringResource(R.string.prayer_method_decl_equinox),
            stringResource(R.string.prayer_method_decl_two_dates, sunYear.spring.dayMonth(), sunYear.autumn.dayMonth()),
            0.0,
        ),
        Triple(stringResource(R.string.prayer_method_decl_winter), sunYear.winter.dayMonth(), -MAX_DECLINATION),
    )
    val horizonLabel = stringResource(R.string.prayer_method_decl_south)
    val placeLabel = stringResource(R.string.prayer_method_decl_place)
    fun noonAltitude(decl: Double) = 90 - latitude + decl
    fun degrees(value: Double) = "%.1f°".format(Locale.US, value)
    val description = stringResource(
        R.string.prayer_method_decl_description,
        degrees(noonAltitude(MAX_DECLINATION)),
        degrees(noonAltitude(0.0)),
        degrees(noonAltitude(-MAX_DECLINATION)),
        degrees(noonAltitude(declination)),
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = constraints.maxWidth.toFloat()
            fun px(dp: Dp) = with(density) { dp.toPx() }
            // Each sun's name with its dates under it in grey.
            val labels = references.map { (name, dates, _) ->
                measurer.measure(
                    buildAnnotatedString {
                        append(name)
                        withStyle(SpanStyle(color = TextMuted)) { append("\n$dates") }
                    },
                    LabelStyle,
                )
            }
            val horizonLayout = measurer.measure(horizonLabel, LabelStyle.copy(color = TextMuted))
            val placeLayout = measurer.measure(placeLabel, LabelStyle.copy(color = TextMuted))
            val deltaLayout = measurer.measure("δ", NumberStyle.copy(color = PrayerSilencePalette.GoldAccent, fontWeight = FontWeight.Bold))
            val sunRadius = px(7.dp)
            val labelGap = px(6.dp)
            val observerX = maxOf(px(12.dp), placeLayout.size.width / 2f)
            val angles = references.map { Math.toRadians(noonAltitude(it.third)) }
            // Labels start this far out along their ray, past today's sun wherever it is on the arc.
            val labelOffset = sunRadius + px(2.dp) + labelGap
            // The rays fan out from the observer; the radius is as large as the widest label allows.
            val radius = references.indices.minOf { i ->
                (width - observerX - labels[i].size.width) / cos(angles[i]).toFloat() - labelOffset
            }.coerceIn(px(80.dp), px(120.dp))
            val top = maxOf(labelOffset * sin(angles[0]).toFloat() + labels[0].size.height / 2f, sunRadius + px(2.dp)) + px(2.dp)
            val ground = top + radius * sin(angles[0]).toFloat()
            fun at(angle: Double, distance: Float) =
                Offset(observerX + distance * cos(angle).toFloat(), ground - distance * sin(angle).toFloat())
            // Each label beside the end of its ray, moved down where the one above would overlap it.
            val placed = separated(
                references.indices.map { i ->
                    val anchor = at(angles[i], radius + labelOffset)
                    PlacedLabel(labels[i], anchor.x.fitIn(width, labels[i].size.width), anchor.y - labels[i].size.height / 2f)
                },
                px(4.dp),
            )
            val groundLabels = ground + px(6.dp) + maxOf(horizonLayout.size.height, placeLayout.size.height)
            val height = maxOf(groundLabels, placed.maxOf { it.bottom }) + px(2.dp)

            LeftToRight {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(with(density) { height.toDp() })
                        .semantics { contentDescription = description },
                ) {
                    val observer = Offset(observerX, ground)
                    val box = Size(2 * radius, 2 * radius)
                    val boxTopLeft = Offset(observer.x - radius, observer.y - radius)

                    // Where the noon sun can be over the year.
                    drawArc(
                        color = PrayerSilencePalette.GoldAccent.copy(alpha = 0.18f),
                        startAngle = -Math.toDegrees(angles[0]).toFloat(),
                        sweepAngle = Math.toDegrees(angles[0] - angles[2]).toFloat(),
                        useCenter = false,
                        topLeft = boxTopLeft,
                        size = box,
                        style = Stroke(10.dp.toPx(), cap = StrokeCap.Round),
                    )
                    references.indices.forEach { i ->
                        val sun = at(angles[i], radius)
                        drawLine(
                            PrayerSilencePalette.Tick,
                            observer,
                            sun,
                            if (i == 1) 1.5.dp.toPx() else 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                        )
                        drawCircle(PrayerSilencePalette.Tick, sunRadius * 0.75f, sun)
                        drawLabel(placed[i])
                    }

                    // Today's sun, and δ as the angle between the halfway ray and today's ray.
                    val today = Math.toRadians(noonAltitude(declination))
                    val arcRadius = radius * 0.4f
                    drawArc(
                        color = PrayerSilencePalette.GoldAccent,
                        startAngle = -Math.toDegrees(maxOf(today, angles[1])).toFloat(),
                        sweepAngle = abs(declination).toFloat(),
                        useCenter = false,
                        topLeft = Offset(observer.x - arcRadius, observer.y - arcRadius),
                        size = Size(2 * arcRadius, 2 * arcRadius),
                        style = Stroke(2.dp.toPx()),
                    )
                    // Between the two rays when they are far enough apart, otherwise just outside them.
                    val deltaAt = when {
                        abs(declination) >= 10 -> at((today + angles[1]) / 2, arcRadius + 10.dp.toPx())
                        declination < 0 -> at(today - Math.toRadians(6.0), arcRadius)
                        else -> at(today + Math.toRadians(6.0), arcRadius)
                    }
                    drawText(deltaLayout, topLeft = Offset(deltaAt.x - deltaLayout.size.width / 2f, deltaAt.y - deltaLayout.size.height / 2f))
                    val sun = at(today, radius)
                    drawLine(PrayerSilencePalette.GoldAccent, observer, sun, 2.dp.toPx())
                    drawCircle(Color.White, sunRadius + 2.dp.toPx(), sun)
                    drawCircle(PrayerSilencePalette.GoldAccent, sunRadius, sun)

                    drawLine(PrayerSilencePalette.Tick, Offset(0f, ground), Offset(size.width, ground), 1.5.dp.toPx())
                    drawCircle(PrayerSilencePalette.PrimaryText, 4.dp.toPx(), observer)
                    val labelTop = ground + 6.dp.toPx()
                    drawText(placeLayout, topLeft = Offset((observer.x - placeLayout.size.width / 2f).fitIn(size.width, placeLayout.size.width), labelTop))
                    drawText(horizonLayout, topLeft = Offset(size.width - horizonLayout.size.width, labelTop))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.width(14.dp).height(14.dp)) {
                drawCircle(PrayerSilencePalette.GoldAccent, 6.dp.toPx())
            }
            Text(
                stringResource(R.string.prayer_method_decl_today, "\u2066${degrees(noonAltitude(declination))}\u2069"),
                fontSize = 12.sp,
                color = TextDark,
            )
        }
        Text(stringResource(R.string.prayer_method_decl_caption), fontSize = 12.sp, color = TextMuted, lineHeight = 17.sp)
    }
}

/**
 * Step 1, the year: δ day by day. It climbs to +23.44° on the first day of summer, falls to
 * −23.44° on the first day of winter, and crosses zero on the two days night equals day.
 */
@Composable
internal fun DeclinationYearChart(sunYear: SunYear, date: LocalDate, declination: Double) {
    val keyDates = listOf(sunYear.spring, sunYear.summer, sunYear.autumn, sunYear.winter)
    val longerDays = stringResource(R.string.prayer_method_decl_longer_days)
    val shorterDays = stringResource(R.string.prayer_method_decl_shorter_days)
    val todayLabel = stringResource(R.string.prayer_method_decl_today_marker)
    val description = stringResource(
        R.string.prayer_method_decl_year_description,
        sunYear.summer.dayMonth(),
        sunYear.winter.dayMonth(),
        sunYear.spring.dayMonth(),
        sunYear.autumn.dayMonth(),
        (if (declination < 0) "−" else "+") + "%.2f°".format(Locale.US, abs(declination)),
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = constraints.maxWidth.toFloat()
            fun px(dp: Dp) = with(density) { dp.toPx() }
            val axisLayouts = listOf("+%.2f°".format(Locale.US, MAX_DECLINATION), "0°", "−%.2f°".format(Locale.US, MAX_DECLINATION))
                .map { measurer.measure(it, NumberStyle) }
            val dateLayouts = keyDates.map { measurer.measure(it.dayMonth(), LabelStyle.copy(color = TextMuted)) }
            val longerLayout = measurer.measure(longerDays, LabelStyle.copy(color = TextMuted))
            val shorterLayout = measurer.measure(shorterDays, LabelStyle.copy(color = TextMuted))
            val todayLayout = measurer.measure(todayLabel, LabelStyle.copy(color = PrayerSilencePalette.GoldAccent, fontWeight = FontWeight.Bold))
            val plotLeft = axisLayouts.maxOf { it.size.width } + px(6.dp)
            val plotTop = axisLayouts[0].size.height / 2f
            val plotBottom = plotTop + px(96.dp)
            val lastDay = sunYear.days.size
            fun x(dayOfYear: Int) = plotLeft + (dayOfYear - 1) * (width - plotLeft) / (lastDay - 1)
            fun y(decl: Double) = plotTop + ((MAX_DECLINATION - decl) / (2 * MAX_DECLINATION)).toFloat() * (plotBottom - plotTop)
            // The dates under the baseline, each dropped to a second row when it would touch the one before.
            val dateLefts = keyDates.indices.map { i ->
                (x(keyDates[i].dayOfYear) - dateLayouts[i].size.width / 2f).fitIn(width, dateLayouts[i].size.width)
            }
            var rowEnd = Float.NEGATIVE_INFINITY
            val dateRows = keyDates.indices.map { i ->
                if (dateLefts[i] >= rowEnd + px(8.dp)) {
                    rowEnd = dateLefts[i] + dateLayouts[i].size.width
                    0
                } else {
                    1
                }
            }
            val rowHeight = dateLayouts.maxOf { it.size.height } + px(2.dp)
            val datesTop = plotBottom + px(5.dp)
            val height = datesTop + (dateRows.max() + 1) * rowHeight

            LeftToRight {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(with(density) { height.toDp() })
                        .semantics { contentDescription = description },
                ) {
                    val zero = y(0.0)
                    val curve = Path().apply {
                        sunYear.declinations.forEachIndexed { i, decl ->
                            if (i == 0) moveTo(x(i + 1), y(decl)) else lineTo(x(i + 1), y(decl))
                        }
                    }
                    val area = Path().apply {
                        addPath(curve)
                        lineTo(x(lastDay), zero)
                        lineTo(x(1), zero)
                        close()
                    }
                    // Above the halfway line the sun is high and days are long; below, the opposite.
                    clipRect(plotLeft, plotTop, size.width, zero) { drawPath(area, DayFill) }
                    clipRect(plotLeft, zero, size.width, plotBottom) { drawPath(area, TwilightFill) }
                    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                    listOf(plotTop, plotBottom).forEach {
                        drawLine(PrayerSilencePalette.SoftBorder, Offset(plotLeft, it), Offset(size.width, it), 1.dp.toPx())
                    }
                    drawLine(PrayerSilencePalette.Tick, Offset(plotLeft, zero), Offset(size.width, zero), 1.5.dp.toPx(), pathEffect = dash)
                    listOf(plotTop, zero, plotBottom).forEachIndexed { i, lineY ->
                        drawText(axisLayouts[i], topLeft = Offset(0f, lineY - axisLayouts[i].size.height / 2f))
                    }
                    // A tick on the baseline at the first of each month.
                    (1..12).forEach { month ->
                        val tickX = x(LocalDate.of(date.year, month, 1).dayOfYear)
                        drawLine(PrayerSilencePalette.Tick, Offset(tickX, plotBottom), Offset(tickX, plotBottom + 3.dp.toPx()), 1.dp.toPx())
                    }
                    drawPath(curve, PrayerSilencePalette.Tick, style = Stroke(2.dp.toPx()))
                    drawText(longerLayout, topLeft = Offset(size.width - longerLayout.size.width, plotTop + 3.dp.toPx()))
                    drawText(
                        shorterLayout,
                        topLeft = Offset(
                            (x(sunYear.summer.dayOfYear) - shorterLayout.size.width / 2f).fitIn(size.width, shorterLayout.size.width),
                            plotBottom - shorterLayout.size.height - 3.dp.toPx(),
                        ),
                    )

                    // The four turning days: a marker on the curve, the date under the baseline.
                    keyDates.forEachIndexed { i, day ->
                        val point = Offset(x(day.dayOfYear), y(sunYear.declinations[day.dayOfYear - 1]))
                        drawMarker(point, PrayerSilencePalette.Tick)
                        drawLine(PrayerSilencePalette.Tick, Offset(point.x, plotBottom), Offset(point.x, plotBottom + 6.dp.toPx()), 1.dp.toPx())
                        drawText(dateLayouts[i], topLeft = Offset(dateLefts[i], datesTop + dateRows[i] * rowHeight))
                    }

                    val today = Offset(x(date.dayOfYear), y(declination))
                    drawLine(PrayerSilencePalette.GoldAccent, today, Offset(today.x, plotBottom), 1.dp.toPx(), pathEffect = dash)
                    drawCircle(Color.White, 7.dp.toPx(), today)
                    drawCircle(PrayerSilencePalette.GoldAccent, 5.dp.toPx(), today)
                    // "Today" beside its dot, or above it when the dot sits on the baseline.
                    val todayGap = 9.dp.toPx()
                    val todayTopLeft = when {
                        today.y + todayLayout.size.height / 2f > plotBottom -> Offset(
                            (today.x - todayLayout.size.width / 2f).fitIn(size.width, todayLayout.size.width),
                            today.y - todayGap - todayLayout.size.height,
                        )
                        today.x + todayGap + todayLayout.size.width <= size.width ->
                            Offset(today.x + todayGap, today.y - todayLayout.size.height / 2f)
                        else -> Offset(today.x - todayGap - todayLayout.size.width, today.y - todayLayout.size.height / 2f)
                    }
                    drawText(todayLayout, topLeft = todayTopLeft)
                }
            }
        }
        Text(stringResource(R.string.prayer_method_decl_year_caption), fontSize = 12.sp, color = TextMuted, lineHeight = 17.sp)
    }
}

/** The obliquity of the ecliptic: how far the sun gets from the celestial equator each year. */
private const val MAX_DECLINATION = 23.44

/**
 * Step 4: a stick and its shadow. Asr begins when the shadow is the stick's length plus its
 * noon shadow, which fixes the sun's altitude a.
 */
@Composable
internal fun AsrShadowDiagram(latitude: Double, explanation: InmDayExplanation) {
    val noonShadow = tan(Math.toRadians(abs(latitude - explanation.declinationDeg)))
    val asrAltitude = Math.toRadians(explanation.asrAltitudeDeg)
    val noonAltitude = Math.toRadians(90 - abs(latitude - explanation.declinationDeg))
    val stickLabel = stringResource(R.string.prayer_method_shadow_stick)
    val lengthLabel = stringResource(R.string.prayer_method_shadow_length)
    val noonLabel = stringResource(R.string.prayer_method_shadow_noon)
    val asrLabel = stringResource(R.string.prayer_method_shadow_asr)
    val angleText = "a = %.1f°".format(Locale.US, explanation.asrAltitudeDeg)
    val description = stringResource(
        R.string.prayer_method_shadow_description,
        "%.2f".format(Locale.US, noonShadow),
        "%.2f".format(Locale.US, 1 + noonShadow),
        "%.1f°".format(Locale.US, explanation.asrAltitudeDeg),
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.toFloat()
        fun px(dp: Dp) = with(density) { dp.toPx() }
        val stickLayout = measurer.measure(stickLabel, LabelStyle)
        val bracketLayouts = listOf(lengthLabel, noonLabel, asrLabel).map { measurer.measure(it, LabelStyle) }
        val angleLayout = measurer.measure(angleText, NumberStyle.copy(color = PrayerSilencePalette.GoldAccent))
        val sunRadius = px(7.dp)
        // The two suns sit at different distances along their rays so they never touch.
        val noonSun = px(24.dp)
        val asrSun = px(48.dp)
        val rightPad = maxOf(stickLayout.size.width + px(10.dp), asrSun * cos(asrAltitude).toFloat() + sunRadius + px(2.dp))
        val stick = min((width - px(8.dp) - rightPad) / (1 + noonShadow).toFloat(), px(120.dp))
        val top = maxOf(noonSun * sin(noonAltitude).toFloat(), asrSun * sin(asrAltitude).toFloat()) + sunRadius + px(2.dp)
        val ground = top + stick
        val labelHeight = bracketLayouts.maxOf { it.size.height }
        val rowHeight = px(10.dp) + labelHeight
        val stickX = width - rightPad
        val asrTip = stickX - (1 + noonShadow).toFloat() * stick
        val noonTip = stickX - noonShadow.toFloat() * stick
        // The two short brackets share a row unless their labels would touch.
        val gap = px(14.dp)
        val lengthWidth = bracketLayouts[0].size.width
        val noonWidth = bracketLayouts[1].size.width
        var lengthLeft = (asrTip + noonTip) / 2 - lengthWidth / 2f
        var noonLeft = maxOf((noonTip + stickX) / 2 - noonWidth / 2f, lengthLeft + lengthWidth + gap)
        if (noonLeft + noonWidth > width) {
            noonLeft = width - noonWidth
            lengthLeft = maxOf(0f, minOf(lengthLeft, noonLeft - gap - lengthWidth))
        }
        val sharedRow = lengthLeft + lengthWidth + gap <= noonLeft + 0.5f
        val rows = if (sharedRow) 2 else 3
        val height = ground + rows * rowHeight + px(4.dp)

        LeftToRight {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { height.toDp() })
                    .semantics { contentDescription = description },
            ) {
                val stickTop = Offset(stickX, ground - stick)
                fun along(angle: Double, distance: Float) =
                    Offset(stickTop.x + distance * cos(angle).toFloat(), stickTop.y - distance * sin(angle).toFloat())

                // Sunlight at noon (dashed) and at Asr, through the top of the stick.
                drawLine(
                    PrayerSilencePalette.Tick,
                    Offset(noonTip, ground),
                    along(noonAltitude, noonSun),
                    1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                )
                drawCircle(PrayerSilencePalette.Tick, sunRadius, along(noonAltitude, noonSun))
                drawLine(PrayerSilencePalette.GoldAccent, Offset(asrTip, ground), along(asrAltitude, asrSun), 2.dp.toPx())
                drawCircle(PrayerSilencePalette.GoldAccent, sunRadius, along(asrAltitude, asrSun))

                drawLine(PrayerSilencePalette.Tick, Offset(0f, ground), Offset(size.width, ground), 1.5.dp.toPx())
                drawLine(PrayerSilencePalette.PrimaryText.copy(alpha = 0.3f), Offset(asrTip, ground), Offset(stickX, ground), 5.dp.toPx())
                drawLine(PrayerSilencePalette.PrimaryText.copy(alpha = 0.6f), Offset(noonTip, ground), Offset(stickX, ground), 5.dp.toPx())
                drawLine(PrayerSilencePalette.PrimaryText, Offset(stickX, ground), stickTop, 3.dp.toPx(), StrokeCap.Round)
                drawText(stickLayout, topLeft = Offset(stickX + 8.dp.toPx(), ground - stick / 2 - stickLayout.size.height / 2f))

                // The angle a at the tip of the Asr shadow, labelled above the ray so the ray never crosses it.
                val arcRadius = 26.dp.toPx()
                drawArc(
                    color = PrayerSilencePalette.GoldAccent,
                    startAngle = -Math.toDegrees(asrAltitude).toFloat(),
                    sweepAngle = Math.toDegrees(asrAltitude).toFloat(),
                    useCenter = false,
                    topLeft = Offset(asrTip - arcRadius, ground - arcRadius),
                    size = Size(2 * arcRadius, 2 * arcRadius),
                    style = Stroke(1.5.dp.toPx()),
                )
                val angleLeft = (asrTip + arcRadius * cos(asrAltitude).toFloat() - angleLayout.size.width)
                    .fitIn(size.width, angleLayout.size.width)
                val rayAtRight = ground - (angleLeft + angleLayout.size.width - asrTip) * tan(asrAltitude).toFloat()
                drawText(angleLayout, topLeft = Offset(angleLeft, minOf(rayAtRight, ground - arcRadius) - angleLayout.size.height - 3.dp.toPx()))

                val firstRow = ground + 8.dp.toPx()
                drawBracket(asrTip, noonTip, firstRow, bracketLayouts[0], lengthLeft)
                drawBracket(noonTip, stickX, if (sharedRow) firstRow else firstRow + rowHeight, bracketLayouts[1], noonLeft.takeIf { sharedRow })
                drawBracket(asrTip, stickX, firstRow + (rows - 1) * rowHeight, bracketLayouts[2], null)
            }
        }
    }
}

/** A dimension line from [from] to [to] with its label underneath, at [labelLeft] or centred. */
private fun DrawScope.drawBracket(from: Float, to: Float, y: Float, label: TextLayoutResult, labelLeft: Float?) {
    val tick = 4.dp.toPx()
    val color = PrayerSilencePalette.SecondaryText
    drawLine(color, Offset(from, y), Offset(to, y), 1.dp.toPx())
    drawLine(color, Offset(from, y - tick), Offset(from, y + tick), 1.dp.toPx())
    drawLine(color, Offset(to, y - tick), Offset(to, y + tick), 1.dp.toPx())
    val left = (labelLeft ?: ((from + to) / 2 - label.size.width / 2f)).fitIn(size.width, label.size.width)
    drawText(label, topLeft = Offset(left, y + 3.dp.toPx()))
}

/**
 * Step 5: the moment of sunrise and sunset, magnified. The sun's upper edge touches the horizon;
 * light bent by the air shows it higher than it really is, so its centre is 0.83° below the
 * horizon, plus d when the horizon sits lower because the place is high.
 */
@Composable
internal fun SunriseDiagram(explanation: InmDayExplanation, maghrib: InmEventStep) {
    val dip = explanation.dipDeg
    // 0.83° = the sun's radius (16′) + refraction (34′), split in that ratio so the parts add up.
    val radiusDeg = 0.83 * 16 / 50
    val labels = listOf(
        stringResource(R.string.prayer_method_sunrise_dip),
        stringResource(R.string.prayer_method_sunrise_radius),
        stringResource(R.string.prayer_method_sunrise_refraction),
    )
    val seenLabel = stringResource(R.string.prayer_method_sunrise_seen)
    val trueLabel = stringResource(R.string.prayer_method_sunrise_true)
    val altitudeText = "${minusSigned(maghrib.altitudeDeg)}°"
    val description = stringResource(R.string.prayer_method_sunrise_description, "${minusSigned(maghrib.altitudeDeg)}°")
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.toFloat()
        fun px(dp: Dp) = with(density) { dp.toPx() }
        val zeroLayout = measurer.measure("0°", NumberStyle.copy(color = TextMuted))
        val scale = min(px(96.dp), px(150.dp) / (dip + 0.83 + radiusDeg).toFloat())
        val sunRadius = (radiusDeg * scale).toFloat()
        val altitudeWidth = measurer.measure(altitudeText, NumberStyle).size.width
        val sunX = minOf(width * 0.58f, width - altitudeWidth - sunRadius - px(8.dp))
        val bracketX = sunX - sunRadius - px(14.dp)
        val bracketLayouts = labels.map {
            measurer.measure(it, LabelStyle, constraints = Constraints(maxWidth = (bracketX - px(6.dp)).toInt().coerceAtLeast(1)))
        }
        // When d is too thin to hold its label, the label goes above the level line instead.
        val dipAbove = dip * scale >= px(2.dp) && dip * scale < bracketLayouts[0].size.height
        val horizontal = maxOf(zeroLayout.size.height, if (dipAbove) bracketLayouts[0].size.height else 0) + px(4.dp)
        val horizon = horizontal + (dip * scale).toFloat()
        val seenCenter = horizon + sunRadius
        val trueCenter = horizon + (0.83 * scale).toFloat()
        val sideWidth = (width - (sunX + sunRadius + px(8.dp))).toInt().coerceAtLeast(1)
        val seenLayout = measurer.measure(seenLabel, LabelStyle, constraints = Constraints(maxWidth = sideWidth))
        val trueLayout = measurer.measure(trueLabel, LabelStyle.copy(fontWeight = FontWeight.Bold), constraints = Constraints(maxWidth = sideWidth))
        val altitudeLayout = measurer.measure(altitudeText, NumberStyle, softWrap = false)
        val sideX = sunX + sunRadius + px(8.dp)
        val seenTop = seenCenter - seenLayout.size.height / 2f
        val trueTop = maxOf(trueCenter - trueLayout.size.height / 2f, seenTop + seenLayout.size.height + px(2.dp))
        // The parts of the depth, top to bottom, labelled on their left.
        val segments = listOf(horizontal to horizon, horizon to seenCenter, seenCenter to trueCenter)
            .mapIndexed { index, (from, to) -> Triple(index, from, to) }
            .filter { (_, from, to) -> to - from >= px(2.dp) }
        val bracketLabels = separated(
            segments.filterNot { (index) -> index == 0 && dipAbove }.map { (index, from, to) ->
                val layout = bracketLayouts[index]
                PlacedLabel(layout, bracketX - px(6.dp) - layout.size.width, (from + to) / 2 - layout.size.height / 2f)
            },
            px(1.dp),
        )
        val height = listOf(
            trueCenter + sunRadius,
            trueTop + trueLayout.size.height + altitudeLayout.size.height,
            bracketLabels.maxOfOrNull { it.bottom } ?: 0f,
        ).max() + px(8.dp)

        LeftToRight {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { height.toDp() })
                    .semantics { contentDescription = description },
            ) {
                drawRect(GroundFill, topLeft = Offset(0f, horizon), size = Size(size.width, size.height - horizon))
                val dashed = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                if (dip * scale >= 2.dp.toPx()) {
                    drawLine(PrayerSilencePalette.Tick, Offset(0f, horizontal), Offset(size.width, horizontal), 1.dp.toPx(), pathEffect = dashed)
                }
                drawText(zeroLayout, topLeft = Offset(size.width - zeroLayout.size.width, horizontal - zeroLayout.size.height - 2.dp.toPx()))
                drawLine(PrayerSilencePalette.PrimaryText, Offset(0f, horizon), Offset(size.width, horizon), 1.5.dp.toPx())

                drawCircle(PrayerSilencePalette.GoldAccent.copy(alpha = 0.85f), sunRadius, Offset(sunX, seenCenter))
                drawCircle(PrayerSilencePalette.GoldAccent, sunRadius, Offset(sunX, trueCenter), style = Stroke(1.5.dp.toPx(), pathEffect = dashed))
                drawCircle(PrayerSilencePalette.PrimaryText, 2.5.dp.toPx(), Offset(sunX, trueCenter))
                listOf(seenCenter, trueCenter).forEach { y ->
                    drawLine(PrayerSilencePalette.Tick, Offset(bracketX, y), Offset(sunX, y), 1.dp.toPx(), pathEffect = dashed)
                }

                val tick = 4.dp.toPx()
                segments.forEach { (index, from, to) ->
                    drawLine(PrayerSilencePalette.SecondaryText, Offset(bracketX, from), Offset(bracketX, to), 1.dp.toPx())
                    drawLine(PrayerSilencePalette.SecondaryText, Offset(bracketX, from), Offset(bracketX + tick, from), 1.dp.toPx())
                    drawLine(PrayerSilencePalette.SecondaryText, Offset(bracketX, to), Offset(bracketX + tick, to), 1.dp.toPx())
                    if (index == 0 && dipAbove) {
                        val layout = bracketLayouts[0]
                        drawLabel(PlacedLabel(layout, bracketX - 6.dp.toPx() - layout.size.width, from - layout.size.height - 2.dp.toPx()))
                    }
                }
                bracketLabels.forEach { drawLabel(it) }

                drawText(seenLayout, topLeft = Offset(sideX, seenTop))
                drawText(trueLayout, topLeft = Offset(sideX, trueTop))
                drawText(altitudeLayout, topLeft = Offset(sideX, trueTop + trueLayout.size.height))
            }
        }
    }
}

/**
 * Step 6: the sun below the horizon, seen from the side with east on the left. Sunrise and
 * Maghrib sit just under the horizon; Fajr and Isha at 18° + d, where twilight meets night.
 */
@Composable
internal fun TwilightDiagram(explanation: InmDayExplanation, names: Map<InmEvent, String>) {
    val steps = explanation.events.associateBy { it.event }
    fun depth(event: InmEvent) = abs(steps.getValue(event).altitudeDeg).toFloat()
    fun value(event: InmEvent) = "${minusSigned(steps.getValue(event).altitudeDeg)}°"
    val dayLabel = stringResource(R.string.prayer_method_twilight_day)
    val nightLabel = stringResource(R.string.prayer_method_twilight_night)
    val description = stringResource(
        R.string.prayer_method_twilight_description,
        value(InmEvent.FAJR),
        value(InmEvent.MAGHRIB),
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.toFloat()
        fun px(dp: Dp) = with(density) { dp.toPx() }
        fun label(event: InmEvent): Pair<TextLayoutResult, TextLayoutResult> =
            measurer.measure(names.getValue(event), LabelStyle.copy(fontWeight = FontWeight.Bold)) to
                measurer.measure(value(event), NumberStyle)
        val labels = listOf(InmEvent.FAJR, InmEvent.SUNRISE, InmEvent.MAGHRIB, InmEvent.ISHA).associateWith(::label)
        val dayLayout = measurer.measure(dayLabel, LabelStyle.copy(color = PrayerSilencePalette.GoldAccent))
        val nightLayout = measurer.measure(nightLabel, LabelStyle.copy(color = PrayerSilencePalette.PrimaryText))
        val twoLines = labels.values.maxOf { (name, number) -> name.size.height + number.size.height }
        val horizon = maxOf(twoLines, dayLayout.size.height) + px(6.dp)
        val sun = px(6.dp)
        val gap = px(4.dp)
        fun blockWidth(event: InmEvent) = labels.getValue(event).let { (name, number) -> maxOf(name.size.width, number.size.width).toFloat() }
        fun blockHeight(event: InmEvent) = labels.getValue(event).let { (name, number) -> (name.size.height + number.size.height).toFloat() }
        val angles = mapOf(
            InmEvent.FAJR to 180 - depth(InmEvent.FAJR),
            InmEvent.SUNRISE to 180 - depth(InmEvent.SUNRISE),
            InmEvent.MAGHRIB to depth(InmEvent.MAGHRIB),
            InmEvent.ISHA to depth(InmEvent.ISHA),
        )
        // Compose arcs: 0° points right (west), 90° straight down, 180° left (east).
        fun pointAt(radius: Float, degrees: Float) = Offset(
            width / 2 + radius * cos(Math.toRadians(degrees.toDouble())).toFloat(),
            horizon + radius * sin(Math.toRadians(degrees.toDouble())).toFloat(),
        )
        // Sunrise and Maghrib are labelled above the horizon. Fajr and Isha are labelled inside the
        // half circle beside their sun when the two labels and "night" fit there; otherwise the
        // circle shrinks and every label moves outside it, beside its sun.
        val wide = min(width / 2 - sun - px(2.dp), px(140.dp))
        val fajrInside = pointAt(wide, angles.getValue(InmEvent.FAJR))
        val ishaInside = pointAt(wide, angles.getValue(InmEvent.ISHA))
        val insideLabelsBottom = fajrInside.y + gap + maxOf(blockHeight(InmEvent.FAJR), blockHeight(InmEvent.ISHA))
        val inside = fajrInside.x + sun + gap + blockWidth(InmEvent.FAJR) + px(24.dp) <= ishaInside.x - sun - gap - blockWidth(InmEvent.ISHA) &&
            insideLabelsBottom + gap + nightLayout.size.height <= horizon + wide - px(4.dp)
        val sideBlock = labels.keys.maxOf(::blockWidth)
        val radius = if (inside) wide else (width / 2 - sideBlock - sun - 2 * gap).coerceAtMost(wide).coerceAtLeast(min(px(40.dp), wide))
        val blocks = angles.mapValues { (event, degrees) ->
            val point = pointAt(radius, degrees)
            val east = degrees > 90
            val w = blockWidth(event)
            val left = when {
                event == InmEvent.SUNRISE || event == InmEvent.MAGHRIB ->
                    if (inside) (if (east) point.x - sun else point.x + sun - w) else (if (east) point.x - sun - gap - w else point.x + sun + gap)
                inside -> if (east) point.x + sun + gap else point.x - sun - gap - w
                else -> if (east) point.x - sun - gap - w else point.x + sun + gap
            }
            val top = when {
                event == InmEvent.SUNRISE || event == InmEvent.MAGHRIB -> horizon - blockHeight(event) - gap
                inside -> point.y + gap
                else -> maxOf(point.y - blockHeight(event) / 2, horizon + gap)
            }
            Offset(left.fitIn(width, w), top)
        }
        val nightTop = if (inside) {
            maxOf(horizon + radius * 0.62f, insideLabelsBottom + gap)
        } else {
            minOf(horizon + radius * 0.55f, horizon + radius - px(4.dp) - nightLayout.size.height)
        }
        val showNight = nightTop >= horizon + radius * 0.3f
        val height = maxOf(
            horizon + radius + sun,
            blocks.entries.maxOf { (event, at) -> at.y + blockHeight(event) },
        ) + px(4.dp)

        LeftToRight {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { height.toDp() })
                    .semantics { contentDescription = description },
            ) {
                val observer = Offset(size.width / 2, horizon)
                fun wedge(color: Color, from: Float, to: Float) = drawArc(
                    color = color,
                    startAngle = from,
                    sweepAngle = to - from,
                    useCenter = true,
                    topLeft = Offset(observer.x - radius, observer.y - radius),
                    size = Size(2 * radius, 2 * radius),
                )
                drawRect(DayFill, size = Size(size.width, horizon))
                wedge(NightFill, 0f, 180f)
                wedge(TwilightFill, 180 - depth(InmEvent.FAJR), 180 - depth(InmEvent.SUNRISE))
                wedge(TwilightFill, depth(InmEvent.MAGHRIB), depth(InmEvent.ISHA))
                drawArc(
                    color = PrayerSilencePalette.Tick,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(observer.x - radius, observer.y - radius),
                    size = Size(2 * radius, 2 * radius),
                    style = Stroke(1.dp.toPx()),
                )
                drawLine(PrayerSilencePalette.PrimaryText, Offset(0f, horizon), Offset(size.width, horizon), 1.5.dp.toPx())

                angles.values.forEach { drawLine(PrayerSilencePalette.PrimaryText, observer, pointAt(radius, it), 1.5.dp.toPx()) }
                angles.values.forEach { drawCircle(PrayerSilencePalette.GoldAccent, sun, pointAt(radius, it)) }
                drawCircle(PrayerSilencePalette.PrimaryText, 3.dp.toPx(), observer)

                drawText(dayLayout, topLeft = Offset(observer.x - dayLayout.size.width / 2f, horizon - dayLayout.size.height - 4.dp.toPx()))
                if (showNight) drawText(nightLayout, topLeft = Offset(observer.x - nightLayout.size.width / 2f, nightTop))

                blocks.forEach { (event, at) ->
                    val (name, number) = labels.getValue(event)
                    val w = blockWidth(event)
                    // Each line hugs the side its sun is on: inside, eastern labels sit right of their sun.
                    val alignLeft = (angles.getValue(event) > 90) == inside
                    fun lineLeft(layout: TextLayoutResult) = if (alignLeft) at.x else at.x + w - layout.size.width
                    drawText(name, topLeft = Offset(lineLeft(name), at.y))
                    drawText(number, topLeft = Offset(lineLeft(number), at.y + name.size.height))
                }
            }
        }
    }
}

/**
 * Step 7: where each exact time falls inside its minute. From 30 seconds on it goes up to the
 * next minute; below that the seconds are dropped.
 */
@Composable
internal fun RoundingDiagram(events: List<InmEventStep>, names: Map<InmEvent, String>) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val nameStyle = TextStyle(fontSize = 12.sp, color = TextDark)
    val nameWidth = with(density) { events.maxOf { measurer.measure(names.getValue(it.event), nameStyle).size.width }.toDp() } + 8.dp
    val upText = stringResource(R.string.prayer_method_rounding_up)
    val downText = stringResource(R.string.prayer_method_rounding_down)
    val description = events.joinToString("، ") { step ->
        val seconds = secondsOf(step.exactMinutes)
        (if (seconds >= 30) upText else downText).format(Locale.US, names.getValue(step.event), seconds)
    }
    val axisLabels = listOf(0, 30, 60).map { stringResource(R.string.prayer_method_rounding_seconds, it) }

    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        events.forEach { step ->
            val seconds = secondsOf(step.exactMinutes)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(names.getValue(step.event), style = nameStyle, maxLines = 1, modifier = Modifier.width(nameWidth))
                Box(Modifier.weight(1f)) {
                    LeftToRight {
                        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                            val pad = 6.dp.toPx()
                            fun x(s: Int) = pad + s / 60f * (size.width - 2 * pad)
                            val y = size.height / 2
                            drawLine(
                                PrayerSilencePalette.Tick,
                                Offset(x(30), 0f),
                                Offset(x(30), size.height),
                                1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                            )
                            drawLine(PrayerSilencePalette.SoftBorder, Offset(x(0), y), Offset(x(60), y), 2.dp.toPx())
                            listOf(0, 60).forEach {
                                drawLine(PrayerSilencePalette.Tick, Offset(x(it), y - 4.dp.toPx()), Offset(x(it), y + 4.dp.toPx()), 1.dp.toPx())
                            }
                            val up = seconds >= 30
                            val color = if (up) GreenPrimary else PrayerSilencePalette.Tick
                            val end = Offset(x(if (up) 60 else 0), y)
                            drawLine(color, Offset(x(seconds), y), end, 2.dp.toPx())
                            drawArrowHead(end, if (up) 1f else -1f, color)
                            drawCircle(Color.White, 5.dp.toPx(), Offset(x(seconds), y))
                            drawCircle(if (up) GreenPrimary else PrayerSilencePalette.PrimaryText, 3.5.dp.toPx(), Offset(x(seconds), y))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(nameWidth))
            Box(Modifier.weight(1f)) {
                val style = LabelStyle.copy(fontSize = 10.sp, color = TextMuted, textAlign = TextAlign.Center)
                val axisHeight = with(density) { axisLabels.maxOf { measurer.measure(it, style).size.height }.toDp() }
                LeftToRight {
                    Canvas(Modifier.fillMaxWidth().height(axisHeight + 2.dp)) {
                        val pad = 6.dp.toPx()
                        listOf(0, 30, 60).forEachIndexed { index, s ->
                            val layout = measurer.measure(axisLabels[index], style)
                            val center = pad + s / 60f * (size.width - 2 * pad)
                            val left = (center - layout.size.width / 2f).fitIn(size.width, layout.size.width)
                            drawText(layout, topLeft = Offset(left, 2.dp.toPx()))
                        }
                    }
                }
            }
        }
    }
}

private class PlacedLabel(val layout: TextLayoutResult, val left: Float, val top: Float) {
    val bottom: Float get() = top + layout.size.height
}

/** [labels] top to bottom, each moved down just enough not to overlap the one above. */
private fun separated(labels: List<PlacedLabel>, spacing: Float): List<PlacedLabel> {
    var floor = Float.NEGATIVE_INFINITY
    return labels.sortedBy { it.top }.map { label ->
        PlacedLabel(label.layout, label.left, maxOf(label.top, floor + spacing, 0f)).also { floor = it.bottom }
    }
}

private fun DrawScope.drawLabel(label: PlacedLabel) =
    drawText(label.layout, topLeft = Offset(label.left.fitIn(size.width, label.layout.size.width), label.top))

/** This left edge moved just enough for a [width]-wide label to stay inside [available]. */
private fun Float.fitIn(available: Float, width: Number): Float = coerceIn(0f, maxOf(0f, available - width.toFloat()))

/** A dot with a white ring, as on the sun-path chart. */
private fun DrawScope.drawMarker(center: Offset, color: Color) {
    drawCircle(Color.White, 5.dp.toPx(), center)
    drawCircle(color, 3.5.dp.toPx(), center)
}

/** A small arrow head at [tip], pointing right when [direction] is 1 and left when it is -1. */
private fun DrawScope.drawArrowHead(tip: Offset, direction: Float, color: Color) {
    val length = 6.dp.toPx()
    val half = 4.dp.toPx()
    drawPath(
        Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - direction * length, tip.y - half)
            lineTo(tip.x - direction * length, tip.y + half)
            close()
        },
        color,
    )
}

@Composable
private fun LeftToRight(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)

/** Seconds of the exact time as the explainer shows it (truncated, like its clock times). */
private fun secondsOf(minutes: Double): Int = (floor(minutes * 60).toLong() % 60).toInt()

private fun hhmmOf(minutes: Int): String = "%02d:%02d".format(Locale.US, minutes / 60 % 24, minutes % 60)

private fun clockSeconds(minutes: Double): String {
    val seconds = floor(minutes * 60).toLong()
    return "%02d:%02d:%02d".format(Locale.US, seconds / 3600, seconds / 60 % 60, seconds % 60)
}

/** A value with the minus sign the formulas use (−), not a hyphen. */
private fun minusSigned(value: Double, decimals: Int = 3): String =
    (if (value < 0) "−" else "") + "%.${decimals}f".format(Locale.US, abs(value))
