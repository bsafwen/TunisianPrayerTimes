package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.IslamicDays
import com.tunisianprayertimes.ManualIslamicDates
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerFormulaSettings
import com.tunisianprayertimes.RamadanOverrideChecker
import com.tunisianprayertimes.TunisianHijriCalendar
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ErrorCode
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MosqueSettingsFileTest {

    private val current = MosqueSchedule.DEFAULT

    private fun success(text: String): ParseResult.Success = assertIs<ParseResult.Success>(MosqueSettingsFile.parse(text, current))

    private fun errors(text: String): List<Pair<ErrorCode, String>> =
        assertIs<ParseResult.Failure>(MosqueSettingsFile.parse(text, current)).errors.map { it.code to it.path }

    @Test
    fun fullFileSetsEveryPrayer() {
        val result = success(
            """
            {
              "format": "tunisian-prayer-times-tv",
              "version": 1,
              "prayers": {
                "fajr":    { "iqamah": "+20",   "duration": 12 },
                "dhuhr":   { "iqamah": "+10",   "duration": 10 },
                "asr":     { "iqamah": "+10",   "duration": 10 },
                "maghrib": { "iqamah": "+5",    "duration": 7 },
                "isha":    { "iqamah": "20:00", "duration": 10 },
                "jumua":   { "iqamah": "13:15", "duration": 15 }
              }
            }
            """.trimIndent()
        )
        assertEquals(PrayerSettings(IqamahRule.AfterAdhan(20), 12), result.schedule.settings(Prayer.FAJR))
        assertEquals(PrayerSettings(IqamahRule.FixedTime(LocalTime.of(20, 0)), 10), result.schedule.settings(Prayer.ISHA))
        assertEquals(PrayerSettings(IqamahRule.FixedTime(LocalTime.of(13, 15)), 15), result.schedule.settings(Prayer.JOMOAA))
        assertEquals(
            listOf(
                MosqueSettingsFile.Change(Prayer.FAJR, MosqueSettingsFile.Field.IQAMAH, "+15", "+20"),
                MosqueSettingsFile.Change(Prayer.FAJR, MosqueSettingsFile.Field.DURATION, "10", "12"),
                MosqueSettingsFile.Change(Prayer.MAGHRIB, MosqueSettingsFile.Field.DURATION, "8", "7"),
                MosqueSettingsFile.Change(Prayer.ISHA, MosqueSettingsFile.Field.IQAMAH, "+10", "20:00"),
                MosqueSettingsFile.Change(Prayer.JOMOAA, MosqueSettingsFile.Field.IQAMAH, "+15", "13:15"),
            ),
            result.changes,
        )
    }

    @Test
    fun partialFileChangesOnlyWhatItNames() {
        val result = success("""{ "prayers": { "isha": { "duration": 12 } } }""")
        assertEquals(PrayerSettings(IqamahRule.AfterAdhan(10), 12), result.schedule.settings(Prayer.ISHA))
        assertEquals(current.settings(Prayer.FAJR), result.schedule.settings(Prayer.FAJR))
        assertEquals(1, result.changes.size)
    }

    @Test
    fun arabicNamesDigitsAndTunisianTimeStylesAreAccepted() {
        val result = success(
            """{ "prayers": { "العشاء": { "iqamah": "٢٠:٠٠", "duration": "١٠" }, "الصبح": { "iqamah": " + 25 " },
                 "Maghreb": { "iqamah": "18h40" }, "dohr": { "iqamah": 12 }, "الجمعة": { "iqamah": "13.10" } } }"""
        )
        assertEquals(PrayerSettings(IqamahRule.FixedTime(LocalTime.of(20, 0)), 10), result.schedule.settings(Prayer.ISHA))
        assertEquals(IqamahRule.AfterAdhan(25), result.schedule.settings(Prayer.FAJR).iqamah)
        assertEquals(IqamahRule.FixedTime(LocalTime.of(18, 40)), result.schedule.settings(Prayer.MAGHRIB).iqamah)
        assertEquals(IqamahRule.AfterAdhan(12), result.schedule.settings(Prayer.DHUHR).iqamah)
        assertEquals(IqamahRule.FixedTime(LocalTime.of(13, 10)), result.schedule.settings(Prayer.JOMOAA).iqamah)
    }

    @Test
    fun keysWrittenTwiceOrMisspelledSectionsAreRefused() {
        // The admin added a line instead of editing the template's: the reader would keep only the last one.
        assertEquals(listOf(ErrorCode.DUPLICATE_FIELD to "prayers.isha"),
            errors("""{ "prayers": { "isha": { "iqamah": "20:00" }, "asr": {}, "isha": { "iqamah": "+10" } } }"""))
        assertEquals(listOf(ErrorCode.DUPLICATE_FIELD to "prayers.isha.iqamah"),
            errors("""{ "prayers": { "isha": { "iqamah": "20:00", /* "iqamah": "x", */ "iqamah": "+10" } } }"""))
        // A quoted name inside a value is not a key; the same names in different objects are fine.
        success("""{ "prayers": { "isha": { "iqamah": "+10", "note": "\"isha\": no" }, "fajr": { "iqamah": "+10" } } }""")
        assertEquals(listOf(ErrorCode.UNKNOWN_FIELD to "ramadhan"),
            errors("""{ "prayers": { "isha": { "iqamah": "20:00" } }, "ramadhan": { "isha": { "duration": 75 } } }"""))
        assertEquals(listOf(ErrorCode.NOT_AN_OBJECT to "mosque"), errors("""{ "mosque": "مسجد النور" }"""))
        assertEquals(listOf(ErrorCode.DUPLICATE_FIELD to "الصلوات"),
            errors("""{ "prayers": { "isha": { "duration": 12 } }, "الصلوات": { "fajr": { "duration": 12 } } }"""))
        assertEquals(listOf(ErrorCode.DUPLICATE_FIELD to "islamicDates.١٤٤٨"),
            errors("""{ "islamicDates": { "1448": { "ramadanStart": "2027-02-08" }, "١٤٤٨": { "eidAdha": "2027-05-17" } } }"""))
    }

    @Test
    fun ownTextsKeepTheirVerseMarks() {
        val text = "ذَٰلِكَ الْكِتَابُ لَا رَيْبَ فِيهِ ۝٢"
        val result = success("""{ "adhkar": { "afterSalah": [ { "text": "‏$text", "reference": "البقرة 2" } ] } }""")
        assertEquals(text, (result.content.afterSalah!!.items.single() as CustomDhikr).text)
    }

    @Test
    fun handEditingLeftoversAreTolerated() {
        val result = success(
            "﻿{ // mosque settings\n \"note\": \"مسجد\", \"prayers\": { \"asr\": { \"iqamah\": \"+15\", \"note\": \"صيفي\", }, }, }"
        )
        assertEquals(IqamahRule.AfterAdhan(15), result.schedule.settings(Prayer.ASR).iqamah)
    }

    @Test
    fun badIqamahValuesPointAtTheirField() {
        for (bad in listOf("8:5", "24:00", "20:60", "-5", "abc", "", "+", "true")) {
            assertEquals(
                listOf(ErrorCode.INVALID_IQAMAH to "prayers.isha.iqamah"),
                errors("""{ "prayers": { "isha": { "iqamah": "$bad" } } }"""),
                "iqamah «$bad»",
            )
        }
        assertEquals(listOf(ErrorCode.IQAMAH_OUT_OF_RANGE to "prayers.fajr.iqamah"), errors("""{ "prayers": { "fajr": { "iqamah": "+120" } } }"""))
        assertEquals(listOf(ErrorCode.IQAMAH_OUT_OF_RANGE to "prayers.fajr.iqamah"), errors("""{ "prayers": { "fajr": { "iqamah": "+0" } } }"""))
    }

    @Test
    fun badDurationsPointAtTheirField() {
        assertEquals(listOf(ErrorCode.DURATION_OUT_OF_RANGE to "prayers.asr.duration"), errors("""{ "prayers": { "asr": { "duration": 91 } } }"""))
        assertEquals(listOf(ErrorCode.DURATION_OUT_OF_RANGE to "prayers.asr.duration"), errors("""{ "prayers": { "asr": { "duration": 0 } } }"""))
        for (bad in listOf("10.5", "-3", "\"dix\"", "true", "[10]")) {
            assertEquals(
                listOf(ErrorCode.INVALID_DURATION to "prayers.asr.duration"),
                errors("""{ "prayers": { "asr": { "duration": $bad } } }"""),
                "duration $bad",
            )
        }
    }

    @Test
    fun wrongFilesAreRejectedWithAReason() {
        assertEquals(listOf(ErrorCode.UNKNOWN_FORMAT to "format"), errors("""{ "format": "mawaqit", "prayers": { "asr": { "duration": 5 } } }"""))
        assertEquals(listOf(ErrorCode.UNSUPPORTED_VERSION to "version"), errors("""{ "version": 2, "prayers": { "asr": { "duration": 5 } } }"""))
        assertEquals(listOf(ErrorCode.NO_PRAYERS to "prayers"), errors("""{ "format": "tunisian-prayer-times-tv" }"""))
        assertEquals(listOf(ErrorCode.UNKNOWN_PRAYER to "prayers.fajer"), errors("""{ "prayers": { "fajer": { "duration": 5 } } }"""))
        assertEquals(listOf(ErrorCode.DUPLICATE_PRAYER to "prayers.الفجر"), errors("""{ "prayers": { "fajr": { "duration": 5 }, "الفجر": { "duration": 6 } } }"""))
        assertEquals(listOf(ErrorCode.NOT_A_PRAYER_OBJECT to "prayers.asr"), errors("""{ "prayers": { "asr": "+10" } }"""))
    }

    @Test
    fun unreadableTextNeverThrows() {
        for (text in listOf("", "   ", "{", "not json", "{ \"prayers\": { \"asr\": { \"iqamah\": \"+10\" }", "[1, 2]", "null", "\"text\"")) {
            val result = MosqueSettingsFile.parse(text, current)
            assertIs<ParseResult.Failure>(result, "text «$text»")
        }
        assertEquals(listOf(ErrorCode.TOO_LARGE to ""), errors("{ \"x\": \"" + "a".repeat(MosqueSettingsFile.MAX_CHARS) + "\" }"))
    }

    @Test
    fun deeplyNestedValuesAreRejectedWithoutCrashing() {
        val deep = "{\"a\":".repeat(3000) + "1" + "}".repeat(3000)
        for (field in listOf("iqamah", "duration")) {
            val result = MosqueSettingsFile.parse("""{ "prayers": { "asr": { "$field": $deep } } }""", current)
            assertIs<ParseResult.Failure>(result, field)
        }
    }

    @Test
    fun nestingFarDeeperThanAnySettingsFileIsRefusedBeforeReading() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        assertEquals(listOf(ErrorCode.INVALID_JSON to ""), errors("""{ "announcements": $deep }"""))
    }

    @Test
    fun announcementAndAdhkarItemsTakeOnlyTheirOwnFields() {
        assertEquals(
            listOf(ErrorCode.UNKNOWN_FIELD to "announcements[0].txt"),
            errors("""{ "announcements": [ { "txt": "درس", "until": "2026-10-31" } ] }"""),
        )
        assertEquals(
            listOf(ErrorCode.DUPLICATE_FIELD to "adhkar.ticker.items[0].النص"),
            errors("""{ "adhkar": { "ticker": { "items": [ { "text": "أ", "النص": "ب", "reference": "م" } ] } } }"""),
        )
        assertEquals(
            listOf(ErrorCode.UNKNOWN_FIELD to "adhkar.afterSalah.mod"),
            errors("""{ "adhkar": { "afterSalah": { "mod": "replace", "items": [ { "text": "أ", "reference": "م" } ] } } }"""),
        )
        // A note for the admin is fine anywhere.
        success("""{ "announcements": [ { "text": "درس", "note": "من الإمام" } ] }""")
    }

    @Test
    fun invisibleDirectionMarksFromArabicKeyboardsAreIgnored() {
        val rlm = '\u200F'
        val alm = '\u061C'
        val result = success("""{ "prayers": { "${rlm}العشاء$rlm": { "iqamah": "${rlm}20:00", "duration": "${alm}12" } } }""")
        assertEquals(PrayerSettings(IqamahRule.FixedTime(LocalTime.of(20, 0)), 12), result.schedule.settings(Prayer.ISHA))
    }

    @Test
    fun fieldNamesAcceptCommonSpellingsAndRejectTypos() {
        val result = success("""{ "prayers": { "isha": { "Iqamah": "+12", "المدة": 9, "note": "الشتاء" }, "asr": { "iqama": "+11" } } }""")
        assertEquals(PrayerSettings(IqamahRule.AfterAdhan(12), 9), result.schedule.settings(Prayer.ISHA))
        assertEquals(IqamahRule.AfterAdhan(11), result.schedule.settings(Prayer.ASR).iqamah)
        assertEquals(listOf(ErrorCode.UNKNOWN_FIELD to "prayers.isha.iqamahh"), errors("""{ "prayers": { "isha": { "iqamahh": "+12" } } }"""))
        assertEquals(
            listOf(ErrorCode.DUPLICATE_FIELD to "prayers.isha.الإقامة"),
            errors("""{ "prayers": { "isha": { "iqamah": "+12", "الإقامة": "+13" } } }"""),
        )
    }

    @Test
    fun eidPrayersAreTimedFromSunrise() {
        val both = success("""{ "prayers": { "eid": { "iqamah": "+40", "duration": 35 } } }""")
        assertEquals(PrayerSettings(IqamahRule.AfterAdhan(40), 35), both.schedule.settings(Prayer.AID_FITR))
        assertEquals(PrayerSettings(IqamahRule.AfterAdhan(40), 35), both.schedule.settings(Prayer.AID_ADHA))
        val adha = success("""{ "prayers": { "عيد الأضحى": { "iqamah": "07:15" } } }""")
        assertEquals(IqamahRule.FixedTime(LocalTime.of(7, 15)), adha.schedule.settings(Prayer.AID_ADHA).iqamah)
        assertEquals(current.settings(Prayer.AID_FITR), adha.schedule.settings(Prayer.AID_FITR))
        assertEquals(
            listOf(ErrorCode.DUPLICATE_PRAYER to "prayers.eidFitr"),
            errors("""{ "prayers": { "eid": { "duration": 30 }, "eidFitr": { "duration": 20 } } }"""),
        )
    }

    @Test
    fun ramadanChangesOnlyTheFieldsItNames() {
        val result = success("""{ "ramadan": { "isha": { "duration": 75 }, "الفجر": { "iqamah": "+20" } } }""")
        assertEquals(PrayerOverride(salahMinutes = 75), result.schedule.ramadan[Prayer.ISHA])
        assertEquals(PrayerSettings(IqamahRule.AfterAdhan(10), 75), result.schedule.settingsOn(Prayer.ISHA, isRamadan = true))
        assertEquals(current.settings(Prayer.ISHA), result.schedule.settingsOn(Prayer.ISHA, isRamadan = false))
        assertTrue(result.changes.any { it.ramadan && it.prayer == Prayer.ISHA && it.after == "75" })
        // null returns a field to the usual setting
        val cleared = assertIs<ParseResult.Success>(MosqueSettingsFile.parse("""{ "ramadan": { "isha": { "duration": null } } }""", result.schedule))
        assertEquals(null, cleared.schedule.ramadan[Prayer.ISHA])
        assertEquals(listOf(ErrorCode.UNKNOWN_PRAYER to "ramadan.eid"), errors("""{ "ramadan": { "eid": { "duration": 30 } } }"""))
    }

    @Test
    fun islamicDatesAreSetCheckedAndCleared() {
        val result = success("""{ "islamicDates": { "١٤٤٨": { "ramadanStart": "2027-02-08", "عيد الفطر": "2027/03/10" } } }""")
        assertEquals(
            mapOf(1448 to com.tunisianprayertimes.ManualIslamicDates(ramadanStart = LocalDate.of(2027, 2, 8), eidFitr = LocalDate.of(2027, 3, 10))),
            result.islamicDates,
        )
        assertEquals(2, result.dateChanges.size)
        assertEquals(listOf(ErrorCode.DATE_OUT_OF_RANGE to "islamicDates.1448.eidAdha"),
            errors("""{ "islamicDates": { "1448": { "eidAdha": "2027-07-01" } } }"""))
        assertEquals(listOf(ErrorCode.INVALID_DATE to "islamicDates.1448.eidAdha"),
            errors("""{ "islamicDates": { "1448": { "eidAdha": "16 mai" } } }"""))
        assertEquals(listOf(ErrorCode.INVALID_YEAR to "islamicDates.2027"),
            errors("""{ "islamicDates": { "2027": { "eidAdha": "2027-05-16" } } }"""))
        val existing = mapOf(1448 to com.tunisianprayertimes.ManualIslamicDates(ramadanStart = LocalDate.of(2027, 2, 8)))
        val cleared = assertIs<ParseResult.Success>(
            MosqueSettingsFile.parse("""{ "islamicDates": { "1448": { "ramadanStart": null } } }""", current, existing))
        assertEquals(com.tunisianprayertimes.ManualIslamicDates(), cleared.islamicDates[1448])
    }

    @Test
    fun datesThatWouldMakeA31DayRamadanAreRefusedWithTheReason() {
        // Both set by hand in the same file.
        val both = assertIs<ParseResult.Failure>(MosqueSettingsFile.parse(
            """{ "islamicDates": { "1448": { "ramadanStart": "2027-02-08", "eidFitr": "2027-03-11" } } }""", current))
        assertEquals(listOf(ErrorCode.DATES_CONFLICT to "islamicDates.1448"), both.errors.map { it.code to it.path })
        assertEquals("رمضان سيكون 31 يومًا، والشهر 29 أو 30 يومًا: عدّل أحدهما", both.errors.single().message)
        // Against the Ramadan the TV has from an announcement.
        val announced = TunisianHijriCalendar(mapOf(1448 to RamadanOverrideChecker.RamadanOverride(1448, LocalDate.of(2027, 2, 8), null, null)))
        val yearDates = { year: Int -> IslamicDays.yearDates(year, announced, announced, ManualIslamicDates()) }
        val late = assertIs<ParseResult.Failure>(MosqueSettingsFile.parse(
            """{ "islamicDates": { "1448": { "eidFitr": "2027-03-11" } } }""", current, yearDates = yearDates))
        assertEquals("رمضان سيكون 31 يومًا، والشهر 29 أو 30 يومًا: عدّل بداية رمضان أيضًا", late.errors.single().message)
        // Moving Ramadan with it is accepted, and so is an Eid against a mere estimate.
        assertIs<ParseResult.Success>(MosqueSettingsFile.parse(
            """{ "islamicDates": { "1448": { "ramadanStart": "2027-02-09", "eidFitr": "2027-03-11" } } }""", current, yearDates = yearDates))
        assertIs<ParseResult.Success>(MosqueSettingsFile.parse("""{ "islamicDates": { "1448": { "eidFitr": "2027-03-11" } } }""", current))
        // A date the file leaves as it was is not questioned.
        val existing = mapOf(1448 to ManualIslamicDates(eidFitr = LocalDate.of(2027, 3, 11)))
        assertIs<ParseResult.Success>(MosqueSettingsFile.parse("""{ "islamicDates": { "1448": { "eidFitr": "2027-03-11" } } }""",
            current, existing, yearDates = yearDates))
    }

    @Test
    fun aMosquesOwnEidAlFitrMayStandBesideTheAnnouncedEidAlAdha() {
        // Announced: Eid al-Fitr 2027-03-09 and Eid al-Adha 2027-05-15, 58 days from 1 Shawwal to 1 Dhul Hijja.
        val records = mapOf(1448 to RamadanOverrideChecker.RamadanOverride(1448, null, LocalDate.of(2027, 3, 9), LocalDate.of(2027, 5, 15)))
        val announced = TunisianHijriCalendar(records)
        val yearDates = { year: Int -> IslamicDays.yearDates(year, announced, announced, ManualIslamicDates()) }
        // The mosque's Eid al-Fitr a day later: the calendar keeps both, and so does the file.
        assertIs<ParseResult.Success>(MosqueSettingsFile.parse(
            """{ "islamicDates": { "1448": { "eidFitr": "2027-03-10" } } }""", current, yearDates = yearDates))
        val merged = TunisianHijriCalendar(records, mapOf(1448 to ManualIslamicDates(eidFitr = LocalDate.of(2027, 3, 10))))
        assertEquals(LocalDate.of(2027, 3, 10), merged.month(1448, 10).start)
        assertEquals(LocalDate.of(2027, 5, 6), merged.month(1448, 12).start)
        // Farther than months of 28 to 31 days can join: the calendar would drop the announcement.
        val far = assertIs<ParseResult.Failure>(MosqueSettingsFile.parse(
            """{ "islamicDates": { "1448": { "eidFitr": "2027-03-13" } } }""", current, yearDates = yearDates))
        assertEquals("بين عيد الفطر وعيد الأضحى 63 يومًا، والممكن من 65 إلى 71: عدّل عيد الأضحى أيضًا", far.errors.single().message)
    }

    @Test
    fun aMosqueCanSayItHoldsNoJumuaOrEidPrayer() {
        val result = success("""{ "prayers": { "jumua": { "held": false }, "eid": { "تقام": "لا" } } }""")
        assertEquals(PrayerSettings(IqamahRule.AfterAdhan(15), 15, held = false), result.schedule.settings(Prayer.JOMOAA))
        assertEquals(false, result.schedule.settings(Prayer.AID_FITR).held)
        assertEquals(false, result.schedule.settings(Prayer.AID_ADHA).held)
        assertEquals(
            listOf(
                MosqueSettingsFile.Change(Prayer.JOMOAA, MosqueSettingsFile.Field.HELD, "true", "false"),
                MosqueSettingsFile.Change(Prayer.AID_FITR, MosqueSettingsFile.Field.HELD, "true", "false"),
                MosqueSettingsFile.Change(Prayer.AID_ADHA, MosqueSettingsFile.Field.HELD, "true", "false"),
            ),
            result.changes,
        )
        // Written only when off (and in a complete snapshot), and read back to the same state.
        val written = MosqueSettingsFile.write(result.schedule)
        assertTrue(written.contains("\"jumua\": { \"iqamah\": \"+15\", \"duration\": 15, \"held\": false }"), written)
        assertTrue("held" !in MosqueSettingsFile.write(MosqueSchedule.DEFAULT))
        assertTrue("\"held\": true" in MosqueSettingsFile.write(MosqueSchedule.DEFAULT, complete = true))
        assertTrue(!assertIs<ParseResult.Success>(MosqueSettingsFile.parse(written, result.schedule)).hasChanges)
        // Only for Jumu'a and the Eids, only in "prayers", and only true or false.
        assertEquals(listOf(ErrorCode.UNKNOWN_FIELD to "prayers.isha.held"), errors("""{ "prayers": { "isha": { "held": false } } }"""))
        assertEquals(listOf(ErrorCode.UNKNOWN_FIELD to "ramadan.jumua.held"), errors("""{ "ramadan": { "jumua": { "held": false } } }"""))
        assertEquals(listOf(ErrorCode.INVALID_OPTION to "prayers.jumua.held"), errors("""{ "prayers": { "jumua": { "held": "sometimes" } } }"""))
    }

    @Test
    fun aMosqueCanSayHowLongItsKhutbaLasts() {
        val result = success("""{ "prayers": { "jumua": { "iqamah": "13:15", "الخطبة": 30 } } }""")
        assertEquals(30, result.schedule.settings(Prayer.JOMOAA).khutbaMinutes)
        assertEquals(MosqueSettingsFile.Change(Prayer.JOMOAA, MosqueSettingsFile.Field.KHUTBA, "0", "30"), result.changes.last())
        // Written only when set (and in a complete snapshot), and read back to the same state.
        val written = MosqueSettingsFile.write(result.schedule)
        assertTrue(written.contains("\"jumua\": { \"iqamah\": \"13:15\", \"duration\": 15, \"khutba\": 30 }"), written)
        assertTrue("khutba" !in MosqueSettingsFile.write(MosqueSchedule.DEFAULT))
        assertTrue("\"khutba\": 0" in MosqueSettingsFile.write(MosqueSchedule.DEFAULT, complete = true))
        assertTrue(!assertIs<ParseResult.Success>(MosqueSettingsFile.parse(written, result.schedule)).hasChanges)
        // 0 returns to the khutba screen from the adhan.
        val whole = assertIs<ParseResult.Success>(MosqueSettingsFile.parse("""{ "prayers": { "jumua": { "khutba": 0 } } }""", result.schedule))
        assertEquals(0, whole.schedule.settings(Prayer.JOMOAA).khutbaMinutes)
        // Only for Jumu'a, only in "prayers", and a number of minutes up to an hour.
        assertEquals(listOf(ErrorCode.UNKNOWN_FIELD to "prayers.dhuhr.khutba"), errors("""{ "prayers": { "dhuhr": { "khutba": 30 } } }"""))
        assertEquals(listOf(ErrorCode.UNKNOWN_FIELD to "ramadan.jumua.khutba"), errors("""{ "ramadan": { "jumua": { "khutba": 30 } } }"""))
        assertEquals(listOf(ErrorCode.DURATION_OUT_OF_RANGE to "prayers.jumua.khutba"), errors("""{ "prayers": { "jumua": { "khutba": 90 } } }"""))
        assertEquals(listOf(ErrorCode.INVALID_DURATION to "prayers.jumua.khutba"), errors("""{ "prayers": { "jumua": { "khutba": "long" } } }"""))
    }

    @Test
    fun aMosqueCanLeaveOutTheFridayDuaAfterTheAdhan() {
        // Shown by default: the imam says it too.
        assertTrue(MosqueSchedule.DEFAULT.settings(Prayer.JOMOAA).adhanDua)
        val result = success("""{ "prayers": { "jumua": { "iqamah": "13:15", "dua": false } } }""")
        assertEquals(false, result.schedule.settings(Prayer.JOMOAA).adhanDua)
        assertEquals(MosqueSettingsFile.Change(Prayer.JOMOAA, MosqueSettingsFile.Field.DUA, "true", "false"), result.changes.last())
        // Written only when off (and in a complete snapshot), and read back to the same state.
        val written = MosqueSettingsFile.write(result.schedule)
        assertTrue(written.contains("\"jumua\": { \"iqamah\": \"13:15\", \"duration\": 15, \"dua\": false }"), written)
        assertTrue("\"dua\"" !in MosqueSettingsFile.write(MosqueSchedule.DEFAULT))
        assertTrue("\"khutba\": 0, \"dua\": true }" in MosqueSettingsFile.write(MosqueSchedule.DEFAULT, complete = true))
        assertTrue(!assertIs<ParseResult.Success>(MosqueSettingsFile.parse(written, result.schedule)).hasChanges)
        val snapshot = MosqueSettingsFile.write(MosqueSchedule.DEFAULT, complete = true)
        val back = assertIs<ParseResult.Success>(MosqueSettingsFile.parse(snapshot, result.schedule))
        assertEquals(true, back.schedule.settings(Prayer.JOMOAA).adhanDua)
        assertEquals(MosqueSettingsFile.Change(Prayer.JOMOAA, MosqueSettingsFile.Field.DUA, "false", "true"), back.changes.single { it.field == MosqueSettingsFile.Field.DUA })
        // Its other names, with Arabic yes and no; null leaves it as it is.
        listOf("adhanDua", "الدعاء", "دعاء الأذان").forEach { key ->
            assertEquals(false, success("""{ "prayers": { "jumua": { "$key": "لا" } } }""").schedule.settings(Prayer.JOMOAA).adhanDua, key)
        }
        assertTrue(!success("""{ "prayers": { "jumua": { "dua": null, "duration": 20 } } }""").changes.any { it.field == MosqueSettingsFile.Field.DUA })
        // Only for Jumu'a, only in "prayers", only true or false, once.
        assertEquals(listOf(ErrorCode.UNKNOWN_FIELD to "prayers.dhuhr.dua"), errors("""{ "prayers": { "dhuhr": { "dua": false } } }"""))
        assertEquals(listOf(ErrorCode.UNKNOWN_FIELD to "prayers.eidFitr.dua"), errors("""{ "prayers": { "eidFitr": { "dua": false } } }"""))
        assertEquals(listOf(ErrorCode.UNKNOWN_FIELD to "ramadan.jumua.dua"), errors("""{ "ramadan": { "jumua": { "dua": false } } }"""))
        assertEquals(listOf(ErrorCode.INVALID_OPTION to "prayers.jumua.dua"), errors("""{ "prayers": { "jumua": { "dua": "sometimes" } } }"""))
        assertEquals(listOf(ErrorCode.DUPLICATE_FIELD to "prayers.jumua.الدعاء"), errors("""{ "prayers": { "jumua": { "dua": false, "الدعاء": true } } }"""))
        // An unknown field of Jumu'a names it among the accepted ones.
        val unknown = assertIs<ParseResult.Failure>(MosqueSettingsFile.parse("""{ "prayers": { "jumua": { "duaa": false } } }""", current))
        assertTrue("\"dua\"" in unknown.errors.single().message, unknown.errors.single().message)
    }

    @Test
    fun everySectionSurvivesAWriteAndRead() {
        val schedule = MosqueSchedule.DEFAULT
            .with(Prayer.AID_ADHA, PrayerSettings(IqamahRule.FixedTime(LocalTime.of(7, 10)), 25))
            .withRamadan(Prayer.ISHA, PrayerOverride(IqamahRule.AfterAdhan(15), 80))
        val dates = mapOf(1448 to com.tunisianprayertimes.ManualIslamicDates(eidFitr = LocalDate.of(2027, 3, 10)))
        val read = assertIs<ParseResult.Success>(MosqueSettingsFile.parse(MosqueSettingsFile.write(schedule, dates), MosqueSchedule()))
        assertEquals(schedule.settings(Prayer.AID_ADHA), read.schedule.settings(Prayer.AID_ADHA))
        assertEquals(schedule.ramadan, read.schedule.ramadan)
        assertEquals(dates, read.islamicDates)
    }

    @Test
    fun oneBadValueRejectsTheWholeFile() {
        val result = MosqueSettingsFile.parse("""{ "prayers": { "fajr": { "iqamah": "+20" }, "isha": { "iqamah": "25:00" } } }""", current)
        assertIs<ParseResult.Failure>(result)
    }

    @Test
    fun everyProblemIsReportedAtOnceInArabic() {
        val failure = assertIs<ParseResult.Failure>(
            MosqueSettingsFile.parse("""{ "prayers": { "fajr": { "iqamah": "x", "duration": 99 }, "isha": { "iqamah": "25:00" } } }""", current)
        )
        assertEquals(3, failure.errors.size)
        assertTrue(failure.errors.all { error -> error.message.any { it in '؀'..'ۿ' } })
    }

    @Test
    fun writtenFilesReadBackToTheSameSettings() {
        val custom = MosqueSchedule.DEFAULT
            .with(Prayer.ISHA, PrayerSettings(IqamahRule.FixedTime(LocalTime.of(20, 5)), 11))
            .with(Prayer.FAJR, PrayerSettings(IqamahRule.AfterAdhan(1), 1))
        for (schedule in listOf(MosqueSchedule.DEFAULT, custom)) {
            val text = MosqueSettingsFile.write(schedule)
            val read = assertIs<ParseResult.Success>(MosqueSettingsFile.parse(text, MosqueSchedule()))
            assertEquals(MosqueSchedule.CONFIGURABLE.associateWith(schedule::settings), MosqueSchedule.CONFIGURABLE.associateWith(read.schedule::settings))
        }
        assertTrue(MosqueSettingsFile.write(custom).contains("\"isha\": { \"iqamah\": \"20:05\", \"duration\": 11 }"))
    }

    // 460 as gouvernorats.json has it, with a space after it.
    private val places = mapOf(615 to "مدينة تونس", 101 to "صفاقس المدينة", 700 to "المرسى", 801 to "الوسط", 802 to "الوسط", 460 to "بني خيار ")
    private val catalog = ProfileCatalog(
        delegationName = { id -> places[id] },
        themes = mapOf("horizon" to "أفق", "midad" to "مداد"),
        delegationIds = { places.keys.toList() },
    )

    private fun profileParse(text: String, profile: MosqueProfile = MosqueProfile("مسجد الفتح", 615, "horizon")) =
        MosqueSettingsFile.parse(text, current, emptyMap(), profile, catalog)

    @Test
    fun aFileCanSetTheMosqueNamePlaceAndTheme() {
        val result = assertIs<ParseResult.Success>(profileParse(
            """{ "mosque": { "name": "  مسجد   النور ", "delegation": "١٠١", "delegationName": "صفاقس المدينة" }, "display": { "theme": "مداد" } }"""))
        assertEquals(MosqueProfile("مسجد النور", 101, "midad"), result.profile)
        assertEquals(
            listOf(
                MosqueSettingsFile.ProfileChange(MosqueSettingsFile.ProfileField.NAME, "مسجد الفتح", "مسجد النور"),
                MosqueSettingsFile.ProfileChange(MosqueSettingsFile.ProfileField.DELEGATION, "مدينة تونس", "صفاقس المدينة"),
                MosqueSettingsFile.ProfileChange(MosqueSettingsFile.ProfileField.THEME, "أفق", "مداد"),
            ),
            result.profileChanges,
        )
        assertTrue(result.hasChanges)
    }

    @Test
    fun unknownPlacesThemesAndOverlongNamesAreRefused() {
        val failure = assertIs<ParseResult.Failure>(profileParse(
            """{ "mosque": { "name": "${"م".repeat(61)}", "delegation": 999 }, "display": { "theme": "pink" } }"""))
        assertEquals(
            listOf(ErrorCode.INVALID_NAME to "mosque.name", ErrorCode.UNKNOWN_DELEGATION to "mosque.delegation", ErrorCode.UNKNOWN_THEME to "display.theme"),
            failure.errors.map { it.code to it.path },
        )
        // Without the app's catalog, a place cannot be checked, so it is refused.
        assertEquals(listOf(ErrorCode.UNKNOWN_DELEGATION to "mosque.delegation"), errors("""{ "mosque": { "delegation": 615 } }"""))
    }

    @Test
    fun aWholeTvSurvivesAWriteAndRead() {
        val profile = MosqueProfile("مسجد \"الرحمة\"", 101, "midad")
        val schedule = MosqueSchedule.DEFAULT.with(Prayer.ISHA, PrayerSettings(IqamahRule.FixedTime(LocalTime.of(20, 0)), 12))
        val text = MosqueSettingsFile.write(schedule, emptyMap(), profile, catalog)
        assertTrue(text.contains("\"delegationName\": \"صفاقس المدينة\""))
        val read = assertIs<ParseResult.Success>(MosqueSettingsFile.parse(text, MosqueSchedule(), emptyMap(), MosqueProfile(), catalog))
        assertEquals(profile, read.profile)
        assertEquals(schedule.settings(Prayer.ISHA), read.schedule.settings(Prayer.ISHA))
        // The same TV reading its own file sees nothing new.
        val same = assertIs<ParseResult.Success>(MosqueSettingsFile.parse(text, schedule, emptyMap(), profile, catalog))
        assertTrue(!same.hasChanges)
    }

    @Test
    fun aCompleteSnapshotUndoesAnImportExactly() {
        val before = MosqueSchedule.DEFAULT
        val beforeProfile = MosqueProfile("", 615, "horizon")
        // What the import will touch: Ramadan's Isha, the year 1448, the name.
        val snapshot = MosqueSettingsFile.write(before, mapOf(1448 to com.tunisianprayertimes.ManualIslamicDates()), beforeProfile, catalog, complete = true)
        val imported = assertIs<ParseResult.Success>(profileParse(
            """{ "mosque": { "name": "مسجد النور" }, "ramadan": { "isha": { "duration": 75 } }, "islamicDates": { "1448": { "eidFitr": "2027-03-10" } } }""",
            beforeProfile,
        ))
        val undo = assertIs<ParseResult.Success>(
            MosqueSettingsFile.parse(snapshot, imported.schedule, imported.islamicDates, imported.profile, catalog))
        assertEquals(before, undo.schedule)
        assertEquals(beforeProfile, undo.profile)
        assertEquals(mapOf(1448 to com.tunisianprayertimes.ManualIslamicDates()), undo.islamicDates)
    }

    @Test
    fun displayOptionsAreReadCheckedAndWrittenBack() {
        val result = assertIs<ParseResult.Success>(profileParse(
            """{ "display": { "weather": false, "backgrounds": "نعم", "announcements": true, "slideSeconds": "٢٠", "announcementsEveryMinutes": 15 } }"""))
        assertEquals(DisplayOptions(false, true, true, 20, 15), result.profile.display)
        assertEquals(5, result.profileChanges.size)
        val written = MosqueSettingsFile.write(MosqueSchedule.DEFAULT, profile = result.profile, catalog = catalog)
        val read = assertIs<ParseResult.Success>(MosqueSettingsFile.parse(written, MosqueSchedule.DEFAULT, emptyMap(), result.profile, catalog))
        assertTrue(!read.hasChanges)
        val bad = assertIs<ParseResult.Failure>(profileParse("""{ "display": { "weather": "maybe", "slideSeconds": 2 } }"""))
        assertEquals(listOf(ErrorCode.INVALID_OPTION to "display.weather", ErrorCode.INVALID_OPTION to "display.slideSeconds"),
            bad.errors.map { it.code to it.path })
    }

    @Test
    fun theNightScreenIsASwitchLikeTheOthers() {
        // Unset on a TV that never had the option: a file turning it off shows as a change.
        val off = assertIs<ParseResult.Success>(profileParse("""{ "display": { "nightScreen": false } }"""))
        assertEquals(false, off.profile.display.nightScreen)
        assertEquals(listOf(MosqueSettingsFile.ProfileChange(MosqueSettingsFile.ProfileField.NIGHT_SCREEN, "—", "false")), off.profileChanges)
        // Its Arabic name and a hand-written "yes", on a TV that has it off.
        val tvWithoutNight = MosqueProfile(display = DisplayOptions(nightScreen = false))
        val on = assertIs<ParseResult.Success>(profileParse("""{ "العرض": { "شاشة الليل": "نعم" } }""", tvWithoutNight))
        assertEquals(true, on.profile.display.nightScreen)
        assertEquals(listOf(MosqueSettingsFile.ProfileChange(MosqueSettingsFile.ProfileField.NIGHT_SCREEN, "false", "true")), on.profileChanges)
        // Written back by the TV, read back to the same state.
        val written = MosqueSettingsFile.write(MosqueSchedule.DEFAULT, profile = on.profile, catalog = catalog)
        assertTrue(written.contains("\"nightScreen\": true"), written)
        val copied = assertIs<ParseResult.Success>(MosqueSettingsFile.parse(written, MosqueSchedule.DEFAULT, emptyMap(), MosqueProfile(), catalog))
        assertEquals(true, copied.profile.display.nightScreen)
        val same = assertIs<ParseResult.Success>(MosqueSettingsFile.parse(written, MosqueSchedule.DEFAULT, emptyMap(), on.profile, catalog))
        assertTrue(!same.hasChanges)
        // Mistakes are refused as for the other switches.
        val bad = assertIs<ParseResult.Failure>(profileParse("""{ "display": { "nightScreen": 2 } }"""))
        assertEquals(listOf(ErrorCode.INVALID_OPTION to "display.nightScreen"), bad.errors.map { it.code to it.path })
        assertEquals("«nightScreen» يكون true (تشغيل) أو false (إيقاف)", bad.errors.single().message)
        assertEquals(
            listOf(ErrorCode.DUPLICATE_FIELD to "display.شاشة الليل"),
            assertIs<ParseResult.Failure>(profileParse("""{ "display": { "nightScreen": true, "شاشة الليل": false } }""")).errors.map { it.code to it.path },
        )
        val typo = assertIs<ParseResult.Failure>(profileParse("""{ "display": { "nightScren": false } }"""))
        assertEquals(listOf(ErrorCode.UNKNOWN_FIELD to "display.nightScren"), typo.errors.map { it.code to it.path })
        assertTrue(typo.errors.single().message.contains("\"nightScreen\""), "the fields a display section takes are named")
    }

    @Test
    fun theAdhanScreensMinutesAreANumberFromOneToFive() {
        val three = assertIs<ParseResult.Success>(profileParse("""{ "display": { "adhanScreenMinutes": 3 } }"""))
        assertEquals(3, three.profile.display.adhanScreenMinutes)
        assertEquals(listOf(MosqueSettingsFile.ProfileChange(MosqueSettingsFile.ProfileField.ADHAN_SCREEN, "—", "3")), three.profileChanges)
        // Its Arabic name and Arabic digits, on a TV at 2 minutes.
        val tv = MosqueProfile(display = DisplayOptions(adhanScreenMinutes = 2))
        val five = assertIs<ParseResult.Success>(profileParse("""{ "العرض": { "مدة شاشة الأذان": "٥" } }""", tv))
        assertEquals(listOf(MosqueSettingsFile.ProfileChange(MosqueSettingsFile.ProfileField.ADHAN_SCREEN, "2", "5")), five.profileChanges)
        // Written back by the TV, read back to the same state.
        val written = MosqueSettingsFile.write(MosqueSchedule.DEFAULT, profile = five.profile, catalog = catalog)
        assertTrue(written.contains("\"adhanScreenMinutes\": 5"), written)
        val same = assertIs<ParseResult.Success>(MosqueSettingsFile.parse(written, MosqueSchedule.DEFAULT, emptyMap(), five.profile, catalog))
        assertTrue(!same.hasChanges)
        // Out of 1..5 is refused, with the range.
        listOf("0", "6", "\"two\"").forEach { value ->
            val bad = assertIs<ParseResult.Failure>(profileParse("""{ "display": { "adhanScreenMinutes": $value } }"""))
            assertEquals(listOf(ErrorCode.INVALID_OPTION to "display.adhanScreenMinutes"), bad.errors.map { it.code to it.path })
            assertTrue(bad.errors.single().message.contains("بين 1 و5"), bad.errors.single().message)
        }
    }

    @Test
    fun anEditedPlaceNameIsFollowedWhenItNamesOnePlace() {
        fun place(mosque: String) = assertIs<ParseResult.Success>(profileParse("""{ "mosque": $mosque }""")).profile.delegationId
        // The TV's own file with only the name edited: the place follows the name.
        assertEquals(101, place("""{ "delegation": 615, "delegationName": " صفاقس  المدينة " }"""))
        assertEquals(700, place("""{ "delegationName": "المرسى" }"""))
        // The number changed and the name left as it was: the number wins; a name that agrees says nothing.
        assertEquals(101, place("""{ "delegation": 101, "delegationName": "مدينة تونس" }"""))
        assertEquals(615, place("""{ "delegation": 615, "delegationName": "مدينة تونس" }"""))
        // A name that names no place, or two, or another place than a new number: refused on the name.
        for (mosque in listOf(
            """{ "delegation": 615, "delegationName": "صفاقس" }""",
            """{ "delegationName": "الوسط" }""",
            """{ "delegation": 101, "delegationName": "المرسى" }""",
        )) {
            val failure = assertIs<ParseResult.Failure>(profileParse("""{ "mosque": $mosque }"""), mosque)
            assertEquals(listOf(ErrorCode.UNKNOWN_DELEGATION to "mosque.delegationName"), failure.errors.map { it.code to it.path }, mosque)
        }
    }

    @Test
    fun aCatalogNameWithStraySpacesMatchesAsWritten() {
        val atBeniKhiar = MosqueProfile("مسجد الفتح", 460, "horizon")
        fun place(mosque: String, profile: MosqueProfile = atBeniKhiar) =
            assertIs<ParseResult.Success>(profileParse("""{ "mosque": $mosque }""", profile), mosque).profile.delegationId
        // The TV's own file («بني خيار » as it writes it) with only the number changed: the number wins.
        assertEquals(101, place("""{ "delegation": 101, "delegationName": "بني خيار " }"""))
        assertEquals(460, place("""{ "delegation": 460, "delegationName": "بني خيار" }"""))
        // Found by a name typed without the catalog's space, or with one too many.
        assertEquals(460, place("""{ "delegation": 615, "delegationName": "بني  خيار" }""", MosqueProfile("مسجد الفتح", 615, "horizon")))
    }

    @Test
    fun samplesInMessagesStayLeftToRight() {
        fun message(text: String) = assertIs<ParseResult.Failure>(MosqueSettingsFile.parse(text, current)).errors.first().message
        val iqamah = message("""{ "prayers": { "isha": { "iqamah": "8h" } } }""")
        assertTrue(iqamah.contains("$LRI+10$PDI") && iqamah.contains("${LRI}20:00$PDI"), iqamah)
        assertTrue(message("""{ "version": 2, "prayers": {} }""").contains("$LRI\"version\": 1$PDI"))
        assertTrue(message("""{ "adhkar": { "afterSalah": 3 } }""").contains("$LRI{ \"mode\": \"append\", \"items\": [...] }$PDI"))
        assertTrue(message("""{ "prayers": { "isha": { "iqama": "+10", "wait": 5 } } }""").contains("$LRI\"iqamah\"$PDI أو $LRI\"duration\"$PDI"))
        assertTrue(message("""{ "islamicDates": { "1448": { "eidFitr": "10/03" } } }""").contains("${LRI}2027-02-08$PDI"))
        // Every isolate the messages open, they close.
        val all = listOf(iqamah, message("""{ "announcements": [ 5 ] }"""), message("""{ "ramadan": "x" }"""))
        for (text in all) assertEquals(text.count { it == LRI }, text.count { it == PDI }, text)
    }

    @Test
    fun aRefusedSignedValueIsQuotedLeftToRight() {
        fun message(result: ParseResult) = assertIs<ParseResult.Failure>(result).errors.single().message
        fun prayers(text: String) = message(MosqueSettingsFile.parse(text, current))
        // «-1», not «1-»: the file's value is an isolate wherever the message quotes it.
        assertTrue("(في الملف: $LRI-1$PDI)" in message(profileParse("""{ "display": { "adhanScreenMinutes": -1 } }""")))
        assertTrue("(في الملف: $LRI-5$PDI)" in message(profileParse("""{ "display": { "slideSeconds": -5 } }""")))
        assertTrue("«$LRI-1$PDI»" in message(profileParse("""{ "mosque": { "delegation": -1 } }""")))
        assertTrue("(في الملف: $LRI-3$PDI)" in message(formulaParse("""{ "prayerTimes": { "dhuhrMinutes": -3 } }""")))
        assertTrue("(في الملف: $LRI-18$PDI)" in message(formulaParse("""{ "prayerTimes": { "fajrAngle": -18 } }""")))
        assertTrue("«$LRI-5$PDI»" in prayers("""{ "prayers": { "isha": { "iqamah": "-5" } } }"""))
        assertTrue("«$LRI-5$PDI»" in prayers("""{ "prayers": { "fajr": { "duration": -5 } } }"""))
        assertTrue("«$LRI-10$PDI»" in prayers("""{ "prayers": { "jumua": { "khutba": -10 } } }"""))
        // So is a Hijri year's key, the file's one numeric key.
        val year = prayers("""{ "islamicDates": { "-1448": { "ramadanStart": "2027-02-08" } } }""")
        assertTrue("«$LRI-1448$PDI»" in year, year)
        val twice = prayers("""{ "islamicDates": { "1448": { "eidAdha": "2027-05-17" }, "+1448": { "eidAdha": "2027-05-17" } } }""")
        assertTrue("«${LRI}1448$PDI» و«$LRI+1448$PDI»" in twice, twice)
        // A direction mark in the file cannot close the isolate before the value ends.
        val marked = prayers("""{ "prayers": { "isha": { "iqamah": "-5⁩ x" } } }""")
        assertTrue("«$LRI-5 x$PDI»" in marked, marked)
    }

    @Test
    fun anEidIqamahCountsFromSunriseInItsMessages() {
        fun messages(text: String) = assertIs<ParseResult.Failure>(MosqueSettingsFile.parse(text, current)).errors.map { it.message }
        val far = messages("""{ "prayers": { "eidAdha": { "iqamah": "+120" } } }""").single()
        assertTrue(far.contains("بعد الشروق") && !far.contains("الأذان"), far)
        val both = messages("""{ "prayers": { "eid": { "iqamah": "8h" } } }""")
        assertEquals(2, both.size)
        assertTrue(both.all { it.contains("بعد الشروق") && !it.contains("الأذان") }, both.toString())
        assertTrue(messages("""{ "prayers": { "isha": { "iqamah": "+120" } } }""").single().contains("بعد الأذان"))
    }

    @Test
    fun aHostileFileGetsAScreenOfMistakesNotThousands() {
        val keys = (0 until 5_000).joinToString(",") { "\"a$it\": 0" }
        val failure = assertIs<ParseResult.Failure>(MosqueSettingsFile.parse("{ $keys }", current))
        assertEquals(MosqueSettingsFile.MAX_ERRORS, failure.errors.size)
        assertEquals(5_000 - MosqueSettingsFile.MAX_ERRORS, failure.more)
        // A key hundreds of thousands of letters long is quoted short.
        val long = assertIs<ParseResult.Failure>(MosqueSettingsFile.parse("{ \"${"x".repeat(300_000)}\": 0 }", current))
        assertTrue(long.errors.single().message.length < 200)
    }

    @Test
    fun jsonMistakesSayWhereTheyAre() {
        fun message(text: String) = assertIs<ParseResult.Failure>(MosqueSettingsFile.parse(text, current)).errors.single().message
        val comma = message("{\n  \"prayers\": {\n    \"isha\": { \"iqamah\": \"+10\" }،\n    \"fajr\": { \"iqamah\": \"+20\" }\n  }\n}")
        assertTrue(comma.contains("السطر 3") && comma.contains("«،»"), comma)
        val quotes = message("{\n  // “ in a comment is fine\n  \"prayers\": { \"isha\": { \"iqamah\": “20:00” } }\n}")
        assertTrue(quotes.contains("السطر 3") && quotes.contains("“"), quotes)
        val missing = message("{\n  \"prayers\": {\n    \"isha\": { \"iqamah\": \"+10\" }\n    \"fajr\": { \"iqamah\": \"+20\" }\n  }\n}")
        assertTrue(missing.contains("قرب السطر 4"), missing)
        // Inside a text, an Arabic comma is just a comma.
        assertEquals("درس، بعد العشاء", success("""{ "announcements": [ { "text": "درس، بعد العشاء" } ] }""").announcements.single().text)
    }

    @Test
    fun itemMistakesSayWhichItem() {
        val announcement = assertIs<ParseResult.Failure>(MosqueSettingsFile.parse(
            """{ "announcements": [ { "text": "أ" }, { "text": "ب" }, { "text": "ج", "until": "31/10" } ] }""", current)).errors.single()
        assertEquals("announcements[2]", announcement.path)
        assertTrue(announcement.message.startsWith("الإعلان 3: "), announcement.message)
        val dhikr = assertIs<ParseResult.Failure>(MosqueSettingsFile.parse(
            """{ "adhkar": { "ticker": [ { "id": "salah_salam" }, { "text": "دعاء" } ] } }""", current)).errors.single()
        assertTrue(dhikr.message.startsWith("شريط الأذكار، النص 2: "), dhikr.message)
    }

    @Test
    fun aFileSavedInAnEncodingTheTvCannotReadIsRefusedAsSuch() {
        // UTF-16 without its mark reads as letters between NULs; a broken byte as the replacement character.
        for (text in listOf("{\u0000 \u0000\"\u0000p\u0000", "{ \"mosque\": { \"name\": \"مسجد \uFFFD\uFFFD\" } }")) {
            assertEquals(listOf(ErrorCode.INVALID_ENCODING to ""), errors(text), text)
        }
    }

    private val custom = PrayerFormulaSettings(fajrAngle = 17.5, maghribMinutes = 3, adjustments = mapOf(Prayer.ISHA to 2, Prayer.FAJR to -1))

    private fun formulaParse(text: String, formula: PrayerFormulaSettings? = null) =
        MosqueSettingsFile.parse(text, current, emptyMap(), MosqueProfile(formula = formula))

    private fun formulaErrors(text: String): List<Pair<ErrorCode, String>> =
        assertIs<ParseResult.Failure>(formulaParse(text)).errors.map { it.code to it.path }

    private fun formulaChange(field: MosqueSettingsFile.FormulaField, before: String, after: String, prayer: Prayer? = null) =
        MosqueSettingsFile.FormulaChange(field, prayer, before, after)

    @Test
    fun aFileSetsThePrayerTimeValues() {
        val result = assertIs<ParseResult.Success>(formulaParse(
            """{ "prayerTimes": { "fajrAngle": 17.5, "ishaAngle": 18, "asrShadow": 1, "dhuhrMinutes": 7,
                                  "maghribMinutes": 3, "elevation": true, "adjust": { "isha": 2, "fajr": -1 } } }"""))
        assertEquals(custom, result.profile.formula)
        assertEquals(custom, result.formula)
        assertEquals(
            listOf(
                formulaChange(MosqueSettingsFile.FormulaField.FAJR_ANGLE, "18", "17.5"),
                formulaChange(MosqueSettingsFile.FormulaField.MAGHRIB_MINUTES, "2", "3"),
                formulaChange(MosqueSettingsFile.FormulaField.ADJUSTMENT, "0", "-1", Prayer.FAJR),
                formulaChange(MosqueSettingsFile.FormulaField.ADJUSTMENT, "0", "+2", Prayer.ISHA),
            ),
            result.formulaChanges,
        )
        assertTrue(result.hasChanges)
        assertTrue(result.changes.isEmpty() && result.profileChanges.isEmpty())
    }

    @Test
    fun theSectionReadsArabicKeysSignedStringsAndArabicIndicDigits() {
        val result = assertIs<ParseResult.Success>(formulaParse(
            """{ "حساب المواقيت": { "زاوية الفجر": "١٦٫٥", "زاوية العشاء": "17,5", "ظلّ العصر": "٢", "الظهر بعد الزوال": "٥",
                 "المغرب بعد الغروب": 3, "ارتفاع المكان": "لا", "تعديل": { "العشاء": "+2", "الصبح": "-١", "المغرب": "−3", "الظهر": 0 } } }"""))
        assertEquals(
            PrayerFormulaSettings(16.5, 17.5, 2, 5, 3, false, mapOf(Prayer.ISHA to 2, Prayer.FAJR to -1, Prayer.MAGHRIB to -3)),
            result.formula,
        )
        for (section in listOf("prayer_times", "formula", "PrayerTimes")) {
            assertEquals(2, assertIs<ParseResult.Success>(formulaParse("""{ "$section": { "asr_shadow": 2 } }""")).formula.asrShadow, section)
        }
        // The TV and the dashboard name it «حساب ارتفاع المسجد»: an admin copying that name is understood.
        for (key in listOf("حساب ارتفاع المسجد", "ارتفاع المسجد")) {
            assertEquals(false, assertIs<ParseResult.Success>(formulaParse("""{ "prayerTimes": { "$key": false } }""")).formula.elevation, key)
        }
    }

    @Test
    fun formulaMistakesAreRefusedWithTheAcceptedValues() {
        assertEquals(
            listOf(
                ErrorCode.INVALID_FORMULA to "prayerTimes.fajrAngle",
                ErrorCode.INVALID_FORMULA to "prayerTimes.ishaAngle",
                ErrorCode.INVALID_FORMULA to "prayerTimes.asrShadow",
                ErrorCode.INVALID_FORMULA to "prayerTimes.dhuhrMinutes",
                ErrorCode.INVALID_FORMULA to "prayerTimes.maghribMinutes",
                ErrorCode.INVALID_FORMULA to "prayerTimes.elevation",
                ErrorCode.UNKNOWN_FIELD to "prayerTimes.fajrAngel",
            ),
            formulaErrors(
                """{ "prayerTimes": { "fajrAngle": 17.3, "ishaAngle": 21, "asrShadow": 3, "dhuhrMinutes": 16,
                                      "maghribMinutes": -1, "elevation": "maybe", "fajrAngel": 16 } }"""),
        )
        for (bad in listOf("14.5", "\"high\"", "true", "{}")) {
            assertEquals(listOf(ErrorCode.INVALID_FORMULA to "prayerTimes.ishaAngle"), formulaErrors("""{ "prayerTimes": { "ishaAngle": $bad } }"""), bad)
        }
        assertEquals(listOf(ErrorCode.INVALID_FORMULA to "prayerTimes.maghribMinutes"), formulaErrors("""{ "prayerTimes": { "maghribMinutes": 11 } }"""))
        assertEquals(
            listOf(
                ErrorCode.INVALID_FORMULA to "prayerTimes.adjust.jumua",
                ErrorCode.INVALID_FORMULA to "prayerTimes.adjust.eid",
                ErrorCode.INVALID_FORMULA to "prayerTimes.adjust.الشروق",
                ErrorCode.UNKNOWN_PRAYER to "prayerTimes.adjust.fajer",
                ErrorCode.INVALID_FORMULA to "prayerTimes.adjust.isha",
                ErrorCode.INVALID_FORMULA to "prayerTimes.adjust.asr",
                ErrorCode.DUPLICATE_PRAYER to "prayerTimes.adjust.الفجر",
            ),
            formulaErrors(
                """{ "prayerTimes": { "adjust": { "jumua": 2, "eid": 2, "الشروق": 1, "fajer": 1, "isha": 16, "asr": "2.5",
                                                  "fajr": 1, "الفجر": 2 } } }"""),
        )
        assertEquals(listOf(ErrorCode.INVALID_FORMULA to "prayerTimes.adjust"), formulaErrors("""{ "prayerTimes": { "adjust": "+2" } }"""))
        assertEquals(listOf(ErrorCode.DUPLICATE_FIELD to "prayerTimes.زاوية الفجر"),
            formulaErrors("""{ "prayerTimes": { "fajrAngle": 16, "زاوية الفجر": 17 } }"""))
        assertEquals(listOf(ErrorCode.NOT_AN_OBJECT to "prayerTimes"), formulaErrors("""{ "prayerTimes": 18 }"""))
        val message = assertIs<ParseResult.Failure>(formulaParse("""{ "prayerTimes": { "fajrAngle": 17.3 } }""")).errors.single().message
        assertTrue("15" in message && "20" in message && "0.5" in message && "17.3" in message, message)
        val shuruk = assertIs<ParseResult.Failure>(formulaParse("""{ "prayerTimes": { "adjust": { "sunrise": 1 } } }""")).errors.single().message
        assertTrue("الشروق" in shuruk, shuruk)
        // Below the lower bound, as a number or a signed string; the bound itself is accepted.
        for (bad in listOf("-16", "\"-16\"")) {
            assertEquals(listOf(ErrorCode.INVALID_FORMULA to "prayerTimes.adjust.fajr"), formulaErrors("""{ "prayerTimes": { "adjust": { "fajr": $bad } } }"""), bad)
        }
        assertEquals(-15, assertIs<ParseResult.Success>(formulaParse("""{ "prayerTimes": { "adjust": { "fajr": "-15" } } }""")).formula.adjustment(Prayer.FAJR))
        // The file's signed value kept left to right, as the bounds are: not «16-».
        val below = assertIs<ParseResult.Failure>(formulaParse("""{ "prayerTimes": { "adjust": { "isha": "-16" } } }""")).errors.single().message
        assertTrue("(في الملف: $LRI-16$PDI)" in below, below)
        // The accepted values in the sentence, then only the file's value in parentheses.
        val minutes = assertIs<ParseResult.Failure>(formulaParse("""{ "prayerTimes": { "dhuhrMinutes": 20 } }""")).errors.single().message
        assertTrue(minutes.endsWith("بين 0 و15، والرسمي 7 (في الملف: ${LRI}20$PDI)"), minutes)
        for (text in listOf("""{ "prayerTimes": { "asrShadow": 3 } }""", """{ "prayerTimes": { "elevation": 3 } }""")) {
            val refused = assertIs<ParseResult.Failure>(formulaParse(text)).errors.single().message
            assertTrue(") (" !in refused && refused.endsWith("(في الملف: ${LRI}3$PDI)"), refused)
        }
    }

    @Test
    fun absentFormulaFieldsStayAndNullReturnsToOfficial() {
        val tv = custom.copy(asrShadow = 2)
        val partial = assertIs<ParseResult.Success>(formulaParse("""{ "prayerTimes": { "maghribMinutes": 4, "adjust": { "dhuhr": 1 } } }""", tv))
        assertEquals(tv.copy(maghribMinutes = 4).withAdjustment(Prayer.DHUHR, 1), partial.formula)
        assertEquals(
            listOf(
                formulaChange(MosqueSettingsFile.FormulaField.MAGHRIB_MINUTES, "3", "4"),
                formulaChange(MosqueSettingsFile.FormulaField.ADJUSTMENT, "0", "+1", Prayer.DHUHR),
            ),
            partial.formulaChanges,
        )
        val nullFields = assertIs<ParseResult.Success>(formulaParse("""{ "prayerTimes": { "fajrAngle": null, "adjust": { "isha": null } } }""", tv))
        assertEquals(tv.copy(fajrAngle = 18.0, adjustments = mapOf(Prayer.FAJR to -1)), nullFields.formula)
        val noAdjustments = assertIs<ParseResult.Success>(formulaParse("""{ "prayerTimes": { "adjust": null } }""", tv))
        assertEquals(tv.copy(adjustments = emptyMap()), noAdjustments.formula)
        // The whole section null: INM's values again, stored as "not set".
        val official = assertIs<ParseResult.Success>(formulaParse("""{ "prayerTimes": null }""", tv))
        assertEquals(null, official.profile.formula)
        assertTrue(official.formula.isOfficial)
        assertEquals(5, official.formulaChanges.size)
        // Every value set back by hand is the same as null.
        val byHand = assertIs<ParseResult.Success>(formulaParse(
            """{ "prayerTimes": { "fajrAngle": 18, "maghribMinutes": 2, "adjust": { "fajr": 0, "isha": "0" } } }""", custom))
        assertEquals(MosqueProfile(), byHand.profile)
        // A TV already official reading null changes nothing.
        assertTrue(!assertIs<ParseResult.Success>(formulaParse("""{ "prayerTimes": null }""")).hasChanges)
    }

    @Test
    fun aFileWithOnlyThePrayerTimeValuesIsAFile() {
        assertEquals(2, success("""{ "prayerTimes": { "asrShadow": 2 } }""").formula.asrShadow)
        assertTrue(success("""{ "prayerTimes": null }""").formula.isOfficial)
        assertEquals(listOf(ErrorCode.NO_PRAYERS to "prayers"), errors("""{ "prayerTimes": {} }"""))
        // A note, or an empty "adjust", is a section that changes nothing.
        for (text in listOf("""{ "prayerTimes": { "note": "x" } }""", """{ "prayerTimes": { "adjust": {} } }""")) {
            val result = success(text)
            assertTrue(result.formula.isOfficial && !result.hasChanges, text)
        }
    }

    @Test
    fun officialValuesAlwaysReadAsNotSet() {
        // Without the section too: a TV handing an explicit OFFICIAL over gets the same profile as an official one.
        val result = assertIs<ParseResult.Success>(formulaParse("""{ "display": { "weather": false } }""", PrayerFormulaSettings.OFFICIAL))
        assertEquals(null, result.profile.formula)
        assertTrue(result.formulaChanges.isEmpty())
        assertEquals(custom, assertIs<ParseResult.Success>(formulaParse("""{ "display": { "weather": false } }""", custom)).profile.formula)
    }

    @Test
    fun customValuesSurviveAWriteAndRead() {
        val text = MosqueSettingsFile.write(MosqueSchedule.DEFAULT, profile = MosqueProfile(formula = custom))
        assertTrue(
            "  \"prayerTimes\": { \"fajrAngle\": 17.5, \"ishaAngle\": 18, \"asrShadow\": 1, \"dhuhrMinutes\": 7, \"maghribMinutes\": 3, " +
                "\"elevation\": true, \"adjust\": { \"fajr\": -1, \"dhuhr\": 0, \"asr\": 0, \"maghrib\": 0, \"isha\": 2 } },\n" in text,
            text,
        )
        val other = PrayerFormulaSettings(fajrAngle = 16.0, asrShadow = 2, elevation = false, adjustments = mapOf(Prayer.ASR to 5))
        // Written whole: another TV's own values are all replaced.
        val read = assertIs<ParseResult.Success>(formulaParse(text, other))
        assertEquals(custom, read.formula)
        assertTrue(!assertIs<ParseResult.Success>(formulaParse(text, custom)).hasChanges)
        val hanafi = MosqueSettingsFile.write(MosqueSchedule.DEFAULT, profile = MosqueProfile(formula = other))
        assertEquals(other, assertIs<ParseResult.Success>(formulaParse(hanafi)).formula)
        assertTrue("\"ishaAngle\": 18, \"asrShadow\": 2" in hanafi && "\"elevation\": false" in hanafi && "\"asr\": 5" in hanafi, hanafi)
        // A complete file carries the custom values over another TV's the same way.
        val complete = MosqueSettingsFile.write(MosqueSchedule.DEFAULT, profile = MosqueProfile(formula = custom), complete = true)
        assertTrue("\"prayerTimes\": null" !in complete, complete)
        assertEquals(custom, assertIs<ParseResult.Success>(formulaParse(complete, other)).formula)
    }

    @Test
    fun officialValuesAreWrittenOnlyInACompleteFile() {
        for (profile in listOf(MosqueProfile(), MosqueProfile(formula = PrayerFormulaSettings.OFFICIAL))) {
            assertTrue("prayerTimes" !in MosqueSettingsFile.write(MosqueSchedule.DEFAULT, profile = profile))
            assertTrue("  \"prayerTimes\": null,\n" in MosqueSettingsFile.write(MosqueSchedule.DEFAULT, profile = profile, complete = true))
        }
        // The snapshot of an official TV undoes an import of custom values.
        val snapshot = MosqueSettingsFile.write(MosqueSchedule.DEFAULT, profile = MosqueProfile(), complete = true)
        val imported = assertIs<ParseResult.Success>(formulaParse("""{ "prayerTimes": { "fajrAngle": 16 } }"""))
        val undo = assertIs<ParseResult.Success>(MosqueSettingsFile.parse(snapshot, imported.schedule, emptyMap(), imported.profile))
        assertEquals(MosqueProfile(), undo.profile)
        assertEquals(listOf(formulaChange(MosqueSettingsFile.FormulaField.FAJR_ANGLE, "16", "18")), undo.formulaChanges)
    }

    private companion object {
        const val LRI = '⁦'
        const val PDI = '⁩'
    }
}
