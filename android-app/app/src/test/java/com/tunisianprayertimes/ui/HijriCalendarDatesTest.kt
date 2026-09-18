package com.tunisianprayertimes.ui

import com.tunisianprayertimes.HijriCalendarDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.util.TimeZone

class HijriCalendarDatesTest {
    @Test
    fun localDateUsesTheDeviceCivilDayOnBothSidesOfUtcMidnight() {
        inZone("Africa/Tunis") {
            assertEquals(LocalDate.of(2026, 3, 20), calendarLocalDate(Instant.parse("2026-03-19T23:30:00Z").toEpochMilli()))
        }
        inZone("America/Los_Angeles") {
            assertEquals(LocalDate.of(2026, 3, 19), calendarLocalDate(Instant.parse("2026-03-20T01:30:00Z").toEpochMilli()))
        }
    }

    @Test
    fun selectedDatesRoundTripAcrossOffsetsAndDaylightSavingChanges() {
        val dates = listOf("2026-01-01", "2026-03-08", "2026-03-29", "2026-10-25", "2026-11-01", "2026-12-31")
        for (zone in listOf("Africa/Tunis", "Europe/Paris", "America/New_York", "Pacific/Kiritimati", "Pacific/Pago_Pago")) {
            inZone(zone) {
                dates.map(LocalDate::parse).forEach { date ->
                    assertEquals("$zone: $date", date, calendarLocalDate(calendarDateMillis(date)))
                }
            }
        }
    }

    @Test
    fun midnightConversionRespectsShortAndLongDays() {
        inZone("Europe/Paris") {
            for ((dateText, expectedHours) in listOf("2026-03-29" to 23L, "2026-10-25" to 25L)) {
                val date = LocalDate.parse(dateText)
                assertEquals(expectedHours * 3_600_000L, calendarDateMillis(date.plusDays(1)) - calendarDateMillis(date))
            }
        }
    }

    @Test
    fun correctionCanDisplayDayThirtyInAnAlgorithmicTwentyNineDayMonth() {
        val month = (1..12).first { HijrahDate.of(1447, it, 1).lengthOfMonth() == 29 }
        val label = hijriDateLabel(HijriCalendarDate(1447, month, 30, false))
        assertTrue(label.startsWith("30 "))
        assertTrue(label.endsWith("1447 هـ"))
    }

    @Test
    fun fourthHijriMonthUsesTunisianNaming() {
        assertEquals("ربيع الثاني 1447 هـ", hijriMonthLabel(1447, 4))
    }

    @Test
    fun dateLabelsDistinguishHijriAndSolarYearsUsingLatinDigits() {
        assertTrue(hijriDateLabel(HijriCalendarDate(1447, 9, 1, false)).endsWith("1447 هـ"))
        assertTrue(gregorianDateLabel(LocalDate.of(2026, 2, 19)).endsWith("2026 م"))
    }

    private fun inZone(id: String, body: () -> Unit) {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            body()
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
