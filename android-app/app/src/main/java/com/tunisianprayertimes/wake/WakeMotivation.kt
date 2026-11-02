package com.tunisianprayertimes.wake

/** The reviewed selection: the retained 36 messages and the 12 approved additions about resolve. */
internal data class WakeMotivation(
    val reviewId: String,
    val text: String,
    val source: String,
    val sourceUrl: String,
    val topics: Set<WakeMotivationTopic>,
    val note: String? = null,
)

internal enum class WakeMotivationTopic {
    GENERAL, MORNING, PRAYER, FAJR, FAJR_SUNNAH, ASR, NIGHT_PRAYER, PRE_DAWN, QURAN_READING,
}

internal object WakeMotivations {
    private fun message(
        id: String,
        text: String,
        source: String,
        url: String,
        vararg topics: WakeMotivationTopic,
        note: String? = null,
    ) = WakeMotivation(
        id, text, source, url,
        topics.toSet().ifEmpty { setOf(WakeMotivationTopic.GENERAL) },
        note,
    )

    val all: List<WakeMotivation> = listOf(
        message(
            "1", "اللهم بارك لأمتي في بكورها",
            "حديث نبوي · رواه أبو داود والترمذي", "https://hadeethenc.com/ar/browse/hadith/5941",
            WakeMotivationTopic.MORNING,
        ),
        message(
            "2", "الحمد لله الذي أحيانا بعدما أماتنا، وإليه النشور",
            "دعاء الاستيقاظ · رواه البخاري (6324)", "https://hadeethenc.com/ar/browse/hadith/65913",
            WakeMotivationTopic.MORNING,
        ),
        message(
            "3", "ركعتا الفجر خير من الدنيا وما فيها",
            "حديث نبوي · رواه مسلم (725)", "https://hadeethenc.com/ar/browse/hadith/66400",
            WakeMotivationTopic.FAJR_SUNNAH,
            note = "ركعتا سنة الفجر قبل الفريضة، بعد دخول وقت الفجر.",
        ),
        message(
            "4", "من صلى صلاة الصبح فهو في ذمة الله",
            "من حديث رواه مسلم (657)", "https://hadeethenc.com/ar/browse/hadith/5435",
            WakeMotivationTopic.FAJR,
        ),
        message(
            "5", "من صلى البردين دخل الجنة",
            "حديث نبوي · متفق عليه", "https://hadeethenc.com/ar/browse/hadith/4198",
            WakeMotivationTopic.FAJR, WakeMotivationTopic.ASR,
            note = "البردان: الفجر والعصر.",
        ),
        message(
            "6", "﴿إِنَّ قُرْآنَ الْفَجْرِ كَانَ مَشْهُودًا﴾",
            "القرآن الكريم · الإسراء: 78", "https://quran.com/17/78", WakeMotivationTopic.FAJR,
        ),
        message(
            "7", "﴿وَأَقِمِ الصَّلَاةَ لِذِكْرِي﴾",
            "القرآن الكريم · طه: 14", "https://quran.com/20/14", WakeMotivationTopic.PRAYER,
        ),
        message(
            "8", "﴿إِنَّ الصَّلَاةَ تَنْهَىٰ عَنِ الْفَحْشَاءِ وَالْمُنْكَرِ﴾",
            "القرآن الكريم · العنكبوت: 45", "https://quran.com/29/45", WakeMotivationTopic.PRAYER,
        ),
        message(
            "9", "أقرب ما يكون العبد من ربه وهو ساجد، فأكثروا الدعاء",
            "حديث نبوي · رواه مسلم (482)", "https://hadeethenc.com/ar/browse/hadith/5382",
            WakeMotivationTopic.PRAYER,
        ),
        message(
            "10", "يا بلال، أقم الصلاة، أرحنا بها",
            "حديث نبوي · رواه أبو داود (4985)", "https://hadeethenc.com/ar/browse/hadith/65095",
            WakeMotivationTopic.PRAYER,
        ),
        message(
            "11", "وكل خطوة يخطوها إلى الصلاة صدقة",
            "من حديث رواه البخاري (2989)", "https://hadeethenc.com/ar/browse/hadith/4568",
            WakeMotivationTopic.PRAYER,
        ),
        message(
            "12", "وأفضل الصلاة بعد الفريضة صلاة الليل",
            "من حديث رواه مسلم (1163)", "https://hadeethenc.com/ar/browse/hadith/11261",
            WakeMotivationTopic.NIGHT_PRAYER,
        ),
        message(
            "15", "﴿وَبِالْأَسْحَارِ هُمْ يَسْتَغْفِرُونَ﴾",
            "القرآن الكريم · الذاريات: 18", "https://quran.com/51/18", WakeMotivationTopic.PRE_DAWN,
        ),
        message(
            "17", "اقرؤوا القرآن؛ فإنه يأتي يوم القيامة شفيعًا لأصحابه",
            "حديث نبوي · رواه مسلم", "https://hadeethenc.com/ar/browse/hadith/5383",
            WakeMotivationTopic.QURAN_READING,
        ),
        message(
            "18", "﴿فَاقْرَؤُوا مَا تَيَسَّرَ مِنَ الْقُرْآنِ﴾",
            "القرآن الكريم · المزمل: 20", "https://quran.com/73/20", WakeMotivationTopic.QURAN_READING,
        ),
        message(
            "19", "﴿فَاذْكُرُونِي أَذْكُرْكُمْ وَاشْكُرُوا لِي وَلَا تَكْفُرُونِ﴾",
            "القرآن الكريم · البقرة: 152", "https://quran.com/2/152",
        ),
        message(
            "20", "﴿أَلَا بِذِكْرِ اللَّهِ تَطْمَئِنُّ الْقُلُوبُ﴾",
            "القرآن الكريم · الرعد: 28", "https://quran.com/13/28",
        ),
        message(
            "21", "احرص على ما ينفعك، واستعن بالله ولا تعجز",
            "من حديث رواه مسلم (2664)", "https://hadeethenc.com/ar/browse/hadith/5493",
        ),
        message(
            "22", "سُئل النبي ﷺ: أي الأعمال أحب إلى الله؟ قال: «أدومها وإن قل»",
            "من حديث متفق عليه · البخاري (6465)، مسلم (783)", "https://hadeethenc.com/ar/browse/hadith/66227",
        ),
        message(
            "28", "فسددوا وقاربوا، وأبشروا",
            "من حديث رواه البخاري (39)", "https://hadeethenc.com/ar/browse/hadith/5795",
        ),
        message(
            "29", "﴿فَاسْتَبِقُوا الْخَيْرَاتِ﴾",
            "القرآن الكريم · البقرة: 148", "https://quran.com/2/148",
        ),
        message(
            "30", "﴿وَافْعَلُوا الْخَيْرَ لَعَلَّكُمْ تُفْلِحُونَ﴾",
            "القرآن الكريم · الحج: 77", "https://quran.com/22/77",
        ),
        message(
            "31", "﴿فَمَنْ يَعْمَلْ مِثْقَالَ ذَرَّةٍ خَيْرًا يَرَهُ﴾",
            "القرآن الكريم · الزلزلة: 7", "https://quran.com/99/7",
        ),
        message(
            "32", "﴿فَإِنَّ مَعَ الْعُسْرِ يُسْرًا﴾",
            "القرآن الكريم · الشرح: 5", "https://quran.com/94/5",
        ),
        message(
            "33", "﴿وَمَنْ يَتَوَكَّلْ عَلَى اللَّهِ فَهُوَ حَسْبُهُ﴾",
            "القرآن الكريم · الطلاق: 3", "https://quran.com/65/3",
        ),
        message(
            "34", "﴿وَاصْبِرْ فَإِنَّ اللَّهَ لَا يُضِيعُ أَجْرَ الْمُحْسِنِينَ﴾",
            "القرآن الكريم · هود: 115", "https://quran.com/11/115",
        ),
        message(
            "35", "﴿وَالَّذِينَ جَاهَدُوا فِينَا لَنَهْدِيَنَّهُمْ سُبُلَنَا﴾",
            "القرآن الكريم · العنكبوت: 69", "https://quran.com/29/69",
        ),
        message(
            "36", "﴿فَإِذَا فَرَغْتَ فَانْصَبْ ۝ وَإِلَىٰ رَبِّكَ فَارْغَبْ﴾",
            "القرآن الكريم · الشرح: 7–8", "https://quran.com/94/7-8",
        ),
        message(
            "37", "إذا أصبحت فلا تنتظر المساء",
            "عبد الله بن عمر · رواه البخاري", "https://hadeethenc.com/ar/browse/hadith/4704",
            WakeMotivationTopic.MORNING,
        ),
        message(
            "39", "فإن اليوم عمل ولا حساب، وغدًا حساب ولا عمل",
            "علي بن أبي طالب · ذكره البخاري تعليقًا", "https://shamela.ws/book/1681/9633",
        ),
        message(
            "42", "رب عمل صغير تعظمه النية، ورب عمل كبير تصغره النية",
            "عبد الله بن المبارك · نقله ابن رجب في جامع العلوم والحكم", "https://shamela.ws/book/10443/65",
        ),
        message(
            "46", "من علامة كمال العقل علو الهمة",
            "ابن الجوزي · صيد الخاطر", "https://shamela.ws/book/12028/31",
        ),
        message(
            "47", "من عرف قدر مطلوبه هان عليه ما يبذل فيه",
            "ابن القيم · مدارج السالكين", "https://shamela.ws/book/199/1015",
        ),
        message(
            "49", "يا أقدام الصبر، احملي! بقي القليل",
            "ابن القيم · الفوائد", "https://shamela.ws/book/212/120",
        ),
        message(
            "A1", "يا ابن آدم، إياك والتسويف، فإنك بيومك ولست بغد",
            "مروي عن الحسن البصري · قصر الأمل لابن أبي الدنيا", "https://shamela.ws/book/6899/213",
        ),
        message(
            "B2", "وجدنا خير عيشنا بالصبر",
            "عمر بن الخطاب · ذكره البخاري تعليقًا", "https://shamela.ws/book/1681/9701",
        ),
        message(
            "H1", "إذا هممت بأمر من طاعة الله، فلا تحبسه إن استطعت فُواقًا، حتى تمضيه",
            "مالك · ترتيب المدارك", "https://shamela.ws/book/31215/287",
            note = "فُواقًا: وقتًا يسيرًا؛ أي بادر بالطاعة ولا تؤخرها.",
        ),
        message(
            "H2", "وعليك بمعالي الأمور وكرائمها",
            "مالك · ترتيب المدارك", "https://shamela.ws/book/31215/287",
        ),
        message(
            "H3", "الراحة لا تُنال بالراحة",
            "أوردها ابن القيم في شفاء العليل", "https://shamela.ws/book/205/819",
        ),
        message(
            "H4", "ينبغي للإنسان أن يعرف شرف زمانه، وقدر وقته، فلا يضيع منه لحظة في غير قربة، ويقدم الأفضل فالأفضل من القول والعمل",
            "ابن الجوزي · صيد الخاطر", "https://shamela.ws/book/12028/43",
        ),
        message(
            "H5", "إذا استطعت أن لا يسبقك إلى الله أحد فافعل",
            "وهيب بن الورد · نقله الذهبي في تاريخ الإسلام", "https://shamela.ws/book/12397/5116",
            note = "المقصود المبالغة في الحث على الاجتهاد في الطاعة، كما بيّن الذهبي.",
        ),
        message(
            "H6", "كابدت الصلاة عشرين سنة، وتنعمت بها عشرين سنة",
            "ثابت البناني · نقله الذهبي في سير أعلام النبلاء", "https://shamela.ws/book/10906/3996",
            WakeMotivationTopic.PRAYER,
        ),
        message(
            "H7", "أهل الليل في ليلهم ألذّ من أهل اللهو في لهوهم",
            "أبو سليمان الداراني · المجالسة وجواهر العلم", "https://shamela.ws/book/9948/1655",
            WakeMotivationTopic.NIGHT_PRAYER,
        ),
        message(
            "H8", "ألا إن سلعة الله غالية، ألا إن سلعة الله الجنة",
            "من حديث رواه الترمذي · صحيح", "https://hadeethenc.com/ar/browse/hadith/5434",
        ),
        message(
            "H9", "حُجبت الجنة بالمكاره",
            "من حديث رواه البخاري (6487)", "https://hadeethenc.com/ar/browse/hadith/3702",
        ),
        message(
            "H10", "إن قامت على أحدكم القيامة وفي يده فسيلة فليغرسها",
            "حديث نبوي · رواه أحمد (12902) · صحيح", "https://hadeethenc.com/ar/browse/hadith/66161",
            note = "الفسيلة: شتلة نخل.",
        ),
        message(
            "H11", "﴿وَفِي ذَٰلِكَ فَلْيَتَنَافَسِ الْمُتَنَافِسُونَ﴾",
            "القرآن الكريم · المطففين: 26", "https://quran.com/83/26",
        ),
        message(
            "H12", "﴿فَإِذَا عَزَمْتَ فَتَوَكَّلْ عَلَى اللَّهِ﴾",
            "القرآن الكريم · آل عمران: 159", "https://quran.com/3/159",
        ),
    )

    fun eligible(topics: Set<WakeMotivationTopic>): List<WakeMotivation> = all.filter { message ->
        WakeMotivationTopic.GENERAL in message.topics || message.topics.any { it in topics }
    }

    /** Daily rotation without a network request, mutable counters, or rerolls during a ringing occurrence. */
    fun select(topics: Set<WakeMotivationTopic>, alarmId: String, epochDay: Long): WakeMotivation {
        val pool = eligible(topics)
        val index = Math.floorMod(epochDay + alarmId.hashCode().toLong(), pool.size.toLong()).toInt()
        return pool[index]
    }
}
