package com.tunisianprayertimes.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.tunisianprayertimes.tv.R

/**
 * The «مداد» palette of the «أفق» design, after the Blue Qur'an of Kairouan: ivory on ink, silver for
 * the 8-point star, and one stone gold reserved for the next prayer. The screen is laid out on the
 * 960 × 540 dp canvas, half the 1920 × 1080 px of the mockups: a 44 px mockup size is 22 sp here.
 */
object Midad {
    /** The ground under everything; the sky fades into it at the horizon. */
    val Ground = Color(0xFF080B18)
    /** Tiles, cards and the settings panels. */
    val Surface = Color(0xFF0F1428)
    /** A row inside a surface (the settings tables). */
    val SurfaceRaised = Color(0xFF151B35)
    /** The inside of the mihrab on the adhan screen. */
    val Niche = Color(0xFF0B0F20)

    /** Ivory text; never pure white, which blooms on a cheap LCD. */
    val Text = Color(0xFFEEE7D8)
    val Muted = Color(0xFFB3B8D0)
    val Dim = Color(0xFF8E94AE)
    /** Sacred text set in Amiri on the sky, a little warmer than the interface text. */
    val Verse = Color(0xFFDCD6C8)

    /** Stone gold: the next prayer only (its tile, its countdown), never decoration. */
    val Gold = Color(0xFFD9B45C)
    /** The carved line inside the gold arch. */
    val GoldLine = Color(0xFFB8954A)
    /** Text on the gold tile. */
    val OnGold = Color(0xFF0B0F1E)
    val OnGoldMuted = Color(0xFF3A3320)

    /** Silver: the 8-point star and the medallion. */
    val Silver = Color(0xFFBCC3D8)
    /** Warm alert: the last minute before the iqamah, problems in settings. */
    val Alert = Color(0xFFE8906A)

    /** Hairlines: the frame of an image, a card's edge. */
    val Keyline = Color(0xFF232A42)
    /** The rule above the ticker. */
    val Rule = Color(0xFF161B2E)
    /** The carved line inside a dark arch. */
    val ArchLine = Color(0xFF1D2445)
    /** The faint star frieze at the ends of the ticker. */
    val Frieze = Color(0xFF1A2140)
    /** The mihrab's outer and inner lines. */
    val MihrabLine = Color(0xFF4A5578)
    val MihrabInner = Color(0xFF252D4C)
    /** A dot that is off (pager, studs). */
    val DotOff = Color(0xFF2A3048)
    /** A fading rule beside a medallion. */
    val DividerLine = Color(0xFF2E3656)

    /** During the prayer and the khutba. */
    val Prayer = Color.Black
    /** The night screen: a warm, dim clock on black. */
    val NightClock = Color(0xFFB8A88E)
    val NightText = Color(0xFF8F8372)
    /** The khutba's one line: dim on purpose. */
    val Khutba = Color(0xFF6B6B6B)
}

/** Readex Pro: the interface and the numbers. A variable font; each weight sets its axis. */
val Readex = FontFamily(
    Font(R.font.readex_pro, FontWeight.Light),
    Font(R.font.readex_pro, FontWeight.Normal),
    Font(R.font.readex_pro, FontWeight.Medium),
    Font(R.font.readex_pro, FontWeight.SemiBold),
    Font(R.font.readex_pro, FontWeight.Bold),
)

/** Reem Kufi: a Fatimid Kufic, for one word at a time (a prayer's name, the mosque's name). */
val Kufi = FontFamily(
    Font(R.font.reem_kufi, FontWeight.Normal),
    Font(R.font.reem_kufi, FontWeight.Medium),
    Font(R.font.reem_kufi, FontWeight.SemiBold),
    Font(R.font.reem_kufi, FontWeight.Bold),
)

/** Amiri: the Quran, the adhkar and the duas, with their full vowel marks. */
val Amiri = FontFamily(Font(R.font.amiri, FontWeight.Normal))

/**
 * A text style in one of the three families. [lineHeight] is a multiple of the size (the mockups'
 * `line-height`); big numbers use less than 1, trimmed so the digits sit where the mockup puts them.
 */
fun midadStyle(
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Midad.Text,
    family: FontFamily = Readex,
    lineHeight: Float? = null,
): TextStyle = TextStyle(
    fontFamily = family,
    fontSize = size,
    fontWeight = weight,
    color = color,
    lineHeight = lineHeight?.em ?: TextUnit.Unspecified,
    lineHeightStyle = lineHeight?.let { LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both) },
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)
