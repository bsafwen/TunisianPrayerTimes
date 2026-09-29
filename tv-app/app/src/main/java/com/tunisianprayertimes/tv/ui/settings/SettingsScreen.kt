package com.tunisianprayertimes.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.Delegation
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.mosque.DisplayOptions
import com.tunisianprayertimes.mosque.MosqueProfile
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.tv.data.IqamahConfig
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.AdminPage
import com.tunisianprayertimes.tv.ui.common.ChoiceGrid
import com.tunisianprayertimes.tv.ui.common.FocusableListItem
import com.tunisianprayertimes.tv.ui.common.FocusableSurface
import com.tunisianprayertimes.tv.ui.common.KeyHints
import com.tunisianprayertimes.tv.ui.common.Stepper
import com.tunisianprayertimes.tv.ui.common.ToggleRow
import com.tunisianprayertimes.tv.ui.common.adminPanel
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.common.onSurfaceMuted
import com.tunisianprayertimes.tv.ui.common.onSurfaceText
import com.tunisianprayertimes.tv.ui.common.rtl
import com.tunisianprayertimes.tv.ui.kiosk.HealthDot
import com.tunisianprayertimes.tv.ui.kiosk.HealthLevel
import com.tunisianprayertimes.tv.ui.kiosk.HealthRow
import com.tunisianprayertimes.tv.ui.kiosk.healthSummary
import com.tunisianprayertimes.tv.ui.kiosk.worstFirst
import com.tunisianprayertimes.tv.ui.setup.IqamahTable
import com.tunisianprayertimes.tv.ui.setup.iqamahText
import com.tunisianprayertimes.tv.ui.setup.mosqueNameFieldColors
import com.tunisianprayertimes.tv.ui.theme.DisplayTheme
import com.tunisianprayertimes.tv.ui.theme.Kufi
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.SkyPhase
import com.tunisianprayertimes.tv.ui.theme.ThemeRegistry
import com.tunisianprayertimes.tv.ui.theme.archTile
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import com.tunisianprayertimes.tv.ui.theme.skyBackground
import com.tunisianprayertimes.weather.OpenMeteo
import java.time.LocalDate

/**
 * The settings, reached from the display by a long press on OK. The menu is master-detail as on the
 * «الإعدادات» board: the sections on the right, and on the left what the focused one holds, so the
 * admin sees the values without opening each section. OK opens a section; Back goes up one level,
 * then to the display (MainActivity also returns there after a few idle minutes).
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
    /**
     * Empties both media folders: every image, from a USB key or the phone, and the announcement .txt
     * files. Called only once the admin confirms on its own page.
     */
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
    /** After Isha the display dims to a small clock until shortly before Fajr. */
    nightScreenEnabled: Boolean = true,
    onNightScreenChanged: (Boolean) -> Unit = {},
    /** A phone session is running: the menu's preview says so, so the admin remembers to stop it. */
    phoneSessionOpen: Boolean = false,
    /** The kiosk page's rows, read when the admin reaches the kiosk section; null shows a description only. */
    kioskPreview: (() -> List<HealthRow>)? = null,
    /**
     * The files [onDeleteImages] would delete: the images in both media folders plus the announcement
     * .txt files (not the settings file's written announcements). Its row shows only when there are some.
     */
    mediaFiles: Int = backgroundCount,
    /**
     * Called on every change of a text field. The box's keyboard takes the remote's keys while it is
     * open, so this is the only sign that the admin is still there, typing.
     */
    onTyping: () -> Unit = {},
) {
    var page by remember { mutableStateOf(if (openKioskPage) SettingsPage.Kiosk else SettingsPage.Menu) }
    // The page the admin just left: back on the menu or a section, its row takes the focus again.
    var from by remember { mutableStateOf<SettingsPage?>(null) }
    fun go(to: SettingsPage) {
        from = page
        page = to
    }
    fun up() = go(page.up)
    // Back goes up one level, then to the display; Android 16 no longer sends Back as a key.
    BackHandler { if (page == SettingsPage.Menu) onBack() else up() }

    val gouvernorat = remember(gouvernorats, currentDelegationId) { gouvernoratOf(gouvernorats, currentDelegationId) }
    val place = placeName(delegationName, gouvernorat)
    val advanced = advancedActions(canUndoImport, customTexts)

    Box(
        Modifier
            .fillMaxSize()
            .background(Midad.Ground)
            .padding(horizontal = 48.dp, vertical = 27.dp)
    ) {
        when (page) {
            SettingsPage.Menu -> SettingsMenu(
                summary = SettingsSummary(
                    mosqueName = mosqueName,
                    delegationName = delegationName,
                    gouvernoratName = gouvernorat?.nomAr,
                    iqamahConfigs = iqamahConfigs,
                    today = today,
                    announcementsEnabled = announcementsEnabled,
                    announcementCount = announcementCount,
                    announcementsEveryMinutes = announcementsEveryMinutes,
                    announcementIntervalSec = announcementIntervalSec,
                    backgroundCount = backgroundCount,
                    customBgEnabled = customBgEnabled,
                    theme = ThemeRegistry.findById(currentThemeId),
                    weatherEnabled = weatherEnabled,
                    nightScreenEnabled = nightScreenEnabled,
                    phoneSessionOpen = phoneSessionOpen,
                    kioskPreview = kioskPreview,
                    advanced = advanced,
                    aboutLines = aboutLines,
                ),
                focusOn = from?.section?.takeIf { it in SETTINGS_MENU } ?: SETTINGS_MENU.first(),
                onOpen = ::go,
            )
            SettingsPage.Mosque -> MosquePage(mosqueName, place, focusOn = from, onOpen = ::go)
            SettingsPage.MosqueName -> MosqueNamePage(
                mosqueName = mosqueName,
                onSave = {
                    onMosqueNameChanged(it)
                    up()
                },
                onCancel = ::up,
                onTyping = onTyping,
            )
            SettingsPage.Location -> LocationPage(
                gouvernorats = gouvernorats,
                current = gouvernorat,
                currentDelegationId = currentDelegationId,
                currentPlace = place,
                previewTimes = previewTimes,
                onChanged = onDelegationChanged,
                onDone = ::up,
            )
            SettingsPage.Iqamah -> AdminPage(
                title = TvStrings.SECTION_IQAMAH,
                aside = TvStrings.DURATION_NOTE,
                hints = listOf(TvStrings.HINT_SAVED_AT_ONCE, TvStrings.HINT_BACK_TO_SETTINGS),
            ) {
                IqamahTable(configs = iqamahConfigs, onChanged = onIqamahChanged, modifier = Modifier.weight(1f))
            }
            SettingsPage.Dates -> IslamicDatesSection(today = today)
            SettingsPage.Media -> MediaPage(
                announcementsEnabled = announcementsEnabled,
                announcementCount = announcementCount,
                announcementIntervalSec = announcementIntervalSec,
                announcementsEveryMinutes = announcementsEveryMinutes,
                onAnnouncementsEnabledChanged = onAnnouncementsEnabledChanged,
                onAnnouncementIntervalChanged = onAnnouncementIntervalChanged,
                onAnnouncementsEveryChanged = onAnnouncementsEveryChanged,
                mediaFiles = mediaFiles,
                // Back from the question (or Cancel) returns to its row.
                focusDelete = from == SettingsPage.DeleteMedia,
                onDeleteImages = { go(SettingsPage.DeleteMedia) },
            )
            SettingsPage.DeleteMedia -> ConfirmPage(
                title = TvStrings.DELETE_IMAGES,
                text = TvStrings.DELETE_IMAGES_CONFIRM,
                confirm = TvStrings.DELETE_IMAGES_DO,
                onConfirm = {
                    onDeleteImages()
                    // The row leaves with the files: the page opens again on its first row.
                    from = null
                    page = SettingsPage.Media
                },
                onCancel = ::up,
            )
            SettingsPage.Appearance -> AppearancePage(
                currentThemeId = currentThemeId,
                onThemeChanged = onThemeChanged,
                nightScreenEnabled = nightScreenEnabled,
                onNightScreenChanged = onNightScreenChanged,
                weatherEnabled = weatherEnabled,
                onWeatherChanged = onWeatherChanged,
                customBgEnabled = customBgEnabled,
                backgroundCount = backgroundCount,
                onCustomBgEnabledChanged = onCustomBgEnabledChanged,
            )
            SettingsPage.Phone -> phonePage { go(SettingsPage.Menu) }
            SettingsPage.Kiosk -> kioskPage { go(SettingsPage.Menu) }
            SettingsPage.Advanced -> AdvancedPage(advanced, focusOn = from) { action ->
                when (action) {
                    AdvancedAction.UNDO_IMPORT -> onUndoImport()
                    AdvancedAction.BUNDLED_TEXTS -> go(SettingsPage.BundledTexts)
                    AdvancedAction.RESET -> go(SettingsPage.Reset)
                    AdvancedAction.EXIT_TO_ANDROID -> onExitToAndroid()
                }
            }
            SettingsPage.Reset -> ConfirmPage(
                title = TvStrings.RESET_ALL,
                text = TvStrings.RESET_CONFIRM,
                confirm = TvStrings.RESET_DO,
                onConfirm = onResetAll,
                onCancel = ::up,
            )
            SettingsPage.BundledTexts -> ConfirmPage(
                title = TvStrings.BUNDLED_TEXTS,
                text = TvStrings.BUNDLED_TEXTS_CONFIRM,
                confirm = TvStrings.BUNDLED_TEXTS_DO,
                onConfirm = onBundledTexts,
                onCancel = ::up,
            )
            SettingsPage.About -> AboutPage(aboutLines, onBack = ::up)
        }
    }
}

/** Every page of the settings. The menu's sections are [SETTINGS_MENU]; the others open from a section. */
internal enum class SettingsPage {
    Menu, Mosque, MosqueName, Location, Iqamah, Dates, Media, DeleteMedia, Appearance, Phone, Kiosk, Advanced, Reset, BundledTexts, About;

    /** Where Back goes from here. */
    val up: SettingsPage
        get() = when (this) {
            MosqueName, Location -> Mosque
            DeleteMedia -> Media
            Reset, BundledTexts -> Advanced
            else -> Menu
        }

    /** The menu row this page belongs to. */
    val section: SettingsPage get() = if (this == Menu || up == Menu) this else up.section
}

/** The menu, in the board's order; «متقدم» holds the rare and irreversible actions before «حول التطبيق». */
internal val SETTINGS_MENU = listOf(
    SettingsPage.Mosque, SettingsPage.Iqamah, SettingsPage.Dates, SettingsPage.Media, SettingsPage.Appearance,
    SettingsPage.Phone, SettingsPage.Kiosk, SettingsPage.Advanced, SettingsPage.About,
)

/** The quieter rows at the foot of the menu. */
private val QUIET_ROWS = setOf(SettingsPage.Advanced, SettingsPage.About)

internal val SettingsPage.title: String
    get() = when (this) {
        SettingsPage.Menu -> TvStrings.SETTINGS_TITLE
        SettingsPage.Mosque -> TvStrings.SECTION_MOSQUE
        SettingsPage.MosqueName -> TvStrings.MOSQUE_NAME_LABEL
        SettingsPage.Location -> TvStrings.SETTINGS_LOCATION
        SettingsPage.Iqamah -> TvStrings.SECTION_IQAMAH
        SettingsPage.Dates -> TvStrings.SECTION_DATES
        SettingsPage.Media -> TvStrings.SECTION_MEDIA
        SettingsPage.DeleteMedia -> TvStrings.DELETE_IMAGES
        SettingsPage.Appearance -> TvStrings.SETTINGS_THEME
        SettingsPage.Phone -> TvStrings.SETTINGS_PHONE
        SettingsPage.Kiosk -> TvStrings.SETTINGS_KIOSK
        SettingsPage.Advanced -> TvStrings.SECTION_ADVANCED
        SettingsPage.Reset -> TvStrings.RESET_ALL
        SettingsPage.BundledTexts -> TvStrings.BUNDLED_TEXTS
        SettingsPage.About -> TvStrings.ABOUT
    }

/** The actions under «متقدم», each with the line that says what it does. */
internal enum class AdvancedAction(val title: String, val hint: String, val page: SettingsPage?) {
    UNDO_IMPORT(TvStrings.UNDO_IMPORT, TvStrings.UNDO_IMPORT_HINT, null),
    BUNDLED_TEXTS(TvStrings.BUNDLED_TEXTS, TvStrings.BUNDLED_TEXTS_HINT, SettingsPage.BundledTexts),
    RESET(TvStrings.RESET_ALL, TvStrings.RESET_HINT, SettingsPage.Reset),
    EXIT_TO_ANDROID(TvStrings.EXIT_TO_ANDROID, TvStrings.EXIT_TO_ANDROID_HINT, null),
}

/** The actions that apply now: undo only after an import, the bundled texts only when the mosque changed them. */
internal fun advancedActions(canUndoImport: Boolean, customTexts: Boolean): List<AdvancedAction> =
    AdvancedAction.entries.filter {
        when (it) {
            AdvancedAction.UNDO_IMPORT -> canUndoImport
            AdvancedAction.BUNDLED_TEXTS -> customTexts
            else -> true
        }
    }

/** The gouvernorat that holds [delegationId], if any. */
internal fun gouvernoratOf(gouvernorats: List<Gouvernorat>, delegationId: Int): Gouvernorat? =
    gouvernorats.find { g -> g.delegations.any { it.id == delegationId } }

/** «المرسى — تونس», or what is known of it. */
internal fun placeName(delegationName: String, gouvernorat: Gouvernorat?): String = when {
    delegationName.isBlank() -> TvStrings.NOT_SET
    gouvernorat == null -> delegationName
    else -> "$delegationName — ${gouvernorat.nomAr}"
}

/** The announcements between prayers, five minutes a press; 0 means only after the prayers. */
internal fun stepEveryMinutes(minutes: Int, direction: Int): Int =
    (minutes + 5 * direction).coerceIn(DisplayOptions.EVERY_MINUTES)

/** How long each announcement stays, five seconds a press. */
internal fun stepSlideSeconds(seconds: Int, direction: Int): Int =
    (seconds + 5 * direction).coerceIn(DisplayOptions.SLIDE_SECONDS)

internal fun everyText(minutes: Int): String =
    if (minutes <= 0) TvStrings.ANNOUNCEMENTS_EVERY_OFF else TvStrings.everyMinutes(minutes)

private fun onOff(on: Boolean): String = if (on) TvStrings.ON else TvStrings.OFF

/** The rows of the section pages stay readable across the room rather than stretch over the whole width. */
private val ROW_WIDTH = 680.dp

// ── The menu ──

/** What the menu's preview shows of each section, as the settings are now. */
private class SettingsSummary(
    val mosqueName: String,
    val delegationName: String,
    val gouvernoratName: String?,
    val iqamahConfigs: Map<Prayer, IqamahConfig>,
    val today: LocalDate,
    val announcementsEnabled: Boolean,
    val announcementCount: Int,
    val announcementsEveryMinutes: Int,
    val announcementIntervalSec: Int,
    val backgroundCount: Int,
    val customBgEnabled: Boolean,
    val theme: DisplayTheme,
    val weatherEnabled: Boolean,
    val nightScreenEnabled: Boolean,
    val phoneSessionOpen: Boolean,
    val kioskPreview: (() -> List<HealthRow>)?,
    val advanced: List<AdvancedAction>,
    val aboutLines: List<String>,
)

/**
 * The sections on the right, 300 dp as on the board; on the left, in a panel, what the focused one
 * holds, and the remote's keys at its foot. [focusOn] takes the focus: the section the admin came
 * back from, so Back then OK returns to the same page.
 */
@Composable
private fun SettingsMenu(summary: SettingsSummary, focusOn: SettingsPage, onOpen: (SettingsPage) -> Unit) {
    var previewed by remember { mutableStateOf(focusOn) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(TvStrings.SETTINGS_TITLE, style = midadStyle(26.sp, FontWeight.SemiBold), modifier = Modifier.padding(bottom = 3.dp))
            SETTINGS_MENU.forEach { section ->
                MenuRow(
                    text = section.title,
                    quiet = section in QUIET_ROWS,
                    onClick = { onOpen(section) },
                    modifier = Modifier
                        .onFocusChanged { if (it.isFocused) previewed = section }
                        .initialFocus(section == focusOn),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .adminPanel(),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                SectionPreview(previewed, summary)
            }
            KeyHints(listOf(TvStrings.HINT_OK_OPEN, TvStrings.HINT_BACK_TO_SCREEN, TvStrings.HINT_FROM_PHONE))
        }
    }
}

/** A section of the menu: 38 dp, ivory with ink text and the gold ring when focused. */
@Composable
private fun MenuRow(text: String, quiet: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 38.dp)) { focused ->
        Text(
            text,
            style = midadStyle(
                17.sp,
                if (focused) FontWeight.SemiBold else FontWeight.Normal,
                onSurfaceText(focused, rest = if (quiet) Midad.Muted else Midad.Text),
            ).rtl(),
        )
    }
}

/** What [section] holds, before it is opened. */
@Composable
private fun ColumnScope.SectionPreview(section: SettingsPage, summary: SettingsSummary) {
    when (section) {
        SettingsPage.Mosque -> {
            PreviewHeader(section.title)
            if (summary.mosqueName.isBlank()) {
                Text(TvStrings.NOT_SET, style = midadStyle(20.sp, color = Midad.Muted), modifier = Modifier.padding(vertical = 6.dp))
            } else {
                // The mosque's name, one of the few words set in Kufic.
                Text(
                    summary.mosqueName,
                    style = midadStyle(26.sp, FontWeight.SemiBold, family = Kufi, lineHeight = 1.3f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            InfoRow(TvStrings.GOUVERNORAT_LABEL, summary.gouvernoratName ?: TvStrings.NOT_SET)
            InfoRow(TvStrings.DELEGATION_LABEL, summary.delegationName.ifBlank { TvStrings.NOT_SET })
        }
        SettingsPage.Iqamah -> {
            PreviewHeader(section.title, aside = TvStrings.DURATION_NOTE)
            SettingsTable(
                header = listOf(TvStrings.PRAYER_COLUMN, TvStrings.IQAMAH_LABEL, TvStrings.DURATION_COLUMN),
                rows = MosqueSchedule.CONFIGURABLE.map { prayer ->
                    val config = summary.iqamahConfigs[prayer] ?: IqamahConfig.from(MosqueSchedule.DEFAULT.settings(prayer))
                    listOf(TvStrings.prayerName(prayer), iqamahText(config), TvStrings.minutesShort(config.salahMinutes))
                },
                widths = listOf(130.dp, 110.dp),
            )
        }
        SettingsPage.Dates -> {
            val upcoming = rememberUpcomingDates(summary.today)
            PreviewHeader(section.title, aside = TvStrings.hijriYear(upcoming.year))
            SettingsTable(
                header = null,
                rows = listOf(
                    TvStrings.RAMADAN_START to upcoming.dates.ramadanStart,
                    TvStrings.EID_FITR to upcoming.dates.eidFitr,
                    TvStrings.EID_ADHA to upcoming.dates.eidAdha,
                ).map { (label, event) -> listOf(label, TvStrings.gregorianDate(event.date), sourceLabel(event.source)) },
                widths = listOf(210.dp, 56.dp),
            )
            Note(TvStrings.ISLAMIC_DATES_HINT)
        }
        SettingsPage.Media -> {
            PreviewHeader(section.title)
            InfoRow(TvStrings.SETTINGS_ANNOUNCEMENTS, "${onOff(summary.announcementsEnabled)} · ${TvStrings.filesCount(summary.announcementCount)}")
            InfoRow(TvStrings.ANNOUNCEMENTS_BETWEEN, everyText(summary.announcementsEveryMinutes))
            InfoRow(TvStrings.ANNOUNCEMENT_INTERVAL, TvStrings.secondsShort(summary.announcementIntervalSec))
            InfoRow(TvStrings.BACKGROUNDS_LABEL, TvStrings.filesCount(summary.backgroundCount))
            Note(TvStrings.MEDIA_HINT)
        }
        SettingsPage.Appearance -> {
            PreviewHeader(section.title)
            Row(
                Modifier.padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThemeSwatch(summary.theme)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(summary.theme.nameAr, style = midadStyle(20.sp, FontWeight.SemiBold))
                    Text(summary.theme.description, style = midadStyle(14.sp, color = Midad.Muted))
                }
            }
            InfoRow(TvStrings.NIGHT_SCREEN, onOff(summary.nightScreenEnabled))
            InfoRow(TvStrings.WEATHER_LABEL, onOff(summary.weatherEnabled))
            InfoRow(TvStrings.BACKGROUNDS_LABEL, onOff(summary.customBgEnabled))
        }
        SettingsPage.Phone -> {
            PreviewHeader(section.title)
            Text(TvStrings.PHONE_PREVIEW, style = midadStyle(17.sp, lineHeight = 1.55f).rtl())
            if (summary.phoneSessionOpen) {
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HealthDot(HealthLevel.GOOD)
                    Text(TvStrings.PHONE_SESSION_OPEN, style = midadStyle(17.sp, FontWeight.Medium))
                }
            }
        }
        SettingsPage.Kiosk -> {
            PreviewHeader(section.title)
            Note(TvStrings.KIOSK_PREVIEW)
            // Read once as the admin reaches the row: it asks the system for its power and start-up settings.
            val rows = remember { runCatching { summary.kioskPreview?.invoke() }.getOrNull() }
            if (rows != null) {
                Text(healthSummary(rows), style = midadStyle(18.sp, FontWeight.Medium), modifier = Modifier.padding(vertical = 4.dp))
                rows.worstFirst().take(KIOSK_PREVIEW_ROWS).forEach { row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Midad.SurfaceRaised, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HealthDot(row.level)
                        Text(
                            row.text,
                            style = midadStyle(15.sp, lineHeight = 1.4f).rtl(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        SettingsPage.Advanced -> {
            PreviewHeader(section.title)
            summary.advanced.forEach { action ->
                Column(Modifier.padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(action.title, style = midadStyle(17.sp, FontWeight.Medium))
                    Text(action.hint, style = midadStyle(14.sp, color = Midad.Muted).rtl())
                }
            }
        }
        SettingsPage.About -> {
            PreviewHeader(section.title)
            summary.aboutLines.take(ABOUT_PREVIEW_LINES).forEach {
                Text(it, style = midadStyle(15.sp, color = Midad.Muted, lineHeight = 1.45f).rtl())
            }
        }
        else -> Unit
    }
}

private const val KIOSK_PREVIEW_ROWS = 4
private const val ABOUT_PREVIEW_LINES = 5

/** The preview's title, 21 sp, and a quiet line at the other end of it (the board's «المدة: …»). */
@Composable
private fun PreviewHeader(title: String, aside: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = midadStyle(21.sp, FontWeight.SemiBold).rtl(), modifier = Modifier.alignByBaseline())
        if (aside != null) Text(aside, style = midadStyle(14.sp, color = Midad.Muted).rtl(), modifier = Modifier.alignByBaseline())
    }
}

/** A value in the preview: its name, and what it is set to. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(Midad.SurfaceRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = midadStyle(17.sp).rtl(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(value, style = midadStyle(17.sp, FontWeight.Medium).rtl(), maxLines = 1)
    }
}

/** A quiet line of explanation under a preview or a page. */
@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = midadStyle(14.sp, color = Midad.Muted, lineHeight = 1.5f).rtl(), modifier = modifier.padding(top = 4.dp))
}

/**
 * A table on a panel, as the board's iqamah table: a quiet header, then one row per line on the raised
 * surface. The first column takes what is left; the others have [widths], so the columns line up.
 */
@Composable
private fun SettingsTable(header: List<String>?, rows: List<List<String>>, widths: List<Dp>, rowHeight: Dp = 38.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (header != null) TableLine(header, widths, midadStyle(14.sp, color = Midad.Muted), Modifier.padding(horizontal = 10.dp))
        rows.forEach { cells ->
            TableLine(
                cells,
                widths,
                midadStyle(18.sp),
                Modifier
                    .height(rowHeight)
                    .background(Midad.SurfaceRaised, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
            )
        }
    }
}

@Composable
private fun TableLine(cells: List<String>, widths: List<Dp>, style: TextStyle, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { index, cell ->
            Text(
                cell,
                style = style.rtl(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (index == 0) Modifier.weight(1f) else Modifier.width(widths[index - 1]),
            )
        }
    }
}

// ── «المسجد والموقع» ──

/** The mosque's name and its place, each opening its own page. */
@Composable
private fun MosquePage(mosqueName: String, place: String, focusOn: SettingsPage?, onOpen: (SettingsPage) -> Unit) {
    AdminPage(title = TvStrings.SECTION_MOSQUE, hints = listOf(TvStrings.HINT_OK_CHANGE, TvStrings.HINT_BACK_TO_SETTINGS)) {
        ValueRow(
            label = TvStrings.MOSQUE_NAME_LABEL,
            value = mosqueName.ifBlank { TvStrings.NOT_SET },
            onClick = { onOpen(SettingsPage.MosqueName) },
            modifier = Modifier.initialFocus(focusOn != SettingsPage.Location),
        )
        ValueRow(
            label = TvStrings.SETTINGS_LOCATION,
            value = place,
            onClick = { onOpen(SettingsPage.Location) },
            modifier = Modifier.initialFocus(focusOn == SettingsPage.Location),
        )
    }
}

/** A setting that opens a page to change it: its name, and its value at the other end. */
@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.widthIn(max = ROW_WIDTH).fillMaxWidth().heightIn(min = 46.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
    ) { focused ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = midadStyle(17.sp, FontWeight.Medium, onSurfaceText(focused)).rtl(), modifier = Modifier.weight(1f))
            Text(value, style = midadStyle(17.sp, color = onSurfaceMuted(focused)).rtl(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The name, typed with the box's keyboard. Save has the focus, as before: a text field that took it
 * would open the keyboard over the page at once. Each change calls [onTyping], so a slow name typed
 * with the arrows is not taken for an idle screen.
 */
@Composable
private fun MosqueNamePage(mosqueName: String, onSave: (String) -> Unit, onCancel: () -> Unit, onTyping: () -> Unit) {
    var name by remember { mutableStateOf(mosqueName) }
    AdminPage(title = TvStrings.MOSQUE_NAME_LABEL, hints = listOf(TvStrings.HINT_BACK_TO_SETTINGS)) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                onTyping()
            },
            placeholder = { Text(TvStrings.SETUP_MOSQUE_HINT, style = midadStyle(20.sp, color = Midad.Dim)) },
            colors = mosqueNameFieldColors(),
            textStyle = midadStyle(20.sp),
            shape = RoundedCornerShape(9.dp),
            singleLine = true,
            modifier = Modifier.width(480.dp),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusableListItem(
                text = TvStrings.SAVE,
                onClick = { onSave(name.trim().take(MosqueProfile.MAX_NAME_LENGTH)) },
                modifier = Modifier.width(160.dp).initialFocus(),
            )
            FocusableListItem(text = TvStrings.CANCEL, onClick = onCancel, modifier = Modifier.width(160.dp))
        }
    }
}

/**
 * A new place in three steps: the gouvernorat, its delegation, then today's times here and there
 * side by side. Nothing changes until the admin confirms; Back steps back through the choice.
 */
@Composable
private fun LocationPage(
    gouvernorats: List<Gouvernorat>,
    current: Gouvernorat?,
    currentDelegationId: Int,
    currentPlace: String,
    previewTimes: (Int) -> DayPrayerTimes?,
    onChanged: (Int, Int, String) -> Unit,
    onDone: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf(current) }
    var delegation by remember { mutableStateOf<Delegation?>(null) }
    BackHandler(enabled = step > 0) { step -= 1 }
    val gouvernorat = chosen
    val aside = "${TvStrings.CURRENT_PLACE}: $currentPlace"

    // Each step is its own page, so the grid it shows takes the focus afresh.
    key(step) {
        when {
            step == 0 || gouvernorat == null -> AdminPage(
                title = TvStrings.SETUP_SELECT_GOUVERNORAT,
                aside = aside,
                hints = listOf(TvStrings.HINT_BACK_TO_SETTINGS),
            ) {
                ChoiceGrid(
                    choices = gouvernorats,
                    label = { it.nomAr },
                    onChoose = {
                        if (it != chosen) delegation = null
                        chosen = it
                        step = 1
                    },
                    focusFirst = gouvernorat,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
            step == 1 || delegation == null -> AdminPage(
                title = "${TvStrings.SETUP_SELECT_DELEGATION} — ${gouvernorat.nomAr}",
                aside = aside,
                hints = listOf(TvStrings.HINT_BACK_TO_STEP),
            ) {
                ChoiceGrid(
                    choices = gouvernorat.delegations,
                    label = { it.nomAr },
                    onChoose = {
                        delegation = it
                        step = 2
                    },
                    focusFirst = delegation ?: gouvernorat.delegations.find { it.id == currentDelegationId },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
            else -> {
                val target = delegation!!
                val before = remember(currentDelegationId) { previewTimes(currentDelegationId) }
                val after = remember(target.id) { previewTimes(target.id) }
                ConfirmPage(
                    title = TvStrings.SETTINGS_LOCATION,
                    text = "${TvStrings.LOCATION_CONFIRM} ${target.nomAr} (${gouvernorat.nomAr})",
                    confirm = TvStrings.CONFIRM,
                    onConfirm = {
                        onChanged(gouvernorat.id, target.id, target.nomAr)
                        onDone()
                    },
                    onCancel = { step = 1 },
                    hints = listOf(TvStrings.HINT_BACK_TO_STEP),
                ) {
                    Text(TvStrings.TODAY_TIMES, style = midadStyle(14.sp, color = Midad.Muted), modifier = Modifier.padding(top = 4.dp))
                    Box(Modifier.widthIn(max = 560.dp)) {
                        SettingsTable(
                            header = listOf(TvStrings.PRAYER_COLUMN, TvStrings.CURRENT_PLACE, TvStrings.NEW_PLACE),
                            rows = compareTimes(before, after),
                            widths = listOf(130.dp, 130.dp),
                            rowHeight = 32.dp,
                        )
                    }
                }
            }
        }
    }
}

/** Today's five prayers at the current place and the new one; «—» where a table is missing. */
internal fun compareTimes(before: DayPrayerTimes?, after: DayPrayerTimes?): List<List<String>> {
    fun hm(time: PrayerTime?) = time?.let { String.format(java.util.Locale.ROOT, "%02d:%02d", it.hour, it.minute) } ?: "—"
    fun times(day: DayPrayerTimes?) = listOf(day?.fajr, day?.dhuhr, day?.asr, day?.maghrib, day?.isha)
    val names = listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA).map(TvStrings::prayerName)
    return names.indices.map { i -> listOf(names[i], hm(times(before)[i]), hm(times(after)[i])) }
}

// ── «الإعلانات والصور» ──

/**
 * The announcements' options, then the deletion of the media files, offered only when there are
 * some ([mediaFiles]); [onDeleteImages] opens its question. [focusDelete]: back from that question,
 * its row takes the focus again.
 */
@Composable
private fun MediaPage(
    announcementsEnabled: Boolean,
    announcementCount: Int,
    announcementIntervalSec: Int,
    announcementsEveryMinutes: Int,
    onAnnouncementsEnabledChanged: (Boolean) -> Unit,
    onAnnouncementIntervalChanged: (Int) -> Unit,
    onAnnouncementsEveryChanged: (Int) -> Unit,
    mediaFiles: Int,
    focusDelete: Boolean,
    onDeleteImages: () -> Unit,
) {
    val canDelete = mediaFiles > 0
    AdminPage(
        title = TvStrings.SECTION_MEDIA,
        hints = listOf(TvStrings.HINT_OK_CHANGE, TvStrings.HINT_SAVED_AT_ONCE, TvStrings.HINT_BACK_TO_SETTINGS),
        scroll = true,
    ) {
        ToggleRow(
            label = TvStrings.SETTINGS_ANNOUNCEMENTS,
            checked = announcementsEnabled,
            onToggle = { onAnnouncementsEnabledChanged(!announcementsEnabled) },
            detail = TvStrings.filesCount(announcementCount),
            modifier = Modifier.widthIn(max = ROW_WIDTH).initialFocus(!(focusDelete && canDelete)),
        )
        StepperRow(
            label = TvStrings.ANNOUNCEMENTS_BETWEEN,
            value = everyText(announcementsEveryMinutes),
            onMinus = { onAnnouncementsEveryChanged(stepEveryMinutes(announcementsEveryMinutes, -1)) },
            onPlus = { onAnnouncementsEveryChanged(stepEveryMinutes(announcementsEveryMinutes, 1)) },
            valueWidth = 130.dp,
        )
        StepperRow(
            label = TvStrings.ANNOUNCEMENT_INTERVAL,
            value = TvStrings.secondsShort(announcementIntervalSec),
            onMinus = { onAnnouncementIntervalChanged(stepSlideSeconds(announcementIntervalSec, -1)) },
            onPlus = { onAnnouncementIntervalChanged(stepSlideSeconds(announcementIntervalSec, 1)) },
            valueWidth = 70.dp,
        )
        if (canDelete) {
            ActionRow(
                TvStrings.DELETE_IMAGES,
                TvStrings.DELETE_IMAGES_HINT,
                onDeleteImages,
                Modifier.widthIn(max = ROW_WIDTH).initialFocus(focusDelete),
            )
        }
        Column(Modifier.widthIn(max = ROW_WIDTH).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(TvStrings.MEDIA_HINT, style = midadStyle(14.sp, color = Midad.Muted, lineHeight = 1.5f).rtl())
            Text(TvStrings.BACKGROUNDS_FOLDER_HINT, style = midadStyle(14.sp, color = Midad.Muted).rtl())
            Text(TvStrings.ANNOUNCEMENTS_FOLDER_HINT, style = midadStyle(14.sp, color = Midad.Muted).rtl())
        }
    }
}

/** A number the remote changes with − and +, on the same surface as a toggle. */
@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, valueWidth: Dp) {
    Row(
        Modifier
            .widthIn(max = ROW_WIDTH)
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .background(Midad.Surface, RoundedCornerShape(9.dp))
            .padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = midadStyle(17.sp, FontWeight.Medium).rtl(), modifier = Modifier.weight(1f))
        Stepper(value = value, onMinus = onMinus, onPlus = onPlus, valueWidth = valueWidth)
    }
}

/** An action with one line that says what it does. */
@Composable
private fun ActionRow(title: String, hint: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 46.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
    ) { focused ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = midadStyle(17.sp, FontWeight.Medium, onSurfaceText(focused)).rtl())
            Text(hint, style = midadStyle(14.sp, color = onSurfaceMuted(focused)).rtl())
        }
    }
}

// ── «المظهر» ──

/** The two looks side by side, then what the display shows: the night screen, the weather, the backgrounds. */
@Composable
private fun AppearancePage(
    currentThemeId: String,
    onThemeChanged: (String) -> Unit,
    nightScreenEnabled: Boolean,
    onNightScreenChanged: (Boolean) -> Unit,
    weatherEnabled: Boolean,
    onWeatherChanged: (Boolean) -> Unit,
    customBgEnabled: Boolean,
    backgroundCount: Int,
    onCustomBgEnabledChanged: (Boolean) -> Unit,
) {
    val current = ThemeRegistry.findById(currentThemeId)
    AdminPage(
        title = TvStrings.SETTINGS_THEME,
        hints = listOf(TvStrings.HINT_OK_CHANGE, TvStrings.HINT_SAVED_AT_ONCE, TvStrings.HINT_BACK_TO_SETTINGS),
        scroll = true,
    ) {
        Row(Modifier.widthIn(max = ROW_WIDTH).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeRegistry.builtInThemes.forEach { theme ->
                ThemeCard(
                    theme = theme,
                    selected = theme.id == current.id,
                    onClick = { onThemeChanged(theme.id) },
                    modifier = Modifier.weight(1f).initialFocus(theme.id == current.id),
                )
            }
        }
        Text(TvStrings.DISPLAY_OPTIONS, style = midadStyle(17.sp, FontWeight.SemiBold), modifier = Modifier.padding(top = 10.dp))
        ToggleRow(
            label = TvStrings.NIGHT_SCREEN,
            checked = nightScreenEnabled,
            onToggle = { onNightScreenChanged(!nightScreenEnabled) },
            detail = TvStrings.NIGHT_SCREEN_HINT,
            modifier = Modifier.widthIn(max = ROW_WIDTH),
        )
        ToggleRow(
            label = TvStrings.WEATHER_LABEL,
            checked = weatherEnabled,
            onToggle = { onWeatherChanged(!weatherEnabled) },
            // Open-Meteo's data is CC BY 4.0: it is credited wherever the weather is offered.
            detail = "${TvStrings.WEATHER_HINT} · ${TvStrings.WEATHER_SOURCE} ${OpenMeteo.ATTRIBUTION}",
            modifier = Modifier.widthIn(max = ROW_WIDTH),
        )
        ToggleRow(
            label = TvStrings.BACKGROUNDS_LABEL,
            checked = customBgEnabled,
            onToggle = { onCustomBgEnabledChanged(!customBgEnabled) },
            detail = "${TvStrings.filesCount(backgroundCount)} · ${TvStrings.BACKGROUNDS_HOWTO}",
            modifier = Modifier.widthIn(max = ROW_WIDTH),
        )
    }
}

/** A look: a small picture of it, its name and line, and a mark on the one in use. */
@Composable
private fun ThemeCard(theme: DisplayTheme, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 76.dp),
        radius = 12.dp,
        contentPadding = PaddingValues(10.dp),
    ) { focused ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ThemeSwatch(theme)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(theme.nameAr, style = midadStyle(19.sp, FontWeight.SemiBold, onSurfaceText(focused)))
                Text(theme.description, style = midadStyle(13.sp, color = onSurfaceMuted(focused), lineHeight = 1.4f), maxLines = 2)
                if (selected) Text(TvStrings.THEME_CURRENT, style = midadStyle(13.sp, FontWeight.Medium, onSurfaceText(focused)))
            }
            SelectedMark(selected, focused)
        }
    }
}

/**
 * The look in miniature: the sky of Maghrib fading into the ground for «أفق», the plain ground for
 * «مداد», with the timetable's arches along the bottom as on the display.
 */
@Composable
private fun ThemeSwatch(theme: DisplayTheme) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(width = 80.dp, height = 45.dp)
            .clip(shape)
            .skyBackground(if (theme.sky) SkyPhase.MAGHRIB.colors else null, horizon = 20.dp, groundAt = 45.dp)
            .border(1.dp, Midad.Keyline, shape),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(5) { Box(Modifier.weight(1f).height(14.dp).archTile(Midad.Surface, Midad.ArchLine)) }
        }
    }
}

/** A round mark, filled on the look in use. */
@Composable
private fun SelectedMark(selected: Boolean, focused: Boolean) {
    val color = onSurfaceText(focused)
    Canvas(Modifier.size(18.dp)) {
        val stroke = 2.dp.toPx()
        drawCircle(color, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
        if (selected) drawCircle(color, radius = size.minDimension / 4f)
    }
}

// ── «متقدم», confirmations, «حول التطبيق» ──

/** The rare actions, each with what it does; [focusOn] is the one the admin came back from. */
@Composable
private fun AdvancedPage(actions: List<AdvancedAction>, focusOn: SettingsPage?, onAction: (AdvancedAction) -> Unit) {
    val first = actions.find { it.page != null && it.page == focusOn } ?: actions.first()
    AdminPage(title = TvStrings.SECTION_ADVANCED, hints = listOf(TvStrings.HINT_OK_OPEN, TvStrings.HINT_BACK_TO_SETTINGS)) {
        actions.forEach { action ->
            ActionRow(
                title = action.title,
                hint = action.hint,
                onClick = { onAction(action) },
                modifier = Modifier.widthIn(max = ROW_WIDTH).initialFocus(action == first),
            )
        }
    }
}

/** A question with one confirming key and Cancel, which has the focus: nothing happens until the admin moves to confirm. */
@Composable
private fun ConfirmPage(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    hints: List<String> = listOf(TvStrings.HINT_BACK_TO_SETTINGS),
    content: @Composable ColumnScope.() -> Unit = {},
) {
    AdminPage(title = title, hints = hints) {
        Text(text, style = midadStyle(18.sp, lineHeight = 1.5f).rtl(), modifier = Modifier.widthIn(max = ROW_WIDTH))
        content()
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusableListItem(text = confirm, onClick = onConfirm, modifier = Modifier.width(240.dp))
            FocusableListItem(text = TvStrings.CANCEL, onClick = onCancel, modifier = Modifier.width(160.dp).initialFocus())
        }
    }
}

/** Version, data and device, for a phone call with whoever maintains the screens. */
@Composable
private fun AboutPage(lines: List<String>, onBack: () -> Unit) {
    AdminPage(title = TvStrings.ABOUT, hints = listOf(TvStrings.HINT_BACK_TO_SETTINGS)) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().adminPanel(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEachIndexed { index, line ->
                Text(
                    line,
                    style = midadStyle(if (index == 0) 17.sp else 15.sp, if (index == 0) FontWeight.Medium else FontWeight.Normal, lineHeight = 1.45f).rtl(),
                )
            }
        }
        FocusableListItem(text = TvStrings.BACK, onClick = onBack, modifier = Modifier.padding(top = 6.dp).width(160.dp).initialFocus())
    }
}
