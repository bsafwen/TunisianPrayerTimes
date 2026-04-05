package com.tunisianprayertimes.platform

import android.content.Context
import com.tunisianprayertimes.*
import org.json.JSONObject

actual object GouvernoratLoader {
    private var appContext: Context? = null
    private var cachedGouvernorats: List<Gouvernorat>? = null
    private var cachedDelegations: List<Delegation>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context =
        appContext ?: error("GouvernoratLoader not initialized. Call init(context) first.")

    actual fun loadAll(): List<Gouvernorat> {
        cachedGouvernorats?.let { return it }
        val json = requireContext().assets.open("gouvernorats.json").bufferedReader().use { it.readText() }
        val result = GouvernoratJsonParser.parse(json)
        cachedGouvernorats = result
        return result
    }

    actual fun loadAllDelegations(): List<Delegation> {
        cachedDelegations?.let { return it }
        val now = java.util.Calendar.getInstance()
        val year = now.get(java.util.Calendar.YEAR)
        val month = now.get(java.util.Calendar.MONTH) + 1
        val all = loadAll().flatMap { it.delegations }.filter { delegation ->
            PrayerDataLoader.hasPrayerData(delegation.id, year, month)
        }
        cachedDelegations = all
        return all
    }

    actual fun findDelegationById(id: Int): Delegation? {
        return loadAllDelegations().find { it.id == id }
    }

    actual fun findNearestDelegation(lat: Double, lng: Double): Delegation? {
        return loadAllDelegations()
            .filter { it.lat != 0.0 && it.lng != 0.0 }
            .minByOrNull { haversineKm(lat, lng, it.lat, it.lng) }
    }
}
