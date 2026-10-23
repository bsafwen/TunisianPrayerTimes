package com.tunisianprayertimes.remembrance

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeathReminderDaysTest {
    private val monday = LocalDate.of(2026, 10, 26)

    private fun week(start: LocalDate, frequency: DeathReminder.Frequency, salt: Long) =
        (0L until 7L).map(start::plusDays).filter { DeathReminder.chosenDay(it, frequency, salt) }

    @Test
    fun eachWeekHasItsCountAndTwoDaysAreNeverSideBySide() {
        listOf(0L, 7L, -3L, 5610157592578582497L).forEach { salt ->
            repeat(60) { w ->
                val start = monday.plusWeeks(w.toLong())
                assertEquals(DayOfWeek.MONDAY, start.dayOfWeek)
                assertEquals(7, week(start, DeathReminder.Frequency.DAILY, salt).size)
                assertEquals(1, week(start, DeathReminder.Frequency.WEEKLY, salt).size)
                val twice = week(start, DeathReminder.Frequency.TWICE, salt)
                assertEquals(2, twice.size)
                assertTrue("$salt week $w: $twice", twice[1].toEpochDay() - twice[0].toEpochDay() >= 2)
            }
        }
    }

    @Test
    fun theDayIsDrawnAfreshEveryWeek() {
        listOf(1L, 42L, 5610157592578582497L, 4750555147799623249L).forEach { salt ->
            val days = (0 until 52).map { week(monday.plusWeeks(it.toLong()), DeathReminder.Frequency.WEEKLY, salt).single().dayOfWeek }
            assertTrue("$salt: $days", days.toSet().size >= 6)
            // Seeds side by side once gave the same day, or the next, week after week.
            val drifting = days.zipWithNext().count { (a, b) -> Math.floorMod(b.value - a.value, 7) <= 1 }
            assertTrue("$salt: $drifting of 51", drifting < 30)
        }
    }
}
