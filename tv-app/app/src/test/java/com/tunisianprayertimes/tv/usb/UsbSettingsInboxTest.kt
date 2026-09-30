package com.tunisianprayertimes.tv.usb

import com.tunisianprayertimes.DateSource
import com.tunisianprayertimes.EventDate
import com.tunisianprayertimes.ManualIslamicDates
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.YearDates
import com.tunisianprayertimes.mosque.AdhkarContent
import com.tunisianprayertimes.mosque.CustomAdhkarList
import com.tunisianprayertimes.mosque.IqamahRule
import com.tunisianprayertimes.mosque.ReviewedDhikr
import com.tunisianprayertimes.mosque.TextAnnouncement
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.mosque.DisplayOptions
import com.tunisianprayertimes.mosque.MosqueProfile
import com.tunisianprayertimes.mosque.PrayerOverride
import com.tunisianprayertimes.mosque.ProfileCatalog
import com.tunisianprayertimes.mosque.PrayerSettings
import com.tunisianprayertimes.tv.data.InMemoryPreferences
import com.tunisianprayertimes.tv.data.PrefsManager
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSettingsInboxTest {

    private val root: File = Files.createTempDirectory("usb").toFile()
    private val store = InMemoryPreferences()
    private val prefs = PrefsManager(store)
    private var dates: Map<Int, ManualIslamicDates> = emptyMap()
    private var snapshot: String? = null
    private val places = mapOf(615 to (11 to "مدينة تونس"), 101 to (34 to "صفاقس المدينة"))
    private val catalog = ProfileCatalog({ places[it]?.second }, mapOf("horizon" to "أفق", "midad" to "مداد"))
    private val inbox = UsbSettingsInbox(
        readSchedule = { prefs.schedule },
        writeSchedule = { prefs.schedule = it },
        readHandled = { prefs.usbHandledSettings },
        writeHandled = { prefs.usbHandledSettings = it },
        readDates = { dates },
        writeDates = { dates = (dates + it).filterValues { value -> !value.isEmpty } },
        readProfile = { prefs.profile },
        writeProfile = { profile -> prefs.applyProfile(profile) { places[it] } },
        catalog = catalog,
        saveSnapshot = { snapshot = it },
        readContent = { prefs.adhkarContent },
        writeContent = { prefs.adhkarContent = it },
        readAnnouncements = { prefs.textAnnouncements },
        writeAnnouncements = { prefs.textAnnouncements = it },
        readSnapshot = { snapshot },
        today = { TODAY },
    )
    private val key = RemovableVolume(File(root, "key/Android/data/com.tunisianprayertimes.tv/files"))

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun putOnKey(text: String) = UsbSettings.settingsFile(key).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    @Test
    fun aKeyWithoutSettingsGetsATemplateThatIsNeverOffered() {
        assertTrue(inbox.scan(listOf(key)) is UsbScan.TemplateWritten)
        // Reboots and replugs with the key left in: the TV's own file stays quiet.
        assertEquals(UsbScan.Quiet, inbox.scan(listOf(key)))
        assertEquals(UsbScan.Quiet, inbox.scan(listOf(key)))
    }

    @Test
    fun anEditedTemplateIsOffered() {
        inbox.scan(listOf(key))
        putOnKey(UsbSettings.settingsFile(key).readText().replace("\"+5\"", "\"+7\""))
        val offer = inbox.scan(listOf(key)) as UsbScan.Offer
        val preview = inbox.preview(offer.found) as ParseResult.Success
        assertEquals(1, preview.changes.size)
    }

    @Test
    fun aFileWithNothingNewIsSettledSilently() {
        putOnKey("""{ "prayers": { "maghrib": { "iqamah": "+5", "duration": 8 } } }""") // the defaults
        assertEquals(UsbScan.Quiet, inbox.scan(listOf(key)))
    }

    @Test
    fun applyingUsesTheSettingsOfThatMomentNotOfTheScan() {
        // Scanned before onboarding saved anything...
        putOnKey("""{ "prayers": { "isha": { "iqamah": "20:00" } } }""")
        val offer = inbox.scan(listOf(key)) as UsbScan.Offer
        // ...then the admin finished onboarding with their own Fajr and Jumu'a.
        val fajr = PrayerSettings(IqamahRule.AfterAdhan(25), 12)
        val jumua = PrayerSettings(IqamahRule.FixedTime(LocalTime.of(13, 0)), 20)
        prefs.schedule = MosqueSchedule.DEFAULT.with(Prayer.FAJR, fajr).with(Prayer.JOMOAA, jumua)

        assertTrue(inbox.apply(offer.found))

        assertEquals(fajr, prefs.schedule.settings(Prayer.FAJR))
        assertEquals(jumua, prefs.schedule.settings(Prayer.JOMOAA))
        assertEquals(IqamahRule.FixedTime(LocalTime.of(20, 0)), prefs.schedule.settings(Prayer.ISHA).iqamah)
        assertEquals("applied files are not offered again", UsbScan.Quiet, inbox.scan(listOf(key)))
    }

    @Test
    fun aFileWithMistakesIsOfferedChangesNothingAndCanBeDismissed() {
        putOnKey("""{ "prayers": { "isha": { "iqamah": "25:00" } } }""")
        val offer = inbox.scan(listOf(key)) as UsbScan.Offer
        assertTrue(inbox.preview(offer.found) is ParseResult.Failure)
        val before = prefs.schedule
        assertFalse(inbox.apply(offer.found))
        assertEquals(before, prefs.schedule)
        assertEquals(UsbScan.Quiet, inbox.scan(listOf(key)))
    }

    @Test
    fun aDismissedFileIsOfferedAgainOnlyWhenItChanges() {
        putOnKey("""{ "prayers": { "isha": { "duration": 12 } } }""")
        inbox.dismiss((inbox.scan(listOf(key)) as UsbScan.Offer).found)
        assertEquals(UsbScan.Quiet, inbox.scan(listOf(key)))
        putOnKey("""{ "prayers": { "isha": { "duration": 14 } } }""")
        assertTrue(inbox.scan(listOf(key)) is UsbScan.Offer)
    }

    @Test
    fun ramadanAndEidDatesAloneAreOfferedAndApplied() {
        putOnKey("""{ "islamicDates": { "1448": { "ramadanStart": "2027-02-08" } } }""")
        val offer = inbox.scan(listOf(key)) as UsbScan.Offer
        val preview = inbox.preview(offer.found) as ParseResult.Success
        assertTrue(preview.changes.isEmpty())
        assertEquals(1, preview.dateChanges.size)
        assertTrue(inbox.apply(offer.found))
        assertEquals(mapOf(1448 to ManualIslamicDates(ramadanStart = LocalDate.of(2027, 2, 8))), dates)
    }

    @Test
    fun theTemplateCarriesRamadanSettingsAndTheAdminsDates() {
        prefs.schedule = MosqueSchedule.DEFAULT.withRamadan(Prayer.ISHA, PrayerOverride(salahMinutes = 75))
        dates = mapOf(1448 to ManualIslamicDates(eidFitr = LocalDate.of(2027, 3, 10)))
        assertTrue(inbox.scan(listOf(key)) is UsbScan.TemplateWritten)
        val text = UsbSettings.settingsFile(key).readText()
        assertTrue(text, text.contains("\"ramadan\"") && text.contains("\"duration\": 75"))
        assertTrue(text, text.contains("\"eidFitr\": \"2027-03-10\""))
        assertTrue(text, text.contains("\"eidAdha\": { \"iqamah\": \"+30\""))
    }

    @Test
    fun oneTvsFileSetsUpAnotherTv() {
        // A configured TV writes its whole settings to a new key...
        val configured = PrefsManager(InMemoryPreferences()).apply {
            mosqueName = "مسجد النور"
            applyProfile(MosqueProfile(delegationId = 101, themeId = "midad")) { places[it] }
            schedule = MosqueSchedule.DEFAULT.with(Prayer.ISHA, PrayerSettings(IqamahRule.FixedTime(LocalTime.of(20, 0)), 12))
        }
        val text = UsbSettingsInbox({ configured.schedule }, {}, { "" }, {}, readProfile = { configured.profile }, catalog = catalog).currentFile()
        // ...and a new TV applies it.
        putOnKey(text)
        val offer = inbox.scan(listOf(key)) as UsbScan.Offer
        assertTrue(inbox.apply(offer.found))
        assertEquals(MosqueProfile("مسجد النور", 101, "midad"), prefs.profile.copy(display = DisplayOptions()))
        assertEquals("صفاقس المدينة", prefs.delegationName)
        assertEquals(34, prefs.gouvernoratId)
        assertEquals(configured.schedule.settings(Prayer.ISHA), prefs.schedule.settings(Prayer.ISHA))
    }

    @Test
    fun onboardingOffersOnlyAFileThatNamesThePlace() {
        // Nothing on the key, or a file without a place: the wizard it is, and the key is left alone.
        assertEquals(null, inbox.setupFile(listOf(key)))
        assertFalse(UsbSettings.settingsFile(key).exists())
        putOnKey("""{ "prayers": { "isha": { "iqamah": "20:00" } } }""")
        assertEquals(null, inbox.setupFile(listOf(key)))
        putOnKey("""{ "mosque": { "delegation": 999 } }""")
        assertEquals(null, inbox.setupFile(listOf(key)))
        // Another TV's file sets up this one; it stays on offer until it is applied.
        putOnKey("""{ "mosque": { "name": "مسجد النور", "delegation": 101 }, "prayers": { "isha": { "iqamah": "20:00" } } }""")
        val found = inbox.setupFile(listOf(key))!!
        assertEquals("", prefs.usbHandledSettings)
        assertTrue(inbox.apply(found))
        assertEquals(101, prefs.delegationId)
        assertEquals(34, prefs.gouvernoratId)
        assertEquals(IqamahRule.FixedTime(LocalTime.of(20, 0)), prefs.schedule.settings(Prayer.ISHA).iqamah)
        assertEquals("not offered again once onboarding is done", UsbScan.Quiet, inbox.scan(listOf(key)))
    }

    @Test
    fun theLastImportCanBeUndone() {
        prefs.mosqueName = "مسجد الفتح"
        prefs.applyProfile(MosqueProfile(delegationId = 615)) { places[it] }
        val before = prefs.schedule
        putOnKey("""{ "mosque": { "name": "مسجد آخر", "delegation": 101 }, "ramadan": { "isha": { "duration": 75 } },
            "islamicDates": { "1448": { "eidFitr": "2027-03-10" } } }""")
        assertTrue(inbox.apply((inbox.scan(listOf(key)) as UsbScan.Offer).found))
        assertEquals("مسجد آخر", prefs.mosqueName)

        val undo = UsbSettingsFound(File(root, "previous-settings.json"), snapshot!!, "undo")
        assertTrue(inbox.apply(undo, fromKey = false))
        assertEquals(MosqueProfile("مسجد الفتح", 615, PrefsManager.DEFAULT_THEME_ID), prefs.profile.copy(display = DisplayOptions()))
        assertEquals(before, prefs.schedule)
        assertEquals(emptyMap<Int, ManualIslamicDates>(), dates)
        assertFalse("undo leaves the key's file handled", "undo" in prefs.usbHandledSettings.split(' '))
    }

    @Test
    fun undoReturnsTheTvsDatesEvenAgainstALaterAnnouncement() {
        // Eid al-Fitr 1448 was set for 2027-03-11; Ramadan was announced since for 2027-02-08, which makes it 31 days.
        val ramadan = LocalDate.of(2027, 2, 8)
        val adha = LocalDate.of(2027, 5, 16)
        val announced = YearDates(
            1448,
            EventDate(ramadan, DateSource.OFFICIAL, ramadan, announced = true),
            EventDate(LocalDate.of(2027, 3, 11), DateSource.MANUAL, LocalDate.of(2027, 3, 10), announced = false),
            EventDate(adha, DateSource.ESTIMATE, adha, announced = false),
        )
        dates = mapOf(1448 to ManualIslamicDates(eidFitr = LocalDate.of(2027, 3, 11)))
        val tv = UsbSettingsInbox(
            readSchedule = { prefs.schedule },
            writeSchedule = { prefs.schedule = it },
            readHandled = { prefs.usbHandledSettings },
            writeHandled = { prefs.usbHandledSettings = it },
            readDates = { dates },
            writeDates = { dates = (dates + it).filterValues { value -> !value.isEmpty } },
            saveSnapshot = { snapshot = it },
            yearDates = { year -> announced.takeIf { year == 1448 } },
            readSnapshot = { snapshot },
        )
        fun phone(fitr: String) = UsbSettingsFound(File("dashboard"), """{ "islamicDates": { "1448": { "eidFitr": "$fitr" } } }""", "dashboard")
        assertTrue(tv.apply(phone("2027-03-10"), fromKey = false))
        assertEquals(LocalDate.of(2027, 3, 10), dates.getValue(1448).eidFitr)
        // The phone cannot set the old date again: Ramadan would be 31 days.
        val refused = tv.preview(phone("2027-03-11")) as ParseResult.Failure
        assertEquals(MosqueSettingsFile.ErrorCode.DATES_CONFLICT, refused.errors.single().code)
        // Undoing the import can: the TV returns to exactly what it had.
        assertTrue(tv.apply(UsbSettingsFound(File(root, "previous-settings.json"), snapshot!!, "undo"), fromKey = false))
        assertEquals(LocalDate.of(2027, 3, 11), dates.getValue(1448).eidFitr)
    }

    @Test
    fun aMosquesOwnTextsAreAppliedKeptAndUndone() {
        putOnKey("""{ "adhkar": { "afterSalah": { "mode": "append",
            "items": [ { "text": "سبحان الله وبحمده", "reference": "صحيح مسلم 2692", "count": 100 } ] } } }""")
        val offer = inbox.scan(listOf(key)) as UsbScan.Offer
        assertEquals(1, (inbox.preview(offer.found) as ParseResult.Success).contentChanges.size)
        assertTrue(inbox.apply(offer.found))
        // Kept across a restart, and written into the file another TV would copy.
        val reloaded = PrefsManager(store).adhkarContent
        assertEquals(100, (reloaded.afterSalah?.items?.single() as com.tunisianprayertimes.mosque.CustomDhikr).count)
        assertTrue(inbox.currentFile().contains("\"adhkar\""))
        // Undo returns to the bundled texts.
        assertTrue(inbox.apply(UsbSettingsFound(File(root, "previous-settings.json"), snapshot!!, "undo"), fromKey = false))
        assertTrue(prefs.adhkarContent.isBundled)
    }

    @Test
    fun returningToTheBundledTextsFromTheRemoteCanBeUndone() {
        val list = """{ "adhkar": { "afterSalah": { "mode": "replace", "items": [ { "id": "salah_istighfar", "count": 5 }, { "id": "sayyid_istighfar" } ] } } }"""
        assertTrue(inbox.apply(UsbSettingsFound(File("dashboard"), list, "dashboard"), fromKey = false))
        val mosqueList = prefs.adhkarContent
        assertFalse(mosqueList.isBundled)
        // What the TV's "استعادة النصوص المضمّنة" does, after its confirmation.
        assertTrue(inbox.apply(UsbSettingsFound(File("tv"), """{ "adhkar": null }""", "bundled-texts"), fromKey = false))
        assertTrue(prefs.adhkarContent.isBundled)
        assertTrue(inbox.apply(UsbSettingsFound(File(root, "previous-settings.json"), snapshot!!, "undo"), fromKey = false))
        assertEquals(mosqueList, prefs.adhkarContent)
    }

    @Test
    fun theNightScreenTravelsInTheFileAndItsImportCanBeUndone() {
        assertTrue(inbox.scan(listOf(key)) is UsbScan.TemplateWritten)
        val template = UsbSettings.settingsFile(key).readText()
        assertTrue(template, template.contains("\"nightScreen\": true"))
        putOnKey(template.replace("\"nightScreen\": true", "\"nightScreen\": false"))
        val offer = inbox.scan(listOf(key)) as UsbScan.Offer
        val preview = inbox.preview(offer.found) as ParseResult.Success
        assertEquals(listOf(MosqueSettingsFile.ProfileField.NIGHT_SCREEN), preview.profileChanges.map { it.field })
        assertTrue(inbox.apply(offer.found))
        assertFalse(prefs.nightScreenEnabled)
        assertTrue(inbox.apply(UsbSettingsFound(File(root, "previous-settings.json"), snapshot!!, "undo"), fromKey = false))
        assertTrue(prefs.nightScreenEnabled)
    }

    @Test
    fun theAdhanScreensMinutesTravelInTheFile() {
        assertTrue(inbox.scan(listOf(key)) is UsbScan.TemplateWritten)
        val template = UsbSettings.settingsFile(key).readText()
        assertTrue(template, template.contains("\"adhanScreenMinutes\": 2"))
        putOnKey(template.replace("\"adhanScreenMinutes\": 2", "\"adhanScreenMinutes\": 3"))
        val offer = inbox.scan(listOf(key)) as UsbScan.Offer
        val preview = inbox.preview(offer.found) as ParseResult.Success
        assertEquals(listOf(MosqueSettingsFile.ProfileField.ADHAN_SCREEN), preview.profileChanges.map { it.field })
        assertTrue(inbox.apply(offer.found))
        assertEquals(3, prefs.adhanScreenMinutes)
    }

    @Test
    fun aTvUpdatedFromAnOldThemeStillAcceptsItsOwnFile() {
        // Saved before the «أفق» redesign: the template must not carry an id the TV now refuses.
        store.edit().putString("theme_id", "desert_sand").apply()
        inbox.scan(listOf(key))
        putOnKey(UsbSettings.settingsFile(key).readText().replace("\"+5\"", "\"+7\""))
        assertTrue(inbox.apply((inbox.scan(listOf(key)) as UsbScan.Offer).found))
        assertTrue(inbox.apply(UsbSettingsFound(File(root, "previous-settings.json"), snapshot!!, "undo"), fromKey = false))
    }

    @Test
    fun aKeyTheBoxHidesFromAppsIsReported() {
        assertEquals(UsbScan.Inaccessible, inbox.scan(emptyList(), HiddenVolumes(keptFromApps = 1)))
        assertEquals(UsbScan.Quiet, inbox.scan(emptyList(), HiddenVolumes()))
        // A new NTFS key: Android could not make the app's folder on it, so it is missing too.
        assertEquals(UsbScan.ReadOnly, inbox.scan(emptyList(), HiddenVolumes(readOnly = 1)))
    }

    private fun volume(name: String, readOnly: Boolean = false) =
        RemovableVolume(File(root, "$name/Android/data/com.tunisianprayertimes.tv/files"), readOnly)

    @Test
    fun aCardLeftInTheBoxNeverHidesAKeysFile() {
        // The card got the TV's template when it was first seen...
        val card = volume("card")
        assertTrue(inbox.scan(listOf(card)) is UsbScan.TemplateWritten)
        // ...then a key comes with another TV's file, written before that template.
        val file = putOnKey("""{ "prayers": { "isha": { "iqamah": "20:00" } } }""")
        file.setLastModified(UsbSettings.settingsFile(card).lastModified() - 86_400_000)
        val offer = inbox.scan(listOf(card, key), justMounted = { it == key }) as UsbScan.Offer
        assertEquals(file, offer.found.file)
    }

    @Test
    fun onlyTheKeyJustPluggedInGetsATemplate() {
        val card = volume("card")
        val written = inbox.scan(listOf(card, key), justMounted = { it == key }) as UsbScan.TemplateWritten
        assertEquals(listOf(UsbSettings.settingsFile(key)), written.files)
        assertFalse(UsbSettings.settingsFile(card).exists())
        // At start (a restart with the card in), nothing is written.
        assertEquals(UsbScan.Quiet, inbox.scan(listOf(card), justMounted = { false }))
    }

    @Test
    fun anAnsweredFileStaysAnsweredWhileOtherKeysComeAndGo() {
        putOnKey("""{ "prayers": { "isha": { "iqamah": "20:00" } } }""")
        assertTrue(inbox.apply((inbox.scan(listOf(key)) as UsbScan.Offer).found))
        // A new key gets a template, and the admin sets Isha back with the remote.
        assertTrue(inbox.scan(listOf(volume("other"))) is UsbScan.TemplateWritten)
        prefs.schedule = MosqueSchedule.DEFAULT
        // Months later the first key comes back to copy images: its old file is not offered again.
        assertEquals(UsbScan.Quiet, inbox.scan(listOf(key)))
    }

    @Test
    fun aReadOnlyKeyIsReadButNeverWritten() {
        val readOnly = volume("ntfs", readOnly = true)
        assertEquals(UsbScan.ReadOnly, inbox.scan(listOf(readOnly)))
        assertFalse(UsbSettings.settingsFile(readOnly).exists())
        UsbSettings.settingsFile(readOnly).apply {
            parentFile!!.mkdirs()
            writeText("""{ "prayers": { "isha": { "duration": 12 } } }""")
        }
        assertTrue(inbox.scan(listOf(readOnly)) is UsbScan.Offer)
    }

    @Test
    fun anExportPutsThisTvsSettingsOnTheKeyAndIsNotOfferedBack() {
        putOnKey("""{ "prayers": { "isha": { "iqamah": "20:00" } } }""")
        prefs.schedule = MosqueSchedule.DEFAULT.with(Prayer.ISHA, PrayerSettings(IqamahRule.FixedTime(LocalTime.of(19, 30)), 10))
        assertEquals(listOf(UsbSettings.settingsFile(key)), inbox.export(listOf(key)))
        assertEquals(inbox.currentFile(), UsbSettings.settingsFile(key).readText())
        assertEquals(UsbScan.Quiet, inbox.scan(listOf(key)))
    }

    @Test
    fun aCopyMakesTheOtherTvTheSame() {
        // This TV was set up from an earlier key: a Ramadan Isha, its own texts, an announcement, a manual Eid.
        prefs.schedule = MosqueSchedule.DEFAULT.withRamadan(Prayer.ISHA, PrayerOverride(iqamah = IqamahRule.AfterAdhan(20), salahMinutes = 75))
        prefs.adhkarContent = AdhkarContent(afterSalah = CustomAdhkarList(CustomAdhkarList.Mode.REPLACE, listOf(ReviewedDhikr("ayat_kursi", null))))
        prefs.textAnnouncements = listOf(TextAnnouncement("درس بعد صلاة العشاء"))
        dates = mapOf(1448 to ManualIslamicDates(eidFitr = LocalDate.of(2027, 3, 11)))
        // The TV it copies has none of them any more.
        val source = UsbSettingsInbox({ MosqueSchedule.DEFAULT }, {}, { "" }, {}, catalog = catalog, today = { TODAY })
        putOnKey(source.currentFile())
        assertTrue(inbox.apply((inbox.scan(listOf(key)) as UsbScan.Offer).found))
        // The prayers and Ramadan's changes are the other TV's (the minutes kept behind a fixed time follow them).
        assertEquals(MosqueSchedule.DEFAULT.prayers, prefs.schedule.prayers)
        assertEquals(emptyMap<Prayer, PrayerOverride>(), prefs.schedule.ramadan)
        assertTrue(prefs.adhkarContent.isBundled)
        assertEquals(emptyList<TextAnnouncement>(), prefs.textAnnouncements)
        assertEquals(emptyMap<Int, ManualIslamicDates>(), dates)
    }

    @Test
    fun theTvsOwnListsNeverBlockItsTemplateOrItsUndo() {
        // Saved before an update retired one of its texts.
        val retired = AdhkarContent(afterSalah = CustomAdhkarList(CustomAdhkarList.Mode.APPEND, listOf(ReviewedDhikr("retired_text", null))))
        prefs.adhkarContent = retired
        assertTrue(inbox.scan(listOf(key)) is UsbScan.TemplateWritten)
        // The admin edits only an iqamah in the template: the texts they never touched do not refuse it.
        putOnKey(UsbSettings.settingsFile(key).readText().replace("\"+5\"", "\"+7\""))
        val edited = inbox.preview((inbox.scan(listOf(key)) as UsbScan.Offer).found) as ParseResult.Success
        assertEquals(1, edited.changes.size)
        assertTrue(edited.contentChanges.isEmpty())
        // A file returns to the bundled texts; its undo brings the saved list back, read as it was saved.
        putOnKey("""{ "adhkar": null }""")
        assertTrue(inbox.apply((inbox.scan(listOf(key)) as UsbScan.Offer).found))
        assertTrue(prefs.adhkarContent.isBundled)
        val saved = File(root, "previous-settings.json").apply { writeText(snapshot!!) }
        // A key's file with the retired text is refused; the TV's own saved state is not.
        assertTrue(inbox.preview(UsbSettingsFound(File("key"), "$snapshot ", "key")) is ParseResult.Failure)
        assertTrue(inbox.preview(UsbSettings.read(saved)!!) is ParseResult.Success)
        assertTrue(inbox.apply(UsbSettings.readSaved(saved)!!, fromKey = false))
        assertEquals(retired, prefs.adhkarContent)
    }

    private companion object {
        /** In Hijri 1448. */
        val TODAY: LocalDate = LocalDate.of(2026, 10, 1)
    }
}
