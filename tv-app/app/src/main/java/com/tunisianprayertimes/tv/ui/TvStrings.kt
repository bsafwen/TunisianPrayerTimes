package com.tunisianprayertimes.tv.ui

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.time.ClockSource
import com.tunisianprayertimes.tv.kiosk.AdminEntryDetector

/**
 * Arabic strings for the TV app.
 */
object TvStrings {
    const val APP_NAME = "أوقات الصلاة تونس"
    const val MOSQUE_DEFAULT = "مسجد"

    // Setup wizard (the basmala over the welcome is a reviewed text: DisplayTexts.BASMALA)
    const val SETUP_WELCOME_SUB = "مرحبًا بكم في تطبيق أوقات الصلاة"
    const val SETUP_SELECT_GOUVERNORAT = "اختر الولاية"
    const val SETUP_SELECT_DELEGATION = "اختر المعتمدية"
    const val SETUP_IQAMAH_TITLE = "إعداد أوقات الإقامة"
    const val SETUP_IQAMAH_SUBTITLE = "حدد التأخير بالدقائق بعد الأذان لكل صلاة"
    const val SETUP_MOSQUE_NAME = "اسم المسجد (اختياري)"
    const val SETUP_MOSQUE_HINT = "مثال: مسجد الفتح"
    const val NEXT = "التالي"
    const val PREVIOUS = "السابق"
    const val CONFIRM = "تأكيد"
    const val SAVE = "حفظ"
    const val CANCEL = "إلغاء"
    const val MINUTES_SUFFIX = "د"
    const val IQAMAH_LABEL = "الإقامة"
    const val DURATION_LABEL = "مدة الصلاة"

    // USB settings import
    const val USB_FOUND_TITLE = "وُجد ملف إعدادات على مفتاح USB"
    const val USB_NO_CHANGES = "الإعدادات على المفتاح مطابقة لإعدادات الشاشة"
    const val USB_ERROR_TITLE = "لم تُطبَّق الإعدادات"
    const val USB_ERROR_HINT = "صحّح الملف على الحاسوب ثم أعد إدخال المفتاح"
    const val USB_APPLY = "تطبيق"
    const val USB_OK = "حسنًا"
    /** The remote cannot set everything a key can (Ramadan's changes, texts, images): the phone can, offline. */
    const val USB_INACCESSIBLE = "لا يسمح هذا الجهاز للتطبيقات بقراءة مفتاح USB: اضبط الشاشة من الإعدادات، " +
        "أو من الهاتف دون إنترنت (الإعدادات ← لوحة الإدارة من الهاتف)"
    const val USB_READ_ONLY = "المفتاح مركَّب للقراءة فقط فلا يُكتب عليه ملف الإعدادات: هيّئه على الحاسوب بنظام FAT32 أو exFAT"
    /** An offer nobody answered, or found with nobody at the remote: a quiet line until the admin opens the settings. */
    const val USB_WAITING = "ملف على مفتاح USB بانتظار الرد: افتح الإعدادات"
    /** The undo reuses the import screen, in its own words: no key and no computer are involved. */
    const val UNDO_NO_CHANGES = "لا شيء للتراجع عنه: الإعدادات الحالية هي نفسها"
    const val UNDO_ERROR_HINT = "تعذّر التراجع: عدّل الإعدادات من الشاشة أو من الهاتف"
    /** «متقدم»: the TV's current settings onto a key, to copy to another screen; and a key's files offered again. */
    const val USB_EXPORT = "نسخ إعدادات الشاشة إلى مفتاح USB"
    const val USB_EXPORT_HINT = "لنقلها إلى شاشة أخرى؛ يبقى الملف السابق على المفتاح باسم mosque-tv.json.bak"
    const val USB_EXPORTED = "كُتبت إعدادات الشاشة على مفتاح USB: أدخله في الشاشة الأخرى واضغط «تطبيق»"
    const val USB_EXPORT_NONE = "لا يوجد مفتاح USB تستطيع الشاشة الكتابة عليه: أدخل مفتاحًا ثم أعد المحاولة"
    const val USB_READ_AGAIN = "قراءة مفتاح USB من جديد"
    const val USB_READ_NOTHING = "لم تجد الشاشة على مفتاح USB ما تعرضه: تأكّد أن المفتاح موصول، وأن عليه ملف الإعدادات أو الصور"
    const val USB_READ_AGAIN_HINT = "تُعرض من جديد إعدادات المفتاح وصوره، حتى ما طُبِّق منها أو أُلغي"
    /** Onboarding from a key whose settings file names the mosque's place (another TV's copy). */
    const val USB_SETUP = "الإعداد من مفتاح USB"

    // Ramadan and Eid dates
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

    /** On the wall, dim, a few days before a date that is still the estimate on an offline TV: where the admin confirms it. */
    fun estimatedDateMark(event: String): String = "تاريخ $event تقديري: الإعدادات ← رمضان والعيد"
    /** The kiosk page's row for it: «تاريخ عيد الفطر (الجمعة 20 مارس 2026) تقديري: …». */
    fun estimatedDateRow(event: String, date: java.time.LocalDate): String =
        "تاريخ $event (${gregorianDate(date)}) تقديري: الشاشة لم تتلقَّ الإعلان الرسمي"
    const val ESTIMATED_DATE_FIX = "أكّده أو عدّله: الإعدادات ← رمضان والعيد"
    const val AFTER_SALAH_TEXTS = "أذكار بعد الصلاة"
    const val TICKER_TEXTS = "شريط الأذكار"
    const val BUNDLED_TEXTS = "استعادة النصوص المضمّنة"
    const val BUNDLED_TEXTS_CONFIRM = "تعود أذكار ما بعد الصلاة والشريط إلى النصوص المضمّنة المراجَعة، وتُحذف قائمتا المسجد. يمكن إرجاعهما بعد ذلك من «التراجع عن آخر استيراد»."
    const val BUNDLED_TEXTS_DO = "نعم، استعد النصوص المضمّنة"

    // Clock
    const val CLOCK_WRONG_TITLE = "ساعة الجهاز غير صحيحة"
    const val CLOCK_WRONG_HINT = "لا تُعرض أوقات الصلاة حتى يُضبط الوقت: اضبطه هنا، أو من إعدادات الجهاز، أو من الهاتف"
    const val CLOCK_DEVICE_TIME = "وقت الجهاز"
    const val CLOCK_MONTH = "الشهر"
    const val CLOCK_DATE = "التاريخ"
    const val CLOCK_HOUR = "الساعة"
    const val CLOCK_MINUTE = "الدقيقة"
    const val CLOCK_SET_HERE = "اعتماد هذا الوقت"
    const val CLOCK_OPEN_SETTINGS = "فتح إعدادات التاريخ والوقت"

    /** The right-to-left mark: after a Latin word (a zone id) it keeps the Arabic sentence in order. */
    private val RLM = Char(0x200F)

    /** Confirms the Tunisia time shown with it (on its own row, or just above it), not the device's clock in its own zone. */
    const val CLOCK_CONFIRM = "هذا الوقت صحيح"

    // The clock page: the question after a box in another zone, and the «الساعة» section.
    const val SECTION_CLOCK = "الساعة"
    const val CLOCK_QUESTION_TITLE = "كم الساعة الآن في تونس؟"
    const val CLOCK_QUESTION_HINT = "قد تكون ساعة هذا الجهاز مضبوطة على وقت بلد آخر: منطقته الزمنية ليست توقيت تونس طوال السنة، أو تغيّرت بعد ضبط الساعة. " +
        "اختر الوقت الصحيح الآن في تونس: عليه تُحسب كل أوقات الصلاة، ويبقى بعد إعادة التشغيل."
    const val CLOCK_SETTINGS_HINT = "كل أوقات الصلاة تُحسب على هذا الوقت. تؤكده الشاشة وحدها عند اتصالها بالإنترنت، أو تؤكده أنت هنا أو من الهاتف."
    const val CLOCK_TIME_IN_TUNIS = "الوقت الآن في تونس"
    const val CLOCK_CANDIDATE_SCREEN = "الوقت الذي تعرضه الشاشة"
    const val CLOCK_CANDIDATE_DEVICE = "ساعة الجهاز كما هي"
    const val CLOCK_OTHER_TIME = "وقت آخر"
    const val CLOCK_LATER = "لاحقًا"
    const val CLOCK_FROM_PHONE = "ضبط الوقت من الهاتف"
    const val CLOCK_CONFIRMED = "مؤكَّدة"
    const val CLOCK_UNCONFIRMED = "غير مؤكَّدة"
    /** On the wall, dim, while the time is not confirmed: where the admin answers. */
    const val CLOCK_UNVERIFIED_MARK = "الوقت غير مؤكَّد: الإعدادات ← الساعة"
    /** Before the system's own date page: on a box in another zone, the zone first, or setting the time brings the problem back. */
    val CLOCK_ZONE_FIRST = "في إعدادات الجهاز: اختر المنطقة الزمنية تونس (Africa/Tunis)$RLM أولًا ثم الوقت، وإلا عاد الفرق."

    /** How the time was confirmed, after «مؤكَّدة». */
    fun clockSource(source: ClockSource): String = when (source) {
        ClockSource.ZONE -> "الجهاز على توقيت تونس"
        ClockSource.NETWORK -> "من الإنترنت"
        ClockSource.ADMIN -> "أكّدها المسؤول"
        ClockSource.PHONE -> "من الهاتف"
    }

    /** The device's own clock and zone, for diagnosis: «ساعة الجهاز: 2026-09-29 14:00 (Asia/Shanghai)». */
    fun clockDevice(deviceTime: String): String = "$CLOCK_DEVICE_TIME: $deviceTime"

    // The kiosk page's rows about the clock.
    fun clockGood(source: ClockSource): String = "ساعة الشاشة مؤكَّدة (${clockSource(source)})"
    const val CLOCK_ROW_UNVERIFIED = "ساعة الشاشة غير مؤكَّدة: قد تكون مضبوطة على وقت بلد آخر"
    const val CLOCK_ROW_UNVERIFIED_FIX = "الإعدادات ← الساعة، أو من الهاتف"
    const val CLOCK_ROW_WRONG = "ساعة الجهاز غير صحيحة: لا تُعرض أوقات الصلاة"
    fun clockZoneInfo(zoneId: String): String = "المنطقة الزمنية للجهاز $zoneId$RLM: لا أثر لها، الأوقات بتوقيت تونس"

    /** Each build reads its own folder on the key: a file written by the other build is copied into this one. */
    fun usbTemplateWritten(packageName: String) =
        "كُتب ملف الإعدادات على مفتاح USB في Android/data/$packageName/files/mosque-tv.json: عدّله على الحاسوب ثم أعد إدخال المفتاح. " +
            "وملف كتبته نسخة أخرى من التطبيق يُنسخ على الحاسوب إلى هذا المجلد"
    const val FRIDAY_IQAMAH = "إقامة الجمعة"

    // Main display
    const val SUNRISE = "الشروق"
    const val NO_DATA = "لا تتوفر بيانات لهذا اليوم"

    // Prayer names
    const val FAJR = "الفجر"
    const val DHUHR = "الظهر"
    const val ASR = "العصر"
    const val MAGHRIB = "المغرب"
    const val ISHA = "العشاء"
    const val JOMOAA = "الجمعة"

    // Settings
    const val SETTINGS_TITLE = "الإعدادات"
    const val SETTINGS_KIOSK = "التشغيل الدائم للشاشة"
    const val SETTINGS_PHONE = "لوحة الإدارة من الهاتف"
    const val ANNOUNCEMENT_LABEL = "إعلان"
    const val USB_COPYING = "جارٍ نسخ الصور من مفتاح USB…"
    /** After a copy: how many files came, and how many images could not be decoded (broken files). */
    fun usbCopied(count: Int, unreadable: Int = 0) = when {
        unreadable == 0 -> "نُسخ ${filesCount(count)} من مفتاح USB: يمكنك نزعه"
        count == 0 -> "تعذّرت قراءة الصور على مفتاح USB (${filesCount(unreadable)}): احفظها من جديد على الحاسوب"
        else -> "نُسخ ${filesCount(count)} من مفتاح USB وتعذّرت قراءة ${filesCount(unreadable)}: يمكنك نزعه"
    }
    const val USB_COPY_FAILED = "تعذّر نسخ الصور: أعد إدخال المفتاح ثم اضغط «تطبيق»"
    const val USB_COPY_NO_ROOM = "لا تتّسع ذاكرة الجهاز لهذه الصور: قلّل عددها أو حجمها ثم أعد إدخال المفتاح"
    const val DASHBOARD_OPEN = "لوحة الإدارة مفتوحة"
    const val TEXTS_ADDED = "يُضاف"
    const val TEXTS_REMOVED = "يُحذف"
    const val TEXTS_RECOUNTED = "يتغيّر عدد المرات"
    const val TEXTS_RESOURCED = "يتغيّر المصدر"
    const val TEXTS_REWORDED = "يتغيّر النص"
    const val TEXTS_REORDERED = "يتغيّر ترتيب النصوص"
    const val ANNOUNCEMENTS_REDATED = "يتغيّر التاريخ"
    const val ANNOUNCEMENTS_EXPIRED = "انتهى تاريخه ولن يُعرض"
    /** A Ramadan setting set back to the usual one. */
    const val AS_USUAL = "كالمعتاد"
    /** After a fixed iqamah the flow will not use today (outside 1 to 90 minutes after the adhan, or sunrise for an Eid). */
    fun notTodayAfterAdhan(adhan: String, instead: String) = "لا يناسب أذان اليوم $adhan، فتكون $instead"
    fun notTodayAfterSunrise(sunrise: String, instead: String) = "لا يناسب شروق اليوم $sunrise، فتكون $instead"
    /** After an iqamah set before the end of the adhan screen, which waits for it ([iqamahWaitsForAdhan]). */
    fun waitsForAdhanScreen(adhan: String, time: String) = "أقرب إلى أذان اليوم $adhan، فتكون الساعة $time، عند نهاية شاشة الأذان"
    /** On the kiosk page: an iqamah the screen moved today from its setting. */
    fun iqamahMoved(prayer: String, time: String) = "إقامة $prayer اليوم الساعة $time، لا كما ضُبطت: الوقت المضبوط لا يناسب أذان اليوم"
    const val IQAMAH_MOVED_FIX = "اضبطها دقائق بعد الأذان، أو وقتًا ثابتًا بين 1 و90 دقيقة بعد أذان كل يوم"
    /** An iqamah set before the end of the adhan screen, which waits for it. */
    fun iqamahWaitsForAdhan(prayer: String, time: String) = "إقامة $prayer اليوم الساعة $time، عند نهاية شاشة الأذان: الوقت المضبوط أقرب إلى الأذان"
    const val IQAMAH_WAITS_FIX = "اضبطها بعد نهاية شاشة الأذان، أو قصّر مدة شاشة الأذان في «الإقامة ومدة الصلاة»"
    /** The same for an Eid prayer, which has no adhan: its time counts from sunrise. */
    fun eidPrayerMoved(eid: String, time: String) = "صلاة $eid اليوم الساعة $time، لا كما ضُبطت: الوقت المضبوط لا يناسب شروق اليوم"
    const val EID_PRAYER_MOVED_FIX = "اضبطها دقائق بعد الشروق، أو وقتًا ثابتًا بين 1 و90 دقيقة بعد شروق كل يوم"
    /** Under the first lines of a very long list of changes or mistakes. */
    fun moreChanges(count: Int) = "و" + counted(count, "تغيير آخر", "تغييران آخران", "تغييرات أخرى", "تغييرًا آخر", "تغيير آخر")
    fun moreErrors(count: Int) = "و" + counted(count, "خطأ آخر", "خطآن آخران", "أخطاء أخرى", "خطأً آخر", "خطأ آخر")
    fun andOthers(count: Int) = "و" + counted(count, "نص آخر", "نصان آخران", "نصوص أخرى", "نصًا آخر", "نص آخر")
    const val WEATHER_ENABLED = "عرض الطقس (عند الاتصال بالإنترنت)"
    const val ANNOUNCEMENTS_EVERY = "عرض الإعلانات بين الصلوات كل"
    const val ANNOUNCEMENTS_EVERY_OFF = "بعد الصلاة فقط"
    const val MINUTES_WORD = "دقيقة"
    const val ALLOW_UPDATES = "السماح بتثبيت التحديثات"
    const val EXIT_TO_ANDROID = "الخروج إلى إعدادات الجهاز"
    /**
     * Back on the display, and the About page: every way the remote opens the settings, with the
     * detector's own numbers. A shorter press of OK does nothing, so the hold's length is said.
     * The RLM after the second «OK» keeps the count with «مرات»: a number right after a Latin word
     * joins its left-to-right run and would be read before it, next to «اضغط».
     */
    val HOLD_OK_HINT = "لفتح الإعدادات: اضغط OK مطولًا " +
        counted((AdminEntryDetector.LONG_PRESS_MILLIS / 1000).toInt(), "ثانية واحدة", "ثانيتين", "ثوانٍ", "ثانية", "ثانية") +
        "، أو اضغط OK$RLM " + counted(AdminEntryDetector.TAPS, "مرة واحدة", "مرتين", "مرات", "مرة", "مرة") + " بسرعة، أو زر القائمة"
    const val UNDO_IMPORT = "التراجع عن آخر استيراد"
    const val RESET_ALL = "إعادة ضبط الشاشة"
    const val RESET_CONFIRM = "ستُمحى كل الإعدادات (المسجد، الموقع، الإقامة، التواريخ) ثم يبدأ الإعداد من جديد"
    const val RESET_DO = "نعم، أعد الضبط"
    const val ABOUT = "حول التطبيق"
    /** The About page's years, isolated left to right like a verse range ([source]): «2020–2035», never «2035–2020». */
    fun offlineYears(first: Int, last: Int): String = "أوقات الصلاة تُحسب على الجهاز دون إنترنت للسنوات ${source("$first–$last")}"
    const val LOCATION_CONFIRM = "تغيير موقع المسجد إلى"
    const val MOSQUE_NAME_LABEL = "اسم المسجد"
    const val THEME_LABEL = "المظهر"
    const val SETTINGS_LOCATION = "الموقع"
    const val SETTINGS_ANNOUNCEMENTS = "الإعلانات"
    const val SETTINGS_THEME = "المظهر"
    const val ANNOUNCEMENTS_ENABLED = "تفعيل الإعلانات"
    const val CUSTOM_BG_ENABLED = "تفعيل الخلفيات المخصصة"
    const val ANNOUNCEMENT_INTERVAL = "مدة عرض كل إعلان"
    const val SECONDS_SUFFIX = "ث"
    const val MEDIA_HINT = "ضع الصور على مفتاح USB بجانب ملف الإعدادات mosque-tv.json ثم أدخله في الجهاز"
    /** The folder names are isolated left to right: in right-to-left text the slash would go before them («/backgrounds»). */
    const val BACKGROUNDS_FOLDER_HINT = "\u2066backgrounds/\u2069 — صور الخلفيات (JPG أو PNG أو WebP)"
    const val ANNOUNCEMENTS_FOLDER_HINT = "\u2066announcements/\u2069 — صور الإعلانات، وملفات نصية ‎.txt لكل إعلان مكتوب"
    /** It empties both media folders, so it names the written .txt announcements as well as the images. */
    const val DELETE_IMAGES = "حذف الصور وملفات الإعلانات"
    const val USB_MEDIA_TITLE = "وُجدت صور على مفتاح USB"
    const val USB_MEDIA_NONE_TITLE = "لا ملفات تُنسخ من مفتاح USB"
    /** Images replace the images of their kind, .txt files the .txt files: a key never takes the other away. */
    const val USB_MEDIA_HINT = "تحلّ الصور محلّ صور الشاشة من النوع نفسه، وملفات ‎.txt محلّ ملفات ‎.txt، ويبقى الباقي"
    fun usbMediaReplaces(count: Int) = "تحلّ محلّ ${filesCount(count)} على الشاشة"
    /** The files of a key left out, by reason («3 ملفات بصيغة …»). */
    fun rejectedFormat(count: Int) = "${filesCount(count)} بصيغة لا تعرضها الشاشة (مثل HEIC أو GIF): احفظها بصيغة JPG أو PNG"
    fun rejectedTooLarge(count: Int) = "${filesCount(count)} أكبر من 15 ميغابايت"
    fun rejectedTooMany(count: Int) = "${filesCount(count)} بعد الحد، 20 لكل نوع"
    fun rejectedText(count: Int) = "${filesCount(count)} ‎.txt أكبر من 4 كيلوبايت أو غير مقروءة"
    fun rejectedMisplaced(count: Int) = "${filesCount(count)} خارج مكانها: الصور في مجلد backgrounds أو announcements، وملفات ‎.txt في announcements"
    const val BACKGROUNDS_LABEL = "صور الخلفية"
    const val ANNOUNCEMENT_IMAGES_LABEL = "ملفات الإعلانات (صور ونصوص)"
    const val TEXT_ANNOUNCEMENTS = "الإعلانات المكتوبة"

    // Iqamah label
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
    const val ARAFAH = "يوم عرفة"
    const val EID_AFTER_SUNRISE = "صلاة العيد: الدقائق بعد الشروق"

    /** Under Jumu'a and the Eids in the iqamah table: whether the mosque holds them at all. */
    const val HOLDS_JUMUA = "تقام صلاة الجمعة في هذا المسجد"
    const val HOLDS_JUMUA_HINT = "إن لم تُقم يبقى الظهر يوم الجمعة، دون شاشة الخطبة"
    const val HOLDS_EID = "تقام صلاة العيد في هذا المسجد"
    const val HOLDS_EID_HINT = "إن لم تُقم لا يُعرض وقتها ولا عدّها التنازلي، وتبقى تهنئة العيد"
    const val HELD = "تقام"
    const val NOT_HELD = "لا تقام"

    /** Under Jumu'a in the iqamah table: how long its khutba lasts, for the quiet screen. */
    const val KHUTBA_LENGTH = "مدة الخطبة"
    const val KHUTBA_LENGTH_HINT = "شاشة الخطبة الهادئة بهذه المدة قبل الإقامة، وقبلها العدّ التنازلي"
    const val KHUTBA_FROM_ADHAN = "من الأذان"
    fun khutbaLength(minutes: Int): String = if (minutes == 0) KHUTBA_FROM_ADHAN else minutesShort(minutes)
    /** Over the iqamah table in the settings: how long the adhan screen lasts, and what it means for the iqamah. */
    const val ADHAN_SCREEN_LENGTH = "مدة شاشة الأذان"
    const val ADHAN_SCREEN_LENGTH_HINT = "الإقامة الأقرب إلى الأذان من هذه المدة تنتظر نهايتها، فلا تسودّ الشاشة والمؤذّن يؤذّن"

    /** The Eid prayer's time before it: «صلاة عيد الفطر غدًا 06:45» from the evening before, without «غدًا» on its day. */
    fun eidPrayerNote(prayer: Prayer, at: java.time.LocalDateTime, now: java.time.LocalDateTime): String =
        listOfNotNull(PRAYER_OF, prayerName(prayer), TOMORROW.takeIf { at.toLocalDate() != now.toLocalDate() }, hm(at.toLocalTime()))
            .joinToString(" ")

    /** "3 مرات", "33 مرة", "103 مرات"; nothing for a single reading. */
    fun times(count: Int): String? = if (count <= 1) null else counted(count, "مرة", "مرتان", "مرات", "مرة", "مرة")

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

    /**
     * A source as the screen shows it. A verse range such as «البقرة 253–254» or «المؤمنون 1–2» would
     * otherwise read «254–253» in right-to-left text: its digits and dash are kept left to right.
     */
    fun source(reference: String): String = VERSE_RANGE.replace(reference) { "$LEFT_TO_RIGHT${it.value}$END_ISOLATE" }
    /** Latin text (a log line, an error) kept left to right inside an Arabic line. */
    fun leftToRight(text: String): String = "$LEFT_TO_RIGHT$text$END_ISOLATE"
    private val VERSE_RANGE = Regex("""\d+\s*[–-]\s*\d+""")

    /**
     * A mosque's own words (an announcement) as the screen shows them. In right-to-left text a phone
     * number «98 123 456» or a date «25-10-2026» would read «456 123 98» and «2026-10-25»: each run of
     * digit groups is kept left to right, as a verse range in [source].
     */
    fun mosqueText(text: String): String = DIGIT_GROUPS.replace(text) { "$LEFT_TO_RIGHT${it.value}$END_ISOLATE" }
    /**
     * Groups of digits (Western or Arabic-Indic) joined by spaces or dashes, or by a single slash, dot
     * or colon (25/10/2026, 20:00; not the end of a sentence), or after a «+».
     */
    private val DIGIT_GROUPS = Regex("""\+?[0-9٠-٩۰-۹]+(?:(?:[ \u00A0–-]+|[/.:])[0-9٠-٩۰-۹]+)+|\+[0-9٠-٩۰-۹]+""")
    /** Unicode's left-to-right isolate and its end (LRI, PDI). */
    private val LEFT_TO_RIGHT = Char(0x2066)
    private val END_ISOLATE = Char(0x2069)

    /** "05:01". */
    fun hm(time: java.time.LocalTime): String = String.format(java.util.Locale.ROOT, "%02d:%02d", time.hour, time.minute)
    fun hms(time: java.time.LocalTime): String = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", time.hour, time.minute, time.second)

    /** A countdown: "01:24:48" from an hour up, "29:12" below; never negative. */
    fun countdown(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format(java.util.Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }

    // ── «أفق»: main screen ──

    /** Over the countdown to the next adhan: «أذان العصر بعد». */
    fun adhanIn(prayer: Prayer): String = "أذان ${prayerName(prayer)} بعد"

    /** A niche's iqamah line, «إقامة 05:01»; the hero says «الإقامة 15:42» ([IQAMAH]). */
    const val IQAMAH_IN_TILE = "إقامة"

    /** The last five minutes before an adhan. */
    const val SOON = "قريبًا"

    /** Ramadan's hero: «الإمساك غدًا 05:32» under the iftar countdown, «الإفطار 18:17» under the suhoor's. */
    const val IMSAK = "الإمساك"
    const val IFTAR = "الإفطار"
    const val TOMORROW = "غدًا"

    /** Under Isha on the nights of Ramadan. */
    const val THEN_TARAWIH = "ثم التراويح"

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

    // ── «أفق»: announcements and notices ──

    /** Under a written announcement that ends: «إلى 31 أكتوبر 2026». */
    fun announcementUntil(date: java.time.LocalDate): String = "إلى ${gregorianDate(date, withWeekday = false)}"

    /** A month in the clock page's stepper: «سبتمبر 2026». */
    fun monthYear(date: java.time.LocalDate): String = "${MONTHS[date.monthValue - 1]} ${date.year}"

    /** Under a list longer than its panel (a USB file's changes or mistakes): the arrows show the rest. */
    const val HINT_SCROLL_LIST = "▲ ▼: بقية القائمة"

    // ── «أفق»: settings and admin pages ──

    /** The settings menu, as on the board; «متقدم» holds the rare and irreversible actions. */
    const val SECTION_MOSQUE = "المسجد والموقع"
    const val SECTION_IQAMAH = "الإقامة ومدة الصلاة"
    const val SECTION_DATES = "رمضان والعيد"
    const val SECTION_MEDIA = "الإعلانات والصور"
    const val SECTION_ADVANCED = "متقدم"

    /** What the remote's keys do, at the foot of the admin pages. */
    const val HINT_OK_OPEN = "OK: فتح"
    const val HINT_OK_CHANGE = "OK: تغيير"
    const val HINT_BACK_TO_SCREEN = "الرجوع: العودة إلى الشاشة"
    const val HINT_BACK_TO_SETTINGS = "الرجوع: الإعدادات"
    const val HINT_BACK_TO_STEP = "الرجوع: الخطوة السابقة"
    const val HINT_FROM_PHONE = "أسهل من الهاتف: «لوحة الإدارة من الهاتف»"
    const val HINT_SAVED_AT_ONCE = "تُحفظ التغييرات فورًا"
    const val HINT_BACK_SAVES_NAME = "الرجوع: يُحفظ الاسم"

    const val BACK = "رجوع"
    const val ON = "مفعّل"
    const val OFF = "متوقف"
    const val NOT_SET = "غير محدد"
    const val GOUVERNORAT_LABEL = "الولاية"
    const val DELEGATION_LABEL = "المعتمدية"

    /** The iqamah table: «بعد الأذان 15 د», «الساعة 19:40», «10 د». */
    const val PRAYER_COLUMN = "الصلاة"
    const val DURATION_COLUMN = "المدة"
    const val DURATION_NOTE = "المدة: الشاشة سوداء أثناء الصلاة"
    fun minutesShort(minutes: Int): String = "$minutes $MINUTES_SUFFIX"
    fun iqamahAfterAdhan(minutes: Int): String = "بعد الأذان $minutes $MINUTES_SUFFIX"
    fun iqamahAfterSunrise(minutes: Int): String = "بعد الشروق $minutes $MINUTES_SUFFIX"
    fun atTime(hour: Int, minute: Int): String = "الساعة ${hm(java.time.LocalTime.of(hour, minute))}"
    fun hijriYear(year: Int): String = "$year هـ"

    /** In the settings' iqamah table: the iqamah the wall uses today, what decided it, and the keys of a row. */
    const val TODAY_COLUMN = "اليوم"
    const val IQAMAH_ADJUSTED = "عُدِّلت"
    const val IQAMAH_ADJUSTED_NOTE = "«عُدِّلت»: الإعداد لا يناسب أوقات اليوم، فتعتمد الشاشة الوقت المعروض"
    const val SWITCH_TO_FIXED = "وقت ثابت"
    const val SWITCH_TO_ADHAN = "بعد الأذان"
    const val SWITCH_TO_SUNRISE = "بعد الشروق"
    const val IN_RAMADAN = "في رمضان"
    const val RAMADAN_CLEAR = "حذف"

    /** Before a change of place: today's times here and there. */
    const val TODAY_TIMES = "أوقات اليوم"
    const val CURRENT_PLACE = "الموقع الحالي"
    const val NEW_PLACE = "الموقع الجديد"

    /** «المظهر»: the two looks, and what the screen shows. */
    const val THEME_CURRENT = "المظهر الحالي"
    const val DISPLAY_OPTIONS = "خيارات العرض"
    const val NIGHT_SCREEN = "شاشة الليل الخافتة"
    const val NIGHT_SCREEN_HINT = "بعد العشاء تخفت الشاشة إلى ساعة صغيرة حتى قُبيل الفجر"
    const val WEATHER_SOURCE = "المصدر:"

    /** «متقدم»: one line under each action. */
    const val UNDO_IMPORT_HINT = "تعود الإعدادات كما كانت قبل آخر ملف من مفتاح USB أو من الهاتف"
    const val BUNDLED_TEXTS_HINT = "تعود أذكار ما بعد الصلاة والشريط إلى النصوص المضمّنة المراجَعة"
    const val RESET_HINT = "تُمحى كل الإعدادات ويبدأ الإعداد من جديد"
    const val EXIT_TO_ANDROID_HINT = "إعدادات الشبكة والصوت والتاريخ في الجهاز نفسه"

    /** «الإعلانات والصور» and «المظهر»: the rows and what they say. */
    const val ANNOUNCEMENTS_BETWEEN = "الإعلانات بين الصلوات"
    /** «كل دقيقة», «كل دقيقتين», «كل 5 دقائق», «كل 15 دقيقة»: the noun agrees with the count ([counted]). */
    fun everyMinutes(minutes: Int): String = "كل " + counted(minutes, MINUTES_WORD, "دقيقتين", "دقائق", MINUTES_WORD, MINUTES_WORD)
    fun secondsShort(seconds: Int): String = "$seconds $SECONDS_SUFFIX"
    const val WEATHER_LABEL = "الطقس"
    const val WEATHER_HINT = "عند الاتصال بالإنترنت"

    /**
     * Everything in the two media folders goes: the images copied from a USB key or uploaded from the
     * phone, and the .txt announcements. The settings file's written announcements stay.
     */
    const val DELETE_IMAGES_HINT = "صور الخلفية وصور الإعلانات، من مفتاح USB أو من الهاتف، والإعلانات المكتوبة في ملفات ‎.txt"
    const val DELETE_IMAGES_CONFIRM = "تُحذف من الشاشة كل صور الخلفية وصور الإعلانات، ما نُسخ منها من مفتاح USB وما أُرسل من الهاتف، " +
        "وكل الإعلانات المكتوبة التي جاءت في ملفات ‎.txt. تبقى الإعلانات المكتوبة من الهاتف أو في ملف الإعدادات. لا يمكن التراجع عن الحذف."
    const val DELETE_IMAGES_DO = "نعم، احذف الملفات"
    const val BACKGROUNDS_HOWTO = "من مجلد backgrounds على مفتاح USB أو من الهاتف"

    /** What a section holds, in the menu's preview, when it has no values to show. */
    const val PHONE_PREVIEW = "عدّل الإقامة والتواريخ والإعلانات والصور والأذكار من هاتف أو حاسوب: امسح رمزًا يظهر على الشاشة، على شبكة المسجد أو نقطة اتصال الهاتف، دون إنترنت."
    const val PHONE_SESSION_OPEN = "جلسة مفتوحة الآن: أوقفها عند الانتهاء"
    const val KIOSK_PREVIEW = "هل تعود الشاشة وحدها بعد انقطاع الكهرباء، وهل ينام الجهاز، وهل توقّف التطبيق."

    /** The kiosk page. */
    const val KIOSK_ALL_GOOD = "كل شيء جاهز"
    const val KIOSK_EVENTS = "آخر الأحداث"
    const val GRANT_OVERLAY = "منح إذن الظهور فوق التطبيقات"
    const val QUICK_START_ON = "تفعيل البدء السريع"
    const val QUICK_START_OFF = "إيقاف البدء السريع"
    const val FIRE_TV_SLEEP_OFF = "إيقاف نوم Fire TV"
    const val HOME_MODE_ON = "جعل التطبيق الشاشة الرئيسية"
    const val HOME_MODE_OFF = "إيقاف وضع الشاشة الرئيسية"
    const val INSTALL_UPDATE = "تثبيت التحديث الآن"
    /** Both builds on one box: each brings its own display back over the other's, and each has its own settings. */
    const val OTHER_BUILD_INSTALLED = "نسختا التطبيق مثبّتتان على هذا الجهاز (Google Play وGitHub): تتناوبان على الشاشة، ولكل منهما إعداداتها"
    const val OTHER_BUILD_FIX = "أبقِ نسخة Google Play إن كان في الجهاز متجر Google Play، وإلا فنسخة GitHub، واحذف الأخرى من إعدادات الجهاز ← التطبيقات"
    /** Installing restarts the app: refused during a prayer and just before an adhan (update.InstallGate). */
    const val UPDATE_WAIT_PRAYER = "لا يُثبَّت التحديث أثناء الصلاة: أعد المحاولة بعدها"
    const val UPDATE_WAIT_ADHAN = "الأذان قريب: ثبّت التحديث بعد الصلاة"
    /** Device-owner boxes: the second press gives the mode up (only a factory reset gives it back). */
    const val LEAVE_DEVICE_OWNER = "إلغاء وضع مالك الجهاز"
    const val LEAVE_DEVICE_OWNER_CONFIRM = "اضغط مرة أخرى للتأكيد: لا يعود إلا بإعادة ضبط المصنع"
    fun problems(count: Int): String = counted(count, "مشكلة واحدة", "مشكلتان", "مشاكل", "مشكلة", "مشكلة")
    fun warnings(count: Int): String = counted(count, "تنبيه واحد", "تنبيهان", "تنبيهات", "تنبيهًا", "تنبيه")

    /** «ملف واحد», «ملفان», «3 ملفات», «12 ملفًا»; «لا ملفات» for none. */
    fun filesCount(count: Int): String = if (count <= 0) "لا ملفات" else counted(count, "ملف واحد", "ملفان", "ملفات", "ملفًا", "ملف")

    /**
     * The announcements beside their switch: the image and .txt [files], and the [written] ones from the
     * settings file or the phone (those shown today), each counted as what it is: «ملف واحد · إعلانان مكتوبان».
     */
    fun announcementsCount(files: Int, written: Int): String {
        val parts = listOfNotNull(
            filesCount(files).takeIf { files > 0 },
            counted(written, "إعلان مكتوب واحد", "إعلانان مكتوبان", "إعلانات مكتوبة", "إعلانًا مكتوبًا", "إعلان مكتوب").takeIf { written > 0 },
        )
        return if (parts.isEmpty()) "لا إعلانات" else parts.joinToString(" · ")
    }

    /** Durations on the kiosk page: «ساعتان», «5 ساعات و7 دقائق», «9 ثوانٍ». */
    fun hours(count: Int): String = counted(count, "ساعة", "ساعتان", "ساعات", "ساعة", "ساعة")
    fun minutes(count: Int): String = counted(count, "دقيقة", "دقيقتان", "دقائق", "دقيقة", "دقيقة")
    fun seconds(count: Int): String = counted(count, "ثانية", "ثانيتان", "ثوانٍ", "ثانية", "ثانية")
    fun hoursAndMinutes(totalMinutes: Long): String {
        val hours = (totalMinutes / 60).toInt()
        val minutes = (totalMinutes % 60).toInt()
        return when {
            hours == 0 -> minutes(minutes)
            minutes == 0 -> hours(hours)
            else -> "${hours(hours)} و${minutes(minutes)}"
        }
    }

    /**
     * A count with its noun as Arabic wants it: [one] and [two] alone, the plural from 3 to 10, the
     * accusative singular from 11 to 99, the singular for 100 and the round hundreds after it.
     */
    fun counted(count: Int, one: String, two: String, plural: String, accusative: String, singular: String): String = when {
        count == 1 -> one
        count == 2 -> two
        count % 100 in 3..10 -> "$count $plural"
        count % 100 in 11..99 -> "$count $accusative"
        else -> "$count $singular"
    }

    /** The phone page. */
    const val PHONE_SUBTITLE = "من هاتف أو حاسوب على شبكة الشاشة، دون إنترنت"
    const val PHONE_INTRO = "الهاتف والشاشة على الشبكة نفسها، ولا حاجة إلى الإنترنت. ابدأ الجلسة وامسح الرمز بالهاتف: تُفتح لوحة فيها " +
        "أوقات اليوم والإقامة والتواريخ والإعلانات والصور والأذكار. تنتهي الجلسة وحدها بعد ربع ساعة دون استعمال."
    const val PHONE_START = "بدء الجلسة"
    const val PHONE_STOP = "إيقاف الجلسة"
    const val STOP = "إيقاف"
    const val WIFI_SETTINGS = "إعدادات Wi-Fi"
    const val PHONE_NO_NETWORK = "الشاشة غير متصلة بأي شبكة: صِلها بشبكة المسجد أو بنقطة اتصال الهاتف"
    val PHONE_SCAN_STEPS = listOf(
        "امسح الرمز بكاميرا الهاتف",
        "افتح الرابط الذي يقترحه الهاتف: تُفتح لوحة الإدارة",
        "أوقف الجلسة هنا عند الانتهاء",
    )
    const val PHONE_WRONG_NETWORK = "إن لم تُفتح اللوحة، فالهاتف على شبكة غير شبكة الشاشة."
    const val PHONE_OTHER_ADDRESSES = "الشاشة على أكثر من شبكة: إن لم يُفتح الرمز، اكتب في متصفح الهاتف أحد هذه العناوين:"
    const val PHONE_WARNING = "من يرى هذا الرمز يستطيع تغيير إعدادات الشاشة: أوقف الجلسة عند الانتهاء."
    const val HOTSPOT_TITLE = "دون Wi-Fi في المسجد: نقطة اتصال الهاتف، مرة واحدة"
    val HOTSPOT_STEPS = listOf(
        "في الهاتف: الإعدادات ← نقطة الاتصال ← تشغيل، باسم وكلمة سر سهلين.",
        "هنا: «إعدادات Wi-Fi» ← اختر نقطة اتصال الهاتف واكتب كلمة السر، ثم ارجع إلى التطبيق.",
        "في المرات القادمة يكفي تشغيل نقطة الاتصال: تتصل بها الشاشة وحدها.",
    )
    const val HOTSPOT_NOTE = "إن قال الهاتف إن الشبكة بلا إنترنت، فاختر البقاء متصلًا."

    // ── The screen's clock from the phone (the dashboard's POST /api/clock): its answers ──
    const val PHONE_CLOCK_SET = "ضُبطت ساعة الشاشة على وقت هاتفك"
    const val PHONE_CLOCK_SET_REFUSED = "لم تُضبط الساعة: تاريخ الهاتف غير صحيح"
    const val PHONE_CLOCK_CONFIRMED = "أُكِّد وقت الشاشة"
    const val PHONE_CLOCK_CONFIRM_REFUSED = "لم يُؤكَّد الوقت: ساعة الشاشة غير صحيحة، اضبطها على وقت هاتفك"
    const val PHONE_CLOCK_BAD_REQUEST = "طلب غير صالح"
    const val PHONE_CLOCK_BUSY = "الشاشة مشغولة، أعد المحاولة"
    const val PHONE_CLOCK_UNAVAILABLE = "تعذّر ذلك على الشاشة الآن، أعد المحاولة"
    const val PHONE_UPDATE_CONTINUES = "يتواصل التحديث على الشاشة: تابع حالته هنا بعد قليل"

    /** Onboarding. */
    fun step(index: Int, count: Int): String = "الخطوة $index من $count"
}
