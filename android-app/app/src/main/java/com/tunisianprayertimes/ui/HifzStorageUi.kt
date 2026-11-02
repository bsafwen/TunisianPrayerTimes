package com.tunisianprayertimes.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

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
