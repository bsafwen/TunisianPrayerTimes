package com.tunisianprayertimes.tv.ui.kiosk

import com.tunisianprayertimes.time.TunisTime
import com.tunisianprayertimes.tv.kiosk.AutoStart
import com.tunisianprayertimes.tv.kiosk.AutoStartTier
import com.tunisianprayertimes.tv.kiosk.KioskReport
import com.tunisianprayertimes.tv.kiosk.PowerLevel
import com.tunisianprayertimes.tv.kiosk.PowerStatus
import com.tunisianprayertimes.tv.kiosk.SleepGap
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskRowsTest {

    private fun tunis(text: String) = LocalDateTime.parse(text).atZone(TunisTime.ZONE).toInstant().toEpochMilli()

    @Test
    fun aSleepIsWrittenInTunisiaTimeAndInWords() {
        val gap = SleepGap(tunis("2026-09-29T03:00"), tunis("2026-09-29T04:00"), 3_600_000L)
        assertEquals("نام الجهاز يوم 29 سبتمبر 2026 من 03:00 إلى 04:00 (ساعة)", sleepText(gap, TunisTime.ZONE))
    }

    @Test
    fun aSleepOverMidnightNamesBothDays() {
        val gap = SleepGap(tunis("2026-09-28T23:10"), tunis("2026-09-29T03:10"), 4 * 3_600_000L)
        assertEquals(
            "نام الجهاز من 28 سبتمبر 2026 23:10 إلى 29 سبتمبر 2026 03:10 (4 ساعات)",
            sleepText(gap, TunisTime.ZONE),
        )
    }

    private fun report(tier: AutoStartTier, fireTv: Boolean, quickStartAvailable: Boolean) = KioskReport(
        packageName = "app.tv",
        versionName = "1.0",
        autoStart = AutoStart(tier, fireTv),
        canDrawOverlays = false,
        homeModeEnabled = false,
        isDefaultHome = false,
        isDeviceOwner = false,
        quickStartAvailable = quickStartAvailable,
        power = PowerStatus(PowerLevel.OK, null, PowerLevel.OK),
        safeMode = false,
        uptimeMillis = 0L,
        lastAutoStart = null,
        lastCrash = null,
        sleepGaps = emptyList(),
        events = emptyList(),
    )

    @Test
    fun theGithubBuildGivesFireTvBothAdbCommandsAndOffersQuickStart() {
        val fireTv = healthRows(report(AutoStartTier.NONE, fireTv = true, quickStartAvailable = true)).first()
        assertEquals(
            "adb shell pm grant app.tv android.permission.WRITE_SECURE_SETTINGS\nadb shell appops set app.tv SYSTEM_ALERT_WINDOW allow",
            fireTv.command,
        )
        val overlay = healthRows(report(AutoStartTier.OVERLAY, fireTv = false, quickStartAvailable = true))
        assertTrue(overlay.any { it.text.startsWith("البدء السريع غير مفعّل") })
    }

    @Test
    fun thePlayBuildGivesOnlyTheOverlayCommandAndNeverMentionsQuickStartOn() {
        val fireTv = healthRows(report(AutoStartTier.NONE, fireTv = true, quickStartAvailable = false)).first()
        assertEquals("adb shell appops set app.tv SYSTEM_ALERT_WINDOW allow", fireTv.command)
        assertTrue(fireTv.fix!!.contains("نسخة GitHub"))
        val overlay = healthRows(report(AutoStartTier.OVERLAY, fireTv = false, quickStartAvailable = false))
        assertFalse(overlay.any { it.text.startsWith("البدء السريع غير مفعّل") })
    }
}
