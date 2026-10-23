package com.tunisianprayertimes

import com.tunisianprayertimes.adhkar.DeathRemembranceTexts
import com.tunisianprayertimes.adhkar.MushafPassages
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeathRemembranceTextsTest {
    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Source-checked hadith wording and marked excerpts (docs/adhkar-sources.md, «ذكر الموت»). */
    private val hadithSha = mapOf(
        "tirmidhi_2307" to "9113741ca42979ae45113629f5f64a515010e82aa0a72b8d0a7f1b671b8d910a",
        "muslim_1844" to "2089494de3616144a1e1c201ad4cb2836cf23670ad0357a385b98f201bbafa66",
        "bukhari_2449" to "662be3ce7d545014cd847eb6cac3cdd469c68cfa42f2f1f223ad649680bbb629",
        "ibnmajah_4171" to "c70b7ee8e29b54bd518933d5ba6e4ec9c55a9690a25a48e5c181edab3038da40",
        "ibnmajah_4259" to "a81de78cb9c6f1fc86cebb1f6fdbf6b59b90ac6a1dd24ba583fe3cadbf8a8f9f",
        "bukhari_6416" to "1ec4b99ec7fc95912b1eb209107c57ca85ec9cddf01c7aab1dc183bb8da07232",
        "muslim_1032" to "75d7320878b9d7ddcab7cbc6520fbca11b491568295aef5cf955659aef4b3d1a",
        "muslim_2960" to "42729d2c21af6107c1702373c94e4fe6906981d22220b641daab8418072f8239",
        "muslim_1631" to "427adf653bceb616008c286343835d169f0b6d29f4732828dba422717ca0ac67",
        "bukhari_2738" to "47756dd6cded1db5ea86a05191e2c88d2ace8404e6656fc6414f67807ead262d",
        "muslim_2759" to "71eb9b222723b2da9f6f890ebeea86e55dad11352aa6cf4b8cc7798e6d469a12",
        "muslim_2877" to "33b23b795195e442b3d2ddd10cc5f8e7658f72598aced444de3f3912adfafbad",
        "bukhari_6412" to "d7688aae0c68990a7677c312415a5a93678b4000a5db9e5e499089f0dae70f27",
        "muslim_963" to "ddda4e20c26759d5756fd23c0ff874a58235d9bd9d88bcaa754dc38883edf55e",
    )

    @Test
    fun hadithAreTheSourceWordsAndVersesAreTheMushafs() {
        val all = DeathRemembranceTexts.ALL
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(hadithSha.keys, all.filter { !it.quran }.map { it.id }.toSet())
        all.filter { !it.quran }.forEach { assertEquals(hadithSha[it.id], sha256(it.text), it.id) }
        all.filter { it.quran }.forEach {
            assertEquals("", it.text, it.id)
            assertNotNull(MushafPassages.text(it.id), it.id)
        }
    }

    @Test
    fun aVerseAndAHadithInTurnEveryDay() {
        val all = DeathRemembranceTexts.ALL
        (all + all.first()).zipWithNext().forEach { (a, b) -> assertTrue(a.quran != b.quran, "${a.id} ${b.id}") }
        assertEquals(all, all.indices.map { DeathRemembranceTexts.forDay(1_000L * all.size + it) })
        // The notification's title: the prophetic words alone, as the source vowels them.
        val bare = DeathRemembranceTexts.anchorWords.filterNot { it in 'ً'..'ْ' }
        assertEquals("أكثروا ذكر هاذم اللذات", bare)
    }

    @Test
    fun wordsAreWrittenTheAppsWay() {
        DeathRemembranceTexts.ALL.forEach { item ->
            val written = item.reference + item.deed + item.text + item.title + item.reflection.orEmpty() + item.sourceNote.orEmpty()
            assertTrue(written.none { it in '٠'..'٩' || it in '۰'..'۹' }, "${item.id}: Western digits only")
            assertTrue('ـ' !in written, "${item.id}: no tatweel")
            assertTrue(item.deed.endsWith("."), "${item.id}: the deed is a sentence")
            assertTrue(item.reference.isNotBlank(), item.id)
            assertTrue(item.title.isNotBlank(), item.id)
            item.reflection?.let { assertTrue(it.isNotBlank() && it.endsWith("؟"), item.id) }
            if (!item.quran) assertTrue(item.sourceUrl?.startsWith("https://") == true, item.id)
        }
    }
}
