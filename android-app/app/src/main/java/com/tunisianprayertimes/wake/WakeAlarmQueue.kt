package com.tunisianprayertimes.wake

import android.os.Bundle

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
     *
     * Returns `true` if the payload was accepted (set as current or queued).
     */
    fun handleIncoming(payload: WakeTriggerPayload): Boolean {
        val cur = current
        if (cur == null || !cur.wakeUpCheckEnabled) {
            current = payload
            return true
        }
        if (cur.eventId == payload.eventId) return false
        if (pending.any { it.eventId == payload.eventId }) return false
        pending.addLast(payload)
        return true
    }

    /**
     * Advance to the next queued payload.
     * Returns `true` if a pending payload was promoted to current.
     */
    fun advance(): Boolean {
        val next = pending.removeFirstOrNull() ?: return false
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
     * Save queue state into a [Bundle] for activity state preservation.
     */
    fun saveToBundle(outState: Bundle) {
        current?.let { outState.putBundle(KEY_CURRENT_PAYLOAD, it.toBundle()) }
        outState.putInt(KEY_PENDING_COUNT, pending.size)
        pending.forEachIndexed { i, p ->
            outState.putBundle("$KEY_PENDING_PREFIX$i", p.toBundle())
        }
    }

    /**
     * Restore queue state from a saved [Bundle].
     */
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
