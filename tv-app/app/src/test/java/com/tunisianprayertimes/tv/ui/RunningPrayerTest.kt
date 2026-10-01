package com.tunisianprayertimes.tv.ui

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.mosque.FlowPhase
import com.tunisianprayertimes.mosque.IqamahRule
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.PrayerEvent
import com.tunisianprayertimes.mosque.PrayerFlow
import com.tunisianprayertimes.mosque.PrayerSettings
import com.tunisianprayertimes.tv.ui.display.RunningPrayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RunningPrayerTest {

    private val day = LocalDate.of(2026, 9, 29)
    private fun at(hms: String) = LocalDateTime.of(day, LocalTime.parse(hms))

    /** Isha at 19:30: adhan screen 3 minutes, a 10-minute salah, 10 minutes of adhkar. */
    private fun isha(iqamahMinutes: Long, adhan: LocalDateTime = at("19:30")) = PrayerEvent(
        Prayer.ISHA, adhan, adhan.plusMinutes(3), adhan.plusMinutes(iqamahMinutes), adhan.plusMinutes(iqamahMinutes + 10),
        adhan.plusMinutes(iqamahMinutes + 20), iqamahAdjusted = false,
    )

    /** Runs the wall tick by tick, as the display does, carrying the pin from one to the next. */
    private class Wall {
        var pin: RunningPrayer.Pin? = null
        fun at(now: LocalDateTime, events: List<PrayerEvent>, plausible: Boolean = true): FlowPhase {
            val result = RunningPrayer.stateAt(now, events, pin, plausible)
            pin = result.pin
            return result.state.phase
        }
    }

    @Test
    fun aLongerIqamahSetAfterThePrayerDoesNotReplayIt() {
        val wall = Wall()
        assertEquals(FlowPhase.ADHAN, wall.at(at("19:31:00"), listOf(isha(10))))
        assertEquals(FlowPhase.SALAH, wall.at(at("19:41:00"), listOf(isha(10))))
        assertEquals(FlowPhase.AFTER_SALAH, wall.at(at("19:52:00"), listOf(isha(10))))
        // The imam asks for +15 from now on, at iqamah + 12: the adhkar go on.
        assertEquals(FlowPhase.AFTER_SALAH, wall.at(at("19:52:01"), listOf(isha(15))))
        assertEquals(FlowPhase.AFTER_SALAH, wall.at(at("19:54:00"), listOf(isha(15))))
        // +25 would have been a countdown after the prayer, then a second black screen.
        assertEquals(FlowPhase.AFTER_SALAH, wall.at(at("19:55:00"), listOf(isha(25))))
        // It ends when it began to end, and does not start again with the new times.
        assertEquals(FlowPhase.IDLE, wall.at(at("20:00:00"), listOf(isha(25))))
        assertEquals(FlowPhase.IDLE, wall.at(at("20:05:00"), listOf(isha(25))))
    }

    @Test
    fun theWaitCanStillBeChangedBeforeTheIqamah() {
        val wall = Wall()
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN, wall.at(at("19:35:00"), listOf(isha(10))))
        // Raised to 15 during the countdown: the countdown runs to the new iqamah.
        val raised = RunningPrayer.stateAt(at("19:36:00"), listOf(isha(15)), wall.pin)
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN, raised.state.phase)
        assertEquals(at("19:45:00"), raised.state.event!!.iqamahAt)
        wall.pin = raised.pin
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN, wall.at(at("19:42:00"), listOf(isha(15))))
        // Shortened to an iqamah already past: the countdown keeps the time it showed.
        val past = RunningPrayer.stateAt(at("19:43:00"), listOf(isha(10)), wall.pin)
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN, past.state.phase)
        assertEquals(at("19:45:00"), past.state.event!!.iqamahAt)
    }

    /** Isha at 19:30 as the flow makes it: the adhan screen 2 minutes, then the dua until 19:33. */
    private fun ishaWithDua(iqamahMinutes: Long) = PrayerFlow.eventsFor(
        day, times, MosqueSchedule.DEFAULT.with(Prayer.ISHA, PrayerSettings(IqamahRule.AfterAdhan(iqamahMinutes.toInt()), 10)),
    ).filter { it.prayer == Prayer.ISHA }

    private val times = com.tunisianprayertimes.DayPrayerTimes(
        day = day.dayOfMonth,
        fajr = PrayerTime(Prayer.FAJR, 4, 46), shurukHour = 6, shurukMinute = 12,
        dhuhr = PrayerTime(Prayer.DHUHR, 12, 17), asr = PrayerTime(Prayer.ASR, 15, 32),
        maghrib = PrayerTime(Prayer.MAGHRIB, 18, 8), isha = PrayerTime(Prayer.ISHA, 19, 30),
    )

    @Test
    fun theDuaFollowsTheAdhanAndTheWallNeverGoesBackToIt() {
        val wall = Wall()
        assertEquals(FlowPhase.ADHAN, wall.at(at("19:31:00"), ishaWithDua(10)))
        assertEquals(FlowPhase.ADHAN_DUA, wall.at(at("19:32:00"), ishaWithDua(10)))
        val dua = RunningPrayer.stateAt(at("19:32:30"), ishaWithDua(10), wall.pin)
        assertEquals(at("19:33:00"), dua.state.phaseEndsAt)
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN, wall.at(at("19:33:00"), ishaWithDua(10)))
        // The clock put back by a minute and a half: the countdown stays, the replies and the dua do not come back.
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN, wall.at(at("19:31:30"), ishaWithDua(10)))
        // Put back into the dua from the dua itself: it stays the dua, with its own end.
        val back = Wall()
        assertEquals(FlowPhase.ADHAN_DUA, back.at(at("19:32:40"), ishaWithDua(10)))
        val replay = RunningPrayer.stateAt(at("19:31:50"), ishaWithDua(10), back.pin)
        assertEquals(FlowPhase.ADHAN_DUA, replay.state.phase)
        assertEquals(at("19:33:00"), replay.state.phaseEndsAt)
    }

    @Test
    fun theIqamahCanStillBeChangedDuringTheDua() {
        val wall = Wall()
        assertEquals(FlowPhase.ADHAN_DUA, wall.at(at("19:32:10"), ishaWithDua(10)))
        // Raised to 15 during the dua: the countdown that follows it runs to the new iqamah.
        assertEquals(FlowPhase.ADHAN_DUA, wall.at(at("19:32:20"), ishaWithDua(15)))
        val after = RunningPrayer.stateAt(at("19:34:00"), ishaWithDua(15), wall.pin)
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN, after.state.phase)
        assertEquals(at("19:45:00"), after.state.event!!.iqamahAt)
        // Lowered to +1 during the dua: it waits for the dua's end, the black screen follows it.
        val short = Wall()
        assertEquals(FlowPhase.ADHAN_DUA, short.at(at("19:32:10"), ishaWithDua(10)))
        assertEquals(FlowPhase.ADHAN_DUA, short.at(at("19:32:20"), ishaWithDua(1)))
        assertEquals(FlowPhase.SALAH, short.at(at("19:33:00"), ishaWithDua(1)))
    }

    @Test
    fun aClockPutBackAFewMinutesNeverGoesBackAScreen() {
        val wall = Wall()
        assertEquals(FlowPhase.SALAH, wall.at(at("19:41:00"), listOf(isha(10))))
        assertEquals(FlowPhase.AFTER_SALAH, wall.at(at("19:51:00"), listOf(isha(10))))
        // Back three minutes, into the salah: the adhkar stay.
        val back = RunningPrayer.stateAt(at("19:48:00"), listOf(isha(10)), wall.pin)
        assertEquals(FlowPhase.AFTER_SALAH, back.state.phase)
        assertEquals(at("20:00:00"), back.state.phaseEndsAt)
        // Back before the adhan: a real correction, the flow follows the clock again.
        assertEquals(FlowPhase.IDLE, wall.at(at("19:00:00"), listOf(isha(10))))
        assertNull(wall.pin)
    }

    @Test
    fun theNextPrayerStartsAfresh() {
        val wall = Wall()
        assertEquals(FlowPhase.AFTER_SALAH, wall.at(at("19:52:00"), listOf(isha(10))))
        assertEquals(FlowPhase.IDLE, wall.at(at("20:10:00"), listOf(isha(10))))
        // Tomorrow's Fajr (as a later prayer here) is not held by the finished Isha.
        val fajr = isha(10, at("23:00")).copy(prayer = Prayer.FAJR)
        assertEquals(FlowPhase.ADHAN, wall.at(at("23:01:00"), listOf(isha(10), fajr)))
        assertEquals(Prayer.FAJR, wall.pin!!.event.prayer)
    }

    @Test
    fun anImpossibleClockRunsNoPrayer() {
        val wall = Wall()
        assertEquals(FlowPhase.IDLE, wall.at(at("19:41:00"), listOf(isha(10)), plausible = false))
        assertNull(wall.pin)
        assertEquals(FlowPhase.SALAH, wall.at(at("19:41:00"), listOf(isha(10))))
    }

    /** Friday: Jumu'a adhan 12:20, a 2-minute adhan screen, the khutba screen until the iqamah. */
    private fun jomoaa(iqamah: String) = at(iqamah).let { iq ->
        PrayerEvent(
            Prayer.JOMOAA, at("12:20"), at("12:22"), iq, iq.plusMinutes(15), iq.plusMinutes(25),
            iqamahAdjusted = false, khutbaAt = at("12:22"),
        )
    }

    @Test
    fun aJumuaIqamahRaisedDuringTheKhutbaApplies() {
        val wall = Wall()
        assertEquals(FlowPhase.KHUTBA, wall.at(at("12:30:00"), listOf(jomoaa("13:15"))))
        // The imam runs late: the iqamah goes to 13:25 at 13:05, the khutba screen runs on to it.
        val raised = RunningPrayer.stateAt(at("13:05:00"), listOf(jomoaa("13:25")), wall.pin)
        assertEquals(FlowPhase.KHUTBA, raised.state.phase)
        assertEquals(at("13:25:00"), raised.state.phaseEndsAt)
        wall.pin = raised.pin
        assertEquals(FlowPhase.KHUTBA, wall.at(at("13:15:00"), listOf(jomoaa("13:25"))))
        assertEquals(FlowPhase.SALAH, wall.at(at("13:30:00"), listOf(jomoaa("13:25"))))
    }

    @Test
    fun aPrayerTheSettingsNoLongerHaveEndsAtOnce() {
        val wall = Wall()
        val eid = isha(30, at("06:20")).copy(prayer = Prayer.AID_FITR)
        val fajr = isha(20, at("04:40")).copy(prayer = Prayer.FAJR)
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN, wall.at(at("06:30:00"), listOf(fajr, eid)))
        // Times still loading: the Eid prayer stays.
        assertEquals(FlowPhase.IQAMAH_COUNTDOWN, wall.at(at("06:40:00"), emptyList()))
        // The Eid date is moved away: today is an ordinary morning again.
        assertEquals(FlowPhase.IDLE, wall.at(at("06:50:00"), listOf(fajr)))
        assertEquals(FlowPhase.IDLE, wall.at(at("06:51:00"), listOf(fajr)))
    }
}
