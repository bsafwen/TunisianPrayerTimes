package com.tunisianprayertimes.engine

import com.tunisianprayertimes.method.Rounding
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Step 10 (plan 6.2, "Event order"): keeps the displayed order Fajr ≤ sunrise ≤ Dhuhr ≤ Asr ≤ Maghrib ≤
 * Isha within the day and the night's lowest point between prayer days, then rounds each time once.
 * A pure function of the unrounded instants, so it is tested on its own.
 */
internal object OrderGuard {
    /** A move smaller than this is floating-point noise, not a move. */
    private const val MOVED_SECONDS = 1e-3

    /** UP and DOWN keep an instant this close to a whole minute on that minute. */
    private const val MINUTE_EPSILON_SECONDS = 1e-3

    /** One time going into the guard: unrounded epoch seconds (null when absent) and the mode to round it with. */
    data class Input(
        val event: TimeEvent,
        val seconds: Double?,
        val status: EventStatus,
        val rule: RuleName?,
        val rounding: Rounding,
    )

    /** One time coming out: the instant after the walk and the hold, its minute, and what moved it. */
    data class Output(
        val event: TimeEvent,
        val seconds: Double?,
        val minuteSeconds: Long?,
        val status: EventStatus,
        val rule: RuleName?,
        val rounding: Rounding,
        val moves: List<Move>,
    )

    /** A change the guard made: [rule] is HELD_IN_ORDER or NIGHT_LOWEST_POINT, [onMinutes] for the walk on the minutes. */
    data class Move(val rule: RuleName, val fromSeconds: Double, val toSeconds: Double, val onMinutes: Boolean = false)

    /**
     * [times] are the six times of day D in the order of the day; [lowestBefore] is the lowest point of
     * the night N(D − 1), which Fajr and sunrise may not precede, and [lowestAfter] that of N(D), which
     * Maghrib and Isha may not follow (null when that night has no lowest point).
     */
    fun guard(times: List<Input>, lowestBefore: Double?, lowestAfter: Double?): List<Output> {
        require(times.map { it.event } == TimeEvent.entries) { "six times in the order of the day" }
        val seconds = times.map { it.seconds }.toMutableList()
        val status = times.map { it.status }.toMutableList()
        val rule = times.map { it.rule }.toMutableList()
        val moves = List(6) { mutableListOf<Move>() }

        fun move(i: Int, to: Double, why: RuleName) {
            val from = seconds[i] ?: return
            if (abs(to - from) <= MOVED_SECONDS) return
            seconds[i] = to
            moves[i] += Move(why, from, to)
            if (status[i] != EventStatus.ESTIMATED) {
                status[i] = EventStatus.ESTIMATED
                rule[i] = why
            }
        }

        // 1. The walk: Fajr no later than sunrise, then each later time no earlier than the one before it.
        walk(seconds) { i, to -> move(i, to, RuleName.HELD_IN_ORDER) }
        // 2. The night hold: evening times at or before N(D)'s lowest point, morning times at or after N(D − 1)'s.
        if (lowestAfter != null) for (i in EVENING) seconds[i]?.let { if (it > lowestAfter) move(i, lowestAfter, RuleName.NIGHT_LOWEST_POINT) }
        if (lowestBefore != null) for (i in MORNING) seconds[i]?.let { if (it < lowestBefore) move(i, lowestBefore, RuleName.NIGHT_LOWEST_POINT) }

        // 3. One rounding per time: to the nearest minute within a minute of its night's lowest point.
        val modes = times.mapIndexed { i, input ->
            val t = seconds[i]
            val lowest = if (i in EVENING) lowestAfter else if (i in MORNING) lowestBefore else null
            if (t != null && lowest != null && abs(t - lowest) < 60.0) Rounding.NEAREST else input.rounding
        }
        val minutes = seconds.mapIndexed { i, t -> t?.let { roundToMinute(it, modes[i]).toDouble() } }.toMutableList()

        // 4. The walk again, on the minutes: it can only make ties, and a time it changes keeps its status.
        walk(minutes) { i, to ->
            val from = minutes[i] ?: return@walk
            minutes[i] = to
            moves[i] += Move(RuleName.HELD_IN_ORDER, from, to, onMinutes = true)
        }

        return times.indices.map { i ->
            Output(times[i].event, seconds[i], minutes[i]?.toLong(), status[i], rule[i], modes[i], moves[i])
        }
    }

    /** Rounds epoch seconds to a whole minute: NEAREST takes 30 s and more up; UP and DOWN keep an exact minute. */
    fun roundToMinute(seconds: Double, mode: Rounding): Long = 60L * when (mode) {
        Rounding.NEAREST -> floor((seconds + 30.0) / 60.0)
        Rounding.UP -> ceil((seconds - MINUTE_EPSILON_SECONDS) / 60.0)
        Rounding.DOWN -> floor((seconds + MINUTE_EPSILON_SECONDS) / 60.0)
    }.toLong()

    /** Fajr held at no later than sunrise, then sunrise..Isha each held at no earlier than the last present time before it. */
    private fun walk(values: List<Double?>, set: (Int, Double) -> Unit) {
        val fajr = values[FAJR]
        val sunrise = values[SUNRISE]
        if (fajr != null && sunrise != null && fajr > sunrise) set(FAJR, sunrise)
        var previous: Double? = null
        for (i in SUNRISE..ISHA) {
            val t = values[i] ?: continue
            if (previous != null && t < previous) set(i, previous)
            previous = values[i]
        }
    }

    private const val FAJR = 0
    private const val SUNRISE = 1
    private const val ISHA = 5
    private val MORNING = listOf(0, 1)
    private val EVENING = listOf(4, 5)
}
