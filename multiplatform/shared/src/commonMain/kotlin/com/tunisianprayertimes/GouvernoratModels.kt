package com.tunisianprayertimes

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

    fun searchableText(): String = "$nomFr $nomAr $nomEn $gouvernoratName".lowercase()

    override fun toString(): String = displayName()
}

/** Haversine great-circle distance in km. */
fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}
