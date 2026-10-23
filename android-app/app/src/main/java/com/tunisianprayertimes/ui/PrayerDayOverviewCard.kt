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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.Divider
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.TextMuted
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

private data class NightTimelineAlarmPlacement(
    val id: String,
    val alarms: List<NightTimelineAlarm>,
    val iconCenterX: Float,
    val iconCenterY: Float,
    val nearNonAlarmMarker: Boolean,
    val labelCenterX: Float,
    val labelCenterY: Float,
    val labelX: Float,
    val labelY: Float,
    val labelWidth: Float,
    val label: String,
    val time: String,
) {
    val isCluster: Boolean = alarms.size > 1
    val hasSubAlarm: Boolean = alarms.any { alarm -> alarm.isSubAlarm }
}

@Composable
internal fun PrayerDayOverviewCard(
    data: NightTimesCardData,
    onEditWakeAlarm: (String) -> Unit,
) {
    val skyBlue = Color(0xFF92D8F3)
    val deepNight = Color(0xFF16345C)
    val sunAngleLineHeight = with(LocalDensity.current) { 16.sp.toDp().value }
    var selectedAlarmCluster by remember(data.selectedDateLabel) { mutableStateOf<List<NightTimelineAlarm>?>(null) }

    selectedAlarmCluster?.let { alarms ->
        TimelineAlarmClusterSheet(
            alarms = alarms,
            onDismiss = { selectedAlarmCluster = null },
            onEditAlarm = { alarmId ->
                selectedAlarmCluster = null
                onEditWakeAlarm(alarmId)
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 430.dp)
                .fillMaxWidth()
                .testTag("prayer_day_overview"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            border = BorderStroke(1.dp, Gold.copy(alpha = 0.18f)),
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(442.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(deepNight)
                            .border(BorderStroke(1.dp, Divider), RoundedCornerShape(22.dp)),
                    ) {
                    val panelWidth = maxWidth.value
                    val panelHeight = 442f
                    val fajrMinuteOfDay = data.timelinePrayers.firstOrNull { it.prayer == Prayer.FAJR }?.minuteOfDay
                        ?: data.thirds[2].endMinuteOfDay
                    val maghribMinuteOfDay = data.timelinePrayers.firstOrNull { it.prayer == Prayer.MAGHRIB }?.minuteOfDay
                        ?: data.thirds[0].startMinuteOfDay
                    val daylightMinutes = minutesBetweenInDay(data.sunriseMinuteOfDay, maghribMinuteOfDay)
                    val daylightFraction = daylightMinutes / (24f * 60f)
                    val daylightSweepDegrees = daylightFraction * 360f
                    val sunriseAngleDegrees = 180f
                    val sunsetAngleDegrees = sunriseAngleDegrees + daylightSweepDegrees
                    val sunsetRadians = sunsetAngleDegrees * PI / 180.0
                    val circleRadius = if (panelWidth - 86f > 262f) 131f else (panelWidth - 86f) / 2f
                    val circleCenterX = panelWidth / 2f
                    val circleCenterY = panelHeight / 2f
                    val circleTop = circleCenterY - circleRadius
                    val circleBottom = circleCenterY + circleRadius
                    val sunriseX = circleCenterX - circleRadius
                    val sunriseY = circleCenterY
                    val sunsetX = circleCenterX + circleRadius * cos(sunsetRadians).toFloat()
                    val sunsetY = circleCenterY + circleRadius * sin(sunsetRadians).toFloat()
                    val sunriseSunOutsideOffset = 26f
                    val sunriseSunX = sunriseX + (sunriseX - circleCenterX) / circleRadius * sunriseSunOutsideOffset
                    val sunriseSunY = sunriseY + (sunriseY - circleCenterY) / circleRadius * sunriseSunOutsideOffset
                    val sunsetRayUnitX = (sunsetX - circleCenterX) / circleRadius
                    val sunsetRayUnitY = (sunsetY - circleCenterY) / circleRadius
                    fun distanceToPanelBorderAlongRay(originX: Float, originY: Float, unitX: Float, unitY: Float): Float {
                        val horizontalDistance = when {
                            unitX > 0f -> (panelWidth - originX) / unitX
                            unitX < 0f -> -originX / unitX
                            else -> Float.POSITIVE_INFINITY
                        }
                        val verticalDistance = when {
                            unitY > 0f -> (panelHeight - originY) / unitY
                            unitY < 0f -> -originY / unitY
                            else -> Float.POSITIVE_INFINITY
                        }
                        return minOf(horizontalDistance, verticalDistance).coerceAtLeast(0f)
                    }
                    val sunsetDistanceToPanelBorder = distanceToPanelBorderAlongRay(sunsetX, sunsetY, sunsetRayUnitX, sunsetRayUnitY)
                    val sunsetSunOffset = sunsetDistanceToPanelBorder / 2f
                    val sunsetSunX = sunsetX + sunsetRayUnitX * sunsetSunOffset
                    val sunsetSunY = sunsetY + sunsetRayUnitY * sunsetSunOffset
                    val sunsetPanelBorderX = sunsetX + sunsetRayUnitX * sunsetDistanceToPanelBorder
                    val sunsetPanelBorderY = sunsetY + sunsetRayUnitY * sunsetDistanceToPanelBorder
                    val twilightMinutes = minutesBetweenInDay(fajrMinuteOfDay, data.sunriseMinuteOfDay)
                    val twilightSweepDegrees = twilightMinutes / (24f * 60f) * 360f
                    val nightSweepDegrees = (360f - daylightSweepDegrees - twilightSweepDegrees).coerceAtLeast(0f)
                    val nightTotalMinutes = minutesBetweenInDay(maghribMinuteOfDay, fajrMinuteOfDay).coerceAtLeast(1)
                    fun minuteToNightAngleDegrees(minuteOfDay: Int): Float {
                        val minutesFromMaghrib = minutesBetweenInDay(maghribMinuteOfDay, minuteOfDay)
                        return sunsetAngleDegrees + nightSweepDegrees * (minutesFromMaghrib.toFloat() / nightTotalMinutes.toFloat())
                    }
                    val secondThirdAngleDegrees = minuteToNightAngleDegrees(data.thirds[1].startMinuteOfDay)
                    val finalThirdAngleDegrees = minuteToNightAngleDegrees(data.thirds[2].startMinuteOfDay)
                    val fajrAngleDegrees = minuteToNightAngleDegrees(fajrMinuteOfDay)
                    fun minuteToTimelineAngleDegrees(minuteOfDay: Int): Float {
                        val minutesFromSunrise = minutesBetweenInDay(data.sunriseMinuteOfDay, minuteOfDay)
                        val minutesFromMaghrib = minutesBetweenInDay(maghribMinuteOfDay, minuteOfDay)
                        return when {
                            minutesFromSunrise <= daylightMinutes -> {
                                sunriseAngleDegrees + daylightSweepDegrees * (minutesFromSunrise.toFloat() / daylightMinutes.coerceAtLeast(1).toFloat())
                            }

                            minutesFromMaghrib <= nightTotalMinutes -> minuteToNightAngleDegrees(minuteOfDay)

                            else -> {
                                val minutesFromFajr = minutesBetweenInDay(fajrMinuteOfDay, minuteOfDay)
                                fajrAngleDegrees + twilightSweepDegrees * (minutesFromFajr.toFloat() / twilightMinutes.coerceAtLeast(1).toFloat())
                            }
                        }
                    }
                    fun containsCurrentMinute(startMinuteOfDay: Int, endMinuteOfDay: Int): Boolean {
                        val segmentDuration = minutesBetweenInDay(startMinuteOfDay, endMinuteOfDay)
                        if (segmentDuration <= 0) return false
                        return minutesBetweenInDay(startMinuteOfDay, data.currentMinuteOfDay) < segmentDuration
                    }
                    fun clockwiseSweepDegrees(startAngle: Float, endAngle: Float): Float {
                        val sweep = endAngle - startAngle
                        return if (sweep >= 0f) sweep else sweep + 360f
                    }
                    val middayMinuteOfDay = data.timelinePrayers.firstOrNull { it.prayer == Prayer.DHUHR || it.prayer == Prayer.JOMOAA }?.minuteOfDay
                        ?: data.sunriseMinuteOfDay
                    val asrMinuteOfDay = data.timelinePrayers.firstOrNull { it.prayer == Prayer.ASR }?.minuteOfDay
                        ?: middayMinuteOfDay
                    val ishaMinuteOfDay = data.timelinePrayers.firstOrNull { it.prayer == Prayer.ISHA }?.minuteOfDay
                        ?: maghribMinuteOfDay
                    val activeSegmentMinutes = listOf(
                        fajrMinuteOfDay to data.sunriseMinuteOfDay,
                        data.sunriseMinuteOfDay to middayMinuteOfDay,
                        middayMinuteOfDay to asrMinuteOfDay,
                        asrMinuteOfDay to maghribMinuteOfDay,
                        maghribMinuteOfDay to ishaMinuteOfDay,
                        ishaMinuteOfDay to data.thirds[1].startMinuteOfDay,
                        data.thirds[1].startMinuteOfDay to data.thirds[2].startMinuteOfDay,
                        data.thirds[2].startMinuteOfDay to fajrMinuteOfDay,
                    ).firstOrNull { (startMinuteOfDay, endMinuteOfDay) ->
                        containsCurrentMinute(startMinuteOfDay, endMinuteOfDay)
                    } ?: (data.sunriseMinuteOfDay to maghribMinuteOfDay)
                    val activeSegmentStartAngle = minuteToTimelineAngleDegrees(activeSegmentMinutes.first)
                    val activeSegmentEndAngle = minuteToTimelineAngleDegrees(activeSegmentMinutes.second)
                    val secondThirdRadians = secondThirdAngleDegrees * PI / 180.0
                    val finalThirdRadians = finalThirdAngleDegrees * PI / 180.0
                    val fajrRadians = fajrAngleDegrees * PI / 180.0
                    val twilightLabelRadians = (fajrAngleDegrees + twilightSweepDegrees * 0.52f) * PI / 180.0
                    val secondThirdX = circleCenterX + circleRadius * cos(secondThirdRadians).toFloat()
                    val secondThirdY = circleCenterY + circleRadius * sin(secondThirdRadians).toFloat()
                    val finalThirdX = circleCenterX + circleRadius * cos(finalThirdRadians).toFloat()
                    val finalThirdY = circleCenterY + circleRadius * sin(finalThirdRadians).toFloat()
                    val fajrX = circleCenterX + circleRadius * cos(fajrRadians).toFloat()
                    val fajrY = circleCenterY + circleRadius * sin(fajrRadians).toFloat()
                    val twilightLabelRadius = circleRadius * 0.58f
                    val twilightLabelX = (circleCenterX + twilightLabelRadius * cos(twilightLabelRadians).toFloat() - 28f).coerceIn(4f, panelWidth - 58f)
                    val twilightLabelY = (circleCenterY + twilightLabelRadius * sin(twilightLabelRadians).toFloat() - 9f).coerceIn(circleTop + 14f, circleBottom - 20f)
                    val dayLabelY = circleTop + circleRadius * 0.48f
                    val nightLabelY = circleCenterY + circleRadius * 0.22f
                    val timeLabelHeight = 29f
                    val fajrLabelWidth = 54f
                    val sunriseLabelWidth = 52f
                    val maghribLabelWidth = 58f
                    val horizonLabelSunLift = 52f
                    val maghribLabelSunLift = horizonLabelSunLift + 10f
                    val sunriseLabelX = (sunriseSunX - sunriseLabelWidth / 2f).coerceIn(6f, panelWidth - sunriseLabelWidth - 6f)
                    val maghribLabelX = (sunsetSunX - maghribLabelWidth / 2f).coerceIn(6f, panelWidth - maghribLabelWidth - 6f)
                    val thirdLabelWidth = 82f
                    val exteriorLabelTimelineGap = 3f
                    val sunriseLabelY = (sunriseSunY - horizonLabelSunLift).coerceAtLeast(12f)
                    val maghribLabelY = (sunsetSunY - maghribLabelSunLift).coerceIn(8f, panelHeight - timeLabelHeight - 8f)

                    fun radialLabelExtent(angleDegrees: Float, labelWidth: Float, labelHeight: Float): Float {
                        val radians = angleDegrees * PI / 180.0
                        val radialX = cos(radians).toFloat()
                        val radialY = sin(radians).toFloat()
                        return abs(radialX) * labelWidth / 2f + abs(radialY) * labelHeight / 2f
                    }

                    fun exteriorMarkerLabelCenterDistance(
                        angleDegrees: Float,
                        labelWidth: Float,
                        labelHeight: Float,
                    ): Float = exteriorLabelTimelineGap + radialLabelExtent(angleDegrees, labelWidth, labelHeight)

                    fun labelOutsideTimeline(
                        angleDegrees: Float,
                        labelWidth: Float,
                        labelHeight: Float,
                        markerLabelCenterDistance: Float,
                    ): Pair<Float, Float> {
                        val radians = angleDegrees * PI / 180.0
                        val radialX = cos(radians).toFloat()
                        val radialY = sin(radians).toFloat()
                        val halfWidth = labelWidth / 2f
                        val halfHeight = labelHeight / 2f
                        val labelRadius = circleRadius + markerLabelCenterDistance
                        val labelCenterX = circleCenterX + labelRadius * radialX
                        val labelCenterY = circleCenterY + labelRadius * radialY
                        return labelCenterX - halfWidth to labelCenterY - halfHeight
                    }

                    val (fajrLabelX, fajrLabelY) = labelOutsideTimeline(
                        angleDegrees = fajrAngleDegrees,
                        labelWidth = fajrLabelWidth,
                        labelHeight = timeLabelHeight,
                        markerLabelCenterDistance = exteriorMarkerLabelCenterDistance(
                            fajrAngleDegrees,
                            fajrLabelWidth,
                            timeLabelHeight,
                        ),
                    )
                    val (secondThirdLabelX, secondThirdLabelY) = labelOutsideTimeline(
                        angleDegrees = secondThirdAngleDegrees,
                        labelWidth = thirdLabelWidth,
                        labelHeight = timeLabelHeight,
                        markerLabelCenterDistance = exteriorMarkerLabelCenterDistance(
                            secondThirdAngleDegrees,
                            thirdLabelWidth,
                            timeLabelHeight,
                        ),
                    )
                    val (finalThirdLabelX, finalThirdLabelY) = labelOutsideTimeline(
                        angleDegrees = finalThirdAngleDegrees,
                        labelWidth = thirdLabelWidth,
                        labelHeight = timeLabelHeight,
                        markerLabelCenterDistance = exteriorMarkerLabelCenterDistance(
                            finalThirdAngleDegrees,
                            thirdLabelWidth,
                            timeLabelHeight,
                        ),
                    )
                    val timelinePrayerAngles = data.timelinePrayers.map { prayer -> minuteToTimelineAngleDegrees(prayer.minuteOfDay) }
                    val timelinePrayerLabelWidth = 58f
                    val timelinePrayerLabelHeight = 26f
                    val timelinePrayerLabelRadius = (circleRadius - 31f).coerceAtLeast(20f)
                    val timelinePrayerLabels = data.timelinePrayers.zip(timelinePrayerAngles)
                        .filter { (prayer, _) -> prayer.prayer != Prayer.FAJR && prayer.prayer != Prayer.MAGHRIB }
                        .map { (prayer, angleDegrees) ->
                            val radians = angleDegrees * PI / 180.0
                            val labelCenterX = circleCenterX + timelinePrayerLabelRadius * cos(radians).toFloat()
                            val labelCenterY = circleCenterY + timelinePrayerLabelRadius * sin(radians).toFloat()
                            Triple(
                                prayer,
                                (labelCenterX - timelinePrayerLabelWidth / 2f).coerceIn(4f, panelWidth - timelinePrayerLabelWidth - 4f),
                                (labelCenterY - timelinePrayerLabelHeight / 2f).coerceIn(4f, panelHeight - timelinePrayerLabelHeight - 4f),
                            )
                        }
                    val timelineAlarmLabelWidth = 40f
                    val timelineAlarmLabelHeight = 16f
                    val timelineAlarmMarkerRadius = 5.2f
                    val timelineAlarmGap = 3f
                    val timelineAlarmClusterLabelWidth = timelineAlarmLabelWidth
                    val timelineAlarmCollisionPadding = 3f
                    val timelineAlarmMinMarkerSpacing = timelineAlarmMarkerRadius * 2f + 3.2f
                    val timelineAlarmAngles = data.timelineAlarms.map { alarm -> minuteToTimelineAngleDegrees(alarm.minuteOfDay) }
                    val timelineAlarmLabelHalfWidth = timelineAlarmLabelWidth / 2f
                    val timelineAlarmLabelHalfHeight = timelineAlarmLabelHeight / 2f
                    val timelineAlarmMarkerLabelCenterDistance = timelineAlarmMarkerRadius + timelineAlarmGap + (
                        timelineAlarmAngles.maxOfOrNull { angleDegrees ->
                            val radians = angleDegrees * PI / 180.0
                            val radialX = cos(radians).toFloat()
                            val radialY = sin(radians).toFloat()
                            abs(radialX) * timelineAlarmLabelHalfWidth + abs(radialY) * timelineAlarmLabelHalfHeight
                        } ?: 0f
                    )

                    // Fixed obstacles: every non-alarm time label that alarm labels must never overlap.
                    val nonAlarmLabelObstacles = mutableListOf<Rect>()
                    run {
                        val pad = 2f
                        fun obstacle(x: Float, y: Float, width: Float, height: Float) {
                            nonAlarmLabelObstacles.add(
                                Rect(x - pad, y - pad, x + width + pad, y + height + pad),
                            )
                        }
                        obstacle(sunriseLabelX, sunriseLabelY, sunriseLabelWidth, timeLabelHeight)
                        obstacle(maghribLabelX, maghribLabelY, maghribLabelWidth, timeLabelHeight)
                        obstacle(fajrLabelX, fajrLabelY, fajrLabelWidth, timeLabelHeight)
                        obstacle(secondThirdLabelX, secondThirdLabelY, thirdLabelWidth, timeLabelHeight)
                        obstacle(finalThirdLabelX, finalThirdLabelY, thirdLabelWidth, timeLabelHeight)
                        obstacle(twilightLabelX, twilightLabelY, 56f, 20f)
                        if (data.showCurrentTime && data.sunAltitudeDeg != null) {
                            obstacle(12f, 14f, panelWidth - 24f, sunAngleLineHeight)
                        }
                        timelinePrayerLabels.forEach { (_, labelX, labelY) ->
                            obstacle(labelX, labelY, timelinePrayerLabelWidth, timelinePrayerLabelHeight)
                        }
                    }

                    // Non-alarm markers act as ordering barriers: alarms separated by a prayer/event
                    // marker must never be reordered or merged across it (keeps before/after intact).
                    val nonAlarmBarrierAngles = (
                        listOf(
                            sunriseAngleDegrees,
                            sunriseAngleDegrees + 360f,
                            sunsetAngleDegrees,
                            secondThirdAngleDegrees,
                            finalThirdAngleDegrees,
                            fajrAngleDegrees,
                        ) + timelinePrayerAngles
                        ).distinct().sorted()
                    fun barrierGapIndex(angleDegrees: Float): Int =
                        nonAlarmBarrierAngles.count { barrierAngle -> barrierAngle < angleDegrees }

                    data class TimelineAlarmCandidate(
                        val alarm: NightTimelineAlarm,
                        val angleDegrees: Float,
                        val gapIndex: Int,
                        val iconCenterX: Float,
                        val iconCenterY: Float,
                        val labelX: Float,
                        val labelY: Float,
                        val labelBounds: Rect,
                        val markerBounds: Rect,
                    )

                    data class TimelineAlarmClusterDraft(
                        val candidates: MutableList<TimelineAlarmCandidate>,
                        var labelBounds: Rect,
                        var markerBounds: Rect,
                    )

                    fun expandedRect(left: Float, top: Float, right: Float, bottom: Float, padding: Float): Rect = Rect(
                        left = left - padding,
                        top = top - padding,
                        right = right + padding,
                        bottom = bottom + padding,
                    )

                    fun combineRects(first: Rect, second: Rect): Rect = Rect(
                        left = minOf(first.left, second.left),
                        top = minOf(first.top, second.top),
                        right = maxOf(first.right, second.right),
                        bottom = maxOf(first.bottom, second.bottom),
                    )

                    fun rectsOverlap(first: Rect, second: Rect): Boolean =
                        first.left < second.right &&
                            second.left < first.right &&
                            first.top < second.bottom &&
                            second.top < first.bottom

                    fun markerDistance(first: TimelineAlarmCandidate, second: TimelineAlarmCandidate): Float {
                        val dx = first.iconCenterX - second.iconCenterX
                        val dy = first.iconCenterY - second.iconCenterY
                        return sqrt(dx * dx + dy * dy)
                    }

                    // Only merge alarms that physically collide on the ring AND share the same gap
                    // between non-alarm barriers — this preserves before/after ordering (reqs 5 & 6).
                    fun clusterCollides(cluster: TimelineAlarmClusterDraft, candidate: TimelineAlarmCandidate): Boolean =
                        cluster.candidates.first().gapIndex == candidate.gapIndex &&
                            cluster.candidates.any { existing -> existing.alarm.minuteOfDay == candidate.alarm.minuteOfDay }

                    fun clustersCollide(first: TimelineAlarmClusterDraft, second: TimelineAlarmClusterDraft): Boolean =
                        first.candidates.first().gapIndex == second.candidates.first().gapIndex &&
                            first.candidates.any { left ->
                                second.candidates.any { right -> left.alarm.minuteOfDay == right.alarm.minuteOfDay }
                            }

                    val markerHalfSize = timelineAlarmMarkerRadius + 1f
                    val timelineAlarmCandidates = data.timelineAlarms.zip(timelineAlarmAngles).map { (alarm, angleDegrees) ->
                        val radians = angleDegrees * PI / 180.0
                        val radialX = cos(radians).toFloat()
                        val radialY = sin(radians).toFloat()
                        val iconCenterX = circleCenterX + circleRadius * radialX
                        val iconCenterY = circleCenterY + circleRadius * radialY
                        val labelRadius = (circleRadius - timelineAlarmMarkerLabelCenterDistance).coerceAtLeast(18f)
                        val labelCenterX = circleCenterX + labelRadius * radialX
                        val labelCenterY = circleCenterY + labelRadius * radialY
                        val labelX = labelCenterX - timelineAlarmLabelWidth / 2f
                        val labelY = labelCenterY - timelineAlarmLabelHeight / 2f
                        TimelineAlarmCandidate(
                            alarm = alarm,
                            angleDegrees = angleDegrees,
                            gapIndex = barrierGapIndex(angleDegrees),
                            iconCenterX = iconCenterX,
                            iconCenterY = iconCenterY,
                            labelX = labelX,
                            labelY = labelY,
                            labelBounds = expandedRect(
                                labelX,
                                labelY,
                                labelX + timelineAlarmLabelWidth,
                                labelY + timelineAlarmLabelHeight,
                                timelineAlarmCollisionPadding,
                            ),
                            markerBounds = expandedRect(
                                iconCenterX - markerHalfSize,
                                iconCenterY - markerHalfSize,
                                iconCenterX + markerHalfSize,
                                iconCenterY + markerHalfSize,
                                0f,
                            ),
                        )
                    }
                    val timelineAlarmClusterDrafts = mutableListOf<TimelineAlarmClusterDraft>()
                    timelineAlarmCandidates.sortedBy { candidate -> candidate.angleDegrees }.forEach { candidate ->
                        val previousCluster = timelineAlarmClusterDrafts.lastOrNull()
                        if (previousCluster != null && clusterCollides(previousCluster, candidate)) {
                            previousCluster.candidates.add(candidate)
                            previousCluster.labelBounds = combineRects(previousCluster.labelBounds, candidate.labelBounds)
                            previousCluster.markerBounds = combineRects(previousCluster.markerBounds, candidate.markerBounds)
                        } else {
                            timelineAlarmClusterDrafts.add(
                                TimelineAlarmClusterDraft(
                                    candidates = mutableListOf(candidate),
                                    labelBounds = candidate.labelBounds,
                                    markerBounds = candidate.markerBounds,
                                ),
                            )
                        }
                    }
                    if (timelineAlarmClusterDrafts.size > 1) {
                        val firstCluster = timelineAlarmClusterDrafts.first()
                        val lastCluster = timelineAlarmClusterDrafts.last()
                        if (clustersCollide(lastCluster, firstCluster)) {
                            lastCluster.candidates.addAll(firstCluster.candidates)
                            lastCluster.labelBounds = combineRects(lastCluster.labelBounds, firstCluster.labelBounds)
                            lastCluster.markerBounds = combineRects(lastCluster.markerBounds, firstCluster.markerBounds)
                            timelineAlarmClusterDrafts.removeAt(0)
                        }
                    }

                    val nonAlarmMarkerCenters = nonAlarmBarrierAngles.map { angleDegrees ->
                        val radians = angleDegrees * PI / 180.0
                        (circleCenterX + circleRadius * cos(radians).toFloat()) to
                            (circleCenterY + circleRadius * sin(radians).toFloat())
                    }
                    // Non-alarm marker dots become small obstacles so alarm labels never sit on top of them.
                    val nonAlarmMarkerObstacles = nonAlarmMarkerCenters.map { (markerX, markerY) ->
                        expandedRect(markerX - 5.5f, markerY - 5.5f, markerX + 5.5f, markerY + 5.5f, 1.5f)
                    }
                    val placedAlarmMarkerCenters = mutableListOf<Pair<Float, Float>>()
                    val allAlarmMarkerObstacles = mutableListOf<Rect>()
                    val placedAlarmLabelBounds = mutableListOf<Rect>()

                    data class TimelineAlarmMarkerPlacement(
                        val cluster: TimelineAlarmClusterDraft,
                        val anchor: TimelineAlarmCandidate,
                        val alarms: List<NightTimelineAlarm>,
                        val labelWidth: Float,
                        val desiredAngleDegrees: Float,
                        val visualAngleDegrees: Float,
                        val markerX: Float,
                        val markerY: Float,
                        val markerWasNudged: Boolean,
                    )

                    data class TimelineAlarmMarkerDraft(
                        val candidates: List<TimelineAlarmCandidate>,
                        val desiredAngleDegrees: Float,
                        val gapIndex: Int,
                    ) {
                        val alarms: List<NightTimelineAlarm> = candidates.map { candidate -> candidate.alarm }.sortedBy { alarm -> alarm.minuteOfDay }
                        val anchor: TimelineAlarmCandidate = candidates.sortedBy { candidate -> candidate.angleDegrees }[candidates.size / 2]
                        val labelWidth: Float = if (alarms.size > 1) timelineAlarmClusterLabelWidth else timelineAlarmLabelWidth
                    }

                    fun signedAngleDeltaDegrees(fromDegrees: Float, toDegrees: Float): Float =
                        ((toDegrees - fromDegrees + 540f) % 360f) - 180f

                    fun timelinePointAt(angleDegrees: Float): Pair<Float, Float> {
                        val radians = angleDegrees * PI / 180.0
                        return (circleCenterX + circleRadius * cos(radians).toFloat()) to
                            (circleCenterY + circleRadius * sin(radians).toFloat())
                    }

                    fun pointDistance(first: Pair<Float, Float>, second: Pair<Float, Float>): Float {
                        val dx = first.first - second.first
                        val dy = first.second - second.second
                        return sqrt(dx * dx + dy * dy)
                    }

                    val alarmMarkerMinGap = timelineAlarmMarkerRadius + 5.2f + 2f
                    val alarmMarkerMinAngleGap = alarmMarkerMinGap / circleRadius * (180f / PI.toFloat())
                    val alarmToAlarmMarkerMinAngleGap = timelineAlarmMinMarkerSpacing / circleRadius * (180f / PI.toFloat())

                    fun gapBounds(gapIndex: Int): Pair<Float, Float> {
                        val lower = if (gapIndex > 0) {
                            nonAlarmBarrierAngles[gapIndex - 1]
                        } else {
                            nonAlarmBarrierAngles.first() - 360f
                        }
                        val upper = if (gapIndex < nonAlarmBarrierAngles.size) {
                            nonAlarmBarrierAngles[gapIndex]
                        } else {
                            nonAlarmBarrierAngles.last() + 360f
                        }
                        return (lower + alarmMarkerMinAngleGap) to (upper - alarmMarkerMinAngleGap)
                    }

                    fun fitMarkerAngles(drafts: List<TimelineAlarmMarkerDraft>): List<Pair<TimelineAlarmMarkerDraft, Float>>? {
                        if (drafts.isEmpty()) return emptyList()
                        val gapIndex = drafts.first().gapIndex
                        val (minAngle, maxAngle) = gapBounds(gapIndex)
                        if (minAngle > maxAngle) return null
                        val sortedDrafts = drafts.sortedBy { draft -> draft.desiredAngleDegrees }
                        val angles = sortedDrafts.map { draft -> draft.desiredAngleDegrees.coerceIn(minAngle, maxAngle) }.toMutableList()
                        angles[0] = angles[0].coerceAtLeast(minAngle)
                        for (index in 1 until angles.size) {
                            angles[index] = angles[index].coerceAtLeast(angles[index - 1] + alarmToAlarmMarkerMinAngleGap)
                        }
                        if (angles.last() > maxAngle) {
                            angles[angles.lastIndex] = maxAngle
                            for (index in angles.lastIndex - 1 downTo 0) {
                                angles[index] = angles[index].coerceAtMost(angles[index + 1] - alarmToAlarmMarkerMinAngleGap)
                            }
                        }
                        if (angles.first() < minAngle || angles.last() > maxAngle) return null
                        return sortedDrafts.zip(angles)
                    }

                    fun mergeClosestMarkerDrafts(drafts: List<TimelineAlarmMarkerDraft>): List<TimelineAlarmMarkerDraft> {
                        if (drafts.size < 2) return drafts
                        val sortedDrafts = drafts.sortedBy { draft -> draft.desiredAngleDegrees }
                        val mergeIndex = (0 until sortedDrafts.lastIndex).minByOrNull { index ->
                            sortedDrafts[index + 1].desiredAngleDegrees - sortedDrafts[index].desiredAngleDegrees
                        } ?: return sortedDrafts
                        val mergedCandidates = sortedDrafts[mergeIndex].candidates + sortedDrafts[mergeIndex + 1].candidates
                        val mergedDesiredAngle = mergedCandidates.map { candidate -> candidate.angleDegrees }.average().toFloat()
                        val mergedDraft = TimelineAlarmMarkerDraft(
                            candidates = mergedCandidates.sortedBy { candidate -> candidate.angleDegrees },
                            desiredAngleDegrees = mergedDesiredAngle,
                            gapIndex = sortedDrafts[mergeIndex].gapIndex,
                        )
                        return buildList {
                            sortedDrafts.forEachIndexed { index, draft ->
                                when (index) {
                                    mergeIndex -> add(mergedDraft)
                                    mergeIndex + 1 -> Unit
                                    else -> add(draft)
                                }
                            }
                        }
                    }

                    fun layoutMarkerDraftsForGap(drafts: List<TimelineAlarmMarkerDraft>): List<Pair<TimelineAlarmMarkerDraft, Float>> {
                        var pendingDrafts = drafts
                        while (pendingDrafts.size > 1) {
                            fitMarkerAngles(pendingDrafts)?.let { return it }
                            pendingDrafts = mergeClosestMarkerDrafts(pendingDrafts)
                        }
                        return fitMarkerAngles(pendingDrafts) ?: pendingDrafts.map { draft ->
                            val (minAngle, maxAngle) = gapBounds(draft.gapIndex)
                            // Close prayer markers can leave less room than the preferred padding.
                            // Keep the remaining alarm group between them without an invalid range.
                            draft to if (minAngle <= maxAngle) {
                                draft.desiredAngleDegrees.coerceIn(minAngle, maxAngle)
                            } else {
                                (minAngle + maxAngle) / 2f
                            }
                        }
                    }

                    // Resolve the label position for one cluster: search outward from the marker dot
                    // for the nearest free spot, preferring the radial direction (inside then outside the
                    // ring) and only drifting tangentially when needed. Distance from the dot grows
                    // gradually, so the chosen label is always as close to its marker as possible.
                    fun resolveLabelCenter(angleDegrees: Float, labelWidth: Float): Pair<Float, Float> {
                        val radians = angleDegrees * PI / 180.0
                        val radialX = cos(radians).toFloat()
                        val radialY = sin(radians).toFloat()
                        val halfWidth = labelWidth / 2f
                        val halfHeight = timelineAlarmLabelHeight / 2f
                        val markerX = circleCenterX + circleRadius * radialX
                        val markerY = circleCenterY + circleRadius * radialY
                        // Minimum gap so the label box never overlaps its own marker dot.
                        val minDistance = timelineAlarmMarkerRadius + timelineAlarmGap +
                            (abs(radialX) * halfWidth + abs(radialY) * halfHeight)
                        // Inward direction (marker -> circle centre); outward is the opposite.
                        val inwardAngle = atan2((circleCenterY - markerY).toDouble(), (circleCenterX - markerX).toDouble())

                        fun boxAtPoint(centerX: Float, centerY: Float): Rect =
                            Rect(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
                        fun withinPanel(box: Rect): Boolean =
                            box.left >= 2f && box.top >= 2f && box.right <= panelWidth - 2f && box.bottom <= panelHeight - 2f
                        fun touchesTimelineCircle(box: Rect): Boolean {
                            val closestX = circleCenterX.coerceIn(box.left, box.right)
                            val closestY = circleCenterY.coerceIn(box.top, box.bottom)
                            val closestDx = closestX - circleCenterX
                            val closestDy = closestY - circleCenterY
                            val closestDistance = sqrt(closestDx * closestDx + closestDy * closestDy)
                            val farthestDistance = maxOf(
                                sqrt((box.left - circleCenterX) * (box.left - circleCenterX) + (box.top - circleCenterY) * (box.top - circleCenterY)),
                                sqrt((box.right - circleCenterX) * (box.right - circleCenterX) + (box.top - circleCenterY) * (box.top - circleCenterY)),
                                sqrt((box.left - circleCenterX) * (box.left - circleCenterX) + (box.bottom - circleCenterY) * (box.bottom - circleCenterY)),
                                sqrt((box.right - circleCenterX) * (box.right - circleCenterX) + (box.bottom - circleCenterY) * (box.bottom - circleCenterY)),
                            )
                            val circlePadding = 4f
                            return closestDistance <= circleRadius + circlePadding && farthestDistance >= circleRadius - circlePadding
                        }
                        fun clears(box: Rect): Boolean =
                            withinPanel(box) &&
                                !touchesTimelineCircle(box) &&
                                nonAlarmLabelObstacles.none { rectsOverlap(it, box) } &&
                                nonAlarmMarkerObstacles.none { rectsOverlap(it, box) } &&
                                allAlarmMarkerObstacles.none { rectsOverlap(it, box) } &&
                                placedAlarmLabelBounds.none { rectsOverlap(it, box) }
                        fun clearsHardObstacles(box: Rect): Boolean =
                            withinPanel(box) &&
                                !touchesTimelineCircle(box) &&
                                nonAlarmLabelObstacles.none { rectsOverlap(it, box) } &&
                                nonAlarmMarkerObstacles.none { rectsOverlap(it, box) } &&
                                allAlarmMarkerObstacles.none { rectsOverlap(it, box) }

                        // Direction offsets relative to the inward radial ray, ordered by preference:
                        // straight inside, straight outside, then progressively larger tangential drifts.
                        val directionOffsets = listOf(
                            0f,
                            180f,
                            20f,
                            -20f,
                            160f,
                            -160f,
                            40f,
                            -40f,
                            140f,
                            -140f,
                            60f,
                            -60f,
                            120f,
                            -120f,
                            90f,
                            -90f,
                        )
                        var fallbackCenter: Pair<Float, Float>? = null
                        var distance = minDistance
                        while (distance <= minDistance + 220f) {
                            for (offset in directionOffsets) {
                                val dir = inwardAngle + offset * PI / 180.0
                                val centerX = markerX + distance * cos(dir).toFloat()
                                val centerY = markerY + distance * sin(dir).toFloat()
                                val box = boxAtPoint(centerX, centerY)
                                if (fallbackCenter == null && clearsHardObstacles(box)) {
                                    fallbackCenter = centerX to centerY
                                }
                                if (clears(box)) {
                                    placedAlarmLabelBounds.add(
                                        expandedRect(box.left, box.top, box.right, box.bottom, timelineAlarmCollisionPadding),
                                    )
                                    return centerX to centerY
                                }
                            }
                            distance += 8f
                        }
                        val safeFallbackCenter = fallbackCenter ?: (
                            (circleCenterX + (circleRadius - minDistance) * radialX) to
                                (circleCenterY + (circleRadius - minDistance) * radialY)
                            )
                        val fallbackBox = boxAtPoint(safeFallbackCenter.first, safeFallbackCenter.second)
                        placedAlarmLabelBounds.add(
                            expandedRect(fallbackBox.left, fallbackBox.top, fallbackBox.right, fallbackBox.bottom, timelineAlarmCollisionPadding),
                        )
                        return safeFallbackCenter
                    }

                    val timelineAlarmMarkerDrafts = timelineAlarmClusterDrafts
                        .map { cluster ->
                            val sortedCandidates = cluster.candidates.sortedBy { candidate -> candidate.angleDegrees }
                            val anchor = sortedCandidates[sortedCandidates.size / 2]
                            TimelineAlarmMarkerDraft(
                                candidates = sortedCandidates,
                                desiredAngleDegrees = anchor.angleDegrees,
                                gapIndex = anchor.gapIndex,
                            )
                        }

                    val timelineAlarmMarkerPlacements = timelineAlarmMarkerDrafts
                        .groupBy { draft -> draft.gapIndex }
                        .toSortedMap()
                        .flatMap { (_, gapDrafts) -> layoutMarkerDraftsForGap(gapDrafts) }
                        .sortedBy { (draft, _) -> draft.desiredAngleDegrees }
                        .map { (draft, visualAngleDegrees) ->
                            val radians = visualAngleDegrees * PI / 180.0
                            val radialX = cos(radians).toFloat()
                            val radialY = sin(radians).toFloat()
                            val markerX = circleCenterX + circleRadius * radialX
                            val markerY = circleCenterY + circleRadius * radialY
                            val markerWasNudgedForNonAlarm = nonAlarmMarkerCenters.any { markerCenter ->
                                pointDistance(timelinePointAt(draft.desiredAngleDegrees), markerCenter) < alarmMarkerMinGap
                            }

                            TimelineAlarmMarkerPlacement(
                                cluster = TimelineAlarmClusterDraft(
                                    candidates = draft.candidates.toMutableList(),
                                    labelBounds = draft.candidates.first().labelBounds,
                                    markerBounds = draft.candidates.first().markerBounds,
                                ),
                                anchor = draft.anchor,
                                alarms = draft.alarms,
                                labelWidth = draft.labelWidth,
                                desiredAngleDegrees = draft.desiredAngleDegrees,
                                visualAngleDegrees = visualAngleDegrees,
                                markerX = markerX,
                                markerY = markerY,
                                markerWasNudged = markerWasNudgedForNonAlarm,
                            )
                        }

                    timelineAlarmMarkerPlacements.forEach { placement ->
                        allAlarmMarkerObstacles.add(
                            expandedRect(
                                placement.markerX - timelineAlarmMarkerRadius,
                                placement.markerY - timelineAlarmMarkerRadius,
                                placement.markerX + timelineAlarmMarkerRadius,
                                placement.markerY + timelineAlarmMarkerRadius,
                                1.5f,
                            ),
                        )
                    }

                    val timelineAlarmPlacements = timelineAlarmMarkerPlacements.map { placement ->
                        val (labelCenterX, labelCenterY) = resolveLabelCenter(placement.visualAngleDegrees, placement.labelWidth)

                        NightTimelineAlarmPlacement(
                            id = placement.alarms.joinToString("|") { alarm -> alarm.id },
                            alarms = placement.alarms,
                            iconCenterX = placement.markerX,
                            iconCenterY = placement.markerY,
                            nearNonAlarmMarker = placement.markerWasNudged,
                            labelCenterX = labelCenterX,
                            labelCenterY = labelCenterY,
                            labelX = labelCenterX - placement.labelWidth / 2f,
                            labelY = labelCenterY - timelineAlarmLabelHeight / 2f,
                            labelWidth = placement.labelWidth,
                            label = if (placement.alarms.size > 1) "+${placement.alarms.size}" else placement.alarms.first().title,
                            time = placement.alarms.first().time,
                        )
                    }

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val scaleX = size.width / panelWidth
                        val scaleY = size.height / panelHeight
                        val circleCenter = Offset(circleCenterX * scaleX, circleCenterY * scaleY)
                        val circleRadiusPx = circleRadius * scaleX
                        val circleTopPx = circleTop * scaleY
                        val circleBottomPx = circleBottom * scaleY
                        val circleLeftPx = (circleCenterX - circleRadius) * scaleX
                        val circleRightPx = (circleCenterX + circleRadius) * scaleX
                        val sunrisePoint = Offset(sunriseX * scaleX, sunriseY * scaleY)
                        val sunsetPoint = Offset(sunsetX * scaleX, sunsetY * scaleY)
                        val sunriseSunCenter = Offset(sunriseSunX * scaleX, sunriseSunY * scaleY)
                        val sunsetSunCenter = Offset(sunsetSunX * scaleX, sunsetSunY * scaleY)
                        val noonSunCenter = Offset(circleCenter.x, circleTopPx - 28f * scaleY)
                        val midnightMoonCenter = Offset(
                            x = circleCenter.x * 2f - noonSunCenter.x,
                            y = circleCenter.y * 2f - noonSunCenter.y,
                        )
                        val secondThirdPoint = Offset(secondThirdX * scaleX, secondThirdY * scaleY)
                        val finalThirdPoint = Offset(finalThirdX * scaleX, finalThirdY * scaleY)
                        val fajrPoint = Offset(fajrX * scaleX, fajrY * scaleY)
                        val nightApex = Offset(circleCenter.x, circleBottomPx - 8f * scaleY)
                        val nightHeightPx = circleBottomPx - circleCenter.y
                        val sunsetBoundaryEnd = Offset(sunsetPanelBorderX * scaleX, sunsetPanelBorderY * scaleY)
                        val fajrRayDx = fajrPoint.x - circleCenter.x
                        val fajrRayDy = fajrPoint.y - circleCenter.y
                        val fajrRayToLeft = if (fajrRayDx < 0f) (0f - fajrPoint.x) / fajrRayDx else Float.POSITIVE_INFINITY
                        val fajrRayToBottom = if (fajrRayDy > 0f) (size.height - fajrPoint.y) / fajrRayDy else Float.POSITIVE_INFINITY
                        val fajrRayScale = minOf(fajrRayToLeft, fajrRayToBottom).coerceAtLeast(0f)
                        val fajrBoundaryEnd = Offset(
                            x = fajrPoint.x + fajrRayDx * fajrRayScale,
                            y = fajrPoint.y + fajrRayDy * fajrRayScale,
                        )
                        val circleRect = Rect(circleLeftPx, circleTopPx, circleRightPx, circleBottomPx)
                        val circlePath = Path().apply {
                            addOval(circleRect)
                        }
                        val dayPath = Path().apply {
                            moveTo(circleCenter.x, circleCenter.y)
                            lineTo(sunrisePoint.x, sunrisePoint.y)
                            arcTo(circleRect, sunriseAngleDegrees, daylightSweepDegrees, false)
                            lineTo(circleCenter.x, circleCenter.y)
                            close()
                        }
                        val twilightPath = Path().apply {
                            moveTo(circleCenter.x, circleCenter.y)
                            lineTo(fajrPoint.x, fajrPoint.y)
                            arcTo(circleRect, fajrAngleDegrees, twilightSweepDegrees, false)
                            lineTo(circleCenter.x, circleCenter.y)
                            close()
                        }
                        val lowerNightField = Path().apply {
                            moveTo(0f, sunrisePoint.y)
                            lineTo(sunrisePoint.x, sunrisePoint.y)
                            lineTo(sunsetPoint.x, sunsetPoint.y)
                            lineTo(sunsetBoundaryEnd.x, sunsetBoundaryEnd.y)
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                        val daylightField = Path().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width, 0f)
                            lineTo(sunsetBoundaryEnd.x, sunsetBoundaryEnd.y)
                            lineTo(sunsetPoint.x, sunsetPoint.y)
                            lineTo(sunrisePoint.x, sunrisePoint.y)
                            lineTo(0f, sunrisePoint.y)
                            close()
                        }
                        val twilightField = Path().apply {
                            moveTo(0f, sunrisePoint.y)
                            lineTo(sunrisePoint.x, sunrisePoint.y)
                            lineTo(fajrPoint.x, fajrPoint.y)
                            lineTo(fajrBoundaryEnd.x, fajrBoundaryEnd.y)
                            if (fajrBoundaryEnd.y >= size.height - 1f) {
                                lineTo(0f, size.height)
                            }
                            close()
                        }
                        fun drawHorizonSun(center: Offset, color: Color) {
                            for (ray in 0..8) {
                                val angle = PI + ray * PI / 8.0
                                drawLine(
                                    color = color.copy(alpha = 0.60f),
                                    start = Offset(
                                        center.x + 14f * scaleX * cos(angle).toFloat(),
                                        center.y + 14f * scaleY * sin(angle).toFloat(),
                                    ),
                                    end = Offset(
                                        center.x + 23f * scaleX * cos(angle).toFloat(),
                                        center.y + 23f * scaleY * sin(angle).toFloat(),
                                    ),
                                    strokeWidth = 1.7f * scaleY,
                                )
                            }
                            drawCircle(color.copy(alpha = 0.33f), radius = 18f * scaleX, center = center)
                            drawCircle(color, radius = 11f * scaleX, center = center)
                        }
                        fun drawCloud(anchorX: Float, anchorY: Float, cloudScale: Float, alpha: Float = 0.36f) {
                            val cloudScalePx = scaleX * cloudScale
                            val cloudColor = Color.White.copy(alpha = alpha)
                            val cloudPath = Path().apply {
                                moveTo(anchorX, anchorY + 28f * cloudScalePx)
                                cubicTo(
                                    anchorX + 5f * cloudScalePx,
                                    anchorY + 16f * cloudScalePx,
                                    anchorX + 15f * cloudScalePx,
                                    anchorY + 17f * cloudScalePx,
                                    anchorX + 21f * cloudScalePx,
                                    anchorY + 10f * cloudScalePx,
                                )
                                cubicTo(
                                    anchorX + 27f * cloudScalePx,
                                    anchorY - 4f * cloudScalePx,
                                    anchorX + 44f * cloudScalePx,
                                    anchorY - 2f * cloudScalePx,
                                    anchorX + 49f * cloudScalePx,
                                    anchorY + 12f * cloudScalePx,
                                )
                                cubicTo(
                                    anchorX + 60f * cloudScalePx,
                                    anchorY + 7f * cloudScalePx,
                                    anchorX + 75f * cloudScalePx,
                                    anchorY + 15f * cloudScalePx,
                                    anchorX + 76f * cloudScalePx,
                                    anchorY + 28f * cloudScalePx,
                                )
                                cubicTo(
                                    anchorX + 76f * cloudScalePx,
                                    anchorY + 40f * cloudScalePx,
                                    anchorX + 57f * cloudScalePx,
                                    anchorY + 43f * cloudScalePx,
                                    anchorX + 45f * cloudScalePx,
                                    anchorY + 39f * cloudScalePx,
                                )
                                cubicTo(
                                    anchorX + 31f * cloudScalePx,
                                    anchorY + 45f * cloudScalePx,
                                    anchorX + 10f * cloudScalePx,
                                    anchorY + 42f * cloudScalePx,
                                    anchorX,
                                    anchorY + 28f * cloudScalePx,
                                )
                                close()
                            }
                            val lowerWisp = Path().apply {
                                moveTo(anchorX + 8f * cloudScalePx, anchorY + 34f * cloudScalePx)
                                cubicTo(
                                    anchorX + 24f * cloudScalePx,
                                    anchorY + 41f * cloudScalePx,
                                    anchorX + 49f * cloudScalePx,
                                    anchorY + 42f * cloudScalePx,
                                    anchorX + 70f * cloudScalePx,
                                    anchorY + 31f * cloudScalePx,
                                )
                            }
                            drawPath(cloudPath, cloudColor)
                            drawPath(
                                path = cloudPath,
                                color = Color.White.copy(alpha = alpha * 0.50f),
                                style = Stroke(width = 1.4f * cloudScalePx),
                            )
                            drawPath(
                                path = lowerWisp,
                                color = Color.White.copy(alpha = alpha * 0.62f),
                                style = Stroke(width = 1.1f * cloudScalePx),
                            )
                        }
                        fun drawStar(center: Offset, radius: Float, alpha: Float = 0.70f) {
                            drawCircle(GoldLight.copy(alpha = alpha), radius = radius * 0.34f, center = center)
                            drawCircle(
                                Color.White.copy(alpha = alpha * 0.50f),
                                radius = radius * 0.13f,
                                center = Offset(center.x + radius * 0.15f, center.y - radius * 0.12f),
                            )
                        }
                        fun drawCrescent(center: Offset) {
                            val moonRadius = 8.5f * scaleX
                            val outerMoonPath = Path().apply {
                                addOval(
                                    Rect(
                                        left = center.x - moonRadius,
                                        top = center.y - moonRadius,
                                        right = center.x + moonRadius,
                                        bottom = center.y + moonRadius,
                                    ),
                                )
                            }
                            val innerMoonRadius = moonRadius * 0.96f
                            val innerMoonCenter = Offset(
                                x = center.x + moonRadius * 0.50f,
                                y = center.y,
                            )
                            val innerMoonPath = Path().apply {
                                addOval(
                                    Rect(
                                        left = innerMoonCenter.x - innerMoonRadius,
                                        top = innerMoonCenter.y - innerMoonRadius,
                                        right = innerMoonCenter.x + innerMoonRadius,
                                        bottom = innerMoonCenter.y + innerMoonRadius,
                                    ),
                                )
                            }
                            drawPath(Path.combine(PathOperation.Difference, outerMoonPath, innerMoonPath), GoldLight)
                        }
                        fun drawNightMosqueSilhouette(
                            centerX: Float,
                            baseY: Float,
                            silhouetteScale: Float,
                            alpha: Float,
                            color: Color = Color(0xFF061D32),
                        ) {
                            val silhouetteColor = color.copy(alpha = alpha)
                            val detailColor = Color(0xFF86AFC2).copy(alpha = alpha * 0.22f)
                            val xScale = scaleX * silhouetteScale
                            val yScale = scaleY * silhouetteScale

                            fun drawRectangle(leftOffset: Float, topOffset: Float, rightOffset: Float, bottomOffset: Float) {
                                val rectangle = Path().apply {
                                    moveTo(centerX + leftOffset * xScale, baseY + topOffset * yScale)
                                    lineTo(centerX + rightOffset * xScale, baseY + topOffset * yScale)
                                    lineTo(centerX + rightOffset * xScale, baseY + bottomOffset * yScale)
                                    lineTo(centerX + leftOffset * xScale, baseY + bottomOffset * yScale)
                                    close()
                                }
                                drawPath(rectangle, silhouetteColor)
                            }

                            fun drawArchedOpening(centerOffset: Float, bottomOffset: Float, width: Float, height: Float) {
                                val halfWidth = width * xScale / 2f
                                val openingCenterX = centerX + centerOffset * xScale
                                val openingBottomY = baseY + bottomOffset * yScale
                                val openingHeight = height * yScale
                                val openingPath = Path().apply {
                                    moveTo(openingCenterX - halfWidth, openingBottomY)
                                    lineTo(openingCenterX - halfWidth, openingBottomY - openingHeight * 0.56f)
                                    cubicTo(
                                        openingCenterX - halfWidth,
                                        openingBottomY - openingHeight * 0.88f,
                                        openingCenterX - halfWidth * 0.45f,
                                        openingBottomY - openingHeight,
                                        openingCenterX,
                                        openingBottomY - openingHeight,
                                    )
                                    cubicTo(
                                        openingCenterX + halfWidth * 0.45f,
                                        openingBottomY - openingHeight,
                                        openingCenterX + halfWidth,
                                        openingBottomY - openingHeight * 0.88f,
                                        openingCenterX + halfWidth,
                                        openingBottomY - openingHeight * 0.56f,
                                    )
                                    lineTo(openingCenterX + halfWidth, openingBottomY)
                                    close()
                                }
                                drawPath(openingPath, detailColor)
                            }

                            fun drawFinial(finialCenterX: Float, finialBaseY: Float, finialHeight: Float, finialRadius: Float) {
                                drawLine(
                                    color = silhouetteColor,
                                    start = Offset(finialCenterX, finialBaseY),
                                    end = Offset(finialCenterX, finialBaseY - finialHeight),
                                    strokeWidth = 1.1f * xScale,
                                )
                                drawCircle(
                                    color = silhouetteColor,
                                    radius = finialRadius,
                                    center = Offset(finialCenterX, finialBaseY - finialHeight * 0.58f),
                                )
                            }

                            fun drawDome(centerOffset: Float, baseOffset: Float, halfWidth: Float, height: Float, finialHeight: Float) {
                                val domeCenterX = centerX + centerOffset * xScale
                                val domeBaseY = baseY + baseOffset * yScale
                                val halfWidthPx = halfWidth * xScale
                                val heightPx = height * yScale
                                val domePath = Path().apply {
                                    moveTo(domeCenterX - halfWidthPx, domeBaseY)
                                    cubicTo(
                                        domeCenterX - halfWidthPx * 1.03f,
                                        domeBaseY - heightPx * 0.32f,
                                        domeCenterX - halfWidthPx * 0.64f,
                                        domeBaseY - heightPx * 0.64f,
                                        domeCenterX - halfWidthPx * 0.14f,
                                        domeBaseY - heightPx * 0.83f,
                                    )
                                    quadraticTo(
                                        domeCenterX,
                                        domeBaseY - heightPx,
                                        domeCenterX + halfWidthPx * 0.14f,
                                        domeBaseY - heightPx * 0.83f,
                                    )
                                    cubicTo(
                                        domeCenterX + halfWidthPx * 0.64f,
                                        domeBaseY - heightPx * 0.64f,
                                        domeCenterX + halfWidthPx * 1.03f,
                                        domeBaseY - heightPx * 0.32f,
                                        domeCenterX + halfWidthPx,
                                        domeBaseY,
                                    )
                                    close()
                                }
                                drawPath(domePath, silhouetteColor)
                                drawFinial(
                                    finialCenterX = domeCenterX,
                                    finialBaseY = domeBaseY - heightPx + 1.5f * yScale,
                                    finialHeight = finialHeight * yScale,
                                    finialRadius = 1.2f * xScale,
                                )
                            }

                            fun drawMinaret(centerOffset: Float, height: Float, width: Float, balconyLevels: List<Float>) {
                                val towerCenterX = centerX + centerOffset * xScale
                                val towerBaseY = baseY + 5f * yScale
                                val towerHeightPx = height * yScale
                                val towerWidthPx = width * xScale
                                val shaftTopY = towerBaseY - towerHeightPx
                                val shaftPath = Path().apply {
                                    moveTo(towerCenterX - towerWidthPx * 0.48f, towerBaseY)
                                    lineTo(towerCenterX - towerWidthPx * 0.36f, shaftTopY + towerWidthPx * 1.1f)
                                    lineTo(towerCenterX + towerWidthPx * 0.36f, shaftTopY + towerWidthPx * 1.1f)
                                    lineTo(towerCenterX + towerWidthPx * 0.48f, towerBaseY)
                                    close()
                                }
                                drawPath(shaftPath, silhouetteColor)
                                drawArchedOpening(centerOffset, -height * 0.28f, width * 0.38f, height * 0.10f)
                                drawArchedOpening(centerOffset, -height * 0.48f, width * 0.34f, height * 0.09f)
                                balconyLevels.forEach { level ->
                                    val balconyY = towerBaseY - towerHeightPx * level
                                    val balconyPath = Path().apply {
                                        moveTo(towerCenterX - towerWidthPx * 0.86f, balconyY - 1.4f * yScale)
                                        lineTo(towerCenterX + towerWidthPx * 0.86f, balconyY - 1.4f * yScale)
                                        lineTo(towerCenterX + towerWidthPx * 0.76f, balconyY + 2.4f * yScale)
                                        lineTo(towerCenterX - towerWidthPx * 0.76f, balconyY + 2.4f * yScale)
                                        close()
                                    }
                                    drawPath(balconyPath, silhouetteColor)
                                }
                                val capBaseY = shaftTopY + towerWidthPx * 1.1f
                                val capHeightPx = towerWidthPx * 2.2f
                                val capPath = Path().apply {
                                    moveTo(towerCenterX - towerWidthPx * 0.62f, capBaseY)
                                    cubicTo(
                                        towerCenterX - towerWidthPx * 0.62f,
                                        capBaseY - capHeightPx * 0.52f,
                                        towerCenterX - towerWidthPx * 0.22f,
                                        capBaseY - capHeightPx * 0.92f,
                                        towerCenterX,
                                        capBaseY - capHeightPx,
                                    )
                                    cubicTo(
                                        towerCenterX + towerWidthPx * 0.22f,
                                        capBaseY - capHeightPx * 0.92f,
                                        towerCenterX + towerWidthPx * 0.62f,
                                        capBaseY - capHeightPx * 0.52f,
                                        towerCenterX + towerWidthPx * 0.62f,
                                        capBaseY,
                                    )
                                    close()
                                }
                                drawPath(capPath, silhouetteColor)
                                drawFinial(
                                    finialCenterX = towerCenterX,
                                    finialBaseY = capBaseY - capHeightPx + 1f * yScale,
                                    finialHeight = 7f * yScale,
                                    finialRadius = 0.9f * xScale,
                                )
                            }

                            fun drawNeedle(centerOffset: Float, baseOffset: Float, height: Float) {
                                val needleCenterX = centerX + centerOffset * xScale
                                val needleBaseY = baseY + baseOffset * yScale
                                val needlePath = Path().apply {
                                    moveTo(needleCenterX - 2.4f * xScale, needleBaseY)
                                    lineTo(needleCenterX, needleBaseY - height * yScale)
                                    lineTo(needleCenterX + 2.4f * xScale, needleBaseY)
                                    close()
                                }
                                drawPath(needlePath, silhouetteColor)
                                drawFinial(needleCenterX, needleBaseY - height * yScale, 4.5f * yScale, 0.6f * xScale)
                            }

                            drawRectangle(-108f, 6f, 108f, 12f)
                            drawRectangle(-100f, -3f, 100f, 7f)
                            drawRectangle(-82f, -16f, -50f, 1f)
                            drawRectangle(-43f, -20f, 43f, 1f)
                            drawRectangle(50f, -16f, 82f, 1f)
                            drawRectangle(-99f, -25f, -87f, 1f)
                            drawRectangle(87f, -25f, 99f, 1f)

                            drawMinaret(-88f, 74f, 8f, listOf(0.35f, 0.66f))
                            drawMinaret(-63f, 58f, 6.5f, listOf(0.50f))
                            drawMinaret(63f, 58f, 6.5f, listOf(0.50f))
                            drawMinaret(88f, 74f, 8f, listOf(0.35f, 0.66f))

                            drawDome(-73f, -16f, 14f, 29f, 7f)
                            drawDome(-35f, -18f, 22f, 45f, 8f)
                            drawDome(0f, -19f, 31f, 62f, 10f)
                            drawDome(35f, -18f, 22f, 45f, 8f)
                            drawDome(73f, -16f, 14f, 29f, 7f)

                            drawNeedle(-18f, -21f, 16f)
                            drawNeedle(18f, -21f, 16f)
                            drawNeedle(-51f, -19f, 12f)
                            drawNeedle(51f, -19f, 12f)
                            listOf(-82f, -62f, -40f, -20f, 0f, 20f, 40f, 62f, 82f).forEach { offset ->
                                drawArchedOpening(offset, 6f, 5.6f, 9f)
                            }
                            listOf(-35f, 0f, 35f).forEach { offset ->
                                drawArchedOpening(offset, -3f, 7.2f, 12f)
                            }
                            drawLine(
                                color = detailColor,
                                start = Offset(centerX - 96f * xScale, baseY - 4f * yScale),
                                end = Offset(centerX + 96f * xScale, baseY - 4f * yScale),
                                strokeWidth = 0.9f * yScale,
                            )
                            drawLine(
                                color = detailColor,
                                start = Offset(centerX - 84f * xScale, baseY - 16f * yScale),
                                end = Offset(centerX + 84f * xScale, baseY - 16f * yScale),
                                strokeWidth = 0.8f * yScale,
                            )
                        }
                        drawRect(color = deepNight.copy(alpha = 0.96f), topLeft = Offset.Zero, size = size)
                        drawPath(
                            path = daylightField,
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFFCFF5FF), skyBlue, Color(0xFF88D5F1)),
                                startY = 0f,
                                endY = sunsetPoint.y + 18f * scaleY,
                            ),
                        )
                        drawHorizonSun(sunriseSunCenter, Color(0xFFFFB84D))
                        drawHorizonSun(sunsetSunCenter, Color(0xFFFF7A3D))
                        drawCloud(24f * scaleX, 54f * scaleY, 0.70f, alpha = 0.24f)
                        drawCloud(size.width - 120f * scaleX, 70f * scaleY, 0.64f, alpha = 0.20f)
                        drawPath(lowerNightField, Color(0xFF12375D))
                        drawNightMosqueSilhouette(
                            centerX = circleCenter.x - circleRadiusPx * 0.96f,
                            baseY = circleBottomPx + 82f * scaleY,
                            silhouetteScale = 0.78f,
                            alpha = 0.18f,
                        )
                        drawNightMosqueSilhouette(
                            centerX = circleCenter.x + circleRadiusPx * 0.96f,
                            baseY = circleBottomPx + 84f * scaleY,
                            silhouetteScale = 0.78f,
                            alpha = 0.17f,
                        )
                        drawNightMosqueSilhouette(
                            centerX = circleCenter.x,
                            baseY = size.height - 16f * scaleY,
                            silhouetteScale = 1.04f,
                            alpha = 0.12f,
                        )
                        listOf(
                            Offset(32f * scaleX, 210f * scaleY) to 1.5f,
                            Offset(82f * scaleX, 258f * scaleY) to 1.0f,
                            Offset(132f * scaleX, 350f * scaleY) to 1.3f,
                            Offset(54f * scaleX, 384f * scaleY) to 1.1f,
                            Offset(118f * scaleX, 416f * scaleY) to 1.2f,
                            Offset(190f * scaleX, 400f * scaleY) to 0.9f,
                            Offset(236f * scaleX, 378f * scaleY) to 1.0f,
                            Offset(size.width - 42f * scaleX, 238f * scaleY) to 1.4f,
                            Offset(size.width - 92f * scaleX, 318f * scaleY) to 1.1f,
                            Offset(size.width - 160f * scaleX, 376f * scaleY) to 1.2f,
                            Offset(size.width - 76f * scaleX, 398f * scaleY) to 0.9f,
                            Offset(size.width - 128f * scaleX, 424f * scaleY) to 1.1f,
                        ).forEach { (star, radius) -> drawStar(star, radius * scaleX, alpha = 0.55f) }
                        drawPath(
                            path = twilightField,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF12375D).copy(alpha = 0.0f),
                                    Color(0xFF3F86A8).copy(alpha = 0.48f),
                                    Color(0xFFBFEFFF).copy(alpha = 0.68f),
                                ),
                                start = fajrBoundaryEnd,
                                end = sunrisePoint,
                            ),
                        )
                        drawLine(
                            color = Color.White.copy(alpha = 0.34f),
                            start = Offset(0f, sunrisePoint.y),
                            end = sunrisePoint,
                            strokeWidth = 1.6f * scaleY,
                        )
                        drawLine(
                            color = Color.White.copy(alpha = 0.34f),
                            start = sunsetPoint,
                            end = sunsetBoundaryEnd,
                            strokeWidth = 1.6f * scaleY,
                        )
                        clipPath(circlePath) {
                            drawRect(
                                brush = Brush.verticalGradient(listOf(Color(0xFF173F68), deepNight, Color(0xFF09223C))),
                                topLeft = Offset(circleLeftPx, circleTopPx),
                                size = Size(circleRadiusPx * 2f, circleRadiusPx * 2f),
                            )
                            val secondNightSector = Path().apply {
                                moveTo(circleRightPx, circleCenter.y)
                                lineTo(circleCenter.x, circleBottomPx)
                                lineTo(nightApex.x, nightApex.y)
                                close()
                            }
                            val thirdNightSector = Path().apply {
                                moveTo(circleCenter.x, circleBottomPx)
                                lineTo(sunrisePoint.x, sunrisePoint.y)
                                lineTo(nightApex.x, nightApex.y)
                                close()
                            }
                            drawPath(secondNightSector, Color(0xFF082A4A).copy(alpha = 0.22f))
                            drawPath(thirdNightSector, Color(0xFF0F5F83).copy(alpha = 0.18f))
                            drawPath(
                                path = twilightPath,
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFBFEFFF).copy(alpha = 0.66f),
                                        Color(0xFF3F86A8).copy(alpha = 0.44f),
                                        Color(0xFF12375D).copy(alpha = 0.0f),
                                    ),
                                    center = sunrisePoint,
                                    radius = circleRadiusPx * 0.82f,
                                ),
                            )
                            drawPath(dayPath, Brush.verticalGradient(listOf(Color(0xFFCFF5FF), skyBlue, Color(0xFF75C9EA))))
                            drawCloud(circleCenter.x - circleRadiusPx * 0.72f, circleTopPx + circleRadiusPx * 0.25f, 0.68f)
                            drawCloud(circleCenter.x + circleRadiusPx * 0.30f, circleTopPx + circleRadiusPx * 0.23f, 0.72f)

                            listOf(sunrisePoint, sunsetPoint).forEach { daylightBoundaryEnd ->
                                drawLine(
                                    color = Color.White.copy(alpha = 0.74f),
                                    start = circleCenter,
                                    end = daylightBoundaryEnd,
                                    strokeWidth = 2.6f * scaleY,
                                )
                            }
                            listOf(secondThirdPoint, finalThirdPoint).forEach { separatorEnd ->
                                drawLine(Color(0xFF071D31).copy(alpha = 0.24f), start = circleCenter, end = separatorEnd, strokeWidth = 1.4f * scaleY)
                                drawLine(GoldLight.copy(alpha = 0.20f), start = circleCenter, end = separatorEnd, strokeWidth = 0.75f * scaleY)
                            }

                            listOf(
                                Offset(circleCenter.x - circleRadiusPx * 0.63f, circleCenter.y + nightHeightPx * 0.33f) to 2.2f,
                                Offset(circleCenter.x - circleRadiusPx * 0.53f, circleCenter.y + nightHeightPx * 0.48f) to 1.6f,
                                Offset(circleCenter.x - circleRadiusPx * 0.40f, circleCenter.y + nightHeightPx * 0.61f) to 1.4f,
                                Offset(circleCenter.x - circleRadiusPx * 0.26f, circleCenter.y + nightHeightPx * 0.44f) to 1.4f,
                                Offset(circleCenter.x + circleRadiusPx * 0.20f, circleCenter.y + nightHeightPx * 0.50f) to 1.3f,
                                Offset(circleCenter.x + circleRadiusPx * 0.42f, circleCenter.y + nightHeightPx * 0.34f) to 1.8f,
                                Offset(circleCenter.x + circleRadiusPx * 0.58f, circleCenter.y + nightHeightPx * 0.55f) to 1.5f,
                                Offset(circleCenter.x + circleRadiusPx * 0.70f, circleCenter.y + nightHeightPx * 0.42f) to 1.2f,
                            ).forEach { (star, radius) -> drawStar(star, radius * scaleX) }

                            val mosqueBaseY = circleCenter.y + nightHeightPx * 0.78f
                            drawNightMosqueSilhouette(
                                centerX = circleCenter.x,
                                baseY = mosqueBaseY,
                                silhouetteScale = 0.64f,
                                alpha = 0.62f,
                                color = Color(0xFF05192B),
                            )

                        }
                        drawCrescent(midnightMoonCenter)
                        drawHorizonSun(noonSunCenter, Color(0xFFFFB84D))
                        drawCircle(
                            color = Color.White.copy(alpha = 0.76f),
                            radius = circleRadiusPx,
                            center = circleCenter,
                            style = Stroke(width = 3f * scaleX),
                        )
                        drawCircle(
                            color = Color(0xFF6F8491).copy(alpha = 0.52f),
                            radius = circleRadiusPx + 2f * scaleX,
                            center = circleCenter,
                            style = Stroke(width = 1.4f * scaleX),
                        )
                        if (data.showCurrentTime) {
                            drawArc(
                                color = Color(0xFFFFC247),
                                startAngle = activeSegmentStartAngle,
                                sweepAngle = clockwiseSweepDegrees(activeSegmentStartAngle, activeSegmentEndAngle),
                                useCenter = false,
                                topLeft = Offset(circleLeftPx, circleTopPx),
                                size = Size(circleRadiusPx * 2f, circleRadiusPx * 2f),
                                style = Stroke(width = 3f * scaleX),
                            )
                        }
                        timelineAlarmPlacements.forEach { placement ->
                            val marker = Offset(placement.iconCenterX * scaleX, placement.iconCenterY * scaleY)
                            val labelCenter = Offset(placement.labelCenterX * scaleX, placement.labelCenterY * scaleY)
                            val dx = labelCenter.x - marker.x
                            val dy = labelCenter.y - marker.y
                            val distance = sqrt(dx * dx + dy * dy)
                            if (distance > 19f * scaleX) {
                                val unitX = dx / distance
                                val unitY = dy / distance
                                drawLine(
                                    color = Color(0xFFFFC247).copy(alpha = if (placement.nearNonAlarmMarker) 0.72f else 0.46f),
                                    start = Offset(
                                        x = marker.x + unitX * 7f * scaleX,
                                        y = marker.y + unitY * 7f * scaleY,
                                    ),
                                    end = Offset(
                                        x = labelCenter.x - unitX * 10f * scaleX,
                                        y = labelCenter.y - unitY * 10f * scaleY,
                                    ),
                                    strokeWidth = 1.15f * scaleX,
                                )
                            }
                        }
                        fun drawTimelineMarker(center: Offset) {
                            drawCircle(Color.White.copy(alpha = 0.96f), radius = 5.2f * scaleX, center = center)
                            drawCircle(Color(0xFF173F68), radius = 2.4f * scaleX, center = center)
                        }
                        fun drawPrayerTimelineMarker(center: Offset) {
                            drawCircle(Color.White.copy(alpha = 0.96f), radius = 5.0f * scaleX, center = center)
                            drawCircle(GreenPrimaryDark.copy(alpha = 0.94f), radius = 3.0f * scaleX, center = center)
                            drawCircle(GoldLight.copy(alpha = 0.72f), radius = 1.05f * scaleX, center = center)
                        }
                        fun drawAlarmTimelineMarker(center: Offset, cluster: Boolean, sub: Boolean, nearNonAlarmMarker: Boolean) {
                            val amber = Color(0xFFFFC247).copy(alpha = if (sub && !cluster) 0.9f else 1f)
                            if (cluster) {
                                drawCircle(amber.copy(alpha = 0.52f), radius = 6.9f * scaleX, center = center, style = Stroke(width = 1.15f * scaleX))
                                drawCircle(Color.White.copy(alpha = 0.96f), radius = 5.4f * scaleX, center = center)
                                drawCircle(amber, radius = 3.0f * scaleX, center = center)
                                drawCircle(Color(0xFF061D32), radius = 1.1f * scaleX, center = center)
                            } else {
                                if (nearNonAlarmMarker) {
                                    drawCircle(amber.copy(alpha = 0.68f), radius = 6.9f * scaleX, center = center, style = Stroke(width = 1.1f * scaleX))
                                }
                                drawCircle(Color.White.copy(alpha = 0.96f), radius = 5.2f * scaleX, center = center)
                                drawCircle(amber, radius = 2.8f * scaleX, center = center)
                            }
                        }
                        // Alarm dots are drawn BEFORE non-alarm markers so prayer/event dots stay on top (req 2).
                        timelineAlarmPlacements.forEach { placement ->
                            drawAlarmTimelineMarker(
                                center = Offset(placement.iconCenterX * scaleX, placement.iconCenterY * scaleY),
                                cluster = placement.isCluster,
                                sub = placement.hasSubAlarm,
                                nearNonAlarmMarker = placement.nearNonAlarmMarker,
                            )
                        }
                        val timelineEventPoints = listOf(sunrisePoint, sunsetPoint, secondThirdPoint, finalThirdPoint, fajrPoint)
                        timelineEventPoints.forEach(::drawTimelineMarker)
                        timelinePrayerAngles.forEach { angleDegrees ->
                            val radians = angleDegrees * PI / 180.0
                            drawPrayerTimelineMarker(
                                Offset(
                                    x = (circleCenterX + circleRadius * cos(radians).toFloat()) * scaleX,
                                    y = (circleCenterY + circleRadius * sin(radians).toFloat()) * scaleY,
                                ),
                            )
                        }
                        if (data.showCurrentTime && data.sunAltitudeDeg != null) {
                            val radians = minuteToTimelineAngleDegrees(data.currentMinuteOfDay) * PI / 180.0
                            val sunCenter = Offset(
                                x = (circleCenterX + circleRadius * cos(radians).toFloat()) * scaleX,
                                y = (circleCenterY + circleRadius * sin(radians).toFloat()) * scaleY,
                            )
                            repeat(8) { ray ->
                                val rayRadians = ray * PI / 4.0
                                val unit = Offset(cos(rayRadians).toFloat(), sin(rayRadians).toFloat())
                                drawLine(
                                    color = Color(0xFFFFC247),
                                    start = sunCenter + unit * (6.5f * scaleX),
                                    end = sunCenter + unit * (8.5f * scaleX),
                                    strokeWidth = 1.3f * scaleX,
                                )
                            }
                            drawCircle(Color.White, radius = 5.2f * scaleX, center = sunCenter)
                            drawCircle(Color(0xFFFFC247), radius = 3.5f * scaleX, center = sunCenter)
                        }
                    }

                    timelineAlarmPlacements.forEach { placement ->
                        key(placement.id) {
                            val accessibilityDescription = timelineAlarmAccessibilityDescription(placement)
                            val actionLabel = timelineAlarmActionLabel(placement)
                            // Transparent hit target over the alarm dot (the dot itself is drawn in the
                            // Canvas below the non-alarm markers). Single → edit directly; cluster → sheet.
                            val touchSize = 30f
                            Box(
                                modifier = Modifier
                                    .offset(x = (placement.iconCenterX - touchSize / 2f).dp, y = (placement.iconCenterY - touchSize / 2f).dp)
                                    .size(touchSize.dp)
                                    .testTag("prayer_original_alarm")
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button, onClickLabel = actionLabel) {
                                        if (placement.isCluster) {
                                            selectedAlarmCluster = placement.alarms
                                        } else {
                                            onEditWakeAlarm(placement.alarms.first().wakeAlarmId)
                                        }
                                    }
                                    .semantics { contentDescription = accessibilityDescription },
                            )
                        }
                    }

                    if (data.showCurrentTime) data.sunAltitudeDeg?.let { altitude ->
                        val formattedAngle = String.format(Locale.US, "%.1f°", if (abs(altitude) < 0.05) 0.0 else altitude)
                        Text(
                            text = "${stringResource(R.string.prayer_day_overview_sun_angle)}  \u2066$formattedAngle\u2069",
                            modifier = Modifier.offset(x = 12.dp, y = 14.dp).width((panelWidth - 24f).dp)
                                .testTag("prayer_day_sun_angle"),
                            color = Color(0xFF22364F),
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            style = TextStyle(textDirection = TextDirection.Rtl),
                        )
                    }

                    Text(
                        text = "النهار",
                        modifier = Modifier.offset(x = (panelWidth / 2f - 47f).dp, y = dayLabelY.dp).width(94.dp),
                        fontSize = 24.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF22364F),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Text(
                        text = "الليل",
                        modifier = Modifier.offset(x = (panelWidth / 2f - 61f).dp, y = nightLabelY.dp).width(122.dp),
                        fontSize = 24.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Text(
                        text = "الشفق",
                        modifier = Modifier.offset(x = twilightLabelX.dp, y = twilightLabelY.dp).width(56.dp),
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.88f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    DayNightHorizonTime(
                        label = "الشروق",
                        time = data.sunriseTime,
                        dark = false,
                        modifier = Modifier.offset(x = sunriseLabelX.dp, y = sunriseLabelY.dp).width(sunriseLabelWidth.dp),
                    )
                    DayNightHorizonTime(
                        label = "المغرب",
                        time = data.timelinePrayers.firstOrNull { it.prayer == Prayer.MAGHRIB }?.time ?: data.thirds[0].startTime,
                        dark = false,
                        modifier = Modifier.offset(x = maghribLabelX.dp, y = maghribLabelY.dp).width(maghribLabelWidth.dp),
                    )
                    DayNightHorizonTime(
                        label = "الفجر",
                        time = data.timelinePrayers.firstOrNull { it.prayer == Prayer.FAJR }?.time ?: data.thirds[2].endTime,
                        dark = false,
                        modifier = Modifier.offset(x = fajrLabelX.dp, y = fajrLabelY.dp).width(fajrLabelWidth.dp),
                    )
                    DayNightHorizonTime(
                        label = "الثلث الأخير",
                        time = data.thirds[2].startTime,
                        dark = false,
                        modifier = Modifier.offset(x = finalThirdLabelX.dp, y = finalThirdLabelY.dp).width(thirdLabelWidth.dp),
                    )
                    DayNightHorizonTime(
                        label = "الثلث الثاني",
                        time = data.thirds[1].startTime,
                        dark = false,
                        modifier = Modifier.offset(x = secondThirdLabelX.dp, y = secondThirdLabelY.dp).width(thirdLabelWidth.dp),
                    )
                    timelineAlarmPlacements.forEach { placement ->
                        key("label_${placement.id}") {
                            val accessibilityDescription = timelineAlarmAccessibilityDescription(placement)
                            val actionLabel = timelineAlarmActionLabel(placement)
                            val labelModifier = Modifier
                                .offset(x = placement.labelX.dp, y = placement.labelY.dp)
                                .width(placement.labelWidth.dp)
                                .then(
                                    if (placement.isCluster) {
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable(role = Role.Button, onClickLabel = actionLabel) { selectedAlarmCluster = placement.alarms }
                                    } else {
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable(role = Role.Button, onClickLabel = actionLabel) { onEditWakeAlarm(placement.alarms.first().wakeAlarmId) }
                                    },
                                )
                                .semantics { contentDescription = accessibilityDescription }
                            DayNightHorizonAlarmTime(
                                time = placement.time,
                                modifier = labelModifier,
                            )
                        }
                    }
                    timelinePrayerLabels.forEach { (prayer, labelX, labelY) ->
                        key(prayer.prayer.name) {
                            DayNightHorizonPrayerTime(
                                label = prayer.title,
                                time = prayer.time,
                                dark = prayer.prayer == Prayer.DHUHR || prayer.prayer == Prayer.JOMOAA || prayer.prayer == Prayer.ASR,
                                modifier = Modifier.offset(x = labelX.dp, y = labelY.dp).width(timelinePrayerLabelWidth.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun timelineAlarmAccessibilityDescription(placement: NightTimelineAlarmPlacement): String {
    val mainAlarm = stringResource(R.string.prayer_day_overview_main_alarm)
    val extraAlarm = stringResource(R.string.prayer_day_overview_extra_alarm)
    val alarms = placement.alarms.joinToString("؛ ") { alarm ->
        "${if (alarm.isSubAlarm) extraAlarm else mainAlarm}، ${alarm.title}، ${alarm.time}"
    }
    return if (placement.isCluster) {
        "${stringResource(R.string.prayer_day_overview_alarm_count, placement.alarms.size)}. $alarms"
    } else alarms
}

@Composable
private fun timelineAlarmActionLabel(placement: NightTimelineAlarmPlacement): String? =
    if (placement.isCluster) null else stringResource(
        R.string.prayer_day_overview_edit_named_alarm,
        placement.alarms.first().title,
        placement.alarms.first().time,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineAlarmClusterSheet(
    alarms: List<NightTimelineAlarm>,
    onDismiss: () -> Unit,
    onEditAlarm: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sortedAlarms = remember(alarms) { alarms.sortedBy { alarm -> alarm.minuteOfDay } }

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (sortedAlarms.size == 1) "منبّه على الخط الزمني" else "${sortedAlarms.size} منبّهات متقاربة",
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor,
            )
            sortedAlarms.forEach { alarm ->
                TimelineAlarmClusterRow(
                    alarm = alarm,
                    onEditAlarm = onEditAlarm,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TimelineAlarmClusterRow(
    alarm: NightTimelineAlarm,
    onEditAlarm: (String) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable { onEditAlarm(alarm.wakeAlarmId) }
            .background(if (alarm.isSubAlarm) GoldLight.copy(alpha = 0.12f) else GreenPrimary.copy(alpha = 0.08f))
            .border(BorderStroke(1.dp, if (alarm.isSubAlarm) Gold.copy(alpha = 0.22f) else GreenPrimary.copy(alpha = 0.24f)), shape)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = alarm.time,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Black,
                color = PrayerNameColor,
                maxLines = 1,
            )
            Text(
                text = alarm.title,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                maxLines = 1,
            )
        }
        TextButton(onClick = { onEditAlarm(alarm.wakeAlarmId) }) {
            Text(text = "تعديل")
        }
    }
}

@Composable
private fun DayNightHorizonTime(
    label: String,
    time: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (dark) Color(0xFF22364F) else Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = time,
            fontSize = 12.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Black,
            color = if (dark) Color(0xFF22364F) else Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun DayNightHorizonAlarmTime(
    time: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = time,
            fontSize = 10.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Black,
            color = Color(0xFFFFD479),
            textAlign = TextAlign.Center,
            maxLines = 1,
            style = TextStyle(
                shadow = Shadow(
                    color = Color(0xFF061D32).copy(alpha = 0.72f),
                    offset = Offset(0f, 0.8f),
                    blurRadius = 1.8f,
                ),
            ),
        )
    }
}

@Composable
private fun DayNightHorizonPrayerTime(
    label: String,
    time: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = label,
            fontSize = 8.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (dark) Color(0xFF173F68) else Color.White.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = time,
            modifier = Modifier.offset(y = (-2).dp),
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Black,
            color = if (dark) Color(0xFF173F68) else Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private fun normalizedMinuteOfDay(minuteOfDay: Int): Int =
    ((minuteOfDay % (24 * 60)) + (24 * 60)) % (24 * 60)

private fun minutesBetweenInDay(startMinuteOfDay: Int, endMinuteOfDay: Int): Int {
    val totalMinutes = 24 * 60
    return (normalizedMinuteOfDay(endMinuteOfDay) - normalizedMinuteOfDay(startMinuteOfDay) + totalMinutes) % totalMinutes
}
