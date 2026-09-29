package com.tunisianprayertimes.tv.ui.usb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.tv.data.MediaKind
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.common.FocusableListItem
import com.tunisianprayertimes.tv.ui.theme.Gold
import com.tunisianprayertimes.tv.usb.UsbMediaFound

/** Images found on a USB key: how many of each kind, to copy to the TV or not. Minimal; to be redesigned. */
@Composable
fun UsbMediaScreen(found: UsbMediaFound, onApply: () -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(TvStrings.USB_MEDIA_TITLE, color = Gold, fontSize = 26.sp)
        found.images.forEach { (kind, files) ->
            if (files.isNotEmpty()) {
                val label = when (kind) {
                    MediaKind.BACKGROUNDS -> TvStrings.BACKGROUNDS_LABEL
                    MediaKind.ANNOUNCEMENTS -> TvStrings.ANNOUNCEMENT_IMAGES_LABEL
                }
                Text("$label: ${files.size}", color = MaterialTheme.colorScheme.onBackground, fontSize = 22.sp)
            }
        }
        Text(TvStrings.USB_MEDIA_HINT, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        Row(Modifier.fillMaxWidth(0.6f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f)) { FocusableListItem(text = TvStrings.USB_APPLY, onClick = onApply, modifier = Modifier.initialFocus()) }
            Box(Modifier.weight(1f)) { FocusableListItem(text = TvStrings.CANCEL, onClick = onDismiss) }
        }
    }
}
