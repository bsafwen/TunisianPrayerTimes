package com.tunisianprayertimes.tv.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BootIdTest {

    @Test
    fun theKernelsBootIdComesBeforeTheBootCount() {
        // A restart of the system alone moves the count, not the kernel's id: the id is the boot.
        assertEquals("5e2d8a4c-0b7e-4c1f-9a51-3f6d2b8e7c10", BootId.of("5e2d8a4c-0b7e-4c1f-9a51-3f6d2b8e7c10\n", 12))
        // Unreadable (an older system): the count.
        assertEquals("count 12", BootId.of(null, 12))
        assertEquals("count 12", BootId.of(" \n", 12))
        assertNull(BootId.of(null, -1))
    }
}
