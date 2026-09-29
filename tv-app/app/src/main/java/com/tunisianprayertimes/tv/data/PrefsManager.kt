package com.tunisianprayertimes.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.AdhkarContent
import com.tunisianprayertimes.mosque.DisplayOptions
import com.tunisianprayertimes.mosque.MosqueProfile
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.TextAnnouncement
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.PrayerOverride
import com.tunisianprayertimes.time.ClockStore

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

    /** Announcements come back every this many minutes between prayers; 0: only after the adhkar. */
    var announcementsEveryMinutes: Int
        get() = prefs.getInt(KEY_ANNOUNCEMENTS_EVERY, 15)
        set(value) = prefs.edit { putInt(KEY_ANNOUNCEMENTS_EVERY, value) }

    /** The weather at the mosque, shown only when the TV is online and the data is recent. */
    var weatherEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEATHER, true)
        set(value) = prefs.edit { putBoolean(KEY_WEATHER, value) }

    /** What the clock guard remembers (last good time, in-app time correction). */
    val clockStore: ClockStore by lazy { PrefsClockStore(prefs) }

    /** Content signature of the last USB settings file the admin applied or dismissed. */
    var usbLastHandledSignature: String
        get() = prefs.getString(KEY_USB_LAST_HANDLED, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_USB_LAST_HANDLED, value) }

    /** The mosque's name, place and theme, as the settings file carries them. */
    val profile: MosqueProfile
        get() = MosqueProfile(
            mosqueName.trim().take(MosqueProfile.MAX_NAME_LENGTH), delegationId.takeIf { it > 0 }, themeId,
            DisplayOptions(
                weatherEnabled, customBackgroundEnabled, announcementsEnabled,
                announcementIntervalSec.coerceIn(DisplayOptions.SLIDE_SECONDS),
                announcementsEveryMinutes.coerceIn(DisplayOptions.EVERY_MINUTES),
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
    }

    /** Back to a new TV (one moved to another mosque): setup runs again. The clock's memory is kept. */
    fun resetAll() = prefs.edit {
        prefs.all.keys.filterNot { it.startsWith(CLOCK_KEY_PREFIX) }.forEach { remove(it) }
    }

    fun getIqamahConfig(prayer: Prayer): IqamahConfig = readIqamah(prayer)

    fun setIqamahConfig(prayer: Prayer, config: IqamahConfig) = writeIqamah(prayer, config)

    /** The rows the admin edits on the TV: the daily prayers, Jumu'a and the two Eids. */
    fun iqamahConfigs(): Map<Prayer, IqamahConfig> = EDITABLE.associateWith(::readIqamah)

    /** The mosque's own texts from the USB file (none: the bundled, reviewed texts), kept in the file's format. */
    var adhkarContent: AdhkarContent
        get() {
            val text = prefs.getString(KEY_ADHKAR, null) ?: return AdhkarContent()
            val parsed = MosqueSettingsFile.parse(text, MosqueSchedule())
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

    /** Where the last USB images came from, so a key left in is not offered again. */
    var usbMediaLastHandled: String
        get() = prefs.getString(KEY_USB_MEDIA, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_USB_MEDIA, value) }

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
     * so switching back loses nothing.
     */
    var schedule: MosqueSchedule
        get() = MosqueSchedule(EDITABLE.associateWith { readIqamah(it).toPrayerSettings() }, ramadanOverrides)
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
        )
    }

    private fun writeIqamah(prayer: Prayer, config: IqamahConfig) = prefs.edit {
        val name = prayer.name
        putString("iqamah_mode_$name", config.mode.name)
        putInt("iqamah_delay_$name", config.delayMinutes)
        putInt("iqamah_fixed_h_$name", config.fixedHour)
        putInt("iqamah_fixed_m_$name", config.fixedMinute)
        putInt("salah_minutes_$name", config.salahMinutes)
    }

    companion object {
        const val PREFS_NAME = "tv_prefs"
        private const val CLOCK_KEY_PREFIX = "clock_"
        val EDITABLE: List<Prayer> = MosqueSchedule.CONFIGURABLE + MosqueSchedule.EID
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
        private const val KEY_USB_LAST_HANDLED = "usb_last_handled_signature"
        private const val KEY_RAMADAN = "ramadan_overrides"
        private const val KEY_ADHKAR = "adhkar_content"
        private const val KEY_ANNOUNCEMENTS = "text_announcements"
        private const val KEY_USB_MEDIA = "usb_media_last_handled"
        private const val KEY_ANNOUNCEMENTS_EVERY = "announcements_every_minutes"
        private const val KEY_WEATHER = "weather_enabled"
    }
}
