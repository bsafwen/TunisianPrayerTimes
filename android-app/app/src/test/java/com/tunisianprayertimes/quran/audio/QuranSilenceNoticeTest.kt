package com.tunisianprayertimes.quran.audio

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.ui.PhoneSilenceReason
import com.tunisianprayertimes.ui.PhoneSilenceReasonSource
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuranSilenceNoticeTest {
    private val sevenOhSeven = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 7)
        set(Calendar.MINUTE, 7)
    }.timeInMillis

    private fun notice(
        source: PhoneSilenceReasonSource,
        endsAt: Long? = sevenOhSeven,
        alarms: Int = 0,
        ownSilenceAfter: Boolean = false,
    ) = quranSilenceNotice(
        PhoneSilenceReason(source, prayer = Prayer.FAJR.takeIf { source == PhoneSilenceReasonSource.AUTO_PRAYER || source == PhoneSilenceReasonSource.MANUAL_UNTIL_PRAYER }),
        endsAt, alarms, ownSilenceAfter,
    ) { "الفجر" }

    @Test
    fun prayerSilenceGivesItsEnd() {
        assertTrue(notice(PhoneSilenceReasonSource.AUTO_PRAYER).action.contains("07:07"))
        assertTrue(notice(PhoneSilenceReasonSource.MANUAL_UNTIL_TIME).action.contains("07:07"))
    }

    /** [alarms] counts only the alarms that take the silence over: for a timed manual one, those ringing after its end. */
    @Test
    fun anAlarmStillToRingReplacesTheEnd() {
        for (source in listOf(PhoneSilenceReasonSource.AUTO_PRAYER, PhoneSilenceReasonSource.MANUAL_UNTIL_TIME, PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED)) {
            val withAlarm = notice(source, alarms = 1)
            assertFalse(withAlarm.action.contains("07:07"))
            assertNotEquals(notice(source, endsAt = null).action, withAlarm.action)
        }
    }

    @Test
    fun stoppingTheirOwnSilenceWithAnAlarmPendingIsStillAsked() {
        for (source in listOf(PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED, PhoneSilenceReasonSource.MANUAL_UNTIL_PRAYER)) {
            val stop = notice(source, endsAt = null).action.substringBefore(" ")
            assertTrue(notice(source, alarms = 1).action.startsWith(stop))
        }
    }

    @Test
    fun aSilenceUntilAPrayerGivesNoTime() {
        assertFalse(notice(PhoneSilenceReasonSource.MANUAL_UNTIL_PRAYER).action.contains("07:07"))
        assertEquals(notice(PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED, endsAt = null), notice(PhoneSilenceReasonSource.MANUAL_UNTIL_PRAYER))
    }

    @Test
    fun theListenersOwnModeAfterChangesTheAdvice() {
        for (source in PhoneSilenceReasonSource.entries - PhoneSilenceReasonSource.EXTERNAL) {
            assertNotEquals(source.name, notice(source).action, notice(source, ownSilenceAfter = true).action)
        }
    }

    @Test
    fun theirOwnSilenceIsNamedByItsSetting() {
        assertEquals(QuranSilenceNotice.DoNotDisturb, notice(PhoneSilenceReasonSource.EXTERNAL))
        assertNotEquals(QuranSilenceNotice.DoNotDisturb.title, notice(PhoneSilenceReasonSource.AUTO_PRAYER).title)
    }

    @Test
    fun severalAlarmsAreNotOne() {
        assertNotEquals(notice(PhoneSilenceReasonSource.WAKE_ALARM, alarms = 1), notice(PhoneSilenceReasonSource.WAKE_ALARM, alarms = 2))
        assertNotEquals(notice(PhoneSilenceReasonSource.AUTO_PRAYER, alarms = 1).action, notice(PhoneSilenceReasonSource.AUTO_PRAYER, alarms = 2).action)
    }
}
