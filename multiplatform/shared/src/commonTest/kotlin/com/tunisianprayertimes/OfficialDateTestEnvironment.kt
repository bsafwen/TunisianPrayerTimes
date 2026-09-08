package com.tunisianprayertimes

import java.time.LocalDate

/** Run public app consumers against an isolated, entirely offline official-date store. */
internal class OfficialDateTestEnvironment {
    private val previousStore = OfficialIslamicDates.store
    private val previousOverride = RamadanOverrideChecker.cachedOverride
    private val previousDate = RamadanOverrideChecker.testDateOverride
    val savedYears = mutableMapOf<Int, String>()
    var legacyJson: String? = null

    init {
        RamadanOverrideChecker.cachedOverride = null
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 19)
        restart()
    }

    fun restart() {
        RamadanOverrideChecker.cachedOverride = null
        OfficialIslamicDates.store = OfficialIslamicDateStore(
            readSavedYear = { savedYears[it] },
            writeSavedYear = { year, json -> savedYears[year] = json },
            readLegacy = { legacyJson },
            fetchYear = { throw AssertionError("This test must not fetch from the network") },
            nanoTime = { 0L },
            currentHijriYear = { 1447 },
            onRecord = { RamadanOverrideChecker.cachedOverride = it },
            legacyOverride = { RamadanOverrideChecker.cachedOverride },
        )
    }

    fun close() {
        OfficialIslamicDates.store = previousStore
        RamadanOverrideChecker.cachedOverride = previousOverride
        RamadanOverrideChecker.testDateOverride = previousDate
    }
}
