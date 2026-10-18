package com.tunisianprayertimes.wake

import android.os.SystemClock

/** Keeps a delivered ring visible while its receiver hands it to the playback service. */
internal object WakeAlarmDeliveryTracker {
    private const val HANDOFF_TIMEOUT_MILLIS = 30_000L
    private data class Delivery(val payload: WakeTriggerPayload, val startedAtMillis: Long)
    private val deliveries = mutableMapOf<Pair<String, Long>, Delivery>()

    @Synchronized
    fun begin(payload: WakeTriggerPayload, nowMillis: Long = SystemClock.elapsedRealtime()) {
        deliveries[payload.eventId to payload.triggerAtMillis] = Delivery(payload, nowMillis)
    }

    @Synchronized
    fun finish(payload: WakeTriggerPayload) {
        deliveries.remove(payload.eventId to payload.triggerAtMillis)
    }

    @Synchronized
    fun pendingForAlarm(alarmId: String, nowMillis: Long = SystemClock.elapsedRealtime()): WakeTriggerPayload? {
        deliveries.entries.removeAll { (_, delivery) ->
            nowMillis - delivery.startedAtMillis >= HANDOFF_TIMEOUT_MILLIS
        }
        return deliveries.values.firstOrNull { delivery ->
            wakeAlarmIdFromEventId(delivery.payload.eventId) == alarmId
        }?.payload
    }
}
