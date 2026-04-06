package com.tunisianprayertimes.tv.ui.settings

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
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.tv.data.IqamahConfig
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.setup.FocusableButton
import com.tunisianprayertimes.tv.ui.setup.FocusableListItem
import com.tunisianprayertimes.tv.ui.theme.Gold
import com.tunisianprayertimes.tv.ui.theme.TvThemeConfig
import com.tunisianprayertimes.tv.ui.theme.ThemeRegistry

/**
 * Settings screen accessible via Menu button on remote.
 */
@Composable
fun SettingsScreen(
    mosqueName: String,
    delegationName: String,
    iqamahConfigs: Map<Prayer, IqamahConfig>,
    jomoaaConfig: IqamahConfig,
    gouvernorats: List<Gouvernorat>,
    announcementsEnabled: Boolean,
    customBgEnabled: Boolean,
    announcementIntervalSec: Int,
    backgroundCount: Int,
    announcementCount: Int,
    currentThemeId: String,
    onMosqueNameChanged: (String) -> Unit,
    onIqamahChanged: (Prayer, IqamahConfig) -> Unit,
    onJomoaaChanged: (IqamahConfig) -> Unit,
    onDelegationChanged: (Int, Int, String) -> Unit,
    onAnnouncementsEnabledChanged: (Boolean) -> Unit,
    onCustomBgEnabledChanged: (Boolean) -> Unit,
    onAnnouncementIntervalChanged: (Int) -> Unit,
    onThemeChanged: (String) -> Unit,
    onBack: () -> Unit
) {
    var currentSection by remember { mutableStateOf<SettingsSection>(SettingsSection.Main) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back && event.type == KeyEventType.KeyUp) {
                    if (currentSection != SettingsSection.Main) {
                        currentSection = SettingsSection.Main
                        true
                    } else {
                        onBack()
                        true
                    }
                } else false
            }
    ) {
        when (currentSection) {
            SettingsSection.Main -> MainSettingsMenu(
                onSectionSelected = { currentSection = it },
                onBack = onBack
            )
            SettingsSection.MosqueName -> MosqueNameSection(
                mosqueName = mosqueName,
                onChanged = onMosqueNameChanged,
                onBack = { currentSection = SettingsSection.Main }
            )
            SettingsSection.Iqamah -> IqamahSection(
                configs = iqamahConfigs,
                jomoaaConfig = jomoaaConfig,
                onIqamahChanged = onIqamahChanged,
                onJomoaaChanged = onJomoaaChanged,
                onBack = { currentSection = SettingsSection.Main }
            )
            SettingsSection.Location -> LocationSection(
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

private enum class SettingsSection { Main, MosqueName, Iqamah, Location, Media, Theme }

@Composable
private fun MainSettingsMenu(
    onSectionSelected: (SettingsSection) -> Unit,
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
                    onClick = { onSectionSelected(SettingsSection.MosqueName) }
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

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            FocusableListItem(text = TvStrings.SAVE, onClick = {
                onChanged(name)
                onBack()
            })
            FocusableListItem(text = TvStrings.CANCEL, onClick = onBack)
        }
    }
}

@Composable
private fun IqamahSection(
    configs: Map<Prayer, IqamahConfig>,
    jomoaaConfig: IqamahConfig,
    onIqamahChanged: (Prayer, IqamahConfig) -> Unit,
    onJomoaaChanged: (IqamahConfig) -> Unit,
    onBack: () -> Unit
) {
    val prayers = listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)

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
                .fillMaxWidth(0.6f)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(prayers) { prayer ->
                val config = configs[prayer] ?: IqamahConfig()
                IqamahSettingsRow(
                    prayerName = TvStrings.prayerName(prayer),
                    config = config,
                    onConfigChanged = { onIqamahChanged(prayer, it) }
                )
            }
            item {
                IqamahSettingsRow(
                    prayerName = TvStrings.FRIDAY_IQAMAH,
                    config = jomoaaConfig,
                    onConfigChanged = onJomoaaChanged
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        FocusableListItem(text = TvStrings.SAVE, onClick = onBack)
    }
}

@Composable
private fun IqamahSettingsRow(
    prayerName: String,
    config: IqamahConfig,
    onConfigChanged: (IqamahConfig) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = prayerName,
            style = MaterialTheme.typography.titleLarge,
            color = Gold,
            fontSize = 22.sp,
            modifier = Modifier.width(120.dp)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FocusableButton(
                text = "−",
                onClick = {
                    if (config.delayMinutes > 1) {
                        onConfigChanged(config.copy(delayMinutes = config.delayMinutes - 1))
                    }
                }
            )
            Text(
                text = "${config.delayMinutes} ${TvStrings.MINUTES_SUFFIX}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(80.dp)
            )
            FocusableButton(
                text = "+",
                onClick = {
                    if (config.delayMinutes < 60) {
                        onConfigChanged(config.copy(delayMinutes = config.delayMinutes + 1))
                    }
                }
            )
        }
    }
}

@Composable
private fun LocationSection(
    gouvernorats: List<Gouvernorat>,
    currentDelegation: String,
    onDelegationChanged: (Int, Int, String) -> Unit,
    onBack: () -> Unit
) {
    var selectedGouvernorat by remember { mutableStateOf<Int?>(null) }

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
                        onClick = { selectedGouvernorat = g.id }
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
                            onClick = {
                                onDelegationChanged(gouv.id, d.id, d.nomAr)
                                onBack()
                            }
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
                            if (announcementIntervalSec > 5) {
                                onAnnouncementIntervalChanged(announcementIntervalSec - 5)
                            }
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
                            if (announcementIntervalSec < 60) {
                                onAnnouncementIntervalChanged(announcementIntervalSec + 5)
                            }
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
        }

        Spacer(Modifier.height(16.dp))
        FocusableListItem(text = TvStrings.SAVE, onClick = onBack)
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
        FocusableListItem(text = TvStrings.CANCEL, onClick = onBack)
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
