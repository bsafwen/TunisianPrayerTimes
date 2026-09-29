package com.tunisianprayertimes.tv.data

import android.content.Context
import androidx.core.content.edit
import com.tunisianprayertimes.Prayer

/** Mosque settings entered by the admin on the TV. */
class PrefsManager(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isSetupDone: Boolean
        get() = prefs.getBoolean("setup_done", false)
        set(value) = prefs.edit { putBoolean("setup_done", value) }

    var gouvernoratId: Int
        get() = prefs.getInt("gouvernorat_id", 0)
        set(value) = prefs.edit { putInt("gouvernorat_id", value) }

    /** 0 until setup chooses a delegation. */
    var delegationId: Int
        get() = prefs.getInt("delegation_id", 0)
        set(value) = prefs.edit { putInt("delegation_id", value) }

    var delegationName: String
        get() = prefs.getString("delegation_name", "").orEmpty()
        set(value) = prefs.edit { putString("delegation_name", value) }

    var mosqueName: String
        get() = prefs.getString("mosque_name", "").orEmpty()
        set(value) = prefs.edit { putString("mosque_name", value) }

    /** Unknown ids fall back to the first built-in theme in ThemeRegistry. */
    var themeId: String
        get() = prefs.getString("theme_id", "").orEmpty()
        set(value) = prefs.edit { putString("theme_id", value) }

    var announcementsEnabled: Boolean
        get() = prefs.getBoolean("announcements_enabled", false)
        set(value) = prefs.edit { putBoolean("announcements_enabled", value) }

    var customBackgroundEnabled: Boolean
        get() = prefs.getBoolean("custom_background_enabled", false)
        set(value) = prefs.edit { putBoolean("custom_background_enabled", value) }

    var announcementIntervalSec: Int
        get() = prefs.getInt("announcement_interval_sec", DEFAULT_ANNOUNCEMENT_INTERVAL_SEC)
        set(value) = prefs.edit { putInt("announcement_interval_sec", value) }

    fun getIqamahConfig(prayer: Prayer): IqamahConfig = readIqamah(prayer.name)

    fun setIqamahConfig(prayer: Prayer, config: IqamahConfig) = writeIqamah(prayer.name, config)

    fun getJomoaaIqamahConfig(): IqamahConfig = readIqamah(Prayer.JOMOAA.name)

    fun setJomoaaIqamahConfig(config: IqamahConfig) = writeIqamah(Prayer.JOMOAA.name, config)

    private fun readIqamah(key: String): IqamahConfig {
        val default = IqamahConfig()
        val mode = prefs.getString("iqamah_${key}_mode", null)
            ?.let { name -> IqamahMode.entries.find { it.name == name } }
            ?: default.mode
        return IqamahConfig(
            mode = mode,
            delayMinutes = prefs.getInt("iqamah_${key}_delay", default.delayMinutes),
            fixedHour = prefs.getInt("iqamah_${key}_fixed_hour", default.fixedHour),
            fixedMinute = prefs.getInt("iqamah_${key}_fixed_minute", default.fixedMinute),
        )
    }

    private fun writeIqamah(key: String, config: IqamahConfig) = prefs.edit {
        putString("iqamah_${key}_mode", config.mode.name)
        putInt("iqamah_${key}_delay", config.delayMinutes)
        putInt("iqamah_${key}_fixed_hour", config.fixedHour)
        putInt("iqamah_${key}_fixed_minute", config.fixedMinute)
    }

    private companion object {
        const val PREFS_NAME = "tv_mosque_prefs"
        const val DEFAULT_ANNOUNCEMENT_INTERVAL_SEC = 15
    }
}
