package com.tunisianprayertimes.tv.ui

import com.tunisianprayertimes.tv.kiosk.AutoStart
import com.tunisianprayertimes.tv.kiosk.AutoStartTier
import com.tunisianprayertimes.tv.kiosk.KioskReport
import com.tunisianprayertimes.tv.kiosk.PowerLevel
import com.tunisianprayertimes.tv.kiosk.PowerStatus
import com.tunisianprayertimes.tv.ui.kiosk.HealthLevel
import com.tunisianprayertimes.tv.ui.kiosk.healthRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskHealthRowsTest {

    private fun report(otherBuildInstalled: Boolean) = KioskReport(
        packageName = "com.tunisianprayertimes.tv",
        versionName = "1.0",
        autoStart = AutoStart(AutoStartTier.OVERLAY),
        canDrawOverlays = true,
        homeModeEnabled = false,
        isDefaultHome = false,
        isDeviceOwner = false,
        otherBuildInstalled = otherBuildInstalled,
        power = PowerStatus(PowerLevel.OK, null, PowerLevel.OK),
        safeMode = false,
        uptimeMillis = 0,
        lastAutoStart = null,
        lastCrash = null,
        sleepGaps = emptyList(),
        events = emptyList(),
    )

    @Test
    fun bothBuildsOnOneBoxIsAProblemToFix() {
        val row = healthRows(report(otherBuildInstalled = true)).single { it.text == TvStrings.OTHER_BUILD_INSTALLED }
        assertEquals(HealthLevel.BAD, row.level)
        assertEquals(TvStrings.OTHER_BUILD_FIX, row.fix)
        assertTrue(healthRows(report(otherBuildInstalled = false)).none { it.text == TvStrings.OTHER_BUILD_INSTALLED })
    }
}
