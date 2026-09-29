package com.tunisianprayertimes.mosque

import java.time.LocalDate

/**
 * The fixed texts a mosque screen shows beside the prayer times: a verse in its header, the Eid
 * greeting, and the basmala of the first setup. Reviewed like the adhkar (docs/adhkar-sources.md) and
 * never editable by a mosque. Verse numbers follow the Qaloon mushaf with the later Madani count, as
 * the rest of the catalog does.
 */
object DisplayTexts {

    data class DisplayText(val id: String, val text: String, val reference: String)

    /**
     * On ordinary days, in turn: what the Quran says of the prayer (its fixed times, guarding it,
     * humility in it, what it does, commanding one's family to it, seeking help through it). Each was
     * checked word by word against the Qaloon text (docs/adhkar-sources.md); two keep Qaloon's reading
     * where Hafs differs, «لِذِكْرِيَ» and «يَا بُنَيِّ». Themes alternate in this order.
     */
    val PRAYER_VERSES: List<DisplayText> = listOf(
        DisplayText("an_nisa_102", "إِنَّ الصَّلَاةَ كَانَتْ عَلَى الْمُؤْمِنِينَ كِتَابًا مَوْقُوتًا", "النساء 102"),
        DisplayText("al_baqara_236", "حَافِظُوا عَلَى الصَّلَوَاتِ وَالصَّلَاةِ الْوُسْطَىٰ", "البقرة 236"),
        DisplayText("al_muminun_1_2", "قَدْ أَفْلَحَ الْمُؤْمِنُونَ ۝1 الَّذِينَ هُمْ فِي صَلَاتِهِمْ خَاشِعُونَ", "المؤمنون 1–2"),
        DisplayText("al_ankabut_45", "إِنَّ الصَّلَاةَ تَنْهَىٰ عَنِ الْفَحْشَاءِ وَالْمُنْكَرِ", "العنكبوت 45"),
        DisplayText("taha_131", "وَأْمُرْ أَهْلَكَ بِالصَّلَاةِ وَاصْطَبِرْ عَلَيْهَا", "طه 131"),
        DisplayText("al_isra_78", "أَقِمِ الصَّلَاةَ لِدُلُوكِ الشَّمْسِ إِلَىٰ غَسَقِ اللَّيْلِ وَقُرْآنَ الْفَجْرِ", "الإسراء 78"),
        DisplayText("al_baqara_42", "وَأَقِيمُوا الصَّلَاةَ وَآتُوا الزَّكَاةَ وَارْكَعُوا مَعَ الرَّاكِعِينَ", "البقرة 42"),
        DisplayText("taha_13", "وَأَقِمِ الصَّلَاةَ لِذِكْرِيَ", "طه 13"),
        DisplayText("al_baqara_44", "وَاسْتَعِينُوا بِالصَّبْرِ وَالصَّلَاةِ", "البقرة 44"),
        DisplayText("luqman_16", "يَا بُنَيِّ أَقِمِ الصَّلَاةَ", "لقمان 16"),
        DisplayText("hud_114", "وَأَقِمِ الصَّلَاةَ طَرَفَيِ النَّهَارِ وَزُلَفًا مِنَ اللَّيْلِ", "هود 114"),
        DisplayText("ibrahim_42", "رَبِّ اجْعَلْنِي مُقِيمَ الصَّلَاةِ وَمِنْ ذُرِّيَّتِي", "إبراهيم 42"),
    )

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

    /**
     * Over the setup wizard's welcome. In the Qaloon reading with the Madani count the basmala opens
     * the suras but is not a verse of al-Fatiha, so its reference names it rather than giving «الفاتحة 1».
     */
    val BASMALA = DisplayText("basmala", "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ", "البسملة")

    val ALL: List<DisplayText> = PRAYER_VERSES + listOf(FRIDAY_VERSE, RAMADAN_VERSE, EID_GREETING, BASMALA)

    /**
     * The header's verse: Ramadan's all month, the Jumu'a verse on Fridays, otherwise [PRAYER_VERSES]
     * in turn. [turn] counts up with every adhan (see [turnAt]), so each congregation meets another verse.
     */
    fun headerVerse(isRamadan: Boolean, isFriday: Boolean, turn: Long = 0): DisplayText = when {
        isRamadan -> RAMADAN_VERSE
        isFriday -> FRIDAY_VERSE
        else -> PRAYER_VERSES[verseIndex(turn)]
    }

    /**
     * The verse for [turn]. Each round of the list starts one verse further on: with six turns a day
     * and twelve verses, the congregation of each prayer would otherwise only ever see the same two.
     */
    internal fun verseIndex(turn: Long): Int {
        val count = PRAYER_VERSES.size.toLong()
        return (turn + turn.floorDiv(count)).mod(count).toInt()
    }

    /**
     * The verse's turn on [date] once [adhansPassed] of its five daily adhans have been called: it moves
     * on at each adhan and the day before continues the sequence, the same after a restart.
     */
    fun turnAt(date: LocalDate, adhansPassed: Int): Long = date.toEpochDay() * TURNS_PER_DAY + adhansPassed.coerceIn(0, 5)

    private const val TURNS_PER_DAY = 6L
}
