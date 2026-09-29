package com.tunisianprayertimes.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tunisianprayertimes.Prayer

/** Mosque settings entered by the admin on the TV. */
class PrefsManager(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    var isSetupDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_SETUP_DONE, value) }

    /** -1 until setup chooses a gouvernorat. */
    var gouvernoratId: Int
        get() = prefs.getInt(KEY_GOUVERNORAT_ID, -1)
        set(value) = prefs.edit { putInt(KEY_GOUVERNORAT_ID, value) }

    /** -1 until setup chooses a delegation. */
    var delegationId: Int
        get() = prefs.getInt(KEY_DELEGATION_ID, -1)
        set(value) = prefs.edit { putInt(KEY_DELEGATION_ID, value) }

    var delegationName: String
        get() = prefs.getString(KEY_DELEGATION_NAME, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_DELEGATION_NAME, value) }

    var mosqueName: String
        get() = prefs.getString(KEY_MOSQUE_NAME, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_MOSQUE_NAME, value) }

    var themeId: String
        get() = prefs.getString(KEY_THEME_ID, DEFAULT_THEME_ID) ?: DEFAULT_THEME_ID
        set(value) = prefs.edit { putString(KEY_THEME_ID, value) }

    var announcementsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANNOUNCEMENTS_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ANNOUNCEMENTS_ENABLED, value) }

    var customBackgroundEnabled: Boolean
        get() = prefs.getBoolean(KEY_CUSTOM_BG_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_CUSTOM_BG_ENABLED, value) }

    var announcementIntervalSec: Int
        get() = prefs.getInt(KEY_ANNOUNCEMENT_INTERVAL_SEC, 15)
        set(value) = prefs.edit { putInt(KEY_ANNOUNCEMENT_INTERVAL_SEC, value) }

    fun getIqamahConfig(prayer: Prayer): IqamahConfig = readIqamah(prayer.name, defaultDelay = 10)

    fun setIqamahConfig(prayer: Prayer, config: IqamahConfig) = writeIqamah(prayer.name, config)

    fun getJomoaaIqamahConfig(): IqamahConfig = readIqamah(Prayer.JOMOAA.name, defaultDelay = 15)

    fun setJomoaaIqamahConfig(config: IqamahConfig) = writeIqamah(Prayer.JOMOAA.name, config)

    private fun readIqamah(name: String, defaultDelay: Int): IqamahConfig {
        val mode = prefs.getString("iqamah_mode_$name", null)
            ?.let { saved -> IqamahMode.entries.find { it.name == saved } }
            ?: IqamahMode.DELAY
        return IqamahConfig(
            mode = mode,
            delayMinutes = prefs.getInt("iqamah_delay_$name", defaultDelay),
            fixedHour = prefs.getInt("iqamah_fixed_h_$name", -1),
            fixedMinute = prefs.getInt("iqamah_fixed_m_$name", -1),
        )
    }

    private fun writeIqamah(name: String, config: IqamahConfig) = prefs.edit {
        putString("iqamah_mode_$name", config.mode.name)
        putInt("iqamah_delay_$name", config.delayMinutes)
        putInt("iqamah_fixed_h_$name", config.fixedHour)
        putInt("iqamah_fixed_m_$name", config.fixedMinute)
    }

    companion object {
        const val PREFS_NAME = "tv_prefs"
        const val DEFAULT_THEME_ID = "midnight_navy"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_GOUVERNORAT_ID = "gouvernorat_id"
        private const val KEY_DELEGATION_ID = "delegation_id"
        private const val KEY_DELEGATION_NAME = "delegation_name"
        private const val KEY_MOSQUE_NAME = "mosque_name"
        private const val KEY_THEME_ID = "theme_id"
        private const val KEY_ANNOUNCEMENTS_ENABLED = "announcements_enabled"
        private const val KEY_CUSTOM_BG_ENABLED = "custom_bg_enabled"
        private const val KEY_ANNOUNCEMENT_INTERVAL_SEC = "announcement_interval_sec"
    }
}
