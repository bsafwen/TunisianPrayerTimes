package com.tunisianprayertimes.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.DeathRemembranceTexts
import com.tunisianprayertimes.adhkar.MushafPassages
import com.tunisianprayertimes.quran.QuranMushafText
import com.tunisianprayertimes.remembrance.DeathReminder
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the reminder's notification asked «عباداتي» to open. */
internal object RemembranceRequests {
    var sheet by mutableStateOf(false)
}

/** What the card says under the day's deed about the reminder: off, how often, or paused until when. */
private fun reminderStatus(context: android.content.Context): String {
    if (!DeathReminder.isEnabled(context)) return "نرسل إليك هذا التذكير إن شئت، في ساعة من النهار تختلف كل مرة."
    DeathReminder.pausedUntil(context)?.let { return "أوقفنا التذكير، ويعود يوم ${it.dayMonth()}." }
    return "يصلك تذكير ${DeathReminder.frequency(context).label}، في ساعة من النهار تختلف كل مرة."
}

/** Turns the reminder on, asking for notifications the first time; off at once. */
@Composable
private fun rememberRemembranceSwitch(onChanged: () -> Unit): (Boolean) -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    return { on ->
        DeathReminder.setEnabled(context, on)
        if (on) Toast.makeText(context, "سيصلك التذكير ${DeathReminder.frequency(context).label}، دون صوت.", Toast.LENGTH_SHORT).show()
        if (on && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        onChanged()
    }
}

/** A verse as the mushaf prints it, a hadith as running text. */
@Composable
private fun RemembranceText(item: DeathRemembranceTexts.Remembrance) {
    val mushaf = if (item.quran) MushafPassages.text(item.id) else null
    if (mushaf != null) {
        MushafPassage(mushaf, QuranMushafText.plain(mushaf), 17.sp, 34.sp, GreenPrimaryDark, Modifier.fillMaxWidth())
    } else if (!item.quran) {
        Text(item.text, Modifier.fillMaxWidth(), fontFamily = AdhkarReadingFont, fontSize = 16.sp, lineHeight = 31.sp,
            color = TextDark, textAlign = TextAlign.Start)
    }
}

/** Source context stays available without making every day's reading longer. */
@Composable
private fun RemembranceSource(item: DeathRemembranceTexts.Remembrance) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var expanded by remember(item.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(item.reference, color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp,
                modifier = Modifier.weight(1f).padding(end = 8.dp))
            RemembranceDisclosure("المصدر", expanded, onClick = { expanded = !expanded })
        }
        if (expanded) {
            if (item.quran) {
                Text("نعرض النص وترقيم الآيات كما في مصحف قالون المعتمد في التطبيق. وقد يختلف رقم الآية بين المصاحف باختلاف طريقة عدّ الآيات.",
                    color = TextMuted, fontSize = 13.sp, lineHeight = 21.sp)
            }
            item.sourceNote?.let { note ->
                Text(note, color = TextMuted, fontSize = 13.sp, lineHeight = 21.sp)
            }
            item.sourceLinks.forEach { link ->
                TextButton(onClick = {
                    runCatching { uriHandler.openUri(link.url) }.onFailure {
                        Toast.makeText(context, "تعذّر فتح رابط المصدر.", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(link.label, color = GreenPrimary, fontSize = 13.sp)
                }
            }
            item.sourceUrl?.let { url ->
                TextButton(onClick = {
                    runCatching { uriHandler.openUri(url) }.onFailure {
                        Toast.makeText(context, "تعذّر فتح رابط المصدر.", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("اقرأ المصدر في المتصفّح", color = GreenPrimary, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun RemembranceDisclosure(label: String, expanded: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.semantics { stateDescription = if (expanded) "مفتوح" else "مطويّ" },
    ) {
        Text(label, color = GreenPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f, fill = false))
        Icon(painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
            contentDescription = null, tint = GreenPrimary, modifier = Modifier.padding(start = 4.dp).size(18.dp))
    }
}

/**
 * The card of «ذكر الموت» in «عباداتي», always there: the day's verse or hadith with its source, the day's good deed
 * and optional reflection, and the reminder's switch. The text changes at midnight, the same as the day's notification.
 */
@Composable
internal fun DeathRemembranceCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        revision++
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(60_000); revision++ }
    }
    var sheet by remember { mutableStateOf(false) }
    LaunchedEffect(RemembranceRequests.sheet) {
        if (RemembranceRequests.sheet) { RemembranceRequests.sheet = false; sheet = true }
    }
    val switch = rememberRemembranceSwitch { revision++ }
    val today = remember(revision) { DeathReminder.today(context) }
    val item = DeathReminder.remembranceFor(today)
    var reflectionExpanded by remember(item.id) { mutableStateOf(false) }
    val enabled = remember(revision) { DeathReminder.isEnabled(context) }
    val status = remember(revision) { reminderStatus(context) }

    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White)
            .border(BorderStroke(1.dp, Gold.copy(alpha = 0.25f)), shape)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(item.title, color = PrayerNameColor, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().semantics { heading() })
        RemembranceText(item)
        RemembranceSource(item)
        HorizontalDivider(color = Gold.copy(alpha = 0.18f))
        Text("اقتراح لليوم", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() })
        Text(item.deed, color = GreenPrimaryDark, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
        item.reflection?.let { reflection ->
            RemembranceDisclosure("سؤال للتأمل", reflectionExpanded, onClick = { reflectionExpanded = !reflectionExpanded })
            if (reflectionExpanded) {
                Text(reflection, color = TextDark, fontSize = 14.sp, lineHeight = 23.sp,
                    modifier = Modifier.fillMaxWidth())
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { sheet = true }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text("التذكير", color = TextDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(status, color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Switch(checked = enabled, onCheckedChange = switch,
                modifier = Modifier.semantics { contentDescription = "تذكير ذكر الموت" })
        }
    }
    if (sheet) DeathRemembranceSheet(onDismiss = { sheet = false }, onChanged = { revision++ })
}

/** The reminder's choices: how often, a month's pause, and why remembering death is kept here. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DeathRemembranceSheet(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        revision++
        onPauseOrDispose { }
    }
    val changed = { revision++; onChanged() }
    val switch = rememberRemembranceSwitch(changed)
    val enabled = remember(revision) { DeathReminder.isEnabled(context) }
    val frequency = remember(revision) { DeathReminder.frequency(context) }
    val pausedUntil = remember(revision) { DeathReminder.pausedUntil(context) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("ذكر الموت", color = PrayerNameColor, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                    Text("كل يوم آية أو حديث يذكّرك بالآخرة، ومعه اقتراح لخير تقدر عليه.", color = TextMuted, fontSize = 13.sp,
                        lineHeight = 20.sp)
                }
                Switch(checked = enabled, onCheckedChange = switch,
                    modifier = Modifier.semantics { contentDescription = "تذكير ذكر الموت" })
            }
            if (enabled) {
                Text("كم مرة يصلك التذكير؟", color = TextDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeathReminder.Frequency.entries.forEach { choice ->
                        FilterChip(
                            selected = choice == frequency,
                            onClick = { DeathReminder.setFrequency(context, choice); changed() },
                            label = { Text(choice.label, fontSize = 14.sp) },
                            leadingIcon = if (choice == frequency) {
                                { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp)) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = GreenPrimary.copy(alpha = 0.12f)),
                        )
                    }
                }
                Text("يصلك في ساعة من النهار تختلف كل مرة، بعيدًا عن أوقات الصلاة وعن قراءتك للقرآن، ودون صوت.",
                    color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp)
                ReminderNotificationsWarning(DeathReminder.CHANNEL_ID, "ذكر الموت")
                if (pausedUntil != null) {
                    Text("أوقفنا التذكير، ويعود يوم ${pausedUntil.dayMonth()}.", color = TextDark, fontSize = 14.sp)
                    TextButton(onClick = { DeathReminder.resume(context); changed() }) {
                        Text("استأنف التذكير", color = GreenPrimary, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { DeathReminder.pauseMonth(context) }
                                changed()
                            }
                        },
                        border = BorderStroke(1.dp, GreenPrimary.copy(alpha = .4f)),
                    ) { Text("أوقف التذكير شهرًا", color = GreenPrimary) }
                }
            }
            HorizontalDivider(color = Gold.copy(alpha = 0.18f))
            Text("لماذا نذكر الموت؟", color = PrayerNameColor, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            Text(DeathRemembranceTexts.ANCHOR.text, fontFamily = AdhkarReadingFont, color = TextDark, fontSize = 15.sp,
                lineHeight = 28.sp)
            RemembranceSource(DeathRemembranceTexts.ANCHOR)
            Text("نتذكّر الموت لنراجع ما نقدّمه، ونرجع إلى الله، ونحفظ حقوق الناس، ونحسن العمل فيما بقي من أعمارنا.",
                color = TextDark, fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}
