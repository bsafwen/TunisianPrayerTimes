package com.tunisianprayertimes.adhkar

import com.tunisianprayertimes.quran.QuranMushafText
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MushafExplanationQuotesTest {
    @Test
    fun allRegisteredQuotesAreShownAndSurroundingProseIsUnchanged() {
        val counts = mapOf("surah_ikhlas" to 2, "surah_falaq" to 5, "surah_nas" to 2, "ayat_kursi" to 8, "subhan_count" to 1)
        assertEquals(counts.keys, MushafExplanationQuotes.ids)
        counts.forEach { (id, count) ->
            val explanation = DhikrCatalog.find(id)!!.explanation
            val quotes = MushafExplanationQuotes.quotes(id)
            assertEquals(id, count, quotes.size)
            quotes.forEach { quote ->
                assertEquals(id, 1, Regex(Regex.escape("«${quote.original}»")).findAll(explanation).count())
            }
            val parts = MushafExplanationQuotes.parts(id, explanation)
            assertEquals(id, quotes.map { it.text }, parts.filter { it.quran }.map { it.text })
            // Restoring only the generated fragments must reproduce every original prose character and quote mark.
            var nextQuote = 0
            val restored = parts.joinToString("") { if (it.quran) quotes[nextQuote++].original else it.text }
            assertEquals(id, explanation, restored)
        }
    }

    @Test
    fun fragmentsMatchTheQuranWordsAndAllMappedWaqfIncludingTheirEndpoints() {
        val index = JSONObject(File("src/main/assets/quran/index.json").readText()).getJSONArray("entries")
        val verses = mutableMapOf<Pair<Int, Int>, MutableList<String>>()
        for (i in 0 until index.length()) {
            val entry = index.getJSONObject(i)
            verses.getOrPut(entry.getInt("surah") to entry.getInt("ayah")) { mutableListOf() }
                .addAll(entry.getString("text").split(' '))
        }
        val waqf = JSONObject(File("src/main/assets/quran/waqf.json").readText())
        val signNames = waqf.getJSONObject("signs")
        val positions = waqf.getJSONObject("verses")
        MushafExplanationQuotes.ids.forEach { id ->
            MushafExplanationQuotes.quotes(id).forEach { quote ->
                val (surah, ayah, bounds) = quote.source.split(':')
                val (first, last) = bounds.split('-').map(String::toInt)
                val converted = QuranMushafText.words(verses.getValue(surah.toInt() to ayah.toInt()))
                val signs = mutableMapOf<Int, String>()
                positions.optJSONArray("$surah:$ayah")?.let { entries ->
                    for (i in 0 until entries.length()) {
                        val item = entries.getJSONArray(i)
                        signs[item.getInt(0)] = signNames.getString(item.getString(1))
                    }
                }
                val expected = (first..last).joinToString(" ") { converted[it] + signs[it].orEmpty() }
                assertEquals("$id ${quote.source}", expected, quote.text)
                val sha = MessageDigest.getInstance("SHA-256").digest(quote.text.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                assertEquals(id, quote.sha256, sha)
            }
        }
        val kursi = MushafExplanationQuotes.quotes("ayat_kursi")
        assertTrue(kursi[3].text.endsWith('\u06DA'))
        assertTrue(kursi[4].text.endsWith('\u06DA'))
        assertTrue(kursi[6].text.endsWith('\u06D6'))
        assertTrue(kursi[7].text.endsWith('\u06DA'))
    }

    @Test
    fun lexicalDefinitionsAndHadithQuotesDoNotBecomeQuranSpans() {
        val falaq = MushafExplanationQuotes.parts("surah_falaq", DhikrCatalog.find("surah_falaq")!!.explanation)
        val prose = falaq.filterNot { it.quran }.joinToString("") { it.text }
        assertTrue(prose.contains("«الغَسَق»"))
        assertTrue(prose.contains("«الوقوب»"))
        listOf("travel", "dhi_nun", "evening_refuge", "fatir_samawat").forEach { id ->
            val entry = DhikrCatalog.find(id)!!
            val parts = MushafExplanationQuotes.parts(id, entry.explanation)
            assertFalse(id, parts.any { it.quran })
            assertEquals(id, entry.explanation, parts.joinToString("") { it.text })
        }
        val unknown = "شرح مخصص «اللَّهُ الصَّمَدُ»"
        assertEquals(listOf(MushafExplanationQuotes.Part(unknown)), MushafExplanationQuotes.parts("custom", unknown))
    }
}
