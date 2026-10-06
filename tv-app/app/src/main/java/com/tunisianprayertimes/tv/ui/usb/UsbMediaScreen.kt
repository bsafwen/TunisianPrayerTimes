package com.tunisianprayertimes.tv.ui.usb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.tv.data.MediaKind
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.AdminPage
import com.tunisianprayertimes.tv.ui.common.FocusableListItem
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.common.rtl
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import com.tunisianprayertimes.tv.usb.RejectedFile
import com.tunisianprayertimes.tv.usb.UsbMediaCopy
import com.tunisianprayertimes.tv.usb.UsbMediaFound
import com.tunisianprayertimes.tv.usb.UsbScan

/**
 * Images (and announcement .txt files) found on a USB key: how many of each kind and how many of the
 * TV's own files each replaces, to copy to the TV or not, then the files left out and why. A key with
 * nothing to copy only says why. Back dismisses, as «إلغاء» does.
 */
@Composable
fun UsbMediaScreen(found: UsbMediaFound, onApply: () -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    AdminPage(
        title = if (found.isEmpty) TvStrings.USB_MEDIA_NONE_TITLE else TvStrings.USB_MEDIA_TITLE,
        modifier = Modifier.background(Midad.Ground).padding(horizontal = 48.dp, vertical = 27.dp),
        scroll = true,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Midad.Surface, RoundedCornerShape(14.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            found.images.forEach { (kind, files) ->
                if (files.isEmpty()) return@forEach
                val replaced = found.replaced[kind] ?: 0
                val count = TvStrings.filesCount(files.size) + if (replaced > 0) " · ${TvStrings.usbMediaReplaces(replaced)}" else ""
                KindRow(label(kind), count)
            }
            rejectedLines(found.rejected).forEach { line ->
                Text(line, style = midadStyle(17.sp, color = Midad.Alert, lineHeight = 1.4f).rtl())
            }
            if (!found.isEmpty) {
                Text(TvStrings.USB_MEDIA_HINT, style = midadStyle(17.sp, color = Midad.Muted, lineHeight = 1.4f).rtl(), modifier = Modifier.padding(top = 4.dp))
            }
        }
        DialogButtons {
            if (found.isEmpty) {
                FocusableListItem(TvStrings.USB_OK, onDismiss, Modifier.initialFocus().width(BUTTON_WIDTH))
            } else {
                FocusableListItem(TvStrings.USB_APPLY, onApply, Modifier.initialFocus().width(BUTTON_WIDTH))
                FocusableListItem(TvStrings.CANCEL, onDismiss, Modifier.width(BUTTON_WIDTH))
            }
        }
    }
}

/** The files left out, one line per reason. */
internal fun rejectedLines(rejected: List<RejectedFile>): List<String> =
    rejected.groupingBy { it.reason }.eachCount().toSortedMap().map { (reason, count) ->
        when (reason) {
            RejectedFile.Reason.FORMAT -> TvStrings.rejectedFormat(count)
            RejectedFile.Reason.TOO_LARGE -> TvStrings.rejectedTooLarge(count)
            RejectedFile.Reason.TOO_MANY -> TvStrings.rejectedTooMany(count)
            RejectedFile.Reason.TEXT -> TvStrings.rejectedText(count)
            RejectedFile.Reason.MISPLACED -> TvStrings.rejectedMisplaced(count)
        }
    }

/**
 * The answer to «قراءة مفتاح USB من جديد» when the scan found nothing to offer (no key, or a key with
 * nothing new on it): the other outcomes already say something (the offer itself, or their notice).
 */
fun readAgainNotice(scan: UsbScan, media: UsbMediaFound?): String? =
    TvStrings.USB_READ_NOTHING.takeIf { scan == UsbScan.Quiet && media == null }

/** What the wall says once a copy from a key has ended. */
fun copyNotice(copy: UsbMediaCopy): String = when (copy) {
    is UsbMediaCopy.Done -> TvStrings.usbCopied(copy.files, copy.unreadable)
    UsbMediaCopy.Failed -> TvStrings.USB_COPY_FAILED
    UsbMediaCopy.NoRoom -> TvStrings.USB_COPY_NO_ROOM
}

/** A kind of files on a raised row: its name, and how many the key holds at the end of the line. */
@Composable
private fun KindRow(label: String, count: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(Midad.SurfaceRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = midadStyle(17.sp, FontWeight.Medium).rtl())
        Text(count, style = midadStyle(17.sp, color = Midad.Muted))
    }
}

private fun label(kind: MediaKind): String = when (kind) {
    MediaKind.BACKGROUNDS -> TvStrings.BACKGROUNDS_LABEL
    MediaKind.ANNOUNCEMENTS -> TvStrings.ANNOUNCEMENT_IMAGES_LABEL
}
