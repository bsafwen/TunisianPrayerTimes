package com.tunisianprayertimes.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import com.tunisianprayertimes.DayPrayerTimes
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** The sky's two colours at a moment: at the top of the screen, and at the horizon above the timetable. */
@Immutable
data class SkyColors(val zenith: Color, val horizon: Color) {
    /** Toward the ground by [amount] (0..1): the iqamah screen dims the sky, the prayer is waited for. */
    fun dimmed(amount: Float): SkyColors =
        SkyColors(lerp(zenith, Midad.Ground, amount), lerp(horizon, Midad.Ground, amount))
}

/**
 * The seven skies of a day, after the real one over the mosque: deep at night, violet at Fajr, the rose
 * band at sunrise, slate blue by day, dust at Asr, ember at Maghrib, indigo after it. Every one keeps
 * the ivory text above 7.5:1, even at the horizon.
 */
enum class SkyPhase(zenith: Long, horizon: Long) {
    NIGHT(0xFF06081A, 0xFF0D1230),
    FAJR(0xFF0A0E2A, 0xFF2B2A55),
    SUNRISE(0xFF13204A, 0xFF4F3F58),
    DAY(0xFF0F2444, 0xFF2A4A6B),
    ASR(0xFF172640, 0xFF4A3F36),
    MAGHRIB(0xFF171634, 0xFF5A3438),
    ISHA(0xFF0B0F26, 0xFF1F1D40);

    val colors = SkyColors(Color(zenith), Color(horizon))
}

/**
 * The sky at any moment, from the day's prayer times alone: no network, nothing to set. A change of
 * phase is spread over [BLEND_MINUTES] around its moment, in Oklab, so nobody ever sees it happen.
 */
object Sky {
    const val BLEND_MINUTES = 30L

    /** When each phase begins on [date], in order; the night before Fajr and after Isha is [SkyPhase.NIGHT]. */
    fun timeline(date: LocalDate, times: DayPrayerTimes): List<Pair<LocalDateTime, SkyPhase>> {
        val sunrise = date.atTime(times.shurukHour, times.shurukMinute)
        val maghrib = date.atTime(times.maghrib.hour, times.maghrib.minute)
        return listOf(
            date.atTime(times.fajr.hour, times.fajr.minute) to SkyPhase.FAJR,
            sunrise.minusMinutes(25) to SkyPhase.SUNRISE,
            sunrise.plusMinutes(20) to SkyPhase.DAY,
            date.atTime(times.asr.hour, times.asr.minute) to SkyPhase.ASR,
            maghrib.minusMinutes(30) to SkyPhase.MAGHRIB,
            maghrib.plusMinutes(25) to SkyPhase.ISHA,
            date.atTime(times.isha.hour, times.isha.minute).plusMinutes(30) to SkyPhase.NIGHT,
        ).sortedBy { it.first }
    }

    /** The sky at [now] under [times] (today's); a plain day sky while the times are unknown. */
    fun at(now: LocalDateTime, times: DayPrayerTimes?): SkyColors {
        if (times == null) return SkyPhase.DAY.colors
        val line = timeline(now.toLocalDate(), times)
        line.forEachIndexed { index, (start, phase) ->
            val before = line.getOrNull(index - 1)
            val after = line.getOrNull(index + 1)
            // Never more than a third of the phases on either side, so a short one is still seen whole.
            val half = listOfNotNull(
                Duration.ofMinutes(BLEND_MINUTES).dividedBy(2),
                before?.let { Duration.between(it.first, start).dividedBy(3) },
                after?.let { Duration.between(start, it.first).dividedBy(3) },
            ).min()
            val from = start.minus(half)
            val to = start.plus(half)
            if (!now.isBefore(from) && now.isBefore(to)) {
                val fraction = Duration.between(from, now).toMillis().toFloat() / Duration.between(from, to).toMillis()
                return blend((before?.second ?: SkyPhase.NIGHT).colors, phase.colors, fraction)
            }
        }
        return (line.lastOrNull { !it.first.isAfter(now) }?.second ?: SkyPhase.NIGHT).colors
    }

    fun blend(from: SkyColors, to: SkyColors, fraction: Float): SkyColors {
        val f = fraction.coerceIn(0f, 1f)
        return SkyColors(lerp(from.zenith, to.zenith, f), lerp(from.horizon, to.horizon, f))
    }
}

/**
 * The sky behind a screen: [sky]'s zenith at the top, its horizon at [horizon] from the top, then a
 * soft fade into the ground, reached at [groundAt]. With no sky (the «مداد» theme) the ground alone.
 */
fun Modifier.skyBackground(sky: SkyColors?, horizon: Dp, groundAt: Dp): Modifier = drawWithCache {
    val brush = if (sky == null) null else {
        val end = groundAt.toPx().coerceAtLeast(1f)
        val h = (horizon.toPx() / end).coerceIn(0f, 1f)
        Brush.verticalGradient(
            0f to sky.zenith,
            h to sky.horizon,
            (h + (1f - h) * 0.6f) to lerp(sky.horizon, Midad.Ground, 0.72f),
            1f to Midad.Ground,
            startY = 0f,
            endY = end,
        )
    }
    onDrawBehind {
        drawRect(Midad.Ground)
        if (brush != null) drawRect(brush)
    }
}
