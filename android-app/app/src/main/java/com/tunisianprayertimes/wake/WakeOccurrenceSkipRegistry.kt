package com.tunisianprayertimes.wake

import java.util.concurrent.ConcurrentHashMap

/** Covers broadcasts and service starts already handed off when an occurrence is skipped. */
internal object WakeOccurrenceSkipRegistry {
    private val skipped = ConcurrentHashMap<Pair<String, Long>, Long>()

    fun mark(alarmId: String, occurrenceAtMillis: Long) {
        skipped[alarmId to occurrenceAtMillis] = System.currentTimeMillis()
    }

    fun clear(alarmId: String, occurrenceAtMillis: Long) {
        skipped.remove(alarmId to occurrenceAtMillis)
    }

    fun clearAlarm(alarmId: String) {
        skipped.keys.removeAll { (skippedAlarmId, _) -> skippedAlarmId == alarmId }
    }

    fun contains(alarmId: String, occurrenceAtMillis: Long): Boolean =
        skipped.containsKey(alarmId to occurrenceAtMillis)

    fun wasRecentlyMarked(alarmId: String, occurrenceAtMillis: Long, nowMillis: Long): Boolean =
        skipped[alarmId to occurrenceAtMillis]
            ?.let { markedAtMillis -> nowMillis - markedAtMillis < 2 * 60_000L }
            ?: false

    fun contains(payload: WakeTriggerPayload): Boolean =
        wakeAlarmIdFromEventId(payload.eventId)
            ?.let { alarmId -> contains(alarmId, payload.resolvedOccurrenceAtMillis()) }
            ?: false
}
