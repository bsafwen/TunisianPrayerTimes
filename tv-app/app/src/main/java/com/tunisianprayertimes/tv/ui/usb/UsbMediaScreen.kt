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
import com.tunisianprayertimes.tv.usb.UsbMediaFound

/**
 * Images (and announcement .txt files) found on a USB key: how many of each kind, to copy to the TV
 * or not. Back dismisses, as «إلغاء» does.
 */
@Composable
fun UsbMediaScreen(found: UsbMediaFound, onApply: () -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    AdminPage(
        title = TvStrings.USB_MEDIA_TITLE,
        modifier = Modifier.background(Midad.Ground).padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Midad.Surface, RoundedCornerShape(14.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            found.images.forEach { (kind, files) ->
                if (files.isNotEmpty()) KindRow(label(kind), TvStrings.filesCount(files.size))
            }
            Text(TvStrings.USB_MEDIA_HINT, style = midadStyle(17.sp, color = Midad.Muted, lineHeight = 1.4f).rtl(), modifier = Modifier.padding(top = 4.dp))
        }
        DialogButtons {
            FocusableListItem(TvStrings.USB_APPLY, onApply, Modifier.initialFocus().width(BUTTON_WIDTH))
            FocusableListItem(TvStrings.CANCEL, onDismiss, Modifier.width(BUTTON_WIDTH))
        }
    }
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
