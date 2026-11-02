package com.tunisianprayertimes.wake

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.engine.EventStatus
import com.tunisianprayertimes.engine.EventTime
import com.tunisianprayertimes.engine.PrayerDay
import com.tunisianprayertimes.engine.TimeEvent
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeMotivationTest {
    private val zone = ZoneId.of("Africa/Tunis")
    private val date = LocalDate.of(2026, 11, 1)
    private val days = (-1L..1L).map { day(date.plusDays(it)) }

    private fun at(time: String, date: LocalDate = this.date, zone: ZoneId = this.zone): Instant =
        date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    private fun day(date: LocalDate, zone: ZoneId = this.zone): PrayerDay {
        val times = listOf("05:00", "06:30", "12:00", "15:00", "17:30", "19:00")
        return PrayerDay(date, zone, "fixture", TimeEvent.entries.mapIndexed { index, event ->
            EventTime(event, at(times[index], date, zone), EventStatus.COMPUTED)
        })
    }

    private fun topics(time: String, prayer: Prayer? = null, title: String = "") =
        wakeMotivationTopics(at(time), prayer, title, days)

    @Test
    fun catalogueContainsExactlyTheRetainedSelectionAndApprovedAdditions() {
        val retained = setOf(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "15", "17", "18",
            "19", "20", "21", "22", "28", "29", "30", "31", "32", "33", "34", "35", "36", "37",
            "39", "42", "46", "47", "49", "A1", "B2",
        )
        val expected = retained + (1..12).map { "H$it" }
        assertEquals(48, WakeMotivations.all.size)
        assertEquals(expected, WakeMotivations.all.map { it.reviewId }.toSet())
        assertEquals(48, WakeMotivations.all.map { it.text }.toSet().size)
        WakeMotivations.all.forEach { message ->
            assertTrue("Complete attribution for ${message.reviewId}", message.source.isNotBlank())
            val uri = URI(message.sourceUrl)
            assertEquals("https", uri.scheme)
            assertTrue(uri.host.isNotBlank())
        }
    }

    @Test
    fun generalAlarmsNeverSelectAPurposeSpecificMessage() {
        val generic = setOf(WakeMotivationTopic.GENERAL)
        repeat(366) { day ->
            val message = WakeMotivations.select(generic, "ordinary-alarm", day.toLong())
            assertEquals(setOf(WakeMotivationTopic.GENERAL), message.topics)
        }
    }

    @Test
    fun dailyRotationCoversTheWholeEligiblePoolAndDoesNotRepeatAdjacentDays() {
        val contexts = listOf(
            emptySet(), setOf(WakeMotivationTopic.PRAYER),
            setOf(WakeMotivationTopic.PRAYER, WakeMotivationTopic.FAJR, WakeMotivationTopic.FAJR_SUNNAH),
            setOf(WakeMotivationTopic.NIGHT_PRAYER, WakeMotivationTopic.PRE_DAWN),
            setOf(WakeMotivationTopic.QURAN_READING),
        )
        contexts.forEach { topics ->
            val pool = WakeMotivations.eligible(topics)
            val selected = (0 until pool.size).map { WakeMotivations.select(topics, "alarm-💚", it.toLong() - 100) }
            assertEquals(pool.toSet(), selected.toSet())
            selected.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
            assertEquals(selected.first(), WakeMotivations.select(topics, "alarm-💚", -100L))
        }
    }

    @Test
    fun rotatingBeforeTheEpochOrWithTheMinimumIntHashStillSelectsAMessage() {
        val id = "polygenelubricants"
        assertEquals(Int.MIN_VALUE, id.hashCode())
        assertTrue(WakeMotivations.select(emptySet(), id, -10_000L) in WakeMotivations.all)
    }

    @Test
    fun morningTextsUseSolarTimesAndStopBeforeNoon() {
        assertFalse(WakeMotivationTopic.MORNING in topics("04:30"))
        assertTrue(WakeMotivationTopic.MORNING in topics("05:00"))
        assertTrue(WakeMotivationTopic.MORNING in topics("08:29"))
        assertFalse(WakeMotivationTopic.MORNING in topics("08:30"))
        assertFalse(WakeMotivationTopic.MORNING in topics("18:00"))
    }

    @Test
    fun fajrSunnahIsOnlySelectedAfterFajrAndBeforeSunriseForAFajrAlarm() {
        assertTrue(WakeMotivationTopic.FAJR in topics("04:30", Prayer.FAJR))
        assertFalse(WakeMotivationTopic.FAJR_SUNNAH in topics("04:30", Prayer.FAJR))
        assertTrue(WakeMotivationTopic.FAJR_SUNNAH in topics("05:00", Prayer.FAJR))
        assertTrue(WakeMotivationTopic.FAJR_SUNNAH in topics("06:29", Prayer.FAJR))
        assertFalse(WakeMotivationTopic.FAJR in topics("06:30", Prayer.FAJR))
        assertFalse(WakeMotivationTopic.FAJR_SUNNAH in topics("05:30"))
        assertFalse(WakeMotivationTopic.FAJR in topics("15:00", Prayer.FAJR))
    }

    @Test
    fun anAsrAlarmDoesNotUseFajrTextAndStopsUsingAsrTextAtSunset() {
        assertTrue(WakeMotivationTopic.ASR in topics("14:30", Prayer.ASR))
        assertFalse(WakeMotivationTopic.FAJR in topics("14:30", Prayer.ASR))
        assertFalse(WakeMotivationTopic.ASR in topics("17:30", Prayer.ASR))
    }

    @Test
    fun beingAtNightOrLinkedToFajrDoesNotAssumeVoluntaryNightPrayer() {
        assertFalse(WakeMotivationTopic.NIGHT_PRAYER in topics("03:00"))
        assertFalse(WakeMotivationTopic.NIGHT_PRAYER in topics("04:00", Prayer.FAJR))
        assertFalse(WakeMotivationTopic.NIGHT_PRAYER in topics("03:00", title = "qiyamah reminder"))
    }

    @Test
    fun anExplicitNightPrayerPurposeIsLimitedToIshaUntilFajr() {
        assertFalse(WakeMotivationTopic.NIGHT_PRAYER in topics("18:59", title = "قيام الليل"))
        assertTrue(WakeMotivationTopic.NIGHT_PRAYER in topics("19:00", title = "قيام الليل"))
        assertTrue(WakeMotivationTopic.NIGHT_PRAYER in topics("03:00", title = "منبه التَّهَجُّد"))
        assertFalse(WakeMotivationTopic.NIGHT_PRAYER in topics("05:00", title = "قيام الليل"))
        assertFalse(WakeMotivationTopic.NIGHT_PRAYER in topics("15:00", title = "قيام الليل"))
    }

    @Test
    fun preDawnMeansTheLastThirdOfTheActualNightAndRequiresThePurpose() {
        assertFalse(WakeMotivationTopic.PRE_DAWN in topics("00:30", title = "قيام الليل"))
        assertTrue(WakeMotivationTopic.PRE_DAWN in topics("03:00", title = "قيام الليل"))
        assertFalse(WakeMotivationTopic.PRE_DAWN in topics("03:00"))
        assertFalse(WakeMotivationTopic.PRE_DAWN in topics("05:00", title = "قيام الليل"))
    }

    @Test
    fun quranReadingRequiresAnExplicitTitleAndHandlesArabicMarksAndHamza() {
        assertTrue(WakeMotivationTopic.QURAN_READING in topics("15:00", title = "منبه قِراءة القُرْآنِ"))
        assertTrue(WakeMotivationTopic.QURAN_READING in topics("15:00", title = "ورد القران"))
        assertTrue(WakeMotivationTopic.QURAN_READING in topics("15:00", title = "QURAN READING"))
        assertFalse(WakeMotivationTopic.QURAN_READING in topics("15:00", title = "قراءة كتاب"))
        assertFalse(WakeMotivationTopic.QURAN_READING in topics("15:00"))
    }

    @Test
    fun missingTimesCannotInventMorningOrNightWindows() {
        val missing = listOf(PrayerDay(date, zone, "missing", TimeEvent.entries.map {
            EventTime(it, null, EventStatus.ABSENT)
        }))
        assertEquals(
            setOf(WakeMotivationTopic.GENERAL, WakeMotivationTopic.PRAYER),
            wakeMotivationTopics(at("04:00"), Prayer.FAJR, "قيام الليل", missing),
        )
    }

    @Test
    fun prayerInstantsRemainCorrectWhenTheSelectedPlaceIsInAnotherZone() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val tokyoDays = (-1L..1L).map { day(date.plusDays(it), tokyo) }
        val localMorning = at("05:30", zone = tokyo)
        assertTrue(WakeMotivationTopic.MORNING in wakeMotivationTopics(localMorning, null, "", tokyoDays))
        assertFalse(WakeMotivationTopic.MORNING in wakeMotivationTopics(localMorning, null, "", days))
    }

    @Test
    fun anIshaAfterCivilMidnightDoesNotStartNightWorshipEarlier() {
        val shifted = days.map { day ->
            day.copy(events = day.events.map { event ->
                if (event.event == TimeEvent.ISHA) event.copy(at = at("00:30", day.date.plusDays(1))) else event
            })
        }
        assertFalse(WakeMotivationTopic.NIGHT_PRAYER in wakeMotivationTopics(at("23:00"), null, "قيام الليل", shifted))
        assertTrue(WakeMotivationTopic.NIGHT_PRAYER in wakeMotivationTopics(at("00:45"), null, "قيام الليل", shifted))
    }
}
