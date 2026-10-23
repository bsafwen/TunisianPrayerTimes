package com.tunisianprayertimes.ui

import androidx.compose.runtime.saveable.SaverScope
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.hifz.HifzGrade
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzMistake
import com.tunisianprayertimes.quran.hifz.HifzPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HifzPracticeStateTest {
    private var now = 10_000L
    private val calls = mutableListOf<String>()
    private val due = mutableSetOf<Long>()
    private val actions = object : HifzPracticeActions {
        override fun dueNow(point: HifzPoint) = point.id in due
        override fun seen(verses: Set<QuranVerseReference>) { calls += "seen ${verses.map { it.ayah }.sorted()}" }
        override fun grade(point: HifzPoint, grade: HifzGrade, counted: Boolean) { calls += "grade ${point.id} $grade $counted" }
        override fun listenBefore(point: HifzPoint) { calls += "cue ${point.id}" }
        override fun listen(point: HifzPoint) { calls += "listen ${point.id}" }
        override fun drill(point: HifzPoint) { calls += "drill ${point.id}" }
        override fun addWords(point: HifzPoint, words: Set<Int>) { calls += "words ${point.id}" }
        override fun leaveCard() { calls += "leave" }
        override fun close() { calls += "close" }
    }

    private fun point(ayah: Int, kind: HifzKind = HifzKind.Word) =
        HifzPoint(id = ayah.toLong(), verse = QuranVerseReference(67, ayah), mistakes = listOf(HifzMistake(kind)), dueDay = 100L, createdAt = 0L)

    private val points = listOf(point(2, HifzKind.Skip), point(3), point(4))

    /** The card at the state's place drawn, then the 400 ms guard over. */
    private fun shown(state: HifzPracticeState) {
        state.shown(state.cards(points).getOrNull(state.index))
        now += 500L
    }

    private fun opened(): HifzPracticeState = HifzPracticeState(points.map { it.id }) { now }.also { shown(it) }

    @Test
    fun anAnswerCountsOnlyWhenItsQuestionWasDueAsItsAnswerWasShown() {
        due += 2L
        val state = opened()
        state.reveal(points[0], actions)
        // Due before the look: decided first, then the verses shown, the verse before a skip with it.
        assertTrue(state.counted(points[0]))
        assertTrue("seen [1, 2]" in calls)
        shown(state)
        state.answer(points[0], HifzGrade.Hesitated, "", points.size, actions)
        assertTrue("grade 2 Hesitated true" in calls)
        // Not due: told so, and its answer is not counted.
        state.step(1, points.size, actions)
        shown(state)
        state.reveal(points[1], actions)
        assertFalse(state.counted(points[1]))
        shown(state)
        state.answer(points[1], HifzGrade.Again, "", points.size, actions)
        assertTrue("grade 3 Again false" in calls)
    }

    @Test
    fun coveredCardPreviewsCurrentEligibility_butRevealFreezesItBeforeExposure() {
        val state = opened()
        assertFalse(state.countsNow(points[0], actions))
        due += points[0].id
        assertTrue(state.countsNow(points[0], actions))
        state.reveal(points[0], actions)
        // Exposure or a later clock change cannot change what this already revealed answer counts for.
        due.clear()
        assertTrue(state.countsNow(points[0], actions))
        state.step(1, points.size, actions)
        shown(state)
        state.reveal(points[1], actions)
        due += points[1].id
        assertFalse(state.countsNow(points[1], actions))
    }

    @Test
    fun practicePreviewIncludesTransitiveDueQuestionsBeforeTheirAnswersAreShown() {
        val all = listOf(point(1), point(2), point(3), point(4), point(6))
        // 4 shows 3 as its cue, and 3 shows 2; neither is in the filtered notebook selection.
        val plan = hifzPracticePlan(all, listOf(4L), setOf(2L, 3L, 6L))
        assertEquals(1, plan.selectedCount)
        assertEquals(2, plan.addedDueCount)
        assertEquals(listOf(2L, 3L, 4L), plan.points.map { it.id })
    }

    @Test
    fun practicePreviewCountsOnlyExistingUniqueSelections_andAddsOnlyDueLinks() {
        val plan = hifzPracticePlan(points, listOf(4L, 4L, 99L), emptySet())
        assertEquals(1, plan.selectedCount)
        assertEquals(0, plan.addedDueCount)
        assertEquals(listOf(4L), plan.points.map { it.id })
        assertTrue(hifzPracticePlan(points, listOf(99L), points.map { it.id }.toSet()).points.isEmpty())
    }

    @Test
    fun aCardIsAnsweredOnce_andKeepsWhatWasDoneOnItWhenComeBackTo() {
        val state = opened()
        // No answer before «أظهر».
        state.answer(points[0], HifzGrade.Held, "", points.size, actions)
        assertNull(state.answer(points[0]))
        state.reveal(points[0], actions)
        shown(state)
        state.answer(points[0], HifzGrade.Held, "", points.size, actions)
        // Remembered: the next card comes.
        assertEquals(1, state.index)
        state.step(-1, points.size, actions)
        shown(state)
        assertTrue(state.revealed(points[0]))
        assertEquals(HifzGrade.Held, state.answer(points[0]))
        calls.clear()
        state.answer(points[0], HifzGrade.Again, "", points.size, actions)
        assertEquals(HifzGrade.Held, state.answer(points[0]))
        assertTrue(calls.none { it.startsWith("grade") })
        // A card left unanswered after «أظهر» is still shown when come back to.
        state.step(1, points.size, actions)
        shown(state)
        state.reveal(points[1], actions)
        state.step(1, points.size, actions)
        state.step(-1, points.size, actions)
        assertTrue(state.revealed(points[1]))
        assertNull(state.answer(points[1]))
    }

    @Test
    fun aTapRightAfterTheCardChangedDoesNothing() {
        val state = opened()
        state.step(1, points.size, actions)
        state.shown(points[1])
        state.reveal(points[1], actions)
        assertFalse(state.revealed(points[1]))
        now += 500L
        state.reveal(points[1], actions)
        assertTrue(state.revealed(points[1]))
    }

    @Test
    fun theStepsStayBetweenTheFirstCardAndTheEnd() {
        val state = opened()
        state.step(-1, points.size, actions)
        assertEquals(0, state.index)
        repeat(5) { state.step(1, points.size, actions) }
        assertEquals(points.size, state.index)
        state.keepWithin(2)
        assertEquals(2, state.index)
    }

    @Test
    fun itComesBackAsItWasAfterTheActivityIsRecreated() {
        due += 2L
        val state = opened()
        state.reveal(points[0], actions)
        shown(state)
        state.answer(points[0], HifzGrade.Again, "نذكّرك بها من جديد، ونسألك عنها اليوم بعد المغرب", points.size, actions)
        state.drill(points[0], actions)
        val value = with(HifzPracticeState.Saver) { SaverScope { true }.save(state) }!!
        val restored = HifzPracticeState.Saver.restore(value)!!
        assertEquals(state.ids, restored.ids)
        assertEquals(state.index, restored.index)
        assertTrue(restored.revealed(points[0]) && restored.counted(points[0]) && restored.drilled(points[0]))
        assertEquals(HifzGrade.Again, restored.answer(points[0]))
        assertEquals(state.outcome(points[0]), restored.outcome(points[0]))
    }
}
