package com.tunisianprayertimes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.DhikrEntry

/** Editor for a personal dhikr; entries are stored with the reading state in DhikrRepository. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DhikrCustomEditor(
    initial: DhikrEntry?,
    onDismiss: () -> Unit,
    onSave: (DhikrEntry, (String) -> Unit) -> Unit,
    onDelete: (DhikrEntry) -> Unit,
) {
    val p = LocalAdhkarPalette.current
    val source = initial ?: DhikrEntry(id = "", title = "", text = "", reference = "", defaultCount = 1,
        categories = emptySet(), custom = true)
    var title by rememberSaveable(source.id) { mutableStateOf(source.title) }
    var text by rememberSaveable(source.id) { mutableStateOf(source.text) }
    var reference by rememberSaveable(source.id) { mutableStateOf(source.reference) }
    var countText by rememberSaveable(source.id) { mutableStateOf(source.defaultCount.toString()) }
    var showErrors by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    fun integer(value: String): Int? = value.trim().map { if (it.isDigit()) Character.digit(it, 10).digitToChar() else it }.joinToString("").toIntOrNull()
    val count = integer(countText) ?: 0
    val problem = when {
        title.isBlank() -> "أدخل عنوان الذكر."
        text.isBlank() -> "أدخل نص الذكر."
        count !in 1..100_000 -> "أدخل عددًا صحيحًا من ١ إلى ١٠٠٬٠٠٠."
        else -> null
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = AdhkarSurface, unfocusedContainerColor = AdhkarSurface,
        focusedBorderColor = p.primary.copy(alpha = .6f), unfocusedBorderColor = AdhkarBorder)
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.background, modifier = Modifier.testTag("adhkar_custom_editor")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (initial == null) "إضافة ذكر" else "تعديل الذكر", Modifier.weight(1f), textAlign = TextAlign.Center,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AdhkarHeading)
                IconButton(onClick = { if (!saving) onDismiss() }) { DhikrIcon(R.drawable.ic_adhkar_back, "رجوع") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("اكتب الذكر بالطريقة التي تناسبك. سيظهر في «أذكاري»، ويمكنك قراءته وعدّ مرات تكراره وإنشاء تذكير له.",
                    color = p.muted, fontSize = 13.sp, lineHeight = 24.sp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("عنوان الذكر", color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    OutlinedTextField(title, { if (it.length <= 120) title = it }, singleLine = true,
                        placeholder = { Text("مثال: وردي اليومي", fontSize = 14.sp, color = p.muted) },
                        shape = RoundedCornerShape(14.dp), colors = fieldColors,
                        modifier = Modifier.fillMaxWidth().testTag("adhkar_custom_title"))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("نص الذكر", color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    OutlinedTextField(text, { text = it }, minLines = 4, maxLines = 8,
                        placeholder = { Text("اكتب نص الذكر...", fontSize = 14.sp, color = p.muted) },
                        textStyle = LocalTextStyle.current.copy(lineHeight = 30.sp),
                        shape = RoundedCornerShape(14.dp), colors = fieldColors,
                        modifier = Modifier.fillMaxWidth().testTag("adhkar_custom_text"))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("المصدر أو ملاحظة (اختياري)", color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    OutlinedTextField(reference, { if (it.length <= 200) reference = it }, singleLine = true,
                        placeholder = { Text("مثال: من أذكار الصباح", fontSize = 14.sp, color = p.muted) },
                        shape = RoundedCornerShape(14.dp), colors = fieldColors,
                        modifier = Modifier.fillMaxWidth().testTag("adhkar_custom_reference"))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("العدد الافتراضي عند القراءة", color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("يمكنك تغيير العدد لاحقًا عند إعداد تذكير لهذا الذكر.", color = p.muted, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(onClick = { countText = ((integer(countText) ?: 0) + 1).coerceAtMost(100000).toString() },
                        shape = CircleShape, color = AdhkarSoftGreen, modifier = Modifier.size(48.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            DhikrIcon(R.drawable.ic_add, "زيادة العدد", tint = p.primary, modifier = Modifier.size(22.dp))
                        }
                    }
                    OutlinedTextField(
                        value = countText, onValueChange = { if (it.length <= 7) countText = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AdhkarHeading),
                        shape = RoundedCornerShape(14.dp), colors = fieldColors,
                        modifier = Modifier.weight(1f).testTag("adhkar_custom_count"),
                    )
                    Surface(onClick = { countText = ((integer(countText) ?: 1) - 1).coerceAtLeast(1).toString() },
                        shape = CircleShape, color = AdhkarSoftGreen, modifier = Modifier.size(48.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            DhikrIcon(R.drawable.ic_remove, "تقليل العدد", tint = p.primary, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                if (text.isNotBlank()) AdhkarCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title.ifBlank { "ذكر جديد" }, color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(text, fontFamily = AdhkarReadingFont, fontSize = 24.sp, lineHeight = 44.sp,
                            color = p.forest, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        if (reference.isNotBlank()) Text(reference, color = p.muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            HorizontalDivider(color = AdhkarBorder)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 16.dp)) {
                (saveError ?: problem?.takeIf { showErrors })?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp).testTag("adhkar_custom_error"))
                }
                Button(onClick = {
                    if (problem != null) showErrors = true else {
                        saving = true
                        onSave(source.copy(title = title.trim(), text = text.trim(), reference = reference.trim(),
                            defaultCount = count)) { error -> saving = false; saveError = error }
                    }
                }, enabled = !saving, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("adhkar_custom_save")) {
                    DhikrIcon(R.drawable.ic_adhkar_check, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (saving) "جارٍ الحفظ…" else "حفظ الذكر", fontWeight = FontWeight.Bold)
                }
                if (initial != null) OutlinedButton(onClick = { onDelete(initial) }, enabled = !saving,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("adhkar_custom_delete")) {
                    DhikrIcon(R.drawable.ic_delete, "حذف الذكر",
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("حذف الذكر", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
