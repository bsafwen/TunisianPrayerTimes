package com.tunisianprayertimes.engine

import com.tunisianprayertimes.method.Rounding
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The order guard alone, as a function of unrounded instants (plan 6.2 "Event order", 9.1). */
class OrderGuardTest {
    private val base = Instant.parse("2026-06-21T00:00:00Z").epochSecond.toDouble()

    /** Seconds since 2026-06-21T00:00Z from "hh:mm:ss" (hours may pass 24, or be negative with a leading minus). */
    private fun t(hms: String): Double {
        val sign = if (hms.startsWith("-")) -1 else 1
        val (h, m, s) = hms.removePrefix("-").split(":").map(String::toDouble)
        return base + sign * (h * 3600 + m * 60 + s)
    }

    private fun hm(seconds: Long?): String? = seconds?.let {
        val s = it - base.toLong()
        val sign = if (s < 0) "-" else ""
        val a = kotlin.math.abs(s)
        "$sign%02d:%02d".format(a / 3600, a % 3600 / 60)
    }

    private fun day(
        fajr: String, sunrise: String, dhuhr: String, asr: String, maghrib: String, isha: String,
        modes: Map<TimeEvent, Rounding> = emptyMap(),
        estimated: Map<TimeEvent, RuleName> = emptyMap(),
    ): List<OrderGuard.Input> = TimeEvent.entries.zip(listOf(fajr, sunrise, dhuhr, asr, maghrib, isha)).map { (event, time) ->
        val rule = estimated[event]
        OrderGuard.Input(
            event, t(time),
            if (rule != null) EventStatus.ESTIMATED else EventStatus.COMPUTED, rule,
            modes[event] ?: Rounding.NEAREST,
        )
    }

    private fun guard(inputs: List<OrderGuard.Input>, before: String? = null, after: String? = null) =
        OrderGuard.guard(inputs, before?.let(::t), after?.let(::t)).associateBy { it.event }

    @Test
    fun fajrAfterSunriseIsHeldAtSunrise() {
        val out = guard(day("05:50:10", "05:45:00", "12:30:00", "16:00:00", "19:30:00", "21:00:00"))
        assertEquals(t("05:45:00"), out.getValue(TimeEvent.FAJR).seconds)
        assertEquals(EventStatus.ESTIMATED, out.getValue(TimeEvent.FAJR).status)
        assertEquals(RuleName.HELD_IN_ORDER, out.getValue(TimeEvent.FAJR).rule)
        assertEquals(EventStatus.COMPUTED, out.getValue(TimeEvent.SUNRISE).status)
    }

    @Test
    fun asrBeforeDhuhrIsShownAtDhuhr() {
        // tunisia_18_18 at Rovaniemi on 2026-12-21: Dhuhr + 7 at 10:22, Asr at 10:16 (plan 6.2).
        val out = guard(day("07:00:00", "09:00:00", "10:22:07", "10:15:40", "11:30:00", "13:00:00"))
        assertEquals(hm(out.getValue(TimeEvent.DHUHR).minuteSeconds), hm(out.getValue(TimeEvent.ASR).minuteSeconds))
        assertEquals(RuleName.HELD_IN_ORDER, out.getValue(TimeEvent.ASR).rule)
        assertEquals(EventStatus.COMPUTED, out.getValue(TimeEvent.DHUHR).status)
    }

    @Test
    fun ishaBeforeMaghribIsShownAtMaghrib() {
        // mwl_angle's 22:45 cap at Bergen in June, sunset 23:11 (plan 6.2).
        val out = guard(
            day("03:00:00", "04:00:00", "13:30:00", "17:50:00", "23:11:00", "22:45:00", estimated = mapOf(TimeEvent.ISHA to RuleName.ISHA_LATEST)),
            after = "25:20:00",
        )
        assertEquals(t("23:11:00"), out.getValue(TimeEvent.ISHA).seconds)
        // Already ESTIMATED by the cap: it keeps its own rule.
        assertEquals(RuleName.ISHA_LATEST, out.getValue(TimeEvent.ISHA).rule)
    }

    @Test
    fun nightHoldMeetsAtTheLowestPoint() {
        // ecfr_90 at Luleå (plan 6.2): Maghrib = sunset, Isha = Maghrib + 90, Fajr = sunrise − 90, on nights of
        // about 53 minutes; in UTC from 2026-06-21T00:00Z (hours below 0 written from 24 on 2026-06-20).
        val out = guard(
            day("-02:30:12", "-01:00:12", "11:20:00", "15:30:00", "22:06:48", "23:36:48"),
            before = "-01:26:42",
            after = "22:33:18",
        )
        assertEquals(t("22:33:18"), out.getValue(TimeEvent.ISHA).seconds)
        assertEquals(RuleName.NIGHT_LOWEST_POINT, out.getValue(TimeEvent.ISHA).rule)
        assertEquals("22:33", hm(out.getValue(TimeEvent.ISHA).minuteSeconds))
        assertEquals(t("-01:26:42"), out.getValue(TimeEvent.FAJR).seconds)
        assertEquals(RuleName.NIGHT_LOWEST_POINT, out.getValue(TimeEvent.FAJR).rule)
        assertEquals(EventStatus.COMPUTED, out.getValue(TimeEvent.MAGHRIB).status)
        assertEquals(EventStatus.COMPUTED, out.getValue(TimeEvent.SUNRISE).status)
    }

    @Test
    fun nightHoldOnEachMorningTime() {
        val out = guard(day("01:00:00", "01:20:00", "12:00:00", "16:00:00", "21:00:00", "22:00:00"), before = "01:10:00")
        assertEquals(t("01:10:00"), out.getValue(TimeEvent.FAJR).seconds)
        assertEquals(RuleName.NIGHT_LOWEST_POINT, out.getValue(TimeEvent.FAJR).rule)
        assertEquals(t("01:20:00"), out.getValue(TimeEvent.SUNRISE).seconds)
        val both = guard(day("01:00:00", "01:05:00", "12:00:00", "16:00:00", "21:00:00", "22:00:00"), before = "01:10:00")
        assertEquals(t("01:10:00"), both.getValue(TimeEvent.FAJR).seconds)
        assertEquals(t("01:10:00"), both.getValue(TimeEvent.SUNRISE).seconds)
        assertEquals(RuleName.NIGHT_LOWEST_POINT, both.getValue(TimeEvent.SUNRISE).rule)
    }

    @Test
    fun nightHoldOnBothSidesAtOnce() {
        val out = guard(
            day("00:50:00", "02:00:00", "12:00:00", "16:00:00", "22:30:00", "24:40:00"),
            before = "01:00:00",
            after = "24:30:00",
        )
        assertEquals(t("01:00:00"), out.getValue(TimeEvent.FAJR).seconds)
        assertEquals(t("24:30:00"), out.getValue(TimeEvent.ISHA).seconds)
        assertEquals(EventStatus.COMPUTED, out.getValue(TimeEvent.MAGHRIB).status)
    }

    @Test
    fun timesNearTheLowestPointRoundToNearestWhateverTheirMode() {
        // Syria: Fajr DOWN, Isha UP; within a minute of the lowest point both round to the nearest minute.
        val modes = mapOf(TimeEvent.FAJR to Rounding.DOWN, TimeEvent.ISHA to Rounding.UP)
        val out = guard(day("01:30:50", "02:30:00", "12:00:00", "16:00:00", "23:00:00", "24:29:20", modes), before = "01:30:20", after = "24:30:00")
        assertEquals("01:31", hm(out.getValue(TimeEvent.FAJR).minuteSeconds))
        assertEquals(Rounding.NEAREST, out.getValue(TimeEvent.FAJR).rounding)
        assertEquals("24:29", hm(out.getValue(TimeEvent.ISHA).minuteSeconds))
        // More than a minute away, their own modes apply.
        val far = guard(day("01:32:50", "02:30:00", "12:00:00", "16:00:00", "23:00:00", "24:28:20", modes), before = "01:30:20", after = "24:30:00")
        assertEquals("01:32", hm(far.getValue(TimeEvent.FAJR).minuteSeconds))
        assertEquals("24:29", hm(far.getValue(TimeEvent.ISHA).minuteSeconds))
    }

    @Test
    fun roundingTieOfInstantsMoreThanAMinuteApart() {
        // umm_al_qura at Luleå on 2026-07-15 with Fajr + 30 and sunrise − 30: 01:45:31 up, 01:46:32 down, both 01:46.
        val modes = mapOf(TimeEvent.FAJR to Rounding.UP, TimeEvent.SUNRISE to Rounding.DOWN)
        val out = guard(day("01:45:31", "01:46:32", "13:00:00", "17:00:00", "22:00:00", "23:30:00", modes))
        assertEquals("01:46", hm(out.getValue(TimeEvent.FAJR).minuteSeconds))
        assertEquals("01:46", hm(out.getValue(TimeEvent.SUNRISE).minuteSeconds))
        assertEquals(EventStatus.COMPUTED, out.getValue(TimeEvent.FAJR).status)
        assertEquals(EventStatus.COMPUTED, out.getValue(TimeEvent.SUNRISE).status)
    }

    @Test
    fun walkOnTheMinutesMakesOnlyTiesAndKeepsStatuses() {
        // Maghrib rounded up past an Isha rounded down 20 s later: the walk on the minutes ties them.
        val modes = mapOf(TimeEvent.MAGHRIB to Rounding.UP, TimeEvent.ISHA to Rounding.DOWN)
        val out = guard(day("03:00:00", "05:00:00", "12:00:00", "16:00:00", "21:00:10", "21:00:30", modes))
        assertEquals("21:01", hm(out.getValue(TimeEvent.MAGHRIB).minuteSeconds))
        assertEquals("21:01", hm(out.getValue(TimeEvent.ISHA).minuteSeconds))
        assertEquals(EventStatus.COMPUTED, out.getValue(TimeEvent.ISHA).status)
        assertEquals(true, out.getValue(TimeEvent.ISHA).moves.single().onMinutes)
    }

    @Test
    fun ishaRoundedUpBesideFajrJustAfterTheLowestPoint() {
        // An Isha rounded up just over a minute before the lowest point (24:00:20), and the next Fajr less
        // than a minute after it, rounded to the nearest minute, share a minute (plan 6.2, permitted ties).
        val today = guard(
            day("02:00:00", "03:00:00", "12:00:00", "16:00:00", "22:00:00", "23:59:15", mapOf(TimeEvent.ISHA to Rounding.UP)),
            after = "24:00:20",
        )
        val tomorrow = guard(
            day("24:00:25", "26:00:00", "36:00:00", "40:00:00", "46:00:00", "47:00:00", mapOf(TimeEvent.FAJR to Rounding.DOWN)),
            before = "24:00:20",
        )
        assertEquals("24:00", hm(today.getValue(TimeEvent.ISHA).minuteSeconds))
        assertEquals("24:00", hm(tomorrow.getValue(TimeEvent.FAJR).minuteSeconds))
        assertEquals(Rounding.UP, today.getValue(TimeEvent.ISHA).rounding)
        assertEquals(Rounding.NEAREST, tomorrow.getValue(TimeEvent.FAJR).rounding)
        assertEquals(EventStatus.COMPUTED, today.getValue(TimeEvent.ISHA).status)
        assertEquals(EventStatus.COMPUTED, tomorrow.getValue(TimeEvent.FAJR).status)
    }

    @Test
    fun absentTimesAreLeftOutOfTheWalk() {
        val inputs = day("03:00:00", "05:00:00", "12:00:00", "16:00:00", "21:00:00", "20:00:00").map {
            if (it.event == TimeEvent.MAGHRIB) it.copy(seconds = null, status = EventStatus.ABSENT) else it
        }
        val out = guard(inputs)
        assertNull(out.getValue(TimeEvent.MAGHRIB).minuteSeconds)
        // Isha is still held after the last present time before it, Asr.
        assertEquals(t("20:00:00"), out.getValue(TimeEvent.ISHA).seconds)
    }

    @Test
    fun roundingModes() {
        val exact = t("10:00:00")
        assertEquals(exact.toLong(), OrderGuard.roundToMinute(exact + 0.0004, Rounding.UP))
        assertEquals(exact.toLong() + 60, OrderGuard.roundToMinute(exact + 0.01, Rounding.UP))
        assertEquals(exact.toLong(), OrderGuard.roundToMinute(exact + 59.9, Rounding.DOWN))
        assertEquals(exact.toLong(), OrderGuard.roundToMinute(exact - 0.0004, Rounding.DOWN))
        assertEquals(exact.toLong() + 60, OrderGuard.roundToMinute(exact + 30.0, Rounding.NEAREST))
        assertEquals(exact.toLong(), OrderGuard.roundToMinute(exact + 29.999, Rounding.NEAREST))
    }
}
