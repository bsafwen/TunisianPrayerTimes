package com.tunisianprayertimes.ui

import android.content.Context
import android.net.ConnectivityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tunisianprayertimes.quran.assets.QuranAssets
import com.tunisianprayertimes.quran.audio.QuranAudioController
import com.tunisianprayertimes.quran.assets.QuranDownloadProblem
import com.tunisianprayertimes.quran.assets.QuranPack
import com.tunisianprayertimes.quran.assets.QuranPackLayout
import com.tunisianprayertimes.quran.assets.QuranPackSource
import com.tunisianprayertimes.quran.assets.QuranPackState
import com.tunisianprayertimes.quran.assets.QuranPackStatus
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.CardBorder
import com.tunisianprayertimes.ui.theme.TextMuted
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Quran's downloadable packs as the reader sees them. */
@Stable
internal class QuranMedia(
    /** Null when this build bundles every Quran file: nothing is ever downloaded. */
    val layout: QuranPackLayout?,
    val source: QuranPackSource?,
    private val bundled: Set<String>,
    private val metered: () -> Boolean = { false },
) {
    var states by mutableStateOf<Map<String, QuranPackState>>(emptyMap())

    fun state(pack: QuranPack): QuranPackState =
        if (pack.name in bundled) QuranPackState.Available else states[pack.name] ?: QuranPackState.Missing

    fun missing(packs: List<QuranPack>): List<QuranPack> = packs.filterNot { state(it).available }

    /** Downloaded to this device, so it can be deleted again; bundled packs cannot. */
    fun downloaded(pack: QuranPack): Boolean = source != null && pack.name !in bundled && states[pack.name]?.available == true

    val pages: QuranPack? get() = layout?.pages

    val pagesReady: Boolean get() = pages?.let { state(it).available } ?: true

    fun recitationPacks(reciterId: String, surahs: IntRange): List<QuranPack> = layout?.audioPacks(reciterId, surahs).orEmpty()

    fun isMetered(): Boolean = metered()

    /** Downloads [packs]; over mobile data only when [allowMetered]. */
    fun fetch(packs: List<QuranPack>, allowMetered: Boolean) {
        val missing = missing(packs).filterNot { state(it).active && !allowMetered }
        if (missing.isNotEmpty()) source?.fetch(missing, allowMetered)
    }

    companion object {
        /** Every file bundled: what a build without packs.json behaves like. */
        val Bundled = QuranMedia(null, null, emptySet())
    }
}

/** Null while the pack layout is read, the first time. */
@Composable
internal fun rememberQuranMedia(): QuranMedia? {
    val context = LocalContext.current.applicationContext
    var media by remember { mutableStateOf<QuranMedia?>(null) }
    LaunchedEffect(context) {
        media = withContext(Dispatchers.IO) {
            runCatching {
                val layout = QuranAssets.layout(context)
                if (layout == null) QuranMedia.Bundled
                else QuranMedia(layout, QuranAssets.source(context), QuranAssets.bundledPacks(context), { isMeteredNetwork(context) })
            }.getOrElse { QuranMedia.Bundled }
        }
    }
    val current = media
    if (current?.source != null) {
        LaunchedEffect(current) { current.source.states.collect { current.states = it } }
        // Packs may have been delivered, removed or invalidated while the reader was away.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { current.source.refresh() }
    }
    return current
}

private fun isMeteredNetwork(context: Context): Boolean =
    context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

/**
 * Starts recitations once the chapters they need are on the device, downloading them first.
 * A start waiting for its download is forgotten when the reader leaves the screen, so a
 * recitation never begins unexpectedly later.
 */
@Stable
internal class QuranRecitationStarter(private val media: () -> QuranMedia?) {
    private class Waiting(val packs: List<QuranPack>, val start: () -> Unit)

    private var waiting by mutableStateOf<Waiting?>(null)

    /** The packs a start waits for, or empty. */
    val pendingPacks: List<QuranPack> get() = waiting?.packs.orEmpty()

    fun start(reciterId: String, surahs: IntRange, start: () -> Unit) {
        val current = media()
        val needed = current?.recitationPacks(reciterId, surahs).orEmpty()
        val missing = current?.missing(needed).orEmpty()
        if (current == null || missing.isEmpty()) {
            waiting = null
            start()
            current?.let { prefetchAfter(it, reciterId, surahs.last) }
            return
        }
        // Asked for by the listener: one recitation is worth mobile data, Play asks above 200 MB.
        current.fetch(missing, allowMetered = true)
        waiting = Waiting(missing, start)
    }

    /** Called whenever pack states change: starts the waiting recitation once it can play. */
    fun onStatesChanged() {
        val pending = waiting ?: return
        val current = media() ?: return
        if (current.missing(pending.packs).isEmpty()) {
            waiting = null
            pending.start()
        }
    }

    fun cancel() {
        waiting = null
    }

    private fun prefetchAfter(media: QuranMedia, reciterId: String, surah: Int) {
        // Continuous recitation moves on to the next chapter; fetch it ahead, but never over mobile data.
        if (surah >= 114 || media.isMetered()) return
        media.fetch(media.recitationPacks(reciterId, surah + 1..surah + 1), allowMetered = false)
    }
}

@Composable
internal fun rememberQuranRecitationStarter(media: QuranMedia?): QuranRecitationStarter {
    val currentMedia by rememberUpdatedState(media)
    val starter = remember { QuranRecitationStarter { currentMedia } }
    LaunchedEffect(media?.states) { starter.onStatesChanged() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { starter.cancel() }
    LaunchedEffect(starter) {
        // Stopping the recitation, e.g. from its notification, also drops a start waiting for a download.
        var previous = QuranAudioController.state.value.surah
        QuranAudioController.state.collect { state ->
            if (previous != null && state.surah == null) starter.cancel()
            previous = state.surah
        }
    }
    return starter
}

/** Play shows its own confirmation before downloading over mobile data; the result needs no handling. */
@Composable
internal fun rememberMobileDataLauncher(): ActivityResultLauncher<IntentSenderRequest> =
    rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}

/** Downloaded and total bytes over [packs], for one progress figure. */
internal fun QuranMedia.progress(packs: List<QuranPack>): Float? {
    var total = 0L
    var done = 0L
    packs.forEach { pack ->
        val state = state(pack)
        val size = state.totalBytes.takeIf { it > 0L } ?: pack.archive.bytes
        total += size
        // A pack being unpacked is fully downloaded.
        done += if (state.available || state.status == QuranPackStatus.Transferring) size else state.downloadedBytes.coerceAtMost(size)
    }
    return if (total > 0L) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

/** The most pressing state among [packs]: a failure, then a wait for the user, then progress. */
internal fun QuranMedia.summary(packs: List<QuranPack>): QuranPackState? {
    val states = packs.map(::state).filterNot { it.available }
    return states.firstOrNull { it.status == QuranPackStatus.Failed }
        ?: states.firstOrNull { it.status == QuranPackStatus.NeedsConfirmation }
        ?: states.firstOrNull { it.status == QuranPackStatus.WaitingForWifi }
        ?: states.firstOrNull { it.active }
        ?: states.firstOrNull()
}

internal fun quranDownloadProblemText(problem: QuranDownloadProblem?): String = when (problem) {
    QuranDownloadProblem.Network -> "تعذّر التنزيل: تحقّق من الاتصال بالإنترنت"
    QuranDownloadProblem.Storage -> "لا توجد مساحة كافية على الجهاز"
    QuranDownloadProblem.Store -> "تعذّر التنزيل من متجر Google Play. حدّثه ثم أعد المحاولة"
    QuranDownloadProblem.Integrity -> "وصل الملف تالفًا. أعد المحاولة"
    QuranDownloadProblem.Other, null -> "تعذّر التنزيل. أعد المحاولة"
}

internal fun quranSize(bytes: Long): String {
    // Digits are always 0-9, never the Arabic-Indic ones.
    val arabic = Locale.ROOT
    return if (bytes >= 1_000_000_000L) String.format(arabic, "%.1f غيغابايت", bytes / 1e9)
    else String.format(arabic, "%d ميغابايت", ((bytes + 999_999L) / 1_000_000L).coerceAtLeast(1L))
}

internal fun quranPercent(fraction: Float): String = String.format(Locale.ROOT, "%d%%", (fraction * 100).toInt())

/**
 * The mushaf's pages, until they are on the device: their size and a download button, the
 * download's progress, or why it stopped. Nothing once they are there.
 */
@Composable
internal fun QuranPagesDownloadBanner(
    media: QuranMedia,
    onDownload: (allowMetered: Boolean) -> Unit,
    onUseMobileData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = media.pages ?: return
    val state = media.state(pages)
    if (state.available) return
    val progress = media.progress(listOf(pages))
    Column(modifier.fillMaxWidth().testTag("quran_pages_download")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (state.status) {
                    QuranPackStatus.Pending -> "جارٍ بدء تنزيل صفحات المصحف…"
                    QuranPackStatus.Downloading -> "جارٍ تنزيل صفحات المصحف · ${quranPercent(progress ?: 0f)} من ${quranSize(pages.archive.bytes)}"
                    QuranPackStatus.Transferring -> "جارٍ تجهيز صفحات المصحف…"
                    QuranPackStatus.WaitingForWifi -> "صفحات المصحف بانتظار شبكة Wi-Fi"
                    QuranPackStatus.NeedsConfirmation -> "يحتاج تنزيل صفحات المصحف إلى تأكيد"
                    QuranPackStatus.Failed -> quranDownloadProblemText(state.problem)
                    else -> "صفحات المصحف غير محمّلة (${quranSize(pages.archive.bytes)})"
                },
                Modifier.weight(1f).padding(vertical = 8.dp), fontSize = 12.sp, color = TextMuted,
            )
            when (state.status) {
                QuranPackStatus.WaitingForWifi -> TextButton(onClick = onUseMobileData, modifier = Modifier.testTag("quran_pages_mobile_data")) {
                    Text("استخدام بيانات الجوال")
                }
                QuranPackStatus.NeedsConfirmation -> TextButton(onClick = onUseMobileData) { Text("متابعة") }
                QuranPackStatus.Failed -> TextButton(onClick = { onDownload(true) }, modifier = Modifier.testTag("quran_pages_retry")) {
                    Text("إعادة المحاولة")
                }
                QuranPackStatus.Missing -> TextButton(onClick = { onDownload(true) }, modifier = Modifier.testTag("quran_pages_download_button")) {
                    Text("تنزيل")
                }
                else -> Unit
            }
        }
        if (state.status == QuranPackStatus.Downloading || state.status == QuranPackStatus.Transferring) {
            LinearProgressIndicator(
                progress = { if (state.status == QuranPackStatus.Transferring) 1f else progress ?: 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp).testTag("quran_pages_progress"),
                color = GreenPrimary, trackColor = CardBorder,
            )
        }
    }
}

/** In the listening sheet: how much of the recitation is on the device, to download all of it or to free the space. */
@Composable
internal fun QuranRecitationDownloadsSection(
    media: QuranMedia,
    reciterId: String,
    onDownloadAll: () -> Unit,
    onUseMobileData: (List<QuranPack>) -> Unit,
    onDelete: (List<QuranPack>) -> Unit,
) {
    val layout = media.layout ?: return
    val packs = layout.audioPacks(reciterId)
    if (packs.isEmpty()) return
    val downloaded = packs.filter { media.state(it).available }
    val chapters = downloaded.sumOf { pack -> pack.surahs?.let { it.last - it.first + 1 } ?: 0 }
    val missing = packs - downloaded.toSet()
    val summary = media.summary(missing)
    // Mobile data is allowed for what waits for it, never for every missing chapter at once.
    val waiting = missing.filter {
        val status = media.state(it).status
        status == QuranPackStatus.WaitingForWifi || status == QuranPackStatus.NeedsConfirmation
    }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("quran_audio_downloads"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "التلاوات المحمّلة: $chapters من 114 سورة",
            fontSize = 13.sp, color = TextMuted,
        )
        when (summary?.status) {
            QuranPackStatus.Failed -> Text(quranDownloadProblemText(summary.problem), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            QuranPackStatus.WaitingForWifi, QuranPackStatus.NeedsConfirmation -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("بانتظار شبكة Wi-Fi", Modifier.weight(1f), fontSize = 12.sp, color = TextMuted)
                TextButton(onClick = { onUseMobileData(waiting) }, modifier = Modifier.testTag("quran_audio_mobile_data")) {
                    Text("استخدام بيانات الجوال (${quranSize(waiting.sumOf { it.archive.bytes })})")
                }
            }
            QuranPackStatus.Pending, QuranPackStatus.Downloading, QuranPackStatus.Transferring -> {
                val progress = media.progress(packs) ?: 0f
                Text("جارٍ تنزيل التلاوات · ${quranPercent(progress)}", fontSize = 12.sp, color = TextMuted)
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), color = GreenPrimary, trackColor = CardBorder)
            }
            else -> Unit
        }
        // Shown exactly when it would fetch something: packs neither queued nor waiting (incl. failed ones).
        val fetchable = missing.filterNot { media.state(it).active }
        if (fetchable.isNotEmpty()) {
            OutlinedButton(onClick = onDownloadAll, modifier = Modifier.fillMaxWidth().testTag("quran_audio_download_all")) {
                Text("تنزيل كل التلاوات (${quranSize(fetchable.sumOf { it.archive.bytes })})")
            }
        }
        val removable = packs.filter(media::downloaded)
        if (removable.isNotEmpty()) {
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("quran_audio_delete_downloads")) {
                Text("حذف التلاوات المحمّلة")
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("حذف التلاوات المحمّلة؟") },
        text = { Text("تُحذف من الجهاز وتتوقف التلاوة. يمكنك تنزيلها مجددًا متى شئت.") },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; onDelete(packs.filter(media::downloaded)) }, modifier = Modifier.testTag("quran_audio_delete_confirm")) {
                Text("حذف")
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } },
    )
}
