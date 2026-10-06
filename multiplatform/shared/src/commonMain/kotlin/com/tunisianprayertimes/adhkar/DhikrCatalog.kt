package com.tunisianprayertimes.adhkar

enum class DhikrCategory(val title: String) {
    SALAH("بعد الصلاة"),
    PRAYER("الصلاة"),
    MORNING("الصباح"),
    EVENING("المساء"),
    NIGHT("الليل"),
    SLEEP("النوم"),
    HOME("المنزل"),
    DAILY("الحياة اليومية")
}

data class DhikrStep(val label: String, val text: String, val repetitions: Int)

data class DhikrEntry(
    val id: String,
    val title: String,
    val text: String,
    val reference: String,
    val defaultCount: Int,
    val categories: Set<DhikrCategory>,
    val contentVersion: String = "2026-09-28",
    val editorialNote: String = "مراجعة المصادر موثقة؛ يُعرض النص دون تغيير",
    /** Plain-Arabic meaning of the wording, compiled from the source books. */
    val explanation: String = "",
    /** The hadith the dhikr is taken from, quoted from the cited collection; see [DhikrNarrations]. */
    val narration: String = "",
    /** True for entries the user wrote; they are stored with the reading state, not shipped in [entries]. */
    val custom: Boolean = false,
    /** A collection may prescribe a different reading count while reusing this same catalog entry. */
    val collectionCountOverrides: Map<DhikrCategory, Int> = emptyMap(),
    /** Ordered phrases counted within one reading item. */
    val steps: List<DhikrStep> = emptyList(),
    /**
     * The part of [reference] that concerns one collection, where the full source covers several
     * occasions (morning, bedtime, after the prayer): the mosque's after-prayer screen cites only its own.
     */
    val collectionReferences: Map<DhikrCategory, String> = emptyMap(),
)

fun DhikrEntry.countForCollection(category: DhikrCategory?): Int =
    category?.let(collectionCountOverrides::get) ?: defaultCount

fun DhikrEntry.referenceForCollection(category: DhikrCategory?): String =
    category?.let(collectionReferences::get) ?: reference

/**
 * Offline Arabic reading catalog. Source links and editorial notes are in docs/adhkar-sources.md.
 * Counts belong to the indicated occasion; user reminder targets are separate personal goals.
 * A count of one is also the reading default where the source does not specify a repetition count.
 */
object DhikrCatalog {
    const val SALAWAT_ID = "salawat_ibrahimiyya"
    const val DAILY_TAHLIL_ID = "tahlil_ashr"
    const val SALAH_HUNDRED_ID = "salah_hundred"
    private val salahHundredSteps = listOf(
        DhikrStep("التسبيح", "سُبْحَانَ اللَّهِ", 33),
        DhikrStep("التحميد", "الْحَمْدُ لِلَّهِ", 33),
        DhikrStep("التكبير", "اللَّهُ أَكْبَرُ", 33),
        DhikrStep("التهليل", "لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ، وَهُوَ عَلَى كُلِّ شَيْءٍ قَدِيرٌ", 1),
    )

    private val baseEntries: List<DhikrEntry> = listOf(
        DhikrEntry(
            id = "salah_istighfar",
            title = "الاستغفار بعد الصلاة",
            text = "أَسْتَغْفِرُ اللَّهَ",
            reference = "صحيح مسلم 591؛ ثلاث مرات عقب السلام",
            defaultCount = 3,
            categories = setOf(DhikrCategory.SALAH)
        ),
        DhikrEntry(
            id = "salah_salam",
            title = "اللهم أنت السلام",
            text = "اللَّهُمَّ أَنْتَ السَّلَامُ، وَمِنْكَ السَّلَامُ، تَبَارَكْتَ ذَا الْجَلَالِ وَالْإِكْرَامِ.",
            reference = "صحيح مسلم 591",
            defaultCount = 1,
            categories = setOf(DhikrCategory.SALAH)
        ),
        DhikrEntry(
            id = "salah_la_mani",
            title = "لا مانع لما أعطيت",
            text = "لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ، وَهُوَ عَلَى كُلِّ شَيْءٍ قَدِيرٌ، اللَّهُمَّ لَا مَانِعَ لِمَا أَعْطَيْتَ، وَلَا مُعْطِيَ لِمَا مَنَعْتَ، وَلَا يَنْفَعُ ذَا الْجَدِّ مِنْكَ الْجَدُّ.",
            reference = "صحيح البخاري 844؛ صحيح مسلم 593؛ دبر كل صلاة مكتوبة",
            defaultCount = 1,
            categories = setOf(DhikrCategory.SALAH)
        ),
        DhikrEntry(
            id = SALAH_HUNDRED_ID,
            title = "ذكر المائة بعد الصلاة",
            text = salahHundredSteps.joinToString("، ثم ") { "${it.text} (${it.repetitions})" },
            reference = "صحيح مسلم 597؛ التسبيح والتحميد والتكبير ثلاثًا وثلاثين لكل منها، ثم التهليل مرة واحدة تمام المائة",
            defaultCount = salahHundredSteps.sumOf(DhikrStep::repetitions),
            categories = setOf(DhikrCategory.SALAH),
            steps = salahHundredSteps,
        ),
        DhikrEntry(
            id = SALAWAT_ID,
            title = "الصلاة الإبراهيمية",
            text = "اللَّهُمَّ صَلِّ عَلَى مُحَمَّدٍ، وَعَلَى آلِ مُحَمَّدٍ، كَمَا صَلَّيْتَ عَلَى آلِ إِبْرَاهِيمَ، إِنَّكَ حَمِيدٌ مَجِيدٌ، اللَّهُمَّ بَارِكْ عَلَى مُحَمَّدٍ، وَعَلَى آلِ مُحَمَّدٍ، كَمَا بَارَكْتَ عَلَى آلِ إِبْرَاهِيمَ، إِنَّكَ حَمِيدٌ مَجِيدٌ.",
            reference = "صحيح البخاري 6357؛ صحيح مسلم 406؛ متفق عليه من حديث كعب بن عجرة بهذا اللفظ",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "morning_kingdom",
            title = "أصبحنا وأصبح الملك لله",
            text = "أَصْبَحْنَا وَأَصْبَحَ الْمُلْكُ لِلَّهِ، وَالْحَمْدُ لِلَّهِ، لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ، وَهُوَ عَلَى كُلِّ شَيْءٍ قَدِيرٌ. رَبِّ أَسْأَلُكَ خَيْرَ مَا فِي هَذَا الْيَوْمِ وَخَيْرَ مَا بَعْدَهُ، وَأَعُوذُ بِكَ مِنْ شَرِّ مَا فِي هَذَا الْيَوْمِ وَشَرِّ مَا بَعْدَهُ. رَبِّ أَعُوذُ بِكَ مِنَ الْكَسَلِ وَسُوءِ الْكِبَرِ. رَبِّ أَعُوذُ بِكَ مِنْ عَذَابٍ فِي النَّارِ وَعَذَابٍ فِي الْقَبْرِ.",
            reference = "صحيح مسلم 2723؛ بلفظ الصباح كما في الحديث",
            defaultCount = 1,
            categories = setOf(DhikrCategory.MORNING)
        ),
        DhikrEntry(
            id = "evening_kingdom",
            title = "أمسينا وأمسى الملك لله",
            text = "أَمْسَيْنَا وَأَمْسَى الْمُلْكُ لِلَّهِ، وَالْحَمْدُ لِلَّهِ، لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ، وَهُوَ عَلَى كُلِّ شَيْءٍ قَدِيرٌ. رَبِّ أَسْأَلُكَ خَيْرَ مَا فِي هَذِهِ اللَّيْلَةِ وَخَيْرَ مَا بَعْدَهَا، وَأَعُوذُ بِكَ مِنْ شَرِّ مَا فِي هَذِهِ اللَّيْلَةِ وَشَرِّ مَا بَعْدَهَا. رَبِّ أَعُوذُ بِكَ مِنَ الْكَسَلِ وَسُوءِ الْكِبَرِ. رَبِّ أَعُوذُ بِكَ مِنْ عَذَابٍ فِي النَّارِ وَعَذَابٍ فِي الْقَبْرِ.",
            reference = "صحيح مسلم 2723",
            defaultCount = 1,
            categories = setOf(DhikrCategory.EVENING)
        ),
        DhikrEntry(
            id = "sayyid_istighfar",
            title = "سيد الاستغفار",
            text = "اللَّهُمَّ أَنْتَ رَبِّي، لَا إِلَهَ إِلَّا أَنْتَ، خَلَقْتَنِي وَأَنَا عَبْدُكَ، وَأَنَا عَلَى عَهْدِكَ وَوَعْدِكَ مَا اسْتَطَعْتُ، أَعُوذُ بِكَ مِنْ شَرِّ مَا صَنَعْتُ، أَبُوءُ لَكَ بِنِعْمَتِكَ عَلَيَّ، وَأَبُوءُ لَكَ بِذَنْبِي، فَاغْفِرْ لِي، فَإِنَّهُ لَا يَغْفِرُ الذُّنُوبَ إِلَّا أَنْتَ.",
            reference = "صحيح البخاري 6306",
            defaultCount = 1,
            categories = setOf(DhikrCategory.MORNING, DhikrCategory.EVENING, DhikrCategory.NIGHT)
        ),
        DhikrEntry(
            id = "bismillah_protection",
            title = "بسم الله الذي لا يضر مع اسمه شيء",
            text = "بِسْمِ اللَّهِ الَّذِي لَا يَضُرُّ مَعَ اسْمِهِ شَيْءٌ فِي الْأَرْضِ وَلَا فِي السَّمَاءِ، وَهُوَ السَّمِيعُ الْعَلِيمُ.",
            reference = "سنن أبي داود 5088؛ ثلاثًا صباحًا ومساءً؛ صححه الألباني",
            defaultCount = 3,
            categories = setOf(DhikrCategory.MORNING, DhikrCategory.EVENING)
        ),
        DhikrEntry(
            id = "subhanallah_bihamdih",
            title = "سبحان الله وبحمده",
            text = "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ",
            reference = "صحيح مسلم 2692؛ مائة مرة صباحًا ومساءً",
            defaultCount = 100,
            categories = setOf(DhikrCategory.MORNING, DhikrCategory.EVENING)
        ),
        DhikrEntry(
            id = "surah_ikhlas",
            title = "سورة الإخلاص",
            text = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ\nقُلْ هُوَ اللَّهُ أَحَدٌ ۝1 اللَّهُ الصَّمَدُ ۝2 لَمْ يَلِدْ وَلَمْ يُولَدْ ۝3 وَلَمْ يَكُنْ لَهُ كُفُؤًا أَحَدٌ ۝4",
            reference = SURAH_OCCASIONS.format("القرآن 112"),
            defaultCount = 3,
            categories = setOf(DhikrCategory.MORNING, DhikrCategory.EVENING, DhikrCategory.SLEEP, DhikrCategory.SALAH),
            collectionCountOverrides = mapOf(DhikrCategory.SALAH to 1),
            collectionReferences = mapOf(DhikrCategory.SALAH to SURAH_AFTER_SALAH.format("سورة الإخلاص")),
        ),
        DhikrEntry(
            id = "surah_falaq",
            title = "سورة الفلق",
            text = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ\nقُلْ أَعُوذُ بِرَبِّ الْفَلَقِ ۝1 مِنْ شَرِّ مَا خَلَقَ ۝2 وَمِنْ شَرِّ غَاسِقٍ إِذَا وَقَبَ ۝3 وَمِنْ شَرِّ النَّفَّاثَاتِ فِي الْعُقَدِ ۝4 وَمِنْ شَرِّ حَاسِدٍ إِذَا حَسَدَ ۝5",
            reference = SURAH_OCCASIONS.format("القرآن 113"),
            defaultCount = 3,
            categories = setOf(DhikrCategory.MORNING, DhikrCategory.EVENING, DhikrCategory.SLEEP, DhikrCategory.SALAH),
            collectionCountOverrides = mapOf(DhikrCategory.SALAH to 1),
            collectionReferences = mapOf(DhikrCategory.SALAH to SURAH_AFTER_SALAH.format("سورة الفلق")),
        ),
        DhikrEntry(
            id = "surah_nas",
            title = "سورة الناس",
            text = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ\nقُلْ أَعُوذُ بِرَبِّ النَّاسِ ۝1 مَلِكِ النَّاسِ ۝2 إِلَٰهِ النَّاسِ ۝3 مِنْ شَرِّ الْوَسْوَاسِ الْخَنَّاسِ ۝4 الَّذِي يُوَسْوِسُ فِي صُدُورِ النَّاسِ ۝5 مِنَ الْجِنَّةِ وَالنَّاسِ ۝6",
            reference = SURAH_OCCASIONS.format("القرآن 114"),
            defaultCount = 3,
            categories = setOf(DhikrCategory.MORNING, DhikrCategory.EVENING, DhikrCategory.SLEEP, DhikrCategory.SALAH),
            collectionCountOverrides = mapOf(DhikrCategory.SALAH to 1),
            collectionReferences = mapOf(DhikrCategory.SALAH to SURAH_AFTER_SALAH.format("سورة الناس")),
        ),
        DhikrEntry(
            id = "sleep_bismika",
            title = "باسمك اللهم أموت وأحيا",
            text = "بِاسْمِكَ اللَّهُمَّ أَمُوتُ وَأَحْيَا.",
            reference = "صحيح البخاري 6324",
            defaultCount = 1,
            categories = setOf(DhikrCategory.SLEEP)
        ),
        DhikrEntry(
            id = "ayat_kursi",
            title = "آية الكرسي",
            text = "اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ ۝253 لَا تَأْخُذُهُ سِنَةٌ وَلَا نَوْمٌ ۚ لَهُ مَا فِي السَّمَاوَاتِ وَمَا فِي الْأَرْضِ ۗ مَنْ ذَا الَّذِي يَشْفَعُ عِنْدَهُ إِلَّا بِإِذْنِهِ ۚ يَعْلَمُ مَا بَيْنَ أَيْدِيهِمْ وَمَا خَلْفَهُمْ ۖ وَلَا يُحِيطُونَ بِشَيْءٍ مِنْ عِلْمِهِ إِلَّا بِمَا شَاءَ ۚ وَسِعَ كُرْسِيُّهُ السَّمَاوَاتِ وَالْأَرْضَ ۖ وَلَا يَئُودُهُ حِفْظُهُمَا ۚ وَهْوَ الْعَلِيُّ الْعَظِيمُ ۝254",
            reference = "البقرة 253–254 (قالون، العد المدني الأخير)؛ عند النوم: صحيح البخاري 2311؛ صباحًا ومساءً: مستدرك الحاكم (حديث أبي بن كعب)؛ دبر كل صلاة: السنن الكبرى للنسائي (حديث أبي أمامة)؛ وصححهما الألباني",
            defaultCount = 1,
            categories = setOf(DhikrCategory.MORNING, DhikrCategory.EVENING, DhikrCategory.SLEEP, DhikrCategory.SALAH),
            collectionReferences = mapOf(
                DhikrCategory.SALAH to "البقرة 253–254 (قالون، العد المدني الأخير)؛ دبر كل صلاة: السنن الكبرى للنسائي (حديث أبي أمامة)؛ وصححه الألباني",
            ),
        ),
        DhikrEntry(
            id = "sleep_tasbih",
            title = "التسبيح عند النوم",
            text = "سُبْحَانَ اللَّهِ",
            reference = "صحيح مسلم 2727؛ التسبيح والتحميد ثلاثًا وثلاثين، والتكبير أربعًا وثلاثين",
            defaultCount = 33,
            categories = setOf(DhikrCategory.SLEEP)
        ),
        DhikrEntry(
            id = "sleep_tahmid",
            title = "التحميد عند النوم",
            text = "الْحَمْدُ لِلَّهِ",
            reference = "صحيح مسلم 2727؛ ثلاثًا وثلاثين",
            defaultCount = 33,
            categories = setOf(DhikrCategory.SLEEP)
        ),
        DhikrEntry(
            id = "sleep_takbir",
            title = "التكبير عند النوم",
            text = "اللَّهُ أَكْبَرُ",
            reference = "صحيح مسلم 2727؛ أربعًا وثلاثين",
            defaultCount = 34,
            categories = setOf(DhikrCategory.SLEEP)
        ),
        DhikrEntry(
            id = "sleep_submission",
            title = "دعاء الاضطجاع على الجانب الأيمن",
            text = "اللَّهُمَّ أَسْلَمْتُ وَجْهِي إِلَيْكَ، وَفَوَّضْتُ أَمْرِي إِلَيْكَ، وَأَلْجَأْتُ ظَهْرِي إِلَيْكَ، رَغْبَةً وَرَهْبَةً إِلَيْكَ، لَا مَلْجَأَ وَلَا مَنْجَا مِنْكَ إِلَّا إِلَيْكَ، آمَنْتُ بِكِتَابِكَ الَّذِي أَنْزَلْتَ، وَبِنَبِيِّكَ الَّذِي أَرْسَلْتَ.",
            reference = "صحيح البخاري 6311؛ بعد الوضوء والاضطجاع على الشق الأيمن، ويجعله آخر ما يقول",
            defaultCount = 1,
            categories = setOf(DhikrCategory.SLEEP)
        ),
        DhikrEntry(
            id = "sleep_last_two_baqarah",
            title = "آخر آيتين من سورة البقرة",
            text = "آمَنَ الرَّسُولُ بِمَا أُنْزِلَ إِلَيْهِ مِنْ رَبِّهِ وَالْمُؤْمِنُونَ ۚ كُلٌّ آمَنَ بِاللَّهِ وَمَلَائِكَتِهِ وَكُتُبِهِ وَرُسُلِهِ لَا نُفَرِّقُ بَيْنَ أَحَدٍ مِنْ رُسُلِهِ ۚ وَقَالُوا سَمِعْنَا وَأَطَعْنَا ۖ غُفْرَانَكَ رَبَّنَا وَإِلَيْكَ الْمَصِيرُ ۝284\nلَا يُكَلِّفُ اللَّهُ نَفْسًا إِلَّا وُسْعَهَا ۚ لَهَا مَا كَسَبَتْ وَعَلَيْهَا مَا اكْتَسَبَتْ ۗ رَبَّنَا لَا تُؤَاخِذْنَا إِنْ نَسِينَا أَوْ أَخْطَأْنَا ۚ رَبَّنَا وَلَا تَحْمِلْ عَلَيْنَا إِصْرًا كَمَا حَمَلْتَهُ عَلَى الَّذِينَ مِنْ قَبْلِنَا ۚ رَبَّنَا وَلَا تُحَمِّلْنَا مَا لَا طَاقَةَ لَنَا بِهِ ۖ وَاعْفُ عَنَّا وَاغْفِرْ لَنَا وَارْحَمْنَا ۚ أَنْتَ مَوْلَانَا فَانْصُرْنَا عَلَى الْقَوْمِ الْكَافِرِينَ ۝285",
            reference = "البقرة 284–285 (قالون، العد المدني الأخير)؛ صحيح البخاري 5009",
            defaultCount = 1,
            categories = setOf(DhikrCategory.SLEEP, DhikrCategory.NIGHT)
        ),
        DhikrEntry(
            id = "home_enter",
            title = "ذكر الله والسلام عند دخول المنزل",
            text = "بِسْمِ اللَّهِ.\nالسَّلَامُ عَلَيْكُمْ.",
            reference = "ذكر الله عند الدخول: صحيح مسلم 2018؛ والسلام على أهل البيت: النور 59 (العد المدني الأخير)",
            defaultCount = 1,
            categories = setOf(DhikrCategory.HOME)
        ),
        DhikrEntry(
            id = "home_leave",
            title = "الخروج من المنزل",
            text = "بِسْمِ اللَّهِ، تَوَكَّلْتُ عَلَى اللَّهِ، لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ.",
            reference = "سنن أبي داود 5095؛ صححه الألباني",
            defaultCount = 1,
            categories = setOf(DhikrCategory.HOME)
        ),
        DhikrEntry(
            id = "before_food",
            title = "عند بدء الطعام",
            text = "بِسْمِ اللَّهِ",
            reference = "صحيح البخاري 5376؛ التسمية قبل الطعام",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "food_forgot_bismillah",
            title = "إذا نسيت التسمية أول الطعام",
            text = "بِسْمِ اللَّهِ أَوَّلَهُ وَآخِرَهُ.",
            reference = "سنن أبي داود 3767؛ صححه الألباني",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "after_food_drink",
            title = "الحمد بعد الطعام والشراب",
            text = "الْحَمْدُ لِلَّهِ",
            reference = "صحيح مسلم 2734؛ حمد الله بعد الأكل والشرب",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "toilet_enter",
            title = "قبل دخول الخلاء",
            text = "اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنَ الْخُبُثِ وَالْخَبَائِثِ.",
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
            title = "دعاء دخول المسجد",
            text = "اللَّهُمَّ افْتَحْ لِي أَبْوَابَ رَحْمَتِكَ.",
            reference = "صحيح مسلم 713",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "mosque_leave",
            title = "دعاء الخروج من المسجد",
            text = "اللَّهُمَّ إِنِّي أَسْأَلُكَ مِنْ فَضْلِكَ.",
            reference = "صحيح مسلم 713",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "travel",
            title = "دعاء السفر",
            text = "اللَّهُ أَكْبَرُ، اللَّهُ أَكْبَرُ، اللَّهُ أَكْبَرُ. سُبْحَانَ الَّذِي سَخَّرَ لَنَا هَذَا وَمَا كُنَّا لَهُ مُقْرِنِينَ، وَإِنَّا إِلَى رَبِّنَا لَمُنْقَلِبُونَ. اللَّهُمَّ إِنَّا نَسْأَلُكَ فِي سَفَرِنَا هَذَا الْبِرَّ وَالتَّقْوَى، وَمِنَ الْعَمَلِ مَا تَرْضَى. اللَّهُمَّ هَوِّنْ عَلَيْنَا سَفَرَنَا هَذَا وَاطْوِ عَنَّا بُعْدَهُ. اللَّهُمَّ أَنْتَ الصَّاحِبُ فِي السَّفَرِ، وَالْخَلِيفَةُ فِي الْأَهْلِ. اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنْ وَعْثَاءِ السَّفَرِ، وَكَآبَةِ الْمَنْظَرِ، وَسُوءِ الْمُنْقَلَبِ فِي الْمَالِ وَالْأَهْلِ.",
            reference = "صحيح مسلم 1342؛ عند الاستواء على المركوب، ويتضمن الزخرف 12–13 (العد المدني الأخير)",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "waking",
            title = "الاستيقاظ من النوم",
            text = "الْحَمْدُ لِلَّهِ الَّذِي أَحْيَانَا بَعْدَ مَا أَمَاتَنَا، وَإِلَيْهِ النُّشُورُ.",
            reference = "صحيح البخاري 6324",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "after_wudu",
            title = "بعد الوضوء",
            text = "أَشْهَدُ أَنْ لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، وَأَشْهَدُ أَنَّ مُحَمَّدًا عَبْدُهُ وَرَسُولُهُ.",
            reference = "صحيح مسلم 234",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "worry",
            title = "الاستعاذة من الهم والحزن",
            text = "اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنَ الْهَمِّ وَالْحَزَنِ، وَالْعَجْزِ وَالْكَسَلِ، وَالْجُبْنِ وَالْبُخْلِ، وَضَلَعِ الدَّيْنِ، وَغَلَبَةِ الرِّجَالِ.",
            reference = "صحيح البخاري 6369",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "dua_huda_tuqa",
            title = "سؤال الهدى والتقوى والعفاف والغنى",
            text = "اللَّهُمَّ إِنِّي أَسْأَلُكَ الْهُدَى وَالتُّقَى وَالْعَفَافَ وَالْغِنَى.",
            reference = "صحيح مسلم 2721",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "la_hawla_quwwata",
            title = "لا حول ولا قوة إلا بالله",
            text = "لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ.",
            reference = "صحيح البخاري 6384",
            defaultCount = 1,
            categories = setOf(DhikrCategory.DAILY)
        ),
        DhikrEntry(
            id = "evening_refuge",
            title = "الاستعاذة بكلمات الله التامات",
            text = "أَعُوذُ بِكَلِمَاتِ اللَّهِ التَّامَّاتِ مِنْ شَرِّ مَا خَلَقَ.",
            reference = "مساءً: صحيح مسلم 2709؛ وعند نزول المنزل: صحيح مسلم 2708؛ دون تعيين عدد في الروايتين",
            defaultCount = 1,
            categories = setOf(DhikrCategory.MORNING, DhikrCategory.EVENING, DhikrCategory.DAILY)
        )
    )

    /**
     * Meanings compiled from «معاني الأذكار» and «31 فائدة في أذكار الصباح والمساء»; those of the morning
     * and evening adhkar quote the booklet (benefits 28-29), with each quoted phrase in the catalog's own wording.
     */
    private val explanations: Map<String, String> = mapOf(
        "salah_istighfar" to "الاستغفار بعد العبادة يجبر ما وقع فيها من تقصير ظاهر أو باطن.",
        "salah_salam" to "«السلام» اسم من أسماء الله، ومنه تُطلب السلامة؛ فيسأل العبد سلامة صلاته من الردّ والنقص. و«ذا الجلال والإكرام»: صاحب العظمة والإحسان.",
        "salah_la_mani" to "إقرار بانفراد الله بالملك والحمد والقدرة؛ فلا أحد يردّ عطاءه، ولا أحد يعطي ما منعه. و«الجد»: الحظ والغنى والعظمة، فلا ينجي صاحبه من الله، وإنما ينفعه العمل الصالح.",
        SALAH_HUNDRED_ID to "يُسبّح الله ثلاثًا وثلاثين، ويحمده ثلاثًا وثلاثين، ويكبّره ثلاثًا وثلاثين، ثم يقول التهليل مرة واحدة فتتم المائة. وعد النبي ﷺ من فعل ذلك دبر الصلاة بمغفرة خطاياه وإن كانت مثل زبد البحر.",
        SALAWAT_ID to "«صلِّ على محمد»: أثنِ عليه عند الملائكة؛ قاله أبو العالية فيما علّقه البخاري. و«آل محمد»: أتباعه على دينه، وقيل: أهل بيته، و«آل إبراهيم» يدخل فيهم إبراهيم عليه السلام. و«بارك»: أثبت له الخير وأدمه وزده. و«حميد»: المحمود في ذاته وصفاته وأفعاله، و«مجيد»: واسع المجد والكرم.",
        "morning_kingdom" to "«أَصْبَحْنَا وَأَصْبَحَ الْمُلْكُ لِلَّهِ»: هذا بيانُ حال القائل، أي: عرفنا أنَّ المُلك لله والحَمْدَ له لا لغيره، فالتجأنا إليه واستعنَّا به، وخصَّصناه بالعبادة والثناء عليه والشُّكر له. «سُوءِ الْكِبَرِ»: يستعيذُ بالله ممَّا يُورِثه كِبَرُ السِّنِّ (الهَرَم) من ذهاب العقل، بحيث يصير الرجل خَرِفًا، والتخبُّط في الرأي، وغير ذلك ممَّا يَسوءُ به الحال، كالذِّلَّة بين الناس، والعَجْز عن الحركة، ونحو ذلك.",
        "evening_kingdom" to "«أَمْسَيْنَا وَأَمْسَى الْمُلْكُ لِلَّهِ»: هذا بيانُ حال القائل، أي: عرفنا أنَّ المُلك لله والحَمْدَ له لا لغيره، فالتجأنا إليه واستعنَّا به، وخصَّصناه بالعبادة والثناء عليه والشُّكر له. «سُوءِ الْكِبَرِ»: يستعيذُ بالله ممَّا يُورِثه كِبَرُ السِّنِّ (الهَرَم) من ذهاب العقل، بحيث يصير الرجل خَرِفًا، والتخبُّط في الرأي، وغير ذلك ممَّا يَسوءُ به الحال، كالذِّلَّة بين الناس، والعَجْز عن الحركة، ونحو ذلك.",
        "sayyid_istighfar" to "«وَأَنَا عَلَى عَهْدِكَ وَوَعْدِكَ مَا اسْتَطَعْتُ»: فالله سبحانه وتعالى عَهِدَ إلى عباده عهدًا أمرهم فيه ونهاهم، ووعدهم على وفائهم بعهده أن يثيبهم بأعلى المثوبات. وقوله: «مَا اسْتَطَعْتُ» أي: إنَّما أقوم بذلك بحَسَب استطاعتي، لا بحسب ما ينبغي لك وتستحقُّه عليَّ. «أَبُوءُ لَكَ بِنِعْمَتِكَ عَلَيَّ، وَأَبُوءُ لَكَ بِذَنْبِي»: أعترف لك بإنعامك عليَّ، وأنِّي أنا المذنب، فمنك الإحسان ومنِّي الإساءة، فأنا أحمدك على نِعمتك -وأنت أهلٌ لأن تُحمَد-، وأستغفرك لذنوبي، فالعبد دائمًا بين نِعْمَةٍ من الله يحتاج فيها إلى شُكْر، وذنب منه يحتاج فيه إلى الاستغفار، فهو لا يزال يتقلَّب في نِعَم الله وآلائه، ولا يزال محتاجًا إلى التوبة والاستغفار.",
        "bismillah_protection" to "«بِسْمِ اللَّهِ»: بسم الله أستعيذُ، أي: ألجأ وأعتَصِم وأحتَمي بالله القادِر على كلِّ شيء، والذي لا يقدر على دفع شرِّ كلِّ ذي شرٍ إلا هو سبحانه. فمَن تعوَّذ باسم الله؛ فإنَّه لا تَضرُّه مُصيبةٌ من جهة الأرض ولا من جهة السماء.",
        "subhanallah_bihamdih" to "«سُبْحَانَ اللَّهِ وَبِحَمْدِهِ»: أنزِّه الله عمَّا لا يليق به من كلِّ نقصٍ وعيبٍ، وأثبِتُ له كلَّ صفات الكمال، فهذا الذِّكْر جمعٌ بين التسبيح والحمد، يعني: أسبِّح الله تعالى حالَ كوني حامدًا له، أو: أسبِّح الله تعالى وأحمَدُه، ولذا كان هذا الذِّكْر من أفضل الأذكار وأحبِّ الكلام إلى الله تعالى.",
        "surah_ikhlas" to "«اللَّهُ الصَّمَدُ»: المقصودُ في جميع الحوائج، الذي تقصِده جميعُ المخلوقات، في جميع حاجاتِها ومسائِلِها وأحوالِها وضروراتِها، بالذُّلِّ والحاجة والافتقار، فالكلُّ خاضعٌ له، مُفتَقِرٌ إليه، وهو سبحانه الغنيُّ عن عبادِه، الذي كَمُلَ في عِلْمه، وحِكْمَتِه، وحِلْمه، وقُدْرَته، وعَظَمَته، ورحمته، وسائر أوصافه. فالصَّمَد هو كامل الصِّفات، وهو الذي تقصِده المخلوقات في كلِّ الحاجات. «وَلَمْ يَكُنْ لَهُ كُفُؤًا أَحَدٌ»: ليسَ له شبيهٌ ولا مِثْلٌ، وليس كمِثْلِه شيءٌ، وليس له ولدٌ ولا والدٌ ولا صاحبةٌ.",
        "surah_falaq" to "«أَعُوذُ»: ألْجأ وأستجير وأحْتَمي وأعْتَصِم. «الْفَلَقِ»: الصُّبْح. «مِنْ شَرِّ مَا خَلَقَ» أي: من شرِّ كلِّ ذي شرّ، من إنسٍ أو جنٍّ أو حيواناتٍ أو غيرها. «وَمِنْ شَرِّ غَاسِقٍ إِذَا وَقَبَ»: من شرِّ الليل وما يكونُ فيه إذا أقبلَ بظلامِه، أو القَمَر إذا خَسَفَ. و«الغَسَق»: الظُّلْمة، و«الوقوب»: الدُّخول. «وَمِنْ شَرِّ النَّفَّاثَاتِ فِي الْعُقَدِ»: السواحِر إذا نَفَثْنَ في العُقَد التي يَعْقِدْنها على السِّحْر.",
        "surah_nas" to "«الْوَسْوَاسِ الْخَنَّاسِ»: الشيطان الذي يخنَس (يرجع) إذا ذُكِرَ الله، فإذا غفلَ الإنسان وَسْوَس. «مِنَ الْجِنَّةِ وَالنَّاسِ» أي: من شياطين الإنسِ والجِنِّ؛ فهم يُوَسْوِسونَ في صُدُور الناس. أو هذا الشيطانُ يُوَسْوِسُ في صُدُورِ الناسِ جِنِّهم وإنْسِهم.",
        "sleep_bismika" to "بذكر اسمك أحيا وعليه أموت؛ والموت والحياة يحتملان النوم واليقظة، أو الموت والبعث. والمناسبة أن يكون ختام عمل اليوم ذكر الله.",
        "ayat_kursi" to "«لَا إِلَٰهَ إِلَّا هُوَ» أي: لا معبود بحقِّ إلَّا هو. «الْقَيُّومُ»: الذي قامَ بنفسه وقامَ به غيرُه، فهو سبحانه القائم بذاته لا يحتاج إلى أحد، والقائِم على غيره يحتاج إليه كلُّ أحدٍ، يقوم بأمور السماوات والأرض ومَن فيهنَّ، وهو القائم على كلِّ شيء. «لَا تَأْخُذُهُ سِنَةٌ» أي: نُعاس، وهو مقدِّمة النوم، «وَلَا نَوْمٌ»؛ لأنَّ هذا نقصٌ لا يليقُ بالله تعالى، والله تعالى منزَّهٌ عن ذلك، ويستحيلُ في حقِّه النوم؛ لأنَّ النائم يغيبُ عما حولَه ولا يغيبُ على الله شيء، والنوم غفلة والله لا يغفُل عن شيء سبحانه، والنوم راحةٌ من التعَب والله تعالى منزَّهٌ عن ذلك، فلا يَمَسُّه إعياءٌ ولا تَعَب. «مَنْ ذَا الَّذِي يَشْفَعُ عِنْدَهُ إِلَّا بِإِذْنِهِ»: لا أحدَ يشفَعُ عنده إلَّا بإذْنِه وأمرِه وإرادتِه، بأن يأذنَ للشافع أن يشفَع، وبأن يرضى الله تعالى عن المشفوع له أن يُشَفَّع فيه. «يَعْلَمُ مَا بَيْنَ أَيْدِيهِمْ» أي: ما هو حاضرٌ أمامَهم وشاهِدٌ، وما يكون في المستقبَل، «وَمَا خَلْفَهُمْ» أي: عِلم الماضي. فيعلَم سبحانه الماضي والحاضر والمستقبَل. «وَلَا يَئُودُهُ حِفْظُهُمَا» أي: لا يُثْقِلُه ولا يُتْعِبُه حِفظُ السماوات والأرض ومَا فيهما ومَا بينهما.",
        "sleep_tasbih" to "«سبحان الله»: تنزيه الله عن كل نقص؛ ثلاثًا وثلاثين عند النوم. علّمه النبي ﷺ عليًّا وفاطمة مع التحميد والتكبير، وقال: هو خير لكما من خادم.",
        "sleep_tahmid" to "«الحمد لله»: إثبات الكمال لله مع محبته وتعظيمه؛ ثلاثًا وثلاثين عند النوم.",
        "sleep_takbir" to "«الله أكبر»: إثبات أن الله أكبر من كل شيء؛ أربعًا وثلاثين عند النوم، وبه تتم المائة.",
        "sleep_submission" to "«أسلمت وجهي إليك»: استسلمت لك وانقدت، و«فوضت أمري»: توكلت عليك في شأني كله، و«ألجأت ظهري»: أسندته إلى حفظك. والرغبة في ثوابك والرهبة من عقابك، ولا مهرب منك إلا إليك. ومن مات من ليلته مات على الفطرة؛ ويحافظ على لفظ «وبنبيك» كما صحّحه النبي ﷺ للبراء.",
        "sleep_last_two_baqarah" to "من قرأ بهما في ليلة كفتاه؛ قيل: من قيام الليل، وقيل: من الشيطان، وقيل: من الآفات والسوء، ويحتمل الجميع. وفيهما الثناء على انقياد المؤمنين وابتهالهم ورجوعهم إلى الله، ووقتهما الليل بعد غروب الشمس.",
        "home_enter" to "إذا ذكر العبد الله عند دخول بيته وعند طعامه قال الشيطان: لا مبيت لكم ولا عشاء؛ والسلام على أهل البيت تحية من عند الله مباركة طيبة.",
        "home_leave" to "الخروج باسم الله والتوكل عليه والبراءة من الحول والقوة إلا به؛ فيقال لقائله: هُديت وكُفيت ووُقيت، وتتنحى عنه الشياطين.",
        "before_food" to "التسمية قبل الطعام سبب للبركة ومنع مشاركة الشيطان.",
        "food_forgot_bismillah" to "من نسي التسمية في أوله قالها حين يذكر؛ وذكر الأول والآخر يراد به شمول الطعام كله، لا إخراج وسطه.",
        "after_food_drink" to "إن الله ليرضى عن العبد أن يأكل الأكلة فيحمده عليها، أو يشرب الشربة فيحمده عليها؛ فهو شكر للنعمة.",
        "toilet_enter" to "يقال عند إرادة الدخول. و«الخبث والخبائث»: ذكران الشياطين وإناثهم، وقيل: الشر والمكروه والمعاصي.",
        "toilet_leave" to "أي: أسألك غفرانك؛ فإذا تخفف العبد من أذى الجسد تذكّر أذى الإثم فسأل التخفف منه. وقيل: اعتراف بالعجز عن شكر نعمة الطعام والشراب.",
        "mosque_enter" to "يناسب الدخول سؤال الرحمة؛ لأن العبد يدخل ليعمل ما يقربه من ثواب الله وجنته.",
        "mosque_leave" to "يناسب الخروج سؤال الفضل؛ لأنه وقت الاشتغال بابتغاء الرزق الحلال.",
        "travel" to "تنزيه الله عند شهود نعمة تسخير المركوب، وتذكير بالرجوع إليه. «الصاحب في السفر»: الحافظ المعين، و«الخليفة في الأهل»: القائم بحفظهم في غيبة المسافر. و«وعثاء السفر»: مشقته، و«كآبة المنظر»: ما يحزن من فوات مطلوب أو وقوع محذور، و«سوء المنقلب»: الرجوع بما يسوء في المال والأهل.",
        "waking" to "حمد الله على الإحياء بعد النوم الذي يشبه الموت، ففي اليوم الجديد فرصة للعلم والعمل والتوبة؛ و«إليه النشور»: إليه رجوع الخلق بعد البعث، فلا ينسى العبد آخرته عند ابتداء يومه.",
        "after_wudu" to "تجديد لشهادة التوحيد والرسالة بعد طهارة البدن؛ ومن قالها بعد إسباغ الوضوء فُتحت له أبواب الجنة الثمانية يدخل من أيها شاء.",
        "worry" to "«الهم»: لما يُتوقع من مكروه، و«الحزن»: لما فات. و«العجز»: ضد القدرة، و«الكسل»: التثاقل عن الخير. و«ضلع الدين»: ثقله وشدته، و«غلبة الرجال»: قهرهم وتسلطهم.",
        "dua_huda_tuqa" to "سؤال الله الهداية إلى الحق، والتقوى بطاعته، والعفاف عن الحرام، والغنى والكفاية عن الخلق.",
        "la_hawla_quwwata" to "لا حركة ولا استطاعة إلا بمشيئة الله وعونه؛ ففيها التفويض والتبرؤ من الاعتماد على حول النفس وقوتها. ووصفها النبي ﷺ بأنها كنز من كنوز الجنة.",
        "evening_refuge" to "«كَلِمَاتِ اللَّهِ التَّامَّاتِ» أي: الكاملات التي لا يدخل فيها نقصٌ ولا عيب. وقيل: النافعة الشافية. وقيل: المراد بـ«الكلمات» هنا: القرآن. و«مِنْ شَرِّ مَا خَلَقَ» أي: من كلِّ شرٍّ، ومن أيِّ مخلوقٍ قام به الشرُّ، من حيوانٍ أو غيرِه، إنسيًّا كان أو جنيًّا، جمادًا أو غيرَه، كريحٍ أو صاعقةٍ. فيستحضِرُ العبدُ أنَّ هذه استعاذة والتجاء واعتصام بالمَلِك القَدير، الذي لا يقدر على دفع شرِّ كلِّ ذي شرٍ إلا هو سبحانه.",
    )

    private val byId: Map<String, DhikrEntry> =
        (baseEntries.map { entry -> explanations[entry.id]?.let { entry.copy(explanation = it) } ?: entry } +
            DhikrAdditionalCatalog.entries)
            .map { entry -> DhikrNarrations.byId[entry.id]?.let { entry.copy(narration = it) } ?: entry }
            .associateBy { it.id }

    /**
     * Default reading order of each collection. Morning and evening follow, item for item, the list of
     * «31 فائدة في أذكار الصباح والمساء» (benefit 28); the morning list then adds the fourfold tasbih,
     * which the booklet counts among the adhkar of any time. The others follow the usual sequence of the adhkar books
     * (e.g. «حصن المسلم»): Qur'an first, the longer supplications next, and the counted tasbih last;
     * at bedtime the supplication the hadith says to make last comes last. A user's own reordering
     * takes precedence; ids here must match each entry's [DhikrEntry.categories].
     */
    val collectionOrder: Map<DhikrCategory, List<String>> = linkedMapOf(
        DhikrCategory.MORNING to listOf(
            "morning_kingdom", "bika_asbahna", "fitrah_islam", "ayat_kursi", "surah_ikhlas", "surah_falaq",
            "surah_nas", "sayyid_istighfar", "afiyah_ask", "fatir_samawat", "bismillah_protection", "evening_refuge",
            "ya_hayyu", "afini_badani", "kufr_faqr", "tahlil_ashr", "subhanallah_bihamdih", "subhan_count",
        ),
        DhikrCategory.EVENING to listOf(
            "evening_kingdom", "bika_amsayna", "fitrah_islam_evening", "ayat_kursi", "surah_ikhlas", "surah_falaq",
            "surah_nas", "sayyid_istighfar", "afiyah_ask", "fatir_samawat", "bismillah_protection", "evening_refuge",
            "ya_hayyu", "afini_badani", "kufr_faqr", "tahlil_ashr", "subhanallah_bihamdih",
        ),
        DhikrCategory.NIGHT to listOf("sayyid_istighfar", "sleep_last_two_baqarah"),
        DhikrCategory.SALAH to listOf(
            "salah_istighfar", "salah_salam", "salah_la_mani", SALAH_HUNDRED_ID,
            "ayat_kursi", "surah_ikhlas", "surah_falaq", "surah_nas",
        ),
        DhikrCategory.PRAYER to listOf(
            "adhan_response", "adhan_shahada", "after_adhan_wasila", "salah_noor", "takbir_ihram", "istiftah",
            "ruku_tasbih", "ruku_rise", "ruku_hamd", "sujud_tasbih", "sujud_shared", "between_sujud",
            "before_taslim", "taslim",
        ),
        DhikrCategory.SLEEP to listOf(
            "surah_ikhlas", "surah_falaq", "surah_nas", "ayat_kursi", "sleep_last_two_baqarah", "sleep_rahma_hifz",
            "sleep_qini", "sleep_bismika", "sleep_tasbih", "sleep_tahmid", "sleep_takbir", "sleep_hamd_kifaya",
            "fatir_samawat", "sleep_ghufr_rihan", "sleep_submission",
        ),
        DhikrCategory.HOME to listOf("home_leave", "home_enter", "home_enter_bismillah"),
        DhikrCategory.DAILY to listOf(
            // Waking, purification and the mosque.
            "waking", "waking_afiyah", "tahajjud", "toilet_enter", "toilet_leave", "after_wudu",
            "mosque_enter", "mosque_enter_refuge", "mosque_leave",
            // Food and travel.
            "before_food", "food_forgot_bismillah", "after_food_drink",
            "travel_farewell", "travel", "evening_refuge", "travel_return",
            // Distress, decisions and daily occasions.
            "worry", "hamm_quran", "karb_tawhid", "dhi_nun", "rahma_urgent", "istikhara",
            "good_news", "bad_news", "afflicted", "anger", "sneeze", "majlis_kaffara", "jaza_khayran",
            "market", "enemy",
            // Marriage and children.
            "nikah_congrats", "marriage_dua", "intimacy", "newborn",
            // Illness and death.
            "sick_visit", "talqin", "condolence", "janazah_dua", "graves_visit",
            // Unrestricted dhikr.
            SALAWAT_ID, "tahlil_ashr", "istighfar_absolute", "subhan_count", "kalimatan_khafifatan",
            "tasbih_arba", "la_hawla_quwwata", "dua_huda_tuqa",
        ),
    )

    /** The full library follows the collections in turn; each dhikr appears once, where it is first read. */
    val entries: List<DhikrEntry> = collectionOrder.values.flatten().distinct().mapNotNull(byId::get).let { ordered ->
        ordered + byId.values.filterNot { it in ordered }
    }

    fun find(id: String): DhikrEntry? = byId[id]
}

/** The after-prayer clause of the three protective surahs' source. */
private const val AFTER_EVERY_PRAYER = "دبر كل صلاة مرة: سنن أبي داود 1523 (صحيح)"

/** Shared by the three protective surahs; `%s` is the surah reference. */
private const val SURAH_OCCASIONS =
    "%s؛ ثلاثًا صباحًا ومساءً: سنن أبي داود 5082 (حسن)؛ " + AFTER_EVERY_PRAYER + "؛ وعند النوم مع النفث في الكفين ومسح الجسد ثلاثًا: صحيح البخاري 5017"

/** The same surahs after the prayer, cited by name; `%s` is the surah's name. */
private const val SURAH_AFTER_SALAH = "%s؛ " + AFTER_EVERY_PRAYER
