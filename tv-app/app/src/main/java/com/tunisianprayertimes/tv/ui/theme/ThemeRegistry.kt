package com.tunisianprayertimes.tv.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

/**
 * Registry of available themes.
 * Built-in themes are hardcoded. Additional themes can be loaded
 * from JSON files in assets/themes/ or from external storage.
 */
object ThemeRegistry {

    /** All built-in themes. */
    val builtInThemes: List<TvThemeConfig> = listOf(
        midnightNavy(),
        emeraldMosque(),
        desertSand(),
    )

    /** Find a theme by ID, falling back to the default. */
    fun findById(id: String): TvThemeConfig =
        builtInThemes.find { it.id == id } ?: builtInThemes.first()

    /** Load additional themes from assets/themes/ directory. */
    fun loadFromAssets(context: Context): List<TvThemeConfig> {
        return try {
            val assetFiles = context.assets.list("themes") ?: emptyArray()
            assetFiles.filter { it.endsWith(".json") }.mapNotNull { fileName ->
                try {
                    val jsonStr = context.assets.open("themes/$fileName")
                        .bufferedReader().use { it.readText() }
                    parseThemeJson(jsonStr)
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Get all available themes (built-in + loaded from assets). */
    fun allThemes(context: Context): List<TvThemeConfig> =
        builtInThemes + loadFromAssets(context)

    /** Parse a Color hex string like "#FF0A1628" → Color. */
    private fun parseColor(hex: String): Color =
        Color(hex.removePrefix("#").toLong(16))

    /** Parse a JSON object into a [TvThemeConfig]. */
    private fun parseThemeJson(jsonStr: String): TvThemeConfig {
        val j = JSONObject(jsonStr)
        return TvThemeConfig(
            id = j.getString("id"),
            nameAr = j.getString("nameAr"),
            nameEn = j.getString("nameEn"),
            primary = parseColor(j.getString("primary")),
            primaryDark = parseColor(j.getString("primaryDark")),
            primaryDeep = parseColor(j.getString("primaryDeep")),
            accent = parseColor(j.getString("accent")),
            accentLight = parseColor(j.getString("accentLight")),
            accentMuted = parseColor(j.getString("accentMuted")),
            background = parseColor(j.getString("background")),
            surfaceDark = parseColor(j.getString("surfaceDark")),
            surfaceCard = parseColor(j.getString("surfaceCard")),
            surfaceElevated = parseColor(j.getString("surfaceElevated")),
            nextPrayerBg = parseColor(j.getString("nextPrayerBg")),
            nextPrayerGlow = parseColor(j.getString("nextPrayerGlow")),
            cardBorder = parseColor(j.getString("cardBorder")),
            accentBorder = parseColor(j.getString("accentBorder")),
            textPrimary = parseColor(j.getString("textPrimary")),
            textMuted = parseColor(j.getString("textMuted")),
            textDim = parseColor(j.getString("textDim")),
            countdownColor = parseColor(j.getString("countdownColor")),
            ramadanPrimary = parseColor(j.getString("ramadanPrimary")),
            ramadanAccent = parseColor(j.getString("ramadanAccent")),
            ramadanText = parseColor(j.getString("ramadanText")),
            ramadanDeep = parseColor(j.getString("ramadanDeep")),
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Built-in themes
    // ══════════════════════════════════════════════════════════════════════

    /** Default — Deep navy with gold accents (MAWAQIT-inspired). */
    private fun midnightNavy() = TvThemeConfig(
        id = "midnight_navy",
        nameAr = "أزرق داكن",
        nameEn = "Midnight Navy",
        primary = Color(0xFF0D9B6E),
        primaryDark = Color(0xFF067A55),
        primaryDeep = Color(0xFF054D37),
        accent = Color(0xFFD4A843),
        accentLight = Color(0xFFEDD9A3),
        accentMuted = Color(0xFFA88734),
        background = Color(0xFF0A1628),
        surfaceDark = Color(0xFF0F1F38),
        surfaceCard = Color(0xFF152A4A),
        surfaceElevated = Color(0xFF1C3358),
        nextPrayerBg = Color(0xFF3D2E0A),
        nextPrayerGlow = Color(0xFFD4A843),
        cardBorder = Color(0x33FFFFFF),
        accentBorder = Color(0x55D4A843),
        textPrimary = Color(0xFFF0F0F0),
        textMuted = Color(0xFF8AA4BE),
        textDim = Color(0xFF5A7A96),
        countdownColor = Color(0xFFE8A317),
        ramadanPrimary = Color(0xFF2D1B69),
        ramadanAccent = Color(0xFFFFD700),
        ramadanText = Color(0xFFFFF8E1),
        ramadanDeep = Color(0xFF1A0F42),
    )

    /** Warm mosque green with gold — traditional masjid feel. */
    private fun emeraldMosque() = TvThemeConfig(
        id = "emerald_mosque",
        nameAr = "أخضر مسجدي",
        nameEn = "Emerald Mosque",
        primary = Color(0xFF1B8C6A),
        primaryDark = Color(0xFF0E6B4F),
        primaryDeep = Color(0xFF0A3D2E),
        accent = Color(0xFFDAA520),
        accentLight = Color(0xFFFFE4A0),
        accentMuted = Color(0xFFB8860B),
        background = Color(0xFF071E15),
        surfaceDark = Color(0xFF0C2A1F),
        surfaceCard = Color(0xFF12372A),
        surfaceElevated = Color(0xFF1A4535),
        nextPrayerBg = Color(0xFF1B5E3A),
        nextPrayerGlow = Color(0xFFDAA520),
        cardBorder = Color(0x40DAA520),
        accentBorder = Color(0x66DAA520),
        textPrimary = Color(0xFFF5F0E1),
        textMuted = Color(0xFFA8C4B0),
        textDim = Color(0xFF6B9A7E),
        countdownColor = Color(0xFFE8A317),
        ramadanPrimary = Color(0xFF2D1B69),
        ramadanAccent = Color(0xFFFFD700),
        ramadanText = Color(0xFFFFF8E1),
        ramadanDeep = Color(0xFF1A0F42),
    )

    /** Warm beige/sand with teal accents — desert oasis feel. */
    private fun desertSand() = TvThemeConfig(
        id = "desert_sand",
        nameAr = "رمال الصحراء",
        nameEn = "Desert Sand",
        primary = Color(0xFF1A8A7A),
        primaryDark = Color(0xFF14706A),
        primaryDeep = Color(0xFF0C4A46),
        accent = Color(0xFFC48B3C),
        accentLight = Color(0xFFE8C88A),
        accentMuted = Color(0xFF9A6E2E),
        background = Color(0xFF1C1610),
        surfaceDark = Color(0xFF2A2118),
        surfaceCard = Color(0xFF362B1E),
        surfaceElevated = Color(0xFF443626),
        nextPrayerBg = Color(0xFF3E2E10),
        nextPrayerGlow = Color(0xFFC48B3C),
        cardBorder = Color(0x33C48B3C),
        accentBorder = Color(0x55C48B3C),
        textPrimary = Color(0xFFF5EDE0),
        textMuted = Color(0xFFB8A898),
        textDim = Color(0xFF8A7A6A),
        countdownColor = Color(0xFFD4953A),
        ramadanPrimary = Color(0xFF2D1B69),
        ramadanAccent = Color(0xFFFFD700),
        ramadanText = Color(0xFFFFF8E1),
        ramadanDeep = Color(0xFF1A0F42),
    )
}
