package com.tunisianprayertimes.silence

import com.tunisianprayertimes.DelayMode
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.SilenceMode
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/** How a silence window starts (plan 6.9). */
sealed interface SilenceStart {
    /** [minutes] from the adhan; negative is before it. */
    data class FromAdhan(val minutes: Int) : SilenceStart

    /** A clock time in the place's zone: its occurrence nearest the adhan (D16). */
    data class Clock(val time: LocalTime) : SilenceStart

    /** Never edited: D12's default for Dhuhr, the Jumu'a time for Jumu'a (A18), the adhan for the other prayers. */
    data object Unset : SilenceStart
}

/** How a silence window ends (plan 6.9). */
sealed interface SilenceEnd {
    /** [minutes] after the window's start: today's duration. */
    data class FromStart(val minutes: Int) : SilenceEnd

    /** [minutes] from the adhan, whatever the start. */
    data class FromAdhan(val minutes: Int) : SilenceEnd

    /**
     * A clock time in the place's zone, on the start's day (D16). [savedAdhanOffsetMinutes] is the end's
     * offset from the adhan on the day the user saved it: the end of a day on which even the capped end
     * comes before the start. Null for a setting saved before the offset was stored. [atLeastAdhanMinutes]:
     * never fewer minutes after the adhan (D12's Jumu'a end in Tunisia, owner 2026-10-18: the later of 13:15
     * and Jumu'a + 45, so a mosque's later Jumu'a, an hour before Asr, keeps its whole window).
     */
    data class Clock(val time: LocalTime, val savedAdhanOffsetMinutes: Int? = null, val atLeastAdhanMinutes: Int? = null) : SilenceEnd

    /** Never edited: D12's default for Dhuhr and Jumu'a, a [SilenceEndpoints.DEFAULT_MINUTES] window otherwise. */
    data object Unset : SilenceEnd
}

/** One prayer's silence window as the user set it, each endpoint possibly unset (6.9). */
data class SilenceRule(val start: SilenceStart, val end: SilenceEnd) {
    companion object {
        /**
         * The rule of a phone config. [startUnset] and [endUnset] say that the phone stored no value for that
         * endpoint (an absent key, or the −1 clock values, 6.9). The end's precedence is today's
         * ([com.tunisianprayertimes.SilenceAlarmComputer]): a fixed clock, then an adhan offset, then a duration.
         */
        fun of(
            config: PrayerSilenceConfig,
            startUnset: Boolean = false,
            endUnset: Boolean = false,
            savedEndOffsetMinutes: Int? = null,
        ): SilenceRule {
            val start = when {
                startUnset -> SilenceStart.Unset
                config.delayMode == DelayMode.FIXED_TIME && config.delayFixedHour in 0..23 && config.delayFixedMinute in 0..59 ->
                    SilenceStart.Clock(LocalTime.of(config.delayFixedHour, config.delayFixedMinute))
                else -> SilenceStart.FromAdhan(config.delayMinutes)
            }
            val end = when {
                endUnset -> SilenceEnd.Unset
                config.mode == SilenceMode.FIXED_TIME && config.fixedHour in 0..23 && config.fixedMinute in 0..59 ->
                    SilenceEnd.Clock(LocalTime.of(config.fixedHour, config.fixedMinute), savedEndOffsetMinutes)
                config.endOffsetMinutes != null -> SilenceEnd.FromAdhan(config.endOffsetMinutes)
                else -> SilenceEnd.FromStart(config.afterMinutes)
            }
            return SilenceRule(start, end)
        }
    }
}

/** How a window's end was found on its day (D16): the prayer card flags a saved fixed end that no longer fits. */
enum class EndResolution {
    AS_SET,

    /** The fixed end came before the start and moved later, by at most 30 minutes and never to the next day. */
    CAPPED,

    /** Even the capped end came before the start: the end took its saved offset from the adhan. */
    SAVED_OFFSET,
}

/**
 * Resolves every endpoint of a silence window in one place (plan 6.9, D12, D16). Clock times are read in the
 * place's zone with [ZonedDateTime.of]: in an autumn overlap the earlier instant, in a spring gap later by the
 * gap (6.6). The planner calls it; the desktop keeps today's rule in `SilenceAlarmComputer`.
 */
object SilenceEndpoints {
    /** D16: a fixed end moves later by at most this much. */
    const val CAP_MINUTES = 30L

    /** D12: the Dhuhr window of a `Coordinates` place, from the adhan. */
    const val DHUHR_START_MINUTES = 5
    const val DHUHR_END_MINUTES = 45

    /** D12: Tunisia's Dhuhr window, read in `Africa/Tunis`; 13:15 also ends Jumu'a, or Jumu'a + 45 when that is later (owner, 2026-10-18). */
    val TUNISIA_DHUHR_START: LocalTime = LocalTime.of(12, 40)
    val TUNISIA_DHUHR_END: LocalTime = LocalTime.of(13, 15)

    /** An unset end of a prayer without a D12 default, never produced by the phone, which stores its own defaults. */
    const val DEFAULT_MINUTES = 30

    /** The adhan offset of a fixed end saved before the offset was stored: D12's adhan + 45. */
    const val FALLBACK_OFFSET_MINUTES = DHUHR_END_MINUTES

    /**
     * The rule with D12's defaults in place of its unset endpoints. Jumu'a's window counts from the resolved
     * Jumu'a time (A18), which the planner passes as its adhan, so its start defaults to + 0; in Tunisia it ends
     * at 13:15 or 45 min after Jumu'a, whichever is later (a mosque's Jumu'a an hour before Asr keeps 45 min).
     * [inm] is true for a Tunisian delegation.
     */
    fun withDefaults(rule: SilenceRule, prayer: Prayer, inm: Boolean): SilenceRule {
        val dhuhrOrJumua = prayer == Prayer.DHUHR || prayer == Prayer.JOMOAA
        val start = if (rule.start != SilenceStart.Unset) rule.start else when {
            prayer == Prayer.DHUHR && inm -> SilenceStart.Clock(TUNISIA_DHUHR_START)
            prayer == Prayer.DHUHR -> SilenceStart.FromAdhan(DHUHR_START_MINUTES)
            else -> SilenceStart.FromAdhan(0)
        }
        val end = if (rule.end != SilenceEnd.Unset) rule.end else when {
            prayer == Prayer.JOMOAA && inm -> SilenceEnd.Clock(TUNISIA_DHUHR_END, DHUHR_END_MINUTES, atLeastAdhanMinutes = DHUHR_END_MINUTES)
            dhuhrOrJumua && inm -> SilenceEnd.Clock(TUNISIA_DHUHR_END, DHUHR_END_MINUTES)
            dhuhrOrJumua -> SilenceEnd.FromAdhan(DHUHR_END_MINUTES)
            else -> SilenceEnd.FromStart(DEFAULT_MINUTES)
        }
        return SilenceRule(start, end)
    }

    /** The start's instant: a clock start takes the occurrence of its clock time nearest the adhan (D16). */
    fun start(start: SilenceStart, adhan: Instant, zone: ZoneId): Instant = when (start) {
        is SilenceStart.FromAdhan -> adhan.plusSeconds(start.minutes * 60L)
        is SilenceStart.Clock -> nearestOccurrence(start.time, adhan, zone)
        SilenceStart.Unset -> adhan
    }

    /** An end's instant and how it was found. */
    data class End(val at: Instant, val resolution: EndResolution)

    /** The end's instant from the window's (already clamped) [start] (D16); [prayer] reads an Isha's evening end. */
    fun end(end: SilenceEnd, start: Instant, adhan: Instant, zone: ZoneId, prayer: Prayer? = null): End = when (end) {
        is SilenceEnd.FromStart -> End(start.plusSeconds(end.minutes * 60L), EndResolution.AS_SET)
        is SilenceEnd.FromAdhan -> End(adhan.plusSeconds(end.minutes * 60L), EndResolution.AS_SET)
        is SilenceEnd.Clock -> clockEnd(end, start, adhan, zone, prayer).let { resolved ->
            // A floor after the adhan (D12's Jumu'a end in Tunisia): the later end is the rule as set.
            val floor = end.atLeastAdhanMinutes?.let { adhan.plusSeconds(it * 60L) }
            if (floor != null && floor >= resolved.at) End(floor, EndResolution.AS_SET) else resolved
        }
        SilenceEnd.Unset -> End(start.plusSeconds(DEFAULT_MINUTES * 60L), EndResolution.AS_SET)
    }

    /**
     * D16: the clock time on the start's day when it comes after the start; otherwise later by at most
     * [CAP_MINUTES], never to the next day; and if that still comes before the start, the saved offset from
     * the adhan, so the prayer is still silenced. An adhan after midnight keeps an evening end on its evening:
     * when the clock time's occurrence nearest the end as saved (adhan + the saved offset; the adhan itself for an
     * Isha saved before the offset was stored) comes before the start, that is the end, so an Isha at 00:14 with
     * a 22:00 end saved at adhan + 150 is not silenced until 22:00 the same day. A long window saved on purpose
     * keeps its end: Fajr until 23:45, saved about 20 h after the adhan, or set before offsets were stored.
     */
    private fun clockEnd(end: SilenceEnd.Clock, start: Instant, adhan: Instant, zone: ZoneId, prayer: Prayer?): End {
        val day = start.atZone(zone).toLocalDate()
        val onStartsDay = ZonedDateTime.of(day, end.time, zone).toInstant()
        val meantNear = end.savedAdhanOffsetMinutes?.let { adhan.plusSeconds(it * 60L) } ?: adhan.takeIf { prayer == Prayer.ISHA }
        val meant = meantNear?.let { nearestOccurrence(end.time, it, zone) }
        val asSet = if (meant != null && onStartsDay > start && meant <= start) meant else onStartsDay
        if (asSet > start) return End(asSet, EndResolution.AS_SET)
        val lastMinuteOfDay = ZonedDateTime.of(asSet.atZone(zone).toLocalDate(), LocalTime.of(23, 59), zone).toInstant()
        val capped = minOf(asSet.plusSeconds(CAP_MINUTES * 60), lastMinuteOfDay)
        if (capped > start) return End(capped, EndResolution.CAPPED)
        end.savedAdhanOffsetMinutes?.let { return End(adhan.plusSeconds(it * 60L), EndResolution.SAVED_OFFSET) }
        // A fixed end saved before its offset was stored: the offset it had where it was set is unknown. Its
        // clock's next occurrence after the start keeps today's meaning (an Isha end at 00:30) while that stays
        // within 12 h of the adhan; beyond, it is the 23-hour roll D16 forbids, so D12's adhan + 45 instead.
        val rolled = if (asSet != onStartsDay) onStartsDay else ZonedDateTime.of(day.plusDays(1), end.time, zone).toInstant()
        val offset = if (!rolled.isAfter(adhan.plusSeconds(LEGACY_ROLL_LIMIT_MINUTES * 60))) rolled else adhan.plusSeconds(FALLBACK_OFFSET_MINUTES * 60L)
        return End(offset, EndResolution.SAVED_OFFSET)
    }

    /** The longest adhan offset a fixed end saved before D16 may roll to (the endpoint domain's start bound). */
    const val LEGACY_ROLL_LIMIT_MINUTES = 720L

    /** The occurrence of [time] in [zone] nearest [adhan]; at exactly 12 h either way, the one after it. */
    fun nearestOccurrence(time: LocalTime, adhan: Instant, zone: ZoneId): Instant {
        val date = adhan.atZone(zone).toLocalDate()
        return (-1L..1L)
            .map { ZonedDateTime.of(date.plusDays(it), time, zone).toInstant() }
            .minWith(compareBy<Instant>({ abs(Duration.between(adhan, it).seconds) }, { if (it >= adhan) 0 else 1 }))
    }
}
