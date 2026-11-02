package com.tunisianprayertimes.wake

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted

/** The selected id survives recreation and challenge progress; a ringing alarm never rerolls its text. */
@Composable
internal fun rememberWakeMotivation(
    payload: WakeTriggerPayload?,
    resolve: suspend (Context, WakeTriggerPayload) -> WakeMotivation = { context, alarm ->
        resolveWakeMotivation(context, alarm)
    },
): WakeMotivation? {
    val context = LocalContext.current.applicationContext
    val occurrenceKey = payload?.let { "${it.eventId}|${it.triggerAtMillis}|${it.occurrenceAtMillis}" }
    // rememberSaveable's inputs are not validated against the inputs of a restored instance.
    // Save the occurrence alongside the id so queue promotion while the window is gone cannot
    // restore a night/Fajr message into a different alarm.
    var savedOccurrenceKey by rememberSaveable { mutableStateOf<String?>(null) }
    var messageId by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    LaunchedEffect(occurrenceKey) {
        if (payload != null && (savedOccurrenceKey != occurrenceKey || WakeMotivations.all.none { it.reviewId == messageId })) {
            messageId = resolve(context, payload).reviewId
            savedOccurrenceKey = occurrenceKey
        }
    }
    return WakeMotivations.all.firstOrNull { it.reviewId == messageId }
        ?.takeIf { occurrenceKey != null && savedOccurrenceKey == occurrenceKey }
}

@Composable
internal fun WakeMotivationCard(
    message: WakeMotivation,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Surface(
        modifier = modifier.testTag("wake_motivation"),
        shape = RoundedCornerShape(24.dp),
        color = BgCream,
        contentColor = TextDark,
        border = BorderStroke(1.dp, GoldLight),
    ) {
        // Keep the approved wording and its source complete, including at large accessibility fonts.
        // A bounded card can scroll; it never ellipsizes a verse or drops an attribution.
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(if (compact) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
        ) {
            Text(
                text = message.text,
                modifier = Modifier.testTag("wake_motivation_text"),
                style = (if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleLarge)
                    .copy(textDirection = TextDirection.ContentOrRtl),
                textAlign = TextAlign.Center,
            )
            message.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = message.source,
                modifier = Modifier.testTag("wake_motivation_source"),
                style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.ContentOrRtl),
                color = GreenPrimaryDark,
                textAlign = TextAlign.Center,
            )
        }
    }
}
