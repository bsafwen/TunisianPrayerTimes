package com.tunisianprayertimes.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.ALL_WAKE_SCHEDULE_DAYS
import com.tunisianprayertimes.WakeScheduleDay
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class WakeScheduleDayTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun allDaysSummaryUsesEveryDayText() {
        assertEquals(
            "كل يوم",
            formatWakeScheduleDaysSummary(context, ALL_WAKE_SCHEDULE_DAYS),
        )
    }

    @Test
    fun selectedDaysSummaryUsesFullDayNames() {
        assertEquals(
            "الإثنين، الخميس",
            formatWakeScheduleDaysSummary(
                context = context,
                days = setOf(WakeScheduleDay.THURSDAY, WakeScheduleDay.MONDAY),
            ),
        )
    }
}