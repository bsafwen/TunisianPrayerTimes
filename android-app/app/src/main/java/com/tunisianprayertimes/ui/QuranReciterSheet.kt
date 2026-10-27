package com.tunisianprayertimes.ui

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.assets.QuranPack
import com.tunisianprayertimes.quran.assets.QuranPackStatus
import com.tunisianprayertimes.quran.audio.QuranAudioController
import com.tunisianprayertimes.ui.theme.*

/** «محمّلة كاملة · 1.1 غيغابايت», «محمّلة جزئيًا: 3 من 9 حزم», «غير محمّلة · 0.9 غيغابايت للكل». */
internal fun QuranMedia.reciterStatus(reciterId: String): String? {
    val packs = layout?.audioPacks(reciterId).orEmpty()
    if (packs.isEmpty()) return null
    val ready = packs.count { state(it).available }
    val total = packs.sumOf { it.archive.bytes }
    val waiting = packs.filterNot { state(it).available }.map { state(it).status }
    val active = packs.filterNot { state(it).available }.any { state(it).active }
    return when {
        ready == packs.size -> "محمّلة بالكامل · ${quranSize(total)}"
        // Asked for but held back: not a download under way.
        waiting.any { it == QuranPackStatus.NeedsConfirmation } -> "بانتظار تأكيد التنزيل · $ready من ${packs.size}${com.tunisianprayertimes.quran.hifz.HifzReminder.NB}حزم"
        waiting.any { it == QuranPackStatus.WaitingForWifi } -> "بانتظار شبكة ${ltr("Wi-Fi")} · $ready من ${packs.size}${com.tunisianprayertimes.quran.hifz.HifzReminder.NB}حزم"
        active -> "جارٍ التنزيل · ${quranPercent(progress(packs) ?: 0f)}"
        ready == 0 -> "غير محمّلة · ${quranSize(total)} للكل"
        else -> "محمّلة جزئيًا: $ready من ${packs.size}${com.tunisianprayertimes.quran.hifz.HifzReminder.NB}حزم"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuranReciterSheet(
    selected: String,
    media: QuranMedia?,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
    /** The surah listened to now, if any: each voice says whether its recording of it is on the device. */
    surah: Int? = null,
    surahName: (Int) -> String = { "" },
    /** The voice chosen while listening, downloading before it replaces the one heard. */
    switchingTo: String? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        // Large text makes it taller than the screen: it scrolls then.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag("quran_reciter_sheet")) {
            QuranPanelHeader("القارئ", onDismiss)
            QuranAudioController.reciters.forEach { reciter ->
                val status = media?.reciterStatus(reciter.id)
                val missing = media?.layout?.audioPacks(reciter.id).orEmpty().filterNot { media?.state(it)?.let { s -> s.available || s.active } ?: true }
                // Chosen now, a voice without the surah downloads it first, the voice heard going on meanwhile.
                val forSurah = surah?.takeIf { reciter.id != selected && media != null }?.let { s ->
                    val packs = media!!.recitationPacks(reciter.id, s..s)
                    val absent = media.missing(packs)
                    if (absent.isEmpty()) "تلاوته لسورة ${surahName(s)} على الجهاز"
                    else if (reciter.id == switchingTo) "تتابع الاستماع بصوته بعد اكتمال تنزيل تلاوته لسورة ${surahName(s)}."
                    else "لم تُنزَّل تلاوته لسورة ${surahName(s)} بعد. عند اختياره، نُنزّلها${absent.firstOrNull()?.let { quranPackCompanions(it, s, surahName) }.orEmpty()} " +
                        "(${quranSize(absent.sumOf { it.archive.bytes })})، ثم تتابع الاستماع بصوته."
                }
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(reciter.id) }.padding(vertical = 10.dp).testTag("quran_reciter_${reciter.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = reciter.id == selected, onClick = { onSelect(reciter.id) }, colors = RadioButtonDefaults.colors(selectedColor = GreenPrimary))
                    // With large text the button goes under the name, which would otherwise be a word wide.
                    BesideOrBelow(Modifier.weight(1f), minLeadingEms = 8f, leading = {
                        Column {
                            Text(reciter.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                            if (forSurah != null) Text(forSurah, fontSize = 12.sp, color = GreenPrimary)
                            if (status != null) Text(status, fontSize = 12.sp, color = TextMuted)
                        }
                    }) {
                        if (missing.isNotEmpty() && media != null) TextButton(onClick = { media.fetch(missing, allowMetered = false) }) {
                            Text("نزّل الكل", fontSize = 13.sp)
                        }
                    }
                }
                HorizontalDivider(color = CardBorder)
            }
            BesideOrBelow(Modifier.fillMaxWidth().padding(top = 8.dp), leading = {
                Text("رواية قالون عن نافع", fontSize = 12.sp, color = TextMuted)
            }) {
                if (media?.layout != null) TextButton(onClick = onManage, modifier = Modifier.testTag("quran_manage_downloads")) { Text("إدارة التنزيلات") }
            }
        }
    }
}

/** Every downloadable part of the Quran, with its size, its state and what can be done with it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuranDownloadsSheet(
    media: QuranMedia,
    mobileData: ActivityResultLauncher<IntentSenderRequest>,
    onDeleted: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = BgCream) {
        Column(Modifier.fillMaxWidth().quranSheetHeight().padding(horizontal = 16.dp).testTag("quran_downloads_sheet")) {
            QuranPanelHeader("التنزيلات", onDismiss)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                media.pages?.let { pages ->
                    DownloadCard("صفحات المصحف") {
                        val state = media.state(pages)
                        Text(
                            if (state.available) "${quranSize(pages.archive.bytes)} · محمّلة"
                            else "${quranSize(pages.archive.bytes)} · ${when {
                                state.status == QuranPackStatus.NeedsConfirmation -> "بانتظار تأكيد التنزيل"
                                state.status == QuranPackStatus.WaitingForWifi -> "بانتظار شبكة Wi-Fi"
                                state.active -> "جارٍ التنزيل…"
                                else -> "غير محمّلة"
                            }}",
                            fontSize = 12.sp, color = TextMuted,
                        )
                        if (!state.available) QuranPagesDownloadBanner(
                            media = media,
                            onDownload = { allowMetered -> media.fetch(listOf(pages), allowMetered) },
                            onUseMobileData = { media.source?.confirmMobileData(listOf(pages), mobileData) },
                        )
                    }
                }
                QuranAudioController.reciters.forEach { reciter ->
                    DownloadCard(reciter.name) {
                        QuranRecitationDownloadsSection(
                            media = media,
                            reciterId = reciter.id,
                            onDownloadAll = { media.fetch(media.layout?.audioPacks(reciter.id).orEmpty(), allowMetered = false) },
                            onUseMobileData = { packs: List<QuranPack> -> media.source?.confirmMobileData(packs, mobileData) },
                            onDelete = { packs ->
                                if (QuranAudioController.state.value.reciterId == reciter.id) QuranAudioController.stop(context)
                                media.source?.remove(packs)
                                onDeleted(reciter.id)
                            },
                        )
                    }
                }
                Text("ننزّل الملفات عبر شبكة Wi-Fi، ونستأذنك قبل استخدام بيانات الهاتف.", fontSize = 12.sp, color = TextMuted)
            }
        }
    }
}

@Composable
private fun DownloadCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, border = BorderStroke(1.dp, CardBorder)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            content()
        }
    }
}

/** The pages are not on the device: their size and one button, in the page's own place. */
@Composable
internal fun QuranPagesEmptyState(media: QuranMedia, onDownload: () -> Unit, onUseMobileData: () -> Unit, modifier: Modifier = Modifier) {
    val pages = media.pages ?: return
    val state = media.state(pages)
    val progress = media.progress(listOf(pages))
    Surface(modifier.widthIn(max = 320.dp).padding(24.dp).testTag("quran_pages_empty"), shape = RoundedCornerShape(20.dp), color = BgCream, border = BorderStroke(1.dp, CardBorder)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(R.drawable.ic_tab_quran), null, Modifier.size(40.dp), tint = GreenPrimary)
            when (state.status) {
                QuranPackStatus.Pending, QuranPackStatus.Downloading, QuranPackStatus.Transferring -> {
                    Text("جارٍ تنزيل الصفحات · ${quranPercent(progress ?: 0f)}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                    LinearProgressIndicator(progress = { progress ?: 0f }, Modifier.fillMaxWidth(), color = GreenPrimary, trackColor = CardBorder)
                }
                QuranPackStatus.WaitingForWifi, QuranPackStatus.NeedsConfirmation -> {
                    val needsConfirmation = state.status == QuranPackStatus.NeedsConfirmation
                    Text(if (needsConfirmation) "بانتظار تأكيد تنزيل الصفحات" else "الصفحات بانتظار شبكة Wi-Fi",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                    Button(onClick = onUseMobileData, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)) {
                        Text(if (needsConfirmation) "تأكيد التنزيل" else "نزّل ${quranSize(pages.archive.bytes)} عبر بيانات الهاتف")
                    }
                }
                QuranPackStatus.Failed -> {
                    Text(quranDownloadProblemText(state.problem), fontSize = 14.sp, color = GreenPrimaryDark)
                    Button(onClick = onDownload, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)) { Text("أعد المحاولة") }
                }
                else -> {
                    Text("صفحات المصحف غير محمّلة", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                    Text("${quranSize(pages.archive.bytes)} · تنزيل لمرة واحدة", fontSize = 13.sp, color = TextMuted)
                    Button(onClick = onDownload, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.testTag("quran_pages_download_button")) {
                        Text("نزّل الصفحات")
                    }
                }
            }
        }
    }
}
