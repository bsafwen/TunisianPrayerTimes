package com.tunisianprayertimes.adhkar

/**
 * What the card shown on finishing a wird or a reminder's goal reminds of: the reward of remembering
 * Allah and of keeping at it. Reviewed like the catalog (docs/adhkar-sources.md): verses in the Qaloon
 * wording with the later Madani count, in the catalog's readable spelling; hadith as sunnah.com words
 * them, from the Companion onward, checked again on dorar.net.
 */
object DhikrClosingTexts {

    enum class ClosingTheme { DHIKR, PERSEVERANCE }

    data class ClosingText(
        val id: String, val theme: ClosingTheme, val quran: Boolean, val text: String, val reference: String,
    )

    val REWARDS: List<ClosingText> = listOf(
        ClosingText("al_baqara_151", ClosingTheme.DHIKR, quran = true,
            text = "فَاذْكُرُونِي أَذْكُرْكُمْ وَاشْكُرُوا لِي وَلَا تَكْفُرُونِ",
            reference = "البقرة 151"),
        ClosingText("ar_rad_29", ClosingTheme.DHIKR, quran = true,
            text = "الَّذِينَ آمَنُوا وَتَطْمَئِنُّ قُلُوبُهُمْ بِذِكْرِ اللَّهِ ۗ أَلَا بِذِكْرِ اللَّهِ تَطْمَئِنُّ الْقُلُوبُ",
            reference = "الرعد 29"),
        ClosingText("al_ahzab_35", ClosingTheme.DHIKR, quran = true,
            text = "إِنَّ الْمُسْلِمِينَ وَالْمُسْلِمَاتِ وَالْمُؤْمِنِينَ وَالْمُؤْمِنَاتِ وَالْقَانِتِينَ وَالْقَانِتَاتِ وَالصَّادِقِينَ وَالصَّادِقَاتِ وَالصَّابِرِينَ وَالصَّابِرَاتِ وَالْخَاشِعِينَ وَالْخَاشِعَاتِ وَالْمُتَصَدِّقِينَ وَالْمُتَصَدِّقَاتِ وَالصَّائِمِينَ وَالصَّائِمَاتِ وَالْحَافِظِينَ فُرُوجَهُمْ وَالْحَافِظَاتِ وَالذَّاكِرِينَ اللَّهَ كَثِيرًا وَالذَّاكِرَاتِ أَعَدَّ اللَّهُ لَهُمْ مَغْفِرَةً وَأَجْرًا عَظِيمًا",
            reference = "الأحزاب 35"),
        ClosingText("al_ahzab_41_43", ClosingTheme.DHIKR, quran = true,
            text = "يَا أَيُّهَا الَّذِينَ آمَنُوا اذْكُرُوا اللَّهَ ذِكْرًا كَثِيرًا ۝41 وَسَبِّحُوهُ بُكْرَةً وَأَصِيلًا ۝42 هُوَ الَّذِي يُصَلِّي عَلَيْكُمْ وَمَلَائِكَتُهُ لِيُخْرِجَكُمْ مِنَ الظُّلُمَاتِ إِلَى النُّورِ ۚ وَكَانَ بِالْمُؤْمِنِينَ رَحِيمًا",
            reference = "الأحزاب 41–43"),
        ClosingText("taha_128", ClosingTheme.DHIKR, quran = true,
            text = "فَاصْبِرْ عَلَىٰ مَا يَقُولُونَ وَسَبِّحْ بِحَمْدِ رَبِّكَ قَبْلَ طُلُوعِ الشَّمْسِ وَقَبْلَ غُرُوبِهَا ۖ وَمِنْ آنَاءِ اللَّيْلِ فَسَبِّحْ وَأَطْرَافَ النَّهَارِ لَعَلَّكَ تَرْضَىٰ",
            reference = "طه 128"),
        ClosingText("bukhari_7405", ClosingTheme.DHIKR, quran = false,
            text = "عَنْ أَبِي هُرَيْرَةَ رضي الله عنه قَالَ قَالَ النَّبِيُّ ﷺ «يَقُولُ اللَّهُ تَعَالَى أَنَا عِنْدَ ظَنِّ عَبْدِي بِي، وَأَنَا مَعَهُ إِذَا ذَكَرَنِي، فَإِنْ ذَكَرَنِي فِي نَفْسِهِ ذَكَرْتُهُ فِي نَفْسِي، وَإِنْ ذَكَرَنِي فِي مَلأٍ ذَكَرْتُهُ فِي مَلأٍ خَيْرٍ مِنْهُمْ، وَإِنْ تَقَرَّبَ إِلَيَّ بِشِبْرٍ تَقَرَّبْتُ إِلَيْهِ ذِرَاعًا، وَإِنْ تَقَرَّبَ إِلَيَّ ذِرَاعًا تَقَرَّبْتُ إِلَيْهِ بَاعًا، وَإِنْ أَتَانِي يَمْشِي أَتَيْتُهُ هَرْوَلَةً».",
            reference = "صحيح البخاري 7405"),
        ClosingText("bukhari_6465", ClosingTheme.PERSEVERANCE, quran = false,
            text = "عَنْ عَائِشَةَ رضي الله عنها أَنَّهَا قَالَتْ سُئِلَ النَّبِيُّ ﷺ أَيُّ الأَعْمَالِ أَحَبُّ إِلَى اللَّهِ قَالَ «أَدْوَمُهَا وَإِنْ قَلَّ». وَقَالَ «اكْلَفُوا مِنَ الأَعْمَالِ مَا تُطِيقُونَ».",
            reference = "صحيح البخاري 6465"),
    )

    /**
     * The text for [epochDay]: one a day in turn, so a daily wird meets each of them within a week.
     * [shift] moves another reading of the same day (the evening after the morning) to another text.
     */
    fun rewardFor(epochDay: Long, shift: Int = 0): ClosingText =
        REWARDS[(epochDay + shift).mod(REWARDS.size.toLong()).toInt()]
}
