package com.tunisianprayertimes.tv.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.FlowTiming
import com.tunisianprayertimes.mosque.IqamahRule
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.PrayerEvent
import com.tunisianprayertimes.mosque.PrayerOverride
import com.tunisianprayertimes.tv.data.IqamahConfig
import com.tunisianprayertimes.tv.data.IqamahMode
import com.tunisianprayertimes.tv.data.PrefsManager
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.FocusableSurface
import com.tunisianprayertimes.tv.ui.common.Stepper
import com.tunisianprayertimes.tv.ui.common.ToggleRow
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.common.onSurfaceText
import com.tunisianprayertimes.tv.ui.common.rtl
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import java.time.LocalTime

/** The table's columns, so the header sits over the keys: − value + for the iqamah, then for the duration. */
private val KEY = 34.dp
private val IQAMAH_VALUE = 140.dp
private val DURATION_VALUE = 64.dp
private val IQAMAH_COLUMN = KEY * 2 + IQAMAH_VALUE + 12.dp
private val DURATION_COLUMN = KEY * 2 + DURATION_VALUE + 12.dp
/** In the settings only: the key that switches the rule's kind, and the iqamah the wall uses today. */
private val MODE_COLUMN = 92.dp
private val TODAY_COLUMN = 76.dp

/** Jumu'a and Dhuhr share the noon adhan: on the day of one, the other takes its time from it. */
private val NOON_TWIN = mapOf(Prayer.JOMOAA to Prayer.DHUHR, Prayer.DHUHR to Prayer.JOMOAA)

/**
 * The iqamah table shared by onboarding and settings: the daily prayers and Jumu'a, then the two
 * Eids, whose minutes count from sunrise. Under Jumu'a and under the Eids, whether the mosque holds
 * them at all (a neighbourhood masjid holds neither), and under a Jumu'a it holds, whether the dua
 * after the adhan comes before the khutba (on by default) and the khutba's length. The first row's
 * − takes the focus when the page opens. A plain scrolling column, not a lazy list: a row scrolled
 * away and back must not take the focus again.
 *
 * In the settings, [today] holds today's prayers as the wall runs them: each row then gets a key that
 * switches its rule between minutes and a fixed time, and the iqamah the wall uses today beside the
 * rule, marked when Ramadan's changes or a fixed time that does not fit today decided it. A prayer's
 * Ramadan change from the USB file or the phone ([ramadan]) shows under its row, with a key that
 * removes it. With [adhanScreenMinutes] (the settings), a row over the table sets how long the adhan
 * screen lasts, and says that an iqamah set sooner waits for its end.
 */
@Composable
fun IqamahTable(
    configs: Map<Prayer, IqamahConfig>,
    onChanged: (Prayer, IqamahConfig) -> Unit,
    modifier: Modifier = Modifier,
    today: Map<Prayer, PrayerEvent>? = null,
    ramadan: Map<Prayer, PrayerOverride> = emptyMap(),
    onRamadanCleared: (Prayer) -> Unit = {},
    adhanScreenMinutes: Int? = null,
    onAdhanScreenMinutesChanged: (Int) -> Unit = {},
) {
    fun config(prayer: Prayer) = configs[prayer] ?: IqamahConfig.from(MosqueSchedule.DEFAULT.settings(prayer))
    Column(
        modifier
            .widthIn(max = if (today != null) 840.dp else 680.dp)
            .verticalScroll(rememberScrollState())
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        adhanScreenMinutes?.let { AdhanScreenRow(it, withToday = today != null, onAdhanScreenMinutesChanged) }
        IqamahHeader(withToday = today != null)
        PrefsManager.EDITABLE.forEach { prayer ->
            if (prayer == MosqueSchedule.EID.first()) {
                Text(
                    TvStrings.EID_AFTER_SUNRISE,
                    style = midadStyle(14.sp, color = Midad.Muted),
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                )
            }
            val config = config(prayer)
            val adhan = today?.let { it[prayer] ?: it[NOON_TWIN[prayer]] }?.adhanAt?.toLocalTime()
            IqamahEditorRow(
                prayerName = TvStrings.editorLabel(prayer),
                config = config,
                onConfigChanged = { onChanged(prayer, it) },
                focusFirst = prayer == PrefsManager.EDITABLE.first(),
                afterSunrise = prayer in MosqueSchedule.EID,
                withToday = today != null,
                todayEvent = today?.get(prayer),
                switched = config.switched(adhan),
                ramadan = ramadan[prayer]?.takeIf { today != null },
                onRamadanCleared = { onRamadanCleared(prayer) },
            )
            // One switch for Jumu'a, one for both Eids: a mosque holds both Eid prayers or neither.
            val held = when (prayer) {
                Prayer.JOMOAA -> listOf(prayer)
                MosqueSchedule.EID.last() -> MosqueSchedule.EID
                else -> null
            }
            if (held != null) {
                val on = held.all { config(it).held }
                ToggleRow(
                    label = if (prayer == Prayer.JOMOAA) TvStrings.HOLDS_JUMUA else TvStrings.HOLDS_EID,
                    checked = on,
                    onToggle = { held.forEach { onChanged(it, config(it).copy(held = !on)) } },
                    detail = if (prayer == Prayer.JOMOAA) TvStrings.HOLDS_JUMUA_HINT else TvStrings.HOLDS_EID_HINT,
                )
            }
            if (prayer == Prayer.JOMOAA && config.held) {
                // The order of the Friday screens: the adhan, its dua (unless off), then the khutba.
                ToggleRow(
                    label = TvStrings.JUMUA_ADHAN_DUA,
                    checked = config.adhanDua,
                    onToggle = { onChanged(prayer, config.copy(adhanDua = !config.adhanDua)) },
                    detail = TvStrings.JUMUA_ADHAN_DUA_HINT,
                )
                KhutbaRow(config, withToday = today != null) { onChanged(prayer, it) }
            }
        }
        if (today.orEmpty().values.any { it.iqamahAdjusted }) {
            Text(
                TvStrings.IQAMAH_ADJUSTED_NOTE,
                style = midadStyle(14.sp, color = Midad.Muted, lineHeight = 1.45f).rtl(),
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
    }
}

/** Jumu'a's khutba length, under its iqamah: «من الأذان» (the whole wait, as by default), or up to an hour. */
@Composable
private fun KhutbaRow(config: IqamahConfig, withToday: Boolean, onConfigChanged: (IqamahConfig) -> Unit) {
    val range = MosqueSchedule.KHUTBA_MINUTES
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .background(Midad.SurfaceRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(TvStrings.KHUTBA_LENGTH, style = midadStyle(17.sp, FontWeight.Medium))
            Text(TvStrings.KHUTBA_LENGTH_HINT, style = midadStyle(14.sp, color = Midad.Muted))
        }
        // Under the iqamah's column, by five minutes.
        if (withToday) Spacer(Modifier.width(MODE_COLUMN))
        Stepper(
            value = TvStrings.khutbaLength(config.khutbaMinutes),
            onMinus = { onConfigChanged(config.copy(khutbaMinutes = (config.khutbaMinutes - 5).coerceIn(range))) },
            onPlus = { onConfigChanged(config.copy(khutbaMinutes = (config.khutbaMinutes + 5).coerceIn(range))) },
            valueWidth = IQAMAH_VALUE,
            keySize = KEY,
        )
        Spacer(Modifier.width(DURATION_COLUMN))
        if (withToday) Spacer(Modifier.width(TODAY_COLUMN))
    }
}

/**
 * How many minutes the adhan screen lasts (1 to 5), under the iqamah's column: an iqamah set closer to
 * the adhan waits for the end of the adhan screen and the minute of the dua after it, which the row's hint says.
 */
@Composable
private fun AdhanScreenRow(minutes: Int, withToday: Boolean, onChanged: (Int) -> Unit) {
    val range = FlowTiming.ADHAN_SCREEN_MINUTES
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .background(Midad.SurfaceRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(TvStrings.ADHAN_SCREEN_LENGTH, style = midadStyle(17.sp, FontWeight.Medium))
            Text(TvStrings.ADHAN_SCREEN_LENGTH_HINT, style = midadStyle(14.sp, color = Midad.Muted, lineHeight = 1.45f).rtl())
        }
        if (withToday) Spacer(Modifier.width(MODE_COLUMN))
        Stepper(
            value = TvStrings.minutesShort(minutes),
            onMinus = { onChanged((minutes - 1).coerceIn(range)) },
            onPlus = { onChanged((minutes + 1).coerceIn(range)) },
            valueWidth = IQAMAH_VALUE,
            keySize = KEY,
        )
        Spacer(Modifier.width(DURATION_COLUMN))
        if (withToday) Spacer(Modifier.width(TODAY_COLUMN))
    }
}

/** «الصلاة · الإقامة · مدة الصلاة», and «اليوم» in the settings, over the table, quiet. */
@Composable
private fun IqamahHeader(withToday: Boolean) {
    val style = midadStyle(14.sp, color = Midad.Muted)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(TvStrings.PRAYER_COLUMN, style = style, modifier = Modifier.weight(1f))
        if (withToday) Spacer(Modifier.width(MODE_COLUMN))
        Text(TvStrings.IQAMAH_LABEL, style = style, textAlign = TextAlign.Center, modifier = Modifier.width(IQAMAH_COLUMN))
        Text(TvStrings.DURATION_LABEL, style = style, textAlign = TextAlign.Center, modifier = Modifier.width(DURATION_COLUMN))
        if (withToday) Text(TvStrings.TODAY_COLUMN, style = style, textAlign = TextAlign.Center, modifier = Modifier.width(TODAY_COLUMN))
    }
}

/**
 * One prayer's iqamah and prayer duration (the black screen), used by onboarding and settings.
 * − and + change the minutes after the adhan ([afterSunrise]: after sunrise, for an Eid), or move a
 * fixed time by a minute. [withToday] (the settings) adds the key that turns the rule into [switched],
 * and today's iqamah ([todayEvent], none when the prayer is not prayed today).
 */
@Composable
fun IqamahEditorRow(
    prayerName: String,
    config: IqamahConfig,
    onConfigChanged: (IqamahConfig) -> Unit,
    /** The first row of a table takes the remote's focus when the page opens. */
    focusFirst: Boolean = false,
    afterSunrise: Boolean = false,
    withToday: Boolean = false,
    todayEvent: PrayerEvent? = null,
    switched: IqamahConfig? = null,
    /** Ramadan's change to this prayer, shown under the row with the key that removes it. */
    ramadan: PrayerOverride? = null,
    onRamadanCleared: () -> Unit = {},
) {
    val iqamahRange = MosqueSchedule.IQAMAH_MINUTES
    val salahRange = MosqueSchedule.SALAH_MINUTES
    val fixed = config.isFixed
    // The Ramadan line's key goes away once pressed: the focus moves to the iqamah's − rather than into nowhere.
    val minus = remember { FocusRequester() }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Midad.SurfaceRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(prayerName, style = midadStyle(17.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
            if (withToday) {
                // Always the same room, so the rows keep their columns whether the key is offered or not.
                Box(Modifier.width(MODE_COLUMN)) {
                    if (switched != null) {
                        RowKey(
                            text = when {
                                !fixed -> TvStrings.SWITCH_TO_FIXED
                                afterSunrise -> TvStrings.SWITCH_TO_SUNRISE
                                else -> TvStrings.SWITCH_TO_ADHAN
                            },
                            onClick = { onConfigChanged(switched) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Stepper(
                value = iqamahText(config, afterSunrise),
                onMinus = { onConfigChanged(if (fixed) config.shiftFixed(-1) else config.copy(delayMinutes = (config.delayMinutes - 1).coerceIn(iqamahRange))) },
                onPlus = { onConfigChanged(if (fixed) config.shiftFixed(1) else config.copy(delayMinutes = (config.delayMinutes + 1).coerceIn(iqamahRange))) },
                valueWidth = IQAMAH_VALUE,
                keySize = KEY,
                minusModifier = Modifier.focusRequester(minus).initialFocus(focusFirst),
            )
            Stepper(
                value = TvStrings.minutesShort(config.salahMinutes),
                onMinus = { onConfigChanged(config.copy(salahMinutes = (config.salahMinutes - 1).coerceIn(salahRange))) },
                onPlus = { onConfigChanged(config.copy(salahMinutes = (config.salahMinutes + 1).coerceIn(salahRange))) },
                valueWidth = DURATION_VALUE,
                keySize = KEY,
            )
            if (withToday) TodayCell(todayEvent)
        }
        if (ramadan != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(ramadanText(ramadan), style = midadStyle(14.sp, color = Midad.Muted).rtl(), modifier = Modifier.weight(1f))
                RowKey(
                    text = TvStrings.RAMADAN_CLEAR,
                    onClick = {
                        onRamadanCleared()
                        runCatching { minus.requestFocus() }
                    },
                    modifier = Modifier.width(MODE_COLUMN),
                )
            }
        }
    }
}

/**
 * Today's iqamah on the wall, and a word when it is not the rule's: Ramadan's, or moved to fit today.
 * Set tight, so the two lines keep the row's height.
 */
@Composable
private fun TodayCell(event: PrayerEvent?) {
    Column(Modifier.width(TODAY_COLUMN), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(todayIqamahText(event), style = midadStyle(16.sp, FontWeight.Medium, lineHeight = 1.15f))
        todayMark(event)?.let { mark ->
            val color = if (event?.iqamahAdjusted == true) Midad.Alert else Midad.Muted
            Text(mark, style = midadStyle(12.sp, color = color, lineHeight = 1.15f))
        }
    }
}

/** A small key on a row of the table, quieter than the steppers' keys. */
@Composable
private fun RowKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = KEY),
        rest = Midad.Surface,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { focused ->
        Text(text, style = midadStyle(14.sp, color = onSurfaceText(focused)), textAlign = TextAlign.Center, maxLines = 1)
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

/**
 * The rule turned into the other kind, keeping what the other kind had saved: a fixed time back to
 * minutes after the adhan, or minutes to the fixed time set before, else to where the minutes put it
 * after today's [adhan] (sunrise for an Eid). Null when no fixed time is known to start from.
 */
internal fun IqamahConfig.switched(adhan: LocalTime?): IqamahConfig? = when {
    isFixed -> copy(mode = IqamahMode.DELAY)
    fixedHour in 0..23 && fixedMinute in 0..59 -> copy(mode = IqamahMode.FIXED_TIME)
    adhan != null -> adhan.plusMinutes(delayMinutes.toLong()).let { copy(mode = IqamahMode.FIXED_TIME, fixedHour = it.hour, fixedMinute = it.minute) }
    else -> null
}

/** Ramadan's change to a prayer, under its row: «في رمضان: الإقامة بعد الأذان 20 د · مدة الصلاة 75 د». */
internal fun ramadanText(override: PrayerOverride): String = "${TvStrings.IN_RAMADAN}: " + listOfNotNull(
    override.iqamah?.let { rule ->
        val iqamah = when (rule) {
            is IqamahRule.AfterAdhan -> TvStrings.iqamahAfterAdhan(rule.minutes)
            is IqamahRule.FixedTime -> TvStrings.atTime(rule.time.hour, rule.time.minute)
        }
        "${TvStrings.IQAMAH_LABEL} $iqamah"
    },
    override.salahMinutes?.let { "${TvStrings.DURATION_LABEL} ${TvStrings.minutesShort(it)}" },
).joinToString(" · ")

/** The iqamah the wall uses today, «19:52»; «—» for a prayer not prayed today (Jumu'a on a weekday, an Eid). */
internal fun todayIqamahText(event: PrayerEvent?): String = event?.let { TvStrings.hm(it.iqamahAt.toLocalTime()) } ?: "—"

/** Why today's iqamah is not the rule's: moved to fit today's times (first, it needs the admin), or Ramadan's. */
internal fun todayMark(event: PrayerEvent?): String? = when {
    event == null -> null
    event.iqamahAdjusted -> TvStrings.IQAMAH_ADJUSTED
    event.ramadanSettings -> TvStrings.RAMADAN
    else -> null
}
