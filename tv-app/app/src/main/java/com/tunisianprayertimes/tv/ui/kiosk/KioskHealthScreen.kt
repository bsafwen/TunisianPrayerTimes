package com.tunisianprayertimes.tv.ui.kiosk

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.tv.kiosk.AutoStartTier
import com.tunisianprayertimes.tv.kiosk.BootTiming
import com.tunisianprayertimes.tv.kiosk.KioskAccessibility
import com.tunisianprayertimes.tv.kiosk.EventEntry
import com.tunisianprayertimes.tv.kiosk.KioskEvent
import com.tunisianprayertimes.tv.kiosk.KioskReport
import com.tunisianprayertimes.tv.kiosk.PowerLevel
import com.tunisianprayertimes.tv.ui.common.FocusableListItem
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.theme.Gold
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class HealthLevel { GOOD, WARNING, BAD, INFO }

/** One line of the kiosk page: its state, and how to fix it when it is not good ([command] for adb). */
data class HealthRow(val level: HealthLevel, val text: String, val fix: String? = null, val command: String? = null)

/** The page's rows, in Arabic; everything works without a network. */
/** [quickStartSettling]: quick start was switched on a moment ago and the system is still binding it. */
fun healthRows(report: KioskReport, zone: ZoneId = ZoneId.systemDefault(), quickStartSettling: Boolean = false): List<HealthRow> {
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
            HealthLevel.WARNING, "ينام Fire TV بعد ${sleep / 60_000} دقيقة دون ضغط زر إن لم تكن الشاشة ظاهرة",
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
        rows += HealthRow(HealthLevel.WARNING, "وضع الشاشة الرئيسية مفعّل لكن التطبيق لم يُختر شاشةً رئيسية", fix = "اختر التطبيق في نافذة اختيار الشاشة الرئيسية")
    }
    rows += when (report.power.attentiveTimeout) {
        PowerLevel.OK -> HealthRow(HealthLevel.GOOD, "توفير الطاقة لا يطفئ الجهاز")
        PowerLevel.WARNING -> HealthRow(
            HealthLevel.WARNING,
            "توفير الطاقة يطفئ الجهاز بعد ${(report.power.attentiveTimeoutMillis ?: 0) / 60_000} دقيقة دون استعمال",
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
            ?.let { s -> " بعد ${"%.0f".format(Locale.ROOT, s)} ثانية" }.orEmpty()
        rows += if (it.type == KioskEvent.AUTOSTART_OK) HealthRow(HealthLevel.GOOD, "آخر تشغيل للجهاز: ظهرت الشاشة وحدها$seconds (${time(it, zone)})")
        // With nothing granted the tier row above already says what to run; otherwise something else was in the way.
        else HealthRow(
            HealthLevel.BAD, "آخر تشغيل للجهاز: لم تظهر الشاشة وحدها (${time(it, zone)})",
            fix = if (report.autoStart.tier == AutoStartTier.NONE) null
            else "تحقّق أن التطبيق فُتح مرة بعد تثبيته ولم يُوقف قسرًا (Force stop)، وأن لا شاشة أخرى تغطيه عند التشغيل " +
                "(اختيار ملف شخصي، البحث عن جهاز التحكم)",
        )
    }
    report.sleepGaps.forEach { rows += HealthRow(HealthLevel.WARNING, "نام الجهاز: ${it.detail}", fix = "تحقّق من توفير الطاقة ومن مؤقّت إطفاء التلفاز") }
    report.lastCrash?.let { rows += HealthRow(HealthLevel.WARNING, "آخر توقف مفاجئ: ${time(it, zone)}", fix = it.detail.take(160)) }
    if (report.safeMode) rows += HealthRow(HealthLevel.BAD, "الوضع الآمن: توقّف التطبيق عدة مرات، فعُطّلت الخلفيات والإعلانات مؤقتًا")
    rows += HealthRow(HealthLevel.INFO, "مدة التشغيل: ${report.uptimeMillis / 3_600_000} ساعة ${report.uptimeMillis / 60_000 % 60} دقيقة · الإصدار ${report.versionName}")
    return rows
}

private fun time(entry: EventEntry, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).format(Instant.ofEpochMilli(entry.atMillis).atZone(zone))

/**
 * The kiosk page: can the box start the app by itself, will it fall asleep, has the app crashed.
 * Minimal on purpose; the page will be redesigned.
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
) {
    val rows = remember(report, extraRows, quickStartSettling) { healthRows(report, quickStartSettling = quickStartSettling) + extraRows }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("التشغيل الدائم للشاشة", color = Gold, fontSize = 26.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onGrantOverlay != null && !report.canDrawOverlays) {
                Box(Modifier.weight(1f)) { FocusableListItem(text = "منح إذن الظهور فوق التطبيقات", onClick = onGrantOverlay) }
            }
            if (onToggleQuickStart != null) {
                Box(Modifier.weight(1f)) {
                    FocusableListItem(text = if (report.quickStartEnabled) "إيقاف البدء السريع" else "تفعيل البدء السريع", onClick = onToggleQuickStart)
                }
            }
            if (onDisableFireTvSleep != null) {
                Box(Modifier.weight(1f)) { FocusableListItem(text = "إيقاف نوم Fire TV", onClick = onDisableFireTvSleep) }
            }
            // Fire OS puts its own home back: offering the app as home there would only mislead.
            if (!report.autoStart.fireTv) {
                Box(Modifier.weight(1f)) {
                    FocusableListItem(text = if (report.homeModeEnabled) "إيقاف وضع الشاشة الرئيسية" else "جعل التطبيق الشاشة الرئيسية", onClick = onToggleHomeMode)
                }
            }
            if (onAllowUpdates != null) {
                Box(Modifier.weight(1f)) { FocusableListItem(text = "السماح بتثبيت التحديثات", onClick = onAllowUpdates) }
            }
            if (onInstallUpdate != null) {
                Box(Modifier.weight(1f)) { FocusableListItem(text = "تثبيت التحديث الآن", onClick = onInstallUpdate) }
            }
            Box(Modifier.weight(1f)) { FocusableListItem(text = "رجوع", onClick = onBack, modifier = Modifier.initialFocus()) }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows) { row -> HealthRowItem(row) }
            item { Text("آخر الأحداث", color = Gold, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp)) }
            items(report.events) { event ->
                FocusableLine("${time(event, ZoneId.systemDefault())}  ${event.type}  ${event.detail.take(120)}", Color.Unspecified)
            }
        }
    }
}

@Composable
private fun HealthRowItem(row: HealthRow) {
    val color = when (row.level) {
        HealthLevel.GOOD -> Color(0xFF4CAF50)
        HealthLevel.WARNING -> Color(0xFFFFB300)
        HealthLevel.BAD -> Color(0xFFE53935)
        HealthLevel.INFO -> Color(0xFF90A4AE)
    }
    val text = listOfNotNull("● ${row.text}", row.fix, row.command).joinToString("\n")
    FocusableLine(text, color)
}

/** A focusable line, so the D-pad can scroll the page. */
@Composable
private fun FocusableLine(text: String, color: Color) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
        fontSize = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) MaterialTheme.colorScheme.surface else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
