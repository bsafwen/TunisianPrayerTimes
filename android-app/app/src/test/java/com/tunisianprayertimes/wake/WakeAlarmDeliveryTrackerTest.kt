package com.tunisianprayertimes.wake

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.RingtonePreset
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WakeAlarmDeliveryTrackerTest {
    private val payloads = mutableListOf<WakeTriggerPayload>()

    @After
    fun cleanup() {
        payloads.forEach(WakeAlarmDeliveryTracker::finish)
    }

    @Test
    fun deliveryExpiresAtTheHandoffDeadline() {
        val payload = payload("delivery-timeout", triggerAtMillis = 60_000L)
        WakeAlarmDeliveryTracker.begin(payload, nowMillis = 10_000L)

        assertSame(payload, WakeAlarmDeliveryTracker.pendingForAlarm("delivery-timeout", nowMillis = 39_999L))
        assertNull(WakeAlarmDeliveryTracker.pendingForAlarm("delivery-timeout", nowMillis = 40_000L))
    }

    @Test
    fun finishingAnOldTriggerKeepsTheNewerTriggerOfTheSameEvent() {
        val old = payload("delivery-generation", triggerAtMillis = 60_000L)
        val newer = payload("delivery-generation", triggerAtMillis = 120_000L)
        WakeAlarmDeliveryTracker.begin(old, nowMillis = 10_000L)
        WakeAlarmDeliveryTracker.begin(newer, nowMillis = 10_001L)

        WakeAlarmDeliveryTracker.finish(old)

        assertSame(newer, WakeAlarmDeliveryTracker.pendingForAlarm("delivery-generation", nowMillis = 10_002L))
        WakeAlarmDeliveryTracker.finish(newer)
        assertNull(WakeAlarmDeliveryTracker.pendingForAlarm("delivery-generation", nowMillis = 10_003L))
    }

    @Test
    fun finishingOneAlarmKeepsAnotherAlarmsDelivery() {
        val first = payload("delivery-first", triggerAtMillis = 60_000L)
        val second = payload("delivery-second", triggerAtMillis = 60_000L)
        WakeAlarmDeliveryTracker.begin(first, nowMillis = 10_000L)
        WakeAlarmDeliveryTracker.begin(second, nowMillis = 10_000L)
        assertSame(first, WakeAlarmDeliveryTracker.pendingForAlarm("delivery-first", nowMillis = 10_001L))
        assertSame(second, WakeAlarmDeliveryTracker.pendingForAlarm("delivery-second", nowMillis = 10_001L))
        assertNull(WakeAlarmDeliveryTracker.pendingForAlarm("delivery-missing", nowMillis = 10_001L))

        WakeAlarmDeliveryTracker.finish(first)

        assertNull(WakeAlarmDeliveryTracker.pendingForAlarm("delivery-first", nowMillis = 10_002L))
        assertSame(second, WakeAlarmDeliveryTracker.pendingForAlarm("delivery-second", nowMillis = 10_002L))
    }

    private fun payload(alarmId: String, triggerAtMillis: Long) = WakeTriggerPayload(
        eventId = wakeMainEventId(alarmId),
        prayer = Prayer.FAJR,
        effectivePrayer = Prayer.FAJR,
        hour = 5,
        minute = 0,
        ringtone = RingtonePreset.DEFAULT_ALARM,
        vibrationOnly = false,
        wakeUpCheckEnabled = false,
        progressiveVolume = false,
        snoreTrackingEnabled = false,
        awakeCheckEnabled = true,
        isSubAlarm = false,
        triggerAtMillis = triggerAtMillis,
        occurrenceAtMillis = triggerAtMillis,
    ).also(payloads::add)
}
