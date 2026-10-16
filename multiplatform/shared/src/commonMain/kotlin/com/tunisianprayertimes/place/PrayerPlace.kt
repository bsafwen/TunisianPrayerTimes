package com.tunisianprayertimes.place

import java.time.ZoneId
import kotlinx.serialization.Serializable

/** A point in whole 1e-5 degrees (about 1 m), the form places and official zones store (plan 6.5, 6.8). */
@Serializable
data class LatLngE5(val latE5: Int, val lngE5: Int) {
    val latitude: Double get() = latE5 / 1e5
    val longitude: Double get() = lngE5 / 1e5

    companion object {
        fun of(latitude: Double, longitude: Double) =
            LatLngE5(Math.round(latitude * 1e5).toInt(), Math.round(longitude * 1e5).toInt())
    }
}

/** Where the zone of a [PrayerPlace.Coordinates] came from (plan 6.8). */
enum class ZoneSource { CATALOGUE, NEAREST_CITY, DEVICE, USER }

/**
 * The place prayer times are computed for (plan 6.8): a Tunisian delegation, which INM's tables
 * serve, or a point anywhere else, which the shared engine serves.
 */
sealed interface PrayerPlace {
    /** The place's time zone: "today" and every clock time tied to a prayer are read in it (6.6). */
    val zone: ZoneId

    /** A stable key for caches: "inm:615" or "geo:4886000,235000,Europe/Paris" (e5 values verbatim). */
    val cacheKey: String

    /** A Tunisian delegation, computed by INM's formula ([com.tunisianprayertimes.InmPrayerTimes]). */
    data class Inm(val delegationId: Int) : PrayerPlace {
        override val zone: ZoneId get() = TUNIS
        override val cacheKey: String get() = "inm:$delegationId"
    }

    /**
     * A point outside Tunisia. [elevationM] is the catalogue's `dem` where the plan uses one (an Arab
     * League member, Ceuta and Melilla, Réunion), else null; [admin1] and [admin2] are the divisions the
     * zone mapping reads (an RU federal subject, an FR département).
     */
    data class Coordinates(
        val latE5: Int,
        val lngE5: Int,
        val elevationM: Int? = null,
        val zoneId: String,
        val zoneSource: ZoneSource,
        val countryCode: String? = null,
        val catalogueId: Long? = null,
        val admin1: String? = null,
        val admin2: String? = null,
    ) : PrayerPlace {
        val point: LatLngE5 get() = LatLngE5(latE5, lngE5)
        override val zone: ZoneId get() = ZoneId.of(zoneId)
        override val cacheKey: String get() = "geo:$latE5,$lngE5,$zoneId"

        /** Mainland France, Corsica included: the places where every method is held inside E19 (7.7). */
        val isMainlandFrance: Boolean get() = countryCode == "FR"
    }

    companion object {
        val TUNIS: ZoneId = ZoneId.of("Africa/Tunis")
    }
}
