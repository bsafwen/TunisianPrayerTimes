package com.tunisianprayertimes.tv.ui.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.common.FocusableListItem
import com.tunisianprayertimes.tv.ui.theme.Gold

/** A running phone-management session: the address the QR code holds. */
data class PhoneAdminSession(val url: String?, val port: Int)

/**
 * Manage the screen from a phone or laptop on the same network, with no internet: the mosque's Wi-Fi,
 * or the admin's phone hotspot that the TV joins once. The phone scans the QR code and opens the
 * dashboard. Minimal; to be redesigned.
 */
@Composable
fun PhoneAdminScreen(
    session: PhoneAdminSession?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    /** Opens the TV's Wi-Fi settings (to join the phone's hotspot); null where the box has no such page. */
    onOpenWifiSettings: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("لوحة الإدارة من الهاتف أو الحاسوب", color = Gold, fontSize = 26.sp)
        when {
            session == null -> {
                Text(
                    "الهاتف والشاشة على الشبكة نفسها، ولا حاجة إلى الإنترنت. ابدأ الجلسة وامسح الرمز بالهاتف: تُفتح لوحة فيها " +
                        "أوقات اليوم والإقامة والتواريخ والإعلانات والصور والأذكار. تنتهي الجلسة وحدها بعد ربع ساعة دون استعمال.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp,
                )
                HotspotSteps()
                Buttons(
                    "بدء الجلسة" to onStart,
                    onOpenWifiSettings?.let { "إعدادات Wi-Fi" to it },
                    "رجوع" to onBack,
                )
            }
            session.url == null -> {
                Text("الشاشة غير متصلة بأي شبكة: صِلها بشبكة المسجد أو بنقطة اتصال الهاتف", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp)
                HotspotSteps()
                Buttons(
                    onOpenWifiSettings?.let { "إعدادات Wi-Fi" to it },
                    "إيقاف" to onStop,
                    "رجوع" to onBack,
                )
            }
            else -> {
                QrCode(session.url, Modifier.size(220.dp))
                Text(session.url, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp)
                Text(
                    "إن لم تُفتح اللوحة، فالهاتف على شبكة غير شبكة الشاشة. من يرى هذا الرمز يستطيع تغيير إعدادات الشاشة: أوقف الجلسة عند الانتهاء",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                )
                Buttons("إيقاف الجلسة" to onStop, "رجوع" to onBack)
            }
        }
    }
}

/** How to manage a TV in a mosque without Wi-Fi: the admin's phone hotspot, joined once. */
@Composable
private fun HotspotSteps() {
    Column(Modifier.fillMaxWidth(0.85f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("دون Wi-Fi في المسجد: نقطة اتصال الهاتف (مرة واحدة)", color = Gold, fontSize = 17.sp)
        listOf(
            "١. في الهاتف: الإعدادات ← نقطة الاتصال ← تشغيل، باسم وكلمة سر سهلين.",
            "٢. هنا: «إعدادات Wi-Fi» ← اختر نقطة اتصال الهاتف واكتب كلمة السر، ثم ارجع إلى التطبيق.",
            "٣. في المرات القادمة يكفي تشغيل نقطة الاتصال: تتصل بها الشاشة وحدها.",
            "إن قال الهاتف إن الشبكة بلا إنترنت، فاختر البقاء متصلًا.",
        ).forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp) }
    }
}

@Composable
private fun Buttons(vararg buttons: Pair<String, () -> Unit>?) {
    Row(Modifier.fillMaxWidth(0.8f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        buttons.filterNotNull().forEachIndexed { index, (text, onClick) ->
            Box(Modifier.weight(1f)) {
                FocusableListItem(text = text, onClick = onClick, modifier = if (index == 0) Modifier.initialFocus() else Modifier)
            }
        }
    }
}

/** A QR code drawn from its modules, black on white with a quiet zone, readable from a phone at arm's length. */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2),
        )
    }
    Canvas(modifier.background(Color.White)) {
        val cell = minOf(size.width / matrix.width, size.height / matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
            }
        }
    }
}
