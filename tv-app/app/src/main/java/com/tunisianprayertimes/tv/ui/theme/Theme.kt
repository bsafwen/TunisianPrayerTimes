package com.tunisianprayertimes.tv.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

/** The theme the admin picked; screens read [DisplayTheme.sky] from it. */
val LocalDisplayTheme = staticCompositionLocalOf { ThemeRegistry.builtInThemes.first() }

/** Arabic is never letter-spaced: tracking breaks the joins between letters. */
private val TvTypography = Typography(
    displayLarge = midadStyle(72.sp, FontWeight.Bold),
    displayMedium = midadStyle(56.sp, FontWeight.Bold),
    displaySmall = midadStyle(44.sp, FontWeight.SemiBold),
    headlineLarge = midadStyle(36.sp, FontWeight.SemiBold),
    headlineMedium = midadStyle(28.sp, FontWeight.SemiBold),
    headlineSmall = midadStyle(24.sp, FontWeight.SemiBold),
    titleLarge = midadStyle(22.sp, FontWeight.Medium),
    titleMedium = midadStyle(18.sp, FontWeight.Medium),
    titleSmall = midadStyle(16.sp, FontWeight.Medium),
    bodyLarge = midadStyle(18.sp),
    bodyMedium = midadStyle(16.sp),
    bodySmall = midadStyle(14.sp),
    labelLarge = midadStyle(14.sp, FontWeight.Medium),
    labelMedium = midadStyle(12.sp, FontWeight.Medium),
    labelSmall = midadStyle(11.sp, FontWeight.Medium),
)

private val MidadColors = darkColorScheme(
    primary = Midad.Text,
    onPrimary = Midad.OnGold,
    primaryContainer = Midad.SurfaceRaised,
    onPrimaryContainer = Midad.Text,
    secondary = Midad.Gold,
    onSecondary = Midad.OnGold,
    secondaryContainer = Midad.SurfaceRaised,
    onSecondaryContainer = Midad.Text,
    tertiary = Midad.Silver,
    background = Midad.Ground,
    onBackground = Midad.Text,
    surface = Midad.Surface,
    onSurface = Midad.Text,
    surfaceVariant = Midad.SurfaceRaised,
    onSurfaceVariant = Midad.Muted,
    outline = Midad.Keyline,
    outlineVariant = Midad.Rule,
    error = Midad.Alert,
    onError = Midad.OnGold,
)

@Composable
fun TvPrayerTheme(
    theme: DisplayTheme = ThemeRegistry.builtInThemes.first(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalDisplayTheme provides theme,
    ) {
        MaterialTheme(colorScheme = MidadColors, typography = TvTypography) {
            CompositionLocalProvider(LocalTextStyle provides midadStyle(18.sp), content = content)
        }
    }
}
