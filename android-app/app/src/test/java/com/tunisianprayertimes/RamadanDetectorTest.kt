package com.tunisianprayertimes

import org.junit.Assert.*
import org.junit.Test

class RamadanDetectorTest {

    @Test
    fun isRamadan_returnsBoolean() {
        // Just verify it doesn't crash and returns a boolean
        val result = RamadanDetector.isRamadan()
        assertNotNull(result)
    }
}
