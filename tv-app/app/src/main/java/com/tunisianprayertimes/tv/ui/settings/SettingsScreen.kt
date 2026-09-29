package com.tunisianprayertimes.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.mosque.DisplayOptions
import com.tunisianprayertimes.mosque.MosqueProfile
import com.tunisianprayertimes.Delegation
import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.tv.data.IqamahConfig
import com.tunisianprayertimes.tv.ui.setup.iqamahRows
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.setup.FocusableButton
import com.tunisianprayertimes.tv.ui.setup.FocusableListItem
import com.tunisianprayertimes.tv.ui.theme.Gold
import com.tunisianprayertimes.tv.ui.theme.TvThemeConfig
import com.tunisianprayertimes.tv.ui.theme.ThemeRegistry
import java.time.LocalDate

/**
 * Settings screen accessible via Menu button on remote.
 */
@Composable
fun SettingsScreen(
    mosqueName: String,
    delegationName: String,
    /** Every row of the iqamah table: the daily prayers, Jumu'a and the two Eids. */
    iqamahConfigs: Map<Prayer, IqamahConfig>,
    gouvernorats: List<Gouvernorat>,
    announcementsEnabled: Boolean,
    customBgEnabled: Boolean,
    announcementIntervalSec: Int,
    backgroundCount: Int,
    announcementCount: Int,
    currentThemeId: String,
    /** Today in Tunisia, for the Ramadan and Eid dates section. */
    today: LocalDate,
    onMosqueNameChanged: (String) -> Unit,
    onIqamahChanged: (Prayer, IqamahConfig) -> Unit,
    onDelegationChanged: (Int, Int, String) -> Unit,
    onAnnouncementsEnabledChanged: (Boolean) -> Unit,
    onCustomBgEnabledChanged: (Boolean) -> Unit,
    onAnnouncementIntervalChanged: (Int) -> Unit,
    /** Removes the background and announcement images copied from USB keys. */
    onDeleteImages: () -> Unit,
    weatherEnabled: Boolean = true,
    onWeatherChanged: (Boolean) -> Unit = {},
    announcementsEveryMinutes: Int = 15,
    onAnnouncementsEveryChanged: (Int) -> Unit = {},
    onThemeChanged: (String) -> Unit,
    /** The kiosk health page (auto-start, power settings, crashes), shown as a section. */
    kioskPage: @Composable (onBack: () -> Unit) -> Unit,
    /** Managing the screen from a phone on the local network. */
    phonePage: @Composable (onBack: () -> Unit) -> Unit,
    /** Leaves the app for the box's own settings; the watchdog then stays away for a while. */
    onExitToAndroid: () -> Unit,
    onBack: () -> Unit,
    /** Right after onboarding: show the installer whether this box can keep the app on screen. */
    openKioskPage: Boolean = false,
    /** Today's prayer times for a delegation, to compare before a location change. */
    previewTimes: (Int) -> DayPrayerTimes? = { null },
    currentDelegationId: Int = -1,
    /** The settings before the last USB import can be put back. */
    canUndoImport: Boolean = false,
    onUndoImport: () -> Unit = {},
    onResetAll: () -> Unit = {},
    aboutLines: List<String> = emptyList(),
    /** The mosque replaced or extended the texts from a USB file: one action returns to the bundled ones. */
    customTexts: Boolean = false,
    onBundledTexts: () -> Unit = {},
) {
    var currentSection by remember { mutableStateOf(if (openKioskPage) SettingsSection.Kiosk else SettingsSection.Main) }
    // Back goes up one level, then to the display; Android 16 no longer sends Back as a key.
    BackHandler {
        if (currentSection != SettingsSection.Main) currentSection = SettingsSection.Main else onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp)
    ) {
        when (currentSection) {
            SettingsSection.Main -> MainSettingsMenu(
                onSectionSelected = { currentSection = it },
                onExitToAndroid = onExitToAndroid,
                canUndoImport = canUndoImport,
                onUndoImport = onUndoImport,
                customTexts = customTexts,
                onBundledTexts = onBundledTexts,
                onBack = onBack
            )
            SettingsSection.Reset -> ConfirmSection(
                text = TvStrings.RESET_CONFIRM,
                confirm = TvStrings.RESET_DO,
                onConfirm = onResetAll,
                onBack = { currentSection = SettingsSection.Main },
            )
            SettingsSection.BundledTexts -> ConfirmSection(
                text = TvStrings.BUNDLED_TEXTS_CONFIRM,
                confirm = TvStrings.BUNDLED_TEXTS_DO,
                onConfirm = onBundledTexts,
                onBack = { currentSection = SettingsSection.Main },
            )
            SettingsSection.About -> AboutSection(aboutLines) { currentSection = SettingsSection.Main }
            SettingsSection.Kiosk -> kioskPage { currentSection = SettingsSection.Main }
            SettingsSection.Phone -> phonePage { currentSection = SettingsSection.Main }
            SettingsSection.MosqueName -> MosqueNameSection(
                mosqueName = mosqueName,
                onChanged = onMosqueNameChanged,
                onBack = { currentSection = SettingsSection.Main }
            )
            SettingsSection.Iqamah -> IqamahSection(
                configs = iqamahConfigs,
                onIqamahChanged = onIqamahChanged,
                onBack = { currentSection = SettingsSection.Main }
            )
            SettingsSection.IslamicDates -> IslamicDatesSection(
                today = today,
                onBack = { currentSection = SettingsSection.Main }
            )
            SettingsSection.Location -> LocationSection(
                previewTimes = previewTimes,
                currentDelegationId = currentDelegationId,
                gouvernorats = gouvernorats,
                currentDelegation = delegationName,
                onDelegationChanged = onDelegationChanged,
                onBack = { currentSection = SettingsSection.Main }
            )
            SettingsSection.Media -> MediaSection(
                announcementsEnabled = announcementsEnabled,
                customBgEnabled = customBgEnabled,
                announcementIntervalSec = announcementIntervalSec,
                backgroundCount = backgroundCount,
                announcementCount = announcementCount,
                onAnnouncementsEnabledChanged = onAnnouncementsEnabledChanged,
                onCustomBgEnabledChanged = onCustomBgEnabledChanged,
                onAnnouncementIntervalChanged = onAnnouncementIntervalChanged,
                onDeleteImages = onDeleteImages,
                weatherEnabled = weatherEnabled,
                onWeatherChanged = onWeatherChanged,
                announcementsEveryMinutes = announcementsEveryMinutes,
                onAnnouncementsEveryChanged = onAnnouncementsEveryChanged,
                onBack = { currentSection = SettingsSection.Main }
            )
            SettingsSection.Theme -> ThemePickerSection(
                currentThemeId = currentThemeId,
                onThemeChanged = onThemeChanged,
                onBack = { currentSection = SettingsSection.Main }
            )
        }
    }
}

private enum class SettingsSection { Main, MosqueName, Iqamah, IslamicDates, Location, Media, Theme, Kiosk, Phone, Reset, BundledTexts, About }

@Composable
private fun MainSettingsMenu(
    onSectionSelected: (SettingsSection) -> Unit,
    onExitToAndroid: () -> Unit,
    canUndoImport: Boolean,
    onUndoImport: () -> Unit,
    customTexts: Boolean,
    onBundledTexts: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = TvStrings.SETTINGS_TITLE,
            style = MaterialTheme.typography.headlineLarge,
            color = Gold,
            fontSize = 36.sp,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(0.4f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                FocusableListItem(
                    text = TvStrings.SETTINGS_MOSQUE_NAME,
                    onClick = { onSectionSelected(SettingsSection.MosqueName) },
                    modifier = Modifier.initialFocus(),
                )
            }
            item {
                FocusableListItem(
                    text = TvStrings.SETTINGS_IQAMAH,
                    onClick = { onSectionSelected(SettingsSection.Iqamah) }
                )
            }
            item {
                FocusableListItem(
                    text = TvStrings.SETTINGS_ISLAMIC_DATES,
                    onClick = { onSectionSelected(SettingsSection.IslamicDates) }
                )
            }
            item {
                FocusableListItem(
                    text = TvStrings.SETTINGS_LOCATION,
                    onClick = { onSectionSelected(SettingsSection.Location) }
                )
            }
            item {
                FocusableListItem(
                    text = TvStrings.SETTINGS_ANNOUNCEMENTS,
                    onClick = { onSectionSelected(SettingsSection.Media) }
                )
            }
            item {
                FocusableListItem(
                    text = TvStrings.SETTINGS_THEME,
                    onClick = { onSectionSelected(SettingsSection.Theme) }
                )
            }
            item {
                FocusableListItem(
                    text = TvStrings.SETTINGS_KIOSK,
                    onClick = { onSectionSelected(SettingsSection.Kiosk) }
                )
            }
            item { FocusableListItem(text = TvStrings.SETTINGS_PHONE, onClick = { onSectionSelected(SettingsSection.Phone) }) }
            if (canUndoImport) {
                item { FocusableListItem(text = TvStrings.UNDO_IMPORT, onClick = onUndoImport) }
            }
            if (customTexts) {
                item { FocusableListItem(text = TvStrings.BUNDLED_TEXTS, onClick = { onSectionSelected(SettingsSection.BundledTexts) }) }
            }
            item { FocusableListItem(text = TvStrings.ABOUT, onClick = { onSectionSelected(SettingsSection.About) }) }
            item { FocusableListItem(text = TvStrings.RESET_ALL, onClick = { onSectionSelected(SettingsSection.Reset) }) }
            item {
                FocusableListItem(
                    text = TvStrings.EXIT_TO_ANDROID,
                    onClick = onExitToAndroid
                )
            }
            item {
                FocusableListItem(
                    text = TvStrings.CANCEL,
                    onClick = onBack
                )
            }
        }
    }
}

@Composable
private fun MosqueNameSection(
    mosqueName: String,
    onChanged: (String) -> Unit,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf(mosqueName) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = TvStrings.SETTINGS_MOSQUE_NAME,
            style = MaterialTheme.typography.headlineMedium,
            color = Gold,
            fontSize = 28.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text(TvStrings.SETUP_MOSQUE_HINT) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Gold,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                cursorColor = Gold
            ),
            textStyle = LocalTextStyle.current.copy(fontSize = 24.sp, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(0.5f),
            singleLine = true
        )

        Spacer(Modifier.height(32.dp))

        Row(Modifier.fillMaxWidth(0.5f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.weight(1f)) {
                FocusableListItem(text = TvStrings.SAVE, onClick = {
                    onChanged(name.trim().take(MosqueProfile.MAX_NAME_LENGTH))
                    onBack()
                }, modifier = Modifier.initialFocus())
            }
            Box(Modifier.weight(1f)) { FocusableListItem(text = TvStrings.CANCEL, onClick = onBack) }
        }
    }
}

@Composable
private fun IqamahSection(
    configs: Map<Prayer, IqamahConfig>,
    onIqamahChanged: (Prayer, IqamahConfig) -> Unit,
    onBack: () -> Unit
) {

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = TvStrings.SETTINGS_IQAMAH,
            style = MaterialTheme.typography.headlineMedium,
            color = Gold,
            fontSize = 28.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            iqamahRows(configs, onIqamahChanged)
        }

        Spacer(Modifier.height(16.dp))
        FocusableListItem(text = TvStrings.SAVE, onClick = onBack)
    }
}

@Composable
private fun LocationSection(
    previewTimes: (Int) -> DayPrayerTimes?,
    currentDelegationId: Int,
    gouvernorats: List<Gouvernorat>,
    currentDelegation: String,
    onDelegationChanged: (Int, Int, String) -> Unit,
    onBack: () -> Unit
) {
    var selectedGouvernorat by remember { mutableStateOf<Int?>(null) }
    // Nothing changes until the admin has seen today's times at the new place and confirmed.
    var pending by remember { mutableStateOf<Pair<Gouvernorat, Delegation>?>(null) }
    pending?.let { (gouv, delegation) ->
        val before = remember(currentDelegationId) { previewTimes(currentDelegationId) }
        val after = remember(delegation.id) { previewTimes(delegation.id) }
        fun hm(time: com.tunisianprayertimes.PrayerTime?) =
            time?.let { String.format(java.util.Locale.ROOT, "%02d:%02d", it.hour, it.minute) } ?: "—"
        ConfirmSection(
            text = listOf(
                "${TvStrings.LOCATION_CONFIRM} ${delegation.nomAr} (${gouv.nomAr})",
                TvStrings.TODAY_BEFORE_AFTER,
                "${TvStrings.FAJR}: ${hm(before?.fajr)} ← ${hm(after?.fajr)}",
                "${TvStrings.MAGHRIB}: ${hm(before?.maghrib)} ← ${hm(after?.maghrib)}",
            ).joinToString("\n"),
            confirm = TvStrings.CONFIRM,
            onConfirm = {
                onDelegationChanged(gouv.id, delegation.id, delegation.nomAr)
                onBack()
            },
            onBack = { pending = null },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "${TvStrings.SETTINGS_LOCATION} — $currentDelegation",
            style = MaterialTheme.typography.headlineMedium,
            color = Gold,
            fontSize = 28.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        if (selectedGouvernorat == null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(gouvernorats) { g ->
                    FocusableListItem(
                        text = g.nomAr,
                        onClick = { selectedGouvernorat = g.id },
                        modifier = Modifier.initialFocus(g == gouvernorats.first()),
                    )
                }
            }
        } else {
            val gouv = gouvernorats.find { it.id == selectedGouvernorat }
            if (gouv != null) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(gouv.delegations) { d ->
                        FocusableListItem(
                            text = d.nomAr,
                            modifier = Modifier.initialFocus(d == gouv.delegations.first()),
                            onClick = { pending = gouv to d }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        FocusableListItem(text = TvStrings.CANCEL, onClick = {
            if (selectedGouvernorat != null) selectedGouvernorat = null
            else onBack()
        })
    }
}

/** A question with one confirming button and Cancel; nothing happens until the admin confirms. */
@Composable
private fun ConfirmSection(text: String, confirm: String, onConfirm: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onBackground, fontSize = 22.sp, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth(0.6f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f)) { FocusableListItem(text = confirm, onClick = onConfirm) }
            Box(Modifier.weight(1f)) { FocusableListItem(text = TvStrings.CANCEL, onClick = onBack, modifier = Modifier.initialFocus()) }
        }
    }
}

/** Version, data and device, for a phone call with whoever maintains the screens. */
@Composable
private fun AboutSection(lines: List<String>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(TvStrings.ABOUT, color = Gold, fontSize = 28.sp)
        lines.forEach { Text(it, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp) }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(0.3f)) { FocusableListItem(text = TvStrings.CANCEL, onClick = onBack, modifier = Modifier.initialFocus()) }
    }
}

@Composable
private fun MediaSection(
    announcementsEnabled: Boolean,
    customBgEnabled: Boolean,
    announcementIntervalSec: Int,
    backgroundCount: Int,
    announcementCount: Int,
    onAnnouncementsEnabledChanged: (Boolean) -> Unit,
    onCustomBgEnabledChanged: (Boolean) -> Unit,
    onAnnouncementIntervalChanged: (Int) -> Unit,
    onDeleteImages: () -> Unit,
    weatherEnabled: Boolean,
    onWeatherChanged: (Boolean) -> Unit,
    announcementsEveryMinutes: Int,
    onAnnouncementsEveryChanged: (Int) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = TvStrings.SETTINGS_ANNOUNCEMENTS,
            style = MaterialTheme.typography.headlineMedium,
            color = Gold,
            fontSize = 28.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Announcements toggle
            item {
                ToggleRow(
                    label = TvStrings.ANNOUNCEMENTS_ENABLED,
                    enabled = announcementsEnabled,
                    info = "$announcementCount ${TvStrings.MEDIA_FILES_COUNT}",
                    onToggle = { onAnnouncementsEnabledChanged(!announcementsEnabled) }
                )
            }

            item {
                ToggleRow(
                    label = TvStrings.WEATHER_ENABLED,
                    enabled = weatherEnabled,
                    info = com.tunisianprayertimes.weather.OpenMeteo.ATTRIBUTION,
                    onToggle = { onWeatherChanged(!weatherEnabled) }
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = TvStrings.ANNOUNCEMENTS_EVERY, color = Gold, fontSize = 20.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FocusableButton(text = "−", onClick = { onAnnouncementsEveryChanged((announcementsEveryMinutes - 5).coerceAtLeast(0)) })
                        Text(
                            text = if (announcementsEveryMinutes == 0) TvStrings.ANNOUNCEMENTS_EVERY_OFF else "$announcementsEveryMinutes ${TvStrings.MINUTES_WORD}",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 20.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(120.dp)
                        )
                        FocusableButton(text = "+", onClick = { onAnnouncementsEveryChanged((announcementsEveryMinutes + 5).coerceAtMost(120)) })
                    }
                }
            }

            // Custom backgrounds toggle
            item {
                ToggleRow(
                    label = TvStrings.CUSTOM_BG_ENABLED,
                    enabled = customBgEnabled,
                    info = "$backgroundCount ${TvStrings.MEDIA_FILES_COUNT}",
                    onToggle = { onCustomBgEnabledChanged(!customBgEnabled) }
                )
            }

            // Announcement interval
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = TvStrings.ANNOUNCEMENT_INTERVAL,
                        style = MaterialTheme.typography.titleLarge,
                        color = Gold,
                        fontSize = 20.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FocusableButton(text = "−", onClick = {
                            onAnnouncementIntervalChanged((announcementIntervalSec - 5).coerceIn(DisplayOptions.SLIDE_SECONDS))
                        })
                        Text(
                            text = "$announcementIntervalSec ${TvStrings.SECONDS_SUFFIX}",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 24.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(80.dp)
                        )
                        FocusableButton(text = "+", onClick = {
                            onAnnouncementIntervalChanged((announcementIntervalSec + 5).coerceIn(DisplayOptions.SLIDE_SECONDS))
                        })
                    }
                }
            }

            // Hint
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(24.dp)
                ) {
                    Text(
                        text = TvStrings.MEDIA_HINT,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = TvStrings.BACKGROUNDS_FOLDER_HINT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                    Text(
                        text = TvStrings.ANNOUNCEMENTS_FOLDER_HINT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            }
            if (backgroundCount + announcementCount > 0) {
                item { FocusableListItem(text = TvStrings.DELETE_IMAGES, onClick = onDeleteImages) }
            }
        }

        Spacer(Modifier.height(16.dp))
        FocusableListItem(text = TvStrings.SAVE, onClick = onBack, modifier = Modifier.initialFocus())
    }
}

@Composable
private fun ToggleRow(
    label: String,
    enabled: Boolean,
    info: String,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = Gold,
                fontSize = 20.sp
            )
            Text(
                text = info,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        }
        FocusableButton(
            text = if (enabled) "✓" else "✗",
            onClick = onToggle
        )
    }
}

@Composable
private fun ThemePickerSection(
    currentThemeId: String,
    onThemeChanged: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val allThemes = remember { ThemeRegistry.allThemes(context) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = TvStrings.SETTINGS_THEME,
            style = MaterialTheme.typography.headlineMedium,
            color = Gold,
            fontSize = 28.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(allThemes) { theme ->
                val isSelected = theme.id == currentThemeId
                ThemeListItem(
                    theme = theme,
                    isSelected = isSelected,
                    onClick = { onThemeChanged(theme.id) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        FocusableListItem(text = TvStrings.CANCEL, onClick = onBack, modifier = Modifier.initialFocus())
    }
}

@Composable
private fun ThemeListItem(
    theme: TvThemeConfig,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val item = @Composable {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = theme.nameAr,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = theme.nameEn,
                    fontSize = 14.sp,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Color preview dots
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(theme.background, theme.surfaceCard, theme.accent, theme.primary).forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(c, RoundedCornerShape(4.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    )
                }
            }
        }
    }

    if (isSelected) {
        // Highlighted — use accent background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                .border(2.dp, Gold, RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            item()
        }
    } else {
        FocusableListItem(
            text = "${theme.nameAr}  —  ${theme.nameEn}",
            onClick = onClick
        )
    }
}
