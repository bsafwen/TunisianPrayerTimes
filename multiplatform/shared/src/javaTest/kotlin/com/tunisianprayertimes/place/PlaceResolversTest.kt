package com.tunisianprayertimes.place

import com.tunisianprayertimes.method.CountryMethodDefaults
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** CountryResolver and ZoneResolver (plan 6.8) on a small synthetic catalogue. */
class PlaceResolversTest {
    private class ListIndex(private val rows: List<CityRef>) : CityIndex {
        override fun within(point: LatLngE5, km: Double) = rows
            .map { it to CountryMethodDefaults.distanceKm(point, it.point) }
            .filter { (_, d) -> d <= km }
            .sortedBy { (_, d) -> d }
            .map { it.first }

        override fun nearestOf(point: LatLngE5, countryCode: String) = rows
            .filter { CountryResolver.normalize(it.countryCode) == countryCode }
            .minByOrNull { CountryMethodDefaults.distanceKm(point, it.point) }
    }

    private fun row(id: Long, lat: Double, lng: Double, cc: String, zone: String, admin1: String? = null, admin2: String? = null) =
        CityRef(id, LatLngE5.of(lat, lng), cc, zone, admin1, admin2)

    private val catalogue = ListIndex(
        listOf(
            row(1, 41.6771, 26.5557, "TR", "Europe/Istanbul", admin2 = "Edirne Merkez"), // Edirne
            row(2, 41.5031, 26.5297, "GR", "Europe/Athens"), // Orestiada
            row(3, 41.7667, 26.2000, "BG", "Europe/Sofia"), // Svilengrad
            row(4, 48.5734, 7.7521, "FR", "Europe/Paris", admin2 = "67"), // Strasbourg
            row(5, 48.5726, 7.8156, "DE", "Europe/Berlin"), // Kehl
            row(6, 34.7406, 10.7603, "TN", "Africa/Tunis"), // Sfax's delegation seat
            row(7, 31.9038, 35.2034, "PS", "Asia/Hebron", admin2 = "Ramallah and al-Bireh"), // Ramallah
            row(8, 31.5017, 34.4668, "PS", "Asia/Gaza", admin2 = "Gaza"), // Gaza City
            row(9, 46.1937, 6.2347, "FR", "Europe/Paris", admin2 = "74"), // Annemasse
            row(10, 46.2044, 6.1432, "CH", "Europe/Zurich"), // Geneva
            row(11, 55.7558, 37.6173, "RU", "Europe/Moscow", admin1 = "48"), // Moscow
        ),
    )

    @Test
    fun theNetworkCountryWinsAmongTheNearbyCountries() {
        val edirne = LatLngE5.of(41.6771, 26.5557)
        assertEquals("TR", CountryResolver.resolve(edirne, catalogue, "tr", null).countryCode)
        assertEquals("GR", CountryResolver.resolve(edirne, catalogue, "gr", null).countryCode)
        assertEquals("TR", CountryResolver.resolve(edirne, catalogue, null, null).countryCode)
        assertEquals("Edirne Merkez", CountryResolver.resolve(edirne, catalogue, "tr", null).admin2)
        // The Rhine: Kehl is the nearest row, a French network gives FR and the département of the nearest FR row.
        val rhine = LatLngE5.of(48.5730, 7.8000)
        assertEquals(CountryResolver.Resolution("FR", null, "67"), CountryResolver.resolve(rhine, catalogue, "fr", null))
        assertEquals(CountryResolver.Resolution("DE"), CountryResolver.resolve(rhine, catalogue, "de", null))
        assertEquals("DE", CountryResolver.resolve(rhine, catalogue, null, null).countryCode)
        // 20 km off Sfax, outside the outline, on a Tunisian network: TN (the app then takes its nearest delegation).
        assertEquals("TN", CountryResolver.resolve(LatLngE5.of(34.74, 10.98), catalogue, "tn", null).countryCode)
        // Palestinian networks read il, which has no rows: the nearest row decides, with its governorate.
        assertEquals(CountryResolver.Resolution("PS", null, "Ramallah and al-Bireh"), CountryResolver.resolve(LatLngE5.of(31.90, 35.21), catalogue, "il", null))
        assertEquals("Gaza", CountryResolver.resolve(LatLngE5.of(31.52, 34.45), catalogue, "il", null).admin2)
        // Moscow's federal subject is admin1.
        assertEquals(CountryResolver.Resolution("RU", "48", null), CountryResolver.resolve(LatLngE5.of(55.70, 37.50), catalogue, "ru", null))
    }

    @Test
    fun farFromEveryRow() {
        // Longyearbyen with no row in reach and no network: the phone's zone region SJ reads as NO.
        val longyearbyen = LatLngE5.of(78.2232, 15.6267)
        assertEquals("NO", CountryResolver.resolve(longyearbyen, catalogue, null, "SJ").countryCode)
        assertEquals("NO", CountryResolver.resolve(longyearbyen, catalogue, "no", "SJ").countryCode)
        assertEquals("MA", CountryResolver.resolve(LatLngE5.of(25.0, -13.0), catalogue, "eh", null).countryCode)
        assertNull(CountryResolver.resolve(LatLngE5.of(0.0, -30.0), catalogue, null, "001").countryCode)
        assertNull(CountryResolver.resolve(LatLngE5.of(0.0, -30.0), catalogue, null, null).countryCode)
        // A picked row's own values win.
        val picked = row(12, 45.7640, 4.8357, "FR", "Europe/Paris", admin2 = "69")
        assertEquals(CountryResolver.Resolution("FR", null, "69"), CountryResolver.resolve(picked.point, catalogue, "de", null, picked))
    }

    @Test
    fun zones() {
        // A row of another country never lends its zone: a French fix by Geneva stays on Paris time.
        assertEquals("Europe/Paris", ZoneResolver.resolve(LatLngE5.of(46.20, 6.16), "FR", catalogue, "Europe/Zurich"))
        assertEquals("Europe/Zurich", ZoneResolver.resolve(LatLngE5.of(46.20, 6.16), "CH", catalogue, "Europe/Paris"))
        // Several zones in reach: the phone's when it is one of them, else the nearest row's.
        assertEquals("Asia/Gaza", ZoneResolver.resolve(LatLngE5.of(31.50, 34.47), null, catalogue, "Europe/Paris"))
        val twins = ListIndex(listOf(row(1, 65.8481, 24.1466, "XX", "Europe/Helsinki"), row(2, 65.8355, 24.1368, "XX", "Europe/Stockholm")))
        assertEquals("Europe/Helsinki", ZoneResolver.resolve(LatLngE5.of(65.84, 24.14), "XX", twins, "Europe/Helsinki"))
        assertEquals("Europe/Stockholm", ZoneResolver.resolve(LatLngE5.of(65.84, 24.14), "XX", twins, "Europe/Stockholm"))
        assertEquals("Europe/Stockholm", ZoneResolver.resolve(LatLngE5.of(65.835, 24.136), "XX", twins, "Asia/Tokyo"))
        // At sea: the phone's zone. A picked row: its own.
        assertEquals("Atlantic/Azores", ZoneResolver.resolve(LatLngE5.of(40.0, -30.0), null, catalogue, "Atlantic/Azores"))
        assertEquals("Europe/Berlin", ZoneResolver.resolve(LatLngE5.of(48.5730, 7.8000), "FR", catalogue, "Europe/Paris", picked = row(5, 48.5726, 7.8156, "DE", "Europe/Berlin")))
        // The Jerusalem governorate: the phone's zone if Israeli or West Bank time, else Asia/Hebron (Q1).
        val eastJerusalem = LatLngE5.of(31.78, 35.23)
        assertEquals("Asia/Jerusalem", ZoneResolver.resolve(eastJerusalem, "PS", catalogue, "Asia/Jerusalem", jerusalemGovernorate = true))
        assertEquals("Asia/Hebron", ZoneResolver.resolve(eastJerusalem, "PS", catalogue, "Europe/Paris", jerusalemGovernorate = true))
    }

    @Test
    fun storedIdsAreParsedSafely() {
        val paris = ZoneId.of("Europe/Paris")
        assertEquals(ZoneId.of("Europe/Kyiv"), ZoneResolver.parse("Europe/Kyiv", paris))
        assertEquals(paris, ZoneResolver.parse("Not/AZone", paris))
        assertEquals(paris, ZoneResolver.parse(null, paris))
        assertEquals(paris, ZoneResolver.parse("", paris))
    }
}
