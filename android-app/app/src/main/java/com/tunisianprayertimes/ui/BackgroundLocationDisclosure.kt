package com.tunisianprayertimes.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.TextDark

/**
 * The optional background-location request of 6.8's travel rule. Play wants this disclosure before the system's
 * request: the location data, its use while the app is closed, and the one feature it serves. «متابعة» then asks
 * Android, which opens its location page on Android 11 and later, where the user picks «always». Without it the place
 * follows the phone only while a screen of the app is open. [onDone] gets whether the permission is now granted.
 */
@Composable
internal fun BackgroundLocationDisclosure(onDone: (granted: Boolean) -> Unit) {
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> onDone(granted) }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        shape = RoundedCornerShape(16.dp),
        containerColor = Color.White,
        title = {
            Text(
                text = stringResource(R.string.background_location_title),
                color = PrayerNameColor,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.background_location_body),
                color = TextDark,
                lineHeight = 20.sp,
            )
        },
        dismissButton = {
            TextButton(onClick = { onDone(false) }) {
                Text(text = stringResource(R.string.background_location_later))
            }
        },
        confirmButton = {
            Button(
                onClick = { request.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(text = stringResource(R.string.background_location_continue))
            }
        },
    )
}
