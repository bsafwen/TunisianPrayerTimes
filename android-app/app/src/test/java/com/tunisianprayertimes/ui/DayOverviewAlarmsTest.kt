package com.tunisianprayertimes.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.place.PrayerPlace
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The day overview's rings are those ringing on the calendar day shown, whichever prayer day they belong to. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DayOverviewAlarmsTest {
    private lateinit var context: Context
    private val phoneZone: TimeZone = TimeZone.getDefault()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(phoneZone)
    }

    @Test
    fun anIshaAlarmRingingAfterMidnightShowsOnTheDayItRings() {
        // Tunis's summer Isha, about 21:40 there, is about 00:40 on a phone kept at Dubai's time, as an Isha after
        // midnight is in Paris in June with MWL: prayer day D − 1's Isha alarm rings early on D. It used to show on
        // no day: D − 1 filtered it out as tomorrow's, and D never looked at D − 1's prayer day.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dubai"))
        PrefsManager.setDelegationId(context, 615)
        val tunis = PrayerPlace.Inm(615)
        val phone = ZoneId.of("Asia/Dubai")
        fun isha(date: LocalDate) = PrayerTimesRepository.prayerDay(context, date, tunis)?.get(TimeEvent.ISHA)?.at
        val day = (2..30).map { LocalDate.of(2026, 6, it) }.first { date ->
            isha(date.minusDays(1))?.atZone(phone)?.toLocalDate() == date &&
                isha(date)?.atZone(phone)?.toLocalDate() == date.plusDays(1)
        }
        val alarm = PrayerWakeConfig(id = "isha", prayer = Prayer.ISHA, enabled = true)
        val calendar = Calendar.getInstance().apply {
            clear()
            set(day.year, day.monthValue - 1, day.dayOfMonth)
        }
        val rings = scheduledWakeAlarmTriggersForDay(context, PrefsManager.getDelegationId(context), alarm, calendar)
        assertEquals(listOf(requireNotNull(isha(day.minusDays(1))).toEpochMilli()), rings.map { it.triggerAtMillis })
    }
}
