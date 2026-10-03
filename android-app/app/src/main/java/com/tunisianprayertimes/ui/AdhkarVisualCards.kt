package com.tunisianprayertimes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.DhikrCategory

/** Reading entry point: its completion badge describes reading, never reminder activation. */
@Composable
internal fun AdhkarHeroCard(
    category: DhikrCategory,
    completed: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val morning = category == DhikrCategory.MORNING
    val title = collectionTitle(category)
    val ink = if (morning) Color(0xFF06433D) else Color.White
    val shape = RoundedCornerShape(24.dp)

    BoxWithConstraints(modifier = modifier) {
        // Match the scene artwork's proportions, with room for the live text on narrow screens.
        val cardHeight = (maxWidth * (1165f / 1350f)).coerceAtLeast(134.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .clip(shape)
                .clickable(
                    onClickLabel = if (completed) "افتح $title، اكتملت القراءة لهذه الفترة" else "ابدأ قراءة $title",
                    role = Role.Button,
                    onClick = onClick,
                ),
        ) {
            Image(
                painter = painterResource(
                    if (morning) R.drawable.adhkar_morning_scene else R.drawable.adhkar_evening_scene,
                ),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            Column(
                Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    title,
                    color = ink,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (morning) "ابدأ يومك بذكر الله" else "اختم يومك بذكر الله",
                    color = ink.copy(alpha = if (morning) .86f else .9f),
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (morning) Color(0xFF936800).copy(alpha = .91f) else Color(0xFF07433F).copy(alpha = .82f))
                        .border(1.dp, Color.White.copy(alpha = if (morning) .7f else .65f), RoundedCornerShape(50))
                        .padding(horizontal = 11.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (completed) "عرض الورد" else "ابدأ القراءة",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                    DhikrIcon(R.drawable.ic_adhkar_next, tint = Color.White, modifier = Modifier.size(13.dp))
                }
            }

            if (completed) {
                Row(
                    Modifier.align(Alignment.TopEnd)
                        .padding(9.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = if (morning) .85f else .2f))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    DhikrIcon(R.drawable.ic_adhkar_check, tint = ink, modifier = Modifier.size(11.dp))
                    Text("تمّت القراءة", color = ink, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

