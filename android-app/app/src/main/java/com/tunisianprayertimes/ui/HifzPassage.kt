package com.tunisianprayertimes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.ui.theme.*

internal data class HifzPassage(val from: QuranVerseReference, val to: QuranVerseReference) {
    operator fun contains(verse: QuranVerseReference): Boolean = verse >= from && verse <= to
    fun encode(): String = "${from.surah}:${from.ayah}:${to.surah}:${to.ayah}"
    fun title(catalog: QuranCatalog): String = "${hifzVerseName(catalog, from)} — ${hifzVerseName(catalog, to)}"

    companion object {
        fun decode(value: String?): HifzPassage? {
            val parts = value?.split(':')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 } ?: return null
            val from = QuranVerseReference(parts[0], parts[1])
            val to = QuranVerseReference(parts[2], parts[3])
            return HifzPassage(from, to).takeIf { from <= to }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HifzPassageSetup(
    catalog: QuranCatalog,
    page: Int,
    onStart: (HifzPassage) -> Unit,
    onDismiss: () -> Unit,
) {
    val verses = remember(catalog, page) { catalog.versesOnPage(page).filter { it.ayah > 0 } }
    val initial = verses.firstOrNull()?.reference?.let { first -> HifzPassage(first, verses.last().reference) }
    var draft by rememberSaveable(page) { mutableStateOf(initial?.encode()) }
    val passage = HifzPassage.decode(draft)
    var choosing by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = BgCream, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().quranSheetHeight().verticalScroll(rememberScrollState()).padding(16.dp).testTag("hifz_passage_setup"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("اختر مقطع التسميع", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            Text("حدّد البداية والنهاية. سمّع من حفظك، ثم اكشف للتأكد وسجّل ما يحتاج تدريبًا.", color = TextMuted)
            passage?.let {
                OutlinedButton(onClick = { choosing = "from"; error = null }, modifier = Modifier.fillMaxWidth().testTag("hifz_passage_from")) { Text("من: ${hifzVerseName(catalog, it.from)}") }
                OutlinedButton(onClick = { choosing = "to"; error = null }, modifier = Modifier.fillMaxWidth().testTag("hifz_passage_to")) { Text("إلى: ${hifzVerseName(catalog, it.to)}") }
            }
            if (choosing != null) {
                Text(if (choosing == "from") "اختر آية البداية" else "اختر آية النهاية", fontWeight = FontWeight.SemiBold)
                key(choosing) {
                    HifzVerseFinder(catalog, onChosen = { verse ->
                        val current = passage ?: HifzPassage(verse, verse)
                        if (choosing == "to" && verse < current.from) {
                            error = "اختر نهاية تأتي بعد البداية، أو الآية نفسها."
                        } else {
                            draft = (if (choosing == "from") HifzPassage(verse, if (current.to >= verse) current.to else verse) else current.copy(to = verse)).encode()
                            choosing = null
                            error = null
                        }
                    })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { choosing = null; error = null }) { Text("إلغاء الاختيار") }
            } else {
                Button(onClick = { passage?.let(onStart) }, enabled = passage != null, modifier = Modifier.fillMaxWidth().testTag("hifz_passage_start"), colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)) { Text("ابدأ التسميع") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("رجوع") }
            }
        }
    }
}

@Composable
internal fun HifzPassageCard(catalog: QuranCatalog, passage: HifzPassage, onRecord: () -> Unit, onFinish: () -> Unit, onReturn: () -> Unit) {
    QuranFloatingCard(Modifier.fillMaxWidth().testTag("hifz_passage_active")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("تسميع: ${passage.title(catalog)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            Text("اكشف بعد المحاولة للتأكد. اضغط رقم الآية لتسجيل صعوبة.", fontSize = 12.sp, color = TextMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onRecord, modifier = Modifier.weight(1f).testTag("hifz_passage_record")) { Text("سجّل صعوبة") }
                Button(onClick = onFinish, modifier = Modifier.weight(1f).testTag("hifz_passage_finish"), colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)) { Text("أنهيت التسميع") }
            }
            TextButton(onClick = onReturn, contentPadding = PaddingValues(0.dp)) { Text("العودة إلى بداية المقطع", fontSize = 12.sp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HifzPassageRecordSheet(catalog: QuranCatalog, passage: HifzPassage, page: Int, onRecord: (QuranVerseReference) -> Unit, onDismiss: () -> Unit) {
    var search by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = BgCream, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().quranSheetHeight().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("أين توقفت أو أخطأت؟", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            Text(passage.title(catalog), color = TextMuted)
            catalog.versesOnPage(page).filter { it.ayah > 0 && it.reference in passage }.forEach { verse ->
                OutlinedButton(onClick = { onRecord(verse.reference) }, modifier = Modifier.fillMaxWidth()) { Text(hifzVerseName(catalog, verse.reference)) }
            }
            TextButton(onClick = { search = !search }) { Text("اختر آية أخرى من المقطع") }
            if (search) HifzVerseFinder(catalog, onChosen = { verse -> if (verse in passage) onRecord(verse) else error = true })
            if (error) Text("اختر آية بين بداية المقطع ونهايته.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onDismiss) { Text("تابع التسميع") }
        }
    }
}

@Composable
internal fun HifzPassageSummary(catalog: QuranCatalog, passage: HifzPassage, recorded: Int, finished: Boolean, onContinue: () -> Unit, onPractice: (() -> Unit)?, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onContinue,
        title = { Text(if (finished) "انتهى التسميع" else "توقّف التسميع") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(passage.title(catalog))
                Text(if (recorded == 0) "لم تسجّل صعوبات في هذه المحاولة." else "سجّلت ${hifzCountLabel(recorded)} للتدريب.")
                Text("هذا ملخّص محاولتك؛ تحقّق من قراءتك بالمصحف أو مع معلّمك.", fontSize = 13.sp, color = TextMuted)
                if (onPractice != null) OutlinedButton(onClick = onPractice, modifier = Modifier.fillMaxWidth()) { Text("تدرّب على المواضع المسجّلة") }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("العودة إلى المراجعة") } },
        dismissButton = { TextButton(onClick = onContinue) { Text("تابع التسميع") } },
    )
}
