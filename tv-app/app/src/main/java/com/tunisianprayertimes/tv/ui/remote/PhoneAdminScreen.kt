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
import com.tunisianprayertimes.tv.ui.setup.FocusableListItem
import com.tunisianprayertimes.tv.ui.theme.Gold

/** A running phone-management session: the address the QR code holds. */
data class PhoneAdminSession(val url: String?, val port: Int)

/**
 * Manage the screen from a phone on the same Wi-Fi (or on the phone's own hotspot), with no internet:
 * the phone scans the QR code and edits the same settings file as the USB key. Minimal; to be redesigned.
 */
@Composable
fun PhoneAdminScreen(session: PhoneAdminSession?, onStart: () -> Unit, onStop: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("الإدارة من الهاتف", color = Gold, fontSize = 26.sp)
        if (session == null) {
            Text(
                "صِل الهاتف بشبكة الشاشة نفسها (أو صِل الشاشة بنقطة اتصال الهاتف)، ثم ابدأ الجلسة وامسح الرمز بالهاتف. " +
                    "لا حاجة إلى الإنترنت، وتنتهي الجلسة وحدها بعد ربع ساعة دون استعمال.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp,
            )
            Row(Modifier.fillMaxWidth(0.6f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) { FocusableListItem(text = "بدء الجلسة", onClick = onStart, modifier = Modifier.initialFocus()) }
                Box(Modifier.weight(1f)) { FocusableListItem(text = "رجوع", onClick = onBack) }
            }
        } else if (session.url == null) {
            Text("الشاشة غير متصلة بأي شبكة: صِلها بشبكة Wi-Fi أو بنقطة اتصال الهاتف", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp)
            Row(Modifier.fillMaxWidth(0.6f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) { FocusableListItem(text = "إيقاف", onClick = onStop, modifier = Modifier.initialFocus()) }
                Box(Modifier.weight(1f)) { FocusableListItem(text = "رجوع", onClick = onBack) }
            }
        } else {
            QrCode(session.url, Modifier.size(220.dp))
            Text(session.url, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp)
            Text("من يرى هذا الرمز يستطيع تغيير إعدادات الشاشة: أوقف الجلسة عند الانتهاء", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(0.6f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) { FocusableListItem(text = "إيقاف الجلسة", onClick = onStop, modifier = Modifier.initialFocus()) }
                Box(Modifier.weight(1f)) { FocusableListItem(text = "رجوع", onClick = onBack) }
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
