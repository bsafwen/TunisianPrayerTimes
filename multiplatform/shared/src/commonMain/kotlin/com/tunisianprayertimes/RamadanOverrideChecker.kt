package com.tunisianprayertimes

import com.tunisianprayertimes.platform.PrayerDataLoader
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.tunisianprayertimes.platform.Preferences

/**
 * Fetches the Ramadan override JSON from GitHub Pages to get the official
 * Ramadan start / Eid dates as announced by the Tunisian Ministry of Religious Affairs.
 *
 * The file is named ramadan-override-{hijriYear}.json and contains:
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
 * - Once a non-null ramadanStart is fetched, stores it and stops polling for Ramadan start.
 * - Also polls for eidFitrDate near end of Ramadan.
 * - Polls for eidAdhaDate near Dhul Hijja (moon sighting may differ from drift).
 */
object RamadanOverrideChecker {

    private const val BASE_URL = "https://bsafwen.github.io/TunisianPrayerTimes"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 15_000
    private const val MAX_QUICK_RETRIES = 3
    private const val RETRY_DELAY_MS = 60_000L // 1 minute between quick retries

    /** Override for testing — set non-null to simulate a different "today". */
    @JvmStatic
    internal var testDateOverride: LocalDate? = null

    private fun today(): LocalDate = testDateOverride ?: LocalDate.now()
    private fun hijrahToday(): HijrahDate = testDateOverride?.let { HijrahDate.from(it) } ?: HijrahDate.now()

    // Cached override data
    @Volatile
    var cachedOverride: RamadanOverride? = null
        internal set

    private val polling = AtomicBoolean(false)
    private var scheduledFuture: ScheduledFuture<*>? = null

    data class RamadanOverride(
        val hijriYear: Int,
        val ramadanStart: LocalDate?,
        val eidFitrDate: LocalDate?,
        val eidAdhaDate: LocalDate?,
    )

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
        if (cachedOverride == null) {
            loadFromPreferences()
        }

        if (polling.get()) return

        val needsPoll = shouldStartPolling()
        if (!needsPoll) return
        if (!polling.compareAndSet(false, true)) return

        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "RamadanOverridePoller").apply { isDaemon = true }
        }
        scheduledFuture = executor.scheduleAtFixedRate({
            try {
                var fetched: RamadanOverride? = null
                for (attempt in 1..MAX_QUICK_RETRIES) {
                    fetched = fetchOverride()
                    if (fetched != null) break
                    if (attempt < MAX_QUICK_RETRIES) {
                        Thread.sleep(RETRY_DELAY_MS)
                    }
                }
                if (fetched != null) {
                    cachedOverride = fetched
                    saveToPreferences(fetched)
                    if (shouldStopPolling(fetched)) {
                        stopPolling()
                    }
                }
            } catch (_: Exception) {
            }
        }, 0, 1, TimeUnit.HOURS)
    }

    /**
     * Determines whether polling should start based on the current (possibly overridden) date
     * and the state of the cached override. Visible for testing.
     */
    internal fun shouldStartPolling(): Boolean {
        val hijrahDate = hijrahToday()
        val month = hijrahDate.get(ChronoField.MONTH_OF_YEAR)
        val day = hijrahDate.get(ChronoField.DAY_OF_MONTH)
        val daysInMonth = hijrahDate.lengthOfMonth()
        val today = today()

        return when {
            // 28th-29th Sha'ban — moon sighting for Ramadan start (28th covers ±1 day drift)
            month == 8 && day >= daysInMonth - 2 && cachedOverride?.ramadanStart == null -> true
            // First 2 days of Ramadan — in case we missed the announcement
            month == 9 && day <= 2 && cachedOverride?.ramadanStart == null -> true
            // Eid al-Fitr: use known ramadanStart to compute the real 29th Ramadan
            cachedOverride?.ramadanStart != null && cachedOverride?.eidFitrDate == null -> {
                val real29thRamadan = cachedOverride!!.ramadanStart!!.plusDays(28) // day 1 + 28 = day 29
                !today.isBefore(real29thRamadan) && today.isBefore(real29thRamadan.plusDays(3))
            }
            // Fallback: no ramadanStart known yet, use algorithmic 28th Ramadan
            month == 9 && day >= 28 && cachedOverride?.eidFitrDate == null -> true
            month == 10 && day == 1 && cachedOverride?.eidFitrDate == null -> true
            // Eid al-Adha: use drift to compute polling window (moon sighting may differ)
            cachedOverride?.eidAdhaDate == null -> {
                val drift = computeDriftDays()
                if (drift != null) {
                    val hijriYear = hijrahDate.get(ChronoField.YEAR)
                    val real29thDhulQidah = LocalDate.from(
                        HijrahDate.of(hijriYear, 11, 29)
                    ).plusDays(drift)
                    !today.isBefore(real29thDhulQidah) && today.isBefore(real29thDhulQidah.plusDays(13))
                } else {
                    (month == 11 && day >= daysInMonth - 2) || (month == 12 && day in 1..10)
                }
            }
            else -> false
        }
    }

    /** Stop the periodic polling. */
    fun stopPolling() {
        scheduledFuture?.cancel(false)
        scheduledFuture = null
        polling.set(false)
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
        val urlStr = "$BASE_URL/ramadan-override-$hijriYear.json"
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.requestMethod = "GET"
            try {
                if (conn.responseCode == 200) {
                    val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val parsed = parseOverride(text)
                    reportFetch(
                        hijriYear = hijriYear,
                        result = if (parsed != null) "success" else "parse_error",
                        override = parsed,
                        cacheUsed = false,
                    )
                    parsed
                } else {
                    reportFetch(
                        hijriYear = hijriYear,
                        result = "http_error",
                        override = null,
                        cacheUsed = false,
                    )
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            reportFetch(
                hijriYear = hijriYear,
                result = "network_error",
                override = null,
                cacheUsed = false,
            )
            null
        }
    }

    private fun parseOverride(json: String): RamadanOverride? {
        return try {
            // Minimal JSON parsing without external dependencies
            val hijriYear = extractInt(json, "hijriYear") ?: return null
            val ramadanStart = extractString(json, "ramadanStart")?.let { parseDate(it) }
            val eidFitrDate = extractString(json, "eidFitrDate")?.let { parseDate(it) }
            val eidAdhaDate = extractString(json, "eidAdhaDate")?.let { parseDate(it) }

            RamadanOverride(
                hijriYear = hijriYear,
                ramadanStart = ramadanStart,
                eidFitrDate = eidFitrDate,
                eidAdhaDate = eidAdhaDate,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Exposed for testing. */
    internal fun parseOverrideForTest(json: String): RamadanOverride? = parseOverride(json)

    internal fun shouldStopPolling(override: RamadanOverride): Boolean {
        val hijrahDate = hijrahToday()
        val month = hijrahDate.get(ChronoField.MONTH_OF_YEAR)

        return when {
            // Polling for Ramadan start — stop if we got it
            month in 8..9 && override.ramadanStart != null -> true
            // Polling for Eid al-Fitr — stop if we got it
            month in 9..10 && override.eidFitrDate != null -> true
            // Polling for Eid al-Adha — stop if we got it
            month in 11..12 && override.eidAdhaDate != null -> true
            else -> false
        }
    }

    /**
     * Compute the drift in days between the official (announced) Eid al-Fitr date
     * and the algorithmic Umm al-Qura date. This drift is typically 0, +1, or -1.
     *
     * Returns null if no eidFitrDate override is available, or if ramadanStart
     * is also available and can be used instead.
     */
    fun computeDriftDays(): Long? {
        val override = cachedOverride ?: return null
        // Prefer eidFitrDate for drift (most recent confirmed data point)
        if (override.eidFitrDate != null) {
            val algorithmicEidFitr = LocalDate.from(
                HijrahDate.of(override.hijriYear, 10, 1) // 1 Shawwal
            )
            return ChronoUnit.DAYS.between(algorithmicEidFitr, override.eidFitrDate)
        }
        // Fall back to ramadanStart for drift
        if (override.ramadanStart != null) {
            val algorithmicRamadanStart = LocalDate.from(
                HijrahDate.of(override.hijriYear, 9, 1) // 1 Ramadan
            )
            return ChronoUnit.DAYS.between(algorithmicRamadanStart, override.ramadanStart)
        }
        return null
    }

    /**
     * Returns the best-known Eid al-Fitr date:
     * 1. Explicit eidFitrDate from override JSON
     * 2. Algorithmic (1 Shawwal) if no override
     */
    fun getEidFitrDate(): LocalDate {
        val override = cachedOverride
        if (override?.eidFitrDate != null) return override.eidFitrDate
        return LocalDate.from(hijrahToday().let {
            HijrahDate.of(it.get(ChronoField.YEAR), 10, 1)
        })
    }

    /**
     * Returns the best-known Eid al-Adha date (10 Dhul Hijja):
     * 1. Explicit eidAdhaDate from override JSON
     * 2. Algorithmic (10 Dhul Hijja) + drift from Eid al-Fitr offset
     * 3. Algorithmic (10 Dhul Hijja) if no drift available
     */
    fun getEidAdhaDate(): LocalDate {
        val override = cachedOverride
        if (override?.eidAdhaDate != null) return override.eidAdhaDate

        val hijriYear = hijrahToday().get(ChronoField.YEAR)
        val algorithmicEidAdha = LocalDate.from(
            HijrahDate.of(hijriYear, 12, 10)
        )

        val drift = computeDriftDays()
        if (drift != null) return algorithmicEidAdha.plusDays(drift)

        return algorithmicEidAdha
    }

    /**
     * Check if a given Gregorian date is Eid al-Fitr (override-aware).
     */
    fun isEidFitr(date: LocalDate = today()): Boolean {
        return date == getEidFitrDate()
    }

    /**
     * Check if a given Gregorian date is Eid al-Adha (override-aware, drift-adjusted).
     */
    fun isEidAdha(date: LocalDate = today()): Boolean {
        return date == getEidAdhaDate()
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
        return shouldShowEidPrayer(getEidFitrDate(), date, nowHour, nowMinute, dhuhrHour, dhuhrMinute, isToday)
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
        return shouldShowEidPrayer(getEidAdhaDate(), date, nowHour, nowMinute, dhuhrHour, dhuhrMinute, isToday)
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

    // --- Persistence helpers ---

    private fun loadFromPreferences() {
        try {
            val json = Preferences.getRamadanOverrideJson() ?: return
            val parsed = parseOverride(json)
            cachedOverride = parsed
            if (parsed != null) {
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

    private fun toJson(o: RamadanOverride): String {
        fun jsonStr(v: Any?): String = if (v == null) "null" else "\"$v\""
        return """{
  "hijriYear": ${o.hijriYear},
  "ramadanStart": ${jsonStr(o.ramadanStart)},
  "eidFitrDate": ${jsonStr(o.eidFitrDate)},
  "eidAdhaDate": ${jsonStr(o.eidAdhaDate)}
}"""
    }
}
