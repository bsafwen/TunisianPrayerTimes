package com.tunisianprayertimes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.NextPrayerBg
import com.tunisianprayertimes.ui.theme.PrayerNameColor

private val PrayerTimeGold = Color(0xFF896010)

/** Keep clock digits together; move them to a second row before the name gets squeezed. */
@Composable
internal fun PrayerTimelineRowHeader(
    prayer: Prayer,
    prayerName: String,
    time: String,
    isNextPrayer: Boolean,
    onPrayerTimeClick: (() -> Unit)?,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = LocalTextStyle.current
    val editLabel = stringResource(R.string.prayer_timeline_edit_prayer_time, prayerName)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val nameWidth = textMeasurer.measure(
            prayerName, style.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), softWrap = false,
        ).size.width
        val timeWidth = textMeasurer.measure(
            time, style.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), softWrap = false,
        ).size.width + with(density) { (if (onPrayerTimeClick != null) 38.dp else 0.dp).roundToPx() }
        val stack = nameWidth + timeWidth + with(density) { 12.dp.roundToPx() } > constraints.maxWidth
        val name: @Composable (Modifier) -> Unit = { modifier ->
            FlowRow(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    prayerName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PrayerNameColor,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                if (isNextPrayer) {
                    Text(
                        stringResource(R.string.prayer_timeline_up_next), fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark,
                        modifier = Modifier.align(Alignment.CenterVertically).clip(RoundedCornerShape(6.dp))
                            .background(NextPrayerBg).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        val clock: @Composable (Modifier) -> Unit = { modifier ->
            Box(
                modifier = modifier.then(
                    if (onPrayerTimeClick != null) Modifier.heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp)).background(GoldLight.copy(alpha = 0.3f))
                        .clickable(role = Role.Button, onClickLabel = editLabel, onClick = onPrayerTimeClick)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                    else Modifier,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        time, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = PrayerTimeGold,
                        style = TextStyle(textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.testTag("prayer_time_${prayer.name}"),
                    )
                    if (onPrayerTimeClick != null) {
                        Icon(painterResource(R.drawable.ic_edit), null, tint = PrayerTimeGold, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        if (stack) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                name(Modifier.fillMaxWidth())
                clock(Modifier.align(Alignment.End))
            }
        } else {
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                name(Modifier.weight(1f))
                clock(Modifier)
            }
        }
    }
}
