package com.tunisianprayertimes.tv.ui.usb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.FocusableListItem
import com.tunisianprayertimes.tv.ui.theme.Gold
import com.tunisianprayertimes.tv.usb.UsbSettingsFound

/**
 * Settings found on a USB key: the changes to confirm, or why the file was not applied.
 * [preview] is the file read against the current settings. Back dismisses, it never leaves the app.
 * Minimal on purpose; the screen will be redesigned.
 */
@Composable
fun UsbImportScreen(
    found: UsbSettingsFound,
    preview: ParseResult,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    title: String = TvStrings.USB_FOUND_TITLE,
) {
    BackHandler(onBack = onDismiss)
    // The first button has focus, so OK on the remote answers without hunting for it.
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(preview) { runCatching { firstButton.requestFocus() } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val result = preview) {
            is ParseResult.Success -> {
                Text(title, color = Gold, fontSize = 26.sp)
                Text(found.file.path, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                if (!result.hasChanges) {
                    Text(TvStrings.USB_NO_CHANGES, color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp)
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.fillMaxWidth(0.4f)) { FocusableListItem(text = TvStrings.USB_OK, onClick = onDismiss, modifier = Modifier.focusRequester(firstButton)) }
                } else {
                    // Every change stays readable above the buttons, however many the file makes.
                    val lines = SettingsChangeLines.of(result)
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        lines.forEach { line ->
                            Text(line, color = MaterialTheme.colorScheme.onBackground, fontSize = if (lines.size <= 7) 20.sp else 15.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(0.6f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.weight(1f)) { FocusableListItem(text = TvStrings.USB_APPLY, onClick = onApply, modifier = Modifier.focusRequester(firstButton)) }
                        Box(Modifier.weight(1f)) { FocusableListItem(text = TvStrings.CANCEL, onClick = onDismiss) }
                    }
                }
            }
            is ParseResult.Failure -> {
                Text(TvStrings.USB_ERROR_TITLE, color = Gold, fontSize = 26.sp)
                Text(found.file.path, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                result.errors.forEach { error ->
                    Text(error.message, color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp)
                }
                Text(TvStrings.USB_ERROR_HINT, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth(0.4f)) { FocusableListItem(text = TvStrings.USB_OK, onClick = onDismiss, modifier = Modifier.focusRequester(firstButton)) }
            }
        }
    }
}
