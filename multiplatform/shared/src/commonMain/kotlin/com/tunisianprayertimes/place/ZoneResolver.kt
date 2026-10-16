package com.tunisianprayertimes.place

import com.tunisianprayertimes.method.CountryMethodDefaults
import java.time.DateTimeException
import java.time.ZoneId

/** The zone of a place (plan 6.8): pure, from the catalogue's rows and the phone's zone. */
object ZoneResolver {
    const val RADIUS_KM = 40.0

    /**
     * The zone id of a place in [countryCode]. A picked catalogue row carries its own zone; a place in the
     * Jerusalem governorate takes the phone's zone when it is Asia/Jerusalem or Asia/Hebron, else Asia/Hebron,
     * the clock Dar al-Ifta prints in (Q1). For a fix, the zones of the country's rows within 40 km: one is
     * used; of several, the phone's when it is among them (twin towns), else the nearest row's; with no row
     * (at sea, outside the catalogues), the phone's zone. Rows of another country never lend a place their
     * zone; with the country unknown, every row counts.
     */
    fun resolve(
        point: LatLngE5,
        countryCode: String?,
        index: CityIndex,
        phoneZone: String,
        picked: CityRef? = null,
        jerusalemGovernorate: Boolean = false,
    ): String {
        if (jerusalemGovernorate) return if (phoneZone == JERUSALEM || phoneZone == HEBRON) phoneZone else HEBRON
        picked?.let { return it.zoneId }
        val rows = index.within(point, RADIUS_KM)
            .filter { countryCode == null || CountryResolver.normalize(it.countryCode) == countryCode }
        val zones = rows.map { it.zoneId }.distinct()
        return when {
            zones.isEmpty() -> phoneZone
            zones.size == 1 -> zones.single()
            phoneZone in zones -> phoneZone
            else -> rows.minBy { CountryMethodDefaults.distanceKm(point, it.point) }.zoneId
        }
    }

    /**
     * A stored id parsed safely: directly, then through the alias of a renamed zone (an old device knows
     * Europe/Kiev, a new one Europe/Kyiv), then [fallback], the phone's zone.
     */
    fun parse(id: String?, fallback: ZoneId): ZoneId {
        if (id.isNullOrBlank()) return fallback
        tryParse(id)?.let { return it }
        ALIASES[id]?.let { alias -> tryParse(alias)?.let { return it } }
        return fallback
    }

    private fun tryParse(id: String): ZoneId? = try {
        ZoneId.of(id)
    } catch (_: DateTimeException) {
        null
    }

    const val JERUSALEM = "Asia/Jerusalem"
    const val HEBRON = "Asia/Hebron"

    /** Renamed zones, both ways: the new name for an old device and the old one for a device that dropped it. */
    private val ALIASES: Map<String, String> = listOf(
        "Europe/Kyiv" to "Europe/Kiev",
        "America/Nuuk" to "America/Godthab",
        "Pacific/Kanton" to "Pacific/Enderbury",
        "Asia/Yangon" to "Asia/Rangoon",
        "Atlantic/Faroe" to "Atlantic/Faeroe",
        "Asia/Kolkata" to "Asia/Calcutta",
        "Asia/Ho_Chi_Minh" to "Asia/Saigon",
        "Asia/Kathmandu" to "Asia/Katmandu",
    ).flatMap { (new, old) -> listOf(new to old, old to new) }.toMap()
}
