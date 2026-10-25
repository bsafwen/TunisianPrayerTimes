package com.tunisianprayertimes.ui

import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.hifz.HifzCheck
import com.tunisianprayertimes.quran.hifz.HifzGrade
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzMistake
import com.tunisianprayertimes.quran.hifz.HifzPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HifzReviewFirstTest {
    private val today = 100L
    private val verse = QuranVerseReference(2, 67)
    private val other = QuranVerseReference(67, 2)
    private fun point(id: Long = 1L, due: Long = today) = HifzPoint(
        id = id, verse = verse, mistakes = listOf(HifzMistake(HifzKind.Word)),
        key = "", dueDay = due, createdAt = 0L, fresh = 0,
    )

    @Test
    fun reviewFirstProtectsOnlyEligibleAnswersOnThePageUntilReadingIsChosen() {
        val due = point()
        val future = point(2L, today + 1).copy(verse = other)
        assertEquals(listOf(due), hifzReadingGate(listOf(due, future), setOf(due.id), setOf(verse, other), emptySet()))
        assertTrue(hifzReadingGate(listOf(due), setOf(due.id), setOf(verse), setOf(due.id)).isEmpty())
        assertTrue(hifzReadingGate(listOf(due), setOf(due.id), setOf(other), emptySet()).isEmpty())
    }

    @Test
    fun lookAlikeAnswerOnAnotherPageAlsoOffersReviewFirst() {
        val due = point().copy(mistakes = listOf(HifzMistake(HifzKind.Similar, other)))
        assertEquals(listOf(due), hifzReadingGate(listOf(due), setOf(due.id), setOf(other), emptySet()))
    }

    @Test
    fun alreadyExposedQuestionCannotBeOfferedAsUnaidedByTheReadingGate() {
        val due = point()
        // Eligibility is supplied by the normal learning/exposure policy; the UI must not bypass that policy.
        assertTrue(hifzReadingGate(listOf(due), emptySet(), setOf(verse), emptySet()).isEmpty())
    }

    @Test
    fun deferredListKeepsOverdueSpacedChecksVisibleWithoutListingFutureOrFreshQuestions() {
        val old = point(1L, today - 3)
        val due = point(2L)
        val future = point(3L, today + 1)
        val fresh = point(4L).copy(fresh = 2)
        val unseen = point(5L).copy(verse = other)
        assertEquals(listOf(old, due), hifzExposureDeferred(listOf(due, future, fresh, old, unseen), today, setOf(verse)))
        assertTrue(hifzExposureDeferred(listOf(old), today + 1, emptySet()).isEmpty())
    }

    @Test
    fun goalCountsPersistedGradedQuestionsIncludingRepeatedAttemptsButNotExposure() {
        val checked = point().copy(checks = listOf(
            HifzCheck(today - 1, HifzGrade.Held, 1L),
            HifzCheck(today, HifzGrade.Again, 2L),
            HifzCheck(today, HifzGrade.Hesitated, 3L),
            HifzCheck(today, HifzGrade.Held, 4L),
        ))
        assertEquals(3, hifzGradedToday(listOf(checked, point(2L)), today))
        assertEquals(0, hifzGradedToday(listOf(checked), today + 1))
    }
}
