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
 * [adhanScreenMinutes] is the mosque's choice (DisplayOptions.adhanScreenMinutes), within [ADHAN_SCREEN_MINUTES];
 * the dua after the adhan follows it for [ADHAN_DUA_MINUTES].
 */
data class FlowTiming(val adhanScreenMinutes: Int = DEFAULT_ADHAN_SCREEN_MINUTES, val afterSalahMinutes: Int = 10) {
    companion object {
        /** About as long as the muezzin calls; the iqamah never comes before its end and the dua's ([PrayerFlow]). */
        const val DEFAULT_ADHAN_SCREEN_MINUTES = 2
        val ADHAN_SCREEN_MINUTES = 1..5

        /**
         * The dua after the adhan (MosqueAdhkar.adhanDua) alone on the screen once the adhan screen ends,
         * said once the muezzin is done: the owner's choice of 2026-09-30, not a mosque setting, except on
         * Friday, where the mosque may leave it out before the khutba ([PrayerSettings.adhanDua]).
         */
        const val ADHAN_DUA_MINUTES = 1

        /** The longest the adhkar after the prayer may last; a settings file asking for more is refused. */
        const val MAX_AFTER_SALAH_MINUTES = 30
    }
}

/**
 * What the mosque screen shows, in the order a prayer goes through them. [ADHAN] is the listener's
 * replies while the muezzin calls, [ADHAN_DUA] the dua after the adhan alone, for a minute.
 * [SALAH] is the full black screen while the congregation prays; [KHUTBA] is the quiet screen of the
 * Friday sermon, from [PrayerEvent.khutbaAt] until the Jumu'a iqamah.
 */
enum class FlowPhase { IDLE, ADHAN, ADHAN_DUA, IQAMAH_COUNTDOWN, KHUTBA, SALAH, AFTER_SALAH }

/**
 * Why the flow moved an iqamah from its setting ([PrayerEvent.iqamahMove]), so the admin is told the right
 * reason: an iqamah at the end of the dua has not always waited for it.
 */
enum class IqamahMove {
    /** A fixed time that does not suit today's adhan (or sunrise), replaced by the mosque's own minutes. */
    FELL_BACK,
    /** A setting before the end of the adhan screen and the dua after it, which waits for them. */
    WAITED_FOR_ADHAN,
    /** Kept before the next adhan (Fajr's before sunrise, the Eid prayer's before Dhuhr). */
    CAPPED,
}

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
     * Jumu'a: when the khutba screen begins, at the end of the dua after the adhan screen (of the adhan screen when
     * the mosque turned the dua off) or, with the mosque's khutba length, that long before the iqamah (the wait
     * before it is an ordinary countdown).
     */
    val khutbaAt: LocalDateTime? = null,
    /** True when Ramadan's changes to this prayer replaced its usual settings. */
    val ramadanSettings: Boolean = false,
    /**
     * The end of the dua after the adhan, [FlowTiming.ADHAN_DUA_MINUTES] after [adhanScreenEndAt]:
     * the iqamah never comes before it. An event without it (the Eid prayer, a Jumu'a without the dua) has no dua screen.
     */
    val adhanDuaEndAt: LocalDateTime = adhanScreenEndAt,
    /**
     * Why [iqamahAdjusted], null when the iqamah is as set. A stale fixed time whose fallback also
     * waits for the dua is [IqamahMove.FELL_BACK]; a move that ends at the cap is [IqamahMove.CAPPED].
     */
    val iqamahMove: IqamahMove? = null,
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
            now.isBefore(event.adhanDuaEndAt) -> FlowState(FlowPhase.ADHAN_DUA, event, event.adhanDuaEndAt)
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
        // A Friday whose mosque turned the dua off goes from the adhan screen to the khutba, and its iqamah waits for the adhan only.
        val duaMinutes = if (adhanScreen && settings.showsAdhanDua(prayer)) FlowTiming.ADHAN_DUA_MINUTES else 0
        val (iqamah, move) = resolveIqamah(date, anchor, latest, settings.iqamah, fallbackMinutes, screenMinutes + duaMinutes)
        val salahEnd = iqamah.plusMinutes(salahMinutes(settings))
        // Only a day too short for the adhan screen and the dua (latest) ends them at the iqamah.
        val adhanScreenEnd = minOf(anchor.plusMinutes(screenMinutes.toLong()), iqamah)
        val duaEnd = minOf(adhanScreenEnd.plusMinutes(duaMinutes.toLong()), iqamah)
        // A long wait for Jumu'a (a late fixed time) counts down until the khutba, so early comers see the time.
        // The khutba screen follows the dua (the adhan screen without it), as the wait does on other days.
        val khutba = settings.khutbaMinutes.coerceIn(MosqueSchedule.KHUTBA_MINUTES).toLong()
        val khutbaAt = if (khutba > 0) maxOf(duaEnd, iqamah.minusMinutes(khutba)) else duaEnd
        return PrayerEvent(
            prayer = prayer,
            adhanAt = anchor,
            adhanScreenEndAt = adhanScreenEnd,
            iqamahAt = iqamah,
            salahEndAt = salahEnd,
            afterSalahEndAt = salahEnd.plusMinutes(timing.afterSalahMinutes.coerceAtLeast(0).toLong()),
            iqamahAdjusted = move != null,
            khutbaAt = khutbaAt.takeIf { prayer == Prayer.JOMOAA },
            adhanDuaEndAt = duaEnd,
            iqamahMove = move,
        )
    }

    /**
     * The iqamah on [date]. A fixed time that makes no sense today (before the adhan, like a winter
     * time after a summer adhan, or more than 90 minutes after it, like "8:00" meant as 20:00) falls
     * back to [fallbackMinutes] after the adhan (the mosque's own delay), keeping the adhan and countdown screens.
     * An iqamah before the end of the adhan screen and the dua after it ([screenMinutes] after the adhan:
     * a fixed time close to the adhan, or +1) waits for their end, so the wall never goes black while the
     * muezzin still calls, nor takes the dua away before it is said.
     * The result is then kept at or before [latest], but never earlier than a minute after the adhan.
     * The reason, null when the setting stands, tells the admin the setting was moved and why.
     */
    private fun resolveIqamah(
        date: LocalDate,
        adhan: LocalDateTime,
        latest: LocalDateTime?,
        rule: IqamahRule,
        fallbackMinutes: Int,
        screenMinutes: Int,
    ): Pair<LocalDateTime, IqamahMove?> {
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
        val stale = if (fellBack) IqamahMove.FELL_BACK else null
        val (waited, move) = if (requested.isBefore(screenEnd)) screenEnd to (stale ?: IqamahMove.WAITED_FOR_ADHAN) else requested to stale
        val bound = latest?.let { maxOf(it, adhan.plusMinutes(window.first.toLong())) }
        return if (bound != null && waited.isAfter(bound)) bound to IqamahMove.CAPPED else waited to move
    }
}
