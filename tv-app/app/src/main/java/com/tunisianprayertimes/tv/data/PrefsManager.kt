package com.tunisianprayertimes.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.AdhkarContent
import com.tunisianprayertimes.mosque.DisplayOptions
import com.tunisianprayertimes.mosque.FlowTiming
import com.tunisianprayertimes.mosque.MosqueProfile
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.TextAnnouncement
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.PrayerOverride
import com.tunisianprayertimes.tv.ui.theme.ThemeRegistry

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

    /**
     * One of [ThemeRegistry]'s themes. An id saved by an older version (the themes before «أفق») reads
     * as the default: written into the TV's settings file, it would make the file refuse itself
     * (a template edited on a key, the undo snapshot, a copy to another TV).
     */
    var themeId: String
        get() = ThemeRegistry.findById(prefs.getString(KEY_THEME_ID, null) ?: DEFAULT_THEME_ID).id
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

    /** Announcements come back every this many minutes between prayers; 0: only after the adhkar. */
    var announcementsEveryMinutes: Int
        get() = prefs.getInt(KEY_ANNOUNCEMENTS_EVERY, 15)
        set(value) = prefs.edit { putInt(KEY_ANNOUNCEMENTS_EVERY, value) }

    /** The weather at the mosque, shown only when the TV is online and the data is recent. */
    var weatherEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEATHER, true)
        set(value) = prefs.edit { putBoolean(KEY_WEATHER, value) }

    /** The dim night screen between Isha and Fajr, on unless the admin turns it off. */
    var nightScreenEnabled: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_SCREEN, true)
        set(value) = prefs.edit { putBoolean(KEY_NIGHT_SCREEN, value) }

    /**
     * How many minutes the adhan screen lasts ([FlowTiming.ADHAN_SCREEN_MINUTES]); an iqamah set
     * sooner waits for its end.
     */
    var adhanScreenMinutes: Int
        get() = prefs.getInt(KEY_ADHAN_SCREEN_MINUTES, FlowTiming.DEFAULT_ADHAN_SCREEN_MINUTES).coerceIn(FlowTiming.ADHAN_SCREEN_MINUTES)
        set(value) = prefs.edit { putInt(KEY_ADHAN_SCREEN_MINUTES, value.coerceIn(FlowTiming.ADHAN_SCREEN_MINUTES)) }

    /** Content signatures of the last USB settings files the admin applied or dismissed ([com.tunisianprayertimes.tv.usb.HandledSignatures]). */
    var usbHandledSettings: String
        get() = prefs.getString(KEY_USB_HANDLED_SETTINGS, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_USB_HANDLED_SETTINGS, value) }

    /** The mosque's name, place, theme and display options, as the settings file carries them. */
    val profile: MosqueProfile
        get() = MosqueProfile(
            mosqueName.trim().take(MosqueProfile.MAX_NAME_LENGTH), delegationId.takeIf { it > 0 }, themeId,
            DisplayOptions(
                weatherEnabled, customBackgroundEnabled, announcementsEnabled,
                announcementIntervalSec.coerceIn(DisplayOptions.SLIDE_SECONDS),
                announcementsEveryMinutes.coerceIn(DisplayOptions.EVERY_MINUTES),
                nightScreenEnabled,
                adhanScreenMinutes,
            ),
        )

    /** Applies what [profile] sets; [place] gives a delegation's gouvernorat and name, and unknown places are skipped. */
    fun applyProfile(profile: MosqueProfile, place: (Int) -> Pair<Int, String>?) {
        profile.name?.let { mosqueName = it }
        profile.delegationId?.let { id ->
            place(id)?.let { (gouvernorat, name) ->
                gouvernoratId = gouvernorat
                delegationId = id
                delegationName = name
            }
        }
        profile.themeId?.let { themeId = it }
        profile.display.weather?.let { weatherEnabled = it }
        profile.display.backgrounds?.let { customBackgroundEnabled = it }
        profile.display.announcements?.let { announcementsEnabled = it }
        profile.display.slideSeconds?.let { announcementIntervalSec = it }
        profile.display.announcementsEveryMinutes?.let { announcementsEveryMinutes = it }
        profile.display.nightScreen?.let { nightScreenEnabled = it }
        profile.display.adhanScreenMinutes?.let { adhanScreenMinutes = it }
    }

    /** Back to a new TV (one moved to another mosque): setup runs again. The clock's memory, in its own file, is kept. */
    fun resetAll() = prefs.edit { clear() }

    fun getIqamahConfig(prayer: Prayer): IqamahConfig = readIqamah(prayer)

    fun setIqamahConfig(prayer: Prayer, config: IqamahConfig) = writeIqamah(prayer, config)

    /** The rows the admin edits on the TV: the daily prayers, Jumu'a and the two Eids. */
    fun iqamahConfigs(): Map<Prayer, IqamahConfig> = EDITABLE.associateWith(::readIqamah)

    /** The mosque's own texts from the USB file (none: the bundled, reviewed texts), kept in the file's format. */
    var adhkarContent: AdhkarContent
        get() {
            val text = prefs.getString(KEY_ADHKAR, null) ?: return AdhkarContent()
            // Saved by this TV: read leniently, so an app update that retires a text does not lose the mosque's lists.
            val parsed = MosqueSettingsFile.parse(text, MosqueSchedule(), stored = true)
            return (parsed as? MosqueSettingsFile.ParseResult.Success)?.content ?: AdhkarContent()
        }
        set(value) = prefs.edit {
            if (value.isBundled) remove(KEY_ADHKAR) else putString(KEY_ADHKAR, MosqueSettingsFile.write(MosqueSchedule(), content = value))
        }

    /** The mosque's written announcements from the USB file, kept in the file's format. */
    var textAnnouncements: List<TextAnnouncement>
        get() {
            val text = prefs.getString(KEY_ANNOUNCEMENTS, null) ?: return emptyList()
            val parsed = MosqueSettingsFile.parse(text, MosqueSchedule())
            return (parsed as? MosqueSettingsFile.ParseResult.Success)?.announcements.orEmpty()
        }
        set(value) = prefs.edit {
            if (value.isEmpty()) remove(KEY_ANNOUNCEMENTS) else putString(KEY_ANNOUNCEMENTS, MosqueSettingsFile.write(MosqueSchedule(), announcements = value))
        }

    /** The last sets of USB images the admin copied or dismissed, so a key left in is not offered again. */
    var usbHandledMedia: String
        get() = prefs.getString(KEY_USB_HANDLED_MEDIA, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_USB_HANDLED_MEDIA, value) }

    /**
     * Ramadan's changes to the usual settings (for example Isha with tarawih). They come from the
     * USB file and are kept in its own format, so both read the same way.
     */
    var ramadanOverrides: Map<Prayer, PrayerOverride>
        get() {
            val text = prefs.getString(KEY_RAMADAN, null) ?: return emptyMap()
            val parsed = MosqueSettingsFile.parse(text, MosqueSchedule())
            return (parsed as? MosqueSettingsFile.ParseResult.Success)?.schedule?.ramadan.orEmpty()
        }
        set(value) = prefs.edit {
            if (value.isEmpty()) remove(KEY_RAMADAN) else putString(KEY_RAMADAN, MosqueSettingsFile.write(MosqueSchedule(ramadan = value)))
        }

    /**
     * Every prayer's iqamah and duration, with Ramadan's changes, as the shared prayer flow and the
     * USB file use them. Setting a fixed time keeps the saved minutes-after-adhan (and the reverse),
     * so switching back loses nothing, and a fixed time that cannot apply on a day falls back to them.
     */
    var schedule: MosqueSchedule
        get() = IqamahConfig.schedule(iqamahConfigs(), ramadanOverrides)
        set(value) {
            EDITABLE.forEach { prayer ->
                val saved = readIqamah(prayer)
                val incoming = IqamahConfig.from(value.settings(prayer))
                writeIqamah(
                    prayer,
                    if (incoming.mode == IqamahMode.FIXED_TIME) incoming.copy(delayMinutes = saved.delayMinutes)
                    else incoming.copy(fixedHour = saved.fixedHour, fixedMinute = saved.fixedMinute),
                )
            }
            ramadanOverrides = value.ramadan
        }

    private fun readIqamah(prayer: Prayer): IqamahConfig {
        val name = prayer.name
        val default = IqamahConfig.from(MosqueSchedule.DEFAULT.settings(prayer))
        val mode = prefs.getString("iqamah_mode_$name", null)
            ?.let { saved -> IqamahMode.entries.find { it.name == saved } }
            ?: default.mode
        return IqamahConfig(
            mode = mode,
            delayMinutes = prefs.getInt("iqamah_delay_$name", default.delayMinutes),
            fixedHour = prefs.getInt("iqamah_fixed_h_$name", default.fixedHour),
            fixedMinute = prefs.getInt("iqamah_fixed_m_$name", default.fixedMinute),
            salahMinutes = prefs.getInt("salah_minutes_$name", default.salahMinutes),
            held = prefs.getBoolean("held_$name", true),
            khutbaMinutes = prefs.getInt("khutba_minutes_$name", 0),
        )
    }

    private fun writeIqamah(prayer: Prayer, config: IqamahConfig) = prefs.edit {
        val name = prayer.name
        putString("iqamah_mode_$name", config.mode.name)
        putInt("iqamah_delay_$name", config.delayMinutes)
        putInt("iqamah_fixed_h_$name", config.fixedHour)
        putInt("iqamah_fixed_m_$name", config.fixedMinute)
        putInt("salah_minutes_$name", config.salahMinutes)
        putBoolean("held_$name", config.held)
        putInt("khutba_minutes_$name", config.khutbaMinutes)
    }

    companion object {
        const val PREFS_NAME = "tv_prefs"
        val EDITABLE: List<Prayer> = MosqueSchedule.CONFIGURABLE + MosqueSchedule.EID
        const val DEFAULT_THEME_ID = "horizon"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_GOUVERNORAT_ID = "gouvernorat_id"
        private const val KEY_DELEGATION_ID = "delegation_id"
        private const val KEY_DELEGATION_NAME = "delegation_name"
        private const val KEY_MOSQUE_NAME = "mosque_name"
        private const val KEY_THEME_ID = "theme_id"
        private const val KEY_ANNOUNCEMENTS_ENABLED = "announcements_enabled"
        private const val KEY_CUSTOM_BG_ENABLED = "custom_bg_enabled"
        private const val KEY_ANNOUNCEMENT_INTERVAL_SEC = "announcement_interval_sec"
        private const val KEY_USB_HANDLED_SETTINGS = "usb_handled_settings"
        private const val KEY_RAMADAN = "ramadan_overrides"
        private const val KEY_ADHKAR = "adhkar_content"
        private const val KEY_ANNOUNCEMENTS = "text_announcements"
        private const val KEY_USB_HANDLED_MEDIA = "usb_handled_media"
        private const val KEY_ANNOUNCEMENTS_EVERY = "announcements_every_minutes"
        private const val KEY_WEATHER = "weather_enabled"
        private const val KEY_NIGHT_SCREEN = "night_screen_enabled"
        private const val KEY_ADHAN_SCREEN_MINUTES = "adhan_screen_minutes"
    }
}
