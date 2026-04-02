package com.tunisianprayertimes

import java.util.Calendar

/**
 * Pure computation of silence/unsilence alarm times.
 *
 * This class has NO Android dependencies (no Context, AlarmManager, etc.).
 * All time calculations derive from the supplied [now] calendar — never from
 * `Calendar.getInstance()`.  This makes it fully deterministic and testable
 * for any simulated time of day.
 */
object SilenceAlarmComputer {

    data class ScheduledAlarm(
        val triggerAtMillis: Long,
        val action: AlarmAction,
        val prayer: Prayer
    )

    enum class AlarmAction { SILENCE, UNSILENCE, MIDNIGHT_RESCHEDULE }

    data class ComputeResult(
        val alarms: List<ScheduledAlarm>,
        /** True if [now] falls inside at least one prayer's silence window. */
        val currentlyInSilenceWindow: Boolean,
        /** True if tomorrow's Fajr alarms were included. */
        val tomorrowFajrScheduled: Boolean
    )

    /**
     * Computes all alarm times for today (and optionally tomorrow's Fajr).
     *
     * @param now             the current time
     * @param todayTimes      today's prayer times
     * @param configs         per-prayer silence configuration
     * @param tomorrowTimes   tomorrow's prayer times (null = skip tomorrow pre-scheduling)
     */
    fun compute(
        now: Calendar,
        todayTimes: DayPrayerTimes,
        configs: Map<Prayer, PrayerSilenceConfig>,
        tomorrowTimes: DayPrayerTimes? = null
    ): ComputeResult {
        val alarms = mutableListOf<ScheduledAlarm>()
        var currentlyInSilenceWindow = false

        // --- Today's prayer alarms ---
        for (prayerTime in todayTimes.allPrayers()) {
            val config = configs[prayerTime.prayer] ?: PrayerSilenceConfig()

            val silenceTime = computeSilenceStart(now, prayerTime, config)
            val unsilenceTime = computeUnsilenceEnd(now, silenceTime, config)

            // Inside silence window?
            if (!now.before(silenceTime) && now.before(unsilenceTime)) {
                currentlyInSilenceWindow = true
                // Only schedule unsilence (silence already active)
                alarms += ScheduledAlarm(unsilenceTime.timeInMillis, AlarmAction.UNSILENCE, prayerTime.prayer)
                continue
            }

            if (silenceTime.after(now)) {
                alarms += ScheduledAlarm(silenceTime.timeInMillis, AlarmAction.SILENCE, prayerTime.prayer)
            }
            if (unsilenceTime.after(now)) {
                alarms += ScheduledAlarm(unsilenceTime.timeInMillis, AlarmAction.UNSILENCE, prayerTime.prayer)
            }
        }

        // --- Tomorrow's Fajr (only after today's Isha unsilence) ---
        var tomorrowFajrScheduled = false
        if (tomorrowTimes != null) {
            val ishaConfig = configs[Prayer.ISHA] ?: PrayerSilenceConfig()
            val ishaSilence = computeSilenceStart(now, todayTimes.isha, ishaConfig)
            val ishaUnsilence = computeUnsilenceEnd(now, ishaSilence, ishaConfig)

            if (!now.before(ishaUnsilence) && !currentlyInSilenceWindow) {
                val fajrConfig = configs[Prayer.FAJR] ?: PrayerSilenceConfig()
                val tomorrow = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
                val fajrSilence = computeSilenceStartForDay(tomorrow, tomorrowTimes.fajr, fajrConfig)
                val fajrUnsilence = computeUnsilenceEndForDay(tomorrow, fajrSilence, fajrConfig)

                if (fajrSilence.after(now)) {
                    alarms += ScheduledAlarm(fajrSilence.timeInMillis, AlarmAction.SILENCE, Prayer.FAJR)
                }
                if (fajrUnsilence.after(now)) {
                    alarms += ScheduledAlarm(fajrUnsilence.timeInMillis, AlarmAction.UNSILENCE, Prayer.FAJR)
                }
                tomorrowFajrScheduled = true
            }
        }

        // --- Midnight reschedule ---
        val midnight = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        alarms += ScheduledAlarm(midnight.timeInMillis, AlarmAction.MIDNIGHT_RESCHEDULE, Prayer.FAJR)

        return ComputeResult(alarms, currentlyInSilenceWindow, tomorrowFajrScheduled)
    }

    // -- Helpers that derive all times from the given base calendar, never from Calendar.getInstance() --

    /** Compute silence start time for today (using [now]'s date). */
    internal fun computeSilenceStart(now: Calendar, prayerTime: PrayerTime, config: PrayerSilenceConfig): Calendar {
        return if (config.delayMode == DelayMode.FIXED_TIME && config.delayFixedHour >= 0 && config.delayFixedMinute >= 0) {
            (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, config.delayFixedHour)
                set(Calendar.MINUTE, config.delayFixedMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        } else {
            (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, prayerTime.hour)
                set(Calendar.MINUTE, prayerTime.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, config.delayMinutes)
            }
        }
    }

    /** Compute unsilence end time for today (using [now]'s date). */
    internal fun computeUnsilenceEnd(now: Calendar, silenceStart: Calendar, config: PrayerSilenceConfig): Calendar {
        return if (config.mode == SilenceMode.FIXED_TIME && config.fixedHour >= 0 && config.fixedMinute >= 0) {
            (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, config.fixedHour)
                set(Calendar.MINUTE, config.fixedMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(silenceStart)) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        } else {
            (silenceStart.clone() as Calendar).apply {
                add(Calendar.MINUTE, config.afterMinutes)
            }
        }
    }

    /** Compute silence start for a specific day (e.g. tomorrow). */
    private fun computeSilenceStartForDay(day: Calendar, prayerTime: PrayerTime, config: PrayerSilenceConfig): Calendar {
        return if (config.delayMode == DelayMode.FIXED_TIME && config.delayFixedHour >= 0 && config.delayFixedMinute >= 0) {
            (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, config.delayFixedHour)
                set(Calendar.MINUTE, config.delayFixedMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        } else {
            (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, prayerTime.hour)
                set(Calendar.MINUTE, prayerTime.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, config.delayMinutes)
            }
        }
    }

    /** Compute unsilence end for a specific day (e.g. tomorrow). */
    private fun computeUnsilenceEndForDay(day: Calendar, silenceStart: Calendar, config: PrayerSilenceConfig): Calendar {
        return if (config.mode == SilenceMode.FIXED_TIME && config.fixedHour >= 0 && config.fixedMinute >= 0) {
            (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, config.fixedHour)
                set(Calendar.MINUTE, config.fixedMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(silenceStart)) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        } else {
            (silenceStart.clone() as Calendar).apply {
                add(Calendar.MINUTE, config.afterMinutes)
            }
        }
    }
}
