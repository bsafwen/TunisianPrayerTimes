package com.tunisianprayertimes.place

import com.tunisianprayertimes.method.CountryMethodDefaults

/**
 * A catalogue row as the resolvers see it (plan 6.8): a European, overseas or Arab city, a land neighbour's
 * row (country and zone only, never shown), or for the country step a Tunisian delegation seat (TN).
 */
data class CityRef(
    val id: Long,
    val point: LatLngE5,
    val countryCode: String,
    val zoneId: String,
    val admin1: String? = null,
    val admin2: String? = null,
    val population: Int = 0,
    val neighbourOnly: Boolean = false,
)

/** The catalogue's spatial queries, implemented by the app's `PlaceCatalogue`. */
interface CityIndex {
    /** Rows within [km] of [point], nearest first. */
    fun within(point: LatLngE5, km: Double): List<CityRef>

    /**
     * The nearest row of [countryCode] to [point], whatever the distance, for the administrative values
     * (6.8). For NO it also counts IRN's entry points, each standing for its own municipality.
     */
    fun nearestOf(point: LatLngE5, countryCode: String): CityRef?
}

/**
 * The country of a fix outside the Tunisia outline, and the administrative values the zone mapping reads
 * (plan 6.8, A12). No country or département outlines are bundled: the catalogue's rows decide.
 */
object CountryResolver {
    /** The catalogue rows that count for a fix. */
    const val RADIUS_KM = 40.0

    /** The country, `admin1` and `admin2` of a place; [countryCode] null when unknown (then `mwl` with «تأكيد الطريقة»). */
    data class Resolution(val countryCode: String?, val admin1: String? = null, val admin2: String? = null)

    /**
     * A fix's country: among the countries of the rows within 40 km, the network country if it is one of
     * them (Edirne, the Rhine, Ras Jedir), else the nearest row's; with no row within 40 km, the network
     * country, then the region of the phone's zone; otherwise unknown. Palestinian networks read `il`,
     * which has no rows, so the nearest row decides there. [picked] is the catalogue row the user picked,
     * whose own values win.
     */
    fun resolve(point: LatLngE5, index: CityIndex, networkCountry: String?, phoneZoneRegion: String?, picked: CityRef? = null): Resolution {
        val country = picked?.countryCode?.let(::normalize) ?: countryOf(point, index, networkCountry, phoneZoneRegion)
            ?: return Resolution(null)
        val admin = when {
            picked != null && normalize(picked.countryCode) == country -> picked
            else -> index.nearestOf(point, country)
        }
        return Resolution(country, admin1 = admin?.takeIf { country in ADMIN1_COUNTRIES }?.admin1, admin2 = admin?.takeIf { country in ADMIN2_COUNTRIES }?.admin2)
    }

    private fun countryOf(point: LatLngE5, index: CityIndex, networkCountry: String?, phoneZoneRegion: String?): String? {
        val nearby = index.within(point, RADIUS_KM)
        val network = networkCountry?.takeIf { it.isNotBlank() }?.let(::normalize)
        if (nearby.isNotEmpty()) {
            val countries = nearby.map { normalize(it.countryCode) }.toSet()
            if (network != null && network in countries) return network
            return normalize(nearby.minBy { CountryMethodDefaults.distanceKm(point, it.point) }.countryCode)
        }
        return network ?: phoneZoneRegion?.takeIf { it.isNotBlank() && it != "001" }?.let(::normalize)
    }

    /** Western Sahara under Morocco (A1); Svalbard and Jan Mayen under Norway (6.8). */
    fun normalize(countryCode: String): String = when (val cc = countryCode.uppercase()) {
        "EH" -> "MA"
        "SJ" -> "NO"
        else -> cc
    }

    /** The federal subject (RU), the wilaya (DZ, MR), the emirate (AE) or the governorate (JO, IQ). */
    val ADMIN1_COUNTRIES = setOf("RU", "DZ", "MR", "AE", "JO", "IQ")

    /** The département (FR), the governorate (PS), the district (TR) or the municipality (NO). */
    val ADMIN2_COUNTRIES = setOf("FR", "PS", "TR", "NO")
}
