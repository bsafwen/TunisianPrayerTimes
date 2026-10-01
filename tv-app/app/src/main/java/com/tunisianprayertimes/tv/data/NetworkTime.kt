package com.tunisianprayertimes.tv.data

import android.os.Build
import android.os.SystemClock
import com.tunisianprayertimes.time.ClockGuard
import com.tunisianprayertimes.time.ClockSource
import com.tunisianprayertimes.tv.kiosk.ClockLog
import com.tunisianprayertimes.tv.kiosk.EventLog
import com.tunisianprayertimes.tv.kiosk.KioskEvent
import java.net.URL
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The time from the network, to check the device clock ([ClockGuard.networkTime]). Many boxes have no
 * clock battery or were set by hand in a foreign zone; when one is online, this settles its time without
 * asking anyone. Cheap: one HEAD request to Open-Meteo (the weather's server, so no new host), whose
 * answer carries the time in its Date header. HTTPS only: over plain HTTP anyone on the mosque's network
 * could send a date, and it would move every prayer. The system's own network time (Android 13+) is
 * plain NTP, as open to anyone on the network: it only serves when HTTPS gives nothing, and the guard
 * takes it only for a clock that cannot be right (one years off, which fails every HTTPS check).
 */
object NetworkTime {
    const val ADDRESS = "https://api.open-meteo.com/"
    private const val TIMEOUT_MILLIS = 5_000

    /** A longer round trip makes the answer's date too vague to trust (it could have been written at any point of it). */
    const val MAX_ROUND_TRIP_MILLIS = 20_000L

    /** The system's network time comes from NTP, to the millisecond: a second is ample. */
    private val SYSTEM_UNCERTAINTY: Duration = Duration.ofSeconds(1)

    /** A network time: [instant] at [atElapsed] (the time since boot), within [uncertainty]; [authenticated] when it came over HTTPS. */
    class Reading(val instant: Instant, val atElapsed: Long, val uncertainty: Duration, val authenticated: Boolean) {
        /** The time at [nowElapsed], carried forward on the time since boot. */
        fun at(nowElapsed: Long): Instant = instant.plusMillis(nowElapsed - atElapsed)
    }

    /**
     * The network's time, or null (no answer, or an answer that cannot be right). Never throws. The HTTPS
     * date when [internet] (the network says it reaches the internet, validated or not: a failed request
     * is quiet and costs nothing), else the system's own network time, which needs no request at all.
     */
    suspend fun now(internet: Boolean): Reading? = withContext(Dispatchers.IO) {
        (if (internet) runCatching { fromServer() }.getOrNull() else null) ?: systemNetworkTime()
    }

    /**
     * Asks the network for the time and gives it to [guard]. Call it from a coroutine on the main thread,
     * where the guard is read; only the request runs on the IO threads. Logs CLOCK_CONFIRMED when the
     * network confirms a time it had not confirmed before, or corrects it. False when no network time was
     * found (offline) or the guard did not take it: nothing changes then.
     */
    suspend fun check(guard: ClockGuard, log: EventLog, internet: Boolean): Boolean {
        val time = now(internet) ?: return false
        val before = guard.read()
        // Carried to this moment: the main thread may have been busy when the answer came.
        if (!guard.networkTime(time.at(SystemClock.elapsedRealtime()), time.uncertainty, time.authenticated)) return false
        val after = guard.read()
        val moved = Duration.between(before.now, after.now).toMillis()
        if (before.source != ClockSource.NETWORK || abs(moved) > ClockGuard.NETWORK_TOLERANCE.toMillis()) {
            log.append(KioskEvent.CLOCK_CONFIRMED, ClockLog.confirmed(ClockSource.NETWORK, moved))
        }
        return true
    }

    /** Android 13+: the time the system got from the network since boot, carried forward with the time since boot. */
    private fun systemNetworkTime(): Reading? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            plausible(SystemClock.currentNetworkTimeClock().instant())
                ?.let { Reading(it, SystemClock.elapsedRealtime(), SYSTEM_UNCERTAINTY, authenticated = false) }
        } catch (e: DateTimeException) {
            null // no network time yet this boot
        } catch (e: RuntimeException) {
            null
        }
    }

    /**
     * One HEAD request, no redirect, no cache. The connection (and its TLS handshake) is made first, so the
     * round trip measured is the request's alone. With a system clock years off, the certificate check
     * fails and this gives nothing: the system's network time serves then, if it has one, else the admin
     * or the phone sets the time.
     */
    private fun fromServer(): Reading? {
        val connection = URL(ADDRESS).openConnection() as? HttpsURLConnection ?: return null
        try {
            connection.requestMethod = "HEAD"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", "TunisianPrayerTimesTV")
            connection.connect()
            val sent = SystemClock.elapsedRealtime()
            connection.responseCode // any answer of the server carries its date, a 404 as well as a 200
            val received = SystemClock.elapsedRealtime()
            return fromDateHeader(connection.getHeaderField("Date"), sent, received, SystemClock.elapsedRealtime())
        } finally {
            connection.disconnect()
        }
    }

    /** An HTTP Date header ("Tue, 29 Sep 2026 13:00:00 GMT"), or null when missing or malformed. */
    internal fun parseHttpDate(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (e: DateTimeException) {
            null
        }
    }

    /**
     * The time at [nowElapsed] from a server's Date header, received at [receivedAt] for a request sent at
     * [sentAt] (times since boot). The server wrote its date during the round trip, most likely half-way,
     * and cut to the second: half the round trip and half a second are added, then the time since the
     * answer came; it is right to within half the round trip and a second. Null when there is no date,
     * when it cannot be right (before [ClockGuard.EARLIEST] or after [ClockGuard.LATEST]: a server with a
     * broken clock must not move the prayers), or when the round trip was too long for the date to mean much.
     */
    internal fun fromDateHeader(date: String?, sentAt: Long, receivedAt: Long, nowElapsed: Long): Reading? {
        val server = parseHttpDate(date) ?: return null
        val roundTrip = receivedAt - sentAt
        if (roundTrip !in 0..MAX_ROUND_TRIP_MILLIS || nowElapsed < receivedAt) return null
        val instant = plausible(server.plusMillis(roundTrip / 2 + 500 + (nowElapsed - receivedAt))) ?: return null
        return Reading(instant, nowElapsed, Duration.ofMillis(roundTrip / 2 + 1_000), authenticated = true)
    }

    private fun plausible(instant: Instant): Instant? =
        instant.takeUnless { it.isBefore(ClockGuard.EARLIEST) || it.isAfter(ClockGuard.LATEST) }
}
