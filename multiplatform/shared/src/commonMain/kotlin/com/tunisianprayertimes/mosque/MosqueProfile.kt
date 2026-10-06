package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.PrayerFormulaSettings

/**
 * The rest of a mosque screen's settings: its name, where it is, how it looks and how its prayer
 * times are computed ([formula]: null for INM's official values). With the schedule and the Ramadan
 * and Eid dates, it makes the whole settings file, so one configured TV can be copied to the others
 * of a mosque with a USB key. Null fields are not set.
 */
data class MosqueProfile(
    val name: String? = null,
    val delegationId: Int? = null,
    val themeId: String? = null,
    val display: DisplayOptions = DisplayOptions(),
    val formula: PrayerFormulaSettings? = null,
) {
    /** The values the times are computed with: [formula], or INM's official ones. */
    val formulaSettings: PrayerFormulaSettings get() = formula ?: PrayerFormulaSettings.OFFICIAL

    companion object {
        const val MAX_NAME_LENGTH = 60
    }
}

/**
 * How the screen shows the mosque's extras: the weather (when online), its background images and
 * announcements, how long each announcement stays, how often they come back between prayers
 * (0: only after the adhkar of each prayer), and whether the screen dims to the night screen
 * between Isha and Fajr (the rest that spares the TV's panel), and how many minutes the adhan screen
 * lasts (FlowTiming.adhanScreenMinutes). Null fields are not set.
 */
data class DisplayOptions(
    val weather: Boolean? = null,
    val backgrounds: Boolean? = null,
    val announcements: Boolean? = null,
    val slideSeconds: Int? = null,
    val announcementsEveryMinutes: Int? = null,
    val nightScreen: Boolean? = null,
    val adhanScreenMinutes: Int? = null,
) {
    companion object {
        val SLIDE_SECONDS = 5..60
        val EVERY_MINUTES = 0..120
    }
}

/**
 * What a settings file may name, from the app: the delegations it has prayer times for and its
 * themes (id to Arabic name). Without it, a file naming a place or theme is refused.
 * [delegationIds] lists every delegation, to find one by the Arabic name a file's admin edited.
 */
data class ProfileCatalog(
    val delegationName: (Int) -> String?,
    val themes: Map<String, String>,
    val delegationIds: () -> List<Int> = { emptyList() },
) {
    fun themeName(id: String): String = themes[id] ?: id

    companion object {
        val NONE = ProfileCatalog({ null }, emptyMap())
    }
}
