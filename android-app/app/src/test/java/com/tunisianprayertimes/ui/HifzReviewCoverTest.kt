package com.tunisianprayertimes.ui

import com.tunisianprayertimes.quran.QuranVerseReference
import org.junit.Assert.assertEquals
import org.junit.Test

class HifzReviewCoverTest {
    private fun verse(ayah: Int) = QuranVerseReference(2, ayah)
    private val page = (1..10).map(::verse)

    @Test
    fun beforeShowTheAnswerAndItsContinuationStayCovered() {
        assertEquals((3..10).map(::verse).toSet(), hidden(3, false, setOf(verse(7))))
    }

    @Test
    fun showStopsBeforeTheSecondQuestionAndKeepsTheRestOfThePageCovered() {
        assertEquals((7..10).map(::verse).toSet(), hidden(3, true, setOf(verse(7))))
    }

    @Test
    fun theNextQuestionStartsCoveredThenItsShowRevealsTheRemainingPage() {
        assertEquals((7..10).map(::verse).toSet(), hidden(7, false, emptySet()))
        assertEquals(emptySet<QuranVerseReference>(), hidden(7, true, emptySet()))
    }

    @Test
    fun theEarliestFollowingQuestionSetsTheBoundaryRegardlessOfQueueOrder() {
        assertEquals((5..10).map(::verse).toSet(), hidden(3, true, linkedSetOf(verse(8), verse(5))))
    }

    @Test
    fun aQuestionOnAnotherPageDoesNotCoverThisPagesContinuation() {
        assertEquals(emptySet<QuranVerseReference>(), hidden(3, true, setOf(verse(12))))
    }

    @Test
    fun earlierUnansweredNotebookQuestionsStayCoveredWithoutHidingTheCurrentAnswer() {
        assertEquals(setOf(verse(2)) + (7..10).map(::verse), hidden(3, true, setOf(verse(2), verse(7))))
    }

    @Test
    fun theBoundaryAlsoWorksWhenThePageCrossesSurahs() {
        val acrossSurahs = listOf(QuranVerseReference(113, 5)) + (1..6).map { QuranVerseReference(114, it) }
        assertEquals(
            (2..6).map { QuranVerseReference(114, it) }.toSet(),
            hifzReviewHiddenVerses(acrossSurahs, acrossSurahs.first(), true, setOf(QuranVerseReference(114, 2))),
        )
    }

    private fun hidden(asked: Int, revealed: Boolean, upcoming: Set<QuranVerseReference>) =
        hifzReviewHiddenVerses(page, verse(asked), revealed, upcoming)
}
