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
    const val AFTER_ADHAN_DUA = "اللّهُـمَّ رَبَّ هَذِهِ الدَّعْـوَةِ التّـامَّة، وَالصَّلاةِ القائمة، آتِ مُحَمَّداً الوَسيـلَةَ وَالفَضيـلَة، وَابْعَثْـهُ مَقـامـاً مَحمـوداً الَّذي وَعَدْتَـه"

    // Settings
    const val SETTINGS_TITLE = "الإعدادات"
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
    const val MEDIA_HINT = "انسخ الصور إلى مجلد TunisianPrayerTimesTV على الجهاز"
    const val BACKGROUNDS_FOLDER_HINT = "backgrounds/ — صور الخلفيات"
    const val ANNOUNCEMENTS_FOLDER_HINT = "announcements/ — صور أو نصوص الإعلانات"
    const val NO_MEDIA_FOUND = "لا توجد ملفات"
    const val MEDIA_FILES_COUNT = "ملفات"

    // Adhan/Iqamah labels
    const val ADHAN = "الأذان"
    const val IQAMAH = "الإقامة"

    // After-salah
    const val AFTER_SALAH_TITLE = "أذكار بعد الصلاة"

    // Ramadan
    const val RAMADAN_BANNER = "رمضان كريم 🌙"
    const val IFTAR_COUNTDOWN = "الإفطار بعد"
    const val SUHOOR_REMINDER = "السحور حتى"

    // Friday
    const val KHUTBA_TIME = "وقت الخطبة"
    const val JOMOAA_REMINDER = "صلاة الجمعة"

    // Hijri months
    val HIJRI_MONTHS = listOf(
        "محرّم", "صفر", "ربيع الأول", "ربيع الآخر",
        "جمادى الأول", "جمادى الآخرة", "رجب", "شعبان",
        "رمضان", "شوّال", "ذو القعدة", "ذو الحجّة"
    )

    fun prayerName(prayer: Prayer): String = when (prayer) {
        Prayer.FAJR -> FAJR
        Prayer.DHUHR -> DHUHR
        Prayer.ASR -> ASR
        Prayer.MAGHRIB -> MAGHRIB
        Prayer.ISHA -> ISHA
        Prayer.JOMOAA -> JOMOAA
    }
}
