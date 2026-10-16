package com.tunisianprayertimes.engine

import java.time.zone.ZoneRulesProvider
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * The time-zone tests need tzdata 2026c or newer, which carries Morocco's rules of 2026-09-20 (plan 9.1).
 * CI sets REQUIRE_TZDATA, and this test then fails on an older JDK; locally it is skipped with the same
 * message, since a developer's JDK can lag behind IANA.
 */
class TzdataGuardTest {
    @Test
    fun jdkTimeZoneRulesAreRecentEnough() {
        val version = ZoneRulesProvider.getVersions("UTC").lastKey()
        val required = System.getenv("REQUIRE_TZDATA")
        val message = "The JDK at ${System.getProperty("java.home")} has tzdata $version; the time-zone tests need " +
            "${required ?: MINIMUM} or newer. Use a newer JDK build, or update its tzdb.dat."
        if (required == null) {
            assumeTrue(message, atLeast(version, MINIMUM))
        } else {
            assertTrue(atLeast(version, required), message)
        }
    }

    companion object {
        const val MINIMUM = "2026c"

        /** IANA versions are a year and a letter: "2025b" < "2026c". */
        fun atLeast(version: String, minimum: String): Boolean = version >= minimum
    }
}
