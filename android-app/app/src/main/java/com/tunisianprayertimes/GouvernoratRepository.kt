package com.tunisianprayertimes

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Gouvernorat(
    val id: Int,
    val nomFr: String,
    val nomAr: String,
    val nomEn: String,
    val delegations: List<Delegation>
) {
    fun displayName(): String = nomAr
}

data class Delegation(
    val id: Int,
    val nomFr: String,
    val nomAr: String,
    val nomEn: String,
    val gouvernoratName: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0
) {
    fun displayName(): String {
        return if (gouvernoratName.isNotEmpty()) "$nomAr ($gouvernoratName)" else nomAr
    }

    /** All searchable text for fuzzy matching */
    fun searchableText(): String = "$nomFr $nomAr $nomEn $gouvernoratName".lowercase()

    override fun toString(): String = displayName()
}

object GouvernoratRepository {

    private var cachedGouvernorats: List<Gouvernorat>? = null
    private var cachedDelegations: List<Delegation>? = null

    fun loadAll(context: Context): List<Gouvernorat> {
        cachedGouvernorats?.let { return it }

        val json = context.assets.open("gouvernorats.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val arr = root.getJSONArray("gouvernorats")
        val result = mutableListOf<Gouvernorat>()

        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            val delArr = g.getJSONArray("delegations")
            val delegations = mutableListOf<Delegation>()
            val gName = g.getString("nomAr")

            for (j in 0 until delArr.length()) {
                val d = delArr.getJSONObject(j)
                delegations.add(
                    Delegation(
                        id = d.getInt("id"),
                        nomFr = d.getString("nomFr"),
                        nomAr = d.getString("nomAr"),
                        nomEn = d.getString("nomEn"),
                        gouvernoratName = gName,
                        lat = d.optDouble("lat", 0.0),
                        lng = d.optDouble("lng", 0.0)
                    )
                )
            }

            result.add(
                Gouvernorat(
                    id = g.getInt("id"),
                    nomFr = g.getString("nomFr"),
                    nomAr = g.getString("nomAr"),
                    nomEn = g.getString("nomEn"),
                    delegations = delegations
                )
            )
        }

        cachedGouvernorats = result
        return result
    }

    fun loadAllDelegations(context: Context): List<Delegation> {
        cachedDelegations?.let { return it }
        val now = java.util.Calendar.getInstance()
        val year = now.get(java.util.Calendar.YEAR)
        val month = now.get(java.util.Calendar.MONTH) + 1
        val all = loadAll(context).flatMap { it.delegations }.filter { delegation ->
            PrayerTimesRepository.hasPrayerData(context, delegation.id, year, month)
        }
        cachedDelegations = all
        return all
    }

    fun findDelegationById(context: Context, id: Int): Delegation? {
        return loadAllDelegations(context).find { it.id == id }
    }

    /** Find the nearest available delegation to the given coordinates using Haversine distance. */
    fun findNearestDelegation(context: Context, lat: Double, lng: Double): Delegation? {
        return loadAllDelegations(context)
            .filter { it.lat != 0.0 && it.lng != 0.0 }
            .minByOrNull { haversineKm(lat, lng, it.lat, it.lng) }
    }
}

/** Haversine great-circle distance in km. */
internal fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/** Adapter with fuzzy filtering across all name variants */
class DelegationSearchAdapter(
    context: Context,
    private val allDelegations: List<Delegation>
) : ArrayAdapter<Delegation>(context, android.R.layout.simple_dropdown_item_1line, allDelegations.toMutableList()) {

    private var filtered: List<Delegation> = allDelegations

    override fun getCount(): Int = filtered.size
    override fun getItem(position: Int): Delegation = filtered[position]

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val query = constraint?.toString()?.lowercase()?.trim() ?: ""
            val results = if (query.isEmpty()) {
                allDelegations
            } else {
                val terms = query.split(" ").filter { it.isNotEmpty() }
                allDelegations.filter { delegation ->
                    val text = delegation.searchableText()
                    terms.all { term -> text.contains(term) }
                }
            }
            return FilterResults().apply {
                values = results
                count = results.size
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            filtered = (results?.values as? List<Delegation>) ?: allDelegations
            if (filtered.isNotEmpty()) notifyDataSetChanged() else notifyDataSetInvalidated()
        }

        override fun convertResultToString(resultValue: Any?): CharSequence {
            return (resultValue as? Delegation)?.displayName() ?: ""
        }
    }
}
