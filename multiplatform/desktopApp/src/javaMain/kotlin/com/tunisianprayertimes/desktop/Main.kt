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
import java.text.SimpleDateFormat
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
    // Initialize data loaders with the data directory
    val dataDir = resolveDataDir()
    PrayerDataLoader.init(dataDir)
    GouvernoratLoader.init(dataDir)

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

private fun startBackgroundSilenceScheduler(): ScheduledFuture<*>? {
    if (!Preferences.isEnabled()) return null

    val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "SilenceScheduler").apply { isDaemon = true }
    }

    return executor.scheduleAtFixedRate({
        try {
            if (!Preferences.isEnabled()) return@scheduleAtFixedRate

            val delegationId = Preferences.getDelegationId()
            val now = Calendar.getInstance()
            val year = now.get(Calendar.YEAR)
            val month = now.get(Calendar.MONTH) + 1
            val day = now.get(Calendar.DAY_OF_MONTH)
            val isFriday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY

            val todayTimes = PrayerDataLoader.loadDayPrayerTimes(delegationId, year, month, day)
                ?: return@scheduleAtFixedRate

            val configs = mapOf(
                Prayer.FAJR to Preferences.getConfig(Prayer.FAJR),
                Prayer.DHUHR to Preferences.getConfig(Prayer.DHUHR),
                Prayer.ASR to Preferences.getConfig(Prayer.ASR),
                Prayer.MAGHRIB to Preferences.getConfig(Prayer.MAGHRIB),
                Prayer.ISHA to Preferences.getConfig(Prayer.ISHA),
                Prayer.JOMOAA to Preferences.getConfig(Prayer.JOMOAA)
            )

            val result = SilenceAlarmComputer.compute(
                now = now,
                todayTimes = todayTimes,
                configs = configs,
                isFriday = isFriday,
                jomoaaHour = Preferences.getJomoaaTimeHour(),
                jomoaaMinute = Preferences.getJomoaaTimeMinute()
            )

            if (result.currentlyInSilenceWindow && !SilenceController.isSilent()) {
                SilenceController.enableSilence()
                showNotification(Strings.TOAST_SILENT_ENABLED)
            } else if (!result.currentlyInSilenceWindow && SilenceController.isSilent()
                && !Preferences.isManualSilenceActive()) {
                SilenceController.disableSilence()
                showNotification(Strings.TOAST_NORMAL_RESTORED)
            }
        } catch (e: Exception) {
            System.err.println("Background silence scheduler error: ${e.message}")
        }
    }, 0, 30, TimeUnit.SECONDS)
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Text(
            text = if (isSilent) Strings.STATUS_SILENT else Strings.STATUS_NORMAL,
            fontSize = 17.sp,
            color = TextDark,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
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

    val dateFormat = remember { SimpleDateFormat("EEEE d MMMM yyyy", Locale("ar", "TN")) }
    val dateText = remember(selectedDate) { dateFormat.format(selectedDate) }

    val prayerNames = mapOf(
        Prayer.FAJR to Strings.PRAYER_FAJR,
        Prayer.DHUHR to Strings.PRAYER_DHUHR,
        Prayer.ASR to Strings.PRAYER_ASR,
        Prayer.MAGHRIB to Strings.PRAYER_MAGHRIB,
        Prayer.ISHA to Strings.PRAYER_ISHA,
        Prayer.JOMOAA to Strings.PRAYER_JOMOAA
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
                    color = GreenPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            selectedDate = Calendar.getInstance().apply {
                                timeInMillis = selectedDate
                                add(Calendar.DAY_OF_MONTH, 1)
                            }.timeInMillis
                        }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
                Text(
                    text = dateText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = GreenPrimaryDark,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "◂",
                    fontSize = 18.sp,
                    color = GreenPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            selectedDate = Calendar.getInstance().apply {
                                timeInMillis = selectedDate
                                add(Calendar.DAY_OF_MONTH, -1)
                            }.timeInMillis
                        }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
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
                prayers.forEach { prayer ->
                    val prayerTime = displayTimes.allPrayers().find { it.prayer == prayer }
                    PrayerRow(
                        prayer = prayer,
                        prayerName = prayerNames[prayer] ?: prayer.name,
                        prayerTime = prayerTime
                    )
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
    prayerTime: PrayerTime?
) {
    var delayMinutes by remember { mutableStateOf(Preferences.getDelayMinutes(prayer).toString()) }
    var afterMinutes by remember { mutableStateOf(Preferences.getAfterMinutes(prayer).toString()) }
    var showDelayPicker by remember { mutableStateOf(false) }
    var showDurationPicker by remember { mutableStateOf(false) }

    // Delay time picker
    if (showDelayPicker) {
        val currentDelay = delayMinutes.toIntOrNull() ?: 0
        TimePickerDialog(
            initialHour = currentDelay / 60,
            initialMinute = currentDelay % 60,
            onConfirm = { h, m ->
                val totalMin = h * 60 + m
                delayMinutes = totalMin.toString()
                Preferences.setDelayMinutes(prayer, totalMin)
                showDelayPicker = false
            },
            onDismiss = { showDelayPicker = false }
        )
    }

    // Duration time picker
    if (showDurationPicker) {
        val currentDuration = afterMinutes.toIntOrNull() ?: 0
        TimePickerDialog(
            initialHour = currentDuration / 60,
            initialMinute = currentDuration % 60,
            onConfirm = { h, m ->
                val totalMin = h * 60 + m
                afterMinutes = totalMin.toString()
                Preferences.setAfterMinutes(prayer, totalMin)
                showDurationPicker = false
            },
            onDismiss = { showDurationPicker = false }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = prayerName,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = PrayerNameColor,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (prayerTime != null) String.format(Locale.US, "%02d:%02d", prayerTime.hour, prayerTime.minute) else "--:--",
            fontSize = 14.sp,
            color = TextDark,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        // Delay input (click to open time picker)
        Box(modifier = Modifier.weight(1f)) {
            NumberInput(
                value = delayMinutes,
                onValueChange = {
                    delayMinutes = it
                    Preferences.setDelayMinutes(prayer, it.toIntOrNull() ?: 0)
                },
                modifier = Modifier.fillMaxWidth()
                    .clickable { showDelayPicker = true }
            )
        }
        // Duration input (click to open time picker)
        Box(modifier = Modifier.weight(1f)) {
            NumberInput(
                value = afterMinutes,
                onValueChange = {
                    afterMinutes = it
                    Preferences.setAfterMinutes(prayer, it.toIntOrNull() ?: 0)
                },
                modifier = Modifier.fillMaxWidth()
                    .clickable { showDurationPicker = true }
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
