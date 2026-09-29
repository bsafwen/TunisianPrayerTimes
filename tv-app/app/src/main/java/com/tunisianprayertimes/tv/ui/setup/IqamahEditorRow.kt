package com.tunisianprayertimes.tv.ui.setup

import com.tunisianprayertimes.tv.ui.common.FocusableButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.tv.data.IqamahConfig
import com.tunisianprayertimes.tv.data.IqamahMode
import com.tunisianprayertimes.tv.data.PrefsManager
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.theme.Gold
import java.util.Locale

/**
 * The iqamah table shared by onboarding and settings: the daily prayers, Jumu'a, then the two Eids,
 * whose minutes count from sunrise.
 */
fun LazyListScope.iqamahRows(configs: Map<Prayer, IqamahConfig>, onChanged: (Prayer, IqamahConfig) -> Unit) {
    items(PrefsManager.EDITABLE) { prayer ->
        Column {
            if (prayer == MosqueSchedule.EID.first()) {
                Text(TvStrings.EID_AFTER_SUNRISE, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
            }
            IqamahEditorRow(
                prayerName = TvStrings.editorLabel(prayer),
                config = configs[prayer] ?: IqamahConfig.from(MosqueSchedule.DEFAULT.settings(prayer)),
                onConfigChanged = { onChanged(prayer, it) },
                focusFirst = prayer == PrefsManager.EDITABLE.first(),
            )
        }
    }
}

/**
 * One prayer's iqamah and prayer duration (the black screen), used by onboarding and settings.
 * − and + change the minutes after the adhan, or move a fixed time (set from the USB file) by a minute.
 */
@Composable
fun IqamahEditorRow(
    prayerName: String,
    config: IqamahConfig,
    onConfigChanged: (IqamahConfig) -> Unit,
    /** The first row of a table takes the remote's focus when the page opens. */
    focusFirst: Boolean = false,
) {
    val iqamahRange = MosqueSchedule.IQAMAH_MINUTES
    val salahRange = MosqueSchedule.SALAH_MINUTES
    val fixed = config.mode == IqamahMode.FIXED_TIME && config.fixedHour >= 0 && config.fixedMinute >= 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(prayerName, color = Gold, fontSize = 22.sp, modifier = Modifier.width(110.dp))
        Stepper(
            focusFirst = focusFirst,
            label = TvStrings.IQAMAH_LABEL,
            value = if (fixed) {
                String.format(Locale.US, "%02d:%02d %s", config.fixedHour, config.fixedMinute, TvStrings.FIXED_SUFFIX)
            } else {
                "+${config.delayMinutes} ${TvStrings.MINUTES_SUFFIX}"
            },
            onMinus = { onConfigChanged(if (fixed) config.shiftFixed(-1) else config.copy(delayMinutes = (config.delayMinutes - 1).coerceIn(iqamahRange))) },
            onPlus = { onConfigChanged(if (fixed) config.shiftFixed(1) else config.copy(delayMinutes = (config.delayMinutes + 1).coerceIn(iqamahRange))) },
        )
        Stepper(
            label = TvStrings.DURATION_LABEL,
            value = "${config.salahMinutes} ${TvStrings.MINUTES_SUFFIX}",
            onMinus = { onConfigChanged(config.copy(salahMinutes = (config.salahMinutes - 1).coerceIn(salahRange))) },
            onPlus = { onConfigChanged(config.copy(salahMinutes = (config.salahMinutes + 1).coerceIn(salahRange))) },
        )
    }
}

/** The fixed iqamah moved by [minutes], wrapping around midnight; the mode stays fixed. */
private fun IqamahConfig.shiftFixed(minutes: Int): IqamahConfig {
    val total = Math.floorMod(fixedHour * 60 + fixedMinute + minutes, 24 * 60)
    return copy(fixedHour = total / 60, fixedMinute = total % 60)
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, focusFirst: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        FocusableButton(text = "−", onClick = onMinus, modifier = Modifier.initialFocus(focusFirst))
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(110.dp),
        )
        FocusableButton(text = "+", onClick = onPlus)
    }
}
