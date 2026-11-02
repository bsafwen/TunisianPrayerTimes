package com.tunisianprayertimes.quran.hifz

import android.app.Application
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.ui.hifzReviewGapsPhrase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HifzRetentionPlanTest {
    private val dayMs = 86_400_000L
    private val firstDay = 100L
    private fun at(day: Long, hour: Int = 12) = day * dayMs + hour * 3_600_000L
    private fun point() = HifzPoint(
        id = 2018L, verse = QuranVerseReference(2, 18),
        mistakes = listOf(HifzMistake(HifzKind.Word)),
        dueDay = firstDay, createdAt = at(firstDay, 6),
        fresh = HifzFreshRecalls, oncePerDay = false,
    )

    @Test
    fun defaultPath_requiresFourteenSuccessesAndAllNineActualWaitingGaps() {
        var p = point()
        listOf(100L to 8, 100L to 16, 101L to 8, 101L to 16, 102L to 8).forEach { (day, hour) ->
            assertTrue(p.isDue(day, at(day, hour)))
            p = p.graded(HifzGrade.Held, day, at(day, hour))
            assertFalse(p.mastered)
        }
        assertEquals(0, p.fresh)
        val reinforcedOn = 102L
        var previous = reinforcedOn
        val gaps = listOf(1, 3, 5, 7, 10, 13, 17, 22, 28)
        gaps.forEachIndexed { index, gap ->
            assertEquals(gap.toLong(), p.dueDay - previous)
            assertFalse(p.isDue(p.dueDay - 1, at(p.dueDay - 1)))
            assertEquals(index == gaps.lastIndex, p.holdsNext)
            val day = p.dueDay
            p = p.graded(HifzGrade.Held, day, at(day))
            previous = day
            assertEquals(index == gaps.lastIndex, p.mastered)
        }
        assertEquals(106L, previous - reinforcedOn)
        assertEquals(14, p.checks.size)
        assertEquals(at(previous), p.masteredAt)
        repeat(3) {
            assertEquals(previous + 60, p.dueDay)
            previous = p.dueDay
            p = p.graded(HifzGrade.Held, previous, at(previous))
            assertTrue(p.mastered)
        }
    }

    @Test
    fun secondDailySuccess_movesEligibilityAndRemindersToTomorrowEvenAfterRestartOrPlanEdit() {
        val first = point().graded(HifzGrade.Held, firstDay, at(firstDay, 8))
        assertEquals(1, first.daysAfter(HifzGrade.Held, firstDay))
        val second = first.graded(HifzGrade.Held, firstDay, at(firstDay, 10))
        assertEquals(3, second.fresh)
        assertEquals(firstDay + 1, second.dueDay)
        assertFalse(second.isDue(firstDay, at(firstDay, 23)))
        assertEquals(second, second.graded(HifzGrade.Held, firstDay, at(firstDay, 12)))
        val restored = decodeHifzPoints(encodeHifzPoints(listOf(second))).single()
        assertEquals(second, restored)
        val edited = restored.withPlan(HifzPlan(HifzPace.Auto, null, oncePerDay = false), firstDay)
        assertEquals(firstDay + 1, edited.dueDay)
        val next = listOf(edited).nextReminder(
            HifzTimes(minutes = setOf(8 * 60, 12 * 60, 16 * 60)),
            firstDay, at(firstDay, 10), firstDay - 1, zone = ZoneOffset.UTC,
            slotAt = { day, time -> day * dayMs + (time as HifzWhen.At).minute * 60_000L },
        )
        assertEquals(firstDay + 1, next?.day)
        assertEquals(at(firstDay + 1, 8), next?.at)
    }

    @Test
    fun hesitationBeforeTheLastCheck_requiresRepeatingTheFullTwentyEightDayGap() {
        val p = point().copy(fresh = 0, step = 8)
        val hesitant = p.graded(HifzGrade.Hesitated, firstDay, at(firstDay))
        assertEquals(firstDay + 2, hesitant.dueDay)
        assertFalse(hesitant.mastered)
        assertFalse(hesitant.holdsNext)
        val retry = hesitant.graded(HifzGrade.Held, firstDay + 2, at(firstDay + 2))
        assertFalse(retry.mastered)
        assertEquals(firstDay + 2 + 28, retry.dueDay)
        assertTrue(retry.holdsNext)
        assertTrue(retry.graded(HifzGrade.Held, retry.dueDay, at(retry.dueDay)).mastered)
    }

    @Test
    fun aPreviouslyMasteredVerse_neverRestartsWithOnlyTwoRecalls() {
        val old = point().copy(fresh = 0, step = 9, lastSlipAt = at(firstDay - 40), createdAt = at(firstDay - 40))
        val failed = old.graded(HifzGrade.Again, firstDay, at(firstDay))
        assertEquals(5, failed.fresh)
        assertFalse(failed.mastered)
        assertNull(failed.masteredAt)
    }

    @Test
    fun adoptingTheNewPlan_preservesHistoryAndDoesNotCreditMoreThanTwoSuccessesOnAnOldDay() {
        val old = point().copy(
            autoRecalls = 3, fresh = 1,
            checks = listOf(8, 10, 12).map { HifzCheck(firstDay, HifzGrade.Held, at(firstDay, it)) },
        )
        val json = JSONObject(encodeHifzPoints(listOf(old))).apply { remove("learningPlan") }
        val changed = decodeHifzPoints(json.toString()).single()
        assertEquals(5, changed.autoRecalls)
        assertEquals(3, changed.fresh)
        assertEquals(2, changed.rightSinceSlip)
        assertEquals(firstDay + 1, changed.dueDay)
        assertEquals(old.checks, changed.checks)
        assertEquals(old.mistakes, changed.mistakes)
        assertEquals(changed, decodeHifzPoints(encodeHifzPoints(listOf(changed))).single())
    }

    @Test
    fun oldMasteryAndMaintenance_doNotSkipTheNewRetentionChecks() {
        listOf(4, 5, 10, 80).forEach { oldStep ->
            val old = point().copy(
                fresh = 0, step = oldStep, masteredAt = at(firstDay), dueDay = firstDay + 60,
                checks = listOf(HifzCheck(firstDay, HifzGrade.Held, at(firstDay))),
            )
            val json = JSONObject(encodeHifzPoints(listOf(old))).apply { remove("learningPlan") }
            val changed = decodeHifzPoints(json.toString()).single()
            assertEquals(4, changed.step)
            assertFalse(changed.mastered)
            assertNull(changed.masteredAt)
            assertEquals(firstDay + 10, changed.dueDay)
            assertEquals(old.checks, changed.checks)
        }
    }

    @Test
    fun helpText_listsTheActualNineGapsWithoutMaintenance() {
        val text = hifzReviewGapsPhrase()
        assertEquals(9, text.split("، ").size)
        assertTrue(text.endsWith("28\u00a0يومًا"))
        assertFalse(text.contains("60"))
    }
}
