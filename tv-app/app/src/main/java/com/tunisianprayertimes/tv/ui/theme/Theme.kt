package com.tunisianprayertimes.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

// ══════════════════════════════════════════════════════════════════════════
//  Theme system — all colors flow from [LocalTvTheme]
// ══════════════════════════════════════════════════════════════════════════

/**
 * CompositionLocal that provides the current [TvThemeConfig] to all screens.
 * Access via `LocalTvTheme.current`.
 */
val LocalTvTheme = staticCompositionLocalOf { ThemeRegistry.builtInThemes.first() }

// ── Convenience accessors (keep top-level val names for existing code) ──
// These read from LocalTvTheme at call-site — they must be accessed
// inside a @Composable scope. For non-composable contexts the default
// theme's values are used via the backing field.

// Primary
val TealPrimary @Composable get() = LocalTvTheme.current.primary
val TealDark @Composable get() = LocalTvTheme.current.primaryDark
val TealDeep @Composable get() = LocalTvTheme.current.primaryDeep

// Accent / Gold
val Gold @Composable get() = LocalTvTheme.current.accent
val GoldLight @Composable get() = LocalTvTheme.current.accentLight
val GoldMuted @Composable get() = LocalTvTheme.current.accentMuted

// Surfaces
val BackgroundDark @Composable get() = LocalTvTheme.current.background
val SurfaceDark @Composable get() = LocalTvTheme.current.surfaceDark
val SurfaceCard @Composable get() = LocalTvTheme.current.surfaceCard
val SurfaceElevated @Composable get() = LocalTvTheme.current.surfaceElevated

// Next prayer
val NextPrayerHighlight @Composable get() = LocalTvTheme.current.nextPrayerBg
val NextPrayerGlow @Composable get() = LocalTvTheme.current.nextPrayerGlow

// Borders
val GlassWhite get() = Color(0x1AFFFFFF) // fixed — not themed
val GlassBorder @Composable get() = LocalTvTheme.current.cardBorder
val GoldBorder @Composable get() = LocalTvTheme.current.accentBorder
val CardBorder @Composable get() = LocalTvTheme.current.cardBorder

// Text
val TextWhite @Composable get() = LocalTvTheme.current.textPrimary
val TextMuted @Composable get() = LocalTvTheme.current.textMuted
val TextDim @Composable get() = LocalTvTheme.current.textDim

// Semantic
val AdhanGreen get() = Color(0xFF2ECC71) // fixed
val CountdownAmber @Composable get() = LocalTvTheme.current.countdownColor
val CountdownOrange @Composable get() = LocalTvTheme.current.countdownColor

// Ramadan
val RamadanPurple @Composable get() = LocalTvTheme.current.ramadanPrimary
val RamadanGold @Composable get() = LocalTvTheme.current.ramadanAccent
val RamadanMoon @Composable get() = LocalTvTheme.current.ramadanText
val RamadanDeep @Composable get() = LocalTvTheme.current.ramadanDeep
val IftarGreen get() = Color(0xFF2ECC71) // fixed

// Islamic decorative characters
object IslamicSymbols {
    const val BISMILLAH = "﷽"
    const val STAR = "✦"
    const val CRESCENT = "☪"
}

private val TvTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 72.sp, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 56.sp, letterSpacing = (-0.5).sp),
    displaySmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 44.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 36.sp, letterSpacing = (-0.25).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 18.sp, letterSpacing = 0.15.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 18.sp, letterSpacing = 0.25.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, letterSpacing = 0.25.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.5.sp),
)

/**
 * Build a Material3 [darkColorScheme] from the given [TvThemeConfig].
 */
private fun colorSchemeFrom(theme: TvThemeConfig) = darkColorScheme(
    primary = theme.primary,
    onPrimary = Color.White,
    primaryContainer = theme.primaryDark,
    onPrimaryContainer = theme.accentLight,
    secondary = theme.accent,
    onSecondary = Color(0xFF1C1300),
    secondaryContainer = theme.accentMuted,
    background = theme.background,
    onBackground = theme.textPrimary,
    surface = theme.surfaceCard,
    onSurface = theme.textPrimary,
    surfaceVariant = theme.surfaceDark,
    onSurfaceVariant = theme.textMuted,
    outline = theme.cardBorder,
)

@Composable
fun TvPrayerTheme(
    themeConfig: TvThemeConfig = ThemeRegistry.builtInThemes.first(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalTvTheme provides themeConfig,
    ) {
        MaterialTheme(
            colorScheme = colorSchemeFrom(themeConfig),
            typography = TvTypography,
            content = content
        )
    }
}
