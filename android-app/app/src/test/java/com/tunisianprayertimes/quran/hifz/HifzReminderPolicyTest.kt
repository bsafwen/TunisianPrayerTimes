package com.tunisianprayertimes.quran.hifz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class HifzReminderPolicyTest {
    private val utc = ZoneOffset.UTC
    private fun at(value: String) = Instant.parse(value).toEpochMilli()
    private val quiet = HifzSettings(quietHours = true, quietStartMinute = 22 * 60, quietEndMinute = 6 * 60)

    @Test fun overnightQuietHours_endTomorrow_andDoNotDelayDaytime() {
        assertEquals(at("2026-10-25T06:00:00Z"), hifzAfterQuietHours(at("2026-10-24T23:00:00Z"), quiet, utc))
        assertEquals(at("2026-10-25T06:00:00Z"), hifzAfterQuietHours(at("2026-10-25T05:59:00Z"), quiet, utc))
        assertEquals(at("2026-10-25T06:00:00Z"), hifzAfterQuietHours(at("2026-10-25T06:00:00Z"), quiet, utc))
        assertEquals(at("2026-10-25T12:00:00Z"), hifzAfterQuietHours(at("2026-10-25T12:00:00Z"), quiet, utc))
    }

    @Test fun daytimeQuietHours_andEqualEndpoints_areExplicit() {
        val daytime = quiet.copy(quietStartMinute = 12 * 60, quietEndMinute = 14 * 60)
        assertEquals(at("2026-10-25T14:00:00Z"), hifzAfterQuietHours(at("2026-10-25T13:00:00Z"), daytime, utc))
        val now = at("2026-10-25T13:00:00Z")
        assertEquals(now, hifzAfterQuietHours(now, daytime.copy(quietEndMinute = daytime.quietStartMinute), utc))
    }

    @Test fun quietEndDuringRepeatedDstHour_staysInTheFuture() {
        val paris = ZoneId.of("Europe/Paris")
        val second0215 = at("2026-10-25T01:15:00Z")
        val end0230 = quiet.copy(quietEndMinute = 2 * 60 + 30)
        assertEquals(at("2026-10-25T01:30:00Z"), hifzAfterQuietHours(second0215, end0230, paris))
        assertEquals(at("2026-10-25T05:00:00Z"), hifzAfterQuietHours(at("2026-10-24T21:00:00Z"), quiet, paris))
    }

    @Test fun automaticGap_coalescesNearbyAlerts_butExplicitLaterMayUseThirtyMinutes() {
        val last = at("2026-10-25T10:00:00Z")
        val now = last + HifzLaterMs
        val settings = HifzSettings(dailyAlertLimit = 3)
        assertEquals(last + HifzAlertGapMs, hifzAlertNotBefore(now, settings, 1, false, last, zone = utc))
        assertEquals(now, hifzAlertNotBefore(now, settings, 1, false, last, explicitLater = true, zone = utc))
    }

    @Test fun budgetAndPause_alsoApplyToExplicitLater() {
        val now = at("2026-10-25T10:30:00Z")
        val tomorrow = at("2026-10-26T00:00:00Z")
        val settings = HifzSettings(dailyAlertLimit = 3)
        assertEquals(tomorrow, hifzAlertNotBefore(now, settings, 3, false, 0, explicitLater = true, zone = utc))
        assertEquals(tomorrow, hifzAlertNotBefore(now, settings, 0, true, 0, explicitLater = true, zone = utc))
        assertEquals(at("2026-10-26T06:00:00Z"), hifzAlertNotBefore(now, quiet, 3, false, 0, zone = utc))
    }

    @Test fun correctedFutureClock_doesNotImposeAnArbitraryGap() {
        val now = at("2026-10-25T10:00:00Z")
        assertEquals(now, hifzAlertNotBefore(now, HifzSettings(), 0, false, now + 86_400_000L, zone = utc))
    }

    @Test fun catchUpRetainsOriginalDueTime_andHasAnInclusiveNinetyMinuteLimit() {
        val original = at("2026-10-25T10:00:00Z")
        assertTrue(hifzCanCatchUp(original, original))
        assertTrue(hifzCanCatchUp(original, original + HifzCatchUpMs))
        assertFalse(hifzCanCatchUp(original, original + HifzCatchUpMs + 1))
        assertFalse(hifzCanCatchUp(original, original - 1))
    }

    @Test fun readingAndPrayerDeferrals_areCombinedBeforeTestingCatchUp() {
        val original = at("2026-10-25T10:00:00Z")
        val prayerEnd = original + 80 * 60_000L
        val result = hifzAllowedAlertAt(original, HifzSettings(), original + 70 * 60_000L) {
            if (it < prayerEnd) prayerEnd else it
        }
        assertEquals(prayerEnd, result)
        assertTrue(hifzCanCatchUp(original, result))
        assertFalse(hifzCanCatchUp(original, hifzAllowedAlertAt(original, HifzSettings(), original + 91 * 60_000L) { it }))
    }
}
