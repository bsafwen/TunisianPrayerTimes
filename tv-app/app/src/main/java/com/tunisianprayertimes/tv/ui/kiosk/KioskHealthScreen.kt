package com.tunisianprayertimes.tv.ui.kiosk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.time.TunisTime
import com.tunisianprayertimes.tv.kiosk.AutoStartTier
import com.tunisianprayertimes.tv.kiosk.BootTiming
import com.tunisianprayertimes.tv.kiosk.KioskAccessibility
import com.tunisianprayertimes.tv.kiosk.KioskController
import com.tunisianprayertimes.tv.kiosk.EventEntry
import com.tunisianprayertimes.tv.kiosk.KioskEvent
import com.tunisianprayertimes.tv.kiosk.KioskReport
import com.tunisianprayertimes.tv.kiosk.PowerLevel
import com.tunisianprayertimes.tv.kiosk.SleepGap
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.FocusableListItem
import com.tunisianprayertimes.tv.ui.common.focusRing
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.common.rtl
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

enum class HealthLevel { GOOD, WARNING, BAD, INFO }

/** One line of the kiosk page: its state, and how to fix it when it is not good ([command] for adb). */
data class HealthRow(val level: HealthLevel, val text: String, val fix: String? = null, val command: String? = null)

/** The page's rows, in Arabic; everything works without a network. */
/** [quickStartSettling]: quick start was switched on a moment ago and the system is still binding it. */
fun healthRows(report: KioskReport, zone: ZoneId = TunisTime.ZONE, quickStartSettling: Boolean = false): List<HealthRow> {
    val pkg = report.packageName
    val rows = mutableListOf<HealthRow>()
    val fireTv = report.autoStart.fireTv
    // "pm grant" of a permission this build does not declare fails: the Play build gets the overlay grant only.
    val adbSetup = if (report.quickStartAvailable) KioskAccessibility.adbSetup(pkg).joinToString("\n")
    else "adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow"
    rows += when (report.autoStart.tier) {
        AutoStartTier.HOME -> HealthRow(HealthLevel.GOOD, "التطبيق هو الشاشة الرئيسية للجهاز: يظهر عند التشغيل وعند زر الرئيسية")
        AutoStartTier.DEVICE_OWNER -> HealthRow(HealthLevel.GOOD, "التطبيق مالك الجهاز: الشاشة مثبّتة عليه")
        AutoStartTier.ACCESSIBILITY -> if (report.quickStartRunning || quickStartSettling) {
            HealthRow(HealthLevel.GOOD, "البدء السريع مفعّل: تظهر الشاشة فور تشغيل الجهاز، وتعود إذا ظهرت الشاشة الرئيسية للجهاز")
        } else if (report.autoStart.canBringToFront) {
            // "Display over other apps" (or Android 8/9) still lets the display come back by itself.
            HealthRow(
                HealthLevel.WARNING, "البدء السريع مفعّل لكنه لا يعمل الآن",
                fix = "أعد تشغيل الجهاز. في الأثناء تعود الشاشة وحدها، لكن أبطأ",
            )
        } else {
            HealthRow(
                HealthLevel.BAD, "البدء السريع مفعّل لكنه لا يعمل الآن",
                fix = "أعد تشغيل الجهاز. إن بقي كذلك فنظام هذا الجهاز لا يشغّله: يكفي إذن «الظهور فوق التطبيقات»",
                command = "adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow",
            )
        }
        AutoStartTier.OVERLAY -> HealthRow(
            HealthLevel.GOOD,
            if (fireTv) "إذن الظهور فوق التطبيقات ممنوح: يبدأ التطبيق وحده بعد التشغيل، وقد يتأخر قليلًا"
            else "إذن الظهور فوق التطبيقات ممنوح: يعود التطبيق وحده بعد التشغيل",
        )
        AutoStartTier.LEGACY -> HealthRow(
            HealthLevel.GOOD,
            if (fireTv) "Fire OS 7: يبدأ التطبيق وحده بعد التشغيل، بعد ظهور شاشة Amazon الرئيسية بقليل"
            else "أندرويد 8 أو 9: يبدأ التطبيق وحده بعد التشغيل",
        )
        AutoStartTier.NONE -> if (fireTv) HealthRow(
            HealthLevel.BAD,
            "على هذا الـ Fire TV لا يبدأ التطبيق وحده بعد التشغيل حتى يُمنح إذنًا مرة واحدة من حاسوب",
            fix = "في Fire TV: الإعدادات ← My Fire TV‏ ← Developer options‏ ← ADB debugging (تظهر بالضغط 7 مرات على اسم الجهاز في About)، " +
                if (report.quickStartAvailable) "ثم من حاسوب على الشبكة نفسها نفّذ الأمرين، ثم اضغط «تفعيل البدء السريع» على التلفاز"
                else "ثم من حاسوب على الشبكة نفسها نفّذ الأمر. نسخة GitHub من التطبيق هي المعدّة لـ Fire TV (البدء السريع فيها)",
            command = adbSetup,
        ) else HealthRow(
            HealthLevel.BAD,
            "لا يستطيع التطبيق البدء وحده بعد إعادة تشغيل الجهاز",
            fix = "امنح إذن «الظهور فوق التطبيقات» أو اجعل التطبيق الشاشة الرئيسية",
            command = "adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow",
        )
    }
    if (report.otherBuildInstalled) rows += HealthRow(HealthLevel.BAD, TvStrings.OTHER_BUILD_INSTALLED, fix = TvStrings.OTHER_BUILD_FIX)
    // The quick-start service (GitHub build): the fastest start, and the only way back from Fire TV's own home.
    if (report.quickStartAvailable && !report.quickStartEnabled && report.autoStart.tier != AutoStartTier.NONE &&
        report.autoStart.tier != AutoStartTier.HOME
    ) {
        rows += if (report.canWriteSecureSettings) {
            HealthRow(HealthLevel.INFO, "البدء السريع غير مفعّل: فعّله لتظهر الشاشة أسرع بعد التشغيل وتعود بعد زر الرئيسية", fix = "اضغط «تفعيل البدء السريع» على التلفاز")
        } else {
            HealthRow(
                HealthLevel.INFO, "البدء السريع غير مفعّل: تظهر الشاشة أسرع بعد التشغيل وتعود بعد زر الرئيسية",
                fix = "امنح الإذن مرة واحدة من حاسوب، ثم اضغط «تفعيل البدء السريع» على التلفاز",
                command = "adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
            )
        }
    }
    report.fireTvSleepMillis?.takeIf { it > 0 }?.let { sleep ->
        rows += HealthRow(
            HealthLevel.WARNING, "ينام Fire TV بعد ${TvStrings.minutes((sleep / 60_000).toInt())} دون ضغط زر إن لم تكن الشاشة ظاهرة",
            fix = if (report.canWriteSecureSettings) "اضغط «إيقاف نوم Fire TV» على التلفاز" else null,
            command = "adb shell settings put secure ${KioskAccessibility.FIRE_TV_SLEEP} 0",
        )
    }
    if (fireTv) {
        rows += HealthRow(HealthLevel.INFO, "في Fire TV أوقف «Still Watching» (Settings‏ ← Preferences‏ ← Data Monitoring) لكيلا ينام بعد 4 ساعات")
        rows += HealthRow(
            HealthLevel.INFO,
            "أوقف التشغيل التلقائي للفيديو والصوت في شاشة Amazon (Settings‏ ← Preferences‏ ← Featured Content) لكيلا يُسمع إعلان عند تشغيل الجهاز",
        )
        rows += HealthRow(
            HealthLevel.INFO, "اضبط شاشة التوقف على Never (Settings‏ ← Display & Sounds‏ ← Screensaver‏ ← Start Time)",
            command = "adb shell settings put system screen_off_timeout 2147460000",
        )
        rows += HealthRow(
            HealthLevel.INFO,
            "استعمل ملفًا شخصيًا واحدًا دون رمز PIN، واترك جهاز التحكم قرب التلفاز، ولا توقف التطبيق قسرًا (Force stop): يوقف البدء التلقائي حتى يُفتح التطبيق باليد",
        )
    }

    if (report.homeModeEnabled && !report.isDefaultHome) {
        // Many TV boxes never show the Home chooser: adb then sets it (the Home role on Android 10+).
        rows += HealthRow(
            HealthLevel.WARNING, "وضع الشاشة الرئيسية مفعّل لكن التطبيق لم يُختر شاشةً رئيسية",
            fix = "اختر التطبيق في نافذة اختيار الشاشة الرئيسية، وإن لم تظهر فنفّذ الأمر من حاسوب",
            command = "adb shell cmd package set-home-activity $pkg/${KioskController.HOME_ALIAS}",
        )
    }
    rows += when (report.power.attentiveTimeout) {
        PowerLevel.OK -> HealthRow(HealthLevel.GOOD, "توفير الطاقة لا يطفئ الجهاز")
        PowerLevel.WARNING -> HealthRow(
            HealthLevel.WARNING,
            report.power.attentiveTimeoutMillis?.let { "توفير الطاقة يطفئ الجهاز بعد ${TvStrings.hoursAndMinutes(it / 60_000)} دون استعمال" }
                ?: "توفير الطاقة قد يطفئ الجهاز: تعذّرت قراءة مدّته على هذا الجهاز",
            fix = "اضبط «توفير الطاقة» على «أبدًا» من إعدادات الجهاز",
            command = "adb shell settings put secure attentive_timeout -1",
        )
        PowerLevel.UNKNOWN -> HealthRow(HealthLevel.INFO, "توفير الطاقة: تعذّرت القراءة على هذا الجهاز")
    }
    rows += when (report.power.stayAwake) {
        PowerLevel.OK -> HealthRow(HealthLevel.GOOD, "«البقاء متيقظًا» مفعّل")
        PowerLevel.WARNING -> HealthRow(
            HealthLevel.WARNING, "«البقاء متيقظًا» غير مفعّل (خيارات المطوّر)",
            command = "adb shell settings put global stay_on_while_plugged_in 7",
        )
        PowerLevel.UNKNOWN -> HealthRow(HealthLevel.INFO, "«البقاء متيقظًا»: تعذّرت القراءة")
    }
    report.lastAutoStart?.let {
        // How many seconds it took, when the timing is of the same boot.
        val seconds = report.lastBootTiming?.detail
            ?.takeIf { timing -> BootTiming.bootOf(timing) != null && BootTiming.bootOf(timing) == BootTiming.bootOf(it.detail) }
            ?.let(BootTiming::screenSeconds)
            ?.let { s -> " بعد ${TvStrings.seconds(s.roundToInt())}" }.orEmpty()
        rows += if (it.type == KioskEvent.AUTOSTART_OK) HealthRow(HealthLevel.GOOD, "آخر تشغيل للجهاز: ظهرت الشاشة وحدها$seconds (${rowTime(it.atMillis, zone)})")
        // With nothing granted the tier row above already says what to run; otherwise something else was in the way.
        else HealthRow(
            HealthLevel.BAD, "آخر تشغيل للجهاز: لم تظهر الشاشة وحدها (${rowTime(it.atMillis, zone)})",
            fix = if (report.autoStart.tier == AutoStartTier.NONE) null
            else "تحقّق أن التطبيق فُتح مرة بعد تثبيته ولم يُوقف قسرًا (Force stop)، وأن لا شاشة أخرى تغطيه عند التشغيل " +
                "(اختيار ملف شخصي، البحث عن جهاز التحكم)",
        )
    }
    report.sleepGaps.mapNotNull { SleepGap.parse(it.detail) }.forEach {
        rows += HealthRow(HealthLevel.WARNING, sleepText(it, zone), fix = "تحقّق من توفير الطاقة ومن مؤقّت إطفاء التلفاز")
    }
    report.lastCrash?.let { rows += HealthRow(HealthLevel.WARNING, "آخر توقف مفاجئ: ${rowTime(it.atMillis, zone)}", fix = TvStrings.leftToRight(it.detail.take(160))) }
    if (report.safeMode) rows += HealthRow(HealthLevel.BAD, "الوضع الآمن: توقّف التطبيق عدة مرات، فعُطّلت الخلفيات والإعلانات مؤقتًا")
    rows += HealthRow(HealthLevel.INFO, "مدة التشغيل: ${TvStrings.hoursAndMinutes(report.uptimeMillis / 60_000)} · الإصدار ${report.versionName}")
    return rows
}

/**
 * «نام الجهاز يوم 29 سبتمبر 2026 من 03:00 إلى 04:00 (ساعة)», in Tunisia's time like the rest of the
 * page, and in words, so right-to-left layout cannot turn an arrow or a date around.
 */
internal fun sleepText(gap: SleepGap, zone: ZoneId): String {
    val from = Instant.ofEpochMilli(gap.fromWall).atZone(zone)
    val to = Instant.ofEpochMilli(gap.toWall).atZone(zone)
    val slept = TvStrings.hoursAndMinutes(gap.sleptMillis / 60_000)
    return if (from.toLocalDate() == to.toLocalDate()) {
        "نام الجهاز يوم ${TvStrings.gregorianDate(from.toLocalDate(), withWeekday = false)} " +
            "من ${TvStrings.hm(from.toLocalTime())} إلى ${TvStrings.hm(to.toLocalTime())} ($slept)"
    } else {
        "نام الجهاز من ${rowTime(gap.fromWall, zone)} إلى ${rowTime(gap.toWall, zone)} ($slept)"
    }
}

/** «29 سبتمبر 2026 03:00»: a date an Arabic line cannot reverse, unlike 2026-09-29. */
internal fun rowTime(millis: Long, zone: ZoneId): String {
    val at = Instant.ofEpochMilli(millis).atZone(zone)
    return "${TvStrings.gregorianDate(at.toLocalDate(), withWeekday = false)} ${TvStrings.hm(at.toLocalTime())}"
}

/** The event list is left to right and technical; a sleep's raw times are written as clock times there too. */
private fun eventDetail(event: EventEntry, zone: ZoneId): String {
    val gap = SleepGap.parse(event.detail).takeIf { event.type == KioskEvent.SLEEP_GAP } ?: return event.detail
    val format = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
    fun at(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).format(format)
    return "${at(gap.fromWall)} -> ${at(gap.toWall)} (${gap.sleptMillis / 60_000} min)"
}

private fun time(entry: EventEntry, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).format(Instant.ofEpochMilli(entry.atMillis).atZone(zone))

/** The green of a row that is fine: calm, and apart from the gold of a warning. */
private val HealthGood = Color(0xFF6FBF8E)

/** Each level's colour: its dot on the kiosk page and in the settings preview. */
val HealthLevel.color: Color
    get() = when (this) {
        HealthLevel.GOOD -> HealthGood
        HealthLevel.WARNING -> Midad.Gold
        HealthLevel.BAD -> Midad.Alert
        HealthLevel.INFO -> Midad.Muted
    }

/** The worst first, so a short list (the settings preview) shows what needs the admin. */
fun List<HealthRow>.worstFirst(): List<HealthRow> = sortedBy {
    when (it.level) {
        HealthLevel.BAD -> 0
        HealthLevel.WARNING -> 1
        HealthLevel.INFO -> 2
        HealthLevel.GOOD -> 3
    }
}

/** One line over the rows: «كل شيء جاهز», or how many problems and warnings. */
fun healthSummary(rows: List<HealthRow>): String {
    val bad = rows.count { it.level == HealthLevel.BAD }
    val warnings = rows.count { it.level == HealthLevel.WARNING }
    if (bad == 0 && warnings == 0) return TvStrings.KIOSK_ALL_GOOD
    return listOfNotNull(
        TvStrings.problems(bad).takeIf { bad > 0 },
        TvStrings.warnings(warnings).takeIf { warnings > 0 },
    ).joinToString(" · ")
}

/** A level's dot, drawn so it looks the same on every box whatever its fonts. */
@Composable
fun HealthDot(level: HealthLevel, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Canvas(modifier.size(size)) { drawCircle(level.color) }
}

/** The kiosk page's actions. Each keeps its identity while its label changes (quick start on or off). */
internal enum class KioskAction { GRANT_OVERLAY, QUICK_START, FIRE_TV_SLEEP, HOME_MODE, ALLOW_UPDATES, INSTALL_UPDATE, LEAVE_DEVICE_OWNER, BACK }

/**
 * The action that had the focus has left the list (the overlay permission granted, Fire TV's sleep
 * turned off): the focus then goes to «رجوع», never onto the action that took its place, where the
 * next OK would open the Home chooser or install an update.
 */
internal fun focusedActionGone(focused: KioskAction?, actions: List<KioskAction>): Boolean =
    focused != null && focused !in actions

private class ActionItem(val id: KioskAction, val text: String, val onClick: () -> Unit)

/** What the page keeps of the focus, outside the snapshot so that moving it recomposes nothing. */
private class ActionFocus(var first: KioskAction?) {
    /** The action with the focus; null once the admin has moved into the rows. */
    var current: KioskAction? = null
}

private const val BACK_FOCUS_ATTEMPTS = 3

/**
 * The kiosk page: can the box start the app by itself, will it fall asleep, has the app crashed. The
 * actions on the right as in the settings menu, the rows in a panel on the left, each with its fix
 * and, for a computer, its adb command.
 */
@Composable
fun KioskHealthScreen(
    report: KioskReport,
    onGrantOverlay: (() -> Unit)?,
    onToggleHomeMode: () -> Unit,
    onBack: () -> Unit,
    extraRows: List<HealthRow> = emptyList(),
    /** The GitHub build, when the box does not yet let it install its updates. */
    onAllowUpdates: (() -> Unit)? = null,
    /** The GitHub build, when an update is ready: installs it now (the system may ask to confirm). */
    onInstallUpdate: (() -> Unit)? = null,
    /** The GitHub build, when the app may switch its quick-start service on or off. */
    onToggleQuickStart: (() -> Unit)? = null,
    /** Fire TV, when its sleep timer is on and the app may turn it off. */
    onDisableFireTvSleep: (() -> Unit)? = null,
    quickStartSettling: Boolean = false,
    /** Device-owner boxes: gives the mode up, so the app can be uninstalled or the other build installed. */
    onLeaveDeviceOwner: (() -> Unit)? = null,
) {
    val rows = remember(report, extraRows, quickStartSettling) { healthRows(report, quickStartSettling = quickStartSettling) + extraRows }
    // Cannot be undone without a factory reset: the first press only asks for a second.
    var leaveArmed by remember { mutableStateOf(false) }
    val actions = listOfNotNull(
        onGrantOverlay?.takeIf { !report.canDrawOverlays }?.let { ActionItem(KioskAction.GRANT_OVERLAY, TvStrings.GRANT_OVERLAY, it) },
        onToggleQuickStart?.let {
            ActionItem(KioskAction.QUICK_START, if (report.quickStartEnabled) TvStrings.QUICK_START_OFF else TvStrings.QUICK_START_ON, it)
        },
        onDisableFireTvSleep?.let { ActionItem(KioskAction.FIRE_TV_SLEEP, TvStrings.FIRE_TV_SLEEP_OFF, it) },
        // Fire OS puts its own home back: offering the app as home there would only mislead.
        if (report.autoStart.fireTv) null
        else ActionItem(KioskAction.HOME_MODE, if (report.homeModeEnabled) TvStrings.HOME_MODE_OFF else TvStrings.HOME_MODE_ON, onToggleHomeMode),
        onAllowUpdates?.let { ActionItem(KioskAction.ALLOW_UPDATES, TvStrings.ALLOW_UPDATES, it) },
        onInstallUpdate?.let { ActionItem(KioskAction.INSTALL_UPDATE, TvStrings.INSTALL_UPDATE, it) },
        onLeaveDeviceOwner?.takeIf { report.isDeviceOwner }?.let { leave ->
            ActionItem(KioskAction.LEAVE_DEVICE_OWNER, if (leaveArmed) TvStrings.LEAVE_DEVICE_OWNER_CONFIRM else TvStrings.LEAVE_DEVICE_OWNER) {
                if (leaveArmed) leave() else leaveArmed = true
            }
        },
        ActionItem(KioskAction.BACK, TvStrings.BACK, onBack),
    )
    val ids = actions.map { it.id }
    // The first action has the focus: after onboarding, it is what the installer came for. Once it
    // has left the list, it does not take the focus again if it comes back.
    val focus = remember { ActionFocus(first = ids.first()) }
    if (focus.first !in ids) focus.first = null
    // Read while the focused action still holds the focus: the list composed now may have dropped it.
    val focusedBefore = focus.current
    val back = remember { FocusRequester() }
    LaunchedEffect(ids) {
        if (!focusedActionGone(focusedBefore, ids)) return@LaunchedEffect
        repeat(BACK_FOCUS_ATTEMPTS) {
            if (runCatching { back.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(TvStrings.SETTINGS_KIOSK, style = midadStyle(26.sp, FontWeight.SemiBold))
            Text(healthSummary(rows), style = midadStyle(14.sp, color = Midad.Muted), modifier = Modifier.padding(bottom = 9.dp))
            actions.forEach { action ->
                // By identity: an action that leaves takes its focus node with it, rather than
                // handing it, and the next OK, to the action that moves into its place.
                key(action.id) {
                    FocusableListItem(
                        text = action.text,
                        onClick = action.onClick,
                        modifier = Modifier
                            .onFocusChanged { if (it.isFocused) focus.current = action.id }
                            .then(if (action.id == KioskAction.BACK) Modifier.focusRequester(back) else Modifier)
                            .initialFocus(action.id == focus.first),
                    )
                }
            }
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .onFocusChanged { if (it.hasFocus) focus.current = null }
                .background(Midad.Surface, RoundedCornerShape(14.dp)),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows) { row -> HealthRowItem(row) }
            item {
                Text(
                    TvStrings.KIOSK_EVENTS,
                    style = midadStyle(17.sp, FontWeight.SemiBold),
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            items(report.events) { event -> EventLine(event) }
        }
    }
}

/**
 * A row of the page: the level's dot, the state, the fix under it, the command in a box of its own.
 * Focusable only so the remote can scroll the panel; the ring shows where it is.
 */
@Composable
private fun HealthRowItem(row: HealthRow) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(focused, radius = 8.dp)
            .background(Midad.SurfaceRaised, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HealthDot(row.level, Modifier.padding(top = 6.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // The texts carry a right-to-left mark after each Latin label ("My Fire TV" then an arrow),
            // which only works in a right-to-left paragraph, even when the line opens in Latin.
            Text(row.text, style = midadStyle(16.sp, lineHeight = 1.45f).rtl())
            row.fix?.let { Text(it, style = midadStyle(14.sp, color = Midad.Muted, lineHeight = 1.45f).rtl()) }
            row.command?.let { Command(it) }
        }
    }
}

/** An adb command, left to right as it is typed, in a box that sets it apart from the Arabic. */
@Composable
private fun Command(command: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Midad.Ground, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            command,
            style = midadStyle(13.sp, color = Midad.Muted, family = FontFamily.Monospace)
                .copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Left),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** An entry of the event log, as the log writes it; focusable so the remote can scroll to the oldest. */
@Composable
private fun EventLine(event: EventEntry) {
    var focused by remember { mutableStateOf(false) }
    Text(
        "${time(event, TunisTime.ZONE)}  ${event.type}  ${eventDetail(event, TunisTime.ZONE).take(120)}",
        style = midadStyle(13.sp, color = Midad.Dim).copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Left),
        modifier = Modifier
            .fillMaxWidth()
            .focusRing(focused, radius = 6.dp)
            .background(if (focused) Midad.SurfaceRaised else Color.Transparent, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
