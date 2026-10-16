package com.tunisianprayertimes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.silence.SilenceFit
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * D16's proposal (plan 6.9): the window does not fit every day of the coming year at the place. It says on how many
 * days and the first of them, and offers what fits them all: an end at the window's offset from the adhan, or a later
 * fixed end. Keeping the choice is allowed: on those days the end moves later by at most 30 minutes, or keeps its
 * offset from the adhan when even that comes before the start.
 */
@Composable
internal fun SilenceYearFitDialog(
    fit: SilenceFit,
    anchor: PrayerTimelineAnchor,
    onAdhanRelative: (() -> Unit)?,
    onLaterFixedEnd: (() -> Unit)?,
    onKeep: () -> Unit,
    onDismiss: () -> Unit,
) {
    val days = fit.notFitting.size
    val first = fit.notFitting.firstOrNull()?.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ar-TN-u-nu-latn"))).orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.silence_year_fit_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.silence_year_fit_body, pluralStringResource(R.plurals.silence_year_fit_days, days, days), first),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Text(stringResource(R.string.silence_year_fit_keep_note), color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp)
                fit.adhanRelativeEnd?.let { offset ->
                    onAdhanRelative?.let { apply ->
                        TextButton(onClick = apply) {
                            Text(stringResource(R.string.silence_year_fit_use_adhan_end, "⁦$offset⁩"), color = GreenPrimaryDark, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                fit.laterFixedEnd?.let { time ->
                    onLaterFixedEnd?.let { apply ->
                        TextButton(onClick = apply) {
                            Text(stringResource(R.string.silence_year_fit_use_fixed_end, shownClock(anchor, time)), color = GreenPrimaryDark, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.silence_year_fit_keep), color = GreenPrimaryDark) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.prayer_editor_cancel), color = GreenPrimaryDark) } },
    )
}

/** A clock stored in the place's zone, shown in the phone's on the anchor's day (D8). */
private fun shownClock(anchor: PrayerTimelineAnchor, time: LocalTime): String {
    val shown = ZonedDateTime.of(anchor.prayerDay, time, anchor.placeZone).withZoneSameInstant(anchor.displayZone).toLocalTime()
    return "⁦" + String.format(Locale.US, "%02d:%02d", shown.hour, shown.minute) + "⁩"
}
