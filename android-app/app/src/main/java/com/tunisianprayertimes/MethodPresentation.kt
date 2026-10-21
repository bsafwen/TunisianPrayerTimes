package com.tunisianprayertimes

import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.place.MappedZone
import com.tunisianprayertimes.place.PrayerPlace
import java.util.Locale

/**
 * One chip per method (A6, plan 6.11), owner 2026-10-20: «either it is official or it is falakia». «رسمية» where the
 * times follow an official source for the place, «فلكية» where the app computes them from the method.
 */
enum class MethodChip(val labelAr: String) {
    OFFICIAL("رسمية"),
    ASTRONOMICAL("فلكية"),
}

/**
 * How a method is named and labelled on the phone (plan 6.11): Tunisian Arabic names (D19), the authority's name
 * first for a profile, the angles in an LTR isolate, the chip of A6 and the wording of the zone line.
 */
object MethodPresentation {
    private const val LRI = "⁦"
    private const val PDI = "⁩"
    private const val RLI = "⁧"
    private const val MINUS = "−"

    private val NAMES = mapOf(
        "tunisia_18_18" to "طريقة المعهد الوطني للرصد الجوي (تونس)",
        "mwl" to "رابطة العالم الإسلامي",
        "isna" to "الجمعية الإسلامية لأمريكا الشمالية",
        "karachi" to "جامعة العلوم الإسلامية بكراتشي",
        "umm_al_qura" to "تقويم أم القرى",
        "egyptian" to "الهيئة المصرية العامة للمساحة (طريقة عامة)",
        "mwl_angle" to "المركز الثقافي الإسلامي في أيرلندا",
        "mdf_12" to "مسلمو فرنسا",
        "rhone_ctir" to "مجلس مساجد الرون",
        "be_sunrise_90" to "الفجر قبل الشروق بـ90 دقيقة (بلجيكا)",
        "fajr15_isha90" to "الفجر 15° والعشاء بعد المغرب بـ90 دقيقة",
        "fajr15_isha15" to "الفجر 15° والعشاء 15°",
        "gmp" to "مسجد باريس الكبير",
        "apbif" to "جمعية أئمة فرنسا (APBIF)",
        "diyanet_eu" to "رئاسة الشؤون الدينية التركية (أوروبا)",
        "diyanet_tr" to "رئاسة الشؤون الدينية التركية",
        "irn" to "المجلس الإسلامي في النرويج",
        "ru_dum_rt" to "الإدارة الدينية لمسلمي تتارستان",
        "bosnia_iz" to "المشيخة الإسلامية في البوسنة والهرسك",
        "hr_miz_zagreb" to "المشيخة الإسلامية في زغرب",
        "si_islamska_skupnost" to "المشيخة الإسلامية في سلوفينيا",
        "luxembourg_shoura" to "مجلس الشورى في لوكسمبورغ",
        "ie_ifi" to "المؤسسة الإسلامية في أيرلندا",
        "mcw" to "لجنة رؤية الهلال العالمية",
        "london_unified" to "جدول لندن الموحد",
        "uk_18_nearest_day" to "18° مع أقرب يوم (بريطانيا)",
        "cil_lisbon" to "الجماعة الإسلامية في لشبونة",
        "dum_rf" to "الإدارة الدينية لمسلمي روسيا",
        "ecfr_90" to "المجلس الأوروبي للإفتاء والبحوث",
        "al_kmsh" to "الجماعة الإسلامية في ألبانيا",
        "xk_bik" to "الجماعة الإسلامية في كوسوفو",
        "mk_bfi" to "الجماعة الدينية الإسلامية في مقدونيا الشمالية",
        "bg_grand_mufti" to "دار الإفتاء في بلغاريا",
        "re_cir" to "المجلس الإسلامي في لا ريونيون",
        "yt_traditional" to "الجدول التقليدي في مايوت",
        "gf_a2cmgf" to "جمعية مسلمي غويانا الفرنسية",
        "mq_ccim" to "المركز الثقافي الإسلامي في مارتينيك",
        "sa_umm_al_qura" to "تقويم أم القرى (السعودية)",
        "eg_egsa" to "الهيئة المصرية العامة للمساحة",
        "ma_habous" to "وزارة الأوقاف والشؤون الإسلامية (المغرب)",
        "dz_marw" to "وزارة الشؤون الدينية والأوقاف (الجزائر)",
        "ly_awqaf" to "الهيئة العامة للأوقاف والشؤون الإسلامية (ليبيا)",
        "mr_islamic_affairs" to "وزارة الشؤون الإسلامية والتعليم الأصلي (موريتانيا)",
        "jo_awqaf" to "وزارة الأوقاف والشؤون والمقدسات الإسلامية (الأردن)",
        "ps_dar_al_ifta" to "دار الإفتاء الفلسطينية",
        "ps_gaza_awqaf" to "وزارة الأوقاف والشؤون الدينية (غزة)",
        "lb_dar_al_fatwa" to "دار الفتوى (لبنان)",
        "sy_awqaf" to "وزارة الأوقاف (سوريا)",
        "iq_sunni_endowment" to "ديوان الوقف السني (العراق)",
        "kw_awqaf" to "وزارة الأوقاف والشؤون الإسلامية (الكويت)",
        "qa_awqaf" to "وزارة الأوقاف والشؤون الإسلامية (قطر)",
        "qa_qch" to "دار التقويم القطري",
        "bh_calendar" to "المجلس الأعلى للشؤون الإسلامية (البحرين)",
        "ae_awqaf" to "الهيئة العامة للشؤون الإسلامية والأوقاف (الإمارات)",
        "om_mara" to "وزارة الأوقاف والشؤون الدينية (عُمان)",
        "ye_awqaf" to "الهيئة العامة للأوقاف والإرشاد (اليمن)",
        "sd_fitted" to "طريقة مستخلصة (السودان)",
        "so_fitted" to "طريقة مستخلصة (الصومال)",
        "dj_fitted" to "طريقة مستخلصة (جيبوتي)",
        "km_fitted" to "الجدول التقليدي (جزر القمر)",
        PrayerMethod.CUSTOM_ID to "مخصّصة",
    )

    fun name(methodId: String): String = NAMES[methodId] ?: methodId

    /** Every method the app names, for the source-scan test. */
    val namedIds: Set<String> get() = NAMES.keys

    /** The angles or intervals of a method in an LTR isolate: `18° · 17°`, `90 د · 17°`. */
    fun parameters(method: PrayerMethod): String {
        val fajr = when (val f = method.fajr) {
            is FajrRule.Angle -> degrees(f.degrees)
            is FajrRule.MinutesBeforeSunrise -> minutes(f.minutes)
        }
        val isha = when (val i = method.isha) {
            is IshaRule.Angle -> degrees(i.degrees)
            is IshaRule.MinutesAfter -> minutes(i.minutes)
        }
        return "$LRI$fajr · $isha$PDI"
    }

    /**
     * Minutes inside the parameters' LTR isolate: the Arabic «د» in an RTL isolate of its own, which the isolate around it
     * sees as one neutral. Bare, the letter turned the rest of the line around: «90 د · 90 د» showed «90 د 90 · د».
     */
    private fun minutes(value: Int): String = "$value $RLI" + "د" + PDI

    /** The row of «طريقة الحساب»: the name, then its parameters. */
    fun summary(method: PrayerMethod): String = name(method.id) + " · " + parameters(method)

    private fun degrees(value: Double): String =
        if (value % 1.0 == 0.0) "${value.toInt()}°" else String.format(Locale.US, "%s°", value.toBigDecimal().stripTrailingZeros().toPlainString())

    /** A signed minute value in an LTR isolate with U+2212 (6.11): «⁦−3⁩», «⁦+5⁩». */
    fun signedMinutes(value: Int): String = LRI + (if (value < 0) MINUS + (-value) else "+$value") + PDI

    /** The country of each method with official zones, where a zone names no other. A place outside it never shows «رسمية». */
    private val PROFILE_COUNTRY = mapOf(
        "sa_umm_al_qura" to "SA", "eg_egsa" to "EG", "ma_habous" to "MA", "jo_awqaf" to "JO", "qa_awqaf" to "QA", "qa_qch" to "QA",
        "ae_awqaf" to "AE", "om_mara" to "OM", "ye_awqaf" to "YE", "dz_marw" to "DZ", "ly_awqaf" to "LY",
        "mr_islamic_affairs" to "MR", "ps_dar_al_ifta" to "PS", "ps_gaza_awqaf" to "PS", "sy_awqaf" to "SY", "kw_awqaf" to "KW",
        "iq_sunni_endowment" to "IQ", "lb_dar_al_fatwa" to "LB", "bh_calendar" to "BH",
        "irn" to "NO", "luxembourg_shoura" to "LU", "diyanet_eu" to "AT", "bosnia_iz" to "BA", "diyanet_tr" to "TR",
        "xk_bik" to "XK", "bg_grand_mufti" to "BG", "si_islamska_skupnost" to "SI", "hr_miz_zagreb" to "HR", "ie_ifi" to "IE",
        "dum_rf" to "RU",
    )

    /**
     * The chip of A6 (6.11): «رسمية» for a national body's own definition in its own country, for `sa_umm_al_qura`
     * anywhere in Saudi Arabia (KACST computes for any point), and for an authority's table at the mapped zone in the
     * zone's own country; «فلكية» everywhere else: exact coordinates, a place no zone serves (the Kurdistan Region, 8.8),
     * a table no authority publishes, Bosnia's table stood in for Montenegro's, and a custom method.
     */
    fun chip(method: PrayerMethod, place: PrayerPlace, mapped: MappedZone?): MethodChip {
        val cc = (place as? PrayerPlace.Coordinates)?.countryCode
        val official = !method.isCustom && cc != null && when {
            (method.id == "bosnia_iz" && cc == "BA") || (method.id == "diyanet_tr" && cc == "TR") -> true
            method.id == "sa_umm_al_qura" -> cc == "SA"
            mapped == null || mapped.zone.authority in UNOFFICIAL_ZONES || standsInForMontenegro(method.id, place) -> false
            else -> (mapped.zone.country ?: PROFILE_COUNTRY[method.id]) == cc
        }
        return if (official) MethodChip.OFFICIAL else MethodChip.ASTRONOMICAL
    }

    /** Tables no authority publishes for that place drop «الرسمي» (6.11). */
    private val UNOFFICIAL_ZONES = setOf("rhone_ctir", "km_fitted", "yt_traditional", "re_cir")

    /** Bosnia's table, shifted, where the Islamic Community in Montenegro's own was not found (8.8). */
    private fun standsInForMontenegro(methodId: String, place: PrayerPlace) =
        methodId == "bosnia_iz" && (place as? PrayerPlace.Coordinates)?.countryCode == "ME"

    /** The zone line: «الجدول الرسمي المعتمد: مسقط», or «الجدول المعتمد: الرون». */
    fun zoneLine(methodId: String, mapped: MappedZone, place: PrayerPlace): String {
        val prefix = if (methodId in UNOFFICIAL_ZONES || standsInForMontenegro(methodId, place)) "الجدول المعتمد" else "الجدول الرسمي المعتمد"
        val name = mapped.baseZone?.takeIf { mapped.zone.id.startsWith("me:") }?.nameAr ?: mapped.zone.nameAr
        return "$prefix: $name"
    }
}
