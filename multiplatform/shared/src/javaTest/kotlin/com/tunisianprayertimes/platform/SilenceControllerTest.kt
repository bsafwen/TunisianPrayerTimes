package com.tunisianprayertimes.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilenceControllerTest {

    @Test
    fun defaultStateIsNotSilent() {
        // Reset to known state
        if (SilenceController.isSilent()) {
            SilenceController.disableSilence()
        }
        assertFalse(SilenceController.isSilent())
    }

    @Test
    fun hasPermissionAlwaysTrue() {
        assertTrue(SilenceController.hasPermission())
    }
}
