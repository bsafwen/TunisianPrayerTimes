package com.tunisianprayertimes.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Prayer times are computed from the formula; CSV tables expire and must not come back. */
class NoCsvPrayerTablesTest {

    @Test
    fun tvAssetsContainNoCsvFiles() {
        val csv = TestData.tvAssets.walkTopDown()
            .filter { it.isFile && it.extension.equals("csv", ignoreCase = true) }
            .map { it.relativeTo(TestData.tvAssets).path }
            .toList()
        assertEquals(emptyList<String>(), csv)
    }
}
