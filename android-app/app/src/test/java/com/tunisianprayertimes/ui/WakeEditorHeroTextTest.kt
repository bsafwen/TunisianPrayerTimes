package com.tunisianprayertimes.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WakeEditorHeroTextTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val tunis = TimeZone.getTimeZone("Africa/Tunis")

    @Test
    fun countdown_underOneMinute_saysLessThanAMinute() {
        assertEquals("أقل من دقيقة", formatWakeCountdownDuration(0L))
        assertEquals("أقل من دقيقة", formatWakeCountdownDuration(59_999L))
    }

    @Test
    fun countdown_roundsPartialMinutesUp() {
        assertEquals("دقيقة واحدة", formatWakeCountdownDuration(minute))
        assertEquals("دقيقتين", formatWakeCountdownDuration(minute + 1L))
    }

    @Test
    fun countdown_screenshotCase_readsDaysHoursMinutes() {
        // Monday 07:19 -> Wednesday 04:45 (Tuesday skipped).
        assertEquals(
            "يوم و21 ساعة و26 دقيقة",
            formatWakeCountdownDuration(day + 21 * hour + 26 * minute),
        )
    }

    @Test
    fun countdown_usesArabicNumberForms() {
        assertEquals("ساعة", formatWakeCountdownDuration(hour))
        assertEquals("ساعتين و5 دقائق", formatWakeCountdownDuration(2 * hour + 5 * minute))
        assertEquals("3 ساعات", formatWakeCountdownDuration(3 * hour))
        assertEquals("11 ساعة", formatWakeCountdownDuration(11 * hour))
        assertEquals("يومين", formatWakeCountdownDuration(2 * day))
        assertEquals("7 أيام ودقيقة واحدة", formatWakeCountdownDuration(7 * day + minute))
    }

    @Test
    fun relativeDay_sameDateIsToday() {
        val now = millisAt(2026, Calendar.SEPTEMBER, 28, 7, 19)
        val trigger = millisAt(2026, Calendar.SEPTEMBER, 28, 23, 59)

        assertEquals(WakeHeroRelativeDay.TODAY, wakeHeroRelativeDay(trigger, now, tunis))
    }

    @Test
    fun relativeDay_nextDateIsTomorrowEvenUnder24Hours() {
        val now = millisAt(2026, Calendar.SEPTEMBER, 28, 23, 50)
        val trigger = millisAt(2026, Calendar.SEPTEMBER, 29, 4, 45)

        assertEquals(WakeHeroRelativeDay.TOMORROW, wakeHeroRelativeDay(trigger, now, tunis))
    }

    @Test
    fun relativeDay_twoDatesAheadIsLater_andWorksAcrossYears() {
        val now = millisAt(2026, Calendar.SEPTEMBER, 28, 7, 19)
        val wednesday = millisAt(2026, Calendar.SEPTEMBER, 30, 4, 45)
        val newYearEve = millisAt(2026, Calendar.DECEMBER, 31, 22, 0)
        val newYearDay = millisAt(2027, Calendar.JANUARY, 1, 5, 58)

        assertEquals(WakeHeroRelativeDay.LATER, wakeHeroRelativeDay(wednesday, now, tunis))
        assertEquals(WakeHeroRelativeDay.TOMORROW, wakeHeroRelativeDay(newYearDay, newYearEve, tunis))
    }

    private fun millisAt(year: Int, month: Int, dayOfMonth: Int, hourOfDay: Int, minuteOfHour: Int): Long =
        Calendar.getInstance(tunis).apply {
            clear()
            set(year, month, dayOfMonth, hourOfDay, minuteOfHour)
        }.timeInMillis
}
