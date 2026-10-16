package com.tunisianprayertimes.silence

import com.tunisianprayertimes.method.CountryMethodDefaults
import com.tunisianprayertimes.method.JumuaRule
import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.place.LatLngE5
import com.tunisianprayertimes.place.PrayerPlace
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** A Friday time where no authority sets one (plan 6.9, D12, A18). */
sealed interface FridayTime {
    /** That day's Dhuhr. */
    data object AtDhuhr : FridayTime

    /** A clock time in the place's zone, never earlier than Dhuhr + 5 ([SilenceDefaults.CLAMP_AFTER_DHUHR_MINUTES]). */
    data class Clock(val time: LocalTime) : FridayTime

    /** One value in summer time and one in winter time, either of which may be [AtDhuhr] (Q30). */
    data class Seasonal(val summer: FridayTime, val winter: FridayTime) : FridayTime
}

/**
 * The Jumu'a times of 6.9 by country and place, from the owner's dataset (5.6), mosques' published winter
 * Friday times (Q30) and the published Friday times of Martinique and French Guiana (7.6). Entries are keyed
 * by coordinates, not by city names, which come in several spellings.
 */
class SilenceDefaults(val entries: List<Entry>) {
    /**
     * One entry: a whole [country], the part of it east of [eastOfLongitude], or the places within
     * [PLACE_RADIUS_KM] of [centre]. A place entry holds both of its seasons: it never takes its country's
     * winter value (Q30).
     */
    data class Entry(
        val country: String,
        val time: FridayTime,
        val source: String,
        val centre: LatLngE5? = null,
        val eastOfLongitude: Double? = null,
        val lowConfidence: Boolean = false,
    )

    /** The entry of a point: the nearest place entry within reach, then the part of the country, then the country. */
    fun entryFor(countryCode: String?, point: LatLngE5): Entry? {
        val cc = countryCode?.uppercase() ?: return null
        val own = entries.filter { it.country == cc }
        own.filter { it.centre != null }
            .map { it to CountryMethodDefaults.distanceKm(point, checkNotNull(it.centre)) }
            .filter { (_, km) -> km <= PLACE_RADIUS_KM }
            .minByOrNull { (_, km) -> km }
            ?.let { return it.first }
        own.firstOrNull { it.centre == null && it.eastOfLongitude != null && point.longitude >= it.eastOfLongitude }?.let { return it }
        return own.firstOrNull { it.centre == null && it.eastOfLongitude == null }
    }

    /**
     * A18: the default Jumu'a time of the Friday [date] at [place], whose Dhuhr is [dhuhr]. The order is the
     * authority's rule, then the place's or country's entry, then Dhuhr. The authority is the place's own
     * (its country's default profile, 6.4), whichever method the user computes with, since a Friday time is
     * the mosques' practice: the UAE's 12:45 holds there under any method and nowhere else. Its clock, as
     * revised on [date] (E9), is held at Dhuhr, before which Jumu'a is not prayed; an entry's clock is held
     * at Dhuhr + 5. A Tunisian delegation has neither, so Dhuhr.
     */
    fun jumuaTime(place: PrayerPlace, date: LocalDate, dhuhr: Instant): Instant {
        val coordinates = place as? PrayerPlace.Coordinates ?: return dhuhr
        val zone = place.zone
        authorityClock(coordinates, date)?.let { return maxOf(ZonedDateTime.of(date, it, zone).toInstant(), dhuhr) }
        val entry = entryFor(coordinates.countryCode, coordinates.point) ?: return dhuhr
        return resolve(entry.time, date, zone, dhuhr)
    }

    private fun authorityClock(place: PrayerPlace.Coordinates, date: LocalDate): LocalTime? {
        val own = CountryMethodDefaults.forCountry(place.countryCode, place.zoneId, place.admin1, place.admin2, place.point).fallback
        return (PrayerMethods.byId(own)?.revisedFor(date)?.jumua as? JumuaRule.Fixed)?.time
    }

    private fun resolve(time: FridayTime, date: LocalDate, zone: ZoneId, dhuhr: Instant): Instant = when (time) {
        FridayTime.AtDhuhr -> dhuhr
        is FridayTime.Clock -> maxOf(ZonedDateTime.of(date, time.time, zone).toInstant(), dhuhr.plusSeconds(CLAMP_AFTER_DHUHR_MINUTES * 60))
        is FridayTime.Seasonal -> resolve(if (zone.rules.isDaylightSavings(dhuhr)) time.summer else time.winter, date, zone, dhuhr)
    }

    companion object {
        /** About the size of a city and its suburbs. */
        const val PLACE_RADIUS_KM = 25.0

        const val CLAMP_AFTER_DHUHR_MINUTES = 5L

        private fun at(hour: Int, minute: Int) = FridayTime.Clock(LocalTime.of(hour, minute))
        private fun seasonal(summer: FridayTime, winter: FridayTime) = FridayTime.Seasonal(summer, winter)
        private const val DATASET = "dataset 5.6"
        private const val WINTER = "dataset 5.6; published winter Friday times (Q30)"

        /**
         * Plan 6.9's table. Norway, Austria, Ireland and Luxembourg have no entry (no seasonal time was
         * found, Q30); the Netherlands, Switzerland and Sweden keep Dhuhr (46 %, 33 % and 70 % of their
         * mosques); the other Arab countries and Turkey need none, since Dhuhr already fits.
         */
        val BUNDLED = SilenceDefaults(
            listOf(
                // Algeria: Dhuhr west of 2°E; 13:00 east of it, with Dhuhr in six eastern towns.
                Entry("DZ", at(13, 0), DATASET, eastOfLongitude = 2.0),
                Entry("DZ", FridayTime.AtDhuhr, DATASET, centre = LatLngE5.of(36.9000, 7.7667)), // Annaba
                Entry("DZ", FridayTime.AtDhuhr, DATASET, centre = LatLngE5.of(36.2864, 7.9511)), // Souk Ahras
                Entry("DZ", FridayTime.AtDhuhr, DATASET, centre = LatLngE5.of(34.8500, 5.7333)), // Biskra
                Entry("DZ", FridayTime.AtDhuhr, DATASET, centre = LatLngE5.of(36.3750, 3.9000)), // Bouira
                Entry("DZ", FridayTime.AtDhuhr, DATASET, centre = LatLngE5.of(35.2133, 4.1739)), // Bou Saâda
                Entry("DZ", FridayTime.AtDhuhr, DATASET, centre = LatLngE5.of(36.4572, 4.5347)), // Akbou
                // Europe, summer time / winter time (Q30).
                Entry("BE", seasonal(at(14, 0), at(13, 0)), WINTER),
                Entry("GB", seasonal(at(13, 30), at(13, 0)), WINTER),
                Entry("PT", seasonal(at(14, 0), at(13, 0)), WINTER),
                Entry("FR", seasonal(at(14, 0), at(13, 0)), WINTER, lowConfidence = true),
                Entry("ES", seasonal(at(14, 0), at(13, 30)), WINTER),
                Entry("ES", seasonal(at(14, 45), FridayTime.AtDhuhr), WINTER, centre = LatLngE5.of(40.4168, -3.7038)), // Madrid
                Entry("ES", seasonal(at(14, 0), at(13, 30)), WINTER, centre = LatLngE5.of(41.3874, 2.1686)), // Barcelona
                Entry("DE", seasonal(at(14, 0), at(13, 0)), WINTER, lowConfidence = true),
                Entry("DE", seasonal(at(13, 30), FridayTime.AtDhuhr), WINTER, centre = LatLngE5.of(48.1374, 11.5755)), // Munich
                Entry("IT", seasonal(FridayTime.AtDhuhr, at(12, 30)), WINTER),
                Entry("AL", seasonal(at(13, 0), at(12, 0)), WINTER),
                Entry("BA", seasonal(at(13, 0), at(12, 0)), WINTER),
                Entry("ME", seasonal(at(13, 0), at(12, 0)), WINTER),
                Entry("MK", seasonal(at(13, 0), at(12, 0)), WINTER),
                Entry("RS", seasonal(at(13, 0), at(12, 0)), WINTER),
                Entry("MR", at(14, 0), DATASET),
                // Published Friday times (7.6), neither territory with summer time.
                Entry("MQ", at(13, 0), "CCIM's published Friday adhan"),
                Entry("GF", at(13, 30), "A2CMGF's Cayenne mosque"),
            ),
        )
    }
}
