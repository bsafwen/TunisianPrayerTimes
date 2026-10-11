package com.tunisianprayertimes.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranGoTo
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.hifz.HifzFix
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzLookAlike
import com.tunisianprayertimes.quran.hifz.HifzLookAlikes
import com.tunisianprayertimes.quran.hifz.HifzPoint
import com.tunisianprayertimes.quran.hifz.HifzPrayers
import com.tunisianprayertimes.quran.hifz.HifzReminder
import com.tunisianprayertimes.quran.hifz.HifzRepeat
import com.tunisianprayertimes.quran.hifz.HifzRepeatDays
import com.tunisianprayertimes.quran.hifz.HifzRepeatMaxDays
import com.tunisianprayertimes.quran.hifz.HifzRepeatMaxTimes
import com.tunisianprayertimes.quran.hifz.HifzRepeatPreset
import com.tunisianprayertimes.quran.hifz.HifzWords
import com.tunisianprayertimes.quran.hifz.hifzFix
import com.tunisianprayertimes.quran.hifz.hifzOverlap
import com.tunisianprayertimes.quran.hifz.hifzPassage
import com.tunisianprayertimes.quran.hifz.hifzVersesAfterLabel
import com.tunisianprayertimes.quran.hifz.hifzWords
import com.tunisianprayertimes.quran.hifz.hifzWordsLabel
import com.tunisianprayertimes.quran.hifz.hifzRunLabel
import com.tunisianprayertimes.quran.quranGoTo
import com.tunisianprayertimes.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalTime

/** «البقرة 17». */
internal fun hifzVerseName(catalog: QuranCatalog, verse: QuranVerseReference): String =
    "${catalog.surahs.getOrNull(verse.surah - 1)?.name.orEmpty()} ${verse.ayah}"

/** «ص 4», the printed page. */
internal fun hifzPage(catalog: QuranCatalog, verse: QuranVerseReference): String? =
    catalog.pagesForVerse(verse.surah, verse.ayah).firstOrNull()?.let { "ص ${it + 1}" }

/** «اليوم», «غدًا», «بعد يومين», «بعد 3 أيام», «بعد أسبوع», «بعد شهر», «بعد 12 يومًا». */
internal fun hifzWhen(days: Long): String = when {
    days <= 0L -> "اليوم"
    days == 1L -> "غدًا"
    days == 2L -> "بعد يومين"
    days == 7L -> "بعد أسبوع"
    days == 14L -> "بعد أسبوعين"
    days == 30L -> "بعد شهر"
    days == 60L -> "بعد شهرين"
    days in 3L..10L -> "بعد $days أيام"
    else -> "بعد $days يومًا"
}

/** When a point comes back: «المراجعة غدًا», «المراجعة بعد 3 أيام». */
internal fun hifzReviewWhen(days: Long): String = "المراجعة ${hifzWhen(days)}"

/** «سجلته مرة», «سجلته مرتين», «سجلته 3 مرات». */
internal fun hifzSlipsLabel(slips: Int): String = when {
    slips <= 1 -> "سجلته مرة"
    slips == 2 -> "سجلته مرتين"
    slips % 100 in 3..10 -> "سجلته $slips مرات"
    else -> "سجلته $slips مرة"
}

/** «البقرة 17 · خاتمة مع البقرة 170». */
internal fun hifzPointTitle(catalog: QuranCatalog, point: HifzPoint): String {
    val with = when (point.kind) {
        HifzKind.Ending, HifzKind.Similar -> point.other?.let { " مع ${hifzVerseName(catalog, it)}" }
        HifzKind.Skip -> if (point.verse.ayah == 1) " · أول السورة" else " بعد ${point.verse.ayah - 1}"
        else -> null
    }.orEmpty()
    return "${hifzVerseName(catalog, point.verse)} · ${point.kind.short}$with"
}

/**
 * Quran words as printed. [marked] words stand out in [markColor] and underlined; «هنا يفترقان» goes
 * before word [partAt]; [hidden] words are replaced by one «……».
 */
@Composable
internal fun HifzVerseText(
    words: HifzWords,
    modifier: Modifier = Modifier,
    from: Int = 0,
    to: Int = words.size,
    marked: Set<Int> = emptySet(),
    markColor: Color = GreenPrimary,
    partAt: Int? = null,
    hidden: Set<Int>? = null,
    lead: String = "",
    trail: String = "",
    fontSize: TextUnit = 19.sp,
    color: Color = TextDark,
) {
    val text = remember(words, from, to, marked, markColor, partAt, hidden, lead, trail) {
        buildAnnotatedString {
            append(lead)
            var masked = false
            for (i in from.coerceAtLeast(0) until to.coerceAtMost(words.size)) {
                if (hidden != null && i in hidden) {
                    if (!masked) { append("…… "); masked = true }
                    continue
                }
                masked = false
                if (i == partAt && i > from) {
                    withStyle(SpanStyle(color = QuranHifzViolet, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = null)) { append("‹هنا يفترقان› ") }
                }
                if (i in marked) withStyle(SpanStyle(color = markColor, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)) { append(words.printed[i]) }
                else append(words.printed[i])
                append(' ')
            }
            append(trail)
        }
    }
    Text(text, modifier, fontFamily = AdhkarReadingFont, fontSize = fontSize, lineHeight = fontSize * 1.9f, color = color)
}

@Composable
private fun HifzSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, CardBorder), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title.isNotEmpty()) Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextMuted)
            content()
        }
    }
}

/** ●●○: how many times the slip was recorded, up to three. */
@Composable
internal fun HifzDots(slips: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (i < slips) QuranHifzViolet else QuranHifzViolet.copy(alpha = 0.2f)))
        }
    }
}

/**
 * «ما الذي حدث في النساء 47؟»: a kind first, then what more it asks (where the recitation went, which words); the
 * save stays in view with the drill and the reminders it brings, so nothing is saved before it is seen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HifzRecordSheet(
    catalog: QuranCatalog,
    lookAlikes: HifzLookAlikes?,
    initialVerse: QuranVerseReference,
    onSave: (verse: QuranVerseReference, kind: HifzKind, other: QuranVerseReference?, slipWords: Set<Int>, bothWays: Boolean, hafs: Boolean, repeat: HifzRepeat, days: Int) -> Unit,
    onDismiss: () -> Unit,
    /** What «ذكّرني» offers first; see [com.tunisianprayertimes.quran.hifz.HifzSettings.newRepeat]. */
    defaultRepeat: HifzRepeat = HifzRepeatPreset.Prayers.repeat,
    defaultDays: Int = 3,
    /** Offer the verse before: false when the reader typed the verse themselves. */
    offerPrevious: Boolean = true,
) {
    // Saveable: a re-created activity reopens the sheet as the reader left it.
    var verseKey by rememberSaveable { mutableStateOf("${initialVerse.surah}:${initialVerse.ayah}") }
    val verse = remember(verseKey) { verseKey.split(':').let { QuranVerseReference(it[0].toInt(), it[1].toInt()) } }
    var kindKey by rememberSaveable { mutableStateOf<String?>(null) }
    val kind = kindKey?.let(HifzKind::of)
    val words = remember(verse) { catalog.verse(verse)?.let { hifzWords(it.text) } }
    val suggestions = remember(lookAlikes, verse, kind) {
        when (kind) {
            HifzKind.Similar -> lookAlikes?.similar(verse).orEmpty()
            HifzKind.Ending -> lookAlikes?.endings(verse).orEmpty()
            else -> emptyList()
        }
    }
    var otherKey by rememberSaveable(verse, kind) { mutableStateOf<String?>(null) }
    val other = otherKey?.split(':')?.let { QuranVerseReference(it[0].toInt(), it[1].toInt()) }
    fun chooseOther(value: QuranVerseReference?) { otherKey = value?.let { "${it.surah}:${it.ayah}" } }
    var otherChosen by rememberSaveable(verse, kind) { mutableStateOf(false) }
    LaunchedEffect(suggestions) { if (!otherChosen) chooseOther(suggestions.firstOrNull()?.verse) }
    var word by rememberSaveable(verse, kind) { mutableStateOf<Int?>(null) }
    // A word slip may miss several words: any of them, in any order.
    var wrongWords by rememberSaveable(verse, kind) { mutableStateOf(emptyList<Int>()) }
    var wordOpen by rememberSaveable(verse, kind) { mutableStateOf(false) }
    var bothWays by rememberSaveable { mutableStateOf(false) }
    var hafs by rememberSaveable { mutableStateOf(false) }
    var typing by rememberSaveable(verse, kind) { mutableStateOf(false) }
    var repeatCode by rememberSaveable { mutableStateOf(defaultRepeat.encode()) }
    val repeat = HifzRepeat.decode(repeatCode) ?: defaultRepeat
    var repeatDays by rememberSaveable { mutableIntStateOf(defaultDays) }
    val context = LocalContext.current.applicationContext
    var plan by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(repeat, repeatDays) { plan = withContext(Dispatchers.IO) { HifzReminder.planSentence(context, repeat, repeatDays) } }
    // Where the recitation probably left the verse, from the look-alike.
    val suggestedWord = remember(words, other, kind) {
        val a = words ?: return@remember null
        val b = other?.let { catalog.verse(it) }?.let { hifzWords(it.text) } ?: return@remember null
        val overlap = hifzOverlap(a.keys, b.keys)
        when (kind) {
            HifzKind.Similar -> overlap.prefix.takeIf { it in 2 until a.size } ?: (overlap.runA + overlap.run).takeIf { overlap.run >= 2 && it < a.size }
            HifzKind.Ending -> (overlap.runA + overlap.run).takeIf { overlap.run > 0 && it < a.size }
            else -> null
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        Column(Modifier.fillMaxWidth().quranSheetHeight().padding(horizontal = 16.dp).testTag("hifz_record_sheet")) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuranPanelHeader("ما الذي حدث في ${hifzVerseName(catalog, verse)}؟", onDismiss)
                Text(
                    listOfNotNull(hifzPage(catalog, verse), "اختر ما يشبه ما جرى، والتفاصيل اختيارية").joinToString(" · "),
                    fontSize = 12.sp, color = TextMuted,
                )
                // The slip may belong to the verse before, noticed only once this one began; and back again. The kind chosen stays.
                if (initialVerse.ayah > 1 && offerPrevious) TextButton(
                    onClick = { verseKey = if (verse == initialVerse) "${verse.surah}:${verse.ayah - 1}" else "${initialVerse.surah}:${initialVerse.ayah}" },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        if (verse == initialVerse) "كان الخطأ في الآية السابقة؟ سجّله في الآية ${verse.ayah - 1}" else "رجوع إلى الآية ${initialVerse.ayah}",
                        fontSize = 12.sp,
                    )
                }
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) { HifzKind.entries.forEach { option ->
                    val selected = kind == option
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selected) AdhkarSoftGreen else Color.White)
                            .border(1.dp, if (selected) GreenPrimary else CardBorder, RoundedCornerShape(12.dp))
                            .selectable(selected, role = Role.RadioButton) { kindKey = option.key }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("hifz_kind_${option.key}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selected) Text("✓ ", Modifier.clearAndSetSemantics {}, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                        Text(option.label, Modifier.weight(1f), fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = GreenPrimaryDark)
                    }
                } }
                when (kind) {
                    HifzKind.Similar, HifzKind.Ending -> {
                        Text("إلى أين ذهبت؟", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, modifier = Modifier.padding(top = 6.dp))
                        if (lookAlikes == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = GreenPrimary, trackColor = CardBorder)
                        suggestions.forEach { candidate ->
                            HifzLookAlikeRow(catalog, verse, candidate, kind!!, selected = other == candidate.verse) { chooseOther(candidate.verse); otherChosen = true }
                        }
                        if (lookAlikes != null && suggestions.isEmpty()) Text("لم نجد آية شبيهة بنص قريب؛ اختر الآية بنفسك", fontSize = 12.sp, color = TextMuted)
                        val typed = other?.takeIf { o -> suggestions.none { it.verse == o } }
                        if (typed != null) HifzChoiceRow("${hifzVerseName(catalog, typed)} · ${hifzPage(catalog, typed).orEmpty()}", selected = true) {}
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { typing = !typing }, label = { Text("آية أخرى…") })
                            FilterChip(selected = otherChosen && other == null, onClick = { chooseOther(null); otherChosen = true }, label = { Text("لا أعرف") })
                        }
                        if (typing) HifzVerseField(catalog) { chosen -> chooseOther(chosen); otherChosen = true; typing = false }
                        if (words != null) {
                            TextButton(onClick = { wordOpen = !wordOpen }, contentPadding = PaddingValues(0.dp)) {
                                Text("أين خرجت من الآية؟ (اختياري) ${if (wordOpen) "▴" else "▾"}", fontSize = 13.sp)
                            }
                            if (wordOpen) HifzWordPicker(words, setOfNotNull(word ?: suggestedWord), suggested = suggestedWord) { word = it }
                        }
                        Row(Modifier.fillMaxWidth().toggleable(bothWays, role = Role.Checkbox) { bothWays = it }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = bothWays, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text("قد أخلط في الاتجاهين", fontSize = 14.sp)
                        }
                    }
                    HifzKind.Word -> if (words != null) {
                        Text("أي كلمة؟", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, modifier = Modifier.padding(top = 6.dp))
                        Text("اختر كل كلمة نسيتها أو أخطأت فيها، ولمسة أخرى تلغيها", fontSize = 12.sp, color = TextMuted)
                        HifzWordPicker(words, wrongWords.toSet(), suggested = null, multi = true) { i -> wrongWords = if (i in wrongWords) wrongWords - i else wrongWords + i }
                        Row(Modifier.fillMaxWidth().toggleable(hafs, role = Role.Checkbox) { hafs = it }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = hafs, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text("قرأتها كما في رواية حفص", fontSize = 14.sp)
                        }
                    }
                    // Nothing more to ask: what it will be drilled on, the verses around it.
                    HifzKind.Skip, HifzKind.Start -> Text(
                        "سندرّبك على " + hifzDrillLabel(
                            HifzPoint(id = 0L, verse = verse, kind = kind!!, dueDay = 0L, createdAt = 0L),
                            catalog.surahs.getOrNull(verse.surah - 1)?.verseCount, gap = 0f,
                        ),
                        fontSize = 13.sp, color = TextMuted, modifier = Modifier.padding(top = 2.dp),
                    )
                    null -> Unit
                }
                HifzRepeatSection("ذكّرني", repeat, repeatDays, plan) { chosen, days -> repeatCode = chosen.encode(); repeatDays = days }
            }
            // Always in view once a kind is chosen, with the reminders the save brings: a filled-in record is never left
            // below the fold, and none is saved with a plan that was not seen.
            if (kind != null) Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "التذكير: " + if (repeat.none) "في المراجعات فقط" else "${HifzReminder.repeatPhrase(repeat)} ${HifzReminder.daysPhrase(repeatDays)}",
                    fontSize = 12.sp, color = TextMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 4.dp).testTag("hifz_record_plan"),
                )
                Button(
                    onClick = {
                        val lookAlike = kind == HifzKind.Similar || kind == HifzKind.Ending
                        onSave(
                            verse, kind, other.takeIf { lookAlike },
                            if (kind == HifzKind.Word) wrongWords.toSet() else setOfNotNull(word ?: suggestedWord),
                            bothWays && lookAlike, hafs && kind == HifzKind.Word, repeat, repeatDays,
                        )
                    },
                    enabled = kind != HifzKind.Word || wrongWords.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                    modifier = Modifier.fillMaxWidth().testTag("hifz_record_save"),
                ) { Text(if (kind == HifzKind.Word && wrongWords.isEmpty()) "اختر الكلمة ثم احفظ" else "حفظ في دفتر التثبيت") }
                Text("يحفظ بلمسة · يمكنك التراجع", fontSize = 12.sp, color = TextMuted)
            }
        }
    }
}

@Composable
private fun HifzChoiceRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) AdhkarSoftGreen else Color.White)
            .border(1.dp, if (selected) GreenPrimary else CardBorder, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp),
    ) { Text((if (selected) "✓ " else "") + title, fontSize = 14.sp, color = GreenPrimaryDark) }
}

/** A suggested look-alike: where it is, how it goes on after the shared words, and why. */
@Composable
private fun HifzLookAlikeRow(catalog: QuranCatalog, verse: QuranVerseReference, candidate: HifzLookAlike, kind: HifzKind, selected: Boolean, onClick: () -> Unit) {
    val words = remember(candidate) { catalog.verse(candidate.verse)?.let { hifzWords(it.text) } } ?: return
    val o = candidate.overlap
    val from = if (kind == HifzKind.Similar) o.prefix.coerceAtMost(words.size - 1) else o.runB.coerceAtMost(words.size - 1)
    val where = if (candidate.verse.surah == verse.surah) "السورة نفسها" else "سورة ${catalog.surahs.getOrNull(candidate.verse.surah - 1)?.name.orEmpty()}"
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) AdhkarSoftGreen else Color.White)
            .border(1.dp, if (selected) GreenPrimary else CardBorder, RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp).testTag("hifz_lookalike_${candidate.verse.surah}_${candidate.verse.ayah}"),
    ) {
        Text(
            (if (selected) "✓ " else "") + listOfNotNull(hifzVerseName(catalog, candidate.verse), hifzPage(catalog, candidate.verse), where).joinToString(" · "),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark,
        )
        HifzVerseText(words, from = from, lead = if (from > 0) "… " else "", fontSize = 16.sp, color = TextDark)
        Text(
            when {
                kind == HifzKind.Similar && o.prefix > 0 -> "تتفقان في أول ${hifzWordsLabel(o.prefix)} ثم تفترقان"
                kind == HifzKind.Ending -> "تتفقان في ${hifzWordsLabel(o.run)} قبل الخاتمة"
                else -> hifzRunLabel(o.run)
            },
            fontSize = 12.sp, color = TextMuted,
        )
    }
}

/** «2:170» or «البقرة 170». */
@Composable
private fun HifzVerseField(catalog: QuranCatalog, onChosen: (QuranVerseReference) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val found = remember(text) { quranGoTo(text, catalog.surahs, catalog.pages.size + 1).filterIsInstance<QuranGoTo.Verse>().firstOrNull()?.verse }
    OutlinedTextField(
        value = text, onValueChange = { text = it }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("hifz_verse_field"),
        placeholder = { Text("مثل 2:170 أو البقرة 170") },
        supportingText = { Text(found?.let { hifzVerseName(catalog, it) } ?: "اكتب السورة ورقم الآية") },
        trailingIcon = { TextButton(onClick = { found?.let(onChosen) }, enabled = found != null) { Text("اختيار") } },
        shape = RoundedCornerShape(12.dp),
    )
}

/** The verse's words as chips; [multi] picks any number (a word slip), otherwise one (where a look-alike parts). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HifzWordPicker(words: HifzWords, selected: Set<Int>, suggested: Int?, multi: Boolean = false, onSelect: (Int) -> Unit) {
    if (suggested != null) Text("اقترحنا الكلمة ${suggested + 1}", fontSize = 12.sp, color = TextMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        words.printed.forEachIndexed { i, printed ->
            val on = i in selected
            val choose = if (multi) Modifier.toggleable(on, role = Role.Checkbox) { onSelect(i) }
                else Modifier.selectable(on, role = Role.RadioButton) { onSelect(i) }
            Text(
                printed,
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) GreenPrimary else Color.White)
                    .border(1.dp, if (on) GreenPrimary else CardBorder, RoundedCornerShape(8.dp))
                    .then(choose).widthIn(min = 40.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                fontFamily = AdhkarReadingFont, fontSize = 16.sp, color = if (on) Color.White else TextDark, textAlign = TextAlign.Center,
            )
        }
    }
}

/** The ways of remembering a difference that teachers suggest; the reader writes their own. */
private val HifzKeyTemplates = listOf("الترتيب الهجائي", "الموضع الوحيد", "الواو قبل الفاء", "النكرة قبل المعرفة", "المعنى", "الصفحة اليمنى أو اليسرى", "اسم السورة")

/** One fix: the right verse on top, the look-alike below, why they are confused, and what to do. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HifzFixSheet(
    catalog: QuranCatalog,
    lookAlikes: HifzLookAlikes?,
    point: HifzPoint,
    today: Long,
    onChange: (HifzPoint) -> Unit,
    onDrill: () -> Unit,
    onListen: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    /** A new plan of reminders; [HifzRepeat.None] stops them. */
    onRepeat: (HifzRepeat, Int) -> Unit,
    /** The length offered for a point that has no plan. */
    defaultDays: Int = 3,
    /** False when the sheet is over another one, where an undo could not be seen. */
    undoable: Boolean = true,
    /** The recitation playing now, shown at the bottom with its controls while the sheet hides the player; null for none. */
    audio: HifzAudio? = null,
    audioControls: HifzAudioControls? = null,
    /** The drills' silence, for the drill button's description. */
    drillGap: Float = 1f,
    /** «أخطأت فيها مرة أخرى»: counts the slip again; null where it does not belong, as over the review. */
    onSlipAgain: (() -> Unit)? = null,
    /** Puts the point back as it was before «أخطأت فيها مرة أخرى». */
    onUndoSlipAgain: (HifzPoint) -> Unit = {},
    /** «خطأ آخر في هذه الآية»: records a slip of another kind on the same verse; null where it does not belong. */
    onOtherSlip: (() -> Unit)? = null,
    /** The verse's other points, each opened from here. */
    siblings: List<HifzPoint> = emptyList(),
    onSibling: (HifzPoint) -> Unit = {},
    /** The heads-up before points is on in the settings; the point's own switch does nothing otherwise. */
    headsUpOn: Boolean = true,
    /** The recitation also stops before points («توقف قبل الموضع لأسمّعه»). */
    stopBeforeOn: Boolean = false,
) {
    val fix = remember(point, lookAlikes) { hifzFix(catalog, point, lookAlikes) }
    val appContext = LocalContext.current.applicationContext
    var plan by remember(point.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(point) { plan = withContext(Dispatchers.IO) { HifzReminder.pointSentence(appContext, point) } }
    /** The point as it was before «أخطأت فيها مرة أخرى», while its undo is offered. */
    var beforeAgain by remember(point.id) { mutableStateOf<HifzPoint?>(null) }
    var key by rememberSaveable(point.id) { mutableStateOf(point.key) }
    val currentPoint by rememberUpdatedState(point)
    val currentKey by rememberUpdatedState(key)
    val currentOnChange by rememberUpdatedState(onChange)
    LaunchedEffect(key) {
        delay(500)
        if (key != currentPoint.key) onChange(currentPoint.copy(key = key))
    }
    DisposableEffect(point.id) {
        onDispose { if (currentKey != currentPoint.key) currentOnChange(currentPoint.copy(key = currentKey)) }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        Column(Modifier.fillMaxWidth().quranSheetHeight()) {
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
                    .testTag("hifz_fix_sheet"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuranPanelHeader("التصحيح", onDismiss)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(hifzPointTitle(catalog, point), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                        Text(
                            listOfNotNull(
                                hifzPage(catalog, point.verse), hifzSlipsLabel(point.slips),
                                "ثبتت".takeIf { point.mastered }, hifzReviewWhen(point.dueDay - today),
                            ).joinToString(" · "),
                            fontSize = 12.sp, color = TextMuted,
                        )
                    }
                    HifzDots(point.slips)
                }
                val again = beforeAgain
                if (again != null) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AdhkarSoftGreen).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("سُجّل الخطأ مرة أخرى · ${hifzReviewWhen(point.dueDay - today)}", Modifier.weight(1f), fontSize = 13.sp, color = GreenPrimaryDark)
                    TextButton(onClick = { beforeAgain = null; onUndoSlipAgain(again) }, modifier = Modifier.testTag("hifz_slip_again_undo")) { Text("تراجع") }
                } else if (onSlipAgain != null) OutlinedButton(
                    onClick = { beforeAgain = point; onSlipAgain() },
                    modifier = Modifier.fillMaxWidth().testTag("hifz_slip_again"),
                ) { Text("أخطأت فيها مرة أخرى") }
                if (fix == null) {
                    Text("هذه الآية غير موجودة في الفهرس", color = TextMuted)
                    return@Column
                }
                HifzFixBody(catalog, fix, lookAlikes, onChange)
                if (fix.reasons.isNotEmpty()) HifzSection("لماذا يلتبس؟") {
                    fix.reasons.forEach { Text(it, fontSize = 14.sp, color = TextDark) }
                }
                HifzSection("ضابطي") {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth().testTag("hifz_key_field"),
                        placeholder = { Text("ما الذي يذكرك بالفرق؟") }, shape = RoundedCornerShape(12.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        HifzKeyTemplates.forEach { template ->
                            SuggestionChip(onClick = {
                                key = if (key.isBlank()) "$template: " else "$template: $key"
                                onChange(point.copy(key = key))
                            }, label = { Text(template, fontSize = 12.sp) })
                        }
                    }
                }
                Button(onClick = onDrill, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.fillMaxWidth().testTag("hifz_drill")) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("درّب", fontWeight = FontWeight.SemiBold)
                        Text(
                            hifzDrillLabel(point, catalog.surahs.getOrNull(point.verse.surah - 1)?.verseCount, drillGap) { catalog.surahs.getOrNull(it - 1)?.name.orEmpty() },
                            fontSize = 12.sp, textAlign = TextAlign.Center,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onListen, modifier = Modifier.weight(1f)) { Text(hifzListenLabel(catalog, point), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("افتح في المصحف", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                val repeating = point.repeatsOn(today)
                key(point.id) {
                    HifzRepeatSection(
                        "التذكير",
                        repeat = if (repeating) point.repeat else HifzRepeat.None,
                        days = point.repeatDays.takeIf { repeating && it in 1..HifzRepeatMaxDays } ?: defaultDays,
                        sentence = plan,
                        onChange = onRepeat,
                    )
                }
                // One switch for this point, for both aids before it while listening: the heads-up and the stop.
                val aids = headsUpOn || stopBeforeOn
                Row(
                    Modifier.fillMaxWidth().toggleable(point.headsUp, enabled = aids, role = Role.Switch) { onChange(point.copy(headsUp = it)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (headsUpOn) "نبهني قبلها أثناء الاستماع" else "توقف قبلها أثناء الاستماع", fontSize = 14.sp, color = if (aids) TextDark else TextMuted)
                        when {
                            !aids -> Text("التنبيه والتوقف قبل المواضع متوقفان في إعدادات التثبيت", fontSize = 12.sp, color = TextMuted)
                            !headsUpOn -> Text("لتقرأها من حفظك قبل أن تسمعها", fontSize = 12.sp, color = TextMuted)
                            stopBeforeOn && point.headsUp -> Text("ويتوقف الاستماع قبلها لتقرأها من حفظك", fontSize = 12.sp, color = TextMuted)
                        }
                    }
                    Switch(checked = point.headsUp && aids, onCheckedChange = null, enabled = aids)
                }
                if (siblings.isNotEmpty()) HifzSection("في هذه الآية أيضا") {
                    siblings.forEach { other ->
                        TextButton(onClick = { onSibling(other) }, contentPadding = PaddingValues(0.dp)) { Text(hifzPointTitle(catalog, other)) }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (onOtherSlip != null) TextButton(onClick = onOtherSlip, modifier = Modifier.testTag("hifz_other_slip")) { Text("خطأ آخر في هذه الآية") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { confirmDelete = true }) { Text("حذف الموضع", color = TextMuted) }
                }
            }
            if (audio != null && audioControls != null) HifzAudioStrip(audio, audioControls, Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("حذف هذا الموضع؟") },
        text = { Text(if (undoable) "يُحذف من دفتر التثبيت مع مراجعاته. يمكنك التراجع بعده مباشرة." else "يُحذف من دفتر التثبيت مع مراجعاته.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("حذف") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } },
    )
}

/**
 * What «درّب» plays, as [com.tunisianprayertimes.quran.hifz.hifzDrill] builds it: «47 و115 بالتناوب · 5 تلاوات ·
 * مع مهلة للترديد», «الربط: 46 ← 47 ← 48 · 3 مرات». A look-alike in another chapter is named with it.
 */
internal fun hifzDrillLabel(point: HifzPoint, verseCount: Int? = null, gap: Float = 1f, surahName: (Int) -> String = { "" }): String {
    val v = point.verse.ayah
    val other = point.other?.takeIf { point.kind == HifzKind.Ending || point.kind == HifzKind.Similar }
    val sequence = when {
        other != null -> {
            val name = if (other.surah != point.verse.surah) "${surahName(other.surah)} ${other.ayah}".trim() else "${other.ayah}"
            "$v و$name بالتناوب · 5 تلاوات"
        }
        point.kind == HifzKind.Skip -> {
            // Only verses that exist: none before the first, none after the last.
            val landed = (point.other?.ayah ?: (v + 1)).takeIf { verseCount == null || it <= verseCount }
            "الربط: " + listOfNotNull((v - 1).takeIf { it >= 1 }, v, landed).joinToString(" ← ") + " · 3 مرات"
        }
        point.kind == HifzKind.Start && v > 1 -> "الربط: ${v - 1} ← $v · 3 مرات"
        else -> "الآية $v · 3 مرات"
    }
    return if (gap > 0f) "$sequence · مع مهلة للترديد" else sequence
}

/** The right wording with what differs marked, and what stands around it. */
@Composable
internal fun HifzFixBody(catalog: QuranCatalog, fix: HifzFix, lookAlikes: HifzLookAlikes?, onChange: ((HifzPoint) -> Unit)? = null) {
    val point = fix.point
    val v = point.verse
    when (point.kind) {
        HifzKind.Ending, HifzKind.Similar -> {
            HifzSection("الصواب هنا · ${hifzVerseName(catalog, v)} · ${hifzPage(catalog, v).orEmpty()}") {
                HifzVerseText(fix.words, marked = fix.differs, partAt = fix.partAt)
            }
            val other = point.other
            val otherWords = fix.otherWords
            if (other != null && otherWords != null) {
                val title = if (other.surah == v.surah) {
                    val gap = other.ayah - v.ayah
                    "وفي موضع آخر من السورة · ${if (gap > 0) "بعد" else "قبل"} ${hifzVersesAfterLabel(kotlin.math.abs(gap))} · ${hifzPage(catalog, other).orEmpty()}"
                } else "وفي ${hifzVerseName(catalog, other)} · ${hifzPage(catalog, other).orEmpty()}"
                HifzSection(title) {
                    Text(hifzVerseName(catalog, other), fontSize = 12.sp, color = TextMuted)
                    val from = if (point.kind == HifzKind.Ending) (otherWords.size - (fix.words.size - (fix.partAt ?: 0)) - 6).coerceIn(0, otherWords.size - 1) else 0
                    HifzVerseText(otherWords, from = from, lead = if (from > 0) "… " else "", marked = fix.otherDiffers, markColor = TextMuted, color = TextMuted, fontSize = 17.sp)
                }
            } else if (onChange != null && lookAlikes != null) {
                val suggestions = remember(lookAlikes, v, point.kind) { if (point.kind == HifzKind.Similar) lookAlikes.similar(v) else lookAlikes.endings(v) }
                if (suggestions.isNotEmpty()) HifzSection("إلى أين ذهبت؟") {
                    suggestions.forEach { candidate ->
                        TextButton(onClick = { onChange(point.copy(other = candidate.verse)) }) {
                            Text("${hifzVerseName(catalog, candidate.verse)} · ${hifzPage(catalog, candidate.verse).orEmpty()}")
                        }
                    }
                }
            }
        }
        HifzKind.Skip -> HifzSection("") {
            fix.before?.let { before ->
                Text("آخر ${v.ayah - 1}:", fontSize = 12.sp, color = TextMuted)
                HifzVerseText(before, from = (before.size - 4).coerceAtLeast(0), lead = "… ", fontSize = 16.sp, color = TextMuted)
            }
            Text("${hifzVerseName(catalog, v)} · ${hifzPage(catalog, v).orEmpty()}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            HifzVerseText(fix.words, marked = setOf(fix.distinctAt))
            val count = catalog.surahs.getOrNull(v.surah - 1)?.verseCount ?: v.ayah
            val landed = (point.other?.ayah ?: (v.ayah + 1)).coerceAtMost(count)
            fix.after?.takeIf { landed > v.ayah }?.let { after ->
                Text("ثم $landed:", fontSize = 12.sp, color = TextMuted)
                HifzVerseText(after, to = (fix.distinctAt + 2).coerceAtMost(after.size), trail = "…", fontSize = 16.sp, color = TextMuted)
            }
            // Where the skip landed, up to 5 verses on; nothing to choose at the last verse.
            if (onChange != null && v.ayah < count) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (v.ayah == 1) "بدل الآية 1 بدأت بالآية" else "بعد ${v.ayah - 1} انتقلت إلى", Modifier.weight(1f), fontSize = 13.sp, color = TextMuted)
                    IconButton(onClick = { onChange(point.copy(other = QuranVerseReference(v.surah, landed - 1))) }, enabled = landed > v.ayah + 1) { Text("−", fontSize = 20.sp) }
                    Text("$landed", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { onChange(point.copy(other = QuranVerseReference(v.surah, landed + 1))) }, enabled = landed < minOf(v.ayah + 5, count)) { Text("+", fontSize = 20.sp) }
                }
            }
        }
        HifzKind.Start -> HifzSection("") {
            fix.before?.let { before ->
                Text("آخر ${v.ayah - 1}:", fontSize = 12.sp, color = TextMuted)
                HifzVerseText(before, from = (before.size - 4).coerceAtLeast(0), lead = "… ", fontSize = 16.sp, color = TextMuted)
            }
            Text("${hifzVerseName(catalog, v)} · ${hifzPage(catalog, v).orEmpty()}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            HifzVerseText(fix.words, marked = (0..fix.distinctAt.coerceAtLeast(1)).toSet())
        }
        HifzKind.Word -> HifzSection("الصواب هنا · ${hifzVerseName(catalog, v)} · ${hifzPage(catalog, v).orEmpty()}") {
            HifzVerseText(fix.words, marked = point.slipWords)
        }
    }
}

/** «قبل التسميع»: the right wordings only, before reciting from memory; also the points a reminder named. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HifzBriefingSheet(
    catalog: QuranCatalog,
    title: String,
    points: List<HifzPoint>,
    always: Boolean,
    /** Null hides «اعرض هذا قبل كل مقطع فيه مواضع». */
    onAlways: ((Boolean) -> Unit)?,
    onListen: () -> Unit,
    onDismiss: () -> Unit,
    header: String = "قبل التسميع",
    hint: String = "نظرة على مواضعك قبل أن تبدأ · اضغط على موضع لتفتح تصحيحه",
    /** Opens a point's fix. */
    onOpen: ((HifzPoint) -> Unit)? = null,
) {
    val fixes = remember(points) { points.sortedBy { it.verse }.mapNotNull { hifzFix(catalog, it) } }
    val pages = points.mapNotNull { catalog.pagesForVerse(it.verse.surah, it.verse.ayah).firstOrNull()?.plus(1) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        Column(Modifier.fillMaxWidth().quranSheetHeight().padding(horizontal = 16.dp).testTag("hifz_briefing_sheet")) {
            QuranPanelHeader(header, onDismiss)
            Text(
                "$title · ${quranPointsLabel(points.size)}" + if (pages.isNotEmpty()) {
                    if (pages.min() == pages.max()) " في الصفحة ${pages.min()}" else " في الصفحات ${pages.min()} إلى ${pages.max()}"
                } else "",
                fontSize = 13.sp, color = TextMuted,
            )
            Text(if (onOpen != null) hint else hint.substringBefore(" · "), fontSize = 13.sp, color = TextMuted)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fixes.forEach { fix -> HifzBriefingItem(catalog, fix, onOpen) }
            }
            if (onAlways != null) Row(Modifier.fillMaxWidth().toggleable(always, role = Role.Checkbox, onValueChange = onAlways), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = always, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text("اعرض هذا حين أفتح سورة فيها مواضع", fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onListen, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.weight(1f).testTag("hifz_briefing_listen")) {
                    Text("استمع إليها · مواضعي صوتا", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = onDismiss) { Text("تم") }
            }
        }
    }
}

@Composable
private fun HifzBriefingItem(catalog: QuranCatalog, fix: HifzFix, onOpen: ((HifzPoint) -> Unit)?) {
    val point = fix.point
    HifzSection("", if (onOpen != null) Modifier.clip(RoundedCornerShape(14.dp)).clickable { onOpen(point) } else Modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${hifzVerseName(catalog, point.verse)} · ${hifzPage(catalog, point.verse).orEmpty()}", Modifier.weight(1f), fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            Text(point.kind.short, fontSize = 12.sp, color = QuranHifzViolet)
        }
        when (point.kind) {
            HifzKind.Skip -> {
                Text(if (point.verse.ayah == 1) "أول السورة:" else "بعد الآية ${point.verse.ayah - 1} تأتي:", fontSize = 12.sp, color = TextMuted)
                HifzVerseText(fix.words, marked = setOf(fix.distinctAt), fontSize = 17.sp)
            }
            else -> {
                Text("${fix.wordingLabel}:", fontSize = 12.sp, color = TextMuted)
                Text(fix.wording, fontFamily = AdhkarReadingFont, fontSize = 17.sp, lineHeight = 32.sp, color = TextDark)
            }
        }
        if (point.key.isNotBlank()) Text("ضابطي: ${point.key}", fontSize = 13.sp, color = QuranHifzViolet)
    }
}

/**
 * The one-line facts of a point, for lists: «سجلته مرة · المراجعة بعد 3 أيام · تذكير بعد كل صلاة حتى الأحد».
 * Each date says what it is, and a held point shows its next check too.
 */
internal fun hifzListDetail(point: HifzPoint, today: Long): String = listOfNotNull(
    hifzSlipsLabel(point.slips),
    "ثبتت".takeIf { point.mastered },
    hifzReviewWhen(point.dueDay - today),
    // «تذكير بعد كل صلاة حتى الأحد», «تذكير 4 مرات في اليوم حتى الأحد»: what brings the point back besides its checks.
    point.takeIf { it.repeatsOn(today) }?.let { "تذكير ${HifzReminder.repeatShortPhrase(it.repeat)} حتى ${HifzReminder.dayLabel(it.repeatUntil!!, today)}" },
).joinToString(" · ")

/** «3 أيام», «أسبوع», «يومان». */
internal fun hifzDaysLabel(days: Int): String = HifzReminder.daysLabel(days)

/**
 * «ذكّرني»: the quick choices first, then «مخصص» for the reader's own prayers and clock times; and for how
 * long, «3 أيام», «أسبوع» or any length up to a month.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HifzRepeatChoice(repeat: HifzRepeat, days: Int, onChange: (HifzRepeat, Int) -> Unit) {
    val colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = GreenPrimary, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White,
    )
    val preset = HifzRepeatPreset.of(repeat)
    // The reader's own plan stays open while it is edited, also when it comes to match a quick choice.
    var custom by rememberSaveable { mutableStateOf(preset == null) }
    var otherLength by rememberSaveable { mutableStateOf(days !in HifzRepeatDays) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HifzRepeatPreset.entries.forEach { option ->
            FilterChip(
                selected = !custom && preset == option, onClick = { custom = false; onChange(option.repeat, days) },
                label = { Text(option.label) }, colors = colors, modifier = Modifier.testTag("hifz_repeat_${option.key}"),
            )
        }
        // Starts from the plan chosen so far, to change one prayer or add a time to it.
        FilterChip(
            selected = custom, onClick = { custom = true }, label = { Text("مخصص") }, colors = colors,
            leadingIcon = { Icon(painterResource(R.drawable.ic_adhkar_clock), null, Modifier.size(16.dp)) },
            modifier = Modifier.testTag("hifz_repeat_custom"),
        )
    }
    if (custom) HifzRepeatEditor(repeat) { onChange(it, days) }
    if (repeat.none) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
        Text("لمدة", fontSize = 13.sp, color = TextMuted, modifier = Modifier.align(Alignment.CenterVertically))
        HifzRepeatDays.forEach { n ->
            FilterChip(
                selected = !otherLength && days == n, onClick = { otherLength = false; onChange(repeat, n) },
                label = { Text(hifzDaysLabel(n)) }, colors = colors, modifier = Modifier.testTag("hifz_repeat_days_$n"),
            )
        }
        FilterChip(
            selected = otherLength,
            onClick = { if (!otherLength) { otherLength = true; if (days in HifzRepeatDays) onChange(repeat, 14) } },
            label = { Text("مدة أخرى") }, colors = colors, modifier = Modifier.testTag("hifz_repeat_days_other"),
        )
    }
    if (otherLength) Row(Modifier.testTag("hifz_repeat_days_stepper"), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(repeat, days - 1) }, enabled = days > 1, modifier = Modifier.testTag("hifz_repeat_days_less")) {
            Icon(painterResource(R.drawable.ic_remove), "يوم أقل")
        }
        Text(
            hifzDaysLabel(days), Modifier.widthIn(min = 84.dp).testTag("hifz_repeat_days_value"), textAlign = TextAlign.Center,
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark,
        )
        IconButton(onClick = { onChange(repeat, days + 1) }, enabled = days < HifzRepeatMaxDays, modifier = Modifier.testTag("hifz_repeat_days_more")) {
            Icon(painterResource(R.drawable.ic_add), "يوم أكثر")
        }
    }
}

/** «مخصص»: after which prayers and at which clock times, so as many reminders a day as the reader wants. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HifzRepeatEditor(repeat: HifzRepeat, onChange: (HifzRepeat) -> Unit) {
    val soft = GreenPrimary.copy(alpha = 0.14f)
    // The time being changed, -1 for a new one; null while no clock is open.
    var picking by rememberSaveable { mutableStateOf<Int?>(null) }
    Column(
        Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("hifz_repeat_editor"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (repeat.none) "اختر متى يذكّرك: بعد صلاة أو في ساعة محددة" else HifzReminder.countPhrase(repeat.perDay),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, modifier = Modifier.testTag("hifz_repeat_count"),
        )
        Text("بعد الصلاة", fontSize = 12.sp, color = TextMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HifzPrayers.forEach { prayer ->
                val on = prayer in repeat.prayers
                FilterChip(
                    selected = on, onClick = { onChange(repeat.copy(prayers = if (on) repeat.prayers - prayer else repeat.prayers + prayer)) },
                    label = { Text(HifzReminder.prayerLabel(prayer)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = soft, selectedLabelColor = GreenPrimaryDark),
                    modifier = Modifier.testTag("hifz_repeat_prayer_${prayer.name}"),
                )
            }
        }
        Text("في ساعة محددة", fontSize = 12.sp, color = TextMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat.minutes.sorted().forEach { minute ->
                val clock = HifzReminder.clock(minute)
                InputChip(
                    selected = true, onClick = { picking = minute }, label = { Text(clock) },
                    trailingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_close), "حذف $clock",
                            Modifier.size(18.dp).clip(CircleShape).clickable { onChange(repeat.copy(minutes = repeat.minutes - minute)) },
                        )
                    },
                    colors = InputChipDefaults.inputChipColors(selectedContainerColor = soft, selectedLabelColor = GreenPrimaryDark),
                    modifier = Modifier.testTag("hifz_repeat_time_$minute"),
                )
            }
            if (repeat.minutes.size < HifzRepeatMaxTimes) AssistChip(
                onClick = { picking = -1 }, label = { Text("إضافة وقت") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp)) },
                modifier = Modifier.testTag("hifz_repeat_add_time"),
            )
        }
    }
    picking?.let { changing ->
        HifzTimeDialog(
            // A new time starts at the next full hour.
            minute = changing.takeIf { it >= 0 } ?: ((LocalTime.now().hour + 1) % 24 * 60),
            onPick = { picked -> picking = null; onChange(repeat.copy(minutes = repeat.minutes - changing + picked)) },
            onRemove = if (changing >= 0) ({ picking = null; onChange(repeat.copy(minutes = repeat.minutes - changing)) }) else null,
            onDismiss = { picking = null },
        )
    }
}

/** A 24-hour clock for one reminder time; [onRemove] when it changes a time already chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HifzTimeDialog(minute: Int, onPick: (Int) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (onRemove == null) "وقت تذكير جديد" else "وقت التذكير") },
        text = {
            // The clock face and «21:30» read left to right in every language.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                TimePicker(
                    state, Modifier.testTag("hifz_time_picker"),
                    colors = TimePickerDefaults.colors(
                        selectorColor = GreenPrimary, clockDialSelectedContentColor = Color.White,
                        timeSelectorSelectedContainerColor = GreenPrimary.copy(alpha = 0.16f), timeSelectorSelectedContentColor = GreenPrimaryDark,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(state.hour * 60 + state.minute) }, modifier = Modifier.testTag("hifz_time_ok")) { Text("تم") }
        },
        dismissButton = {
            Row {
                if (onRemove != null) TextButton(onClick = onRemove, modifier = Modifier.testTag("hifz_time_remove")) {
                    Text("حذف", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("إلغاء") }
            }
        },
    )
}

/** The reminders of one point: the choice, the plan in a sentence, and a word when notifications are off. */
@Composable
internal fun HifzRepeatSection(title: String, repeat: HifzRepeat, days: Int, sentence: String?, onChange: (HifzRepeat, Int) -> Unit) {
    // A choice shows at once, and the next tap builds on it, before a save comes back with it; a save's value
    // on the way there is not shown.
    var shown by remember { mutableStateOf(repeat to days) }
    var pending by remember { mutableStateOf<Pair<HifzRepeat, Int>?>(null) }
    fun same(a: Pair<HifzRepeat, Int>, b: Pair<HifzRepeat, Int>) = a.first == b.first && (a.first.none || a.second == b.second)
    LaunchedEffect(repeat, days) {
        val saved = repeat to days
        if (pending.let { it == null || same(it, saved) }) { shown = saved; pending = null }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("hifz_repeat"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_adhkar_bell), null, Modifier.size(18.dp), tint = GreenPrimary)
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
        }
        HifzRepeatChoice(shown.first, shown.second) { chosen, length ->
            shown = chosen to length
            pending = chosen to length
            onChange(chosen, length)
        }
        if (sentence != null) Text(sentence, fontSize = 13.sp, color = TextDark, modifier = Modifier.testTag("hifz_repeat_plan"))
        HifzNotificationsWarning(listOf(if (shown.first.none) HifzReminder.CHANNEL_ID else HifzReminder.REPEAT_CHANNEL_ID))
    }
}

/**
 * Shown only while the reminders of [channels] cannot reach the reader, saying why, with the one way to allow
 * them. Nothing for no channel: no reminder is on.
 */
@Composable
internal fun HifzNotificationsWarning(channels: List<String>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var check by remember { mutableIntStateOf(0) }
    val problem = remember(channels, check) { HifzReminder.notificationProblem(context, channels) }
    LifecycleResumeEffect(Unit) {
        check++
        onPauseOrDispose { }
    }
    var askedAt by remember { mutableLongStateOf(0L) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        check++
        // Once refused for good, the system answers at once without asking: the settings are then the only way.
        if (!granted && SystemClock.elapsedRealtime() - askedAt < 500) openHifzNotificationSettings(context, channels)
    }
    if (problem == null) return
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 12.dp, end = 4.dp).testTag("hifz_notifications_off"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(problem, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        TextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                askedAt = SystemClock.elapsedRealtime()
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else openHifzNotificationSettings(context, channels)
        }) { Text("السماح") }
    }
}

/** The app's notification settings, or straight the «التثبيت» channel among [channels] the reader turned off. */
internal fun openHifzNotificationSettings(context: Context, channels: List<String>) {
    val channel = HifzReminder.blockedChannel(context, channels)
    val intent = if (NotificationManagerCompat.from(context).areNotificationsEnabled() && channel != null) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channel)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** «استمع إلى 47», or the passage a skip or a hesitation plays: «استمع: 46 ← 47 ← 48», «استمع: من 46 إلى 52». */
internal fun hifzListenLabel(catalog: QuranCatalog, point: HifzPoint): String {
    val passage = hifzPassage(catalog, point)
    val from = passage.from.ayah
    val to = passage.to.ayah
    return when {
        from == to -> "استمع إلى $from"
        to - from <= 2 -> "استمع: " + (from..to).joinToString(" ← ")
        else -> "استمع: من $from إلى $to"
    }
}

/** A recitation a hifz sheet shows at its bottom, since the sheet hides the player. */
@Immutable
internal data class HifzAudio(
    /** «تدريب · 2 من 5», «الاستماع إلى الموضع», «يبدأ الاستماع بعد تنزيل التلاوة». */
    val title: String,
    /** «الصواب · البقرة 47», «دورك: ردّد الآية 47». */
    val line: String,
    val playing: Boolean,
    /** Waiting for its download: nothing to pause yet, only to cancel. */
    val waiting: Boolean = false,
    /** False for the listener's own recitation: a sheet only pauses it, never ends it and its place. */
    val stoppable: Boolean = true,
    /** Recited to its end: said, with nothing to pause or stop. */
    val finished: Boolean = false,
)

internal class HifzAudioControls(val pause: () -> Unit, val resume: () -> Unit, val stop: () -> Unit)

/** What plays, with pause or play and a stop: the hifz sheets cover the player. */
@Composable
internal fun HifzAudioStrip(audio: HifzAudio, controls: HifzAudioControls, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, CardBorder, RoundedCornerShape(14.dp))
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp).testTag("hifz_audio_strip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_quran_headphones), null, Modifier.size(18.dp), tint = GreenPrimary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(audio.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (audio.line.isNotEmpty()) Text(audio.line, fontSize = 12.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!audio.waiting && !audio.finished) IconButton(onClick = if (audio.playing) controls.pause else controls.resume, modifier = Modifier.testTag("hifz_audio_toggle")) {
            Icon(
                painterResource(if (audio.playing) R.drawable.ic_quran_pause else R.drawable.ic_play_arrow),
                if (audio.playing) "إيقاف مؤقت" else "تشغيل", tint = GreenPrimaryDark,
            )
        }
        if (audio.waiting || audio.stoppable) TextButton(onClick = controls.stop, modifier = Modifier.testTag("hifz_audio_stop")) {
            Text(if (audio.waiting) "إلغاء" else "إيقاف")
        }
    }
}

/** «كيف تعمل المراجعة؟»: the three answers, the spacing, «ثبتت» and «يحتاج عناية», in the screen's own words. */
@Composable
internal fun HifzHowItWorksDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("كيف تعمل المراجعة؟") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("كل موضع تسجّله يعود إليك في «مراجعة اليوم»: تُخفى الآية أو الكلمة فتقرؤها من حفظك، ثم تُظهر الصواب وتختار:", fontSize = 14.sp)
                Text("• «أتقنتها»: قرأتها صحيحة دون تردد، فتطول المدة قبل أن تعود: 3 أيام، ثم أسبوع، ثم أسبوعان.", fontSize = 14.sp)
                Text("• «ترددت»: قرأتها صحيحة بعد تردد، فتعود بعد يومين ولا تطول مدتها.", fontSize = 14.sp)
                Text("• «ما زالت تحتاج تدريبًا»: أخطأت أو نسيت، فتعود غدًا وتبدأ مددها من جديد، ويمكنك أن تتدرّب عليها في الحال.", fontSize = 14.sp)
                Text("إذا أتقنتها في مراجعة الأسبوعين صارت «ثبتت»، ثم تعود بعد شهر ثم كل شهرين للتأكد.", fontSize = 14.sp)
                Text("«يحتاج عناية»: موضع سجلته 3 مرات أو أكثر، فيأتي أولًا.", fontSize = 14.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("فهمت") } },
    )
}
