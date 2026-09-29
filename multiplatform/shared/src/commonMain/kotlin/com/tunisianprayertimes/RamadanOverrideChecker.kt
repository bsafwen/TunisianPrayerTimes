package com.tunisianprayertimes

import com.tunisianprayertimes.platform.PrayerDataLoader
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.Instant
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.tunisianprayertimes.platform.Preferences
import com.tunisianprayertimes.time.TunisTime

/**
 * Fetches the official Islamic date JSON from GitHub Pages to get the official
 * Ramadan start / Eid dates announced for Tunisia.
 *
 * The source file is data/official-islamic-dates/{hijriYear}.json and contains:
 * {
 *   "hijriYear": 1448,
 *   "ramadanStart": "2027-02-17",   // Gregorian date, null if not yet announced
 *   "eidFitrDate": "2027-03-19",    // null if not yet announced
 *   "eidAdhaDate": "2027-05-26",    // null if not yet announced
 *   "lastUpdated": "2027-02-16T20:00:00Z"
 * }
 *
 * Polling strategy:
 * - Starts polling hourly 2 days before algorithmic Ramadan (according to HijrahDate).
 * - Once an accepted ramadanStart is fetched, stops polling for Ramadan start.
 * - Also polls for eidFitrDate near end of Ramadan.
 * - Polls for eidAdhaDate near Dhul Hijja (moon sighting may differ from drift).
 */
object RamadanOverrideChecker {

    private const val BASE_URL = "https://bsafwen.github.io/TunisianPrayerTimes"
    private const val OFFICIAL_DATES_PATH = "data/official-islamic-dates"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 15_000
    private const val MAX_QUICK_RETRIES = 3
    private const val RETRY_DELAY_MS = 60_000L // 1 minute between quick retries

    /** Override for testing — set non-null to simulate a different "today". */
    @JvmStatic
    internal var testDateOverride: LocalDate? = null

    /** Replace only transport in offline tests; exercise the real fetch and parsing path. */
    internal var openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }

    // Today in Tunisia, whatever the device's zone: the announcements are Tunisia's dates.
    private fun today(): LocalDate = testDateOverride ?: LocalDate.now(TunisTime.ZONE)
    private fun hijrahToday(): HijrahDate = testDateOverride?.let { HijrahDate.from(it) } ?: HijrahDate.now(TunisTime.ZONE)

    // Cached override data
    @Volatile
    var cachedOverride: RamadanOverride? = null
        internal set

    private val pollingLock = Any()
    private var pollingGeneration = 0L
    private var pollingExecutor: ScheduledExecutorService? = null
    private var scheduledFuture: ScheduledFuture<*>? = null

    data class RamadanOverride(
        val hijriYear: Int,
        val ramadanStart: LocalDate?,
        val eidFitrDate: LocalDate?,
        val eidAdhaDate: LocalDate?,
        val lastUpdated: String? = null,
        val ramadanStartUpdated: String? = lastUpdated,
        val eidFitrUpdated: String? = lastUpdated,
        val eidAdhaUpdated: String? = lastUpdated,
    )

    private fun currentHijriYear(): Int = hijrahToday().get(ChronoField.YEAR)

    internal fun calendar(): TunisianHijriCalendar = OfficialIslamicDates.calendar()

    /** Keep the legacy current-year cache for released callers and background workers. */
    internal fun useOfficialOverride(override: RamadanOverride) {
        if (override.hijriYear == currentHijriYear() || override.hijriYear == cachedOverride?.hijriYear) {
            if (cachedOverride == override) return
            cachedOverride = override
            saveToPreferences(override)
        }
    }

    data class FetchReport(
        val hijriYear: Int,
        val result: String,
        val hasRamadanStart: Boolean,
        val hasEidFitr: Boolean,
        val hasEidAdha: Boolean,
        val cacheUsed: Boolean,
    )

    @Volatile
    var analyticsReporter: ((FetchReport) -> Unit)? = null

    /**
     * Start periodic polling if we're within 2 days of an event that needs override.
     * Call this on app startup / from periodic workers.
     * Safe to call multiple times — will only start one poller.
     */
    fun startPollingIfNeeded() {
        // Load persisted override if not already in memory
        loadCachedOverrideIfNeeded()

        if (!shouldStartPolling()) return
        synchronized(pollingLock) {
            if (pollingExecutor != null) return
            val executor = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "RamadanOverridePoller").apply { isDaemon = true }
            }
            val generation = ++pollingGeneration
            pollingExecutor = executor
            // Publish the future while holding the same lock used by the first
            // tick, so an immediate successful response cannot leave an orphan.
            scheduledFuture = executor.scheduleWithFixedDelay({
                pollOnce(generation)
            }, 0, 1, TimeUnit.HOURS)
        }
    }

    private fun pollOnce(generation: Long) {
        if (!isCurrentPoller(generation)) return
        try {
            if (!shouldStartPolling()) {
                stopPolling(generation)
                return
            }
            var fetched: RamadanOverride? = null
            for (attempt in 1..MAX_QUICK_RETRIES) {
                if (!isCurrentPoller(generation)) return
                fetched = fetchOverride()
                if (fetched != null) break
                if (attempt < MAX_QUICK_RETRIES) Thread.sleep(RETRY_DELAY_MS)
            }
            if (!isCurrentPoller(generation)) return
            fetched?.let { OfficialIslamicDates.record(it) }
            // A successful HTTP response can still contain a missing or rejected
            // event. Re-evaluate all relevant windows against accepted anchors.
            if (!shouldStartPolling()) stopPolling(generation)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // Keep the hourly retry available while the event window is active.
        }
    }

    private fun isCurrentPoller(generation: Long): Boolean = synchronized(pollingLock) {
        pollingExecutor != null && pollingGeneration == generation
    }

    /**
     * Determines whether polling should start based on the current (possibly overridden) date
     * and the state of the cached override. Visible for testing.
     */
    // Official dates only: a date the admin set by hand must not stop the announcement from being fetched.
    internal fun shouldStartPolling(): Boolean = hasPendingAnnouncement(OfficialIslamicDates.officialCalendar())

    private fun hasPendingAnnouncement(calendar: TunisianHijriCalendar): Boolean {
        val today = today()
        val hijrahDate = HijrahDate.from(today)
        val year = hijrahDate.get(ChronoField.YEAR)
        val month = hijrahDate.get(ChronoField.MONTH_OF_YEAR)
        val day = hijrahDate.get(ChronoField.DAY_OF_MONTH)
        val daysInMonth = hijrahDate.lengthOfMonth()
        val ramadan = calendar.month(year, 9)
        val fitr = calendar.month(year, 10)
        val adha = calendar.month(year, 12)

        val ramadanWindow = (month == 8 && day >= daysInMonth - 2) || (month == 9 && day <= 2)
        val fitrWindow = if (!ramadan.isEstimated) {
            val real29thRamadan = ramadan.start.plusDays(28)
            !today.isBefore(real29thRamadan) && today.isBefore(real29thRamadan.plusDays(3))
        } else {
            (month == 9 && day >= 28) || (month == 10 && day == 1)
        }
        val drift = computeDriftDays(year, calendar)
        val adhaWindow = if (drift != null) {
            val real29thDhulQidah = LocalDate.from(HijrahDate.of(year, 11, 29)).plusDays(drift)
            !today.isBefore(real29thDhulQidah) && today.isBefore(real29thDhulQidah.plusDays(13))
        } else {
            (month == 11 && day >= daysInMonth - 2) || (month == 12 && day in 1..10)
        }
        // These are independent: an unannounced Fitr outside its window must
        // never prevent Adha from being fetched two months later.
        return (ramadanWindow && ramadan.isEstimated) ||
            (fitrWindow && fitr.isEstimated) || (adhaWindow && adha.isEstimated)
    }

    /** Stop the periodic polling. */
    fun stopPolling() = stopPolling(null)

    private fun stopPolling(generation: Long?) {
        synchronized(pollingLock) {
            // A previous request may finish after another caller has restarted
            // polling. It must not cancel the new executor.
            if (generation != null && generation != pollingGeneration) return
            pollingGeneration++
            scheduledFuture?.cancel(false)
            scheduledFuture = null
            pollingExecutor?.shutdownNow()
            pollingExecutor = null
        }
    }

    /** Load the persisted official-date JSON, if available, without starting network polling. */
    fun loadCachedOverrideIfNeeded() {
        if (cachedOverride == null) {
            loadFromPreferences()
        }
        OfficialIslamicDates.loadCachedDates()
    }

    /**
     * Do a single synchronous fetch. Call from background thread / coroutine.
     * Returns null on failure.
     */
    fun fetchOverride(): RamadanOverride? {
        val hijriYear = hijrahToday().get(ChronoField.YEAR)
        return fetchOverrideForYear(hijriYear)
    }

    fun fetchOverrideForYear(hijriYear: Int): RamadanOverride? {
        val primary = fetchOverrideFromUrl(officialDatesUrl(hijriYear), hijriYear)
        if (primary.override != null) {
            reportFetch(
                hijriYear = hijriYear,
                result = "success",
                override = primary.override,
                cacheUsed = false,
            )
            return primary.override
        }

        val legacy = fetchOverrideFromUrl(legacyOverrideUrl(hijriYear), hijriYear)
        val fallbackOverride = legacy.override
        reportFetch(
            hijriYear = hijriYear,
            result = if (fallbackOverride != null) "success" else legacy.result,
            override = fallbackOverride,
            cacheUsed = false,
        )
        return fallbackOverride
    }

    private data class FetchAttempt(
        val override: RamadanOverride?,
        val result: String,
    )

    private fun officialDatesUrl(hijriYear: Int): String = "$BASE_URL/$OFFICIAL_DATES_PATH/$hijriYear.json"

    private fun legacyOverrideUrl(hijriYear: Int): String = "$BASE_URL/ramadan-override-$hijriYear.json"

    internal fun officialDatesUrlForTest(hijriYear: Int): String = officialDatesUrl(hijriYear)

    internal fun legacyOverrideUrlForTest(hijriYear: Int): String = legacyOverrideUrl(hijriYear)

    private fun fetchOverrideFromUrl(urlStr: String, hijriYear: Int): FetchAttempt {
        return try {
            val url = URL(urlStr)
            val conn = openConnection(url)
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.requestMethod = "GET"
            try {
                if (conn.responseCode == 200) {
                    val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val parsed = parseOverride(text)?.takeIf { it.hijriYear == hijriYear }
                    FetchAttempt(parsed, if (parsed != null) "success" else "parse_error")
                } else {
                    FetchAttempt(null, "http_error")
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            FetchAttempt(null, "network_error")
        }
    }

    internal fun parseOverride(json: String): RamadanOverride? {
        return try {
            // Minimal JSON parsing without external dependencies
            val hijriYear = extractInt(json, "hijriYear") ?: return null
            val ramadanStart = extractString(json, "ramadanStart")?.let { parseDate(it) }
            val eidFitrDate = extractString(json, "eidFitrDate")?.let { parseDate(it) }
            val eidAdhaDate = extractString(json, "eidAdhaDate")?.let { parseDate(it) }
            val lastUpdated = parseTimestamp(extractString(json, "lastUpdated"))
            fun eventUpdated(key: String): String? =
                if (containsKey(json, key)) parseTimestamp(extractString(json, key)) else lastUpdated

            RamadanOverride(
                hijriYear = hijriYear,
                ramadanStart = ramadanStart,
                eidFitrDate = eidFitrDate,
                eidAdhaDate = eidAdhaDate,
                lastUpdated = lastUpdated,
                ramadanStartUpdated = eventUpdated("ramadanStartUpdated"),
                eidFitrUpdated = eventUpdated("eidFitrUpdated"),
                eidAdhaUpdated = eventUpdated("eidAdhaUpdated"),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Exposed for testing. */
    internal fun parseOverrideForTest(json: String): RamadanOverride? = parseOverride(json)

    internal fun shouldStopPolling(override: RamadanOverride): Boolean {
        if (override.hijriYear != currentHijriYear()) return false
        val candidate = TunisianHijriCalendar(OfficialIslamicDates.updates.value + (override.hijriYear to override))
        return !hasPendingAnnouncement(candidate)
    }

    /**
     * Compute the drift in days between the official (announced) Eid al-Fitr date
     * and the algorithmic Umm al-Qura date. This drift is typically 0, +1, or -1.
     *
     * Prefers accepted Eid al-Fitr, then accepted Ramadan; returns null when
     * neither announcement is valid in the shared calendar.
     */
    fun computeDriftDays(hijriYear: Int = currentHijriYear()): Long? = computeDriftDays(hijriYear, calendar())

    private fun computeDriftDays(hijriYear: Int, calendar: TunisianHijriCalendar): Long? {
        for (month in listOf(10, 9)) {
            val accepted = calendar.month(hijriYear, month)
            if (!accepted.isEstimated) {
                val algorithmicStart = LocalDate.from(HijrahDate.of(hijriYear, month, 1))
                return ChronoUnit.DAYS.between(algorithmicStart, accepted.start)
            }
        }
        return null
    }

    /**
     * Returns the best-known Eid al-Fitr date:
    * 1. Explicit eidFitrDate from official-date JSON
     * 2. Estimated 1 Shawwal from the same corrected month boundaries as the calendar
     */
    fun getEidFitrDate(hijriYear: Int = currentHijriYear()): LocalDate =
        calendar().month(hijriYear, 10).start

    /**
     * Returns the best-known Eid al-Adha date (10 Dhul Hijja):
    * 1. Explicit eidAdhaDate from official-date JSON
     * 2. Algorithmic (10 Dhul Hijja) + drift from Eid al-Fitr offset
     * 3. Algorithmic (10 Dhul Hijja) if no drift available
     */
    fun getEidAdhaDate(hijriYear: Int = currentHijriYear()): LocalDate =
        calendar().month(hijriYear, 12).start.plusDays(9)

    /**
     * Check if a given Gregorian date is Eid al-Fitr (override-aware).
     */
    fun isEidFitr(date: LocalDate = today()): Boolean {
        return date == getEidFitrDate(calendar().date(date).year)
    }

    /**
     * Check if a given Gregorian date is Eid al-Adha (override-aware, drift-adjusted).
     */
    fun isEidAdha(date: LocalDate = today()): Boolean {
        return date == getEidAdhaDate(calendar().date(date).year)
    }

    /**
     * Whether the Eid al-Fitr prayer row should be visible in the prayer table.
     * Appears 2 days before Eid, disappears after Dhuhr on Eid day.
     *
     * @param date the date being displayed
     * @param nowHour current hour (24h), only used when [date] is the Eid day
     * @param nowMinute current minute, only used when [date] is the Eid day
     * @param dhuhrHour Dhuhr hour for the Eid day
     * @param dhuhrMinute Dhuhr minute for the Eid day
     * @param isToday whether [date] is today (controls the after-Dhuhr cutoff)
     */
    fun shouldShowEidFitrPrayer(
        date: LocalDate,
        nowHour: Int,
        nowMinute: Int,
        dhuhrHour: Int,
        dhuhrMinute: Int,
        isToday: Boolean,
    ): Boolean {
        return shouldShowEidPrayer(getEidFitrDate(calendar().date(date).year), date, nowHour, nowMinute, dhuhrHour, dhuhrMinute, isToday)
    }

    /**
     * Whether the Eid al-Adha prayer row should be visible in the prayer table.
     * Same logic as [shouldShowEidFitrPrayer].
     */
    fun shouldShowEidAdhaPrayer(
        date: LocalDate,
        nowHour: Int,
        nowMinute: Int,
        dhuhrHour: Int,
        dhuhrMinute: Int,
        isToday: Boolean,
    ): Boolean {
        return shouldShowEidPrayer(getEidAdhaDate(calendar().date(date).year), date, nowHour, nowMinute, dhuhrHour, dhuhrMinute, isToday)
    }

    private fun shouldShowEidPrayer(
        eidDate: LocalDate,
        displayDate: LocalDate,
        nowHour: Int,
        nowMinute: Int,
        dhuhrHour: Int,
        dhuhrMinute: Int,
        isToday: Boolean,
    ): Boolean {
        val twoDaysBefore = eidDate.minusDays(2)
        // Outside the [eidDate-2, eidDate] window → hide
        if (displayDate.isBefore(twoDaysBefore) || displayDate.isAfter(eidDate)) return false
        // On the Eid day itself AND it's today → hide after Dhuhr
        if (displayDate == eidDate && isToday) {
            val nowMinutes = nowHour * 60 + nowMinute
            val dhuhrMinutes = dhuhrHour * 60 + dhuhrMinute
            if (nowMinutes >= dhuhrMinutes) return false
        }
        return true
    }

    /**
     * Returns the default Eid prayer time (shuruk) based on the Eid day's
     * prayer data, NOT the currently displayed day.
     *
     * @return Pair(hour, minute) or null if prayer data is unavailable for the Eid day
     */
    fun getDefaultEidPrayerTime(delegationId: Int, eidDate: LocalDate): Pair<Int, Int>? {
        return try {
            val times = PrayerDataLoader.loadDayPrayerTimes(
                delegationId,
                eidDate.year,
                eidDate.monthValue,
                eidDate.dayOfMonth
            ) ?: return null
            val totalMinutes = times.shurukHour * 60 + times.shurukMinute
            Pair(totalMinutes / 60, totalMinutes % 60)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDate(s: String): LocalDate? {
        return try {
            LocalDate.parse(s)
        } catch (_: Exception) {
            null
        }
    }

    // Simple JSON extraction helpers (avoids external JSON dependency in shared module)

    private fun extractString(json: String, key: String): String? {
        val pattern = """"$key"\s*:\s*"([^"]+)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun extractInt(json: String, key: String): Int? {
        val pattern = """"$key"\s*:\s*(\d+)""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun containsKey(json: String, key: String): Boolean =
        """"${Regex.escape(key)}"\s*:""".toRegex().containsMatchIn(json)

    private fun parseTimestamp(value: String?): String? =
        value?.let { runCatching { Instant.parse(it).toString() }.getOrNull() }

    // --- Persistence helpers ---

    private fun loadFromPreferences() {
        try {
            val json = Preferences.getRamadanOverrideJson() ?: return
            val parsed = parseOverride(json)
            if (parsed != null) {
                OfficialIslamicDates.loadCachedYear(parsed.hijriYear)
                OfficialIslamicDates.record(parsed)
                reportFetch(
                    hijriYear = parsed.hijriYear,
                    result = "cache_loaded",
                    override = parsed,
                    cacheUsed = true,
                )
            }
        } catch (_: Exception) {
            // Ignore corrupt data
        }
    }

    private fun reportFetch(
        hijriYear: Int,
        result: String,
        override: RamadanOverride?,
        cacheUsed: Boolean,
    ) {
        analyticsReporter?.invoke(
            FetchReport(
                hijriYear = override?.hijriYear ?: hijriYear,
                result = result,
                hasRamadanStart = override?.ramadanStart != null,
                hasEidFitr = override?.eidFitrDate != null,
                hasEidAdha = override?.eidAdhaDate != null,
                cacheUsed = cacheUsed,
            ),
        )
    }

    private fun saveToPreferences(override: RamadanOverride) {
        try {
            Preferences.setRamadanOverrideJson(toJson(override))
        } catch (_: Exception) {
            // Best-effort persistence
        }
    }

    internal fun toJson(o: RamadanOverride): String {
        fun jsonStr(v: Any?): String = if (v == null) "null" else "\"$v\""
        return """{
  "hijriYear": ${o.hijriYear},
  "ramadanStart": ${jsonStr(o.ramadanStart)},
  "eidFitrDate": ${jsonStr(o.eidFitrDate)},
  "eidAdhaDate": ${jsonStr(o.eidAdhaDate)},
  "lastUpdated": ${jsonStr(o.lastUpdated)},
  "ramadanStartUpdated": ${jsonStr(o.ramadanStartUpdated)},
  "eidFitrUpdated": ${jsonStr(o.eidFitrUpdated)},
  "eidAdhaUpdated": ${jsonStr(o.eidAdhaUpdated)}
}"""
    }
}
