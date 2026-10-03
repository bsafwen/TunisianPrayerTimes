package com.tunisianprayertimes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.audio.QuranRepeatRange
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextMuted

private val QuranRepeatPresets = listOf(2, 3, 5, 10)
private const val QuranRepeatDefaultTimes = 3

/**
 * Chooses the verses to recite again and again. It opens on [initial], which a pressed verse
 * makes that one verse, repeated until the listener stops it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun QuranRepeatSheet(
    catalog: QuranCatalog,
    initial: QuranRepeatRange,
    onStart: (QuranRepeatRange) -> Unit,
    onDismiss: () -> Unit,
) {
    var fromSurah by rememberSaveable { mutableIntStateOf(initial.from.surah) }
    var fromAyah by rememberSaveable { mutableStateOf(initial.from.ayah.toString()) }
    var toSurah by rememberSaveable { mutableIntStateOf(initial.to.surah) }
    var toAyah by rememberSaveable { mutableStateOf(initial.to.ayah.toString()) }
    var endless by rememberSaveable { mutableStateOf(initial.times == null) }
    var times by rememberSaveable { mutableStateOf((initial.times ?: QuranRepeatDefaultTimes).toString()) }

    val from = quranInputNumber(fromAyah)?.takeIf { it in 1..catalog.verseCount(fromSurah) }
        ?.let { QuranVerseReference(fromSurah, it) }
    val to = quranInputNumber(toAyah)?.takeIf { it in 1..catalog.verseCount(toSurah) }
        ?.let { QuranVerseReference(toSurah, it) }
    val count = quranInputNumber(times)?.takeIf { it in 1..QuranRepeatRange.MAX_TIMES }
    val reversed = from != null && to != null && to < from
    val range = if (from != null && to != null && !reversed && (endless || count != null)) {
        QuranRepeatRange(from, to, if (endless) null else count)
    } else null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BgCream,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp).testTag("quran_repeat_sheet")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("تكرار التلاوة", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_adhkar_close), "إغلاق") }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuranRepeatVersePicker(
                    title = "من", catalog = catalog, surah = fromSurah, ayah = fromAyah, verse = from, tag = "quran_repeat_from",
                    onSurah = { surah ->
                        // A single verse stays a single verse, and the end never falls behind the new start.
                        if (from == to || toSurah < surah) { toSurah = surah; toAyah = "1" }
                        fromSurah = surah
                        fromAyah = "1"
                    },
                    onAyah = { fromAyah = it },
                )
                QuranRepeatVersePicker(
                    title = "إلى", catalog = catalog, surah = toSurah, ayah = toAyah, verse = to, tag = "quran_repeat_to",
                    onSurah = { surah ->
                        toSurah = surah
                        toAyah = catalog.verseCount(surah).toString()
                    },
                    onAyah = { toAyah = it },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {
                            val last = from?.let { catalog.pagesForVerse(it.surah, it.ayah).lastOrNull() }
                                ?.let { page -> catalog.versesOnPage(page).lastOrNull { it.ayah > 0 } }
                            if (last != null) { toSurah = last.surah; toAyah = last.ayah.toString() }
                        },
                        enabled = from != null,
                        label = { Text("إلى نهاية الصفحة") },
                        modifier = Modifier.testTag("quran_repeat_to_page_end"),
                    )
                    AssistChip(
                        onClick = { toSurah = fromSurah; toAyah = catalog.verseCount(fromSurah).toString() },
                        label = { Text("إلى نهاية السورة") },
                        modifier = Modifier.testTag("quran_repeat_to_surah_end"),
                    )
                }
                if (reversed) {
                    Text("نهاية المقطع تسبق بدايته.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                HorizontalDivider()
                Text("عدد مرات التكرار", color = GreenPrimaryDark, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = endless, onClick = { endless = true }, label = { Text("بلا توقف") },
                        modifier = Modifier.testTag("quran_repeat_endless"),
                    )
                    QuranRepeatPresets.forEach { preset ->
                        FilterChip(
                            selected = !endless && count == preset,
                            onClick = { endless = false; times = preset.toString() },
                            label = { Text(preset.toString()) },
                            modifier = Modifier.testTag("quran_repeat_times_$preset"),
                        )
                    }
                }
                if (!endless) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { times = ((count ?: 2) - 1).toString() },
                            enabled = count == null || count > 1,
                            modifier = Modifier.testTag("quran_repeat_fewer"),
                        ) { Icon(painterResource(R.drawable.ic_remove), "مرة أقل") }
                        OutlinedTextField(
                            value = times,
                            onValueChange = { times = it.take(3) },
                            modifier = Modifier.width(88.dp).testTag("quran_repeat_times"),
                            singleLine = true,
                            isError = count == null,
                            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            shape = RoundedCornerShape(12.dp),
                        )
                        IconButton(
                            onClick = { times = ((count ?: 0) + 1).toString() },
                            enabled = count == null || count < QuranRepeatRange.MAX_TIMES,
                            modifier = Modifier.testTag("quran_repeat_more"),
                        ) { Icon(painterResource(R.drawable.ic_add), "مرة أكثر") }
                        Text(
                            count?.let(::quranRepeatTimesLabel) ?: "من 1 إلى ${QuranRepeatRange.MAX_TIMES}",
                            Modifier.padding(start = 4.dp), fontSize = 13.sp,
                            color = if (count == null) MaterialTheme.colorScheme.error else TextMuted,
                        )
                    }
                }
                Text(
                    if (endless) "تُعاد التلاوة إلى أن توقفها."
                    else "تتوقف التلاوة بعد إتمام العدد وتعود إلى أول المقطع.",
                    fontSize = 12.sp, color = TextMuted,
                )
            }
            Button(
                onClick = { range?.let(onStart) },
                enabled = range != null,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("quran_repeat_start"),
            ) { Text("ابدأ التكرار") }
        }
    }
}

@Composable
private fun QuranRepeatVersePicker(
    title: String,
    catalog: QuranCatalog,
    surah: Int,
    ayah: String,
    verse: QuranVerseReference?,
    tag: String,
    onSurah: (Int) -> Unit,
    onAyah: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val menuScroll = rememberScrollState()
    val rowHeight = with(LocalDensity.current) { 48.dp.roundToPx() }
    // Open the long chapter list on the chosen chapter rather than on al-Fatiha.
    LaunchedEffect(menu) { if (menu) menuScroll.scrollTo((surah - 2).coerceAtLeast(0) * rowHeight) }
    Column {
        Text(title, fontSize = 13.sp, color = TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The verse field keeps 8 dp above its outline for the floating label.
            Box(Modifier.weight(1f).padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = { menu = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("${tag}_surah"),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("$surah. سورة ${catalog.surahs[surah - 1].name}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    modifier = Modifier.heightIn(max = 320.dp),
                    scrollState = menuScroll,
                ) {
                    catalog.surahs.forEach { item ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${item.number}. ${item.name}",
                                    fontWeight = if (item.number == surah) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            onClick = { menu = false; if (item.number != surah) onSurah(item.number) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = ayah,
                onValueChange = { onAyah(it.take(3)) },
                modifier = Modifier.width(116.dp).testTag("${tag}_ayah"),
                label = { Text("الآية") },
                supportingText = { Text("من 1 إلى ${catalog.verseCount(surah)}") },
                singleLine = true,
                isError = verse == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                shape = RoundedCornerShape(12.dp),
            )
        }
        verse?.let { catalog.textForVerse(it.surah, it.ayah) }?.let { text ->
            Text(
                text, fontFamily = AdhkarReadingFont, fontSize = 18.sp, lineHeight = 30.sp, color = GreenPrimaryDark,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun QuranCatalog.verseCount(surah: Int): Int = surahs[surah - 1].verseCount ?: 1

/** «مرة واحدة», «مرتان», «3 مرات», «11 مرة». */
internal fun quranRepeatTimesLabel(times: Int): String = when {
    times == 1 -> "مرة واحدة"
    times == 2 -> "مرتان"
    times % 100 in 3..10 -> "$times مرات"
    else -> "$times مرة"
}

/** «تكرار بلا توقف · المرة 4» or «تكرار · المرة 2 من 5». */
internal fun quranRepeatStatus(range: QuranRepeatRange, round: Int): String {
    val pass = round.coerceAtLeast(1)
    return range.times?.let { "تكرار · المرة $pass من $it" } ?: "تكرار بلا توقف · المرة $pass"
}

/** Names the repeated verses in words, never as a bare «2:5» that right-to-left text would reorder. */
internal fun quranRepeatRangeLabel(catalog: QuranCatalog, range: QuranRepeatRange): String {
    fun surah(number: Int) = "سورة ${catalog.surahs[number - 1].name}"
    return when {
        range.from == range.to -> "${surah(range.from.surah)} · الآية ${range.from.ayah}"
        range.from.surah == range.to.surah -> "${surah(range.from.surah)} · الآيات من ${range.from.ayah} إلى ${range.to.ayah}"
        else -> "من ${surah(range.from.surah)}، الآية ${range.from.ayah} إلى ${surah(range.to.surah)}، الآية ${range.to.ayah}"
    }
}
