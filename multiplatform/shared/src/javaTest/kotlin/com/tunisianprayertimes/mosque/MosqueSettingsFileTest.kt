package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.Prayer
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
        assertEquals(text, result.content.afterSalah!!.items.single().text)
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

    private val catalog = ProfileCatalog(
        delegationName = { id -> mapOf(615 to "مدينة تونس", 101 to "صفاقس المدينة")[id] },
        themes = mapOf("midnight_navy" to "أزرق ليلي", "desert_gold" to "ذهبي"),
    )

    private fun profileParse(text: String, profile: MosqueProfile = MosqueProfile("مسجد الفتح", 615, "midnight_navy")) =
        MosqueSettingsFile.parse(text, current, emptyMap(), profile, catalog)

    @Test
    fun aFileCanSetTheMosqueNamePlaceAndTheme() {
        val result = assertIs<ParseResult.Success>(profileParse(
            """{ "mosque": { "name": "  مسجد   النور ", "delegation": "١٠١", "delegationName": "ignored" }, "display": { "theme": "ذهبي" } }"""))
        assertEquals(MosqueProfile("مسجد النور", 101, "desert_gold"), result.profile)
        assertEquals(
            listOf(
                MosqueSettingsFile.ProfileChange(MosqueSettingsFile.ProfileField.NAME, "مسجد الفتح", "مسجد النور"),
                MosqueSettingsFile.ProfileChange(MosqueSettingsFile.ProfileField.DELEGATION, "مدينة تونس", "صفاقس المدينة"),
                MosqueSettingsFile.ProfileChange(MosqueSettingsFile.ProfileField.THEME, "أزرق ليلي", "ذهبي"),
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
        val profile = MosqueProfile("مسجد \"الرحمة\"", 101, "desert_gold")
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
        val beforeProfile = MosqueProfile("", 615, "midnight_navy")
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
}
