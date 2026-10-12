package com.tunisianprayertimes.ui

import androidx.compose.runtime.saveable.SaverScope
import com.tunisianprayertimes.quran.QuranVerseReference
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
    private val actions = object : HifzReviewActions {
        override fun grade(point: HifzPoint, grade: HifzGrade) { calls += "grade ${point.id} $grade" }
        override fun undo(given: HifzGiven) { calls += "undo ${given.id}" }
        override fun listenBefore(point: HifzPoint) { calls += "cue ${point.id}" }
        override fun listen(point: HifzPoint) { calls += "listen ${point.id}" }
        override fun drill(point: HifzPoint) { calls += "drill ${point.id}" }
        override fun fix(point: HifzPoint) { calls += "fix ${point.id}" }
        override fun leaveCard() { calls += "leave" }
        override fun more() { calls += "more" }
        override fun dismiss() { calls += "dismiss" }
    }

    private fun point(ayah: Int, kind: HifzKind = HifzKind.Start) =
        HifzPoint(id = ayah.toLong(), verse = QuranVerseReference(41, ayah), kind = kind, dueDay = 100L, createdAt = 0L)

    /** A review opened on [queue], its first card drawn, and time enough passed for a tap to count. */
    private fun opened(queue: List<HifzPoint>): HifzReviewState {
        val state = HifzReviewState { now }
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
        assertEquals(queue[1], state.point(queue))
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
        val fresh = HifzReviewState.Saver.restore(with(HifzReviewState.Saver) { SaverScope { true }.save(HifzReviewState { now }) }!!)!!
        assertEquals(queue[0], fresh.point(queue))
        assertNull(fresh.undoable(queue))
        assertEquals(0, fresh.graded)
    }
}
