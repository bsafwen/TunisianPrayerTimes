package com.tunisianprayertimes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.KAABA_LATITUDE
import com.tunisianprayertimes.KAABA_LONGITUDE
import com.tunisianprayertimes.QiblaMethod
import com.tunisianprayertimes.R
import com.tunisianprayertimes.shortestSignedAngleDegrees
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextMuted
import net.sf.geographiclib.Geodesic
import net.sf.geographiclib.GeodesicMask
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

private const val ROUTE_SEGMENTS = 48
// Mercator's northing grows without bound at the poles, so the sketch stops short of them.
private const val MAP_LATITUDE_LIMIT = 85.0
// Keeps a short trip, next to Mecca, from being blown up into a meaningless scale.
private const val MAP_MIN_SPAN_RADIANS = 0.2

internal val QiblaGreatCircleColor = GreenPrimary
internal val QiblaRhumbLineColor = Color(0xFFB8860B)
private val IllustrationBackground = Color(0xFFEFF5F1)
private val GlobeFill = Color(0xFFDCEBE3)
private val GraticuleColor = Color.White
private val KaabaBlack = Color(0xFF201B18)
private val KaabaGold = Color(0xFFD4AF37)

/** A point along a route, in degrees; longitudes carry on from the start without wrapping. */
internal data class RoutePoint(val latitude: Double, val longitude: Double)

/** A point on a flat drawing, before it is scaled to the screen. */
internal data class PlanePoint(val x: Double, val y: Double)

internal data class QiblaRoutes(
    val greatCircle: List<RoutePoint>,
    val rhumbLine: List<RoutePoint>,
    /** Halfway along the great circle; the globe is drawn as seen from above it. */
    val midpoint: RoutePoint,
)

/** Both ways to the Kaaba from one place, sampled for drawing. */
internal fun qiblaRoutes(latitude: Double, longitude: Double, segments: Int = ROUTE_SEGMENTS): QiblaRoutes {
    val line = Geodesic.WGS84.InverseLine(
        latitude,
        longitude,
        KAABA_LATITUDE,
        KAABA_LONGITUDE,
        GeodesicMask.DISTANCE_IN or GeodesicMask.LATITUDE or GeodesicMask.LONGITUDE,
    )

    fun greatCirclePoint(fraction: Double): RoutePoint {
        val position = line.Position(
            line.Distance() * fraction,
            GeodesicMask.LATITUDE or GeodesicMask.LONGITUDE or GeodesicMask.LONG_UNROLL,
        )
        return RoutePoint(position.lat2, position.lon2)
    }

    // The rhumb line is, by definition, straight on a Mercator map.
    val longitudeSpan = shortestSignedAngleDegrees(longitude, KAABA_LONGITUDE)
    val startNorthing = mercatorNorthing(latitude)
    val endNorthing = mercatorNorthing(KAABA_LATITUDE)
    val rhumbLine = (0..segments).map { step ->
        val fraction = step.toDouble() / segments
        RoutePoint(
            latitude = latitudeAtMercatorNorthing(startNorthing + (endNorthing - startNorthing) * fraction),
            longitude = longitude + longitudeSpan * fraction,
        )
    }

    return QiblaRoutes(
        greatCircle = (0..segments).map { step -> greatCirclePoint(step.toDouble() / segments) },
        rhumbLine = rhumbLine,
        midpoint = greatCirclePoint(0.5),
    )
}

/** Spherical Mercator, in radians: close enough for a sketch, and it keeps the rhumb line straight. */
internal fun mercatorProjection(point: RoutePoint): PlanePoint {
    return PlanePoint(x = Math.toRadians(point.longitude), y = mercatorNorthing(point.latitude))
}

/** The globe seen from far above [center]: x east, y north, in globe radii; null on the far side. */
internal fun orthographicProjection(point: RoutePoint, center: RoutePoint): PlanePoint? {
    val latitude = Math.toRadians(point.latitude)
    val longitudeOffset = Math.toRadians(point.longitude - center.longitude)
    val centerLatitude = Math.toRadians(center.latitude)
    val facing = sin(centerLatitude) * sin(latitude) + cos(centerLatitude) * cos(latitude) * cos(longitudeOffset)
    if (facing < 0.0) return null
    return PlanePoint(
        x = cos(latitude) * sin(longitudeOffset),
        y = cos(centerLatitude) * sin(latitude) - sin(centerLatitude) * cos(latitude) * cos(longitudeOffset),
    )
}

private fun mercatorNorthing(latitudeDegrees: Double): Double {
    val latitude = latitudeDegrees.coerceIn(-MAP_LATITUDE_LIMIT, MAP_LATITUDE_LIMIT)
    return asinh(tan(Math.toRadians(latitude)))
}

private fun latitudeAtMercatorNorthing(northing: Double): Double = Math.toDegrees(atan(sinh(northing)))

/**
 * The two routes side by side: on the globe the great circle runs straight and the rhumb
 * line bends; on a Mercator map it is the other way round.
 */
@Composable
internal fun QiblaRoutesIllustration(
    routes: QiblaRoutes,
    selected: QiblaMethod,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.qibla_illustration_description)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QiblaRoutePanel(
            label = stringResource(R.string.qibla_illustration_globe),
            modifier = Modifier.weight(1f),
        ) {
            drawGlobe(routes, selected)
        }
        QiblaRoutePanel(
            label = stringResource(R.string.qibla_illustration_map),
            modifier = Modifier.weight(1f),
        ) {
            drawMercatorMap(routes, selected)
        }
    }
}

@Composable
private fun QiblaRoutePanel(
    label: String,
    modifier: Modifier = Modifier,
    draw: DrawScope.() -> Unit,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(IllustrationBackground),
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(128.dp), onDraw = draw)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

private fun DrawScope.drawGlobe(routes: QiblaRoutes, selected: QiblaMethod) {
    val radius = size.minDimension / 2f - 10.dp.toPx()
    val globeCenter = center
    fun toScreen(point: PlanePoint) = Offset(
        x = globeCenter.x + point.x.toFloat() * radius,
        y = globeCenter.y - point.y.toFloat() * radius,
    )
    fun project(point: RoutePoint) = orthographicProjection(point, routes.midpoint)?.let(::toScreen)

    drawCircle(color = GlobeFill, radius = radius, center = globeCenter)
    val graticule = Stroke(width = 1.dp.toPx())
    for (meridian in -180 until 180 step 30) {
        drawPolyline((-90..90 step 3).map { latitude -> project(RoutePoint(latitude.toDouble(), meridian.toDouble())) }, GraticuleColor, graticule)
    }
    for (parallel in -60..60 step 30) {
        drawPolyline((-180..180 step 3).map { longitude -> project(RoutePoint(parallel.toDouble(), longitude.toDouble())) }, GraticuleColor, graticule)
    }
    drawCircle(
        color = GreenPrimaryDark.copy(alpha = 0.18f),
        radius = radius,
        center = globeCenter,
        style = Stroke(width = 1.dp.toPx()),
    )

    drawRoutes(routes, selected) { point -> project(point) }
}

private fun DrawScope.drawMercatorMap(routes: QiblaRoutes, selected: QiblaMethod) {
    val planePoints = (routes.greatCircle + routes.rhumbLine).map(::mercatorProjection)
    var minX = planePoints.minOf { point -> point.x }
    var maxX = planePoints.maxOf { point -> point.x }
    var minY = planePoints.minOf { point -> point.y }
    var maxY = planePoints.maxOf { point -> point.y }
    if (maxX - minX < MAP_MIN_SPAN_RADIANS) {
        val middle = (minX + maxX) / 2.0
        minX = middle - MAP_MIN_SPAN_RADIANS / 2.0
        maxX = middle + MAP_MIN_SPAN_RADIANS / 2.0
    }
    if (maxY - minY < MAP_MIN_SPAN_RADIANS) {
        val middle = (minY + maxY) / 2.0
        minY = middle - MAP_MIN_SPAN_RADIANS / 2.0
        maxY = middle + MAP_MIN_SPAN_RADIANS / 2.0
    }
    val padding = 16.dp.toPx()
    // One scale for both axes, or the map would stop being conformal and the rhumb line would tilt.
    val scale = min((size.width - 2 * padding) / (maxX - minX), (size.height - 2 * padding) / (maxY - minY))
    val middleX = (minX + maxX) / 2.0
    val middleY = (minY + maxY) / 2.0
    fun toScreen(point: PlanePoint) = Offset(
        x = (size.width / 2.0 + (point.x - middleX) * scale).toFloat(),
        y = (size.height / 2.0 - (point.y - middleY) * scale).toFloat(),
    )
    fun project(point: RoutePoint) = toScreen(mercatorProjection(point))

    val visibleWest = Math.toDegrees(middleX - size.width / 2.0 / scale)
    val visibleEast = Math.toDegrees(middleX + size.width / 2.0 / scale)
    val step = when {
        visibleEast - visibleWest <= 45.0 -> 10.0
        visibleEast - visibleWest <= 150.0 -> 30.0
        else -> 60.0
    }
    val graticule = Stroke(width = 1.dp.toPx())
    var meridian = floor(visibleWest / step) * step
    while (meridian <= visibleEast) {
        val x = project(RoutePoint(0.0, meridian)).x
        drawLine(GraticuleColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = graticule.width)
        meridian += step
    }
    var parallel = -80.0
    while (parallel <= 80.0) {
        val y = project(RoutePoint(parallel, middleX)).y
        if (y in 0f..size.height) {
            drawLine(GraticuleColor, Offset(0f, y), Offset(size.width, y), strokeWidth = graticule.width)
        }
        parallel += step
    }

    drawRoutes(routes, selected) { point -> project(point) }
}

private fun DrawScope.drawRoutes(
    routes: QiblaRoutes,
    selected: QiblaMethod,
    project: (RoutePoint) -> Offset?,
) {
    val routesBackToFront = listOf(
        QiblaMethod.GreatCircle to routes.greatCircle,
        QiblaMethod.RhumbLine to routes.rhumbLine,
    ).sortedBy { (method, _) -> method == selected }
    for ((method, points) in routesBackToFront) {
        val isSelected = method == selected
        val color = if (method == QiblaMethod.GreatCircle) QiblaGreatCircleColor else QiblaRhumbLineColor
        drawPolyline(
            points = points.map(project),
            color = color.copy(alpha = if (isSelected) 1f else 0.5f),
            stroke = Stroke(
                width = if (isSelected) 3.dp.toPx() else 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }

    project(routes.greatCircle.first())?.let { start ->
        drawCircle(color = Color.White, radius = 4.5.dp.toPx(), center = start)
        drawCircle(color = GreenPrimaryDark, radius = 4.5.dp.toPx(), center = start, style = Stroke(width = 2.dp.toPx()))
    }
    project(routes.greatCircle.last())?.let { kaaba -> drawKaaba(kaaba) }
}

private fun DrawScope.drawKaaba(center: Offset) {
    val side = 9.dp.toPx()
    val topLeft = Offset(center.x - side / 2f, center.y - side / 2f)
    drawRect(color = Color.White, topLeft = topLeft - Offset(1.dp.toPx(), 1.dp.toPx()), size = Size(side + 2.dp.toPx(), side + 2.dp.toPx()))
    drawRect(color = KaabaBlack, topLeft = topLeft, size = Size(side, side))
    drawRect(
        color = KaabaGold,
        topLeft = Offset(topLeft.x, topLeft.y + side * 0.28f),
        size = Size(side, side * 0.18f),
    )
}

/** Draws [points] as one line, lifting the pen wherever a point is out of view. */
private fun DrawScope.drawPolyline(points: List<Offset?>, color: Color, stroke: Stroke) {
    val path = Path()
    var penDown = false
    var previous: Offset? = null
    for (point in points) {
        // A jump across most of the drawing means the line wrapped behind the globe's edge.
        val jumped = point != null && previous != null &&
            max(abs(point.x - previous.x), abs(point.y - previous.y)) > size.maxDimension / 2f
        when {
            point == null -> penDown = false
            !penDown || jumped -> {
                path.moveTo(point.x, point.y)
                penDown = true
            }
            else -> path.lineTo(point.x, point.y)
        }
        previous = point
    }
    drawPath(path = path, color = color, style = stroke)
}
