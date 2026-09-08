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
 * The baseline is built once. Corrections are reconciled once per snapshot, and
 * looking up a day only performs a binary search; calendar cells do no network
 * access or calendar reconstruction. RamadanDetector's visibility buffer is not
 * part of this conversion.
 */
class TunisianHijriCalendar(
    overrides: Map<Int, RamadanOverrideChecker.RamadanOverride> = emptyMap(),
) {
    private val confirmedStarts = BooleanArray(Baseline.monthCount + 1)
    private val starts: LongArray

    val supportedFirst: LocalDate
    val supportedLast: LocalDate

    init {
        val anchors = acceptedAnchors(overrides)
        starts = if (anchors.isEmpty()) Baseline.starts else reconcile(anchors)
        anchors.keys.forEach { confirmedStarts[it] = true }
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
     * Reject mislabelled years, implausibly misplaced dates, and contradictions.
     * If two announcements cannot be joined by 29/30-day months, retain the
     * earlier accepted anchor and leave the later one estimated. This stable
     * policy preserves usable partial data without inventing an invalid month.
     */
    private fun acceptedAnchors(
        overrides: Map<Int, RamadanOverrideChecker.RamadanOverride>,
    ): Map<Int, Long> {
        val candidates = sortedMapOf<Int, Long>()
        overrides.forEach { (year, record) ->
            if (year != record.hijriYear || year !in Baseline.firstYear..Baseline.lastYear) return@forEach
            fun add(month: Int, date: LocalDate?) {
                if (date == null) return
                val index = monthIndex(year, month)
                val epoch = date.toEpochDay()
                if (abs(epoch - Baseline.starts[index]) <= MAX_OFFSET) candidates[index] = epoch
            }
            add(9, record.ramadanStart)
            add(10, record.eidFitrDate)
            // Epoch arithmetic also safely handles malformed extreme LocalDates.
            record.eidAdhaDate?.let { eid ->
                val index = monthIndex(year, 12)
                val epoch = eid.toEpochDay() - 9
                if (abs(epoch - Baseline.starts[index]) <= MAX_OFFSET) candidates[index] = epoch
            }
        }
        val accepted = linkedMapOf<Int, Long>()
        var previousIndex: Int? = null
        var previousDay = 0L
        candidates.forEach { (index, day) ->
            val preceding = previousIndex
            if (preceding != null) {
                val monthsBetween = (index - preceding).toLong()
                if (day - previousDay !in (29 * monthsBetween)..(30 * monthsBetween)) return@forEach
            }
            accepted[index] = day
            previousIndex = index
            previousDay = day
        }
        return accepted
    }

    /** Shortest path through nearby starts, with only 29/30-day transitions. */
    private fun reconcile(anchors: Map<Int, Long>): LongArray {
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
            val anchor = anchors[index]
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
            val anchor = anchors[index]
            val baselineLength = (Baseline.starts[index] - Baseline.starts[index - 1]).toInt()
            for (state in 0 until stateCount) {
                val offset = state - MAX_OFFSET
                if (anchor != null && Baseline.starts[index] + offset != anchor) continue
                for (length in 29..30) {
                    val previousState = state + baselineLength - length
                    if (previousState !in 0 until stateCount || costs[previousState] == unreachable) continue
                    val cost = costs[previousState] + abs(offset - preferredOffsets[index]) * OFFSET_COST +
                        if (length == baselineLength) 0 else 1
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
    }
}
