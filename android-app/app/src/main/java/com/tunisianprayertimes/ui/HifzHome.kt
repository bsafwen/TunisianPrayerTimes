package com.tunisianprayertimes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranGoTo
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzPoint
import com.tunisianprayertimes.quran.hifz.HifzReminder
import com.tunisianprayertimes.quran.hifz.HifzSettings
import com.tunisianprayertimes.quran.hifz.dueToday
import com.tunisianprayertimes.quran.hifz.epochDay
import com.tunisianprayertimes.quran.hifz.hifzVersesLabel
import com.tunisianprayertimes.quran.quranGoTo
import com.tunisianprayertimes.quran.quranNameKey
import com.tunisianprayertimes.ui.theme.*
import java.time.LocalDate

/** What «المراجعة» can ask of the reader. */
internal interface HifzHomeActions {
    /** The launcher's «سجّل موضعا» opened its dialog: the request is done. */
    fun recordShown()
    fun review()
    fun listen(points: List<HifzPoint>)
    fun briefing()
    fun open(point: HifzPoint)
    fun record(verse: QuranVerseReference)
    fun settings()
    /** «وضع المراجعة», the same as the eye in the reader's bar. */
    fun hideVerses(on: Boolean)
    fun dismiss()
}

/**
 * «المراجعة»: everything that helps keep what is memorised. Today's checks, the eyes-free entry, hiding the verses on
 * the page, positive counts only, then the notebook of slips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HifzHomeSheet(
    catalog: QuranCatalog,
    points: List<HifzPoint>,
    settings: HifzSettings,
    today: Long,
    /** Opened from the launcher's «سجّل موضعا»: ask for the verse at once. */
    startRecording: Boolean = false,
    /** «وضع المراجعة» is on in the reader. */
    versesHidden: Boolean,
    actions: HifzHomeActions,
) {
    val due = remember(points, today, settings.perDay) { points.dueToday(today, settings.perDay) }
    /** All the points due, also those beyond one review's. */
    val dueAll = remember(points, today) { points.count { it.isDue(today) } }
    var howItWorks by rememberSaveable { mutableStateOf(false) }
    val month = remember(today) { LocalDate.ofEpochDay(today).withDayOfMonth(1).toEpochDay() }
    val heldThisMonth = points.count { p -> p.masteredAt?.let { epochDay(it) >= month } == true && p.mastered }
    var tab by rememberSaveable { mutableIntStateOf(if (due.isNotEmpty()) 0 else 1) }
    var kindFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var surahFilter by rememberSaveable { mutableStateOf<Int?>(null) }
    var away by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startRecording) {
        if (startRecording) {
            away = true
            actions.recordShown()
        }
    }
    ModalBottomSheet(onDismissRequest = actions::dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        Column(Modifier.fillMaxWidth().quranSheetHeight().testTag("hifz_home")) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                QuranHifzSquare(18.dp)
                Spacer(Modifier.width(8.dp))
                Text("المراجعة", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                TextButton(onClick = actions::settings, modifier = Modifier.testTag("hifz_settings")) { Text("الإعدادات") }
                IconButton(onClick = actions::dismiss) { Icon(painterResource(R.drawable.ic_adhkar_close), "إغلاق") }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    // Only the reminders in use: the morning one, and those after prayers while a plan runs.
                    HifzNotificationsWarning(listOfNotNull(
                        HifzReminder.CHANNEL_ID.takeIf { settings.reminder && points.isNotEmpty() },
                        HifzReminder.REPEAT_CHANNEL_ID.takeIf { points.any { it.repeatsOn(today) } },
                    ))
                }
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = GoldLight) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (due.isNotEmpty()) {
                                // The review the button starts, as the «اليوم» tab and the morning reminder count it.
                                Text("اليوم ${quranPointsLabel(due.size)} للمراجعة", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                                Text(
                                    listOfNotNull(
                                        "نحو ${quranMinutesLabel((due.size + 1).coerceAtLeast(2))}",
                                        "نبدأ بما يحتاج عناية".takeIf { due.any { it.needsCare } },
                                        "وبقي بعدها: ${hifzCountLabel(dueAll - due.size)}".takeIf { dueAll > due.size },
                                    ).joinToString(" · "),
                                    fontSize = 13.sp, color = Color(0xFF6E5A2E),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = actions::review, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.testTag("hifz_start_review")) {
                                        Text("ابدأ المراجعة")
                                    }
                                    OutlinedButton(onClick = { actions.listen(due) }) { Text("استمع إليها") }
                                }
                            } else if (points.isEmpty()) {
                                Text("دفتر المواضع فارغ", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                                Text("اضغط على آية أخطأت فيها، ثم «موضع». أو سجّل موضعا من هنا بعد التسميع.", fontSize = 13.sp, color = Color(0xFF6E5A2E))
                            } else {
                                // Held points come back too, to make sure of them.
                                val next = points.minOfOrNull { it.dueDay }
                                val allHeld = points.all { it.mastered }
                                Text(if (allHeld) "كل مواضعك ثبتت" else "لا مراجعة اليوم", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                                if (next != null) Text(
                                    (if (allHeld) "مراجعة للتأكد " else "المراجعة القادمة ") + hifzWhen(next - today),
                                    fontSize = 13.sp, color = Color(0xFF6E5A2E),
                                )
                                OutlinedButton(onClick = { actions.listen(points.filterNot { it.mastered }.ifEmpty { points }) }) { Text("استمع إلى مواضعي") }
                            }
                        }
                    }
                }
                item {
                    // Reciting a page from memory is part of the review too, with or without slips recorded.
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)
                            .toggleable(versesHidden, role = Role.Switch, onValueChange = actions::hideVerses)
                            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp).testTag("hifz_hide_verses"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_quran_review), null, Modifier.size(22.dp), tint = GreenPrimary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("وضع المراجعة", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                            Text("تُغطّى الآيات في المصحف فتقرؤها من حفظك، وتكشفها بإصبعك. تجده أيضا في زر العين أعلى المصحف.",
                                fontSize = 12.sp, color = TextMuted)
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = versesHidden, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = GreenPrimary))
                    }
                }
                if (points.isNotEmpty()) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = actions::briefing, modifier = Modifier.testTag("hifz_briefing")) { Text("قبل التسميع") }
                        if (heldThisMonth > 0) Text("ثبت هذا الشهر: ${hifzCountLabel(heldThisMonth)}", Modifier.weight(1f), fontSize = 13.sp, color = GreenPrimary)
                        else Spacer(Modifier.weight(1f))
                        TextButton(onClick = { howItWorks = true }, modifier = Modifier.testTag("hifz_home_how_it_works")) { Text("كيف تعمل؟", fontSize = 13.sp) }
                    }
                }
                if (points.isNotEmpty()) item {
                    Text("دفتر المواضع", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, modifier = Modifier.padding(top = 8.dp))
                    val held = points.count { it.mastered }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("اليوم ${due.size}", "الكل ${points.size}", "ثبتت $held").forEachIndexed { index, label ->
                            FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = GreenPrimary, selectedLabelColor = Color.White))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HifzFilterMenu(
                            label = kindFilter?.let { HifzKind.of(it)?.short } ?: "كل الأنواع",
                            options = listOf(null to "كل الأنواع") + HifzKind.entries.map { it.key to it.label },
                        ) { kindFilter = it }
                        val surahs = points.map { it.verse.surah }.distinct().sorted()
                        HifzFilterMenu(
                            label = surahFilter?.let { catalog.surahs.getOrNull(it - 1)?.name } ?: "كل السور",
                            options = listOf(null to "كل السور") + surahs.map { it.toString() to catalog.surahs[it - 1].name },
                        ) { surahFilter = it?.toIntOrNull() }
                    }
                }
                val shown = when (tab) {
                    0 -> due
                    2 -> points.filter { it.mastered }
                    else -> points
                }.filter { (kindFilter == null || it.kind.key == kindFilter) && (surahFilter == null || it.verse.surah == surahFilter) }
                val care = shown.filter { it.needsCare }
                if (care.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(top = 4.dp)) {
                            Text("يحتاج عناية", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = QuranHifzViolet)
                            Text("مواضع سجلتها 3 مرات أو أكثر، تأتي أولًا حتى تثبت", fontSize = 12.sp, color = TextMuted)
                        }
                    }
                    items(care, key = { "care${it.id}" }) { HifzPointRow(catalog, it, today) { actions.open(it) } }
                }
                shown.filterNot { it.needsCare }.groupBy { it.verse.surah }.toSortedMap().forEach { (surah, rows) ->
                    item(key = "surah$surah") {
                        Text("${catalog.surahs.getOrNull(surah - 1)?.name.orEmpty()} (${rows.size})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMuted,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    items(rows, key = { it.id }) { HifzPointRow(catalog, it, today) { actions.open(it) } }
                }
                if (points.isNotEmpty() && shown.isEmpty()) item { Text("لا مواضع هنا", fontSize = 13.sp, color = TextMuted) }
            }
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterEnd) {
                ExtendedFloatingActionButton(
                    onClick = { away = true }, containerColor = GreenPrimary, contentColor = Color.White,
                    modifier = Modifier.testTag("hifz_record_away"),
                ) { Text("سجّل موضعا") }
            }
        }
    }
    if (away) HifzAwayDialog(catalog, onChosen = { away = false; actions.record(it) }, onDismiss = { away = false })
    if (howItWorks) HifzHowItWorksDialog { howItWorks = false }
}

@Composable
private fun HifzFilterMenu(label: String, options: List<Pair<String?, String>>, onChosen: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("$label ▾") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onChosen(value) }) }
        }
    }
}

@Composable
private fun HifzPointRow(catalog: QuranCatalog, point: HifzPoint, today: Long, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp).testTag("hifz_point_${point.verse.surah}_${point.verse.ayah}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(hifzPointTitle(catalog, point), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hifzListDetail(point, today), fontSize = 12.sp, color = TextMuted)
        }
        HifzDots(point.slips)
    }
}

/** «النساء47», «النساء: 47», «ص 51»: a verse typed loosely, which the index's field reads otherwise or not at all. */
private fun hifzTypedVerse(text: String, catalog: QuranCatalog): QuranVerseReference? {
    val clean = text.trim().map { c -> c.digitToIntOrNull()?.digitToChar() ?: c }.joinToString("")
    val match = Regex("^(.*\\D)\\s*(\\d{1,3})$").matchEntire(clean) ?: return null
    val name = match.groupValues[1].trimEnd(' ', ':', '：')
    val surah = catalog.surahs.firstOrNull { quranNameKey(it.name) == quranNameKey(name) } ?: return null
    val ayah = match.groupValues[2].toInt()
    return QuranVerseReference(surah.number, ayah).takeIf { ayah >= 1 && (surah.verseCount == null || ayah <= surah.verseCount) }
}

/** «في سورة النساء 176 آية · اكتب رقم الآية» when a chapter is recognised, otherwise an example. */
private fun hifzAwayHint(text: String, catalog: QuranCatalog): String {
    val clean = text.trim().map { c -> c.digitToIntOrNull()?.digitToChar() ?: c }.joinToString("")
    // «4», «4:200»: a chapter's number; «النساء», «النساء 200»: its name.
    val numbered = Regex("^(\\d{1,3})(?:\\s*[:：/.\\s-]\\s*\\d{0,3})?$").matchEntire(clean)?.groupValues?.get(1)?.toInt()
    val named = Regex("^(.*\\D)\\s*\\d{0,3}$").matchEntire(clean)?.groupValues?.get(1)?.trimEnd(' ', ':', '：')
    val surah = numbered?.let { n -> catalog.surahs.firstOrNull { it.number == n } }
        ?: named?.let { n -> catalog.surahs.firstOrNull { quranNameKey(it.name) == quranNameKey(n) } }
    val count = surah?.verseCount
    return when {
        surah == null -> "اكتب السورة ورقم الآية، مثل النساء 47"
        count == null -> "اكتب رقم الآية بعد سورة ${surah.name}"
        else -> "في سورة ${surah.name} ${hifzVersesLabel(count)} · اكتب رقم الآية"
    }
}

/** A slip made away from the app (in prayer, with a teacher): find the verse by its number or chapter. */
@Composable
private fun HifzAwayDialog(catalog: QuranCatalog, onChosen: (QuranVerseReference) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val found = remember(text) {
        quranGoTo(text, catalog.surahs, catalog.pages.size + 1).filterIsInstance<QuranGoTo.Verse>().firstOrNull()?.verse ?: hifzTypedVerse(text, catalog)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("سجّل موضعا") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("في أي آية أخطأت؟", fontSize = 14.sp)
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("hifz_away_field"),
                    placeholder = { Text("مثل 4:47 أو النساء 47") }, shape = RoundedCornerShape(12.dp),
                    // Why «التالي» waits, once something is typed: never an error while the reader types.
                    supportingText = if (found == null && text.isNotBlank()) ({ Text(hifzAwayHint(text, catalog)) }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { found?.let(onChosen) }),
                )
                found?.let { verse ->
                    Text(hifzVerseName(catalog, verse), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                    catalog.verse(verse)?.let { Text(it.text.split(' ').take(8).joinToString(" ") + " …", fontFamily = AdhkarReadingFont, fontSize = 16.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { found?.let(onChosen) }, enabled = found != null) { Text("التالي") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HifzSettingsSheet(settings: HifzSettings, onChange: (HifzSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag("hifz_settings_sheet"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            QuranPanelHeader("إعدادات المراجعة", onDismiss)
            HifzNotificationsWarning(listOfNotNull(
                HifzReminder.CHANNEL_ID.takeIf { settings.reminder },
                HifzReminder.REPEAT_CHANNEL_ID.takeIf { !settings.newRepeat.none },
            ))
            Text("ذكّرني بالموضع الجديد", fontSize = 15.sp, color = TextDark, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            HifzRepeatChoice(settings.newRepeat, settings.newRepeatDays) { repeat, days ->
                onChange(settings.copy(newRepeat = repeat, newRepeatDays = days))
            }
            Text("يمكنك تغيير ذلك لكل موضع عند تسجيله أو من «التصحيح».", fontSize = 12.sp, color = TextMuted)
            HorizontalDivider(color = CardBorder)
            HifzSwitch("تذكير يومي", settings.reminder) { onChange(settings.copy(reminder = it)) }
            HifzChoice("وقت التذكير", "بعد الشروق بـ ${settings.reminderAfterSunriseMin} دقيقة", HifzSettings.ReminderOffsets.map { it to "بعد الشروق بـ $it دقيقة" }, enabled = settings.reminder) {
                onChange(settings.copy(reminderAfterSunriseMin = it))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("عدد المواضع في كل مراجعة", Modifier.weight(1f), fontSize = 15.sp)
                HifzSettings.PerDayChoices.forEach { n ->
                    FilterChip(selected = settings.perDay == n, onClick = { onChange(settings.copy(perDay = n)) }, label = { Text("$n") }, modifier = Modifier.padding(start = 6.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = GreenPrimary, selectedLabelColor = Color.White))
                }
            }
            HorizontalDivider(color = CardBorder)
            HifzSwitch("إظهار مواضعي على الصفحات", settings.markers) { onChange(settings.copy(markers = it)) }
            HifzSwitch("تنبيه قبل الموضع أثناء الاستماع", settings.headsUp) { onChange(settings.copy(headsUp = it)) }
            HifzSwitch("توقف قبل الموضع لأسمّعه", settings.stopBefore) { onChange(settings.copy(stopBefore = it)) }
            HifzSwitch("اعرض «قبل التسميع» حين أفتح سورة فيها مواضع", settings.briefing) { onChange(settings.copy(briefing = it)) }
            HorizontalDivider(color = CardBorder)
            HifzChoice("مهلة للترديد في التدريب", gapLabel(settings.drillGap), listOf(0f, 0.5f, 1f, 2f).map { it to gapLabel(it) }) {
                onChange(settings.copy(drillGap = it))
            }
            HifzChoice("السرعة في التدريب", speedLabel(settings.drillSpeed), listOf(0.75f, 1f).map { it to speedLabel(it) }) {
                onChange(settings.copy(drillSpeed = it))
            }
        }
    }
}

@Composable
private fun HifzSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        Switch(checked = checked, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = GreenPrimary))
    }
}

@Composable
private fun <T> HifzChoice(label: String, value: String, options: List<Pair<T, String>>, enabled: Boolean = true, onChosen: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val choice: @Composable () -> Unit = {
        Box {
            Text("$value ▾", fontSize = 14.sp, color = if (enabled) GreenPrimary else TextMuted)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (option, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onChosen(option) }) }
            }
        }
    }
    val row = Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }.padding(vertical = 10.dp)
    // With large text the value goes under its label, which then keeps its whole width.
    if (LocalDensity.current.fontScale > 1.3f) Column(row) {
        Text(label, fontSize = 15.sp, color = if (enabled) TextDark else TextMuted)
        choice()
    } else Row(row, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp, color = if (enabled) TextDark else TextMuted)
        choice()
    }
}

/** A count standing alone after a colon: «موضع واحد», «موضعان», «3 مواضع». */
internal fun hifzCountLabel(count: Int): String = if (count == 1) "موضع واحد" else quranPointsLabel(count)
