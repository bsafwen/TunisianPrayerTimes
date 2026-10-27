package com.tunisianprayertimes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.PrayerNameColor

/**
 * The «عباداتي» tab: the Qibla card first, whole, for those who come for it, then worship beyond the five
 * prayers: voluntary prayer times, the sunnah fasts, then the remembrance of death.
 */
@Composable
internal fun WorshipPage(placeRevision: Int) {
    // Opened by a reminder's notification: its sheet comes up, without the location dialog over it.
    val openedForFasting = remember { FastingRequests.sheet || FastingRequests.suhoor || RemembranceRequests.sheet }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "عباداتي",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = PrayerNameColor,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        QiblaCard(placeRevision = placeRevision, askLocation = !openedForFasting)
        WorshipSectionHeader(stringResource(R.string.worship_voluntary_title), R.drawable.ic_prayer_dhuhr_sun)
        VoluntaryPrayerTools(placeRevision = placeRevision)
        WorshipSectionHeader("الصيام", R.drawable.ic_adhkar_moon)
        FastingCard()
        WorshipSectionHeader("ذكر الموت", R.drawable.ic_hourglass)
        DeathRemembranceCard()
    }
}

@Composable
private fun WorshipSectionHeader(title: String, icon: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = GreenPrimary, modifier = Modifier.size(20.dp))
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = PrayerNameColor,
            modifier = Modifier.semantics { heading() },
        )
    }
}
