package com.tunisianprayertimes.ui

import androidx.compose.runtime.saveable.SaverScope
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.hifz.HifzCheck
import com.tunisianprayertimes.quran.hifz.HifzFreshGapMs
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
    private var wall = 1_000_000_000L
    private val calls = mutableListOf<String>()
    private val exposures = mutableListOf<Set<QuranVerseReference>>()
    private var undone: HifzGiven? = null
    private val actions = object : HifzReviewActions {
        override fun grade(point: HifzPoint, grade: HifzGrade) { calls += "grade ${point.id} $grade" }
        override fun undo(given: HifzGiven) { calls += "undo ${given.id}"; undone = given }
        override fun listenBefore(point: HifzPoint) { calls += "cue ${point.id}" }
        override fun listen(point: HifzPoint) { calls += "listen ${point.id}" }
        override fun drill(point: HifzPoint) { calls += "drill ${point.id}" }
        override fun leaveCard() { calls += "leave" }
        override fun more() { calls += "more" }
        override fun listenTo(ids: List<Long>) { calls += "listenTo $ids" }
        override fun exposed(verses: Set<QuranVerseReference>) { exposures += verses }
        override fun dismiss() { calls += "dismiss" }
    }

    private fun point(ayah: Int, kind: HifzKind = HifzKind.Start) =
        HifzPoint(id = ayah.toLong(), verse = QuranVerseReference(41, ayah), kind = kind, dueDay = 100L, createdAt = 0L)

    private fun state(
        exposed: Set<QuranVerseReference> = emptySet(),
        seen: Map<QuranVerseReference, Long> = emptyMap(),
    ) = HifzReviewState(clock = { now }, exposedVerses = exposed, seenVerses = seen, wallClock = { wall })

    /** A review opened on [queue], its first card drawn, and time enough passed for a tap to count. */
    private fun opened(queue: List<HifzPoint>, review: HifzReviewState = state()): HifzReviewState {
        review.follow(review.questions(queue), actions)
        shown(review, review.questions(queue))
        calls.clear()
        return review
    }

    /** The card in view drawn, then the 400 ms guard over. */
    private fun shown(state: HifzReviewState, queue: List<HifzPoint>) {
        state.shown(state.point(queue))
        now += 500L
    }

    /** The card in view answered: revealed, then graded once the guard is over. */
    private fun answer(state: HifzReviewState, queue: List<HifzPoint>, grade: HifzGrade) {
        state.reveal(queue, actions)
        shown(state, queue)
        state.grade(queue, actions, grade)
    }

    private fun saved(state: HifzReviewState): HifzReviewState {
        val value = with(HifzReviewState.Saver) { SaverScope { true }.save(state) }!!
        return HifzReviewState.Saver.restore(value)!!
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
        answer(state, queue, HifzGrade.Hesitated)
        assertEquals("a hesitation shows the training before the next point", queue[0], state.point(queue))
        assertTrue(state.training(queue[0]))
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
        answer(state, queue, HifzGrade.Held)
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
            calls.clear()
            val queue = listOf(point(36).copy(key = "تذكّر بداية الآية"))
            val state = opened(queue)
            if (useMnemonic) state.showHint(queue[0]) else state.markAssisted(queue[0])
            assertTrue(state.assisted(queue[0]))
            answer(state, queue, HifzGrade.Held)

            assertEquals(listOf("grade 36 Hesitated"), calls.filter { it.startsWith("grade ") })
            assertEquals(0, state.held)
            assertEquals(1, state.hesitated)
            assertTrue(state.training(queue[0]))
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
    fun finalAnswerKeepsUndoWithItsPreviousHistory() {
        val history = listOf(HifzCheck(98, HifzGrade.Held, 1_000), HifzCheck(99, HifzGrade.Hesitated, 2_000))
        val original = point(36).copy(step = 2, dueDay = 100, slips = 4, lastSlipAt = 900, checks = history)
        val queue = listOf(original)
        val state = opened(queue)
        answer(state, queue, HifzGrade.Held)

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
    fun olderUndoSnapshotsWithoutHistoryStillDecode() {
        val given = HifzGiven.decode("36|Hesitated|2|100||0|4|900")
        assertEquals(36L, given?.id)
        assertTrue(given!!.checks.isEmpty())
    }

    @Test
    fun exposureDuringTheCurrentHiddenAttemptCountsAsHelp() {
        val queue = listOf(point(36), point(37))
        val state = opened(queue)
        state.noteExposed(setOf(queue[0].verse, queue[1].verse), actions)
        assertTrue(state.assisted(queue[0]))
        answer(state, queue, HifzGrade.Held)
        assertEquals(listOf("grade 36 Hesitated"), calls.filter { it.startsWith("grade ") })
        assertEquals(1, exposures.size)
    }

    @Test
    fun aPointWhoseVerseAnEarlierAnswerShowed_waitsForTheNextReminder() {
        // 37 is the look-alike 36 was confused with: 36's answer shows it.
        val all = listOf(point(36), point(37), point(38))
        val state = opened(all)
        state.reveal(state.questions(all), actions)
        state.noteExposed(setOf(all[1].verse), actions)
        shown(state, state.questions(all))
        state.grade(state.questions(all), actions, HifzGrade.Held)

        assertEquals(setOf(37L), state.deferredIds)
        val asked = state.questions(all)
        assertEquals(listOf(all[0], all[2]), asked)
        assertEquals(2, state.count(asked))
        state.requestEnd(asked, actions)
        assertEquals("the stopped review counts only what it will still ask", 1, state.unanswered(asked))
        assertEquals(all[2], state.point(asked))
        assertFalse("the next card starts hidden", state.revealed(state.point(asked)))
        // Its answer is still recorded as given: no answer is ever practice only.
        assertEquals(listOf("grade 36 Held"), calls.filter { it.startsWith("grade ") })
        // Kept as the activity is re-created.
        assertEquals(setOf(37L), saved(state).deferredIds)
    }

    @Test
    fun aSkip_countsOutTheVersesItsAnswerShows_beforeItIsShown() {
        val all = listOf(point(36, HifzKind.Skip).copy(other = QuranVerseReference(41, 38)), point(37, HifzKind.Word), point(40, HifzKind.Word))
        val state = opened(all)
        assertEquals("its answer will show verse 37", 2, state.count(state.questions(all)))
    }

    @Test
    fun theCount_followsWhatEachCardStillToComeWillShow() {
        val lookAlike = point(20, HifzKind.Similar).copy(other = QuranVerseReference(41, 30))
        val all = listOf(point(10, HifzKind.Word), lookAlike, point(30, HifzKind.Word))
        val state = opened(all)
        // From the first card: the look-alike's answer will show verse 30, whatever this one's answer.
        assertEquals(2, state.count(state.questions(all)))
        assertEquals(2, state.unanswered(state.questions(all)))
    }

    @Test
    fun theCount_staysTheSameAsTheAnswerIsShown() {
        val all = listOf(point(20, HifzKind.Similar).copy(other = QuranVerseReference(41, 30)), point(30, HifzKind.Word))
        val state = opened(all)
        assertEquals(1, state.count(state.questions(all)))
        // «أظهر» notes the verse itself at once, its look-alike a moment later: the count does not jump meanwhile.
        state.reveal(state.questions(all), actions)
        assertEquals(1, state.count(state.questions(all)))
    }

    @Test
    fun aLastPointShownByTheAnswerBeforeIt_endsTheReview() {
        val all = listOf(point(36, HifzKind.Word), point(37, HifzKind.Word))
        val state = opened(all)
        assertEquals(2, state.count(state.questions(all)))
        state.reveal(state.questions(all), actions)
        state.noteExposed(setOf(all[1].verse), actions)
        // Already counted out: «تقييم المحاولة» is unnumbered, and the training's button leads to the result.
        assertEquals(1, state.count(state.questions(all)))
        shown(state, state.questions(all))
        state.grade(state.questions(all), actions, HifzGrade.Held)

        val asked = state.questions(all)
        assertEquals(listOf(all[0]), asked)
        assertTrue(state.atEnd(asked))
        assertEquals(1, state.deferredIds.size)
    }

    @Test
    fun aSpacedCheckSeenToday_andAFreshPointSeenAMomentAgo_areNotAsked() {
        val check = point(36)
        val fresh = point(37).copy(fresh = 2)
        val later = point(38).copy(fresh = 2)
        val all = listOf(check, fresh, later)
        // The check's verse was looked at today; the fresh point's answer was seen a minute ago, the other's long ago.
        val state = opened(all, state(exposed = setOf(check.verse), seen = mapOf(fresh.verse to wall - 60_000L, later.verse to wall - HifzFreshGapMs - 1)))
        val asked = state.questions(all)
        assertEquals(setOf(36L, 37L), state.deferredIds)
        assertEquals(listOf(later), asked)
        assertEquals(later, state.point(asked))
    }

    @Test
    fun noUndoOnceTheNextAnswerWasShown() {
        val queue = listOf(point(10, HifzKind.Word), point(20, HifzKind.Word))
        val state = opened(queue)
        answer(state, queue, HifzGrade.Held)
        assertEquals(10L, state.undoable(queue)?.id)
        shown(state, queue)
        // The next question's answer seen: that question was asked, and the grade before it stays.
        state.reveal(queue, actions)
        assertNull(state.undoable(queue))
    }

    @Test
    fun aFailedAnswerIsAlwaysRecorded() {
        val queue = listOf(point(36))
        // Looked at during the attempt, before «أظهر»: a helped attempt, still an answer.
        val state = opened(queue)
        state.noteExposed(setOf(queue[0].verse), actions)
        answer(state, queue, HifzGrade.Again)
        assertEquals(listOf("grade 36 Again"), calls.filter { it.startsWith("grade ") })
        assertEquals(1, state.day.again)
    }

    @Test
    fun theReviewIsSavedAndReadBackWhole() {
        val queue = listOf(point(36), point(37), point(38))
        val state = opened(queue)
        answer(state, queue, HifzGrade.Held)
        shown(state, queue)
        answer(state, queue, HifzGrade.Again)
        shown(state, queue)
        state.drill(queue[1], actions)
        state.folded = true
        state.howItWorks = true
        val back = saved(state)
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
        val fresh = saved(HifzReviewState(clock = { now }))
        assertEquals(queue[0], fresh.point(queue))
        assertNull(fresh.undoable(queue))
        assertEquals(0, fresh.graded)
    }
}
