package com.tunisianprayertimes.silence

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.engine.PrayerDay
import com.tunisianprayertimes.engine.TimeEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One silence window of a prayer day (plan 6.9): [start] inclusive, [end] exclusive. */
data class SilenceWindow(
    val prayer: Prayer,
    val prayerDay: LocalDate,
    /** The adhan, or for Jumu'a the resolved Jumu'a time and for Eid the Eid prayer's time. */
    val adhan: Instant,
    val start: Instant,
    val end: Instant,
    /** How the end was found (D16). */
    val endResolution: EndResolution,
    /** True when the endpoint domain moved the start or the end (6.9). */
    val clamped: Boolean,
) {
    operator fun contains(at: Instant): Boolean = at >= start && at < end
}

/** What the planner needs of one prayer day. */
data class SilenceDay(
    val date: LocalDate,
    /** The day's six instants; null when the day has no times. */
    val day: PrayerDay?,
    /** On a Friday, the resolved Jumu'a time (an edited time, else [SilenceDefaults.jumuaTime]), which takes Dhuhr's place. */
    val jumua: Instant? = null,
    /** The Eid prayers of the day and their times: Tunisian places only (A10). */
    val eid: List<Pair<Prayer, Instant>> = emptyList(),
)

/**
 * Silence windows as start and end instants (plan 6.9), for prayer days D−2..D+1 around an instant's date D in
 * the place's [zone], so a window carried over from an earlier prayer day is still seen (a Paris Isha of
 * 21 June at 00:11 on 22 June, ending at adhan + 1,440, is open until 00:11 on 23 June).
 *
 * Endpoint domain: a start lies within 12 h of its adhan and an end at most 24 h after it. The start is
 * resolved, then clamped; the end is resolved from that clamped start, then clamped; a window whose end does
 * not come after its start is skipped. Saved values beyond the domain are clamped here, never rewritten.
 *
 * [rules] holds the prayers whose silence is on; a prayer without a rule has no window. [inm] is true for a
 * Tunisian delegation (D12). [dayOf] gives a prayer day's times; each date is asked for at most once.
 */
class SilenceWindowPlanner(
    private val zone: ZoneId,
    private val inm: Boolean,
    private val rules: Map<Prayer, SilenceRule>,
    private val dayOf: (LocalDate) -> SilenceDay?,
) {
    private val days = HashMap<LocalDate, SilenceDay?>()
    private val windowsByDay = HashMap<LocalDate, List<SilenceWindow>>()

    /** Every window of prayer days D−2..D+1, D being [at]'s date in the place's zone, by start. */
    fun windows(at: Instant): List<SilenceWindow> {
        val today = at.atZone(zone).toLocalDate()
        return (-LOOK_BACK_DAYS..LOOK_AHEAD_DAYS)
            .flatMap { windowsOf(today.plusDays(it)) }
            .sortedWith(compareBy<SilenceWindow>({ it.start }, { it.prayerDay }, { it.prayer.ordinal }))
    }

    /** The windows that contain [at]. */
    fun containing(at: Instant): List<SilenceWindow> = windows(at).filter { at in it }

    /** The windows of the scan around [at] that start before it. */
    fun startingBefore(at: Instant): List<SilenceWindow> = windows(at).filter { it.start < at }

    /** The latest end of the windows containing [at]: UNSILENCE keeps the phone silent until then (6.9). */
    fun latestEndContaining(at: Instant): Instant? = containing(at).maxOfOrNull { it.end }

    /** The window a trigger falls in, the one that started first; null outside every window. */
    fun windowAt(trigger: Instant): SilenceWindow? = containing(trigger).firstOrNull()

    /** The windows of one prayer day, by start. */
    fun windowsOf(date: LocalDate): List<SilenceWindow> = windowsByDay.getOrPut(date) {
        val day = days.getOrPut(date) { dayOf(date) } ?: return@getOrPut emptyList()
        slots(day).mapNotNull { (prayer, adhan) ->
            rules[prayer]?.let { window(prayer, date, adhan, it, zone, inm) }
        }.sortedBy { it.start }
    }

    /**
     * D16's year run for the editors: [rule] for [prayer] over [days] prayer days from [from], with the
     * alternatives that fit all of them (6.9). A day without that prayer (no time, or no Friday) is skipped.
     */
    fun fit(prayer: Prayer, rule: SilenceRule, from: LocalDate, days: Int = 365): SilenceFit {
        val resolved = SilenceEndpoints.withDefaults(rule, prayer, inm)
        val checked = ArrayList<Pair<LocalDate, Checked>>()
        for (i in 0 until days) {
            val date = from.plusDays(i.toLong())
            val day = this.days.getOrPut(date) { dayOf(date) } ?: continue
            val adhan = slots(day).firstOrNull { it.first == prayer }?.second ?: continue
            checked += date to check(prayer, date, adhan, resolved)
        }
        val notFitting = checked.filter { (_, c) -> !c.fits }.map { it.first }
        return SilenceFit(
            checkedDays = checked.size,
            notFitting = notFitting,
            laterFixedEnd = if (notFitting.isEmpty()) null else laterFixedEnd(resolved, checked.map { it.second }),
            adhanRelativeEnd = if (notFitting.isEmpty()) null else adhanRelativeEnd(resolved, checked.map { it.second }),
        )
    }

    private class Checked(val adhan: Instant, val start: Instant, val window: SilenceWindow?) {
        val fits: Boolean get() = window != null && window.endResolution == EndResolution.AS_SET && !window.clamped
    }

    private fun check(prayer: Prayer, date: LocalDate, adhan: Instant, rule: SilenceRule): Checked {
        val start = clampedStart(rule.start, adhan, zone)
        return Checked(adhan, start, window(prayer, date, adhan, rule, zone, inm))
    }

    /** The earliest clock end, on the five-minute grid and not before the user's, after every day's start. */
    private fun laterFixedEnd(rule: SilenceRule, checked: List<Checked>): LocalTime? {
        val asSet = (rule.end as? SilenceEnd.Clock)?.time ?: return null
        val latestStart = checked.maxOf { it.start.atZone(zone).toLocalTime().truncatedTo(ChronoUnit.MINUTES) }
        val minutes = latestStart.toSecondOfDay() / 60 + 1
        val onGrid = (minutes + 4) / 5 * 5
        if (onGrid > 23 * 60 + 59) return null
        val candidate = maxOf(LocalTime.of(onGrid / 60, onGrid % 60), asSet)
        // Each day's end as the planner resolves it, so a proposal is never an end the day reads as before its start.
        val fitsAll = checked.all { c ->
            val end = SilenceEndpoints.end(SilenceEnd.Clock(candidate), c.start, c.adhan, zone)
            end.resolution == EndResolution.AS_SET && !end.at.isAfter(c.adhan.plus(END_DOMAIN_MINUTES, ChronoUnit.MINUTES))
        }
        return candidate.takeIf { fitsAll }
    }

    /** The end's saved offset from the adhan, when it comes after every day's start. */
    private fun adhanRelativeEnd(rule: SilenceRule, checked: List<Checked>): Int? {
        val offset = (rule.end as? SilenceEnd.Clock)?.savedAdhanOffsetMinutes ?: return null
        if (offset > END_DOMAIN_MINUTES) return null
        return offset.takeIf { checked.all { c -> c.adhan.plus(offset.toLong(), ChronoUnit.MINUTES) > c.start } }
    }

    /** The day's windows in the order of the day: Jumu'a in Dhuhr's place on a Friday, Eid beside them. */
    private fun slots(day: SilenceDay): List<Pair<Prayer, Instant>> {
        val times = day.day ?: return day.eid
        fun at(event: TimeEvent) = times[event].at
        val noon = if (day.jumua != null && day.date.dayOfWeek == DayOfWeek.FRIDAY) Prayer.JOMOAA to day.jumua else at(TimeEvent.DHUHR)?.let { Prayer.DHUHR to it }
        return listOfNotNull(
            at(TimeEvent.FAJR)?.let { Prayer.FAJR to it },
            noon,
            at(TimeEvent.ASR)?.let { Prayer.ASR to it },
            at(TimeEvent.MAGHRIB)?.let { Prayer.MAGHRIB to it },
            at(TimeEvent.ISHA)?.let { Prayer.ISHA to it },
        ) + day.eid
    }

    companion object {
        const val START_DOMAIN_MINUTES = 720L
        const val END_DOMAIN_MINUTES = 1440L
        const val LOOK_BACK_DAYS = 2L
        const val LOOK_AHEAD_DAYS = 1L

        private fun clampedStart(start: SilenceStart, adhan: Instant, zone: ZoneId): Instant {
            val resolved = SilenceEndpoints.start(start, adhan, zone)
            return resolved.coerceIn(adhan.minus(START_DOMAIN_MINUTES, ChronoUnit.MINUTES), adhan.plus(START_DOMAIN_MINUTES, ChronoUnit.MINUTES))
        }

        /**
         * A window's ends before the check that the end comes after the start: D12's defaults, the start resolved
         * and clamped, the end resolved from it and clamped (6.9). The silence editors draw these, valid or not.
         */
        fun ends(prayer: Prayer, adhan: Instant, rule: SilenceRule, zone: ZoneId, inm: Boolean): SilenceEnds {
            val resolved = SilenceEndpoints.withDefaults(rule, prayer, inm)
            val rawStart = SilenceEndpoints.start(resolved.start, adhan, zone)
            val start = clampedStart(resolved.start, adhan, zone)
            val end = SilenceEndpoints.end(resolved.end, start, adhan, zone)
            val endAt = minOf(end.at, adhan.plus(END_DOMAIN_MINUTES, ChronoUnit.MINUTES))
            return SilenceEnds(start, endAt, end.resolution, clamped = start != rawStart || endAt != end.at)
        }

        /** One window, or null when its end does not come after its start (6.9). */
        fun window(prayer: Prayer, prayerDay: LocalDate, adhan: Instant, rule: SilenceRule, zone: ZoneId, inm: Boolean): SilenceWindow? {
            val ends = ends(prayer, adhan, rule, zone, inm)
            if (ends.end <= ends.start) return null
            return SilenceWindow(prayer, prayerDay, adhan, ends.start, ends.end, ends.resolution, ends.clamped)
        }
    }
}

/** A window's resolved ends (6.9); [clamped] when the endpoint domain moved either of them. */
data class SilenceEnds(val start: Instant, val end: Instant, val resolution: EndResolution, val clamped: Boolean)

/** D16's year run (6.9): the days a rule does not fit at the place, and alternatives that fit all of them. */
data class SilenceFit(
    val checkedDays: Int,
    /** Days whose end needed D16's cap or the saved offset, whose window left the endpoint domain, or that have no window. */
    val notFitting: List<LocalDate>,
    /** A later fixed end that fits every day, when the end is a clock time. */
    val laterFixedEnd: LocalTime?,
    /** An end at the saved offset from the adhan, when that fits every day. */
    val adhanRelativeEnd: Int?,
) {
    val fitsAllYear: Boolean get() = notFitting.isEmpty()
}
