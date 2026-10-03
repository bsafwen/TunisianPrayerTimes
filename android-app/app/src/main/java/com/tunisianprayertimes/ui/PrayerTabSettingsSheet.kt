package com.tunisianprayertimes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.TextDark

/** The settings button beside the prayer tab's location header; opens [PrayerTabSettingsSheet]. */
@Composable
internal fun PrayerTabSettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(GreenPrimary.copy(alpha = 0.08f))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(TestTags.PRAYER_TAB_SETTINGS_BUTTON),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = stringResource(R.string.prayer_tab_settings_title),
            tint = GreenPrimary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Prayer-tab settings kept out of the scrolling page: automatic silencing first, then
 * the rarely changed toggles. State and persistence stay with the caller, so each
 * switch reflects only its own flag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrayerTabSettingsSheet(
    autoSilenceEnabled: Boolean,
    onAutoSilenceChange: (Boolean) -> Unit,
    callEndVibrationEnabled: Boolean,
    onCallEndVibrationChange: (Boolean) -> Unit,
    autoLocationEnabled: Boolean,
    onAutoLocationChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PrayerSilencePalette.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.prayer_tab_settings_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerSilencePalette.PrimaryText,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            PrayerTabSettingsSwitchRow(
                title = stringResource(R.string.auto_silence),
                checked = autoSilenceEnabled,
                onCheckedChange = onAutoSilenceChange,
                testTag = TestTags.AUTO_SILENCE_SWITCH,
            )
            HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
            PrayerTabSettingsSwitchRow(
                title = stringResource(R.string.call_end_vibration_title),
                checked = callEndVibrationEnabled,
                onCheckedChange = onCallEndVibrationChange,
                testTag = TestTags.CALL_END_VIBRATION_SWITCH,
            )
            HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
            PrayerTabSettingsSwitchRow(
                title = stringResource(R.string.auto_location_title),
                checked = autoLocationEnabled,
                onCheckedChange = onAutoLocationChange,
                testTag = TestTags.AUTO_LOCATION_SWITCH,
            )
        }
    }
}

@Composable
private fun PrayerTabSettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .testTag(testTag)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = TextDark,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = GreenPrimary,
            ),
        )
    }
}
