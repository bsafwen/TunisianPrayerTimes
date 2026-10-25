package com.tunisianprayertimes.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.tunisianprayertimes.adhkar.MushafPassages
import com.tunisianprayertimes.adhkar.MushafExplanationQuotes

/** The mushaf font's letters are smaller than the reading font's at the same size: this brings them level. */
private const val MUSHAF_SCALE = 1.2f

private fun TextUnit.mushaf() = this * MUSHAF_SCALE

/**
 * A Qur'an passage as the mushaf prints it, at the size the reading text would have: [fontSize] and [lineHeight]
 * are the reading font's. [description] is what TalkBack reads (the readable wording).
 */
@Composable
internal fun MushafPassage(text: String, description: String, fontSize: TextUnit, lineHeight: TextUnit, color: Color,
                           modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { contentDescription = description }, fontFamily = QuranMushafFont,
        fontSize = fontSize.mushaf(), lineHeight = lineHeight.mushaf(), color = color, textAlign = TextAlign.Center)
}

/** Registered Quran quotes use the mushaf's exact text and font; prose and the accessible reading stay intact. */
@Composable
internal fun DhikrExplanation(id: String, text: String, color: Color, fontSize: TextUnit, lineHeight: TextUnit,
                             modifier: Modifier = Modifier) {
    val displayed = remember(id, text) { withMushafExplanation(id, text) }
    Text(displayed, modifier.semantics { contentDescription = text }, color = color,
        fontSize = fontSize, lineHeight = lineHeight, textAlign = TextAlign.Right)
}

internal fun withMushafExplanation(id: String, explanation: String): AnnotatedString = buildAnnotatedString {
    MushafExplanationQuotes.parts(id, explanation).forEach { part ->
        if (part.quran) withStyle(SpanStyle(fontFamily = QuranMushafFont, fontSize = MUSHAF_SCALE.em)) { append(part.text) }
        else append(part.text)
    }
}

/**
 * A dhikr's preview in a list: a Qur'an passage as the mushaf prints it (its verses, without the basmala) in the
 * mushaf's font, any other dhikr as [text] reads in [font].
 */
@Composable
internal fun DhikrPreview(id: String, text: String, color: Color, fontSize: TextUnit, maxLines: Int,
                          modifier: Modifier = Modifier, font: FontFamily? = AdhkarReadingFont,
                          lineHeight: TextUnit = TextUnit.Unspecified) {
    val verses = MushafPassages.verses(id)
    if (verses == null) Text(text, modifier, color = color, fontFamily = font, fontSize = fontSize, lineHeight = lineHeight,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    else Text(verses, modifier.semantics { contentDescription = text }, color = color, fontFamily = QuranMushafFont,
        // The font's own line is very tall (room for stacked marks): a list keeps its rows' rhythm.
        fontSize = fontSize.mushaf(), lineHeight = if (lineHeight == TextUnit.Unspecified) fontSize.mushaf() * 1.8f else lineHeight.mushaf(),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/**
 * A line of the interface quoting Qur'an words in «»: the quoted words in the mushaf font, a little larger so they
 * sit level with the interface's letters.
 */
internal fun withQuranQuotes(text: String): AnnotatedString = buildAnnotatedString {
    var at = 0
    Regex("«([^»]+)»").findAll(text).forEach { m ->
        append(text.substring(at, m.range.first + 1))
        withStyle(SpanStyle(fontFamily = QuranMushafFont, fontSize = 1.15.em)) { append(m.groupValues[1]) }
        append('»')
        at = m.range.last + 1
    }
    append(text.substring(at))
}
