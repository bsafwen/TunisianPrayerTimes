package com.tunisianprayertimes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.adhkar.DhikrClosingTexts
import com.tunisianprayertimes.adhkar.DhikrSession
import com.tunisianprayertimes.adhkar.MushafPassages
import com.tunisianprayertimes.adhkar.isOutsideOccasionPeriod
import kotlinx.coroutines.delay
import java.time.LocalDate

/** Which reading was finished, and so which words close it. */
internal enum class DhikrClosingKind { MORNING, EVENING, NIGHT, SLEEP, SALAH, REMINDER, OUTSIDE }

/**
 * The closing card a reading earns once it is complete, or null for none: a whole wird (the morning,
 * evening and night lists, the bedtime and the after-prayer adhkar) and a reading toward a reminder's
 * goal. A single dhikr opened from the library counts freely and gets none, nor do the lists of
 * occasions (home, travel, food) read through like a book.
 */
internal fun closingKind(session: DhikrSession, fromReminder: Boolean): DhikrClosingKind? {
    val periodKey = session.collectionPeriodKey ?: return if (fromReminder) DhikrClosingKind.REMINDER else null
    return when (session.category) {
        // Read outside its time, the list is dhikr all the same, but not that time's adhkar.
        DhikrCategory.MORNING -> DhikrClosingKind.MORNING
        DhikrCategory.EVENING -> if (isOutsideOccasionPeriod(periodKey)) DhikrClosingKind.OUTSIDE else DhikrClosingKind.EVENING
        DhikrCategory.NIGHT -> DhikrClosingKind.NIGHT
        DhikrCategory.SLEEP -> DhikrClosingKind.SLEEP
        DhikrCategory.SALAH -> DhikrClosingKind.SALAH
        else -> null
    }
}

private class ClosingGreeting(val title: String, val dua: String)

/**
 * The card's own words: completion and a dua, never a promise on Allah's behalf. The reward itself is
 * left to [DhikrClosingTexts]. [shift] gives the readings of one day different texts from it.
 */
private class ClosingWords(
    val icon: Int,
    val completion: String,
    val shift: Int,
    val greetings: List<ClosingGreeting>,
)

private fun closingWords(kind: DhikrClosingKind, readingTitle: String): ClosingWords = when (kind) {
    DhikrClosingKind.MORNING -> ClosingWords(R.drawable.ic_adhkar_sun,
        "أتممت أذكار الصباح بفضل الله.", 0, listOf(
            ClosingGreeting("صبّحك الله بالخير", "بارك الله لك في يومك، وأعانك على ذكره وشكره."),
            ClosingGreeting("بارك الله في صباحك", "رزقك الله يومًا طيبًا، وعملًا صالحًا، وقلبًا مطمئنًا."),
            ClosingGreeting("يسّر الله يومك", "فتح الله لك أبواب الخير، وأعانك على ما ينفعك."),
            ClosingGreeting("أدام الله عليك الخير", "جعل الله صباحك بدايةً ليومٍ عامرٍ بالطاعة."),
        ))
    DhikrClosingKind.EVENING -> ClosingWords(R.drawable.ic_adhkar_moon,
        "أتممت أذكار المساء بفضل الله.", 3, listOf(
            ClosingGreeting("مسّاك الله بالخير", "حفظك الله في ليلتك، ورزقك السكينة والطمأنينة."),
            ClosingGreeting("بارك الله في مسائك", "أراح الله قلبك، وجعل لك في ليلتك خيرًا."),
            ClosingGreeting("أنزل الله عليك السكينة", "رزقك الله مساءً طيبًا، وقلبًا حاضرًا بذكره."),
            ClosingGreeting("أدام الله عليك العافية", "صرف الله عنك السوء، وأعانك على طاعته."),
        ))
    DhikrClosingKind.NIGHT -> ClosingWords(R.drawable.ic_adhkar_moon,
        "أتممت أذكار الليل بفضل الله.", 1, listOf(
            ClosingGreeting("نوّر الله قلبك", "غفر الله لنا ولك، وأعانك على طاعته."),
            ClosingGreeting("آنس الله قلبك بذكره", "رزقك الله سكينةً في ليلتك، وحسن التوكل عليه."),
            ClosingGreeting("أحاطك الله بلطفه", "أذهب الله همّك، وشرح صدرك، ويسّر أمرك."),
            ClosingGreeting("رزقك الله طمأنينة القلب", "أعانك الله على ذكره، وملأ ليلتك خيرًا."),
        ))
    DhikrClosingKind.SLEEP -> ClosingWords(R.drawable.ic_adhkar_sleep,
        "أتممت أذكار النوم بفضل الله.", 4, listOf(
            ClosingGreeting("حفظك الله في منامك", "رزقك الله نومًا هادئًا، وأيقظك على خير."),
            ClosingGreeting("أراح الله قلبك", "جعل الله نومك راحةً، ويقظتك عونًا على طاعته."),
            ClosingGreeting("رزقك الله سكينةً", "أذهب الله عنك القلق، وكتب لك العافية."),
            ClosingGreeting("حفظك الله ورعاك", "رزقك الله راحةً في منامك، ونشاطًا عند يقظتك."),
        ))
    // Not «تقبّل الله» after every prayer: the card would make a set formula of it.
    DhikrClosingKind.SALAH -> ClosingWords(R.drawable.ic_adhkar_tasbih,
        "أتممت أذكار ما بعد الصلاة بفضل الله.", 2, listOf(
            ClosingGreeting("بارك الله فيك", "أعانك الله على ذكره وشكره وحسن عبادته."),
            ClosingGreeting("زادك الله هدًى", "رزقك الله قلبًا خاشعًا، ولسانًا ذاكرًا."),
            ClosingGreeting("أعانك الله على الخير", "ثبّتك الله على طاعته، ويسّر لك عملًا صالحًا."),
            ClosingGreeting("شرح الله صدرك", "رزقك الله الإخلاص، وأعانك على حسن عبادته."),
        ))
    DhikrClosingKind.REMINDER -> ClosingWords(R.drawable.ic_adhkar_leaf,
        "أتممت وِردك من «$readingTitle» بفضل الله.", 5, listOf(
            ClosingGreeting("تقبّل الله ذكرك", "أعانك الله على المداومة على ذكره."),
            ClosingGreeting("زادك الله ثباتًا", "يسّر الله لك المداومة، وبارك في وقتك وعملك."),
            ClosingGreeting("بارك الله في وِردك", "جعل الله لك من ذكره أُنسًا، وأعانك على الاستمرار."),
            ClosingGreeting("أعانك الله على المداومة", "رزقك الله قلبًا محبًّا لذكره، وهمّةً في طاعته."),
        ))
    DhikrClosingKind.OUTSIDE -> ClosingWords(R.drawable.ic_adhkar_leaf,
        "أتممت هذه القراءة بفضل الله.", 0, listOf(
            ClosingGreeting("تقبّل الله ذكرك", "جعل الله أيامك عامرةً بذكره."),
            ClosingGreeting("بارك الله في قراءتك", "رزقك الله قلبًا مطمئنًا، ولسانًا رطبًا بذكره."),
            ClosingGreeting("أنار الله قلبك", "جعل الله لك في ذكره راحةً، وفي طاعته أُنسًا."),
            ClosingGreeting("زادك الله قربًا منه", "بارك الله في وقتك، وأعانك على كل خير."),
        ))
}

/**
 * Shown over the reader once a reading is complete: a completion message, a dua, and a verse or
 * hadith on the reward of remembering Allah. Taps outside it do nothing, so quick counting cannot
 * dismiss it unread; back returns to the reading.
 */
@Composable
internal fun DhikrClosingCard(kind: DhikrClosingKind, readingTitle: String, onDone: () -> Unit, onStay: () -> Unit) {
    val p = LocalAdhkarPalette.current
    val words = remember(kind, readingTitle) { closingWords(kind, readingTitle) }
    // Pick once per card; counting touches, recomposition and rotation keep the same greeting.
    val greetingIndex = rememberSaveable(kind, readingTitle) { words.greetings.indices.random() }
    val greeting = words.greetings[greetingIndex]
    val reward = remember(kind) { DhikrClosingTexts.rewardFor(LocalDate.now().toEpochDay(), words.shift) }
    // Someone counting without looking keeps tapping after the last count: the buttons act only once
    // the card has gone untouched for a moment, every touch starting that moment over.
    var armed by remember { mutableStateOf(false) }
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(touches) { armed = false; delay(600); armed = true }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val morning = kind == DhikrClosingKind.MORNING
    Dialog(onDismissRequest = onStay,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        AnimatedVisibility(shown, enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = .94f)) {
            Surface(shape = RoundedCornerShape(28.dp), color = AdhkarSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 32.dp).widthIn(max = 440.dp).fillMaxWidth()
                    .pointerInput(Unit) {
                        // Watched before the buttons see it, and left to them.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (!armed) touches++
                        }
                    }
                    .testTag("adhkar_closing_card")) {
                // Only the words scroll: on a small screen or with large text the buttons stay in view.
                Column(Modifier.padding(horizontal = 22.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(64.dp).clip(CircleShape).background(if (morning) AdhkarSoftGold else AdhkarSoftGreen),
                            contentAlignment = Alignment.Center) {
                            DhikrIcon(words.icon, tint = if (morning) AdhkarGoldAccent else p.primary, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(greeting.title, Modifier.semantics { heading() }, color = AdhkarHeading, fontSize = 22.sp,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        Text(words.completion, color = p.forest, fontSize = 15.sp, lineHeight = 26.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        Text(greeting.dua, color = p.muted, fontSize = 14.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(20.dp))
                        Surface(shape = RoundedCornerShape(20.dp), color = AdhkarSoftGreen.copy(alpha = .55f),
                            border = BorderStroke(1.dp, AdhkarBorder), modifier = Modifier.fillMaxWidth().testTag("adhkar_closing_reward")) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(if (reward.theme == DhikrClosingTexts.ClosingTheme.PERSEVERANCE) "من فضل المداومة" else "من فضل الذكر",
                                    color = p.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                // A verse as the mushaf prints it; a hadith reads as running text.
                                val mushaf = if (reward.quran) MushafPassages.text(reward.id) else null
                                if (mushaf != null) MushafPassage(mushaf, reward.text, 17.sp, 34.sp, p.forest,
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                                else if (reward.quran) Text("﴿" + reward.text + "﴾", Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    fontFamily = AdhkarReadingFont, fontSize = 19.sp, lineHeight = 36.sp, color = p.forest, textAlign = TextAlign.Center)
                                else Text(reward.text, Modifier.fillMaxWidth().padding(horizontal = 16.dp), fontFamily = AdhkarReadingFont, fontSize = 16.sp,
                                    lineHeight = 31.sp, color = p.forest, textAlign = TextAlign.Right)
                                Text(reward.reference, color = p.muted, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { if (armed) onDone() }, shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("adhkar_closing_done")) {
                        Text("العودة إلى الأذكار", fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { if (armed) onStay() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("adhkar_closing_stay")) {
                        Text("متابعة القراءة", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
