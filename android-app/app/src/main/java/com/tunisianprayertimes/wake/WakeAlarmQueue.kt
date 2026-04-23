package com.tunisianprayertimes.wake

import android.os.Bundle

enum class IncomingResult {
    /** Payload became the new current (queue was empty / current had no challenge). */
    BECAME_CURRENT,

    /** Payload was added to the pending queue behind an active challenge. */
    QUEUED,

    /** Payload was rejected because its eventId duplicates current or a queued payload. */
    REJECTED_DUPLICATE,
}

class WakeAlarmQueue {
    var current: WakeTriggerPayload? = null
        private set

    private val pending = ArrayDeque<WakeTriggerPayload>()

    val pendingCount: Int get() = pending.size

    fun pendingEventIds(): List<String> = pending.map { it.eventId }

    /**
     * Handle an incoming payload.
     *
     * - If there is no current payload or the current has no active challenge,
     *   the incoming payload replaces the current one immediately.
     * - If a challenge IS active, the incoming payload is queued — unless its
     *   eventId already matches the current or any queued payload (dedup).
     */
    fun handleIncoming(payload: WakeTriggerPayload): IncomingResult {
        val cur = current
        if (cur == null || !cur.wakeUpCheckEnabled) {
            current = payload
            return IncomingResult.BECAME_CURRENT
        }
        if (cur.eventId == payload.eventId) return IncomingResult.REJECTED_DUPLICATE
        if (pending.any { it.eventId == payload.eventId }) return IncomingResult.REJECTED_DUPLICATE
        pending.addLast(payload)
        return IncomingResult.QUEUED
    }

    /**
     * Advance to the next queued payload. If the queue is empty, [current]
     * is cleared to `null` (the just-handled alarm is fully done).
     * Returns `true` if a pending payload was promoted to current,
     * `false` if the queue was empty (and `current` is now `null`).
     */
    fun advance(): Boolean {
        val next = pending.removeFirstOrNull()
        if (next == null) {
            current = null
            return false
        }
        current = next
        return true
    }

    /**
     * Handle a dismiss broadcast for a specific eventId.
     * If [eventId] is `null` or matches the current payload, we try to advance.
     * Returns `true` if we advanced to a next payload (caller should NOT finish).
     */
    fun handleDismiss(eventId: String?): Boolean {
        if (eventId != null && eventId != current?.eventId) return true
        return advance()
    }

    /**
     * Clear all state. Used when the alarm session fully ends or for testing.
     */
    fun clear() {
        current = null
        pending.clear()
    }

    /**
     * Save queue state into a [Bundle]. Used by tests; the production queue
     * lives in [WakeAlarmQueueHolder] and survives activity destruction
     * naturally as a process-level singleton.
     */
    fun saveToBundle(outState: Bundle) {
        current?.let { outState.putBundle(KEY_CURRENT_PAYLOAD, it.toBundle()) }
        outState.putInt(KEY_PENDING_COUNT, pending.size)
        pending.forEachIndexed { i, p ->
            outState.putBundle("$KEY_PENDING_PREFIX$i", p.toBundle())
        }
    }

    /** Restore queue state from a saved [Bundle]. */
    fun restoreFromBundle(savedState: Bundle) {
        savedState.getBundle(KEY_CURRENT_PAYLOAD)
            ?.toWakeTriggerPayload()
            ?.let { current = it }
        val count = savedState.getInt(KEY_PENDING_COUNT, 0)
        for (i in 0 until count) {
            savedState.getBundle("$KEY_PENDING_PREFIX$i")
                ?.toWakeTriggerPayload()
                ?.let { pending.addLast(it) }
        }
    }

    companion object {
        private const val KEY_CURRENT_PAYLOAD = "current_payload"
        private const val KEY_PENDING_COUNT = "pending_count"
        private const val KEY_PENDING_PREFIX = "pending_"
    }
}

/**
 * Process-wide singleton holder for the alarm queue.
 *
 * Survives activity destruction and service restarts within the same process,
 * which is essential to prevent challenge bypass and duplicate delivery bugs.
 * On full process death (rare — Android only kills a foregrounded app under
 * extreme memory pressure) the queue is reset, which is acceptable.
 */
object WakeAlarmQueueHolder {
    val queue = WakeAlarmQueue()
}

