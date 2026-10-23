package com.tunisianprayertimes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.audio.QuranGapChoices
import com.tunisianprayertimes.quran.audio.QuranRepeatRange
import com.tunisianprayertimes.quran.audio.QuranRepeatScope
import com.tunisianprayertimes.quran.audio.quranTimes
import com.tunisianprayertimes.quran.quranAyah
import com.tunisianprayertimes.ui.theme.*

/** How many times, offered the same everywhere: 0 is «بلا توقّف». */
internal val QuranRepeatCounts = listOf(0, 2, 3, 5, 10)

/**
 * «ماذا تريد أن تكرّر؟»: the one place for everything that repeats, opened by the repeat button of the dock and of the
 * player. Each choice applies at once; «تم» closes it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun QuranRepeatChooser(
    surahName: String,
    /** The surah recited after this one, for «وفي آخر السورة؟»; null after the last. */
    nextSurahName: String?,
    /** The verse recited now, where chosen verses start. */
    verse: Int?,
    scope: QuranRepeatScope,
    /** For the surah or each verse; 0 without end. */
    count: Int,
    /** The chosen verses repeating now, «الآيات من 8 إلى 12 · 3 مرات», while they do. */
    passage: String?,
    gapFactor: Float,
    continueAfterSurah: Boolean,
    onScope: (QuranRepeatScope) -> Unit,
    onCount: (Int) -> Unit,
    onGap: (Float) -> Unit,
    onContinue: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var gapOpen by rememberSaveable { mutableStateOf(gapFactor > 0f) }
    var asking by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BgCream,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .testTag("quran_repeat_chooser"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("ماذا تريد أن تكرّر؟", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RepeatChoice("سورة $surahName كلها", "من أولها إلى آخرها", scope == QuranRepeatScope.Surah, "quran_repeat_surah") {
                    onScope(QuranRepeatScope.Surah)
                }
                RepeatChoice("كل آية", "تتكرّر الآية قبل أن تنتقل إلى التي بعدها", scope == QuranRepeatScope.Verse, "quran_repeat_verse") {
                    onScope(QuranRepeatScope.Verse)
                }
                RepeatChoice(
                    "آيات تختارها",
                    passage ?: verse?.let { "من ${quranAyah(it)} إلى آية تضغط عليها" } ?: "من أول الصفحة إلى آية تضغط عليها",
                    scope == QuranRepeatScope.Passage, "quran_repeat_passage",
                ) { onScope(QuranRepeatScope.Passage) }
                RepeatChoice("لا شيء", "تُتلى السورة مرة واحدة", scope == QuranRepeatScope.None, "quran_repeat_none") {
                    onScope(QuranRepeatScope.None)
                }
            }
            if (scope == QuranRepeatScope.Surah || scope == QuranRepeatScope.Verse) {
                Text("كم مرة؟", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                QuranCountChips(count, onCount = onCount, onOther = { asking = true }, minimum = 2)
                Text(
                    when {
                        scope == QuranRepeatScope.Surah && count == 0 -> "تتكرّر سورة $surahName إلى أن توقفها."
                        scope == QuranRepeatScope.Surah -> "تُتلى سورة $surahName ${quranTimes(count)}، ثم يتوقّف الاستماع."
                        count == 0 -> "تتكرّر الآية، وتنقلك «الآية التالية» أو «الآية السابقة» إلى غيرها."
                        else -> "تُتلى كل آية ${quranTimes(count)} قبل التي بعدها."
                    },
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AdhkarSoftGreen).padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp, color = GreenPrimary,
                )
            }
            // What comes at the end of the surah, when it is not recited again.
            if ((scope == QuranRepeatScope.None || scope == QuranRepeatScope.Verse) && nextSurahName != null) {
                Text("وفي آخر السورة؟", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // What happens, not an order: «توقّف» alone would read as stop now.
                    QuranChoiceChip("تنتقل إلى سورة $nextSurahName", continueAfterSurah, "quran_repeat_end_next") { onContinue(true) }
                    QuranChoiceChip("يتوقّف الاستماع", !continueAfterSurah, "quran_repeat_end_stop") { onContinue(false) }
                }
            }
            HorizontalDivider(color = CardBorder)
            Surface(
                onClick = { gapOpen = !gapOpen }, color = Color.Transparent, shape = RoundedCornerShape(10.dp),
                modifier = Modifier.semantics { stateDescription = if (gapOpen) "الخيارات ظاهرة" else "الخيارات مطويّة" },
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("وقفة بعد كل آية لتردّدها", Modifier.weight(1f), fontSize = 15.sp, color = GreenPrimaryDark)
                    Text(quranGapChoiceLabel(gapFactor), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimary)
                    Icon(
                        painterResource(if (gapOpen) R.drawable.ic_expand_less else R.drawable.ic_expand_more), null,
                        Modifier.padding(start = 4.dp).size(20.dp), tint = GreenPrimary,
                    )
                }
            }
            if (gapOpen) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuranGapChoices.forEach { g -> QuranChoiceChip(quranGapChoiceLabel(g), g == gapFactor, "quran_repeat_gap_$g") { onGap(g) } }
            }
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("quran_repeat_done"),
            ) { Text("تم", fontSize = 16.sp) }
        }
    }
    if (asking) QuranCountDialog(initial = count.takeIf { it >= 2 }, minimum = 2, onCount = { asking = false; onCount(it) }, onDismiss = { asking = false })
}

@Composable
private fun RepeatChoice(label: String, detail: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) GreenPrimary else CardBorder), RoundedCornerShape(14.dp))
            .background(if (selected) AdhkarSoftGreen else Color.White)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(22.dp).border(2.dp, if (selected) GreenPrimary else Color(0xFF9E9E9E), CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(11.dp).clip(CircleShape).background(GreenPrimary)) }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            Text(detail, fontSize = 13.sp, color = TextMuted)
        }
    }
}

/** «بلا توقّف · مرتين · 3 مرات · 5 مرات · 10 مرات · عدد آخر…»; a count of the listener's own shows in the last. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuranCountChips(count: Int, onCount: (Int) -> Unit, onOther: () -> Unit, minimum: Int = 1, tagPrefix: String = "quran_repeat_times") {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QuranRepeatCounts.forEach { n ->
            QuranChoiceChip(if (n == 0) "بلا توقّف" else quranTimes(n), n == count, "${tagPrefix}_$n") { onCount(n) }
        }
        val own = count.takeIf { it !in QuranRepeatCounts && it >= minimum }
        QuranChoiceChip(own?.let(::quranTimes) ?: "عدد آخر…", own != null, "${tagPrefix}_other", onClick = onOther)
    }
}

@Composable
internal fun QuranChoiceChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 14.sp, maxLines = 1) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.White, selectedContainerColor = AdhkarSoftGreen, selectedLabelColor = GreenPrimaryDark,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected, borderColor = Color(0xFFC9BFAF), selectedBorderColor = GreenPrimary,
        ),
        modifier = Modifier.testTag(tag),
    )
}

/** «كم مرة؟» for a count none of the chips has. */
@Composable
internal fun QuranCountDialog(initial: Int?, minimum: Int, onCount: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial?.toString().orEmpty()) }
    val value = quranInputNumber(text)?.takeIf { it in minimum..QuranRepeatRange.MAX_TIMES }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("كم مرة؟") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(3) },
                singleLine = true,
                isError = text.isNotEmpty() && value == null,
                supportingText = { Text("من $minimum إلى ${QuranRepeatRange.MAX_TIMES}") },
                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.testTag("quran_repeat_count_field"),
            )
        },
        confirmButton = { TextButton(onClick = { value?.let(onCount) }, enabled = value != null) { Text("تم") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

/** «بلا وقفة», «قصيرة», «بمدة تلاوة الآية», «بضعف المدة»: the silence after each verse. */
internal fun quranGapChoiceLabel(factor: Float): String = when (factor) {
    0f -> "بلا وقفة"
    0.5f -> "قصيرة"
    1f -> "بمدة تلاوة الآية"
    2f -> "بضعف المدة"
    else -> ltr("×$factor")
}
