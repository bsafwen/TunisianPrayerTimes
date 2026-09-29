package com.tunisianprayertimes.tv.data

/** One after-salah dhikr card: the text, how many times it is said, and its source. */
data class Dhikr(val text: String, val repetition: String, val source: String)

/**
 * Temporary verbatim copy of the four non-Quranic after-salah entries of the phone app's
 * reviewed catalog (android-app/.../adhkar/DhikrCatalog.kt: salah_istighfar, salah_salam,
 * salah_la_mani, salah_hundred), with their references; AzkarDataTest keeps it identical.
 * Ayat al-Kursi and the three Quls are left out until the card can show long passages in
 * full. The TV will read the shared catalog directly once it moves to the shared module.
 */
object AzkarData {

    private const val ISTIGHFAR = "أَسْتَغْفِرُ اللَّهَ"
    private const val SALAM = "اللَّهُمَّ أَنْتَ السَّلَامُ، وَمِنْكَ السَّلَامُ، تَبَارَكْتَ ذَا الْجَلَالِ وَالْإِكْرَامِ."
    private const val LA_MANIA = "لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ، وَهُوَ عَلَى كُلِّ شَيْءٍ قَدِيرٌ، اللَّهُمَّ لَا مَانِعَ لِمَا أَعْطَيْتَ، وَلَا مُعْطِيَ لِمَا مَنَعْتَ، وَلَا يَنْفَعُ ذَا الْجَدِّ مِنْكَ الْجَدُّ."
    private const val TAHLIL = "لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ، وَهُوَ عَلَى كُلِّ شَيْءٍ قَدِيرٌ"
    private const val HUNDRED_SOURCE = "صحيح مسلم 597؛ التسبيح والتحميد والتكبير ثلاثًا وثلاثين لكل منها، ثم التهليل مرة واحدة تمام المائة"

    val AFTER_SALAH_AZKAR: List<Dhikr> = listOf(
        Dhikr(ISTIGHFAR, "3 مرات", "صحيح مسلم 591؛ ثلاث مرات عقب السلام"),
        Dhikr(SALAM, "مرة واحدة", "صحيح مسلم 591"),
        Dhikr(LA_MANIA, "مرة واحدة", "صحيح البخاري 844؛ صحيح مسلم 593؛ دبر كل صلاة مكتوبة"),
        Dhikr("سُبْحَانَ اللَّهِ", "33 مرة", HUNDRED_SOURCE),
        Dhikr("الْحَمْدُ لِلَّهِ", "33 مرة", HUNDRED_SOURCE),
        Dhikr("اللَّهُ أَكْبَرُ", "33 مرة", HUNDRED_SOURCE),
        Dhikr(TAHLIL, "مرة واحدة", HUNDRED_SOURCE),
    )

    /** Ticker texts, from the same reviewed entries. */
    val TICKER_ITEMS: List<String> = listOf(SALAM, LA_MANIA, TAHLIL)

    val RAMADAN_TICKER_ITEMS: List<String> = TICKER_ITEMS
}
