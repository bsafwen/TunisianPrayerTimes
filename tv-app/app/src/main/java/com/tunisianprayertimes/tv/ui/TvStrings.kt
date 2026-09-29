package com.tunisianprayertimes.tv.ui

import com.tunisianprayertimes.Prayer

/**
 * Arabic strings for the TV app.
 */
object TvStrings {
    const val APP_NAME = "أوقات الصلاة تونس"
    const val MOSQUE_DEFAULT = "مسجد"

    // Setup wizard
    const val SETUP_WELCOME = "بسم الله الرحمن الرحيم"
    const val SETUP_WELCOME_SUB = "مرحبًا بكم في تطبيق أوقات الصلاة"
    const val SETUP_SELECT_GOUVERNORAT = "اختر الولاية"
    const val SETUP_SELECT_DELEGATION = "اختر المعتمدية"
    const val SETUP_IQAMAH_TITLE = "إعداد أوقات الإقامة"
    const val SETUP_IQAMAH_SUBTITLE = "حدد التأخير بالدقائق بعد الأذان لكل صلاة"
    const val SETUP_MOSQUE_NAME = "اسم المسجد (اختياري)"
    const val SETUP_MOSQUE_HINT = "مثال: مسجد الفتح"
    const val SETUP_DONE = "تم الإعداد بنجاح"
    const val SETUP_START = "ابدأ"
    const val NEXT = "التالي"
    const val PREVIOUS = "السابق"
    const val CONFIRM = "تأكيد"
    const val SAVE = "حفظ"
    const val CANCEL = "إلغاء"
    const val MINUTES_SUFFIX = "د"
    const val DELAY_MODE = "تأخير"
    const val FIXED_TIME_MODE = "وقت ثابت"
    const val IQAMAH_LABEL = "الإقامة"
    const val DURATION_LABEL = "مدة الصلاة"
    const val FIXED_SUFFIX = "ثابت"

    // USB settings import
    const val USB_FOUND_TITLE = "وُجد ملف إعدادات على مفتاح USB"
    const val USB_NO_CHANGES = "الإعدادات على المفتاح مطابقة لإعدادات الشاشة"
    const val USB_ERROR_TITLE = "لم تُطبَّق الإعدادات"
    const val USB_ERROR_HINT = "صحّح الملف على الحاسوب ثم أعد إدخال المفتاح"
    const val USB_APPLY = "تطبيق"
    const val USB_OK = "حسنًا"
    const val USB_INACCESSIBLE = "لا يسمح هذا الجهاز للتطبيقات بقراءة مفتاح USB: عدّل الإقامة ومدة الصلاة من شاشة الإعدادات"

    // Ramadan and Eid dates
    const val SETTINGS_ISLAMIC_DATES = "تواريخ رمضان والعيد"
    const val ISLAMIC_DATES_TITLE = "تواريخ رمضان والعيد لسنة"
    const val ISLAMIC_DATES_HINT = "تُحدَّد تلقائيًا من الإعلان الرسمي أو التقدير، ويمكن تعديل كل تاريخ يومًا أو أكثر"
    const val RAMADAN_START = "بداية رمضان"
    const val EID_FITR = "عيد الفطر"
    const val EID_ADHA = "عيد الأضحى"
    const val SOURCE_MANUAL = "يدوي"
    const val SOURCE_OFFICIAL = "رسمي"
    const val SOURCE_ESTIMATE = "تقديري"
    const val ANNOUNCED_DATE = "التاريخ المعلن رسميًا"
    const val AUTOMATIC = "تلقائي"
    const val AFTER_SALAH_TEXTS = "أذكار بعد الصلاة"
    const val TICKER_TEXTS = "شريط الأذكار"
    const val BUNDLED_TEXTS = "استعادة النصوص المضمّنة"
    const val BUNDLED_TEXTS_CONFIRM = "تعود أذكار ما بعد الصلاة والشريط إلى النصوص المضمّنة المراجَعة، وتُحذف قائمتا المسجد. يمكن إرجاعهما بعد ذلك من «التراجع عن آخر استيراد»."
    const val BUNDLED_TEXTS_DO = "نعم، استعد النصوص المضمّنة"

    // Clock
    const val CLOCK_WRONG_TITLE = "ساعة الجهاز غير صحيحة"
    const val CLOCK_WRONG_HINT = "لا تُعرض أوقات الصلاة حتى يُضبط الوقت: اضبطه هنا أو من إعدادات الجهاز، أو أكّد أنه صحيح"
    const val CLOCK_DEVICE_TIME = "وقت الجهاز"
    const val CLOCK_MONTH = "الشهر"
    const val CLOCK_DATE = "التاريخ"
    const val CLOCK_HOUR = "الساعة"
    const val CLOCK_MINUTE = "الدقيقة"
    const val CLOCK_SET_HERE = "اعتماد هذا الوقت"
    const val CLOCK_OPEN_SETTINGS = "فتح إعدادات التاريخ والوقت"
    const val CLOCK_CONFIRM = "وقت الجهاز صحيح"
    const val CLOCK_WRONG_ZONE = "المنطقة الزمنية للجهاز ليست توقيت تونس: تُعرض الأوقات بتوقيت تونس، واضبط المنطقة الزمنية من إعدادات الجهاز"

    fun usbTemplateWritten(packageName: String) =
        "كُتب ملف الإعدادات على مفتاح USB في Android/data/$packageName/files/mosque-tv.json: عدّله على الحاسوب ثم أعد إدخال المفتاح"
    const val FRIDAY_IQAMAH = "إقامة الجمعة"

    // Main display
    const val SUNRISE = "الشروق"
    const val NEXT_PRAYER = "الصلاة القادمة"
    const val NO_DATA = "لا تتوفر بيانات لهذا اليوم"

    // Prayer names
    const val FAJR = "الفجر"
    const val DHUHR = "الظهر"
    const val ASR = "العصر"
    const val MAGHRIB = "المغرب"
    const val ISHA = "العشاء"
    const val JOMOAA = "الجمعة"

    // Transition screens
    const val ALLAHU_AKBAR = "الله أكبر"
    const val IQAMAH_SOON = "الإقامة بعد"
    const val PRAYER_STARTED = "أُقيمت الصلاة"
    const val SILENCE_PHONES = "أغلقوا هواتفكم"

    // Settings
    const val SETTINGS_TITLE = "الإعدادات"
    const val SETTINGS_KIOSK = "التشغيل الدائم للشاشة"
    const val SETTINGS_PHONE = "لوحة الإدارة من الهاتف"
    const val ANNOUNCEMENT_LABEL = "إعلان"
    const val USB_COPYING = "جارٍ نسخ الصور من مفتاح USB…"
    const val DASHBOARD_OPEN = "لوحة الإدارة مفتوحة"
    const val TEXTS_ADDED = "يُضاف"
    const val TEXTS_REMOVED = "يُحذف"
    const val TEXTS_RECOUNTED = "يتغيّر عدد المرات"
    const val TEXTS_REORDERED = "يتغيّر ترتيب النصوص"
    fun andOthers(count: Int) = if (count == 1) "ونص آخر" else "و$count غيرها"
    const val WEATHER_ENABLED = "عرض الطقس (عند الاتصال بالإنترنت)"
    const val ANNOUNCEMENTS_EVERY = "عرض الإعلانات بين الصلوات كل"
    const val ANNOUNCEMENTS_EVERY_OFF = "بعد الصلاة فقط"
    const val MINUTES_WORD = "دقيقة"
    const val ALLOW_UPDATES = "السماح بتثبيت التحديثات"
    const val EXIT_TO_ANDROID = "الخروج إلى إعدادات الجهاز"
    const val HOLD_OK_HINT = "اضغط مطولًا على زر OK لفتح الإعدادات"
    const val UNDO_IMPORT = "التراجع عن آخر استيراد"
    const val RESET_ALL = "إعادة ضبط الشاشة"
    const val RESET_CONFIRM = "ستُمحى كل الإعدادات (المسجد، الموقع، الإقامة، التواريخ) ثم يبدأ الإعداد من جديد"
    const val RESET_DO = "نعم، أعد الضبط"
    const val ABOUT = "حول التطبيق"
    const val LOCATION_CONFIRM = "تغيير موقع المسجد إلى"
    const val TODAY_BEFORE_AFTER = "أوقات اليوم: الحالية ← الجديدة"
    const val MOSQUE_NAME_LABEL = "اسم المسجد"
    const val THEME_LABEL = "المظهر"
    const val SETTINGS_LOCATION = "الموقع"
    const val SETTINGS_IQAMAH = "أوقات الإقامة"
    const val SETTINGS_MOSQUE_NAME = "اسم المسجد"
    const val SETTINGS_DISPLAY = "العرض"
    const val SETTINGS_ANNOUNCEMENTS = "الإعلانات"
    const val SETTINGS_CUSTOM_BG = "خلفيات مخصصة"
    const val SETTINGS_THEME = "المظهر"
    const val ANNOUNCEMENTS_ENABLED = "تفعيل الإعلانات"
    const val CUSTOM_BG_ENABLED = "تفعيل الخلفيات المخصصة"
    const val ANNOUNCEMENT_INTERVAL = "مدة عرض كل إعلان"
    const val SECONDS_SUFFIX = "ث"
    const val MEDIA_HINT = "ضع الصور على مفتاح USB بجانب ملف الإعدادات mosque-tv.json ثم أدخله في الجهاز"
    const val BACKGROUNDS_FOLDER_HINT = "backgrounds/ — صور الخلفيات (JPG أو PNG أو WebP)"
    const val ANNOUNCEMENTS_FOLDER_HINT = "announcements/ — صور الإعلانات، وملفات نصية ‎.txt لكل إعلان مكتوب"
    const val DELETE_IMAGES = "حذف كل الصور"
    const val USB_MEDIA_TITLE = "وُجدت صور على مفتاح USB"
    const val USB_MEDIA_HINT = "تحلّ محل الصور الحالية على الشاشة من النوع نفسه"
    const val BACKGROUNDS_LABEL = "صور الخلفية"
    const val ANNOUNCEMENT_IMAGES_LABEL = "ملفات الإعلانات (صور ونصوص)"
    const val TEXT_ANNOUNCEMENTS = "الإعلانات المكتوبة"
    const val NO_MEDIA_FOUND = "لا توجد ملفات"
    const val MEDIA_FILES_COUNT = "ملفات"

    // Adhan/Iqamah labels
    const val ADHAN = "الأذان"
    const val IQAMAH = "الإقامة"

    // After-salah
    const val AFTER_SALAH_TITLE = "أذكار بعد الصلاة"

    // Ramadan and Eid
    const val RAMADAN = "رمضان"
    const val RAMADAN_BANNER = "رمضان كريم"
    const val IFTAR_COUNTDOWN = "الإفطار بعد"
    const val IMSAK_COUNTDOWN = "الإمساك بعد"
    const val EID_MUBARAK = "عيد مبارك"
    const val EID_PRAYER_AT = "صلاة العيد"
    const val EID_PRAYER_IN = "صلاة العيد بعد..."
    const val ARAFAH = "يوم عرفة"
    const val EID_AFTER_SUNRISE = "صلاة العيد: الدقائق بعد الشروق"

    // Friday
    const val KHUTBA_TIME = "وقت الخطبة"
    const val KHUTBA_LISTEN = "الإنصات للخطبة"
    const val JOMOAA_REMINDER = "صلاة الجمعة"


    /** "3 مرات", "33 مرة"; nothing for a single reading. */
    fun times(count: Int): String? = when {
        count <= 1 -> null
        count == 2 -> "مرتان"
        count <= 10 -> "$count مرات"
        else -> "$count مرة"
    }

    /** Written out in words, so right-to-left layout cannot reverse "2 / 4". */
    fun part(part: Int, parts: Int): String = "الجزء $part من $parts"

    fun progress(index: Int, total: Int): String = "$index من $total"

    /** The row label in the iqamah tables. */
    fun editorLabel(prayer: Prayer): String = if (prayer == Prayer.JOMOAA) FRIDAY_IQAMAH else prayerName(prayer)

    fun prayerName(prayer: Prayer): String = when (prayer) {
        Prayer.FAJR -> FAJR
        Prayer.DHUHR -> DHUHR
        Prayer.ASR -> ASR
        Prayer.MAGHRIB -> MAGHRIB
        Prayer.ISHA -> ISHA
        Prayer.JOMOAA -> JOMOAA
        Prayer.AID_FITR -> com.tunisianprayertimes.ui.Strings.PRAYER_AID_FITR
        Prayer.AID_ADHA -> com.tunisianprayertimes.ui.Strings.PRAYER_AID_ADHA
    }

    // Dates and times, written the Tunisian way: "الثلاثاء 29 سبتمبر 2026", "05:01", "01:24:48".

    /** The months as Tunisia names them (جانفي، فيفري، …), not the Levant's or Egypt's. */
    val MONTHS = listOf("جانفي", "فيفري", "مارس", "أفريل", "ماي", "جوان", "جويلية", "أوت", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")
    /** Monday first, as [java.time.DayOfWeek]. */
    val WEEKDAYS = listOf("الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد")

    /** "الثلاثاء 29 سبتمبر 2026", or without the weekday. */
    fun gregorianDate(date: java.time.LocalDate, withWeekday: Boolean = true): String {
        val day = "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]} ${date.year}"
        return if (withWeekday) "${WEEKDAYS[date.dayOfWeek.value - 1]} $day" else day
    }

    /** "05:01". */
    fun hm(time: java.time.LocalTime): String = String.format(java.util.Locale.ROOT, "%02d:%02d", time.hour, time.minute)

    /** A countdown: "01:24:48" from an hour up, "29:12" below; never negative. */
    fun countdown(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format(java.util.Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }

    // ── «أفق»: main screen ──

    // ── «أفق»: prayer flow screens (adhan, iqamah, khutba, adhkar, night, Eid) ──

    const val ADHAN_NOW = "حان الآن وقت صلاة"
    const val IQAMAH_OF = "إقامة صلاة"
    /** Before an Eid prayer's name: it has no adhan or iqamah. */
    const val PRAYER_OF = "صلاة"
    const val AFTER = "بعد"
    /** Beside the Eid prayer's time on the countdown, where other prayers show their iqamah. */
    const val PRAYER_LABEL = "الصلاة"
    const val PHONES_OFF = "الرجاء إغلاق الهاتف"
    const val KHUTBA_SILENCE = "الرجاء الإنصات أثناء الخطبة"

    /**
     * When the text beside the muezzin is said, by its catalog id ([com.tunisianprayertimes.mosque.MosqueAdhkar.ADHAN_IDS]).
     * The reply is only for the two calls to prayer: the rest of the adhan is repeated after him.
     */
    fun adhanCaption(entryId: String?): String? = when (entryId) {
        "adhan_response" -> "يُقال عند «حيّ على الصلاة» و«حيّ على الفلاح»"
        "adhan_shahada" -> "يُقال عند سماع المؤذّن"
        "after_adhan_wasila" -> "يُقال بعد الأذان"
        else -> null
    }

    // ── «أفق»: announcements and notices ──

    // ── «أفق»: settings and admin pages ──
}
