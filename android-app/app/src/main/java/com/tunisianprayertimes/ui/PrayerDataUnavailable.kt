package com.tunisianprayertimes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.TextMuted

@Composable
internal fun PrayerDataUnavailable(
    isToday: Boolean,
    onReturnToToday: () -> Unit,
    onChooseLocation: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.prayer_data_unavailable_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = PrayerNameColor,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(if (isToday) R.string.prayer_data_unavailable_today else R.string.prayer_data_unavailable_date),
            fontSize = 14.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )
        if (!isToday) {
            Button(
                onClick = onReturnToToday,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("prayer_data_return_today"),
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
            ) {
                Text(stringResource(R.string.date_go_back_today), textAlign = TextAlign.Center)
            }
        }
        OutlinedButton(
            onClick = onChooseLocation,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("prayer_data_choose_location"),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = GreenPrimary),
        ) {
            Text(stringResource(R.string.prayer_data_choose_location), textAlign = TextAlign.Center)
        }
    }
}
