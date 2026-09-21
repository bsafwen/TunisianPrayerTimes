package com.tunisianprayertimes.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.DhikrCategory

internal data class AdhkarPalette(
    val background: Color, val surface: Color, val ink: Color, val muted: Color,
    val primary: Color, val sage: Color, val border: Color,
    val forest: Color = Color(0xFF104E43), val gold: Color = Color(0xFFC7A775),
)
internal val AdhkarLight = AdhkarPalette(Color(0xFFFAF8F2), Color(0xFFFFFEFA), Color(0xFF263D36),
    Color(0xFF616E65), Color(0xFF104E43), Color(0xFFE5EDE5), Color(0xFFE5E6DC))
internal val AdhkarDark = AdhkarPalette(Color(0xFF101C17), Color(0xFF192A22), Color(0xFFE2ECE4),
    Color(0xFFB0BFB3), Color(0xFFA0D7BF), Color(0xFF243B2E), Color(0xFF344A3D))
internal val LocalAdhkarPalette = staticCompositionLocalOf { AdhkarLight }
internal val AdhkarReadingFont = FontFamily(Font(R.font.noto_naskh_arabic))
private val AdhkarUiFont = FontFamily(Font(R.font.noto_sans_arabic), Font(R.font.noto_sans_arabic_semibold, FontWeight.SemiBold))
private fun TextStyle.adhkarUi() = copy(fontFamily = AdhkarUiFont,
    lineHeight = 1.5.em,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    platformStyle = PlatformTextStyle(includeFontPadding = false))

@Composable internal fun AdhkarTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) AdhkarDark else AdhkarLight
    val scheme = if (dark) darkColorScheme(primary = p.primary, background = p.background, surface = p.surface,
        onSurface = p.ink, onBackground = p.ink, outline = p.border, surfaceVariant = p.sage, onSurfaceVariant = p.muted)
    else lightColorScheme(primary = p.primary, background = p.background, surface = p.surface,
        onSurface = p.ink, onBackground = p.ink, outline = p.border, surfaceVariant = p.sage, onSurfaceVariant = p.muted)
    val t = MaterialTheme.typography
    val typography = Typography(
        displayLarge = t.displayLarge.adhkarUi(), displayMedium = t.displayMedium.adhkarUi(), displaySmall = t.displaySmall.adhkarUi(),
        headlineLarge = t.headlineLarge.adhkarUi(), headlineMedium = t.headlineMedium.adhkarUi(), headlineSmall = t.headlineSmall.adhkarUi(),
        titleLarge = t.titleLarge.adhkarUi(), titleMedium = t.titleMedium.adhkarUi(), titleSmall = t.titleSmall.adhkarUi(),
        bodyLarge = t.bodyLarge.adhkarUi(), bodyMedium = t.bodyMedium.adhkarUi(), bodySmall = t.bodySmall.adhkarUi(),
        labelLarge = t.labelLarge.adhkarUi(), labelMedium = t.labelMedium.adhkarUi(), labelSmall = t.labelSmall.adhkarUi(),
    )
    val themedScheme = scheme.copy(secondary = p.primary, onSecondary = if (dark) p.background else Color.White,
        secondaryContainer = p.sage, onSecondaryContainer = p.primary, primaryContainer = p.sage,
        onPrimaryContainer = p.primary, onPrimary = if (dark) p.background else Color.White,
        tertiary = p.primary, outlineVariant = p.border, surfaceTint = p.primary)
    CompositionLocalProvider(LocalAdhkarPalette provides p) { MaterialTheme(colorScheme = themedScheme, typography = typography, content = content) }
}
@Composable internal fun DhikrIcon(resource: Int, description: String? = null, tint: Color = LocalAdhkarPalette.current.primary,
                                   modifier: Modifier = Modifier.size(22.dp)) =
    Icon(painterResource(resource), description, modifier, tint)

@Composable internal fun AdhkarDialogSystemBars() {
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}

internal fun categoryIcon(category: DhikrCategory): Int = when (category) {
    DhikrCategory.MORNING -> R.drawable.ic_adhkar_sun
    DhikrCategory.EVENING -> R.drawable.ic_adhkar_moon
    DhikrCategory.SALAH -> R.drawable.ic_adhkar_mosque
    DhikrCategory.SLEEP -> R.drawable.ic_adhkar_sleep
    DhikrCategory.HOME -> R.drawable.ic_adhkar_home
    DhikrCategory.DAILY -> R.drawable.ic_adhkar_leaf
}
internal val adhkarCategoryOrder = listOf(DhikrCategory.MORNING, DhikrCategory.EVENING, DhikrCategory.SALAH,
    DhikrCategory.SLEEP, DhikrCategory.HOME, DhikrCategory.DAILY)
internal fun arabicNumber(value: Int): String = value.toString().map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("")
internal fun bidiClock(value: String): String = "\u2066" + value + "\u2069"
