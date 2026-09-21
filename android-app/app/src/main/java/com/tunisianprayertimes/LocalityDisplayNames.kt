package com.tunisianprayertimes

import android.content.Context
import org.json.JSONObject

/**
 * Runtime-only Arabic display names for stable locality IDs.
 * Missing or malformed assets leave all existing names unchanged.
 */
internal object LocalityDisplayNames {
    private const val ASSET_NAME = "locality-display-names.json"

    private data class DisplayName(
        val nameAr: String,
        val searchAliases: List<String>,
    )

    @Volatile
    private var cached: Map<String, DisplayName>? = null
    private val lock = Any()

    private fun names(context: Context): Map<String, DisplayName> {
        cached?.let { return it }
        return synchronized(lock) {
            cached ?: load(context).also { cached = it }
        }
    }

    private fun load(context: Context): Map<String, DisplayName> = runCatching {
        val json = JSONObject(
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() },
        )
        require(json.getInt("schemaVersion") == 1)
        val rows = json.getJSONArray("names")
        val result = LinkedHashMap<String, DisplayName>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val id = row.get("id")
            val nameAr = row.get("nameAr")
            require(id is String && id.isNotBlank())
            require(nameAr is String && nameAr.isNotBlank())
            val aliases = row.getJSONArray("searchAliases")
            val searchAliases = List(aliases.length()) { aliasIndex ->
                val alias = aliases.get(aliasIndex)
                require(alias is String && alias.isNotBlank())
                alias
            }
            require(!result.containsKey(id))
            result[id] = DisplayName(nameAr, searchAliases)
        }
        result.toMap()
    }.getOrDefault(emptyMap<String, DisplayName>())

    /** Returns a display-name copy, or the original when the ID has no asset row. */
    fun localize(context: Context, locality: Locality): Locality {
        val displayName = names(context)[locality.id] ?: return locality
        val searchText = normalizeLocalitySearch(
            (listOf(locality.searchText, locality.name, displayName.nameAr) + displayName.searchAliases)
                .joinToString(" "),
        )
        return locality.copy(name = displayName.nameAr, searchText = searchText)
    }

    /** Arabic name for a saved stable ID, or null to keep the saved display name. */
    fun nameAr(context: Context, id: String): String? = names(context)[id]?.nameAr
}
