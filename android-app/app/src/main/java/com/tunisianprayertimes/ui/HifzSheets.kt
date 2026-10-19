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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.tunisianprayertimes.quran.hifz.HifzDrillGap
import com.tunisianprayertimes.quran.hifz.todayEpochDay
import com.tunisianprayertimes.quran.hifz.rightSinceSlip
import com.tunisianprayertimes.quran.hifz.HifzSettings
import com.tunisianprayertimes.quran.hifz.withPlan
import com.tunisianprayertimes.quran.hifz.hifzAutoRecalls
import com.tunisianprayertimes.quran.hifz.HifzMaxRecalls
import com.tunisianprayertimes.quran.hifz.HifzPace
import com.tunisianprayertimes.quran.hifz.HifzFix
import com.tunisianprayertimes.quran.hifz.HifzGrade
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzLookAlike
import com.tunisianprayertimes.quran.hifz.HifzLookAlikes
import com.tunisianprayertimes.quran.hifz.HifzPoint
import com.tunisianprayertimes.quran.hifz.HifzPrayers
import com.tunisianprayertimes.quran.hifz.HifzMaxClockTimes
import com.tunisianprayertimes.quran.hifz.HifzReminder
import com.tunisianprayertimes.quran.hifz.HifzTimes
import com.tunisianprayertimes.quran.hifz.HifzTimesPreset
import com.tunisianprayertimes.quran.hifz.HifzWords
import com.tunisianprayertimes.quran.hifz.hifzFix
import com.tunisianprayertimes.quran.hifz.hifzOverlap
import com.tunisianprayertimes.quran.hifz.hifzPassage
import com.tunisianprayertimes.quran.hifz.hifzVersesAfterLabel
import com.tunisianprayertimes.quran.hifz.hifzWords
import com.tunisianprayertimes.quran.hifz.hifzWordsLabel
import com.tunisianprayertimes.quran.hifz.hifzRunLabel
import com.tunisianprayertimes.quran.quranGoTo
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import com.tunisianprayertimes.quran.hifz.HifzFreshGapMs
import com.tunisianprayertimes.quran.hifz.HifzFreshRecalls
import com.tunisianprayertimes.quran.hifz.HifzMasteredStep
import com.tunisianprayertimes.quran.hifz.hifzClosings
import com.tunisianprayertimes.quran.hifz.sameSlip
import com.tunisianprayertimes.quran.normalizeQuranSearch
import com.tunisianprayertimes.quran.quranNameKey
import com.tunisianprayertimes.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** «البقرة 17». */
internal fun hifzVerseName(catalog: QuranCatalog, verse: QuranVerseReference): String =
    "${catalog.surahs.getOrNull(verse.surah - 1)?.name.orEmpty()} ${verse.ayah}"

/** «صفحة 4», the printed page; never «ص 4», which reads as سورة ص. */
internal fun hifzPage(catalog: QuranCatalog, verse: QuranVerseReference): String? =
    catalog.pagesForVerse(verse.surah, verse.ayah).firstOrNull()?.let { "صفحة ${it + 1}" }

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

/** «قراءة واحدة», «قراءتان», «4 قراءات»: right answers, as a count on its own. */
internal fun hifzReadingsLabel(count: Int): String = when {
    count == 1 -> "قراءة واحدة"
    count == 2 -> "قراءتان"
    count % 100 in 3..10 -> "$count قراءات"
    else -> "$count قراءة"
}

/** Why the app suggests [auto] right answers, from the slip's history; see [hifzAutoRecalls]. */
internal fun hifzAutoReason(auto: Int): String = when {
    auto <= 2 -> "مقترح: آية كانت ثابتة، وحفظها يعود أسرع."
    auto == HifzFreshRecalls -> "مقترح لخطأ جديد. وإن عاد الخطأ قبل أن تثبت نطلب ${hifzReadingsLabel(hifzAutoRecalls(2, false))}."
    auto < HifzMaxRecalls -> "مقترح: الخطأ عاد مرة أخرى. وإن عاد ثانية نطلب ${hifzReadingsLabel(HifzMaxRecalls)}."
    else -> "مقترح: الخطأ تكرّر، فيحتاج قراءات أكثر."
}

/** The spaced checks after the right answers, as the plan's last step: when each comes, and what makes it held. */
internal const val HifzLadderSentence = "بعدها تتباعد أسئلتها: بعد يوم، ثم 3 أيام، ثم أسبوع، ثم أسبوعين؛ فإن تذكّرتها فيها دون مساعدة ثبتت."

/** Chosen: one style everywhere a choice is made, solid, never a tint that reads as «suggested». */
@Composable
internal fun hifzChoiceColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = GreenPrimary, selectedLabelColor = Color.White,
)

/**
 * A slip's plan, on «أخطأت» and in the correction: how many right answers, «دون مساعدة», before its checks space
 * out, the app's suggestion ([auto]) chosen unless the reader picks another, and, with more than one reminder a day
 * ([offerDaily]), whether it is asked at each or at the first of each day. [done]: right answers already given since
 * the slip, which still count. In its own dialog: opened over a sheet, it covers nothing the sheet shows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HifzPlanDialog(
    auto: Int,
    pace: HifzPace,
    daily: Boolean,
    offerDaily: Boolean,
    done: Int = 0,
    onChange: (HifzPace, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("خطة الأسئلة") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("hifz_plan_chooser"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("كم قراءة دون مساعدة قبل أن تتباعد أسئلتها؟", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                // Different counts only: the suggestion may already be the most.
                val options = buildList {
                    add(HifzPace.Light to 1)
                    add(HifzPace.Auto to auto)
                    if (auto < HifzMaxRecalls) add(HifzPace.Intense to HifzMaxRecalls)
                }
                val selected = if (pace == HifzPace.Intense && auto >= HifzMaxRecalls) HifzPace.Auto else pace
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    options.forEach { (option, count) ->
                        val on = selected == option
                        FilterChip(
                            selected = on, onClick = { onChange(option, daily) },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(hifzReadingsLabel(count))
                                    // The suggestion is a tag beside its count, whichever is chosen.
                                    if (option == HifzPace.Auto) Text(
                                        "مقترح", Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp))
                                            .background(if (on) Color.White.copy(alpha = 0.22f) else AdhkarSoftGold).padding(horizontal = 5.dp, vertical = 1.dp),
                                        fontSize = 11.sp, color = if (on) Color.White else Color(0xFF6E5A2E),
                                    )
                                }
                            },
                            colors = hifzChoiceColors(),
                            modifier = Modifier.testTag("hifz_plan_${option.key}"),
                        )
                    }
                }
                Text(
                    when (selected) {
                        HifzPace.Light -> "لخطأ عابر: تكفي قراءة دون مساعدة، والأسئلة المتباعدة تكشفه إن عاد."
                        HifzPace.Intense -> "لخطأ يصعب: أكثر القراءات قبل أن تتباعد أسئلته."
                        HifzPace.Auto -> hifzAutoReason(auto)
                    },
                    fontSize = 13.sp, color = TextMuted,
                )
                if (done > 0) Text(
                    "أتممت منذ الخطأ ${HifzReminder.freshLeftPhrase(done)}، وتُحسب لك: إن اخترت $done أو أقل، تتباعد أسئلتها من الغد.",
                    fontSize = 13.sp, color = TextMuted,
                )
                Text(HifzLadderSentence, fontSize = 13.sp, color = TextMuted)
                if (offerDaily) {
                    Text("متى نسألك عنها؟", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, modifier = Modifier.padding(top = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        FilterChip(
                            selected = !daily, onClick = { onChange(pace, false) }, label = { Text("في كل تذكير") },
                            colors = hifzChoiceColors(), modifier = Modifier.testTag("hifz_plan_often"),
                        )
                        FilterChip(
                            selected = daily, onClick = { onChange(pace, true) }, label = { Text("في أول تذكير من اليوم") },
                            colors = hifzChoiceColors(), modifier = Modifier.testTag("hifz_plan_daily"),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("hifz_plan_done")) { Text("تم") } },
    )
}

/**
 * When a point comes back, said everywhere as its next question: «السؤال القادم غدًا», «السؤال القادم بعد 3 أيام»;
 * one whose day has passed, after a journey say, is not «اليوم» but «سؤالها متأخر منذ 9 أيام».
 */
internal fun hifzReviewWhen(days: Long): String = when {
    days >= 0L -> "السؤال القادم ${hifzWhen(days)}"
    days == -1L -> "سؤالها متأخر منذ أمس"
    days == -2L -> "سؤالها متأخر منذ يومين"
    -days in 3L..10L -> "سؤالها متأخر منذ ${-days} أيام"
    else -> "سؤالها متأخر منذ ${-days} يومًا"
}

/**
 * When [point] comes back, for its facts: a fresh one, asked at every reminder, «تتباعد أسئلتها بعد قراءتين دون
 * مساعدة»; any other [hifzReviewWhen].
 */
internal fun hifzPointWhen(point: HifzPoint, today: Long): String =
    if (point.fresh > 0) "تتباعد أسئلتها بعد ${HifzReminder.freshLeftPhrase(point.fresh)}" else hifzReviewWhen(point.dueDay - today)

/** The right answers a fresh point gave toward its plan, «دون مساعدة»: its [recalls] less those still needed. */
internal val HifzPoint.freshDone: Int get() = (recalls - fresh).coerceIn(0, recalls)

/** «أخطأت فيها مرة», «أخطأت فيها مرتين», «أخطأت فيها 3 مرات»: slips recorded and checks failed alike. */
internal fun hifzSlipsLabel(slips: Int): String = when {
    slips <= 1 -> "أخطأت فيها مرة"
    slips == 2 -> "أخطأت فيها مرتين"
    slips % 100 in 3..10 -> "أخطأت فيها $slips مرات"
    else -> "أخطأت فيها $slips مرة"
}

/**
 * After «تثبت بعد»: «سؤال متباعد واحد», «سؤالين متباعدين», «3 أسئلة متباعدة»: the spaced checks, named as the plan
 * names them, each to pass «دون مساعدة».
 */
internal fun hifzChecksLabel(count: Int): String = when (count) {
    1 -> "سؤال متباعد واحد"
    2 -> "سؤالين متباعدين"
    else -> "$count أسئلة متباعدة"
}

/** «البقرة 17 · خاتمة مع البقرة 170», «البقرة 17 · تخطٍّ بعد 16». */
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
 * before word [partAt]; [hidden] words are replaced by one violet «……», the review's blank.
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
                    if (!masked) { withStyle(SpanStyle(color = QuranHifzViolet, fontWeight = FontWeight.SemiBold)) { append("……") }; append(' '); masked = true }
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
    Text(text, modifier, fontFamily = QuranMushafFont, fontSize = fontSize, lineHeight = fontSize * 1.9f, color = color)
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

/**
 * How far the point has come, said for TalkBack too. While it is asked at every reminder, a violet dot for each right
 * answer its plan asks for, filled as they are given, «2/3» beside them; then a green dot for each spaced check on the
 * way to «ثبتت», four in all. An empty dot is a ring, seen on white as a filled one is.
 */
@Composable
internal fun HifzLadder(point: HifzPoint) {
    val fresh = point.fresh > 0
    val label = when {
        point.mastered -> "ثبتت"
        fresh -> "قراءات دون مساعدة: ${point.freshDone} من ${point.recalls}، ثم تتباعد أسئلتها"
        else -> "تثبت بعد ${hifzChecksLabel(point.checksToHold)}"
    }
    val color = if (fresh) QuranHifzViolet else GreenPrimary
    val (filled, all) = if (fresh) point.freshDone to point.recalls else point.step.coerceAtMost(HifzMasteredStep) to HifzMasteredStep
    Row(
        Modifier.clearAndSetSemantics { contentDescription = label }.testTag("hifz_ladder"),
        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(all) { i ->
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(if (i < filled) color else Color.White)
                    .border(1.5.dp, color.copy(alpha = if (i < filled) 1f else 0.6f), CircleShape),
            )
        }
        if (fresh) Text(ltr("${point.freshDone}/${point.recalls}"), Modifier.padding(start = 3.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/**
 * «ما الذي حدث في النساء 47؟»: a kind first, then what more it asks (where the recitation went, which words). The
 * save stays in view with when the slip will be asked, so nothing is saved before it is seen, and nothing the reader
 * did not choose: no look-alike, no parting word is picked for them. A kind already recorded on the verse says so and
 * keeps its look-alike; a second look-alike is a slip of its own. [editing] corrects a point's details instead, with
 * no slip counted. Filled in, the sheet asks before it is closed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HifzRecordSheet(
    catalog: QuranCatalog,
    lookAlikes: HifzLookAlikes?,
    initialVerse: QuranVerseReference,
    onSave: (verse: QuranVerseReference, kind: HifzKind, other: QuranVerseReference?, slipWords: Set<Int>, bothWays: Boolean, pace: HifzPace, daily: Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** Offer the verse before: false when the reader typed the verse themselves. */
    offerPrevious: Boolean = true,
    /** The notebook, to say which kinds this verse already has. */
    points: List<HifzPoint> = emptyList(),
    /** The point corrected; null records a slip. */
    editing: HifzPoint? = null,
    onEdit: (HifzPoint) -> Unit = {},
    /** The reminder is on: the sheet says when it asks, and why it cannot when notifications are off. */
    remind: Boolean = true,
) {
    // Saveable: a re-created activity reopens the sheet as the reader left it.
    var verseKey by rememberSaveable { mutableStateOf((editing?.verse ?: initialVerse).let { "${it.surah}:${it.ayah}" }) }
    val verse = remember(verseKey) { verseKey.split(':').let { QuranVerseReference(it[0].toInt(), it[1].toInt()) } }
    var kindKey by rememberSaveable { mutableStateOf(editing?.kind?.key) }
    val kind = kindKey?.let(HifzKind::of)
    var choosingKind by rememberSaveable { mutableStateOf(editing == null) }
    var forgottenAnchorKey by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsOpen by rememberSaveable(verse, kind) { mutableStateOf(false) }
    val words = remember(verse) { catalog.verse(verse)?.let { hifzWords(it.text) } }
    val count = catalog.surahs.getOrNull(verse.surah - 1)?.verseCount ?: verse.ayah
    // The slips this verse already has, other than the one corrected.
    val onVerse = remember(points, verse, editing) { points.filter { it.verse == verse && it.id != editing?.id } }
    val suggestions = remember(lookAlikes, verse, kind) {
        when (kind) {
            HifzKind.Similar -> lookAlikes?.similar(verse).orEmpty()
            HifzKind.Ending -> lookAlikes?.endings(verse).orEmpty()
            else -> emptyList()
        }
    }
    val closings = remember(catalog, verse, kind) { if (kind == HifzKind.Ending) hifzClosings(catalog, verse) else emptyList() }
    val editingHere = editing?.takeIf { it.verse == verse && it.kind == kind }
    var otherKey by rememberSaveable(verse, kind) { mutableStateOf(editingHere?.other?.let { "${it.surah}:${it.ayah}" }) }
    val other = otherKey?.split(':')?.let { QuranVerseReference(it[0].toInt(), it[1].toInt()) }
    // Whether the reader answered «إلى أين ذهبت؟»: a suggestion, a verse found, or «لا أعرف». Never answered for them.
    var otherChosen by rememberSaveable(verse, kind) { mutableStateOf(editingHere != null) }
    fun chooseOther(value: QuranVerseReference?) {
        otherKey = value?.let { "${it.surah}:${it.ayah}" }
        otherChosen = true
    }
    // A look-alike recorded on this verse before is the reader's own answer, offered again as such.
    LaunchedEffect(verse, kind) {
        if (editing == null && kind?.lookAlike == true && !otherChosen) {
            onVerse.firstOrNull { it.kind == kind && it.other != null }?.other?.let(::chooseOther)
        }
    }
    var word by rememberSaveable(verse, kind) { mutableStateOf(editingHere?.takeIf { it.kind.lookAlike }?.word) }
    // A word slip may miss several words: any of them, in any order.
    var wrongWords by rememberSaveable(verse, kind) {
        mutableStateOf(editingHere?.takeIf { it.kind == HifzKind.Word }?.slipWords?.sorted().orEmpty())
    }
    var wordOpen by rememberSaveable(verse, kind) { mutableStateOf(false) }
    var bothWays by rememberSaveable { mutableStateOf(editing?.bothWays ?: false) }
    var typing by rememberSaveable(verse, kind) { mutableStateOf(false) }
    // Where a skip landed: the verse after it, unless the reader says further.
    var landed by rememberSaveable(verse) {
        mutableIntStateOf(editing?.takeIf { it.kind == HifzKind.Skip && it.verse == verse }?.other?.ayah ?: (verse.ayah + 1))
    }
    // The slip this save counts again, if any: the same verse and kind, and for a look-alike the same verse confused.
    val again = remember(onVerse, verse, kind, other) { kind?.let { k -> onVerse.sameSlip(verse, k, other) }?.takeIf { editing == null } }
    // A word slip recorded again starts from the words chosen before: saving it again is one tap.
    LaunchedEffect(verse, kind) {
        if (editing == null && kind == HifzKind.Word && wrongWords.isEmpty()) again?.slipWords?.sorted()?.let { wrongWords = it }
    }
    val context = LocalContext.current.applicationContext
    // How the slip will be asked: as many right answers as its history calls for (more when it came back), at the
    // reminder's times; the reader may change both. A slip counted again keeps its plan, a passing one judged again.
    var chosenPace by rememberSaveable { mutableStateOf<String?>(null) }
    var chosenDaily by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var planOpen by rememberSaveable { mutableStateOf(false) }
    val pace = chosenPace?.let(HifzPace::of) ?: again?.pace?.takeIf { it != HifzPace.Light } ?: HifzPace.Auto
    val daily = chosenDaily ?: again?.daily ?: false
    val autoRecalls = again?.let { hifzAutoRecalls(it.slips + 1, it.mastered) } ?: HifzFreshRecalls
    val recalls = when (pace) {
        HifzPace.Auto -> autoRecalls
        HifzPace.Light -> 1
        HifzPace.Intense -> HifzMaxRecalls
    }
    val settings = remember { HifzSettings.read(context) }
    var plan by remember { mutableStateOf<String?>(null) }
    // Asked once a day and already asked today, a slip counted again starts tomorrow, as record() saves it.
    val tomorrow = daily && again?.checks?.any { it.day == todayEpochDay() } == true
    LaunchedEffect(recalls, daily, tomorrow, again?.id) {
        if (editing == null) plan = withContext(Dispatchers.IO) {
            // Asked once a day: not on a prayer day a reminder already asked it.
            HifzReminder.freshSentence(context, recalls, daily, tomorrow = tomorrow, askedDay = again?.let { HifzReminder.dailyAskedDay(context, it.id) })
        }
    }
    // Where the recitation probably left the verse, from the look-alike: offered, never saved unless the reader picks it.
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
    var discard by remember { mutableStateOf(false) }
    fun hasChanges(): Boolean {
        if (editing == null) return kindKey != null
        val chosenOther = when {
            kind?.lookAlike == true -> other
            kind == HifzKind.Skip && landed > verse.ayah + 1 -> QuranVerseReference(verse.surah, landed)
            else -> null
        }
        val chosenWords = when {
            kind == HifzKind.Word -> wrongWords.toSet()
            kind?.lookAlike == true -> setOfNotNull(word)
            else -> emptySet()
        }
        return kind != editing.kind || chosenOther != editing.other || chosenWords != editing.slipWords ||
            (bothWays && kind?.lookAlike == true && chosenOther != null) != editing.bothWays
    }
    val close: () -> Unit = { if (!hasChanges()) onDismiss() else discard = true }
    // Filled in, it is not lost to a swipe or a stray tap outside: closing asks first.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = {
        if (it == SheetValue.Hidden && hasChanges()) { discard = true; false } else true
    })
    ModalBottomSheet(onDismissRequest = close, sheetState = sheetState, containerColor = BgCream) {
        Column(Modifier.fillMaxWidth().quranSheetHeight().padding(horizontal = 16.dp).testTag("hifz_record_sheet")) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuranPanelHeader(
                    if (editing != null) "تعديل الموضع · ${hifzVerseName(catalog, verse)}" else "ما الذي حدث في ${hifzVerseName(catalog, verse)}؟",
                    close,
                )
                Text(
                    listOfNotNull(
                        hifzPage(catalog, verse),
                        if (editing != null) "صحّح ما سجّلته؛ لا يُحسب خطأً جديدًا" else "اختر ما حدث في الآية.",
                    ).joinToString(" · "),
                    fontSize = 12.sp, color = TextMuted,
                )
                // The verse in its place, with the end of the one before: the slip is recorded where it is remembered.
                QuranVerseExcerpt(catalog, verse, "${hifzVerseName(catalog, verse)} في المصحف", linesBefore = 1)
                // The slip may belong to the verse before, noticed only once this one began; and back again. The kind chosen stays.
                if (editing == null && initialVerse.ayah > 1 && offerPrevious) TextButton(
                    onClick = { forgottenAnchorKey = null; verseKey = if (verse == initialVerse) "${verse.surah}:${verse.ayah - 1}" else "${initialVerse.surah}:${initialVerse.ayah}" },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        if (verse == initialVerse) "كان الخطأ في الآية السابقة؟ سجّله في الآية ${verse.ayah - 1}" else "رجوع إلى الآية ${initialVerse.ayah}",
                        fontSize = 12.sp,
                    )
                }
                if (kind != null && !choosingKind) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AdhkarSoftGreen).padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(kind.label, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                    TextButton(onClick = { choosingKind = true }) { Text("تغيير") }
                }
                if (kind == null || choosingKind) Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HifzKind.entries.forEach { option ->
                    val selected = kind == option
                    val recorded = onVerse.filter { it.kind == option }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selected) AdhkarSoftGreen else Color.White)
                            .border(1.dp, if (selected) GreenPrimary else CardBorder, RoundedCornerShape(12.dp))
                            .selectable(selected, role = Role.RadioButton) { forgottenAnchorKey = verseKey.takeIf { option == HifzKind.Start }; kindKey = option.key; choosingKind = false }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("hifz_kind_${option.key}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selected) Text("✓ ", Modifier.clearAndSetSemantics {}, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                        Column(Modifier.weight(1f)) {
                            Text(option.label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = GreenPrimaryDark)
                            // Already in the notebook for this verse: recording it again counts it again.
                            if (recorded.isNotEmpty()) Text(
                                "مسجّلة من قبل · ${hifzSlipsLabel(recorded.maxOf { it.slips })}", fontSize = 12.sp, color = QuranHifzViolet,
                            )
                        }
                    }
                } }
                when (kind) {
                    HifzKind.Similar, HifzKind.Ending -> {
                        Text(
                            if (kind == HifzKind.Ending) "بماذا ختمتها؟" else "إلى أين ذهبت؟",
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, modifier = Modifier.padding(top = 6.dp),
                        )
                        // An ending is remembered by its words: the closings around the verse, the one said is tapped.
                        if (kind == HifzKind.Ending && closings.isNotEmpty()) {
                            Text("خواتيم الآيات حولها: اختر ما قلته", fontSize = 12.sp, color = TextMuted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                closings.forEach { (closing, ref) ->
                                    FilterChip(
                                        selected = other == ref, onClick = { chooseOther(ref) },
                                        label = { Text(closing, fontFamily = QuranMushafFont, fontSize = 15.sp) },
                                        colors = hifzChoiceColors(),
                                        modifier = Modifier.testTag("hifz_closing_${ref.surah}_${ref.ayah}"),
                                    )
                                }
                            }
                            closings.firstOrNull { it.second == other }?.let { (_, ref) ->
                                Text(hifzVerseName(catalog, ref), fontSize = 12.sp, color = TextMuted)
                                QuranVerseExcerpt(catalog, ref, "${hifzVerseName(catalog, ref)} في المصحف", mark = QuranExcerptMark.Other)
                            }
                            if (suggestions.isNotEmpty()) Text("أو آية تشترك معها في كلمات قبل الخاتمة:", fontSize = 12.sp, color = TextMuted)
                        }
                        if (lookAlikes == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = GreenPrimary, trackColor = CardBorder)
                        suggestions.forEach { candidate ->
                            HifzLookAlikeRow(catalog, verse, candidate, kind, selected = other == candidate.verse) { chooseOther(candidate.verse) }
                        }
                        if (lookAlikes != null && suggestions.isEmpty() && closings.isEmpty()) Text("لم نجد آية شبيهة بنص قريب؛ ابحث عنها بكلماتها", fontSize = 12.sp, color = TextMuted)
                        val typed = other?.takeIf { o -> suggestions.none { it.verse == o } && closings.none { it.second == o } }
                        if (typed != null) {
                            HifzChoiceRow("${hifzVerseName(catalog, typed)} · ${hifzPage(catalog, typed).orEmpty()}", selected = true) {}
                            QuranVerseExcerpt(catalog, typed, "${hifzVerseName(catalog, typed)} في المصحف", mark = QuranExcerptMark.Other)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { typing = !typing }, label = { Text("آية أخرى…") })
                            FilterChip(selected = otherChosen && other == null, onClick = { chooseOther(null) }, label = { Text("لا أعرف") },
                                colors = hifzChoiceColors(), modifier = Modifier.testTag("hifz_other_unknown"))
                        }
                        if (typing) HifzVerseFinder(catalog, exclude = verse) { chosen -> chooseOther(chosen); typing = false }
                        TextButton(onClick = { detailsOpen = !detailsOpen }, contentPadding = PaddingValues(0.dp)) {
                            Text(if (detailsOpen) "إخفاء التفاصيل الاختيارية" else "تفاصيل إضافية (اختياري)")
                        }
                        if (detailsOpen && words != null) {
                            TextButton(onClick = { wordOpen = !wordOpen }, contentPadding = PaddingValues(0.dp)) {
                                Text("أين افترقت عن الآية؟ (اختياري) ${if (wordOpen) "▴" else "▾"}", fontSize = 13.sp)
                            }
                            if (wordOpen) HifzWordPicker(words, setOfNotNull(word), suggested = suggestedWord) { word = if (word == it) null else it }
                        }
                        if (detailsOpen) Row(Modifier.fillMaxWidth().toggleable(bothWays, role = Role.Checkbox) { bothWays = it }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = bothWays, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("قد أخلط في الاتجاهين", fontSize = 14.sp)
                                Text("فنسألك عن الآيتين كلتيهما", fontSize = 12.sp, color = TextMuted)
                            }
                        }
                    }
                    HifzKind.Word -> if (words != null) {
                        Text("أي كلمة؟", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, modifier = Modifier.padding(top = 6.dp))
                        Text("اختر كل كلمة نسيتها أو أخطأت فيها، ولمسة أخرى تلغيها", fontSize = 12.sp, color = TextMuted)
                        HifzWordPicker(words, wrongWords.toSet(), suggested = null, multi = true) { i ->
                            wrongWords = if (i in wrongWords) wrongWords - i else wrongWords + i
                        }
                    }
                    HifzKind.Skip, HifzKind.Start -> {
                        if (kind == HifzKind.Start && editing == null) {
                            val anchor = forgottenAnchorKey?.split(':')?.mapNotNull { it.toIntOrNull() }
                                ?.takeIf { it.size == 2 }?.let { QuranVerseReference(it[0], it[1]) } ?: verse
                            val next = QuranVerseReference(anchor.surah, anchor.ayah + 1).takeIf { catalog.verse(it) != null }
                            Text("أي آية نسيت؟", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(selected = verse == anchor, onClick = { verseKey = "${anchor.surah}:${anchor.ayah}" },
                                    label = { Text("هذه الآية (${anchor.ayah})") }, colors = hifzChoiceColors(), modifier = Modifier.testTag("hifz_forgot_current"))
                                if (next != null) FilterChip(selected = verse == next, onClick = { verseKey = "${next.surah}:${next.ayah}" },
                                    label = { Text("الآية التي بعدها (${next.ayah})") }, colors = hifzChoiceColors(), modifier = Modifier.testTag("hifz_forgot_next"))
                            }
                            Text(
                                if (verse.ayah > 1) "يُسجّل النسيان في الآية ${verse.ayah}. نتدرّب على الانتقال من الآية ${verse.ayah - 1} إليها."
                                else "يُسجّل النسيان في أول السورة، ونتدرّب على بدايتها.",
                                fontSize = 13.sp, color = TextMuted, modifier = Modifier.testTag("hifz_forgot_explanation"),
                            )
                        }
                        // Where the recitation went, for a skip: often further than the next verse.
                        if (kind == HifzKind.Skip && verse.ayah < count) HifzSkipDestination(catalog, verse, landed) { landed = it }
                        Text(
                            "التدريب بعد الحفظ: " + hifzDrillLabel(
                                HifzPoint(id = 0L, verse = verse, kind = kind, other = QuranVerseReference(verse.surah, landed), dueDay = 0L, createdAt = 0L),
                                count, gap = HifzDrillGap,
                            ),
                            fontSize = 13.sp, color = TextMuted, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    null -> Unit
                }
            }
            // Always in view once a kind is chosen, with what the save does: a filled-in record is never left below the
            // fold, none is saved without saying when it will be asked, and a slip counted again says so.
            if (kind != null) Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    again != null -> Text(
                        "سجلت هذا الخطأ من قبل: يُحسب مرة أخرى، ونسألك عنه من جديد", fontSize = 12.sp, color = QuranHifzViolet,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 4.dp).testTag("hifz_record_again"),
                    )
                    kind.lookAlike && other != null && onVerse.any { it.kind == kind && it.other != null } -> Text(
                        "يُسجَّل موضعا جديدا مع ${hifzVerseName(catalog, other)}، ويبقى ما سجّلته من قبل", fontSize = 12.sp, color = QuranHifzViolet,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (editing == null) plan?.let {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, Modifier.weight(1f).testTag("hifz_record_plan"), fontSize = 12.sp, color = TextMuted)
                        // Named for what it changes: the kind above has its own «تغيير».
                        TextButton(onClick = { planOpen = true }, modifier = Modifier.testTag("hifz_record_plan_change")) {
                            Text("تغيير الخطة")
                        }
                    }
                }
                if (editing == null && planOpen) HifzPlanDialog(
                    // Offered with several reminders a day, and always to undo a once a day already set.
                    auto = autoRecalls, pace = pace, daily = daily, offerDaily = (settings.reminder && settings.times.count > 1) || daily,
                    onChange = { newPace, newDaily -> chosenPace = newPace.key; chosenDaily = newDaily },
                    onDismiss = { planOpen = false },
                )
                if (editing == null && remind) HifzNotificationsWarning(listOf(HifzReminder.CHANNEL_ID), Modifier.padding(bottom = 4.dp))
                val ready = (kind != HifzKind.Word || wrongWords.isNotEmpty()) && (!kind.lookAlike || otherChosen)
                Button(
                    onClick = {
                        val skipTo = QuranVerseReference(verse.surah, landed).takeIf { kind == HifzKind.Skip && landed > verse.ayah + 1 }
                        val chosenOther = if (kind.lookAlike) other else skipTo
                        val slipWords = when {
                            kind == HifzKind.Word -> wrongWords.toSet()
                            kind.lookAlike -> setOfNotNull(word)
                            else -> emptySet()
                        }
                        val both = bothWays && kind.lookAlike && chosenOther != null
                        if (editing != null) onEdit(editing.copy(kind = kind, other = chosenOther, slipWords = slipWords, bothWays = both))
                        else onSave(verse, kind, chosenOther, slipWords, both, pace, daily)
                    },
                    enabled = ready,
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                    modifier = Modifier.fillMaxWidth().testTag("hifz_record_save"),
                ) {
                    Text(
                        when {
                            editing != null -> "حفظ التعديل"
                            kind == HifzKind.Word && wrongWords.isEmpty() -> "اختر الكلمة"
                            kind.lookAlike && !otherChosen -> if (kind == HifzKind.Ending) "اختر الخاتمة أو «لا أعرف»" else "اختر الآية أو «لا أعرف»"
                            kind.lookAlike && other != null -> "حفظ · مع ${hifzVerseName(catalog, other)}"
                            kind.lookAlike -> "حفظ دون الآية الشبيهة"
                            else -> "حفظ في دفتر المواضع"
                        },
                    )
                }
                if (editing == null) Text("يُحفظ بلمسة · يمكنك إلغاء الحفظ بعده", fontSize = 12.sp, color = TextMuted)
            }
        }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text(if (editing != null) "تجاهل التعديل؟" else "تجاهل هذا الموضع؟") },
        text = { Text("لن يُحفظ ما اخترته.") },
        confirmButton = { TextButton(onClick = { discard = false; onDismiss() }, modifier = Modifier.testTag("hifz_record_discard")) { Text("تجاهل") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("متابعة") } },
    )
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
        if (!selected) HifzVerseText(words, from = from, lead = if (from > 0) "… " else "", fontSize = 16.sp, color = TextDark)
        Text(
            when {
                kind == HifzKind.Similar && o.prefix > 0 -> "تتفقان في أول ${hifzWordsLabel(o.prefix)} ثم تفترقان"
                kind == HifzKind.Ending -> "تتفقان في ${hifzWordsLabel(o.run)} قبل الخاتمة"
                else -> hifzRunLabel(o.run)
            },
            fontSize = 12.sp, color = TextMuted,
        )
        // The one chosen, in its place: where it sits on its page often tells it from the verse it is confused with.
        if (selected) QuranVerseExcerpt(
            catalog, candidate.verse, "${hifzVerseName(catalog, candidate.verse)} في المصحف",
            Modifier.padding(top = 6.dp), mark = QuranExcerptMark.Other,
        ) { HifzVerseText(words, from = from, lead = if (from > 0) "… " else "", fontSize = 16.sp) }
    }
}

/**
 * Finds a verse as a hafiz remembers it: by words from it, by its page («صفحة 51», «ص 51», «51»), or by its reference
 * («2:170», «البقرة 170»). Every match is a row to tap; nothing is chosen for the reader.
 */
@Composable
internal fun HifzVerseFinder(
    catalog: QuranCatalog,
    modifier: Modifier = Modifier,
    /** The verse being recorded, never its own look-alike. */
    exclude: QuranVerseReference? = null,
    /** Skip destinations stay within the surah, after the missed verse. */
    onlySurah: Int? = null,
    afterAyah: Int = 0,
    onChosen: (QuranVerseReference) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var visibleCount by rememberSaveable(text) { mutableIntStateOf(8) }
    // Off the main thread: the index's first search builds it.
    val lookup by produceState("" to emptyList<QuranVerseReference>(), text, catalog, exclude, onlySurah, afterAyah) {
        val matches = if (text.isBlank()) emptyList() else withContext(Dispatchers.Default) { hifzFindVerses(catalog, text, Int.MAX_VALUE) }
            .filter { it != exclude && (onlySurah == null || it.surah == onlySurah) && it.ayah > afterAyah }
        value = text to matches
    }
    val searching = lookup.first != text
    val found = if (searching) emptyList() else lookup.second
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("hifz_verse_field"),
            placeholder = { Text("كلمات من الآية، أو الصفحة، أو البقرة 170") },
            supportingText = when {
                searching -> ({ Text("جارٍ البحث…") })
                text.isNotBlank() && found.isEmpty() -> ({ Text(
                    if (onlySurah != null) "اختر آية بعد $afterAyah من سورة ${catalog.surahs.getOrNull(onlySurah - 1)?.name.orEmpty()}"
                    else "لم نجد آية بهذا بعد · جرّب كلمات أخرى أو «صفحة 51»",
                ) })
                else -> null
            },
            trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(painterResource(R.drawable.ic_adhkar_close), "مسح البحث") } },
            shape = RoundedCornerShape(12.dp),
        )
        if (found.isNotEmpty()) Text("${found.size} نتيجة", fontSize = 12.sp, color = TextMuted)
        found.take(visibleCount).forEach { verse ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                    .clickable { onChosen(verse) }.padding(horizontal = 12.dp, vertical = 8.dp).testTag("hifz_found_${verse.surah}_${verse.ayah}"),
            ) {
                Text(listOfNotNull(hifzVerseName(catalog, verse), hifzPage(catalog, verse)).joinToString(" · "), fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                catalog.verse(verse)?.let { found ->
                    val w = hifzWords(found.text)
                    val snippet = remember(found, text) {
                        // Matched on the words' comparison keys, as the search found the verse: the printed words
                        // are in the mushaf's spelling, with marks the typed text never has.
                        val terms = normalizeQuranSearch(text).split(' ').filter { it.length >= 2 }
                        val matches = w.keys.mapIndexedNotNull { i, key -> i.takeIf { terms.any { key.contains(it) } } }.toSet()
                        val from = ((matches.minOrNull() ?: 0) - 2).coerceAtLeast(0)
                        val to = (from + 10).coerceAtMost(w.size)
                        buildAnnotatedString {
                            if (from > 0) append("… ")
                            for (i in from until to) {
                                if (i in matches) withStyle(SpanStyle(color = GreenPrimaryDark, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)) { append(w.printed[i]) }
                                else append(w.printed[i])
                                append(' ')
                            }
                            if (to < w.size) append("…")
                        }
                    }
                    Text(snippet, fontFamily = QuranMushafFont, fontSize = 16.sp, lineHeight = 16.sp * 1.9f, color = TextDark)
                }
            }
        }
        if (found.size > visibleCount) TextButton(onClick = { visibleCount += 8 }, modifier = Modifier.fillMaxWidth()) {
            Text("عرض المزيد · ${found.size - visibleCount} نتائج أخرى")
        }
    }
}

/** Quick adjacent choices, with the full verse finder for a jump anywhere later in the surah. */
@Composable
private fun HifzSkipDestination(catalog: QuranCatalog, verse: QuranVerseReference, landed: Int, onChange: (Int) -> Unit) {
    val count = catalog.surahs.getOrNull(verse.surah - 1)?.verseCount ?: verse.ayah
    var choosing by rememberSaveable(verse) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (verse.ayah == 1) "بدل الآية 1 بدأت بالآية" else "بعد ${verse.ayah - 1} انتقلت إلى", fontSize = 13.sp, color = TextMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(landed - 1) }, enabled = landed > verse.ayah + 1) {
                Icon(painterResource(R.drawable.ic_remove), "الآية السابقة")
            }
            Text("$landed", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("hifz_skip_landed"))
            IconButton(onClick = { onChange(landed + 1) }, enabled = landed < count) {
                Icon(painterResource(R.drawable.ic_add), "الآية التالية")
            }
            TextButton(onClick = { choosing = !choosing }, modifier = Modifier.testTag("hifz_skip_choose")) {
                Text(if (choosing) "إغلاق البحث" else "اختر آية أخرى…")
            }
        }
        if (choosing) {
            Text("ابحث بكلمات الآية أو اكتب مثل «${catalog.surahs.getOrNull(verse.surah - 1)?.name.orEmpty()} $count».", fontSize = 12.sp, color = TextMuted)
            HifzVerseFinder(catalog, onlySurah = verse.surah, afterAyah = verse.ayah) {
                onChange(it.ayah)
                choosing = false
            }
        }
    }
}

/**
 * The verses [input] names: a reference first, then a page and its verses, then the verses holding its words, nearest
 * match first; at most [limit] for words.
 */
internal fun hifzFindVerses(catalog: QuranCatalog, input: String, limit: Int = 8): List<QuranVerseReference> {
    val text = input.trim().map { c -> c.digitToIntOrNull()?.digitToChar() ?: c }.joinToString("")
    if (text.isEmpty()) return emptyList()
    quranGoTo(text, catalog.surahs, catalog.pages.size + 1).filterIsInstance<QuranGoTo.Verse>().firstOrNull()?.let { return listOf(it.verse) }
    val typed = hifzTypedVerse(text, catalog)
    // A page, as many huffaz say where a passage is: its verses, to tap the one. «ص 51» may also be سورة ص 51, first.
    val page = Regex("^(?:صفحة|ص)\\s*(\\d{1,3})$").matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("^\\d{1,3}$").matchEntire(text)?.value?.toIntOrNull()
    if (page != null) {
        val onPage = catalog.versesOnPage(page - 1).filter { it.ayah > 0 }.map { it.reference }.distinct()
        return (listOfNotNull(typed) + onPage).distinct()
    }
    typed?.let { return listOf(it) }
    if (text.count { it.isLetter() } < 3) return emptyList()
    return catalog.search(text).flatMap { it.verses }.distinct().take(limit)
}

/** «النساء47», «النساء: 47», «ص 51»: a verse typed loosely, which the index's own reading does not take. */
internal fun hifzTypedVerse(text: String, catalog: QuranCatalog): QuranVerseReference? {
    val clean = text.trim().map { c -> c.digitToIntOrNull()?.digitToChar() ?: c }.joinToString("")
    val match = Regex("^(.*\\D)\\s*(\\d{1,3})$").matchEntire(clean) ?: return null
    val name = match.groupValues[1].trimEnd(' ', ':', '：')
    val surah = catalog.surahs.firstOrNull { quranNameKey(it.name) == quranNameKey(name) } ?: return null
    val ayah = match.groupValues[2].toInt()
    return QuranVerseReference(surah.number, ayah).takeIf { ayah >= 1 && (surah.verseCount == null || ayah <= surah.verseCount) }
}

/**
 * The verse's words as chips; [multi] picks any number (a word slip), otherwise one (where a look-alike parts), and a
 * second tap takes a choice back. [suggested] is only outlined, never chosen until tapped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HifzWordPicker(words: HifzWords, selected: Set<Int>, suggested: Int?, multi: Boolean = false, onSelect: (Int) -> Unit) {
    if (suggested != null && suggested !in selected) Text(withQuranQuotes("نقترح «${words.printed[suggested]}» · المسها لتختارها"), fontSize = 12.sp, color = TextMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        words.printed.forEachIndexed { i, printed ->
            val on = i in selected
            val hinted = i == suggested && !on
            Text(
                printed,
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) GreenPrimary else Color.White)
                    .border(if (hinted) 1.5.dp else 1.dp, when { on -> GreenPrimary; hinted -> QuranHifzViolet; else -> CardBorder }, RoundedCornerShape(8.dp))
                    .toggleable(on, role = if (multi) Role.Checkbox else Role.RadioButton) { onSelect(i) }
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp).padding(horizontal = 10.dp, vertical = 10.dp),
                fontFamily = QuranMushafFont, fontSize = 16.sp, color = if (on) Color.White else TextDark, textAlign = TextAlign.Center,
            )
        }
    }
}

/** The ways teachers suggest to remember a difference between look-alikes; the reader writes their own. */
private val HifzKeyTemplates = listOf("الترتيب الهجائي", "الموضع الوحيد", "الواو قبل الفاء", "النكرة قبل المعرفة", "المعنى", "الصفحة اليمنى أو اليسرى", "اسم السورة")

/** What «ضابطي» asks, by kind: a skip, a start or a word has no pair to tell apart. */
private fun hifzKeyPlaceholder(point: HifzPoint): String = when (point.kind) {
    HifzKind.Ending, HifzKind.Similar -> "ما الذي يذكّرك بالفرق؟"
    HifzKind.Skip -> if (point.verse.ayah > 1) "ما الذي يربط آخر ${point.verse.ayah - 1} بأول ${point.verse.ayah}؟" else "ما الذي يذكّرك بأول السورة؟"
    HifzKind.Start -> "ما الذي يذكّرك بأول الآية؟"
    HifzKind.Word -> "ما الذي يذكّرك بالكلمة الصحيحة؟"
}

/**
 * One fix: the right verse on top, the look-alike below, each on its mushaf page and in text, why they are confused,
 * and what to do. «تدرّب» and «استمع» stay in view at its foot; what counts a slip or deletes the point waits at its end.
 * [onEdit] corrects what was recorded; [onSettings] opens the settings a switch here depends on; [onOpen] is null where
 * the page is in view already, over the review.
 */
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
    onOpen: (() -> Unit)?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    /** False when the sheet is over another one, where an undo could not be seen. */
    undoable: Boolean = true,
    /** The recitation playing now, shown at the bottom with its controls while the sheet hides the player; null for none. */
    audio: HifzAudio? = null,
    audioControls: HifzAudioControls? = null,
    /** «أخطأت فيها مرة أخرى»: counts the slip again; null where it does not belong, as over the review. */
    onSlipAgain: (() -> Unit)? = null,
    /** Puts the point back as it was before «أخطأت فيها مرة أخرى». */
    onUndoSlipAgain: (HifzPoint) -> Unit = {},
    /** «خطأ آخر في هذه الآية»: records a slip of another kind on the same verse; null where it does not belong. */
    onOtherSlip: (() -> Unit)? = null,
    /** The verse's other points, each opened from here. */
    siblings: List<HifzPoint> = emptyList(),
    onSibling: (HifzPoint) -> Unit = {},
    onEdit: (() -> Unit)? = null,
    /** The reminder is on: the sheet says when it asks, and why it cannot when notifications are off. */
    remind: Boolean = true,
    /**
     * Its plan can change here: not over the review, where a count already reached would end the questions of the card
     * in view, which would then be answered as a spaced check the same day.
     */
    planChangeable: Boolean = true,
) {
    val fix = remember(point, lookAlikes) { hifzFix(catalog, point, lookAlikes) }
    val appContext = LocalContext.current.applicationContext
    var plan by remember(point.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(point) { plan = withContext(Dispatchers.IO) { HifzReminder.pointSentence(appContext, point) } }
    var planOpen by rememberSaveable(point.id) { mutableStateOf(false) }
    // A plan whose count is already reached ends the questions: its dialog goes, and does not come back by itself.
    LaunchedEffect(point.fresh) { if (point.fresh == 0) planOpen = false }
    val planSettings = remember { HifzSettings.read(appContext) }
    /** The point as it was before «أخطأت فيها مرة أخرى», while its undo is offered. */
    var beforeAgain by remember(point.id) { mutableStateOf<HifzPoint?>(null) }
    var key by rememberSaveable(point.id) { mutableStateOf(point.key) }
    val keyFocus = remember { FocusRequester() }
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
    var manageOpen by rememberSaveable(point.id) { mutableStateOf(false) }
    var editKey by rememberSaveable(point.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        Column(Modifier.fillMaxWidth().quranSheetHeight()) {
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)
                    .testTag("hifz_fix_sheet"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("التصحيح", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                    if (onOpen != null) {
                        if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) IconButton(onClick = onOpen, modifier = Modifier.testTag("hifz_fix_open")) {
                            Icon(painterResource(R.drawable.ic_quran_locate), "في المصحف", Modifier.size(22.dp), tint = GreenPrimaryDark)
                        } else TextButton(onClick = onOpen, modifier = Modifier.testTag("hifz_fix_open")) { Text("في المصحف") }
                    }
                    if (onEdit != null) IconButton(onClick = onEdit, modifier = Modifier.testTag("hifz_fix_edit")) {
                        Icon(painterResource(R.drawable.ic_edit), "تعديل الموضع", Modifier.size(20.dp), tint = GreenPrimaryDark)
                    }
                    IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_adhkar_close), "إغلاق") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(hifzPointTitle(catalog, point), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                        Text(
                            listOfNotNull(
                                hifzPage(catalog, point.verse), hifzSlipsLabel(point.slips), "يحتاج عناية".takeIf { point.needsCare },
                                // A spaced check's time is said once, by the line under it, which knows its answer is in view.
                                "ثبتت".takeIf { point.mastered }, hifzPointWhen(point, today).takeIf { point.fresh > 0 },
                            ).joinToString(" · "),
                            fontSize = 12.sp, color = TextMuted,
                        )
                        // When it is asked next, at which reminder.
                        plan?.let { Text(it, fontSize = 12.sp, color = TextMuted, modifier = Modifier.testTag("hifz_fix_plan")) }
                        // While it is fresh, how many right answers it asks for, and how often, can change.
                        if (point.fresh > 0 && planChangeable) TextButton(
                            onClick = { planOpen = true }, contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.testTag("hifz_fix_plan_change"),
                        ) {
                            Text("الخطة: ${hifzReadingsLabel(point.recalls)} دون مساعدة${if (point.daily) "، في أول تذكير من اليوم" else ""} · تغيير", fontSize = 12.sp)
                        }
                        if (remind) HifzNotificationsWarning(listOf(HifzReminder.CHANNEL_ID), Modifier.padding(top = 2.dp))
                        if (!point.mastered && point.fresh == 0) Text("تثبت بعد ${hifzChecksLabel(point.checksToHold)}", fontSize = 12.sp, color = GreenPrimary)
                         point.recentFeedback?.let { Text(it, fontSize = 13.sp, color = GreenPrimaryDark) }
                    }
                    HifzLadder(point)
                }
                if (point.fresh > 0 && planOpen) HifzPlanDialog(
                    auto = point.autoRecalls, pace = point.pace, daily = point.daily,
                    offerDaily = (planSettings.reminder && planSettings.times.count > 1) || point.daily, done = point.rightSinceSlip,
                    onChange = { newPace, newDaily -> onChange(point.withPlan(newPace, newDaily, today)) },
                    onDismiss = { planOpen = false },
                )
                if (fix == null) {
                    Text("هذه الآية غير موجودة في الفهرس", color = TextMuted)
                    return@Column
                }
                HifzFixBody(catalog, fix, lookAlikes, onChange, onPickOther = onEdit, learningNotes = false)
                if (fix.reasons.isNotEmpty()) HifzSection("لماذا يلتبس؟") {
                    fix.reasons.forEach { Text(withQuranQuotes(it), fontSize = 14.sp, color = TextDark) }
                }
                HifzSection("ما يساعدني على التذكّر") {
                    if (!editKey) {
                        if (key.isNotBlank()) Text(withQuranQuotes(key), fontSize = 15.sp, color = GreenPrimaryDark, modifier = Modifier.testTag("hifz_saved_key"))
                        TextButton(onClick = { editKey = true }, modifier = Modifier.testTag("hifz_edit_key")) {
                            Text(if (key.isBlank()) "أضف كلمة تساعدك على التذكّر" else "تعديل ملاحظتي")
                        }
                    } else {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth().focusRequester(keyFocus).testTag("hifz_key_field"),
                        placeholder = { Text(hifzKeyPlaceholder(point)) }, shape = RoundedCornerShape(12.dp),
                    )
                    Text("تُحفظ ملاحظتك تلقائيًا", fontSize = 12.sp, color = TextMuted)
                    // Only for a pair to tell apart: a fact the app can vouch for first, then the teachers' headings, each
                    // added after what is written, once.
                    if (point.kind.lookAlike) {
                        val order = point.other?.let { o ->
                            val (first, second) = if (point.verse < o) point.verse to o else o to point.verse
                            "في المصحف: ${hifzVerseName(catalog, first)} قبل ${hifzVerseName(catalog, second)}"
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            (listOfNotNull(order) + HifzKeyTemplates).filter { it !in key }.forEach { template ->
                                SuggestionChip(onClick = {
                                    key = if (key.isBlank()) "$template: " else key.trimEnd() + "\n$template: "
                                    keyFocus.requestFocus()
                                }, label = { Text(template, fontSize = 12.sp) })
                            }
                        }
                    }
                    TextButton(onClick = { editKey = false }) { Text("تم") }
                    }
                }
                HifzPointHistory(point)
                Text(
                    "«تدرّب»: " + hifzDrillLabel(point, catalog.surahs.getOrNull(point.verse.surah - 1)?.verseCount, HifzDrillGap) { catalog.surahs.getOrNull(it - 1)?.name.orEmpty() },
                    fontSize = 12.sp, color = TextMuted,
                )
                TextButton(onClick = { manageOpen = !manageOpen }, modifier = Modifier.testTag("hifz_fix_details")) {
                    Text(if (manageOpen) "إخفاء خيارات الموضع" else "خيارات الموضع")
                }
                if (manageOpen) {
                if (siblings.isNotEmpty()) HifzSection("في هذه الآية أيضا") {
                    siblings.forEach { other ->
                        TextButton(onClick = { onSibling(other) }, contentPadding = PaddingValues(0.dp)) { Text(hifzPointTitle(catalog, other)) }
                    }
                }
                // What counts a slip or deletes the point, apart at the end: never the first thing under the finger.
                val again = beforeAgain
                if (again != null) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AdhkarSoftGreen).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text("سُجّل الخطأ مرة أخرى", fontSize = 13.sp, color = GreenPrimaryDark)
                        // When it is asked again, now that it is fresh again: said here, not found later.
                        plan?.let { Text(it, fontSize = 12.sp, color = TextMuted) }
                    }
                    TextButton(onClick = { beforeAgain = null; onUndoSlipAgain(again) }, modifier = Modifier.testTag("hifz_slip_again_undo")) { Text("إلغاء التسجيل") }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
                    if (onSlipAgain != null && again == null) TextButton(
                        onClick = { beforeAgain = point; onSlipAgain() }, modifier = Modifier.testTag("hifz_slip_again"),
                    ) { Text("أخطأت فيها مرة أخرى") }
                    if (onOtherSlip != null) TextButton(onClick = onOtherSlip, modifier = Modifier.testTag("hifz_other_slip")) { Text("خطأ آخر في هذه الآية") }
                    TextButton(onClick = { confirmDelete = true }) { Text("حذف الموضع", color = TextMuted) }
                }
                }
            }
            // Practice, always within reach: the drill, and the right wording heard.
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (audio != null && audioControls != null) HifzAudioStrip(audio, audioControls)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onDrill, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.weight(1f).testTag("hifz_drill")) {
                        Text("تدرّب", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(onClick = onListen, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                        Text("استمع", textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("حذف هذا الموضع؟") },
        text = { Text(if (undoable) "يُحذف من دفتر المواضع مع مراجعاته. يمكنك التراجع بعده مباشرة." else "يُحذف من دفتر المواضع مع مراجعاته.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("حذف") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } },
    )
}

/** Every answer given to the point's questions, the latest first: each question asked counts. */
@Composable
private fun HifzPointHistory(point: HifzPoint) {
    var all by rememberSaveable(point.id) { mutableStateOf(false) }
    HifzSection("نتائج أسئلتك", Modifier.testTag("hifz_point_history")) {
        if (point.checks.isEmpty()) {
            Text("لا توجد نتائج مراجعة مسجّلة لهذا الموضع بعد.", fontSize = 13.sp, color = TextMuted)
        } else {
            val recent = point.checks.asReversed()
            (if (all) recent else recent.take(3)).forEach { check ->
                val date = remember(check.day) {
                    runCatching { LocalDate.ofEpochDay(check.day).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale("ar", "TN"))) }
                        .getOrElse { check.day.toString() }
                }
                val outcome = when (check.grade) {
                    HifzGrade.Held -> "تذكّرتها دون مساعدة"
                    HifzGrade.Hesitated -> "تردد أو احتجت مساعدة"
                    HifzGrade.Again -> "نسيت أو أخطأت"
                }
                Text("$date · $outcome", fontSize = 13.sp, color = if (check.grade == HifzGrade.Held) GreenPrimaryDark else TextDark)
            }
            if (recent.size > 3) TextButton(onClick = { all = !all }) {
                Text(if (all) "عرض آخر 3 مراجعات" else "عرض كل المراجعات (${recent.size})")
            }
        }
    }
}

/**
 * What «تدرّب» plays, as [com.tunisianprayertimes.quran.hifz.hifzDrill] builds it: «47 و115 بالتناوب · 5 تلاوات ·
 * مع مهلة للترديد», «الربط: 46 ← 47 ← 48 · 3 تكرارات». Repeats are «تكرارات», never «مرات», which count right
 * answers and reminders. A look-alike in another chapter is named with it.
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
            "الربط: " + listOfNotNull((v - 1).takeIf { it >= 1 }, v, landed).joinToString(" ← ") + " · 3 تكرارات"
        }
        point.kind == HifzKind.Start && v > 1 -> "الربط: ${v - 1} ← $v · 3 تكرارات"
        else -> "الآية $v · 3 تكرارات"
    }
    return if (gap > 0f) "$sequence · مع مهلة للترديد" else sequence
}

/** A short next step for the difficulty; Quran wording is shown in the scan, not repeated beneath it. */
internal fun hifzPracticeHint(point: HifzPoint): String = when (point.kind) {
    HifzKind.Similar -> "قارن الموضعين، ثم اقرأ كل آية من حفظك دون الانتقال إلى الأخرى."
    HifzKind.Ending -> "لاحظ خاتمة كل موضع، ثم اربطها بما قبلها واقرأ الآية كاملة من حفظك."
    HifzKind.Skip -> if (point.verse.ayah > 1) "صل آخر الآية السابقة بالآية المظلّلة، ثم أكمل إلى ما بعدها من حفظك." else "ابدأ بأول السورة ثم أكمل إلى الآية التالية من حفظك."
    HifzKind.Start -> if (point.verse.ayah > 1) "اربط آخر الآية السابقة ببداية الآية المظلّلة، ثم أعدهما من حفظك." else "تأمّل بداية السورة، ثم أخفها وابدأ من حفظك."
    HifzKind.Word -> if (point.slipWords.isEmpty()) "راجع الآية كاملة وحدّد أين توقفت، ثم أعد قراءتها من حفظك."
        else "راجع ${if (point.slipWords.size > 1) "الكلمات التي أخطأت فيها" else "الكلمة التي أخطأت فيها"} داخل الآية المظلّلة، ثم اقرأ الآية كاملة من حفظك."
}

/** The scan is the correction. Text is only its missing-image fallback; [ownOnPage] avoids repeating the reader. */
@Composable
internal fun HifzFixBody(
    catalog: QuranCatalog,
    fix: HifzFix,
    lookAlikes: HifzLookAlikes?,
    onChange: ((HifzPoint) -> Unit)? = null,
    ownOnPage: Boolean = true,
    onPickOther: (() -> Unit)? = null,
    learningNotes: Boolean = true,
    /** The look-alike's page behind one line, opened on demand: where they part is already shown above it. */
    otherFolded: Boolean = false,
    /** The words to mind, in large type; false where the review says them already, above its answers. */
    exact: Boolean = true,
) {
    val point = fix.point
    val v = point.verse
    if (exact) HifzExactCorrection(catalog, fix, onPickOther)
    when (point.kind) {
        HifzKind.Ending, HifzKind.Similar -> {
            val other = point.other
            val otherWords = fix.otherWords
            if (ownOnPage) HifzSection("الموضع الذي تراجعه · ${hifzVerseName(catalog, v)}") {
                QuranVerseExcerpt(catalog, v, "${hifzVerseName(catalog, v)} في المصحف") {
                    HifzVerseText(fix.words, marked = fix.focus, partAt = fix.partAt)
                }
            }
            if (other != null && otherWords != null && otherFolded) {
                var open by rememberSaveable(point.id) { mutableStateOf(false) }
                TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("hifz_fix_other_page")) {
                    Text("${hifzVerseName(catalog, other)} في المصحف ${if (open) "▴" else "▾"}", fontSize = 13.sp)
                }
                if (open) QuranVerseExcerpt(catalog, other, "${hifzVerseName(catalog, other)} في المصحف", mark = QuranExcerptMark.Other) {
                    HifzVerseText(otherWords, marked = fix.otherFocus, markColor = TextMuted, color = TextMuted)
                }
            } else if (other != null && otherWords != null) {
                HifzSection("الموضع المشابه · ${hifzVerseName(catalog, other)}") {
                    QuranVerseExcerpt(catalog, other, "${hifzVerseName(catalog, other)} في المصحف", mark = QuranExcerptMark.Other) {
                        HifzVerseText(otherWords, marked = fix.otherFocus, markColor = TextMuted, color = TextMuted)
                    }
                }
            } else if (onPickOther != null) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (point.kind == HifzKind.Ending) "لم تحدد الخاتمة التي قلتها" else "لم تحدد الآية التي ذهبت إليها",
                    Modifier.weight(1f), fontSize = 13.sp, color = TextMuted,
                )
                TextButton(onClick = onPickOther, modifier = Modifier.testTag("hifz_fix_pick_other")) { Text("حدّدها") }
            }
        }
        HifzKind.Skip -> if (ownOnPage || onChange != null) HifzSection("") {
            val count = catalog.surahs.getOrNull(v.surah - 1)?.verseCount ?: v.ayah
            val landed = (point.other?.ayah ?: (v.ayah + 1)).coerceAtMost(count)
            // The way through the page: the end of the verse before, the verse skipped, and where the recitation landed.
            if (ownOnPage) QuranVerseExcerpt(
                catalog, v, "${hifzVerseName(catalog, v)} بين ما قبلها وما بعدها في المصحف",
                linesBefore = 1, linesAfter = if (landed > v.ayah) 1 else 0, after = QuranVerseReference(v.surah, landed),
            )
            // The full destination remains editable, even when it is far beyond the next few verses.
            if (onChange != null && v.ayah < count) {
                HifzSkipDestination(catalog, v, landed) { onChange(point.copy(other = QuranVerseReference(v.surah, it))) }
            }
        }
        HifzKind.Start -> if (ownOnPage) HifzSection("") {
            QuranVerseExcerpt(catalog, v, "${hifzVerseName(catalog, v)} وما قبلها في المصحف", linesBefore = 1)
        }
        HifzKind.Word -> {
            if (ownOnPage) HifzSection("الصواب هنا · ${hifzVerseName(catalog, v)}") {
                QuranVerseExcerpt(catalog, v, "${hifzVerseName(catalog, v)} في المصحف") {
                    HifzVerseText(fix.words, marked = point.slipWords)
                }
            }
        }
    }
    Text(hifzPracticeHint(point), fontSize = 14.sp, color = GreenPrimaryDark)
    if (learningNotes) {
        if (fix.reasons.isNotEmpty()) HifzSection("لماذا يلتبس؟") {
            fix.reasons.forEach { Text(withQuranQuotes(it), fontSize = 14.sp, color = TextDark) }
        }
        if (point.key.isNotBlank()) HifzSection("ما يساعدني على التذكّر") {
            Text(withQuranQuotes(point.key), fontSize = 15.sp, color = GreenPrimaryDark)
        }
    }
}

/** A textual focus remains visible beside the scans, which can only mark a whole verse. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HifzExactCorrection(catalog: QuranCatalog, fix: HifzFix, onEdit: (() -> Unit)?) {
    val point = fix.point
    when (point.kind) {
        HifzKind.Word -> HifzSection(if (point.slipWords.size > 1) "الكلمات التي تراجعها" else "الكلمة التي تراجعها", Modifier.testTag("hifz_exact_word")) {
            val selected = point.slipWords.sorted().filter { it in 0 until fix.words.size }
            if (selected.isEmpty()) {
                Text("لم تحدد الكلمة بعد؛ راجع الآية كاملة ثم حدّد موضع الصعوبة.", fontSize = 14.sp, color = TextDark)
                if (onEdit != null) TextButton(onClick = onEdit, modifier = Modifier.testTag("hifz_fix_pick_word")) { Text("حدّد الكلمة") }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    selected.forEach { index ->
                        HifzVerseText(fix.words, from = index, to = index + 1, marked = setOf(index), fontSize = 23.sp)
                    }
                }
                Text("الصواب في رواية قالون؛ اقرأ الكلمة ضمن الآية.", fontSize = 12.sp, color = TextMuted)
            }
        }
        HifzKind.Similar, HifzKind.Ending -> {
            val other = point.other
            val otherWords = fix.otherWords
            if (other != null && otherWords != null) HifzSection(
                if (fix.focus.isEmpty() && fix.otherFocus.isEmpty()) "قارن الموضعين" else "موضع الفرق",
                Modifier.testTag("hifz_exact_contrast"),
            ) {
                HifzContrastFragment("في هذا الموضع · ${hifzVerseName(catalog, point.verse)}", fix.words, fix.focus, fix.partAt, point.kind == HifzKind.Ending)
                HorizontalDivider(color = CardBorder)
                HifzContrastFragment("في الموضع المشابه · ${hifzVerseName(catalog, other)}", otherWords, fix.otherFocus, fix.otherPartAt, point.kind == HifzKind.Ending)
            }
        }
        else -> Unit
    }
}

@Composable
private fun HifzContrastFragment(label: String, words: HifzWords, focus: Set<Int>, partAt: Int?, ending: Boolean) {
    val pivot = (partAt ?: focus.minOrNull() ?: (words.size - 3)).coerceIn(0, (words.size - 1).coerceAtLeast(0))
    val from = (pivot - 2).coerceAtLeast(0)
    val to = if (ending) words.size else (pivot + 6).coerceAtMost(words.size)
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
    HifzVerseText(words, from = from, to = to, marked = focus, fontSize = 21.sp,
        lead = if (from > 0) "… " else "", trail = if (to < words.size) " …" else "")
}

/**
 * The one-line facts of a point, for lists: «أخطأت فيها مرة · المراجعة بعد 3 أيام», «أخطأت فيها مرتين · تُسأل عنها
 * الآن». Each date says what it is, and a held point shows its next check too.
 */
internal fun hifzListDetail(point: HifzPoint, today: Long, due: Boolean): String = listOfNotNull(
    "الكلمة لم تُحدّد بعد".takeIf { point.kind == HifzKind.Word && point.slipWords.isEmpty() },
    hifzSlipsLabel(point.slips),
    "ثبتت".takeIf { point.mastered },
    if (point.fresh > 0 && due) "تُسأل عنها الآن" else hifzPointWhen(point, today),
).joinToString(" · ")

/**
 * When the reminder comes: the quick choices first, then «مخصص» for the reader's own prayers and clock times. Never
 * none: the last time left stays, the reminder being turned off by its own switch.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HifzTimesChoice(times: HifzTimes, onChange: (HifzTimes) -> Unit) {
    val colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = GreenPrimary, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White,
    )
    val preset = HifzTimesPreset.of(times)
    // A choice shows at once, and the next tap builds on it, before the save comes back with it.
    var shown by remember { mutableStateOf(times) }
    LaunchedEffect(times) { shown = times }
    fun change(next: HifzTimes) {
        if (next.none) return
        shown = next
        onChange(next)
    }
    // The reader's own times stay open while they are edited, also when they come to match a quick choice.
    var custom by rememberSaveable { mutableStateOf(preset == null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HifzTimesPreset.entries.forEach { option ->
            FilterChip(
                selected = !custom && HifzTimesPreset.of(shown) == option, onClick = { custom = false; change(option.times) },
                label = { Text(option.label) }, colors = colors, modifier = Modifier.testTag("hifz_times_${option.key}"),
            )
        }
        // Starts from the times chosen so far, to change one prayer or add a time to them.
        FilterChip(
            selected = custom, onClick = { custom = true }, label = { Text("مخصص") }, colors = colors,
            leadingIcon = { Icon(painterResource(R.drawable.ic_adhkar_clock), null, Modifier.size(16.dp)) },
            modifier = Modifier.testTag("hifz_times_custom"),
        )
    }
    if (custom) HifzTimesEditor(shown, ::change)
}

/** «مخصص»: after which prayers and at which clock times, so as many reminders a day as the reader wants. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HifzTimesEditor(times: HifzTimes, onChange: (HifzTimes) -> Unit) {
    val soft = GreenPrimary.copy(alpha = 0.14f)
    // The time being changed, -1 for a new one; null while no clock is open.
    var picking by rememberSaveable { mutableStateOf<Int?>(null) }
    Column(
        Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("hifz_times_editor"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            HifzReminder.countPhrase(times.count) + if (times.count == 1) "، وهو الأقل" else "",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, modifier = Modifier.testTag("hifz_times_count"),
        )
        Text("بعد الصلاة", fontSize = 12.sp, color = TextMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HifzPrayers.forEach { prayer ->
                val on = prayer in times.prayers
                FilterChip(
                    selected = on, onClick = { onChange(times.copy(prayers = if (on) times.prayers - prayer else times.prayers + prayer)) },
                    label = { Text(HifzReminder.prayerLabel(prayer)) },
                    colors = hifzChoiceColors(),
                    modifier = Modifier.testTag("hifz_times_prayer_${prayer.name}"),
                )
            }
        }
        Text("في ساعة محددة", fontSize = 12.sp, color = TextMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            times.minutes.sorted().forEach { minute ->
                val clock = HifzReminder.clock(minute)
                InputChip(
                    selected = true, onClick = { picking = minute }, label = { Text(clock) },
                    trailingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_close), "حذف $clock",
                            Modifier.size(18.dp).clip(CircleShape).clickable { onChange(times.copy(minutes = times.minutes - minute)) },
                        )
                    },
                    colors = InputChipDefaults.inputChipColors(selectedContainerColor = soft, selectedLabelColor = GreenPrimaryDark),
                    modifier = Modifier.testTag("hifz_times_time_$minute"),
                )
            }
            if (times.minutes.size < HifzMaxClockTimes) AssistChip(
                onClick = { picking = -1 }, label = { Text("إضافة وقت") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp)) },
                modifier = Modifier.testTag("hifz_times_add_time"),
            )
        }
    }
    picking?.let { changing ->
        HifzTimeDialog(
            // A new time starts at the next full hour.
            minute = changing.takeIf { it >= 0 } ?: ((LocalTime.now().hour + 1) % 24 * 60),
            onPick = { picked -> picking = null; onChange(times.copy(minutes = times.minutes - changing + picked)) },
            onRemove = if (changing >= 0) ({ picking = null; onChange(times.copy(minutes = times.minutes - changing)) }) else null,
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

/** The app's notification settings, or straight the «المراجعة» channel among [channels] the reader turned off. */
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

/** «كيف تعمل المراجعة؟»: each part of «المراجعة» in a few lines, in the screen's own words. */
@Composable
internal fun HifzHowItWorksDialog(onDismiss: () -> Unit) {
    @Composable
    fun part(title: String, vararg lines: String) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, modifier = Modifier.padding(top = 6.dp))
        lines.forEach { Text(it, fontSize = 14.sp) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("كيف تعمل المراجعة؟") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                part(
                    "سجّل الخطأ",
                    "إذا توقفت أو أخطأت وأنت تقرأ، اضغط على الآية ثم «أخطأت»، واختر ما حدث. وإن أخطأت في الصلاة أو مع معلّمك، سجّله من «سجّل موضعا» في «المراجعة». تصحيحه في دفتر المواضع حين تحتاجه.",
                )
                part(
                    "التذكير",
                    "بعد كل صلاة، أو في الأوقات التي تختارها في الإعدادات، يصلك إشعار حين يكون لديك ما تراجعه، فيه أسئلة مواضعك دون أجوبتها. اضغط «ابدأ المراجعة» لتقرأها من حفظك والآية مغطاة في المصحف.",
                    "الخطأ الجديد، أو الذي أخطأت فيه في سؤال، يُسأل عنه في كل تذكير حتى تُتمّ القراءات دون مساعدة التي تطلبها خطته، ثم تتباعد أسئلته: بعد يوم، ثم 3 أيام، ثم أسبوع، ثم أسبوعين.",
                    "يقترح التطبيق عدد القراءات حسب تاريخ الخطأ: 3 لخطأ جديد، و4 إذا عاد، و5 إذا تكرّر، وقراءتين لآية كانت ثابتة. يمكنك تغييره لكل خطأ من «تغيير الخطة»: قراءة واحدة لخطأ عابر، أو 5 قراءات، وأن نسألك في أول تذكير من اليوم بدل كل تذكير.",
                    "النقاط بجانب كل موضع في دفترك تُظهر تقدّمه: البنفسجية للقراءات دون مساعدة، ثم الخضراء للأسئلة المتباعدة حتى تثبت.",
                )
                part(
                    "السؤال",
                    "تُغطّى الآية في المصحف، فتقرؤها من حفظك، ثم تضغط «أظهر» وتختار:",
                    "• «دون مساعدة»: تذكّرتها دون تردد أو تلميح. بعد الخطأ تُحسب من القراءات التي تطلبها الخطة، وفي الأسئلة المتباعدة تطول المدة قبل أن تعود: 3 أيام، ثم أسبوع، ثم أسبوعان.",
                    "• «بتردد أو تلميح»: تذكّرتها بعد تردد أو بمساعدة، فلا تُحسب. بعد الخطأ تُسأل عنها في تذكير لاحق، وفي الأسئلة المتباعدة تعود بعد يومين ولا تطول مدتها.",
                    "• «نسيت أو أخطأت»: يُسجّل الخطأ، وتُسأل عنها في تذكير لاحق (غدًا لما يُسأل في أول تذكير من اليوم) حتى تُتمّ قراءاتها دون مساعدة من جديد، ثم تبدأ مددها من جديد. وإن عاد الخطأ زادت القراءات المطلوبة.",
                    "إذا استعنت بتلميح أو كشفت جزءًا من الآية، اختر «تذكّرتها بالمساعدة» أو «لم أتذكّرها».",
                    "لا نسألك عن آية رأيتها قبل قليل: بعد الخطأ نسألك عنها بعد ${HifzFreshGapMs / 60_000L} دقيقة على الأقل من آخر مرة رأيت فيها الصواب، وفي الأسئلة المتباعدة في اليوم التالي. كل سؤال يُطرح عليك يُحسب.",
                    "بعد التردد أو الخطأ، استمع إليها وكرّرها؛ ثم نسألك عنها في موعدها والآية مغطاة.",
                    "بعد الأسئلة المتباعدة الناجحة تصير «ثبتت»، وتعود بعد شهر ثم كل شهرين للتأكد.",
                    "«يحتاج عناية» يعتمد على الصعوبات الحديثة. قراءتان دون مساعدة، في يومين، بعد آخر خطأ تزيلان هذه العلامة.",
                )
                part("إخفاء الآيات", "زر العين في أعلى المصحف يغطي الآيات، فتقرؤها من حفظك وتكشفها بإصبعك للتأكد.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("فهمت") } },
    )
}
