package com.tunisianprayertimes.ui

import com.tunisianprayertimes.WakeAlarmComputer
import com.tunisianprayertimes.wake.WakeAlarmScheduler

/** What the Alarms hero leads with for one alarm. */
internal enum class WakeAlarmFocus {
    /** A ring still to come: late (its time passed, not delivered yet) or ahead. */
    RING,

    /** The awake check that follows the last ring, scheduled or running. */
    AWAKE_CHECK,

    /** A delivered ring going now, or waiting in the queue behind another alarm's ring. */
    RINGING,
}

private const val MINUTE_MILLIS = 60_000L

/**
 * What one alarm is doing, shared by the Alarms hero and the alarm's row so the two always agree. While an
 * occurrence is in progress (a ring going or late, rings to come, or the awake check after them) both show that
 * occurrence; only when it ends do they move on to the next one.
 */
internal data class WakeAlarmNow(
    /** The occurrence on show, as its main alarm's time. */
    val occurrenceAtMillis: Long,
    /** Its ring still to come: one Android has not delivered although its time passed, else the next one ahead. */
    val nextRing: WakeAlarmScheduler.Ring?,
    /** The occurrence is in progress, so Stop applies to it. */
    val inProgress: Boolean,
    /** The ring going now, or one waiting in the queue behind another alarm's ring ([ringWaiting]). */
    val ringing: WakeAlarmScheduler.Ring? = null,
    val ringWaiting: Boolean = false,
    /** When its awake check starts, or started if it is running. */
    val awakeCheckAtMillis: Long? = null,
    /** When the awake check was set, i.e. when the last ring was stopped. */
    val awakeCheckFromMillis: Long? = null,
    val awakeCheckRunning: Boolean = false,
    /** The occurrence's rings as signed minutes, as scheduled before it started; null when not recorded. */
    val recordedRingOffsets: List<Int>? = null,
) {
    val focus: WakeAlarmFocus
        get() = when {
            awakeCheckRunning -> WakeAlarmFocus.AWAKE_CHECK
            ringing != null && !ringWaiting -> WakeAlarmFocus.RINGING
            nextRing != null && (awakeCheckAtMillis == null || nextRing.atMillis <= awakeCheckAtMillis) ->
                WakeAlarmFocus.RING
            awakeCheckAtMillis != null -> WakeAlarmFocus.AWAKE_CHECK
            ringing != null -> WakeAlarmFocus.RINGING
            else -> WakeAlarmFocus.RING
        }

    /** The ring the hero and the strips light up: the one going, else the one coming; none for the check. */
    val highlightedRing: WakeAlarmScheduler.Ring?
        get() = when (focus) {
            WakeAlarmFocus.RINGING -> ringing
            WakeAlarmFocus.RING -> nextRing
            WakeAlarmFocus.AWAKE_CHECK -> null
        }

    /**
     * When what the hero leads with happens, to pick the alarm the hero shows. A ring going or a check running come
     * first, then whatever is late or nearest.
     */
    fun focusAtMillis(nowMillis: Long): Long = when (focus) {
        WakeAlarmFocus.AWAKE_CHECK ->
            if (awakeCheckRunning) nowMillis - 1 else maxOf(awakeCheckAtMillis ?: nowMillis, nowMillis)
        WakeAlarmFocus.RINGING -> if (ringWaiting) nowMillis else nowMillis - 1
        WakeAlarmFocus.RING -> maxOf(nextRing?.atMillis ?: nowMillis, nowMillis)
    }

    /**
     * Signed minutes of every ring of the occurrence: for one in progress, the rings it was scheduled with up to now
     * and the alarm's current rings after now, so an edit made meanwhile neither erases nor invents a ring.
     * [alarmOffsets] are the alarm's rings now, 0 for the main alarm included.
     */
    fun occurrenceRingOffsets(alarmOffsets: List<Int>, nowMillis: Long): List<Int> {
        val recorded = recordedRingOffsets?.takeIf { inProgress } ?: return alarmOffsets
        fun atMillis(offset: Int): Long = occurrenceAtMillis + offset * MINUTE_MILLIS
        return (recorded.filter { offset -> atMillis(offset) <= nowMillis } +
            alarmOffsets.filter { offset -> atMillis(offset) > nowMillis })
            .distinct()
            .sorted()
    }
}

/**
 * [nextTrigger] is the alarm's next ring from the clock alone, which leaves an occurrence as soon as its last ring
 * time passes; [running] is the occurrence still in progress, if any, and wins over it.
 */
internal fun wakeAlarmNow(
    nextTrigger: WakeAlarmComputer.ScheduledWakeTrigger?,
    running: WakeAlarmScheduler.RunningOccurrence?,
): WakeAlarmNow? {
    val nextRing = nextTrigger?.let { trigger -> WakeAlarmScheduler.Ring(trigger.triggerAtMillis, trigger.isSubAlarm) }
    if (running == null) {
        return nextTrigger?.let { trigger ->
            WakeAlarmNow(occurrenceAtMillis = trigger.occurrenceAtMillis, nextRing = nextRing, inProgress = false)
        }
    }
    return WakeAlarmNow(
        occurrenceAtMillis = running.occurrenceAtMillis,
        nextRing = running.dueRing
            ?: nextRing.takeIf { nextTrigger?.occurrenceAtMillis == running.occurrenceAtMillis },
        inProgress = true,
        ringing = running.ringing,
        ringWaiting = running.ringWaiting,
        awakeCheckAtMillis = running.awakeCheckAtMillis,
        awakeCheckFromMillis = running.awakeCheckFromMillis,
        awakeCheckRunning = running.awakeCheckRunning,
        recordedRingOffsets = running.ringOffsets,
    )
}

/** The alarm's rings as signed minutes from its main alarm, 0 included, earliest first. */
internal fun wakeAlarmRingOffsets(subAlarmOffsets: List<Int>): List<Int> =
    (listOf(0) + subAlarmOffsets).distinct().sorted()
