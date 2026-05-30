package com.tunisianprayertimes

import java.util.Calendar

/**
 * Pure computation of prayer-aware wake alarm trigger times.
 *
 * Callers should provide a small window of adjacent prayer dates around `now`
 * (typically previous/current/next/day-after-next) so cross-midnight offsets can
 * resolve to the correct future civil day.
 */
object WakeAlarmComputer {

    data class PrayerDayContext(
        val date: Calendar,
        val prayerTimes: DayPrayerTimes,
        val isFriday: Boolean = false,
        val jomoaaHour: Int = -1,
        val jomoaaMinute: Int = -1,
    )

    data class ScheduledWakeTrigger(
        val alarmId: String,
        val triggerAtMillis: Long,
        val prayer: Prayer,
        val effectivePrayer: Prayer,
        val mainAlarmMode: WakeMainAlarmMode,
        val isSubAlarm: Boolean,
        val subAlarmId: String? = null,
        val signedOffsetMinutes: Int = 0,
        val playback: WakePlaybackOptions,
    )

    data class ComputeResult(
        val mainAlarm: ScheduledWakeTrigger?,
        val subAlarms: List<ScheduledWakeTrigger>,
    ) {
        val allTriggers: List<ScheduledWakeTrigger>
            get() = (listOfNotNull(mainAlarm) + subAlarms).sortedBy { trigger -> trigger.triggerAtMillis }
    }

    fun compute(
        now: Calendar,
        config: PrayerWakeConfig,
        prayerDays: List<PrayerDayContext>,
    ): ComputeResult {
        if (!config.enabled) {
            return ComputeResult(mainAlarm = null, subAlarms = emptyList())
        }

        if (config.mainAlarm.mode == WakeMainAlarmMode.FROM_NOW) {
            val mainOccurrence = oneOffMainOccurrence(config) ?: return ComputeResult(
                mainAlarm = null,
                subAlarms = emptyList(),
            )

            val mainAlarm = nextFutureTrigger(
                now = now,
                candidates = listOf(
                    ScheduledWakeTrigger(
                        alarmId = config.id,
                        triggerAtMillis = mainOccurrence.triggerAtMillis,
                        prayer = config.prayer,
                        effectivePrayer = mainOccurrence.effectivePrayer,
                        mainAlarmMode = config.mainAlarm.mode,
                        isSubAlarm = false,
                        playback = config.playback,
                    ),
                ),
            )

            val subAlarms = config.subAlarms
                .mapNotNull { subAlarm ->
                    nextFutureTrigger(
                        now = now,
                        candidates = listOf(
                            ScheduledWakeTrigger(
                                alarmId = config.id,
                                triggerAtMillis = mainOccurrence.triggerAtMillis + subAlarm.signedOffsetMinutes.toMillis(),
                                prayer = config.prayer,
                                effectivePrayer = mainOccurrence.effectivePrayer,
                                mainAlarmMode = config.mainAlarm.mode,
                                isSubAlarm = true,
                                subAlarmId = subAlarm.id,
                                signedOffsetMinutes = subAlarm.signedOffsetMinutes,
                                playback = subAlarm.playback,
                            ),
                        ),
                    )
                }
                .sortedBy { trigger -> trigger.triggerAtMillis }

            return ComputeResult(mainAlarm = mainAlarm, subAlarms = subAlarms)
        }

        val scheduledDays = config.scheduledDays.normalizedWakeScheduleDays()
        val sortedPrayerDays = prayerDays
            .filter { day -> day.date.wakeScheduleDay() in scheduledDays }
            .sortedBy { day -> day.date.timeInMillis }
        val mainAlarm = nextFutureTrigger(
            now = now,
            candidates = sortedPrayerDays.mapNotNull { day ->
                mainTriggerForDay(day, config)
            },
        )

        val subAlarms = config.subAlarms
            .mapNotNull { subAlarm ->
                nextFutureTrigger(
                    now = now,
                    candidates = sortedPrayerDays.mapNotNull { day ->
                        subTriggerForDay(day, config, subAlarm)
                    },
                )
            }
            .sortedBy { trigger -> trigger.triggerAtMillis }

        return ComputeResult(mainAlarm = mainAlarm, subAlarms = subAlarms)
    }

    private fun mainTriggerForDay(
        prayerDay: PrayerDayContext,
        config: PrayerWakeConfig,
    ): ScheduledWakeTrigger? {
        val occurrence = mainOccurrenceForDay(prayerDay, config) ?: return null
        return ScheduledWakeTrigger(
            alarmId = config.id,
            triggerAtMillis = occurrence.triggerAtMillis,
            prayer = config.prayer,
            effectivePrayer = occurrence.effectivePrayer,
            mainAlarmMode = config.mainAlarm.mode,
            isSubAlarm = false,
            playback = config.playback,
        )
    }

    private fun subTriggerForDay(
        prayerDay: PrayerDayContext,
        config: PrayerWakeConfig,
        subAlarm: PrayerWakeSubAlarm,
    ): ScheduledWakeTrigger? {
        val mainOccurrence = mainOccurrenceForDay(prayerDay, config) ?: return null
        return ScheduledWakeTrigger(
            alarmId = config.id,
            triggerAtMillis = mainOccurrence.triggerAtMillis + subAlarm.signedOffsetMinutes.toMillis(),
            prayer = config.prayer,
            effectivePrayer = mainOccurrence.effectivePrayer,
            mainAlarmMode = config.mainAlarm.mode,
            isSubAlarm = true,
            subAlarmId = subAlarm.id,
            signedOffsetMinutes = subAlarm.signedOffsetMinutes,
            playback = subAlarm.playback,
        )
    }

    private fun mainOccurrenceForDay(
        prayerDay: PrayerDayContext,
        config: PrayerWakeConfig,
    ): MainOccurrence? {
        return when (config.mainAlarm.mode) {
            WakeMainAlarmMode.FIXED_TIME -> {
                val fixedTime = config.mainAlarm.fixedTime
                MainOccurrence(
                    triggerAtMillis = calendarForTime(prayerDay.date, fixedTime.hour, fixedTime.minute).timeInMillis,
                    effectivePrayer = config.prayer,
                )
            }

            WakeMainAlarmMode.PRAYER_RELATIVE -> {
                val effectivePrayer = effectivePrayerFor(config.prayer, prayerDay.isFriday)
                val prayerTime = prayerDay.resolvePrayerTime(effectivePrayer) ?: return null
                MainOccurrence(
                    triggerAtMillis = calendarForTime(prayerDay.date, prayerTime.hour, prayerTime.minute)
                        .apply { add(Calendar.MINUTE, config.mainAlarm.prayerOffset.minutes) }
                        .timeInMillis,
                    effectivePrayer = effectivePrayer,
                )
            }

            WakeMainAlarmMode.FROM_NOW -> null
        }
    }

    private fun oneOffMainOccurrence(config: PrayerWakeConfig): MainOccurrence? {
        val triggerAtMillis = config.mainAlarm.oneOffTriggerAtMillis.takeIf { it > 0L } ?: return null
        return MainOccurrence(
            triggerAtMillis = triggerAtMillis,
            effectivePrayer = config.prayer,
        )
    }

    private fun nextFutureTrigger(
        now: Calendar,
        candidates: List<ScheduledWakeTrigger>,
    ): ScheduledWakeTrigger? =
        candidates
            .filter { trigger -> trigger.triggerAtMillis > now.timeInMillis }
            .minByOrNull { trigger -> trigger.triggerAtMillis }

    private fun PrayerDayContext.resolvePrayerTime(prayer: Prayer): PrayerTime? =
        prayerTimes
            .scheduledPrayers(
                isFriday = isFriday,
                jomoaaHour = jomoaaHour,
                jomoaaMinute = jomoaaMinute,
            )
            .firstOrNull { prayerTime -> prayerTime.prayer == prayer }

    private fun effectivePrayerFor(prayer: Prayer, isFriday: Boolean): Prayer =
        if (prayer == Prayer.DHUHR && isFriday) Prayer.JOMOAA else prayer

    private fun calendarForTime(date: Calendar, hour: Int, minute: Int): Calendar =
        (date.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun Int.toMillis(): Long = this * 60_000L

    private data class MainOccurrence(
        val triggerAtMillis: Long,
        val effectivePrayer: Prayer,
    )
}