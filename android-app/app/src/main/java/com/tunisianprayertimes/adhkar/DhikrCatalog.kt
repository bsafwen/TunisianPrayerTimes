package com.tunisianprayertimes.adhkar

enum class DhikrCategory(val title: String) {
    SALAH("بعد الصلاة"),
    MORNING("الصباح"),
    EVENING("المساء"),
    SLEEP("النوم"),
    HOME("المنزل"),
    DAILY("الحياة اليومية")
}

data class DhikrEntry(
    val id: String,
    val title: String,
    val text: String,
    val reference: String,
    val defaultCount: Int,
    val categories: Set<DhikrCategory>,
    val contentVersion: String = "2026-09-21",
    val editorialNote: String = "مراجعة المصادر موثقة؛ يُعرض النص دون تغيير",
)

/**
 * Offline Arabic reading catalog. Source links and editorial notes are in docs/adhkar-sources.md.
 * Counts belong to the indicated occasion; user reminder targets are separate personal goals.
 * A count of one is also the reading default where the source does not specify a repetition count.
 */
object DhikrCatalog {
    const val SALAWAT_ID = "salawat_ibrahimiyya"

    val entries: List<DhikrEntry> = listOf(
        DhikrEntry(
            id = "salah_istighfar",
            title = "الاستغفار بعد الصلاة",
            text = "أَسْتَغْفِرُ اللَّهَ",
            reference = "صحيح مسلم 591",
            defaultCount = 3,
            categories = setOf(DhikrCategory.SALAH)
        ),
        DhikrEntry(
            id = "salah_salam",
            title = "اللهم أنت السلام",
            text = "اللَّهُمَّ أَنْتَ السَّلَامُ، وَمِنْكَ السَّلَامُ، تَبَارَكْتَ ذَا الْجَلَالِ وَالْإِكْرَامِ.",
            reference = "صحيح مسلم 591",
            defaultCount = 1,
            categories = setOf(DhikrCategory.SALAH)
        ),
        DhikrEntry(
            id = "salah_tasbih",
            title = "التسبيح بعد الصلاة",
            text = "سُبْحَانَ اللَّهِ",
            reference = "صحيح مسلم 597؛ التسبيح والتحميد والتكبير 33 مرة لكل منها، ثم التهليل مرة",
            defaultCount = 33,
            categories = setOf(DhikrCategory.SALAH)
        ),
        DhikrEntry(
            id = "salah_tahmid",
            title = "التحميد بعد الصلاة",
            text = "الْحَمْدُ لِلَّهِ",
            reference = "صحيح مسلم 597؛ إحدى صيغ الذكر بعد الصلاة",
            defaultCount = 33,
            categories = setOf(DhikrCategory.SALAH)
        ),
        DhikrEntry(
            id = "salah_takbir",
            title = "التكبير بعد الصلاة",
            text = "اللَّهُ أَكْبَرُ",
            reference = "صحيح مسلم 597؛ إحدى صيغ الذكر بعد الصلاة",
            defaultCount = 33,
            categories = setOf(DhikrCategory.SALAH)
        ),
        DhikrEntry(
            id = "salah_tahlil",
            title = "تمام المائة بعد الصلاة",
            text = "لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ، وَهُوَ عَلَى كُلِّ شَيْءٍ قَدِيرٌ.",
            reference = "صحيح مسلم 597؛ بعد التسبيح والتحميد والتكبير 33 مرة لكل منها",
            defaultCount = 1,
            categories = setOf(DhikrCategory.SALAH)
        ),
        DhikrEntry(
            id = SALAWAT_ID,
            title = "الصلاة الإبراهيمية",
            text = "اللَّهُمَّ صَلِّ عَلَى مُحَمَّدٍ، وَعَلَى آلِ مُحَمَّدٍ، كَمَا صَلَّيْتَ عَلَى إِبْرَاهِيمَ، وَعَلَى آلِ إِبْرَاهِيمَ، إِنَّكَ حَمِيدٌ مَجِيدٌ، وَبَارِكْ عَلَى مُحَمَّدٍ، وَعَلَى آلِ مُحَمَّدٍ، كَمَا بَارَكْتَ عَلَى إِبْرَاهِيمَ، وَعَلَى آلِ إِبْرَاهِيمَ، فِي الْعَالَمِينَ إِنَّكَ حَمِيدٌ مَجِيدٌ",
            reference = "النص الذي اختاره المستخدم للصلاة على النبي ﷺ. لا تُنسب هذه الصيغة المركبة هنا إلى رواية واحدة، والعدد هدف شخصي.",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY),
            editorialNote = "نص المستخدم؛ يحتاج اعتماد الصيغة قبل النشر العام"
        ),
        DhikrEntry(
            id = "morning_kingdom",
            title = "أصبحنا وأصبح الملك لله",
            text = "أَصْبَحْنَا وَأَصْبَحَ الْمُلْكُ لِلَّهِ، وَالْحَمْدُ لِلَّهِ، لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ، وَهُوَ عَلَى كُلِّ شَيْءٍ قَدِيرٌ. رَبِّ أَسْأَلُكَ خَيْرَ مَا فِي هَذَا الْيَوْمِ وَخَيْرَ مَا بَعْدَهُ، وَأَعُوذُ بِكَ مِنْ شَرِّ مَا فِي هَذَا الْيَوْمِ وَشَرِّ مَا بَعْدَهُ. رَبِّ أَعُوذُ بِكَ مِنَ الْكَسَلِ وَسُوءِ الْكِبَرِ. رَبِّ أَعُوذُ بِكَ مِنْ عَذَابٍ فِي النَّارِ وَعَذَابٍ فِي الْقَبْرِ.",
            reference = "صحيح مسلم 2723؛ بلفظ الصباح",
            defaultCount = 1,
            categories = setOf(DhikrCategory.MORNING)
        ),
        DhikrEntry(
            id = "evening_kingdom",
            title = "أمسينا وأمسى الملك لله",
            text = "أَمْسَيْنَا وَأَمْسَى الْمُلْكُ لِلَّهِ، وَالْحَمْدُ لِلَّهِ، لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ، وَهُوَ عَلَى كُلِّ شَيْءٍ قَدِيرٌ. رَبِّ أَسْأَلُكَ خَيْرَ مَا فِي هَذِهِ اللَّيْلَةِ وَخَيْرَ مَا بَعْدَهَا، وَأَعُوذُ بِكَ مِنْ شَرِّ مَا فِي هَذِهِ اللَّيْلَةِ وَشَرِّ مَا بَعْدَهَا. رَبِّ أَعُوذُ بِكَ مِنَ الْكَسَلِ وَسُوءِ الْكِبَرِ. رَبِّ أَعُوذُ بِكَ مِنْ عَذَابٍ فِي النَّارِ وَعَذَابٍ فِي الْقَبْرِ.",
            reference = "صحيح مسلم 2723",
            defaultCount = 1,
            categories = setOf(DhikrCategory.EVENING)
        ),
        DhikrEntry(
            id = "sayyid_istighfar",
            title = "سيد الاستغفار",
            text = "اللَّهُمَّ أَنْتَ رَبِّي، لَا إِلَهَ إِلَّا أَنْتَ، خَلَقْتَنِي وَأَنَا عَبْدُكَ، وَأَنَا عَلَى عَهْدِكَ وَوَعْدِكَ مَا اسْتَطَعْتُ، أَعُوذُ بِكَ مِنْ شَرِّ مَا صَنَعْتُ، أَبُوءُ لَكَ بِنِعْمَتِكَ عَلَيَّ، وَأَبُوءُ لَكَ بِذَنْبِي، فَاغْفِرْ لِي، فَإِنَّهُ لَا يَغْفِرُ الذُّنُوبَ إِلَّا أَنْتَ.",
            reference = "صحيح البخاري 6306",
            defaultCount = 1,
            categories = setOf(DhikrCategory.MORNING, DhikrCategory.EVENING)
        ),
        DhikrEntry(
            id = "bismillah_protection",
            title = "بسم الله الذي لا يضر مع اسمه شيء",
            text = "بِسْمِ اللَّهِ الَّذِي لَا يَضُرُّ مَعَ اسْمِهِ شَيْءٌ فِي الْأَرْضِ وَلَا فِي السَّمَاءِ، وَهُوَ السَّمِيعُ الْعَلِيمُ.",
            reference = "سنن أبي داود 5088؛ صححه الألباني",
            defaultCount = 3,
            categories = setOf(DhikrCategory.MORNING, DhikrCategory.EVENING)
        ),
        DhikrEntry(
            id = "subhanallah_bihamdih",
            title = "سبحان الله وبحمده",
            text = "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ",
            reference = "صحيح مسلم 2692؛ مائة مرة صباحًا ومساءً",
            defaultCount = 100,
            categories = setOf(DhikrCategory.MORNING, DhikrCategory.EVENING)
        ),
        DhikrEntry(
            id = "surah_ikhlas",
            title = "سورة الإخلاص",
            text = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ\nقُلْ هُوَ اللَّهُ أَحَدٌ ۝ اللَّهُ الصَّمَدُ ۝ لَمْ يَلِدْ وَلَمْ يُولَدْ ۝ وَلَمْ يَكُنْ لَهُ كُفُوًا أَحَدٌ.",
            reference = "القرآن 112؛ أبو داود 5082 (حسن)، والبخاري 5017 للنوم مع النفث ومسح الجسد",
            defaultCount = 3,
            categories = setOf(DhikrCategory.MORNING, DhikrCategory.EVENING, DhikrCategory.SLEEP)
        ),
        DhikrEntry(
            id = "surah_falaq",
            title = "سورة الفلق",
            text = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ\nقُلْ أَعُوذُ بِرَبِّ الْفَلَقِ ۝ مِنْ شَرِّ مَا خَلَقَ ۝ وَمِنْ شَرِّ غَاسِقٍ إِذَا وَقَبَ ۝ وَمِنْ شَرِّ النَّفَّاثَاتِ فِي الْعُقَدِ ۝ وَمِنْ شَرِّ حَاسِدٍ إِذَا حَسَدَ.",
            reference = "القرآن 113؛ أبو داود 5082 (حسن)، والبخاري 5017 للنوم مع النفث ومسح الجسد",
            defaultCount = 3,
            categories = setOf(DhikrCategory.MORNING, DhikrCategory.EVENING, DhikrCategory.SLEEP)
        ),
        DhikrEntry(
            id = "surah_nas",
            title = "سورة الناس",
            text = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ\nقُلْ أَعُوذُ بِرَبِّ النَّاسِ ۝ مَلِكِ النَّاسِ ۝ إِلَٰهِ النَّاسِ ۝ مِنْ شَرِّ الْوَسْوَاسِ الْخَنَّاسِ ۝ الَّذِي يُوَسْوِسُ فِي صُدُورِ النَّاسِ ۝ مِنَ الْجِنَّةِ وَالنَّاسِ.",
            reference = "القرآن 114؛ أبو داود 5082 (حسن)، والبخاري 5017 للنوم مع النفث ومسح الجسد",
            defaultCount = 3,
            categories = setOf(DhikrCategory.MORNING, DhikrCategory.EVENING, DhikrCategory.SLEEP)
        ),
        DhikrEntry(
            id = "sleep_bismika",
            title = "باسمك اللهم أموت وأحيا",
            text = "بِاسْمِكَ اللَّهُمَّ أَمُوتُ وَأَحْيَا.",
            reference = "صحيح البخاري 6324",
            defaultCount = 1,
            categories = setOf(DhikrCategory.SLEEP)
        ),
        DhikrEntry(
            id = "ayat_kursi",
            title = "آية الكرسي عند النوم",
            text = "اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ، لَا تَأْخُذُهُ سِنَةٌ وَلَا نَوْمٌ، لَهُ مَا فِي السَّمَاوَاتِ وَمَا فِي الْأَرْضِ، مَنْ ذَا الَّذِي يَشْفَعُ عِنْدَهُ إِلَّا بِإِذْنِهِ، يَعْلَمُ مَا بَيْنَ أَيْدِيهِمْ وَمَا خَلْفَهُمْ، وَلَا يُحِيطُونَ بِشَيْءٍ مِنْ عِلْمِهِ إِلَّا بِمَا شَاءَ، وَسِعَ كُرْسِيُّهُ السَّمَاوَاتِ وَالْأَرْضَ، وَلَا يَئُودُهُ حِفْظُهُمَا، وَهُوَ الْعَلِيُّ الْعَظِيمُ.",
            reference = "البقرة 255؛ صحيح البخاري 2311",
            defaultCount = 1,
            categories = setOf(DhikrCategory.SLEEP)
        ),
        DhikrEntry(
            id = "sleep_tasbih",
            title = "التسبيح عند النوم",
            text = "سُبْحَانَ اللَّهِ",
            reference = "صحيح مسلم 2727؛ التسبيح والتحميد 33 مرة والتكبير 34 مرة",
            defaultCount = 33,
            categories = setOf(DhikrCategory.SLEEP)
        ),
        DhikrEntry(
            id = "sleep_tahmid",
            title = "التحميد عند النوم",
            text = "الْحَمْدُ لِلَّهِ",
            reference = "صحيح مسلم 2727",
            defaultCount = 33,
            categories = setOf(DhikrCategory.SLEEP)
        ),
        DhikrEntry(
            id = "sleep_takbir",
            title = "التكبير عند النوم",
            text = "اللَّهُ أَكْبَرُ",
            reference = "صحيح مسلم 2727",
            defaultCount = 34,
            categories = setOf(DhikrCategory.SLEEP)
        ),
        DhikrEntry(
            id = "sleep_submission",
            title = "دعاء الاضطجاع على الجانب الأيمن",
            text = "اللَّهُمَّ أَسْلَمْتُ وَجْهِي إِلَيْكَ، وَفَوَّضْتُ أَمْرِي إِلَيْكَ، وَأَلْجَأْتُ ظَهْرِي إِلَيْكَ، رَغْبَةً وَرَهْبَةً إِلَيْكَ، لَا مَلْجَأَ وَلَا مَنْجَا مِنْكَ إِلَّا إِلَيْكَ، آمَنْتُ بِكِتَابِكَ الَّذِي أَنْزَلْتَ، وَبِنَبِيِّكَ الَّذِي أَرْسَلْتَ.",
            reference = "صحيح البخاري 6311؛ بعد الوضوء، ويكون آخر ما يقال قبل النوم",
            defaultCount = 1,
            categories = setOf(DhikrCategory.SLEEP)
        ),
        DhikrEntry(
            id = "home_enter",
            title = "ذكر الله والسلام عند دخول المنزل",
            text = "بِسْمِ اللَّهِ.\nالسَّلَامُ عَلَيْكُمْ.",
            reference = "أصل ذكر الله عند الدخول: مسلم 2018؛ والسلام على أهل البيت: النور 61",
            defaultCount = 1,
            categories = setOf(DhikrCategory.HOME)
        ),
        DhikrEntry(
            id = "home_leave",
            title = "الخروج من المنزل",
            text = "بِسْمِ اللَّهِ، تَوَكَّلْتُ عَلَى اللَّهِ، لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ.",
            reference = "سنن أبي داود 5095؛ صححه الألباني",
            defaultCount = 1,
            categories = setOf(DhikrCategory.HOME)
        ),
        DhikrEntry(
            id = "before_food",
            title = "عند بدء الطعام",
            text = "بِسْمِ اللَّهِ",
            reference = "صحيح البخاري 5376؛ التسمية قبل الطعام",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "food_forgot_bismillah",
            title = "إذا نسيت التسمية أول الطعام",
            text = "بِسْمِ اللَّهِ أَوَّلَهُ وَآخِرَهُ.",
            reference = "سنن أبي داود 3767؛ صححه الألباني",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "after_food_drink",
            title = "الحمد بعد الطعام والشراب",
            text = "الْحَمْدُ لِلَّهِ",
            reference = "صحيح مسلم 2734؛ حمد الله بعد الأكل والشرب",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "toilet_enter",
            title = "قبل دخول الخلاء",
            text = "اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنَ الْخُبُثِ وَالْخَبَائِثِ.",
            reference = "صحيح البخاري 142",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "toilet_leave",
            title = "بعد الخروج من الخلاء",
            text = "غُفْرَانَكَ",
            reference = "سنن أبي داود 30؛ صححه الألباني",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "mosque_enter",
            title = "دخول المسجد",
            text = "اللَّهُمَّ افْتَحْ لِي أَبْوَابَ رَحْمَتِكَ.",
            reference = "صحيح مسلم 713",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "mosque_leave",
            title = "الخروج من المسجد",
            text = "اللَّهُمَّ إِنِّي أَسْأَلُكَ مِنْ فَضْلِكَ.",
            reference = "صحيح مسلم 713",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "travel",
            title = "الركوب عند بدء السفر",
            text = "اللَّهُ أَكْبَرُ، اللَّهُ أَكْبَرُ، اللَّهُ أَكْبَرُ. سُبْحَانَ الَّذِي سَخَّرَ لَنَا هَذَا وَمَا كُنَّا لَهُ مُقْرِنِينَ، وَإِنَّا إِلَى رَبِّنَا لَمُنْقَلِبُونَ. اللَّهُمَّ إِنَّا نَسْأَلُكَ فِي سَفَرِنَا هَذَا الْبِرَّ وَالتَّقْوَى، وَمِنَ الْعَمَلِ مَا تَرْضَى. اللَّهُمَّ هَوِّنْ عَلَيْنَا سَفَرَنَا هَذَا وَاطْوِ عَنَّا بُعْدَهُ. اللَّهُمَّ أَنْتَ الصَّاحِبُ فِي السَّفَرِ، وَالْخَلِيفَةُ فِي الْأَهْلِ. اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنْ وَعْثَاءِ السَّفَرِ، وَكَآبَةِ الْمَنْظَرِ، وَسُوءِ الْمُنْقَلَبِ فِي الْمَالِ وَالْأَهْلِ.",
            reference = "صحيح مسلم 1342؛ يتضمن الزخرف 13–14",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "waking",
            title = "الاستيقاظ من النوم",
            text = "الْحَمْدُ لِلَّهِ الَّذِي أَحْيَانَا بَعْدَ مَا أَمَاتَنَا، وَإِلَيْهِ النُّشُورُ.",
            reference = "صحيح البخاري 6324",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "after_wudu",
            title = "بعد الوضوء",
            text = "أَشْهَدُ أَنْ لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، وَأَشْهَدُ أَنَّ مُحَمَّدًا عَبْدُهُ وَرَسُولُهُ.",
            reference = "صحيح مسلم 234",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "worry",
            title = "الاستعاذة من الهم والحزن",
            text = "اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنَ الْهَمِّ وَالْحَزَنِ، وَالْعَجْزِ وَالْكَسَلِ، وَالْجُبْنِ وَالْبُخْلِ، وَضَلَعِ الدَّيْنِ، وَغَلَبَةِ الرِّجَالِ.",
            reference = "صحيح البخاري 6369",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "evening_refuge",
            title = "الاستعاذة بكلمات الله التامات",
            text = "أَعُوذُ بِكَلِمَاتِ اللَّهِ التَّامَّاتِ مِنْ شَرِّ مَا خَلَقَ.",
            reference = "صحيح مسلم 2709؛ رياض الصالحين 1452، دون تعيين عدد في هذه الرواية",
            defaultCount = 1,
            categories = setOf(DhikrCategory.EVENING)
        )
    )

    fun find(id: String): DhikrEntry? = entries.firstOrNull { it.id == id }
}
