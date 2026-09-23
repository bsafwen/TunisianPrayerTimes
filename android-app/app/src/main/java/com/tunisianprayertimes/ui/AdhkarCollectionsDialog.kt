package com.tunisianprayertimes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.adhkar.DhikrEntry

/** Lets the user choose which collections (morning, evening, ...) show a dhikr. */
@Composable
internal fun DhikrCollectionsDialog(
    entry: DhikrEntry,
    isMember: (DhikrCategory) -> Boolean,
    onToggle: (DhikrCategory, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalAdhkarPalette.current
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("مجموعات الذكر", color = AdhkarHeading, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.title, color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("اختر المجموعات التي يظهر فيها الذكر. ينطبق ذلك على قراءة المجموعة وتذكيراتها.",
                    color = p.muted, fontSize = 12.sp, lineHeight = 20.sp)
                adhkarCategoryOrder.forEach { category ->
                    val checked = isMember(category)
                    Row(Modifier.fillMaxWidth().clickable { onToggle(category, !checked) }.padding(vertical = 4.dp)
                        .testTag("adhkar_collection_" + category.name),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = { onToggle(category, it) })
                        DhikrIcon(categoryIcon(category), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(collectionTitle(category), color = AdhkarHeading, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } })
}
