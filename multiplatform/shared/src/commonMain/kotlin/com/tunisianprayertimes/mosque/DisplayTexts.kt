package com.tunisianprayertimes.mosque

/**
 * The fixed texts a mosque screen shows beside the prayer times: a verse in its header, and the Eid
 * greeting. Reviewed like the adhkar (docs/adhkar-sources.md) and never editable by a mosque. Verse
 * numbers follow the Qaloon mushaf with the later Madani count, as the rest of the catalog does.
 */
object DisplayTexts {

    data class DisplayText(val id: String, val text: String, val reference: String)

    /** On ordinary days: the prayers have their appointed times. */
    val TIMES_VERSE = DisplayText("an_nisa_102", "إِنَّ الصَّلَاةَ كَانَتْ عَلَى الْمُؤْمِنِينَ كِتَابًا مَوْقُوتًا", "النساء 102")

    /** On Fridays: the call to the Jumu'a prayer. */
    val FRIDAY_VERSE = DisplayText("al_jumua_9", "فَاسْعَوْا إِلَىٰ ذِكْرِ اللَّهِ وَذَرُوا الْبَيْعَ", "الجمعة 9")

    /** In Ramadan. */
    val RAMADAN_VERSE = DisplayText("al_baqara_184", "شَهْرُ رَمَضَانَ الَّذِي أُنْزِلَ فِيهِ الْقُرْآنُ", "البقرة 184")

    /**
     * What the companions said to each other on Eid (Jubayr ibn Nufayr, in al-Mahamiliyyat; Ibn Hajar
     * graded its chain hasan in Fath al-Bari), in the plural, as the screen greets everyone.
     */
    val EID_GREETING = DisplayText(
        "eid_taqabbal",
        "تَقَبَّلَ اللَّهُ مِنَّا وَمِنْكُمْ",
        "تهنئة الصحابة يوم العيد: عن جبير بن نفير في المحامليات، وحسّن إسناده ابن حجر في فتح الباري",
    )

    val ALL: List<DisplayText> = listOf(TIMES_VERSE, FRIDAY_VERSE, RAMADAN_VERSE, EID_GREETING)

    /** The header's verse: Ramadan's all month, the Jumu'a verse on Fridays, otherwise the prayer times. */
    fun headerVerse(isRamadan: Boolean, isFriday: Boolean): DisplayText = when {
        isRamadan -> RAMADAN_VERSE
        isFriday -> FRIDAY_VERSE
        else -> TIMES_VERSE
    }
}
