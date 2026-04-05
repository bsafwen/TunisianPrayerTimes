package com.tunisianprayertimes.platform

import com.tunisianprayertimes.*
import java.io.File

/**
 * Desktop implementation: loads gouvernorats from a bundled JSON file.
 */
actual object GouvernoratLoader {
    private var dataDir: File? = null
    private var cachedGouvernorats: List<Gouvernorat>? = null
    private var cachedDelegations: List<Delegation>? = null

    fun init(dataDirectory: File) {
        dataDir = dataDirectory
    }

    actual fun loadAll(): List<Gouvernorat> {
        cachedGouvernorats?.let { return it }
        val dir = dataDir ?: error("GouvernoratLoader not initialized. Call init() first.")
        val jsonFile = File(dir, "gouvernorats.json")
        if (!jsonFile.exists()) return emptyList()
        val result = GouvernoratJsonParser.parse(jsonFile.readText())
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
