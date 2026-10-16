package com.tunisianprayertimes.quran

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class QuranMushafTextTest {
    @Test
    fun wordsTakeTheMushafSpelling() {
        // Each rule at a word the page scans were read at (scripts/mushaf_text/mushaf_text.py).
        listOf(
            Triple("\u0623\u064E\u0644\u064E\u0653\u0645\u0651\u0650\u0653\u06D6", "\u0630\u064E\u0670\u0644\u0650\u0643\u064E", "\u0627\u0644\u0653\u0645\u0651\u0653"), // 2:1 opening letters
            Triple("\u0639\u064E\u0653\u0633\u0650\u0653\u0642\u064E\u0653\u06D6", "\u0643\u064E\u0630\u064E\u0670\u0644\u0650\u0643\u064E", "\u0639\u0653\u0633\u0653\u0642\u0653"), // 42:1 opening letters, three madds
            Triple("\u0637\u064E\u0647\u064E\u06D6", "\u0645\u064E\u0627", "\u0637\u0647"), // 20:1 opening letters, no madd
            Triple("\u0627\u064E\u06EC\u0644\u0652\u062D\u064E\u064A\u0651\u064F", "\u0627\u064F\u06EC\u0644\u0652\u0642\u064E\u064A\u0651\u064F\u0648\u0645\u064F\u06D6", "\u0671\u064E\u06EC\u0644\u0652\u062D\u064E\u0649\u0651\u064F"), // 2:253 wasl on U+0671
            Triple("\u0627\u0650\u06EC\u0644\u0652\u0623\u0653\u062E\u0650\u0631\u0650", "\u0648\u064E\u0645\u064E\u0627", "\u0671\u0650\u06EC\u0644\u0652\u0623\u064E\u062E\u0650\u0631\u0650"), // 2:7 wasl joining a kasra
            Triple("\u0627\u064F\u06DF\u0630\u0652\u0643\u064F\u0631\u064F\u0648\u0627\u0652", "\u0627\u064F\u06EC\u0644\u0644\u0651\u064E\u0647\u064E", "\u0671\u064F\u06DF\u0630\u0652\u0643\u064F\u0631\u064F\u0648\u0627\u0652"), // 33:41 wasl starting with a damma
            Triple("\u0627\u06EC\u064E\u0644\u064E\u0627", "\u0625\u0650\u0646\u0651\u064E\u0647\u064F\u0645\u0652", "\u0627\u06EC\u064E\u0644\u064E\u0627"), // 2:12 eased hamza keeps its alif
            Triple("\u0627\u06EA\u0650\u0644\u064E\u0649\u0670", "\u0635\u0650\u0631\u064E\u0670\u0637\u0656", "\u0627\u065C\u0650\u0644\u064E\u0649\u0670"), // 2:141 eased hamza, dot below
            Triple("\u0627\u0644\u0644\u064E\u0651\u0647\u0650", "\u0627\u0644\u0631\u064E\u0651\u062D\u0652\u0645\u064E\u0670\u0646\u0650", "\u0671\u0650\u06EC\u0644\u0644\u064E\u0651\u0647\u0650"), // 1:0 basmala article
            Triple("\u0625\u0650\u0646\u0651\u0650\u064A\u064E", "\u0623\u064E\u0639\u0652\u0644\u064E\u0645\u064F", "\u0625\u0650\u0646\u0651\u0650\u0649\u064E"), // 2:29 final ya with a fatha
            Triple("\u0648\u064E\u0644\u0650\u064A\u0651\u064C", "\u062D\u064E\u0645\u0650\u064A\u0645\u065E\u06D6", "\u0648\u064E\u0644\u0650\u0649\u0651\u064C"), // 41:33 final ya with a shadda
            Triple("\u0641\u064E\u0633\u064E\u0648\u0651\u064E\u064A\u0670\u0647\u064F\u0646\u0651\u064E", "\u0633\u064E\u0628\u0652\u0639\u064E", "\u0641\u064E\u0633\u064E\u0648\u0651\u064E\u0649\u0670\u0647\u064F\u0646\u0651\u064E"), // 2:28 ya with a small alif
            Triple("\u0647\u064F\u062F\u0649\u0657", "\u0644\u0651\u0650\u0644\u0652\u0645\u064F\u062A\u0651\u064E\u0642\u0650\u064A\u0646\u064E", "\u0647\u064F\u062F\u08F0\u0649"), // 2:1 tanween before alif maqsura
            Triple("\u0634\u064E\u064A\u0652\u0640\u0654\u0627\u0657", "\u0648\u064E\u0636\u064E\u0627\u0642\u064E\u062A\u0652", "\u0634\u064E\u064A\u0652\u0640\u08F0\u0654\u0627"), // 9:25 tanween on the tatweel
            Triple("\u0648\u064E\u0628\u0650\u0627\u0644\u0652\u0623\u0653\u062E\u0650\u0631\u064E\u0629\u0650", "\u0647\u064F\u0645\u0652", "\u0648\u064E\u0628\u0650\u0627\u0644\u0652\u0623\u064E\u062E\u0650\u0631\u064E\u0629\u0650"), // 2:3 madd on the hamza is a fatha
            Triple("\u0648\u064E\u0627\u0644\u0630\u0650\u064A\u0646\u064E", "\u064A\u064F\u0624\u0652\u0645\u0650\u0646\u064F\u0648\u0646\u064E", "\u0648\u064E\u0627\u0644\u0651\u064E\u0630\u0650\u064A\u0646\u064E"), // 2:3 relative pronoun
            Triple("\u0648\u064E\u0627\u0644\u0630\u0650\u064A\u0646\u064E", "\u0643\u064E\u0641\u064E\u0631\u064F\u0648\u0627\u0652", "\u0648\u064E\u0627\u0644\u0651\u064E\u0630\u0650\u064A\u0646\u064E"), // 5:11 prefixed relative pronoun
            Triple("\u0644\u0650\u0644\u0630\u0650\u064A\u0646\u064E", "\u0621\u064E\u0627\u0645\u064E\u0646\u064F\u0648\u0627\u0652", "\u0644\u0650\u0644\u0651\u064E\u0630\u0650\u064A\u0646\u064E"), // 41:43 relative after li
            Triple("\u0648\u064E\u0627\u0644\u064A\u0652\u0644\u0650", "\u0625\u0650\u0630\u064E\u0627", "\u0648\u064E\u0627\u0644\u0651\u064E\u064A\u0652\u0644\u0650"), // 93:2 al-layl
            Triple("\u0644\u0650\u0644\u0647\u0650", "\u0631\u064E\u0628\u0651\u0650", "\u0644\u0650\u0644\u0651\u064E\u0647\u0650"), // 1:1 Allah after li
            Triple("\u0648\u064E\u0644\u0650\u0644\u0647\u0650", "\u0645\u064E\u0627", "\u0648\u064E\u0644\u0650\u0644\u0651\u064E\u0647\u0650"), // 4:130 Allah after wa-li
            Triple("\u0623\u064E\u0646\u064E\u0627", "\u0639\u064E\u0627\u0628\u0650\u062F\u065E", "\u0623\u064E\u0646\u064E\u0627\u0652"), // 109:4 ana before a letter
            Triple("\u0623\u064E\u0646\u064E\u0627", "\u0627\u064E\u06EC\u0644\u0644\u0651\u064E\u0647\u064F", "\u0623\u064E\u0646\u064E\u0627"), // 20:13 ana before a wasl
            Triple("\u0623\u064E\u0646\u064E\u0627", "\u0623\u064F\u062D\u0652\u064A\u0650\u06E6", "\u0623\u064E\u0646\u064E\u0627"), // 2:257 ana before a hamza
            Triple("\u0645\u064E\u0646\u0652", "\u064A\u0651\u064E\u0642\u064F\u0648\u0644\u064F", "\u0645\u064E\u0646\u0652"), // 2:7 a nun merged with its ghunna keeps its sukun
            Triple("\u0623\u064E\u0628\u064E\u062F\u0627\u064E\u06E2", "\u0628\u0650\u0645\u064E\u0627", "\u0623\u064E\u0628\u064E\u062F\u064E\u06E2\u0627"), // 2:94 iqlab tanween on the letter before the alif
            Triple("\u0648\u064E\u0627\u0644\u0630\u064E\u0670\u0646\u0650", "\u064A\u064E\u0623\u0652\u062A\u0650\u064A\u064E\u0670\u0646\u0650\u0647\u064E\u0627", "\u0648\u064E\u0627\u0644\u0651\u064E\u0630\u064E\u0670\u0646\u0650"), // 4:16 the dual relative pronoun
            Triple("\u0644\u0651\u064E\u0670\u0643\u0650\u0646\u0651\u064E\u0627", "\u0647\u064F\u0648\u064E", "\u0644\u0651\u064E\u0670\u0643\u0650\u0646\u0651\u064E\u0627\u0652"), // 18:37 lakinna takes the silent mark like ana
            Triple("\u0639\u064E\u0635\u064E\u0648\u0627\u0652", "\u0648\u0651\u064E\u0643\u064E\u0627\u0646\u064F\u0648\u0627\u0652", "\u0639\u064E\u0635\u064E\u0648\u0627\u0652"), // 2:60 a silent alif keeps its mark before a merged letter
            Triple("\u0648\u064E\u0642\u064F\u0644\u0652", "\u0631\u0651\u064E\u0628\u0651\u0650", "\u0648\u064E\u0642\u064F\u0644"), // 17:80 merged lam
            Triple("\u0647\u064E\u0670\u0624\u064F\u0644\u064E\u0627\u0653\u06DF", "\u0625\u0650\u0646", "\u0647\u064E\u0670\u0624\u064F\u0644\u064E\u0627\u0653\u06DF"), // 2:30 dropped hamza on the line
            Triple("\u0623\u064E\u0670\u06DF\u0630\u064E\u0627", "\u0643\u064F\u0646\u0651\u064E\u0627", "\u0623\u064E\u0670\u06DF\u0630\u064E\u0627"), // 13:5 eased hamza on the line
            Triple("\u0623\u064E\u0670\u0648\u0652\u06DF\u0646\u064E\u0628\u0651\u0650\u064A\u0654\u064F\u0643\u064F\u0645", "\u0628\u0650\u062E\u064E\u064A\u0652\u0631\u0656", "\u0623\u064E\u0670\u0648\u06EC\u0646\u064E\u0628\u0651\u0650\u0626\u064F\u0643\u064F\u0645"), // 3:15 eased hamza on a waw
            Triple("\u0627\u06DF\u064E\u0644\u0650\u0647\u064E\u0629\u0657", "\u0645\u0651\u064E\u0627", "\u0627\u06DF\u064E\u0644\u0650\u0647\u064E\u0629\u08F0"), // 21:98 eased hamza before an alif
            Triple("\u0639\u064F\u0645\u0652\u064A\u065E", "\u0641\u064E\u0647\u064F\u0645\u0652", "\u0639\u064F\u0645\u0652\u0649\u08F1"), // 2:17 open tanween
            Triple("\u0621\u064E\u0670\u0627\u06EC\u0646\u0630\u064E\u0631\u0652\u062A\u064E\u0647\u064F\u0645\u0652", "\u0623\u064E\u0645\u0652", "\u0621\u064E\u0670\u0627\u06EC\u0646\u0630\u064E\u0631\u0652\u062A\u064E\u0647\u064F\u0645\u0652"), // 2:5 tas-hil dot
        ).forEach { (raw, next, expected) -> assertEquals(raw, expected, QuranMushafText.word(raw, next)) }
    }

    @Test
    fun plainTextDropsWhatOnlyTheMushafFontDraws() {
        assertEquals("\u0671\u0644\u0652", QuranMushafText.plain("\u0671\u064E\u06EC\u0644\u0652"))
        assertEquals("\u0623\u064E\u0670\u0630", QuranMushafText.plain("\u0623\u064E\u0670\u06DF\u0630"))
    }

    @Test
    fun wholeTextIsTheReferences() {
        // The conversion of every entry of the bundled text, as scripts/mushaf_text/mushaf_text.py prints it. After a
        // deliberate rule change, change both, read the words that change on the page scans, and update the hash.
        val index = JSONObject(File("src/main/assets/quran/index.json").readText()).getJSONArray("entries")
        val converted = (0 until index.length()).joinToString("\n") { QuranMushafText.text(index.getJSONObject(it).getString("text")) }
        val sha = MessageDigest.getInstance("SHA-256").digest(converted.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("cae13804a6d6d10c8b25769876bb06b1bf5ab4c759bbd1ba0837bf4ed26b7f7f", sha)
    }

    @Test
    fun aVerseKeepsItsWordsOneForOne() {
        val index = JSONObject(File("src/main/assets/quran/index.json").readText()).getJSONArray("entries")
        for (k in 0 until index.length()) {
            val tokens = index.getJSONObject(k).getString("text").split(' ')
            assertEquals(tokens.size, QuranMushafText.words(tokens).size)
        }
    }
}
