package com.tunisianprayertimes.ui

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tunisianprayertimes.R
import com.tunisianprayertimes.fasting.FastingReminder
import com.tunisianprayertimes.quran.hifz.HifzReminder
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the fasting notification asked «عباداتي» to open. */
internal object FastingRequests {
    var sheet by mutableStateOf(false)
    var suhoor by mutableStateOf(false)
}

/** Turns the reminder on, asking for notifications the first time; off at once. */
@Composable
private fun rememberFastingSwitch(onChanged: () -> Unit): (Boolean) -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    return { on ->
        FastingReminder.setEnabled(context, on)
        if (on) Toast.makeText(context, "سنذكّرك بعد صلاة المغرب قبل أيام الصيام التي اخترتها.", Toast.LENGTH_SHORT).show()
        if (on && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        onChanged()
    }
}

/** What the card shows, read off the main thread: it reads the prayer days and the alarms. */
private data class FastingCardState(
    val enabled: Boolean,
    val tonight: FastingReminder.Eve?,
    val title: String,
    val lines: List<String>,
    val suhoorAt: Instant?,
    val ownAlarm: Instant?,
    val fajr: String?,
    /** A suhoor ring still fits before Fajr tonight. */
    val canSuhoor: Boolean = false,
)

private fun fastingCardState(context: android.content.Context, now: Instant = Instant.now()): FastingCardState {
    val enabled = FastingReminder.isEnabled(context)
    val eve = FastingReminder.tonight(context, now)
    val chosenDay = eve != null && FastingReminder.isChosen(context, eve)
    val tonight = eve?.takeIf { enabled && chosenDay && !it.paused }
    return when {
        tonight != null -> {
            val suhoorAt = FastingReminder.suhoorAt(context, tonight, now)
            FastingCardState(
                enabled = true, tonight = tonight,
                title = FastingReminder.title(context, tonight, now),
                lines = emptyList(), suhoorAt = suhoorAt,
                ownAlarm = if (suhoorAt == null) FastingReminder.ownAlarm(context, tonight) else null,
                fajr = tonight.fajr?.let { FastingReminder.clockOf(context, it) },
                canSuhoor = tonight.fajr?.let { FastingReminder.suhoorOptions(it, now).isNotEmpty() } == true,
            )
        }
        // Off, on an eve of a day the reader keeps: the card asks, in its own place.
        !enabled && eve != null && chosenDay -> FastingCardState(
            enabled = false, tonight = null,
            title = FastingReminder.title(context, eve, now) + ".",
            lines = listOf("أتريد أن نذكّرك بعد صلاة المغرب بأيام صيام التطوع؟"),
            suhoorAt = null, ownAlarm = null, fajr = null,
        )
        else -> FastingCardState(
            enabled = enabled, tonight = null, title = "صيام التطوع",
            lines = FastingReminder.statusLines(context, now), suhoorAt = null, ownAlarm = null, fajr = null,
        )
    }
}

/**
 * The fasting card of «عباداتي», always there: off, it says what it does, and asks on an eve; on, it gives
 * the next reminder or why there is none; on the eve itself, the intention's deadline and the suhoor alarm.
 */
@Composable
internal fun FastingCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        revision++
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(60_000); revision++ }
    }
    var sheet by remember { mutableStateOf(false) }
    var suhoorSheet by remember { mutableStateOf<FastingReminder.Eve?>(null) }
    val switch = rememberFastingSwitch { revision++ }
    val state by produceState<FastingCardState?>(initialValue = null, revision) {
        value = withContext(Dispatchers.IO) { fastingCardState(context) }
    }
    val setSuhoor: (FastingReminder.Eve) -> Unit = { target ->
        scope.launch {
            withContext(Dispatchers.IO) { FastingReminder.setSuhoor(context, target) }
            revision++
        }
    }
    // One tap once chosen, with alarm access and the chosen minutes still in time; the questions otherwise.
    val askSuhoor: (FastingReminder.Eve) -> Unit = { target ->
        val options = target.fajr?.let { FastingReminder.suhoorOptions(it) }.orEmpty()
        if (FastingReminder.suhoorChosen(context) && FastingReminder.suhoorMinutes(context) in options &&
            com.tunisianprayertimes.wake.WakeAlarmScheduler.canScheduleExactAlarms(context)
        ) {
            setSuhoor(target)
        } else if (options.isNotEmpty()) {
            suhoorSheet = target
        }
    }
    // From the notification: the sheet, or the suhoor questions.
    LaunchedEffect(FastingRequests.sheet) {
        if (FastingRequests.sheet) { FastingRequests.sheet = false; sheet = true }
    }
    LaunchedEffect(FastingRequests.suhoor) {
        if (FastingRequests.suhoor) {
            // Cleared only after the read: clearing the key first would restart this effect and cancel it.
            val eve = withContext(Dispatchers.IO) { FastingReminder.tonight(context) }
            FastingRequests.suhoor = false
            eve?.let(askSuhoor)
        }
    }

    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(shape)
            .background(Color.White)
            .border(BorderStroke(1.dp, Gold.copy(alpha = 0.25f)), shape)
            .clickable(role = Role.Button) { sheet = true }
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(GoldLight.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_adhkar_moon), contentDescription = null, tint = GreenPrimaryDark, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val current = state
            Text(current?.title ?: "صيام التطوع", color = PrayerNameColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            when {
                current == null -> Unit
                current.tonight != null -> {
                    Text("إن شئت الصيام فانوِه الليلة قبل أذان الفجر في ${current.fajr} صباحًا", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                    when {
                        current.suhoorAt != null -> Text(
                            "منبه السحور ${FastingReminder.clockOf(context, current.suhoorAt)} · الفجر ${current.fajr}",
                            color = GreenPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp,
                        )
                        current.ownAlarm != null -> Text(
                            "سيوقظك منبهك في ${FastingReminder.clockOf(context, current.ownAlarm)} صباحًا",
                            color = GreenPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp,
                        )
                        current.canSuhoor -> OutlinedButton(
                            onClick = { askSuhoor(current.tonight) },
                            border = BorderStroke(1.dp, GreenPrimary.copy(alpha = .5f)),
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier.padding(top = 4.dp).heightIn(min = 40.dp),
                        ) {
                            Text("أيقظني للسحور", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp))
                        }
                        else -> Unit
                    }
                }
                else -> current.lines.forEach { Text(it, color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp) }
            }
        }
        Switch(
            checked = state?.enabled ?: FastingReminder.isEnabled(context),
            onCheckedChange = switch,
            modifier = Modifier.semantics { contentDescription = "تذكير صيام التطوع" },
        )
    }

    if (sheet) FastingSheet(onDismiss = { sheet = false }, onChanged = { revision++ })
    suhoorSheet?.let { target ->
        SuhoorAlarmSetupSheet(
            fajrAtMillis = target.fajr?.toEpochMilli(),
            initialMinutes = FastingReminder.suhoorMinutes(context),
            initialPersistence = FastingReminder.suhoorLevel(context),
            buttonLabel = "إضافة منبه السحور",
            onSave = { minutes, level ->
                FastingReminder.saveSuhoorChoice(context, minutes, level)
                setSuhoor(target)
            },
            onDismiss = { suhoorSheet = null },
        )
    }
}

/** Everything about the reminder: the days, a week's pause, the suhoor alarm, why nothing comes, and the intention. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FastingSheet(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        revision++
        onPauseOrDispose { }
    }
    val changed = { revision++; onChanged() }
    val switch = rememberFastingSwitch(changed)
    val enabled = remember(revision) { FastingReminder.isEnabled(context) }
    val fasts = remember(revision) { FastingReminder.fasts(context) }
    val lines by produceState(initialValue = emptyList<String>(), revision) {
        value = withContext(Dispatchers.IO) { FastingReminder.statusLines(context) }
    }
    val paused = remember(revision) { FastingReminder.isPaused(context) }
    var suhoorSheet by remember { mutableStateOf(false) }
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
                    Text("صيام التطوع", color = PrayerNameColor, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                    Text("نذكّرك بعد صلاة المغرب في الليلة التي قبل يوم الصيام.", color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp)
                }
                Switch(checked = enabled, onCheckedChange = switch,
                    modifier = Modifier.semantics { contentDescription = "تذكير صيام التطوع" })
            }
            if (enabled) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FastingReminder.Fast.entries.forEach { fast ->
                        FilterChip(
                            selected = fast in fasts,
                            onClick = { FastingReminder.setFast(context, fast, fast !in fasts); changed() },
                            label = { Text(fast.label, fontSize = 14.sp) },
                            leadingIcon = if (fast in fasts) {
                                { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp)) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = GreenPrimary.copy(alpha = 0.12f)),
                        )
                    }
                }
                ReminderNotificationsWarning(FastingReminder.CHANNEL_ID, "تذكير الصيام")
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .border(BorderStroke(1.dp, Gold.copy(alpha = 0.25f)), RoundedCornerShape(12.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    lines.forEach { Text(it, color = TextDark, fontSize = 14.sp, lineHeight = 21.sp) }
                    if (paused) {
                        TextButton(onClick = { FastingReminder.resume(context); changed() }) {
                            Text("استأنف التذكير", color = GreenPrimary, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { FastingReminder.pauseWeek(context) }
                                    changed()
                                    Toast.makeText(context, "أوقفنا التذكير أسبوعًا.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            border = BorderStroke(1.dp, GreenPrimary.copy(alpha = .4f)),
                        ) { Text("أوقف التذكير أسبوعًا", color = GreenPrimary) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { suhoorSheet = true }
                        .border(BorderStroke(1.dp, Gold.copy(alpha = 0.25f)), RoundedCornerShape(12.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("منبه السحور", color = TextDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "قبل الفجر بـ${FastingReminder.suhoorMinutes(context)} دقيقة · ${FastingReminder.suhoorLevel(context).title(context)}",
                            color = TextMuted, fontSize = 13.sp,
                        )
                    }
                    Text("غيّر", color = GreenPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                Text(
                    "لا نذكّرك بصيام أيام رمضان، فصيامها فرض، ولا بصيام العيدين وأيام التشريق، فهي لا تُصام. " +
                        "وما دام موعدها لم يُعلَن، أو كنت خارج تونس، فلا نذكّرك أيضًا بصيام اليوم الذي يسبقها أو يليها، " +
                        "كيوم عرفة والثاني من شوال.",
                    color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp,
                )
                // Only Ramadan and the two Eids are announced; Ashura and the white days are named on any day chosen.
                if (fasts.any { it != FastingReminder.Fast.ARAFA && it != FastingReminder.Fast.SHAWWAL }) {
                    Text("قد يتقدّم موعد تاسوعاء وعاشوراء والأيام البيض أو يتأخّر يومًا، إذ لا يصلنا إلا إعلان رمضان والعيدين.",
                        color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp)
                }
            }
            HorizontalDivider(color = Gold.copy(alpha = 0.18f))
            Text("النية", color = PrayerNameColor, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text("النية أن تعزم الليلة على صيام الغد، ولا تحتاج إلى لفظ. ولا يضرّك أن تأكل أو تنام بعدها.",
                color = TextDark, fontSize = 14.sp, lineHeight = 22.sp)
            Text("لماذا هذه الأيام؟", color = PrayerNameColor, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            FastingReason("تُعرض الأعمال يومي الاثنين والخميس، وكان النبي ﷺ يحبّ أن يُعرض عمله وهو صائم.", "سنن الترمذي 747")
            FastingReason("والاثنين يوم وُلد فيه النبي ﷺ، ويوم أُنزل عليه الوحي.", "صحيح مسلم 1162")
            FastingReason(
                "ورجا النبي ﷺ أن يكفّر الله بصيام يوم عرفة السنة التي قبله والتي بعده، وبصيام عاشوراء السنة التي قبله.",
                "صحيح مسلم 1162",
            )
            FastingReason("وعزم ﷺ على صيام التاسع إن بقي إلى العام القادم، فيُصام تاسوعاء مع عاشوراء.", "صحيح مسلم 1134")
            FastingReason("وشرب ﷺ اللبن يوم عرفة وهو واقف بها، فالفطر فيه أفضل للحاج.", "صحيح البخاري 1988")
            FastingReason(
                "وأمر ﷺ أبا ذرّ، إن صام من الشهر ثلاثة أيام، أن يصوم الثالث عشر والرابع عشر والخامس عشر، وهي الأيام البيض.",
                "سنن الترمذي 761",
            )
            FastingReason("وأخبر ﷺ أن من صام رمضان ثم أتبعه ستة أيام من شوال كان كمن صام الدهر.", "صحيح مسلم 1164")
        }
    }
    if (suhoorSheet) SuhoorAlarmSetupSheet(
        fajrAtMillis = null,
        initialMinutes = FastingReminder.suhoorMinutes(context),
        initialPersistence = FastingReminder.suhoorLevel(context),
        buttonLabel = "حفظ",
        onSave = { minutes, level ->
            FastingReminder.saveSuhoorChoice(context, minutes, level)
            changed()
            // An alarm set for tonight that has not rung yet takes the new choice too.
            scope.launch {
                withContext(Dispatchers.IO) {
                    FastingReminder.tonight(context)?.takeIf { FastingReminder.suhoorPending(context, it) }
                        ?.let { FastingReminder.setSuhoor(context, it) }
                }
                changed()
            }
        },
        onDismiss = { suhoorSheet = false },
    )
}

@Composable
private fun FastingReason(text: String, source: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, color = TextDark, fontSize = 14.sp, lineHeight = 22.sp)
        Text(source, color = TextMuted, fontSize = 12.sp)
    }
}

/** Shown only while the reminder cannot reach the reader, with the one way to allow it. */
@Composable
internal fun ReminderNotificationsWarning(channelId: String, channelName: String) {
    val context = LocalContext.current
    var check by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        check++
        onPauseOrDispose { }
    }
    val problem = remember(check) {
        when {
            !HifzReminder.appNotificationsAllowed(context) -> "الإشعارات متوقّفة: لن يصلك أي تذكير."
            context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE ->
                "إشعارات «$channelName» متوقّفة في إعدادات الهاتف."
            else -> null
        }
    }
    var askedAt by remember { mutableLongStateOf(0L) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        check++
        // Once refused for good, the system answers at once without asking: the settings are then the only way.
        if (!granted && SystemClock.elapsedRealtime() - askedAt < 500) openHifzNotificationSettings(context, listOf(channelId))
    }
    if (problem == null) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(problem, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        TextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                askedAt = SystemClock.elapsedRealtime()
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openHifzNotificationSettings(context, listOf(channelId))
            }
        }) { Text("اسمح") }
    }
}
