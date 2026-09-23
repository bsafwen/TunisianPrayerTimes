package com.tunisianprayertimes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.ui.theme.TextMuted

internal data class AdhkarPalette(
    val background: Color, val surface: Color, val ink: Color, val muted: Color,
    val primary: Color, val sage: Color, val border: Color, val forest: Color, val gold: Color,
)

// Colors always come from the app theme so the tab matches the rest of the app.
private fun adhkarPaletteOf(scheme: ColorScheme) = AdhkarPalette(
    background = scheme.background, surface = scheme.surface, ink = scheme.onSurface,
    muted = TextMuted, primary = scheme.primary, sage = scheme.surfaceVariant,
    border = scheme.outline, forest = scheme.primaryContainer, gold = scheme.secondary,
)
internal val LocalAdhkarPalette = staticCompositionLocalOf { adhkarPaletteOf(lightColorScheme()) }
internal val AdhkarReadingFont = FontFamily(Font(R.font.noto_naskh_arabic))
private val AdhkarUiFont = FontFamily(Font(R.font.noto_sans_arabic), Font(R.font.noto_sans_arabic_semibold, FontWeight.SemiBold))
private fun TextStyle.adhkarUi() = copy(fontFamily = AdhkarUiFont,
    lineHeight = 1.5.em,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    platformStyle = PlatformTextStyle(includeFontPadding = false))

// Approved mockup tokens layered on the app palette.
internal val AdhkarHeading = Color(0xFF1D2B4F)
internal val AdhkarSurface = Color(0xFFFFFFFF)
internal val AdhkarBorder = Color(0xFFEDE7DA)
internal val AdhkarSoftGreen = Color(0xFFE6F0EA)
internal val AdhkarSoftGold = Color(0xFFFBF1D8)
internal val AdhkarGoldAccent = Color(0xFFE3A93C)
internal val AdhkarMorningTop = Color(0xFFF8E3A8)
internal val AdhkarMorningBottom = Color(0xFFEFC65C)
internal val AdhkarEveningTop = Color(0xFF20645A)
internal val AdhkarEveningBottom = Color(0xFF12463F)
internal val AdhkarRingTrack = Color(0xFFDCE8E1)
internal val AdhkarHeart = Color(0xFFE23B3B)

@Composable internal fun AdhkarTheme(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val p = adhkarPaletteOf(scheme)
    val t = MaterialTheme.typography
    val typography = Typography(
        displayLarge = t.displayLarge.adhkarUi(), displayMedium = t.displayMedium.adhkarUi(), displaySmall = t.displaySmall.adhkarUi(),
        headlineLarge = t.headlineLarge.adhkarUi(), headlineMedium = t.headlineMedium.adhkarUi(), headlineSmall = t.headlineSmall.adhkarUi(),
        titleLarge = t.titleLarge.adhkarUi(), titleMedium = t.titleMedium.adhkarUi(), titleSmall = t.titleSmall.adhkarUi(),
        bodyLarge = t.bodyLarge.adhkarUi(), bodyMedium = t.bodyMedium.adhkarUi(), bodySmall = t.bodySmall.adhkarUi(),
        labelLarge = t.labelLarge.adhkarUi(), labelMedium = t.labelMedium.adhkarUi(), labelSmall = t.labelSmall.adhkarUi(),
    )
    CompositionLocalProvider(LocalAdhkarPalette provides p) { MaterialTheme(colorScheme = scheme, typography = typography, content = content) }
}
@Composable internal fun DhikrIcon(resource: Int, description: String? = null, tint: Color = LocalAdhkarPalette.current.primary,
                                   modifier: Modifier = Modifier.size(22.dp)) =
    Icon(painterResource(resource), description, modifier, tint)

@Composable internal fun AdhkarDialogSystemBars() {
    val view = LocalView.current
    val light = LocalAdhkarPalette.current.background.luminance() > .5f
    SideEffect {
        (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }
}

/** White rounded card with the mockup's soft border. */
@Composable internal fun AdhkarCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = AdhkarSurface,
            border = BorderStroke(1.dp, AdhkarBorder), modifier = modifier) { content() }
    } else {
        Surface(shape = shape, color = AdhkarSurface,
            border = BorderStroke(1.dp, AdhkarBorder), modifier = modifier) { content() }
    }
}

/** Right-aligned section title with an optional trailing action (styled like "عرض الكل"). */
@Composable internal fun AdhkarSectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    actionModifier: Modifier = Modifier,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = AdhkarHeading, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        if (action != null) {
            TextButton(onClick = { onAction?.invoke() }, modifier = actionModifier) {
                Text(action, color = LocalAdhkarPalette.current.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

internal fun categoryIcon(category: DhikrCategory): Int = when (category) {
    DhikrCategory.MORNING -> R.drawable.ic_adhkar_sun
    DhikrCategory.EVENING -> R.drawable.ic_adhkar_moon
    DhikrCategory.SALAH -> R.drawable.ic_adhkar_mosque
    DhikrCategory.PRAYER -> R.drawable.ic_adhkar_bookmark
    DhikrCategory.SLEEP -> R.drawable.ic_adhkar_sleep
    DhikrCategory.HOME -> R.drawable.ic_adhkar_home
    DhikrCategory.DAILY -> R.drawable.ic_adhkar_leaf
}
internal val adhkarCategoryOrder = listOf(DhikrCategory.MORNING, DhikrCategory.EVENING, DhikrCategory.SALAH,
    DhikrCategory.PRAYER, DhikrCategory.SLEEP, DhikrCategory.HOME, DhikrCategory.DAILY)
internal fun latinNumber(value: Int): String = value.toString()
internal fun bidiClock(value: String): String = "\u2066" + value + "\u2069"
