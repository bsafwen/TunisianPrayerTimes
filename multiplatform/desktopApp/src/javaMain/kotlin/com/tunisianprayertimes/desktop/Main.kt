package com.tunisianprayertimes.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.tunisianprayertimes.*
import com.tunisianprayertimes.platform.*
import com.tunisianprayertimes.ui.Strings
import com.tunisianprayertimes.ui.theme.*
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDate
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Tray icon reference shared with the background scheduler for notifications. */
private var globalTrayIcon: TrayIcon? = null

private fun showNotification(message: String, type: TrayIcon.MessageType = TrayIcon.MessageType.INFO) {
    globalTrayIcon?.displayMessage(Strings.APP_NAME, message, type)
}

fun main() {
    // Force Arabic locale so Material3 DatePicker displays in Arabic
    Locale.setDefault(Locale("ar", "TN"))

    // Initialize data loaders with the data directory
    val dataDir = resolveDataDir()
    PrayerDataLoader.init(dataDir)
    GouvernoratLoader.init(dataDir)

    // Start Ramadan override polling if near a moon-sighting event
    RamadanOverrideChecker.startPollingIfNeeded()

    // Start background silence scheduler
    val silenceSchedulerHandle = startBackgroundSilenceScheduler()

    application {
        var isVisible by remember { mutableStateOf(true) }
        val windowState = rememberWindowState(width = 500.dp, height = 900.dp)

        // System tray
        val trayIcon = remember { setupSystemTray(
            onShow = { isVisible = true },
            onQuit = {
                silenceSchedulerHandle?.cancel(false)
                exitApplication()
            }
        ).also { globalTrayIcon = it } }

        DisposableEffect(Unit) {
            onDispose {
                trayIcon?.let { icon ->
                    if (SystemTray.isSupported()) {
                        SystemTray.getSystemTray().remove(icon)
                    }
                }
            }
        }

        if (isVisible) {
            val windowIcon = remember { appIconPainter() }
            Window(
                onCloseRequest = {
                    if (SystemTray.isSupported()) {
                        isVisible = false // minimize to tray
                        showNotification(Strings.TRAY_MINIMIZED)
                    } else {
                        silenceSchedulerHandle?.cancel(false)
                        exitApplication()
                    }
                },
                title = Strings.APP_NAME,
                state = windowState,
                icon = windowIcon
            ) {
                TunisianPrayerTimesTheme {
                    DesktopMainScreen()
                }
            }
        }
    }
}

private fun setupSystemTray(
    onShow: () -> Unit,
    onQuit: () -> Unit
): TrayIcon? {
    if (!SystemTray.isSupported()) return null

    val tray = SystemTray.getSystemTray()
    val image = AppIcon.createTrayIcon()
    val popup = PopupMenu()

    val showItem = MenuItem(Strings.TRAY_SHOW)
    showItem.addActionListener { onShow() }

    val quitItem = MenuItem(Strings.TRAY_QUIT)
    quitItem.addActionListener { onQuit() }

    popup.add(showItem)
    popup.addSeparator()
    popup.add(quitItem)

    val trayIcon = TrayIcon(image, Strings.APP_NAME, popup)
    trayIcon.isImageAutoSize = true
    trayIcon.addActionListener { onShow() } // double-click opens window

    tray.add(trayIcon)
    return trayIcon
}

/**
 * Convert the generated AppIcon BufferedImage to a Compose BitmapPainter for use as window icon.
 */
private fun appIconPainter(): androidx.compose.ui.graphics.painter.BitmapPainter {
    val awtImage = AppIcon.create(256)
    val bos = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(awtImage, "png", bos)
    val imageBitmap = androidx.compose.ui.res.loadImageBitmap(java.io.ByteArrayInputStream(bos.toByteArray()))
    return androidx.compose.ui.graphics.painter.BitmapPainter(imageBitmap)
}

/** Shared executor for precise alarm scheduling. */
private val silenceExecutor = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "SilenceScheduler").apply { isDaemon = true }
}

/** Tracks all pending one-shot alarm futures so they can be cancelled on reschedule. */
private val pendingAlarms = mutableListOf<ScheduledFuture<*>>()

private fun startBackgroundSilenceScheduler(): ScheduledFuture<*>? {
    if (!Preferences.isEnabled()) return null

    // Run immediately, then reschedule precise alarms
    scheduleAlarms()

    // Safety-net: re-compute alarms every 10 minutes in case something drifts
    return silenceExecutor.scheduleAtFixedRate({
        try {
            scheduleAlarms()
        } catch (e: Exception) {
            System.err.println("Background silence scheduler error: ${e.message}")
        }
    }, 10, 10, TimeUnit.MINUTES)
}

/**
 * Compute alarm times and schedule precise one-shot tasks for each silence/unsilence event.
 * Also handles the "currently in window" case immediately.
 */
private fun scheduleAlarms() {
    if (!Preferences.isEnabled()) return

    // Cancel any previously scheduled one-shot alarms
    synchronized(pendingAlarms) {
        pendingAlarms.forEach { it.cancel(false) }
        pendingAlarms.clear()
    }

    val delegationId = Preferences.getDelegationId()
    val now = Calendar.getInstance()
    val year = now.get(Calendar.YEAR)
    val month = now.get(Calendar.MONTH) + 1
    val day = now.get(Calendar.DAY_OF_MONTH)
    val isFriday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY

    val todayTimes = PrayerDataLoader.loadDayPrayerTimes(delegationId, year, month, day)
        ?: return

    val configs = mapOf(
        Prayer.FAJR to Preferences.getConfig(Prayer.FAJR),
        Prayer.DHUHR to Preferences.getConfig(Prayer.DHUHR),
        Prayer.ASR to Preferences.getConfig(Prayer.ASR),
        Prayer.MAGHRIB to Preferences.getConfig(Prayer.MAGHRIB),
        Prayer.ISHA to Preferences.getConfig(Prayer.ISHA),
        Prayer.JOMOAA to Preferences.getConfig(Prayer.JOMOAA),
        Prayer.AID_FITR to Preferences.getConfig(Prayer.AID_FITR),
        Prayer.AID_ADHA to Preferences.getConfig(Prayer.AID_ADHA)
    )

    val result = SilenceAlarmComputer.compute(
        now = now,
        todayTimes = todayTimes,
        configs = configs,
        isFriday = isFriday,
        jomoaaHour = Preferences.getJomoaaTimeHour(),
        jomoaaMinute = Preferences.getJomoaaTimeMinute()
    )

    // Handle immediate state: if we're inside a silence window right now, silence immediately
    if (result.currentlyInSilenceWindow && !SilenceController.isSilent()) {
        SilenceController.enableSilence()
        showNotification(Strings.TOAST_SILENT_ENABLED)
    } else if (!result.currentlyInSilenceWindow && SilenceController.isSilent()
        && !Preferences.isManualSilenceActive()) {
        SilenceController.disableSilence()
        showNotification(Strings.TOAST_NORMAL_RESTORED)
    }

    // Schedule precise one-shot tasks for each future alarm
    val nowMillis = now.timeInMillis
    synchronized(pendingAlarms) {
        for (alarm in result.alarms) {
            val delayMs = alarm.triggerAtMillis - nowMillis
            if (delayMs <= 0) continue // already past

            val future = silenceExecutor.schedule({
                try {
                    when (alarm.action) {
                        SilenceAlarmComputer.AlarmAction.SILENCE -> {
                            if (Preferences.isEnabled() && !SilenceController.isSilent()) {
                                SilenceController.enableSilence()
                                showNotification(Strings.TOAST_SILENT_ENABLED)
                            }
                        }
                        SilenceAlarmComputer.AlarmAction.UNSILENCE -> {
                            if (SilenceController.isSilent() && !Preferences.isManualSilenceActive()) {
                                SilenceController.disableSilence()
                                showNotification(Strings.TOAST_NORMAL_RESTORED)
                            }
                        }
                        SilenceAlarmComputer.AlarmAction.MIDNIGHT_RESCHEDULE -> {
                            scheduleAlarms()
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("Alarm execution error: ${e.message}")
                }
            }, delayMs, TimeUnit.MILLISECONDS)
            pendingAlarms.add(future)
        }
    }
}

private fun resolveDataDir(): File {
    // 1. Bundled app: Compose sets compose.application.resources.dir inside the native package
    val bundledDir = System.getProperty("compose.application.resources.dir")
    if (bundledDir != null) {
        val dir = File(bundledDir)
        if (File(dir, "gouvernorats.json").exists()) return dir
    }
    // 2. Dev mode: data symlink or docs/ relative to working directory
    val candidates = listOf(
        File("data"),
        File("../docs"),
        File("../../docs"),
    )
    return candidates.firstOrNull { File(it, "gouvernorats.json").exists() }
        ?: error("Cannot find data directory with gouvernorats.json. Place it in a 'data' or 'docs' folder.")
}

@Composable
fun DesktopMainScreen() {
    var isSilent by remember { mutableStateOf(SilenceController.isSilent()) }
    var autoSilenceEnabled by remember { mutableStateOf(Preferences.isEnabled()) }
    var delegationId by remember { mutableIntStateOf(Preferences.getDelegationId()) }
    var manualUsesDuration by remember { mutableStateOf(Preferences.usesManualSilenceDuration()) }
    var manualDurationHours by remember {
        mutableStateOf((Preferences.getManualSilenceDurationMinutes() / 60).toString())
    }
    var manualDurationMinutes by remember {
        mutableStateOf((Preferences.getManualSilenceDurationMinutes() % 60).toString())
    }
    var manualSilenceActive by remember { mutableStateOf(Preferences.isManualSilenceActive()) }
    var manualSilenceEndsAtMillis by remember {
        mutableLongStateOf(Preferences.getManualSilenceEndsAtMillis())
    }

    // Auto-refresh timer
    LaunchedEffect(manualSilenceEndsAtMillis) {
        if (manualSilenceEndsAtMillis <= 0L) return@LaunchedEffect
        val waitMillis = manualSilenceEndsAtMillis - System.currentTimeMillis()
        if (waitMillis <= 0L) {
            manualSilenceActive = false
            manualSilenceEndsAtMillis = -1L
            isSilent = SilenceController.isSilent()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(waitMillis)
        manualSilenceActive = false
        manualSilenceEndsAtMillis = -1L
        isSilent = SilenceController.isSilent()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgCream)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        IslamicHeader()

        // Content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // Status card
            StatusCard(isSilent = isSilent)

            // Location picker
            LocationPickerCard(
                delegationId = delegationId,
                onDelegationSelected = { delegation ->
                    delegationId = delegation.id
                    Preferences.setDelegationId(delegation.id)
                }
            )

            // Prayer settings
            PrayerSettingsCard(delegationId = delegationId)

            // Auto-silence toggle
            AutoSilenceCard(
                enabled = autoSilenceEnabled,
                onToggle = { enabled ->
                    autoSilenceEnabled = enabled
                    Preferences.setEnabled(enabled)
                }
            )

            // Ramadan badge
            if (RamadanDetector.isRamadan()) {
                RamadanBadge()
            }

            // Manual silence
            ManualSilenceCard(
                isSilent = isSilent,
                manualUsesDuration = manualUsesDuration,
                manualDurationHours = manualDurationHours,
                manualDurationMinutes = manualDurationMinutes,
                manualSilenceActive = manualSilenceActive,
                manualSilenceEndsAtMillis = manualSilenceEndsAtMillis,
                onUseDurationChange = { usesDuration ->
                    manualUsesDuration = usesDuration
                    Preferences.setManualSilenceUsesDuration(usesDuration)
                },
                onDurationHoursChange = { value ->
                    manualDurationHours = value
                    val totalMinutes = (value.toIntOrNull() ?: 0) * 60 + (manualDurationMinutes.toIntOrNull() ?: 0)
                    Preferences.setManualSilenceDurationMinutes(totalMinutes)
                },
                onDurationMinutesChange = { value ->
                    manualDurationMinutes = value
                    val totalMinutes = (manualDurationHours.toIntOrNull() ?: 0) * 60 + (value.toIntOrNull() ?: 0)
                    Preferences.setManualSilenceDurationMinutes(totalMinutes)
                },
                onClick = {
                    if (isSilent) {
                        SilenceController.disableSilence()
                        Preferences.clearManualSilenceState()
                        Preferences.clearManualSilenceEndsAt()
                        TimerScheduler.cancel()
                        isSilent = false
                        manualSilenceActive = false
                        manualSilenceEndsAtMillis = -1L
                        showNotification(Strings.TOAST_NORMAL_RESTORED)
                    } else {
                        val totalMinutes = resolveManualTotalMinutes(
                            manualDurationHours, manualDurationMinutes,
                            Preferences.getManualSilenceDurationMinutes()
                        )
                        SilenceController.enableSilence()
                        Preferences.markManualSilenceActive()
                        isSilent = true
                        manualSilenceActive = true
                        showNotification(Strings.TOAST_SILENT_ENABLED)

                        if (manualUsesDuration && totalMinutes > 0) {
                            val endsAt = TimerScheduler.scheduleAfter(totalMinutes) {
                                SilenceController.disableSilence()
                                Preferences.clearManualSilenceState()
                                Preferences.clearManualSilenceEndsAt()
                                showNotification(Strings.TOAST_NORMAL_RESTORED)
                            }
                            Preferences.setManualSilenceEndsAtMillis(endsAt)
                            manualSilenceEndsAtMillis = endsAt
                        } else {
                            manualSilenceEndsAtMillis = -1L
                        }
                    }
                }
            )

            // Info text
            Text(
                text = Strings.INFO_TEXT,
                fontSize = 12.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun IslamicHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(HeaderStart, HeaderEnd),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            )
            .padding(top = 14.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "﷽", fontSize = 28.sp, color = Gold)
        Text(
            text = Strings.SUBTITLE,
            fontSize = 14.sp,
            color = Color(0xFFB2DFDB),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        Text(
            text = Strings.SOURCE,
            fontSize = 11.sp,
            color = Color(0x80B2DFDB),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StatusCard(isSilent: Boolean) {
    val bgColor by animateColorAsState(
        targetValue = if (isSilent) Color(0xFFFFEBEE) else Color(0xFFE8F5E9),
        label = "statusBg"
    )
    val accentColor = if (isSilent) SilenceRed else GreenPrimary
    val statusText = if (isSilent) "الكمبيوتر في الوضع الصامت 🔇" else "الكمبيوتر في الوضع العادي 🔔"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(bgColor)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(accentColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = statusText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = accentColor
        )
    }
}

@Composable
private fun LocationPickerCard(
    delegationId: Int,
    onDelegationSelected: (Delegation) -> Unit
) {
    val savedDelegation = remember(delegationId) { GouvernoratLoader.findDelegationById(delegationId) }
    var showPicker by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = Strings.LOCATION_TITLE,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrayerNameColor
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = savedDelegation?.displayName() ?: Strings.HINT_SEARCH_DELEGATION,
                    fontSize = 14.sp,
                    color = if (savedDelegation != null) TextDark else TextMuted,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(GoldLight.copy(alpha = 0.25f))
                        .clickable { showPicker = !showPicker }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // Inline delegation search (desktop doesn't have bottom sheets)
            AnimatedVisibility(visible = showPicker) {
                DelegationSearchPanel(
                    currentDelegationId = delegationId,
                    onSelect = { delegation ->
                        showPicker = false
                        onDelegationSelected(delegation)
                    }
                )
            }
        }
    }
}

@Composable
private fun DelegationSearchPanel(
    currentDelegationId: Int,
    onSelect: (Delegation) -> Unit
) {
    val gouvernorats = remember { GouvernoratLoader.loadAll() }
    val allDelegations = remember { GouvernoratLoader.loadAllDelegations() }
    val availableIds = remember { allDelegations.map { it.id }.toSet() }
    var searchText by remember { mutableStateOf("") }

    val filtered = remember(searchText) {
        val terms = searchText.lowercase().trim().split(" ").filter { it.isNotEmpty() }
        if (terms.isEmpty()) allDelegations.filter { it.id in availableIds }
        else allDelegations.filter { d ->
            d.id in availableIds && terms.all { term -> d.searchableText().contains(term) }
        }
    }

    Column(modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            placeholder = { Text(Strings.HINT_SEARCH_DELEGATION, fontSize = 14.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(4.dp))

        Column(modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            if (filtered.isEmpty()) {
                Text(
                    text = Strings.NO_RESULTS,
                    fontSize = 14.sp,
                    color = TextMuted,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                filtered.forEach { delegation ->
                    val isSelected = delegation.id == currentDelegationId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(delegation) }
                            .background(if (isSelected) GoldLight.copy(alpha = 0.2f) else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = delegation.displayName(),
                            fontSize = 14.sp,
                            color = if (isSelected) GreenPrimaryDark else TextDark,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                        if (isSelected) {
                            Text("✓", fontSize = 16.sp, color = GreenPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                    HorizontalDivider(color = Divider, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun PrayerSettingsCard(delegationId: Int) {
    val today = remember { Calendar.getInstance() }
    var selectedDate by remember { mutableStateOf(today.timeInMillis) }

    val selectedCal = remember(selectedDate) {
        Calendar.getInstance().apply { timeInMillis = selectedDate }
    }
    val isToday = remember(selectedDate) {
        val now = Calendar.getInstance()
        selectedCal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            selectedCal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    }

    val displayTimes = remember(delegationId, selectedDate) {
        try {
            PrayerDataLoader.loadDayPrayerTimes(
                delegationId,
                selectedCal.get(Calendar.YEAR),
                selectedCal.get(Calendar.MONTH) + 1,
                selectedCal.get(Calendar.DAY_OF_MONTH)
            )
        } catch (_: Exception) { null }
    }

    val dateText = remember(selectedDate) { formatArabicDate(selectedCal) }
    var showDatePicker by remember { mutableStateOf(false) }

    // Jomoaa time
    var jomoaaH by remember { mutableStateOf(Preferences.getJomoaaTimeHour()) }
    var jomoaaM by remember { mutableStateOf(Preferences.getJomoaaTimeMinute()) }
    val resolvedJomoaaH = if (jomoaaH >= 0) jomoaaH else displayTimes?.dhuhr?.hour ?: -1
    val resolvedJomoaaM = if (jomoaaM >= 0) jomoaaM else displayTimes?.dhuhr?.minute ?: -1
    var showJomoaaTimePicker by remember { mutableStateOf(false) }

    // Aid Fitr time (default = Shuruk of Eid day)
    var aidFitrH by remember { mutableStateOf(Preferences.getAidFitrTimeHour()) }
    var aidFitrM by remember { mutableStateOf(Preferences.getAidFitrTimeMinute()) }
    val defaultAidFitrTime = remember(delegationId) {
        RamadanOverrideChecker.getDefaultEidPrayerTime(delegationId, RamadanOverrideChecker.getEidFitrDate())
    }
    val resolvedAidFitrH = if (aidFitrH >= 0) aidFitrH else defaultAidFitrTime?.first ?: -1
    val resolvedAidFitrM = if (aidFitrM >= 0) aidFitrM else defaultAidFitrTime?.second ?: -1
    var showAidFitrTimePicker by remember { mutableStateOf(false) }

    // Aid Adha time (default = Shuruk of Eid day)
    var aidAdhaH by remember { mutableStateOf(Preferences.getAidAdhaTimeHour()) }
    var aidAdhaM by remember { mutableStateOf(Preferences.getAidAdhaTimeMinute()) }
    val defaultAidAdhaTime = remember(delegationId) {
        RamadanOverrideChecker.getDefaultEidPrayerTime(delegationId, RamadanOverrideChecker.getEidAdhaDate())
    }
    val resolvedAidAdhaH = if (aidAdhaH >= 0) aidAdhaH else defaultAidAdhaTime?.first ?: -1
    val resolvedAidAdhaM = if (aidAdhaM >= 0) aidAdhaM else defaultAidAdhaTime?.second ?: -1
    var showAidAdhaTimePicker by remember { mutableStateOf(false) }

    val isAidFitr = remember(selectedDate, displayTimes) {
        val gregDate = LocalDate.of(
            selectedCal.get(Calendar.YEAR),
            selectedCal.get(Calendar.MONTH) + 1,
            selectedCal.get(Calendar.DAY_OF_MONTH)
        )
        val now = Calendar.getInstance()
        RamadanOverrideChecker.shouldShowEidFitrPrayer(
            date = gregDate,
            nowHour = now.get(Calendar.HOUR_OF_DAY),
            nowMinute = now.get(Calendar.MINUTE),
            dhuhrHour = displayTimes?.dhuhr?.hour ?: 13,
            dhuhrMinute = displayTimes?.dhuhr?.minute ?: 0,
            isToday = isToday,
        )
    }
    val isAidAdha = remember(selectedDate, displayTimes) {
        val gregDate = LocalDate.of(
            selectedCal.get(Calendar.YEAR),
            selectedCal.get(Calendar.MONTH) + 1,
            selectedCal.get(Calendar.DAY_OF_MONTH)
        )
        val now = Calendar.getInstance()
        RamadanOverrideChecker.shouldShowEidAdhaPrayer(
            date = gregDate,
            nowHour = now.get(Calendar.HOUR_OF_DAY),
            nowMinute = now.get(Calendar.MINUTE),
            dhuhrHour = displayTimes?.dhuhr?.hour ?: 13,
            dhuhrMinute = displayTimes?.dhuhr?.minute ?: 0,
            isToday = isToday,
        )
    }

    val isFriday = remember(selectedDate) {
        selectedCal.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
    }

    // Next prayer (only for today)
    val nextPrayer = remember(delegationId, isToday, jomoaaH, jomoaaM) {
        if (!isToday) null
        else {
            val now = Calendar.getInstance()
            try {
                val todayTimes = PrayerDataLoader.loadDayPrayerTimes(
                    delegationId,
                    now.get(Calendar.YEAR),
                    now.get(Calendar.MONTH) + 1,
                    now.get(Calendar.DAY_OF_MONTH)
                )
                todayTimes?.nextPrayer(
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY,
                    Preferences.getJomoaaTimeHour(),
                    Preferences.getJomoaaTimeMinute()
                )
            } catch (_: Exception) { null }
        }
    }

    // Date navigation bounds
    val canGoForward = remember(delegationId, selectedDate) {
        val nextCal = Calendar.getInstance().apply {
            timeInMillis = selectedDate
            add(Calendar.DAY_OF_MONTH, 1)
        }
        try {
            PrayerDataLoader.hasPrayerData(delegationId, nextCal.get(Calendar.YEAR), nextCal.get(Calendar.MONTH) + 1)
        } catch (_: Exception) { false }
    }
    val canGoBack = remember(delegationId, selectedDate) {
        val prevCal = Calendar.getInstance().apply {
            timeInMillis = selectedDate
            add(Calendar.DAY_OF_MONTH, -1)
        }
        try {
            PrayerDataLoader.hasPrayerData(delegationId, prevCal.get(Calendar.YEAR), prevCal.get(Calendar.MONTH) + 1)
        } catch (_: Exception) { false }
    }

    val prayerNames = mapOf(
        Prayer.FAJR to Strings.PRAYER_FAJR,
        Prayer.DHUHR to Strings.PRAYER_DHUHR,
        Prayer.ASR to Strings.PRAYER_ASR,
        Prayer.MAGHRIB to Strings.PRAYER_MAGHRIB,
        Prayer.ISHA to Strings.PRAYER_ISHA,
        Prayer.JOMOAA to Strings.PRAYER_JOMOAA,
        Prayer.AID_FITR to Strings.PRAYER_AID_FITR,
        Prayer.AID_ADHA to Strings.PRAYER_AID_ADHA
    )

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = Strings.PRAYER_SETTINGS_TITLE,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor
            )
            Spacer(Modifier.height(4.dp))
            Text(text = Strings.PRAYER_SETTINGS_SUBTITLE, fontSize = 12.sp, color = TextMuted)
            Spacer(Modifier.height(12.dp))

            // Date navigation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(GoldLight.copy(alpha = 0.4f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "▸",
                    fontSize = 18.sp,
                    color = if (canGoForward) GreenPrimary else TextMuted.copy(alpha = 0.3f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (canGoForward) Modifier.clickable {
                            selectedDate = Calendar.getInstance().apply {
                                timeInMillis = selectedDate
                                add(Calendar.DAY_OF_MONTH, 1)
                            }.timeInMillis
                        } else Modifier)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = dateText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = GreenPrimaryDark,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clickable { showDatePicker = true }
                    )
                    if (isToday) {
                        Text(
                            text = Strings.DATE_TODAY,
                            fontSize = 11.sp,
                            color = Gold,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = Strings.DATE_GO_BACK_TODAY,
                            fontSize = 11.sp,
                            color = GreenPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(GreenPrimary.copy(alpha = 0.1f))
                                .clickable { selectedDate = Calendar.getInstance().timeInMillis }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = "◂",
                    fontSize = 18.sp,
                    color = if (canGoBack) GreenPrimary else TextMuted.copy(alpha = 0.3f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (canGoBack) Modifier.clickable {
                            selectedDate = Calendar.getInstance().apply {
                                timeInMillis = selectedDate
                                add(Calendar.DAY_OF_MONTH, -1)
                            }.timeInMillis
                        } else Modifier)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // Date picker dialog
            if (showDatePicker) {
                DatePickerDialogCustom(
                    initialCal = selectedCal,
                    onConfirm = { cal ->
                        selectedDate = cal.timeInMillis
                        showDatePicker = false
                    },
                    onDismiss = { showDatePicker = false }
                )
            }

            Spacer(Modifier.height(8.dp))

            if (displayTimes != null) {
                // Header
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Text(Strings.COL_PRAYER, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted, modifier = Modifier.weight(1f))
                    Text(Strings.COL_TIME, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    Text(Strings.COL_DELAY, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    Text(Strings.COL_DURATION, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
                HorizontalDivider(color = Divider, thickness = 1.dp)

                val prayers = listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)
                prayers.forEachIndexed { index, prayer ->
                    val prayerTime = displayTimes.allPrayers().find { it.prayer == prayer }
                    val nextPrayerTime = if (index < prayers.size - 1) {
                        displayTimes.allPrayers().find { it.prayer == prayers[index + 1] }
                    } else null
                    PrayerRow(
                        prayer = prayer,
                        prayerName = prayerNames[prayer] ?: prayer.name,
                        prayerTime = prayerTime,
                        nextPrayerTime = nextPrayerTime,
                        isNextPrayer = isToday && prayer == nextPrayer
                    )
                }

                // Jomoaa row — always shown, with editable time
                HorizontalDivider(color = Divider, thickness = 1.dp)
                PrayerRow(
                    prayer = Prayer.JOMOAA,
                    prayerName = prayerNames[Prayer.JOMOAA] ?: Prayer.JOMOAA.name,
                    prayerTime = if (resolvedJomoaaH >= 0) PrayerTime(Prayer.JOMOAA, resolvedJomoaaH, resolvedJomoaaM) else null,
                    nextPrayerTime = displayTimes.allPrayers().find { it.prayer == Prayer.ASR },
                    isNextPrayer = isToday && Prayer.JOMOAA == nextPrayer,
                    onPrayerTimeClick = { showJomoaaTimePicker = true }
                )

                // Jomoaa time picker
                if (showJomoaaTimePicker) {
                    TimePickerDialog(
                        initialHour = resolvedJomoaaH.coerceAtLeast(0),
                        initialMinute = resolvedJomoaaM.coerceAtLeast(0),
                        onConfirm = { h, m ->
                            jomoaaH = h
                            jomoaaM = m
                            Preferences.setJomoaaTime(h, m)
                            showJomoaaTimePicker = false
                        },
                        onDismiss = { showJomoaaTimePicker = false }
                    )
                }

                // Aid Fitr row — shown 2 days before Eid, hidden after Dhuhr on Eid day
                if (isAidFitr) {
                    HorizontalDivider(color = Divider, thickness = 1.dp)
                    PrayerRow(
                        prayer = Prayer.AID_FITR,
                        prayerName = prayerNames[Prayer.AID_FITR] ?: Prayer.AID_FITR.name,
                        prayerTime = if (resolvedAidFitrH >= 0) PrayerTime(Prayer.AID_FITR, resolvedAidFitrH, resolvedAidFitrM) else null,
                        nextPrayerTime = displayTimes.allPrayers().find { it.prayer == Prayer.DHUHR },
                        isNextPrayer = false,
                        onPrayerTimeClick = { showAidFitrTimePicker = true }
                    )
                    if (showAidFitrTimePicker) {
                        TimePickerDialog(
                            initialHour = resolvedAidFitrH.coerceAtLeast(0),
                            initialMinute = resolvedAidFitrM.coerceAtLeast(0),
                            onConfirm = { h, m ->
                                aidFitrH = h
                                aidFitrM = m
                                Preferences.setAidFitrTime(h, m)
                                showAidFitrTimePicker = false
                            },
                            onDismiss = { showAidFitrTimePicker = false }
                        )
                    }
                }

                // Aid Adha row — shown 2 days before Eid, hidden after Dhuhr on Eid day
                if (isAidAdha) {
                    HorizontalDivider(color = Divider, thickness = 1.dp)
                    PrayerRow(
                        prayer = Prayer.AID_ADHA,
                        prayerName = prayerNames[Prayer.AID_ADHA] ?: Prayer.AID_ADHA.name,
                        prayerTime = if (resolvedAidAdhaH >= 0) PrayerTime(Prayer.AID_ADHA, resolvedAidAdhaH, resolvedAidAdhaM) else null,
                        nextPrayerTime = displayTimes.allPrayers().find { it.prayer == Prayer.DHUHR },
                        isNextPrayer = false,
                        onPrayerTimeClick = { showAidAdhaTimePicker = true }
                    )
                    if (showAidAdhaTimePicker) {
                        TimePickerDialog(
                            initialHour = resolvedAidAdhaH.coerceAtLeast(0),
                            initialMinute = resolvedAidAdhaM.coerceAtLeast(0),
                            onConfirm = { h, m ->
                                aidAdhaH = h
                                aidAdhaM = m
                                Preferences.setAidAdhaTime(h, m)
                                showAidAdhaTimePicker = false
                            },
                            onDismiss = { showAidAdhaTimePicker = false }
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = Strings.NO_PRAYER_DATA,
                    fontSize = 13.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PrayerRow(
    prayer: Prayer,
    prayerName: String,
    prayerTime: PrayerTime?,
    nextPrayerTime: PrayerTime? = null,
    isNextPrayer: Boolean = false,
    onPrayerTimeClick: (() -> Unit)? = null
) {
    // Delay state
    var delayMode by remember { mutableStateOf(Preferences.getDelayMode(prayer)) }
    var delayMinutes by remember { mutableStateOf(Preferences.getDelayMinutes(prayer).toString()) }
    var delayFixedH by remember { mutableStateOf(Preferences.getDelayFixedHour(prayer).let { if (it >= 0) it else prayerTime?.hour ?: 0 }) }
    var delayFixedM by remember { mutableStateOf(Preferences.getDelayFixedMinute(prayer).let { if (it >= 0) it else prayerTime?.minute ?: 0 }) }
    var showDelayPicker by remember { mutableStateOf(false) }

    // Duration/end state
    var silenceMode by remember { mutableStateOf(Preferences.getSilenceMode(prayer)) }
    var afterMinutes by remember { mutableStateOf(Preferences.getAfterMinutes(prayer).toString()) }
    var fixedH by remember { mutableStateOf(Preferences.getFixedTimeHour(prayer).let { if (it >= 0) it else prayerTime?.hour ?: 13 }) }
    var fixedM by remember { mutableStateOf(Preferences.getFixedTimeMinute(prayer).let { if (it >= 0) it else prayerTime?.minute ?: 0 }) }
    var showDurationPicker by remember { mutableStateOf(false) }

    // Delay time picker (used in FIXED_TIME delay mode)
    if (showDelayPicker) {
        TimePickerDialog(
            initialHour = if (delayMode == DelayMode.FIXED_TIME) delayFixedH else (delayMinutes.toIntOrNull() ?: 0) / 60,
            initialMinute = if (delayMode == DelayMode.FIXED_TIME) delayFixedM else (delayMinutes.toIntOrNull() ?: 0) % 60,
            onConfirm = { h, m ->
                if (delayMode == DelayMode.FIXED_TIME) {
                    delayFixedH = h
                    delayFixedM = m
                    Preferences.setDelayFixedTime(prayer, h, m)
                } else {
                    val totalMin = h * 60 + m
                    delayMinutes = totalMin.toString()
                    Preferences.setDelayMinutes(prayer, totalMin)
                }
                showDelayPicker = false
            },
            onDismiss = { showDelayPicker = false }
        )
    }

    // Duration/end time picker
    if (showDurationPicker) {
        TimePickerDialog(
            initialHour = if (silenceMode == SilenceMode.FIXED_TIME) fixedH else (afterMinutes.toIntOrNull() ?: 0) / 60,
            initialMinute = if (silenceMode == SilenceMode.FIXED_TIME) fixedM else (afterMinutes.toIntOrNull() ?: 0) % 60,
            onConfirm = { h, m ->
                if (silenceMode == SilenceMode.FIXED_TIME) {
                    fixedH = h
                    fixedM = m
                    Preferences.setFixedTime(prayer, h, m)
                } else {
                    val totalMin = h * 60 + m
                    afterMinutes = totalMin.toString()
                    Preferences.setAfterMinutes(prayer, totalMin)
                }
                showDurationPicker = false
            },
            onDismiss = { showDurationPicker = false }
        )
    }

    // Overlap detection
    val overlapsNextPrayer = remember(prayerTime, nextPrayerTime, silenceMode, afterMinutes, fixedH, fixedM, delayMode, delayMinutes, delayFixedH, delayFixedM) {
        if (prayerTime == null || nextPrayerTime == null) false
        else {
            val config = PrayerSilenceConfig(
                mode = silenceMode,
                afterMinutes = afterMinutes.toIntOrNull() ?: 0,
                fixedHour = fixedH,
                fixedMinute = fixedM,
                delayMode = delayMode,
                delayMinutes = delayMinutes.toIntOrNull() ?: 0,
                delayFixedHour = delayFixedH,
                delayFixedMinute = delayFixedM
            )
            SilenceAlarmComputer.overlapsNextPrayer(prayerTime, nextPrayerTime, config)
        }
    }

    Column {
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(
                if (isNextPrayer) Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(NextPrayerBg)
                else Modifier
            )
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Prayer name
        Text(
            text = prayerName,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (isNextPrayer) GreenPrimary else PrayerNameColor,
            modifier = Modifier.weight(1f)
        )

        // Prayer time
        Text(
            text = if (prayerTime != null) String.format(Locale.US, "%02d:%02d", prayerTime.hour, prayerTime.minute) else "--:--",
            fontSize = 14.sp,
            color = TextDark,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
                .then(
                    if (onPrayerTimeClick != null) Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(GoldLight.copy(alpha = 0.3f))
                        .clickable { onPrayerTimeClick() }
                        .padding(vertical = 4.dp)
                    else Modifier
                )
        )

        // Delay control: input + "د"/"من" toggle
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (delayMode == DelayMode.MINUTES) {
                NumberInput(
                    value = delayMinutes,
                    onValueChange = {
                        delayMinutes = it
                        Preferences.setDelayMinutes(prayer, it.toIntOrNull() ?: 0)
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                // Fixed time display
                Text(
                    text = String.format(Locale.US, "%02d:%02d", delayFixedH, delayFixedM),
                    fontSize = 14.sp,
                    color = TextDark,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(GoldLight.copy(alpha = 0.3f))
                        .clickable { showDelayPicker = true }
                        .padding(4.dp)
                )
            }
            Text(
                text = if (delayMode == DelayMode.MINUTES) Strings.LABEL_DELAY_MINUTES else Strings.LABEL_DELAY_AT,
                fontSize = 12.sp,
                color = Gold,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clickable {
                        val newMode = if (delayMode == DelayMode.MINUTES) DelayMode.FIXED_TIME else DelayMode.MINUTES
                        delayMode = newMode
                        Preferences.setDelayMode(prayer, newMode)
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        // Duration/end control: input + "د"/"حتى" toggle
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (silenceMode == SilenceMode.DURATION) {
                NumberInput(
                    value = afterMinutes,
                    onValueChange = {
                        afterMinutes = it
                        Preferences.setAfterMinutes(prayer, it.toIntOrNull() ?: 0)
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                // Fixed end time display
                Text(
                    text = String.format(Locale.US, "%02d:%02d", fixedH, fixedM),
                    fontSize = 14.sp,
                    color = TextDark,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(GoldLight.copy(alpha = 0.3f))
                        .clickable { showDurationPicker = true }
                        .padding(4.dp)
                )
            }
            Text(
                text = if (silenceMode == SilenceMode.DURATION) Strings.LABEL_DURATION else Strings.LABEL_FIXED_TIME,
                fontSize = 12.sp,
                color = Gold,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clickable {
                        val newMode = if (silenceMode == SilenceMode.DURATION) SilenceMode.FIXED_TIME else SilenceMode.DURATION
                        silenceMode = newMode
                        Preferences.setSilenceMode(prayer, newMode)
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
    if (overlapsNextPrayer) {
        Text(
            text = Strings.WARNING_OVERLAPS_NEXT_PRAYER,
            fontSize = 11.sp,
            color = SilenceRed,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )
    }
    }
    HorizontalDivider(color = Divider, thickness = 0.5.dp)
}

@Composable
private fun NumberInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BasicTextField(
            value = value,
            onValueChange = { new ->
                val filtered = new.filter { it.isDigit() }.take(3)
                onValueChange(filtered)
            },
            modifier = modifier
                .heightIn(min = 36.dp)
                .padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(GoldLight.copy(alpha = 0.3f))
                .padding(4.dp),
            textStyle = TextStyle(
                fontSize = 14.sp,
                color = TextDark,
                textAlign = TextAlign.Center
            ),
            singleLine = true,
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun DatePickerDialogCustom(
    initialCal: Calendar,
    onConfirm: (Calendar) -> Unit,
    onDismiss: () -> Unit
) {
    val arabicMonths = arrayOf(
        "جانفي", "فيفري", "مارس", "أفريل", "ماي", "جوان",
        "جويلية", "أوت", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
    )

    var year by remember { mutableIntStateOf(initialCal.get(Calendar.YEAR)) }
    var month by remember { mutableIntStateOf(initialCal.get(Calendar.MONTH)) }
    var day by remember { mutableIntStateOf(initialCal.get(Calendar.DAY_OF_MONTH)) }

    val daysInMonth = remember(year, month) {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    // Clamp day if month changed
    val clampedDay = day.coerceAtMost(daysInMonth)
    if (clampedDay != day) day = clampedDay

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = Strings.TIME_PICKER_TITLE.replace("الوقت", "التاريخ"),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = GreenPrimaryDark
                )
                Spacer(Modifier.height(16.dp))

                // Date display
                Text(
                    text = formatArabicDate(Calendar.getInstance().apply {
                        set(Calendar.YEAR, year)
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, day)
                    }),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = GreenPrimary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Day / Month / Year spinners (RTL: day on right, year on left)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Day spinner
                        SpinnerColumn(
                            value = day,
                            range = 1..daysInMonth,
                            displayText = { it.toString() },
                            onValueChange = { day = it },
                            modifier = Modifier.width(60.dp)
                        )
                        // Month spinner
                        SpinnerColumn(
                            value = month,
                            range = 0..11,
                            displayText = { arabicMonths[it] },
                            onValueChange = { month = it },
                            modifier = Modifier.width(100.dp)
                        )
                        // Year spinner
                        SpinnerColumn(
                            value = year,
                            range = (year - 2)..(year + 2),
                            displayText = { it.toString() },
                            onValueChange = { year = it },
                            modifier = Modifier.width(70.dp)
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(Strings.CANCEL, color = TextMuted)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        val result = Calendar.getInstance().apply {
                            set(Calendar.YEAR, year)
                            set(Calendar.MONTH, month)
                            set(Calendar.DAY_OF_MONTH, day)
                        }
                        onConfirm(result)
                    }) {
                        Text(Strings.CONFIRM, color = GreenPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpinnerColumn(
    value: Int,
    range: IntRange,
    displayText: (Int) -> String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(
            text = "▲",
            fontSize = 16.sp,
            color = GreenPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { if (value < range.last) onValueChange(value + 1) }
                .padding(4.dp)
        )
        Text(
            text = displayText(value),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TextDark,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(GoldLight.copy(alpha = 0.3f))
                .padding(vertical = 8.dp, horizontal = 4.dp)
        )
        Text(
            text = "▼",
            fontSize = 16.sp,
            color = GreenPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { if (value > range.first) onValueChange(value - 1) }
                .padding(4.dp)
        )
    }
}

@Composable
private fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var hour by remember { mutableIntStateOf(initialHour.coerceIn(0, 23)) }
    var minute by remember { mutableIntStateOf(initialMinute.coerceIn(0, 59)) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.width(260.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = Strings.TIME_PICKER_TITLE,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrayerNameColor
                )
                Spacer(Modifier.height(20.dp))

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Hour spinner
                    SpinnerColumn(
                        value = hour,
                        range = 0..23,
                        onValueChange = { hour = it }
                    )
                    Text(
                        text = ":",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDark,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    // Minute spinner
                    SpinnerColumn(
                        value = minute,
                        range = 0..59,
                        onValueChange = { minute = it }
                    )
                }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = String.format(Locale.US, "%02d:%02d", hour, minute),
                    fontSize = 14.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(Strings.CANCEL, fontSize = 13.sp, color = TextMuted)
                    }
                    Button(
                        onClick = { onConfirm(hour, minute) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)
                    ) {
                        Text(Strings.CONFIRM, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpinnerColumn(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Up button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(GoldLight.copy(alpha = 0.3f))
                .clickable {
                    onValueChange(if (value >= range.last) range.first else value + 1)
                }
        ) {
            Text("▲", fontSize = 16.sp, color = GreenPrimary)
        }

        Spacer(Modifier.height(4.dp))

        // Value display
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp, 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(GreenPrimary.copy(alpha = 0.08f))
        ) {
            Text(
                text = String.format(Locale.US, "%02d", value),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = GreenPrimaryDark,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(4.dp))

        // Down button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(GoldLight.copy(alpha = 0.3f))
                .clickable {
                    onValueChange(if (value <= range.first) range.last else value - 1)
                }
        ) {
            Text("▼", fontSize = 16.sp, color = GreenPrimary)
        }
    }
}

@Composable
private fun AutoSilenceCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Strings.AUTO_SILENCE,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = GreenPrimary
                )
            )
        }
    }
}

@Composable
private fun RamadanBadge() {
    Text(
        text = Strings.RAMADAN_ACTIVE,
        fontSize = 14.sp,
        color = Gold,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(RamadanBg, RoundedCornerShape(8.dp))
            .padding(12.dp)
    )
}

@Composable
private fun ManualSilenceCard(
    isSilent: Boolean,
    manualUsesDuration: Boolean,
    manualDurationHours: String,
    manualDurationMinutes: String,
    manualSilenceActive: Boolean,
    manualSilenceEndsAtMillis: Long,
    onUseDurationChange: (Boolean) -> Unit,
    onDurationHoursChange: (String) -> Unit,
    onDurationMinutesChange: (String) -> Unit,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSilent) SilenceRed else GreenPrimary,
        label = "buttonColor"
    )

    val totalMinutes = resolveManualTotalMinutes(
        manualDurationHours, manualDurationMinutes,
        Preferences.getManualSilenceDurationMinutes()
    )
    val durationText = formatDurationText(totalMinutes)

    val statusText = when {
        manualSilenceActive && manualSilenceEndsAtMillis > 0L ->
            Strings.manualSilenceActiveUntilTime(formatTimeOfDay(manualSilenceEndsAtMillis))
        manualSilenceActive -> Strings.MANUAL_SILENCE_ACTIVE_UNTIL_MANUAL
        manualUsesDuration -> Strings.manualSilenceSelectedDuration(durationText)
        else -> Strings.MANUAL_SILENCE_SUBTITLE
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(Strings.MANUAL_SILENCE_TITLE, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark)
            Spacer(Modifier.height(4.dp))
            Text(statusText, fontSize = 12.sp, color = TextMuted)
            Spacer(Modifier.height(12.dp))

            // Mode chips
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(
                    text = Strings.MANUAL_SILENCE_MODE_UNTIL,
                    selected = !manualUsesDuration,
                    onClick = { onUseDurationChange(false) },
                    modifier = Modifier.weight(1f)
                )
                ModeChip(
                    text = Strings.MANUAL_SILENCE_MODE_DURATION,
                    selected = manualUsesDuration,
                    onClick = { onUseDurationChange(true) },
                    modifier = Modifier.weight(1f)
                )
            }

            AnimatedVisibility(visible = manualUsesDuration) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(Strings.MANUAL_SILENCE_DURATION_LABEL, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PrayerNameColor, modifier = Modifier.width(48.dp))
                    NumberInput(value = manualDurationHours, onValueChange = onDurationHoursChange, modifier = Modifier.width(56.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(Strings.MANUAL_SILENCE_HOURS_LABEL, fontSize = 13.sp, color = TextMuted)
                    Spacer(Modifier.width(8.dp))
                    NumberInput(value = manualDurationMinutes, onValueChange = onDurationMinutesChange, modifier = Modifier.width(56.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(Strings.MANUAL_SILENCE_MINUTES_LABEL, fontSize = 13.sp, color = TextMuted)
                }
            }

            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = bgColor)
            ) {
                Text(
                    text = when {
                        isSilent -> Strings.BTN_UNSILENCE
                        manualUsesDuration -> Strings.btnSilenceForDuration(durationText)
                        else -> Strings.BTN_SILENCE
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) GreenPrimary.copy(alpha = 0.14f) else GoldLight.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (selected) GreenPrimaryDark else TextDark,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

// Helper functions

private fun resolveManualTotalMinutes(hours: String, minutes: String, fallback: Int): Int {
    val h = hours.toIntOrNull() ?: 0
    val m = minutes.toIntOrNull() ?: 0
    val total = h * 60 + m
    return if (total > 0) total else fallback.coerceAtLeast(1)
}

private fun formatDurationText(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> "${h}\u0633 ${m}\u062f"
        h > 0 -> "${h}\u0633"
        else -> "${m}\u062f"
    }
}

private fun formatTimeOfDay(targetTimeInMillis: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = targetTimeInMillis }
    return String.format(
        Locale.forLanguageTag("ar-TN"),
        "%02d:%02d",
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE)
    )
}

private fun formatArabicDate(cal: Calendar): String {
    val dayNames = arrayOf("الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")
    val monthNames = arrayOf(
        "جانفي", "فيفري", "مارس", "أفريل", "ماي", "جوان",
        "جويلية", "أوت", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
    )
    val dayOfWeek = dayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
    val day = cal.get(Calendar.DAY_OF_MONTH)
    val month = monthNames[cal.get(Calendar.MONTH)]
    val year = cal.get(Calendar.YEAR)
    return "$dayOfWeek $day $month $year"
}
