package com.tunisianprayertimes.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.HijriLabels
import com.tunisianprayertimes.IslamicDays
import com.tunisianprayertimes.ManualIslamicDateOverrides
import com.tunisianprayertimes.OfficialIslamicDates
import com.tunisianprayertimes.RamadanOverrideChecker
import com.tunisianprayertimes.mosque.DayBanner
import com.tunisianprayertimes.mosque.DayBanners
import com.tunisianprayertimes.mosque.FlowPhase
import com.tunisianprayertimes.mosque.FlowTiming
import com.tunisianprayertimes.mosque.MosqueAdhkar
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.PrayerFlow
import com.tunisianprayertimes.platform.PrayerDataLoader
import com.tunisianprayertimes.time.ClockGuard
import com.tunisianprayertimes.time.ClockTrust
import com.tunisianprayertimes.tv.data.*
import com.tunisianprayertimes.tv.ui.display.*
import com.tunisianprayertimes.tv.ui.settings.SettingsScreen
import com.tunisianprayertimes.tv.ui.setup.SetupWizard
import com.tunisianprayertimes.tv.ui.theme.LocalDisplayTheme
import com.tunisianprayertimes.tv.ui.theme.Sky
import com.tunisianprayertimes.tv.ui.theme.TvPrayerTheme
import com.tunisianprayertimes.tv.ui.theme.ThemeRegistry
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.usb.UsbImportScreen
import com.tunisianprayertimes.tv.ui.clock.ClockWarningScreen
import com.tunisianprayertimes.tv.ui.common.ScreenNotice
import com.tunisianprayertimes.tv.ui.common.TopMark
import com.tunisianprayertimes.tv.ui.common.VirtualCanvas
import com.tunisianprayertimes.tv.usb.UsbScan
import com.tunisianprayertimes.tv.usb.UsbSettings
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.platform.PrayerDataLoader as SharedPrayerData
import com.tunisianprayertimes.tv.remote.DashboardBackendImpl
import com.tunisianprayertimes.tv.remote.DashboardLive
import com.tunisianprayertimes.tv.remote.DashboardRoutes
import com.tunisianprayertimes.tv.remote.DashboardServer
import com.tunisianprayertimes.tv.ui.kiosk.HealthLevel
import com.tunisianprayertimes.tv.ui.kiosk.HealthRow
import com.tunisianprayertimes.tv.ui.kiosk.healthRows
import com.tunisianprayertimes.tv.update.UpdateStatus
import com.tunisianprayertimes.tv.update.Updates
import com.tunisianprayertimes.weather.CachedWeather
import java.util.concurrent.atomic.AtomicReference
import com.tunisianprayertimes.tv.ui.remote.PhoneAdminScreen
import com.tunisianprayertimes.tv.ui.remote.PhoneAdminSession
import com.tunisianprayertimes.tv.ui.usb.SettingsChangeLines
import com.tunisianprayertimes.tv.usb.UsbMedia
import com.tunisianprayertimes.tv.usb.UsbMediaFound
import com.tunisianprayertimes.tv.usb.UsbMediaInbox
import com.tunisianprayertimes.tv.ui.usb.UsbMediaScreen
import com.tunisianprayertimes.mosque.ProfileCatalog
import java.io.File
import com.tunisianprayertimes.tv.usb.UsbSettingsFound
import com.tunisianprayertimes.tv.usb.UsbSettingsInbox
import com.tunisianprayertimes.tv.usb.UsbVolumes
import com.tunisianprayertimes.tv.kiosk.AdminEntryDetector
import com.tunisianprayertimes.tv.kiosk.BootTiming
import com.tunisianprayertimes.tv.kiosk.KioskAccessibility
import com.tunisianprayertimes.tv.kiosk.WakePolicy
import com.tunisianprayertimes.tv.kiosk.ClockSample
import com.tunisianprayertimes.tv.kiosk.CrashLoopGuard
import com.tunisianprayertimes.tv.kiosk.KioskController
import com.tunisianprayertimes.tv.kiosk.KioskEvent
import com.tunisianprayertimes.tv.kiosk.KioskReport
import com.tunisianprayertimes.tv.kiosk.KioskStore
import com.tunisianprayertimes.tv.kiosk.MaintenanceRestart
import com.tunisianprayertimes.tv.kiosk.PixelShift
import com.tunisianprayertimes.tv.kiosk.SleepGapDetector
import com.tunisianprayertimes.tv.ui.kiosk.KioskHealthScreen
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var prayerRepo: PrayerTimesRepository
    private lateinit var gouvernoratRepo: GouvernoratRepository
    private lateinit var mediaManager: LocalMediaManager

    internal val kiosk: KioskStore get() = (application as TvApplication).kiosk

    /** Three crashes in a few minutes: plain theme, no custom backgrounds or announcements. */
    internal var safeMode = false
        private set

    /** Set by the composition: whether the admin-entry keys are listened to, and what they open. */
    internal var adminEntryActive = false
    internal var onAdminEntry: () -> Unit = {}

    /** Set by the composition: someone is at the remote (every key, even those that open settings or never reach a screen). */
    internal var onRemoteKey: () -> Unit = {}

    /** Set by the composition: what Back does where nothing else handles it (it never leaves the app). */
    internal var onBackOnDisplay: () -> Unit = {}

    /** Counts returns to the foreground, so pages showing system state read it again. */
    internal var resumes by mutableIntStateOf(0)
        private set

    private val adminEntry = AdminEntryDetector()

    /** After a hold of OK opened settings, the rest of that press must not click the first item there. */
    private var swallowOkUntilUp = false
    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen always on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        safeMode = CrashLoopGuard().isSafeMode(kiosk.crashes, SystemClock.elapsedRealtime())
        // Registered before the content, so every screen's own Back handling comes first.
        onBackPressedDispatcher.addCallback(this) { onBackOnDisplay() }
        KioskController.lockTaskIfDeviceOwner(this)
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                kiosk.eventLog.append(if (intent.action == Intent.ACTION_SCREEN_OFF) KioskEvent.SCREEN_OFF else KioskEvent.SCREEN_ON)
                // Fire TV wakes to its own home: without the quick-start service, the display goes back on top here.
                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    window.decorView.postDelayed({
                        val now = SystemClock.elapsedRealtime()
                        val autoStart = KioskController.autoStart(context)
                        if (WakePolicy.shouldRefront(now, inFront, KioskAccessibility.serviceConnected, autoStart.canBringToFront,
                                prefs.isSetupDone, kiosk.state.adminAwayUntil)
                        ) {
                            val started = KioskController.bringToFront(context)
                            kiosk.eventLog.append(KioskEvent.WATCHDOG_REFRONT, "wake ${autoStart.tier} started=$started")
                        }
                    }, WakePolicy.DELAY_MILLIS)
                }
            }
        }.also { receiver ->
            runCatching {
                registerReceiver(receiver, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                })
            }
        }

        val app = applicationContext
        prefs = PrefsManager(this)
        // Prayer times are computed offline from the bundled INM formula parameters.
        prayerRepo = PrayerTimesRepository(source = { PrayerDataLoader.prayerTimes(app) })
        gouvernoratRepo = GouvernoratRepository(
            gouvernoratsJson = { app.assets.open("gouvernorats.json").bufferedReader().use { it.readText() } },
            prayerTimes = { PrayerDataLoader.prayerTimes(app) },
        )
        mediaManager = LocalMediaManager(this)

        setContent {
            // Theme state lives here so it wraps TvPrayerTheme
            var themeId by remember { mutableStateOf(prefs.themeId) }
            val themeConfig = remember(themeId) { ThemeRegistry.findById(if (safeMode) PrefsManager.DEFAULT_THEME_ID else themeId) }

            TvPrayerTheme(theme = themeConfig) {
                VirtualCanvas {
                    TvApp(
                        activity = this,
                        prefs = prefs,
                        prayerRepo = prayerRepo,
                        gouvernoratRepo = gouvernoratRepo,
                        mediaManager = mediaManager,
                        currentThemeId = themeId,
                        onThemeChanged = { newId ->
                            prefs.themeId = newId
                            themeId = newId
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        resumes++
        inFront = true
        val now = SystemClock.elapsedRealtime()
        kiosk.update { it.copy(resumedAt = now) }
        // Pinned again when the admin is back from the system (lock task, device-owner boxes only).
        if ((kiosk.state.adminAwayUntil ?: 0L) <= now) KioskController.lockTaskIfDeviceOwner(this)
        // How fast the display came up after this boot, once, for the kiosk page.
        if (BootTiming.mark(this, BootTiming.SCREEN, now)) BootTiming.takeSummary(this)?.let { kiosk.eventLog.append(KioskEvent.BOOT_TIMING, it) }
        // A force stop removes the quick-start service; put it back if the admin wanted it and the box lets us.
        if (kiosk.quickStartWanted && KioskAccessibility.isAvailable(this) && !KioskAccessibility.isEnabled(this) &&
            KioskAccessibility.canWriteSecureSettings(this) && KioskAccessibility.enable(this)
        ) {
            kiosk.eventLog.append(KioskEvent.QUICK_START, "restored")
        }
    }

    override fun onStop() {
        super.onStop()
        inFront = false
        val now = SystemClock.elapsedRealtime()
        kiosk.update { it.copy(stoppedAt = now) }
        KioskController.armWatchdog(this)
    }

    override fun onDestroy() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Also after the keyboard of the mosque-name field closes.
        if (hasFocus) hideSystemBars()
    }

    /** Opens settings from any remote: OK held 3 s, OK five times, or Menu/Settings/Info. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        onRemoteKey()
        val isOk = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (swallowOkUntilUp && isOk) {
            if (event.action == KeyEvent.ACTION_UP) {
                swallowOkUntilUp = false
                adminEntry.onKey(AdminEntryDetector.Key.OK, AdminEntryDetector.Action.UP, event.eventTime) // ends the press
            }
            return true
        }
        if (!adminEntryActive) return super.dispatchKeyEvent(event)
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> AdminEntryDetector.Key.OK
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_INFO -> AdminEntryDetector.Key.MENU
            else -> AdminEntryDetector.Key.OTHER
        }
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> AdminEntryDetector.Action.DOWN
            KeyEvent.ACTION_UP -> AdminEntryDetector.Action.UP
            else -> return super.dispatchKeyEvent(event)
        }
        return when (adminEntry.onKey(key, action, event.eventTime)) {
            AdminEntryDetector.Result.OPEN_ADMIN -> {
                if (event.action == KeyEvent.ACTION_DOWN && key == AdminEntryDetector.Key.OK) swallowOkUntilUp = true
                onAdminEntry()
                true
            }
            AdminEntryDetector.Result.CONSUME -> true
            AdminEntryDetector.Result.PASS -> super.dispatchKeyEvent(event)
        }
    }

    /** Immersive fullscreen: AOSP boxes with a tablet system UI otherwise show status and navigation bars. */
    private fun hideSystemBars() {
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    internal val isResumed: Boolean get() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    companion object {
        /** Whether the display is on screen in this process; false in a process started for an alarm. */
        @Volatile
        var inFront: Boolean = false
            private set
    }
}

private const val TAG = "TvApp"

@Composable
private fun TvApp(
    activity: MainActivity,
    prefs: PrefsManager,
    prayerRepo: PrayerTimesRepository,
    gouvernoratRepo: GouvernoratRepository,
    mediaManager: LocalMediaManager,
    currentThemeId: String,
    onThemeChanged: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf(if (prefs.isSetupDone) Screen.Display else Screen.Setup) }
    var openKioskPage by remember { mutableStateOf(false) }
    LaunchedEffect(currentScreen) { if (currentScreen != Screen.Settings) openKioskPage = false }

    // Data state
    var delegationId by remember { mutableIntStateOf(prefs.delegationId) }
    var delegationName by remember { mutableStateOf(prefs.delegationName) }
    var mosqueName by remember { mutableStateOf(prefs.mosqueName) }
    // Every prayer's iqamah and duration (daily, Jumu'a, the Eids), and Ramadan's changes from the USB file.
    var iqamahConfigs by remember { mutableStateOf(prefs.iqamahConfigs()) }
    var ramadanOverrides by remember { mutableStateOf(prefs.ramadanOverrides) }
    // The texts: bundled and reviewed, unless the mosque's USB file replaced or extended them.
    var adhkarContent by remember { mutableStateOf(prefs.adhkarContent) }
    val afterSalahSlides = remember(adhkarContent) { MosqueAdhkar.afterSalah(adhkarContent) }
    val adhanSlides = remember { MosqueAdhkar.adhanCompanion() }
    val schedule = remember(iqamahConfigs, ramadanOverrides) {
        MosqueSchedule(iqamahConfigs.mapValues { it.value.toPrayerSettings() }, ramadanOverrides)
    }

    // Tunisia's time from the guarded device clock, on the second. Every screen of the prayer flow
    // is derived from it, so a restart or a clock change lands on the right screen.
    val clock = remember { ClockGuard(prefs.clockStore, Instant::now, SystemClock::elapsedRealtime, ZoneId::systemDefault) }
    var reading by remember { mutableStateOf(clock.read()) }
    LaunchedEffect(Unit) {
        var sample = clockSample()
        while (true) {
            reading = clock.read()
            // Energy saver or standby can put the box to sleep despite the screen flag; record it.
            val next = clockSample()
            SleepGapDetector.compare(sample, next)?.let { gap ->
                activity.kiosk.eventLog.append(KioskEvent.SLEEP_GAP, sleepText(gap.fromWall, gap.toWall, gap.sleptMillis))
            }
            sample = next
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val now = reading.now
    val today = now.toLocalDate()

    // Computed on the device from the bundled formula: no network, no expiry. Yesterday is kept
    // because an Isha flow can run past midnight; tomorrow for the suhoor after iftar.
    val todayTimes by produceState<DayPrayerTimes?>(null, delegationId, today) {
        value = loadDay(prayerRepo, delegationId, today)
    }
    val yesterdayTimes by produceState<DayPrayerTimes?>(null, delegationId, today) {
        value = loadDay(prayerRepo, delegationId, today.minusDays(1))
    }
    val tomorrowTimes by produceState<DayPrayerTimes?>(null, delegationId, today) {
        value = loadDay(prayerRepo, delegationId, today.plusDays(1))
    }

    // The shared Tunisian calendar: the admin's dates, then announcements, then estimates.
    val manualDates by ManualIslamicDateOverrides.updates.collectAsState()
    val officialDates by OfficialIslamicDates.updates.collectAsState()
    val islamicDays = remember(today, manualDates, officialDates) {
        (-1L..1L).map(today::plusDays).associateWith { IslamicDays.of(it) }
    }
    val islamicDay = islamicDays.getValue(today)

    // Ramadan's settings in Ramadan, the Eid prayer on Eid, Jumu'a on Fridays. The time after the
    // prayer is long enough for the whole sequence of texts (the mosque may have added its own).
    val timing = remember(afterSalahSlides) {
        val minutes = ((MosqueAdhkar.totalMillis(afterSalahSlides) + 59_999) / 60_000).toInt()
        FlowTiming(afterSalahMinutes = minutes.coerceIn(FlowTiming().afterSalahMinutes, FlowTiming.MAX_AFTER_SALAH_MINUTES))
    }
    val events = remember(todayTimes, yesterdayTimes, schedule, islamicDays, timing) {
        val yesterday = today.minusDays(1)
        listOfNotNull(
            yesterdayTimes?.let { PrayerFlow.eventsFor(yesterday, it, schedule, islamicDays[yesterday], timing, nextDay = islamicDay) },
            todayTimes?.let { PrayerFlow.eventsFor(today, it, schedule, islamicDay, timing, nextDay = islamicDays[today.plusDays(1)]) },
        ).flatten()
    }
    val flow = PrayerFlow.stateAt(now, events)
    val iqamahTimes: Map<Prayer, LocalTime> = remember(events, today) {
        events.filter { it.adhanAt.toLocalDate() == today }.associate { it.prayer to it.iqamahAt.toLocalTime() }
    }
    // Tomorrow's prayers are only shown, never run: the Fajr the main screen counts to after Isha, and
    // the one the night screen waits for.
    val tomorrowEvents = remember(tomorrowTimes, schedule, islamicDays, timing) {
        val tomorrow = today.plusDays(1)
        tomorrowTimes?.let {
            PrayerFlow.eventsFor(tomorrow, it, schedule, islamicDays[tomorrow], timing, nextDay = IslamicDays.of(tomorrow.plusDays(1)))
        }.orEmpty()
    }
    val tomorrowFajrIqamah = tomorrowEvents.firstOrNull { it.prayer == Prayer.FAJR }?.iqamahAt?.toLocalTime()
    val banner = DayBanners.at(
        now, islamicDay, islamicDays.getValue(today.plusDays(1)), todayTimes, tomorrowTimes,
        eidPrayerAt = events.firstOrNull { it.prayer in MosqueSchedule.EID && it.adhanAt.toLocalDate() == today }?.iqamahAt,
    )

    // The sky of the theme «أفق», recomputed once a minute: it changes too slowly for more.
    val withSky = LocalDisplayTheme.current.sky
    val minute = now.truncatedTo(ChronoUnit.MINUTES)
    val sky = remember(minute, todayTimes, withSky) { if (withSky) Sky.at(minute, todayTimes) else null }

    // Gouvernorats for setup/settings
    val gouvernorats = remember { gouvernoratRepo.loadAll() }
    val gouvernoratName = remember(gouvernorats, delegationId) { gouvernorats.findDelegation(delegationId)?.first?.nomAr }

    // Start Ramadan override polling on first composition
    LaunchedEffect(Unit) {
        RamadanOverrideChecker.startPollingIfNeeded()
    }

    // The mosque's images (copied from USB keys) and written announcements. In safe mode none are
    // shown: a bad image may be what crashed the app.
    var mediaVersion by remember { mutableIntStateOf(0) }
    var customBgEnabled by remember { mutableStateOf(prefs.customBackgroundEnabled) }
    var announcementsEnabled by remember { mutableStateOf(prefs.announcementsEnabled) }
    var announcementSeconds by remember { mutableIntStateOf(prefs.announcementIntervalSec) }
    var announcementsEvery by remember { mutableIntStateOf(prefs.announcementsEveryMinutes) }
    var weatherEnabled by remember { mutableStateOf(prefs.weatherEnabled) }
    var nightScreenEnabled by remember { mutableStateOf(prefs.nightScreenEnabled) }
    var textAnnouncements by remember { mutableStateOf(prefs.textAnnouncements) }
    val backgroundImages: List<Uri> = remember(mediaVersion, customBgEnabled) {
        if (activity.safeMode || !customBgEnabled) emptyList() else mediaManager.getBackgroundImages()
    }
    // Written announcements: from the settings file (with their end date) and from .txt files on a USB key.
    val writtenSlides: List<Announcement.Text> = remember(mediaVersion, announcementsEnabled, textAnnouncements, today) {
        if (activity.safeMode || !announcementsEnabled) emptyList()
        else textAnnouncements.filter { it.isShownOn(today) }.map(Announcement.Text::of) +
            mediaManager.textFileAnnouncements().map { Announcement.Text(title = "", content = it) }
    }
    val writtenAnnouncements: List<String> = remember(writtenSlides) { writtenSlides.map { it.content } }
    val announcements: List<Announcement> = remember(mediaVersion, announcementsEnabled, writtenSlides) {
        if (activity.safeMode || !announcementsEnabled) emptyList() else mediaManager.getImageAnnouncements() + writtenSlides
    }
    val imageCounts = remember(mediaVersion) {
        MediaKind.entries.associateWith { mediaManager.images(it).size + if (it == MediaKind.ANNOUNCEMENTS) mediaManager.textFiles().size else 0 }
    }
    // The ticker: the short daily adhkar, with the written announcements between them.
    val tickerSlides = remember(adhkarContent, writtenAnnouncements) {
        MosqueAdhkar.tickerWithAnnouncements(MosqueAdhkar.ticker(adhkarContent), writtenAnnouncements, TvStrings.ANNOUNCEMENT_LABEL)
    }

    // The weather at the mosque (Open-Meteo), only for TVs that are online, never older than a few hours.
    val weatherRepo = remember { WeatherRepository(context) }
    var weather by remember { mutableStateOf<CachedWeather?>(null) }
    LaunchedEffect(weatherEnabled, delegationId) {
        weather = if (weatherEnabled && delegationId > 0) weatherRepo.cached(delegationId) else null
        if (!weatherEnabled || delegationId <= 0) return@LaunchedEffect
        while (true) {
            val due = weather?.let { System.currentTimeMillis() - it.fetchedAtMillis !in 0 until CachedWeather.REFRESH_MILLIS } ?: true
            if (due && WeatherRepository.isOnline(context)) {
                val fresh = withContext(Dispatchers.IO) {
                    SharedPrayerData.prayerTimes(context.applicationContext).coordinates(delegationId)?.let { (latitude, longitude) ->
                        weatherRepo.refresh(delegationId, latitude, longitude, System.currentTimeMillis())
                    }
                }
                if (fresh != null) weather = fresh
            }
            delay(WEATHER_TICK_MILLIS)
        }
    }
    val weatherNow = weather?.takeIf { weatherEnabled && it.isFresh(System.currentTimeMillis()) }?.weather

    val afterSalahSlide = flow.event?.takeIf { flow.phase == FlowPhase.AFTER_SALAH }?.let { event ->
        MosqueAdhkar.slideAt(afterSalahSlides, Duration.between(event.salahEndAt, now).toMillis())
    }

    // Announcements play once after each prayer's adhkar, and every few minutes between prayers,
    // never close to an adhan.
    var adhkarShownFor by remember { mutableStateOf<LocalDateTime?>(null) }
    var announcementsShownFor by remember { mutableStateOf<LocalDateTime?>(null) }
    var lastSlideshowAt by remember { mutableStateOf(now) }
    LaunchedEffect(flow.phase, flow.event?.adhanAt) {
        if (flow.phase == FlowPhase.AFTER_SALAH) adhkarShownFor = flow.event?.adhanAt
    }
    val nextAdhan = remember(events, now.minute) { events.map { it.adhanAt }.filter { it.isAfter(now) }.minOrNull() }

    // After Isha the hall empties: a dim clock and the Fajr time on black until shortly before Fajr.
    val nextFajrEvent = NightWindow.nextFajr(now, events + tomorrowEvents)
    val lastIshaIqamah = NightWindow.lastIshaIqamah(now, events)
    // Tarawih follow Isha on the nights before a fast (the Hijri day begins at sunset).
    val ramadanNight = lastIshaIqamah?.let { islamicDays[it.toLocalDate().plusDays(1)]?.isRamadan } == true
    val isNight = NightWindow.isNight(now, lastIshaIqamah, nextFajrEvent?.adhanAt, ramadanNight, nightScreenEnabled)
    val eidMorning = EidMorning.isShown(
        now, banner,
        fajrDoneAt = events.firstOrNull { it.prayer == Prayer.FAJR && it.adhanAt.toLocalDate() == today }?.afterSalahEndAt,
        dhuhrAdhan = todayTimes?.let { today.atTime(it.dhuhr.hour, it.dhuhr.minute) },
    )

    val afterPrayerDue = adhkarShownFor != null && adhkarShownFor != announcementsShownFor
    val periodicDue = announcementsEvery > 0 && !now.isBefore(lastSlideshowAt.plusMinutes(announcementsEvery.toLong())) &&
        (nextAdhan == null || now.plusMinutes(QUIET_BEFORE_ADHAN_MINUTES).isBefore(nextAdhan))
    val showAnnouncements = flow.phase == FlowPhase.IDLE && !isNight && announcements.isNotEmpty() && (afterPrayerDue || periodicDue)

    // Mosque settings from a USB key: checked once onboarding is done, then whenever a key is plugged in.
    // The file carries the whole TV (name, place, theme, prayers, dates), so one TV can set up another.
    val snapshotFile = remember { File(context.filesDir, "previous-settings.json") }
    var canUndoImport by remember { mutableStateOf(snapshotFile.isFile) }
    val catalog = remember(gouvernorats) {
        ProfileCatalog(
            delegationName = { id -> gouvernorats.findDelegation(id)?.second?.nomAr },
            themes = ThemeRegistry.builtInThemes.associate { it.id to it.nameAr },
        )
    }
    val inbox = remember(catalog) {
        UsbSettingsInbox(
            readSchedule = { prefs.schedule },
            writeSchedule = { prefs.schedule = it },
            lastHandled = { prefs.usbLastHandledSignature },
            setLastHandled = { prefs.usbLastHandledSignature = it },
            readDates = { ManualIslamicDateOverrides.all() },
            writeDates = { dates -> dates.forEach { (year, value) -> ManualIslamicDateOverrides.set(year, value) } },
            readProfile = { prefs.profile },
            writeProfile = { profile ->
                prefs.applyProfile(profile) { id -> gouvernorats.findDelegation(id)?.let { (g, d) -> g.id to d.nomAr } }
            },
            catalog = catalog,
            readContent = { prefs.adhkarContent },
            writeContent = { prefs.adhkarContent = it },
            readAnnouncements = { prefs.textAnnouncements },
            writeAnnouncements = { prefs.textAnnouncements = it },
            saveSnapshot = { text ->
                runCatching { UsbSettings.writeAtomically(snapshotFile, text.toByteArray(Charsets.UTF_8)) }
                canUndoImport = snapshotFile.isFile
            },
        )
    }
    // The offer on screen came from a key, or is the undo of the last import.
    var usbFoundIsUndo by remember { mutableStateOf(false) }

    // A phone on the local network can edit the same settings file; its changes reload the screen.
    var settingsVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(settingsVersion) {
        if (settingsVersion == 0) return@LaunchedEffect
        iqamahConfigs = prefs.iqamahConfigs()
        ramadanOverrides = prefs.ramadanOverrides
        adhkarContent = prefs.adhkarContent
        textAnnouncements = prefs.textAnnouncements
        customBgEnabled = prefs.customBackgroundEnabled
        announcementsEnabled = prefs.announcementsEnabled
        announcementSeconds = prefs.announcementIntervalSec
        announcementsEvery = prefs.announcementsEveryMinutes
        weatherEnabled = prefs.weatherEnabled
        nightScreenEnabled = prefs.nightScreenEnabled
        mosqueName = prefs.mosqueName
        delegationId = prefs.delegationId
        delegationName = prefs.delegationName
        if (prefs.themeId != currentThemeId) onThemeChanged(prefs.themeId)
    }
    // The TV's own updates: the Play build has none; the GitHub build fetches them and installs at night.
    val updater = remember { Updates.create(context, activity.kiosk.eventLog) }
    var updateStatus by remember { mutableStateOf(updater.status) }
    val lastIsha = events.filter { it.prayer == Prayer.ISHA && !it.iqamahAt.isAfter(now) }.maxOfOrNull { it.iqamahAt }
    val nextFajr = listOfNotNull(
        todayTimes?.let { today.atTime(it.fajr.hour, it.fajr.minute) },
        tomorrowTimes?.let { today.plusDays(1).atTime(it.fajr.hour, it.fajr.minute) },
    ).firstOrNull { it.isAfter(now) }
    val quietHours by rememberUpdatedState(MaintenanceRestart.isDue(now, Duration.ofDays(1), flow.phase, lastIsha, nextFajr))
    LaunchedEffect(Unit) {
        while (true) {
            delay(UPDATE_TICK_MILLIS)
            // Installing ends the app: at night only, and only on a box where it comes back by itself.
            val comesBack = KioskController.autoStart(context).canBringToFront
            runCatching { updater.tick(WeatherRepository.isOnline(context), quietHours && activity.isResumed && comesBack) }
            updateStatus = updater.status
        }
    }

    // What the screen shows, for the dashboard (read from its threads).
    val dashboardLive = remember { AtomicReference<DashboardLive?>(null) }
    SideEffect {
        dashboardLive.set(DashboardLive(
            now = now,
            clockTrusted = reading.trust != ClockTrust.IMPLAUSIBLE,
            times = todayTimes,
            iqamahTimes = iqamahTimes,
            hijriLabel = HijriLabels.dateLabel(islamicDay.hijri),
            banner = banner?.let { dayBannerLines(it, now) }?.let { (title, detail) -> listOfNotNull(title, detail).joinToString(" · ") },
            flow = flow,
            weather = weather.takeIf { weatherEnabled },
            screen = when {
                currentScreen != Screen.Display || flow.phase != FlowPhase.IDLE -> null
                showAnnouncements -> "ANNOUNCEMENTS"
                isNight -> "NIGHT"
                eidMorning -> "EID"
                else -> null
            },
        ))
    }

    var phoneServer by remember { mutableStateOf<DashboardServer?>(null) }
    var phoneRoutes by remember { mutableStateOf<DashboardRoutes?>(null) }
    var phoneSession by remember { mutableStateOf<PhoneAdminSession?>(null) }
    fun stopPhone() {
        phoneServer?.stop()
        phoneServer = null
        phoneRoutes = null
        phoneSession = null
    }
    DisposableEffect(Unit) { onDispose { phoneServer?.stop() } }
    // The session ends 15 minutes after its last use with the token (an open page keeps it alive),
    // and after 2 hours whatever happens.
    LaunchedEffect(now.minute) {
        val routes = phoneRoutes ?: return@LaunchedEffect
        val clock = System.currentTimeMillis()
        if (clock - routes.lastUsedAt > PHONE_IDLE_MILLIS || clock - routes.startedAt > PHONE_MAX_MILLIS) stopPhone()
    }
    var usbScans by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val stop = UsbVolumes.onMounted(context) { usbScans++ }
        onDispose { stop() }
    }
    var usbFound by remember { mutableStateOf<UsbSettingsFound?>(null) }
    // Images on the key are offered after its settings file, never at the same time.
    val mediaInbox = remember { UsbMediaInbox(mediaManager, { prefs.usbMediaLastHandled }, { prefs.usbMediaLastHandled = it }) }
    var usbMedia by remember { mutableStateOf<UsbMediaFound?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val setupDone = currentScreen != Screen.Setup
    LaunchedEffect(usbScans, setupDone) {
        if (!setupDone) return@LaunchedEffect
        val (scan, media) = withContext(Dispatchers.IO) { scanUsb(context, inbox, mediaInbox) }
        if (scan !is UsbScan.Offer) usbMedia = media
        when (scan) {
            is UsbScan.Offer -> {
                usbFoundIsUndo = false
                usbFound = scan.found
            }
            is UsbScan.TemplateWritten -> notice = TvStrings.usbTemplateWritten(context.packageName)
            UsbScan.Inaccessible -> notice = TvStrings.USB_INACCESSIBLE
            UsbScan.Quiet -> Unit
        }
    }
    // The file is always read against the settings on screen now, never an older snapshot.
    val usbPreview = remember(usbFound, schedule, manualDates, mosqueName, delegationId, currentThemeId) { usbFound?.let(inbox::preview) }
    // Nothing over the prayer and the khutba: a notice waits until they end, and only then starts its time.
    val quietWall = currentScreen != Screen.Settings && flow.phase in QUIET_PHASES
    LaunchedEffect(notice, quietWall) {
        if (notice != null && !quietWall) {
            delay(NOTICE_MILLIS)
            notice = null
        }
    }

    // Back never leaves the app: on the display it tells how to reach settings.
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(HINT_MILLIS)
            hint = null
        }
    }
    SideEffect {
        activity.adminEntryActive = currentScreen == Screen.Display && usbFound == null && usbMedia == null && reading.trust != ClockTrust.IMPLAUSIBLE
        activity.onAdminEntry = { currentScreen = Screen.Settings }
        activity.onBackOnDisplay = { hint = TvStrings.HOLD_OK_HINT }
    }

    // Once a night, after a long run, a fresh process (only on the display, in the quiet hours).
    LaunchedEffect(now.minute) {
        if (currentScreen != Screen.Display || !activity.isResumed) return@LaunchedEffect
        val uptime = Duration.ofMillis(SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())
        val lastIsha = events.filter { it.prayer == Prayer.ISHA && !it.iqamahAt.isAfter(now) }.maxOfOrNull { it.iqamahAt }
        val nextFajr = listOfNotNull(
            todayTimes?.let { today.atTime(it.fajr.hour, it.fajr.minute) },
            tomorrowTimes?.let { today.plusDays(1).atTime(it.fajr.hour, it.fajr.minute) },
        ).firstOrNull { it.isAfter(now) }
        if (MaintenanceRestart.isDue(now, uptime, flow.phase, lastIsha, nextFajr)) {
            activity.kiosk.eventLog.append(KioskEvent.MAINT_RESTART, "uptime ${uptime.toHours()} h")
            KioskController.relaunch(activity)
        }
    }

    // An admin screen left open with nobody at the remote gives the wall back to the prayer times.
    var lastKeyAt by remember { mutableStateOf(now) }
    SideEffect { activity.onRemoteKey = { lastKeyAt = clock.read().now } }
    // Back from a system page (Wi-Fi, overlay permission, date) counts as being at the remote.
    LaunchedEffect(activity.resumes) { lastKeyAt = clock.read().now }
    // A USB offer's time starts when it is on the wall, and again after a prayer that covered it.
    LaunchedEffect(usbFound, usbMedia, flow.phase in PRAYER_PHASES) {
        if (usbFound != null || usbMedia != null) lastKeyAt = clock.read().now
    }
    LaunchedEffect(now) {
        val idle = Duration.between(lastKeyAt, now)
        val praying = flow.phase in PRAYER_PHASES
        if (currentScreen == Screen.Settings && (idle > SETTINGS_IDLE || praying && idle > SETTINGS_IDLE_DURING_PRAYER)) {
            currentScreen = Screen.Display
        }
        if (usbFound != null && idle > USB_DIALOG_IDLE) usbFound = null // offered again at the next scan
        if (usbMedia != null && idle > USB_DIALOG_IDLE) usbMedia = null
    }

    // A slow drift against burn-in on panels that show the same layout all day.
    val (shiftX, shiftY) = PixelShift.offsetAt(now.toLocalDate().toEpochDay() * 24 * 60 + now.hour * 60 + now.minute)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = shiftX.dp.toPx()
                translationY = shiftY.dp.toPx()
            }
    ) {
        val inSettings = currentScreen == Screen.Settings
        when {
            currentScreen == Screen.Setup -> {
                SetupWizard(
                    gouvernorats = gouvernorats,
                    onComplete = { gouvId, delegation, configs, mName ->
                        prefs.gouvernoratId = gouvId
                        prefs.delegationId = delegation.id
                        prefs.delegationName = delegation.nomAr
                        prefs.mosqueName = mName
                        configs.forEach { (prayer, config) -> prefs.setIqamahConfig(prayer, config) }
                        prefs.isSetupDone = true

                        delegationId = delegation.id
                        delegationName = delegation.nomAr
                        mosqueName = mName
                        iqamahConfigs = prefs.iqamahConfigs()
                        openKioskPage = true
                        currentScreen = Screen.Settings
                    }
                )
            }
            reading.trust == ClockTrust.IMPLAUSIBLE && !inSettings -> ClockWarningScreen(
                deviceTime = deviceTimeText(),
                initial = clock.suggestedTime(),
                canOpenSystemSettings = dateSettingsIntent().resolveActivity(context.packageManager) != null,
                onOpenSystemSettings = {
                    awayForAdmin(activity, ADMIN_SYSTEM_SETTINGS_AWAY)
                    runCatching { context.startActivity(dateSettingsIntent()) }
                },
                onSetTime = { time ->
                    clock.accept(time)
                    reading = clock.read()
                },
                onConfirm = {
                    clock.confirm()
                    reading = clock.read()
                },
            )
            // The prayer itself outranks everything except an admin working in settings.
            !inSettings && flow.phase == FlowPhase.ADHAN -> AdhanScreen(
                event = flow.event!!,
                now = now,
                mosqueName = mosqueName,
                companion = MosqueAdhkar.adhanCompanionAt(
                    Duration.between(flow.event!!.adhanAt, now).toMillis(),
                    Duration.between(flow.event!!.adhanAt, flow.event!!.adhanScreenEndAt).toMillis(),
                    adhanSlides,
                ),
                sky = sky,
            )
            !inSettings && flow.phase == FlowPhase.IQAMAH_COUNTDOWN -> IqamahCountdownScreen(
                event = flow.event!!,
                now = now,
                mosqueName = mosqueName,
                sky = sky,
            )
            !inSettings && flow.phase == FlowPhase.KHUTBA -> KhutbaScreen()
            !inSettings && flow.phase == FlowPhase.SALAH -> PrayerBlackScreen()
            usbFound != null && usbPreview != null -> {
                val found = usbFound!!
                UsbImportScreen(
                    found = found,
                    preview = usbPreview,
                    title = if (usbFoundIsUndo) TvStrings.UNDO_IMPORT else TvStrings.USB_FOUND_TITLE,
                    onApply = {
                        if (inbox.apply(found, fromKey = !usbFoundIsUndo)) settingsVersion++
                        usbFound = null
                        usbScans++ // then the key's images, if any
                    },
                    onDismiss = {
                        if (!usbFoundIsUndo) inbox.dismiss(found)
                        usbFound = null
                        usbScans++
                    },
                )
            }
            usbMedia != null -> {
                val media = usbMedia!!
                UsbMediaScreen(
                    found = media,
                    onApply = {
                        usbMedia = null
                        notice = TvStrings.USB_COPYING
                        scope.launch {
                            val copied = withContext(Dispatchers.IO) { mediaInbox.apply(media) }
                            notice = if (copied) null else TvStrings.USB_ERROR_TITLE
                            mediaVersion++
                        }
                    },
                    onDismiss = {
                        mediaInbox.dismiss(media)
                        usbMedia = null
                    },
                )
            }
            inSettings -> {
                SettingsScreen(
                    mosqueName = mosqueName,
                    delegationName = delegationName,
                    iqamahConfigs = iqamahConfigs,
                    gouvernorats = gouvernorats,
                    announcementsEnabled = announcementsEnabled,
                    customBgEnabled = customBgEnabled,
                    announcementIntervalSec = announcementSeconds,
                    backgroundCount = imageCounts.getValue(MediaKind.BACKGROUNDS),
                    announcementCount = imageCounts.getValue(MediaKind.ANNOUNCEMENTS) + textAnnouncements.size,
                    currentThemeId = currentThemeId,
                    today = today,
                    onMosqueNameChanged = { name ->
                        prefs.mosqueName = name
                        mosqueName = name
                    },
                    onIqamahChanged = { prayer, config ->
                        prefs.setIqamahConfig(prayer, config)
                        iqamahConfigs = iqamahConfigs + (prayer to config)
                    },
                    onDelegationChanged = { gouvId, delId, delName ->
                        prefs.gouvernoratId = gouvId
                        prefs.delegationId = delId
                        prefs.delegationName = delName
                        delegationId = delId
                        delegationName = delName
                    },
                    onAnnouncementsEnabledChanged = { enabled ->
                        prefs.announcementsEnabled = enabled
                        announcementsEnabled = enabled
                    },
                    onCustomBgEnabledChanged = { enabled ->
                        prefs.customBackgroundEnabled = enabled
                        customBgEnabled = enabled
                    },
                    onAnnouncementIntervalChanged = { interval ->
                        prefs.announcementIntervalSec = interval
                        announcementSeconds = interval
                    },
                    weatherEnabled = weatherEnabled,
                    onWeatherChanged = { enabled ->
                        prefs.weatherEnabled = enabled
                        weatherEnabled = enabled
                    },
                    announcementsEveryMinutes = announcementsEvery,
                    onAnnouncementsEveryChanged = { minutes ->
                        prefs.announcementsEveryMinutes = minutes
                        announcementsEvery = minutes
                    },
                    onDeleteImages = {
                        MediaKind.entries.forEach(mediaManager::clear)
                        mediaVersion++
                    },
                    onThemeChanged = onThemeChanged,
                    phonePage = { back ->
                        PhoneAdminScreen(
                            session = phoneSession,
                            onStart = {
                                stopPhone()
                                val token = DashboardRoutes.newToken()
                                val backend = DashboardBackendImpl(
                                    context = context,
                                    prefs = prefs,
                                    inbox = inbox,
                                    media = mediaManager,
                                    gouvernorats = gouvernorats,
                                    undoFile = snapshotFile,
                                    updater = updater,
                                    flavor = BuildConfig.FLAVOR,
                                    live = dashboardLive::get,
                                    kioskRows = {
                                        healthRows(KioskReport.collect(context, activity.kiosk, activity.safeMode, android.os.Process.getStartElapsedRealtime())) +
                                            updateRows(updater.status, context.packageName)
                                    },
                                    onSettingsChanged = {
                                        settingsVersion++
                                        canUndoImport = snapshotFile.isFile
                                    },
                                    onMediaChanged = { mediaVersion++ },
                                )
                                val routes = DashboardRoutes(token, backend)
                                val server = DashboardServer(routes::admit, routes::handle)
                                val port = runCatching { server.start() }.getOrNull()
                                if (port != null) {
                                    phoneServer = server
                                    phoneRoutes = routes
                                    val address = DashboardServer.localAddresses().firstOrNull()
                                    phoneSession = PhoneAdminSession(address?.let { "http://$it:$port/?t=$token" }, port)
                                }
                            },
                            onStop = ::stopPhone,
                            onBack = back,
                            onOpenWifiSettings = Intent(Settings.ACTION_WIFI_SETTINGS)
                                .takeIf { it.resolveActivity(context.packageManager) != null }
                                ?.let { intent ->
                                    {
                                        awayForAdmin(activity, ADMIN_SYSTEM_SETTINGS_AWAY)
                                        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                    }
                                },
                        )
                    },
                    kioskPage = { back ->
                        var refresh by remember { mutableIntStateOf(0) }
                        var quickStartOnAt by remember { mutableStateOf<Long?>(null) }
                        // The box changes under the page (the quick-start service binds a moment after it is turned on,
                        // an adb grant arrives): read it again while the page is open.
                        LaunchedEffect(Unit) {
                            while (true) {
                                delay(KIOSK_PAGE_REFRESH_MILLIS)
                                refresh++
                            }
                        }
                        val report = remember(activity.resumes, refresh) {
                            KioskReport.collect(context, activity.kiosk, activity.safeMode, android.os.Process.getStartElapsedRealtime())
                        }
                        val overlay = remember(report) { KioskController.overlaySettingsIntent(context) }
                        KioskHealthScreen(
                            report = report,
                            extraRows = updateRows(updateStatus, context.packageName),
                            onAllowUpdates = if (updateStatus.supported && updateStatus.needsPermission) {
                                {
                                    awayForAdmin(activity, ADMIN_SYSTEM_SETTINGS_AWAY)
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                            } else null,
                            onInstallUpdate = if (updateStatus.available != null && !updateStatus.needsPermission) {
                                {
                                    scope.launch {
                                        runCatching { updater.installNow() }
                                        updateStatus = updater.status
                                    }
                                }
                            } else null,
                            onGrantOverlay = overlay?.let { intent ->
                                {
                                    awayForAdmin(activity, ADMIN_SYSTEM_SETTINGS_AWAY)
                                    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }
                            },
                            onToggleHomeMode = {
                                awayForAdmin(activity, ADMIN_SYSTEM_SETTINGS_AWAY)
                                KioskController.setHomeMode(context, !report.homeModeEnabled, activity.kiosk.eventLog)
                                refresh++
                            },
                            onToggleQuickStart = if (report.quickStartAvailable && report.canWriteSecureSettings) {
                                {
                                    val on = !report.quickStartEnabled
                                    val done = if (on) KioskAccessibility.enable(context) else KioskAccessibility.disable(context)
                                    if (on && done) quickStartOnAt = SystemClock.elapsedRealtime()
                                    if (done) activity.kiosk.quickStartWanted = on
                                    activity.kiosk.eventLog.append(KioskEvent.QUICK_START, if (on) "on=$done" else "off=$done")
                                    refresh++
                                }
                            } else null,
                            onDisableFireTvSleep = if ((report.fireTvSleepMillis ?: 0L) > 0 && report.canWriteSecureSettings) {
                                {
                                    KioskAccessibility.disableFireTvSleep(context)
                                    refresh++
                                }
                            } else null,
                            onBack = back,
                            // The system binds the service a moment after it is switched on: no alarm meanwhile.
                            quickStartSettling = quickStartOnAt?.let { SystemClock.elapsedRealtime() - it < QUICK_START_SETTLE_MILLIS } == true,
                        )
                    },
                    onExitToAndroid = {
                        awayForAdmin(activity, ADMIN_EXIT_AWAY)
                        activity.kiosk.eventLog.append(KioskEvent.ADMIN_EXIT)
                        KioskController.unlockTask(activity)
                        currentScreen = Screen.Display
                        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                    onBack = { currentScreen = Screen.Display },
                    openKioskPage = openKioskPage,
                    previewTimes = { id -> runCatching { prayerRepo.loadDay(id, today) }.getOrNull() },
                    currentDelegationId = delegationId,
                    canUndoImport = canUndoImport,
                    onUndoImport = {
                        UsbSettings.read(snapshotFile)?.let { snapshot ->
                            usbFoundIsUndo = true
                            usbFound = snapshot
                            currentScreen = Screen.Display
                        }
                    },
                    onResetAll = {
                        prefs.resetAll()
                        ManualIslamicDateOverrides.all().keys.forEach(ManualIslamicDateOverrides::clear)
                        snapshotFile.delete()
                        activity.kiosk.eventLog.append(KioskEvent.ADMIN_EXIT, "reset")
                        activity.recreate()
                    },
                    aboutLines = remember { aboutLines(context) },
                    customTexts = !adhkarContent.isBundled,
                    // Through the same checked import as a key or the phone, so it can be undone.
                    onBundledTexts = {
                        if (inbox.apply(UsbSettingsFound(File("tv"), """{ "adhkar": null }""", "bundled-texts"), fromKey = false)) settingsVersion++
                        currentScreen = Screen.Display
                    },
                    nightScreenEnabled = nightScreenEnabled,
                    onNightScreenChanged = { enabled ->
                        prefs.nightScreenEnabled = enabled
                        nightScreenEnabled = enabled
                    },
                    phoneSessionOpen = phoneSession != null,
                    // What «حذف الصور وملفات الإعلانات» would remove: both folders' images and .txt files, not the file's announcements.
                    mediaFiles = imageCounts.getValue(MediaKind.BACKGROUNDS) + imageCounts.getValue(MediaKind.ANNOUNCEMENTS),
                    // Typing goes to the keyboard, not through the activity's keys: it still counts as being at the remote.
                    onTyping = { lastKeyAt = clock.read().now },
                    kioskPreview = {
                        healthRows(KioskReport.collect(context, activity.kiosk, activity.safeMode, android.os.Process.getStartElapsedRealtime())) +
                            updateRows(updateStatus, context.packageName)
                    },
                )
            }
            afterSalahSlide != null -> AfterSalahAzkarScreen(afterSalahSlide.value, afterSalahSlide.index, afterSalahSlides.size, sky)
            showAnnouncements -> {
                // A new list (a change from the phone or a key) starts the slideshow again.
                key(announcements) {
                    AnnouncementsSlideshow(
                        announcements = announcements,
                        now = now,
                        displaySeconds = announcementSeconds,
                        footer = MainScreenModel.nextPrayerLine(now, todayTimes, tomorrowTimes, iqamahTimes, tomorrowFajrIqamah),
                        onDismiss = {
                            announcementsShownFor = adhkarShownFor
                            lastSlideshowAt = clock.read().now
                        }
                    )
                }
            }
            isNight && nextFajrEvent != null -> NightScreen(now, nextFajrEvent.adhanAt, nextFajrEvent.iqamahAt)
            eidMorning && banner is DayBanner.Eid -> EidScreen(
                banner, now, mosqueName, HijriLabels.dateLabel(islamicDay.hijri),
                nextPrayerLine = MainScreenModel.nextPrayerLine(now, todayTimes, tomorrowTimes, iqamahTimes, tomorrowFajrIqamah),
            )
            else -> {
                PrayerDisplayScreen(
                    todayTimes = todayTimes,
                    tomorrowTimes = tomorrowTimes,
                    mosqueName = mosqueName,
                    delegationName = delegationName,
                    gouvernoratName = gouvernoratName,
                    now = now,
                    hijriLabel = HijriLabels.dateLabel(islamicDay.hijri),
                    iqamahTimes = iqamahTimes,
                    tomorrowFajrIqamah = tomorrowFajrIqamah,
                    banner = banner,
                    ramadanTomorrow = islamicDays.getValue(today.plusDays(1)).isRamadan,
                    weather = weatherNow,
                    ticker = tickerSlides,
                    sky = sky,
                    backgroundImages = backgroundImages,
                    onSettingsRequested = { currentScreen = Screen.Settings },
                )
            }
        }
        // Nothing over the prayer and the khutba; on the admin pages at the top, clear of their keys and hints.
        (hint ?: notice)
            ?.takeUnless { quietWall }
            ?.let { ScreenNotice(it, atTop = currentScreen != Screen.Display) }
        if (phoneSession != null && currentScreen == Screen.Display && flow.phase !in PRAYER_PHASES) TopMark(TvStrings.DASHBOARD_OPEN)
    }
}

private val PRAYER_PHASES = setOf(FlowPhase.ADHAN, FlowPhase.IQAMAH_COUNTDOWN, FlowPhase.KHUTBA, FlowPhase.SALAH)
private val QUIET_PHASES = setOf(FlowPhase.KHUTBA, FlowPhase.SALAH)
private val SETTINGS_IDLE: Duration = Duration.ofMinutes(3)
private val SETTINGS_IDLE_DURING_PRAYER: Duration = Duration.ofSeconds(30)
private val USB_DIALOG_IDLE: Duration = Duration.ofMinutes(2)
private const val NOTICE_MILLIS = 20_000L
private const val HINT_MILLIS = 4_000L
private const val PHONE_IDLE_MILLIS = 15 * 60_000L
private const val PHONE_MAX_MILLIS = 2 * 60 * 60_000L
private const val KIOSK_PAGE_REFRESH_MILLIS = 2_000L
private const val QUICK_START_SETTLE_MILLIS = 5_000L
private const val WEATHER_TICK_MILLIS = 5 * 60_000L
private const val UPDATE_TICK_MILLIS = 30 * 60_000L
private const val QUIET_BEFORE_ADHAN_MINUTES = 10L

/** The GitHub build's update rows for the kiosk page and the dashboard. */
private fun updateRows(status: UpdateStatus, packageName: String): List<HealthRow> {
    if (!status.supported) return emptyList()
    return listOfNotNull(
        HealthRow(
            HealthLevel.WARNING, "لا يسمح الجهاز للتطبيق بتثبيت تحديثاته",
            fix = "اسمح بتثبيت التطبيقات غير المعروفة لهذا التطبيق",
            command = "adb shell appops set $packageName REQUEST_INSTALL_PACKAGES allow",
        ).takeIf { status.needsPermission },
        HealthRow(if (status.available != null) HealthLevel.INFO else HealthLevel.GOOD, "التحديثات: ${status.message}"),
    )
}
private const val ADMIN_EXIT_AWAY = 30 * 60_000L
private const val ADMIN_SYSTEM_SETTINGS_AWAY = 10 * 60_000L

/** The admin left for the system on purpose: the watchdog leaves them alone for [millis]. */
private fun awayForAdmin(activity: MainActivity, millis: Long) {
    val until = SystemClock.elapsedRealtime() + millis
    activity.kiosk.update { it.copy(adminAwayUntil = until) }
    KioskController.unlockTask(activity) // a pinned app cannot open another app's page
}

/** What the About page says: enough for someone helping by phone. */
private fun aboutLines(context: Context): List<String> {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    val officialYears = OfficialIslamicDates.updates.value.keys.sorted()
    return listOfNotNull(
        "الإصدار ${info?.versionName.orEmpty()} (${info?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it) } ?: 0})" +
            if (BuildConfig.FLAVOR == "github") " · نسخة GitHub، تحدّث نفسها" else " · نسخة Google Play",
        "أوقات الصلاة تُحسب على الجهاز دون إنترنت للسنوات ${com.tunisianprayertimes.InmPrayerTimes.SUPPORTED_YEARS.first}–${com.tunisianprayertimes.InmPrayerTimes.SUPPORTED_YEARS.last}",
        officialYears.takeIf { it.isNotEmpty() }?.let { "تواريخ رمضان والعيد الرسمية: ${it.joinToString("، ")} هـ" },
        "ملف الإعدادات على مفتاح USB: Android/data/${context.packageName}/files/mosque-tv.json",
        "لفتح الإعدادات: اضغط مطولًا على OK، أو اضغط OK خمس مرات",
        "الجهاز: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · أندرويد ${android.os.Build.VERSION.RELEASE}",
        context.packageName,
    )
}

private fun clockSample() = ClockSample(System.currentTimeMillis(), SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())

private fun sleepText(from: Long, to: Long, slept: Long): String {
    val format = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
    fun at(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(format)
    return "${at(from)} → ${at(to)} (${slept / 60_000} min)"
}

private fun dateSettingsIntent() = Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** The device clock as the device shows it, so the admin sees what is wrong. */
private fun deviceTimeText(): String =
    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)) + " (" + ZoneId.systemDefault().id + ")"

private suspend fun loadDay(repo: PrayerTimesRepository, delegationId: Int, date: LocalDate): DayPrayerTimes? =
    if (delegationId > 0) withContext(Dispatchers.Default) { repo.loadDay(delegationId, date) } else null

/**
 * What the plugged-in keys hold: the settings file, and images the TV has not seen yet. Every key
 * gets the image folders, so the admin sees where to put them. Never throws; a failed scan is quiet.
 */
private fun scanUsb(context: android.content.Context, inbox: UsbSettingsInbox, media: UsbMediaInbox): Pair<UsbScan, UsbMediaFound?> = try {
    val volumes = UsbVolumes.mounted(context)
    val scan = inbox.scan(volumes, UsbVolumes.hiddenCount(context, volumes.size)).also { Log.i(TAG, "USB scan: $it") }
    volumes.forEach(UsbMedia::ensureFolders)
    scan to media.scan(volumes)
} catch (e: Exception) {
    Log.w(TAG, "USB settings scan failed", e)
    UsbScan.Quiet to null
}

private enum class Screen { Setup, Display, Settings }
