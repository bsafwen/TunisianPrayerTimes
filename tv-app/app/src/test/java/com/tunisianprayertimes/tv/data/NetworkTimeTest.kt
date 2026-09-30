package com.tunisianprayertimes.tv.data

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkTimeTest {

    private val date = "Tue, 29 Sep 2026 13:00:00 GMT"
    private val server = Instant.parse("2026-09-29T13:00:00Z")

    @Test
    fun readsTheHttpDateAndNothingElse() {
        assertEquals(server, NetworkTime.parseHttpDate(date))
        assertEquals(server, NetworkTime.parseHttpDate(" $date "))
        for (bad in listOf(null, "", "  ", "2026-09-29T13:00:00Z", "Tue, 29 Sep 2026 25:00:00 GMT", "yesterday")) {
            assertNull(bad, NetworkTime.parseHttpDate(bad))
        }
    }

    @Test
    fun theServersDateIsCarriedToNow() {
        // Written half-way through a 400 ms round trip, cut to the second: 200 ms and half a second later.
        assertEquals(server.plusMillis(700), NetworkTime.fromDateHeader(date, sentAt = 1_000, receivedAt = 1_400, nowElapsed = 1_400)?.instant)
        // Then whatever elapsed since the answer came.
        val reading = NetworkTime.fromDateHeader(date, sentAt = 1_000, receivedAt = 1_400, nowElapsed = 2_400)
        assertEquals(server.plusMillis(1_700), reading?.instant)
        // And on to the moment the guard takes it, however late the main thread gets to it.
        assertEquals(server.plusMillis(2_000), reading?.at(2_700))
    }

    @Test
    fun theServersDateIsRightToWithinHalfTheRoundTripAndASecond() {
        val reading = NetworkTime.fromDateHeader(date, sentAt = 1_000, receivedAt = 1_400, nowElapsed = 1_400)
        assertEquals(Duration.ofMillis(1_200), reading?.uncertainty)
        assertEquals("over HTTPS", true, reading?.authenticated)
    }

    @Test
    fun aDateThatCannotBeRightIsNeverTrusted() {
        for (bad in listOf("Thu, 01 Jan 1970 00:00:00 GMT", "Mon, 31 Aug 2026 23:59:58 GMT", "Sat, 01 Jan 2101 00:00:00 GMT")) {
            assertNull(bad, NetworkTime.fromDateHeader(bad, 1_000, 1_400, 1_400))
        }
        assertNull("no Date header", NetworkTime.fromDateHeader(null, 1_000, 1_400, 1_400))
    }

    @Test
    fun aVagueOrImpossibleRoundTripIsNotTrusted() {
        assertNull("too long", NetworkTime.fromDateHeader(date, 0, NetworkTime.MAX_ROUND_TRIP_MILLIS + 1, NetworkTime.MAX_ROUND_TRIP_MILLIS + 1))
        assertEquals(
            server.plusMillis(NetworkTime.MAX_ROUND_TRIP_MILLIS / 2 + 500),
            NetworkTime.fromDateHeader(date, 0, NetworkTime.MAX_ROUND_TRIP_MILLIS, NetworkTime.MAX_ROUND_TRIP_MILLIS)?.instant,
        )
        assertNull("answer before the request", NetworkTime.fromDateHeader(date, 2_000, 1_000, 2_000))
        assertNull("now before the answer", NetworkTime.fromDateHeader(date, 1_000, 1_400, 1_300))
    }
}
