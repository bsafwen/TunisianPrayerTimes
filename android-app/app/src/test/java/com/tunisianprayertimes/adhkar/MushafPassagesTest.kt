package com.tunisianprayertimes.adhkar

import com.tunisianprayertimes.mosque.DisplayTexts
import com.tunisianprayertimes.quran.QuranMushafText
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MushafPassagesTest {
    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun textsAreTheGeneratedOnes() {
        assertEquals(MushafPassages.SHA256.keys, MushafPassages.ids)
        MushafPassages.ids.forEach { id -> assertEquals(id, MushafPassages.SHA256[id], sha256(MushafPassages.text(id)!!)) }
    }

    @Test
    fun everyQuranTextOutsideTheQuranTabHasItsMushafText() {
        val adhkar = DhikrClosingTexts.REWARDS.filter { it.quran }.map { it.id } +
            listOf("ayat_kursi", "sleep_last_two_baqarah", "surah_ikhlas", "surah_falaq", "surah_nas")
        val display = DisplayTexts.PRAYER_VERSES.map { it.id } + listOf(DisplayTexts.FRIDAY_VERSE.id, DisplayTexts.RAMADAN_VERSE.id,
            DisplayTexts.BASMALA.id)
        val remembrance = DeathRemembranceTexts.ALL.filter { it.quran }.map { it.id }
        assertEquals((adhkar + display + remembrance).toSet(), MushafPassages.ids)
        adhkar.filter { it.startsWith("surah_") }.forEach { id ->
            assertTrue(id, MushafPassages.text(id)!!.contains('\n'))
            assertTrue(id, '\n' !in MushafPassages.verses(id)!!)
        }
    }

    @Test
    fun verseNumbersAreWesternAndStayWithTheirVerse() {
        val catalog = DhikrCatalog.entries.associateBy { it.id }
        MushafPassages.ids.forEach { id ->
            val text = MushafPassages.text(id)!!
            assertTrue(id, text.none { it.code in 0x0660..0x0669 || it.code in 0x06F0..0x06F9 })
            // Every ornament is bound to the word before it, so a line never starts with one.
            assertTrue(id, Regex("[^\u00A0]\u06DD").find(text) == null)
            // The catalog's own wording numbers the same verses.
            catalog[id]?.let { entry ->
                val numbers = { s: String -> Regex("\u06DD(\\d+)").findAll(s).map { it.groupValues[1] }.toList() }
                assertEquals(id, numbers(entry.text), numbers(text))
            }
        }
    }

    @Test
    fun wordsAreTheQuranTabsWords() {
        // Each passage, without its waqf signs and verse numbers, is word for word what the Quran tab prints for the
        // same words of the bundled text: one conversion for the whole app.
        val index = JSONObject(File("src/main/assets/quran/index.json").readText()).getJSONArray("entries")
        val verses = mutableMapOf<Pair<Int, Int>, String>()
        for (k in 0 until index.length()) {
            val e = index.getJSONObject(k)
            val key = e.getInt("surah") to e.getInt("ayah")
            verses[key] = listOfNotNull(verses[key], e.getString("text")).joinToString(" ")
        }
        MushafPassages.ids.forEach { id ->
            val expected = MushafPassages.SOURCES[id]!!.split(' ').flatMap { run ->
                val (surah, ayah, range) = run.split(':')
                val (from, to) = range.split('-').map(String::toInt)
                val tokens = verses[surah.toInt() to ayah.toInt()]!!.split(' ')
                QuranMushafText.words(tokens).subList(from, to + 1)
            }
            val shown = MushafPassages.text(id)!!.split(' ', '\n')
                .map { it.replace(Regex("\u00A0\u06DD[0-9]+$"), "").replace(Regex("[\u06D6\u06D7\u06DA]$"), "") }
            assertEquals(id, expected, shown)
        }
    }
}
