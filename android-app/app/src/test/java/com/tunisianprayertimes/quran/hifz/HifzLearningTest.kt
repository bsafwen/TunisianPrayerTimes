package com.tunisianprayertimes.quran.hifz

import android.app.Application
import android.content.Context
import com.tunisianprayertimes.quran.QuranVerseReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HifzLearningTest {
    @Before
    fun clearLedger() {
        RuntimeEnvironment.getApplication().getSharedPreferences("hifz_learning", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun unfinishedPracticeSurvivesReloadAndDayChangeWhileExposureExpires() {
        val context = RuntimeEnvironment.getApplication()
        val verse = QuranVerseReference(2, 17)
        HifzLearning(100, pending = setOf(7, 8), exposed = setOf(verse), practised = setOf(7)).write(context)

        val restarted = HifzLearning.read(context, 100)
        assertEquals(setOf(7L, 8L), restarted.pending)
        assertEquals(setOf(verse), restarted.exposed)
        assertEquals(setOf(7L), restarted.practised)
        val tomorrow = HifzLearning.read(context, 101)
        assertEquals(restarted.pending, tomorrow.pending)
        assertTrue(tomorrow.exposed.isEmpty())
        assertTrue(tomorrow.practised.isEmpty())
    }

    @Test
    fun completionAndUndoKeepOtherPendingWorkAcrossReload() {
        val context = RuntimeEnvironment.getApplication()
        val original = HifzLearning(100, pending = setOf(7, 8), practised = setOf(8))
        original.pending(7, false).practised(7, true).write(context)
        val completed = HifzLearning.read(context, 100)
        assertEquals(setOf(8L), completed.pending)
        assertEquals(setOf(7L, 8L), completed.practised)
        completed.pending(7, 7L in original.pending).practised(7, 7L in original.practised).write(context)
        assertEquals(original, HifzLearning.read(context, 100))
    }

    @Test
    fun backgroundAudioSurvivesAStaleUiSnapshotWhileItsPracticeUpdateIsSaved() {
        val context = RuntimeEnvironment.getApplication()
        val seen = QuranVerseReference(2, 17)
        val heard = QuranVerseReference(2, 18)
        val ui = HifzLearning(100, pending = setOf(7, 8), exposed = setOf(seen), practised = setOf(8))
        ui.write(context)
        HifzLearning.recordExposure(context, heard, 100)

        val merged = ui.pending(7, false).practised(7, true).write(context)
        assertEquals(setOf(seen, heard), merged.exposed)
        assertEquals(setOf(8L), merged.pending)
        assertEquals(setOf(7L, 8L), merged.practised)
        assertEquals(merged, HifzLearning.read(context, 100))
    }

    @Test
    fun backgroundAudioPreservesTheLatestPracticeAndUndoChanges() {
        val context = RuntimeEnvironment.getApplication()
        val heard = QuranVerseReference(2, 18)
        HifzLearning(100, pending = setOf(7, 8), practised = setOf(8)).write(context)
        val afterAudio = HifzLearning.recordExposure(context, heard, 100)
        assertEquals(setOf(7L, 8L), afterAudio.pending)
        assertEquals(setOf(8L), afterAudio.practised)

        afterAudio.practised(8, false).write(context)
        val repeated = HifzLearning.recordExposure(context, heard, 100)
        assertTrue(repeated.practised.isEmpty())
        assertEquals(setOf(7L, 8L), repeated.pending)
        assertEquals(setOf(heard), repeated.exposed)
    }

    @Test
    fun whenAnAnswerWasSeen_isKeptAcrossMidnightForTheFreshPoints() {
        val context = RuntimeEnvironment.getApplication()
        val verse = QuranVerseReference(2, 17)
        val at = System.currentTimeMillis()
        HifzLearning(100).expose(setOf(verse), at).write(context)
        assertEquals(at, HifzLearning.read(context, 100).seen[verse])
        // The day's exposure goes at midnight; when it was seen stays, for a question a while after it.
        val tomorrow = HifzLearning.read(context, 101)
        assertTrue(tomorrow.exposed.isEmpty())
        assertEquals(at, tomorrow.seen[verse])
        // Seen again later: the later time; long ago: forgotten.
        val later = HifzLearning(100, seen = mapOf(verse to at)).expose(setOf(QuranVerseReference(2, 18)), at + HifzFreshGapMs + 1)
        assertNull(later.seen[verse])
        assertEquals(at + HifzFreshGapMs + 1, later.seen[QuranVerseReference(2, 18)])
    }

    @Test
    fun listeningPastMidnightExpiresYesterdayAndRejectsItsDelayedWrite() {
        val context = RuntimeEnvironment.getApplication()
        val yesterdayVerse = QuranVerseReference(2, 17)
        val todayVerse = QuranVerseReference(2, 18)
        val yesterday = HifzLearning(100, pending = setOf(7), exposed = setOf(yesterdayVerse), practised = setOf(8))
        yesterday.write(context)
        val today = HifzLearning.recordExposure(context, todayVerse, 101)
        assertEquals(setOf(7L), today.pending)
        assertEquals(setOf(todayVerse), today.exposed)
        assertTrue(today.practised.isEmpty())
        assertEquals(today, yesterday.write(context))
        assertEquals(today, HifzLearning.recordExposure(context, yesterdayVerse, 100))
        assertEquals(today, HifzLearning.read(context, 101))
    }
}
