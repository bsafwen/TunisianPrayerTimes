package com.tunisianprayertimes.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.MethodPresentation
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.method.ChooserKind
import com.tunisianprayertimes.method.CountryDefault
import com.tunisianprayertimes.method.CountryMethodDefaults
import com.tunisianprayertimes.method.PrayerMethodChoice
import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.ui.theme.BannerBg
import com.tunisianprayertimes.ui.theme.BannerStroke
import com.tunisianprayertimes.ui.theme.BannerText
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.time.LocalDate

/** A place abroad whose country asks for a method, or whose country is unknown, while that is not answered (6.4). */
internal data class MethodQuestion(val place: PrayerPlace.Coordinates, val defaults: CountryDefault, val key: String) {
    /** A chooser country (6.4); otherwise only «تأكيد الطريقة», for a place whose country is unknown. */
    val hasChooser: Boolean get() = defaults.chooser != ChooserKind.NONE
}

/** The method question pending at the saved place, or null (6.4, 6.11). */
internal fun pendingMethodQuestion(context: Context): MethodQuestion? {
    val place = PrayerTimesRepository.place(context) as? PrayerPlace.Coordinates ?: return null
    val defaults = PrayerTimesRepository.placeTimesFor(context).defaults(place)
    if (defaults.chooser == ChooserKind.NONE && !defaults.confirm) return null
    if (PrefsManager.getMethodChoice(context, place)?.chooserAnswered == true) return null
    return MethodQuestion(place, defaults, CountryMethodDefaults.choiceKey(place.countryCode, place.admin1))
}

/**
 * The answer, kept in the country's choice (6.4): a method picked here counts as picked (no longer resolved again on
 * a move), the Asr factor where asked, and «تأكيد الطريقة» goes.
 */
internal fun answerMethodQuestion(context: Context, question: MethodQuestion, methodId: String?, asrShadow: Int?) {
    val base = PrefsManager.getMethodChoice(context, question.place) ?: PrayerMethodChoice.default(question.defaults)
    val next = when (methodId) {
        null -> base
        base.methodId -> base.copy(methodPicked = true)
        else -> base.copy(methodId = methodId, custom = null, zonePick = null, methodPicked = true)
    }
    PrefsManager.setMethodChoice(context, question.place, next.copy(asrShadow = asrShadow ?: next.asrShadow, chooserAnswered = true))
}

/**
 * `MethodChooserSheet` (6.4, 6.11): one screen for every chooser country. The methods its mosques use, each with its
 * chip and today's Fajr, Maghrib and Isha, then the Asr factor where it is asked once (the UK, Portugal, Réunion).
 * Dismissing it keeps the country's fallback, and «تأكيد الطريقة» stays until the user answers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MethodChooserSheet(question: MethodQuestion, onAnswered: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val place = question.place
    val defaults = question.defaults
    val askMethods = defaults.chooser == ChooserKind.METHODS || defaults.chooser == ChooserKind.METHODS_AND_ASR
    val askAsr = defaults.chooser == ChooserKind.ASR_ONLY || defaults.chooser == ChooserKind.METHODS_AND_ASR
    val placeTimes = remember { PrayerTimesRepository.placeTimesFor(context) }
    val today = LocalDate.now(place.zone)
    val methods = remember(defaults) { defaults.entries.mapNotNull(PrayerMethods::byId) }
    var selected by rememberSaveable { mutableStateOf(defaults.preselected) }
    var asr by rememberSaveable { mutableStateOf<Int?>(null) }
    val canSave = (!askMethods || selected != null) && (!askAsr || asr != null)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding().testTag("method_chooser_sheet")) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.method_chooser_title), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GreenPrimaryDark)
                    Text(stringResource(R.string.method_chooser_note), fontSize = 13.sp, color = TextMuted, lineHeight = 19.sp)
                }
            }
            if (askMethods) {
                items(methods, key = { it.id }) { method ->
                    MethodEntry(place, method, today, selected == method.id, placeTimes,
                        inferred = defaults.inferred && method.id == defaults.fallback, onPick = { selected = it })
                }
            }
            if (askAsr) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.method_chooser_asr), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = asr == 1, onClick = { asr = 1 }, label = { Text("الجمهور") })
                            FilterChip(selected = asr == 2, onClick = { asr = 2 }, label = { Text("الحنفية") })
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            answerMethodQuestion(context, question, if (askMethods) selected else null, if (askAsr) asr else null)
                            onAnswered()
                        },
                        enabled = canSave,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("method_chooser_save"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GreenPrimaryDark),
                    ) { Text(stringResource(R.string.method_chooser_save), fontWeight = FontWeight.Bold) }
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.prayer_editor_cancel), color = GreenPrimaryDark)
                    }
                }
            }
        }
    }
}

/**
 * «تأكيد الطريقة» (6.4, 6.11): after a dismissed chooser, until the user answers; and for a place whose country is
 * unknown, which uses `mwl`, with «تأكيد» to keep it.
 */
@Composable
internal fun MethodConfirmBanner(question: MethodQuestion, onChoose: () -> Unit, onConfirm: () -> Unit) {
    val method = MethodPresentation.name(question.defaults.preselected ?: question.defaults.fallback)
    // Inside the page's own 16 dp margins, under the place header.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BannerBg)
            .border(1.dp, BannerStroke, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("method_confirm_banner"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.method_confirm_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = BannerText)
        Text(
            stringResource(if (question.hasChooser) R.string.method_confirm_text_chooser else R.string.method_confirm_text_unknown, method),
            fontSize = 13.sp, color = TextDark, lineHeight = 19.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (question.hasChooser) {
                TextButton(onClick = onChoose, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.method_confirm_choose), color = GreenPrimary, fontWeight = FontWeight.SemiBold)
                }
            } else {
                TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.method_confirm_ok), color = GreenPrimary, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onChoose, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.location_confirm_change), color = GreenPrimaryDark)
                }
            }
        }
    }
}
