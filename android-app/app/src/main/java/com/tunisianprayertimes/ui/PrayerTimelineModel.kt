package com.tunisianprayertimes.ui

import android.content.Context
import com.tunisianprayertimes.DelayMode
import com.tunisianprayertimes.EidSilenceWindows
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.SilenceMode
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.silence.SilenceEnd
import com.tunisianprayertimes.silence.SilenceEndpoints
import com.tunisianprayertimes.silence.SilenceEnds
import com.tunisianprayertimes.silence.SilenceFit
import com.tunisianprayertimes.silence.SilenceRule
import com.tunisianprayertimes.silence.SilenceWindowPlanner
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs

/** The two editable endpoints of a prayer's silence window. */
internal enum class SilenceEndpoint { START, END }

/** How one endpoint is scheduled. */
internal enum class EndpointRuleMode { ADHAN, FIXED_TIME }

/**
 * A prayer's adhan as the silence editors see it (plan 6.9, D8). The timeline's axis is the phone's clock on the
 * prayer day, in minutes from its midnight: below 0 the evening before, from 1,440 the day after. Clock settings
 * are stored in the place's zone ([placeZone]) and shown on the axis; offsets from the adhan are real minutes, as
 * the planner reads them. [savedEnd] is the stored fixed end with the adhan offset it was saved at (D16), which a
 * draft keeping that clock still resolves with.
 */
internal data class PrayerTimelineAnchor(
    val prayer: Prayer,
    val prayerDay: LocalDate,
    val adhan: Instant,
    val placeZone: ZoneId,
    val inm: Boolean,
    val savedEnd: SilenceEnd.Clock? = null,
    val displayZone: ZoneId = ZoneId.systemDefault(),
    /** A rule, a bound or the order guard set the adhan («تقديري», 6.2), and which one. */
    val estimated: Boolean = false,
    val rule: com.tunisianprayertimes.engine.RuleName? = null,
) {
    /** The adhan on the axis. */
    val minutes: Int get() = axisOf(adhan)

    /** An instant's minute on the axis: its wall clock in the phone's zone, counted from the prayer day. */
    fun axisOf(at: Instant): Int {
        val local = at.atZone(displayZone)
        return ((local.toLocalDate().toEpochDay() - prayerDay.toEpochDay()) * MINUTES_PER_DAY + local.hour * 60 + local.minute).toInt()
    }

    /** The instant of an axis minute (in a spring gap, later by the gap). */
    fun instantAt(axisMinutes: Int): Instant {
        val clock = Math.floorMod(axisMinutes, MINUTES_PER_DAY)
        val day = prayerDay.plusDays(Math.floorDiv(axisMinutes, MINUTES_PER_DAY).toLong())
        return ZonedDateTime.of(day, LocalTime.of(clock / 60, clock % 60), displayZone).toInstant()
    }

    /** Real minutes from the adhan to an axis minute. */
    fun offsetAt(axisMinutes: Int): Int = Math.floorDiv(instantAt(axisMinutes).epochSecond - adhan.epochSecond, 60L).toInt()

    /** The axis minute [minutes] real minutes from the adhan. */
    fun axisAtOffset(minutes: Int): Int = axisOf(adhan.plusSeconds(minutes * 60L))

    /** The place's clock at an axis minute: what a fixed endpoint stores. */
    fun placeClockAt(axisMinutes: Int): LocalTime =
        instantAt(axisMinutes).atZone(placeZone).toLocalTime().withSecond(0).withNano(0)

    /** A clock time shown on the axis, as a start: its occurrence nearest the adhan (D16). */
    fun startAxisFor(clockMinutes: Int): Int {
        val clock = Math.floorMod(clockMinutes, MINUTES_PER_DAY)
        return (-1..1).map { clock + it * MINUTES_PER_DAY }
            .minWith(compareBy<Int>({ abs(it - minutes) }, { if (it >= minutes) 0 else 1 }))
    }

    /** A clock time shown on the axis, as an end: its first occurrence after [startAxis]. */
    fun endAxisFor(clockMinutes: Int, startAxis: Int): Int {
        var axis = Math.floorDiv(startAxis, MINUTES_PER_DAY) * MINUTES_PER_DAY + Math.floorMod(clockMinutes, MINUTES_PER_DAY)
        while (axis <= startAxis) axis += MINUTES_PER_DAY
        return axis
    }

    companion object {
        /** A fixed sample prayer for the onboarding's demonstration, on today's date in the phone's zone. */
        fun demo(prayer: Prayer, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): PrayerTimelineAnchor {
            val today = LocalDate.now(zone)
            return PrayerTimelineAnchor(prayer, today, ZonedDateTime.of(today, LocalTime.of(hour, minute), zone).toInstant(), zone, inm = false, displayZone = zone)
        }
    }
}

/** Minutes on an anchor's axis, including previous/next-day endpoints. */
internal data class PrayerTimelineWindow(
    val startMinutes: Int,
    val endMinutes: Int,
) {
    val durationMinutes: Int get() = endMinutes - startMinutes
}

/**
 * One offset range shared by every prayer so the sliders are visually comparable.
 * Offsets are minutes relative to adhan (0 = adhan). The default window always
 * covers [-15, +60]; any saved window outside it extends the range instead of
 * being clamped.
 */
internal data class PrayerTimelineScale(
    val startOffsetMinutes: Int,
    val endOffsetMinutes: Int,
) {
    val spanMinutes: Int get() = (endOffsetMinutes - startOffsetMinutes).coerceAtLeast(1)

    fun offsetForFraction(fraction: Float): Int =
        startOffsetMinutes + (fraction * spanMinutes).toInt()

    fun fractionForOffset(offset: Int): Float =
        (offset - startOffsetMinutes).toFloat() / spanMinutes
}

internal const val DEFAULT_TIMELINE_START_OFFSET_MINUTES = -15
internal const val DEFAULT_TIMELINE_END_OFFSET_MINUTES = 60

/** Deliberate-edge extension policy: how long outward intent must hold before the domain grows. */
internal const val TIMELINE_EDGE_ARM_DELAY_MS = 400L

/** Deliberate-edge extension policy: interval between one-minute viewing-domain steps. */
internal const val TIMELINE_EDGE_STEP_INTERVAL_MS = 200L

/** Deliberate-edge extension policy: minutes added to the endpoint and viewing domain per step. */
internal const val TIMELINE_EDGE_STEP_MINUTES = 1

private const val TIMELINE_SETTLE_STEP_MINUTES = 15

/**
 * The endpoint domain of 6.9 in the editors: a start within 720 min of the adhan either way, an end at most 1,440
 * after it (and, being after the start, at most 720 before it).
 */
internal fun endpointOffsetLimit(endpoint: SilenceEndpoint, after: Boolean): Int =
    if (endpoint == SilenceEndpoint.END && after) SilenceWindowPlanner.END_DOMAIN_MINUTES.toInt()
    else SilenceWindowPlanner.START_DOMAIN_MINUTES.toInt()

/**
 * Viewing boundaries settle outward to whole quarter-hours after an extended drag
 * ([floorToTimelineBoundary]/[ceilToTimelineBoundary]) while the selected values are
 * never rounded, so an extended timeline stays extended instead of collapsing onto
 * the dragged endpoint.
 */
internal fun sharedPrayerTimelineScale(
    windowsByPrayer: List<Pair<PrayerTimelineAnchor, PrayerTimelineWindow>>,
): PrayerTimelineScale {
    var start = DEFAULT_TIMELINE_START_OFFSET_MINUTES
    var end = DEFAULT_TIMELINE_END_OFFSET_MINUTES
    windowsByPrayer.forEach { (anchor, window) ->
        val prayerMinutes = anchor.minutes
        val startOffset = (window.startMinutes - prayerMinutes).coerceAtLeast(Int.MIN_VALUE + 1)
        val endOffset = (window.endMinutes - prayerMinutes).coerceAtMost(Int.MAX_VALUE - 1)
        if (startOffset < start) start = startOffset
        if (endOffset > end) end = endOffset
    }
    val settledStart = minOf(start, floorToTimelineBoundary(start))
    val settledEnd = maxOf(end, ceilToTimelineBoundary(end))
    return PrayerTimelineScale(settledStart, settledEnd.coerceAtLeast(settledStart + 1))
}

/** Rounds an earlier viewing boundary outward to the previous quarter-hour. */
internal fun floorToTimelineBoundary(offsetMinutes: Int): Int =
    Math.floorDiv(offsetMinutes, TIMELINE_SETTLE_STEP_MINUTES) * TIMELINE_SETTLE_STEP_MINUTES

/** Rounds a later viewing boundary outward to the next quarter-hour. */
internal fun ceilToTimelineBoundary(offsetMinutes: Int): Int =
    -Math.floorDiv(-offsetMinutes, TIMELINE_SETTLE_STEP_MINUTES) * TIMELINE_SETTLE_STEP_MINUTES

/** Clock value of an axis minute (includes previous/next-day rows). */
internal fun prayerClockText(absoluteMinutes: Int): String {
    val clock = Math.floorMod(absoluteMinutes, MINUTES_PER_DAY)
    return "⁦${String.format(Locale.US, "%02d:%02d", clock / 60, clock % 60)}⁩"
}

internal fun prayerDayOffset(absoluteMinutes: Int): Int = Math.floorDiv(absoluteMinutes, MINUTES_PER_DAY)

// --- Endpoint rule helpers -------------------------------------------------

internal fun PrayerSilenceConfig.startRuleMode(): EndpointRuleMode =
    if (delayMode == DelayMode.FIXED_TIME && delayFixedHour >= 0 && delayFixedMinute >= 0) {
        EndpointRuleMode.FIXED_TIME
    } else {
        EndpointRuleMode.ADHAN
    }

/** A legacy duration end reads as an adhan-relative rule while its start is unchanged. */
internal fun PrayerSilenceConfig.endRuleMode(): EndpointRuleMode =
    if (mode == SilenceMode.FIXED_TIME && fixedHour >= 0 && fixedMinute >= 0) {
        EndpointRuleMode.FIXED_TIME
    } else {
        EndpointRuleMode.ADHAN
    }

/**
 * Replaces one endpoint's rule without touching the other endpoint: [offsetMinutes] from the adhan, or the
 * place's clock at axis minute [axisMinutes] for a fixed start (its occurrence nearest the adhan, D16).
 */
internal fun PrayerSilenceConfig.withStartRule(
    anchor: PrayerTimelineAnchor,
    mode: EndpointRuleMode,
    offsetMinutes: Int,
    axisMinutes: Int,
): PrayerSilenceConfig = if (mode == EndpointRuleMode.FIXED_TIME) {
    val clock = anchor.placeClockAt(axisMinutes)
    copy(delayMode = DelayMode.FIXED_TIME, delayFixedHour = clock.hour, delayFixedMinute = clock.minute)
} else {
    copy(delayMode = DelayMode.MINUTES, delayMinutes = offsetMinutes)
}

/** Replaces one endpoint's rule without touching the other endpoint (see [withStartRule]); a fixed end is on the start's day (D16). */
internal fun PrayerSilenceConfig.withEndRule(
    anchor: PrayerTimelineAnchor,
    mode: EndpointRuleMode,
    offsetMinutes: Int,
    axisMinutes: Int,
): PrayerSilenceConfig = if (mode == EndpointRuleMode.FIXED_TIME) {
    val clock = anchor.placeClockAt(axisMinutes)
    copy(mode = SilenceMode.FIXED_TIME, fixedHour = clock.hour, fixedMinute = clock.minute, endOffsetMinutes = null)
} else {
    copy(mode = SilenceMode.DURATION, endOffsetMinutes = offsetMinutes)
}

/** The rule a draft schedules: a fixed end that keeps the stored clock keeps its saved adhan offset (D16). */
private fun PrayerSilenceConfig.ruleAt(anchor: PrayerTimelineAnchor): SilenceRule {
    val saved = anchor.savedEnd
        ?.takeIf { mode == SilenceMode.FIXED_TIME && fixedHour == it.time.hour && fixedMinute == it.time.minute }
        ?.savedAdhanOffsetMinutes
    return SilenceRule.of(this, savedEndOffsetMinutes = saved)
}

/** A draft's window on the anchor's day, resolved and clamped as the planner arms it (6.9, D16). */
internal fun silenceEndsAt(anchor: PrayerTimelineAnchor, config: PrayerSilenceConfig): SilenceEnds =
    SilenceWindowPlanner.ends(anchor.prayer, anchor.adhan, config.ruleAt(anchor), anchor.placeZone, anchor.inm)

/** The window the planner arms for [config] on the anchor's day, on the axis (an end before the start included). */
internal fun resolvePrayerTimelineWindow(
    anchor: PrayerTimelineAnchor,
    config: PrayerSilenceConfig,
): PrayerTimelineWindow {
    val ends = silenceEndsAt(anchor, config)
    return PrayerTimelineWindow(anchor.axisOf(ends.start), anchor.axisOf(ends.end))
}

/**
 * Keeps [PrayerSilenceConfig.afterMinutes] usable for legacy readers whenever an
 * adhan-relative end is stored; the scheduler prefers [PrayerSilenceConfig.endOffsetMinutes].
 */
internal fun PrayerSilenceConfig.withLegacyDurationSynced(anchor: PrayerTimelineAnchor): PrayerSilenceConfig {
    if (mode != SilenceMode.DURATION || endOffsetMinutes == null) return this
    val window = resolvePrayerTimelineWindow(anchor, this)
    return copy(afterMinutes = window.durationMinutes.coerceAtLeast(0))
}

/** A draft the planner arms as it is: inside the endpoint domain, ending after it starts (6.9). */
internal fun configResolvesToValidWindow(anchor: PrayerTimelineAnchor, config: PrayerSilenceConfig): Boolean {
    val ends = silenceEndsAt(anchor, config)
    return !ends.clamped && ends.end > ends.start
}

/**
 * Maps a displayed window back to explicit independent endpoint rules. The active
 * gesture only ever moves one endpoint, so the untouched endpoint keeps its
 * resolved time while legacy duration rules are normalized to their equivalent
 * explicit rule for the displayed date. A window outside the endpoint domain has none.
 */
internal fun prayerTimelineConfigForWindow(
    anchor: PrayerTimelineAnchor,
    config: PrayerSilenceConfig,
    window: PrayerTimelineWindow,
): PrayerSilenceConfig? {
    if (window.endMinutes < window.startMinutes) return null
    val started = config.withStartRule(anchor, config.startRuleMode(), anchor.offsetAt(window.startMinutes), window.startMinutes)
    val ended = started.withEndRule(anchor, config.endRuleMode(), anchor.offsetAt(window.endMinutes), window.endMinutes)
    // Legacy readers still use the duration; keep it in sync for the displayed date.
    val candidate = if (ended.mode == SilenceMode.DURATION) ended.copy(afterMinutes = window.durationMinutes.coerceAtLeast(0)) else ended
    return candidate.takeIf { configResolvesToValidWindow(anchor, it) && resolvePrayerTimelineWindow(anchor, it) == window }
}

// --- Anchors from the repository -------------------------------------------

/** The fixed end the planner arms for [prayer], defaults included, with its saved adhan offset (D16), or null. */
private fun savedFixedEnd(context: Context, prayer: Prayer, place: PrayerPlace): SilenceEnd.Clock? =
    SilenceEndpoints.withDefaults(PrefsManager.getSilenceRule(context, prayer), prayer, place is PrayerPlace.Inm).end as? SilenceEnd.Clock

private fun anchorOf(
    context: Context,
    prayer: Prayer,
    prayerDay: LocalDate,
    adhan: Instant,
    place: PrayerPlace,
    time: com.tunisianprayertimes.engine.EventTime? = null,
) = PrayerTimelineAnchor(
    prayer, prayerDay, adhan, place.zone, place is PrayerPlace.Inm, savedFixedEnd(context, prayer, place),
    estimated = time?.status == com.tunisianprayertimes.engine.EventStatus.ESTIMATED, rule = time?.rule,
)

/** The silence editors' anchors of the five prayers of prayer day [date] (6.9, D8); Dhuhr's even on a Friday. */
internal fun prayerTimelineAnchors(
    context: Context,
    date: LocalDate,
    place: PrayerPlace = PrayerTimesRepository.place(context),
): Map<Prayer, PrayerTimelineAnchor> {
    val day = PrayerTimesRepository.prayerDay(context, date, place) ?: return emptyMap()
    return listOf(
        Prayer.FAJR to TimeEvent.FAJR, Prayer.DHUHR to TimeEvent.DHUHR, Prayer.ASR to TimeEvent.ASR,
        Prayer.MAGHRIB to TimeEvent.MAGHRIB, Prayer.ISHA to TimeEvent.ISHA,
    ).mapNotNull { (prayer, event) -> day[event].at?.let { prayer to anchorOf(context, prayer, date, it, place, day[event]) } }.toMap()
}

/** Jumu'a's anchor: the Friday on or after [date], at its resolved time (A18). */
internal fun jumuaTimelineAnchor(
    context: Context,
    date: LocalDate,
    place: PrayerPlace = PrayerTimesRepository.place(context),
): PrayerTimelineAnchor? {
    val friday = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
    val at = PrayerTimesRepository.jumuaTime(context, friday, place) ?: return null
    return anchorOf(context, Prayer.JOMOAA, friday, at, place)
}

/** An Eid prayer's anchor on [eidDate] (Tunisia only, A10), at its edited time or the day's sunrise. */
internal fun eidTimelineAnchor(
    context: Context,
    prayer: Prayer,
    eidDate: LocalDate,
    place: PrayerPlace = PrayerTimesRepository.place(context),
): PrayerTimelineAnchor? {
    val day = PrayerTimesRepository.prayerDay(context, eidDate, place)
    val at = EidSilenceWindows.eidPrayers(context, place, eidDate, day).firstOrNull { it.first == prayer }?.second ?: return null
    return anchorOf(context, prayer, eidDate, at, place)
}

/** [prayer]'s anchor on [date]: Jumu'a on the Friday on or after it, an Eid prayer on its day, else the prayer day's adhan. */
internal fun timelineAnchorFor(
    context: Context,
    prayer: Prayer,
    date: LocalDate,
    place: PrayerPlace = PrayerTimesRepository.place(context),
): PrayerTimelineAnchor? = when (prayer) {
    Prayer.JOMOAA -> jumuaTimelineAnchor(context, date, place)
    Prayer.AID_FITR, Prayer.AID_ADHA -> eidTimelineAnchor(context, prayer, date, place)
    else -> prayerTimelineAnchors(context, date, place)[prayer]
}

/** The first of the five adhans after [anchor]'s, over its prayer day and the next: the overlap warning's limit. */
internal fun nextTimelineAnchor(
    context: Context,
    anchor: PrayerTimelineAnchor,
    place: PrayerPlace = PrayerTimesRepository.place(context),
): PrayerTimelineAnchor? =
    (prayerTimelineAnchors(context, anchor.prayerDay, place).values + prayerTimelineAnchors(context, anchor.prayerDay.plusDays(1), place).values)
        .filter { it.adhan > anchor.adhan }
        .minByOrNull { it.adhan }

/**
 * D16's year run for a draft (plan 6.9): the draft over the next 365 prayer days at the place, a fixed end with the
 * adhan offset it has on the anchor's day, as it would be saved.
 */
internal fun silenceYearFit(context: Context, anchor: PrayerTimelineAnchor, config: PrayerSilenceConfig): SilenceFit {
    val place = PrayerTimesRepository.place(context)
    val savedOffset = if (config.endRuleMode() == EndpointRuleMode.FIXED_TIME) {
        anchor.offsetAt(resolvePrayerTimelineWindow(anchor, config).endMinutes)
    } else null
    val rule = SilenceRule.of(config, savedEndOffsetMinutes = savedOffset)
    return SilenceScheduler.planner(context, place).fit(anchor.prayer, rule, from = LocalDate.now(place.zone), days = 365)
}

private const val MINUTES_PER_DAY = 24 * 60
