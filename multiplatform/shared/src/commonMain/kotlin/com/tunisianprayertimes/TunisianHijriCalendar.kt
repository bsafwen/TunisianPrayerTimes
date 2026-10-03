package com.tunisianprayertimes

import java.time.LocalDate
import java.time.chrono.HijrahChronology
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import kotlin.math.abs

/** A civil day in the Tunisian calendar, which can differ from Umm al-Qura. */
data class HijriCalendarDate(
    val year: Int,
    val month: Int,
    val day: Int,
    val isEstimated: Boolean,
)

data class HijriCalendarMonth(
    val year: Int,
    val month: Int,
    val start: LocalDate,
    val endExclusive: LocalDate,
    /** Whether this month's start is estimated; its end may still be unannounced. */
    val isEstimated: Boolean,
) {
    val lengthOfMonth: Int get() = (endExclusive.toEpochDay() - start.toEpochDay()).toInt()
}

/**
 * Immutable calendar snapshot shared by the date picker and Ramadan/Eid behavior.
 *
 * Official dates anchor 1 Ramadan, 1 Shawwal and (Eid al-Adha minus nine days)
 * 1 Dhul Hijja in their own Hijri year. Unannounced starts use Umm al-Qura with
 * the most recent announced offset in that year. Between differing anchors we
 * reconcile those estimates while requiring every month to contain 29 or 30 days.
 * Moving each event independently, or shifting a HijrahDate by one annual offset,
 * would otherwise create missing dates, repeated dates or 31-day months when two
 * announcements have different offsets.
 *
 * The admin's [manual] dates anchor their events too, and always win: a mosque that
 * follows its own sighting keeps its day. An announcement of another event still
 * keeps its own day, even when that makes a month between the two 28 or 31 days.
 *
 * The baseline is built once. Corrections are reconciled once per snapshot, and
 * looking up a day only performs a binary search; calendar cells do no network
 * access or calendar reconstruction. RamadanDetector's visibility buffer is not
 * part of this conversion.
 */
class TunisianHijriCalendar(
    overrides: Map<Int, RamadanOverrideChecker.RamadanOverride> = emptyMap(),
    manual: Map<Int, ManualIslamicDates> = emptyMap(),
) {
    private val confirmedStarts = BooleanArray(Baseline.monthCount + 1)
    private val starts: LongArray

    val supportedFirst: LocalDate
    val supportedLast: LocalDate

    init {
        val anchors = acceptedAnchors(overrides, manual)
        starts = if (anchors.days.isEmpty()) Baseline.starts else reconcile(anchors)
        anchors.days.keys.forEach { confirmedStarts[it] = true }
        supportedFirst = LocalDate.ofEpochDay(starts.first())
        supportedLast = LocalDate.ofEpochDay(starts.last() - 1)
    }

    fun date(date: LocalDate): HijriCalendarDate {
        val index = monthIndexFor(date)
        val day = (date.toEpochDay() - starts[index]).toInt() + 1
        return HijriCalendarDate(
            year = yearAt(index),
            month = monthAt(index),
            day = day,
            // An announced start establishes days 1..29. An assumed day 30 can
            // still become day 1 of the next month after its announcement.
            isEstimated = !confirmedStarts[index] || (day == 30 && !confirmedStarts[index + 1]),
        )
    }

    fun month(year: Int, month: Int): HijriCalendarMonth = monthAtIndex(monthIndex(year, month))

    fun monthFor(date: LocalDate): HijriCalendarMonth = monthAtIndex(monthIndexFor(date))

    fun adjacentMonth(year: Int, month: Int, amount: Int): HijriCalendarMonth? {
        val index = monthIndex(year, month).toLong() + amount.toLong()
        return if (index in 0L until Baseline.monthCount.toLong()) monthAtIndex(index.toInt()) else null
    }

    private fun monthAtIndex(index: Int): HijriCalendarMonth = HijriCalendarMonth(
        year = yearAt(index),
        month = monthAt(index),
        start = LocalDate.ofEpochDay(starts[index]),
        endExclusive = LocalDate.ofEpochDay(starts[index + 1]),
        isEstimated = !confirmedStarts[index],
    )

    private fun monthIndexFor(date: LocalDate): Int {
        val day = date.toEpochDay()
        require(day >= starts.first() && day < starts.last()) {
            "Date is outside the supported Hijri calendar: $date"
        }
        val found = starts.binarySearch(day)
        return if (found >= 0) found else -found - 2
    }

    private fun monthIndex(year: Int, month: Int): Int {
        require(year in Baseline.firstYear..Baseline.lastYear && month in 1..12) {
            "Unsupported Hijri month: $year/$month"
        }
        return (year - Baseline.firstYear) * 12 + month - 1
    }

    private fun yearAt(index: Int): Int = Baseline.firstYear + index / 12

    private fun monthAt(index: Int): Int = index % 12 + 1

    /**
     * The month starts every month must keep ([days], by month index), and the months that may be 28
     * or 31 days long ([relaxed]: the month before that index), between an admin's date and an
     * announcement that cannot be joined otherwise.
     */
    private class Anchors(val days: Map<Int, Long>, val relaxed: BooleanArray)

    /**
     * Reject mislabelled years, implausibly misplaced dates, and contradictions.
     * The admin's dates come first and are always kept, unless two of them cannot
     * be joined by 29/30-day months (the earlier one stays). Then announcements: if
     * two cannot be joined by 29/30-day months, retain the earlier accepted anchor
     * and leave the later one estimated. This stable policy preserves usable partial
     * data without inventing an invalid month. Only between an admin's date and an
     * announcement may a month be 28 or 31 days, so each keeps its own event's day.
     */
    private fun acceptedAnchors(
        overrides: Map<Int, RamadanOverrideChecker.RamadanOverride>,
        manual: Map<Int, ManualIslamicDates>,
    ): Anchors {
        val announced = sortedMapOf<Int, Long>()
        overrides.forEach { (year, record) ->
            if (year == record.hijriYear) addCandidates(announced, year, record.ramadanStart, record.eidFitrDate, record.eidAdhaDate)
        }
        val admin = sortedMapOf<Int, Long>()
        manual.forEach { (year, dates) -> addCandidates(admin, year, dates.ramadanStart, dates.eidFitr, dates.eidAdha) }

        val accepted = sortedMapOf<Int, Long>()
        fun joins(index: Int, day: Long, relaxedWith: (Int) -> Boolean): Boolean {
            val previous = accepted.headMap(index).let { if (it.isEmpty()) null else it.lastKey() }
            val next = accepted.tailMap(index + 1).let { if (it.isEmpty()) null else it.firstKey() }
            return (previous == null || joined(previous, accepted.getValue(previous), index, day, relaxedWith(previous))) &&
                (next == null || joined(index, day, next, accepted.getValue(next), relaxedWith(next)))
        }
        admin.forEach { (index, day) -> if (joins(index, day) { false }) accepted[index] = day }
        val manualIndices = accepted.keys.toSet()
        announced.forEach { (index, day) ->
            if (index !in accepted && joins(index, day) { it in manualIndices }) accepted[index] = day
        }

        val relaxed = BooleanArray(Baseline.monthCount + 1)
        accepted.keys.zipWithNext().forEach { (from, to) ->
            val mixed = (from in manualIndices) != (to in manualIndices)
            if (mixed && !joined(from, accepted.getValue(from), to, accepted.getValue(to), relaxed = false)) {
                for (index in from + 1..to) relaxed[index] = true
            }
        }
        return Anchors(accepted, relaxed)
    }

    /** 1 Ramadan, 1 Shawwal and 1 Dhul Hijja of [year] from its event dates, when near enough to the baseline. */
    private fun addCandidates(into: MutableMap<Int, Long>, year: Int, ramadan: LocalDate?, fitr: LocalDate?, adha: LocalDate?) {
        if (year !in Baseline.firstYear..Baseline.lastYear) return
        fun add(month: Int, epoch: Long) {
            val index = monthIndex(year, month)
            if (abs(epoch - Baseline.starts[index]) <= MAX_OFFSET) into[index] = epoch
        }
        ramadan?.let { add(9, it.toEpochDay()) }
        fitr?.let { add(10, it.toEpochDay()) }
        // Epoch arithmetic also safely handles malformed extreme LocalDates.
        adha?.let { add(12, it.toEpochDay() - 9) }
    }

    /** Whether months of 29 or 30 days ([relaxed]: 28 to 31) lead from month [from] starting on [fromDay] to [to] on [toDay]. */
    private fun joined(from: Int, fromDay: Long, to: Int, toDay: Long, relaxed: Boolean): Boolean {
        val months = (to - from).toLong()
        val lengths = if (relaxed) ODD_LENGTHS else LENGTHS
        return toDay - fromDay in (lengths.first * months)..(lengths.last * months)
    }

    /** Shortest path through nearby starts, with only 29/30-day transitions except where [Anchors.relaxed]. */
    private fun reconcile(anchors: Anchors): LongArray {
        val stateCount = MAX_OFFSET * 2 + 1
        val unreachable = Long.MAX_VALUE / 4
        val preferredOffsets = IntArray(Baseline.monthCount + 1)
        var latestOffset = 0
        for (index in preferredOffsets.indices) {
            // A year's observed offset must not silently become a correction
            // for every future year. Return toward the baseline after New Year
            // using legal month lengths, rather than pulling an estimated Adha
            // back early just to force Muharram to have zero offset.
            if (index % 12 == 0) latestOffset = 0
            val anchor = anchors.days[index]
            anchor?.let { latestOffset = (it - Baseline.starts[index]).toInt() }
            preferredOffsets[index] = if (index == 0 || anchor != null) {
                latestOffset
            } else {
                val baselineLength = (Baseline.starts[index] - Baseline.starts[index - 1]).toInt()
                val precedingOffset = preferredOffsets[index - 1]
                latestOffset.coerceIn(precedingOffset + 29 - baselineLength, precedingOffset + 30 - baselineLength)
            }
        }
        val predecessors = Array(Baseline.monthCount + 1) { IntArray(stateCount) { -1 } }
        var costs = LongArray(stateCount) { state -> abs(state - MAX_OFFSET).toLong() * OFFSET_COST }

        for (index in 1..Baseline.monthCount) {
            val next = LongArray(stateCount) { unreachable }
            val anchor = anchors.days[index]
            val baselineLength = (Baseline.starts[index] - Baseline.starts[index - 1]).toInt()
            val lengths = if (anchors.relaxed[index]) ODD_LENGTHS else LENGTHS
            for (state in 0 until stateCount) {
                val offset = state - MAX_OFFSET
                if (anchor != null && Baseline.starts[index] + offset != anchor) continue
                for (length in lengths) {
                    val previousState = state + baselineLength - length
                    if (previousState !in 0 until stateCount || costs[previousState] == unreachable) continue
                    // As few 28- or 31-day months as the admin's dates need, whatever the offsets cost.
                    val cost = costs[previousState] + abs(offset - preferredOffsets[index]) * OFFSET_COST +
                        (if (length == baselineLength) 0 else 1) + if (length in LENGTHS) 0 else ODD_LENGTH_COST
                    if (cost < next[state]) {
                        next[state] = cost
                        predecessors[index][state] = previousState
                    }
                }
            }
            costs = next
        }

        var state = costs.indices.minByOrNull { costs[it] }!!
        // The candidate checks guarantee a path: between accepted anchors the
        // required offset can change monotonically within [-MAX_OFFSET, MAX_OFFSET].
        check(costs[state] != unreachable) { "Unable to reconcile Hijri month boundaries" }
        val resolved = LongArray(Baseline.monthCount + 1)
        for (index in Baseline.monthCount downTo 0) {
            resolved[index] = Baseline.starts[index] + state - MAX_OFFSET
            if (index > 0) state = predecessors[index][state]
        }
        return resolved
    }

    private object Baseline {
        val firstYear: Int = HijrahChronology.INSTANCE.range(ChronoField.YEAR).minimum.toInt()
        val lastYear: Int = HijrahChronology.INSTANCE.range(ChronoField.YEAR).maximum.toInt()
        val monthCount: Int = (lastYear - firstYear + 1) * 12
        val starts: LongArray = LongArray(monthCount + 1) { index ->
            if (index == monthCount) {
                val lastMonth = HijrahDate.of(lastYear, 12, 1)
                lastMonth.toEpochDay() + lastMonth.lengthOfMonth()
            } else {
                HijrahDate.of(firstYear + index / 12, index % 12 + 1, 1).toEpochDay()
            }
        }
    }

    private companion object {
        // Much wider than the usual one-day difference, while rejecting dates
        // accidentally attached to another month/year in downloaded data.
        const val MAX_OFFSET = 7
        const val OFFSET_COST = 8L
        val LENGTHS = 29..30
        val ODD_LENGTHS = 28..31
        const val ODD_LENGTH_COST = 1_000_000L
    }
}
