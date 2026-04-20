package com.tunisianprayertimes

import java.util.Calendar

/**
 * Pure computation of silence/unsilence alarm times.
 * No platform dependencies — fully deterministic and testable.
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
        val currentlyInSilenceWindow: Boolean,
        val tomorrowFajrScheduled: Boolean
    )

    data class SilenceWindowOverlap(
        val prayer: Prayer,
        val startAtMillis: Long,
        val endAtMillis: Long,
    )

    fun compute(
        now: Calendar,
        todayTimes: DayPrayerTimes,
        configs: Map<Prayer, PrayerSilenceConfig>,
        tomorrowTimes: DayPrayerTimes? = null,
        isFriday: Boolean = false,
        jomoaaHour: Int = -1,
        jomoaaMinute: Int = -1
    ): ComputeResult {
        val alarms = mutableListOf<ScheduledAlarm>()
        var currentlyInSilenceWindow = false

        for (prayerTime in todayTimes.scheduledPrayers(isFriday, jomoaaHour, jomoaaMinute)) {
            val config = configs[prayerTime.prayer] ?: PrayerSilenceConfig()

            val silenceTime = computeSilenceStart(now, prayerTime, config)
            val unsilenceTime = computeUnsilenceEnd(now, silenceTime, config)

            if (!now.before(silenceTime) && now.before(unsilenceTime)) {
                currentlyInSilenceWindow = true
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

    fun overlapsNextPrayer(
        prayerTime: PrayerTime,
        nextPrayerTime: PrayerTime,
        config: PrayerSilenceConfig
    ): Boolean {
        val now = Calendar.getInstance()
        val silenceStart = computeSilenceStart(now, prayerTime, config)
        val unsilenceEnd = computeUnsilenceEnd(now, silenceStart, config)

        val nextStart = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, nextPrayerTime.hour)
            set(Calendar.MINUTE, nextPrayerTime.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        return unsilenceEnd.after(nextStart)
    }

    fun overlapForTrigger(
        triggerAtMillis: Long,
        prayerDay: Calendar,
        prayerTimes: DayPrayerTimes,
        configs: Map<Prayer, PrayerSilenceConfig>,
        isFriday: Boolean = false,
        jomoaaHour: Int = -1,
        jomoaaMinute: Int = -1,
    ): SilenceWindowOverlap? {
        val day = prayerDay.clone() as Calendar
        return prayerTimes
            .scheduledPrayers(
                isFriday = isFriday,
                jomoaaHour = jomoaaHour,
                jomoaaMinute = jomoaaMinute,
            )
            .firstNotNullOfOrNull { prayerTime ->
                val config = configs[prayerTime.prayer] ?: PrayerSilenceConfig()
                val silenceStart = computeSilenceStart(day, prayerTime, config)
                val unsilenceEnd = computeUnsilenceEnd(day, silenceStart, config)

                if (triggerAtMillis in silenceStart.timeInMillis until unsilenceEnd.timeInMillis) {
                    SilenceWindowOverlap(
                        prayer = prayerTime.prayer,
                        startAtMillis = silenceStart.timeInMillis,
                        endAtMillis = unsilenceEnd.timeInMillis,
                    )
                } else {
                    null
                }
            }
    }
}
