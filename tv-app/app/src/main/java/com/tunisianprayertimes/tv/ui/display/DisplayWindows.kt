package com.tunisianprayertimes.tv.ui.display

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.DayBanner
import com.tunisianprayertimes.mosque.PrayerEvent
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/*
 * When the screens outside the day's timetable take the wall, as pure functions of the clock: like
 * the prayer flow, a restart or a power cut lands on the same screen, in the same place.
 */

/**
 * The night screen: a dim clock on black while the hall is empty, from [AFTER_ISHA] after the Isha
 * iqamah ([AFTER_ISHA_RAMADAN] in Ramadan, for the tarawih) until [BEFORE_FAJR] before the Fajr adhan.
 */
object NightWindow {

    val AFTER_ISHA: Duration = Duration.ofMinutes(60)
    val AFTER_ISHA_RAMADAN: Duration = Duration.ofMinutes(120)
    val BEFORE_FAJR: Duration = Duration.ofMinutes(30)

    /** The block moves this often, to one of [ANCHORS] places, so no pixel holds the clock for long. */
    const val MOVE_MINUTES = 5L
    const val ANCHORS = 9

    /** Isha to the next Fajr is at most about ten hours; more means they are not the same night. */
    private val LONGEST_NIGHT: Duration = Duration.ofHours(14)

    /**
     * Whether [now] is in the night. [lastIshaIqamah] is the latest Isha iqamah at or before [now],
     * [nextFajr] the first Fajr adhan after it; [ramadan] is whether that Isha was a Ramadan night.
     * Never while either is unknown: without prayer times the wall keeps showing the timetable.
     */
    fun isNight(
        now: LocalDateTime,
        lastIshaIqamah: LocalDateTime?,
        nextFajr: LocalDateTime?,
        ramadan: Boolean,
        enabled: Boolean,
    ): Boolean {
        if (!enabled || lastIshaIqamah == null || nextFajr == null) return false
        if (!nextFajr.isAfter(lastIshaIqamah) || Duration.between(lastIshaIqamah, nextFajr) > LONGEST_NIGHT) return false
        val opens = lastIshaIqamah.plus(if (ramadan) AFTER_ISHA_RAMADAN else AFTER_ISHA)
        val closes = nextFajr.minus(BEFORE_FAJR)
        return !now.isBefore(opens) && now.isBefore(closes)
    }

    /** The latest Isha iqamah at or before [now] among [events] (yesterday's and today's). */
    fun lastIshaIqamah(now: LocalDateTime, events: List<PrayerEvent>): LocalDateTime? =
        events.filter { it.prayer == Prayer.ISHA && !it.iqamahAt.isAfter(now) }.maxOfOrNull { it.iqamahAt }

    /** The first Fajr after [now] among [events]; before midnight that is tomorrow's, so pass tomorrow's events too. */
    fun nextFajr(now: LocalDateTime, events: List<PrayerEvent>): PrayerEvent? =
        events.filter { it.prayer == Prayer.FAJR && it.adhanAt.isAfter(now) }.minByOrNull { it.adhanAt }

    /**
     * Where the block is at [now]: 0..8, row by row in a 3 × 3 grid. It changes every [MOVE_MINUTES]
     * and always to another row, so the clock never lingers over the same pixels.
     */
    fun anchorAt(now: LocalDateTime): Int {
        val minutes = now.toLocalDate().toEpochDay() * 24 * 60 + now.hour * 60 + now.minute
        // 5 is prime to 9: the nine places come round in turn, each a row away from the last.
        return Math.floorMod(Math.floorDiv(minutes, MOVE_MINUTES) * 5, ANCHORS.toLong()).toInt()
    }
}

/** The Eid screen: on an Eid day, from midnight until the Dhuhr adhan. */
object EidMorning {

    /** Without today's prayer times the morning ends at noon. */
    private val NOON: LocalTime = LocalTime.NOON

    /** [dhuhrAdhan] is today's; the Eid prayer itself comes from [banner] and may already be over. */
    fun isShown(now: LocalDateTime, banner: DayBanner?, dhuhrAdhan: LocalDateTime?): Boolean {
        if (banner !is DayBanner.Eid) return false
        val end = dhuhrAdhan?.takeIf { it.toLocalDate() == now.toLocalDate() } ?: now.toLocalDate().atTime(NOON)
        return now.isBefore(end)
    }
}

/** The wait from the adhan to the iqamah, as the countdown screen shows it. */
object IqamahWait {

    /** The studs under the countdown, after the 24 ribs of the dome of Kairouan. */
    const val STUDS = 24

    /** The countdown turns warm for the last minute. */
    const val ALERT_SECONDS = 60L

    /** Whole seconds to the iqamah, never negative. */
    fun remainingSeconds(now: LocalDateTime, iqamahAt: LocalDateTime): Long =
        Duration.between(now, iqamahAt).seconds.coerceAtLeast(0)

    /**
     * How many of [count] studs are still lit: all at the adhan, one going out after each
     * [count]th of the wait, none at the iqamah.
     */
    fun litStuds(now: LocalDateTime, adhanAt: LocalDateTime, iqamahAt: LocalDateTime, count: Int = STUDS): Int {
        val total = Duration.between(adhanAt, iqamahAt).seconds
        if (total <= 0) return 0
        val remaining = remainingSeconds(now, iqamahAt).coerceAtMost(total)
        return ((remaining * count + total - 1) / total).toInt()
    }

    /**
     * "07:42": minutes and seconds, the minutes going past 59 for a long wait (an iqamah or an Eid
     * prayer is at most 90 minutes away), so the number keeps its width on the wall.
     */
    fun text(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }
}
