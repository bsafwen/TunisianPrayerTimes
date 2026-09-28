package com.tunisianprayertimes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.adhkar.DhikrEntry
import com.tunisianprayertimes.adhkar.DhikrState
import com.tunisianprayertimes.adhkar.allEntries
import com.tunisianprayertimes.adhkar.isInCollection
import com.tunisianprayertimes.adhkar.normalizeDhikrSearch

@Composable
internal fun DhikrCollectionAddDialog(
    category: DhikrCategory,
    state: DhikrState,
    onAdd: (DhikrEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalAdhkarPalette.current
    var query by remember { mutableStateOf("") }
    val normalized = remember(query) { normalizeDhikrSearch(query) }
    val available = remember(state.customEntries, state.collectionAdditions, state.collectionRemovals, category, normalized) {
        state.allEntries.filter { entry ->
            !state.isInCollection(entry, category) &&
                (normalized.isEmpty() || normalizeDhikrSearch(entry.title + " " + entry.text).contains(normalized))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة ذكر إلى " + collectionTitle(category), color = AdhkarHeading, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("اختر ذكرًا من المكتبة لإضافته إلى هذه المجموعة. سيظهر أيضًا في القراءة الحالية، وسيبقى في المكتبة.",
                    color = p.muted, fontSize = 13.sp, lineHeight = 21.sp)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("ابحث في الأذكار...", fontSize = 14.sp) },
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().testTag("adhkar_add_to_list_search"),
                )
                if (available.isEmpty()) {
                    Text(
                        if (normalized.isEmpty()) "جميع الأذكار موجودة بالفعل في هذه المجموعة."
                        else "لا توجد نتائج.",
                        color = p.muted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("adhkar_add_to_list_results"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(available, key = { it.id }) { entry ->
                            AdhkarCard(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onAdd(entry) },
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(entry.title, color = AdhkarHeading, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(entry.text, color = p.muted, fontFamily = AdhkarReadingFont,
                                            fontSize = 12.sp, maxLines = 2,
                                            overflow = TextOverflow.Ellipsis)
                                    }
                                    DhikrIcon(R.drawable.ic_adhkar_plus, "إضافة إلى المجموعة", tint = p.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } },
    )
}
