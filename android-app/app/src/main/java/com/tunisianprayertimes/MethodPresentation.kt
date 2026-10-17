package com.tunisianprayertimes

import com.tunisianprayertimes.method.EvidenceKind
import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.FitStats
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.place.MappedZone
import com.tunisianprayertimes.place.PrayerPlace
import java.util.Locale

/** One chip per method (A6, plan 6.11). */
enum class MethodChip(val labelAr: String) {
    OFFICIAL("رسمية"),
    MATCHES_TABLE("مطابقة للجدول الرسمي"),
    FROM_TABLES("مستخلصة من جداول"),
    PUBLISHED("طريقة منشورة"),
    APPROXIMATE("تقريبية"),
    CUSTOM("مخصّصة"),
}

/**
 * How a method is named and labelled on the phone (plan 6.11): Tunisian Arabic names (D19), the authority's name
 * first for a profile, the angles in an LTR isolate, the chip of A6 and the wording of the zone line.
 */
object MethodPresentation {
    private const val LRI = "⁦"
    private const val PDI = "⁩"
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
            is FajrRule.MinutesBeforeSunrise -> "${f.minutes} د"
        }
        val isha = when (val i = method.isha) {
            is IshaRule.Angle -> degrees(i.degrees)
            is IshaRule.MinutesAfter -> "${i.minutes} د"
        }
        return "$LRI$fajr · $isha$PDI"
    }

    /** The row of «طريقة الحساب»: the name, then its parameters. */
    fun summary(method: PrayerMethod): String = name(method.id) + " · " + parameters(method)

    private fun degrees(value: Double): String =
        if (value % 1.0 == 0.0) "${value.toInt()}°" else String.format(Locale.US, "%s°", value.toBigDecimal().stripTrailingZeros().toPlainString())

    /** A signed minute value in an LTR isolate with U+2212 (6.11): «⁦−3⁩», «⁦+5⁩». */
    fun signedMinutes(value: Int): String = LRI + (if (value < 0) MINUS + (-value) else "+$value") + PDI

    private val TIER_A = setOf("SA", "EG", "MA", "JO", "QA", "AE", "OM", "YE")
    private val PROFILE_COUNTRY = mapOf(
        "sa_umm_al_qura" to "SA", "eg_egsa" to "EG", "ma_habous" to "MA", "jo_awqaf" to "JO", "qa_awqaf" to "QA", "qa_qch" to "QA",
        "ae_awqaf" to "AE", "om_mara" to "OM", "ye_awqaf" to "YE",
    )

    /** The Kurdistan Region's governorates (Q8), matched on GeoNames' admin1 names: Erbil, Sulaymaniyah, Duhok, Halabja. */
    fun isKurdistan(admin1Name: String?): Boolean {
        val n = admin1Name?.lowercase(Locale.ROOT) ?: return false
        return listOf("arbil", "erbil", "sulaym", "dahuk", "duhok", "dohuk", "halabj").any { it in n }
    }

    /**
     * The chip of A6 (6.11): «رسمية» for a national body's own definition in its own country, «مطابقة للجدول الرسمي»
     * where the mapped zone's own held-out record passes the tier-A gate (for `sa_umm_al_qura` anywhere in Saudi
     * Arabia, its profile's record), else the evidence's chip, with the Kurdistan Region's exception ([admin1Name]:
     * the name of the place's stored admin1, from the catalogue).
     */
    fun chip(method: PrayerMethod, place: PrayerPlace, mapped: MappedZone?, profileFit: FitStats?, admin1Name: String? = null): MethodChip {
        if (method.isCustom) return MethodChip.CUSTOM
        val cc = (place as? PrayerPlace.Coordinates)?.countryCode
        if ((method.id == "bosnia_iz" && cc == "BA") || (method.id == "diyanet_tr" && cc == "TR")) return MethodChip.OFFICIAL
        if (method.id == "iq_sunni_endowment" && cc == "IQ" && isKurdistan(admin1Name)) return MethodChip.APPROXIMATE
        val profileCountry = PROFILE_COUNTRY[method.id]
        if (profileCountry != null && profileCountry in TIER_A && profileCountry == cc) {
            val passes = if (method.id == "sa_umm_al_qura") profileFit?.passesTierA(method) == true
            else mapped != null && mapped.zone.fit?.passesTierA(method) == true &&
                mapped.baseZone.let { base -> base == null || base.fit?.passesTierA(method) == true }
            if (passes) return MethodChip.MATCHES_TABLE
        }
        return when (method.evidence.kind) {
            EvidenceKind.PUBLISHED -> MethodChip.PUBLISHED
            EvidenceKind.FROM_TABLES -> MethodChip.FROM_TABLES
            EvidenceKind.APPROXIMATE -> MethodChip.APPROXIMATE
        }
    }

    /** Tables no authority publishes for that place drop «الرسمي» (6.11). */
    private val UNOFFICIAL_ZONES = setOf("rhone_ctir", "km_fitted", "yt_traditional", "re_cir")

    /** The zone line: «الجدول الرسمي المعتمد: مسقط», or «الجدول المعتمد: الرون». */
    fun zoneLine(methodId: String, mapped: MappedZone, place: PrayerPlace): String {
        val montenegro = methodId == "bosnia_iz" && (place as? PrayerPlace.Coordinates)?.countryCode == "ME"
        val prefix = if (methodId in UNOFFICIAL_ZONES || montenegro) "الجدول المعتمد" else "الجدول الرسمي المعتمد"
        val name = mapped.baseZone?.takeIf { mapped.zone.id.startsWith("me:") }?.nameAr ?: mapped.zone.nameAr
        return "$prefix: $name"
    }
}
