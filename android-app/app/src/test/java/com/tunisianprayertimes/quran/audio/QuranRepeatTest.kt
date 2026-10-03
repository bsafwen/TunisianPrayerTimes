package com.tunisianprayertimes.quran.audio

import com.tunisianprayertimes.quran.QuranVerseReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QuranRepeatTest {
    // Chapter 1 as recorded, then two short chapters; the last verse of chapter 2 runs to the end of its file.
    private val fatiha = recording(1, 52_741L, 13_140L, 17_840L, 21_820L, 25_640L, 32_479L, 37_640L, 42_760L, 51_680L)
    private val second = recording(2, 30_000L, 6_000L, 14_000L, 22_000L, 30_000L)
    private val third = recording(3, 40_000L, 5_000L, 20_000L, 35_000L)

    @Test
    fun onePressedVerse_isAValidEndlessRange() {
        val verse = QuranVerseReference(1, 2)
        val range = QuranRepeatRange(verse, verse)

        assertTrue(range.isValid)
        assertNull(range.times)
        assertTrue(verse in range)
        assertFalse(QuranVerseReference(1, 1) in range)
        assertFalse(QuranVerseReference(1, 3) in range)
    }

    @Test
    fun rangeAcrossChapters_containsEveryVerseBetweenItsEnds() {
        val range = range(1, 6, 3, 1)

        assertTrue(range.isValid)
        assertFalse(QuranVerseReference(1, 5) in range)
        assertTrue(QuranVerseReference(1, 7) in range)
        assertTrue(QuranVerseReference(2, 3) in range)
        assertTrue(QuranVerseReference(3, 1) in range)
        assertFalse(QuranVerseReference(3, 2) in range)
    }

    @Test
    fun reversedEnds_basmalahAndBadCounts_areRejected() {
        assertFalse(range(2, 1, 1, 7).isValid)
        assertFalse(range(1, 5, 1, 4).isValid)
        assertFalse(range(1, 0, 1, 3).isValid)
        assertFalse(range(0, 1, 1, 3).isValid)
        assertFalse(range(1, 1, 115, 1).isValid)
        assertFalse(range(1, 1, 1, 3, times = 0).isValid)
        assertFalse(range(1, 1, 1, 3, times = QuranRepeatRange.MAX_TIMES + 1).isValid)
        assertTrue(range(1, 1, 1, 3, times = QuranRepeatRange.MAX_TIMES).isValid)
    }

    @Test
    fun singleVerseWindow_isExactlyThatVersesInterval() {
        assertEquals(QuranRepeatWindow(17_840L, 21_820L), fatiha.repeatWindow(range(1, 2, 1, 2)))
    }

    @Test
    fun windowAcrossChapters_keepsLaterOpeningsAndStopsAtTheLastVerse() {
        val range = range(1, 6, 3, 2)

        // From the first verse to the end of the file, closing silence included.
        assertEquals(QuranRepeatWindow(37_640L, 52_741L), fatiha.repeatWindow(range))
        assertEquals(QuranRepeatWindow(0L, 30_000L), second.repeatWindow(range))
        // The opening before verse 1 plays, the closing audio after verse 2 does not.
        assertEquals(QuranRepeatWindow(0L, 35_000L), third.repeatWindow(range))
    }

    @Test
    fun chapterOutsideTheRange_orAnUnknownVerse_hasNoWindow() {
        assertNull(third.repeatWindow(range(1, 1, 2, 2)))
        assertNull(fatiha.repeatWindow(range(2, 1, 3, 2)))
        assertNull(fatiha.repeatWindow(range(1, 2, 1, 8)))
        assertNull(third.repeatWindow(range(3, 4, 3, 4)))
    }

    @Test
    fun seeksAreKeptInsideTheWindow() {
        val window = QuranRepeatWindow(17_840L, 21_820L)

        assertEquals(17_840L, window.clamp(0L))
        assertEquals(20_000L, window.clamp(20_000L))
        // The end itself already belongs to the next verse.
        assertEquals(21_819L, window.clamp(21_820L))
        assertEquals(21_819L, window.clamp(50_000L))
    }

    @Test
    fun endlessRange_neverFinishes() {
        val range = range(1, 2, 1, 2)

        var round = 1
        repeat(500) {
            val step = range.afterPass(round)
            assertEquals(QuranRepeatStep.Again(round + 1), step)
            round = (step as QuranRepeatStep.Again).round
        }
    }

    @Test
    fun countedRange_finishesAfterItsLastPass() {
        val thrice = range(1, 2, 1, 4, times = 3)

        assertEquals(QuranRepeatStep.Again(2), thrice.afterPass(1))
        assertEquals(QuranRepeatStep.Again(3), thrice.afterPass(2))
        assertEquals(QuranRepeatStep.Finished, thrice.afterPass(3))
        assertEquals(QuranRepeatStep.Finished, range(1, 2, 1, 4, times = 1).afterPass(1))
    }

    @Test
    fun savedRepetition_survivesAServiceRestart() {
        val counted = range(2, 255, 3, 4, times = 7)
        val endless = range(1, 2, 1, 2)

        assertEquals(counted to 4, decodeQuranRepeat(counted.encode(4)))
        assertEquals(endless to 12, decodeQuranRepeat(endless.encode(12)))
    }

    @Test
    fun damagedSavedRepetition_isIgnored() {
        assertNull(decodeQuranRepeat(null))
        assertNull(decodeQuranRepeat(""))
        assertNull(decodeQuranRepeat("1:2:1:2:0"))
        assertNull(decodeQuranRepeat("1:2:1:x:0:1"))
        assertNull(decodeQuranRepeat("1:5:1:2:0:1"))
        assertNull(decodeQuranRepeat("1:2:1:2:0:0"))
        assertNull(decodeQuranRepeat("1:2:1:2:-3:1"))
    }

    /** Every verse of the bundled recitation can be repeated alone, inside its own file. */
    @Test
    fun everyBundledVerse_hasItsOwnWindow() {
        val source = JSONObject(findAssetFile("app/src/main/assets/quran/audio/hosary/timings.json").readText())
        val chapters = source.getJSONArray("surahs")
        var verses = 0
        for (index in 0 until chapters.length()) {
            val chapter = chapters.getJSONObject(index)
            val rows = chapter.getJSONArray("timings")
            val track = QuranSurahRecording(
                chapter.getInt("number"), chapter.getString("assetPath"), chapter.getLong("durationMs"),
                List(rows.length()) { row ->
                    rows.getJSONObject(row).let { QuranAyahTiming(it.getInt("ayah"), it.getLong("startMs"), it.getLong("endMs")) }
                },
            )
            track.timings.forEach { timing ->
                val verse = QuranVerseReference(track.number, timing.ayah)
                val window = track.repeatWindow(QuranRepeatRange(verse, verse))
                assertNotNull("No window for $verse", window)
                assertEquals(QuranRepeatWindow(timing.startMs, timing.endMs), window)
                assertTrue("$verse leaves its file", window!!.endMs <= track.durationMs)
                assertEquals("$verse is not the verse heard at its own start", timing.ayah, track.ayahAt(window.startMs))
                verses++
            }
        }
        assertEquals(6_214, verses)
    }

    private fun range(fromSurah: Int, fromAyah: Int, toSurah: Int, toAyah: Int, times: Int? = null) =
        QuranRepeatRange(QuranVerseReference(fromSurah, fromAyah), QuranVerseReference(toSurah, toAyah), times)

    /** [boundaries] are the first verse's start, then every verse's end; verses follow one another without gaps. */
    private fun recording(number: Int, durationMs: Long, vararg boundaries: Long) = QuranSurahRecording(
        number, "quran/audio/test/$number.mp3", durationMs,
        boundaries.toList().zipWithNext().mapIndexed { index, (start, end) -> QuranAyahTiming(index + 1, start, end) },
    )

    private fun findAssetFile(relativePath: String): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory ?: return@repeat, relativePath)
            if (candidate.exists()) return candidate
            directory = directory?.parentFile
        }
        error("Could not locate $relativePath")
    }
}
