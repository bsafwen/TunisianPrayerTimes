package com.tunisianprayertimes.quran.audio

import com.tunisianprayertimes.quran.hifz.HifzReminder
import com.tunisianprayertimes.quran.quranAyah
import com.tunisianprayertimes.quran.quranVerseSentence

/** Every verse of a surah of [verseCount] verses, from the first: «سورة … كلها», its opening repeated with it. */
fun QuranRepeatRange.isWholeSurah(verseCount: Int?): Boolean =
    from.surah == to.surah && from.ayah == 1 && verseCount != null && to.ayah == verseCount

/** What the listener chose to repeat, as «ماذا تريد أن تكرّر؟» offers it. */
enum class QuranRepeatScope { None, Surah, Verse, Passage }

/**
 * What repeats now: verses under way say themselves, as the surah when they are the whole surah «سورة … كلها» repeats;
 * otherwise the choice kept for every recitation, the whole surah or each verse. A drill repeats nothing of the
 * listener's own. A whole surah chosen as verses, from the page or by numbers, stays chosen verses: it is not kept.
 */
fun quranRepeatScope(state: QuranPlaybackState, listening: QuranListening, verseCount: (Int) -> Int?): QuranRepeatScope {
    val range = state.repeat?.takeIf { state.playlist == null }
    return when {
        range != null -> if (listening.surahTimes != 1 && range.isWholeSurah(verseCount(range.from.surah))) QuranRepeatScope.Surah
            else QuranRepeatScope.Passage
        listening.surahTimes != 1 -> QuranRepeatScope.Surah
        listening.verseTimes != 1 -> QuranRepeatScope.Verse
        else -> QuranRepeatScope.None
    }
}

/** «مرة واحدة», «مرتين», «3 مرات», «11 مرة»: how many times, after a verb. */
fun quranTimes(times: Int): String = when {
    times == 1 -> "مرة واحدة"
    times == 2 -> "مرتين"
    times % 100 in 3..10 -> "$times${HifzReminder.NB}مرات"
    else -> "$times${HifzReminder.NB}مرة"
}

/**
 * The verses of [range] as a sentence names them: «سورة الملك», «الآية الثامنة», «الآيات من 8 إلى 12»; two verses are
 * «الآيتان 4 و5» as the subject of a verb ([subject]) and «الآيتين 4 و5» after «تكرار».
 */
fun quranRepeatObject(range: QuranRepeatRange, subject: Boolean, surahName: (Int) -> String, verseCount: (Int) -> Int?): String = when {
    range.isWholeSurah(verseCount(range.from.surah)) -> "سورة ${surahName(range.from.surah)}"
    range.from.surah != range.to.surah ->
        "الآيات من ${quranVerseSentence(surahName(range.from.surah), range.from.ayah)} إلى ${quranVerseSentence(surahName(range.to.surah), range.to.ayah)}"
    range.from == range.to -> quranAyah(range.from.ayah)
    range.to.ayah == range.from.ayah + 1 -> (if (subject) "الآيتان" else "الآيتين") + " ${range.from.ayah} و${range.to.ayah}"
    else -> "الآيات من ${range.from.ayah} إلى ${range.to.ayah}"
}

/**
 * The repetition under way, as the dock, the player and the lock screen say it: «تتكرّر سورة الملك بلا توقّف»,
 * «تتكرّر الآيات من 8 إلى 12 · المرة 2 من 3», «كل آية 3 مرات · المرة 2»; null when nothing repeats.
 */
fun quranRepeatSentence(state: QuranPlaybackState, listening: QuranListening, surahName: (Int) -> String, verseCount: (Int) -> Int?): String? {
    if (state.playlist != null) return null
    val range = state.repeat
    if (range != null) {
        val what = quranRepeatObject(range, subject = true, surahName, verseCount)
        return "تتكرّر $what" + (range.times?.let { " · المرة ${state.repeatRound.coerceAtLeast(1)} من $it" } ?: " بلا توقّف")
    }
    // «سورة … كلها» kept, with no surah repeating yet (loading) or its counted passes over: it is for every surah.
    if (listening.surahTimes != 1) {
        return if (listening.surahTimes == 0) "كل سورة تتكرّر بلا توقّف" else "كل سورة تُتلى ${quranTimes(listening.surahTimes)}"
    }
    return when (listening.verseTimes) {
        1 -> null
        0 -> "تتكرّر الآية حتى تنتقل إلى غيرها"
        else -> "كل آية ${quranTimes(listening.verseTimes)}" + (state.verseRound.takeIf { it > 0 }?.let { " · المرة $it" } ?: "")
    }
}

/** «انتهى تكرار سورة الملك»: a counted repetition recited to its end. */
fun quranRepeatEnded(range: QuranRepeatRange, surahName: (Int) -> String, verseCount: (Int) -> Int?): String =
    "انتهى تكرار ${quranRepeatObject(range, subject = false, surahName, verseCount)}"

/** Two verses of one surah, which the verbs about them take in the dual: «تُليتا», «أعِدهما». */
fun QuranRepeatRange.isTwoVerses(verseCount: (Int) -> Int?): Boolean =
    from.surah == to.surah && to.ayah == from.ayah + 1 && !isWholeSurah(verseCount(from.surah))
