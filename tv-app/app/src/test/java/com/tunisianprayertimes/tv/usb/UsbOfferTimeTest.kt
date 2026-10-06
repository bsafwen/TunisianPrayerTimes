package com.tunisianprayertimes.tv.usb

import com.tunisianprayertimes.tv.ui.usb.UsbOfferTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class UsbOfferTimeTest {

    @Test
    fun anOfferOnTheWallWaitsForTheAdminAfterTwoMinutes() {
        assertFalse(UsbOfferTime.lapsed(Duration.ofMinutes(2), hidden = false))
        assertTrue(UsbOfferTime.lapsed(Duration.ofMinutes(2).plusSeconds(1), hidden = false))
    }

    @Test
    fun anOfferUnderAPrayerScreenDoesNotLapse() {
        // Plugged in at the adhan: the adhan, the khutba and the salah cover it for far longer than two minutes.
        assertFalse(UsbOfferTime.lapsed(Duration.ofMinutes(40), hidden = true))
    }
}
