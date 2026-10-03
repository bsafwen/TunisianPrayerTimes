package com.tunisianprayertimes.ui

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranVerse
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.audio.QuranAudioController
import com.tunisianprayertimes.quran.audio.QuranPlaybackState
import com.tunisianprayertimes.quran.audio.QuranRepeatRange
import com.tunisianprayertimes.quran.assets.QuranPackStatus
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.util.Locale

/** Playback belongs to the service; the reader only observes it and issues explicit controls. */
@Composable
internal fun QuranAudioControls(
    /** Null while the text index is still being read; recitation cannot be chosen before it. */
    catalog: QuranCatalog?,
    currentPage: Int,
    following: Boolean,
    onFollow: () -> Unit,
    /** Opens the repeat sheet on these verses. */
    onRepeat: (QuranRepeatRange) -> Unit,
    /** Null while the pack layout is read. */
    media: QuranMedia?,
    /** Downloads what a recitation needs before starting it. */
    starter: QuranRecitationStarter,
    mobileData: ActivityResultLauncher<IntentSenderRequest>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playback by QuranAudioController.state.collectAsStateWithLifecycle()
    val firstVerse = remember(catalog, currentPage) { catalog?.versesOnPage(currentPage)?.firstOrNull { it.ayah > 0 } }
    val reciter = QuranAudioController.reciters.firstOrNull { it.id == playback.reciterId }
        ?: QuranAudioController.reciters.first()
    val currentSurah = catalog?.surahs?.firstOrNull { it.number == playback.surah }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // A recitation waiting for its chapters to download, and how that download is going.
    val pending = starter.pendingPacks
    val download = media?.takeIf { pending.isNotEmpty() }?.summary(pending)
    val downloadProgress = media?.takeIf { pending.isNotEmpty() }?.progress(pending)
    val downloading = download?.active == true && download.status != QuranPackStatus.WaitingForWifi &&
        download.status != QuranPackStatus.NeedsConfirmation

    fun playPause() {
        val surah = playback.surah
        val repeat = playback.repeat
        when {
            playback.playing -> QuranAudioController.pause(context)
            surah != null && playback.error == null -> {
                // A chapter that was not on the device continues once it is downloaded.
                starter.start(playback.reciterId, repeat?.let { it.from.surah..it.to.surah } ?: surah..surah) {
                    QuranAudioController.resume(context)
                }
                onFollow()
            }
            surah != null -> {
                // A repetition goes on from where it stopped, in the same pass.
                starter.start(playback.reciterId, repeat?.let { it.from.surah..it.to.surah } ?: surah..surah) {
                    if (repeat != null) QuranAudioController.resume(context)
                    else QuranAudioController.play(context, playback.reciterId, surah, playback.ayah)
                }
                onFollow()
            }
            firstVerse != null -> {
                starter.start(playback.reciterId, firstVerse.surah..firstVerse.surah) {
                    QuranAudioController.play(context, playback.reciterId, firstVerse.surah, firstVerse.ayah.takeIf { it > 1 })
                }
                onFollow()
            }
        }
    }

    Surface(modifier.fillMaxWidth().testTag("quran_audio_bar"), color = AdhkarSoftGreen) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            QuranAudioPlayButton(
                playback = playback,
                enabled = playback.surah != null || firstVerse != null,
                onClick = ::playPause,
                downloading = downloading,
                modifier = Modifier.testTag("quran_audio_play_pause"),
            )
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = catalog != null, onClickLabel = "خيارات التلاوة") { expanded = true }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    when {
                        download?.status == QuranPackStatus.Failed -> quranDownloadProblemText(download.problem)
                        download?.status == QuranPackStatus.WaitingForWifi -> "التلاوة بانتظار شبكة Wi-Fi"
                        download?.status == QuranPackStatus.NeedsConfirmation -> "يحتاج تنزيل التلاوة إلى تأكيد"
                        download != null -> "جارٍ تنزيل التلاوة… ${quranPercent(downloadProgress ?: 0f)}"
                        playback.loading -> "جارٍ تحميل التلاوة…"
                        playback.error != null -> "تعذّر تشغيل التلاوة"
                        playback.notDownloaded && currentSurah != null -> "سورة ${currentSurah.name} غير محمّلة · اضغط للتنزيل"
                        currentSurah != null -> "${currentSurah.name} · ${audioVerseLabel(playback.ayah)}"
                        catalog == null -> "جارٍ تجهيز التلاوة…"
                        firstVerse == null -> "خيارات التلاوة"
                        else -> "استمع من هذه الصفحة"
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark,
                )
                Text(
                    playback.repeat?.let { quranRepeatStatus(it, playback.repeatRound) } ?: reciter.name,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = TextMuted,
                )
            }
            if (download?.status == QuranPackStatus.WaitingForWifi || download?.status == QuranPackStatus.NeedsConfirmation) {
                TextButton(
                    onClick = { media?.source?.confirmMobileData(pending, mobileData) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.testTag("quran_audio_bar_mobile_data"),
                ) { Text(if (download.status == QuranPackStatus.WaitingForWifi) "بيانات الجوال" else "متابعة", fontSize = 11.sp) }
            } else if (playback.surah != null && !following) {
                TextButton(onClick = onFollow, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("متابعة", fontSize = 11.sp) }
            }
            IconButton(onClick = { expanded = true }, enabled = catalog != null, modifier = Modifier.testTag("quran_audio_options")) {
                Icon(
                    painterResource(R.drawable.ic_settings), "خيارات التلاوة",
                    tint = if (catalog != null) GreenPrimary else TextMuted, modifier = Modifier.size(21.dp),
                )
            }
        }
    }
    if (expanded && catalog != null) {
        QuranAudioSheet(
            catalog = catalog,
            playback = playback,
            firstVerse = firstVerse,
            following = following,
            media = media,
            starter = starter,
            mobileData = mobileData,
            downloading = downloading,
            onFollow = onFollow,
            onPlayPause = ::playPause,
            onRepeat = {
                expanded = false
                // The current repetition, else the verse being recited, else the page's first verse.
                val verse = playback.surah?.let { QuranVerseReference(it, playback.ayah ?: 1) } ?: firstVerse?.reference
                (playback.repeat ?: verse?.let { QuranRepeatRange(it, it) })?.let(onRepeat)
            },
            onDismiss = { expanded = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuranAudioSheet(
    catalog: QuranCatalog,
    playback: QuranPlaybackState,
    firstVerse: QuranVerse?,
    following: Boolean,
    media: QuranMedia?,
    starter: QuranRecitationStarter,
    mobileData: ActivityResultLauncher<IntentSenderRequest>,
    downloading: Boolean,
    onFollow: () -> Unit,
    onPlayPause: () -> Unit,
    onRepeat: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var reciterMenu by remember { mutableStateOf(false) }
    var chapterMenu by remember { mutableStateOf(false) }
    var chosenSurah by rememberSaveable { mutableIntStateOf(playback.surah ?: firstVerse?.surah ?: 1) }
    var chosenVerse by rememberSaveable {
        mutableStateOf((if (playback.surah != null) playback.ayah ?: 1 else firstVerse?.ayah ?: 1).toString())
    }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(playback.surah, playback.reciterId) { scrubFraction = null }
    val reciter = QuranAudioController.reciters.firstOrNull { it.id == playback.reciterId }
        ?: QuranAudioController.reciters.first()
    val activeSurah = catalog.surahs.firstOrNull { it.number == playback.surah }
    val activeVerse = playback.surah?.let { surah -> playback.ayah?.let { catalog.verse(surah, it) } }
    val selectedSurah = catalog.surahs.first { it.number == chosenSurah }
    val verseCount = selectedSurah.verseCount
        ?: remember(catalog, chosenSurah) { catalog.verses.filter { it.surah == chosenSurah }.maxOfOrNull { it.ayah } ?: 1 }
    val verseNumber = chosenVerse.trim().map { it.digitToIntOrNull()?.digitToChar() ?: it }.joinToString("").toIntOrNull()
    val validVerse = verseNumber != null && verseNumber in 1..verseCount
    // While a range is repeated, the slider covers only its part of the chapter.
    val sliderStart = playback.repeatWindow?.startMs ?: 0L
    val duration = ((playback.repeatWindow?.endMs ?: playback.durationMs) - sliderStart).coerceAtLeast(0L)
    val position = (playback.positionMs - sliderStart).coerceIn(0L, duration)
    val fraction = scrubFraction ?: if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val displayedPosition = scrubFraction?.let { (it * duration).toLong() } ?: position

    fun startSelection() {
        if (!validVerse) return
        val surah = chosenSurah
        val verse = verseNumber!!.takeIf { it > 1 }
        starter.start(playback.reciterId, surah..surah) { QuranAudioController.play(context, playback.reciterId, surah, verse) }
        onFollow()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BgCream,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding().padding(horizontal = 16.dp).testTag("quran_audio_sheet")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("الاستماع للقرآن", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_adhkar_close), "إغلاق") }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { reciterMenu = true },
                        modifier = Modifier.fillMaxWidth().testTag("quran_audio_reciter"),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(reciter.name, color = GreenPrimaryDark, fontWeight = FontWeight.SemiBold)
                            Text("رواية ${reciter.riwaya}", fontSize = 12.sp, color = TextMuted)
                        }
                    }
                    DropdownMenu(expanded = reciterMenu, onDismissRequest = { reciterMenu = false }) {
                        QuranAudioController.reciters.forEach { item ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(item.name, fontWeight = if (item.id == playback.reciterId) FontWeight.SemiBold else FontWeight.Normal)
                                        Text(item.riwaya, fontSize = 12.sp, color = TextMuted)
                                    }
                                },
                                onClick = { reciterMenu = false; QuranAudioController.selectReciter(context, item.id) },
                            )
                        }
                    }
                }
                if (media != null) {
                    QuranRecitationDownloadsSection(
                        media = media,
                        reciterId = playback.reciterId,
                        // Wi-Fi first: over mobile data the listener is asked before the whole recitation is fetched.
                        onDownloadAll = { media.fetch(media.layout?.audioPacks(playback.reciterId).orEmpty(), allowMetered = false) },
                        onUseMobileData = { packs -> media.source?.confirmMobileData(packs, mobileData) },
                        onDelete = { packs ->
                            QuranAudioController.stop(context)
                            starter.cancel()
                            media.source?.remove(packs)
                        },
                    )
                }
                if (activeSurah != null) {
                    Text("سورة ${activeSurah.name} · ${audioVerseLabel(playback.ayah)}", color = GreenPrimaryDark, fontWeight = FontWeight.SemiBold)
                }
                val repeat = playback.repeat
                if (repeat != null) {
                    Surface(color = AdhkarSoftGreen, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("quran_audio_repeat_card")) {
                        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
                            Text(quranRepeatStatus(repeat, playback.repeatRound), fontSize = 12.sp, color = GreenPrimary)
                            Text(quranRepeatRangeLabel(catalog, repeat), color = GreenPrimaryDark, fontWeight = FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = onRepeat, modifier = Modifier.testTag("quran_audio_repeat_edit")) { Text("تعديل") }
                                TextButton(
                                    onClick = { QuranAudioController.cancelRepeat(context) },
                                    modifier = Modifier.testTag("quran_audio_repeat_cancel"),
                                ) { Text("إلغاء التكرار") }
                            }
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onRepeat,
                        enabled = playback.surah != null || firstVerse != null,
                        modifier = Modifier.fillMaxWidth().testTag("quran_audio_repeat"),
                    ) {
                        Icon(painterResource(R.drawable.ic_quran_repeat), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("تكرار آية أو مقطع")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { QuranAudioController.previousVerse(context); onFollow() },
                        enabled = playback.surah != null && !playback.loading,
                        modifier = Modifier.testTag("quran_audio_previous_verse"),
                    ) { Icon(painterResource(R.drawable.ic_adhkar_back), "الآية السابقة") }
                    QuranAudioPlayButton(
                        playback = playback,
                        enabled = playback.surah != null || firstVerse != null,
                        onClick = onPlayPause,
                        downloading = downloading,
                        modifier = Modifier.size(56.dp).background(AdhkarSoftGreen, RoundedCornerShape(28.dp)),
                    )
                    IconButton(
                        onClick = { QuranAudioController.nextVerse(context); onFollow() },
                        enabled = playback.surah != null && !playback.loading,
                        modifier = Modifier.testTag("quran_audio_next_verse"),
                    ) { Icon(painterResource(R.drawable.ic_adhkar_next), "الآية التالية") }
                    IconButton(
                        onClick = { QuranAudioController.stop(context) },
                        enabled = playback.surah != null || playback.loading,
                        modifier = Modifier.testTag("quran_audio_stop"),
                    ) { Icon(painterResource(R.drawable.ic_stop), "إيقاف التلاوة") }
                }
                if (playback.surah != null) {
                    Column {
                        Slider(
                            value = fraction,
                            onValueChange = { scrubFraction = it },
                            onValueChangeFinished = {
                                scrubFraction?.let { QuranAudioController.seekTo(context, sliderStart + (it * duration).toLong()); onFollow() }
                                scrubFraction = null
                            },
                            enabled = duration > 0L && !playback.loading,
                            modifier = Modifier.fillMaxWidth().testTag("quran_audio_seek")
                                .semantics { contentDescription = "موضع التلاوة" },
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("الموضع ${audioTime(displayedPosition)}", fontSize = 12.sp, color = TextMuted)
                            Text("المدة ${audioTime(duration)}", fontSize = 12.sp, color = TextMuted)
                        }
                    }
                    OutlinedButton(
                        onClick = { onFollow(); onDismiss() },
                        modifier = Modifier.fillMaxWidth().testTag("quran_audio_follow"),
                    ) { Text(if (following) "العودة إلى صفحة التلاوة" else "متابعة التلاوة على الصفحات") }
                }
                playback.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                if (activeVerse != null) {
                    Surface(color = AdhkarSoftGreen, shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("الآية الحالية", fontSize = 12.sp, color = GreenPrimary)
                            Text(
                                activeVerse.text, fontFamily = AdhkarReadingFont, fontSize = 22.sp, lineHeight = 36.sp,
                                color = GreenPrimaryDark, maxLines = 5, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                HorizontalDivider()
                Text("ابدأ من سورة وآية", color = GreenPrimaryDark, fontWeight = FontWeight.SemiBold)
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { chapterMenu = true },
                        modifier = Modifier.fillMaxWidth().testTag("quran_audio_choose_surah"),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("${selectedSurah.number}. سورة ${selectedSurah.name}") }
                    DropdownMenu(
                        expanded = chapterMenu,
                        onDismissRequest = { chapterMenu = false },
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        catalog.surahs.forEach { surah ->
                            DropdownMenuItem(
                                text = { Text("${surah.number}. ${surah.name}") },
                                onClick = { chosenSurah = surah.number; chosenVerse = "1"; chapterMenu = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = chosenVerse,
                    onValueChange = { chosenVerse = it.take(3) },
                    modifier = Modifier.fillMaxWidth().testTag("quran_audio_choose_verse"),
                    label = { Text("رقم الآية") },
                    supportingText = { Text("من 1 إلى $verseCount") },
                    singleLine = true,
                    isError = chosenVerse.isNotBlank() && !validVerse,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { startSelection() }),
                    shape = RoundedCornerShape(12.dp),
                )
                Button(
                    onClick = ::startSelection,
                    enabled = validVerse,
                    modifier = Modifier.fillMaxWidth().testTag("quran_audio_start_selection"),
                ) { Text("استمع من هذه الآية") }
            }
        }
    }
}

@Composable
private fun QuranAudioPlayButton(
    playback: QuranPlaybackState,
    enabled: Boolean,
    onClick: () -> Unit,
    /** The chapters to recite are being downloaded first. */
    downloading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val busy = playback.loading || downloading
    IconButton(onClick = onClick, enabled = enabled && !busy, modifier = modifier) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), color = GreenPrimary, strokeWidth = 2.dp)
        } else {
            Icon(
                painterResource(if (playback.playing) R.drawable.ic_quran_pause else R.drawable.ic_play_arrow),
                contentDescription = if (playback.playing) "إيقاف مؤقت للتلاوة" else "تشغيل التلاوة",
                tint = if (enabled) GreenPrimary else TextMuted,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private fun audioVerseLabel(ayah: Int?): String = ayah?.takeIf { it > 0 }?.let { "الآية $it" } ?: "التلاوة"

private fun audioTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    return if (seconds >= 3600L) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600L, seconds / 60L % 60L, seconds % 60L)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L)
}
