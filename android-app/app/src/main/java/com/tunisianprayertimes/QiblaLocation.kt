package com.tunisianprayertimes

import android.content.Context
import android.telephony.TelephonyManager
import com.tunisianprayertimes.place.CountryResolver
import com.tunisianprayertimes.place.PrayerPlace
import java.util.Locale
import java.util.TimeZone

private const val TUNISIA_COUNTRY_CODE = "tn"
private const val TUNISIA_TIME_ZONE_ID = "Africa/Tunis"

/**
 * Whether the phone is probably in Tunisia, judged without location permission: the
 * mobile network's country when there is one, otherwise the time zone. The selected
 * delegation only makes a sensible stand-in location when this holds.
 */
fun isPhoneLikelyInTunisia(context: Context): Boolean =
    isLikelyInTunisia(networkCountryIso(context), TimeZone.getDefault().id)

internal fun isLikelyInTunisia(networkCountryIso: String?, timeZoneId: String?): Boolean =
    isLikelyAt(networkCountryIso, timeZoneId, TUNISIA_COUNTRY_CODE, TUNISIA_TIME_ZONE_ID)

/**
 * Whether the saved place makes a sensible stand-in location for the Qibla (plan 6.8): the mobile network's
 * country is the place's, or, with no network, the phone's time zone is the place's. Anywhere, not only in Tunisia.
 */
fun isPhoneLikelyAtPlace(context: Context, place: PrayerPlace): Boolean = when (place) {
    is PrayerPlace.Inm -> isPhoneLikelyInTunisia(context)
    is PrayerPlace.Coordinates -> isLikelyAt(networkCountryIso(context), TimeZone.getDefault().id, place.countryCode, place.zoneId)
}

internal fun isLikelyAt(networkCountryIso: String?, timeZoneId: String?, placeCountry: String?, placeZoneId: String): Boolean {
    val networkCountry = networkCountryIso?.trim()?.uppercase(Locale.ROOT).orEmpty()
    if (networkCountry.isNotEmpty() && placeCountry != null) {
        return CountryResolver.normalize(networkCountry) == CountryResolver.normalize(placeCountry.uppercase(Locale.ROOT))
    }
    return timeZoneId == placeZoneId
}

private fun networkCountryIso(context: Context): String? = runCatching {
    (context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.networkCountryIso
}.getOrNull()
