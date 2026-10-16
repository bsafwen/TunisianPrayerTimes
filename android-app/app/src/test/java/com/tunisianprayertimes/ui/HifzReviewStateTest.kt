package com.tunisianprayertimes.ui

import androidx.compose.runtime.saveable.SaverScope
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.hifz.HifzCheck
import com.tunisianprayertimes.quran.hifz.HifzGrade
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HifzReviewStateTest {
    private var now = 10_000L
    private val calls = mutableListOf<String>()
    private val pendingChanges = mutableListOf<Pair<Long, Boolean>>()
    private val practiceResults = mutableListOf<Pair<Long, HifzGrade?>>()
    private val exposures = mutableListOf<Set<QuranVerseReference>>()
    private var undone: HifzGiven? = null
    private val actions = object : HifzReviewActions {
        override fun grade(point: HifzPoint, grade: HifzGrade) { calls += "grade ${point.id} $grade" }
        override fun undo(given: HifzGiven) { calls += "undo ${given.id}"; undone = given }
        override fun listenBefore(point: HifzPoint) { calls += "cue ${point.id}" }
        override fun listen(point: HifzPoint) { calls += "listen ${point.id}" }
        override fun drill(point: HifzPoint) { calls += "drill ${point.id}" }
        override fun fix(point: HifzPoint) { calls += "fix ${point.id}" }
        override fun leaveCard() { calls += "leave" }
        override fun more() { calls += "more" }
        override fun retry(ids: List<Long>) { calls += "retry $ids" }
        override fun listenTo(ids: List<Long>) { calls += "listenTo $ids" }
        override fun pendingPracticeChanged(id: Long, pending: Boolean) { pendingChanges += id to pending }
        override fun practiceResult(point: HifzPoint, grade: HifzGrade?) { practiceResults += point.id to grade }
        override fun exposed(verses: Set<QuranVerseReference>) { exposures += verses }
        override fun dismiss() { calls += "dismiss" }
    }

    private fun point(ayah: Int, kind: HifzKind = HifzKind.Start) =
        HifzPoint(id = ayah.toLong(), verse = QuranVerseReference(41, ayah), kind = kind, dueDay = 100L, createdAt = 0L)

    /** A review opened on [queue], its first card drawn, and time enough passed for a tap to count. */
    private fun opened(queue: List<HifzPoint>): HifzReviewState {
        val state = HifzReviewState(clock = { now })
        state.follow(queue, actions)
        shown(state, queue)
        calls.clear()
        return state
    }

    /** The card in view drawn, then the 400 ms guard over. */
    private fun shown(state: HifzReviewState, queue: List<HifzPoint>) {
        state.shown(state.point(queue))
        now += 500L
    }

    @Test
    fun aTapRightAfterTheCardChangedDoesNothing() {
        val queue = listOf(point(36), point(37))
        val state = opened(queue)
        state.reveal(queue, actions)
        assertTrue(state.revealed(queue[0]))
        // The grades replaced «أظهر» under the finger: the same tap a moment later is no answer.
        state.shown(state.point(queue))
        now += 100L
        state.grade(queue, actions, HifzGrade.Held)
        assertEquals(0, state.graded)
        assertEquals(queue[0], state.point(queue))
        now += 400L
        state.grade(queue, actions, HifzGrade.Held)
        assertEquals(1, state.held)
        assertEquals(queue[1], state.point(queue))
        assertFalse("the next card starts hidden", state.revealed(state.point(queue)))
    }

    @Test
    fun newBackgroundExposureHelpsOpenAttemptButOldExposureDoesNotSpoilRetest() {
        val queue = listOf(point(36))
        val state = opened(queue)
        state.syncExposed(setOf(queue[0].verse), actions)
        assertTrue(state.assisted(queue[0]))
        state.reveal(queue, actions)
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Hesitated)
        shown(state, queue)
        state.retest(queue[0], actions)
        state.syncExposed(setOf(queue[0].verse), actions)
        assertFalse("previous study should not mark a fresh retest as helped", state.assisted(queue[0]))
    }

    @Test
    fun stillNeedsTrainingStaysOnTheCardUntilNext() {
        val queue = listOf(point(36), point(37))
        val state = opened(queue)
        state.reveal(queue, actions)
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Again)
        assertEquals(queue[0], state.point(queue))
        assertTrue(state.training(queue[0]))
        shown(state, queue)
        state.drill(queue[0], actions)
        assertTrue(state.drilled(queue[0]))
        state.next(queue, actions)
        assertEquals(queue[1], state.point(queue))
        assertFalse(state.training(queue[1]))
        assertEquals(listOf("leave", "grade 36 Again", "drill 36", "leave"), calls)
    }

    @Test
    fun undoTakesTheLastGradeBackOnItsCardRevealed() {
        val queue = listOf(point(36), point(37))
        val state = opened(queue)
        state.reveal(queue, actions)
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Hesitated)
        assertEquals("hesitation opens correction before the next point", queue[0], state.point(queue))
        assertTrue(state.training(queue[0]))
        assertEquals(listOf(36L), state.failedIds)
        assertEquals(36L, state.undoable(queue)?.id)
        state.undo(queue, actions)
        state.follow(queue, actions)
        assertEquals(queue[0], state.point(queue))
        assertTrue(state.revealed(queue[0]))
        assertEquals(0, state.graded)
        assertNull("one level only", state.undoable(queue))
    }

    @Test
    fun aPointDeletedBeforeTheCardDoesNotMoveIt_theCardDeletedGivesWayToTheNext() {
        val queue = listOf(point(36), point(37), point(38))
        val state = opened(queue)
        state.reveal(queue, actions)
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Held)
        assertEquals(queue[1], state.point(queue))
        // The first point deleted from a fix over the review: the card in view stays on 37.
        val fewer = queue - queue[0]
        state.follow(fewer, actions)
        assertEquals(queue[1], state.point(fewer))
        calls.clear()
        // The card itself deleted: the one after takes its place, and the audio it started stops.
        val last = fewer - queue[1]
        state.follow(last, actions)
        assertEquals(queue[2], state.point(last))
        assertEquals(listOf("leave"), calls)
    }

    @Test
    fun aFoldedCardOpensAgainForANewCardAndForItsAnswer() {
        val queue = listOf(point(36), point(37))
        val state = opened(queue)
        state.folded = true
        // «أظهر» from the page, by the verse number: the answers must be seen.
        state.reveal(queue, actions)
        assertFalse(state.folded)
        state.folded = true
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Held)
        state.follow(queue, actions)
        assertEquals(queue[1], state.point(queue))
        assertFalse(state.folded)
    }

    @Test
    fun revealTwiceIsOnce() {
        val queue = listOf(point(36))
        val state = opened(queue)
        state.reveal(queue, actions)
        now += 1_000L
        state.reveal(queue, actions)
        assertEquals(listOf("leave"), calls)
    }

    @Test
    fun eitherPreAnswerHintMakesSuccessfulRecallAssisted() {
        for (useMnemonic in listOf(true, false)) {
            val queue = listOf(point(36).copy(key = "تذكّر بداية الآية"))
            val state = opened(queue)
            if (useMnemonic) state.showHint(queue[0]) else state.markAssisted(queue[0])
            assertTrue(state.assisted(queue[0]))
            state.reveal(queue, actions)
            shown(state, queue)
            state.grade(queue, actions, HifzGrade.Held)

            assertEquals(listOf("grade 36 Hesitated"), calls.filter { it.startsWith("grade ") })
            assertEquals(0, state.held)
            assertEquals(1, state.hesitated)
            assertTrue(state.training(queue[0]))
            assertEquals(listOf(36L), state.failedIds)
        }
    }

    @Test
    fun ordinaryRevealAndHelpAfterRevealDoNotChangeTheOriginalAttempt() {
        val queue = listOf(point(36))
        val state = opened(queue)
        state.reveal(queue, actions)
        state.markAssisted(queue[0])
        assertFalse(state.assisted(queue[0]))
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Held)

        assertTrue(state.atEnd(queue))
        assertEquals(listOf("grade 36 Held"), calls.filter { it.startsWith("grade ") })
    }

    @Test
    fun repairRetestDoesNotWriteAScheduledGradeTwice() {
        for (firstGrade in listOf(HifzGrade.Hesitated, HifzGrade.Again)) {
            val queue = listOf(point(36), point(37))
            val state = opened(queue)
            state.showHint(queue[0])
            state.reveal(queue, actions)
            shown(state, queue)
            state.grade(queue, actions, firstGrade)
            shown(state, queue)
            state.retest(queue[0], actions)
            assertTrue(state.retesting(queue[0]))
            assertFalse(state.revealed(queue[0]))
            assertFalse("fresh recall starts with every hint hidden", state.hinted(queue[0]))
            assertFalse(state.assisted(queue[0]))

            shown(state, queue)
            state.reveal(queue, actions)
            shown(state, queue)
            state.grade(queue, actions, HifzGrade.Held)

            assertEquals(listOf("grade 36 $firstGrade"), calls.filter { it.startsWith("grade ") })
            assertEquals("training does not add another daily answer", 1, state.graded)
            assertEquals(0, state.held)
            assertEquals(firstGrade, state.undoable(queue)?.grade)
            assertTrue(state.repaired(queue[0]))
            assertTrue(state.failedIds.isEmpty())
            assertEquals(listOf(36L), state.recoveredIds)
            state.next(queue, actions)
            assertEquals(queue[1], state.point(queue))
        }
    }

    @Test
    fun anAssistedRetestRemainsWeakAndKeepsTheOriginalSchedule() {
        val queue = listOf(point(36))
        val state = opened(queue)
        state.reveal(queue, actions)
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Again)
        shown(state, queue)
        state.retest(queue[0], actions)
        state.markAssisted(queue[0])
        shown(state, queue)
        state.reveal(queue, actions)
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Held)

        assertEquals(listOf("grade 36 Again"), calls.filter { it.startsWith("grade ") })
        assertFalse(state.repaired(queue[0]))
        assertTrue(state.retested(queue[0]))
        assertEquals(listOf(36L), state.failedIds)
        assertTrue(state.recoveredIds.isEmpty())
    }

    @Test
    fun practiceKeepsHesitationsAndFailuresForRetryWithoutWritingSchedules() {
        val queue = listOf(point(36), point(37))
        val state = HifzReviewState(clock = { now }, practice = true)
        state.follow(queue, actions)
        shown(state, queue)
        for (grade in listOf(HifzGrade.Hesitated, HifzGrade.Again)) {
            state.reveal(queue, actions)
            shown(state, queue)
            state.grade(queue, actions, grade)
            state.next(queue, actions)
            shown(state, queue)
        }

        assertTrue(state.atEnd(queue))
        assertEquals(listOf(36L, 37L), state.failedIds)
        assertEquals(2, state.graded)
        assertTrue(calls.none { it.startsWith("grade ") })
        state.undo(queue, actions)
        assertTrue(calls.none { it.startsWith("undo ") })
    }

    @Test
    fun finalAnswerKeepsUndoWithItsPreviousHistory() {
        val history = listOf(HifzCheck(98, HifzGrade.Held, 1_000), HifzCheck(99, HifzGrade.Hesitated, 2_000))
        val original = point(36).copy(step = 2, dueDay = 100, slips = 4, lastSlipAt = 900, checks = history)
        val queue = listOf(original)
        val state = opened(queue)
        state.reveal(queue, actions)
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Held)

        assertTrue(state.atEnd(queue))
        assertEquals(history, state.undoable(queue)?.checks)
        state.undo(queue, actions)

        assertFalse(state.atEnd(queue))
        assertEquals(original, state.point(queue))
        assertEquals(0, state.graded)
        assertEquals(original.step, undone?.step)
        assertEquals(original.dueDay, undone?.dueDay)
        assertEquals(original.slips, undone?.slips)
        assertEquals(original.lastSlipAt, undone?.lastSlipAt)
        assertEquals("the repository receives the history from before the grade", history, undone?.checks)
    }

    @Test
    fun savedRetestKeepsItsHiddenQuestionAndSeparateOutcome() {
        val queue = listOf(point(36))
        val state = opened(queue)
        state.showHint(queue[0])
        state.reveal(queue, actions)
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Held)
        shown(state, queue)
        state.retest(queue[0], actions)

        fun restored(): HifzReviewState {
            val saved = with(HifzReviewState.Saver) { SaverScope { true }.save(state) }!!
            return HifzReviewState.Saver.restore(saved)!!
        }
        val question = restored()
        assertEquals(queue[0], question.point(queue))
        assertTrue(question.retesting(queue[0]))
        assertFalse(question.revealed(queue[0]))
        assertFalse(question.hinted(queue[0]))
        assertFalse(question.assisted(queue[0]))
        assertEquals(1, question.hesitated)
        assertEquals(HifzGrade.Hesitated, question.undoable(queue)?.grade)

        shown(state, queue)
        state.reveal(queue, actions)
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Held)
        val result = restored()
        assertTrue(result.repaired(queue[0]))
        assertEquals(listOf(36L), result.recoveredIds)
        assertTrue(result.failedIds.isEmpty())
        assertEquals("recovered now does not rewrite the first attempt", 1, result.hesitated)
        result.undo(queue, actions)
        assertTrue("undo retains the original attempt's assistance", result.assisted(queue[0]))
        assertEquals(0, result.graded)
    }

    @Test
    fun olderUndoSnapshotsWithoutHistoryStillDecode() {
        val given = HifzGiven.decode("36|Hesitated|2|100||0|4|900")
        assertEquals(36L, given?.id)
        assertTrue(given!!.checks.isEmpty())
    }

    @Test
    fun pendingPracticeSurvivesTheNextBatchAndKeepsUnrelatedPoints() {
        val queue = listOf(point(36))
        val first = HifzReviewState(clock = { now }, pendingPracticeIds = setOf(90L))
        first.follow(queue, actions)
        shown(first, queue)
        first.reveal(queue, actions)
        shown(first, queue)
        first.grade(queue, actions, HifzGrade.Again)
        first.next(queue, actions)
        assertEquals(setOf(90L, 36L), first.pendingPracticeIds)
        assertEquals(36L to true, pendingChanges.last())

        val second = HifzReviewState(clock = { now }, earlier = first.day, pendingPracticeIds = first.pendingPracticeIds)
        assertTrue(second.failedIds.isEmpty())
        assertEquals("the retry queue is independent of this batch's failures", setOf(90L, 36L), second.pendingPracticeIds)
        assertEquals(1, second.day.again)
    }

    @Test
    fun linkedAnswerExposureMakesTheLaterPointPracticeWithoutReclassifyingTheFirstAttempt() {
        val queue = listOf(point(36), point(37))
        val state = opened(queue)
        state.reveal(queue, actions)
        state.noteExposed(setOf(queue[1].verse), actions)
        assertFalse("the original answer was recalled before reveal", state.practiceOnly(queue[0]))
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Held)
        assertTrue(state.practiceOnly(queue[1]))
        assertFalse("an exposed answer is practice, not a false fresh attempt with a hint", state.assisted(queue[1]))
        shown(state, queue)
        state.reveal(queue, actions)
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Held)

        assertEquals(listOf("grade 36 Held"), calls.filter { it.startsWith("grade ") })
        assertEquals(listOf(37L to HifzGrade.Held), practiceResults)
        assertEquals(2, state.graded)
        assertEquals("practice must not inflate the scheduled day's count", 1, state.day.graded)
        assertEquals(listOf(37L), state.practiceIds)
    }

    @Test
    fun aDayLedgerExposureBeforeOpeningCannotAdvanceTheSchedule() {
        val queue = listOf(point(36))
        val state = HifzReviewState(clock = { now }, exposedVerses = setOf(queue[0].verse))
        state.follow(queue, actions)
        shown(state, queue)
        assertTrue(state.previouslyExposed(queue[0]))
        state.reveal(queue, actions)
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Again)
        assertTrue(calls.none { it.startsWith("grade ") })
        assertEquals(0, state.day.graded)
        assertEquals(setOf(36L), state.pendingPracticeIds)
        assertEquals(36L to HifzGrade.Again, practiceResults.last())
    }

    @Test
    fun exposureDuringTheCurrentHiddenAttemptCountsAsHelp() {
        val queue = listOf(point(36), point(37))
        val state = opened(queue)
        state.noteExposed(setOf(queue[0].verse, queue[1].verse), actions)
        assertTrue(state.assisted(queue[0]))
        assertFalse(state.previouslyExposed(queue[0]))
        state.reveal(queue, actions)
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Held)
        assertEquals(listOf("grade 36 Hesitated"), calls.filter { it.startsWith("grade ") })
        assertEquals(1, exposures.size)
    }

    @Test
    fun undoRestoresPendingPracticeAndItsPreviousCompletionReceipt() {
        val queue = listOf(point(36))
        val state = HifzReviewState(clock = { now }, practice = true, pendingPracticeIds = setOf(36L, 90L))
        state.follow(queue, actions)
        shown(state, queue)
        state.reveal(queue, actions)
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Held)
        assertEquals(setOf(90L), state.pendingPracticeIds)
        val saved = with(HifzReviewState.Saver) { SaverScope { true }.save(state) }!!
        val restored = HifzReviewState.Saver.restore(saved)!!
        restored.undo(queue, actions)
        assertEquals(setOf(36L, 90L), restored.pendingPracticeIds)
        assertEquals(36L to null, practiceResults.last())
        assertEquals(0, restored.graded)
        assertEquals(0, restored.day.graded)
        assertTrue(calls.none { it.startsWith("undo ") })
    }

    @Test
    fun savedExposureLedgerDoesNotChangeTheAttemptAlreadyInProgress() {
        val queue = listOf(point(36), point(37))
        val state = opened(queue)
        state.reveal(queue, actions)
        state.noteExposed(setOf(queue[1].verse), actions)
        val saved = with(HifzReviewState.Saver) { SaverScope { true }.save(state) }!!
        val restored = HifzReviewState.Saver.restore(saved)!!
        assertFalse(restored.practiceOnly(queue[0]))
        restored.next(queue, actions)
        assertTrue(restored.practiceOnly(queue[1]))
    }

    @Test
    fun theReviewIsSavedAndReadBackWhole() {
        val queue = listOf(point(36), point(37), point(38))
        val state = opened(queue)
        state.reveal(queue, actions)
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Held)
        shown(state, queue)
        state.reveal(queue, actions)
        shown(state, queue)
        state.grade(queue, actions, HifzGrade.Again)
        shown(state, queue)
        state.drill(queue[1], actions)
        state.folded = true
        state.howItWorks = true
        val saved = with(HifzReviewState.Saver) { SaverScope { true }.save(state) }!!
        val back = HifzReviewState.Saver.restore(saved)!!
        assertEquals(queue[1], back.point(queue))
        assertTrue(back.revealed(queue[1]))
        assertTrue(back.training(queue[1]))
        assertTrue(back.drilled(queue[1]))
        assertEquals(1, back.held)
        assertEquals(1, back.again)
        assertEquals(37L, back.undoable(queue)?.id)
        assertTrue(back.folded)
        assertTrue(back.howItWorks)
        // A review never opened reads back as new.
        val fresh = HifzReviewState.Saver.restore(with(HifzReviewState.Saver) { SaverScope { true }.save(HifzReviewState(clock = { now })) }!!)!!
        assertEquals(queue[0], fresh.point(queue))
        assertNull(fresh.undoable(queue))
        assertEquals(0, fresh.graded)
    }
}
