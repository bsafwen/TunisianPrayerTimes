package com.tunisianprayertimes

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real URL selection, response parsing, fallback and cleanup with an offline HTTP transport. */
class OfficialDateFetchTest {
    private val previousTransport = RamadanOverrideChecker.openConnection
    private val previousReporter = RamadanOverrideChecker.analyticsReporter
    private lateinit var environment: OfficialDateTestEnvironment
    private val requests = mutableListOf<FakeConnection>()
    private val reports = mutableListOf<RamadanOverrideChecker.FetchReport>()
    private var response: (URL) -> FakeConnection = { throw AssertionError("Unexpected request: $it") }

    @BeforeTest fun setup() {
        environment = OfficialDateTestEnvironment()
        RamadanOverrideChecker.openConnection = { url -> response(url).also { requests += it } }
        RamadanOverrideChecker.analyticsReporter = { reports += it }
    }

    @AfterTest fun cleanup() {
        RamadanOverrideChecker.openConnection = previousTransport
        RamadanOverrideChecker.analyticsReporter = previousReporter
        environment.close()
    }

    @Test
    fun fetchUsesRequestedYearAndPublishesTheActualResponseThroughAppConsumers() {
        val payload = checkNotNull(javaClass.getResourceAsStream("/islamic-calendar/1447.json"))
            .bufferedReader().use { it.readText() }
        response = { FakeConnection(it, payload = payload) }
        val record = assertNotNull(RamadanOverrideChecker.fetchOverride())
        assertEquals(1, requests.size)
        assertTrue(requests.single().url.path.endsWith("/data/official-islamic-dates/1447.json"))
        assertEquals(10_000, requests.single().connectTimeout)
        assertEquals(15_000, requests.single().readTimeout)
        assertEquals("GET", requests.single().requestMethod)
        assertTrue(requests.single().disconnected)
        OfficialIslamicDates.record(record)
        assertEquals(LocalDate.of(2026, 3, 20), RamadanOverrideChecker.getEidFitrDate())
        assertEquals(HijriCalendarDate(1447, 12, 10, false),
            OfficialIslamicDates.calendar().date(LocalDate.of(2026, 5, 27)))
        assertEquals("2026-02-18T20:00:00Z", record.ramadanStartUpdated)
        assertEquals("success", reports.single().result)
    }

    @Test
    fun newYearRequestDoesNotReuseThePreviousYearsEndpoint() {
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2027, 2, 10)
        response = { FakeConnection(it, payload = """{"hijriYear":1448,"ramadanStart":"2027-02-08"}""") }
        assertEquals(1448, RamadanOverrideChecker.fetchOverride()?.hijriYear)
        assertTrue(requests.single().url.path.endsWith("/data/official-islamic-dates/1448.json"))
    }

    @Test
    fun missingPrimaryFallsBackToLegacyAndDisconnectsBothConnections() {
        response = { url ->
            if (url.path.contains("/data/")) FakeConnection(url, status = 404)
            else FakeConnection(url, payload = """{"hijriYear":1447,"ramadanStart":"2026-02-19"}""")
        }
        assertEquals(LocalDate.of(2026, 2, 19), RamadanOverrideChecker.fetchOverride()?.ramadanStart)
        assertEquals(2, requests.size)
        assertTrue(requests.last().url.path.endsWith("/ramadan-override-1447.json"))
        assertTrue(requests.all { it.disconnected })
        assertEquals("success", reports.single().result)
    }

    @Test
    fun wrongYearAndMalformedResponsesCannotReplaceAnOfflineCorrection() {
        OfficialIslamicDates.importJson("""{"hijriYear":1447,"ramadanStart":"2026-02-19"}""")
        response = { url ->
            FakeConnection(url, payload = if (url.path.contains("/data/"))
                """{"hijriYear":1448,"ramadanStart":"2027-02-08"}""" else "not json")
        }
        assertNull(RamadanOverrideChecker.fetchOverride())
        assertTrue(requests.all { it.disconnected })
        assertEquals(LocalDate.of(2026, 2, 19), OfficialIslamicDates.cachedForYear(1447)?.ramadanStart)
        assertEquals("parse_error", reports.single().result)
    }

    @Test
    fun timedOutBodiesReleaseConnectionsAndASecondAttemptCanSucceed() {
        response = { FakeConnection(it, timeout = true) }
        assertNull(RamadanOverrideChecker.fetchOverride())
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.disconnected })
        assertEquals("network_error", reports.single().result)
        response = { FakeConnection(it, payload = """{"hijriYear":1447,"eidFitrDate":"2026-03-20"}""") }
        assertNotNull(RamadanOverrideChecker.fetchOverride())
        assertEquals(3, requests.size)
        assertTrue(requests.last().disconnected)
        assertFalse(reports.last().cacheUsed)
        assertEquals("success", reports.last().result)
    }

    private class FakeConnection(
        url: URL,
        private val status: Int = 200,
        private val payload: String = "",
        private val timeout: Boolean = false,
    ) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream {
            if (timeout) throw SocketTimeoutException("Offline timeout fixture")
            return ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))
        }
    }
}
