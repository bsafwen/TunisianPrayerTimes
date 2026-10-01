package com.tunisianprayertimes.tv.ui

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerFormulaSettings
import com.tunisianprayertimes.mosque.DisplayOptions
import com.tunisianprayertimes.mosque.IqamahMove
import com.tunisianprayertimes.mosque.IqamahRule
import com.tunisianprayertimes.mosque.MosqueProfile
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ContentChange
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ContentList
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.mosque.PrayerEvent
import com.tunisianprayertimes.mosque.PrayerSettings
import com.tunisianprayertimes.tv.data.TestData
import com.tunisianprayertimes.tv.ui.kiosk.HealthLevel
import com.tunisianprayertimes.tv.ui.kiosk.movedIqamahRows
import com.tunisianprayertimes.tv.ui.settings.everyText
import com.tunisianprayertimes.tv.ui.usb.SettingsChangeLines
import com.tunisianprayertimes.tv.ui.usb.copyNotice
import com.tunisianprayertimes.tv.ui.usb.readAgainNotice
import com.tunisianprayertimes.tv.ui.usb.rejectedLines
import com.tunisianprayertimes.tv.usb.RejectedFile
import com.tunisianprayertimes.tv.usb.UsbMediaCopy
import com.tunisianprayertimes.tv.usb.UsbScan
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsChangeLinesTest {

    private val today = LocalDate.of(2026, 10, 1)

    private fun lines(
        file: String,
        profile: MosqueProfile = MosqueProfile(),
        at: SettingsChangeLines.Today? = null,
        schedule: MosqueSchedule = MosqueSchedule.DEFAULT,
    ): List<String> = SettingsChangeLines.of(MosqueSettingsFile.parse(file, schedule, currentProfile = profile), at)

    private fun usualMinutes(prayer: Prayer) = (MosqueSchedule.DEFAULT.settings(prayer).iqamah as IqamahRule.AfterAdhan).minutes

    @Test
    fun valuesReadAsTheSettingsPagesSayThem() {
        val lines = lines("""{ "prayers": { "isha": { "iqamah": "+15", "duration": 12 }, "eidFitr": { "iqamah": "+45" }, "fajr": { "iqamah": "05:10" } },
            "ramadan": { "isha": { "duration": 75 } } }""")
        val isha = MosqueSettingsFile.arabicName(Prayer.ISHA)
        val usualIsha = MosqueSchedule.DEFAULT.settings(Prayer.ISHA)
        for (expected in listOf(
            "$isha · ${TvStrings.IQAMAH_LABEL}: ${TvStrings.iqamahAfterAdhan(usualMinutes(Prayer.ISHA))} ← ${TvStrings.iqamahAfterAdhan(15)}",
            "$isha · ${TvStrings.DURATION_LABEL}: ${TvStrings.minutesShort(usualIsha.salahMinutes)} ← ${TvStrings.minutesShort(12)}",
            // The Eid has no adhan: its minutes count from sunrise.
            "${MosqueSettingsFile.arabicName(Prayer.AID_FITR)} · ${TvStrings.IQAMAH_LABEL}: " +
                "${TvStrings.iqamahAfterSunrise(usualMinutes(Prayer.AID_FITR))} ← ${TvStrings.iqamahAfterSunrise(45)}",
            "${MosqueSettingsFile.arabicName(Prayer.FAJR)} · ${TvStrings.IQAMAH_LABEL}: " +
                "${TvStrings.iqamahAfterAdhan(usualMinutes(Prayer.FAJR))} ← ${TvStrings.atTime(5, 10)}",
            "$isha (${TvStrings.RAMADAN}) · ${TvStrings.DURATION_LABEL}: ${TvStrings.AS_USUAL} ← ${TvStrings.minutesShort(75)}",
        )) {
            assertTrue("$expected\n$lines", expected in lines)
        }
    }

    @Test
    fun displayOptionsReadAsTheSettingsPagesSayThem() {
        val tv = MosqueProfile(display = DisplayOptions(weather = true, slideSeconds = 15, announcementsEveryMinutes = 15))
        val lines = lines("""{ "display": { "weather": false, "slideSeconds": 20, "announcementsEveryMinutes": 0 } }""", tv)
        assertEquals(
            setOf(
                "${TvStrings.WEATHER_ENABLED}: ${TvStrings.ON} ← ${TvStrings.OFF}",
                "${TvStrings.ANNOUNCEMENT_INTERVAL}: ${TvStrings.secondsShort(15)} ← ${TvStrings.secondsShort(20)}",
                // 0 is not "every 0 minutes": the announcements come after the prayers only.
                "${TvStrings.ANNOUNCEMENTS_BETWEEN}: ${everyText(15)} ← ${TvStrings.ANNOUNCEMENTS_EVERY_OFF}",
            ),
            lines.toSet(),
        )
    }

    @Test
    fun theAdhanScreensMinutesReadAsTheIqamahPageSaysThem() {
        val tv = MosqueProfile(display = DisplayOptions(adhanScreenMinutes = 2))
        assertEquals(
            listOf("${TvStrings.ADHAN_SCREEN_LENGTH}: ${TvStrings.minutesShort(2)} ← ${TvStrings.minutesShort(4)}"),
            lines("""{ "display": { "adhanScreenMinutes": 4 } }""", tv),
        )
    }

    @Test
    fun prayerTimeValuesReadBeforeAndAfter() {
        val lines = lines("""{ "prayerTimes": { "fajrAngle": 16.5, "asrShadow": 2, "dhuhrMinutes": 5, "maghribMinutes": 3,
            "elevation": false, "adjust": { "isha": "+2", "fajr": -1 } } }""")
        val ltr = { text: String -> "${Char(0x2066)}$text${Char(0x2069)}" }
        assertEquals(
            listOf(
                // The degree sign and the signs stay by their numbers, left to right.
                "زاوية الفجر: ${ltr("18°")} ← ${ltr("16.5°")}",
                "ظلّ العصر: مثل واحد ← مثلان",
                "الظهر بعد الزوال: 7 د ← 5 د",
                "المغرب بعد الغروب: 2 د ← 3 د",
                "حساب ارتفاع المسجد: نعم ← لا",
                "تعديل الفجر: 0 د ← ${ltr("−1")} د",
                "تعديل العشاء: 0 د ← ${ltr("+2")} د",
            ),
            lines,
        )
        // Only what changes: the TV's own values are the "before".
        val tv = MosqueProfile(formula = PrayerFormulaSettings(ishaAngle = 17.0).withAdjustment(Prayer.ISHA, 2))
        assertEquals(
            listOf("${TvStrings.ISHA_ANGLE}: ${TvStrings.degrees("17")} ← ${TvStrings.degrees("18.5")}", "تعديل العشاء: ${ltr("+2")} د ← 0 د"),
            lines("""{ "prayerTimes": { "ishaAngle": 18.5, "adjust": { "isha": 0 } } }""", tv),
        )
    }

    @Test
    fun aReturnToTheOfficialTimesIsSaid() {
        val tv = MosqueProfile(formula = PrayerFormulaSettings(fajrAngle = 16.0))
        assertEquals(
            listOf("${TvStrings.FAJR_ANGLE}: ${TvStrings.degrees("16")} ← ${TvStrings.degrees("18")}", TvStrings.OFFICIAL_TIMES_BACK),
            lines("""{ "prayerTimes": null }""", tv),
        )
        // Custom values that stay custom are no return.
        assertFalse(TvStrings.OFFICIAL_TIMES_BACK in lines("""{ "prayerTimes": { "fajrAngle": 17 } }""", tv))
        // Already official: nothing to say of them.
        assertEquals(
            listOf("${TvStrings.WEATHER_ENABLED}: ${TvStrings.ON} ← ${TvStrings.OFF}"),
            lines("""{ "prayerTimes": null, "display": { "weather": false } }""", MosqueProfile(display = DisplayOptions(weather = true))),
        )
    }

    @Test
    fun newValuesSayHowTodaysTimesMoveAndJudgeTheIqamahByThem() {
        // Tunis on 2026-09-30: Fajr 04:47 with INM's 18°, 04:57 at 16°.
        val date = LocalDate.of(2026, 9, 30)
        val tv = MosqueProfile(delegationId = TestData.TUNIS)
        fun day(profile: MosqueProfile) = TestData.prayerTimes.loadDayPrayerTimes(profile.delegationId!!, 2026, 9, 30, profile.formulaSettings)
        val at = SettingsChangeLines.Today.of(date, day(tv), ::day)
        val file = """{ "prayerTimes": { "fajrAngle": 16 } }"""
        assertEquals(
            listOf("${TvStrings.FAJR_ANGLE}: ${TvStrings.degrees("18")} ← ${TvStrings.degrees("16")}", "${TvStrings.todayTime(TvStrings.FAJR)}: 04:47 ← 04:57"),
            lines(file, tv, at),
        )
        // Without a way to compute them, the values alone.
        assertEquals(1, lines(file, tv, SettingsChangeLines.Today.of(date, day(tv))).size)
        // Iqamah at 04:55: fine after today's 04:47, but before the 04:57 the file's values give.
        val iqamah = """{ "prayers": { "fajr": { "iqamah": "04:55" } } }"""
        assertFalse(lines(iqamah, tv, at).single().endsWith(")"))
        val both = lines("""{ "prayerTimes": { "fajrAngle": 16 }, "prayers": { "fajr": { "iqamah": "04:55" } } }""", tv, at)
        assertTrue(both.toString(), both.last().endsWith("(${TvStrings.notTodayAfterAdhan("04:57", TvStrings.iqamahAfterAdhan(usualMinutes(Prayer.FAJR)))})"))
    }

    @Test
    fun aFixedIqamahTodaysAdhanWouldNotUseIsNamed() {
        val at = SettingsChangeLines.Today(today, mapOf(Prayer.ISHA to LocalTime.of(19, 30), Prayer.AID_ADHA to LocalTime.of(6, 0)))
        // "08:00" meant as 20:00: the screen would count the usual minutes instead.
        val morning = lines("""{ "prayers": { "isha": { "iqamah": "08:00" } } }""", at = at).single()
        assertTrue(morning, morning.endsWith("(${TvStrings.notTodayAfterAdhan("19:30", TvStrings.iqamahAfterAdhan(usualMinutes(Prayer.ISHA)))})"))
        val eid = lines("""{ "prayers": { "eidAdha": { "iqamah": "09:00" } } }""", at = at).single()
        assertTrue(eid, eid.endsWith("(${TvStrings.notTodayAfterSunrise("06:00", TvStrings.iqamahAfterSunrise(usualMinutes(Prayer.AID_ADHA)))})"))
        // A mosque that calls Isha at +15 hears its own minutes, as the wall uses them.
        val own = MosqueSchedule.DEFAULT.with(Prayer.ISHA, PrayerSettings(IqamahRule.AfterAdhan(15), 10)).copy(delays = mapOf(Prayer.ISHA to 15))
        val mosque = lines("""{ "prayers": { "isha": { "iqamah": "08:00" } } }""", at = at, schedule = own).single()
        assertTrue(mosque, mosque.endsWith("(${TvStrings.notTodayAfterAdhan("19:30", TvStrings.iqamahAfterAdhan(15))})"))
        // A time that suits today, or no day to judge by: nothing to add.
        assertFalse(lines("""{ "prayers": { "isha": { "iqamah": "20:00" } } }""", at = at).single().endsWith(")"))
        assertFalse(lines("""{ "prayers": { "isha": { "iqamah": "08:00" } } }""").single().endsWith(")"))
    }

    @Test
    fun anIqamahBeforeTheEndOfTheAdhanScreenAndTheDuaSaysItWaits() {
        val at = SettingsChangeLines.Today(today, mapOf(Prayer.MAGHRIB to LocalTime.of(18, 8), Prayer.AID_FITR to LocalTime.of(6, 0)))
        val waits = "(${TvStrings.waitsForAdhanScreen("18:08", "18:11")})"
        // The 2-minute adhan screen by default, then the minute of the dua: "+1", "+2" or a time before 18:11 wait for 18:11.
        listOf("+1", "+2", "18:09", "18:10").forEach { iqamah ->
            val line = lines("""{ "prayers": { "maghrib": { "iqamah": "$iqamah" } } }""", at = at).single()
            assertTrue(line, line.endsWith(waits))
        }
        assertFalse(lines("""{ "prayers": { "maghrib": { "iqamah": "+3" } } }""", at = at).single().endsWith(")"))
        assertFalse(lines("""{ "prayers": { "maghrib": { "iqamah": "18:11" } } }""", at = at).single().endsWith(")"))
        // The file's own adhan screen counts: 4 minutes and the dua wait until 18:13; 1 minute until 18:10.
        val longer = lines("""{ "display": { "adhanScreenMinutes": 4 }, "prayers": { "maghrib": { "iqamah": "+3" } } }""", at = at)
        assertTrue(longer.toString(), longer.any { it.endsWith("(${TvStrings.waitsForAdhanScreen("18:08", "18:13")})") })
        val shorter = MosqueProfile(display = DisplayOptions(adhanScreenMinutes = 1))
        val plusOne = lines("""{ "prayers": { "maghrib": { "iqamah": "+1" } } }""", shorter, at).single()
        assertTrue(plusOne, plusOne.endsWith("(${TvStrings.waitsForAdhanScreen("18:08", "18:10")})"))
        assertFalse(lines("""{ "prayers": { "maghrib": { "iqamah": "+2" } } }""", shorter, at).single().endsWith(")"))
        // A stale fixed time falls back to the mosque's minutes, but not before the end of the dua.
        val own = MosqueSchedule.DEFAULT.with(Prayer.MAGHRIB, PrayerSettings(IqamahRule.AfterAdhan(1), 10)).copy(delays = mapOf(Prayer.MAGHRIB to 1))
        val stale = lines("""{ "prayers": { "maghrib": { "iqamah": "08:00" } } }""", at = at, schedule = own).single()
        assertTrue(stale, stale.endsWith("(${TvStrings.notTodayAfterAdhan("18:08", TvStrings.iqamahAfterAdhan(3))})"))
        // The Eid prayer has no adhan screen.
        assertFalse(lines("""{ "prayers": { "eidFitr": { "iqamah": "+1" } } }""", at = at).single().endsWith(")"))
    }

    @Test
    fun theFridayDuaReadsAsYesOrNoAndWithoutItTheIqamahWaitsForTheAdhanScreenOnly() {
        assertEquals(listOf("الدعاء بعد أذان الجمعة: نعم ← لا"), lines("""{ "prayers": { "jumua": { "dua": false } } }"""))
        val off = MosqueSchedule.DEFAULT.with(Prayer.JOMOAA, PrayerSettings(IqamahRule.AfterAdhan(15), 15, adhanDua = false))
        assertEquals(listOf("الدعاء بعد أذان الجمعة: لا ← نعم"), lines("""{ "prayers": { "jumua": { "الدعاء": true } } }""", schedule = off))
        // Jumu'a at "+2" after a 12:17 adhan: with the dua it waits until 12:20, without it 12:19 stands.
        val at = SettingsChangeLines.Today(today, mapOf(Prayer.JOMOAA to LocalTime.of(12, 17)))
        val withDua = lines("""{ "prayers": { "jumua": { "iqamah": "+2" } } }""", at = at).single()
        assertTrue(withDua, withDua.endsWith("(${TvStrings.waitsForAdhanScreen("12:17", "12:20")})"))
        val plain = lines("""{ "prayers": { "jumua": { "iqamah": "+2", "dua": false } } }""", at = at)
        assertFalse(plain.toString(), plain.any { it.endsWith(")") })
        val early = lines("""{ "prayers": { "jumua": { "iqamah": "+1" } } }""", at = at, schedule = off).single()
        assertTrue(early, early.endsWith("(${TvStrings.waitsForAdhanScreen("12:17", "12:19", withDua = false)})"))
        assertFalse(early, "الدعاء" in early)
        // The kiosk page says the same of today's Jumu'a.
        val friday = today.plusDays(1)
        val times = com.tunisianprayertimes.DayPrayerTimes(
            day = friday.dayOfMonth,
            fajr = com.tunisianprayertimes.PrayerTime(Prayer.FAJR, 4, 47), shurukHour = 6, shurukMinute = 13,
            dhuhr = com.tunisianprayertimes.PrayerTime(Prayer.DHUHR, 12, 17), asr = com.tunisianprayertimes.PrayerTime(Prayer.ASR, 15, 31),
            maghrib = com.tunisianprayertimes.PrayerTime(Prayer.MAGHRIB, 18, 6), isha = com.tunisianprayertimes.PrayerTime(Prayer.ISHA, 19, 30),
        )
        fun row(adhanDua: Boolean) = movedIqamahRows(com.tunisianprayertimes.mosque.PrayerFlow.eventsFor(friday, times,
            MosqueSchedule.DEFAULT.with(Prayer.JOMOAA, PrayerSettings(IqamahRule.AfterAdhan(1), 15, adhanDua = adhanDua))), friday).single()
        val jumua = MosqueSettingsFile.arabicName(Prayer.JOMOAA)
        assertEquals(TvStrings.iqamahWaitsForAdhan(jumua, "12:20"), row(adhanDua = true).text)
        assertEquals(TvStrings.IQAMAH_WAITS_FIX, row(adhanDua = true).fix)
        assertEquals(TvStrings.iqamahWaitsForAdhan(jumua, "12:19", withDua = false), row(adhanDua = false).text)
        assertEquals(TvStrings.IQAMAH_WAITS_FIX_NO_DUA, row(adhanDua = false).fix)
        assertFalse("الدعاء" in row(adhanDua = false).text)
    }

    @Test
    fun editsOfTextsAreNamedByWhatChanges() {
        val change = ContentChange(
            ContentList.AFTER_SALAH, "أ", "ب",
            recounted = listOf("آية الكرسي: العدد 1 ← 3"), resourced = listOf("«دعاء»"), reworded = listOf("«اللهم»"),
        )
        val lines = SettingsChangeLines.of(ParseResult.Success(MosqueSchedule.DEFAULT, emptyList(), contentChanges = listOf(change)))
        val list = TvStrings.AFTER_SALAH_TEXTS
        assertEquals(
            listOf(
                "$list: أ ← ب",
                "$list · ${TvStrings.TEXTS_RECOUNTED}: آية الكرسي: العدد 1 ← 3",
                "$list · ${TvStrings.TEXTS_RESOURCED}: «دعاء»",
                "$list · ${TvStrings.TEXTS_REWORDED}: «اللهم»",
            ),
            lines,
        )
    }

    @Test
    fun announcementsThatHaveEndedAreMarked() {
        val file = """{ "announcements": [ { "text": "درس قديم", "until": "2025-10-31" }, { "text": "درس جديد", "until": "2026-10-31" } ] }"""
        val lines = lines(file, at = SettingsChangeLines.Today(today))
        assertEquals(
            "${TvStrings.TEXT_ANNOUNCEMENTS} · ${TvStrings.ANNOUNCEMENTS_EXPIRED}: ${MosqueSettingsFile.textName("درس قديم")}",
            lines.last(),
        )
        assertTrue(lines.none { TvStrings.ANNOUNCEMENTS_EXPIRED in it && "جديد" in it })
    }

    @Test
    fun aSignedNumberInATextsNameKeepsItsSignOnTheLeft() {
        // «−3°», not «3°−»: the preview names a text as the wall shows it.
        val ltr = { text: String -> "${Char(0x2066)}$text${Char(0x2069)}" }
        val file = """{ "announcements": [ { "text": "الحرارة الليلة −3° فاحذروا", "until": "2025-10-31" } ] }"""
        val lines = lines(file, at = SettingsChangeLines.Today(today))
        val added = lines.single { TvStrings.TEXTS_ADDED in it }
        val expired = lines.single { TvStrings.ANNOUNCEMENTS_EXPIRED in it }
        for (line in listOf(added, expired)) assertTrue(line, "الليلة ${ltr("−3°")} فاحذروا" in line)
        val removed = SettingsChangeLines.of(ParseResult.Success(MosqueSchedule.DEFAULT, emptyList(),
            contentChanges = listOf(ContentChange(ContentList.TICKER, "أ", "ب", removed = listOf("«خصم -20% للأيتام»")))))
        assertTrue(removed.toString(), removed.any { TvStrings.TEXTS_REMOVED in it && "«خصم ${ltr("-20%")} للأيتام»" in it })
    }

    @Test
    fun aLongListIsCutWithACount() {
        val errors = List(MosqueSettingsFile.MAX_ERRORS) { MosqueSettingsFile.SettingsError(MosqueSettingsFile.ErrorCode.UNKNOWN_FIELD, "a$it", "خطأ $it") }
        val failure = SettingsChangeLines.of(ParseResult.Failure(errors, more = 7))
        assertEquals(MosqueSettingsFile.MAX_ERRORS + 1, failure.size)
        assertEquals(TvStrings.moreErrors(7), failure.last())
        val changes = List(150) { MosqueSettingsFile.Change(Prayer.ISHA, MosqueSettingsFile.Field.DURATION, "$it", "${it + 1}") }
        val success = SettingsChangeLines.of(ParseResult.Success(MosqueSchedule.DEFAULT, changes))
        assertEquals(101, success.size)
        assertEquals(TvStrings.moreChanges(50), success.last())
    }

    @Test
    fun filesLeftOutAndCopiesAreToldInWords() {
        val rejected = listOf(
            RejectedFile(File("a.heic"), RejectedFile.Reason.FORMAT),
            RejectedFile(File("b.gif"), RejectedFile.Reason.FORMAT),
            RejectedFile(File("photo.jpg"), RejectedFile.Reason.MISPLACED),
        )
        assertEquals(listOf(TvStrings.rejectedFormat(2), TvStrings.rejectedMisplaced(1)), rejectedLines(rejected))
        assertEquals(TvStrings.usbCopied(3), copyNotice(UsbMediaCopy.Done(3)))
        assertTrue(copyNotice(UsbMediaCopy.Done(2, unreadable = 1)).contains(TvStrings.filesCount(1)))
        assertEquals(TvStrings.USB_COPY_FAILED, copyNotice(UsbMediaCopy.Failed))
        assertEquals(TvStrings.USB_COPY_NO_ROOM, copyNotice(UsbMediaCopy.NoRoom))
    }

    @Test
    fun readingTheKeyAgainAnswersEvenWhenItFindsNothing() {
        // No key, or nothing new on it: the admin is told, as the copy to a key does.
        assertEquals(TvStrings.USB_READ_NOTHING, readAgainNotice(UsbScan.Quiet, null))
        // The other outcomes speak for themselves: the offer, or their own notice.
        assertEquals(null, readAgainNotice(UsbScan.Inaccessible, null))
        assertEquals(null, readAgainNotice(UsbScan.ReadOnly, null))
    }

    @Test
    fun theKioskPageNamesAnIqamahMovedToday() {
        fun event(prayer: Prayer, adhan: String, iqamah: String, moved: Boolean, day: LocalDate = today) = PrayerEvent(
            prayer, day.atTime(LocalTime.parse(adhan)), day.atTime(LocalTime.parse(adhan)).plusMinutes(3),
            day.atTime(LocalTime.parse(iqamah)), day.atTime(LocalTime.parse(iqamah)).plusMinutes(10),
            day.atTime(LocalTime.parse(iqamah)).plusMinutes(20), iqamahAdjusted = moved,
        )
        val rows = movedIqamahRows(
            listOf(
                event(Prayer.MAGHRIB, "18:10", "18:15", moved = false),
                event(Prayer.ISHA, "19:30", "19:40", moved = true),
                event(Prayer.ISHA, "19:31", "19:41", moved = true, day = today.minusDays(1)),
            ),
            today,
        )
        assertEquals(1, rows.size)
        assertEquals(HealthLevel.WARNING, rows.single().level)
        assertEquals(TvStrings.iqamahMoved(MosqueSettingsFile.arabicName(Prayer.ISHA), "19:40"), rows.single().text)

        // An iqamah set before the end of the adhan screen and the dua waits for them, and the page says why.
        val waited = movedIqamahRows(
            listOf(PrayerEvent(Prayer.MAGHRIB, today.atTime(18, 8), today.atTime(18, 10), today.atTime(18, 11),
                today.atTime(18, 19), today.atTime(18, 29), iqamahAdjusted = true, adhanDuaEndAt = today.atTime(18, 11),
                iqamahMove = IqamahMove.WAITED_FOR_ADHAN)),
            today,
        ).single()
        assertEquals(TvStrings.iqamahWaitsForAdhan(MosqueSettingsFile.arabicName(Prayer.MAGHRIB), "18:11"), waited.text)
        assertEquals(TvStrings.IQAMAH_WAITS_FIX, waited.fix)
        // The same from the flow itself: "+1" after the default 2-minute adhan screen waits for the dua's end.
        val times = com.tunisianprayertimes.DayPrayerTimes(
            day = today.dayOfMonth,
            fajr = com.tunisianprayertimes.PrayerTime(Prayer.FAJR, 4, 46), shurukHour = 6, shurukMinute = 12,
            dhuhr = com.tunisianprayertimes.PrayerTime(Prayer.DHUHR, 12, 17), asr = com.tunisianprayertimes.PrayerTime(Prayer.ASR, 15, 32),
            maghrib = com.tunisianprayertimes.PrayerTime(Prayer.MAGHRIB, 18, 8), isha = com.tunisianprayertimes.PrayerTime(Prayer.ISHA, 19, 32),
        )
        val flow = com.tunisianprayertimes.mosque.PrayerFlow.eventsFor(
            today, times, MosqueSchedule.DEFAULT.with(Prayer.MAGHRIB, PrayerSettings(IqamahRule.AfterAdhan(1), 8)),
        )
        assertEquals(TvStrings.iqamahWaitsForAdhan(MosqueSettingsFile.arabicName(Prayer.MAGHRIB), "18:11"), movedIqamahRows(flow, today).single().text)
        // Ending with the dua is not enough: a stale fixed time whose fallback (+1, or exactly +3) lands on the
        // dua's end, and an iqamah held before the next adhan, say the time does not suit today, as the USB preview does.
        val maghribMoved = TvStrings.iqamahMoved(MosqueSettingsFile.arabicName(Prayer.MAGHRIB), "18:11")
        listOf(1, 3).forEach { fallback ->
            val stale = MosqueSchedule.DEFAULT.with(Prayer.MAGHRIB, PrayerSettings(IqamahRule.FixedTime(LocalTime.of(8, 0)), 8))
                .copy(delays = mapOf(Prayer.MAGHRIB to fallback))
            val staleFlow = com.tunisianprayertimes.mosque.PrayerFlow.eventsFor(today, times, stale)
            assertEquals(today.atTime(18, 11), staleFlow.single { it.prayer == Prayer.MAGHRIB }.adhanDuaEndAt)
            val row = movedIqamahRows(staleFlow, today).single()
            assertEquals("fallback +$fallback", maghribMoved, row.text)
            assertEquals(TvStrings.IQAMAH_MOVED_FIX, row.fix)
        }
        val earlyIsha = times.copy(isha = com.tunisianprayertimes.PrayerTime(Prayer.ISHA, 18, 12))
        val capped = com.tunisianprayertimes.mosque.PrayerFlow.eventsFor(
            today, earlyIsha, MosqueSchedule.DEFAULT.with(Prayer.MAGHRIB, PrayerSettings(IqamahRule.AfterAdhan(10), 8)),
        )
        assertEquals(IqamahMove.CAPPED, capped.single { it.prayer == Prayer.MAGHRIB }.iqamahMove)
        assertEquals(maghribMoved, movedIqamahRows(capped, today).single { it.text.contains(MosqueSettingsFile.arabicName(Prayer.MAGHRIB)) }.text)

        // The Eid prayer counts from sunrise: no adhan in its words.
        val eid = movedIqamahRows(listOf(event(Prayer.AID_FITR, "06:20", "06:50", moved = true)), today).single()
        assertEquals(TvStrings.eidPrayerMoved(MosqueSettingsFile.arabicName(Prayer.AID_FITR), "06:50"), eid.text)
        assertEquals(TvStrings.EID_PRAYER_MOVED_FIX, eid.fix)
    }
}
