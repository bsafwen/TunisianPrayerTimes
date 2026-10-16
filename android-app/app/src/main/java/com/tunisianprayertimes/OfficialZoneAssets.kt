package com.tunisianprayertimes

import android.content.Context
import android.util.Log
import com.tunisianprayertimes.place.OfficialZoneIndex
import com.tunisianprayertimes.place.OfficialZones

/** The official-zone assets of plan 6.5 (`assets/official-zones/<authority>.json`), read once. */
object OfficialZoneAssets {
    private const val TAG = "OfficialZoneAssets"
    private const val FOLDER = "official-zones"

    @Volatile
    private var index: OfficialZoneIndex? = null

    fun index(context: Context): OfficialZoneIndex = index ?: synchronized(this) {
        index ?: load(context.applicationContext).also { index = it }
    }

    private fun load(context: Context): OfficialZoneIndex {
        val names = runCatching { context.assets.list(FOLDER)?.toList() }.getOrNull().orEmpty().filter { it.endsWith(".json") }
        val assets = names.mapNotNull { name ->
            runCatching { context.assets.open("$FOLDER/$name").bufferedReader().use { OfficialZones.parse(it.readText()) } }
                .onFailure { Log.w(TAG, "Unreadable official-zone asset $name", it) }
                .getOrNull()
        }
        return OfficialZoneIndex(assets)
    }
}
