package com.tunisianprayertimes

import android.content.Context
import android.util.Log
import com.tunisianprayertimes.place.AuthorityZones
import com.tunisianprayertimes.place.OfficialZoneIndex
import com.tunisianprayertimes.place.OfficialZones

/**
 * The official-zone assets of plan 6.5 (`assets/official-zones/<authority>.json`, about 5 MB of JSON). Their manifest
 * (`index.tsv`: authority, method, countries) is read once and each asset only when its method serves a place in its
 * countries, so a cold process (an alarm) parses at most the asset its place needs.
 */
object OfficialZoneAssets {
    private const val TAG = "OfficialZoneAssets"
    private const val FOLDER = "official-zones"

    @Volatile
    private var index: OfficialZoneIndex? = null

    fun index(context: Context): OfficialZoneIndex = index ?: synchronized(this) {
        index ?: load(context.applicationContext).also { index = it }
    }

    private fun load(context: Context): OfficialZoneIndex {
        val manifest = runCatching { context.assets.open("$FOLDER/index.tsv").bufferedReader().use { it.readLines() } }.getOrNull()
            ?: return OfficialZoneIndex(allAssets(context))
        val entries = manifest.drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val columns = line.split('\t')
            if (columns.size < 3) return@mapNotNull null
            val authority = columns[0]
            OfficialZoneIndex.Entry(authority, columns[1], columns[2].split(',').filter { it.isNotBlank() }.toSet()) {
                read(context, "$authority.json")
            }
        }
        return OfficialZoneIndex.lazy(entries)
    }

    /** Every asset at once, for a build without the manifest. */
    private fun allAssets(context: Context): List<AuthorityZones> =
        runCatching { context.assets.list(FOLDER)?.toList() }.getOrNull().orEmpty()
            .filter { it.endsWith(".json") }
            .mapNotNull { read(context, it) }

    private fun read(context: Context, name: String): AuthorityZones? =
        runCatching { context.assets.open("$FOLDER/$name").bufferedReader().use { OfficialZones.parse(it.readText()) } }
            .onFailure { Log.w(TAG, "Unreadable official-zone asset $name", it) }
            .getOrNull()
}
