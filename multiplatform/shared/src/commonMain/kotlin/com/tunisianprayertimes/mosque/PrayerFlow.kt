package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.IslamicDay
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * How long the screens around a prayer last, independent of the mosque's per-prayer settings.
 * [adhanScreenMinutes] is the mosque's choice (DisplayOptions.adhanScreenMinutes), within [ADHAN_SCREEN_MINUTES].
 */
data class FlowTiming(val adhanScreenMinutes: Int = DEFAULT_ADHAN_SCREEN_MINUTES, val afterSalahMinutes: Int = 10) {
    companion object {
        /** About as long as the muezzin calls; the iqamah never comes before its end ([PrayerFlow]). */
        const val DEFAULT_ADHAN_SCREEN_MINUTES = 2
        val ADHAN_SCREEN_MINUTES = 1..5


        /** The longest the adhkar after the prayer may last; a settings file asking for more is refused. */
        const val MAX_AFTER_SALAH_MINUTES = 30
    }
}

/**
 * What the mosque screen shows. [SALAH] is the full black screen while the congregation prays;
 * [KHUTBA] is the quiet screen of the Friday sermon, from [PrayerEvent.khutbaAt] until the Jumu'a iqamah.
 */
enum class FlowPhase { IDLE, ADHAN, IQAMAH_COUNTDOWN, KHUTBA, SALAH, AFTER_SALAH }

/** One prayer's timeline on a given day. For the Eid prayers, [adhanAt] is sunrise and there is no adhan screen. */
data class PrayerEvent(
    val prayer: Prayer,
    val adhanAt: LocalDateTime,
    val adhanScreenEndAt: LocalDateTime,
    val iqamahAt: LocalDateTime,
    val salahEndAt: LocalDateTime,
    val afterSalahEndAt: LocalDateTime,
    /** True when the configured iqamah was moved to stay between this adhan and the next one. */
    val iqamahAdjusted: Boolean,
    /**
     * Jumu'a: when the khutba screen begins, at the end of the adhan screen or, with the mosque's
     * khutba length, that long before the iqamah (the wait before it is an ordinary countdown).
     */
    val khutbaAt: LocalDateTime? = null,
    /** True when Ramadan's changes to this prayer replaced its usual settings. */
    val ramadanSettings: Boolean = false,
)

data class FlowState(val phase: FlowPhase, val event: PrayerEvent?, val phaseEndsAt: LocalDateTime?) {
    companion object {
        val IDLE = FlowState(FlowPhase.IDLE, null, null)
    }
}

/**
 * The prayer-time sequence as a pure function of the clock: the phase is recomputed from 'now',
 * so a restart, a settings screen or a power cut in the middle of a prayer resumes correctly.
 */
object PrayerFlow {

    /**
     * The day's prayers in order. On Fridays the Dhuhr slot is Jumu'a with its own settings; on
     * the days of Ramadan the Ramadan settings apply; on an Eid day the Eid prayer follows sunrise.
     * A mosque that does not hold Jumu'a or the Eid prayer ([MosqueSchedule.holds]) keeps Dhuhr on
     * Fridays and has no Eid prayer.
     * [day] and [nextDay] come from the shared Tunisian calendar (IslamicDays); without them the day
     * is ordinary. Isha follows [nextDay]: its night (and tarawih) belongs to the next Hijri day, so
     * the first tarawih is the evening before the first fast, and the eve of Eid has none.
     */
    fun eventsFor(
        date: LocalDate,
        times: DayPrayerTimes,
        schedule: MosqueSchedule,
        day: IslamicDay? = null,
        timing: FlowTiming = FlowTiming(),
        nextDay: IslamicDay? = null,
    ): List<PrayerEvent> {
        val isRamadan = day?.isRamadan == true
        val nightIsRamadan = nextDay?.isRamadan ?: isRamadan
        val noon = if (date.dayOfWeek == DayOfWeek.FRIDAY && schedule.holds(Prayer.JOMOAA)) Prayer.JOMOAA else Prayer.DHUHR
        val slots: List<Pair<Prayer, PrayerTime>> = listOf(
            Prayer.FAJR to times.fajr,
            noon to times.dhuhr,
            Prayer.ASR to times.asr,
            Prayer.MAGHRIB to times.maghrib,
            Prayer.ISHA to times.isha,
        )
        val adhans = slots.map { (_, time) -> date.atTime(time.hour, time.minute) }
        val sunrise = date.atTime(times.shurukHour, times.shurukMinute)
        val daily = slots.mapIndexed { index, (prayer, _) ->
            val ramadanDay = if (prayer == Prayer.ISHA) nightIsRamadan else isRamadan
            val settings = schedule.settingsOn(prayer, ramadanDay)
            // The iqamah must leave the prayer before the next adhan, and Fajr's before sunrise.
            val latest = listOfNotNull(
                adhans.getOrNull(index + 1)?.minusMinutes(1),
                sunrise.minusMinutes(salahMinutes(settings)).takeIf { prayer == Prayer.FAJR },
            ).minOrNull()
            event(date, prayer, adhans[index], adhanScreen = true, latest, settings, schedule.fallbackMinutes(prayer), timing)
                .copy(ramadanSettings = ramadanDay && schedule.ramadan[prayer]?.isEmpty == false)
        }
        val eid = when {
            day?.isEidFitr == true -> Prayer.AID_FITR
            day?.isEidAdha == true -> Prayer.AID_ADHA
            else -> null
        }?.takeIf(schedule::holds) ?: return daily
        // The Eid prayer has no adhan: it is timed from sunrise and prayed before Dhuhr. The adhkar
        // after the obligatory prayers do not follow it: its time already includes the khutba.
        val eidEvent = event(date, eid, sunrise, adhanScreen = false, adhans[1].minusMinutes(1), schedule.settings(eid),
            schedule.fallbackMinutes(eid), timing.copy(afterSalahMinutes = 0))
        return (daily + eidEvent).sortedBy { it.adhanAt }
    }

    /**
     * The screen at [now]. Pass yesterday's and today's [events] so an Isha flow that runs past
     * midnight continues. The latest adhan at or before [now] decides; empty phases are skipped.
     */
    fun stateAt(now: LocalDateTime, events: List<PrayerEvent>): FlowState {
        val event = events.filter { !it.adhanAt.isAfter(now) }.maxByOrNull { it.adhanAt } ?: return FlowState.IDLE
        val khutbaAt = event.khutbaAt
        return when {
            now.isBefore(event.adhanScreenEndAt) -> FlowState(FlowPhase.ADHAN, event, event.adhanScreenEndAt)
            khutbaAt != null && now.isBefore(khutbaAt) -> FlowState(FlowPhase.IQAMAH_COUNTDOWN, event, khutbaAt)
            now.isBefore(event.iqamahAt) -> {
                val waiting = if (khutbaAt != null) FlowPhase.KHUTBA else FlowPhase.IQAMAH_COUNTDOWN
                FlowState(waiting, event, event.iqamahAt)
            }
            now.isBefore(event.salahEndAt) -> FlowState(FlowPhase.SALAH, event, event.salahEndAt)
            now.isBefore(event.afterSalahEndAt) -> FlowState(FlowPhase.AFTER_SALAH, event, event.afterSalahEndAt)
            else -> FlowState.IDLE
        }
    }

    private fun salahMinutes(settings: PrayerSettings): Long = settings.salahMinutes.coerceIn(MosqueSchedule.SALAH_MINUTES).toLong()

    private fun event(
        date: LocalDate,
        prayer: Prayer,
        anchor: LocalDateTime,
        adhanScreen: Boolean,
        latest: LocalDateTime?,
        settings: PrayerSettings,
        fallbackMinutes: Int,
        timing: FlowTiming,
    ): PrayerEvent {
        val screenMinutes = if (adhanScreen) timing.adhanScreenMinutes.coerceIn(FlowTiming.ADHAN_SCREEN_MINUTES) else 0
        val (iqamah, adjusted) = resolveIqamah(date, anchor, latest, settings.iqamah, fallbackMinutes, screenMinutes)
        val salahEnd = iqamah.plusMinutes(salahMinutes(settings))
        // Only a day too short for the adhan screen (latest) ends it at the iqamah.
        val adhanScreenEnd = minOf(anchor.plusMinutes(screenMinutes.toLong()), iqamah)
        // A long wait for Jumu'a (a late fixed time) counts down until the khutba, so early comers see the time.
        val khutba = settings.khutbaMinutes.coerceIn(MosqueSchedule.KHUTBA_MINUTES).toLong()
        val khutbaAt = if (khutba > 0) maxOf(adhanScreenEnd, iqamah.minusMinutes(khutba)) else adhanScreenEnd
        return PrayerEvent(
            prayer = prayer,
            adhanAt = anchor,
            adhanScreenEndAt = adhanScreenEnd,
            iqamahAt = iqamah,
            salahEndAt = salahEnd,
            afterSalahEndAt = salahEnd.plusMinutes(timing.afterSalahMinutes.coerceAtLeast(0).toLong()),
            iqamahAdjusted = adjusted,
            khutbaAt = khutbaAt.takeIf { prayer == Prayer.JOMOAA },
        )
    }

    /**
     * The iqamah on [date]. A fixed time that makes no sense today (before the adhan, like a winter
     * time after a summer adhan, or more than 90 minutes after it, like "8:00" meant as 20:00) falls
     * back to [fallbackMinutes] after the adhan (the mosque's own delay), keeping the adhan and countdown screens.
     * An iqamah before the end of the adhan screen ([screenMinutes] after the adhan: a fixed time close
     * to the adhan, or +1) waits for its end, so the wall never goes black while the muezzin still calls.
     * The result is then kept at or before [latest], but never earlier than a minute after the adhan.
     * The flag tells the admin the setting was moved.
     */
    private fun resolveIqamah(
        date: LocalDate,
        adhan: LocalDateTime,
        latest: LocalDateTime?,
        rule: IqamahRule,
        fallbackMinutes: Int,
        screenMinutes: Int,
    ): Pair<LocalDateTime, Boolean> {
        val window = MosqueSchedule.IQAMAH_MINUTES
        val (requested, fellBack) = when (rule) {
            is IqamahRule.AfterAdhan -> adhan.plusMinutes(rule.minutes.coerceIn(window).toLong()) to false
            is IqamahRule.FixedTime -> {
                val fixed = date.atTime(rule.time.withSecond(0).withNano(0))
                if (fixed.isBefore(adhan.plusMinutes(window.first.toLong())) || fixed.isAfter(adhan.plusMinutes(window.last.toLong()))) {
                    adhan.plusMinutes(fallbackMinutes.coerceIn(window).toLong()) to true
                } else {
                    fixed to false
                }
            }
        }
        val screenEnd = adhan.plusMinutes(screenMinutes.toLong())
        val (waited, moved) = if (requested.isBefore(screenEnd)) screenEnd to true else requested to fellBack
        val bound = latest?.let { maxOf(it, adhan.plusMinutes(window.first.toLong())) }
        return if (bound != null && waited.isAfter(bound)) bound to true else waited to moved
    }
}
