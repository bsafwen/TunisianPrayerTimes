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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
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
import com.tunisianprayertimes.quran.hifz.HifzMistake
import com.tunisianprayertimes.quran.hifz.corrected
import com.tunisianprayertimes.quran.hifz.pointOf
import com.tunisianprayertimes.quran.hifz.same
import com.tunisianprayertimes.quran.hifz.todayEpochDay
import com.tunisianprayertimes.quran.hifz.rightSinceSlip
import com.tunisianprayertimes.quran.hifz.HifzSettings
import com.tunisianprayertimes.quran.hifz.withPlan
import com.tunisianprayertimes.quran.hifz.hifzAutoRecalls
import com.tunisianprayertimes.quran.hifz.HifzMaxRecalls
import com.tunisianprayertimes.quran.hifz.HifzPace
import com.tunisianprayertimes.quran.hifz.HifzPlan
import com.tunisianprayertimes.quran.hifz.HifzWhen
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.quran.hifz.HifzFix
import com.tunisianprayertimes.quran.hifz.HifzGrade
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzLookAlike
import com.tunisianprayertimes.quran.hifz.HifzLookAlikes
import com.tunisianprayertimes.quran.hifz.HifzPoint
import com.tunisianprayertimes.quran.hifz.HifzPrayers
import com.tunisianprayertimes.quran.hifz.HifzMaxClockTimes
import com.tunisianprayertimes.quran.hifz.HifzReminder
import com.tunisianprayertimes.quran.hifz.HifzReminder.NB
import com.tunisianprayertimes.quran.hifz.HifzTimes
import com.tunisianprayertimes.quran.hifz.HifzTimesPreset
import com.tunisianprayertimes.quran.hifz.HifzWords
import com.tunisianprayertimes.quran.hifz.hifzFixes
import com.tunisianprayertimes.quran.hifz.hifzOverlap
import com.tunisianprayertimes.quran.hifz.hifzPassage
import com.tunisianprayertimes.quran.hifz.hifzVersesAfterLabel
import com.tunisianprayertimes.quran.hifz.hifzWords
import com.tunisianprayertimes.quran.hifz.hifzWordsLabel
import com.tunisianprayertimes.quran.hifz.hifzRunLabel
import com.tunisianprayertimes.quran.quranAyah
import com.tunisianprayertimes.quran.quranAyahOrdinal
import com.tunisianprayertimes.quran.quranGoTo
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.tunisianprayertimes.quran.hifz.HifzFreshGapMs
import com.tunisianprayertimes.quran.hifz.HifzFreshRecalls
import com.tunisianprayertimes.quran.hifz.HifzMasteredStep
import com.tunisianprayertimes.quran.hifz.hifzClosings
import com.tunisianprayertimes.quran.normalizeQuranSearch
import com.tunisianprayertimes.quran.quranNameKey
import com.tunisianprayertimes.quran.quranVerseLabel
import com.tunisianprayertimes.quran.quranVerseSentence
import com.tunisianprayertimes.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** «البقرة · الآية 17»: a verse as a label names it, as the rest of the Quran tab does; in a sentence, see [hifzVerseAbout]. */
internal fun hifzVerseName(catalog: QuranCatalog, verse: QuranVerseReference): String =
    quranVerseLabel(catalog.surahs.getOrNull(verse.surah - 1)?.name.orEmpty(), verse.ayah)

/** «الآية الثانية من سورة الملك», «الآية 12 من سورة الملك»: a verse as a sentence names it. */
internal fun hifzVerseAbout(catalog: QuranCatalog, verse: QuranVerseReference): String =
    quranVerseSentence(catalog.surahs.getOrNull(verse.surah - 1)?.name.orEmpty(), verse.ayah)

/** A verse in a sentence about [from]: «الآية الثالثة» in its surah, «الآية 19 من سورة الإنسان» in another. */
internal fun hifzVerseFrom(catalog: QuranCatalog, verse: QuranVerseReference, from: QuranVerseReference): String =
    if (verse.surah == from.surah) quranAyah(verse.ayah) else hifzVerseAbout(catalog, verse)

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
    days in 3L..10L -> "بعد $days${NB}أيام"
    else -> "بعد $days${NB}يومًا"
}

/** «مرة واحدة», «مرتان», «4 مرات»: right answers, «دون مساعدة», as a count on its own. */
internal fun hifzRecallsLabel(count: Int): String = when {
    count == 1 -> "مرة واحدة"
    count == 2 -> "مرتان"
    count % 100 in 3..10 -> "$count${NB}مرات"
    else -> "$count${NB}مرة"
}

/** Why the app suggests [auto] right answers, from the slip's history, in a sentence; see [hifzAutoRecalls]. */
internal fun hifzAutoReason(auto: Int): String {
    val count = HifzReminder.recallsPhrase(auto)
    return when {
        auto <= 2 -> "نقترح $count لأنك كنت تحفظ هذه الآية جيدًا."
        auto == HifzFreshRecalls -> "نقترح $count لأن الخطأ جديد."
        auto < HifzMaxRecalls -> "نقترح $count لأن هذا الخطأ عاد."
        else -> "نقترح $count لأن هذا الخطأ تكرّر كثيرًا."
    }
}

/** Roughly when each prayer comes, to tell the day's first reminder among prayers and clock times. */
private val HifzPrayerMinute = mapOf(Prayer.FAJR to 330, Prayer.DHUHR to 750, Prayer.ASR to 930, Prayer.MAGHRIB to 1110, Prayer.ISHA to 1200)

/**
 * The spaced reminders after the right answers, the plan's last step, said as they come: on their days, at the first
 * of the settings' times («بعد الفجر»), whatever times the slip chose; with the reminder off, in «مراجعة اليوم».
 */
internal fun hifzLaterReminders(settings: HifzSettings): String {
    val then = "ثم بعد 3${NB}أيام، ثم بعد أسبوع، ثم بعد أسبوعين"
    if (!settings.reminder) return "بعد ذلك تقلّ الأسئلة: تجدها في «مراجعة اليوم» بعد يوم، $then."
    val first = settings.times.times.minByOrNull { time ->
        when (time) {
            is HifzWhen.After -> HifzPrayerMinute[time.prayer] ?: 0
            is HifzWhen.At -> time.minute
        }
    }?.let { HifzReminder.timeLabel(it, null) }
    return "بعد ذلك تقلّ الأسئلة: نسألك عنها في اليوم التالي" + (first?.let { " $it" } ?: "") + "، $then."
}

/** Chosen: one style everywhere a choice is made, solid, never a tint that reads as «suggested». */
@Composable
internal fun hifzChoiceColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = GreenPrimary, selectedLabelColor = Color.White,
)

/**
 * «تذكيرك بهذه الآية»: a slip's plan, on «أخطأت» and in its correction, as two plain questions. When it is reminded:
 * its own [times], by default the [settings]' (after each prayer, three times a day, after Fajr only, or the reader's own
 * prayers and clock times). Until when: the right answers, «دون مساعدة», it asks for, the app's suggestion ([auto])
 * unless the reader picks once or five times. Then the reminders space out. [done]: right answers already given since
 * the slip, which count: a count they already reach ends the reminders that follow one another, and the dialog says so.
 * Nothing changes before «تم»; «إلغاء» or a tap outside keeps the plan as it was. One time of day is kept as the slip's
 * own, settings' or not: it is asked once a day whatever the settings become.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HifzPlanDialog(
    auto: Int,
    pace: HifzPace,
    times: HifzTimes?,
    settings: HifzSettings,
    done: Int = 0,
    onChange: (HifzPlan) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosenPace by remember { mutableStateOf(pace) }
    var chosenTimes by remember { mutableStateOf(times) }
    val shown = chosenTimes ?: settings.times
    val count = when (chosenPace) {
        HifzPace.Auto -> auto
        HifzPace.Light -> 1
        HifzPace.Intense -> HifzMaxRecalls
    }
    val scroll = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تذكيرك بهذه الآية") },
        text = {
          // Scrolled, its edges say so: a line cut under the title is the list going on, not a glitch.
          Column {
            if (scroll.canScrollBackward) HorizontalDivider(color = CardBorder)
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).testTag("hifz_plan_chooser"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (settings.reminder) {
                    Text("متى نذكّرك؟", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                    // The settings' times are no choice of its own, unless one time of day. Its own times open closed, so
                    // that the second question is in view.
                    HifzTimesChoice(shown, open = false) { chosen -> chosenTimes = chosen.takeIf { it != settings.times || it.count == 1 } }
                    // Said only when the chips do not say it: its own times, custom times, or which three prayers.
                    val own = shown != settings.times
                    val preset = HifzTimesPreset.of(shown)
                    if (own || preset == null || preset == HifzTimesPreset.Thrice) Text(
                        "نذكّرك بها ${HifzReminder.slipTimesPhrase(shown)}" + if (own) "، ولا تتغيّر أوقات التذكير بأخطائك الأخرى." else ".",
                        fontSize = 13.sp, color = TextMuted, modifier = Modifier.testTag("hifz_plan_times"),
                    )
                } else Text("التذكير متوقّف في الإعدادات: تجد أسئلتها في «مراجعة اليوم».", fontSize = 13.sp, color = TextMuted)
                Text("إلى متى؟", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, modifier = Modifier.padding(top = 6.dp))
                Text("إلى أن تتذكّرها دون مساعدة:", fontSize = 13.sp, color = TextDark)
                // Different counts only: the suggestion may already be the most.
                val options = buildList {
                    add(HifzPace.Light to 1)
                    add(HifzPace.Auto to auto)
                    if (auto < HifzMaxRecalls) add(HifzPace.Intense to HifzMaxRecalls)
                }
                val selected = if (chosenPace == HifzPace.Intense && auto >= HifzMaxRecalls) HifzPace.Auto else chosenPace
                // The suggestion is said once, with its reason, under the chips: the three then share one row.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    options.forEach { (option, n) ->
                        FilterChip(
                            selected = selected == option, onClick = { chosenPace = option },
                            label = { Text(hifzRecallsLabel(n)) },
                            colors = hifzChoiceColors(),
                            modifier = Modifier.testTag("hifz_plan_${option.key}"),
                        )
                    }
                }
                Text(hifzAutoReason(auto), fontSize = 13.sp, color = TextMuted)
                // What is already done counts; enough of it ends the reminders that follow one another, said before «تم».
                if (done > 0) Text(
                    "تذكّرتها ${HifzReminder.recallsPhrase(done)} دون مساعدة منذ سجّلت الخطأ، " +
                        if (count <= done) "وهذا يكفي لما اخترت." else "وهذا محسوب لك.",
                    fontSize = 13.sp, color = if (count <= done) GreenPrimaryDark else TextMuted, modifier = Modifier.testTag("hifz_plan_done_count"),
                )
                Text(hifzLaterReminders(settings), fontSize = 13.sp, color = TextMuted)
            }
            if (scroll.canScrollForward) HorizontalDivider(color = CardBorder)
          }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // One time of day is the slip's own, settings' or not: once a day, whatever the settings become.
                    onChange(HifzPlan(chosenPace, chosenTimes ?: settings.times.takeIf { it.count == 1 }))
                    onDismiss()
                },
                modifier = Modifier.testTag("hifz_plan_done"),
            ) { Text("تم") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

/**
 * When a point comes back, said everywhere as its next question: «نسألك عنها غدًا», «نسألك عنها بعد 3 أيام»;
 * one whose day has passed, after a journey say, is not «اليوم» but «سؤالها متأخر منذ 9 أيام».
 */
internal fun hifzReviewWhen(days: Long): String = when {
    days >= 0L -> "نسألك عنها ${hifzWhen(days)}"
    days == -1L -> "سؤالها متأخّر منذ أمس"
    days == -2L -> "سؤالها متأخّر منذ يومين"
    -days % 100 in 3L..10L -> "سؤالها متأخّر منذ ${-days}${NB}أيام"
    else -> "سؤالها متأخّر منذ ${-days}${NB}" + if (-days % 100 < 3) "يوم" else "يومًا"
}

/** The right answers a fresh point gave toward its plan, «دون مساعدة»: its [recalls] less those still needed. */
internal val HifzPoint.freshDone: Int get() = (recalls - fresh).coerceIn(0, recalls)

/** «أخطأت فيها مرة», «أخطأت فيها مرتين», «أخطأت فيها 3 مرات»: slips recorded and checks failed alike. */
internal fun hifzSlipsLabel(slips: Int): String = when {
    slips <= 1 -> "أخطأت فيها مرة"
    slips == 2 -> "أخطأت فيها مرتين"
    slips % 100 in 3..10 -> "أخطأت فيها $slips${NB}مرات"
    else -> "أخطأت فيها $slips${NB}مرة"
}

/** «تثبت إن تذكّرتها في سؤال آخر», «… في سؤالين آخرين», «… في 3 أسئلة أخرى»: the spaced checks still to pass. */
internal fun hifzHoldsAfter(count: Int): String = "تثبت إن تذكّرتها في " + when (count) {
    1 -> "سؤال آخر"
    2 -> "سؤالين آخرين"
    else -> "$count${NB}أسئلة أخرى"
}

/**
 * «البقرة · الآية 17 · كلمة», «البقرة · الآية 17 · ختمتها بخاتمة الآية 170», «الطور · الآية 22 · دخلت في الآية 19 من سورة
 * الإنسان», «الملك · الآية 5 · تخطيتها», and a verse that went wrong more than one way with each: «الملك · الآية 5 · كلمة
 * · تخطيتها». What happened, in the words of «أخطأت».
 */
internal fun hifzPointTitle(catalog: QuranCatalog, point: HifzPoint): String =
    (listOf(hifzVerseName(catalog, point.verse)) + point.mistakes.map { hifzMistakeShort(catalog, point.verse, it) }).joinToString(" · ")

/**
 * «كلمة», «ختمتها بخاتمة الآية 170», «دخلت في الآية الثالثة», «دخلت في الآية 19 من سورة الإنسان», «تخطيتها»: one mistake,
 * as titles say it; the other verse's surah only when it is another.
 */
internal fun hifzMistakeShort(catalog: QuranCatalog, verse: QuranVerseReference, mistake: HifzMistake): String {
    val other = mistake.other?.let { hifzVerseFrom(catalog, it, verse) }
    return when {
        mistake.kind == HifzKind.Similar && other != null -> "دخلت في $other"
        mistake.kind == HifzKind.Ending && other != null -> "ختمتها بخاتمة $other"
        mistake.kind == HifzKind.Skip -> "تخطيتها"
        mistake.kind.lookAlike -> mistake.kind.label
        else -> mistake.kind.short
    }
}

/** «نسيت كلمة أو أخطأت فيها», «ختمتها بخاتمة الآية 170»: one mistake said in full, heading its part of a correction. */
internal fun hifzMistakeTitle(catalog: QuranCatalog, verse: QuranVerseReference, mistake: HifzMistake): String =
    if (mistake.kind == HifzKind.Word || mistake.kind == HifzKind.Start) mistake.kind.label else hifzMistakeShort(catalog, verse, mistake)

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
    maxLines: Int = Int.MAX_VALUE,
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
                    withStyle(SpanStyle(color = QuranHifzViolet, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = null)) { append("‹هنا تفترقان› ") }
                }
                if (i in marked) withStyle(SpanStyle(color = markColor, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)) { append(words.printed[i]) }
                else append(words.printed[i])
                append(' ')
            }
            append(trail)
        }
    }
    Text(
        text, modifier, fontFamily = QuranMushafFont, fontSize = fontSize, lineHeight = fontSize * 1.9f, color = color,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
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
        fresh && point.freshDone == 0 -> "نسألك عنها إلى أن تتذكّرها ${HifzReminder.recallsPhrase(point.recalls)} دون مساعدة"
        fresh -> "تذكّرتها ${HifzReminder.recallsPhrase(point.freshDone)} من ${point.recalls} دون مساعدة، ثم تقلّ الأسئلة"
        else -> hifzHoldsAfter(point.checksToHold)
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
 * What happened, as «أخطأت» asks it: three choices, the most common first. A verse whose opening would not come is its
 * first word forgotten; an ending said wrong is a look-alike's, told apart by the verse chosen. Each saved as a [HifzKind]:
 * see [HifzRecordSheet]; a point of an older kind shows as the choice it now belongs to.
 */
internal enum class HifzSlipChoice(val key: String, val label: String, val hint: String) {
    Word("word", "نسيت كلمة أو أخطأت فيها", "كلمة أو حركة، ولو كانت أول الآية"),
    LookAlike("lookalike", "دخلت في آية شبيهة", "في أولها أو وسطها أو خاتمتها"),
    Skip("skip", "تخطيتها", "انتقلت إلى ما بعدها دون أن تقرأها");

    companion object {
        fun of(key: String): HifzSlipChoice? = entries.firstOrNull { it.key == key }
        fun of(kind: HifzKind): HifzSlipChoice = when (kind) {
            HifzKind.Word, HifzKind.Start -> Word
            HifzKind.Similar, HifzKind.Ending -> LookAlike
            HifzKind.Skip -> Skip
        }
    }
}

/**
 * How a look-alike the reader found parts from [verse]: sharing its opening words, or going on alike, a similar verse; else
 * alike near its end, an ending said wrong.
 */
internal fun hifzLookAlikeKind(catalog: QuranCatalog, verse: QuranVerseReference, other: QuranVerseReference): HifzKind {
    val a = catalog.verse(verse)?.let { hifzWords(it.text) } ?: return HifzKind.Similar
    val b = catalog.verse(other)?.let { hifzWords(it.text) } ?: return HifzKind.Similar
    val overlap = hifzOverlap(a.keys, b.keys)
    return when {
        overlap.prefix >= 2 -> HifzKind.Similar
        overlap.suffix > 0 || (overlap.run > 0 && overlap.runA + overlap.run >= a.size - 3) -> HifzKind.Ending
        else -> HifzKind.Similar
    }
}

/**
 * «ما خطؤك في النساء 47؟»: a kind first, then what more it asks (where the recitation went, which words). The
 * save stays in view with when the slip will be asked, so nothing is saved before it is seen, and nothing the reader
 * did not choose: no look-alike, no parting word is picked for them. A verse already in the notebook is one point,
 * whatever went wrong in it: the sheet says so, opens on the mistake told last, filled in as it was, so that the same
 * mistake come back is saved in one tap, and any save counts the verse again. [editing] corrects one of a point's
 * mistakes instead, with no slip counted. Filled in by the reader, the sheet asks before it is closed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HifzRecordSheet(
    catalog: QuranCatalog,
    lookAlikes: HifzLookAlikes?,
    initialVerse: QuranVerseReference,
    onSave: (verse: QuranVerseReference, kind: HifzKind, other: QuranVerseReference?, slipWords: Set<Int>, bothWays: Boolean, plan: HifzPlan) -> Unit,
    onDismiss: () -> Unit,
    /** The notebook, to say what this verse already has. */
    points: List<HifzPoint> = emptyList(),
    /** The point whose mistake at [editingIndex] is corrected; null records a slip. */
    editing: HifzPoint? = null,
    editingIndex: Int = 0,
    onEdit: (HifzPoint) -> Unit = {},
    /** The reminder is on: the sheet says when it asks, and why it cannot when notifications are off. */
    remind: Boolean = true,
    /** «سجّل خطأً آخر في هذه الآية»: nothing is offered, the reader having said it is another mistake. */
    another: Boolean = false,
) {
    // One verse for the sheet's life: the one tapped, or typed. A wrong one is closed and the right one tapped.
    val verse = editing?.verse ?: initialVerse
    val corrected = editing?.mistakes?.getOrNull(editingIndex)
    // The verse's point when a slip is recorded: what is saved is told to it, and counts it again.
    val recorded = remember(points, verse, editing) { if (editing == null) points.pointOf(verse) else null }
    // The mistake told last, offered as the one come back; chosen for the sheet's life, as it opened.
    val offered by rememberSaveable { mutableStateOf(if (editing == null && !another) recorded?.latest?.let { HifzSlipChoice.of(it.kind).key } else null) }
    // What happened, in three: a word, a look-alike, a verse skipped. The kind saved follows from it; for a look-alike,
    // from the verse chosen, as it parts from this one in its course or at its ending.
    var choiceKey by rememberSaveable { mutableStateOf(corrected?.let { HifzSlipChoice.of(it.kind).key } ?: offered) }
    val choice = choiceKey?.let { HifzSlipChoice.of(it) }
    val editingHere = corrected?.takeIf { HifzSlipChoice.of(it.kind) == choice }
    // Something the reader chose themselves, past what the sheet opened on: closing then asks first.
    var touched by rememberSaveable { mutableStateOf(false) }
    // Reset with the look-alike chosen: back to the point corrected, back to its own kind.
    var otherKindKey by rememberSaveable(verse, choiceKey) { mutableStateOf(editingHere?.kind?.takeIf { it.lookAlike }?.key ?: HifzKind.Similar.key) }
    val kind = when (choice) {
        HifzSlipChoice.Word -> HifzKind.Word
        HifzSlipChoice.Skip -> HifzKind.Skip
        HifzSlipChoice.LookAlike -> HifzKind.of(otherKindKey) ?: HifzKind.Similar
        null -> null
    }
    var choosingKind by rememberSaveable { mutableStateOf(editing == null && offered == null) }
    var detailsOpen by rememberSaveable(verse, choiceKey) { mutableStateOf(false) }
    val words = remember(verse) { catalog.verse(verse)?.let { hifzWords(it.text) } }
    val count = catalog.surahs.getOrNull(verse.surah - 1)?.verseCount ?: verse.ayah
    // The mistakes this verse already has, other than the one corrected.
    val onVerse = remember(recorded, editing, editingIndex) {
        editing?.mistakes?.filterIndexed { i, _ -> i != editingIndex } ?: recorded?.mistakes.orEmpty()
    }
    // A look-alike: the verses that open or go on like this one, then those that close like it, each with what it shares.
    val suggestions = remember(lookAlikes, verse, choice) {
        if (choice != HifzSlipChoice.LookAlike) emptyList() else {
            val similar = lookAlikes?.similar(verse).orEmpty().map { it to HifzKind.Similar }
            similar + lookAlikes?.endings(verse).orEmpty().filter { e -> similar.none { it.first.verse == e.verse } }.map { it to HifzKind.Ending }
        }
    }
    val closings = remember(catalog, verse, choice) { if (choice == HifzSlipChoice.LookAlike) hifzClosings(catalog, verse) else emptyList() }
    var otherKey by rememberSaveable(verse, choiceKey) { mutableStateOf(editingHere?.other?.let { "${it.surah}:${it.ayah}" }) }
    val other = otherKey?.split(':')?.let { QuranVerseReference(it[0].toInt(), it[1].toInt()) }
    // Whether the reader answered «إلى أين ذهبت؟»: a suggestion, a verse found, or «لا أعرف». Never answered for them.
    var otherChosen by rememberSaveable(verse, choiceKey) { mutableStateOf(editingHere != null) }
    fun chooseOther(value: QuranVerseReference?, asKind: HifzKind) {
        otherKey = value?.let { "${it.surah}:${it.ayah}" }
        otherChosen = true
        otherKindKey = asKind.key
    }
    /** The reader's own answer to «إلى أين ذهبت؟», as against the one offered again. */
    fun pickOther(value: QuranVerseReference?, asKind: HifzKind) {
        chooseOther(value, asKind)
        touched = true
    }
    // A look-alike recorded on this verse before is the reader's own answer, offered again as such: the latest told.
    LaunchedEffect(verse, choice) {
        if (editing == null && choice == HifzSlipChoice.LookAlike && !otherChosen) {
            onVerse.filter { it.kind.lookAlike && it.other != null }.maxByOrNull { it.at }?.let { chooseOther(it.other, it.kind) }
        }
    }
    var word by rememberSaveable(verse, choiceKey) { mutableStateOf(editingHere?.takeIf { it.kind.lookAlike }?.word) }
    // A word slip may miss several words: any of them, in any order.
    var wrongWords by rememberSaveable(verse, choiceKey) {
        mutableStateOf(editingHere?.takeIf { it.kind == HifzKind.Word }?.slipWords?.sorted().orEmpty())
    }
    var wordOpen by rememberSaveable(verse, choiceKey) { mutableStateOf(false) }
    var bothWays by rememberSaveable { mutableStateOf(corrected?.bothWays ?: false) }
    var typing by rememberSaveable(verse, choiceKey) { mutableStateOf(false) }
    // Where a skip landed: the verse after it, unless the reader says further. A skip counted again starts from where it
    // landed before, which the reader may bring back to the next verse.
    var landed by rememberSaveable(verse) {
        mutableIntStateOf(
            (corrected?.takeIf { it.kind == HifzKind.Skip } ?: onVerse.takeIf { editing == null }?.same(HifzKind.Skip))
                ?.other?.takeIf { it.surah == verse.surah && it.ayah > verse.ayah }?.ayah ?: (verse.ayah + 1),
        )
    }
    // The mistake this save tells again, if any: the same kind, and for a look-alike the same verse confused.
    val again = remember(onVerse, kind, other) { kind?.let { k -> onVerse.same(k, other) }?.takeIf { editing == null } }
    // A word slip recorded again starts from the words chosen before: saving it again is one tap.
    LaunchedEffect(verse, choiceKey) {
        if (editing == null && kind == HifzKind.Word && wrongWords.isEmpty()) again?.slipWords?.sorted()?.let { wrongWords = it }
    }
    val context = LocalContext.current.applicationContext
    val settings = remember { HifzSettings.read(context) }
    // How the slip will be asked: as many right answers as the verse's history calls for (more when it came back), at
    // the reminder's times; the reader may change both. A verse counted again keeps its plan, a passing slip judged again.
    var chosenPace by rememberSaveable { mutableStateOf<String?>(null) }
    // The verse's own reminder times: null until chosen (a verse counted again keeps its own), "" for the settings'.
    var chosenTimes by rememberSaveable { mutableStateOf<String?>(null) }
    var planOpen by rememberSaveable { mutableStateOf(false) }
    val pace = chosenPace?.let(HifzPace::of) ?: recorded?.pace?.takeIf { it != HifzPace.Light } ?: HifzPace.Auto
    // One time of day in the settings is kept as the verse's own: it is asked once a day, whatever they become.
    val times: HifzTimes? = when (val chosen = chosenTimes) {
        null -> recorded?.times ?: settings.times.takeIf { it.count == 1 }
        "" -> settings.times.takeIf { it.count == 1 }
        else -> HifzTimes.decode(chosen)
    }
    val daily = times?.count == 1
    val autoRecalls = recorded?.let { hifzAutoRecalls(it.slips + 1, it.mastered) } ?: HifzFreshRecalls
    val recalls = when (pace) {
        HifzPace.Auto -> autoRecalls
        HifzPace.Light -> 1
        HifzPace.Intense -> HifzMaxRecalls
    }
    var plan by remember { mutableStateOf<String?>(null) }
    // Asked once a day and already asked today, a verse counted again starts tomorrow, as record() saves it.
    val tomorrow = daily && recorded?.checks?.any { it.day == todayEpochDay() } == true
    LaunchedEffect(recalls, times, tomorrow, recorded?.id) {
        // Again every minute while the sheet is open: a prayer that begins, or a reminder now less than 45 minutes off,
        // changes when a slip saved now is first asked.
        while (editing == null) {
            plan = withContext(Dispatchers.IO) {
                // Asked once a day: not on a prayer day a reminder already asked it.
                HifzReminder.freshSentence(context, recalls, times, tomorrow = tomorrow, askedDay = recorded?.let { HifzReminder.dailyAskedDay(context, it.id) })
            }
            delay(60_000L)
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
    var removing by remember { mutableStateOf(false) }
    fun hasChanges(): Boolean {
        // A new record: anything the reader chose; what the sheet opened on, unchanged, is nothing to lose.
        if (corrected == null) return touched
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
        return kind != corrected.kind || chosenOther != corrected.other || chosenWords != corrected.slipWords ||
            (bothWays && kind?.lookAlike == true && chosenOther != null) != corrected.bothWays
    }
    val close: () -> Unit = { if (!hasChanges()) onDismiss() else discard = true }
    // Filled in, it is not lost to a swipe or a stray tap outside: closing asks first. One lambda for the sheet's life:
    // a new one would build a new sheet state, hidden, and the sheet would drop and rise at every choice.
    val dirty by rememberUpdatedState(hasChanges())
    val confirmHide = remember { { value: SheetValue -> if (value == SheetValue.Hidden && dirty) { discard = true; false } else true } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirmHide)
    val scroll = rememberScrollState()
    // The words to choose come in view whole once the slip is a word, above the footer that stays at the bottom.
    LaunchedEffect(choiceKey) {
        if (choice == HifzSlipChoice.Word) { withFrameNanos {}; scroll.animateScrollTo(scroll.maxValue) }
    }
    ModalBottomSheet(onDismissRequest = close, sheetState = sheetState, containerColor = BgCream) {
        Column(Modifier.fillMaxWidth().quranSheetHeight().padding(horizontal = 16.dp).testTag("hifz_record_sheet")) {
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuranPanelHeader(
                    // The reader's own mistake: «ما الخطأ في الملك 2؟» would read as a fault in the verse. Of a verse that
                    // went wrong more than one way, one mistake is corrected, not the verse's whole record.
                    when {
                        editing == null -> "ما خطؤك في ${hifzVerseAbout(catalog, verse)}؟"
                        editing.mistakes.size > 1 -> "تعديل الخطأ في ${hifzVerseAbout(catalog, verse)}"
                        else -> "تعديل ما سجّلته في ${hifzVerseAbout(catalog, verse)}"
                    },
                    close,
                )
                // The page is under the excerpt, the question in the title: only a correction says what it does.
                if (editing != null) Text("صحّح ما سجّلته؛ لا يُحسب التعديل خطأً جديدًا.", fontSize = 12.sp, color = TextMuted)
                // A verse in the notebook already: what is saved now joins it, and counts it again.
                recorded?.let {
                    Text(
                        "في دفتر المراجعة · ${hifzSlipsLabel(it.slips)}", fontSize = 12.sp, color = QuranHifzViolet,
                        modifier = Modifier.testTag("hifz_record_recorded"),
                    )
                }
                // The verse in its place, with the end of the one before: the slip is recorded where it is remembered.
                QuranVerseExcerpt(catalog, verse, "${hifzVerseAbout(catalog, verse)} في المصحف", linesBefore = 1)
                if (choice != null && !choosingKind) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AdhkarSoftGreen).padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(choice.label, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                    TextButton(onClick = { choosingKind = true }) { Text("غيّر") }
                }
                if (choice == null || choosingKind) Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HifzSlipChoice.entries.forEach { option ->
                    val selected = choice == option
                    val told = onVerse.any { HifzSlipChoice.of(it.kind) == option }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selected) AdhkarSoftGreen else Color.White)
                            .border(1.dp, if (selected) GreenPrimary else CardBorder, RoundedCornerShape(12.dp))
                            .selectable(selected, role = Role.RadioButton) { choiceKey = option.key; choosingKind = false; touched = true }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("hifz_kind_${option.key}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selected) Text("✓ ", Modifier.clearAndSetSemantics {}, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                        Column(Modifier.weight(1f)) {
                            Text(option.label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = GreenPrimaryDark)
                            Text(option.hint, fontSize = 12.sp, color = TextMuted)
                            // Already told of this verse: the line above says how many times the verse went wrong.
                            if (told) Text("سجّلته من قبل", fontSize = 12.sp, color = QuranHifzViolet)
                        }
                    }
                } }
                when (choice) {
                    HifzSlipChoice.Word -> if (words != null) {
                        Text("أي كلمة؟", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, modifier = Modifier.padding(top = 6.dp))
                        Text("اختر كلمة أو أكثر.", fontSize = 12.sp, color = TextMuted)
                        HifzWordPicker(words, wrongWords.toSet(), suggested = null, multi = true) { i ->
                            wrongWords = if (i in wrongWords) wrongWords - i else wrongWords + i
                            touched = true
                        }
                        // Its opening forgotten, the verse is practised from the end of the one before.
                        if (0 in wrongWords && verse.ayah > 1) Text(
                            "نسيت أولها: ندرّبك على الانتقال من ${quranAyah(verse.ayah - 1)} إليها.", fontSize = 12.sp, color = TextMuted,
                            modifier = Modifier.testTag("hifz_forgot_explanation"),
                        )
                    }
                    HifzSlipChoice.LookAlike -> {
                        Text("إلى أين ذهبت؟", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, modifier = Modifier.padding(top = 6.dp))
                        if (lookAlikes == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = GreenPrimary, trackColor = CardBorder)
                        val similar = suggestions.filter { it.second == HifzKind.Similar }
                        val endings = suggestions.filter { it.second == HifzKind.Ending }
                        similar.forEach { (candidate, k) ->
                            HifzLookAlikeRow(catalog, verse, candidate, k, selected = other == candidate.verse) { pickOther(candidate.verse, k) }
                        }
                        // An ending is remembered by its words: the closings around the verse, the one said is tapped.
                        if (closings.isNotEmpty()) {
                            Text("أو ختمتها بخاتمة أخرى؟ اختر ما قلته:", fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(top = 4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                closings.forEach { (closing, ref) ->
                                    FilterChip(
                                        selected = other == ref, onClick = { pickOther(ref, HifzKind.Ending) },
                                        label = { Text(closing, fontFamily = QuranMushafFont, fontSize = 15.sp) },
                                        colors = hifzChoiceColors(),
                                        modifier = Modifier.testTag("hifz_closing_${ref.surah}_${ref.ayah}"),
                                    )
                                }
                            }
                            closings.firstOrNull { it.second == other }?.let { (_, ref) ->
                                Text(hifzVerseName(catalog, ref), fontSize = 12.sp, color = TextMuted)
                                QuranVerseExcerpt(catalog, ref, "${hifzVerseAbout(catalog, ref)} في المصحف", mark = QuranExcerptMark.Other)
                            }
                        }
                        if (endings.isNotEmpty()) Text(
                            if (closings.isEmpty()) "أو آية تُختم مثلها:" else "أو آية تشترك معها في كلمات قبل الخاتمة:",
                            fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(top = 4.dp),
                        )
                        endings.forEach { (candidate, k) ->
                            HifzLookAlikeRow(catalog, verse, candidate, k, selected = other == candidate.verse) { pickOther(candidate.verse, k) }
                        }
                        if (lookAlikes != null && suggestions.isEmpty() && closings.isEmpty()) Text("لم نجد آية شبيهة في نصها؛ ابحث عنها بكلماتها.", fontSize = 12.sp, color = TextMuted)
                        val typed = other?.takeIf { o -> suggestions.none { it.first.verse == o } && closings.none { it.second == o } }
                        if (typed != null) {
                            HifzChoiceRow("${hifzVerseName(catalog, typed)} · ${hifzPage(catalog, typed).orEmpty()}", selected = true) {}
                            QuranVerseExcerpt(catalog, typed, "${hifzVerseAbout(catalog, typed)} في المصحف", mark = QuranExcerptMark.Other)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { typing = !typing }, label = { Text("آية أخرى…") })
                            FilterChip(selected = otherChosen && other == null, onClick = { pickOther(null, HifzKind.Similar) }, label = { Text("لا أعرف") },
                                colors = hifzChoiceColors(), modifier = Modifier.testTag("hifz_other_unknown"))
                        }
                        // A verse found by the reader: where it parts from this one tells a look-alike from an ending.
                        if (typing) HifzVerseFinder(catalog, exclude = verse) { chosen -> pickOther(chosen, hifzLookAlikeKind(catalog, verse, chosen)); typing = false }
                        TextButton(onClick = { detailsOpen = !detailsOpen }, contentPadding = PaddingValues(0.dp)) {
                            Text(if (detailsOpen) "إخفاء التفاصيل الإضافية" else "تفاصيل إضافية (اختياري)")
                        }
                        if (detailsOpen && words != null) {
                            TextButton(onClick = { wordOpen = !wordOpen }, contentPadding = PaddingValues(0.dp)) {
                                Text("أين افترقت عن الآية؟ ${if (wordOpen) "▴" else "▾"}", fontSize = 13.sp)
                            }
                            if (wordOpen) HifzWordPicker(words, setOfNotNull(word), suggested = suggestedWord) { word = if (word == it) null else it; touched = true }
                        }
                        if (detailsOpen) Row(Modifier.fillMaxWidth().toggleable(bothWays, role = Role.Checkbox) { bothWays = it; touched = true }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = bothWays, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("قد أقرأ كلًّا منهما مكان الأخرى", fontSize = 14.sp)
                                Text("فنسألك عن الآيتين كلتيهما", fontSize = 12.sp, color = TextMuted)
                            }
                        }
                    }
                    HifzSlipChoice.Skip -> {
                        // Where the recitation went: often further than the next verse.
                        if (verse.ayah < count) HifzSkipDestination(catalog, verse, landed) { landed = it; touched = true }
                    }
                    null -> Unit
                }
            }
            // Always in view once a kind is chosen, with what the save does: a filled-in record is never left below the
            // fold, none is saved without saying when it will be asked, and a slip counted again says so.
            if (kind != null) Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Where the scrolled part ends: a row cut by it reads as going on, not as broken.
                HorizontalDivider(Modifier.padding(bottom = 4.dp), color = CardBorder)
                when {
                    again != null -> Text(
                        "سجّلت هذا الخطأ من قبل، فيُحسب مرة أخرى.", fontSize = 12.sp, color = QuranHifzViolet,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 4.dp).testTag("hifz_record_again"),
                    )
                    // Another way the same verse went wrong: one point still, counted again.
                    recorded != null -> Text(
                        "نضيفه إلى ما سجّلته في هذه الآية، ويُحسب خطأً آخر فيها.", fontSize = 12.sp, color = QuranHifzViolet,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 4.dp).testTag("hifz_record_added"),
                    )
                }
                if (editing == null) plan?.let {
                    // Named for what it changes: the kind above has its own «تغيير».
                    val linkStyle = TextLinkStyles(SpanStyle(color = GreenPrimary, fontWeight = FontWeight.SemiBold))
                    Text(
                        buildAnnotatedString {
                            append(it)
                            append(" ")
                            withLink(LinkAnnotation.Clickable("plan", linkStyle) { planOpen = true }) { append("غيّر التذكير") }
                        },
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("hifz_record_plan"), fontSize = 12.sp, color = TextMuted,
                    )
                }
                if (editing == null && planOpen) HifzPlanDialog(
                    auto = autoRecalls, pace = pace, times = times, settings = settings,
                    // Only what was changed: the count kept follows the slip, the times kept follow it too.
                    onChange = { chosen ->
                        if (chosen.pace != pace) chosenPace = chosen.pace.key
                        if (chosen.times != times) chosenTimes = chosen.times?.encode() ?: ""
                        touched = true
                    },
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
                        if (editing != null && corrected != null) {
                            onEdit(editing.corrected(editingIndex, corrected.copy(kind = kind, other = chosenOther, slipWords = slipWords, bothWays = both)))
                        } else onSave(verse, kind, chosenOther, slipWords, both, HifzPlan(pace, times))
                    },
                    enabled = ready,
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                    modifier = Modifier.fillMaxWidth().testTag("hifz_record_save"),
                ) {
                    Text(
                        when {
                            kind == HifzKind.Word && wrongWords.isEmpty() -> "اختر الكلمة"
                            kind.lookAlike && !otherChosen -> "اختر الآية أو «لا أعرف»"
                            editing != null -> "احفظ التعديل"
                            kind.lookAlike && other != null -> "سجّل مع ${hifzVerseFrom(catalog, other, verse)}"
                            kind.lookAlike -> "سجّل دون الآية الشبيهة"
                            // In the notebook already: what is saved is the mistake, not the verse.
                            recorded != null -> "سجّل الخطأ"
                            else -> "سجّل في دفتر المراجعة"
                        },
                    )
                }
                // One of several mistakes recorded wrongly goes alone; the verse's last one goes with the verse, from its correction.
                if (editing != null && editing.mistakes.size > 1) TextButton(
                    onClick = { removing = true }, modifier = Modifier.testTag("hifz_record_remove"),
                ) { Text("احذف هذا الخطأ") }
            }
        }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text(if (editing != null) "تجاهل التعديل؟" else "تجاهل هذا الموضع؟") },
        text = { Text("لن يُحفظ ما اخترته.") },
        confirmButton = { TextButton(onClick = { discard = false; onDismiss() }, modifier = Modifier.testTag("hifz_record_discard")) { Text("تجاهل") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("تابع") } },
    )
    if (removing && editing != null) AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text("حذف هذا الخطأ؟") },
        text = { Text("يبقى غيره مما سجّلته في هذه الآية.") },
        confirmButton = {
            TextButton(
                onClick = { removing = false; onEdit(editing.copy(mistakes = editing.mistakes.filterIndexed { i, _ -> i != editingIndex })) },
                modifier = Modifier.testTag("hifz_record_remove_confirm"),
            ) { Text("احذف") }
        },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("إلغاء") } },
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
    // Where the candidate parts from the verse: after a shared opening, else after the words they share in its course or
    // before its ending. Shown from two words before it, the parting marked: what tells the two apart, never a whole verse.
    // None for a verse that never parts from it (the same refrain), or that shares only its closing: shown from the
    // shared words, unmarked.
    val partAt: Int? = when {
        kind == HifzKind.Similar && o.prefix in 2 until words.size -> o.prefix
        o.run >= 2 && o.runB + o.run < words.size -> o.runB + o.run
        else -> null
    }
    val from = ((partAt ?: o.runB) - 2).coerceIn(0, (words.size - 1).coerceAtLeast(0))
    val where = if (candidate.verse.surah == verse.surah) "السورة نفسها" else "سورة أخرى"
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) AdhkarSoftGreen else Color.White)
            .border(1.dp, if (selected) GreenPrimary else CardBorder, RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp).testTag("hifz_lookalike_${candidate.verse.surah}_${candidate.verse.ayah}"),
    ) {
        Text(
            (if (selected) "✓ " else "") + listOfNotNull(hifzVerseName(catalog, candidate.verse), hifzPage(catalog, candidate.verse), where).joinToString(" · "),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark,
        )
        if (!selected) HifzVerseText(words, from = from, partAt = partAt, lead = if (from > 0) "… " else "", fontSize = 16.sp, color = TextDark, maxLines = 2)
        Text(
            when {
                // Said as the row marks it: a one-word opening («إن», «قال») is no shared opening.
                kind == HifzKind.Similar && o.prefix in 2 until words.size -> "تتفقان في أول ${hifzWordsLabel(o.prefix)} ثم تفترقان"
                kind == HifzKind.Ending -> "تتفقان في ${hifzWordsLabel(o.run)} قبل الخاتمة"
                else -> hifzRunLabel(o.run)
            },
            fontSize = 12.sp, color = TextMuted,
        )
        // The one chosen, in its place: where it sits on its page often tells it from the verse it is confused with.
        if (selected) QuranVerseExcerpt(
            catalog, candidate.verse, "${hifzVerseAbout(catalog, candidate.verse)} في المصحف",
            Modifier.padding(top = 6.dp), mark = QuranExcerptMark.Other,
        ) { HifzVerseText(words, from = from, partAt = partAt, lead = if (from > 0) "… " else "", fontSize = 16.sp) }
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
            placeholder = { Text("كلمات من الآية أو «صفحة 51» أو «البقرة 170»") },
            supportingText = when {
                searching -> ({ Text("جارٍ البحث…") })
                text.isNotBlank() && found.isEmpty() -> ({ Text(
                    if (onlySurah != null) "اختر آية بعد ${quranAyah(afterAyah)} من سورة ${catalog.surahs.getOrNull(onlySurah - 1)?.name.orEmpty()}."
                    else "لم نجد آية تطابق ما كتبت. جرّب كلمات أخرى أو «صفحة 51».",
                ) })
                else -> null
            },
            trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(painterResource(R.drawable.ic_adhkar_close), "مسح البحث") } },
            shape = RoundedCornerShape(12.dp),
        )
        if (found.isNotEmpty()) Text(hifzResultsLabel(found.size), fontSize = 12.sp, color = TextMuted)
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
            Text("اعرض المزيد · ${hifzResultsLabel(found.size - visibleCount, other = true)}")
        }
    }
}

/**
 * «تخطيت الآيتين 4 و5، وانتقلت إلى الآية 6: «…»»: what was skipped, as it is remembered, and where the recitation went, by
 * its first words. One verse by default; how many, or the verse found by its words, once the reader says it was more.
 */
@Composable
private fun HifzSkipDestination(catalog: QuranCatalog, verse: QuranVerseReference, landed: Int, onChange: (Int) -> Unit) {
    val count = catalog.surahs.getOrNull(verse.surah - 1)?.verseCount ?: verse.ayah
    // Open from the start when more than one was skipped, and kept open once opened, down to one again.
    var more by rememberSaveable(verse) { mutableStateOf(landed > verse.ayah + 1) }
    var choosing by rememberSaveable(verse) { mutableStateOf(false) }
    val skipped = landed - verse.ayah
    val landedWords = remember(verse, landed) { catalog.verse(QuranVerseReference(verse.surah, landed))?.let { hifzWords(it.text) } }
    // The landing verse's opening is shown, which may be another point's answer: it counts as seen once on screen.
    val onExposure by rememberUpdatedState(LocalQuranExposure.current)
    var shown by remember(verse, landed) { mutableStateOf(false) }
    LaunchedEffect(verse, landed, shown) { if (shown) onExposure(setOf(QuranVerseReference(verse.surah, landed))) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(hifzSkipSentence(verse.ayah, landed), fontSize = 14.sp, color = TextDark, modifier = Modifier.testTag("hifz_skip_sentence"))
        landedWords?.let {
            HifzVerseText(
                it, to = 4, trail = if (it.size > 4) "…" else "", fontSize = 18.sp,
                modifier = Modifier.onGloballyPositioned { c -> val b = c.boundsInWindow(); shown = b.width > 0f && b.height > 0f },
            )
        }
        if (!more && skipped == 1 && !choosing) TextButton(
            onClick = { more = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("hifz_skip_more"),
        ) { Text("تخطيت أكثر من آية؟", fontSize = 13.sp) }
        else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("كم آية تخطيت؟", fontSize = 13.sp, color = TextMuted)
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { more = true; onChange(landed - 1) }, enabled = skipped > 1) {
                    Icon(painterResource(R.drawable.ic_remove), "تقليص العدد")
                }
                Text("$skipped", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("hifz_skip_count"))
                IconButton(onClick = { more = true; onChange(landed + 1) }, enabled = landed < count) {
                    Icon(painterResource(R.drawable.ic_add), "زيادة العدد")
                }
            }
            TextButton(onClick = { choosing = !choosing }, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("hifz_skip_choose")) {
                Text(if (choosing) "أغلق البحث" else if (verse.ayah == 1) "ابحث عن الآية التي بدأت بها" else "ابحث عن الآية التي انتقلت إليها", fontSize = 13.sp)
            }
        }
        if (choosing) {
            Text("ابحث بكلمات من الآية أو اكتب مثلًا «${catalog.surahs.getOrNull(verse.surah - 1)?.name.orEmpty()} $count».", fontSize = 12.sp, color = TextMuted)
            HifzVerseFinder(catalog, onlySurah = verse.surah, afterAyah = verse.ayah) {
                onChange(it.ayah)
                choosing = false
            }
        }
    }
}

/**
 * «تخطيت الآية 4، وانتقلت إلى الآية 5:», «تخطيت الآيتين 4 و5، …», «تخطيت الآيات من 4 إلى 6، …»; at a surah's first verse
 * «…، وبدأت بالآية 2:». [first] is the first verse skipped, [landed] the one the recitation went to.
 */
internal fun hifzSkipSentence(first: Int, landed: Int): String {
    val last = landed - 1
    // One verse skipped, the sentence names both by their words; a series keeps its numbers, and so does the verse
    // the recitation went to beside them.
    val to = if (last == first) quranAyahOrdinal(landed) else "$landed"
    val skipped = when (last - first) {
        0 -> "تخطيت ${quranAyah(first)}"
        1 -> "تخطيت الآيتين $first و$last"
        else -> "تخطيت الآيات من $first إلى $last"
    }
    return "$skipped، " + (if (first == 1) "وبدأت بالآية $to:" else "وانتقلت إلى الآية $to:")
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
internal fun HifzWordPicker(words: HifzWords, selected: Set<Int>, suggested: Int?, multi: Boolean = false, onSelect: (Int) -> Unit) {
    if (suggested != null && suggested !in selected) Text(withQuranQuotes("نقترح «${words.printed[suggested]}»، اضغط عليها لتختارها."), fontSize = 12.sp, color = TextMuted)
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

/**
 * What «ضابطي» asks: the difference, for a verse confused with another; else by the mistake told last, a skip, a start
 * or a word having no pair to tell apart.
 */
internal fun hifzKeyPlaceholder(point: HifzPoint): String = when {
    point.mistakes.any { it.kind.lookAlike } -> "ما الذي يذكّرك بالفرق؟"
    point.latest.kind == HifzKind.Skip -> if (point.verse.ayah > 1) "ما الذي يربط آخر ${quranAyah(point.verse.ayah - 1)} بأول ${quranAyah(point.verse.ayah)}؟" else "ما الذي يذكّرك بأول السورة؟"
    point.latest.kind == HifzKind.Start -> "ما الذي يذكّرك بأول الآية؟"
    else -> "ما الذي يذكّرك بالكلمة الصحيحة؟"
}

/**
 * A correction that would answer a question still waiting, [due]'s: the question is offered first, the correction shown
 * only on purpose, as the notebook's dialog does when a row is tapped.
 */
@Immutable
internal data class HifzFixGate(val due: HifzPoint, val onReview: () -> Unit, val onOpen: () -> Unit)

/**
 * One fix in its sheet. Opened from the notebook, [position] is its place in the notebook's list as it was shown, and a
 * swipe, or the arrows, go on to the next point or back ([onStep]); the sheet stays, the points slide. A point whose
 * correction would answer a question still waiting shows [gate] instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    audio: HifzAudio? = null,
    audioControls: HifzAudioControls? = null,
    onSlipAgain: (() -> Unit)? = null,
    onUndoSlipAgain: (HifzPoint) -> Unit = {},
    onOtherSlip: (() -> Unit)? = null,
    /** Corrects the point's mistake at an index. */
    onEdit: ((Int) -> Unit)? = null,
    remind: Boolean = true,
    planChangeable: Boolean = true,
    /** Its place in the notebook's list and the list's size; null outside the notebook. */
    position: Pair<Int, Int>? = null,
    onStep: (Int) -> Unit = {},
    gate: HifzFixGate? = null,
) {
    // Each point as it was last shown, with its place and its gate: the one leaving keeps them while it slides out.
    val pages = remember { mutableMapOf<Long, Triple<HifzPoint, Pair<Int, Int>?, HifzFixGate?>>() }
    pages[point.id] = Triple(point, position, gate)
    var forward by remember { mutableStateOf(true) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val step: (Int) -> Unit = { delta -> forward = delta > 0; onStep(delta) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        AnimatedContent(
            targetState = point.id,
            transitionSpec = {
                // In Arabic the next point comes from the left, as the mushaf's next page does.
                val from = if (forward == rtl) -1 else 1
                slideInHorizontally { it * from } togetherWith slideOutHorizontally { -it * from }
            },
            label = "hifz_fix_page",
        ) { id ->
            val (shown, place, shownGate) = pages[id] ?: Triple(point, position, gate)
            // The point sliding out takes no tap: its buttons are the incoming point's now, which may be behind its question.
            val leaving = id != point.id
            Box(
                if (!leaving) Modifier else Modifier.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                },
            ) {
                HifzFixContent(
                    catalog, lookAlikes, shown, today, onChange, onDrill, onListen, onOpen, onDelete, onDismiss, audio, audioControls,
                    onSlipAgain, onUndoSlipAgain, onOtherSlip, onEdit, remind, planChangeable, place, step, shownGate,
                )
            }
        }
    }
}

/**
 * One fix: what went wrong in the verse, each mistake in its own part when there are several, the verse on its mushaf
 * page once, each look-alike below, why they are confused, and what to do. «تدرّب» and «استمع» stay in view at its foot;
 * what counts a slip again waits at its end, the delete in the header. [onEdit] corrects one of the mistakes recorded;
 * [onOpen] is null where the page is in view already, over the review.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HifzFixContent(
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
    /** The recitation playing now, shown at the bottom with its controls while the sheet hides the player; null for none. */
    audio: HifzAudio? = null,
    audioControls: HifzAudioControls? = null,
    /** «أخطأت فيها مرة أخرى»: counts the slip again; null where it does not belong, as over the review. */
    onSlipAgain: (() -> Unit)? = null,
    /** Puts the point back as it was before «أخطأت فيها مرة أخرى». */
    onUndoSlipAgain: (HifzPoint) -> Unit = {},
    /** «خطأ آخر في هذه الآية»: tells another way the verse went wrong; null where it does not belong. */
    onOtherSlip: (() -> Unit)? = null,
    onEdit: ((Int) -> Unit)? = null,
    /** The reminder is on: the sheet says when it asks, and why it cannot when notifications are off. */
    remind: Boolean = true,
    /**
     * Its plan can change here: not over the review, where a count already reached would end the questions of the card
     * in view, which would then be answered as a spaced check the same day.
     */
    planChangeable: Boolean = true,
    position: Pair<Int, Int>? = null,
    onStep: (Int) -> Unit = {},
    gate: HifzFixGate? = null,
) {
    val fixes = remember(point, lookAlikes) { hifzFixes(catalog, point, lookAlikes) }
    val appContext = LocalContext.current.applicationContext
    var plan by remember(point.id) { mutableStateOf<String?>(null) }
    // Behind its question the answer is not in view: said as its reminder will still ask it.
    LaunchedEffect(point, gate != null) { plan = withContext(Dispatchers.IO) { HifzReminder.pointSentence(appContext, point, answerShown = gate == null) } }
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
    var manageOpen by rememberSaveable(point.id) { mutableStateOf(false) }
    var editKey by rememberSaveable(point.id) { mutableStateOf(false) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val step by rememberUpdatedState(onStep)
    // A swipe goes on through the notebook: in Arabic the next point lies to the left, brought by a drag to the right.
    val swipe = if (position == null) Modifier else Modifier.pointerInput(position, rtl) {
        val (index, count) = position
        var dragged = 0f
        detectHorizontalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = {
            val far = 56.dp.toPx()
            val towardsNext = if (rtl) dragged > far else dragged < -far
            val towardsPrevious = if (rtl) dragged < -far else dragged > far
            if (towardsNext && index < count - 1) step(1) else if (towardsPrevious && index > 0) step(-1)
        }) { change, amount -> dragged += amount; change.consume() }
    }
    Column(Modifier.fillMaxWidth().quranSheetHeight().then(swipe)) {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)
                .testTag("hifz_fix_sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("التصحيح", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                // Behind its question, nothing that would show the verse: neither its page nor its record.
                if (onOpen != null && gate == null) {
                    // Beside three icons, the words leave «التصحيح» no room on a narrow phone, or with large text.
                    val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 400 ||
                        androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
                    if (narrow) IconButton(onClick = onOpen, modifier = Modifier.testTag("hifz_fix_open")) {
                        Icon(painterResource(R.drawable.ic_quran_locate), "عرض الآية في المصحف", Modifier.size(22.dp), tint = GreenPrimaryDark)
                    } else TextButton(onClick = onOpen, modifier = Modifier.testTag("hifz_fix_open")) { Text("في المصحف") }
                }
                // One mistake: corrected from here. Several: each from its own part, below.
                if (onEdit != null && gate == null && point.mistakes.size == 1) IconButton(onClick = { onEdit(0) }, modifier = Modifier.testTag("hifz_fix_edit")) {
                    Icon(painterResource(R.drawable.ic_edit), "تعديل الموضع", Modifier.size(20.dp), tint = GreenPrimaryDark)
                }
                // One tap, no question: the undo comes with what it deleted.
                IconButton(onClick = onDelete, modifier = Modifier.testTag("hifz_fix_delete")) {
                    Icon(painterResource(R.drawable.ic_delete), "حذف الموضع", Modifier.size(20.dp), tint = GreenPrimaryDark)
                }
                IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_adhkar_close), "إغلاق") }
            }
            position?.let { (index, count) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onStep(-1) }, enabled = index > 0, modifier = Modifier.testTag("hifz_fix_previous")) {
                        Icon(painterResource(R.drawable.ic_adhkar_back), "الموضع السابق", Modifier.size(20.dp))
                    }
                    Text(
                        "${index + 1} من $count", fontSize = 13.sp, color = TextMuted,
                        modifier = Modifier.semantics { contentDescription = "الموضع ${index + 1} من $count" }.testTag("hifz_fix_position"),
                    )
                    IconButton(onClick = { onStep(1) }, enabled = index < count - 1, modifier = Modifier.testTag("hifz_fix_next")) {
                        Icon(painterResource(R.drawable.ic_adhkar_next), "الموضع التالي", Modifier.size(20.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(hifzPointTitle(catalog, point), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                    Text(
                        listOfNotNull(
                            hifzSlipsLabel(point.slips), "يحتاج عناية".takeIf { point.needsCare },
                            // When it is asked is said once, by the line under it, which knows its answer is in view;
                            // how many right answers are left, by the ladder beside it.
                            "ثبتت".takeIf { point.mastered },
                        ).joinToString(" · "),
                        fontSize = 12.sp, color = TextMuted,
                    )
                    // When it is asked next, at which reminder; while it is fresh, its whole plan.
                    plan?.let { Text(it, fontSize = 12.sp, color = TextMuted, modifier = Modifier.testTag("hifz_fix_plan")) }
                    // While it is fresh, how many right answers it asks for, and how often, can change.
                    if (point.fresh > 0 && planChangeable) TextButton(
                        onClick = { planOpen = true }, modifier = Modifier.testTag("hifz_fix_plan_change"),
                    ) { Text("غيّر", fontSize = 13.sp) }
                    if (remind) HifzNotificationsWarning(listOf(HifzReminder.CHANNEL_ID), Modifier.padding(top = 2.dp))
                    if (!point.mastered && point.fresh == 0) Text(hifzHoldsAfter(point.checksToHold), fontSize = 12.sp, color = GreenPrimary)
                     point.recentFeedback?.let { Text(it, fontSize = 13.sp, color = GreenPrimaryDark) }
                }
                HifzLadder(point)
            }
            if (point.fresh > 0 && planOpen) HifzPlanDialog(
                auto = point.autoRecalls, pace = point.pace, times = point.times, settings = planSettings, done = point.rightSinceSlip,
                onChange = { chosen -> onChange(point.withPlan(chosen, today)) },
                onDismiss = { planOpen = false },
            )
            if (gate != null) {
                HifzFixGateBox(catalog, point, gate)
                return@Column
            }
            if (fixes.isEmpty()) {
                Text("هذه الآية غير موجودة في الفهرس.", color = TextMuted)
                return@Column
            }
            HifzFixBody(catalog, fixes, lookAlikes, onChange, onEdit = onEdit, learningNotes = false)
            val reasons = fixes.flatMap { it.reasons }.distinct()
            if (reasons.isNotEmpty()) HifzSection("لماذا يلتبس؟") {
                reasons.forEach { Text(withQuranQuotes(it), fontSize = 14.sp, color = TextDark) }
            }
            HifzSection("ما يذكّرك بها") {
                if (!editKey) {
                    if (key.isNotBlank()) Text(withQuranQuotes(key), fontSize = 15.sp, color = GreenPrimaryDark, modifier = Modifier.testTag("hifz_saved_key"))
                    TextButton(onClick = { editKey = true }, modifier = Modifier.testTag("hifz_edit_key")) {
                        Text(if (key.isBlank()) "أضف ملاحظة" else "عدّل ملاحظتك")
                    }
                } else {
                OutlinedTextField(
                    value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth().focusRequester(keyFocus).testTag("hifz_key_field"),
                    placeholder = { Text(hifzKeyPlaceholder(point)) }, shape = RoundedCornerShape(12.dp),
                )
                Text("تُحفظ ملاحظتك تلقائيًا.", fontSize = 12.sp, color = TextMuted)
                // Only for a pair to tell apart: a fact the app can vouch for first, then the teachers' headings, each
                // added after what is written, once.
                if (point.mistakes.any { it.kind.lookAlike }) {
                    val order = point.lookAlikes.firstOrNull()?.let { o ->
                        val (first, second) = if (point.verse < o) point.verse to o else o to point.verse
                        "${hifzVerseAbout(catalog, first)} قبل ${hifzVerseFrom(catalog, second, first)} في المصحف"
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
                "في «تدرّب» " + hifzDrillLabel(point, catalog.surahs.getOrNull(point.verse.surah - 1)?.verseCount, HifzDrillGap) { catalog.surahs.getOrNull(it - 1)?.name.orEmpty() } + ".",
                fontSize = 12.sp, color = TextMuted,
            )
            TextButton(onClick = { manageOpen = !manageOpen }, modifier = Modifier.testTag("hifz_fix_details")) {
                Text(if (manageOpen) "إخفاء خيارات الموضع" else "خيارات الموضع")
            }
            if (manageOpen) {
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
                if (onOtherSlip != null) TextButton(onClick = onOtherSlip, modifier = Modifier.testTag("hifz_other_slip")) { Text("سجّل خطأً آخر في هذه الآية") }
            }
            }
        }
        // Practice, always within reach: the drill, and the right wording heard; not behind a question, whose answer
        // they would play. What plays already keeps its controls there.
        if (gate == null || (audio != null && audioControls != null)) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (audio != null && audioControls != null) HifzAudioStrip(audio, audioControls)
            if (gate == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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

/** «حان وقتُ السؤال عن الآية 2 من سورة الملك» in the sheet, where its correction would be: the question first, the correction on purpose. */
@Composable
private fun HifzFixGateBox(catalog: QuranCatalog, opened: HifzPoint, gate: HifzFixGate) {
    Surface(shape = RoundedCornerShape(16.dp), color = GoldLight, modifier = Modifier.fillMaxWidth().testTag("hifz_fix_gate")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("حان وقتُ السؤال عن ${hifzVerseAbout(catalog, gate.due.verse)}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            Text(hifzSpoilSentence(catalog, gate.due, opened), fontSize = 14.sp, color = TextDark)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = gate.onReview, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.testTag("hifz_gate_review")) {
                    Text("اسألني الآن")
                }
                TextButton(onClick = gate.onOpen, modifier = Modifier.testTag("hifz_gate_open")) { Text("اعرض التصحيح") }
            }
        }
    }
}

/** Every answer given to the point's questions, the latest first: each question asked counts. */
@Composable
private fun HifzPointHistory(point: HifzPoint) {
    var all by rememberSaveable(point.id) { mutableStateOf(false) }
    HifzSection("أجوبتك", Modifier.testTag("hifz_point_history")) {
        if (point.checks.isEmpty()) {
            Text("لم نسألك عنها بعد.", fontSize = 13.sp, color = TextMuted)
        } else {
            val recent = point.checks.asReversed()
            (if (all) recent else recent.take(3)).forEach { check ->
                val date = remember(check.day) {
                    runCatching { LocalDate.ofEpochDay(check.day).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale("ar", "TN"))) }
                        .getOrElse { check.day.toString() }
                }
                val outcome = when (check.grade) {
                    HifzGrade.Held -> "تذكّرتها دون مساعدة"
                    HifzGrade.Hesitated -> "تردّدت أو احتجت مساعدة"
                    HifzGrade.Again -> "نسيت أو أخطأت"
                }
                Text("$date · $outcome", fontSize = 13.sp, color = if (check.grade == HifzGrade.Held) GreenPrimaryDark else TextDark)
            }
            if (recent.size > 3) TextButton(onClick = { all = !all }) {
                Text(if (all) "اعرض آخر 3${NB}أجوبة" else "اعرض كل الأجوبة (${recent.size})")
            }
        }
    }
}

/**
 * What «تدرّب» plays, as [com.tunisianprayertimes.quran.hifz.hifzDrill] builds it, said as a sentence: «تسمع الآيات
 * 46 و47 و48 متصلة 3 مرات، وتردّد بعد كل آية», «تسمع الآيتين 47 و115 بالتناوب 5 مرات، …», «تسمع الآية 47 بالتناوب مع
 * الآيتين 115 و20، …». With no [gap], nothing to repeat after. A look-alike in another chapter is named with it.
 */
internal fun hifzDrillLabel(point: HifzPoint, verseCount: Int? = null, gap: Float = 1f, surahName: (Int) -> String = { "" }): String {
    val v = point.verse.ayah
    val repeat = if (gap > 0f) "، وتردّد كل آية بعد القارئ" else ""
    val verses = hifzDrillVerses(point, verseCount)
    val others = point.lookAlikes
    val names = others.map { "${it.ayah}" }
    // One verse beside another: by their words in a sentence, their surahs when they are two; a series by its numbers,
    // or verse by verse when one of them is in another surah.
    val alone = others.singleOrNull()?.let { other ->
        if (other.surah == point.verse.surah) quranAyah(other.ayah) else quranVerseSentence(surahName(other.surah), other.ayah)
    }
    val elsewhere = others.any { it.surah != point.verse.surah }
    if (alone != null && verses.size == 1) {
        return if (others.single().surah == point.verse.surah) "تسمع الآيتين $v و${names[0]} بالتناوب 5${NB}مرات$repeat"
        else "تسمع ${quranAyah(v)} و$alone بالتناوب 5${NB}مرات$repeat"
    }
    if (names.isNotEmpty()) {
        val own = when (verses.size) {
            1 -> quranAyah(v)
            2 -> "الآيتين ${verses[0]} و${verses[1]} متصلتين"
            3 -> "الآيات " + verses.joinToString(" و") + " متصلة"
            else -> "الآيات من ${verses.first()} إلى ${verses.last()} متصلة"
        }
        val looks = when {
            alone != null -> alone
            elsewhere -> others.joinToString(" و") { other ->
                if (other.surah == point.verse.surah) quranAyah(other.ayah) else quranVerseSentence(surahName(other.surah), other.ayah)
            }
            names.size == 2 -> "الآيتين ${names[0]} و${names[1]}"
            else -> "الآيات " + names.joinToString(" و")
        }
        return "تسمع $own بالتناوب مع $looks$repeat"
    }
    return when (verses.size) {
        // The verse in view: its number beside the count would read as one number.
        1 -> "تسمع الآية 3${NB}مرات" + if (gap > 0f) "، وتردّدها بعد كل مرة" else ""
        2 -> "تسمع الآيتين ${verses[0]} و${verses[1]} متصلتين 3${NB}مرات$repeat"
        3 -> "تسمع الآيات " + verses.joinToString(" و") + " متصلة 3${NB}مرات$repeat"
        else -> "تسمع الآيات من ${verses.first()} إلى ${verses.last()} متصلة 3${NB}مرات$repeat"
    }
}

/**
 * The verses «تدرّب» plays for [point], as [com.tunisianprayertimes.quran.hifz.hifzPassage] does: from the verse before a
 * skip to where the recitation landed, the verse before and a forgotten opening, or the verse alone.
 */
internal fun hifzDrillVerses(point: HifzPoint, verseCount: Int?): List<Int> {
    val v = point.verse.ayah
    val from = (v - 1).coerceAtLeast(1)
    val landed = point.skipLanded
    return when {
        landed != null -> (from..(verseCount?.let { landed.coerceAtMost(it) } ?: landed)).toList()
        point.atStart -> (from..v).toList()
        else -> listOf(v)
    }
}

/** «نتيجة واحدة», «نتيجتان», «5 نتائج», «12 نتيجة»; [other]: the rest, «نتيجة أخرى», «3 نتائج أخرى». */
private fun hifzResultsLabel(count: Int, other: Boolean = false): String = when {
    count == 1 -> if (other) "نتيجة أخرى" else "نتيجة واحدة"
    count == 2 -> if (other) "نتيجتان أخريان" else "نتيجتان"
    count % 100 in 3..10 -> "$count${NB}نتائج" + if (other) " أخرى" else ""
    else -> "$count${NB}نتيجة" + if (other) " أخرى" else ""
}

/**
 * A short next step for the difficulty; Quran wording is shown in the scan, not repeated beneath it. Verses by their
 * number, never by how the scan marks them: «المظلّلة» also reads as darkened, and the scan tints a whole verse.
 */
internal fun hifzPracticeHint(point: HifzPoint, mistake: HifzMistake): String {
    val v = point.verse.ayah
    return when (mistake.kind) {
        HifzKind.Similar -> "قارن الآيتين، ثم اقرأ كل واحدة منهما من حفظك دون أن تدخل في الأخرى."
        HifzKind.Ending -> "لاحظ خاتمة كل من الآيتين، ثم اربطها بما قبلها واقرأ الآية كاملة من حفظك."
        HifzKind.Skip -> if (v > 1) "صِل آخر ${quranAyah(v - 1)} ب${quranAyah(v)}، ثم أكمل ما بعدها من حفظك." else "ابدأ بأول السورة، ثم أكمل إلى الآية التالية من حفظك."
        HifzKind.Start -> if (v > 1) "اربط آخر ${quranAyah(v - 1)} بأول ${quranAyah(v)}، ثم أعدهما من حفظك." else "تأمّل بداية السورة، ثم غطِّها وابدأ من حفظك."
        HifzKind.Word -> if (mistake.atStart && v > 1) "اربط آخر ${quranAyah(v - 1)} بأول ${quranAyah(v)}، ثم أعدهما من حفظك."
            else if (mistake.slipWords.isEmpty()) "راجع الآية كاملة وحدّد أين أخطأت، ثم أعد قراءتها من حفظك."
            else "راجع ${if (mistake.slipWords.size > 2) "الكلمات التي أخطأت فيها" else if (mistake.slipWords.size == 2) "الكلمتين اللتين أخطأت فيهما" else "الكلمة التي أخطأت فيها"}، ثم اقرأ الآية كاملة من حفظك."
    }
}

/**
 * The scan is the correction. Text is only its missing-image fallback; [ownOnPage] avoids repeating the reader. A verse
 * that went wrong more than one way has each mistake under its own heading, then the verse on its page once, every place
 * to mind marked in its text, then each look-alike.
 */
@Composable
internal fun HifzFixBody(
    catalog: QuranCatalog,
    fixes: List<HifzFix>,
    lookAlikes: HifzLookAlikes?,
    onChange: ((HifzPoint) -> Unit)? = null,
    ownOnPage: Boolean = true,
    /** Corrects the point's mistake at an index: also where its look-alike or its word was left unsaid. */
    onEdit: ((Int) -> Unit)? = null,
    learningNotes: Boolean = true,
    /** The reader's note with the learning notes; false where it is said already, under the review's answer. */
    note: Boolean = learningNotes,
    /** The look-alike's page behind one line, opened on demand: where they part is already shown above it. */
    otherFolded: Boolean = false,
    /** A word slip's words in large type; false where the review says them already, above its answers. A look-alike keeps its comparison. */
    exactWords: Boolean = true,
    /** The next step in a line; false over the review, whose question has said it and whose drill does it. */
    practice: Boolean = true,
) {
    val point = fixes.firstOrNull()?.point ?: return
    val v = point.verse
    val name = hifzVerseName(catalog, v)
    val about = hifzVerseAbout(catalog, v)
    val count = catalog.surahs.getOrNull(v.surah - 1)?.verseCount ?: v.ayah
    val landed = point.skipLanded?.coerceAtMost(count)
    fixes.forEach { fix ->
        val index = point.mistakes.indexOf(fix.mistake)
        val exact = exactWords || fix.mistake.kind.lookAlike
        // Which mistake each part corrects, once there is more than one, each corrected on its own; over the review,
        // whose heading names them all, only above a comparison, never over nothing.
        if (fixes.size > 1 && (onEdit != null || (fix.mistake.kind.lookAlike && fix.otherWords != null))) Row(
            Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(hifzMistakeTitle(catalog, v, fix.mistake), Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = QuranHifzViolet)
            if (onEdit != null) TextButton(onClick = { onEdit(index) }, modifier = Modifier.testTag("hifz_fix_edit_$index")) { Text("عدّل") }
        }
        if (exact) HifzExactCorrection(catalog, fix, onEdit?.let { edit -> { edit(index) } })
    }
    if (ownOnPage) {
        // Every place to mind, in the text shown when the scan is missing: the words said wrong, where a look-alike parts.
        val words = fixes.first().words
        val marked = fixes.flatMap { fix ->
            when {
                fix.mistake.kind == HifzKind.Word -> fix.mistake.slipWords
                fix.mistake.kind.lookAlike -> fix.focus
                else -> emptySet()
            }
        }.toSet()
        val partAt = fixes.firstOrNull { it.mistake.kind.lookAlike }?.partAt
        val text: (@Composable () -> Unit)? = if (marked.isEmpty() && partAt == null) null else ({ HifzVerseText(words, marked = marked, partAt = partAt) })
        val kinds = point.mistakes.map { it.kind }.toSet()
        when {
            // The way through the page: the end of the verse before, the verse skipped, and where the recitation landed.
            landed != null -> HifzSection("") {
                QuranVerseExcerpt(
                    catalog, v, "$about بين ما قبلها وما بعدها في المصحف",
                    linesBefore = 1, linesAfter = if (landed > v.ayah) 1 else 0, after = QuranVerseReference(v.surah, landed), fallback = text,
                )
            }
            HifzKind.Start in kinds -> HifzSection("") {
                QuranVerseExcerpt(catalog, v, "$about وما قبلها في المصحف", linesBefore = 1, fallback = text)
            }
            kinds.all { it.lookAlike } -> HifzSection(name) {
                QuranVerseExcerpt(catalog, v, "$about في المصحف", fallback = text)
            }
            else -> HifzSection("كما في المصحف") {
                QuranVerseExcerpt(catalog, v, "$about في المصحف", fallback = text)
            }
        }
    }
    fixes.forEach { fix ->
        val mistake = fix.mistake
        val index = point.mistakes.indexOf(mistake)
        val other = mistake.other
        val otherWords = fix.otherWords
        when {
            mistake.kind.lookAlike && other != null && otherWords != null && otherFolded -> {
                var open by rememberSaveable(point.id, other) { mutableStateOf(false) }
                TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("hifz_fix_other_page")) {
                    Text("${hifzVerseFrom(catalog, other, v)} في المصحف ${if (open) "▴" else "▾"}", fontSize = 13.sp)
                }
                if (open) QuranVerseExcerpt(catalog, other, "${hifzVerseAbout(catalog, other)} في المصحف", mark = QuranExcerptMark.Other) {
                    HifzVerseText(otherWords, marked = fix.otherFocus, markColor = TextMuted, color = TextMuted)
                }
            }
            mistake.kind.lookAlike && other != null && otherWords != null -> HifzSection("الشبيهة · ${hifzVerseName(catalog, other)}") {
                QuranVerseExcerpt(catalog, other, "${hifzVerseAbout(catalog, other)} في المصحف", mark = QuranExcerptMark.Other) {
                    HifzVerseText(otherWords, marked = fix.otherFocus, markColor = TextMuted, color = TextMuted)
                }
            }
            mistake.kind.lookAlike && onEdit != null -> Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (mistake.kind == HifzKind.Ending) "لم تحدّد الخاتمة التي قلتها." else "لم تحدّد الآية التي دخلت فيها.",
                    Modifier.weight(1f), fontSize = 13.sp, color = TextMuted,
                )
                TextButton(onClick = { onEdit(index) }, modifier = Modifier.testTag("hifz_fix_pick_other")) { Text("حدّدها") }
            }
            // The full destination remains editable, even when it is far beyond the next few verses; saved as the record
            // sheet saves it: no other verse when only this one was skipped.
            mistake.kind == HifzKind.Skip && landed != null && onChange != null && v.ayah < count ->
                HifzSkipDestination(catalog, v, landed) { onChange(point.corrected(index, mistake.copy(other = QuranVerseReference(v.surah, it).takeIf { _ -> it > v.ayah + 1 }))) }
        }
    }
    if (practice) fixes.map { hifzPracticeHint(point, it.mistake) }.distinct().forEach { Text(it, fontSize = 14.sp, color = GreenPrimaryDark) }
    if (learningNotes) {
        val reasons = fixes.flatMap { it.reasons }.distinct()
        if (reasons.isNotEmpty()) HifzSection("لماذا يلتبس؟") {
            reasons.forEach { Text(withQuranQuotes(it), fontSize = 14.sp, color = TextDark) }
        }
        if (note && point.key.isNotBlank()) HifzSection("ما يذكّرك بها") {
            Text(withQuranQuotes(point.key), fontSize = 15.sp, color = GreenPrimaryDark)
        }
    }
}

/** A textual focus remains visible beside the scans, which can only mark a whole verse. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HifzExactCorrection(catalog: QuranCatalog, fix: HifzFix, onEdit: (() -> Unit)?) {
    val mistake = fix.mistake
    when (mistake.kind) {
        HifzKind.Word -> HifzSection(if (mistake.slipWords.size > 2) "الكلمات الصحيحة" else if (mistake.slipWords.size == 2) "الكلمتان الصحيحتان" else "الكلمة الصحيحة", Modifier.testTag("hifz_exact_word")) {
            val selected = mistake.slipWords.sorted().filter { it in 0 until fix.words.size }
            if (selected.isEmpty()) {
                Text("لم تحدّد الكلمة التي أخطأت فيها بعد؛ راجع الآية كاملة، ثم حدّدها.", fontSize = 14.sp, color = TextDark)
                if (onEdit != null) TextButton(onClick = onEdit, modifier = Modifier.testTag("hifz_fix_pick_word")) { Text("حدّد الكلمة") }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    selected.forEach { index ->
                        HifzVerseText(fix.words, from = index, to = index + 1, marked = setOf(index), fontSize = 23.sp)
                    }
                }
                Text("هكذا تُقرأ في رواية قالون.", fontSize = 12.sp, color = TextMuted)
            }
        }
        HifzKind.Similar, HifzKind.Ending -> {
            val other = mistake.other
            val otherWords = fix.otherWords
            if (other != null && otherWords != null) HifzSection(
                if (fix.focus.isEmpty() && fix.otherFocus.isEmpty()) "قارن الآيتين" else "موضع الفرق",
                Modifier.testTag("hifz_exact_contrast"),
            ) {
                HifzContrastFragment(hifzVerseName(catalog, fix.point.verse), fix.words, fix.focus, fix.partAt, mistake.kind == HifzKind.Ending)
                HorizontalDivider(color = CardBorder)
                HifzContrastFragment("الشبيهة · ${hifzVerseName(catalog, other)}", otherWords, fix.otherFocus, fix.otherPartAt, mistake.kind == HifzKind.Ending)
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
 * The one-line facts of a point, for lists: «أخطأت فيها مرة · نسألك عنها اليوم الساعة 15:35», «أخطأت فيها مرتين ·
 * نسألك عنها الآن». When it is asked, as the reader is spoken to everywhere; how many right answers are left is the
 * ladder's. [next]: a fresh point's next question as its reminder brings it, worked out off the main thread («نسألك
 * عنها اليوم الساعة 15:35», «تجدها في «مراجعة اليوم» …»); without it, its day. A check of today not due now had its
 * verse looked at today: it is tomorrow's. A late one says so.
 */
internal fun hifzListDetail(point: HifzPoint, today: Long, due: Boolean, next: String? = null): String = listOfNotNull(
    "لم تحدّد الكلمة بعد".takeIf { point.mistakes.any { it.kind == HifzKind.Word && it.slipWords.isEmpty() } },
    hifzSlipsLabel(point.slips),
    "ثبتت".takeIf { point.mastered },
    when {
        point.fresh == 0 && point.dueDay < today && due -> hifzReviewWhen(point.dueDay - today)
        due -> "نسألك عنها الآن"
        point.fresh > 0 -> next ?: "نسألك عنها ${hifzWhen((point.dueDay - today).coerceAtLeast(0L))}"
        point.dueDay <= today -> "نسألك عنها غدًا"
        else -> hifzReviewWhen(point.dueDay - today)
    },
).joinToString(" · ")

/**
 * When the reminder comes: the quick choices first, then «مخصص» for the reader's own prayers and clock times. Never
 * none: the last time left stays, the reminder being turned off by its own switch.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HifzTimesChoice(
    times: HifzTimes,
    /** Its own times open as an editor; false keeps it closed, «مخصص» chosen, until tapped. */
    open: Boolean = HifzTimesPreset.of(times) == null,
    onChange: (HifzTimes) -> Unit,
) {
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
    var custom by rememberSaveable { mutableStateOf(open && preset == null) }
    // From the most a day to the fewest: two chips a row, the default first.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HifzTimesPreset.entries.reversed().forEach { option ->
            FilterChip(
                selected = !custom && HifzTimesPreset.of(shown) == option, onClick = { custom = false; change(option.times) },
                label = { Text(option.label) }, colors = colors, modifier = Modifier.testTag("hifz_times_${option.key}"),
            )
        }
        // Starts from the times chosen so far, to change one prayer or add a time to them.
        FilterChip(
            selected = custom || HifzTimesPreset.of(shown) == null, onClick = { custom = true }, label = { Text("مخصّص") }, colors = colors,
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
        Text("في ساعة محدّدة", fontSize = 12.sp, color = TextMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            times.minutes.sorted().forEach { minute ->
                val clock = HifzReminder.clock(minute)
                InputChip(
                    selected = true, onClick = { picking = minute }, label = { Text(clock) },
                    // The last time left stays: no remove that would do nothing.
                    trailingIcon = if (times.count > 1) ({
                        Icon(
                            painterResource(R.drawable.ic_close), "حذف $clock",
                            Modifier.size(18.dp).clip(CircleShape).clickable { onChange(times.copy(minutes = times.minutes - minute)) },
                        )
                    }) else null,
                    colors = InputChipDefaults.inputChipColors(selectedContainerColor = soft, selectedLabelColor = GreenPrimaryDark),
                    modifier = Modifier.testTag("hifz_times_time_$minute"),
                )
            }
            if (times.minutes.size < HifzMaxClockTimes) AssistChip(
                onClick = { picking = -1 }, label = { Text("أضف وقتًا") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp)) },
                modifier = Modifier.testTag("hifz_times_add_time"),
            )
        }
        // A time that falls in a prayer today: its reminder waits for the prayer's end, as it is told here.
        val inPrayer by produceState(emptyList<Pair<Int, Prayer>>(), times.minutes) {
            value = withContext(Dispatchers.IO) { times.minutes.sorted().mapNotNull { m -> HifzReminder.prayerAt(m)?.let { m to it } } }
        }
        inPrayer.forEach { (minute, prayer) ->
            Text(
                "الساعة$NB${HifzReminder.clock(minute)} توافق اليوم ${HifzReminder.prayerPhrase(prayer, todayEpochDay())}، فنذكّرك بعدها.",
                fontSize = 12.sp, color = TextMuted, modifier = Modifier.testTag("hifz_times_in_prayer_$minute"),
            )
        }
    }
    picking?.let { changing ->
        HifzTimeDialog(
            // A new time starts at the next full hour.
            minute = changing.takeIf { it >= 0 } ?: ((LocalTime.now().hour + 1) % 24 * 60),
            onPick = { picked -> picking = null; onChange(times.copy(minutes = times.minutes - changing + picked)) },
            onRemove = if (changing >= 0 && times.count > 1) ({ picking = null; onChange(times.copy(minutes = times.minutes - changing)) }) else null,
            onDismiss = { picking = null },
            adding = changing < 0,
        )
    }
}

/**
 * A 24-hour clock for one reminder time, a new one when [adding]; [onRemove] when a time already chosen may go, never
 * the last one left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HifzTimeDialog(minute: Int, onPick: (Int) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit, adding: Boolean) {
    val state = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (adding) "وقت تذكير جديد" else "وقت التذكير") },
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
                    Text("احذف", color = MaterialTheme.colorScheme.error)
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
        }) { Text("اسمح") }
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
        from == to -> "استمع إلى ${quranAyah(from)}"
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
                if (audio.playing) "إيقاف مؤقّت" else "تشغيل", tint = GreenPrimaryDark,
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
                    "تسجيل الخطأ",
                    "إذا توقّفت أو أخطأت وأنت تقرأ، فاضغط على الآية ثم «أخطأت»، واختر ما حدث. وإن أخطأت في الصلاة أو مع معلّمك، فسجّله من «سجّل موضعًا» في «المراجعة». وتجد تصحيحه في «دفتر المراجعة» متى احتجت إليه.",
                    "وإن أخطأت مرة أخرى في آية سجّلتها، فاضغط «أخطأت» عليها كذلك، فنضيف ما حدث إلى ما سجّلته فيها. فلكل آية موضع واحد، نسألك عنها كاملة.",
                )
                part(
                    "التذكير",
                    "بعد كل صلاة، أو في الأوقات التي تختارها في الإعدادات، يصلك إشعار حين يكون لديك ما تراجعه، فيه أسئلة مواضعك دون أجوبتها. اضغط «ابدأ المراجعة» لتقرأ الآيات من حفظك وهي مغطاة في المصحف. ولا يصلك الإشعار في وقت الصلاة: إن وافق وقتُه صلاةً، يصلك بعدها.",
                    "إذا أخطأت في آية، أو نسيتها حين سألناك عنها، نذكّرك بها إلى أن تتذكّرها 3${NB}مرات دون مساعدة، أو العدد الذي تختاره. ثم نذكّرك بها بعد يوم، ثم بعد 3${NB}أيام، ثم بعد أسبوع، ثم بعد أسبوعين.",
                    "نقترح لكل آية عددًا: 3${NB}مرات بعد أول خطأ فيها، و4 إذا عاد الخطأ، و5 إذا تكرّر كثيرًا، ومرتين لآية كنت تحفظها جيدًا. ومن «غيّر التذكير» تختار لكل آية متى نذكّرك بها (بعد كل صلاة، أو بعد صلوات تختارها، أو في ساعات تحدّدها)، وإلى متى: إلى أن تتذكّرها من مرة واحدة إلى 5${NB}مرات دون مساعدة.",
                    "النقاط بجانب كل موضع في «دفتر المراجعة» تُظهر تقدّمه: البنفسجية للمرات التي تذكّرت فيها آيته دون مساعدة، ثم الخضراء لأسئلته اللاحقة إلى أن تثبت.",
                )
                part(
                    "السؤال",
                    "تُغطّى الآية في المصحف، فتقرؤها من حفظك، ثم تضغط «أظهر» وتختار:",
                    "• «دون مساعدة»: تذكّرتها دون تردّد أو تلميح. بعد الخطأ، تُحسب من المرات المطلوبة، وبعدها يطول الوقت قبل أن تعود: 3${NB}أيام، ثم أسبوع، ثم أسبوعان.",
                    "• «بتردّد أو تلميح»: تذكّرتها بعد تردّد أو بمساعدة، فلا تُحسب. نسألك عنها في تذكير لاحق، أو بعد يومين إن كانت أسئلتها قد قلّت.",
                    "• «نسيت أو أخطأت»: يُسجّل الخطأ، ونذكّرك بها من جديد إلى أن تتذكّرها دون مساعدة، ويزيد العدد إن عاد الخطأ. وإن أخطأت في كلمة لم تسجّلها من قبل، فأضفها من «أخطأت في كلمة أخرى؟».",
                    "إذا استعنت بتلميح أو كشفت جزءًا من الآية، فاختر «تذكّرتها بمساعدة» أو «لم أتذكّرها».",
                    "لا نسألك عن آية رأيتها قبل قليل: إن كنت أخطأت فيها حديثًا، ننتظر أن تمرّ ${HifzFreshGapMs / 60_000L}${NB}دقيقة على آخر مرة رأيت فيها الصواب، أو أن تنتهي الصلاة التالية، أيّهما أقرب؛ وإن كانت أسئلتها قد قلّت، لا نسألك عنها قبل اليوم التالي. كل سؤال نطرحه عليك يُحسب.",
                    "بعد التردّد أو الخطأ، استمع إليها وكرّرها، ثم نسألك عنها في موعدها وهي مغطاة.",
                    "إذا تذكّرتها دون مساعدة في هذه الأسئلة كلها، فقد «ثبتت»، وتعود بعد شهر ثم كل شهرين للتأكد.",
                    "يظهر «يحتاج عناية» على الآية التي تكرّرت صعوبتها حديثًا، ويزول حين تتذكّرها دون مساعدة في يومين مختلفين بعد آخر خطأ.",
                )
                part("وضع المراجعة", "زر العين في أعلى المصحف يغطي الآيات، فتقرؤها من حفظك وتكشفها بإصبعك للتأكد.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("فهمت") } },
    )
}
