package com.tunisianprayertimes

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale
import java.util.TimeZone

private const val TUNISIA_COUNTRY_CODE = "tn"
private const val TUNISIA_TIME_ZONE_ID = "Africa/Tunis"

/**
 * Whether the phone is probably in Tunisia, judged without location permission: the
 * mobile network's country when there is one, otherwise the time zone. The selected
 * delegation only makes a sensible stand-in location when this holds.
 */
fun isPhoneLikelyInTunisia(context: Context): Boolean {
    val networkCountryIso = runCatching {
        (context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
            ?.networkCountryIso
    }.getOrNull()
    return isLikelyInTunisia(networkCountryIso, TimeZone.getDefault().id)
}

internal fun isLikelyInTunisia(networkCountryIso: String?, timeZoneId: String?): Boolean {
    val networkCountry = networkCountryIso?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (networkCountry.isNotEmpty()) {
        return networkCountry == TUNISIA_COUNTRY_CODE
    }
    return timeZoneId == TUNISIA_TIME_ZONE_ID
}
