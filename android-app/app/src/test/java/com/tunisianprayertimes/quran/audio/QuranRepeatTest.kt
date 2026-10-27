package com.tunisianprayertimes.quran.audio

import com.tunisianprayertimes.quran.QuranVerseReference
import org.json.JSONArray
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
    fun window_endsAtItsLastVersesStop_beforeTheNextVerseIsHeard() {
        // The next verse starts at 20 000 ms, its first sound a little later; verse 1 stops in the pause before it.
        val track = QuranSurahRecording(
            4, "quran/audio/test/4.mp3", 50_000L,
            listOf(
                QuranAyahTiming(1, 5_000L, 20_000L, stopMs = 19_400L),
                QuranAyahTiming(2, 20_000L, 30_000L, stopMs = 29_500L),
                QuranAyahTiming(3, 30_000L, 45_000L),
            ),
        )
        val window = track.repeatWindow(range(4, 1, 4, 1))

        assertEquals(QuranRepeatWindow(5_000L, 19_400L), window)
        assertEquals(19_399L, window!!.clamp(19_900L))
        // The stop is still the verse's own time: the verse after starts where it did.
        assertEquals(1, track.ayahAt(19_700L))
        assertEquals(20_000L, track.startOf(2))
        assertEquals(QuranRepeatWindow(5_000L, 29_500L), track.repeatWindow(range(4, 1, 4, 2)))
        // Without a stop, a verse stops at its end, here the end of the last verse.
        assertEquals(QuranRepeatWindow(30_000L, 45_000L), track.repeatWindow(range(4, 3, 4, 3)))
    }

    @Test
    fun timingsFile_readsStopsAndRefusesOnesOutsideTheirVerse() {
        fun file(stop: String) = JSONObject()
            .put("version", 1).put("reciterId", "test-qaloun").put("numbering", "madani-later")
            .put("surahs", JSONArray(List(114) { index ->
                val n = index + 1
                JSONObject().put("number", n).put("assetPath", "quran/audio/test/$n.mp3").put("durationMs", 10_000L)
                    .put("timings", JSONArray(listOf(
                        JSONObject("""{"ayah":1,"startMs":1000,"endMs":5000$stop}"""),
                        JSONObject("""{"ayah":2,"startMs":5000,"endMs":9000}"""),
                    )))
            }))
            .toString()

        val read = QuranRecitationTimings.parse(file(""","stopMs":4600"""), "test-qaloun")
        assertEquals(4_600L, read[0].stopOf(1))
        assertEquals(9_000L, read[0].stopOf(2))
        assertEquals(5_000L, QuranRecitationTimings.parse(file(""), "test-qaloun")[0].stopOf(1))
        for (bad in listOf(""","stopMs":5001""", ""","stopMs":1000""", ""","stopMs":0""")) {
            assertTrue(bad, runCatching { QuranRecitationTimings.parse(file(bad), "test-qaloun") }.isFailure)
        }
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
    fun wholeSurah_startsEachPassAtItsOpening() {
        // Chapter 3 has two verses, its first after a 5-second opening.
        val whole = range(3, 1, 3, 2)
        assertTrue(whole.isWholeSurah(2))
        assertEquals(QuranRepeatWindow(0L, 35_000L), third.repeatWindow(whole))
        // Verse 1 alone is a chosen verse, not the surah: no opening.
        assertFalse(range(3, 1, 3, 1).isWholeSurah(2))
        assertEquals(QuranRepeatWindow(5_000L, 20_000L), third.repeatWindow(range(3, 1, 3, 1)))
    }

    @Test
    fun counts_agreeWithTheirNumber() {
        assertEquals("مرة واحدة", quranTimes(1))
        assertEquals("مرتين", quranTimes(2))
        assertEquals("3 مرات", quranTimes(3))
        assertEquals("10 مرات", quranTimes(10))
        assertEquals("11 مرة", quranTimes(11))
        assertEquals("100 مرة", quranTimes(100))
        assertEquals("103 مرات", quranTimes(103))
    }

    @Test
    fun scope_aPassageUnderWayFirst_thenTheChoiceKept() {
        val verses = { _: Int -> 2 }
        val whole = QuranPlaybackState(surah = 3, repeat = range(3, 1, 3, 2))
        val passage = QuranPlaybackState(surah = 3, repeat = range(3, 2, 3, 2))
        val nothing = QuranPlaybackState(surah = 3)
        // The whole surah is «سورة … كلها» only while that choice is kept; chosen as verses, it stays verses.
        assertEquals(QuranRepeatScope.Surah, quranRepeatScope(whole, QuranListening(surahTimes = 0), verses))
        assertEquals(QuranRepeatScope.Passage, quranRepeatScope(whole, QuranListening(), verses))
        assertEquals(QuranRepeatScope.Passage, quranRepeatScope(passage, QuranListening(surahTimes = 0), verses))
        assertEquals(QuranRepeatScope.Surah, quranRepeatScope(nothing, QuranListening(surahTimes = 0), verses))
        assertEquals(QuranRepeatScope.Verse, quranRepeatScope(nothing, QuranListening(verseTimes = 3), verses))
        assertEquals(QuranRepeatScope.None, quranRepeatScope(nothing, QuranListening(), verses))
        // A drill repeats nothing of the listener's own.
        val drill = QuranPlaylist("تدريب", listOf(range(3, 2, 3, 2)), listOf("الآية"))
        assertEquals(QuranRepeatScope.None, quranRepeatScope(passage.copy(playlist = drill), QuranListening(), verses))
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

    /** Every verse of every bundled recitation can be repeated alone, inside its own file, up to its stop. */
    @Test
    fun everyBundledVerse_hasItsOwnWindow() {
        for ((folder, reciterId) in listOf("hosary" to "hosary-qaloun", "ayoub" to "ayoub-qaloun", "trabelsi" to "trabelsi-qaloun")) {
            val tracks = QuranRecitationTimings.parse(findAssetFile("app/src/main/assets/quran/audio/$folder/timings.json").readText(), reciterId)
            var verses = 0
            var stopsEarly = 0
            for (track in tracks) {
                track.timings.forEach { timing ->
                    val verse = QuranVerseReference(track.number, timing.ayah)
                    val window = track.repeatWindow(QuranRepeatRange(verse, verse))
                    assertNotNull("No window for $verse", window)
                    assertEquals(QuranRepeatWindow(timing.startMs, timing.stopMs), window)
                    assertTrue("$verse leaves its file", window!!.endMs <= track.durationMs)
                    assertEquals("$verse is not the verse heard at its own start", timing.ayah, track.ayahAt(window.startMs))
                    assertEquals("$verse stops outside itself", timing.ayah, track.ayahAt(window.endMs - 1L))
                    if (timing.stopMs < timing.endMs) stopsEarly++
                    verses++
                }
                // Nothing follows a surah's last verse in its file: it is heard to its end.
                assertEquals(track.timings.last().endMs, track.timings.last().stopMs)
            }
            assertEquals(6_214, verses)
            // The aligned reciters' verses end just before the next one's first sound; each was given a stop in its pause.
            if (folder != "hosary") assertTrue("$folder: only $stopsEarly stops", stopsEarly > 5_000)
        }
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
