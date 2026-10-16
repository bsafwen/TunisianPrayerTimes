package com.tunisianprayertimes.method

import com.tunisianprayertimes.place.LatLngE5
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What the first run asks in a country (plan 6.4, D4). */
enum class ChooserKind {
    /** No question: the default, with one line naming it and «تغيير». */
    NONE,

    /** A one-screen chooser of the methods the country's mosques use. */
    METHODS,

    /** Only the Asr factor, asked once (Portugal, Réunion). */
    ASR_ONLY,

    /** The methods, then the Asr factor (the UK). */
    METHODS_AND_ASR,
}

/**
 * A country's default (plan 6.4): the method used when the user has picked none ([fallback], also the
 * chooser's dismissal), the chooser's entries in their order, the preselected one, and whether the default
 * depends on the place within the country, so it is resolved again on every move there until the user picks
 * a method (FR, HR, RS, RU, PS).
 */
data class CountryDefault(
    val fallback: String,
    val chooser: ChooserKind = ChooserKind.NONE,
    val entries: List<String> = listOf(fallback),
    val preselected: String? = null,
    val resolvesOnMove: Boolean = false,
    /** «تأكيد الطريقة» even with no chooser: a place whose country is unknown. */
    val confirm: Boolean = false,
    /** Greece's `diyanet_tr`, inferred by analogy until the Thrace muftis' tables are looked for. */
    val inferred: Boolean = false,
)

object CountryMethodDefaults {
    private const val MWL = "mwl"

    /**
     * The default for a place: [iso2] its country, [territory] its resolved zone id (Asia/Gaza tells the Gaza
     * Strip from the West Bank), [admin1] an RU federal subject (GeoNames code or name), [admin2] an FR
     * département, [point] the stored point (Croatia's Zagreb area, Serbia's Preševo Valley).
     */
    fun forCountry(iso2: String?, territory: String? = null, admin1: String? = null, admin2: String? = null, point: LatLngE5? = null): CountryDefault {
        val cc = iso2?.uppercase() ?: return CountryDefault(MWL, confirm = true)
        return when (cc) {
            "TN" -> CountryDefault("inm")
            "AT" -> CountryDefault("diyanet_eu")
            "LU" -> CountryDefault("luxembourg_shoura")
            "BA" -> CountryDefault("bosnia_iz")
            "HR" -> CountryDefault(if (point != null && inZagrebArea(point)) "hr_miz_zagreb" else "bosnia_iz", resolvesOnMove = true)
            "SI" -> CountryDefault("si_islamska_skupnost")
            "TR" -> CountryDefault("diyanet_tr")
            "AL" -> CountryDefault("al_kmsh")
            "XK" -> CountryDefault("xk_bik")
            "RS" -> CountryDefault(if (point != null && inPresevoValley(point)) "xk_bik" else "bosnia_iz", resolvesOnMove = true)
            "ME" -> CountryDefault("bosnia_iz")
            "MK" -> CountryDefault("mk_bfi")
            "BG" -> CountryDefault("bg_grand_mufti")
            "GR" -> CountryDefault("diyanet_tr", inferred = true)
            "PT" -> CountryDefault(MWL, chooser = ChooserKind.ASR_ONLY)
            "ES" -> CountryDefault(MWL, ChooserKind.METHODS, listOf(MWL, "isna"), preselected = MWL)
            "IT" -> CountryDefault(MWL, ChooserKind.METHODS, listOf(MWL, "isna"))
            "IE" -> CountryDefault("ie_ifi")
            "RU" -> CountryDefault(
                when {
                    admin1 in MOSCOW || admin1 in MOSCOW_OBLAST -> "dum_rf"
                    admin1 in TATARSTAN -> "ru_dum_rt"
                    else -> MWL
                },
                resolvesOnMove = true,
            )
            "FR" -> france(admin2)
            "BE" -> CountryDefault("karachi", ChooserKind.METHODS, listOf("karachi", "be_sunrise_90", "diyanet_eu", MWL))
            "NL" -> CountryDefault(MWL, ChooserKind.METHODS, listOf("diyanet_eu", MWL, "isna"))
            "CH" -> CountryDefault("isna", ChooserKind.METHODS, listOf("isna", "fajr15_isha90", MWL, "diyanet_eu", "mdf_12"))
            "DE", "SE" -> CountryDefault("diyanet_eu", ChooserKind.METHODS, listOf("diyanet_eu", MWL), preselected = "diyanet_eu")
            "GB" -> CountryDefault(MWL, ChooserKind.METHODS_AND_ASR, listOf("isna", MWL))
            "NO", "SJ" -> CountryDefault("irn")
            "RE" -> CountryDefault("re_cir", chooser = ChooserKind.ASR_ONLY)
            "YT" -> CountryDefault("yt_traditional", ChooserKind.METHODS, listOf("yt_traditional", MWL), preselected = "yt_traditional")
            "MQ" -> CountryDefault("mq_ccim")
            "GF" -> CountryDefault("gf_a2cmgf")
            "SA" -> CountryDefault("sa_umm_al_qura")
            "EG" -> CountryDefault("eg_egsa")
            "MA", "EH" -> CountryDefault("ma_habous")
            "DZ" -> CountryDefault("dz_marw")
            "LY" -> CountryDefault("ly_awqaf")
            "MR" -> CountryDefault("mr_islamic_affairs")
            "JO" -> CountryDefault("jo_awqaf")
            "PS" -> CountryDefault(if (territory == "Asia/Gaza") "ps_gaza_awqaf" else "ps_dar_al_ifta", resolvesOnMove = true)
            "SY" -> CountryDefault("sy_awqaf")
            "KW" -> CountryDefault("kw_awqaf")
            "QA" -> CountryDefault("qa_awqaf")
            "AE" -> CountryDefault("ae_awqaf")
            "OM" -> CountryDefault("om_mara")
            "YE" -> CountryDefault("ye_awqaf")
            "IQ" -> CountryDefault("iq_sunni_endowment")
            "LB" -> CountryDefault("lb_dar_al_fatwa")
            "BH" -> CountryDefault("bh_calendar")
            "SD" -> CountryDefault("sd_fitted")
            "SO" -> CountryDefault("so_fitted")
            "DJ" -> CountryDefault("dj_fitted")
            "KM" -> CountryDefault("km_fitted")
            // Every other European country, the other overseas territories, the land neighbours and any other country.
            else -> CountryDefault(MWL)
        }
    }

    /** France's list (D4): the same everywhere, `rhone_ctir` first in Rhône (69), `diyanet_eu` first in Bas-Rhin (67). */
    private fun france(admin2: String?): CountryDefault {
        val base = listOf("mdf_12", "isna", "diyanet_eu", MWL)
        return when (admin2) {
            "69" -> CountryDefault("rhone_ctir", ChooserKind.METHODS, listOf("rhone_ctir") + base, resolvesOnMove = true)
            "67" -> CountryDefault("diyanet_eu", ChooserKind.METHODS, listOf("diyanet_eu") + (base - "diyanet_eu"), resolvesOnMove = true)
            else -> CountryDefault("mdf_12", ChooserKind.METHODS, base, resolvesOnMove = true)
        }
    }

    /**
     * The preference key of a place's choice (6.4): one per country, with Ceuta and Melilla apart
     * (`ES-CE`, `ES-ML`), Western Sahara under Morocco (A1), and `ZZ` for a place whose country is unknown.
     */
    fun choiceKey(iso2: String?, admin1: String? = null): String {
        val cc = iso2?.uppercase() ?: return "method_choice_ZZ"
        return "method_choice_" + when {
            cc == "EH" -> "MA"
            cc == "ES" && admin1 in CEUTA -> "ES-CE"
            cc == "ES" && admin1 in MELILLA -> "ES-ML"
            else -> cc
        }
    }

    /** Every list also offers «طرق أخرى»: every preset and profile but `inm`, which serves Tunisian places only (D7). */
    fun otherMethods(): List<PrayerMethod> = PrayerMethods.ALL

    // GeoNames admin1 codes, with the names CountryResolver may give instead.
    private val MOSCOW = setOf("48", "Moscow", "Moskva")
    private val MOSCOW_OBLAST = setOf("47", "Moscow Oblast", "Moskovskaya Oblast'", "Moskovskaya")
    private val TATARSTAN = setOf("73", "Tatarstan", "Tatarstan Republic")
    private val CEUTA = setOf("CE", "Ceuta")
    private val MELILLA = setOf("ML", "Melilla")

    private val CROATIAN_TOWNS = mapOf(
        "zagreb" to LatLngE5.of(45.8150, 15.9819), "rijeka" to LatLngE5.of(45.3271, 14.4422), "osijek" to LatLngE5.of(45.5550, 18.6955),
        "split" to LatLngE5.of(43.5081, 16.4402), "dubrovnik" to LatLngE5.of(42.6507, 18.0944),
    )
    /** Preševo, Bujanovac and Medveđa with the radii fitted from their municipalities (fits/xk_bik, the asset's valley zones). */
    private val PRESEVO_VALLEY = listOf(
        LatLngE5.of(42.3092, 21.6497) to 15.0, LatLngE5.of(42.4598, 21.7660) to 13.5, LatLngE5.of(42.8433, 21.5853) to 13.0,
    )

    private fun nearestCroatianTown(point: LatLngE5): String = CROATIAN_TOWNS.minBy { (_, town) -> distanceKm(point, town) }.key

    /** Croatia's Zagreb area (8.8): Zagreb is the nearest of the Takvim's five Croatian towns. */
    fun inZagrebArea(point: LatLngE5): Boolean = nearestCroatianTown(point) == "zagreb"

    private fun inPresevoValley(point: LatLngE5) = PRESEVO_VALLEY.any { (town, reachKm) -> distanceKm(point, town) <= reachKm }

    /** Great-circle distance in km (haversine, mean Earth radius). */
    fun distanceKm(a: LatLngE5, b: LatLngE5): Double {
        val toRad = PI / 180
        val dLat = (b.latitude - a.latitude) * toRad
        val dLng = (b.longitude - a.longitude) * toRad
        val h = sin(dLat / 2).let { it * it } + cos(a.latitude * toRad) * cos(b.latitude * toRad) * sin(dLng / 2).let { it * it }
        return 2 * 6371.0088 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

}
