package com.tunisianprayertimes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

// Islamic teal & gold palette
val GreenPrimary = Color(0xFF00695C)
val GreenPrimaryDark = Color(0xFF004D40)
val Gold = Color(0xFFD4AF37)
val GoldLight = Color(0xFFF5E6C8)
val CardBg = Color(0xFFFFFFFF)
val BgCream = Color(0xFFFFF8F0)
val TextDark = Color(0xFF2C2C2C)
val TextMuted = Color(0xFF6D6D6D)
val Divider = Color(0xFFE8DFD0)
val PrayerNameColor = Color(0xFF004D40)
val SilenceRed = Color(0xFFC62828)
val CardBorder = Color(0xFFE8DFD0)
val HeaderStart = Color(0xFF004D40)
val HeaderEnd = Color(0xFF00695C)
val BannerBg = Color(0xFFFFF3E0)
val BannerStroke = Color(0xFFFFB74D)
val BannerText = Color(0xFFE65100)
val RamadanBg = Color(0xFFFFF8E1)

private val LightColorScheme = lightColorScheme(
    primary = GreenPrimary,
    onPrimary = Color.White,
    primaryContainer = GreenPrimaryDark,
    secondary = Gold,
    onSecondary = Color.White,
    background = BgCream,
    onBackground = TextDark,
    surface = CardBg,
    onSurface = TextDark,
    surfaceVariant = GoldLight,
    outline = CardBorder,
    error = SilenceRed,
)

@Composable
fun TunisianPrayerTimesTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = LightColorScheme,
            content = content
        )
    }
}
