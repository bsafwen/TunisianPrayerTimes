package com.tunisianprayertimes.tv.usb

import com.tunisianprayertimes.ManualIslamicDates
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.IqamahRule
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
        lastHandled = { prefs.usbLastHandledSignature },
        setLastHandled = { prefs.usbLastHandledSignature = it },
        readDates = { dates },
        writeDates = { dates = (dates + it).filterValues { value -> !value.isEmpty } },
        readProfile = { prefs.profile },
        writeProfile = { profile -> prefs.applyProfile(profile) { places[it] } },
        catalog = catalog,
        saveSnapshot = { snapshot = it },
        readContent = { prefs.adhkarContent },
        writeContent = { prefs.adhkarContent = it },
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
        assertFalse("undo leaves the key's file handled", prefs.usbLastHandledSignature == "undo")
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
        assertEquals(UsbScan.Inaccessible, inbox.scan(emptyList(), hiddenVolumes = 1))
        assertEquals(UsbScan.Quiet, inbox.scan(emptyList(), hiddenVolumes = 0))
    }
}
