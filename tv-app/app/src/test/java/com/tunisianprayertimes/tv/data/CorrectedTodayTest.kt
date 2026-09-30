package com.tunisianprayertimes.tv.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorrectedTodayTest {

    private val store = PrefsClockStore(InMemoryPreferences())

    @Test
    fun theDateFollowsTheScreensCorrectionNotTheDeviceClock() {
        // The device clock was set three days ahead by hand; the network corrected the screen.
        val device = Instant.parse("2027-02-10T21:00:00Z")
        assertEquals(LocalDate.of(2027, 2, 10), correctedToday(store, device))
        store.correctionMillis = -Duration.ofDays(3).toMillis()
        assertEquals(LocalDate.of(2027, 2, 7), correctedToday(store, device))
        // In Tunisia's zone: 23:30 UTC is already tomorrow there.
        store.correctionMillis = 0
        assertEquals(LocalDate.of(2027, 2, 11), correctedToday(store, Instant.parse("2027-02-10T23:30:00Z")))
    }

    @Test
    fun aTimeThatCannotBeRightGivesNoDate() {
        assertNull(correctedToday(store, Instant.parse("2015-01-01T00:00:00Z")))
    }
}
