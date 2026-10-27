package com.tunisianprayertimes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.quran.hifz.HifzReadProblem
import com.tunisianprayertimes.quran.hifz.HifzRepository
import com.tunisianprayertimes.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** An unreadable notebook stays recoverable and never masquerades as a new, empty notebook. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HifzStorageNotice(problem: HifzReadProblem) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember(problem) { mutableStateOf<String?>(null) }
    var hidden by rememberSaveable(problem.recoveryFile?.path, problem.preventsSaving) { mutableStateOf(false) }
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val file = exportPath?.let(::File)
        exportPath = null
        if (uri != null && file != null) scope.launch {
            busy = true
            status = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                        ?: throw IOException("Cannot open export destination")
                }
                "حُفظت نسخة من ملف المراجعة. بقي الأصل محفوظًا في التطبيق."
            } catch (_: IOException) {
                "تعذّر حفظ النسخة. بقي الأصل محفوظًا؛ يمكنك المحاولة مجددًا."
            } catch (_: SecurityException) {
                "لم يُسمح بحفظ النسخة هنا. اختر مكانًا آخر."
            }
            busy = false
        }
    }
    if (hidden && !problem.preventsSaving) return
    Surface(color = BgCream, border = BorderStroke(1.dp, CardBorder), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp).testTag("hifz_storage_notice"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (problem.preventsSaving) "تعذّر فتح دفتر المراجعة" else "توجد نسخة محفوظة للاسترداد", fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            Text(
                if (problem.preventsSaving) "احتفظنا بملفك، ولم نستبدله بدفتر فارغ. يمكنك حفظ نسخة منه أو إعادة محاولة فتحه. تتوقف التغييرات إلى أن يُفتح الدفتر."
                else "دفترك الحالي متاح. احتفظنا أيضًا بالنسخة السابقة التي تعذّر فتحها؛ يمكنك حفظها خارج التطبيق.",
                fontSize = 13.sp, color = TextMuted,
            )
            status?.let { Text(it, fontSize = 13.sp, color = GreenPrimaryDark) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (problem.preventsSaving) TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        val recovered = withContext(Dispatchers.IO) { HifzRepository.retryLoad(context) }
                        status = if (recovered) "فُتح دفتر المراجعة." else "ما زال الملف غير قابل للفتح. النسخة محفوظة ويمكنك تصديرها."
                        busy = false
                    }
                }, modifier = Modifier.testTag("hifz_storage_retry")) { Text("إعادة المحاولة") }
                if (problem.recoveryFile != null) TextButton(enabled = !busy, onClick = {
                    exportPath = problem.recoveryFile.path
                    export.launch("hifz-recovery.json")
                }, modifier = Modifier.testTag("hifz_storage_export")) { Text("حفظ نسخة من الملف") }
                if (!problem.preventsSaving) TextButton(onClick = { hidden = true }) { Text("إخفاء") }
            }
        }
    }
}

/** The failed write is retried before later writes; cancellation returns to the saved notebook. */
@Composable
internal fun HifzSaveFailureDialog(onRetry: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("لم يُحفظ التغيير") },
        text = {
            Text("تعذّر حفظ التغيير في دفتر المراجعة. بقيت البيانات المحفوظة كما هي. أعد المحاولة، أو ألغِ التغيير للعودة إلى الدفتر.", Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = { TextButton(onClick = onRetry, modifier = Modifier.testTag("hifz_save_retry")) { Text("إعادة المحاولة") } },
        dismissButton = { TextButton(onClick = onDiscard, modifier = Modifier.testTag("hifz_save_discard")) { Text("إلغاء التغيير") } },
    )
}
