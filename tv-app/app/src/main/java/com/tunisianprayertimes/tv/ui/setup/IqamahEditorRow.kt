package com.tunisianprayertimes.tv.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.tv.data.IqamahConfig
import com.tunisianprayertimes.tv.data.IqamahMode
import com.tunisianprayertimes.tv.data.PrefsManager
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.Stepper
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle

/** The table's columns, so the header sits over the keys: − value + for the iqamah, then for the duration. */
private val KEY = 34.dp
private val IQAMAH_VALUE = 140.dp
private val DURATION_VALUE = 64.dp
private val IQAMAH_COLUMN = KEY * 2 + IQAMAH_VALUE + 12.dp
private val DURATION_COLUMN = KEY * 2 + DURATION_VALUE + 12.dp

/**
 * The iqamah table shared by onboarding and settings: the daily prayers and Jumu'a, then the two
 * Eids, whose minutes count from sunrise. The first row's − takes the focus when the page opens. A
 * plain scrolling column, not a lazy list: a row scrolled away and back must not take the focus again.
 */
@Composable
fun IqamahTable(
    configs: Map<Prayer, IqamahConfig>,
    onChanged: (Prayer, IqamahConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .widthIn(max = 680.dp)
            .verticalScroll(rememberScrollState())
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IqamahHeader()
        PrefsManager.EDITABLE.forEach { prayer ->
            if (prayer == MosqueSchedule.EID.first()) {
                Text(
                    TvStrings.EID_AFTER_SUNRISE,
                    style = midadStyle(14.sp, color = Midad.Muted),
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                )
            }
            IqamahEditorRow(
                prayerName = TvStrings.editorLabel(prayer),
                config = configs[prayer] ?: IqamahConfig.from(MosqueSchedule.DEFAULT.settings(prayer)),
                onConfigChanged = { onChanged(prayer, it) },
                focusFirst = prayer == PrefsManager.EDITABLE.first(),
                afterSunrise = prayer in MosqueSchedule.EID,
            )
        }
    }
}

/** «الصلاة · الإقامة · مدة الصلاة» over the table, quiet. */
@Composable
private fun IqamahHeader() {
    val style = midadStyle(14.sp, color = Midad.Muted)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(TvStrings.PRAYER_COLUMN, style = style, modifier = Modifier.weight(1f))
        Text(TvStrings.IQAMAH_LABEL, style = style, textAlign = TextAlign.Center, modifier = Modifier.width(IQAMAH_COLUMN))
        Text(TvStrings.DURATION_LABEL, style = style, textAlign = TextAlign.Center, modifier = Modifier.width(DURATION_COLUMN))
    }
}

/**
 * One prayer's iqamah and prayer duration (the black screen), used by onboarding and settings.
 * − and + change the minutes after the adhan ([afterSunrise]: after sunrise, for an Eid), or move a
 * fixed time (set from the USB file or the phone) by a minute.
 */
@Composable
fun IqamahEditorRow(
    prayerName: String,
    config: IqamahConfig,
    onConfigChanged: (IqamahConfig) -> Unit,
    /** The first row of a table takes the remote's focus when the page opens. */
    focusFirst: Boolean = false,
    afterSunrise: Boolean = false,
) {
    val iqamahRange = MosqueSchedule.IQAMAH_MINUTES
    val salahRange = MosqueSchedule.SALAH_MINUTES
    val fixed = config.isFixed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .background(Midad.SurfaceRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(prayerName, style = midadStyle(17.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
        Stepper(
            value = iqamahText(config, afterSunrise),
            onMinus = { onConfigChanged(if (fixed) config.shiftFixed(-1) else config.copy(delayMinutes = (config.delayMinutes - 1).coerceIn(iqamahRange))) },
            onPlus = { onConfigChanged(if (fixed) config.shiftFixed(1) else config.copy(delayMinutes = (config.delayMinutes + 1).coerceIn(iqamahRange))) },
            valueWidth = IQAMAH_VALUE,
            keySize = KEY,
            minusModifier = Modifier.initialFocus(focusFirst),
        )
        Stepper(
            value = TvStrings.minutesShort(config.salahMinutes),
            onMinus = { onConfigChanged(config.copy(salahMinutes = (config.salahMinutes - 1).coerceIn(salahRange))) },
            onPlus = { onConfigChanged(config.copy(salahMinutes = (config.salahMinutes + 1).coerceIn(salahRange))) },
            valueWidth = DURATION_VALUE,
            keySize = KEY,
        )
    }
}

/** A fixed iqamah that is really set; a fixed mode without its time counts from the adhan. */
internal val IqamahConfig.isFixed: Boolean
    get() = mode == IqamahMode.FIXED_TIME && fixedHour in 0..23 && fixedMinute in 0..59

/** The iqamah as the tables say it: «بعد الأذان 15 د», «بعد الشروق 20 د» for an Eid, «الساعة 19:40». */
internal fun iqamahText(config: IqamahConfig, afterSunrise: Boolean = false): String = when {
    config.isFixed -> TvStrings.atTime(config.fixedHour, config.fixedMinute)
    afterSunrise -> TvStrings.iqamahAfterSunrise(config.delayMinutes)
    else -> TvStrings.iqamahAfterAdhan(config.delayMinutes)
}

/** The fixed iqamah moved by [minutes], wrapping around midnight; the mode stays fixed. */
internal fun IqamahConfig.shiftFixed(minutes: Int): IqamahConfig {
    val total = Math.floorMod(fixedHour * 60 + fixedMinute + minutes, 24 * 60)
    return copy(fixedHour = total / 60, fixedMinute = total % 60)
}
