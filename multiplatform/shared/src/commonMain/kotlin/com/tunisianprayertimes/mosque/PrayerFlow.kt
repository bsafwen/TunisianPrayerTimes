package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.IslamicDay
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** How long the screens around a prayer last, independent of the mosque's per-prayer settings. */
data class FlowTiming(val adhanScreenMinutes: Int = 3, val afterSalahMinutes: Int = 10)

/**
 * What the mosque screen shows. [SALAH] is the full black screen while the congregation prays;
 * [KHUTBA] is the quiet screen of the Friday sermon, between the Jumu'a adhan and its iqamah.
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
        val noon = if (date.dayOfWeek == DayOfWeek.FRIDAY) Prayer.JOMOAA else Prayer.DHUHR
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
            val settings = schedule.settingsOn(prayer, if (prayer == Prayer.ISHA) nightIsRamadan else isRamadan)
            // The iqamah must leave the prayer before the next adhan, and Fajr's before sunrise.
            val latest = listOfNotNull(
                adhans.getOrNull(index + 1)?.minusMinutes(1),
                sunrise.minusMinutes(salahMinutes(settings)).takeIf { prayer == Prayer.FAJR },
            ).minOrNull()
            event(date, prayer, adhans[index], adhanScreen = true, latest, settings, timing)
        }
        val eid = when {
            day?.isEidFitr == true -> Prayer.AID_FITR
            day?.isEidAdha == true -> Prayer.AID_ADHA
            else -> null
        } ?: return daily
        // The Eid prayer has no adhan: it is timed from sunrise and prayed before Dhuhr. The adhkar
        // after the obligatory prayers do not follow it: its time already includes the khutba.
        val eidEvent = event(date, eid, sunrise, adhanScreen = false, adhans[1].minusMinutes(1), schedule.settings(eid),
            timing.copy(afterSalahMinutes = 0))
        return (daily + eidEvent).sortedBy { it.adhanAt }
    }

    /**
     * The screen at [now]. Pass yesterday's and today's [events] so an Isha flow that runs past
     * midnight continues. The latest adhan at or before [now] decides; empty phases are skipped.
     */
    fun stateAt(now: LocalDateTime, events: List<PrayerEvent>): FlowState {
        val event = events.filter { !it.adhanAt.isAfter(now) }.maxByOrNull { it.adhanAt } ?: return FlowState.IDLE
        return when {
            now.isBefore(event.adhanScreenEndAt) -> FlowState(FlowPhase.ADHAN, event, event.adhanScreenEndAt)
            now.isBefore(event.iqamahAt) -> {
                val waiting = if (event.prayer == Prayer.JOMOAA) FlowPhase.KHUTBA else FlowPhase.IQAMAH_COUNTDOWN
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
        timing: FlowTiming,
    ): PrayerEvent {
        val (iqamah, adjusted) = resolveIqamah(date, anchor, latest, settings.iqamah, MosqueSchedule.defaultIqamahMinutes(prayer))
        val salahEnd = iqamah.plusMinutes(salahMinutes(settings))
        val adhanScreenEnd = if (adhanScreen) minOf(anchor.plusMinutes(timing.adhanScreenMinutes.coerceAtLeast(0).toLong()), iqamah) else anchor
        return PrayerEvent(
            prayer = prayer,
            adhanAt = anchor,
            adhanScreenEndAt = adhanScreenEnd,
            iqamahAt = iqamah,
            salahEndAt = salahEnd,
            afterSalahEndAt = salahEnd.plusMinutes(timing.afterSalahMinutes.coerceAtLeast(0).toLong()),
            iqamahAdjusted = adjusted,
        )
    }

    /**
     * The iqamah on [date]. A fixed time that makes no sense today (before the adhan, like a winter
     * time after a summer adhan, or more than 90 minutes after it, like "8:00" meant as 20:00) falls
     * back to the prayer's default minutes after the adhan, keeping the adhan and countdown screens.
     * The result is then kept at or before [latest], but never earlier than a minute after the adhan.
     * The flag tells the admin the setting was moved.
     */
    private fun resolveIqamah(
        date: LocalDate,
        adhan: LocalDateTime,
        latest: LocalDateTime?,
        rule: IqamahRule,
        fallbackMinutes: Int,
    ): Pair<LocalDateTime, Boolean> {
        val window = MosqueSchedule.IQAMAH_MINUTES
        val (requested, fellBack) = when (rule) {
            is IqamahRule.AfterAdhan -> adhan.plusMinutes(rule.minutes.coerceIn(window).toLong()) to false
            is IqamahRule.FixedTime -> {
                val fixed = date.atTime(rule.time.withSecond(0).withNano(0))
                if (fixed.isBefore(adhan.plusMinutes(window.first.toLong())) || fixed.isAfter(adhan.plusMinutes(window.last.toLong()))) {
                    adhan.plusMinutes(fallbackMinutes.toLong()) to true
                } else {
                    fixed to false
                }
            }
        }
        val bound = latest?.let { maxOf(it, adhan.plusMinutes(window.first.toLong())) }
        return if (bound != null && requested.isAfter(bound)) bound to true else requested to fellBack
    }
}
