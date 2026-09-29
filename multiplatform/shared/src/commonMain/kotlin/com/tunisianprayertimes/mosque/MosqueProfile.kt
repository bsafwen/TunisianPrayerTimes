package com.tunisianprayertimes.mosque

/**
 * The rest of a mosque screen's settings: its name, where it is and how it looks. With the
 * schedule and the Ramadan and Eid dates, it makes the whole settings file, so one configured
 * TV can be copied to the others of a mosque with a USB key. Null fields are not set.
 */
data class MosqueProfile(
    val name: String? = null,
    val delegationId: Int? = null,
    val themeId: String? = null,
) {
    companion object {
        const val MAX_NAME_LENGTH = 60
    }
}

/**
 * What a settings file may name, from the app: the delegations it has prayer times for and its
 * themes (id to Arabic name). Without it, a file naming a place or theme is refused.
 */
data class ProfileCatalog(
    val delegationName: (Int) -> String?,
    val themes: Map<String, String>,
) {
    fun themeName(id: String): String = themes[id] ?: id

    companion object {
        val NONE = ProfileCatalog({ null }, emptyMap())
    }
}
