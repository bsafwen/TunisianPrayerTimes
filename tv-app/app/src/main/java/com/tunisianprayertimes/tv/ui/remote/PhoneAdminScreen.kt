package com.tunisianprayertimes.tv.ui.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.Digits
import com.tunisianprayertimes.tv.ui.common.FocusableListItem
import com.tunisianprayertimes.tv.ui.common.adminPanel
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.common.rtl
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import kotlin.math.floor

/**
 * A running phone-management session: the address the QR code holds, and the TV's other addresses
 * ([otherUrls]) for a box on two networks (Ethernet and Wi-Fi, or Wi-Fi Direct for screen casting).
 */
data class PhoneAdminSession(val url: String?, val port: Int, val otherUrls: List<String> = emptyList()) {
    companion object {
        /**
         * The session at [port] with [token] on the TV's addresses now: the one of the network the box
         * uses ([active], when it is among the local [addresses]) in the code, the others under it. Read
         * again while the session runs, so joining the phone's hotspot afterwards shows the code.
         */
        fun of(port: Int, token: String, active: String?, addresses: List<String>): PhoneAdminSession {
            val ordered = (listOfNotNull(active?.takeIf { it in addresses }) + addresses).distinct()
            val urls = ordered.map { "http://$it:$port/?t=$token" }
            return PhoneAdminSession(urls.firstOrNull(), port, urls.drop(1))
        }
    }
}

/**
 * Manage the screen from a phone or laptop on the same network, with no internet: the mosque's Wi-Fi,
 * or the admin's phone hotspot that the TV joins once. The phone scans the QR code and opens the
 * dashboard. The actions on the right as in the settings menu; on the left, the code on an ivory
 * card with its address, or how to get the TV and the phone on one network.
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
    val wifi = onOpenWifiSettings?.let { TvStrings.WIFI_SETTINGS to it }
    val actions = when {
        session == null -> listOfNotNull(TvStrings.PHONE_START to onStart, wifi, TvStrings.BACK to onBack)
        session.url == null -> listOfNotNull(wifi, TvStrings.STOP to onStop, TvStrings.BACK to onBack)
        else -> listOf(TvStrings.PHONE_STOP to onStop, TvStrings.BACK to onBack)
    }
    val labels = actions.map { it.first }
    // The first action has the focus when the page opens; once it has left the list, never again.
    val focus = remember { ActionFocus(first = labels.first()) }
    if (focus.first !in labels) focus.first = null
    // Read while the focused action still holds the focus: the list composed now may have dropped it.
    val focusedBefore = focus.current
    val back = remember { FocusRequester() }
    LaunchedEffect(labels) {
        if (focusedBefore == null || focusedBefore in labels) return@LaunchedEffect
        repeat(BACK_FOCUS_ATTEMPTS) {
            if (runCatching { back.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(TvStrings.SETTINGS_PHONE, style = midadStyle(26.sp, FontWeight.SemiBold))
            Text(TvStrings.PHONE_SUBTITLE, style = midadStyle(14.sp, color = Midad.Muted), modifier = Modifier.padding(bottom = 9.dp))
            actions.forEach { (text, action) ->
                // By label, as on the kiosk page: «بدء الجلسة» leaves with its focus, which goes to «رجوع»,
                // rather than handing it to «إيقاف الجلسة» where a second OK (or a bouncing remote) would stop the session.
                key(text) {
                    FocusableListItem(
                        text = text,
                        onClick = action,
                        modifier = Modifier
                            .onFocusChanged { if (it.isFocused) focus.current = text }
                            .then(if (text == TvStrings.BACK) Modifier.focusRequester(back) else Modifier)
                            .initialFocus(text == focus.first),
                    )
                }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .adminPanel()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                session == null -> {
                    Paragraph(TvStrings.PHONE_INTRO)
                    HotspotSteps()
                }
                session.url == null -> {
                    Text(TvStrings.PHONE_NO_NETWORK, style = midadStyle(18.sp, FontWeight.Medium, Midad.Alert, lineHeight = 1.45f))
                    HotspotSteps()
                }
                else -> SessionCode(session.url, session.otherUrls)
            }
        }
    }
}

/** The open session: the code to scan on its card, the steps beside it, the address under both. */
@Composable
private fun ColumnScope.SessionCode(url: String, otherUrls: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        // Dark modules on ivory: the contrast a phone camera needs, without a white square glaring on the wall.
        Box(Modifier.background(Midad.Text, RoundedCornerShape(12.dp)).padding(10.dp)) {
            QrCode(url, Modifier.size(200.dp), background = Midad.Text, modules = Midad.OnGold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberedSteps(TvStrings.PHONE_SCAN_STEPS)
        }
    }
    // Typed by hand on a laptop: left to right, each digit in its own cell so the address reads clearly.
    Digits(url, style = midadStyle(16.sp, FontWeight.Medium), modifier = Modifier.align(Alignment.CenterHorizontally))
    Paragraph(TvStrings.PHONE_WRONG_NETWORK, color = Midad.Muted, size = 14)
    if (otherUrls.isNotEmpty()) {
        Paragraph(TvStrings.PHONE_OTHER_ADDRESSES, color = Midad.Muted, size = 14)
        otherUrls.forEach { Digits(it, style = midadStyle(14.sp), modifier = Modifier.align(Alignment.CenterHorizontally)) }
    }
    Paragraph(TvStrings.PHONE_WARNING, color = Midad.Muted, size = 14)
}

/** How to manage a TV in a mosque without Wi-Fi: the admin's phone hotspot, joined once. */
@Composable
private fun HotspotSteps() {
    Text(TvStrings.HOTSPOT_TITLE, style = midadStyle(17.sp, FontWeight.SemiBold).rtl(), modifier = Modifier.padding(top = 4.dp))
    NumberedSteps(TvStrings.HOTSPOT_STEPS)
    Paragraph(TvStrings.HOTSPOT_NOTE, color = Midad.Muted, size = 14)
}

/** Steps with their numbers in small discs, so the order reads at a glance from across the room. */
@Composable
private fun NumberedSteps(steps: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(22.dp).background(Midad.SurfaceRaised, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", style = midadStyle(13.sp, FontWeight.Medium))
                }
                Text(step, style = midadStyle(15.sp, lineHeight = 1.45f).rtl(), modifier = Modifier.weight(1f))
            }
        }
    }
}

/** What the page keeps of the focus, outside the snapshot so that moving it recomposes nothing. */
private class ActionFocus(var first: String?) {
    /** The label of the action with the focus. */
    var current: String? = null
}

private const val BACK_FOCUS_ATTEMPTS = 3

@Composable
private fun Paragraph(text: String, color: Color = Midad.Text, size: Int = 16) {
    Text(text, style = midadStyle(size.sp, color = color, lineHeight = 1.5f).rtl(), modifier = Modifier.fillMaxWidth())
}

/**
 * A QR code drawn from its modules with a quiet zone, readable from a phone at arm's length:
 * [modules] on [background], black on white unless told otherwise.
 */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, background: Color = Color.White, modules: Color = Color.Black) {
    val matrix = remember(content) {
        QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2),
        )
    }
    Canvas(modifier.background(background)) {
        // Whole pixels per module: fractional ones leave faint seams that a camera can misread.
        val cell = floor(minOf(size.width / matrix.width, size.height / matrix.height)).coerceAtLeast(1f)
        val left = floor((size.width - cell * matrix.width) / 2f)
        val top = floor((size.height - cell * matrix.height) / 2f)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(modules, Offset(left + x * cell, top + y * cell), Size(cell, cell))
            }
        }
    }
}
