package com.tunisianprayertimes.tv.ui.display

import com.tunisianprayertimes.mosque.FlowPhase
import com.tunisianprayertimes.mosque.FlowState
import com.tunisianprayertimes.mosque.PrayerEvent
import com.tunisianprayertimes.mosque.PrayerFlow
import java.time.LocalDateTime

/**
 * The prayer on the wall only moves forward. The flow is a function of the clock and the settings,
 * so a change during a prayer (an iqamah raised on the remote, the phone or a key, a Ramadan date, a
 * new delegation) or a clock put back by a few minutes would otherwise replay its countdown and its
 * black screen. Once its adhan has passed, the running prayer is kept as it started ([Pin]) until its
 * adhkar end; while the iqamah is still ahead (adhan, its dua, countdown or khutba), a new iqamah that is still
 * ahead applies, so the admin can extend or shorten the wait. A prayer the settings no longer have (an
 * Eid date moved away, Jumu'a or the Eid prayer no longer held) ends at once.
 */
object RunningPrayer {

    /** The running prayer's timeline as the wall runs it, and the furthest phase it reached. */
    data class Pin(val event: PrayerEvent, val phase: FlowPhase)

    /** The wall's screen at [now], and the pin to keep for the next tick. */
    data class Result(val state: FlowState, val pin: Pin?)

    private val BEFORE_IQAMAH = setOf(FlowPhase.ADHAN, FlowPhase.ADHAN_DUA, FlowPhase.IQAMAH_COUNTDOWN, FlowPhase.KHUTBA)

    /**
     * The screen at [now] from [events] (yesterday's and today's, from the current settings) and the
     * last tick's [pin]. With an impossible clock ([clockPlausible] false) there is no prayer at all:
     * the wall shows the clock page, and the admin's pages must not follow a phantom day's prayers.
     * A clock put back before the pinned adhan is a real correction: the pin goes, and the flow starts
     * from the clock again. So does a pinned prayer that its day's [events] no longer have; while that
     * day's times are not loaded (still loading, or empty) the pin stays.
     */
    fun stateAt(now: LocalDateTime, events: List<PrayerEvent>, pin: Pin?, clockPlausible: Boolean = true): Result {
        if (!clockPlausible) return Result(FlowState.IDLE, null)
        val fresh = PrayerFlow.stateAt(now, events)
        val pinned = pin?.event
        val kept = pinned != null && !now.isBefore(pinned.adhanAt) &&
            (events.any { sameSlot(it, pinned) } || events.none { sameDay(it, pinned) }) &&
            fresh.event.let { it == null || sameSlot(it, pinned) || !it.adhanAt.isAfter(pinned.adhanAt) }
        if (!kept || pin == null) return Result(fresh, fresh.event?.let { Pin(it, fresh.phase) })
        // The prayer is over: the same prayer with new settings does not start again.
        if (!now.isBefore(pin.event.afterSalahEndAt)) return Result(FlowState.IDLE, pin)
        val update = events.firstOrNull { sameSlot(it, pin.event) }
        val event = if (update != null && pin.phase in BEFORE_IQAMAH && update.iqamahAt.isAfter(now) && update.adhanAt == pin.event.adhanAt) {
            update
        } else {
            pin.event
        }
        val state = PrayerFlow.stateAt(now, listOf(event))
        // Never back to an earlier screen of the same prayer (a clock put back by a few minutes).
        val shown = if (state.phase < pin.phase) FlowState(pin.phase, event, endOf(pin.phase, event)) else state
        return Result(shown, Pin(event, maxOf(pin.phase, shown.phase)))
    }

    /** The same prayer on the same day, whatever its times became. */
    private fun sameSlot(a: PrayerEvent, b: PrayerEvent): Boolean = a.prayer == b.prayer && sameDay(a, b)

    private fun sameDay(a: PrayerEvent, b: PrayerEvent): Boolean = a.adhanAt.toLocalDate() == b.adhanAt.toLocalDate()

    private fun endOf(phase: FlowPhase, event: PrayerEvent): LocalDateTime? = when (phase) {
        FlowPhase.IDLE -> null
        FlowPhase.ADHAN -> event.adhanScreenEndAt
        FlowPhase.ADHAN_DUA -> event.adhanDuaEndAt
        FlowPhase.IQAMAH_COUNTDOWN -> event.khutbaAt ?: event.iqamahAt
        FlowPhase.KHUTBA -> event.iqamahAt
        FlowPhase.SALAH -> event.salahEndAt
        FlowPhase.AFTER_SALAH -> event.afterSalahEndAt
    }
}
