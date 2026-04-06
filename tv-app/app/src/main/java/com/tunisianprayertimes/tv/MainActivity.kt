package com.tunisianprayertimes.tv

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.RamadanDetector
import com.tunisianprayertimes.tv.data.*
import com.tunisianprayertimes.tv.ui.display.*
import com.tunisianprayertimes.tv.ui.settings.SettingsScreen
import com.tunisianprayertimes.tv.ui.setup.SetupWizard
import com.tunisianprayertimes.tv.ui.theme.TvPrayerTheme
import com.tunisianprayertimes.tv.ui.theme.ThemeRegistry
import kotlinx.coroutines.delay
import java.util.Calendar

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var prayerRepo: PrayerTimesRepository
    private lateinit var gouvernoratRepo: GouvernoratRepository
    private lateinit var mediaManager: LocalMediaManager
    private lateinit var otaUpdater: OtaDataUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen always on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = PrefsManager(this)
        prayerRepo = PrayerTimesRepository(this)
        gouvernoratRepo = GouvernoratRepository(this)
        mediaManager = LocalMediaManager(this)
        mediaManager.ensureDirectories()
        otaUpdater = OtaDataUpdater(this)

        setContent {
            // Theme state lives here so it wraps TvPrayerTheme
            var themeId by remember { mutableStateOf(prefs.themeId) }
            val themeConfig = remember(themeId) { ThemeRegistry.findById(themeId) }

            TvPrayerTheme(themeConfig = themeConfig) {
                TvApp(
                    prefs = prefs,
                    prayerRepo = prayerRepo,
                    gouvernoratRepo = gouvernoratRepo,
                    mediaManager = mediaManager,
                    otaUpdater = otaUpdater,
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

@Composable
private fun TvApp(
    prefs: PrefsManager,
    prayerRepo: PrayerTimesRepository,
    gouvernoratRepo: GouvernoratRepository,
    mediaManager: LocalMediaManager,
    otaUpdater: OtaDataUpdater,
    currentThemeId: String,
    onThemeChanged: (String) -> Unit,
) {
    var isSetupDone by remember { mutableStateOf(prefs.isSetupDone) }
    var currentScreen by remember { mutableStateOf<Screen>(if (prefs.isSetupDone) Screen.Display else Screen.Setup) }

    // Data state
    var delegationId by remember { mutableIntStateOf(prefs.delegationId) }
    var delegationName by remember { mutableStateOf(prefs.delegationName) }
    var mosqueName by remember { mutableStateOf(prefs.mosqueName) }
    var iqamahConfigs by remember {
        mutableStateOf(
            listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)
                .associateWith { prefs.getIqamahConfig(it) }
        )
    }
    var jomoaaConfig by remember { mutableStateOf(prefs.getJomoaaIqamahConfig()) }

    // Prayer times — refresh daily
    var dayPrayerTimes by remember { mutableStateOf<DayPrayerTimes?>(null) }
    var shuruk by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Refresh prayer data when delegation changes or at midnight
    LaunchedEffect(delegationId) {
        while (true) {
            if (delegationId > 0) {
                // Background OTA check (daily, non-blocking)
                if (otaUpdater.shouldCheck(delegationId)) {
                    otaUpdater.checkAndUpdate(delegationId)
                }
                dayPrayerTimes = prayerRepo.loadToday(delegationId)
                shuruk = prayerRepo.loadTodayShuruk(delegationId)
            }
            // Wait until next midnight to refresh
            val now = Calendar.getInstance()
            val tomorrow = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 5)
            }
            delay(tomorrow.timeInMillis - now.timeInMillis)
        }
    }

    // Gouvernorats for setup/settings
    val gouvernorats = remember { gouvernoratRepo.loadAll() }

    // Ramadan detection — refreshes with prayer data
    var isRamadan by remember { mutableStateOf(RamadanDetector.isRamadan()) }
    LaunchedEffect(dayPrayerTimes) {
        isRamadan = RamadanDetector.isRamadan()
    }

    // Local media — backgrounds and announcements
    var backgroundImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var announcements by remember { mutableStateOf<List<Announcement>>(emptyList()) }
    LaunchedEffect(Unit) {
        backgroundImages = if (prefs.customBackgroundEnabled) mediaManager.getBackgroundImages() else emptyList()
        announcements = if (prefs.announcementsEnabled) mediaManager.getAnnouncements() else emptyList()
    }

    // Transition overlay state
    var overlayState by remember { mutableStateOf<OverlayState>(OverlayState.None) }

    // Iqamah countdown ticker
    var countdownSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(overlayState) {
        if (overlayState is OverlayState.IqamahCountdown) {
            val config = (overlayState as OverlayState.IqamahCountdown).let { state ->
                if (state.prayer == Prayer.JOMOAA) jomoaaConfig
                else iqamahConfigs[state.prayer] ?: IqamahConfig()
            }
            countdownSeconds = config.delayMinutes * 60
            while (countdownSeconds > 0) {
                delay(1000L)
                countdownSeconds--
            }
            // Countdown done — show prayer in progress
            val prayer = (overlayState as? OverlayState.IqamahCountdown)?.prayer
            if (prayer != null) {
                overlayState = OverlayState.PrayerInProgress(prayer)
            }
        }
    }

    when {
        // Overlay takes precedence
        overlayState is OverlayState.Adhan -> {
            AdhanScreen(
                prayer = (overlayState as OverlayState.Adhan).prayer,
                onDismiss = {
                    val prayer = (overlayState as OverlayState.Adhan).prayer
                    overlayState = OverlayState.IqamahCountdown(prayer)
                }
            )
        }
        overlayState is OverlayState.IqamahCountdown -> {
            IqamahCountdownScreen(
                prayer = (overlayState as OverlayState.IqamahCountdown).prayer,
                remainingSeconds = countdownSeconds,
                onDismiss = {
                    val prayer = (overlayState as OverlayState.IqamahCountdown).prayer
                    overlayState = OverlayState.PrayerInProgress(prayer)
                }
            )
        }
        overlayState is OverlayState.PrayerInProgress -> {
            PrayerInProgressScreen(
                prayer = (overlayState as OverlayState.PrayerInProgress).prayer,
                onDismiss = {
                    val prayer = (overlayState as OverlayState.PrayerInProgress).prayer
                    overlayState = OverlayState.AfterSalah(prayer)
                }
            )
        }
        overlayState is OverlayState.AfterSalah -> {
            AfterSalahAzkarScreen(
                prayer = (overlayState as OverlayState.AfterSalah).prayer,
                durationMinutes = 10,
                onDismiss = {
                    // Show announcements after azkar if available
                    if (announcements.isNotEmpty() && prefs.announcementsEnabled) {
                        overlayState = OverlayState.Announcements
                    } else {
                        overlayState = OverlayState.None
                    }
                }
            )
        }
        overlayState is OverlayState.Announcements -> {
            AnnouncementsSlideshow(
                announcements = announcements,
                displaySeconds = prefs.announcementIntervalSec,
                onDismiss = { overlayState = OverlayState.None }
            )
        }
        // Normal screens
        currentScreen == Screen.Setup -> {
            SetupWizard(
                gouvernorats = gouvernorats,
                onComplete = { gouvId, delegation, configs, jConfig, mName ->
                    prefs.gouvernoratId = gouvId
                    prefs.delegationId = delegation.id
                    prefs.delegationName = delegation.nomAr
                    prefs.mosqueName = mName
                    configs.forEach { (prayer, config) -> prefs.setIqamahConfig(prayer, config) }
                    prefs.setJomoaaIqamahConfig(jConfig)
                    prefs.isSetupDone = true

                    delegationId = delegation.id
                    delegationName = delegation.nomAr
                    mosqueName = mName
                    iqamahConfigs = configs
                    jomoaaConfig = jConfig
                    isSetupDone = true
                    currentScreen = Screen.Display
                }
            )
        }
        currentScreen == Screen.Settings -> {
            SettingsScreen(
                mosqueName = mosqueName,
                delegationName = delegationName,
                iqamahConfigs = iqamahConfigs,
                jomoaaConfig = jomoaaConfig,
                gouvernorats = gouvernorats,
                announcementsEnabled = prefs.announcementsEnabled,
                customBgEnabled = prefs.customBackgroundEnabled,
                announcementIntervalSec = prefs.announcementIntervalSec,
                backgroundCount = backgroundImages.size,
                announcementCount = announcements.size,
                currentThemeId = currentThemeId,
                onMosqueNameChanged = { name ->
                    prefs.mosqueName = name
                    mosqueName = name
                },
                onIqamahChanged = { prayer, config ->
                    prefs.setIqamahConfig(prayer, config)
                    iqamahConfigs = iqamahConfigs + (prayer to config)
                },
                onJomoaaChanged = { config ->
                    prefs.setJomoaaIqamahConfig(config)
                    jomoaaConfig = config
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
                    announcements = if (enabled) mediaManager.getAnnouncements() else emptyList()
                },
                onCustomBgEnabledChanged = { enabled ->
                    prefs.customBackgroundEnabled = enabled
                    backgroundImages = if (enabled) mediaManager.getBackgroundImages() else emptyList()
                },
                onAnnouncementIntervalChanged = { interval ->
                    prefs.announcementIntervalSec = interval
                },
                onThemeChanged = onThemeChanged,
                onBack = { currentScreen = Screen.Display }
            )
        }
        else -> {
            PrayerDisplayScreen(
                dayPrayerTimes = dayPrayerTimes,
                shuruk = shuruk,
                mosqueName = mosqueName,
                delegationName = delegationName,
                iqamahConfigs = iqamahConfigs,
                jomoaaConfig = jomoaaConfig,
                isRamadan = isRamadan,
                backgroundImages = backgroundImages,
                onSettingsRequested = { currentScreen = Screen.Settings },
                onAdhanTriggered = { prayer ->
                    overlayState = OverlayState.Adhan(prayer)
                },
                onIqamahTriggered = { /* handled by countdown flow */ }
            )
        }
    }
}

private enum class Screen { Setup, Display, Settings }

private sealed class OverlayState {
    data object None : OverlayState()
    data class Adhan(val prayer: Prayer) : OverlayState()
    data class IqamahCountdown(val prayer: Prayer) : OverlayState()
    data class PrayerInProgress(val prayer: Prayer) : OverlayState()
    data class AfterSalah(val prayer: Prayer) : OverlayState()
    data object Announcements : OverlayState()
}
