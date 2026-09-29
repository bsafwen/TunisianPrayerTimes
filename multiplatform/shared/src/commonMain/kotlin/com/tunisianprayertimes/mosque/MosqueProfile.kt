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
    val display: DisplayOptions = DisplayOptions(),
) {
    companion object {
        const val MAX_NAME_LENGTH = 60
    }
}

/**
 * How the screen shows the mosque's extras: the weather (when online), its background images and
 * announcements, how long each announcement stays, how often they come back between prayers
 * (0: only after the adhkar of each prayer), and whether the screen dims to the night screen
 * between Isha and Fajr (the rest that spares the TV's panel). Null fields are not set.
 */
data class DisplayOptions(
    val weather: Boolean? = null,
    val backgrounds: Boolean? = null,
    val announcements: Boolean? = null,
    val slideSeconds: Int? = null,
    val announcementsEveryMinutes: Int? = null,
    val nightScreen: Boolean? = null,
) {
    companion object {
        val SLIDE_SECONDS = 5..60
        val EVERY_MINUTES = 0..120
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
