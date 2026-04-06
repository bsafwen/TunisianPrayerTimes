package com.tunisianprayertimes.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Complete theme definition — every color slot the UI uses.
 * Themes can be defined in JSON and loaded at runtime.
 *
 * To add a new built-in theme: add a factory function in [ThemeRegistry].
 * To add a custom theme: place a JSON file in assets/themes/ with the same keys.
 */
@Immutable
data class TvThemeConfig(
    val id: String,
    val nameAr: String,
    val nameEn: String,

    // ── Primary ──────────────────────────────────────────────────────────
    val primary: Color,
    val primaryDark: Color,
    val primaryDeep: Color,

    // ── Accent / Gold ───────────────────────────────────────────────────
    val accent: Color,
    val accentLight: Color,
    val accentMuted: Color,

    // ── Surfaces ────────────────────────────────────────────────────────
    val background: Color,
    val surfaceDark: Color,
    val surfaceCard: Color,
    val surfaceElevated: Color,

    // ── Next prayer highlight ───────────────────────────────────────────
    val nextPrayerBg: Color,
    val nextPrayerGlow: Color,

    // ── Borders ─────────────────────────────────────────────────────────
    val cardBorder: Color,
    val accentBorder: Color,

    // ── Text ────────────────────────────────────────────────────────────
    val textPrimary: Color,
    val textMuted: Color,
    val textDim: Color,

    // ── Semantic ────────────────────────────────────────────────────────
    val countdownColor: Color,

    // ── Ramadan overlay ─────────────────────────────────────────────────
    val ramadanPrimary: Color,
    val ramadanAccent: Color,
    val ramadanText: Color,
    val ramadanDeep: Color,
)
