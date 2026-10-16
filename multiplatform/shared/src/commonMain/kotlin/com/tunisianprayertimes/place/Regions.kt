package com.tunisianprayertimes.place

/**
 * The regions of plan 2 (D2): Tunisia, Europe (the countries GeoNames files under Europe, plus Turkey and
 * Cyprus), overseas France (behind the Europe switch, each territory with its own code), the 22 members
 * of the Arab League (Western Sahara counted with Morocco, A1), and everything else, which the Europe
 * switch also covers.
 */
enum class Region { TUNISIA, EUROPE, OVERSEAS_FRANCE, ARAB_LEAGUE, OTHER, UNKNOWN }

object Regions {
    val EUROPE: Set<String> = setOf(
        "AD", "AL", "AT", "AX", "BA", "BE", "BG", "BY", "CH", "CZ", "DE", "DK", "EE", "ES", "FI", "FO", "FR", "GB", "GG",
        "GI", "GR", "HR", "HU", "IE", "IM", "IS", "IT", "JE", "LI", "LT", "LU", "LV", "MC", "MD", "ME", "MK", "MT", "NL",
        "NO", "PL", "PT", "RO", "RS", "RU", "SE", "SI", "SJ", "SK", "SM", "UA", "VA", "XK", "TR", "CY",
    )

    val OVERSEAS_FRANCE: Set<String> = setOf("RE", "YT", "GP", "MQ", "GF", "NC", "PF", "PM", "BL", "MF", "WF")

    /** The 22 members; Western Sahara (EH) is listed under Morocco (A1). */
    val ARAB_LEAGUE: Set<String> = setOf(
        "DZ", "BH", "KM", "DJ", "EG", "IQ", "JO", "KW", "LB", "LY", "MR", "MA", "OM", "PS", "QA", "SA", "SO", "SD", "SY",
        "TN", "AE", "YE",
    )

    fun of(countryCode: String?): Region = when (val cc = countryCode?.uppercase()) {
        null -> Region.UNKNOWN
        "TN" -> Region.TUNISIA
        "EH" -> Region.ARAB_LEAGUE
        in ARAB_LEAGUE -> Region.ARAB_LEAGUE
        in OVERSEAS_FRANCE -> Region.OVERSEAS_FRANCE
        in EUROPE -> Region.EUROPE
        else -> Region.OTHER
    }

    /** The region switch that opens a place (2): Tunisia always, the Arab League switch, or the Europe switch for every other place. */
    fun isOpen(region: Region, arabLeagueOpen: Boolean, europeOpen: Boolean): Boolean = when (region) {
        Region.TUNISIA -> true
        Region.ARAB_LEAGUE -> arabLeagueOpen
        else -> europeOpen
    }
}
