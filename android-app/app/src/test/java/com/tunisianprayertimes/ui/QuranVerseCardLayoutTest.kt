package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuranVerseCardLayoutTest {
    @get:Rule val rule = createComposeRule()

    private fun card(fontScale: Float) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                // A 360dp phone: the card sits inside the reader's side margins, in the reader's own theme and font.
                AdhkarTheme {
                    Box(Modifier.width(344.dp)) {
                        QuranVerseCard(
                            title = "البقرة · الآية 47", bookmarked = false, hasPoint = true,
                            onListen = {}, onRepeat = {}, onPoint = {}, onNotebook = {}, onBookmark = {}, onCopy = {}, onShare = {}, onDismiss = {},
                        )
                    }
                }
            }
        }
    }

    private fun top(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot().top

    @Test
    fun normalText_keepsTheSixActionsInOneRow() {
        card(1f)
        assertEquals(top("quran_verse_listen"), top("quran_verse_share"))
    }

    @Test
    fun ownersFontScale_stillFitsOneRow() {
        // The OnePlus the app is used on: 360dp wide at font scale 1.15.
        card(1.15f)
        assertEquals(top("quran_verse_listen"), top("quran_verse_share"))
    }

    @Test
    fun aVerseInTheNotebook_saysSoAboveItsActions() {
        card(1.15f)
        assertTrue(top("quran_verse_fix") < top("quran_verse_point"))
    }

    @Test
    fun largeText_putsTheActionsInTwoRowsOfThree() {
        card(2f)
        assertEquals(top("quran_verse_listen"), top("quran_verse_point"))
        assertEquals(top("quran_verse_bookmark"), top("quran_verse_share"))
        assertTrue(top("quran_verse_bookmark") > top("quran_verse_listen"))
    }
}
