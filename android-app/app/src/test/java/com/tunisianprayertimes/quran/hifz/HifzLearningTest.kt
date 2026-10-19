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
    fun theDaysExposureSurvivesReloadAndExpiresAtMidnight() {
        val context = RuntimeEnvironment.getApplication()
        val verse = QuranVerseReference(2, 17)
        HifzLearning(100, exposed = setOf(verse)).write(context)

        assertEquals(setOf(verse), HifzLearning.read(context, 100).exposed)
        assertTrue(HifzLearning.read(context, 101).exposed.isEmpty())
    }

    @Test
    fun backgroundAudioSurvivesAStaleUiSnapshot() {
        val context = RuntimeEnvironment.getApplication()
        val seen = QuranVerseReference(2, 17)
        val heard = QuranVerseReference(2, 18)
        val turned = QuranVerseReference(2, 19)
        val ui = HifzLearning(100, exposed = setOf(seen))
        ui.write(context)
        HifzLearning.recordExposure(context, heard, 100)

        val merged = ui.expose(setOf(turned)).write(context)
        assertEquals(setOf(seen, heard, turned), merged.exposed)
        assertEquals(setOf(heard, turned), merged.seen.keys)
        assertEquals(merged, HifzLearning.read(context, 100))
    }

    @Test
    fun theTrainingListsThatAreNoMoreAreDeletedFromStorage() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("hifz_learning", Context.MODE_PRIVATE)
        prefs.edit().putLong("day", 100).putStringSet("pending", setOf("7")).putStringSet("practised", setOf("8")).commit()

        HifzLearning.read(context, 100).expose(setOf(QuranVerseReference(2, 17))).write(context)
        assertFalse(prefs.contains("pending"))
        assertFalse(prefs.contains("practised"))
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
        val yesterday = HifzLearning(100, exposed = setOf(yesterdayVerse))
        yesterday.write(context)
        val today = HifzLearning.recordExposure(context, todayVerse, 101)
        assertEquals(setOf(todayVerse), today.exposed)
        assertEquals(today, yesterday.write(context))
        assertEquals(today, HifzLearning.recordExposure(context, yesterdayVerse, 100))
        assertEquals(today, HifzLearning.read(context, 101))
    }
}
