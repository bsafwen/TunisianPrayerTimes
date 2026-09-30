package com.tunisianprayertimes.tv

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.tunisianprayertimes.time.ClockReading
import com.tunisianprayertimes.time.ClockSource
import com.tunisianprayertimes.time.ClockTrust
import com.tunisianprayertimes.time.TunisTime
import com.tunisianprayertimes.tv.data.*
import com.tunisianprayertimes.tv.ui.display.*
import com.tunisianprayertimes.tv.ui.settings.EstimatedDates
import com.tunisianprayertimes.tv.ui.settings.SettingsPage
import com.tunisianprayertimes.tv.ui.settings.SettingsScreen
import com.tunisianprayertimes.tv.ui.setup.SetupWizard
import com.tunisianprayertimes.tv.ui.theme.LocalDisplayTheme
import com.tunisianprayertimes.tv.ui.theme.Sky
import com.tunisianprayertimes.tv.ui.theme.TvPrayerTheme
import com.tunisianprayertimes.tv.ui.theme.ThemeRegistry
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.usb.UsbImportScreen
import com.tunisianprayertimes.tv.ui.clock.ClockPage
import com.tunisianprayertimes.tv.ui.clock.ClockPageMode
import com.tunisianprayertimes.tv.ui.clock.ClockView
import com.tunisianprayertimes.tv.ui.clock.clockRows
import com.tunisianprayertimes.tv.ui.common.NoticePlace
import com.tunisianprayertimes.tv.ui.common.NoticePlacement
import com.tunisianprayertimes.tv.ui.common.ScreenNotice
import com.tunisianprayertimes.tv.ui.common.TopMark
import com.tunisianprayertimes.tv.ui.common.VirtualCanvas
import com.tunisianprayertimes.tv.ui.common.onPointerHold
import com.tunisianprayertimes.tv.usb.UsbScan
import com.tunisianprayertimes.tv.usb.UsbSettings
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.platform.PrayerDataLoader as SharedPrayerData
import com.tunisianprayertimes.tv.remote.DashboardBackendImpl
import com.tunisianprayertimes.tv.remote.ClockAnswer
import com.tunisianprayertimes.tv.remote.DashboardClock
import com.tunisianprayertimes.tv.remote.DashboardClockState
import com.tunisianprayertimes.tv.remote.DashboardScreen
import com.tunisianprayertimes.tv.remote.DashboardLive
import com.tunisianprayertimes.tv.remote.DashboardRoutes
import com.tunisianprayertimes.tv.remote.DashboardServer
import com.tunisianprayertimes.tv.remote.runPosted
import com.tunisianprayertimes.tv.ui.kiosk.HealthLevel
import com.tunisianprayertimes.tv.ui.kiosk.HealthRow
import com.tunisianprayertimes.tv.ui.kiosk.healthRows
import com.tunisianprayertimes.tv.ui.kiosk.movedIqamahRows
import com.tunisianprayertimes.tv.update.InstallGate
import com.tunisianprayertimes.tv.update.UpdateStatus
import com.tunisianprayertimes.tv.update.Updates
import com.tunisianprayertimes.weather.CachedWeather
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.tunisianprayertimes.tv.ui.remote.PhoneAdminScreen
import com.tunisianprayertimes.tv.ui.remote.PhoneAdminSession
import com.tunisianprayertimes.tv.ui.usb.SettingsChangeLines
import com.tunisianprayertimes.tv.usb.UsbMedia
import com.tunisianprayertimes.tv.usb.UsbMediaFound
import com.tunisianprayertimes.tv.usb.UsbMediaInbox
import com.tunisianprayertimes.tv.ui.usb.UsbMediaScreen
import com.tunisianprayertimes.tv.ui.usb.UsbOfferTime
import com.tunisianprayertimes.tv.ui.usb.copyNotice
import com.tunisianprayertimes.tv.ui.usb.readAgainNotice
import com.tunisianprayertimes.tv.usb.RemovableVolume
import com.tunisianprayertimes.mosque.ProfileCatalog
import java.io.File
import com.tunisianprayertimes.tv.usb.UsbSettingsFound
import com.tunisianprayertimes.tv.usb.UsbSettingsInbox
import com.tunisianprayertimes.tv.usb.UsbVolumes
import com.tunisianprayertimes.tv.kiosk.AdminAway
import com.tunisianprayertimes.tv.kiosk.AdminEntryDetector
import com.tunisianprayertimes.tv.kiosk.BootId
import com.tunisianprayertimes.tv.kiosk.BootTiming
import com.tunisianprayertimes.tv.kiosk.ClockChange
import com.tunisianprayertimes.tv.kiosk.ClockChangeReceiver
import com.tunisianprayertimes.tv.kiosk.ClockLog
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var prayerRepo: PrayerTimesRepository
    private lateinit var gouvernoratRepo: GouvernoratRepository
    private lateinit var mediaManager: LocalMediaManager

    internal val kiosk: KioskStore get() = (application as TvApplication).kiosk

    /** Three crashes in a few minutes: plain theme, no custom backgrounds or announcements. */
    internal var safeMode by mutableStateOf(false)
        private set

    /** Safe mode ends in this process too once the guard's time passes without a crash; the 1 s tick asks. */
    internal fun refreshSafeMode() {
        if (safeMode && !CrashLoopGuard().isSafeMode(kiosk.crashes, SystemClock.elapsedRealtime())) {
            safeMode = false
            kiosk.eventLog.append(KioskEvent.SAFE_MODE_EXIT)
        }
    }

    /** Set by the composition: whether the admin-entry keys are listened to, and what they open. */
    internal var adminEntryActive = false
    internal var onAdminEntry: () -> Unit = {}

    /** Set by the composition: whether holding or tapping OK opens settings too, or only the Menu keys (during the prayer). */
    internal var okOpensAdmin = true

    /**
     * Set by the composition: someone is at the remote (every press, even those that open settings or never
     * reach a screen; not the repeats of a held key, nor the rest of the hold that opened settings).
     */
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
        // One display per process: an older instance (the launcher's, when home mode was just chosen) goes.
        // A destroyed one is skipped: a recreated activity shares its token, so finishing it would close this one.
        current?.get()?.takeIf { it !== this && !it.isFinishing && !it.isDestroyed }?.finish()
        current = WeakReference(this)

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
            val themeConfig = remember(themeId, safeMode) { ThemeRegistry.findById(if (safeMode) PrefsManager.DEFAULT_THEME_ID else themeId) }

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
        if (!countedInFront) frontCount++
        countedInFront = true
        val now = SystemClock.elapsedRealtime()
        // The display is back, so the admin is back from the system: the away window ends here, and the
        // app is pinned again (lock task, device-owner boxes only).
        kiosk.update { it.copy(resumedAt = now, adminAwayUntil = null) }
        KioskController.lockTaskIfDeviceOwner(this)
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
        if (countedInFront) frontCount--
        countedInFront = false
        val now = SystemClock.elapsedRealtime()
        // A replaced instance stopping behind its successor is no departure from the screen.
        if (!inFront) kiosk.update { it.copy(stoppedAt = now) }
        KioskController.armWatchdog(this)
        // The admin's away window is looked at again now that the display has left: one that ended while
        // it was still in front (no time left before the prayer) found it there and did nothing.
        if (!inFront) kiosk.state.adminAwayUntil?.let { until -> KioskController.scheduleAwayEnd(this, AdminAway.checkAt(until, now)) }
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
    // androidx.core's ComponentActivity marks its dispatchKeyEvent restricted; this overrides the public
    // Activity method, which lint mistakes for a call into the library (without appcompat nothing hides it).
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isOk = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (swallowOkUntilUp && isOk) {
            if (event.action == KeyEvent.ACTION_UP) {
                swallowOkUntilUp = false
                adminEntry.onKey(AdminEntryDetector.Key.OK, AdminEntryDetector.Action.UP, event.eventTime) // ends the press
            }
            return true
        }
        // A held key's repeats are not someone at the remote: a remote lying on a key must not keep settings lit.
        if (event.repeatCount == 0) onRemoteKey()
        if (!adminEntryActive || isOk && !okOpensAdmin) {
            adminEntry.reset() // the end of a press it saw begin never comes here
            return super.dispatchKeyEvent(event)
        }
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

    /** An air mouse's click is someone at the remote as well. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onRemoteKey()
        return super.dispatchTouchEvent(event)
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

    /** This instance counts in [frontCount] (resumed, not stopped yet). */
    private var countedInFront = false

    companion object {
        /** Whether the display is on screen in this process; false in a process started for an alarm. */
        val inFront: Boolean get() = frontCount > 0

        /** Instances resumed and not stopped yet: a replaced instance stops after its successor resumed. */
        @Volatile
        private var frontCount = 0

        /** The live display; a HOME start (home mode) creates a new instance in the home task. */
        private var current: WeakReference<MainActivity>? = null
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
    // Kept when the system recreates the activity (a change of output mode, a low-memory box back from a system page).
    var currentScreen by rememberSaveable { mutableStateOf(if (prefs.isSetupDone) Screen.Display else Screen.Setup) }
    // The settings' first page: the kiosk page after onboarding, the phone's from the clock page.
    var settingsStart by remember { mutableStateOf(SettingsPage.Menu) }
    LaunchedEffect(currentScreen) { if (currentScreen != Screen.Settings) settingsStart = SettingsPage.Menu }
    // Each opening of settings keeps its own saved page: the one saved as a full reset recreates the
    // activity must not come back when settings next open, after onboarding.
    var settingsOpenings by rememberSaveable { mutableIntStateOf(0) }

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
    // How long the adhan screen lasts; an iqamah set sooner waits for its end (PrayerFlow).
    var adhanScreenMinutes by remember { mutableIntStateOf(prefs.adhanScreenMinutes) }
    val schedule = remember(iqamahConfigs, ramadanOverrides) { IqamahConfig.schedule(iqamahConfigs, ramadanOverrides) }

    // Tunisia's time from the guarded device clock, on the second. Every screen of the prayer flow
    // is derived from it, so a restart or a clock change lands on the right screen.
    val clockStore = remember { PrefsClockStore(context) }
    val clock = remember {
        // Read once: a process never outlives its boot.
        val boot = BootId.current(context)
        ClockGuard(clockStore, Instant::now, SystemClock::elapsedRealtime, ZoneId::systemDefault, bootId = { boot })
    }
    var reading by remember { mutableStateOf(clock.read()) }
    LaunchedEffect(Unit) {
        var sample = clockSample()
        while (true) {
            reading = clock.read()
            activity.refreshSafeMode()
            // Energy saver or standby can put the box to sleep despite the screen flag; record it.
            val next = clockSample()
            SleepGapDetector.compare(sample, next)?.let { gap ->
                // Off the main thread: the log is synced to disk just as the display wakes.
                launch(Dispatchers.IO) { activity.kiosk.eventLog.append(KioskEvent.SLEEP_GAP, gap.detail) }
            }
            sample = next
            delay(reading.millisToNextTick())
        }
    }
    val now = reading.now
    val today = now.toLocalDate()

    // Computed on the device from the bundled formula: no network, no expiry. Yesterday is kept
    // because an Isha flow can run past midnight; tomorrow for the suhoor after iftar.
    // They take a moment after the app starts: until then the wall stays dark, never "no data".
    var timesLoaded by remember { mutableStateOf(false) }
    val todayTimes by produceState<DayPrayerTimes?>(null, delegationId, today) {
        value = loadDay(prayerRepo, delegationId, today)
        timesLoaded = true
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

    // Ramadan's settings in Ramadan, the Eid prayer on Eid, Jumu'a on Fridays. The adhan screen lasts
    // the mosque's minutes; the time after the prayer is long enough for the whole sequence of texts
    // (the mosque may have added its own).
    val timing = remember(afterSalahSlides, adhanScreenMinutes) {
        val minutes = ((MosqueAdhkar.totalMillis(afterSalahSlides) + 59_999) / 60_000).toInt()
        FlowTiming(
            adhanScreenMinutes = adhanScreenMinutes,
            afterSalahMinutes = minutes.coerceIn(FlowTiming().afterSalahMinutes, FlowTiming.MAX_AFTER_SALAH_MINUTES),
        )
    }
    val events = remember(todayTimes, yesterdayTimes, schedule, islamicDays, timing) {
        val yesterday = today.minusDays(1)
        listOfNotNull(
            yesterdayTimes?.let { PrayerFlow.eventsFor(yesterday, it, schedule, islamicDays[yesterday], timing, nextDay = islamicDay) },
            todayTimes?.let { PrayerFlow.eventsFor(today, it, schedule, islamicDay, timing, nextDay = islamicDays[today.plusDays(1)]) },
        ).flatten()
    }
    // The running prayer is kept as it started, so a change of settings or a clock put back by a few
    // minutes never replays its countdown or its black screen; an impossible clock runs no prayer at all.
    val flowPin = remember { AtomicReference<RunningPrayer.Pin?>(null) }
    val running = RunningPrayer.stateAt(now, events, flowPin.get(), clockPlausible = reading.trust != ClockTrust.IMPLAUSIBLE)
    SideEffect { flowPin.set(running.pin) }
    val flow = running.state
    val iqamahTimes: Map<Prayer, LocalTime> = remember(events, today) {
        events.filter { it.adhanAt.toLocalDate() == today }.associate { it.prayer to it.iqamahAt.toLocalTime() }
    }
    // Today's prayers as the wall runs them, beside the rules on the settings' iqamah page.
    val todayEvents = remember(events, today) { events.filter { it.adhanAt.toLocalDate() == today }.associateBy { it.prayer } }
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
    // The Eid prayer's time from the Maghrib before it, when the congregation asks, until it begins.
    val eidNote = EidPrayerNotice.at(now, events + tomorrowEvents, todayTimes?.let { today.atTime(it.maghrib.hour, it.maghrib.minute) })
        ?.let { TvStrings.eidPrayerNote(it.prayer, it.iqamahAt, now) }

    // The sky of the theme «أفق», recomputed once a minute: it changes too slowly for more.
    val withSky = LocalDisplayTheme.current.sky
    val minute = now.truncatedTo(ChronoUnit.MINUTES)
    val sky = remember(minute, todayTimes, withSky) { if (withSky) Sky.at(minute, todayTimes) else null }

    // Offline, a Ramadan or Eid date a few days ahead is still the estimate: the admin is asked to confirm it.
    val estimatedDate = remember(minute, manualDates, officialDates) { EstimatedDates.pending(today, WeatherRepository.isOnline(context)) }

    // Gouvernorats for setup/settings
    val gouvernorats = remember { gouvernoratRepo.loadAll() }
    val gouvernoratName = remember(gouvernorats, delegationId) { gouvernorats.findDelegation(delegationId)?.first?.nomAr }

    // The official Ramadan and Eid dates are polled only in the few days around an announcement, and
    // the process can run for weeks (a box put in standby every night misses the nightly restart): the
    // poller is asked again each day, on each return to the screen, and when the network comes back.
    // It starts one poller at most.
    var networkBacks by remember { mutableIntStateOf(0) }
    LaunchedEffect(today, activity.resumes, networkBacks) {
        // Its windows follow the screen's date: the guard's correction of a wrong device clock.
        RamadanOverrideChecker.todayProvider = { correctedToday(clockStore, Instant.now()) }
        withContext(Dispatchers.IO) { runCatching { RamadanOverrideChecker.startPollingIfNeeded() } }
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
    val backgroundImages: List<Uri> = remember(mediaVersion, customBgEnabled, activity.safeMode) {
        if (activity.safeMode || !customBgEnabled) emptyList() else mediaManager.getBackgroundImages()
    }
    // Written announcements: from the settings file (with their end date) and from .txt files on a USB key.
    val writtenSlides: List<Announcement.Text> = remember(mediaVersion, announcementsEnabled, textAnnouncements, today, activity.safeMode) {
        if (activity.safeMode || !announcementsEnabled) emptyList()
        else textAnnouncements.filter { it.isShownOn(today) }.map(Announcement.Text::of) +
            mediaManager.textFileAnnouncements().map { Announcement.Text(title = "", content = it) }
    }
    val writtenAnnouncements: List<String> = remember(writtenSlides) { writtenSlides.map { it.content } }
    val announcements: List<Announcement> = remember(mediaVersion, announcementsEnabled, writtenSlides, activity.safeMode) {
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
    // The adhkar have played through: the rest of the after-prayer time is the wall's again, and the
    // announcements owed to this prayer start there, while the congregation is still in the hall.
    val adhkarDone = flow.phase == FlowPhase.AFTER_SALAH && afterSalahSlide == null

    // Announcements play once after each prayer's adhkar, and every few minutes between prayers,
    // never close to an adhan (AnnouncementsWindow).
    var adhkarShownFor by remember { mutableStateOf<LocalDateTime?>(null) }
    var announcementsShownFor by remember { mutableStateOf<LocalDateTime?>(null) }
    // On the time since boot: a pass stored as a wall time would hold the next ones back for hours after
    // the wall clock was put back.
    var lastSlideshowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lastSlideshowAt = AnnouncementsWindow.lastPassAt(now, SystemClock.elapsedRealtime() - lastSlideshowElapsed)
    // The list of the pass on the wall: it runs to its end, past the quiet time before an adhan. A new
    // list is a new pass, which starts only when due and clear, like any other.
    var slideshowList by remember { mutableStateOf<List<Announcement>?>(null) }
    LaunchedEffect(flow.phase, flow.event?.adhanAt, announcements.isEmpty()) {
        if (flow.phase == FlowPhase.AFTER_SALAH) adhkarShownFor = flow.event?.adhanAt
        // Nothing to show after the prayer (none, or a pass's list emptied): no pass is owed to it any more.
        if (flow.phase == FlowPhase.IDLE && announcements.isEmpty()) announcementsShownFor = adhkarShownFor
    }
    val nextAdhan = remember(events, now.minute) { events.map { it.adhanAt }.filter { it.isAfter(now) }.minOrNull() }
    // The admin's time in the system's pages ends before this: the iqamah of a prayer already called,
    // or the next adhan. None with an impossible clock: its prayers are a phantom day's.
    val wallNow by rememberUpdatedState(now)
    val prayerAhead by rememberUpdatedState(
        if (reading.trust == ClockTrust.IMPLAUSIBLE) null
        else listOfNotNull(flow.event?.iqamahAt?.takeIf { it.isAfter(now) }, nextAdhan).minOrNull()
    )

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
    val slideshowDue = !isNight && announcements.isNotEmpty() && AnnouncementsWindow.mayStart(
        now, afterPrayerDue, lastSlideshowAt, announcementsEvery, Duration.ofMillis(passMillis(announcements.size, announcementSeconds)), nextAdhan,
    )
    val showAnnouncements = (flow.phase == FlowPhase.IDLE || adhkarDone) && (slideshowList == announcements || slideshowDue)

    // Mosque settings from a USB key: checked once onboarding is done, then whenever a key is plugged in.
    // The file carries the whole TV (name, place, theme, prayers, dates), so one TV can set up another.
    val snapshotFile = remember { File(context.filesDir, "previous-settings.json") }
    var canUndoImport by remember { mutableStateOf(snapshotFile.isFile) }
    val catalog = remember(gouvernorats) {
        ProfileCatalog(
            delegationName = { id -> gouvernorats.findDelegation(id)?.second?.nomAr },
            themes = ThemeRegistry.builtInThemes.associate { it.id to it.nameAr },
            delegationIds = { gouvernorats.flatMap { g -> g.delegations.map { it.id } } },
        )
    }
    val inbox = remember(catalog) {
        UsbSettingsInbox(
            readSchedule = { prefs.schedule },
            writeSchedule = { prefs.schedule = it },
            readHandled = { prefs.usbHandledSettings },
            writeHandled = { prefs.usbHandledSettings = it },
            readDates = { ManualIslamicDateOverrides.all() },
            writeDates = { dates -> dates.forEach { (year, value) -> ManualIslamicDateOverrides.set(year, value) } },
            yearDates = IslamicDays::yearDates,
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
            readSnapshot = { UsbSettings.read(snapshotFile)?.text },
        )
    }
    // The offer on screen came from a key, or is the undo of the last import.
    var usbFoundIsUndo by remember { mutableStateOf(false) }

    // A phone on the local network can edit the same settings file; its changes reload the screen.
    var settingsVersion by remember { mutableIntStateOf(0) }
    // The version the wall was last rebuilt from, for the phone's page: it waits for it after an apply.
    var settingsLoaded by remember { mutableIntStateOf(0) }
    LaunchedEffect(settingsVersion) {
        if (settingsVersion == 0) return@LaunchedEffect
        iqamahConfigs = prefs.iqamahConfigs()
        ramadanOverrides = prefs.ramadanOverrides
        adhanScreenMinutes = prefs.adhanScreenMinutes
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
        settingsLoaded = settingsVersion
    }
    // The TV's own updates: the Play build has none; the GitHub build fetches them and installs at night.
    val installRefusal by rememberUpdatedState(InstallGate.refusal(flow.phase, now, nextAdhan))
    val updater = remember { Updates.create(context, activity.kiosk.eventLog) { installRefusal } }
    // Set further down, where the admin's presence is known: no night install or restart under the admin's
    // hands (settings, the phone's session, a key's offer or its copy).
    val adminHere = remember { AtomicBoolean(false) }
    val lastIsha = events.filter { it.prayer == Prayer.ISHA && !it.iqamahAt.isAfter(now) }.maxOfOrNull { it.iqamahAt }
    val nextFajr = listOfNotNull(
        todayTimes?.let { today.atTime(it.fajr.hour, it.fajr.minute) },
        tomorrowTimes?.let { today.plusDays(1).atTime(it.fajr.hour, it.fajr.minute) },
    ).firstOrNull { it.isAfter(now) }
    val quietHours by rememberUpdatedState(MaintenanceRestart.isDue(now, Duration.ofDays(1), flow.phase, lastIsha, nextFajr, ramadanNight))
    LaunchedEffect(Unit) {
        while (true) {
            delay(UPDATE_TICK_MILLIS)
            // Installing ends the app: at night only, and only on a box where it comes back by itself.
            val comesBack = KioskController.autoStart(context).canBringToFront
            runCatching { updater.tick(WeatherRepository.isOnline(context), quietHours && activity.isResumed && comesBack && !adminHere.get()) }
        }
    }

    // What the screen shows, for the dashboard (read from its threads).
    val dashboardLive = remember { AtomicReference<DashboardLive?>(null) }

    var phoneServer by remember { mutableStateOf<DashboardServer?>(null) }
    var phoneRoutes by remember { mutableStateOf<DashboardRoutes?>(null) }
    var phoneSession by remember { mutableStateOf<PhoneAdminSession?>(null) }
    var phoneToken by remember { mutableStateOf<String?>(null) }
    fun stopPhone() {
        phoneServer?.stop()
        phoneServer = null
        phoneRoutes = null
        phoneSession = null
        phoneToken = null
    }
    DisposableEffect(Unit) { onDispose { phoneServer?.stop() } }
    // The session ends 15 minutes after its last use with the token (an open page keeps it alive),
    // and after 2 hours whatever happens; meanwhile the routes refuse its token themselves.
    LaunchedEffect(now.minute) {
        if (phoneRoutes?.isOver() == true) stopPhone()
    }
    // The session's code follows the TV's network: the hotspot joined after it started, a second network,
    // a new address from the router. Read again on return to the screen, on network changes, each minute.
    var linkChanges by remember { mutableIntStateOf(0) }
    LaunchedEffect(phoneSession != null, activity.resumes, linkChanges, now.minute) {
        val session = phoneSession ?: return@LaunchedEffect
        val token = phoneToken ?: return@LaunchedEffect
        val fresh = withContext(Dispatchers.IO) { PhoneAdminSession.of(session.port, token, activeAddress(context), DashboardServer.localAddresses()) }
        if (phoneSession == session && fresh != session) phoneSession = fresh
    }
    var usbScans by remember { mutableIntStateOf(0) }
    // Where the last key was mounted: only a key just plugged in gets the template, not a card left in the box.
    var usbMountedAt by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val stop = UsbVolumes.onMounted(context) { path ->
            usbMountedAt = path
            usbScans++
        }
        onDispose { stop() }
    }
    var usbFound by remember { mutableStateOf<UsbSettingsFound?>(null) }
    // Images on the key are offered after its settings file, never at the same time.
    val mediaInbox = remember { UsbMediaInbox(mediaManager, { prefs.usbHandledMedia }, { prefs.usbHandledMedia = it }) }
    var usbMedia by remember { mutableStateOf<UsbMediaFound?>(null) }
    // The key's images are being copied: nothing may end the copy (the previous files stay until it
    // finishes, and the key's set is offered again if it fails).
    var usbCopying by remember { mutableStateOf(false) }
    // How the last copy ended: shown where the copy's own notice is, so the admin hears it at once.
    var usbCopyOutcome by remember { mutableStateOf<String?>(null) }
    // An offer found at start (a night restart with a key left in) or left unanswered: a quiet mark on the
    // wall, and the dialog once the admin opens the settings.
    var usbWaiting by remember { mutableStateOf(false) }
    // A key taken out takes its offer with it, instead of a mark left till the night restart; it is
    // offered again when the key comes back.
    var usbRemovals by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val stop = UsbVolumes.onGone(context) { usbRemovals++ }
        onDispose { stop() }
    }
    LaunchedEffect(usbRemovals) {
        if (usbRemovals == 0) return@LaunchedEffect
        val found = usbFound
        val media = usbMedia
        val (fileGone, mediaGone) = withContext(Dispatchers.IO) {
            (found != null && !found.file.isFile) to (media != null && !UsbMedia.stillThere(media))
        }
        if (fileGone && usbFound === found) usbFound = null
        if (mediaGone && usbMedia === media) usbMedia = null
    }
    var notice by remember { mutableStateOf<String?>(null) }
    // «قراءة مفتاح USB من جديد» was asked: the scan answers even when it finds nothing.
    var usbReadAgain by remember { mutableStateOf(false) }
    // During onboarding, a key's file that sets up the whole TV is offered instead of the wizard's steps.
    var usbSetup by remember { mutableStateOf<UsbSettingsFound?>(null) }
    val setupDone = currentScreen != Screen.Setup
    LaunchedEffect(usbScans, setupDone) {
        if (!setupDone) {
            usbSetup = withContext(Dispatchers.IO) { runCatching { inbox.setupFile(UsbVolumes.mounted(context)) }.getOrNull() }
            return@LaunchedEffect
        }
        val (scan, media) = withContext(Dispatchers.IO) { scanUsb(context, inbox, mediaInbox, usbMountedAt) }
        usbWaiting = usbScans == 0
        if (scan !is UsbScan.Offer) usbMedia = media
        if (usbReadAgain) {
            usbReadAgain = false
            readAgainNotice(scan, media)?.let { notice = it }
        }
        when (scan) {
            is UsbScan.Offer -> {
                usbFoundIsUndo = false
                usbFound = scan.found
            }
            is UsbScan.TemplateWritten -> notice = TvStrings.usbTemplateWritten(context.packageName)
            UsbScan.Inaccessible -> notice = TvStrings.USB_INACCESSIBLE
            UsbScan.ReadOnly -> notice = TvStrings.USB_READ_ONLY
            UsbScan.Quiet -> Unit
        }
    }
    // The file is always read against the settings on screen now, never an older snapshot: any change
    // made meanwhile (the phone's texts, announcements or display options included) reads it again, so
    // «تطبيق» applies what the dialog says.
    val usbPreview = remember(
        usbFound, settingsLoaded, schedule, manualDates, mosqueName, delegationId, currentThemeId, adhkarContent, textAnnouncements,
        adhanScreenMinutes, weatherEnabled, nightScreenEnabled, customBgEnabled, announcementsEnabled, announcementSeconds, announcementsEvery,
    ) { usbFound?.let(inbox::preview) }
    // Nothing over the prayer's screens and the adhkar after it: a notice waits until they end, and only
    // then starts its time (NoticePlacement).
    fun noticePlace(message: String) = NoticePlacement.of(
        flow.phase, adhkarOnWall = afterSalahSlide != null, onDisplay = currentScreen == Screen.Display,
        inSettings = currentScreen == Screen.Settings, copying = message == TvStrings.USB_COPYING || message == usbCopyOutcome,
    )
    val noticeHeld = notice?.let { noticePlace(it) == NoticePlace.HELD } == true
    LaunchedEffect(notice, noticeHeld) {
        // The copy's notice stays until the copy ends: the admin must not pull the key before.
        if (notice != null && notice != TvStrings.USB_COPYING && !noticeHeld) {
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

    // Once a night, after a long run, a fresh process (only on the display, in the quiet hours).
    LaunchedEffect(now.minute) {
        if (currentScreen != Screen.Display || !activity.isResumed) return@LaunchedEffect
        val uptime = Duration.ofMillis(SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())
        val lastIsha = events.filter { it.prayer == Prayer.ISHA && !it.iqamahAt.isAfter(now) }.maxOfOrNull { it.iqamahAt }
        val nextFajr = listOfNotNull(
            todayTimes?.let { today.atTime(it.fajr.hour, it.fajr.minute) },
            tomorrowTimes?.let { today.plusDays(1).atTime(it.fajr.hour, it.fajr.minute) },
        ).firstOrNull { it.isAfter(now) }
        if (MaintenanceRestart.isDue(now, uptime, flow.phase, lastIsha, nextFajr, ramadanNight, adminBusy = adminHere.get())) {
            activity.kiosk.eventLog.append(KioskEvent.MAINT_RESTART, "uptime ${uptime.toHours()} h")
            KioskController.relaunch(activity)
        }
    }

    // An admin screen left open with nobody at the remote gives the wall back to the prayer times.
    // On the time since boot: the wall clock can be corrected by hours while the admin is there.
    var lastKeyAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    SideEffect { activity.onRemoteKey = { lastKeyAt = SystemClock.elapsedRealtime() } }
    // Back from a system page (Wi-Fi, overlay permission, date) counts as being at the remote.
    LaunchedEffect(activity.resumes) { lastKeyAt = SystemClock.elapsedRealtime() }

    // The clock. Online, the network's time settles it: at start, when the network comes back, after
    // the system clock was changed, and every few hours (more often while it is not confirmed). Offline
    // on a box in another zone, the admin is asked once, when at the remote: after a clock or zone
    // change, or on opening the settings.
    var askClock by remember { mutableStateOf(false) }
    var askedThisRun by remember { mutableStateOf(false) }
    var askAfterCheck by remember { mutableStateOf(false) }
    val timeChecks = remember { Channel<Unit>(Channel.CONFLATED) }
    fun adminPresent(): Boolean {
        val elapsed = SystemClock.elapsedRealtime()
        return currentScreen == Screen.Settings || elapsed - lastKeyAt < SETTINGS_IDLE.toMillis() ||
            (activity.kiosk.state.adminAwayUntil ?: 0L) > elapsed
    }
    SideEffect {
        // An offer left waiting for the admin (a key left in) holds nothing: the night restart shows it again.
        val usbOffered = (usbFound != null || usbMedia != null) && !usbWaiting
        adminHere.set(adminPresent() || currentScreen != Screen.Display || phoneSession != null || usbOffered || usbCopying)
    }
    fun openSettings(start: SettingsPage = SettingsPage.Menu) {
        settingsStart = start
        settingsOpenings++
        currentScreen = Screen.Settings
        if (!askedThisRun && reading.trust == ClockTrust.UNVERIFIED) {
            askedThisRun = true
            askClock = true
        }
    }
    // An answer from the admin or the phone: logged, then the box's own clock and zone follow where the app may set them.
    fun answerClock(source: ClockSource, answer: () -> Boolean): Boolean {
        val before = clock.read()
        if (!answer()) return false
        val moved = Duration.between(before.now, clock.read().now).toMillis()
        activity.kiosk.eventLog.append(KioskEvent.CLOCK_CONFIRMED, ClockLog.confirmed(source, moved))
        KioskController.alignSystemClock(context, clock)
        reading = clock.read()
        askClock = false
        return true
    }
    LaunchedEffect(Unit) {
        while (true) {
            val checked = NetworkTime.check(clock, activity.kiosk.eventLog, WeatherRepository.hasInternet(context))
            if (checked) KioskController.alignSystemClock(context, clock)
            reading = clock.read()
            if (askAfterCheck) {
                askAfterCheck = false
                if (reading.trust == ClockTrust.UNVERIFIED) askClock = true
            }
            val wait = if (checked && reading.trust == ClockTrust.TRUSTED) NETWORK_TIME_REFRESH_MILLIS else NETWORK_TIME_RETRY_MILLIS
            if (withTimeoutOrNull(wait) { timeChecks.receive() } != null) delay(NETWORK_SETTLE_MILLIS)
        }
    }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                timeChecks.trySend(Unit)
                networkBacks++
                linkChanges++
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                linkChanges++
            }

            override fun onLost(network: Network) {
                linkChanges++
            }
        }
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        val listener: (ClockChange) -> Unit = { change ->
            if (change == ClockChange.TIME) clock.systemClockChanged()
            reading = clock.read()
            if (adminPresent()) askAfterCheck = true
            timeChecks.trySend(Unit)
        }
        ClockChangeReceiver.listener = listener
        onDispose {
            // A replaced instance is disposed after the new one set its own.
            if (ClockChangeReceiver.listener === listener) ClockChangeReceiver.listener = null
            if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
    LaunchedEffect(reading.trust) { if (reading.trust != ClockTrust.UNVERIFIED) askClock = false }
    val askingClock = askClock && reading.trust == ClockTrust.UNVERIFIED && currentScreen != Screen.Setup && flow.phase !in PRAYER_PHASES
    // A prayer screen or the clock page covers a key's offer: its time stops, and starts again when it is
    // back on the wall (UsbOfferTime).
    val usbHidden = currentScreen == Screen.Display &&
        (flow.phase in PRAYER_PHASES || askingClock || reading.trust == ClockTrust.IMPLAUSIBLE)
    LaunchedEffect(usbFound, usbMedia, usbHidden) {
        if ((usbFound != null || usbMedia != null) && !usbWaiting) lastKeyAt = SystemClock.elapsedRealtime()
    }
    val clockView = if (reading.trust != ClockTrust.TRUSTED || askingClock || currentScreen == Screen.Settings) {
        ClockView.of(clock, reading, deviceTimeText())
    } else null
    val openDateSettings: (() -> Unit)? = remember {
        dateSettingsIntent().takeIf { it.resolveActivity(context.packageManager) != null }?.let { intent ->
            {
                awayForAdmin(activity, ADMIN_SYSTEM_SETTINGS_AWAY, wallNow, prayerAhead)
                runCatching { context.startActivity(intent) }
            }
        }
    }

    // The clock for the phone's page, read on its threads: a snapshot taken here, carried forward with the time since boot.
    val clockSnapshot = remember { AtomicReference<ClockSnapshot?>(null) }
    SideEffect {
        clockSnapshot.set(ClockSnapshot(reading, clock.deviceZone(), clock.zoneDiffers(), SystemClock.elapsedRealtime()))
    }
    val dashboardClock = remember {
        object : DashboardClock {
            override fun state(): DashboardClockState = checkNotNull(clockSnapshot.get()).state(SystemClock.elapsedRealtime())
            override fun set(epochMillis: Long): ClockAnswer = onMain {
                answerClock(ClockSource.PHONE) { clock.acceptInstant(Instant.ofEpochMilli(epochMillis), ClockSource.PHONE) }.also { if (it) publish() }
            }
            override fun confirm(): ClockAnswer = onMain { answerClock(ClockSource.ADMIN) { clock.confirm() }.also { if (it) publish() } }

            // The page reads the state again as soon as it is answered: the new clock is published here,
            // on the main thread, not at the display's next frame.
            private fun publish() {
                val fresh = clock.read()
                clockSnapshot.set(ClockSnapshot(fresh, clock.deviceZone(), clock.zoneDiffers(), SystemClock.elapsedRealtime()))
                dashboardLive.get()?.let { dashboardLive.set(it.copy(now = fresh.now, clockTrusted = fresh.trust != ClockTrust.IMPLAUSIBLE)) }
            }
        }
    }

    val adminEntryOpen = currentScreen == Screen.Display && (usbWaiting || usbFound == null && usbMedia == null) &&
        reading.trust != ClockTrust.IMPLAUSIBLE && !askingClock
    // Over the prayer and the khutba only a Menu key opens settings: not a held OK, nor a pointer.
    val okOpensAdmin = flow.phase !in QUIET_PHASES
    SideEffect {
        activity.adminEntryActive = adminEntryOpen
        activity.okOpensAdmin = okOpensAdmin
        activity.onAdminEntry = { openSettings() }
        // Holding OK does nothing during onboarding: Back says nothing there.
        activity.onBackOnDisplay = { if (currentScreen == Screen.Display) hint = TvStrings.HOLD_OK_HINT }
    }

    LaunchedEffect(now) {
        val idle = Duration.ofMillis(SystemClock.elapsedRealtime() - lastKeyAt)
        val praying = flow.phase in PRAYER_PHASES
        if (currentScreen == Screen.Settings && (idle > SETTINGS_IDLE || praying && idle > SETTINGS_IDLE_DURING_PRAYER)) {
            currentScreen = Screen.Display
        }
        if (askClock && idle > SETTINGS_IDLE) askClock = false // the mark on the wall and the settings still say it
        // Nobody answered: the offer waits for the admin instead of coming back on the wall at every restart.
        if ((usbFound != null || usbMedia != null) && UsbOfferTime.lapsed(idle, usbHidden)) {
            if (usbFound != null && usbFoundIsUndo) usbFound = null else usbWaiting = true
        }
    }

    // A quiet line at the top of the wall, outside the prayer.
    val wallMark = when {
        currentScreen != Screen.Display || flow.phase in PRAYER_PHASES || askingClock -> null
        phoneSession != null -> TvStrings.DASHBOARD_OPEN
        usbWaiting && (usbFound != null || usbMedia != null) -> TvStrings.USB_WAITING
        // Not confirmed, offline, on a box in another zone: where the admin answers.
        reading.trust == ClockTrust.UNVERIFIED -> TvStrings.CLOCK_UNVERIFIED_MARK
        estimatedDate != null -> TvStrings.estimatedDateMark(estimatedDate.first)
        else -> null
    }

    // A key's offer on the wall (or over the settings): what the dialogs below show.
    // Today's iqamahs the wall moved from their setting: on the TV's kiosk page and the phone's.
    val movedIqamahs = remember(events, today) { movedIqamahRows(events, today) }
    val usbOfferOnWall = (usbFound != null && usbPreview != null || usbMedia != null) && (!usbWaiting || currentScreen == Screen.Settings)
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
            screen = DashboardScreen.of(
                adminPage = currentScreen != Screen.Display,
                clockPage = reading.trust == ClockTrust.IMPLAUSIBLE,
                prayerScreen = flow.phase in PRAYER_PHASES,
                clockQuestion = askingClock,
                usbOffer = currentScreen != Screen.Setup && usbOfferOnWall,
                adhkarOnWall = afterSalahSlide != null,
                announcements = showAnnouncements,
                night = isNight && nextFajrEvent != null,
                eid = eidMorning && banner is DayBanner.Eid,
            ),
            tomorrowFajr = tomorrowTimes?.let { LocalTime.of(it.fajr.hour, it.fajr.minute) },
            tomorrowFajrIqamah = tomorrowFajrIqamah,
            settingsVersion = settingsLoaded,
            movedIqamahs = movedIqamahs,
        ))
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
            // An air mouse in mouse mode sends no OK key: held on the wall, its button opens settings.
            .then(if (adminEntryOpen && okOpensAdmin) Modifier.onPointerHold(POINTER_HOLD_MILLIS) { openSettings() } else Modifier)
    ) {
        val inSettings = currentScreen == Screen.Settings
        var markOnNightScreen = false
        when {
            currentScreen == Screen.Setup && usbFound != null && usbPreview != null -> {
                val found = usbFound!!
                UsbImportScreen(
                    found = found,
                    preview = usbPreview,
                    title = TvStrings.USB_SETUP,
                    onApply = {
                        usbFound = null
                        if (inbox.apply(found)) {
                            prefs.isSetupDone = true
                            settingsVersion++
                            openSettings(SettingsPage.Kiosk)
                        }
                    },
                    // Back to the wizard; the file is offered again after it, as any key's.
                    onDismiss = { usbFound = null },
                )
            }
            currentScreen == Screen.Setup -> {
                SetupWizard(
                    gouvernorats = gouvernorats,
                    onUsbSetup = usbSetup?.let { found ->
                        {
                            usbFoundIsUndo = false
                            usbFound = found
                        }
                    },
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
                        openSettings(SettingsPage.Kiosk)
                    }
                )
            }
            reading.trust == ClockTrust.IMPLAUSIBLE && !inSettings && clockView != null -> ClockPage(
                mode = ClockPageMode.BLOCKING,
                clock = clockView,
                onPick = { time -> answerClock(ClockSource.ADMIN) { clock.accept(time) } },
                onConfirm = { answerClock(ClockSource.ADMIN) { clock.confirm() } },
                onOpenSystemSettings = openDateSettings,
                onOpenPhone = { openSettings(SettingsPage.Phone) },
            )
            // The prayer itself outranks everything except an admin working in settings.
            !inSettings && flow.phase == FlowPhase.ADHAN -> AdhanScreen(
                event = flow.event!!,
                now = now,
                mosqueName = mosqueName,
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
            askingClock && clockView != null -> ClockPage(
                mode = ClockPageMode.QUESTION,
                clock = clockView,
                onPick = { time -> answerClock(ClockSource.ADMIN) { clock.accept(time) } },
                onConfirm = { answerClock(ClockSource.ADMIN) { clock.confirm() } },
                onOpenSystemSettings = null,
                onOpenPhone = {
                    askClock = false
                    openSettings(SettingsPage.Phone)
                },
                onLater = { askClock = false },
            )
            usbFound != null && usbPreview != null && (!usbWaiting || inSettings) -> {
                val found = usbFound!!
                UsbImportScreen(
                    found = found,
                    preview = usbPreview,
                    undo = usbFoundIsUndo,
                    today = SettingsChangeLines.Today.of(today, todayTimes),
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
            usbMedia != null && (!usbWaiting || inSettings) -> {
                val media = usbMedia!!
                UsbMediaScreen(
                    found = media,
                    onApply = {
                        usbMedia = null
                        notice = TvStrings.USB_COPYING
                        usbCopying = true
                        scope.launch {
                            val copied = try {
                                withContext(Dispatchers.IO) { mediaInbox.apply(media) }
                            } finally {
                                usbCopying = false
                            }
                            usbCopyOutcome = copyNotice(copied)
                            notice = usbCopyOutcome
                            mediaVersion++
                        }
                    },
                    onDismiss = {
                        mediaInbox.dismiss(media)
                        usbMedia = null
                    },
                )
            }
            inSettings -> key(settingsOpenings) {
                SettingsScreen(
                    mosqueName = mosqueName,
                    delegationName = delegationName,
                    iqamahConfigs = iqamahConfigs,
                    gouvernorats = gouvernorats,
                    announcementsEnabled = announcementsEnabled,
                    customBgEnabled = customBgEnabled,
                    announcementIntervalSec = announcementSeconds,
                    backgroundCount = imageCounts.getValue(MediaKind.BACKGROUNDS),
                    announcementCount = imageCounts.getValue(MediaKind.ANNOUNCEMENTS),
                    // The written ones the wall shows today, not those past their end date.
                    writtenAnnouncementCount = textAnnouncements.count { it.isShownOn(today) },
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
                    todayEvents = todayEvents,
                    ramadanOverrides = ramadanOverrides,
                    onRamadanCleared = { prayer ->
                        prefs.ramadanOverrides = ramadanOverrides - prayer
                        ramadanOverrides = prefs.ramadanOverrides
                    },
                    adhanScreenMinutes = adhanScreenMinutes,
                    onAdhanScreenMinutesChanged = { minutes ->
                        prefs.adhanScreenMinutes = minutes
                        adhanScreenMinutes = prefs.adhanScreenMinutes
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
                        // The same key can bring them back.
                        prefs.usbHandledMedia = ""
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
                                            clockSnapshot.get()?.rows().orEmpty() + updateRows(updater.status, context.packageName) +
                                            EstimatedDates.rows(dashboardLive.get()?.let { EstimatedDates.pending(it.now.toLocalDate(), WeatherRepository.isOnline(context)) }) +
                                            dashboardLive.get()?.movedIqamahs.orEmpty()
                                    },
                                    onSettingsChanged = {
                                        settingsVersion++
                                        canUndoImport = snapshotFile.isFile
                                    },
                                    settingsWanted = { settingsVersion },
                                    onMediaChanged = { mediaVersion++ },
                                    clock = dashboardClock,
                                )
                                val routes = DashboardRoutes(token, backend, SystemClock::elapsedRealtime)
                                val server = DashboardServer(routes::admit, routes::handle)
                                val port = runCatching { server.start() }.getOrNull()
                                if (port != null) {
                                    phoneServer = server
                                    phoneRoutes = routes
                                    phoneToken = token
                                    phoneSession = PhoneAdminSession.of(port, token, activeAddress(context), DashboardServer.localAddresses())
                                }
                            },
                            onStop = ::stopPhone,
                            onBack = back,
                            onOpenWifiSettings = Intent(Settings.ACTION_WIFI_SETTINGS)
                                .takeIf { it.resolveActivity(context.packageManager) != null }
                                ?.let { intent ->
                                    {
                                        awayForAdmin(activity, ADMIN_SYSTEM_SETTINGS_AWAY, wallNow, prayerAhead)
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
                        // Read once when the page opens, then off the main thread so the remote does not stutter.
                        var report by remember {
                            mutableStateOf(KioskReport.collect(context, activity.kiosk, activity.safeMode, android.os.Process.getStartElapsedRealtime()))
                        }
                        LaunchedEffect(activity.resumes, refresh) {
                            report = withContext(Dispatchers.IO) {
                                KioskReport.collect(context, activity.kiosk, activity.safeMode, android.os.Process.getStartElapsedRealtime())
                            }
                        }
                        // Read with the report: a permission granted in the system's page, or by adb, shows at once.
                        val update = remember(activity.resumes, refresh) { updater.status }
                        val overlay = remember(report) { KioskController.overlaySettingsIntent(context) }
                        KioskHealthScreen(
                            report = report,
                            extraRows = clockSnapshot.get()?.rows().orEmpty() + updateRows(update, context.packageName) +
                                EstimatedDates.rows(estimatedDate) + movedIqamahs,
                            onAllowUpdates = if (update.supported && update.needsPermission) {
                                {
                                    awayForAdmin(activity, ADMIN_SYSTEM_SETTINGS_AWAY, wallNow, prayerAhead)
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                            } else null,
                            onInstallUpdate = if (update.available != null && !update.needsPermission) {
                                {
                                    scope.launch {
                                        runCatching { updater.installNow() }
                                        refresh++
                                    }
                                }
                            } else null,
                            onGrantOverlay = overlay?.let { intent ->
                                {
                                    awayForAdmin(activity, ADMIN_SYSTEM_SETTINGS_AWAY, wallNow, prayerAhead)
                                    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }
                            },
                            onToggleHomeMode = {
                                awayForAdmin(activity, ADMIN_SYSTEM_SETTINGS_AWAY, wallNow, prayerAhead)
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
                            onLeaveDeviceOwner = {
                                KioskController.leaveDeviceOwner(activity, activity.kiosk.eventLog)
                                refresh++
                            },
                        )
                    },
                    onExitToAndroid = {
                        awayForAdmin(activity, ADMIN_EXIT_AWAY, wallNow, prayerAhead)
                        activity.kiosk.eventLog.append(KioskEvent.ADMIN_EXIT)
                        KioskController.unlockTask(activity)
                        currentScreen = Screen.Display
                        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                    onBack = { currentScreen = Screen.Display },
                    startPage = settingsStart,
                    previewTimes = { id -> runCatching { prayerRepo.loadDay(id, today) }.getOrNull() },
                    currentDelegationId = delegationId,
                    canUndoImport = canUndoImport,
                    onUndoImport = {
                        UsbSettings.readSaved(snapshotFile)?.let { snapshot ->
                            usbFoundIsUndo = true
                            usbFound = snapshot
                            usbWaiting = false
                            currentScreen = Screen.Display
                        }
                    },
                    onUsbExport = {
                        scope.launch {
                            val written = withContext(Dispatchers.IO) { runCatching { inbox.export(UsbVolumes.mounted(context)) }.getOrDefault(emptyList()) }
                            notice = if (written.isEmpty()) TvStrings.USB_EXPORT_NONE else TvStrings.USB_EXPORTED
                        }
                    },
                    onUsbReadAgain = {
                        prefs.usbHandledSettings = ""
                        prefs.usbHandledMedia = ""
                        currentScreen = Screen.Display
                        usbReadAgain = true
                        usbScans++
                    },
                    onResetAll = {
                        // The previous mosque's images, notices and phone session go with its settings.
                        stopPhone()
                        mediaManager.clearAll()
                        prefs.resetAll()
                        ManualIslamicDateOverrides.all().keys.forEach(ManualIslamicDateOverrides::clear)
                        snapshotFile.delete()
                        activity.kiosk.eventLog.append(KioskEvent.ADMIN_EXIT, "reset")
                        currentScreen = Screen.Setup // what the recreated activity restores
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
                    onTyping = { lastKeyAt = SystemClock.elapsedRealtime() },
                    kioskPreview = {
                        healthRows(KioskReport.collect(context, activity.kiosk, activity.safeMode, android.os.Process.getStartElapsedRealtime())) +
                            clockSnapshot.get()?.rows().orEmpty() + updateRows(updater.status, context.packageName) +
                            EstimatedDates.rows(estimatedDate) + movedIqamahs
                    },
                    clockPage = { openPhone ->
                        clockView?.let { view ->
                            ClockPage(
                                mode = ClockPageMode.SETTINGS,
                                clock = view,
                                onPick = { time -> answerClock(ClockSource.ADMIN) { clock.accept(time) } },
                                onConfirm = { answerClock(ClockSource.ADMIN) { clock.confirm() } },
                                onOpenSystemSettings = openDateSettings,
                                onOpenPhone = openPhone,
                            )
                        }
                    },
                    clock = clockView,
                )
            }
            !timesLoaded -> TimesLoadingScreen()
            afterSalahSlide != null -> AfterSalahAzkarScreen(afterSalahSlide.value, afterSalahSlide.index, afterSalahSlides.size, sky)
            showAnnouncements -> {
                // Held for its list while it is on the wall; whatever takes the wall from it (the prayer,
                // the settings, a key's dialog, a new list) ends it, and it starts again only when due.
                DisposableEffect(announcements) {
                    slideshowList = announcements
                    onDispose { slideshowList = null }
                }
                // A new list (a change from the phone or a key) starts the slideshow again.
                key(announcements) {
                    AnnouncementsSlideshow(
                        announcements = announcements,
                        now = now,
                        displaySeconds = announcementSeconds,
                        footer = MainScreenModel.nextPrayerLine(now, todayTimes, tomorrowTimes, iqamahTimes, tomorrowFajrIqamah),
                        onDismiss = {
                            slideshowList = null
                            announcementsShownFor = adhkarShownFor
                            lastSlideshowElapsed = SystemClock.elapsedRealtime()
                        }
                    )
                }
            }
            isNight && nextFajrEvent != null -> {
                markOnNightScreen = true
                NightScreen(
                    now, nextFajrEvent.adhanAt, nextFajrEvent.iqamahAt,
                    // The Fajr adhan ends the suhoor of tomorrow's fast.
                    imsak = nextFajrEvent.adhanAt.takeIf { ramadanNight },
                    eidNote = eidNote,
                    mark = wallMark,
                )
            }
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
                    eidNote = eidNote,
                    weather = weatherNow,
                    ticker = tickerSlides,
                    sky = sky,
                    backgroundImages = backgroundImages,
                    onSettingsRequested = { openSettings() },
                )
            }
        }
        // Nothing over the prayer's screens and the adhkar; on the admin pages at the top, clear of their keys and hints.
        (hint ?: notice)?.let { message ->
            when (noticePlace(message)) {
                NoticePlace.HELD -> Unit
                NoticePlace.BOTTOM -> ScreenNotice(message)
                NoticePlace.TOP -> ScreenNotice(message, atTop = true)
                NoticePlace.WALL_TOP -> ScreenNotice(message, atTop = true, onWall = true)
            }
        }
        // On the night screen the mark moves with the clock's block instead; the wall's top bar takes its place.
        val wallBar = (hint ?: notice)?.let { noticePlace(it) == NoticePlace.WALL_TOP } == true
        if (!markOnNightScreen && !wallBar) wallMark?.let { TopMark(it) }
    }
}

private val PRAYER_PHASES = setOf(FlowPhase.ADHAN, FlowPhase.IQAMAH_COUNTDOWN, FlowPhase.KHUTBA, FlowPhase.SALAH)
private val QUIET_PHASES = setOf(FlowPhase.KHUTBA, FlowPhase.SALAH)
private val SETTINGS_IDLE: Duration = Duration.ofMinutes(3)
private val SETTINGS_IDLE_DURING_PRAYER: Duration = Duration.ofSeconds(30)
/** As long as OK is held on the remote to open settings. */
private const val POINTER_HOLD_MILLIS = 3_000L
private const val NOTICE_MILLIS = 20_000L
private const val HINT_MILLIS = 4_000L
private const val KIOSK_PAGE_REFRESH_MILLIS = 2_000L
private const val QUICK_START_SETTLE_MILLIS = 5_000L
private const val WEATHER_TICK_MILLIS = 5 * 60_000L
private const val UPDATE_TICK_MILLIS = 30 * 60_000L
private const val NETWORK_TIME_REFRESH_MILLIS = 6 * 60 * 60_000L
private const val NETWORK_TIME_RETRY_MILLIS = 5 * 60_000L
/** A network that just came up is validated a moment later. */
private const val NETWORK_SETTLE_MILLIS = 10_000L
private const val MAIN_THREAD_TIMEOUT_MILLIS = 5_000L

/** The clock as the display last read it, for the phone's threads; [atElapsed] is the time since boot then. */
private class ClockSnapshot(val reading: ClockReading, val deviceZone: ZoneId, val zoneDiffers: Boolean, val atElapsed: Long) {
    fun state(nowElapsed: Long) = DashboardClockState(
        epochMillis = reading.now.atZone(TunisTime.ZONE).toInstant().toEpochMilli() + (nowElapsed - atElapsed),
        verified = reading.trust == ClockTrust.TRUSTED,
        source = reading.source,
        deviceZone = deviceZone.id,
        zoneDiffers = zoneDiffers,
    )

    fun rows(): List<HealthRow> = clockRows(reading.trust, reading.source, deviceZone, zoneDiffers)
}

/** Runs [action] on the main thread, where the clock guard is read, from a server thread; BUSY when it did not start in time. */
private fun onMain(action: () -> Boolean): ClockAnswer =
    when (runPosted(Handler(Looper.getMainLooper())::post, MAIN_THREAD_TIMEOUT_MILLIS, action)) {
        true -> ClockAnswer.DONE
        false -> ClockAnswer.REFUSED
        null -> ClockAnswer.BUSY
    }

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

/**
 * The admin left for the system on purpose: the watchdog leaves them alone for [millis], but never into
 * the prayer at [prayerAt] ([AdminAway]); an alarm brings the display back when that time ends.
 */
private fun awayForAdmin(activity: MainActivity, millis: Long, now: LocalDateTime, prayerAt: LocalDateTime?) {
    val until = AdminAway.until(SystemClock.elapsedRealtime(), millis, now, prayerAt)
    activity.kiosk.update { it.copy(adminAwayUntil = until) }
    KioskController.scheduleAwayEnd(activity, until)
    KioskController.unlockTask(activity) // a pinned app cannot open another app's page
}

/** What the About page says: enough for someone helping by phone. */
private fun aboutLines(context: Context): List<String> {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    val officialYears = OfficialIslamicDates.updates.value.keys.sorted()
    return listOfNotNull(
        "الإصدار ${info?.versionName.orEmpty()} (${info?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it) } ?: 0})" +
            if (BuildConfig.FLAVOR == "github") " · نسخة GitHub، تحدّث نفسها" else " · نسخة Google Play",
        TvStrings.offlineYears(com.tunisianprayertimes.InmPrayerTimes.SUPPORTED_YEARS.first, com.tunisianprayertimes.InmPrayerTimes.SUPPORTED_YEARS.last),
        officialYears.takeIf { it.isNotEmpty() }?.let { "تواريخ رمضان والعيد الرسمية: ${it.joinToString("، ")} هـ" },
        "ملف الإعدادات على مفتاح USB: Android/data/${context.packageName}/files/mosque-tv.json",
        TvStrings.HOLD_OK_HINT,
        "الجهاز: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · أندرويد ${android.os.Build.VERSION.RELEASE}",
        context.packageName,
    )
}

/** The TV's IPv4 address on the network it uses now (the one a phone on the same network reaches), or null. */
private fun activeAddress(context: Context): String? = runCatching {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    manager.getLinkProperties(manager.activeNetwork)?.linkAddresses.orEmpty()
        .map { it.address }.filterIsInstance<java.net.Inet4Address>().firstOrNull()?.hostAddress
}.getOrNull()

private fun clockSample() = ClockSample(System.currentTimeMillis(), SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())

private fun dateSettingsIntent() = Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** The device clock as the device shows it, so the admin sees what is wrong. */
private fun deviceTimeText(): String =
    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)) + " (" + ZoneId.systemDefault().id + ")"

private suspend fun loadDay(repo: PrayerTimesRepository, delegationId: Int, date: LocalDate): DayPrayerTimes? =
    if (delegationId > 0) withContext(Dispatchers.Default) { repo.loadDay(delegationId, date) } else null

/**
 * What the plugged-in keys hold: the settings file, and images the TV has not seen yet. Every key
 * gets the image folders, so the admin sees where to put them; only the key mounted at [mountedAt]
 * gets a template. Never throws; a failed scan is quiet.
 */
private fun scanUsb(context: android.content.Context, inbox: UsbSettingsInbox, media: UsbMediaInbox, mountedAt: String?): Pair<UsbScan, UsbMediaFound?> = try {
    val volumes = UsbVolumes.mounted(context)
    val justMounted = { volume: RemovableVolume -> mountedAt != null && UsbVolumes.isAt(volume, mountedAt) }
    val scan = inbox.scan(volumes, UsbVolumes.hidden(context, volumes), justMounted).also { Log.i(TAG, "USB scan: $it") }
    volumes.forEach(UsbMedia::ensureFolders)
    scan to media.scan(volumes)
} catch (e: Exception) {
    Log.w(TAG, "USB settings scan failed", e)
    UsbScan.Quiet to null
}

private enum class Screen { Setup, Display, Settings }
