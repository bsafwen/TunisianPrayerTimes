package com.tunisianprayertimes.quran

// How the app names a verse (owner, 21 October): in a sentence as people say it, «الآية الثانية من سورة الملك», the
// number in words up to the ninth verse and in digits from the tenth; in a title, a row or a label, «الملك · الآية 2».
// Several verses keep their digits («الآيات من 2 إلى 5»), so that one sentence never mixes words and digits.

/** A verse from 1 to 9 as a sentence counts it, «الأولى» to «التاسعة»; from 10 on, its number. */
fun quranAyahOrdinal(ayah: Int): String = AyahOrdinals.getOrNull(ayah - 1) ?: "$ayah"

/** Feminine, as «الآية» they qualify. */
private val AyahOrdinals = listOf("الأولى", "الثانية", "الثالثة", "الرابعة", "الخامسة", "السادسة", "السابعة", "الثامنة", "التاسعة")

/** «الآية الثانية», «الآية 12»: one verse named in a sentence, its surah clear around it. */
fun quranAyah(ayah: Int): String = "الآية ${quranAyahOrdinal(ayah)}"

/**
 * «الآية الثانية», «الآيتين 4 و5», «الآيات من 4 إلى 7»: verses [from] to [to] of one surah named in a sentence, as its
 * object; several keep their digits.
 */
fun quranAyahSpan(from: Int, to: Int): String = when {
    to <= from -> quranAyah(from)
    to == from + 1 -> "الآيتين $from و$to"
    else -> "الآيات من $from إلى $to"
}

/** «الآية الثانية من سورة الملك», «الآية 12 من سورة الملك»: one verse named in a sentence. */
fun quranVerseSentence(surahName: String, ayah: Int): String = "${quranAyah(ayah)} من سورة $surahName"

/** «الملك · الآية 2»: one verse named in a title, a row or a label. */
fun quranVerseLabel(surahName: String, ayah: Int): String = "$surahName · الآية $ayah"
