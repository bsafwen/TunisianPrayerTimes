package com.tunisianprayertimes

import android.content.Context
import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import java.util.Calendar

/** Rebuildable Eid alarm state; the source day is independent of a delayed alarm's delivery day. */
internal object EidSilenceWindows {
    val prayers = listOf(Prayer.AID_FITR, Prayer.AID_ADHA)

    data class Window(
        val prayer: Prayer,
        val eventDate: LocalDate,
        val prayerStart: Long,
        val start: Long,
        val end: Long,
    ) {
        fun contains(now: Long): Boolean = now >= start && now < end
    }

    fun nearby(context: Context, now: Calendar): List<Window> {
        val today = localDate(now)
        val year = HijrahDate.from(today).get(ChronoField.YEAR)
        return prayers.mapNotNull { prayer ->
            val config = PrefsManager.getConfig(context, prayer)
            val relativeEnd = config.endOffsetMinutes?.let { maxOf(0L, it.toLong()) } ?: 0L
            val lookBackDays = (maxOf(0L, config.delayMinutes.toLong()) + maxOf(0L, config.afterMinutes.toLong()) + relativeEnd) / 1440 + 2
            (year - 1..year + 1).mapNotNull event@{ eventYear ->
                val date = runCatching {
                    if (prayer == Prayer.AID_FITR) RamadanOverrideChecker.getEidFitrDate(eventYear)
                    else RamadanOverrideChecker.getEidAdhaDate(eventYear)
                }.getOrNull() ?: return@event null
                // Tomorrow's Eid has its own request codes, so it can be prepared
                // without overwriting any of today's normal prayer alarms.
                if (date.isAfter(today.plusDays(1)) || date.isBefore(today.minusDays(lookBackDays))) return@event null
                forDate(context, prayer, date, now)
                    ?.takeIf { it.end > now.timeInMillis }
            }.maxByOrNull { it.eventDate }
        }
    }

    fun forDate(context: Context, prayer: Prayer, date: LocalDate, now: Calendar): Window? {
        if (prayer !in prayers) return null
        val isEid = if (prayer == Prayer.AID_FITR) RamadanOverrideChecker.isEidFitr(date)
            else RamadanOverrideChecker.isEidAdha(date)
        if (!isEid) return null
        val delegation = PrefsManager.getDelegationId(context)
        val times = PrayerTimesRepository.loadDayPrayerTimes(context, delegation, date.year, date.monthValue, date.dayOfMonth)
            // Keep an Eid prepared using the existing tomorrow-prayer fallback
            // valid after midnight as well, including its carried end alarm.
            ?: PrayerTimesRepository.loadDayPrayerTimes(context, delegation, date.year - 1, date.monthValue, date.dayOfMonth)
            ?: return null
        val hour = if (prayer == Prayer.AID_FITR) PrefsManager.getAidFitrTimeHour(context)
            else PrefsManager.getAidAdhaTimeHour(context)
        val minute = if (prayer == Prayer.AID_FITR) PrefsManager.getAidFitrTimeMinute(context)
            else PrefsManager.getAidAdhaTimeMinute(context)
        return window(context, date, now, PrayerTime(prayer,
            hour.takeIf { it >= 0 } ?: times.shurukHour,
            minute.takeIf { it >= 0 } ?: times.shurukMinute,
        ))
    }

    /** An official-date refresh must not end an unrelated window started yesterday. */
    fun hasNormalCarryOver(context: Context, now: Calendar): Boolean {
        val yesterday = localDate(now).minusDays(1)
        val times = PrayerTimesRepository.loadDayPrayerTimes(context, PrefsManager.getDelegationId(context),
            yesterday.year, yesterday.monthValue, yesterday.dayOfMonth) ?: return false
        return times.scheduledPrayers(
            yesterday.dayOfWeek == java.time.DayOfWeek.FRIDAY,
            PrefsManager.getJomoaaTimeHour(context),
            PrefsManager.getJomoaaTimeMinute(context),
        ).any { window(context, yesterday, now, it).contains(now.timeInMillis) }
    }

    private fun window(context: Context, date: LocalDate, now: Calendar, time: PrayerTime): Window {
        val config = PrefsManager.getConfig(context, time.prayer)
        fun at(hour: Int, minute: Int) = (now.clone() as Calendar).apply {
            set(date.year, date.monthValue - 1, date.dayOfMonth, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val prayerStart = at(time.hour, time.minute)
        val start = if (config.delayMode == DelayMode.FIXED_TIME && config.delayFixedHour >= 0 && config.delayFixedMinute >= 0) {
            at(config.delayFixedHour, config.delayFixedMinute)
        } else (prayerStart.clone() as Calendar).apply { add(Calendar.MINUTE, config.delayMinutes) }
        val configEndOffset = config.endOffsetMinutes
        val end = when {
            config.mode == SilenceMode.FIXED_TIME && config.fixedHour >= 0 && config.fixedMinute >= 0 ->
                at(config.fixedHour, config.fixedMinute).apply { if (before(start)) add(Calendar.DAY_OF_YEAR, 1) }
            configEndOffset != null ->
                (prayerStart.clone() as Calendar).apply { add(Calendar.MINUTE, configEndOffset) }
            else ->
                (start.clone() as Calendar).apply { add(Calendar.MINUTE, config.afterMinutes) }
        }
        return Window(time.prayer, date, prayerStart.timeInMillis, start.timeInMillis, end.timeInMillis)
    }

    fun localDate(date: Calendar): LocalDate = LocalDate.of(
        date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH),
    )
}
