package com.tunisianprayertimes

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/**
 * Plan 9.1 (the API 26 and 34 CI emulators): the device's own zone rules, in which times abroad are shown, give
 * 2026's summer-time changes at 01:00 UTC on 03-29 and 10-25 for Paris, London and Helsinki.
 */
@RunWith(AndroidJUnit4::class)
class ZoneRulesInstrumentedTest {
    @Test
    fun theDevicesRulesGive2026sChanges() {
        for (zone in listOf("Europe/Paris", "Europe/London", "Europe/Helsinki")) {
            val rules = ZoneId.of(zone).rules
            val spring = rules.nextTransition(Instant.parse("2026-01-01T00:00:00Z"))
            val autumn = rules.nextTransition(spring.instant)
            assertEquals("$zone, spring", Instant.parse("2026-03-29T01:00:00Z"), spring.instant)
            assertEquals("$zone, autumn", Instant.parse("2026-10-25T01:00:00Z"), autumn.instant)
            assertEquals("$zone, one hour forward", 3600, spring.offsetAfter.totalSeconds - spring.offsetBefore.totalSeconds)
            assertEquals("$zone, one hour back", -3600, autumn.offsetAfter.totalSeconds - autumn.offsetBefore.totalSeconds)
        }
    }
}
