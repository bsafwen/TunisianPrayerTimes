package com.tunisianprayertimes

import com.tunisianprayertimes.RamadanOverrideChecker.RamadanOverride
import com.tunisianprayertimes.platform.Preferences
import java.time.Instant
import java.time.LocalDate
import java.time.chrono.HijrahChronology
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared official announcements. Reading/formatting a date never performs network IO. */
object OfficialIslamicDates {
    internal var store = OfficialIslamicDateStore(
        readSavedYear = Preferences::getOfficialIslamicDatesJson,
        writeSavedYear = Preferences::setOfficialIslamicDatesJson,
        readLegacy = Preferences::getRamadanOverrideJson,
        fetchYear = RamadanOverrideChecker::fetchOverrideForYear,
        nanoTime = System::nanoTime,
        currentHijriYear = { HijrahDate.now().get(ChronoField.YEAR) },
        onRecord = RamadanOverrideChecker::useOfficialOverride,
        legacyOverride = { RamadanOverrideChecker.cachedOverride },
        manual = { ManualIslamicDateOverrides.all() },
    )

    val updates: StateFlow<Map<Int, RamadanOverride>> get() = store.updates
    fun cachedForYear(hijriYear: Int): RamadanOverride? = store.cachedForYear(hijriYear)
    fun importJson(json: String) = store.importJson(json)
    fun loadCachedDates() = store.loadCachedDates()
    fun loadCachedYear(hijriYear: Int) = store.loadCachedYear(hijriYear)
    fun loadYear(hijriYear: Int, refresh: Boolean = false) = store.loadYear(hijriYear, refresh)
    internal fun record(incoming: RamadanOverride): RamadanOverride = store.record(incoming)
    /** The calendar every Ramadan/Eid behavior follows: the admin's dates, then announcements, then estimates. */
    internal fun calendar(): TunisianHijriCalendar = store.calendar()

    /** Announcements and estimates without the admin's dates: what to poll for, and what a manual date overrides. */
    internal fun officialCalendar(): TunisianHijriCalendar = store.officialCalendar()
}

/** A separately owned cache lets the same production behavior run with isolated storage and IO. */
internal class OfficialIslamicDateStore(
    private val readSavedYear: (Int) -> String?,
    private val writeSavedYear: (Int, String) -> Unit,
    private val readLegacy: () -> String?,
    private val fetchYear: (Int) -> RamadanOverride?,
    private val nanoTime: () -> Long,
    private val currentHijriYear: () -> Int,
    private val onRecord: (RamadanOverride) -> Unit,
    private val legacyOverride: () -> RamadanOverride?,
    private val manual: () -> Map<Int, ManualIslamicDates>,
) {
    /** Without admin dates, as before they existed (tests inject the store through this signature). */
    constructor(
        readSavedYear: (Int) -> String?,
        writeSavedYear: (Int, String) -> Unit,
        readLegacy: () -> String?,
        fetchYear: (Int) -> RamadanOverride?,
        nanoTime: () -> Long,
        currentHijriYear: () -> Int,
        onRecord: (RamadanOverride) -> Unit,
        legacyOverride: () -> RamadanOverride?,
    ) : this(readSavedYear, writeSavedYear, readLegacy, fetchYear, nanoTime, currentHijriYear, onRecord, legacyOverride, { emptyMap() })

    private val lock = Any()
    private val records = MutableStateFlow<Map<Int, RamadanOverride>>(emptyMap())
    val updates: StateFlow<Map<Int, RamadanOverride>> = records.asStateFlow()
    private val loadedYears = mutableSetOf<Int>()
    private val inFlight = mutableSetOf<Int>()
    private val lastAttempt = mutableMapOf<Int, Long>()
    private val successfulAttempts = mutableSetOf<Int>()
    private var calendarRecords: Map<Int, RamadanOverride>? = null
    private var calendarManual: Map<Int, ManualIslamicDates>? = null
    private var resolvedCalendar: TunisianHijriCalendar? = null
    private var officialRecords: Map<Int, RamadanOverride>? = null
    private var resolvedOfficialCalendar: TunisianHijriCalendar? = null
    private val refreshInterval = TimeUnit.HOURS.toNanos(1)
    private val failedRetryInterval = TimeUnit.MINUTES.toNanos(1)
    private val minimumRequestInterval = TimeUnit.SECONDS.toNanos(5)

    fun cachedForYear(hijriYear: Int): RamadanOverride? = records.value[hijriYear]

    /** Import a packaged record without fetching. Newer dated records win over older caches. */
    fun importJson(json: String) {
        val parsed = RamadanOverrideChecker.parseOverride(json) ?: return
        // Capture the released app's record before publishing a bundle can replace it.
        loadCachedDates()
        loadCachedYear(parsed.hijriYear)
        record(parsed)
    }

    /** Migrate the released app's single-year preference and load today's year without a fetch. */
    fun loadCachedDates() {
        runCatching { readLegacy() }
            .getOrNull()?.let { json ->
                RamadanOverrideChecker.parseOverride(json)?.let { legacy ->
                    loadCachedYear(legacy.hijriYear)
                    record(legacy)
                }
            }
        loadCachedYear(currentHijriYear())
    }

    fun loadCachedYear(hijriYear: Int) {
        if (!isSupportedYear(hijriYear)) return
        synchronized(lock) {
            if (hijriYear in loadedYears) return
            // Keep loading and publication atomic: a bundled record must not race a saved one.
            val saved = runCatching { readSavedYear(hijriYear) }.getOrNull()
            saved?.let(RamadanOverrideChecker::parseOverride)
                ?.takeIf { it.hijriYear == hijriYear }?.let { record(it) }
            loadedYears += hijriYear
        }
    }

    /**
     * Call on an IO dispatcher. Failed years retry after a minute; successful years after an hour.
     * Resume/reconnect refreshes bypass those delays, retaining a short gap and in-flight deduplication.
     */
    fun loadYear(hijriYear: Int, refresh: Boolean = false) {
        if (!isSupportedYear(hijriYear)) return
        loadCachedYear(hijriYear)
        val now = nanoTime()
        synchronized(lock) {
            if (hijriYear in inFlight) return
            val interval = when {
                refresh -> minimumRequestInterval
                hijriYear in successfulAttempts -> refreshInterval
                else -> failedRetryInterval
            }
            if (lastAttempt[hijriYear]?.let { now - it < interval } == true) return
            inFlight += hijriYear
            lastAttempt[hijriYear] = now
        }
        var succeeded = false
        try {
            fetchYear(hijriYear)?.let { fetched ->
                record(fetched)
                succeeded = true
            }
        } catch (_: Exception) {
            // The already-visible estimate or offline announcement remains usable.
        } finally {
            synchronized(lock) {
                if (succeeded) successfulAttempts += hijriYear else successfulAttempts -= hijriYear
                inFlight -= hijriYear
            }
        }
    }

    /** Missing fields in an update must never erase an already announced event. */
    internal fun record(incoming: RamadanOverride): RamadanOverride = synchronized(lock) {
        if (!isSupportedYear(incoming.hijriYear)) return incoming
        val old = records.value[incoming.hijriYear]
        val ramadan = mergeDate(old?.ramadanStart, old?.ramadanStartUpdated,
            incoming.ramadanStart, incoming.ramadanStartUpdated)
        val fitr = mergeDate(old?.eidFitrDate, old?.eidFitrUpdated,
            incoming.eidFitrDate, incoming.eidFitrUpdated)
        val adha = mergeDate(old?.eidAdhaDate, old?.eidAdhaUpdated,
            incoming.eidAdhaDate, incoming.eidAdhaUpdated)
        val merged = RamadanOverride(
            hijriYear = incoming.hijriYear,
            ramadanStart = ramadan.first,
            eidFitrDate = fitr.first,
            eidAdhaDate = adha.first,
            lastUpdated = if (preferIncoming(old?.lastUpdated, incoming.lastUpdated))
                incoming.lastUpdated ?: old?.lastUpdated else old?.lastUpdated,
            ramadanStartUpdated = ramadan.second,
            eidFitrUpdated = fitr.second,
            eidAdhaUpdated = adha.second,
        )
        if (old != merged) {
            records.value = records.value + (merged.hijriYear to merged)
            runCatching {
                writeSavedYear(merged.hijriYear, RamadanOverrideChecker.toJson(merged))
            }
        }
        // Synchronize legacy readers while still holding the publication lock.
        onRecord(merged)
        merged
    }

    /** Retaining a missing event also retains its own freshness, including unknown provenance. */
    private fun mergeDate(
        oldDate: LocalDate?,
        oldUpdated: String?,
        newDate: LocalDate?,
        newUpdated: String?,
    ): Pair<LocalDate?, String?> = when {
        newDate == null -> oldDate to oldUpdated.takeIf { oldDate != null }
        oldDate == null || preferIncoming(oldUpdated, newUpdated) -> newDate to newUpdated
        else -> oldDate to oldUpdated
    }

    private fun preferIncoming(oldUpdated: String?, newUpdated: String?): Boolean {
        val oldInstant = oldUpdated?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val newInstant = newUpdated?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return oldInstant == null || (newInstant != null && !newInstant.isBefore(oldInstant))
    }

    /** Same resolver used by event behavior and Android presentation; cache immutable snapshots. */
    internal fun calendar(): TunisianHijriCalendar = synchronized(lock) {
        val snapshot = officialSnapshot()
        val manualSnapshot = runCatching { manual() }.getOrDefault(emptyMap())
        if (calendarRecords != snapshot || calendarManual != manualSnapshot || resolvedCalendar == null) {
            resolvedCalendar = TunisianHijriCalendar(withManualDates(snapshot, manualSnapshot))
            calendarRecords = snapshot
            calendarManual = manualSnapshot
        }
        resolvedCalendar!!
    }

    internal fun officialCalendar(): TunisianHijriCalendar = synchronized(lock) {
        val snapshot = officialSnapshot()
        if (officialRecords != snapshot || resolvedOfficialCalendar == null) {
            resolvedOfficialCalendar = TunisianHijriCalendar(snapshot)
            officialRecords = snapshot
        }
        resolvedOfficialCalendar!!
    }

    private fun officialSnapshot(): Map<Int, RamadanOverride> {
        val legacyOverride = legacyOverride()
        return if (legacyOverride == null || records.value[legacyOverride.hijriYear] == legacyOverride) records.value
            else records.value + (legacyOverride.hijriYear to legacyOverride)
    }

    private fun isSupportedYear(year: Int): Boolean =
        HijrahChronology.INSTANCE.range(ChronoField.YEAR).isValidValue(year.toLong())
}
