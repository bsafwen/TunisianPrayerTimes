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
import com.tunisianprayertimes.tv.kiosk.EventEntry
import com.tunisianprayertimes.tv.kiosk.KioskEvent
import com.tunisianprayertimes.tv.kiosk.KioskReport
import com.tunisianprayertimes.tv.kiosk.PowerLevel
import com.tunisianprayertimes.tv.ui.setup.FocusableListItem
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
fun healthRows(report: KioskReport, zone: ZoneId = ZoneId.systemDefault()): List<HealthRow> {
    val pkg = report.packageName
    val rows = mutableListOf<HealthRow>()
    rows += when (report.autoStart.tier) {
        AutoStartTier.HOME -> HealthRow(HealthLevel.GOOD, "التطبيق هو الشاشة الرئيسية للجهاز: يظهر عند التشغيل وعند زر الرئيسية")
        AutoStartTier.DEVICE_OWNER -> HealthRow(HealthLevel.GOOD, "التطبيق مالك الجهاز: الشاشة مثبّتة عليه")
        AutoStartTier.OVERLAY -> HealthRow(HealthLevel.GOOD, "إذن الظهور فوق التطبيقات ممنوح: يعود التطبيق وحده بعد التشغيل")
        AutoStartTier.LEGACY -> HealthRow(HealthLevel.GOOD, "أندرويد 8 أو 9: يبدأ التطبيق وحده بعد التشغيل")
        AutoStartTier.NONE -> HealthRow(
            HealthLevel.BAD,
            if (report.autoStart.unsupportedDevice) "أجهزة Fire TV لا تسمح ببدء التطبيق مع التشغيل: يُنصح بجهاز أندرويد TV آخر"
            else "لا يستطيع التطبيق البدء وحده بعد إعادة تشغيل الجهاز",
            fix = "امنح إذن «الظهور فوق التطبيقات» أو اجعل التطبيق الشاشة الرئيسية",
            command = "adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow",
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
        rows += if (it.type == KioskEvent.AUTOSTART_OK) HealthRow(HealthLevel.GOOD, "آخر تشغيل للجهاز: ظهرت الشاشة وحدها (${time(it, zone)})")
        else HealthRow(HealthLevel.BAD, "آخر تشغيل للجهاز: لم تظهر الشاشة وحدها (${time(it, zone)})", command = "adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow")
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
) {
    val rows = remember(report, extraRows) { healthRows(report) + extraRows }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("التشغيل الدائم للشاشة", color = Gold, fontSize = 26.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onGrantOverlay != null && !report.canDrawOverlays) {
                Box(Modifier.weight(1f)) { FocusableListItem(text = "منح إذن الظهور فوق التطبيقات", onClick = onGrantOverlay) }
            }
            Box(Modifier.weight(1f)) {
                FocusableListItem(text = if (report.homeModeEnabled) "إيقاف وضع الشاشة الرئيسية" else "جعل التطبيق الشاشة الرئيسية", onClick = onToggleHomeMode)
            }
            if (onAllowUpdates != null) {
                Box(Modifier.weight(1f)) { FocusableListItem(text = "السماح بتثبيت التحديثات", onClick = onAllowUpdates) }
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
