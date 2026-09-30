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

/**
 * The admin's settings for one prayer: its iqamah and how long the congregation prays. [held] is
 * false for a Jumu'a or an Eid prayer the mosque does not hold ([MosqueSchedule.HOLDABLE]).
 * [khutbaMinutes] is how long the Jumu'a khutba lasts before its iqamah: the khutba screen covers
 * only that, after a countdown; 0, as by default, covers the whole wait after the adhan.
 */
data class PrayerSettings(val iqamah: IqamahRule, val salahMinutes: Int, val held: Boolean = true, val khutbaMinutes: Int = 0)

/** Ramadan changes to one prayer (for example a longer Isha with tarawih); unset fields keep the usual setting. */
data class PrayerOverride(val iqamah: IqamahRule? = null, val salahMinutes: Int? = null) {
    val isEmpty: Boolean get() = iqamah == null && salahMinutes == null

    fun applyTo(base: PrayerSettings) = base.copy(iqamah = iqamah ?: base.iqamah, salahMinutes = salahMinutes ?: base.salahMinutes)
}

/**
 * The mosque's per-prayer settings, set during onboarding or from a USB key.
 * [salahMinutes][PrayerSettings.salahMinutes] is how long the screen stays black after the iqamah.
 * The two Eid prayers are timed from sunrise. [ramadan] applies on the days of Ramadan. [delays] are
 * the minutes after the adhan the mosque keeps behind a fixed iqamah time (the TV keeps them when a
 * fixed time is set): a fixed time that cannot apply on a day falls back to them.
 */
data class MosqueSchedule(
    val prayers: Map<Prayer, PrayerSettings> = emptyMap(),
    val ramadan: Map<Prayer, PrayerOverride> = emptyMap(),
    val delays: Map<Prayer, Int> = emptyMap(),
) {

    fun settings(prayer: Prayer): PrayerSettings =
        prayers[prayer] ?: DEFAULT.prayers[prayer] ?: PrayerSettings(IqamahRule.AfterAdhan(10), 10)

    /** The settings that apply on a day, with Ramadan's changes during Ramadan. */
    fun settingsOn(prayer: Prayer, isRamadan: Boolean): PrayerSettings {
        val base = settings(prayer)
        return if (isRamadan) ramadan[prayer]?.applyTo(base) ?: base else base
    }

    /** Whether the mosque holds [prayer]: the daily prayers always, Jumu'a and the Eid prayers unless turned off. */
    fun holds(prayer: Prayer): Boolean = prayer !in HOLDABLE || settings(prayer).held

    /**
     * The minutes after the adhan a fixed iqamah falls back to on a day it cannot apply: the mosque's
     * own delay (its usual minutes when a Ramadan fixed time is stale), else the built-in default.
     */
    fun fallbackMinutes(prayer: Prayer): Int =
        delays[prayer] ?: (settings(prayer).iqamah as? IqamahRule.AfterAdhan)?.minutes ?: defaultIqamahMinutes(prayer)

    fun with(prayer: Prayer, settings: PrayerSettings): MosqueSchedule = copy(prayers = prayers + (prayer to settings))

    fun withRamadan(prayer: Prayer, override: PrayerOverride): MosqueSchedule =
        copy(ramadan = if (override.isEmpty) ramadan - prayer else ramadan + (prayer to override))

    companion object {
        /** The daily prayers a mosque configures; Jumu'a replaces Dhuhr on Fridays. */
        val CONFIGURABLE = listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA, Prayer.JOMOAA)

        /** The Eid prayers, timed from sunrise. */
        val EID = listOf(Prayer.AID_FITR, Prayer.AID_ADHA)

        /** The prayers a mosque may not hold: a neighbourhood masjid holds neither Jumu'a nor the Eid prayer. */
        val HOLDABLE = listOf(Prayer.JOMOAA) + EID

        /**
         * At least a minute after the adhan. A delay shorter than the adhan screen (FlowTiming.adhanScreenMinutes)
         * is accepted but waits for its end (PrayerFlow), so the screen never goes black during the adhan.
         */
        val IQAMAH_MINUTES = 1..90

        /** At least a minute: no black screen would put the after-salah adhkar on screen during the prayer. */
        val SALAH_MINUTES = 1..90

        /** The Jumu'a khutba's length; 0 is the whole wait from the adhan to the iqamah. */
        val KHUTBA_MINUTES = 0..60

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

        /** The default minutes after the adhan, used when a stale fixed time cannot apply today and the mosque kept none. */
        fun defaultIqamahMinutes(prayer: Prayer): Int =
            (DEFAULT.settings(prayer).iqamah as? IqamahRule.AfterAdhan)?.minutes ?: 10
    }
}
