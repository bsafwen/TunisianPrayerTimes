package com.tunisianprayertimes.bugreport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.DelegationLocationResult
import com.tunisianprayertimes.DelegationLocator
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.PrayerWakeSubAlarm
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.WakePlaybackOptions
import com.tunisianprayertimes.isInsideTunisiaBounds
import com.tunisianprayertimes.ui.EndpointRuleMode
import com.tunisianprayertimes.ui.PrayerTimelineAnchor
import com.tunisianprayertimes.ui.resolvePrayerTimelineWindow
import com.tunisianprayertimes.ui.soundMatches
import com.tunisianprayertimes.ui.withStartRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/** Logic bugs from BUG_REPORT.md: M5, M12, L5 and L12. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogicBugsTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.clearSilenceAndWakePrefs()
    }

    /** M5: on a Friday with a custom Jomoaa time, "until Dhuhr" ends at Dhuhr instead of the Friday prayer. */
    @Test
    fun M5_untilDhuhrOnFridayEndsAtTheCustomJomoaaTime() {
        PrefsManager.setJomoaaTime(context, 13, 15)
        val fridayMorning = calendarAt(2026, Calendar.OCTOBER, 16, 10, 0) // Friday
        assertEquals("Precondition: a Friday", Calendar.FRIDAY, fridayMorning.get(Calendar.DAY_OF_WEEK))
        val resolveEnd = Class.forName("com.tunisianprayertimes.ui.MainScreenKt").getDeclaredMethod(
            "resolveManualSilencePrayerEndMillis",
            Context::class.java, Int::class.javaPrimitiveType, Prayer::class.java, Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        val endMillis = resolveEnd.invoke(
            null, context, PrefsManager.getDelegationId(context), Prayer.DHUHR, fridayMorning.timeInMillis,
        ) as Long?

        assertEquals("Silence should last until the Friday prayer at 13:15",
            calendarAt(2026, Calendar.OCTOBER, 16, 13, 15).timeInMillis, endMillis)
    }

    /** M12: a GPS fix inside a border sector is reported as outside Tunisia. */
    @Test
    fun M12_fixInsideBorderSectorIsNotOutsideTunisia() {
        val lat = 33.305522
        val lng = 7.765197
        assertTrue("Precondition: the silence worker treats this point as inside Tunisia", isInsideTunisiaBounds(lat, lng))

        val result = DelegationLocator.resolveGpsLocation(context, lat, lng)

        assertTrue("A fix inside a delegation sector takes a delegation (got $result)",
            result is DelegationLocationResult.Success)
    }

    /** L5: switching a previous-day adhan-relative start to "Fixed" moves it 24 hours later. */
    @Test
    fun L5_fixingAPreviousDayStartKeepsItOnThePreviousDay() {
        val anchor = PrayerTimelineAnchor.demo(Prayer.FAJR, 6, 0)
        val config = PrayerSilenceConfig(delayMinutes = -420) // 7 hours before a 06:00 adhan
        val before = resolvePrayerTimelineWindow(anchor, config)
        assertEquals("Precondition: 23:00 on the previous day", -60, before.startMinutes)

        // D16: a fixed start takes its clock's occurrence nearest the adhan, the previous evening's.
        val fixed = config.withStartRule(anchor, EndpointRuleMode.FIXED_TIME, anchor.offsetAt(before.startMinutes), before.startMinutes)

        assertEquals(-60, resolvePrayerTimelineWindow(anchor, fixed).startMinutes)
    }

    /** L12: an extra alarm with a different progressive-volume setting is shown as "same as main sound". */
    @Test
    fun L12_progressiveVolumeDifferenceIsNotTheSameSound() {
        val main = WakePlaybackOptions(progressiveVolume = true)
        val extra = PrayerWakeSubAlarm("extra", 5, OffsetDirection.BEFORE, playback = main.copy(progressiveVolume = false))

        assertFalse(extra.soundMatches(main))
    }
}
