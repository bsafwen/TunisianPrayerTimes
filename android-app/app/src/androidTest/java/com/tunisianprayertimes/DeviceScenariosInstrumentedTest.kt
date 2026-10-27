package com.tunisianprayertimes

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.adhkar.DhikrRepository
import com.tunisianprayertimes.engine.EventStatus
import com.tunisianprayertimes.engine.HighLatitudeRule
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.PrayerMethodChoice
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.ui.eveningCollectionPreset
import com.tunisianprayertimes.ui.salawatDhikrPreset
import com.tunisianprayertimes.ui.morningCollectionPreset
import com.tunisianprayertimes.ui.tahlilDhikrPreset
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Plan 9.1, "On device". It moves the phone's zone, so it runs only when asked (`-e deviceScenarios true`), only on
 * an emulator, and only on the throwaway one that scripts/device_checks/run_device_scenarios.py creates
 * (`adb -s emulator-5570`, deleted afterwards): never on an existing emulator, never on a phone.
 *
 * - [scenarioTimes] stores each scenario's place as the picker does, reads its prayer days through the repository
 *   and writes them to `files/device-scenarios.tsv`, which `DeviceScenariosCheckTest` (`:shared:javaTest`) computes
 *   again with the engine the reference cross-check ties to the Python reference, Tunis against docs/csv/615.
 * - [alarmsAtThePlannedInstants] arms the scheduler at Paris with the phone in Paris and on London, and across
 *   01:00 UTC on 2026-10-25 and 2027-03-28, and reads the triggers back from `dumpsys alarm`.
 * - [relocationOnZoneChange] moves the phone to Dubai (mock location, then `cmd alarm set-timezone`), with no screen
 *   open and with the main screen open, and checks what the travel check of `TIMEZONE_CHANGED` does;
 *   [prepareBackgroundRelocation] and [finishBackgroundRelocation] frame the script's own zone change, which reaches
 *   the app in the background.
 * - [shownTimesAreTheRepositorys] opens the main screen at Paris and at Dubai and finds each of today's times.
 * - [yearScanTimings] times M9's 365-day scans on this instance.
 *
 * The preferences, the phone's zone and the test location providers are restored afterwards.
 */
@RunWith(AndroidJUnit4::class)
class DeviceScenariosInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val prefs: SharedPreferences = context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
    private var savedPrefs: Map<String, *>? = null
    private var savedZone: String? = null
    private var mockedProviders = emptyList<String>()
    private var savedLocationEnabled: String? = null
    private var keepState = false
    private val phase: String? = InstrumentationRegistry.getArguments().getString("phase")

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        assumeTrue(
            "Plan 9.1's device checks run only with -e deviceScenarios true",
            InstrumentationRegistry.getArguments().getString("deviceScenarios") == "true",
        )
        assumeTrue("Only on an emulator", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        savedPrefs = HashMap(prefs.all)
        savedZone = shell("getprop persist.sys.timezone").trim().ifEmpty { TimeZone.getDefault().id }
        // The countries' defaults, no Friday edit, no dismissal; the first run done.
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("method_choice_") || it.startsWith("jomoaa_time_") }.forEach { edit.remove(it) }
        edit.commit()
        PrefsManager.markFirstLaunchDone(context)
        PrefsManager.clearAutoSilenceDismissals(context)
        PrefsManager.clearAutoSilenceDismissed(context)
    }

    @After
    fun tearDown() {
        val backup = savedPrefs ?: return
        if (keepState) return  // prepareBackgroundRelocation: the script goes on from this state
        val lm = context.getSystemService(LocationManager::class.java)
        mockedProviders.forEach { runCatching { lm.removeTestProvider(it) } }
        runCatching { shell("appops set ${context.packageName} android:mock_location deny") }
        savedLocationEnabled?.let { runCatching { shell("cmd location set-location-enabled $it") } }
        savedZone?.let { runCatching { setPhoneZone(it) } }
        putAll(prefs.edit().clear(), backup).commit()
        PrayerTimesRepository.invalidate()
        runCatching { SilenceScheduler.scheduleAll(context) }
    }

    private fun putAll(edit: SharedPreferences.Editor, values: Map<String, *>): SharedPreferences.Editor {
        for ((key, value) in values) {
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        return edit
    }

    private class Scenario(val name: String, val catalogueId: Long?, val dates: List<LocalDate>, val delegation: Int? = null)

    @Test
    fun scenarioTimes() {
        val today = LocalDate.now(ZoneId.of("Europe/Paris"))
        fun days(from: String, count: Int) = (0 until count).map { LocalDate.parse(from).plusDays(it.toLong()) }
        val scenarios = listOf(
            Scenario("paris-today", PARIS, listOf(today)),
            Scenario("paris-spring-change", PARIS, days("2026-03-28", 3) + days("2027-03-27", 3)),
            Scenario("paris-autumn-change", PARIS, days("2026-10-24", 3)),
            Scenario("brest-june", BREST, days("2026-06-20", 3)),
            Scenario("tromso-june", TROMSO, days("2026-06-20", 3)),
            Scenario("tromso-december", TROMSO, days("2026-12-20", 3)),
            Scenario("muscat", MUSCAT, listOf(today)),
            Scenario("riyadh-ramadan", RIYADH, days("2026-02-17", 2) + days("2026-03-19", 2)),
            Scenario("cairo-october-change", CAIRO, days("2026-10-28", 4)),
            Scenario("casablanca", CASABLANCA, days("2026-02-14", 3) + days("2026-03-21", 3) + today),
            Scenario("gaza", GAZA, days("2026-03-27", 3) + days("2026-10-23", 3)),
            Scenario("moroni", MORONI, listOf(today)),
            Scenario("dubai-friday", DUBAI, listOf(LocalDate.of(2026, 10, 16))),
            Scenario("sharjah", SHARJAH, days("2026-06-20", 2) + days("2026-12-20", 2) + today),
            Scenario("manama-own-points", MANAMA, days("2026-06-20", 3) + today),
            Scenario("kuwait-printed-leap-day", KUWAIT_CITY, days("2028-02-28", 3) + today),
            Scenario("doha-printed-ramadan", DOHA, days("2026-02-17", 3) + today),
            Scenario("beirut-printed-changes", BEIRUT, days("2026-03-28", 3) + days("2026-10-24", 3)),
            Scenario("ramallah-printed", RAMALLAH, days("2026-03-27", 3) + days("2026-10-23", 3)),
            Scenario("baghdad-printed", BAGHDAD, days("2026-06-20", 2) + today),
            Scenario("nouakchott-printed", NOUAKCHOTT, days("2026-04-01", 2) + days("2026-05-01", 2) + today),
            Scenario("tunis-615", null, days("2026-01-01", 365), delegation = 615),
        )
        val out = StringBuilder()
        out.append("# sdk=${Build.VERSION.SDK_INT} tzdata=${android.icu.util.TimeZone.getTZDataVersion()} phone=${TimeZone.getDefault().id} fingerprint=${Build.FINGERPRINT}\n")
        out.append(listOf("scenario", "kind", "catalogueId", "latE5", "lngE5", "elevationM", "zoneId", "zoneSource", "countryCode", "admin1", "admin2", "choice", "date", "event", "epochSecond", "status", "local").joinToString("\t")).append('\n')
        val problems = mutableListOf<String>()
        for (scenario in scenarios) {
            val place = if (scenario.delegation != null) storeDelegation(scenario.delegation) else storeCatalogueCity(scenario.catalogueId!!)
            val choice = PrayerTimesRepository.choice(context)
            val choiceText = choice?.let { CHOICE_JSON.encodeToString(PrayerMethodChoice.serializer(), it) }.orEmpty()
            val coords = place as? PrayerPlace.Coordinates
            val placeColumns = listOf(
                if (coords == null) "inm:${scenario.delegation}" else "coordinates",
                coords?.catalogueId?.toString().orEmpty(), coords?.latE5?.toString().orEmpty(), coords?.lngE5?.toString().orEmpty(),
                coords?.elevationM?.toString().orEmpty(), place.zone.id, coords?.zoneSource?.name.orEmpty(),
                coords?.countryCode.orEmpty(), coords?.admin1.orEmpty(), coords?.admin2.orEmpty(), choiceText,
            )
            for (date in scenario.dates) {
                val day = PrayerTimesRepository.prayerDay(context, date)
                if (day == null) {
                    problems += "${scenario.name} $date: no prayer day"
                    continue
                }
                val rows = TimeEvent.entries.map { Triple(it.name, day[it].at, day[it].status.name) }.toMutableList()
                if (date.dayOfWeek == java.time.DayOfWeek.FRIDAY) rows += Triple("JUMUA", PrayerTimesRepository.jumuaTime(context, date), "")
                for ((event, at, status) in rows) {
                    val local = at?.atZone(place.zone)?.format(LOCAL).orEmpty()
                    out.append((listOf(scenario.name) + placeColumns + listOf(date.toString(), event, at?.epochSecond?.toString().orEmpty(), status, local)).joinToString("\t")).append('\n')
                }
                // The instrument's own checks: six times, in the order of the day.
                val instants = TimeEvent.entries.map { day[it] }
                instants.filter { it.status == EventStatus.ABSENT }.forEach { problems += "${scenario.name} $date: ${it.event} absent" }
                instants.mapNotNull { it.at }.zipWithNext().filter { (a, b) -> a > b }.forEach { (a, b) -> problems += "${scenario.name} $date: $a after $b" }
            }
        }
        val file = File(context.filesDir, "device-scenarios.tsv")
        file.writeText(out.toString())
        Log.i(TAG, "Wrote ${file.absolutePath}")

        // Across the summer-time changes, a day's Dhuhr stays 24 h from the next one's, as instants.
        storeCatalogueCity(PARIS)
        for (from in listOf("2026-03-28", "2026-10-24", "2027-03-27")) {
            val dhuhrs = days(from, 3).map { checkNotNull(PrayerTimesRepository.prayerDay(context, it)!![TimeEvent.DHUHR].at) }
            dhuhrs.zipWithNext().forEach { (a, b) ->
                val gap = Duration.between(a, b).toMinutes()
                if (gap !in (24 * 60 - 2)..(24 * 60 + 2)) problems += "Paris Dhuhr $a to $b: $gap min"
            }
        }
        // E8: Umm al-Qura's Isha 90 min after Maghrib, 120 in Ramadan.
        storeCatalogueCity(RIYADH)
        for ((date, gap) in listOf("2026-02-17" to 90L, "2026-02-18" to 120L)) {
            val day = PrayerTimesRepository.prayerDay(context, LocalDate.parse(date))!!
            val actual = Duration.between(day[TimeEvent.MAGHRIB].at, day[TimeEvent.ISHA].at).toMinutes()
            if (actual != gap) problems += "Riyadh $date: Isha $actual min after Maghrib, expected $gap"
        }
        // A8: a perpetual table shows its printed clock times again every year.
        storeCatalogueCity(KUWAIT_CITY)
        val kuwait = ZoneId.of("Asia/Kuwait")
        fun clocks(date: String) = PrayerTimesRepository.prayerDay(context, LocalDate.parse(date))!!.let { day ->
            TimeEvent.entries.map { day[it].at?.atZone(kuwait)?.format(CLOCK) }
        }
        if (clocks("2026-03-15") != clocks("2031-03-15")) problems += "Kuwait 2026-03-15 ${clocks("2026-03-15")} against 2031-03-15 ${clocks("2031-03-15")}"
        // The UAE's Friday prayer: 12:45 local time.
        storeCatalogueCity(DUBAI)
        val jumua = PrayerTimesRepository.jumuaTime(context, LocalDate.of(2026, 10, 16))?.atZone(ZoneId.of("Asia/Dubai"))
        if (jumua?.toLocalTime()?.toString() != "12:45") problems += "Dubai Friday prayer at $jumua"
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun alarmsAtThePlannedInstants() {
        grantSilencePermissions()
        PrefsManager.setEnabled(context, true)
        PrefsManager.setAutoLocationUpdateEnabled(context, false)
        setPhoneZone("Europe/Paris")
        storeCatalogueCity(PARIS)
        SilenceScheduler.scheduleAll(context)
        val problems = mutableListOf<String>()
        problems += armedProblems("Paris, phone in Paris", Instant.now())
        val inParis = parisWeek()

        setPhoneZone("Europe/London")
        PrayerTimesRepository.invalidate()
        assertEquals("Paris's times with the phone on London", inParis, parisWeek())
        SilenceScheduler.scheduleAll(context)
        problems += armedProblems("Paris, phone on London", Instant.now())

        // Across 01:00 UTC: the scan of a run just before each change (2026-03-29's has passed; 2027-03-28 is the next).
        for (at in listOf(Instant.parse("2026-10-25T00:30:00Z"), Instant.parse("2027-03-28T00:30:00Z"))) {
            SilenceScheduler.scheduleAllInternal(context, Calendar.getInstance().apply { timeInMillis = at.toEpochMilli() })
            problems += armedProblems("Paris, run at $at", at)
        }
        SilenceScheduler.scheduleAll(context)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * Plan 6.8: on `TIMEZONE_CHANGED` with auto-location on, the receiver tries a last-location fix and a job a slower
     * one; with no fix the job posts the notification that opens the picker. The manifest has no background-location
     * permission, so with no screen open the fix may come back empty on Android 10+ (6.8 leaves that to this check):
     * either outcome passes there, and the one seen is written to `files/device-notes.tsv`. With the main screen open
     * the fix is a foreground one, so the place must move to Dubai and its windows be armed.
     */
    @Test
    fun relocationOnZoneChange() {
        grantSilencePermissions()
        shell("pm grant ${context.packageName} android.permission.ACCESS_FINE_LOCATION")
        shell("pm grant ${context.packageName} android.permission.ACCESS_COARSE_LOCATION")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        savedLocationEnabled = shell("cmd location is-location-enabled").trim()
        shell("cmd location set-location-enabled true")
        PrefsManager.setEnabled(context, true)
        val notes = mutableListOf("scenario\toutcome")

        startAtParis()
        mockLocation(25.2048, 55.2708)
        setPhoneZone("Asia/Dubai")
        var outcome = "none"
        waitFor(90_000) {
            outcome = when {
                (PrefsManager.getPlace(context) as? PrayerPlace.Coordinates)?.countryCode == "AE" -> "moved"
                travelNoticePosted() -> "picker-notification"
                else -> "none"
            }
            outcome != "none"
        }
        notes += "no screen open, inside the instrumentation\t$outcome"
        Log.i(TAG, "Background relocation: $outcome")
        assertTrue("background relocation: neither a move nor the picker notification\n${locationDiagnostics()}", outcome != "none")
        if (outcome == "moved") assertTrue(armedProblems("Dubai after the background move", Instant.now()).joinToString("\n"),
            waitFor(30_000) { armedProblems("Dubai", Instant.now()).isEmpty() })

        startAtParis()
        ActivityScenario.launch(MainActivity::class.java).use {
            mockLocation(25.2048, 55.2708)
            setPhoneZone("Asia/Dubai")
            val moved = waitFor(90_000) { (PrefsManager.getPlace(context) as? PrayerPlace.Coordinates)?.countryCode == "AE" }
            notes += "main screen open\t${if (moved) "moved" else "none"}"
            File(context.filesDir, "device-notes.tsv").writeText(notes.joinToString("\n", postfix = "\n"))
            assertTrue("foreground relocation: the place stayed ${PrefsManager.getPlace(context)}\n${locationDiagnostics()}", moved)
            assertEquals("Asia/Dubai", PrefsManager.getPlace(context).zone.id)
            assertTrue(armedProblems("Dubai after the foreground move", Instant.now()).joinToString("\n"),
                waitFor(30_000) { armedProblems("Dubai", Instant.now()).isEmpty() })
        }
    }

    /**
     * Plan 6.8's background case, driven by the script, since inside this instrumentation the app may count as a
     * foreground one: this phase leaves Paris stored with auto-location on and Dubai as the last location (the state
     * from before kept in [BACKUP]). The instrumentation's end stops the app's process; the script then changes the
     * zone with adb, so the broadcast starts the app in the background, and runs [finishBackgroundRelocation].
     */
    @Test
    fun prepareBackgroundRelocation() {
        assumeTrue("run by the script with -e phase prepare-background", phase == "prepare-background")
        val backup = context.getSharedPreferences(BACKUP, Context.MODE_PRIVATE)
        if (backup.all.isEmpty()) {
            putAll(backup.edit(), savedPrefs!!)
                .putString(BACKUP_ZONE, savedZone)
                .putString(BACKUP_LOCATION, shell("cmd location is-location-enabled").trim())
                .commit()
        }
        grantSilencePermissions()
        shell("pm grant ${context.packageName} android.permission.ACCESS_FINE_LOCATION")
        shell("pm grant ${context.packageName} android.permission.ACCESS_COARSE_LOCATION")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("cmd location set-location-enabled true")
        PrefsManager.setEnabled(context, true)
        startAtParis()
        // Test providers belong to the location service, not to this process: they keep Dubai as the last location
        // after the instrumentation ends, until the finish phase removes them.
        mockLocation(25.2048, 55.2708)
        keepState = true
    }

    /** The outcome of the script's zone change (a move, or the job's picker notification), then the state from before. */
    @Test
    fun finishBackgroundRelocation() {
        assumeTrue("run by the script with -e phase finish-background", phase == "finish-background")
        val outcome = when {
            (PrefsManager.getPlace(context) as? PrayerPlace.Coordinates)?.countryCode == "AE" -> "moved"
            travelNoticePosted() -> "picker-notification"
            else -> "none"
        }
        val access = shell("appops get ${context.packageName}").lines().filter { "LOCATION" in it }.joinToString(" / ") { it.trim() }
        File(context.filesDir, "device-notes.tsv").appendText("background (process started by the broadcast)\t$outcome\t$access\n")
        File(context.filesDir, "device-background-log.txt").writeText(travelLog() + "\n--- crash buffer ---\n" + shell("logcat -d -b crash -v time"))
        val diagnostics = if (outcome == "none") locationDiagnostics() else ""
        val lm = context.getSystemService(LocationManager::class.java)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused").forEach { runCatching { lm.removeTestProvider(it) } }
        val backup = context.getSharedPreferences(BACKUP, Context.MODE_PRIVATE)
        val saved = backup.all
        if (saved.isNotEmpty()) {
            savedZone = saved[BACKUP_ZONE] as? String ?: savedZone
            savedLocationEnabled = saved[BACKUP_LOCATION] as? String
            savedPrefs = saved.filterKeys { it != BACKUP_ZONE && it != BACKUP_LOCATION }
            backup.edit().clear().commit()
        }
        context.getSystemService(NotificationManager::class.java).cancelAll()
        assertTrue("background relocation: neither a move nor the picker notification\n$diagnostics", outcome != "none")
    }

    /** Paris stored with the phone in Paris, auto-location off while the zone moves back, then on; no notification left. */
    private fun startAtParis() {
        PrefsManager.setAutoLocationUpdateEnabled(context, false)
        setPhoneZone("Europe/Paris")
        storeCatalogueCity(PARIS)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        SilenceScheduler.scheduleAll(context)
        PrefsManager.setAutoLocationUpdateEnabled(context, true)
    }

    private fun travelNoticePosted(): Boolean {
        val title = context.getString(R.string.travel_zone_changed_title)
        return context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title }
    }

    /** The travel check's log lines since boot (the receiver, the job, the locator, the resolution). */
    private fun travelLog(): String =
        shell("logcat -d -v time -s BootReceiver:V TravelPlace:V DelegationLocator:V PlaceResolution:V SilenceScheduler:V WM-WorkerWrapper:V")

    /** What the app could see of the location when a relocation check failed. */
    private fun locationDiagnostics(): String {
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, "fused")
            .joinToString("; ") { p -> "$p enabled=${runCatching { lm.isProviderEnabled(p) }.getOrNull()} last=${runCatching { lm.getLastKnownLocation(p) }.getOrNull()}" }
        val log = travelLog().lines().takeLast(40).joinToString("\n")
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled else null
        return "location enabled=$enabled, network=${PlaceResolution.networkCountry(context)}, test providers=$mockedProviders\n$providers\n$log"
    }

    @Test
    fun shownTimesAreTheRepositorys() {
        for ((id, zone) in listOf(PARIS to "Europe/Paris", DUBAI to "Asia/Dubai")) {
            setPhoneZone(zone)
            val place = storeCatalogueCity(id) as PrayerPlace.Coordinates
            // The chooser question answered with the country's default, so no sheet covers the times.
            PrayerTimesRepository.choice(context, place)?.let { PrefsManager.setMethodChoice(context, place, it.copy(chooserAnswered = true)) }
            val today = LocalDate.now(place.zone)
            val shown = PrayerTimesRepository.prayerOccurrences(context, today).map { it.prayer to it.at.atZone(place.zone).format(CLOCK) }
            assertTrue("five prayers at $id", shown.size == 5)
            ActivityScenario.launch(MainActivity::class.java).use {
                for ((prayer, clock) in shown) {
                    val found = runCatching {
                        compose.waitUntil(15_000) { compose.onAllNodesWithText(clock, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                    }.isSuccess
                    assertTrue("$prayer at $clock shown at $id", found)
                }
            }
        }
    }

    /**
     * M9's 365-day scans, timed on this instance (the owner times them again on the OnePlus): the polar gate at
     * Tromsø, Paris by default and with LRE picked, two places mapped to official zones (the validity guard), and
     * the adhkar re-check after a move. Written to `files/device-timings.tsv`.
     */
    @Test
    fun yearScanTimings() {
        val lines = mutableListOf("scan\tms")
        val from = LocalDate.now(ZoneId.of("Europe/Paris"))
        fun timed(label: String, block: () -> Unit) {
            PrayerTimesRepository.invalidate()
            val start = SystemClock.elapsedRealtime()
            block()
            lines += "$label\t${SystemClock.elapsedRealtime() - start}"
        }
        fun year() = (0L until 365L).forEach { checkNotNull(PrayerTimesRepository.prayerDay(context, from.plusDays(it))) }
        storeCatalogueCity(TROMSO)
        timed("tromso-polar-gate", ::year)
        val paris = storeCatalogueCity(PARIS) as PrayerPlace.Coordinates
        timed("paris-default", ::year)
        PrefsManager.setMethodChoice(context, paris, PrayerTimesRepository.choice(context, paris)!!.copy(highLatitude = HighLatitudeRule.LocalRelativeEstimation))
        timed("paris-lre", ::year)
        storeCatalogueCity(CASABLANCA)
        timed("casablanca-mapped-zone", ::year)
        storeCatalogueCity(MUSCAT)
        timed("muscat-mapped-zone", ::year)
        // The app's four reminder presets (two collections tied to the prayers, the daily salawat, the daily tahlil),
        // removed again afterwards.
        val repository = DhikrRepository(context)
        val seeded = listOf(morningCollectionPreset(), eveningCollectionPreset(), salawatDhikrPreset(), tahlilDhikrPreset())
        seeded.forEach { repository.save(it) }
        try {
            val reminders = repository.state.value.reminders.size
            timed("adhkar-recheck-$reminders-reminders") { DhikrReminderScheduler.recheckSavedReminders(context) }
        } finally {
            seeded.forEach { repository.delete(it.id) }
        }
        File(context.filesDir, "device-timings.tsv").writeText(lines.joinToString("\n", postfix = "\n"))
        Log.i(TAG, lines.joinToString("; "))
    }

    // --- places ---

    /** A catalogue city stored as the picker stores it (6.8), read back from the preferences. */
    private fun storeCatalogueCity(id: Long): PrayerPlace {
        val row = checkNotNull(PlaceCatalogue.get(context).byId(id)) { "catalogue row $id" }
        val place = PlaceResolution.placeFromCatalogue(context, row)
        PrefsManager.setPlace(context, place, PlaceResolution.catalogueLabel(row), fromGps = false)
        val stored = PrefsManager.getPlace(context)
        assertEquals("stored place $id", place, stored)
        return stored
    }

    private fun storeDelegation(id: Int): PrayerPlace {
        PrefsManager.setDelegationId(context, id)
        val stored = PrefsManager.getPlace(context)
        assertEquals(PrayerPlace.Inm(id), stored)
        return stored
    }

    private fun parisWeek(): List<Instant?> = (0L until 7L).flatMap { offset ->
        val day = PrayerTimesRepository.prayerDay(context, LocalDate.now(ZoneId.of("Europe/Paris")).plusDays(offset))!!
        TimeEvent.entries.map { day[it].at }
    }

    // --- alarms ---

    private fun grantSilencePermissions() {
        shell("cmd notification allow_dnd ${context.packageName}")
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
    }

    /**
     * The planner's windows for a run at [now] against the package's alarms in `dumpsys alarm`: each start and end
     * still ahead under its action (6.9), and the run at 00:01 of the next day in the place's zone.
     */
    private fun armedProblems(label: String, now: Instant): List<String> {
        val place = PrefsManager.getPlace(context)
        val windows = SilenceScheduler.planner(context, place).windows(now)
        val expected = HashSet<Pair<String, Long>>()
        for (w in windows) {
            if (w.start > now) expected += "ACTION_SILENCE" to w.start.toEpochMilli()
            if (w.end > now) expected += "ACTION_UNSILENCE" to w.end.toEpochMilli()
        }
        val today = now.atZone(place.zone).toLocalDate()
        expected += "ACTION_RESCHEDULE" to today.plusDays(1).atTime(0, 1).atZone(place.zone).toInstant().toEpochMilli()
        val armed = armedAlarms().filter { it.first in setOf("ACTION_SILENCE", "ACTION_UNSILENCE", "ACTION_RESCHEDULE") }.toSet()
        if (armed.isEmpty()) return listOf("$label: no alarm of ${context.packageName} in dumpsys alarm:\n" + shell("dumpsys alarm").lineSequence().take(60).joinToString("\n"))
        val missing = (expected - armed).sortedBy { it.second }.map { "$label: missing ${it.first} at ${Instant.ofEpochMilli(it.second)}" }
        val extra = (armed - expected).sortedBy { it.second }.map { "$label: unexpected ${it.first} at ${Instant.ofEpochMilli(it.second)}" }
        return missing + extra
    }

    /** The package's RTC alarms: the action of each tag (`*walarm*:<action>`) and its trigger in milliseconds. */
    private fun armedAlarms(): List<Pair<String, Long>> {
        val pkg = context.packageName
        val out = ArrayList<Pair<String, Long>>()
        var pending: Long? = null
        for (raw in shell("dumpsys alarm").lineSequence()) {
            val line = raw.trim()
            val alarm = ALARM_LINE.find(line)
            if (alarm != null) {
                pending = if (alarm.groupValues[2] == pkg) alarm.groupValues[1].toLong() else null
                continue
            }
            val at = pending ?: continue
            if (line.startsWith("tag=")) {
                out += line.substringAfterLast('.') to at
                pending = null
            }
        }
        return out
    }

    // --- the phone ---

    private fun setPhoneZone(zone: String) {
        shell("cmd alarm set-timezone $zone")
        val changed = waitFor(15_000) { TimeZone.getDefault().id == zone && ZoneId.systemDefault().id == zone }
        assertTrue("phone zone $zone (now ${TimeZone.getDefault().id})", changed)
    }

    private fun mockLocation(lat: Double, lng: Double) {
        shell("appops set ${context.packageName} android:mock_location allow")
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
        mockedProviders = providers.filter { provider ->
            runCatching {
                runCatching { lm.removeTestProvider(provider) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    lm.addTestProvider(provider, ProviderProperties.Builder().setAccuracy(ProviderProperties.ACCURACY_FINE).build())
                } else {
                    @Suppress("DEPRECATION")
                    lm.addTestProvider(provider, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                }
                lm.setTestProviderEnabled(provider, true)
                lm.setTestProviderLocation(provider, Location(provider).apply {
                    latitude = lat
                    longitude = lng
                    accuracy = 5f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
            }.onFailure { Log.w(TAG, "No test provider $provider", it) }.isSuccess
        }
        assertTrue("a test location provider", mockedProviders.isNotEmpty())
    }

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(250)
        }
        return runCatching(condition).getOrDefault(false)
    }

    private companion object {
        const val TAG = "DeviceScenarios"

        /** The state from before [prepareBackgroundRelocation], kept across the script's two phases. */
        const val BACKUP = "device_scenarios_backup"
        const val BACKUP_ZONE = "__zone"
        const val BACKUP_LOCATION = "__location_enabled"

        // GeoNames ids of the bundled catalogue (assets/places).
        const val PARIS = 2988507L
        const val BREST = 3030300L
        const val TROMSO = 3133895L
        const val MUSCAT = 287286L
        const val RIYADH = 108410L
        const val CAIRO = 360630L
        const val CASABLANCA = 2553604L
        const val GAZA = 281133L
        const val MORONI = 921772L
        const val DUBAI = 292223L
        const val SHARJAH = 292672L
        const val MANAMA = 290340L
        const val KUWAIT_CITY = 285787L
        const val DOHA = 290030L
        const val BEIRUT = 276781L
        const val RAMALLAH = 282239L
        const val BAGHDAD = 98182L
        const val NOUAKCHOTT = 2377450L

        val CHOICE_JSON = Json { encodeDefaults = false }
        val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

        /** AOSP's `Alarm.toString()`: `Alarm{<hash> type <t> origWhen <ms> whenElapsed <ms> <package>}`. */
        val ALARM_LINE = Regex("""Alarm\{\S+ type \d+ origWhen (-?\d+) whenElapsed \S+ (\S+)\}""")
    }
}
