package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.Prayer
import java.time.LocalTime

/** When the iqamah is called for a prayer. */
sealed interface IqamahRule {
    /** A number of minutes after that day's adhan (after sunrise for the Eid prayers, which have no adhan). */
    data class AfterAdhan(val minutes: Int) : IqamahRule

    /** The same clock time every day (for example a winter Isha at 19:00). */
    data class FixedTime(val time: LocalTime) : IqamahRule
}

/** The admin's settings for one prayer: its iqamah and how long the congregation prays. */
data class PrayerSettings(val iqamah: IqamahRule, val salahMinutes: Int)

/** Ramadan changes to one prayer (for example a longer Isha with tarawih); unset fields keep the usual setting. */
data class PrayerOverride(val iqamah: IqamahRule? = null, val salahMinutes: Int? = null) {
    val isEmpty: Boolean get() = iqamah == null && salahMinutes == null

    fun applyTo(base: PrayerSettings) = PrayerSettings(iqamah ?: base.iqamah, salahMinutes ?: base.salahMinutes)
}

/**
 * The mosque's per-prayer settings, set during onboarding or from a USB key.
 * [salahMinutes][PrayerSettings.salahMinutes] is how long the screen stays black after the iqamah.
 * The two Eid prayers are timed from sunrise. [ramadan] applies on the days of Ramadan.
 */
data class MosqueSchedule(
    val prayers: Map<Prayer, PrayerSettings> = emptyMap(),
    val ramadan: Map<Prayer, PrayerOverride> = emptyMap(),
) {

    fun settings(prayer: Prayer): PrayerSettings =
        prayers[prayer] ?: DEFAULT.prayers[prayer] ?: PrayerSettings(IqamahRule.AfterAdhan(10), 10)

    /** The settings that apply on a day, with Ramadan's changes during Ramadan. */
    fun settingsOn(prayer: Prayer, isRamadan: Boolean): PrayerSettings {
        val base = settings(prayer)
        return if (isRamadan) ramadan[prayer]?.applyTo(base) ?: base else base
    }

    fun with(prayer: Prayer, settings: PrayerSettings): MosqueSchedule = copy(prayers = prayers + (prayer to settings))

    fun withRamadan(prayer: Prayer, override: PrayerOverride): MosqueSchedule =
        copy(ramadan = if (override.isEmpty) ramadan - prayer else ramadan + (prayer to override))

    companion object {
        /** The daily prayers a mosque configures; Jumu'a replaces Dhuhr on Fridays. */
        val CONFIGURABLE = listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA, Prayer.JOMOAA)

        /** The Eid prayers, timed from sunrise. */
        val EID = listOf(Prayer.AID_FITR, Prayer.AID_ADHA)

        /** At least a minute: an iqamah at the adhan instant would black the screen during the adhan. */
        val IQAMAH_MINUTES = 1..90

        /** At least a minute: no black screen would put the after-salah adhkar on screen during the prayer. */
        val SALAH_MINUTES = 1..90

        val DEFAULT = MosqueSchedule(
            mapOf(
                Prayer.FAJR to PrayerSettings(IqamahRule.AfterAdhan(15), 10),
                Prayer.DHUHR to PrayerSettings(IqamahRule.AfterAdhan(10), 10),
                Prayer.ASR to PrayerSettings(IqamahRule.AfterAdhan(10), 10),
                Prayer.MAGHRIB to PrayerSettings(IqamahRule.AfterAdhan(5), 8),
                Prayer.ISHA to PrayerSettings(IqamahRule.AfterAdhan(10), 10),
                Prayer.JOMOAA to PrayerSettings(IqamahRule.AfterAdhan(15), 15),
                // Half an hour after sunrise; the prayer and its khutba together.
                Prayer.AID_FITR to PrayerSettings(IqamahRule.AfterAdhan(30), 30),
                Prayer.AID_ADHA to PrayerSettings(IqamahRule.AfterAdhan(30), 30),
            )
        )

        /** The default minutes after the adhan, used when a stale fixed time cannot apply today. */
        fun defaultIqamahMinutes(prayer: Prayer): Int =
            (DEFAULT.settings(prayer).iqamah as? IqamahRule.AfterAdhan)?.minutes ?: 10
    }
}
